package com.wormzjl.createcheme.world.level.block.entity;

import com.wormzjl.createcheme.CreateChemE;
import com.wormzjl.createcheme.registry.ModBlockEntities;
import com.wormzjl.createcheme.runtime.BoundedCpuSolveService;
import com.wormzjl.createcheme.runtime.ProcessSolveServices;
import com.wormzjl.createcheme.science.column.ColumnSimulation;
import com.wormzjl.createcheme.science.column.ColumnSimulation.ColumnInput;
import com.wormzjl.createcheme.science.column.ColumnSimulation.ColumnResult;
import com.wormzjl.createcheme.world.inventory.ColumnCalculatorMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server-thread-confined state for the first calculator block.
 *
 * <p>Accepted inputs, active tickets, and successful results are immutable values. The current legacy NBT form still
 * persists only revisions and a result digest; complete scientific NBT persistence is the next focused slice.</p>
 */
public final class ColumnCalculatorBlockEntity extends BlockEntity implements MenuProvider {
    private static final String TAG_STATUS = "Status";
    private static final String TAG_INPUT_REVISION = "InputRevision";
    private static final String TAG_RESULT_REVISION = "ResultRevision";
    private static final String TAG_RESULT_SUMMARY = "ResultSummary";
    private static final int MAX_SUMMARY_LENGTH = 256;
    private static final int MAX_DIGEST_LENGTH = 128;
    private static final int MAX_IDENTIFIER_LENGTH = 128;
    private static final int MAX_PRODUCTS = 8;
    private static final int MAX_COMPONENTS = 16;
    private static final int MAX_STAGES = 64;
    private static final double FRACTION_TOLERANCE = 1.0e-5;
    private static final long MINIMUM_REQUEST_INTERVAL_TICKS = 5L;
    private static final AtomicLong TICKET_SEQUENCE = new AtomicLong();

    private CalculatorStatus status = CalculatorStatus.IDLE;
    private long inputRevision;
    private long resultRevision = -1L;
    private String resultSummary = "";
    private long nextAllowedRequestGameTime;
    private ColumnInput acceptedInput;
    private ColumnResult lastResult;
    private CalculationTicket activeTicket;

