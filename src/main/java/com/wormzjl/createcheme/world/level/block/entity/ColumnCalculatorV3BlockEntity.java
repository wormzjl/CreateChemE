package com.wormzjl.createcheme.world.level.block.entity;

import com.wormzjl.createcheme.CreateChemE;
import com.wormzjl.createcheme.registry.ModBlockEntities;
import com.wormzjl.createcheme.science.column.v3.V3ColumnDisplayResult;
import com.wormzjl.createcheme.science.column.v3.V3ColumnInput;
import com.wormzjl.createcheme.science.column.v3.V3ColumnOutcome;
import com.wormzjl.createcheme.science.column.v3.V3ColumnSpecification;
import com.wormzjl.createcheme.science.column.v3.V3ColumnStreamProperties;
import com.wormzjl.createcheme.science.column.v3.V3ComponentBasis;
import com.wormzjl.createcheme.science.column.v3.V3ControlledQuantity;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.science.column.v3.thermo.V3CrudeFeed;
import com.wormzjl.createcheme.world.inventory.ColumnCalculatorV3Menu;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
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

/**
 * Server-thread-confined V3 calculator state.
 *
 * <p>Only the logical server mutates this object. An active operation contains an immutable input snapshot; workers
 * never retain this block entity, world, player, or menu, and a completion is committed only when its operation still
 * exactly matches.</p>
 */
public final class ColumnCalculatorV3BlockEntity extends BlockEntity implements MenuProvider {
    public static final int DATA_VERSION = 3;
    public static final String PILOT_PACKAGE = "createcheme:cdu17_tjl_acs2018";
    private static final int DEFAULT_STAGE_COUNT = 30;
    private static final int DEFAULT_FEED_STAGE = 24;

    private static final String TAG_DATA_VERSION = "V3DataVersion";
    private static final String TAG_INPUT_REVISION = "InputRevision";
    private static final String TAG_RESULT_REVISION = "ResultRevision";
    private static final String TAG_STATE_REVISION = "StateRevision";
    private static final String TAG_INPUT = "Input";
    private static final String TAG_RESULT = "Result";

    private V3Status status = V3Status.DIRTY;
    private long inputRevision;
    private long resultRevision = -1L;
    private long stateRevision;
    private String detail = "Pilot draft is ready";
    private V3ColumnInput currentInput = defaultInput();
    private V3ColumnDisplayResult displayResult;
    private V3Operation activeOperation;

