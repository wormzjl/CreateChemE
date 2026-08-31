package com.wormzjl.createcheme.network;

import com.wormzjl.createcheme.CreateChemE;
import com.wormzjl.createcheme.runtime.BoundedCpuSolveService;
import com.wormzjl.createcheme.runtime.ProcessSolveServices;
import com.wormzjl.createcheme.runtime.ProcessSolveServices.ColumnTarget;
import com.wormzjl.createcheme.runtime.ProcessSolveServices.V3ColumnCompletion;
import com.wormzjl.createcheme.runtime.ProcessSolveServices.V3ColumnRequest;
import com.wormzjl.createcheme.science.column.v3.V3ColumnDisplayResult;
import com.wormzjl.createcheme.science.column.v3.V3ColumnInput;
import com.wormzjl.createcheme.science.column.v3.V3ColumnOutcome;
import com.wormzjl.createcheme.science.column.v3.V3ColumnSpecification;
import com.wormzjl.createcheme.science.column.v3.V3ColumnStreamProperties;
import com.wormzjl.createcheme.science.column.v3.V3ComponentBasis;
import com.wormzjl.createcheme.science.column.v3.V3ControlledQuantity;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.world.inventory.ColumnCalculatorV3Menu;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity.V3Operation;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity.V3State;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity.V3Status;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Versioned V3-only wire protocol for immutable input snapshots and compact authoritative state.
 *
 * <p>Every mutation handler runs on the logical server thread, resolves the block/menu/player again, and serializes
 * no mutable numerical state. The static client consumers are assigned only by the active screen and are volatile so
 * payload delivery observes the most recent screen registration.</p>
 */
public final class ColumnV3Network {
    public static final int WIRE_SCHEMA_VERSION = 3;

    private static final int MAX_IDENTIFIER_LENGTH = 128;
    private static final int MAX_COMPONENT_IDENTIFIER_LENGTH = 64;
    private static final int MAX_DIAGNOSTICS = 32;
    private static final int MAX_DIAGNOSTIC_LENGTH = 256;
    private static final int MAX_REVISION_LENGTH = 128;
    private static final int MAX_STREAM_LABEL_LENGTH = 48;
    private static final int MAX_PHASE_LENGTH = 16;
    private static final int MAX_REJECTION_LENGTH = 128;
    private static final AtomicLong CLIENT_NONCE_SEQUENCE = new AtomicLong();
    private static volatile ClientStateConsumer clientStateConsumer = (pos, state) -> {};
    private static volatile ClientRejectionConsumer clientRejectionConsumer = (pos, nonce, reason) -> {};

    private ColumnV3Network() {}

    static void register(PayloadRegistrar registrar) {
        registrar.playToServer(CalculatePayload.TYPE, CalculatePayload.STREAM_CODEC, ColumnV3Network::handleCalculate);
        registrar.playToServer(StateRequestPayload.TYPE, StateRequestPayload.STREAM_CODEC,
                ColumnV3Network::handleStateRequest);
        registrar.playToClient(StatePayload.TYPE, StatePayload.STREAM_CODEC, ColumnV3Network::handleState);
        registrar.playToClient(ActionRejectedPayload.TYPE, ActionRejectedPayload.STREAM_CODEC,
                ColumnV3Network::handleActionRejected);
    }

    /** Requests the current server-authoritative V3 state for the currently open screen. */
    public static long sendStateRequest(BlockPos blockPos) {
        long nonce = CLIENT_NONCE_SEQUENCE.incrementAndGet();
        PacketDistributor.sendToServer(new StateRequestPayload(blockPos, nonce));
        return nonce;
    }

    /** Sends a bounded immutable draft and the revision from which it was edited. */
    public static long sendCalculate(BlockPos blockPos, long expectedInputRevision, V3ColumnInput input) {
        long nonce = CLIENT_NONCE_SEQUENCE.incrementAndGet();
        PacketDistributor.sendToServer(new CalculatePayload(blockPos, nonce, expectedInputRevision, input));
        return nonce;
    }

