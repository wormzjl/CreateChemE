package com.wormzjl.createcheme.network;

import com.wormzjl.createcheme.CreateChemE;
import com.wormzjl.createcheme.science.column.ColumnSimulation;
import com.wormzjl.createcheme.science.column.ColumnSimulation.ColumnDiagnostic;
import com.wormzjl.createcheme.science.column.ColumnSimulation.ColumnInput;
import com.wormzjl.createcheme.science.column.ColumnSimulation.ColumnResult;
import com.wormzjl.createcheme.science.column.ColumnSimulation.ColumnSolveOutcome;
import com.wormzjl.createcheme.science.column.ColumnSimulation.ColumnValidationResult;
import com.wormzjl.createcheme.science.column.ColumnSimulation.ComponentFraction;
import com.wormzjl.createcheme.science.column.ColumnSimulation.ProductStream;
import com.wormzjl.createcheme.science.column.ColumnSimulation.RefluxCondition;
import com.wormzjl.createcheme.science.column.ColumnSimulation.RefluxMode;
import com.wormzjl.createcheme.science.column.ColumnSimulation.SideDrawSpec;
import com.wormzjl.createcheme.runtime.BoundedCpuSolveService;
import com.wormzjl.createcheme.runtime.ProcessSolveServices;
import com.wormzjl.createcheme.runtime.ProcessSolveServices.AdmissionResult;
import com.wormzjl.createcheme.runtime.ProcessSolveServices.ColumnCompletion;
import com.wormzjl.createcheme.runtime.ProcessSolveServices.ColumnRequest;
import com.wormzjl.createcheme.runtime.ProcessSolveServices.ColumnTarget;
import com.wormzjl.createcheme.runtime.ProcessSolveServices.Diagnostics;
import com.wormzjl.createcheme.world.inventory.ColumnCalculatorMenu;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorBlockEntity;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorBlockEntity.CalculationTicket;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.StringJoiner;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** All Milestone-1 wire formats, handlers, formatting, and logging live in this one utility. */
public final class ColumnNetwork {
    private static final String PROTOCOL_VERSION = "3";
    private static final int MAX_ASSAY_ID_LENGTH = 96;
    private static final int MAX_SIDE_DRAWS = 6;
    private static final int MAX_RESULT_PRODUCTS = 8;
    private static final int MAX_RESULT_COMPONENTS = 16;
    private static final int MAX_RESULT_MESSAGES = 16;
    private static final int MAX_MESSAGE_LENGTH = 256;
    private static final int MAX_STATUS_LENGTH = 48;
    private static final int MAX_REVISION_LENGTH = 128;
    private static final int MAX_DIGEST_LENGTH = 128;
    private static final int MAX_IDENTIFIER_LENGTH = 96;
    private static final int MAX_LABEL_LENGTH = 128;
    private static final int MAX_ENUM_NAME_LENGTH = 48;
    private static final int MAX_REFLUX_MODE_LENGTH = 32;
    private static final AtomicLong CLIENT_REQUEST_SEQUENCE = new AtomicLong();
    private static volatile ClientResultConsumer clientResultConsumer = (pos, request, result) -> {};
    private ColumnNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION).executesOn(HandlerThread.MAIN);
        registrar.playToServer(CalculatePayload.TYPE, CalculatePayload.STREAM_CODEC, ColumnNetwork::handleCalculate);
        registrar.playToClient(ResultPayload.TYPE, ResultPayload.STREAM_CODEC, ColumnNetwork::handleResult);
        ColumnNextNetwork.register(registrar);
    }

    /** Client-only call site used by the screen. */
    public static long sendCalculate(BlockPos blockPos, ColumnInput input) {
        long clientRequestId = CLIENT_REQUEST_SEQUENCE.incrementAndGet();
        PacketDistributor.sendToServer(new CalculatePayload(blockPos, clientRequestId, input));
        return clientRequestId;
    }

    /** Installed once by the existing physical-client bootstrap; dedicated servers retain a no-op. */
    public static void setClientResultConsumer(ClientResultConsumer consumer) {
        clientResultConsumer = Objects.requireNonNull(consumer, "consumer");
    }

    private static void handleCalculate(CalculatePayload payload, IPayloadContext context) {
        long requestId = ProcessSolveServices.nextRequestId();
        long startedAt = System.nanoTime();
        if (!(context.player() instanceof ServerPlayer player)
                || !(player.containerMenu instanceof ColumnCalculatorMenu menu)
                || !menu.blockPos().equals(payload.blockPos())
                || !menu.stillValid(player)
                || !(player.serverLevel().getBlockEntity(payload.blockPos())
                        instanceof ColumnCalculatorBlockEntity calculator)) {
            replyRejectedSafely(
                    context,
                    payload.blockPos(),
                    requestId,
                    payload.clientRequestId(),
                    "REJECTED_CONTEXT",
                    List.of("Calculator menu or block is no longer valid"));
            logImmediateTerminal(
                    requestId,
                    opaqueColumnId(payload.blockPos()),
                    "REJECTED_CONTEXT",
                    startedAt,
                    Diagnostics.EMPTY,
                    "context_invalid");
            return;
        }

        long columnId = opaqueColumnId(
                new ColumnTarget(player.serverLevel().dimension(), payload.blockPos()));
        if (CreateChemE.calculationLoggingEnabled()) {
            logInput(requestId, columnId, payload.input());
        }
        ColumnValidationResult validation = ColumnSimulation.validate(payload.input());
        if (!validation.isValid()) {
            replyRejectedSafely(
                    context,
                    payload.blockPos(),
                    requestId,
                    payload.clientRequestId(),
                    "REJECTED_INPUT",
                    diagnosticLines(validation.diagnostics()));
            logImmediateTerminal(
                    requestId,
                    columnId,
                    "REJECTED_INPUT",
                    startedAt,
                    Diagnostics.EMPTY,
                    "faults=" + diagnosticCodes(validation.diagnostics()));
            return;
        }

        Optional<CalculationTicket> ticket =
                calculator.tryBeginCalculation(player.serverLevel().getGameTime(), payload.input());
        if (ticket.isEmpty()) {
            replyRejectedSafely(
                    context,
                    payload.blockPos(),
                    requestId,
                    payload.clientRequestId(),
                    "RATE_LIMITED",
                    List.of("Wait briefly before calculating this block again"));
            logImmediateTerminal(
                    requestId,
                    columnId,
                    "RATE_LIMITED",
                    startedAt,
                    Diagnostics.EMPTY,
                    "block_busy_or_throttled");
            return;
        }

        CalculationTicket calculationTicket = ticket.orElseThrow();
        AdmissionResult admission;
        try {
            admission = ProcessSolveServices.submitColumn(
                    player.getServer(),
                    new ColumnRequest(
                            requestId,
                            payload.clientRequestId(),
                            menu.containerId,
                            player.getUUID(),
                            new ColumnTarget(player.serverLevel().dimension(), payload.blockPos()),
                            calculationTicket,
                            startedAt));
        } catch (RuntimeException exception) {
            calculator.failCalculation(calculationTicket);
            replyRejectedSafely(
                    context,
                    payload.blockPos(),
                    requestId,
                    payload.clientRequestId(),
                    "INTERNAL_ERROR",
                    List.of("Unexpected server error; see console request " + requestId));
            CreateChemE.LOGGER.error(
                    "column_calc request={} column={} status=INTERNAL_ERROR queue_ms=0.000 worker_ms=0.000 "
                            + "wall_ms={} active_at_admission=0 ready_at_admission=0 outstanding_at_admission=0",
                    requestId,
                    columnId,
                    elapsedMilliseconds(startedAt),
                    exception);
            return;
        }

        if (!admission.accepted()) {
            calculator.failCalculation(calculationTicket);
            String status = admissionStatus(admission.admission());
            replyRejectedSafely(
                    context,
                    payload.blockPos(),
                    requestId,
                    payload.clientRequestId(),
                    status,
                    List.of(admissionMessage(admission.admission())));
            logImmediateTerminal(
                    requestId,
                    columnId,
                    status,
                    startedAt,
                    admission.diagnostics(),
                    admission.admission().name());
        }
    }

    /**
     * Compatibility facade for older lifecycle call sites. The central coordinator owns the actual shared drain.
     */
    @Deprecated(forRemoval = false)
    public static void drainCompletedCalculations(MinecraftServer server) {
        ProcessSolveCoordinator.drainCompletedCalculations(server);
    }

    /** Compatibility facade for older lifecycle call sites; shutdown routing is centralized. */
    @Deprecated(forRemoval = false)
    public static void stopCalculations(MinecraftServer server) {
        ProcessSolveCoordinator.stopCalculations(server);
    }

    /** Routed by {@link ProcessSolveCoordinator}; legacy payload/result behavior remains unchanged. */
    static void handleRoutedCompletion(MinecraftServer server, ColumnCompletion job) {
        ColumnRequest request = job.request();
        ResolvedColumn resolved = resolveColumn(server, request);
        boolean terminalLogged = false;
        try {
            if (job.completion().status() != BoundedCpuSolveService.TerminalStatus.SUCCESS) {
                String status = failTicketStatus(resolved.calculator(), request.ticket(),
                        terminalStatus(job.completion().status()));
                List<String> messages = completionMessages(job);
                boolean unexpected = job.completion().status() == BoundedCpuSolveService.TerminalStatus.FAILED;
                logCompletionTerminal(job, status, messages.toString(), unexpected);
                terminalLogged = true;
                sendIfCalculatorOpen(resolved, request, rejectedResultView(status, messages));
                return;
            }

            ColumnSolveOutcome outcome = job.completion().result().orElseThrow();
            if (!outcome.hasResult()) {
                String status = failTicketStatus(
                        resolved.calculator(), request.ticket(), outcome.status().name());
                List<String> messages = diagnosticLines(outcome.diagnostics());
                logCompletionTerminal(
                        job, status, "faults=" + diagnosticCodes(outcome.diagnostics()), false);
                terminalLogged = true;
                sendIfCalculatorOpen(resolved, request, rejectedResultView(status, messages));
                return;
            }

            ColumnResult result = outcome.result().orElseThrow();
            if (!result.datasetRevision().equals(job.completion().stamp().datasetRevision())) {
                String status = failTicketStatus(
                        resolved.calculator(), request.ticket(), "STALE_DATASET");
                logCompletionTerminal(job, status, "dataset_revision_changed", false);
                terminalLogged = true;
                sendIfCalculatorOpen(
                        resolved,
                        request,
                        rejectedResultView(status, List.of("Scientific dataset changed while solving")));
                return;
            }

            ResultView view = resultView(outcome, result);
            if (resolved.calculator() == null) {
                logCompletionTerminal(job, "STALE_TARGET", "block_missing_or_unloaded", false);
                terminalLogged = true;
                sendIfCalculatorOpen(
                        resolved,
                        request,
                        rejectedResultView("STALE_TARGET", List.of("Calculator block is no longer available")));
                return;
            }
            if (!resolved.calculator().commitCalculation(request.ticket(), result)) {
                logCompletionTerminal(job, "STALE_RESULT", "ticket_mismatch", false);
                terminalLogged = true;
                sendIfCalculatorOpen(
                        resolved,
                        request,
                        rejectedResultView("STALE_RESULT", List.of("A newer calculator input replaced this result")));
                return;
            }

            // From this point onward the scientific result is authoritative. Reporting or delivery failures
            // must never transition the matching block back to FAILED.
            terminalLogged = true;
            if (CreateChemE.calculationLoggingEnabled()) {
                try {
                    logResult(
                            request.requestId(),
                            request.ticket().input(),
                            result,
                            outcome,
                            job,
                            opaqueColumnId(request.target()));
                } catch (RuntimeException exception) {
                    CreateChemE.LOGGER.error(
                            "column_calc request={} column={} status=POST_COMMIT_LOG_ERROR committed=true",
                            request.requestId(),
                            opaqueColumnId(request.target()),
                            exception);
                }
            }
            sendIfCalculatorOpen(resolved, request, view);
        } catch (RuntimeException exception) {
            if (terminalLogged) {
                CreateChemE.LOGGER.error(
                        "column_post_terminal_error request={} column={}",
                        request.requestId(),
                        opaqueColumnId(request.target()),
                        exception);
                return;
            }
            if (resolved.calculator() != null) {
                resolved.calculator().failCalculation(request.ticket());
            }
            sendIfCalculatorOpen(
                    resolved,
                    request,
                    rejectedResultView(
                            "INTERNAL_ERROR",
                            List.of("Unexpected server error; see console request " + request.requestId())));
            logCompletionException(job, exception);
        }
    }

    /** Routed by {@link ProcessSolveCoordinator} after a bounded shutdown that leaves work abandoned. */
    static void handleRoutedAbandoned(MinecraftServer server, ColumnRequest request) {
        ResolvedColumn resolved = resolveColumn(server, request);
        String status = failTicketStatus(
                resolved.calculator(), request.ticket(), "SHUTDOWN_UNTERMINATED");
        sendIfCalculatorOpen(
                resolved,
                request,
                rejectedResultView(status, List.of("Server stopped before the solver worker terminated")));
        CreateChemE.LOGGER.error(
                "column_calc request={} column={} status={} queue_ms=NA worker_ms=NA wall_ms={} "
                        + "active_at_admission=NA ready_at_admission=NA outstanding_at_admission=NA "
                        + "detail=worker_did_not_terminate",
                request.requestId(),
                opaqueColumnId(request.target()),
                status,
                elapsedMilliseconds(request.receivedNanos()));
    }

    private static ResolvedColumn resolveColumn(MinecraftServer server, ColumnRequest request) {
        ServerLevel level = server.getLevel(request.target().dimension());
        ColumnCalculatorBlockEntity calculator = level != null
                        && level.isLoaded(request.target().blockPos())
                        && level.getBlockEntity(request.target().blockPos())
                                instanceof ColumnCalculatorBlockEntity found
                ? found
                : null;
        ServerPlayer player = server.getPlayerList().getPlayer(request.playerId());
        ColumnCalculatorMenu menu = player != null
                        && player.serverLevel().dimension().equals(request.target().dimension())
                        && player.containerMenu instanceof ColumnCalculatorMenu openMenu
                        && openMenu.containerId == request.containerId()
                        && openMenu.blockPos().equals(request.target().blockPos())
                        && openMenu.stillValid(player)
                ? openMenu
                : null;
        return new ResolvedColumn(calculator, player, menu);
    }

    private static void sendIfCalculatorOpen(
            ResolvedColumn resolved, ColumnRequest request, ResultView result) {
        if (resolved.player() == null || resolved.menu() == null) {
            return;
        }
        try {
            PacketDistributor.sendToPlayer(
                    resolved.player(),
                    new ResultPayload(
                            request.target().blockPos(),
                            request.requestId(),
                            request.clientRequestId(),
                            result));
        } catch (RuntimeException exception) {
            CreateChemE.LOGGER.error(
                    "column_delivery_error request={} column={} phase=ASYNC_RESULT",
                    request.requestId(),
                    opaqueColumnId(request.target()),
                    exception);
        }
    }

    private static String failTicketStatus(
            ColumnCalculatorBlockEntity calculator,
            CalculationTicket ticket,
            String matchedStatus) {
        if (calculator == null) {
            return "STALE_TARGET";
        }
        return calculator.failCalculation(ticket) ? matchedStatus : "STALE_RESULT";
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

    private static List<String> completionMessages(ColumnCompletion job) {
        if (job.completion().failure().isPresent()) {
            BoundedCpuSolveService.Failure failure = job.completion().failure().orElseThrow();
            return List.of(bounded(
                    "Solver failure: " + failure.type() + ": " + failure.message(),
                    MAX_MESSAGE_LENGTH));
        }
        String detail = job.completion().detail();
        return List.of(detail.isBlank() ? terminalStatus(job.completion().status()) : bounded(detail, MAX_MESSAGE_LENGTH));
    }

    private static void logCompletionTerminal(
            ColumnCompletion job, String status, String detail, boolean unexpected) {
        if (!unexpected && !CreateChemE.calculationLoggingEnabled()) {
            return;
        }
        String template = "column_calc request={} column={} status={} queue_ms={} worker_ms={} wall_ms={} "
                + "active_at_admission={} ready_at_admission={} outstanding_at_admission={} detail={}";
        Object[] arguments = {
            job.request().requestId(),
            opaqueColumnId(job.request().target()),
            status,
            fixed(job.queueMilliseconds()),
            fixed(job.workerMilliseconds()),
            fixed(job.wallMilliseconds(System.nanoTime())),
            job.admissionDiagnostics().activeWorkers(),
            job.admissionDiagnostics().readyJobs(),
            job.admissionDiagnostics().outstandingJobs(),
            bounded(detail, MAX_MESSAGE_LENGTH)
        };
        if (unexpected) {
            CreateChemE.LOGGER.error(template, arguments);
            job.completion().failure().ifPresent(failure -> CreateChemE.LOGGER.error(
                    "column_solver_failure request={} type={} message={} stack={}",
                    job.request().requestId(),
                    failure.type(),
                    failure.message(),
                    failure.stackTrace()));
        } else {
            CreateChemE.LOGGER.info(template, arguments);
        }
    }

    private static void logCompletionException(ColumnCompletion job, RuntimeException exception) {
        CreateChemE.LOGGER.error(
                "column_calc request={} column={} status=INTERNAL_ERROR queue_ms={} worker_ms={} wall_ms={} "
                        + "active_at_admission={} ready_at_admission={} outstanding_at_admission={}",
                job.request().requestId(),
                opaqueColumnId(job.request().target()),
                fixed(job.queueMilliseconds()),
                fixed(job.workerMilliseconds()),
                fixed(job.wallMilliseconds(System.nanoTime())),
                job.admissionDiagnostics().activeWorkers(),
                job.admissionDiagnostics().readyJobs(),
                job.admissionDiagnostics().outstandingJobs(),
                exception);
    }

    private static String admissionStatus(ProcessSolveServices.Admission admission) {
        return switch (admission) {
            case ACCEPTED -> throw new IllegalArgumentException("Accepted admission is not a rejection");
            case OWNER_BUSY -> "BUSY";
            case QUEUE_FULL -> "OVERLOADED";
            case STALE_EPOCH -> "STALE_SERVER";
            case STOPPING -> "SERVICE_STOPPING";
            case SERVICE_UNAVAILABLE -> "SERVICE_UNAVAILABLE";
        };
    }

    private static String admissionMessage(ProcessSolveServices.Admission admission) {
        return switch (admission) {
            case ACCEPTED -> throw new IllegalArgumentException("Accepted admission has no rejection message");
            case OWNER_BUSY -> "This calculator already has an outstanding calculation";
            case QUEUE_FULL -> "The process solver is overloaded; try again later";
            case STALE_EPOCH -> "The server solver lifecycle changed; try again";
            case STOPPING -> "The process solver is stopping";
            case SERVICE_UNAVAILABLE -> "The process solver is not available";
        };
    }

    private static void logImmediateTerminal(
            long requestId,
            long columnId,
            String status,
            long startedAt,
            Diagnostics diagnostics,
            String detail) {
        if (!CreateChemE.calculationLoggingEnabled()) {
            return;
        }
        CreateChemE.LOGGER.info(
                "column_calc request={} column={} status={} queue_ms=0.000 worker_ms=0.000 wall_ms={} "
                        + "active_at_admission={} ready_at_admission={} outstanding_at_admission={} detail={}",
                requestId,
                columnId,
                status,
                elapsedMilliseconds(startedAt),
                diagnostics.activeWorkers(),
                diagnostics.readyJobs(),
                diagnostics.outstandingJobs(),
                bounded(detail, MAX_MESSAGE_LENGTH));
    }

    private static void handleResult(ResultPayload payload, IPayloadContext context) {
        clientResultConsumer.accept(
                payload.blockPos(), payload.clientRequestId(), payload.result());
    }

    private static void replyRejected(
            IPayloadContext context,
            BlockPos blockPos,
            long requestId,
            long clientRequestId,
            String status,
            List<String> lines
    ) {
        context.reply(new ResultPayload(
                blockPos, requestId, clientRequestId, rejectedResultView(status, lines)));
    }

    private static void replyRejectedSafely(
            IPayloadContext context,
            BlockPos blockPos,
            long requestId,
            long clientRequestId,
            String status,
            List<String> lines) {
        try {
            replyRejected(context, blockPos, requestId, clientRequestId, status, lines);
        } catch (RuntimeException exception) {
            CreateChemE.LOGGER.error(
                    "column_delivery_error request={} column={} phase=IMMEDIATE_REJECTION status={}",
                    requestId,
                    opaqueColumnId(blockPos),
                    status,
                    exception);
        }
    }

    private static ResultView resultView(ColumnSolveOutcome outcome, ColumnResult result) {
        List<ComponentRow> components = result.products().isEmpty()
                ? List.of()
                : result.products().getFirst().composition().stream()
                        .map(component -> new ComponentRow(
                                component.componentId(), component.boilingRangeLabel()))
                        .toList();
        List<ProductColumn> products = new ArrayList<>(result.products().size());
        for (ProductStream product : result.products()) {
            if (product.composition().size() != components.size()) {
                throw new IllegalStateException("Product component count does not match result axis");
            }
            List<Double> moleFractions = new ArrayList<>(components.size());
            List<Double> massFractions = new ArrayList<>(components.size());
            for (int componentIndex = 0; componentIndex < components.size(); componentIndex++) {
                ComponentFraction fraction = product.composition().get(componentIndex);
                ComponentRow row = components.get(componentIndex);
                if (!row.componentId().equals(fraction.componentId())
                        || !row.boilingRangeLabel().equals(fraction.boilingRangeLabel())) {
                    throw new IllegalStateException("Product component order does not match result axis");
                }
                moleFractions.add(fraction.moleFraction());
                massFractions.add(fraction.massFraction());
            }
            products.add(new ProductColumn(
                    product.streamId(),
                    product.displayLabel(),
                    product.rateSpecification().name(),
                    product.phase().name(),
                    product.molarFlowMolPerSecond(),
                    product.massFlowKilogramPerSecond(),
                    product.temperatureKelvin(),
                    product.pressurePascal(),
                    product.boilingRange().t5Kelvin(),
                    product.boilingRange().t50Kelvin(),
                    product.boilingRange().t95Kelvin(),
                    moleFractions,
                    massFractions));
        }
        List<String> messages = outcome.diagnostics().stream()
                .limit(MAX_RESULT_MESSAGES)
                .map(diagnostic -> diagnostic.code().wireCode() + ": " + diagnostic.detail())
                .toList();
        return new ResultView(
                outcome.status().name(),
                result.solverRevision(),
                result.datasetRevision(),
                result.resultDigest(),
                outcome.status() == ColumnSimulation.ColumnSolveStatus.DUMMY_RESULT,
                messages,
                components,
                products);
    }

    private static ResultView rejectedResultView(String status, List<String> messages) {
        return new ResultView(
                status,
                ColumnSimulation.THERMODYNAMIC_SOLVER_REVISION,
                "",
                "",
                false,
                messages,
                List.of(),
                List.of());
    }

    private static void logResult(
            long requestId,
            ColumnInput input,
            ColumnResult result,
            ColumnSolveOutcome outcome,
            ColumnCompletion job,
            long columnId
    ) {
        var diagnostics = result.diagnostics();
        var residuals = diagnostics.residuals();
        double minimumTemperature = result.stages().stream()
                .mapToDouble(stage -> stage.temperatureKelvin())
                .min()
                .orElse(Double.NaN);
        double maximumTemperature = result.stages().stream()
                .mapToDouble(stage -> stage.temperatureKelvin())
                .max()
                .orElse(Double.NaN);
        ProductStream top = result.products().getFirst();
        ProductStream bottom = result.products().getLast();

        CreateChemE.LOGGER.info(
                "column_calc request={} column={} status={} model={} data={} input={} stages={} comps={} dof={} init={} "
                        + "iter={} property_evals={} queue_ms={} worker_ms={} wall_ms={} active_at_admission={} "
                        + "ready_at_admission={} outstanding_at_admission={} component_mb={} overall_mb={} energy={} vle={} "
                        + "sum_x={} top_mol_s={} bottom_mol_s={} qcond_kW={} Tmin_K={} Tmax_K={} result={} warnings={}",
                requestId,
                columnId,
                outcome.status(),
                result.solverRevision(),
                result.datasetRevision(),
                result.inputDigest(),
                input.stageCount(),
                top.composition().size(),
                diagnostics.degreesOfFreedom().remainingDegreesOfFreedom(),
                diagnostics.initializationMode(),
                diagnostics.iterations(),
                diagnostics.propertyEvaluations(),
                fixed(job.queueMilliseconds()),
                fixed(job.workerMilliseconds()),
                fixed(job.wallMilliseconds(System.nanoTime())),
                job.admissionDiagnostics().activeWorkers(),
                job.admissionDiagnostics().readyJobs(),
                job.admissionDiagnostics().outstandingJobs(),
                optionalNumber(residuals.maximumComponentMaterialResidual()),
                optionalNumber(residuals.overallMaterialResidual()),
                optionalNumber(residuals.relativeEnergyResidual()),
                optionalNumber(residuals.maximumEquilibriumResidual()),
                optionalNumber(residuals.maximumCompositionSummationResidual()),
                fixed(top.molarFlowMolPerSecond()),
                fixed(bottom.molarFlowMolPerSecond()),
                fixed(result.condenserDutyWatts() / 1_000.0),
                fixed(minimumTemperature),
                fixed(maximumTemperature),
                result.resultDigest(),
                diagnosticCodes(outcome.diagnostics()));

        // A manual PoC calculation is rare, so one bounded composition line per product is useful.
        for (ProductStream product : result.products()) {
            CreateChemE.LOGGER.info(
                    "column_product request={} stream={} kind={} rate_spec={} mol_s={} mass_kg_s={} T_K={} P_Pa={} "
                            + "T5_K={} T50_K={} T95_K={} mole_fractions={}",
                    requestId,
                    product.streamId(),
                    product.kind(),
                    product.rateSpecification(),
                    fixed(product.molarFlowMolPerSecond()),
                    fixed(product.massFlowKilogramPerSecond()),
                    fixed(product.temperatureKelvin()),
                    fixed(product.pressurePascal()),
                    fixed(product.boilingRange().t5Kelvin()),
                    fixed(product.boilingRange().t50Kelvin()),
                    fixed(product.boilingRange().t95Kelvin()),
                    compositionText(product.composition()));
        }
    }

    private static void logInput(long requestId, long columnId, ColumnInput input) {
        StringJoiner draws = new StringJoiner(",", "[", "]");
        for (SideDrawSpec draw : input.sideDraws()) {
            draws.add(draw.stage() + ":" + fixed(draw.molarFlowMolPerSecond()));
        }
        CreateChemE.LOGGER.info(
                "column_request request={} column={} schema={} assay={} feed_mol_s={} feed_T_K={} stages={} "
                        + "feed_stage={} qreb_W={} reflux_ratio={} reflux_mode={} reflux_T_K={} side_draws_stage_mol_s={}",
                requestId,
                columnId,
                input.schemaVersion(),
                bounded(input.assayId(), MAX_ASSAY_ID_LENGTH),
                fixed(input.feedMolarFlowMolPerSecond()),
                fixed(input.feedTemperatureKelvin()),
                input.stageCount(),
                input.feedStage(),
                fixed(input.reboilerDutyWatts()),
                fixed(input.refluxRatio()),
                input.refluxCondition().mode(),
                optionalNumber(input.refluxCondition().temperatureKelvin()),
                draws);
    }

    private static List<String> diagnosticLines(List<ColumnDiagnostic> diagnostics) {
        if (diagnostics.isEmpty()) {
            return List.of("Calculation rejected without a diagnostic");
        }
        return diagnostics.stream()
                .limit(MAX_RESULT_MESSAGES)
                .map(diagnostic -> diagnostic.code().wireCode() + ": " + diagnostic.detail())
                .toList();
    }

    private static String diagnosticCodes(List<ColumnDiagnostic> diagnostics) {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        diagnostics.stream().limit(16).forEach(diagnostic -> joiner.add(diagnostic.code().wireCode()));
        return joiner.toString();
    }

    private static String compositionText(List<ComponentFraction> composition) {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        for (ComponentFraction component : composition) {
            joiner.add(component.componentId() + "=" + fixed(component.moleFraction()));
        }
        return joiner.toString();
    }

    private static String optionalNumber(OptionalDouble value) {
        return value.isPresent() ? fixed(value.getAsDouble()) : "NA";
    }

    private static String fixed(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.10g", value) : "NA";
    }

    private static String elapsedMilliseconds(long startedAt) {
        return String.format(Locale.ROOT, "%.3f", (System.nanoTime() - startedAt) / 1_000_000.0);
    }

    private static long opaqueColumnId(BlockPos pos) {
        return mixOpaqueId(pos.asLong());
    }

    private static long opaqueColumnId(ColumnTarget target) {
        long dimensionHash = Integer.toUnsignedLong(target.dimension().location().hashCode());
        return mixOpaqueId(target.blockPos().asLong() ^ Long.rotateLeft(dimensionHash, 29));
    }

    private static long mixOpaqueId(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdl;
        value ^= value >>> 33;
        return value & Long.MAX_VALUE;
    }

    private record CalculatePayload(BlockPos blockPos, long clientRequestId, ColumnInput input)
            implements CustomPacketPayload {
        private static final Type<CalculatePayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(CreateChemE.MOD_ID, "calculate_column"));
        private static final StreamCodec<RegistryFriendlyByteBuf, CalculatePayload> STREAM_CODEC =
                new StreamCodec<>() {
                    @Override
                    public CalculatePayload decode(RegistryFriendlyByteBuf buffer) {
                        BlockPos blockPos = buffer.readBlockPos();
                        long clientRequestId = buffer.readVarLong();
                        int schemaVersion = buffer.readVarInt();
                        String assayId = buffer.readUtf(MAX_ASSAY_ID_LENGTH);
                        double feedFlow = buffer.readDouble();
                        double feedTemperature = buffer.readDouble();
                        int stageCount = buffer.readVarInt();
                        int feedStage = buffer.readVarInt();
                        double reboilerDuty = buffer.readDouble();
                        double refluxRatio = buffer.readDouble();
                        String refluxModeName = buffer.readUtf(MAX_REFLUX_MODE_LENGTH);
                        RefluxMode refluxMode = RefluxMode.fromSerializedName(refluxModeName)
                                .orElseThrow(() -> new DecoderException("Invalid reflux mode"));
                        OptionalDouble refluxTemperature = buffer.readBoolean()
                                ? OptionalDouble.of(buffer.readDouble())
                                : OptionalDouble.empty();
                        int sideDrawCount = buffer.readVarInt();
                        if (sideDrawCount < 0 || sideDrawCount > MAX_SIDE_DRAWS) {
                            throw new DecoderException("Invalid side-draw count");
                        }
                        List<SideDrawSpec> sideDraws = new ArrayList<>(sideDrawCount);
                        for (int index = 0; index < sideDrawCount; index++) {
                            sideDraws.add(new SideDrawSpec(buffer.readVarInt(), buffer.readDouble()));
                        }
                        ColumnInput input = new ColumnInput(
                                schemaVersion,
                                assayId,
                                feedFlow,
                                feedTemperature,
                                stageCount,
                                feedStage,
                                reboilerDuty,
                                refluxRatio,
                                new RefluxCondition(refluxMode, refluxTemperature),
                                sideDraws);
                        return new CalculatePayload(blockPos, clientRequestId, input);
                    }

                    @Override
                    public void encode(RegistryFriendlyByteBuf buffer, CalculatePayload payload) {
                        ColumnInput input = payload.input();
                        buffer.writeBlockPos(payload.blockPos());
                        buffer.writeVarLong(payload.clientRequestId());
                        buffer.writeVarInt(input.schemaVersion());
                        buffer.writeUtf(input.assayId(), MAX_ASSAY_ID_LENGTH);
                        buffer.writeDouble(input.feedMolarFlowMolPerSecond());
                        buffer.writeDouble(input.feedTemperatureKelvin());
                        buffer.writeVarInt(input.stageCount());
                        buffer.writeVarInt(input.feedStage());
                        buffer.writeDouble(input.reboilerDutyWatts());
                        buffer.writeDouble(input.refluxRatio());
                        buffer.writeUtf(
                                input.refluxCondition().mode().serializedName(),
                                MAX_REFLUX_MODE_LENGTH);
                        OptionalDouble refluxTemperature = input.refluxCondition().temperatureKelvin();
                        buffer.writeBoolean(refluxTemperature.isPresent());
                        refluxTemperature.ifPresent(buffer::writeDouble);
                        buffer.writeVarInt(input.sideDraws().size());
                        for (SideDrawSpec sideDraw : input.sideDraws()) {
                            buffer.writeVarInt(sideDraw.stage());
                            buffer.writeDouble(sideDraw.molarFlowMolPerSecond());
                        }
                    }
                };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private record ResultPayload(
            BlockPos blockPos,
            long requestId,
            long clientRequestId,
            ResultView result)
            implements CustomPacketPayload {
        private static final Type<ResultPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(CreateChemE.MOD_ID, "column_result"));
        private static final StreamCodec<RegistryFriendlyByteBuf, ResultPayload> STREAM_CODEC =
                new StreamCodec<>() {
                    @Override
                    public ResultPayload decode(RegistryFriendlyByteBuf buffer) {
                        BlockPos blockPos = buffer.readBlockPos();
                        long requestId = buffer.readVarLong();
                        long clientRequestId = buffer.readVarLong();
                        return new ResultPayload(
                                blockPos, requestId, clientRequestId, readResultView(buffer));
                    }

                    @Override
                    public void encode(RegistryFriendlyByteBuf buffer, ResultPayload payload) {
                        buffer.writeBlockPos(payload.blockPos());
                        buffer.writeVarLong(payload.requestId());
                        buffer.writeVarLong(payload.clientRequestId());
                        writeResultView(buffer, payload.result());
                    }
                };

        private ResultPayload {
            Objects.requireNonNull(blockPos, "blockPos");
            Objects.requireNonNull(result, "result");
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static ResultView readResultView(RegistryFriendlyByteBuf buffer) {
        try {
            String status = buffer.readUtf(MAX_STATUS_LENGTH);
            String modelRevision = buffer.readUtf(MAX_REVISION_LENGTH);
            String datasetRevision = buffer.readUtf(MAX_REVISION_LENGTH);
            String resultDigest = buffer.readUtf(MAX_DIGEST_LENGTH);
            boolean placeholder = buffer.readBoolean();

            int messageCount = readCount(buffer, MAX_RESULT_MESSAGES, "result message");
            List<String> messages = new ArrayList<>(messageCount);
            for (int messageIndex = 0; messageIndex < messageCount; messageIndex++) {
                messages.add(buffer.readUtf(MAX_MESSAGE_LENGTH));
            }

            int componentCount = readCount(buffer, MAX_RESULT_COMPONENTS, "result component");
            List<ComponentRow> components = new ArrayList<>(componentCount);
            for (int componentIndex = 0; componentIndex < componentCount; componentIndex++) {
                components.add(new ComponentRow(
                        buffer.readUtf(MAX_IDENTIFIER_LENGTH),
                        buffer.readUtf(MAX_LABEL_LENGTH)));
            }

            int productCount = readCount(buffer, MAX_RESULT_PRODUCTS, "result product");
            List<ProductColumn> products = new ArrayList<>(productCount);
            for (int productIndex = 0; productIndex < productCount; productIndex++) {
                String streamId = buffer.readUtf(MAX_IDENTIFIER_LENGTH);
                String displayLabel = buffer.readUtf(MAX_LABEL_LENGTH);
                String rateSpecification = buffer.readUtf(MAX_ENUM_NAME_LENGTH);
                String phase = buffer.readUtf(MAX_ENUM_NAME_LENGTH);
                double molarFlow = readFiniteDouble(buffer, "molar flow");
                double massFlow = readFiniteDouble(buffer, "mass flow");
                double temperature = readFiniteDouble(buffer, "temperature");
                double pressure = readFiniteDouble(buffer, "pressure");
                double t5 = readFiniteDouble(buffer, "T5");
                double t50 = readFiniteDouble(buffer, "T50");
                double t95 = readFiniteDouble(buffer, "T95");
                List<Double> moleFractions = readFractionVector(buffer, componentCount, "mole");
                List<Double> massFractions = readFractionVector(buffer, componentCount, "mass");
                products.add(new ProductColumn(
                        streamId,
                        displayLabel,
                        rateSpecification,
                        phase,
                        molarFlow,
                        massFlow,
                        temperature,
                        pressure,
                        t5,
                        t50,
                        t95,
                        moleFractions,
                        massFractions));
            }

            return new ResultView(
                    status,
                    modelRevision,
                    datasetRevision,
                    resultDigest,
                    placeholder,
                    messages,
                    components,
                    products);
        } catch (DecoderException invalidWire) {
            throw invalidWire;
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new DecoderException("Invalid structured column result", invalid);
        }
    }

    private static void writeResultView(RegistryFriendlyByteBuf buffer, ResultView result) {
        buffer.writeUtf(result.status(), MAX_STATUS_LENGTH);
        buffer.writeUtf(result.modelRevision(), MAX_REVISION_LENGTH);
        buffer.writeUtf(result.datasetRevision(), MAX_REVISION_LENGTH);
        buffer.writeUtf(result.resultDigest(), MAX_DIGEST_LENGTH);
        buffer.writeBoolean(result.placeholder());

        buffer.writeVarInt(result.messages().size());
        for (String message : result.messages()) {
            buffer.writeUtf(message, MAX_MESSAGE_LENGTH);
        }
        buffer.writeVarInt(result.components().size());
        for (ComponentRow component : result.components()) {
            buffer.writeUtf(component.componentId(), MAX_IDENTIFIER_LENGTH);
            buffer.writeUtf(component.boilingRangeLabel(), MAX_LABEL_LENGTH);
        }
        buffer.writeVarInt(result.products().size());
        for (ProductColumn product : result.products()) {
            buffer.writeUtf(product.streamId(), MAX_IDENTIFIER_LENGTH);
            buffer.writeUtf(product.displayLabel(), MAX_LABEL_LENGTH);
            buffer.writeUtf(product.rateSpecification(), MAX_ENUM_NAME_LENGTH);
            buffer.writeUtf(product.phase(), MAX_ENUM_NAME_LENGTH);
            buffer.writeDouble(product.molarFlowMolPerSecond());
            buffer.writeDouble(product.massFlowKilogramPerSecond());
            buffer.writeDouble(product.temperatureKelvin());
            buffer.writeDouble(product.pressurePascal());
            buffer.writeDouble(product.t5Kelvin());
            buffer.writeDouble(product.t50Kelvin());
            buffer.writeDouble(product.t95Kelvin());
            writeFractionVector(buffer, product.moleFractions());
            writeFractionVector(buffer, product.massFractions());
        }
    }

    private static int readCount(
            RegistryFriendlyByteBuf buffer, int maximumCount, String description) {
        int count = buffer.readVarInt();
        if (count < 0 || count > maximumCount) {
            throw new DecoderException("Invalid " + description + " count: " + count);
        }
        return count;
    }

    private static double readFiniteDouble(RegistryFriendlyByteBuf buffer, String description) {
        double value = buffer.readDouble();
        if (!Double.isFinite(value)) {
            throw new DecoderException("Non-finite structured result " + description);
        }
        return value;
    }

    private static List<Double> readFractionVector(
            RegistryFriendlyByteBuf buffer, int componentCount, String basis) {
        int vectorLength = readCount(buffer, MAX_RESULT_COMPONENTS, basis + "-fraction vector");
        if (vectorLength != componentCount) {
            throw new DecoderException(
                    "Structured result " + basis + "-fraction length does not match component count");
        }
        List<Double> fractions = new ArrayList<>(vectorLength);
        for (int componentIndex = 0; componentIndex < vectorLength; componentIndex++) {
            fractions.add(readFiniteDouble(buffer, basis + " fraction"));
        }
        return List.copyOf(fractions);
    }

    private static void writeFractionVector(
            RegistryFriendlyByteBuf buffer, List<Double> fractions) {
        buffer.writeVarInt(fractions.size());
        for (double fraction : fractions) {
            buffer.writeDouble(fraction);
        }
    }

    /** Immutable client-facing result, retaining numeric values until the GUI formats them. */
    public record ResultView(
            String status,
            String modelRevision,
            String datasetRevision,
            String resultDigest,
            boolean placeholder,
            List<String> messages,
            List<ComponentRow> components,
            List<ProductColumn> products) {
        public ResultView {
            status = stableString(status, MAX_STATUS_LENGTH, "status", false);
            modelRevision = stableString(
                    modelRevision, MAX_REVISION_LENGTH, "modelRevision", true);
            datasetRevision = stableString(
                    datasetRevision, MAX_REVISION_LENGTH, "datasetRevision", true);
            resultDigest = stableString(
                    resultDigest, MAX_DIGEST_LENGTH, "resultDigest", true);
            Objects.requireNonNull(messages, "messages");
            if (messages.size() > MAX_RESULT_MESSAGES) {
                throw new IllegalArgumentException("Too many structured result messages");
            }
            List<String> stableMessages = new ArrayList<>(messages.size());
            for (String message : messages) {
                stableMessages.add(stableString(
                        message, MAX_MESSAGE_LENGTH, "message", true));
            }
            messages = List.copyOf(stableMessages);
            components = List.copyOf(Objects.requireNonNull(components, "components"));
            products = List.copyOf(Objects.requireNonNull(products, "products"));
            if (components.size() > MAX_RESULT_COMPONENTS) {
                throw new IllegalArgumentException("Too many structured result components");
            }
            if (products.size() > MAX_RESULT_PRODUCTS) {
                throw new IllegalArgumentException("Too many structured result products");
            }
            for (ProductColumn product : products) {
                if (product.moleFractions().size() != components.size()
                        || product.massFractions().size() != components.size()) {
                    throw new IllegalArgumentException(
                            "Structured result fraction vectors must match the component axis");
                }
            }
        }
    }

    /** Shared row axis for the transposed composition table. */
    public record ComponentRow(String componentId, String boilingRangeLabel) {
        public ComponentRow {
            componentId = stableString(
                    componentId, MAX_IDENTIFIER_LENGTH, "componentId", false);
            boilingRangeLabel = stableString(
                    boilingRangeLabel, MAX_LABEL_LENGTH, "boilingRangeLabel", true);
        }
    }

    /** One product column with full-precision values for summary and composition views. */
    public record ProductColumn(
            String streamId,
            String displayLabel,
            String rateSpecification,
            String phase,
            double molarFlowMolPerSecond,
            double massFlowKilogramPerSecond,
            double temperatureKelvin,
            double pressurePascal,
            double t5Kelvin,
            double t50Kelvin,
            double t95Kelvin,
            List<Double> moleFractions,
            List<Double> massFractions) {
        public ProductColumn {
            streamId = stableString(streamId, MAX_IDENTIFIER_LENGTH, "streamId", false);
            displayLabel = stableString(displayLabel, MAX_LABEL_LENGTH, "displayLabel", true);
            rateSpecification = stableString(
                    rateSpecification, MAX_ENUM_NAME_LENGTH, "rateSpecification", false);
            phase = stableString(phase, MAX_ENUM_NAME_LENGTH, "phase", false);
            requireFinite(molarFlowMolPerSecond, "molarFlowMolPerSecond");
            requireFinite(massFlowKilogramPerSecond, "massFlowKilogramPerSecond");
            requireFinite(temperatureKelvin, "temperatureKelvin");
            requireFinite(pressurePascal, "pressurePascal");
            requireFinite(t5Kelvin, "t5Kelvin");
            requireFinite(t50Kelvin, "t50Kelvin");
            requireFinite(t95Kelvin, "t95Kelvin");
            moleFractions = immutableFiniteVector(moleFractions, "moleFractions");
            massFractions = immutableFiniteVector(massFractions, "massFractions");
        }
    }

    private static String stableString(
            String value, int maximumLength, String field, boolean blankAllowed) {
        String stable = bounded(Objects.requireNonNull(value, field), maximumLength);
        if (!blankAllowed && stable.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return stable;
    }

    private static void requireFinite(double value, String field) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite");
        }
    }

    private static List<Double> immutableFiniteVector(List<Double> values, String field) {
        Objects.requireNonNull(values, field);
        if (values.size() > MAX_RESULT_COMPONENTS) {
            throw new IllegalArgumentException(field + " is too long");
        }
        List<Double> copy = List.copyOf(values);
        for (double value : copy) {
            requireFinite(value, field);
        }
        return copy;
    }

    private static String bounded(String text, int maximumLength) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sanitized = new StringBuilder(Math.min(text.length(), maximumLength));
        for (int index = 0; index < text.length() && sanitized.length() < maximumLength; index++) {
            char character = text.charAt(index);
            sanitized.append(Character.isISOControl(character) ? ' ' : character);
        }
        String safe = sanitized.toString();
        return safe.length() <= maximumLength ? safe : safe.substring(0, maximumLength);
    }

    private record ResolvedColumn(
            ColumnCalculatorBlockEntity calculator,
            ServerPlayer player,
            ColumnCalculatorMenu menu) {}

    @FunctionalInterface
    public interface ClientResultConsumer {
        void accept(BlockPos blockPos, long clientRequestId, ResultView result);
    }
}