    public ColumnCalculatorV3BlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.COLUMN_CALCULATOR_V3.get(), pos, state);
    }

    /**
     * Atomically freezes a validated server-resolved input for one worker operation.
     *
     * <p>Must run on the logical server thread. A stale editor revision, disabled rollout, or existing operation is
     * rejected without changing the block state.</p>
     */
    public Optional<V3Operation> tryBegin(long expectedInputRevision, long operationId, V3ColumnInput input) {
        if (!(level instanceof ServerLevel) || expectedInputRevision != inputRevision || activeOperation != null
                || CreateChemE.columnV3Rollout() == CreateChemE.V3Rollout.DISABLED) {
            return Optional.empty();
        }
        input = Objects.requireNonNull(input, "input");
        long nextInputRevision;
        try {
            nextInputRevision = Math.incrementExact(inputRevision);
            stateRevision = Math.incrementExact(stateRevision);
        } catch (ArithmeticException overflow) {
            status = V3Status.FAILED;
            detail = "V3 revision space is exhausted";
            setChanged();
            return Optional.empty();
        }
        currentInput = input;
        inputRevision = nextInputRevision;
        activeOperation = new V3Operation(operationId, nextInputRevision, input);
        status = V3Status.CALCULATING;
        detail = "Calculating server-validated V3 input";
        setChanged();
        return Optional.of(activeOperation);
    }

    /** Commits the terminal worker outcome only when it belongs to the exact current operation. */
    public boolean finishOperation(V3Operation operation, V3ColumnOutcome outcome, String terminalDetail) {
        if (!Objects.equals(activeOperation, operation)) return false;
        activeOperation = null;
        if (outcome instanceof V3ColumnOutcome.Success success) {
            displayResult = V3ColumnDisplayResult.fromAccepted(success);
            resultRevision = Math.incrementExact(resultRevision);
            status = V3Status.SUCCESS;
            detail = "Success: accepted residual " + success.diagnostics().maximumScaledResidual();
        } else if (outcome instanceof V3ColumnOutcome.Failure failure) {
            status = V3Status.FAILED;
            detail = bounded(failure.code().name() + ": " + failure.summary());
        } else {
            status = V3Status.FAILED;
            detail = bounded(terminalDetail);
        }
        stateRevision = Math.incrementExact(stateRevision);
        setChanged();
        return true;
    }

    /** Records a terminal service failure while preserving any prior accepted display certificate. */
    public boolean failOperation(V3Operation operation, String failureDetail) {
        if (!Objects.equals(activeOperation, operation)) return false;
        activeOperation = null;
        status = V3Status.FAILED;
        detail = bounded(failureDetail);
        stateRevision = Math.incrementExact(stateRevision);
        setChanged();
        return true;
    }

    /** Immutable view for a requester or broadcast viewer; it intentionally excludes workspaces and profiles. */
    public V3State state(long clientNonce) {
        return new V3State(
                clientNonce,
                stateRevision,
                activeOperation == null ? 0L : activeOperation.operationId(),
                inputRevision,
                resultRevision,
                status,
                currentInput,
                Optional.ofNullable(displayResult),
                List.of(detail));
    }

    public int statusCode() {
        return status.code();
    }

    public Optional<V3Operation> activeOperation(long operationId, long expectedInputRevision) {
        return activeOperation != null && activeOperation.operationId() == operationId
                && activeOperation.inputRevision() == expectedInputRevision
                ? Optional.of(activeOperation) : Optional.empty();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt(TAG_DATA_VERSION, DATA_VERSION);
        tag.putLong(TAG_INPUT_REVISION, inputRevision);
        tag.putLong(TAG_RESULT_REVISION, resultRevision);
        tag.putLong(TAG_STATE_REVISION, stateRevision);
        tag.put(TAG_INPUT, writeInput(currentInput));
        if (displayResult != null) tag.put(TAG_RESULT, writeDisplayResult(displayResult));
        // Active operation identity, queue state, timings, and any warm numerical state are intentionally not saved.
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        activeOperation = null;
        displayResult = null;
        inputRevision = 0L;
        resultRevision = -1L;
        stateRevision = 0L;
        currentInput = defaultInput();
        if (!tag.contains(TAG_DATA_VERSION, Tag.TAG_INT)) {
            status = V3Status.DIRTY;
            detail = "No V3 persisted state";
            return;
        }
        int dataVersion = tag.getInt(TAG_DATA_VERSION);
        if (dataVersion > DATA_VERSION) {
            status = V3Status.INCOMPATIBLE;
            detail = "Persisted V3 state requires a newer data version";
            return;
        }
        if (dataVersion < 1 || !tag.contains(TAG_INPUT, Tag.TAG_COMPOUND)) {
            status = V3Status.DIRTY;
            detail = "CORRUPT_PERSISTED_STATE";
            return;
        }
        try {
            currentInput = readInput(tag.getCompound(TAG_INPUT));
            inputRevision = nonNegative(tag.getLong(TAG_INPUT_REVISION));
            resultRevision = tag.contains(TAG_RESULT_REVISION, Tag.TAG_LONG)
                    ? tag.getLong(TAG_RESULT_REVISION) : -1L;
            if (resultRevision < -1L) throw new IllegalArgumentException("Invalid V3 result revision");
            stateRevision = nonNegative(tag.getLong(TAG_STATE_REVISION));
            if (dataVersion < DATA_VERSION && currentInput.equals(priorUnqualifiedDefaultInput())) {
                currentInput = defaultInput();
                displayResult = null;
                resultRevision = -1L;
                status = V3Status.DIRTY;
                detail = "Updated untouched V3 draft to the DWSIM-qualified 30-stage default";
                return;
            }
            if (tag.contains(TAG_RESULT, Tag.TAG_COMPOUND)) {
                displayResult = readDisplayResult(tag.getCompound(TAG_RESULT));
                if (resultRevision < 0L) throw new IllegalArgumentException("V3 result lacks revision");
                status = V3Status.STALE;
                detail = "Persisted V3 result is presentation-only; recalculate to refresh it";
            } else {
                status = V3Status.DIRTY;
                detail = "Persisted V3 input is ready to calculate";
            }
        } catch (IllegalArgumentException invalid) {
            currentInput = defaultInput();
            displayResult = null;
            inputRevision = 0L;
            resultRevision = -1L;
            stateRevision = 0L;
            status = V3Status.DIRTY;
            detail = "CORRUPT_PERSISTED_STATE";
        }
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.createcheme.column_calculator_v3");
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        if (level == null) return null;
        return new ColumnCalculatorV3Menu(
                containerId, inventory, ContainerLevelAccess.create(level, worldPosition), worldPosition);
    }

    /** Immutable operation identity retained only until its terminal completion is handled on the server thread. */
    public record V3Operation(long operationId, long inputRevision, V3ColumnInput input) {
        public V3Operation {
            if (operationId <= 0L || inputRevision <= 0L) {
                throw new IllegalArgumentException("V3 operation identity must be positive");
            }
            input = Objects.requireNonNull(input, "input");
        }
    }

    /** Bounded immutable state transmitted only after server-side validation. */
    public record V3State(
            long clientNonce,
            long stateRevision,
            long operationId,
            long inputRevision,
            long resultRevision,
            V3Status status,
            V3ColumnInput input,
            Optional<V3ColumnDisplayResult> displayResult,
            List<String> diagnostics) {
        public V3State {
            if (clientNonce < 0L || stateRevision < 0L || operationId < 0L || inputRevision < 0L
                    || resultRevision < -1L) {
                throw new IllegalArgumentException("V3 state revisions are invalid");
            }
            status = Objects.requireNonNull(status, "status");
            input = Objects.requireNonNull(input, "input");
            displayResult = Objects.requireNonNull(displayResult, "displayResult");
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
            if (diagnostics.size() > 32 || diagnostics.stream().anyMatch(value -> value == null || value.length() > 256)) {
                throw new IllegalArgumentException("V3 diagnostics exceed the bounded state contract");
            }
        }
    }

    /** Persisted status uses stable names; only current runtime states are ever sent over the wire. */
    public enum V3Status {
        IDLE("IDLE", 0),
        CALCULATING("CALCULATING", 1),
        SUCCESS("SUCCESS", 2),
        FAILED("FAILED", 3),
        STALE("STALE", 4),
        DIRTY("DIRTY", 5),
        INCOMPATIBLE("INCOMPATIBLE", 6);

        private final String serializedName;
        private final int code;

        V3Status(String serializedName, int code) {
            this.serializedName = serializedName;
            this.code = code;
        }

        public String serializedName() {
            return serializedName;
        }

        public int code() {
            return code;
        }

        public static V3Status fromSerializedName(String name) {
            for (V3Status status : values()) {
                if (status.serializedName.equals(name)) return status;
            }
            throw new IllegalArgumentException("Unknown V3 status");
        }
    }

    private static V3ColumnInput defaultInput() {
        return defaultInput(400.0, 2.0);
    }

    private static V3ColumnInput priorUnqualifiedDefaultInput() {
        return defaultInput(332.15, 4.17);
    }

    private static V3ColumnInput defaultInput(double condenserTemperatureKelvin, double refluxRatio) {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(PILOT_PACKAGE);
        V3CrudeFeed crude = thermo.crudeFeed("createcheme:tia_juana_light");
        double[] feedFlows = crude.moleFractions();
        double totalFlowMolPerSecond = 2_610.7 * 1_000.0 / 3_600.0;
        for (int component = 0; component < feedFlows.length; component++) {
            feedFlows[component] *= totalFlowMolPerSecond;
        }
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(),
                crude.componentBasis(), feedFlows, 365.0 + 273.15, DEFAULT_STAGE_COUNT, DEFAULT_FEED_STAGE,
                250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(condenserTemperatureKelvin),
                        new V3ColumnSpecification.OrganicRefluxRatio(refluxRatio),
                        new V3ColumnSpecification.ReboilerDuty(8_000_000.0)));
    }

    private static CompoundTag writeInput(V3ColumnInput input) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Schema", input.schemaVersion());
        tag.putString("Package", input.packageId());
        tag.putString("Assay", input.assayId());
        ListTag axis = new ListTag();
        for (String componentId : input.componentBasis().componentIds()) axis.add(StringTag.valueOf(componentId));
        tag.put("Axis", axis);
        ListTag feedFlows = new ListTag();
        for (double flow : input.feedComponentMolarFlowsMolPerSecond()) feedFlows.add(DoubleTag.valueOf(flow));
        tag.put("FeedFlows", feedFlows);
        tag.putDouble("FeedTemperature", input.feedTemperatureKelvin());
        tag.putInt("StageCount", input.stageCount());
        tag.putInt("FeedStage", input.feedStageNumber());
        tag.putDouble("TopPressure", input.topPressurePascal());
        tag.putDouble("PressureDrop", input.stagePressureDropPascal());
        ListTag specifications = new ListTag();
        for (V3ColumnSpecification specification : input.specifications()) {
            CompoundTag specificationTag = new CompoundTag();
            specificationTag.putString("Kind", specification.controlledQuantity().name());
            specificationTag.putDouble("Value", specificationValue(specification));
            specifications.add(specificationTag);
        }
        tag.put("Specifications", specifications);
        return tag;
    }

    private static V3ColumnInput readInput(CompoundTag tag) {
        if (tag.getInt("Schema") != V3ColumnInput.SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported V3 input schema");
        }
        ListTag axisTag = tag.getList("Axis", Tag.TAG_STRING);
        if (axisTag.isEmpty() || axisTag.size() > V3ComponentBasis.MAX_COMPONENTS) {
            throw new IllegalArgumentException("Invalid V3 component axis");
        }
        List<String> axis = new ArrayList<>(axisTag.size());
        for (int index = 0; index < axisTag.size(); index++) axis.add(axisTag.getString(index));
        ListTag specificationTags = tag.getList("Specifications", Tag.TAG_COMPOUND);
        if (specificationTags.size() != 3) throw new IllegalArgumentException("Invalid V3 specification count");
        List<V3ColumnSpecification> specifications = new ArrayList<>(specificationTags.size());
        for (int index = 0; index < specificationTags.size(); index++) {
            CompoundTag specification = specificationTags.getCompound(index);
            specifications.add(specification(V3ControlledQuantity.valueOf(specification.getString("Kind")),
                    specification.getDouble("Value")));
        }
        ListTag flowTags = tag.getList("FeedFlows", Tag.TAG_DOUBLE);
        if (flowTags.size() != axis.size()) throw new IllegalArgumentException("Invalid V3 feed-flow axis");
        double[] feedFlows = new double[flowTags.size()];
        for (int index = 0; index < feedFlows.length; index++) feedFlows[index] = flowTags.getDouble(index);
        return new V3ColumnInput(
                tag.getInt("Schema"), tag.getString("Package"), tag.getString("Assay"), new V3ComponentBasis(axis),
                feedFlows, tag.getDouble("FeedTemperature"), tag.getInt("StageCount"),
                tag.getInt("FeedStage"), tag.getDouble("TopPressure"), tag.getDouble("PressureDrop"), specifications);
    }

    private static CompoundTag writeDisplayResult(V3ColumnDisplayResult result) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Digest", result.inputDigest());
        tag.putString("Formulation", result.formulationRevision());
        tag.putString("Assumptions", result.assumptionsRevision());
        tag.putString("Dataset", result.datasetRevision());
        tag.putInt("NewtonIterations", result.newtonIterations());
        tag.putDouble("MaximumResidual", result.maximumScaledResidual());
        tag.putInt("AcceptanceChecks", result.acceptanceCheckCount());
        ListTag streams = new ListTag();
        for (V3ColumnStreamProperties stream : result.streams()) {
            CompoundTag streamTag = new CompoundTag();
            streamTag.putString("Id", stream.streamId());
            streamTag.putString("Name", stream.displayName());
            streamTag.putString("Phase", stream.phase());
            streamTag.putDouble("Flow", stream.molarFlowMolPerSecond());
            streamTag.putDouble("Temperature", stream.temperatureKelvin());
            streamTag.putDouble("Pressure", stream.pressurePascal());
            ListTag composition = new ListTag();
            for (V3ColumnStreamProperties.ComponentFraction fraction : stream.moleFractions()) {
                CompoundTag fractionTag = new CompoundTag();
                fractionTag.putString("Component", fraction.componentId());
                fractionTag.putDouble("Fraction", fraction.moleFraction());
                composition.add(fractionTag);
            }
            streamTag.put("Composition", composition);
            streams.add(streamTag);
        }
        tag.put("Streams", streams);
        return tag;
    }

    private static V3ColumnDisplayResult readDisplayResult(CompoundTag tag) {
        ListTag streamTags = tag.getList("Streams", Tag.TAG_COMPOUND);
        if (streamTags.size() > V3ColumnStreamProperties.MAX_STREAMS) {
            throw new IllegalArgumentException("Persisted V3 stream count exceeds the display contract");
        }
        List<V3ColumnStreamProperties> streams = new ArrayList<>(streamTags.size());
        for (int streamIndex = 0; streamIndex < streamTags.size(); streamIndex++) {
            CompoundTag streamTag = streamTags.getCompound(streamIndex);
            ListTag fractionTags = streamTag.getList("Composition", Tag.TAG_COMPOUND);
            if (fractionTags.isEmpty() || fractionTags.size() > V3ColumnStreamProperties.MAX_COMPONENTS) {
                throw new IllegalArgumentException("Persisted V3 stream composition exceeds the display contract");
            }
            List<V3ColumnStreamProperties.ComponentFraction> fractions = new ArrayList<>(fractionTags.size());
            for (int fractionIndex = 0; fractionIndex < fractionTags.size(); fractionIndex++) {
                CompoundTag fractionTag = fractionTags.getCompound(fractionIndex);
                fractions.add(new V3ColumnStreamProperties.ComponentFraction(
                        fractionTag.getString("Component"), fractionTag.getDouble("Fraction")));
            }
            streams.add(new V3ColumnStreamProperties(
                    streamTag.getString("Id"), streamTag.getString("Name"), streamTag.getString("Phase"),
                    streamTag.getDouble("Flow"), streamTag.getDouble("Temperature"), streamTag.getDouble("Pressure"),
                    fractions));
        }
        return new V3ColumnDisplayResult(
                tag.getString("Digest"), tag.getString("Formulation"), tag.getString("Assumptions"),
                tag.getString("Dataset"), tag.getInt("NewtonIterations"), tag.getDouble("MaximumResidual"),
                tag.getInt("AcceptanceChecks"), streams);
    }

    private static V3ColumnSpecification specification(V3ControlledQuantity quantity, double value) {
        return switch (quantity) {
            case CONDENSER_OUTLET_TEMPERATURE -> new V3ColumnSpecification.CondenserOutletTemperature(value);
            case ORGANIC_REFLUX_RATIO -> new V3ColumnSpecification.OrganicRefluxRatio(value);
            case REBOILER_DUTY -> new V3ColumnSpecification.ReboilerDuty(value);
        };
    }

    private static double specificationValue(V3ColumnSpecification specification) {
        return switch (specification) {
            case V3ColumnSpecification.CondenserOutletTemperature temperature -> temperature.kelvin();
            case V3ColumnSpecification.OrganicRefluxRatio reflux -> reflux.ratio();
            case V3ColumnSpecification.ReboilerDuty duty -> duty.watts();
        };
    }

    private static long nonNegative(long value) {
        if (value < 0L) throw new IllegalArgumentException("V3 persisted revision is negative");
        return value;
    }

    private static String bounded(String value) {
        if (value == null || value.isBlank()) return "V3 operation did not provide a detail";
        return value.length() <= 256 ? value : value.substring(0, 256);
    }
}