    public static void setClientStateConsumer(ClientStateConsumer consumer) {
        clientStateConsumer = Objects.requireNonNull(consumer, "consumer");
    }

    public static void setClientRejectionConsumer(ClientRejectionConsumer consumer) {
        clientRejectionConsumer = Objects.requireNonNull(consumer, "consumer");
    }

    private static void handleCalculate(CalculatePayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            reject(context, payload.blockPos(), payload.clientNonce(), "REJECTED_CONTEXT");
            return;
        }
        ColumnCalculatorV3BlockEntity calculator = resolveCalculator(player, payload.blockPos());
        if (calculator == null || !(player.containerMenu instanceof ColumnCalculatorV3Menu menu)
                || !menu.blockPos().equals(payload.blockPos()) || !menu.stillValid(player)) {
            reject(context, payload.blockPos(), payload.clientNonce(), "REJECTED_CONTEXT");
            return;
        }
        try {
            validateResolvedInput(payload.input());
        } catch (IllegalArgumentException invalid) {
            reject(context, payload.blockPos(), payload.clientNonce(), "REJECTED_INPUT: " + bounded(invalid.getMessage()));
            return;
        }
        long requestId = ProcessSolveServices.nextRequestId();
        Optional<V3Operation> operation = calculator.tryBegin(
                payload.expectedInputRevision(), requestId, payload.input());
        if (operation.isEmpty()) {
            reject(context, payload.blockPos(), payload.clientNonce(), "STALE_REVISION_OR_BUSY");
            return;
        }
        V3Operation acceptedOperation = operation.orElseThrow();
        ColumnTarget target = new ColumnTarget(player.serverLevel().dimension(), payload.blockPos());
        try {
            ProcessSolveServices.AdmissionResult admission = ProcessSolveServices.submitV3Column(
                    player.getServer(),
                    new V3ColumnRequest(requestId, target, acceptedOperation, System.nanoTime()));
            if (!admission.accepted()) {
                calculator.failOperation(acceptedOperation, "Admission rejected: " + admission.admission().name());
            }
        } catch (RuntimeException unexpected) {
            calculator.failOperation(acceptedOperation, "INTERNAL_ERROR: " + bounded(unexpected.getMessage()));
            CreateChemE.LOGGER.error("column_v3 request={} status=INTERNAL_ERROR phase=ADMISSION", requestId, unexpected);
        }
        reply(context, payload.blockPos(), calculator.state(payload.clientNonce()));
        pushToViewers(player.getServer(), target, calculator.state(0L));
    }

    private static void handleStateRequest(StateRequestPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            reject(context, payload.blockPos(), payload.clientNonce(), "REJECTED_CONTEXT");
            return;
        }
        ColumnCalculatorV3BlockEntity calculator = resolveCalculator(player, payload.blockPos());
        if (calculator == null || !(player.containerMenu instanceof ColumnCalculatorV3Menu menu)
                || !menu.blockPos().equals(payload.blockPos()) || !menu.stillValid(player)) {
            reject(context, payload.blockPos(), payload.clientNonce(), "REJECTED_CONTEXT");
            return;
        }
        reply(context, payload.blockPos(), calculator.state(payload.clientNonce()));
    }

    /** Called solely by {@link ProcessSolveCoordinator} on the server thread when a worker completion drains. */
    static void handleRoutedCompletion(MinecraftServer server, V3ColumnCompletion job) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(job, "job");
        ColumnCalculatorV3BlockEntity calculator = resolveCalculator(server, job.request().target());
        if (calculator == null) {
            logTerminal(job, "STALE_TARGET", "block_missing_or_replaced");
            return;
        }
        boolean transitioned;
        BoundedCpuSolveService.Completion<ColumnTarget, V3ColumnOutcome> completion = job.completion();
        String terminalStatus;
        String terminalDetail;
        if (completion.status() == BoundedCpuSolveService.TerminalStatus.SUCCESS) {
            V3ColumnOutcome outcome = completion.result().orElse(null);
            terminalStatus = outcome instanceof V3ColumnOutcome.Success ? "SUCCESS"
                    : outcome instanceof V3ColumnOutcome.Failure failure ? failure.code().name() : "MISSING_OUTCOME";
            terminalDetail = outcome instanceof V3ColumnOutcome.Failure failure
                    ? bounded(failure.summary()) : completionDetail(completion);
            transitioned = calculator.finishOperation(job.request().operation(), outcome, terminalDetail);
        } else {
            terminalStatus = terminalStatus(completion.status());
            terminalDetail = completionDetail(completion);
            transitioned = calculator.failOperation(job.request().operation(), terminalStatus + ": " + terminalDetail);
        }
        if (!transitioned) {
            logTerminal(job, "STALE_RESULT", "operation_mismatch");
            return;
        }
        logTerminal(job, terminalStatus, terminalDetail);
        pushToViewers(server, job.request().target(), calculator.state(0L));
    }

    /** Called by the central shutdown router only for a request without a terminal worker completion. */
    static void handleRoutedAbandoned(MinecraftServer server, V3ColumnRequest request) {
        ColumnCalculatorV3BlockEntity calculator = resolveCalculator(server, request.target());
        if (calculator != null && calculator.failOperation(request.operation(), "SERVER_SHUTDOWN")) {
            pushToViewers(server, request.target(), calculator.state(0L));
        }
    }

    private static void validateResolvedInput(V3ColumnInput input) {
        input = Objects.requireNonNull(input, "input");
        if (input.schemaVersion() != V3ColumnInput.SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported V3 scientific input schema");
        }
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(input.packageId());
        if (!thermo.componentBasis().equals(input.componentBasis())) {
            throw new IllegalArgumentException("V3 component axis does not match the server property package");
        }
        EnumSet<V3ControlledQuantity> controls = EnumSet.noneOf(V3ControlledQuantity.class);
        for (V3ColumnSpecification specification : input.specifications()) controls.add(specification.controlledQuantity());
        if (controls.size() != 3 || !controls.containsAll(EnumSet.allOf(V3ControlledQuantity.class))) {
            throw new IllegalArgumentException("V3 input must provide exactly the supported condenser, reflux, and duty controls");
        }
    }

    private static ColumnCalculatorV3BlockEntity resolveCalculator(ServerPlayer player, BlockPos blockPos) {
        return player.serverLevel().isLoaded(blockPos)
                && player.serverLevel().getBlockEntity(blockPos) instanceof ColumnCalculatorV3BlockEntity calculator
                ? calculator : null;
    }

    private static ColumnCalculatorV3BlockEntity resolveCalculator(MinecraftServer server, ColumnTarget target) {
        var level = server.getLevel(target.dimension());
        return level != null && level.isLoaded(target.blockPos())
                && level.getBlockEntity(target.blockPos()) instanceof ColumnCalculatorV3BlockEntity calculator
                ? calculator : null;
    }

    private static void pushToViewers(MinecraftServer server, ColumnTarget target, V3State state) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.serverLevel().dimension().equals(target.dimension())
                    && player.containerMenu instanceof ColumnCalculatorV3Menu menu
                    && menu.blockPos().equals(target.blockPos()) && menu.stillValid(player)) {
                PacketDistributor.sendToPlayer(player, new StatePayload(target.blockPos(), state));
            }
        }
    }

    private static void reply(IPayloadContext context, BlockPos blockPos, V3State state) {
        context.reply(new StatePayload(blockPos, state));
    }

    private static void reject(IPayloadContext context, BlockPos blockPos, long clientNonce, String reason) {
        context.reply(new ActionRejectedPayload(blockPos, clientNonce, bounded(reason)));
    }

    private static void handleState(StatePayload payload, IPayloadContext context) {
        clientStateConsumer.accept(payload.blockPos(), payload.state());
    }

    private static void handleActionRejected(ActionRejectedPayload payload, IPayloadContext context) {
        clientRejectionConsumer.accept(payload.blockPos(), payload.clientNonce(), payload.reason());
    }

    private static String terminalStatus(BoundedCpuSolveService.TerminalStatus status) {
        return switch (status) {
            case SUCCESS -> throw new IllegalArgumentException("Success is handled separately");
            case FAILED -> "SOLVER_FAILURE";
            case CANCELLED -> "CANCELLED";
            case DEADLINE_EXCEEDED -> "DEADLINE_EXCEEDED";
            case SHUTDOWN -> "SERVER_SHUTDOWN";
        };
    }

    private static String completionDetail(BoundedCpuSolveService.Completion<ColumnTarget, V3ColumnOutcome> completion) {
        return completion.failure().map(failure -> bounded(failure.type() + ": " + failure.message()))
                .orElseGet(() -> bounded(completion.detail()));
    }

    private static void logTerminal(V3ColumnCompletion job, String status, String detail) {
        if (!CreateChemE.calculationLoggingEnabled() && !"FAILED".equals(status) && !"STALE_TARGET".equals(status)) return;
        V3ColumnInput input = job.request().operation().input();
        CreateChemE.LOGGER.info(
                "column_v3 request={} status={} input_revision={} stages={} feed_stage={} feed_mol_s={} feed_k={} "
                        + "top_kpa={} drop_kpa={} condenser_k={} reflux={} reboiler_mw={} detail={}",
                job.request().requestId(),
                status,
                job.request().inputRevision(),
                input.stageCount(),
                input.feedStageNumber(),
                totalFeedFlow(input),
                input.feedTemperatureKelvin(),
                input.topPressurePascal() / 1_000.0,
                input.stagePressureDropPascal() / 1_000.0,
                specifiedValue(input, V3ControlledQuantity.CONDENSER_OUTLET_TEMPERATURE),
                specifiedValue(input, V3ControlledQuantity.ORGANIC_REFLUX_RATIO),
                specifiedValue(input, V3ControlledQuantity.REBOILER_DUTY) / 1_000_000.0,
                detail);
        job.completion().result().ifPresent(outcome -> {
            for (String event : outcome.diagnostics().events()) {
                if (event.startsWith("stage-trace ")) {
                    CreateChemE.LOGGER.info("column_v3 request={} event={}", job.request().requestId(), event);
                }
            }
        });
    }

    private static double totalFeedFlow(V3ColumnInput input) {
        double total = 0.0;
        for (double flow : input.feedComponentMolarFlowsMolPerSecond()) total += flow;
        return total;
    }

    private static double specifiedValue(V3ColumnInput input, V3ControlledQuantity wanted) {
        for (V3ColumnSpecification specification : input.specifications()) {
            if (specification.controlledQuantity() == wanted) return specificationValue(specification);
        }
        throw new IllegalArgumentException("V3 input is missing required control " + wanted);
    }

    private static String bounded(String value) {
        if (value == null || value.isBlank()) return "No V3 detail was supplied";
        return value.length() <= MAX_DIAGNOSTIC_LENGTH ? value : value.substring(0, MAX_DIAGNOSTIC_LENGTH);
    }

    private record CalculatePayload(BlockPos blockPos, long clientNonce, long expectedInputRevision, V3ColumnInput input)
            implements CustomPacketPayload {
        private static final Type<CalculatePayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(CreateChemE.MOD_ID, "calculate_column_v3"));
        private static final StreamCodec<RegistryFriendlyByteBuf, CalculatePayload> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public CalculatePayload decode(RegistryFriendlyByteBuf buffer) {
                requireWireSchema(buffer);
                BlockPos blockPos = buffer.readBlockPos();
                long nonce = nonNegative(buffer.readVarLong(), "client nonce");
                long expectedRevision = nonNegative(buffer.readVarLong(), "input revision");
                return new CalculatePayload(blockPos, nonce, expectedRevision, readInput(buffer));
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buffer, CalculatePayload payload) {
                writeWireSchema(buffer);
                buffer.writeBlockPos(payload.blockPos());
                buffer.writeVarLong(payload.clientNonce());
                buffer.writeVarLong(payload.expectedInputRevision());
                writeInput(buffer, payload.input());
            }
        };

        private CalculatePayload {
            blockPos = Objects.requireNonNull(blockPos, "blockPos");
            if (clientNonce < 0L || expectedInputRevision < 0L) throw new IllegalArgumentException("Invalid V3 request revision");
            input = Objects.requireNonNull(input, "input");
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private record StateRequestPayload(BlockPos blockPos, long clientNonce) implements CustomPacketPayload {
        private static final Type<StateRequestPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(CreateChemE.MOD_ID, "request_column_v3_state"));
        private static final StreamCodec<RegistryFriendlyByteBuf, StateRequestPayload> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public StateRequestPayload decode(RegistryFriendlyByteBuf buffer) {
                requireWireSchema(buffer);
                return new StateRequestPayload(buffer.readBlockPos(), nonNegative(buffer.readVarLong(), "client nonce"));
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buffer, StateRequestPayload payload) {
                writeWireSchema(buffer);
                buffer.writeBlockPos(payload.blockPos());
                buffer.writeVarLong(payload.clientNonce());
            }
        };

        private StateRequestPayload {
            blockPos = Objects.requireNonNull(blockPos, "blockPos");
            if (clientNonce < 0L) throw new IllegalArgumentException("Invalid V3 client nonce");
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private record StatePayload(BlockPos blockPos, V3State state) implements CustomPacketPayload {
        private static final Type<StatePayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(CreateChemE.MOD_ID, "column_v3_state"));
        private static final StreamCodec<RegistryFriendlyByteBuf, StatePayload> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public StatePayload decode(RegistryFriendlyByteBuf buffer) {
                requireWireSchema(buffer);
                return new StatePayload(buffer.readBlockPos(), readState(buffer));
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buffer, StatePayload payload) {
                writeWireSchema(buffer);
                buffer.writeBlockPos(payload.blockPos());
                writeState(buffer, payload.state());
            }
        };

        private StatePayload {
            blockPos = Objects.requireNonNull(blockPos, "blockPos");
            state = Objects.requireNonNull(state, "state");
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private record ActionRejectedPayload(BlockPos blockPos, long clientNonce, String reason) implements CustomPacketPayload {
        private static final Type<ActionRejectedPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(CreateChemE.MOD_ID, "column_v3_action_rejected"));
        private static final StreamCodec<RegistryFriendlyByteBuf, ActionRejectedPayload> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public ActionRejectedPayload decode(RegistryFriendlyByteBuf buffer) {
                requireWireSchema(buffer);
                return new ActionRejectedPayload(
                        buffer.readBlockPos(), nonNegative(buffer.readVarLong(), "client nonce"),
                        buffer.readUtf(MAX_REJECTION_LENGTH));
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buffer, ActionRejectedPayload payload) {
                writeWireSchema(buffer);
                buffer.writeBlockPos(payload.blockPos());
                buffer.writeVarLong(payload.clientNonce());
                buffer.writeUtf(payload.reason(), MAX_REJECTION_LENGTH);
            }
        };

        private ActionRejectedPayload {
            blockPos = Objects.requireNonNull(blockPos, "blockPos");
            if (clientNonce < 0L) throw new IllegalArgumentException("Invalid V3 client nonce");
            reason = bounded(reason);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static void writeState(RegistryFriendlyByteBuf buffer, V3State state) {
        buffer.writeVarLong(state.clientNonce());
        buffer.writeVarLong(state.stateRevision());
        buffer.writeVarLong(state.operationId());
        buffer.writeVarLong(state.inputRevision());
        buffer.writeVarLong(state.resultRevision() + 1L);
        buffer.writeUtf(state.status().serializedName(), MAX_REJECTION_LENGTH);
        writeInput(buffer, state.input());
        buffer.writeBoolean(state.displayResult().isPresent());
        state.displayResult().ifPresent(result -> writeDisplayResult(buffer, result));
        buffer.writeVarInt(state.diagnostics().size());
        for (String diagnostic : state.diagnostics()) buffer.writeUtf(diagnostic, MAX_DIAGNOSTIC_LENGTH);
    }

    private static V3State readState(RegistryFriendlyByteBuf buffer) {
        long clientNonce = nonNegative(buffer.readVarLong(), "client nonce");
        long stateRevision = nonNegative(buffer.readVarLong(), "state revision");
        long operationId = nonNegative(buffer.readVarLong(), "operation id");
        long inputRevision = nonNegative(buffer.readVarLong(), "input revision");
        long resultRevision = nonNegative(buffer.readVarLong(), "result revision") - 1L;
        if (resultRevision < -1L) throw new DecoderException("Invalid V3 result revision");
        V3Status status;
        try {
            status = V3Status.fromSerializedName(buffer.readUtf(MAX_REJECTION_LENGTH));
        } catch (IllegalArgumentException invalid) {
            throw new DecoderException("Unknown V3 status", invalid);
        }
        V3ColumnInput input = readInput(buffer);
        Optional<V3ColumnDisplayResult> result = buffer.readBoolean()
                ? Optional.of(readDisplayResult(buffer)) : Optional.empty();
        int count = readCount(buffer, MAX_DIAGNOSTICS, "diagnostic");
        List<String> diagnostics = new ArrayList<>(count);
        for (int index = 0; index < count; index++) diagnostics.add(buffer.readUtf(MAX_DIAGNOSTIC_LENGTH));
        try {
            return new V3State(clientNonce, stateRevision, operationId, inputRevision, resultRevision, status, input,
                    result, diagnostics);
        } catch (IllegalArgumentException invalid) {
            throw new DecoderException("Invalid V3 state", invalid);
        }
    }

    private static void writeInput(RegistryFriendlyByteBuf buffer, V3ColumnInput input) {
        buffer.writeVarInt(input.schemaVersion());
        buffer.writeUtf(input.packageId(), MAX_IDENTIFIER_LENGTH);
        buffer.writeUtf(input.assayId(), MAX_IDENTIFIER_LENGTH);
        buffer.writeVarInt(input.componentBasis().componentCount());
        for (String componentId : input.componentBasis().componentIds()) {
            buffer.writeUtf(componentId, MAX_COMPONENT_IDENTIFIER_LENGTH);
        }
        double[] flows = input.feedComponentMolarFlowsMolPerSecond();
        buffer.writeVarInt(flows.length);
        for (double flow : flows) buffer.writeDouble(flow);
        buffer.writeDouble(input.feedTemperatureKelvin());
        buffer.writeVarInt(input.stageCount());
        buffer.writeVarInt(input.feedStageNumber());
        buffer.writeDouble(input.topPressurePascal());
        buffer.writeDouble(input.stagePressureDropPascal());
        buffer.writeVarInt(input.specifications().size());
        for (V3ColumnSpecification specification : input.specifications()) {
            buffer.writeUtf(specification.controlledQuantity().name(), MAX_COMPONENT_IDENTIFIER_LENGTH);
            buffer.writeDouble(specificationValue(specification));
        }
    }

    private static V3ColumnInput readInput(RegistryFriendlyByteBuf buffer) {
        try {
            int schemaVersion = buffer.readVarInt();
            if (schemaVersion != V3ColumnInput.SCHEMA_VERSION) throw new DecoderException("Unsupported V3 input schema");
            String packageId = buffer.readUtf(MAX_IDENTIFIER_LENGTH);
            String assayId = buffer.readUtf(MAX_IDENTIFIER_LENGTH);
            int componentCount = readCount(buffer, V3ComponentBasis.MAX_COMPONENTS, "component");
            if (componentCount < 1) throw new DecoderException("V3 component axis is empty");
            List<String> componentIds = new ArrayList<>(componentCount);
            for (int index = 0; index < componentCount; index++) {
                componentIds.add(buffer.readUtf(MAX_COMPONENT_IDENTIFIER_LENGTH));
            }
            int flowCount = readCount(buffer, V3ComponentBasis.MAX_COMPONENTS, "feed flow");
            if (flowCount != componentCount) throw new DecoderException("V3 feed-flow axis disagrees with components");
            double[] flows = new double[flowCount];
            for (int index = 0; index < flowCount; index++) flows[index] = finite(buffer.readDouble(), "feed flow");
            double feedTemperature = finite(buffer.readDouble(), "feed temperature");
            int stages = buffer.readVarInt();
            int feedStage = buffer.readVarInt();
            double topPressure = finite(buffer.readDouble(), "top pressure");
            double pressureDrop = finite(buffer.readDouble(), "pressure drop");
            int specificationCount = readCount(buffer, 3, "specification");
            if (specificationCount != 3) throw new DecoderException("V3 specification count must be three");
            List<V3ColumnSpecification> specifications = new ArrayList<>(specificationCount);
            for (int index = 0; index < specificationCount; index++) {
                V3ControlledQuantity quantity = V3ControlledQuantity.valueOf(
                        buffer.readUtf(MAX_COMPONENT_IDENTIFIER_LENGTH));
                specifications.add(specification(quantity, finite(buffer.readDouble(), "specification")));
            }
            return new V3ColumnInput(schemaVersion, packageId, assayId, new V3ComponentBasis(componentIds), flows,
                    feedTemperature, stages, feedStage, topPressure, pressureDrop, specifications);
        } catch (DecoderException invalidWire) {
            throw invalidWire;
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new DecoderException("Invalid V3 input", invalid);
        }
    }

    private static void writeDisplayResult(RegistryFriendlyByteBuf buffer, V3ColumnDisplayResult result) {
        buffer.writeUtf(result.inputDigest(), 64);
        buffer.writeUtf(result.formulationRevision(), MAX_REVISION_LENGTH);
        buffer.writeUtf(result.assumptionsRevision(), MAX_REVISION_LENGTH);
        buffer.writeUtf(result.datasetRevision(), MAX_REVISION_LENGTH);
        buffer.writeVarInt(result.newtonIterations());
        buffer.writeDouble(result.maximumScaledResidual());
        buffer.writeVarInt(result.acceptanceCheckCount());
        buffer.writeVarInt(result.streams().size());
        for (V3ColumnStreamProperties stream : result.streams()) {
            buffer.writeUtf(stream.streamId(), MAX_COMPONENT_IDENTIFIER_LENGTH);
            buffer.writeUtf(stream.displayName(), MAX_STREAM_LABEL_LENGTH);
            buffer.writeUtf(stream.phase(), MAX_PHASE_LENGTH);
            buffer.writeDouble(stream.molarFlowMolPerSecond());
            buffer.writeDouble(stream.massFlowKgPerSecond());
            buffer.writeDouble(stream.temperatureKelvin());
            buffer.writeDouble(stream.pressurePascal());
            buffer.writeDouble(stream.vaporMoleFraction());
            buffer.writeVarInt(stream.moleFractions().size());
            for (V3ColumnStreamProperties.ComponentFraction fraction : stream.moleFractions()) {
                buffer.writeUtf(fraction.componentId(), MAX_COMPONENT_IDENTIFIER_LENGTH);
                buffer.writeDouble(fraction.moleFraction());
                buffer.writeDouble(fraction.massFraction());
            }
        }
    }

    private static V3ColumnDisplayResult readDisplayResult(RegistryFriendlyByteBuf buffer) {
        try {
            String digest = buffer.readUtf(64);
            String formulation = buffer.readUtf(MAX_REVISION_LENGTH);
            String assumptions = buffer.readUtf(MAX_REVISION_LENGTH);
            String dataset = buffer.readUtf(MAX_REVISION_LENGTH);
            int iterations = buffer.readVarInt();
            double residual = finite(buffer.readDouble(), "maximum residual");
            int acceptanceChecks = buffer.readVarInt();
            int streamCount = readCount(buffer, V3ColumnStreamProperties.MAX_STREAMS, "stream");
            List<V3ColumnStreamProperties> streams = new ArrayList<>(streamCount);
            for (int streamIndex = 0; streamIndex < streamCount; streamIndex++) {
                String streamId = buffer.readUtf(MAX_COMPONENT_IDENTIFIER_LENGTH);
                String displayName = buffer.readUtf(MAX_STREAM_LABEL_LENGTH);
                String phase = buffer.readUtf(MAX_PHASE_LENGTH);
                double flow = finite(buffer.readDouble(), "stream flow");
                double massFlow = finite(buffer.readDouble(), "stream mass flow");
                double temperature = finite(buffer.readDouble(), "stream temperature");
                double pressure = finite(buffer.readDouble(), "stream pressure");
                double vaporMoleFraction = finite(buffer.readDouble(), "stream vapor fraction");
                int fractionCount = readCount(buffer, V3ColumnStreamProperties.MAX_COMPONENTS, "stream component");
                if (fractionCount < 1) throw new DecoderException("V3 stream composition is empty");
                List<V3ColumnStreamProperties.ComponentFraction> fractions = new ArrayList<>(fractionCount);
                for (int fractionIndex = 0; fractionIndex < fractionCount; fractionIndex++) {
                    fractions.add(new V3ColumnStreamProperties.ComponentFraction(
                            buffer.readUtf(MAX_COMPONENT_IDENTIFIER_LENGTH),
                            finite(buffer.readDouble(), "stream component mole fraction"),
                            finite(buffer.readDouble(), "stream component mass fraction")));
                }
                streams.add(new V3ColumnStreamProperties(
                        streamId, displayName, phase, flow, massFlow, temperature, pressure, vaporMoleFraction, fractions));
            }
            return new V3ColumnDisplayResult(
                    digest, formulation, assumptions, dataset, iterations, residual, acceptanceChecks, streams);
        } catch (IllegalArgumentException invalid) {
            throw new DecoderException("Invalid V3 display result", invalid);
        }
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

    private static void writeWireSchema(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(WIRE_SCHEMA_VERSION);
    }

    private static void requireWireSchema(RegistryFriendlyByteBuf buffer) {
        if (buffer.readVarInt() != WIRE_SCHEMA_VERSION) throw new DecoderException("Unsupported V3 wire schema");
    }

    private static int readCount(RegistryFriendlyByteBuf buffer, int maximum, String description) {
        int count = buffer.readVarInt();
        if (count < 0 || count > maximum) throw new DecoderException("Invalid V3 " + description + " count");
        return count;
    }

    private static long nonNegative(long value, String description) {
        if (value < 0L) throw new DecoderException("Negative V3 " + description);
        return value;
    }

    private static double finite(double value, String description) {
        if (!Double.isFinite(value)) throw new DecoderException("Non-finite V3 " + description);
        return value;
    }

    @FunctionalInterface
    public interface ClientStateConsumer {
        void accept(BlockPos blockPos, V3State state);
    }

    @FunctionalInterface
    public interface ClientRejectionConsumer {
        void accept(BlockPos blockPos, long clientNonce, String reason);
    }
}