    public ColumnCalculatorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.COLUMN_CALCULATOR.get(), pos, state);
    }

    public CalculatorStatus status() {
        return status;
    }

    public long inputRevision() {
        return inputRevision;
    }

    public long resultRevision() {
        return resultRevision;
    }

    public String resultSummary() {
        return resultSummary;
    }

    /**
     * Starts one calculation for a previously validated immutable input.
     *
     * <p>This method is confined to the logical server thread. An empty result means this block is already
     * calculating or remains inside its short request throttle.</p>
     *
     * @throws NullPointerException if {@code validatedInput} is null
     * @throws IllegalArgumentException if the scientific input is invalid
     */
    public Optional<CalculationTicket> tryBeginCalculation(long gameTime, ColumnInput validatedInput) {
        requireValidInput(validatedInput);
        if (activeTicket != null
                || status == CalculatorStatus.CALCULATING
                || gameTime < nextAllowedRequestGameTime) {
            return Optional.empty();
        }
        long nextRevision = Math.incrementExact(inputRevision);
        CalculationTicket ticket = new CalculationTicket(
                TICKET_SEQUENCE.incrementAndGet(), nextRevision, validatedInput);
        inputRevision = nextRevision;
        acceptedInput = validatedInput;
        activeTicket = ticket;
        status = CalculatorStatus.CALCULATING;
        nextAllowedRequestGameTime = gameTime + MINIMUM_REQUEST_INTERVAL_TICKS;
        setChanged();
        return Optional.of(ticket);
    }

    /** Marks only the matching active ticket as failed; stale completions leave current state unchanged. */
    public boolean failCalculation(CalculationTicket ticket) {
        Objects.requireNonNull(ticket, "ticket");
        if (!ticket.equals(activeTicket)) {
            return false;
        }
        activeTicket = null;
        status = CalculatorStatus.FAILED;
        setChanged();
        return true;
    }

    /**
     * Atomically commits the complete result only when {@code ticket} is still active.
     * Invalid results fail before any block state is changed.
     */
    public boolean commitCalculation(CalculationTicket ticket, ColumnResult result) {
        Objects.requireNonNull(ticket, "ticket");
        if (!ticket.equals(activeTicket)) {
            return false;
        }
        requireValidResult(result);
        String expectedDataset = "dummy:" + ticket.input().assayId() + "@v1";
        if (!expectedDataset.equals(result.datasetRevision())) {
            throw new IllegalArgumentException("Result dataset does not match the active calculation ticket");
        }
        acceptedInput = ticket.input();
        lastResult = result;
        resultRevision = ticket.inputRevision();
        resultSummary = truncate(result.resultDigest(), MAX_SUMMARY_LENGTH);
        activeTicket = null;
        status = CalculatorStatus.SUCCESS;
        setChanged();
        return true;
    }

    /** Returns the last accepted immutable input, if any. */
    public Optional<ColumnInput> acceptedInput() {
        return Optional.ofNullable(acceptedInput);
    }

    /** Returns the last successfully committed immutable result, if any. */
    public Optional<ColumnResult> lastResult() {
        return Optional.ofNullable(lastResult);
    }

    @Override
    public void onChunkUnloaded() {
        cancelActiveCalculation();
    }

    @Override
    public void setRemoved() {
        cancelActiveCalculation();
        super.setRemoved();
    }

    private void cancelActiveCalculation() {
        CalculationTicket ticket = activeTicket;
        if (ticket == null) {
            return;
        }
        if (level instanceof ServerLevel serverLevel) {
            try {
                BoundedCpuSolveService.CancellationResult cancellation =
                        ProcessSolveServices.cancelColumn(
                                serverLevel.getServer(),
                                new ProcessSolveServices.ColumnTarget(serverLevel.dimension(), worldPosition),
                                ticket,
                                BoundedCpuSolveService.CancelReason.OWNER_UNLOADED);
                if (CreateChemE.calculationLoggingEnabled()) {
                    CreateChemE.LOGGER.info(
                            "column_calc_cancel column={} ticket={} revision={} reason=OWNER_UNLOADED result={}",
                            opaqueColumnId(serverLevel, worldPosition),
                            ticket.token(),
                            ticket.inputRevision(),
                            cancellation);
                }
            } catch (RuntimeException exception) {
                CreateChemE.LOGGER.error(
                        "column_calc_cancel column={} ticket={} revision={} reason=OWNER_UNLOADED result=ERROR",
                        opaqueColumnId(serverLevel, worldPosition),
                        ticket.token(),
                        ticket.inputRevision(),
                        exception);
            }
        }
        activeTicket = null;
        if (status == CalculatorStatus.CALCULATING) {
            status = CalculatorStatus.DIRTY;
            setChanged();
        }
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.createcheme.column_calculator");
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        if (level == null) {
            return null;
        }

        return new ColumnCalculatorMenu(
                containerId,
                inventory,
                ContainerLevelAccess.create(level, worldPosition),
                worldPosition
        );
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString(TAG_STATUS, status.serializedName());
        tag.putLong(TAG_INPUT_REVISION, inputRevision);
        tag.putLong(TAG_RESULT_REVISION, resultRevision);
        tag.putString(TAG_RESULT_SUMMARY, resultSummary);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        status = tag.contains(TAG_STATUS, Tag.TAG_STRING)
                ? CalculatorStatus.fromSerializedName(tag.getString(TAG_STATUS))
                : CalculatorStatus.IDLE;
        inputRevision = Math.max(0L, tag.getLong(TAG_INPUT_REVISION));
        resultRevision = tag.contains(TAG_RESULT_REVISION, Tag.TAG_LONG)
                ? tag.getLong(TAG_RESULT_REVISION)
                : -1L;
        resultSummary = truncate(tag.getString(TAG_RESULT_SUMMARY), MAX_SUMMARY_LENGTH);
        acceptedInput = null;
        lastResult = null;
        activeTicket = null;
        nextAllowedRequestGameTime = 0L;

        if (status == CalculatorStatus.CALCULATING || status == CalculatorStatus.SUCCESS) {
            status = CalculatorStatus.DIRTY;
        }
    }

    private static String truncate(String value, int maximumLength) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
    }

    private static void requireValidInput(ColumnInput input) {
        Objects.requireNonNull(input, "input");
        if (!ColumnSimulation.validate(input).isValid()) {
            throw new IllegalArgumentException("Column input must pass scientific validation");
        }
    }

    private static void requireValidResult(ColumnResult result) {
        Objects.requireNonNull(result, "result");
        if (result.schemaVersion() != ColumnSimulation.INPUT_SCHEMA_VERSION
                || !Double.isFinite(result.condenserDutyWatts())) {
            throw new IllegalArgumentException("Result schema or condenser duty is invalid");
        }
        requireBoundedNonblank(result.solverRevision(), MAX_IDENTIFIER_LENGTH, "result.solverRevision");
        requireBoundedNonblank(result.datasetRevision(), MAX_IDENTIFIER_LENGTH, "result.datasetRevision");
        requireBoundedNonblank(result.assumptionsRevision(), MAX_IDENTIFIER_LENGTH, "result.assumptionsRevision");
        requireBoundedNonblank(result.inputDigest(), MAX_DIGEST_LENGTH, "result.inputDigest");
        requireBoundedNonblank(result.resultDigest(), MAX_DIGEST_LENGTH, "result.resultDigest");
        if (result.products().isEmpty() || result.products().size() > MAX_PRODUCTS) {
            throw new IllegalArgumentException("Result product count is outside persistence bounds");
        }
        if (result.stages().size() < 2 || result.stages().size() > MAX_STAGES) {
            throw new IllegalArgumentException("Result stage count is outside persistence bounds");
        }
        List<String> componentAxis = null;
        for (var product : result.products()) {
            if (product.composition().isEmpty() || product.composition().size() > MAX_COMPONENTS) {
                throw new IllegalArgumentException("Result composition count is outside persistence bounds");
            }
            requireBoundedNonblank(product.streamId(), MAX_IDENTIFIER_LENGTH, "product.streamId");
            if (!finiteNonnegative(product.molarFlowMolPerSecond())
                    || !finiteNonnegative(product.massFlowKilogramPerSecond())
                    || !finitePositive(product.temperatureKelvin())
                    || !finitePositive(product.pressurePascal())
                    || !Double.isFinite(product.boilingRange().t5Kelvin())
                    || !Double.isFinite(product.boilingRange().t50Kelvin())
                    || !Double.isFinite(product.boilingRange().t95Kelvin())
                    || product.boilingRange().t5Kelvin() > product.boilingRange().t50Kelvin()
                    || product.boilingRange().t50Kelvin() > product.boilingRange().t95Kelvin()) {
                throw new IllegalArgumentException("Result product state is invalid");
            }
            List<String> currentAxis = product.composition().stream()
                    .map(component -> component.componentId() + '\u0000' + component.boilingRangeLabel())
                    .toList();
            if (componentAxis != null && !componentAxis.equals(currentAxis)) {
                throw new IllegalArgumentException("Result product component axes do not match");
            }
            componentAxis = currentAxis;
            double moleSum = 0.0;
            double massSum = 0.0;
            for (var component : product.composition()) {
                requireBoundedNonblank(component.componentId(), MAX_IDENTIFIER_LENGTH, "component.componentId");
                requireBoundedNonblank(
                        component.boilingRangeLabel(), MAX_IDENTIFIER_LENGTH, "component.boilingRangeLabel");
                if (!finiteFraction(component.moleFraction()) || !finiteFraction(component.massFraction())) {
                    throw new IllegalArgumentException("Result composition fraction is invalid");
                }
                moleSum += component.moleFraction();
                massSum += component.massFraction();
            }
            if (Math.abs(moleSum - 1.0) > FRACTION_TOLERANCE
                    || Math.abs(massSum - 1.0) > FRACTION_TOLERANCE) {
                throw new IllegalArgumentException("Result composition is not normalized");
            }
        }
        int expectedStage = 1;
        for (var stage : result.stages()) {
            if (stage.stage() != expectedStage++
                    || !finitePositive(stage.temperatureKelvin())
                    || !finiteNonnegative(stage.liquidMolarFlowMolPerSecond())
                    || !finiteNonnegative(stage.vaporMolarFlowMolPerSecond())) {
                throw new IllegalArgumentException("Result stage profile is invalid");
            }
        }
        var diagnostics = result.diagnostics();
        if (diagnostics.iterations() < 0
                || diagnostics.propertyEvaluations() < 0
                || diagnostics.messages().size() > 32
                || !saneResidual(diagnostics.residuals().maximumComponentMaterialResidual())
                || !saneResidual(diagnostics.residuals().overallMaterialResidual())
                || !saneResidual(diagnostics.residuals().relativeEnergyResidual())
                || !saneResidual(diagnostics.residuals().maximumEquilibriumResidual())
                || !saneResidual(diagnostics.residuals().maximumCompositionSummationResidual())) {
            throw new IllegalArgumentException("Result diagnostics are invalid");
        }
    }

    private static boolean finitePositive(double value) {
        return Double.isFinite(value) && value > 0.0;
    }

    private static boolean finiteNonnegative(double value) {
        return Double.isFinite(value) && value >= 0.0;
    }

    private static boolean finiteFraction(double value) {
        return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
    }

    private static boolean saneResidual(OptionalDouble value) {
        return value.isEmpty() || finiteNonnegative(value.getAsDouble());
    }

    private static void requireBoundedNonblank(String value, int maximumLength, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(field + " is blank or exceeds " + maximumLength + " characters");
        }
    }

    /** Immutable, process-unique guard joining one accepted input to one asynchronous completion. */
    public record CalculationTicket(long token, long inputRevision, ColumnInput input) {
        public CalculationTicket {
            if (token <= 0L || inputRevision <= 0L) {
                throw new IllegalArgumentException("Calculation ticket identifiers must be positive");
            }
            requireValidInput(input);
        }
    }

    private static long opaqueColumnId(ServerLevel level, BlockPos pos) {
        long dimensionHash = Integer.toUnsignedLong(level.dimension().location().hashCode());
        long value = pos.asLong() ^ Long.rotateLeft(dimensionHash, 29);
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdl;
        value ^= value >>> 33;
        return value & Long.MAX_VALUE;
    }

    public enum CalculatorStatus {
        IDLE("idle"),
        DIRTY("dirty"),
        CALCULATING("calculating"),
        SUCCESS("success"),
        FAILED("failed"),
        STALE("stale");

        private final String serializedName;

        CalculatorStatus(String serializedName) {
            this.serializedName = serializedName;
        }

        public String serializedName() {
            return serializedName;
        }

        public static CalculatorStatus fromSerializedName(String name) {
            for (CalculatorStatus value : values()) {
                if (value.serializedName.equals(name)) {
                    return value;
                }
            }
            return IDLE;
        }
    }
}
