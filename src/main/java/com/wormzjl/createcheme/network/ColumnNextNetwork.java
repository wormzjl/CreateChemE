package com.wormzjl.createcheme.network;

import com.wormzjl.createcheme.CreateChemE;
import com.wormzjl.createcheme.runtime.BoundedCpuSolveService;
import com.wormzjl.createcheme.runtime.ProcessSolveServices;
import com.wormzjl.createcheme.runtime.ProcessSolveServices.AdmissionResult;
import com.wormzjl.createcheme.runtime.ProcessSolveServices.ColumnTarget;
import com.wormzjl.createcheme.runtime.ProcessSolveServices.NextColumnCompletion;
import com.wormzjl.createcheme.runtime.ProcessSolveServices.NextColumnRequest;
import com.wormzjl.createcheme.science.column.nextgen.ColumnProblem;
import com.wormzjl.createcheme.science.column.nextgen.ColumnNextInput;
import com.wormzjl.createcheme.science.column.nextgen.ColumnNextValidation;
import com.wormzjl.createcheme.science.column.nextgen.DryColumnOutcome;
import com.wormzjl.createcheme.science.column.nextgen.NextColumnResultView;
import com.wormzjl.createcheme.science.column.nextgen.NextWarmState;
import com.wormzjl.createcheme.world.inventory.ColumnCalculatorNextMenu;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorNextBlockEntity;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
 * Version-1 wire shell for the experimental block.  It is registered by {@link ColumnNetwork}, so both payload
 * families have one protocol-version owner while retaining strictly separate payload types and menu checks.
 */
public final class ColumnNextNetwork {
    private static final int MAX_IDENTIFIER_LENGTH = 96;
    private static final int MAX_DIAGNOSTICS = 32;
    private static final int MAX_DIAGNOSTIC_LENGTH = 256;
    private static final AtomicLong CLIENT_NONCE_SEQUENCE = new AtomicLong();
    private static volatile ClientStateConsumer clientStateConsumer = (pos, state) -> {};

    private ColumnNextNetwork() {}

    static void register(PayloadRegistrar registrar) {
        registrar.playToServer(CalculatePayload.TYPE, CalculatePayload.STREAM_CODEC, ColumnNextNetwork::handleCalculate);
        registrar.playToServer(CancelPayload.TYPE, CancelPayload.STREAM_CODEC, ColumnNextNetwork::handleCancel);
        registrar.playToServer(StateRequestPayload.TYPE, StateRequestPayload.STREAM_CODEC,
                ColumnNextNetwork::handleStateRequest);
        registrar.playToClient(StatePayload.TYPE, StatePayload.STREAM_CODEC, ColumnNextNetwork::handleState);
    }

    public static long sendStateRequest(BlockPos blockPos) {
        long nonce = CLIENT_NONCE_SEQUENCE.incrementAndGet();
        PacketDistributor.sendToServer(new StateRequestPayload(blockPos, nonce));
        return nonce;
    }

    public static void sendCalculate(BlockPos blockPos, long clientNonce, ColumnNextInput input) {
        PacketDistributor.sendToServer(new CalculatePayload(blockPos, clientNonce, input));
    }

    public static void sendCancel(BlockPos blockPos, long clientNonce, long operationId, long inputRevision) {
        PacketDistributor.sendToServer(new CancelPayload(blockPos, clientNonce, operationId, inputRevision));
    }

    public static void setClientStateConsumer(ClientStateConsumer consumer) {
        clientStateConsumer = Objects.requireNonNull(consumer, "consumer");
    }

