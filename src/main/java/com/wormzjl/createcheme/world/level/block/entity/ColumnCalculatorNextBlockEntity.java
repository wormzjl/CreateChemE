package com.wormzjl.createcheme.world.level.block.entity;

import com.wormzjl.createcheme.CreateChemE;
import com.wormzjl.createcheme.registry.ModBlockEntities;
import com.wormzjl.createcheme.runtime.BoundedCpuSolveService;
import com.wormzjl.createcheme.runtime.ProcessSolveServices;
import com.wormzjl.createcheme.science.column.nextgen.ColumnNextInput;
import com.wormzjl.createcheme.science.column.nextgen.ColumnNextValidation;
import com.wormzjl.createcheme.science.column.nextgen.ColumnProblem;
import com.wormzjl.createcheme.science.column.nextgen.DryColumnOutcome;
import com.wormzjl.createcheme.science.column.nextgen.NextWarmState;
import com.wormzjl.createcheme.science.column.nextgen.NextColumnResultView;
import com.wormzjl.createcheme.world.inventory.ColumnCalculatorNextMenu;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
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

/** Server-thread-confined accepted state for the experimental calculator. */
public final class ColumnCalculatorNextBlockEntity extends BlockEntity implements MenuProvider {
    private static final int NBT_SCHEMA_VERSION = 1;
    private static final long MINIMUM_REQUEST_INTERVAL_TICKS = 5L;
    private static final int MAX_DIAGNOSTICS = 32;
    private static final int MAX_DIAGNOSTIC_LENGTH = 256;

    private NextStatus status = NextStatus.IDLE;
    private long inputRevision;
    private long resultRevision = -1L;
    private long nextAllowedRequestGameTime;
    private ColumnNextInput acceptedInput;
    private DryColumnOutcome.Success lastAcceptedOutcome;
    private NextColumnResultView acceptedResultView;
    /** In-memory only: never written to NBT and never shared with another calculator position. */
    private NextWarmState warmStart;
    private Operation activeOperation;
    private List<String> diagnostics = List.of();