    private static void handleCalculate(CalculatePayload payload, IPayloadContext context) {
        long requestId = ProcessSolveServices.nextRequestId();
        long receivedNanos = System.nanoTime();
        if (!(context.player() instanceof ServerPlayer player)
                || !(player.containerMenu instanceof ColumnCalculatorNextMenu menu)
                || !menu.blockPos().equals(payload.blockPos()) || !menu.stillValid(player)
                || !(player.serverLevel().getBlockEntity(payload.blockPos())
                        instanceof ColumnCalculatorNextBlockEntity calculator)) {
            reply(context, payload.blockPos(), unavailable(payload.clientNonce(), "REJECTED_CONTEXT"));
            return;
        }
        ColumnNextValidation.Result validation = ColumnNextValidation.validate(payload.input());
        if (!validation.isValid()) {
            reply(context, payload.blockPos(), new StateView(
                    payload.clientNonce(), 0L, 0L, -1L, "REJECTED_INPUT", false, null,
                    false, null, validation.diagnostics().stream().map(ColumnNextValidation.Diagnostic::code).toList()));
            return;
        }
        ColumnProblem problem;
        try {
            // Resolve package, assay, topology, and the complete pressure profile before the worker is admitted.
            problem = ColumnProblem.resolve(payload.input());
        } catch (IllegalArgumentException invalid) {
            reply(context, payload.blockPos(), new StateView(
                    payload.clientNonce(), 0L, 0L, -1L, "REJECTED_INPUT", false, null,
                    false, null, List.of("INVALID_RESOLVED_PROBLEM", bounded(invalid.getMessage()))));
            return;
        }
        var operation = calculator.tryBegin(player.serverLevel().getGameTime(), requestId, problem.input());
        if (operation.isEmpty()) {
            reply(context, payload.blockPos(), unavailable(payload.clientNonce(), "RATE_LIMITED_OR_BUSY"));
            return;
        }
        ColumnCalculatorNextBlockEntity.Operation acceptedOperation = operation.orElseThrow();
        ColumnTarget target = new ColumnTarget(player.serverLevel().dimension(), payload.blockPos());
        var exactResult = ProcessSolveServices.findExactNextResult(player.getServer(), problem);
        if (exactResult.isPresent()) {
            DryColumnOutcome.Success success = exactResult.orElseThrow();
            NextColumnResultView resultView = NextColumnResultView.fromAccepted(problem, success, "EXACT_CACHE");
            if (!calculator.commitExactOutcome(acceptedOperation, success, resultView)) {
                calculator.failOperation(acceptedOperation, List.of("STALE_EXACT_CACHE_COMMIT"));
            }
            reply(context, payload.blockPos(), stateView(calculator.state(payload.clientNonce())));
            pushToViewers(player.getServer(), target, stateView(calculator.state(0L)));
            return;
        }
        AdmissionResult admission;
        try {
            admission = ProcessSolveServices.submitNextColumn(
                    player.getServer(),
                    new NextColumnRequest(
                            requestId,
                            payload.clientNonce(),
                            menu.containerId,
                            player.getUUID(),
                            target,
                            acceptedOperation,
                            problem,
                            calculator.warmStartFor(problem),
                            receivedNanos));
        } catch (RuntimeException exception) {
            calculator.failOperation(acceptedOperation, List.of("INTERNAL_ERROR", bounded(exception.getMessage())));
            reply(context, payload.blockPos(), stateView(calculator.state(payload.clientNonce())));
            pushToViewers(player.getServer(), target, stateView(calculator.state(0L)));
            CreateChemE.LOGGER.error(
                    "column_next request={} status=INTERNAL_ERROR phase=ADMISSION",
                    requestId,
                    exception);
            return;
        }
        if (!admission.accepted()) {
            calculator.failOperation(acceptedOperation, List.of(
                    admission.admission().name(), admissionMessage(admission.admission())));
        }
        reply(context, payload.blockPos(), stateView(calculator.state(payload.clientNonce())));
        pushToViewers(player.getServer(), target, stateView(calculator.state(0L)));
    }

    private static void handleCancel(CancelPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)
                || !(player.containerMenu instanceof ColumnCalculatorNextMenu menu)
                || !menu.blockPos().equals(payload.blockPos()) || !menu.stillValid(player)
                || !(player.serverLevel().getBlockEntity(payload.blockPos())
                        instanceof ColumnCalculatorNextBlockEntity calculator)) {
            reply(context, payload.blockPos(), unavailable(payload.clientNonce(), "REJECTED_CONTEXT"));
            return;
        }
        var operation = calculator.activeOperation(payload.operationId(), payload.inputRevision());
        if (operation.isEmpty()) {
            reply(context, payload.blockPos(), unavailable(payload.clientNonce(), "STALE_CANCEL"));
            return;
        }
        ColumnTarget target = new ColumnTarget(player.serverLevel().dimension(), payload.blockPos());
        BoundedCpuSolveService.CancellationResult cancellation = ProcessSolveServices.cancelNextColumn(
                player.getServer(), target, operation.orElseThrow(), BoundedCpuSolveService.CancelReason.OWNER_REQUEST);
        if (cancellation == BoundedCpuSolveService.CancellationResult.REQUESTED
                || cancellation == BoundedCpuSolveService.CancellationResult.ALREADY_REQUESTED) {
            calculator.beginCancelling(payload.operationId(), payload.inputRevision());
            reply(context, payload.blockPos(), stateView(calculator.state(payload.clientNonce())));
            pushToViewers(player.getServer(), target, stateView(calculator.state(0L)));
            return;
        }
        if (cancellation == BoundedCpuSolveService.CancellationResult.ALREADY_TERMINAL) {
            // The terminal completion is already safely published and will make the matching state transition in
            // the central drain; do not mutate this operation into a misleading local cancellation state.
            reply(context, payload.blockPos(), stateView(calculator.state(payload.clientNonce())));
            return;
        }
        reply(context, payload.blockPos(), unavailable(payload.clientNonce(), "STALE_CANCEL"));
    }

    private static void handleStateRequest(StateRequestPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)
                || !(player.containerMenu instanceof ColumnCalculatorNextMenu menu)
                || !menu.blockPos().equals(payload.blockPos()) || !menu.stillValid(player)
                || !(player.serverLevel().getBlockEntity(payload.blockPos())
                        instanceof ColumnCalculatorNextBlockEntity calculator)) {
            reply(context, payload.blockPos(), unavailable(payload.clientNonce(), "REJECTED_CONTEXT"));
            return;
        }
        reply(context, payload.blockPos(), stateView(calculator.state(payload.clientNonce())));
    }

    private static void pushToViewers(MinecraftServer server, ColumnTarget target, StateView state) {
        // A pushed transition belongs to every current screen instance, not just the requester. Zero is the
        // broadcast nonce and is deliberately accepted by each screen; only delayed request/response replies carry
        // a screen-specific nonce.
        StateView broadcast = new StateView(
                0L, state.operationId(), state.inputRevision(), state.resultRevision(), state.status(),
                state.acceptedInputPresent(), state.acceptedInput(), state.acceptedResultPresent(), state.acceptedResult(),
                state.diagnostics());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.serverLevel().dimension().equals(target.dimension())
                    && player.containerMenu instanceof ColumnCalculatorNextMenu menu
                    && menu.blockPos().equals(target.blockPos())
                    && menu.stillValid(player)) {
                PacketDistributor.sendToPlayer(player, new StatePayload(target.blockPos(), broadcast));
            }
        }
    }

    private static void reply(IPayloadContext context, BlockPos pos, StateView state) {
        context.reply(new StatePayload(pos, state));
    }

    private static void handleState(StatePayload payload, IPayloadContext context) {
        clientStateConsumer.accept(payload.blockPos(), payload.state());
    }

    private static StateView unavailable(long nonce, String code) {
        return new StateView(nonce, 0L, 0L, -1L, code, false, null, false, null, List.of(code));
    }

    private static StateView stateView(ColumnCalculatorNextBlockEntity.NextState state) {
        return new StateView(
                state.clientNonce(), state.operationId(), state.inputRevision(), state.resultRevision(),
                state.status().serializedName(), state.acceptedInput().isPresent(), state.acceptedInput().orElse(null),
                state.acceptedResult().isPresent(), state.acceptedResult().orElse(null), state.diagnostics());
    }

    /** Invoked only by the mod-wide central completion coordinator on the logical server thread. */
    static void handleRoutedCompletion(MinecraftServer server, NextColumnCompletion job) {
        NextColumnRequest request = job.request();
        ColumnCalculatorNextBlockEntity calculator = resolveCalculator(server, request.target());
        if (calculator == null) {
            logTerminal(job, "STALE_TARGET", "block_missing_or_replaced");
            return;
        }
        boolean transitioned;
        try {
            BoundedCpuSolveService.Completion<ColumnTarget, DryColumnOutcome> completion = job.completion();
            if (completion.status() != BoundedCpuSolveService.TerminalStatus.SUCCESS) {
                transitioned = completion.status() == BoundedCpuSolveService.TerminalStatus.CANCELLED
                        ? calculator.finishCancelled(request.operation())
                        : calculator.failOperation(request.operation(), List.of(
                                terminalStatus(completion.status()), completionDetail(completion)));
                if (!transitioned) {
                    logTerminal(job, "STALE_RESULT", "operation_mismatch");
                    return;
                }
                logTerminal(job, terminalStatus(completion.status()), completionDetail(completion));
                pushToViewers(server, request.target(), stateView(calculator.state(0L)));
                return;
            }

            DryColumnOutcome outcome = completion.result().orElseThrow();
            if (outcome instanceof DryColumnOutcome.Success success) {
                NextColumnResultView resultView = NextColumnResultView.fromAccepted(
                        request.problem(), success, initializationMode(success));
                transitioned = calculator.commitDryOutcome(request.operation(), success, resultView);
                if (transitioned) {
                    calculator.installCommittedWarmStart(NextWarmState.fromCommitted(request.problem(), success));
                    ProcessSolveServices.putCommittedExactNextResult(server, request.problem(), success);
                    logTerminal(job, "SUCCESS", "accepted_dry_result");
                }
            } else if (outcome instanceof DryColumnOutcome.Failure failure) {
                transitioned = failure.code().name().equals("CANCELLED")
                        ? calculator.finishCancelled(request.operation())
                        : calculator.failDryOutcome(request.operation(), failure);
                if (transitioned) {
                    logTerminal(job, failure.code().name(), bounded(failure.summary()));
                }
            } else {
                throw new IllegalStateException("Unknown typed dry-column outcome");
            }
            if (!transitioned) {
                logTerminal(job, "STALE_RESULT", "operation_mismatch");
                return;
            }
            pushToViewers(server, request.target(), stateView(calculator.state(0L)));
        } catch (RuntimeException exception) {
            boolean failed = calculator.failOperation(
                    request.operation(), List.of("INTERNAL_ERROR", bounded(exception.getMessage())));
            if (failed) {
                pushToViewers(server, request.target(), stateView(calculator.state(0L)));
            }
            CreateChemE.LOGGER.error(
                    "column_next request={} status=INTERNAL_ERROR phase=COMMIT",
                    request.requestId(),
                    exception);
        }
    }

    /** Invoked by the central shutdown router for an operation whose worker could not terminate. */
    static void handleRoutedAbandoned(MinecraftServer server, NextColumnRequest request) {
        ColumnCalculatorNextBlockEntity calculator = resolveCalculator(server, request.target());
        if (calculator == null) {
            return;
        }
        if (calculator.failOperation(request.operation(), List.of("SHUTDOWN_UNTERMINATED"))) {
            pushToViewers(server, request.target(), stateView(calculator.state(0L)));
        }
    }

    private static ColumnCalculatorNextBlockEntity resolveCalculator(MinecraftServer server, ColumnTarget target) {
        var level = server.getLevel(target.dimension());
        return level != null
                && level.isLoaded(target.blockPos())
                && level.getBlockEntity(target.blockPos()) instanceof ColumnCalculatorNextBlockEntity calculator
                ? calculator
                : null;
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

    private static String initializationMode(DryColumnOutcome.Success success) {
        String path = success.diagnostics().recoveryPath();
        return path.startsWith("CONTINUATION") || path.startsWith("DAMPED") ? "RECOVERY" : path;
    }

    private static String completionDetail(BoundedCpuSolveService.Completion<ColumnTarget, DryColumnOutcome> completion) {
        if (completion.failure().isPresent()) {
            BoundedCpuSolveService.Failure failure = completion.failure().orElseThrow();
            return bounded(failure.type() + ": " + failure.message());
        }
        return bounded(completion.detail());
    }

    private static String admissionMessage(ProcessSolveServices.Admission admission) {
        return switch (admission) {
            case ACCEPTED -> throw new IllegalArgumentException("Accepted admission has no failure message");
            case OWNER_BUSY -> "This calculator position already has an outstanding process solve";
            case QUEUE_FULL -> "The shared process solver is overloaded; try again later";
            case STALE_EPOCH -> "The process solver lifecycle changed; try again";
            case STOPPING -> "The process solver is stopping";
            case SERVICE_UNAVAILABLE -> "The process solver is not available";
        };
    }

    private static void logTerminal(NextColumnCompletion job, String status, String detail) {
        if (!CreateChemE.calculationLoggingEnabled() && !"SOLVER_FAILURE".equals(status)
                && !"INTERNAL_ERROR".equals(status)) {
            return;
        }
        CreateChemE.LOGGER.info(
                "column_next request={} status={} queue_ms={} worker_ms={} wall_ms={} detail={}",
                job.request().requestId(),
                status,
                fixed(job.queueMilliseconds()),
                fixed(job.workerMilliseconds()),
                fixed(job.wallMilliseconds(System.nanoTime())),
                bounded(detail));
    }

    private static String fixed(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private static String bounded(String value) {
        if (value == null || value.isBlank()) {
            return "no_detail";
        }
        return value.length() <= MAX_DIAGNOSTIC_LENGTH
                ? value
                : value.substring(0, MAX_DIAGNOSTIC_LENGTH);
    }

    private record CalculatePayload(BlockPos blockPos, long clientNonce, ColumnNextInput input)
            implements CustomPacketPayload {
        private static final Type<CalculatePayload> TYPE = ColumnNextNetwork.type("calculate_column_next");
        private static final StreamCodec<RegistryFriendlyByteBuf, CalculatePayload> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public CalculatePayload decode(RegistryFriendlyByteBuf buffer) {
                checkPacketBound(buffer);
                return new CalculatePayload(buffer.readBlockPos(), buffer.readVarLong(), readInput(buffer));
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buffer, CalculatePayload payload) {
                buffer.writeBlockPos(payload.blockPos());
                buffer.writeVarLong(payload.clientNonce());
                writeInput(buffer, payload.input());
            }
        };

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private record CancelPayload(BlockPos blockPos, long clientNonce, long operationId, long inputRevision)
            implements CustomPacketPayload {
        private static final Type<CancelPayload> TYPE = ColumnNextNetwork.type("cancel_column_next");
        private static final StreamCodec<RegistryFriendlyByteBuf, CancelPayload> STREAM_CODEC = StreamCodec.of(
                (buffer, payload) -> {
                    buffer.writeBlockPos(payload.blockPos());
                    buffer.writeVarLong(payload.clientNonce());
                    buffer.writeVarLong(payload.operationId());
                    buffer.writeVarLong(payload.inputRevision());
                },
                buffer -> new CancelPayload(buffer.readBlockPos(), buffer.readVarLong(), buffer.readVarLong(),
                        buffer.readVarLong()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private record StateRequestPayload(BlockPos blockPos, long clientNonce) implements CustomPacketPayload {
        private static final Type<StateRequestPayload> TYPE = ColumnNextNetwork.type("request_column_next_state");
        private static final StreamCodec<RegistryFriendlyByteBuf, StateRequestPayload> STREAM_CODEC = StreamCodec.of(
                (buffer, payload) -> { buffer.writeBlockPos(payload.blockPos()); buffer.writeVarLong(payload.clientNonce()); },
                buffer -> new StateRequestPayload(buffer.readBlockPos(), buffer.readVarLong()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private record StatePayload(BlockPos blockPos, StateView state) implements CustomPacketPayload {
        private static final Type<StatePayload> TYPE = ColumnNextNetwork.type("column_next_state");
        private static final StreamCodec<RegistryFriendlyByteBuf, StatePayload> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public StatePayload decode(RegistryFriendlyByteBuf buffer) {
                checkPacketBound(buffer);
                BlockPos pos = buffer.readBlockPos();
                long nonce = buffer.readVarLong();
                long operation = buffer.readVarLong();
                long inputRevision = buffer.readVarLong();
                long resultRevision = buffer.readVarLong();
                String status = buffer.readUtf(MAX_IDENTIFIER_LENGTH);
                boolean hasInput = buffer.readBoolean();
                ColumnNextInput input = hasInput ? readInput(buffer) : null;
                boolean hasResult = buffer.readBoolean();
                NextColumnResultView result = hasResult ? readResultView(buffer) : null;
                int count = readCount(buffer, MAX_DIAGNOSTICS, "diagnostic");
                List<String> diagnostics = new ArrayList<>(count);
                for (int index = 0; index < count; index++) diagnostics.add(buffer.readUtf(MAX_DIAGNOSTIC_LENGTH));
                return new StatePayload(pos, new StateView(
                        nonce, operation, inputRevision, resultRevision, status, hasInput, input, hasResult, result, diagnostics));
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buffer, StatePayload payload) {
                StateView state = payload.state();
                buffer.writeBlockPos(payload.blockPos());
                buffer.writeVarLong(state.clientNonce());
                buffer.writeVarLong(state.operationId());
                buffer.writeVarLong(state.inputRevision());
                buffer.writeVarLong(state.resultRevision());
                buffer.writeUtf(state.status(), MAX_IDENTIFIER_LENGTH);
                buffer.writeBoolean(state.acceptedInputPresent());
                if (state.acceptedInputPresent()) writeInput(buffer, state.acceptedInput());
                buffer.writeBoolean(state.acceptedResultPresent());
                if (state.acceptedResultPresent()) writeResultView(buffer, state.acceptedResult());
                buffer.writeVarInt(state.diagnostics().size());
                for (String diagnostic : state.diagnostics()) buffer.writeUtf(diagnostic, MAX_DIAGNOSTIC_LENGTH);
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private static ColumnNextInput readInput(RegistryFriendlyByteBuf buffer) {
        try {
            int schema = buffer.readVarInt();
            String packageId = buffer.readUtf(MAX_IDENTIFIER_LENGTH);
            String assayId = buffer.readUtf(MAX_IDENTIFIER_LENGTH);
            ColumnNextInput.CrudeFeedInput feed = new ColumnNextInput.CrudeFeedInput(buffer.readDouble(), buffer.readDouble());
            int stages = buffer.readVarInt();
            int feedStage = buffer.readVarInt();
            double topPressure = buffer.readDouble();
            double stageDrop = buffer.readDouble();
            double condenserTemperature = buffer.readDouble();
            double reboilerDuty = buffer.readDouble();
            double reflux = buffer.readDouble();
            int sideCount = readCount(buffer, ColumnNextInput.MAX_SIDE_DRAWS, "side draw");
            List<ColumnNextInput.SideDrawInput> sides = new ArrayList<>(sideCount);
            for (int index = 0; index < sideCount; index++) {
                sides.add(new ColumnNextInput.SideDrawInput(buffer.readVarInt(),
                        ColumnNextInput.AuthoredBasis.fromSerializedName(buffer.readUtf(16)), buffer.readDouble()));
            }
            int utilityCount = readCount(buffer, ColumnNextInput.MAX_UTILITY_FEEDS, "utility feed");
            List<ColumnNextInput.WaterSteamFeedInput> utilities = new ArrayList<>(utilityCount);
            for (int index = 0; index < utilityCount; index++) {
                utilities.add(new ColumnNextInput.WaterSteamFeedInput(
                        ColumnNextInput.UtilityFeedMode.fromSerializedName(buffer.readUtf(16)), buffer.readVarInt(),
                        buffer.readDouble(), buffer.readDouble(), buffer.readDouble()));
            }
            return new ColumnNextInput(schema, packageId, assayId, feed, stages, feedStage, topPressure, stageDrop,
                    condenserTemperature, reboilerDuty, reflux, sides, utilities);
        } catch (IllegalArgumentException | IndexOutOfBoundsException invalid) {
            throw new DecoderException("Invalid next-column input", invalid);
        }
    }

    private static void writeInput(RegistryFriendlyByteBuf buffer, ColumnNextInput input) {
        Objects.requireNonNull(input, "input");
        if (ColumnNextValidation.estimatedWireBytes(input) > ColumnNextInput.MAX_PACKET_BYTES
                || input.sideDraws().size() > ColumnNextInput.MAX_SIDE_DRAWS
                || input.utilityFeeds().size() > ColumnNextInput.MAX_UTILITY_FEEDS) {
            throw new IllegalArgumentException("Next-column input exceeds wire bounds");
        }
        buffer.writeVarInt(input.schemaVersion());
        buffer.writeUtf(input.packageId(), MAX_IDENTIFIER_LENGTH);
        buffer.writeUtf(input.assayId(), MAX_IDENTIFIER_LENGTH);
        buffer.writeDouble(input.crudeFeed().molarFlowMolPerSecond());
        buffer.writeDouble(input.crudeFeed().temperatureKelvin());
        buffer.writeVarInt(input.stageCount());
        buffer.writeVarInt(input.crudeFeedStageNumber());
        buffer.writeDouble(input.topPressurePascal());
        buffer.writeDouble(input.stagePressureDropPascal());
        buffer.writeDouble(input.condenserOutletTemperatureKelvin());
        buffer.writeDouble(input.reboilerDutyWatts());
        buffer.writeDouble(input.organicRefluxRatio());
        buffer.writeVarInt(input.sideDraws().size());
        for (ColumnNextInput.SideDrawInput side : input.sideDraws()) {
            buffer.writeVarInt(side.stageNumber());
            buffer.writeUtf(side.basis().serializedName(), 16);
            buffer.writeDouble(side.authoredRate());
        }
        buffer.writeVarInt(input.utilityFeeds().size());
        for (ColumnNextInput.WaterSteamFeedInput utility : input.utilityFeeds()) {
            buffer.writeUtf(utility.mode().serializedName(), 16);
            buffer.writeVarInt(utility.stageNumber());
            buffer.writeDouble(utility.molarFlowMolPerSecond());
            buffer.writeDouble(utility.temperatureKelvin());
            buffer.writeDouble(utility.upstreamPressurePascal());
        }
    }

    private static NextColumnResultView readResultView(RegistryFriendlyByteBuf buffer) {
        try {
            String solver = buffer.readUtf(MAX_IDENTIFIER_LENGTH);
            String dataset = buffer.readUtf(MAX_IDENTIFIER_LENGTH);
            String assumptions = buffer.readUtf(MAX_IDENTIFIER_LENGTH);
            String inputDigest = buffer.readUtf(MAX_IDENTIFIER_LENGTH);
            String resultDigest = buffer.readUtf(MAX_IDENTIFIER_LENGTH);
            String initialization = buffer.readUtf(MAX_IDENTIFIER_LENGTH);
            double condenserDuty = buffer.readDouble();
            int axisCount = readCount(buffer, NextColumnResultView.COMPONENT_COUNT, "component axis");
            if (axisCount != NextColumnResultView.COMPONENT_COUNT) throw new DecoderException("Invalid component axis length");
            List<String> axis = new ArrayList<>(axisCount);
            for (int index = 0; index < axisCount; index++) axis.add(buffer.readUtf(MAX_IDENTIFIER_LENGTH));
            int streamCount = readCount(buffer, NextColumnResultView.MAX_STREAMS, "stream");
            List<NextColumnResultView.Stream> streams = new ArrayList<>(streamCount);
            for (int index = 0; index < streamCount; index++) {
                String id = buffer.readUtf(MAX_IDENTIFIER_LENGTH);
                String label = buffer.readUtf(MAX_IDENTIFIER_LENGTH);
                NextColumnResultView.Role role = NextColumnResultView.Role.valueOf(buffer.readUtf(16));
                int stage = buffer.readVarInt();
                String phase = buffer.readUtf(MAX_IDENTIFIER_LENGTH);
                double temperature = buffer.readDouble();
                double pressure = buffer.readDouble();
                double[] flows = new double[NextColumnResultView.COMPONENT_COUNT];
                for (int component = 0; component < flows.length; component++) flows[component] = buffer.readDouble();
                streams.add(new NextColumnResultView.Stream(id, label, role, stage, phase, temperature, pressure, flows));
            }
            int diagnosticCount = readCount(buffer, NextColumnResultView.MAX_DIAGNOSTICS, "result diagnostic");
            List<String> diagnostics = new ArrayList<>(diagnosticCount);
            for (int index = 0; index < diagnosticCount; index++) diagnostics.add(buffer.readUtf(MAX_DIAGNOSTIC_LENGTH));
            return new NextColumnResultView(solver, dataset, assumptions, inputDigest, resultDigest, initialization,
                    condenserDuty, axis, streams, diagnostics);
        } catch (IllegalArgumentException | IndexOutOfBoundsException invalid) {
            throw new DecoderException("Invalid next-column result view", invalid);
        }
    }

    private static void writeResultView(RegistryFriendlyByteBuf buffer, NextColumnResultView result) {
        buffer.writeUtf(result.solverRevision(), MAX_IDENTIFIER_LENGTH);
        buffer.writeUtf(result.datasetRevision(), MAX_IDENTIFIER_LENGTH);
        buffer.writeUtf(result.assumptionsRevision(), MAX_IDENTIFIER_LENGTH);
        buffer.writeUtf(result.inputDigest(), MAX_IDENTIFIER_LENGTH);
        buffer.writeUtf(result.resultDigest(), MAX_IDENTIFIER_LENGTH);
        buffer.writeUtf(result.initializationMode(), MAX_IDENTIFIER_LENGTH);
        buffer.writeDouble(result.condenserDutyWatts());
        buffer.writeVarInt(result.componentAxis().size());
        for (String component : result.componentAxis()) buffer.writeUtf(component, MAX_IDENTIFIER_LENGTH);
        buffer.writeVarInt(result.streams().size());
        for (NextColumnResultView.Stream stream : result.streams()) {
            buffer.writeUtf(stream.id(), MAX_IDENTIFIER_LENGTH);
            buffer.writeUtf(stream.label(), MAX_IDENTIFIER_LENGTH);
            buffer.writeUtf(stream.role().name(), 16);
            buffer.writeVarInt(stream.connectedStage());
            buffer.writeUtf(stream.phase(), MAX_IDENTIFIER_LENGTH);
            buffer.writeDouble(stream.temperatureKelvin());
            buffer.writeDouble(stream.pressurePascal());
            for (double flow : stream.componentMolarFlows()) buffer.writeDouble(flow);
        }
        buffer.writeVarInt(result.diagnostics().size());
        for (String diagnostic : result.diagnostics()) buffer.writeUtf(diagnostic, MAX_DIAGNOSTIC_LENGTH);
    }

    private static int readCount(RegistryFriendlyByteBuf buffer, int maximum, String name) {
        int count = buffer.readVarInt();
        if (count < 0 || count > maximum) throw new DecoderException("Invalid " + name + " count");
        return count;
    }

    private static void checkPacketBound(RegistryFriendlyByteBuf buffer) {
        if (buffer.readableBytes() > ColumnNextInput.MAX_PACKET_BYTES) {
            throw new DecoderException("Next-column payload exceeds 64 KiB");
        }
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(CreateChemE.MOD_ID, path));
    }

    public record StateView(
            long clientNonce, long operationId, long inputRevision, long resultRevision, String status,
            boolean acceptedInputPresent, ColumnNextInput acceptedInput,
            boolean acceptedResultPresent, NextColumnResultView acceptedResult, List<String> diagnostics) {
        public StateView {
            Objects.requireNonNull(status, "status");
            if (acceptedInputPresent != (acceptedInput != null)) {
                throw new IllegalArgumentException("Input presence flag and value disagree");
            }
            if (acceptedResultPresent != (acceptedResult != null)) {
                throw new IllegalArgumentException("Result presence flag and value disagree");
            }
            diagnostics = List.copyOf(diagnostics);
            if (diagnostics.size() > MAX_DIAGNOSTICS) throw new IllegalArgumentException("Too many diagnostics");
        }
    }

    @FunctionalInterface
    public interface ClientStateConsumer {
        void accept(BlockPos blockPos, StateView state);
    }
}