    public ColumnCalculatorNextBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.COLUMN_CALCULATOR_NEXT.get(), pos, state);
    }

    public Optional<Operation> tryBegin(long gameTime, long operationId, ColumnNextInput input) {
        ColumnNextValidation.Result validation = ColumnNextValidation.validate(input);
        if (!validation.isValid()) {
            throw new IllegalArgumentException("Next input must validate before being accepted");
        }
        if (operationId <= 0L) {
            throw new IllegalArgumentException("Operation identity must be positive");
        }
        if (activeOperation != null || gameTime < nextAllowedRequestGameTime) {
            return Optional.empty();
        }
        inputRevision = Math.incrementExact(inputRevision);
        Operation operation = new Operation(operationId, inputRevision, input);
        acceptedInput = input;
        activeOperation = operation;
        status = NextStatus.CALCULATING;
        diagnostics = boundedDiagnostics(
                validation.diagnostics().stream().map(ColumnNextValidation.Diagnostic::code).toList());
        nextAllowedRequestGameTime = gameTime + MINIMUM_REQUEST_INTERVAL_TICKS;
        setChanged();
        return Optional.of(operation);
    }

    /** Phase-1 terminal path: a scientific request is acknowledged but never diverted into legacy science. */
    public boolean markSolverUnavailable(Operation operation) {
        return failOperation(operation, List.of("SOLVER_NOT_YET_AVAILABLE"));
    }

    /** Returns the exact operation only while it is still active on this server-thread-confined block. */
    public Optional<Operation> activeOperation(long operationId, long revision) {
        return activeOperation != null
                && activeOperation.operationId() == operationId
                && activeOperation.inputRevision() == revision
                ? Optional.of(activeOperation)
                : Optional.empty();
    }

    public boolean beginCancelling(long operationId, long revision) {
        if (activeOperation(operationId, revision).isEmpty()) {
            return false;
        }
        status = NextStatus.CANCELLING;
        setChanged();
        return true;
    }

    /** Commits only an exact, already-accepted dry solver result; typed failures never become a nominal success. */
    public boolean commitDryOutcome(
            Operation operation, DryColumnOutcome.Success outcome, NextColumnResultView resultView) {
        return commitOutcome(operation, outcome, resultView, outcome.diagnostics().events());
    }

    /** Exact-cache hits retain the normal operation guard but expose their non-worker initialization mode. */
    public boolean commitExactOutcome(
            Operation operation, DryColumnOutcome.Success outcome, NextColumnResultView resultView) {
        List<String> cacheDiagnostics = new java.util.ArrayList<>();
        cacheDiagnostics.add("EXACT_CACHE");
        cacheDiagnostics.addAll(outcome.diagnostics().events());
        return commitOutcome(operation, outcome, resultView, cacheDiagnostics);
    }

    private boolean commitOutcome(
            Operation operation, DryColumnOutcome.Success outcome, NextColumnResultView resultView,
            List<String> outcomeDiagnostics) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(resultView, "resultView");
        if (!Objects.equals(activeOperation, operation)) {
            return false;
        }
        acceptedInput = operation.input();
        lastAcceptedOutcome = outcome;
        acceptedResultView = resultView;
        resultRevision = operation.inputRevision();
        activeOperation = null;
        status = NextStatus.SUCCESS;
        diagnostics = boundedDiagnostics(outcomeDiagnostics);
        setChanged();
        return true;
    }

    /** Records a typed dry-solver failure while preserving any prior accepted outcome as stale presentation data. */
    public boolean failDryOutcome(Operation operation, DryColumnOutcome.Failure outcome) {
        Objects.requireNonNull(outcome, "outcome");
        return failOperation(operation, List.of(outcome.code().name(), outcome.summary()));
    }

    /** Marks only the matching operation terminally failed; stale worker completions cannot mutate this block. */
    public boolean failOperation(Operation operation, List<String> failureDiagnostics) {
        Objects.requireNonNull(operation, "operation");
        if (!Objects.equals(activeOperation, operation)) {
            return false;
        }
        activeOperation = null;
        status = NextStatus.FAILED;
        diagnostics = boundedDiagnostics(failureDiagnostics);
        setChanged();
        return true;
    }

    public boolean finishCancelled(Operation operation) {
        if (!Objects.equals(activeOperation, operation)) {
            return false;
        }
        activeOperation = null;
        status = NextStatus.STALE;
        diagnostics = List.of("CANCELLED");
        setChanged();
        return true;
    }

    public NextState state(long clientNonce) {
        return new NextState(
                clientNonce,
                activeOperation == null ? 0L : activeOperation.operationId(),
                inputRevision,
                resultRevision,
                status,
                Optional.ofNullable(acceptedInput),
                Optional.ofNullable(acceptedResultView),
                diagnostics);
    }

    /** The last fully accepted typed outcome remains available while a newer request is active or fails. */
    public Optional<DryColumnOutcome.Success> lastAcceptedOutcome() {
        return Optional.ofNullable(lastAcceptedOutcome);
    }

    /** Returns only a structurally compatible block-local seed; cache hits and persisted data never reach here. */
    public Optional<NextWarmState> warmStartFor(ColumnProblem problem) {
        Objects.requireNonNull(problem, "problem");
        return warmStart != null && warmStart.isCompatibleWith(problem) ? Optional.of(warmStart) : Optional.empty();
    }

    /** Called only after this exact block operation has committed a successful worker result. */
    public void installCommittedWarmStart(NextWarmState value) {
        warmStart = Objects.requireNonNull(value, "value");
    }

    @Override
    public void onChunkUnloaded() {
        cancelActiveOperation();
    }

    @Override
    public void setRemoved() {
        cancelActiveOperation();
        super.setRemoved();
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.createcheme.column_calculator_next");
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        if (level == null) {
            return null;
        }
        return new ColumnCalculatorNextMenu(
                containerId, inventory, ContainerLevelAccess.create(level, worldPosition), worldPosition);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("NextSchema", NBT_SCHEMA_VERSION);
        tag.putString("Status", status.serializedName());
        tag.putLong("InputRevision", inputRevision);
        tag.putLong("ResultRevision", resultRevision);
        if (acceptedInput != null) {
            tag.put("AcceptedInput", writeInput(acceptedInput));
        }
        if (acceptedResultView != null) {
            tag.put("AcceptedResult", writeResultView(acceptedResultView));
        }
        tag.putString("Diagnostics", String.join("\n", diagnostics));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        inputRevision = Math.max(0L, tag.getLong("InputRevision"));
        resultRevision = tag.contains("ResultRevision", Tag.TAG_LONG) ? tag.getLong("ResultRevision") : -1L;
        acceptedInput = tag.contains("AcceptedInput", Tag.TAG_COMPOUND)
                ? readInput(tag.getCompound("AcceptedInput")).orElse(null) : null;
        acceptedResultView = tag.contains("AcceptedResult", Tag.TAG_COMPOUND)
                ? readResultView(tag.getCompound("AcceptedResult")).orElse(null) : null;
        lastAcceptedOutcome = null;
        diagnostics = List.of("RECALCULATION_REQUIRED");
        activeOperation = null;
        nextAllowedRequestGameTime = 0L;
        status = acceptedInput == null ? NextStatus.IDLE
                : acceptedResultView == null ? NextStatus.DIRTY : NextStatus.STALE;
        warmStart = null;
    }

    private static CompoundTag writeInput(ColumnNextInput input) {
        if (ColumnNextValidation.estimatedWireBytes(input) > ColumnNextInput.MAX_PACKET_BYTES) {
            throw new IllegalArgumentException("Refusing to persist oversized next input");
        }
        CompoundTag tag = new CompoundTag();
        tag.putInt("Schema", input.schemaVersion());
        tag.putString("Package", input.packageId());
        tag.putString("Assay", input.assayId());
        tag.putDouble("FeedFlow", input.crudeFeed().molarFlowMolPerSecond());
        tag.putDouble("FeedTemperature", input.crudeFeed().temperatureKelvin());
        tag.putInt("Stages", input.stageCount());
        tag.putInt("FeedStage", input.crudeFeedStageNumber());
        tag.putDouble("TopPressure", input.topPressurePascal());
        tag.putDouble("StageDrop", input.stagePressureDropPascal());
        tag.putDouble("CondenserTemperature", input.condenserOutletTemperatureKelvin());
        tag.putDouble("ReboilerDuty", input.reboilerDutyWatts());
        tag.putDouble("Reflux", input.organicRefluxRatio());
        ListTag sideDraws = new ListTag();
        for (ColumnNextInput.SideDrawInput side : input.sideDraws()) {
            CompoundTag sideTag = new CompoundTag();
            sideTag.putInt("Stage", side.stageNumber());
            sideTag.putString("Basis", side.basis().serializedName());
            sideTag.putDouble("Rate", side.authoredRate());
            sideDraws.add(sideTag);
        }
        tag.put("SideDraws", sideDraws);
        ListTag utilities = new ListTag();
        for (ColumnNextInput.WaterSteamFeedInput utility : input.utilityFeeds()) {
            CompoundTag utilityTag = new CompoundTag();
            utilityTag.putString("Mode", utility.mode().serializedName());
            utilityTag.putInt("Stage", utility.stageNumber());
            utilityTag.putDouble("Flow", utility.molarFlowMolPerSecond());
            utilityTag.putDouble("Temperature", utility.temperatureKelvin());
            utilityTag.putDouble("Pressure", utility.upstreamPressurePascal());
            utilities.add(utilityTag);
        }
        tag.put("Utilities", utilities);
        return tag;
    }

    private static Optional<ColumnNextInput> readInput(CompoundTag tag) {
        try {
            List<ColumnNextInput.SideDrawInput> sides = new java.util.ArrayList<>();
            ListTag sideTags = tag.getList("SideDraws", Tag.TAG_COMPOUND);
            if (sideTags.size() > ColumnNextInput.MAX_SIDE_DRAWS) return Optional.empty();
            for (int index = 0; index < sideTags.size(); index++) {
                CompoundTag side = sideTags.getCompound(index);
                sides.add(new ColumnNextInput.SideDrawInput(
                        side.getInt("Stage"),
                        ColumnNextInput.AuthoredBasis.fromSerializedName(side.getString("Basis")),
                        side.getDouble("Rate")));
            }
            List<ColumnNextInput.WaterSteamFeedInput> utilities = new java.util.ArrayList<>();
            ListTag utilityTags = tag.getList("Utilities", Tag.TAG_COMPOUND);
            if (utilityTags.size() > ColumnNextInput.MAX_UTILITY_FEEDS) return Optional.empty();
            for (int index = 0; index < utilityTags.size(); index++) {
                CompoundTag utility = utilityTags.getCompound(index);
                utilities.add(new ColumnNextInput.WaterSteamFeedInput(
                        ColumnNextInput.UtilityFeedMode.fromSerializedName(utility.getString("Mode")),
                        utility.getInt("Stage"), utility.getDouble("Flow"), utility.getDouble("Temperature"),
                        utility.getDouble("Pressure")));
            }
            ColumnNextInput input = new ColumnNextInput(
                    tag.getInt("Schema"), tag.getString("Package"), tag.getString("Assay"),
                    new ColumnNextInput.CrudeFeedInput(tag.getDouble("FeedFlow"), tag.getDouble("FeedTemperature")),
                    tag.getInt("Stages"), tag.getInt("FeedStage"), tag.getDouble("TopPressure"),
                    tag.getDouble("StageDrop"), tag.getDouble("CondenserTemperature"), tag.getDouble("ReboilerDuty"),
                    tag.getDouble("Reflux"), sides, utilities);
            return ColumnNextValidation.validate(input).isValid() ? Optional.of(input) : Optional.empty();
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    private static CompoundTag writeResultView(NextColumnResultView view) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Solver", view.solverRevision());
        tag.putString("Dataset", view.datasetRevision());
        tag.putString("Assumptions", view.assumptionsRevision());
        tag.putString("InputDigest", view.inputDigest());
        tag.putString("ResultDigest", view.resultDigest());
        tag.putString("Initialization", view.initializationMode());
        tag.putDouble("CondenserDuty", view.condenserDutyWatts());
        ListTag axis = new ListTag();
        for (String component : view.componentAxis()) axis.add(StringTag.valueOf(component));
        tag.put("Axis", axis);
        ListTag streams = new ListTag();
        for (NextColumnResultView.Stream stream : view.streams()) {
            CompoundTag streamTag = new CompoundTag();
            streamTag.putString("Id", stream.id());
            streamTag.putString("Label", stream.label());
            streamTag.putString("Role", stream.role().name());
            streamTag.putInt("Stage", stream.connectedStage());
            streamTag.putString("Phase", stream.phase());
            streamTag.putDouble("Temperature", stream.temperatureKelvin());
            streamTag.putDouble("Pressure", stream.pressurePascal());
            double[] flows = stream.componentMolarFlows();
            for (int component = 0; component < NextColumnResultView.COMPONENT_COUNT; component++) {
                streamTag.putDouble("C" + component, flows[component]);
            }
            streams.add(streamTag);
        }
        tag.put("Streams", streams);
        tag.putString("ResultDiagnostics", String.join("\n", view.diagnostics()));
        return tag;
    }

    private static Optional<NextColumnResultView> readResultView(CompoundTag tag) {
        try {
            ListTag axisTag = tag.getList("Axis", Tag.TAG_STRING);
            if (axisTag.size() != NextColumnResultView.COMPONENT_COUNT) return Optional.empty();
            List<String> axis = new java.util.ArrayList<>(axisTag.size());
            for (int index = 0; index < axisTag.size(); index++) axis.add(axisTag.getString(index));
            ListTag streamTags = tag.getList("Streams", Tag.TAG_COMPOUND);
            if (streamTags.isEmpty() || streamTags.size() > NextColumnResultView.MAX_STREAMS) return Optional.empty();
            List<NextColumnResultView.Stream> streams = new java.util.ArrayList<>(streamTags.size());
            for (int index = 0; index < streamTags.size(); index++) {
                CompoundTag streamTag = streamTags.getCompound(index);
                double[] flows = new double[NextColumnResultView.COMPONENT_COUNT];
                for (int component = 0; component < flows.length; component++) flows[component] = streamTag.getDouble("C" + component);
                streams.add(new NextColumnResultView.Stream(streamTag.getString("Id"), streamTag.getString("Label"),
                        NextColumnResultView.Role.valueOf(streamTag.getString("Role")), streamTag.getInt("Stage"),
                        streamTag.getString("Phase"), streamTag.getDouble("Temperature"),
                        streamTag.getDouble("Pressure"), flows));
            }
            String diagnostics = tag.getString("ResultDiagnostics");
            List<String> messages = diagnostics.isBlank() ? List.of() : List.of(diagnostics.split("\\n", -1));
            return Optional.of(new NextColumnResultView(tag.getString("Solver"), tag.getString("Dataset"),
                    tag.getString("Assumptions"), tag.getString("InputDigest"), tag.getString("ResultDigest"),
                    tag.getString("Initialization"), tag.getDouble("CondenserDuty"), axis, streams, messages));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    private void cancelActiveOperation() {
        Operation operation = activeOperation;
        if (operation == null) {
            return;
        }
        if (level instanceof ServerLevel serverLevel) {
            try {
                BoundedCpuSolveService.CancellationResult cancellation = ProcessSolveServices.cancelNextColumn(
                        serverLevel.getServer(),
                        new ProcessSolveServices.ColumnTarget(serverLevel.dimension(), worldPosition),
                        operation,
                        BoundedCpuSolveService.CancelReason.OWNER_UNLOADED);
                if (CreateChemE.calculationLoggingEnabled()) {
                    CreateChemE.LOGGER.info(
                            "column_next_cancel column={} operation={} revision={} reason=OWNER_UNLOADED result={}",
                            opaqueColumnId(serverLevel, worldPosition),
                            operation.operationId(),
                            operation.inputRevision(),
                            cancellation);
                }
            } catch (RuntimeException exception) {
                CreateChemE.LOGGER.error(
                        "column_next_cancel column={} operation={} revision={} reason=OWNER_UNLOADED result=ERROR",
                        opaqueColumnId(serverLevel, worldPosition),
                        operation.operationId(),
                        operation.inputRevision(),
                        exception);
            }
        }
        activeOperation = null;
        if (status == NextStatus.CALCULATING || status == NextStatus.CANCELLING) {
            status = NextStatus.DIRTY;
            diagnostics = List.of("RECALCULATION_REQUIRED");
            setChanged();
        }
    }

    private static List<String> boundedDiagnostics(List<String> values) {
        Objects.requireNonNull(values, "values");
        return values.stream()
                .filter(Objects::nonNull)
                .map(value -> value.length() <= MAX_DIAGNOSTIC_LENGTH
                        ? value
                        : value.substring(0, MAX_DIAGNOSTIC_LENGTH))
                .limit(MAX_DIAGNOSTICS)
                .toList();
    }

    private static long opaqueColumnId(ServerLevel level, BlockPos pos) {
        long dimensionHash = Integer.toUnsignedLong(level.dimension().location().hashCode());
        long value = pos.asLong() ^ Long.rotateLeft(dimensionHash, 29);
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdl;
        value ^= value >>> 33;
        return value & Long.MAX_VALUE;
    }

    public record Operation(long operationId, long inputRevision, ColumnNextInput input) {
        public Operation {
            if (operationId <= 0L || inputRevision <= 0L) {
                throw new IllegalArgumentException("Operation identity must be positive");
            }
            Objects.requireNonNull(input, "input");
        }
    }

    public record NextState(
            long clientNonce, long operationId, long inputRevision, long resultRevision, NextStatus status,
            Optional<ColumnNextInput> acceptedInput, Optional<NextColumnResultView> acceptedResult,
            List<String> diagnostics) {
        public NextState {
            Objects.requireNonNull(status, "status");
            acceptedInput = Objects.requireNonNull(acceptedInput, "acceptedInput");
            acceptedResult = Objects.requireNonNull(acceptedResult, "acceptedResult");
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public enum NextStatus {
        IDLE("idle"), DIRTY("dirty"), CALCULATING("calculating"), CANCELLING("cancelling"),
        SUCCESS("success"), FAILED("failed"), STALE("stale");

        private final String serializedName;

        NextStatus(String serializedName) {
            this.serializedName = serializedName;
        }

        public String serializedName() {
            return serializedName;
        }
    }
}
