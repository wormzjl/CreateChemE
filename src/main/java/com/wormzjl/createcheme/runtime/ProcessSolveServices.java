package com.wormzjl.createcheme.runtime;

import com.wormzjl.createcheme.CreateChemE;
import com.wormzjl.createcheme.science.column.v3.V3ColumnCalculator;
import com.wormzjl.createcheme.science.column.v3.V3ColumnInput;
import com.wormzjl.createcheme.science.column.v3.V3ColumnOutcome;
import com.wormzjl.createcheme.science.column.v3.V3HollandExample32;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity.V3Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * NeoForge lifecycle adapter for the pure, bounded CPU solve service.
 *
 * <p>Every method is server-thread confined. The worker receives only a deeply immutable
 * {@link ProcessSolveCommand}; Minecraft objects and request-delivery metadata stay in the server-thread context
 * map. V3 jobs enter the one owned bounded service through this sealed envelope.</p>
 */
public final class ProcessSolveServices {
    public static final int MAXIMUM_COMPLETIONS_PER_TICK = 64;

    private static final AtomicLong SERVER_EPOCH_SEQUENCE = new AtomicLong();
    private static final AtomicLong PROCESS_REQUEST_SEQUENCE = new AtomicLong();
    private static final Map<MinecraftServer, ServerState> STATES = new IdentityHashMap<>();
    private ProcessSolveServices() {}

    /**
     * Returns the only process-wide request identity used by calculator requests.
     *
     * <p>The value is deliberately not reset with a logical server: this keeps stale packet/log identities
     * unambiguous across an integrated-server restart in the same JVM. It is allocated only on the logical server
     * thread by the network admission boundary.</p>
     */
    public static long nextRequestId() {
        return PROCESS_REQUEST_SEQUENCE.updateAndGet(previous -> {
            if (previous == Long.MAX_VALUE) {
                throw new IllegalStateException("Process-solve request sequence exhausted");
            }
            return previous + 1L;
        });
    }

    /** Creates exactly one owned solve service for a starting logical server. */
    public static ServerStarted startServer(MinecraftServer server, Config config) {
        requireServerThread(server);
        Objects.requireNonNull(config, "config");
        if (STATES.containsKey(server)) {
            throw new IllegalStateException("Process solve service already exists for this server");
        }
        long epoch = SERVER_EPOCH_SEQUENCE.incrementAndGet();
        BoundedCpuSolveService<ColumnTarget, ProcessSolveCommand, ProcessSolveResult> service =
                new BoundedCpuSolveService<>(epoch, new BoundedCpuSolveService.Config(
                        config.workerCount(),
                        config.readyCapacity(),
                        "createcheme-cpu-solve-",
                        true,
                        config.gracefulShutdown(),
                        config.forcedShutdown()));
        STATES.put(server, new ServerState(service, config));
        BoundedCpuSolveService.Diagnostics diagnostics = service.diagnostics();
        return new ServerStarted(
                epoch,
                diagnostics.workerCount(),
                diagnostics.readyCapacity(),
                config.solveDeadline().toMillis(),
                config.gracefulShutdown().toMillis(),
                config.forcedShutdown().toMillis());
    }

    /** Admits one immutable V3 input through the existing shared bounded service. */
    public static AdmissionResult submitV3Column(MinecraftServer server, V3ColumnRequest request) {
        requireServerThread(server);
        Objects.requireNonNull(request, "request");
        double stageTraceCutoffMoleFraction = CreateChemE.columnV3StageTraceCutoffMolPercent() / 100.0;
        double convergenceClosureFraction = CreateChemE.columnV3ConvergenceClosurePercent() / 100.0;
        return submit(server, request, new V3ColumnCommand(request.operation().input(),
                        stageTraceCutoffMoleFraction, convergenceClosureFraction),
                request.operation().input().packageId());
    }

    private static AdmissionResult submit(
            MinecraftServer server,
            ProcessSolveRequest request,
            ProcessSolveCommand command,
            String datasetRevision) {
        ServerState state = STATES.get(server);
        if (state == null) {
            return new AdmissionResult(Admission.SERVICE_UNAVAILABLE, Diagnostics.EMPTY);
        }
        if (state.requestsBySequence.containsKey(request.requestId())) {
            throw new IllegalStateException("Duplicate process-solve request sequence");
        }

        long now = System.nanoTime();
        long deadline = now + state.config.solveDeadline().toNanos();
        var stamp = new BoundedCpuSolveService.JobStamp<>(
                state.service.serverEpoch(),
                request.requestId(),
                request.target(),
                request.inputRevision(),
                datasetRevision,
                deadline);
        BoundedCpuSolveService.Admission serviceAdmission = state.service.trySubmit(
                stamp, command, ProcessSolveServices::solveCommand);
        BoundedCpuSolveService.Diagnostics serviceDiagnostics = state.service.diagnostics();
        Diagnostics diagnostics = Diagnostics.from(serviceDiagnostics);
        Admission admission = Admission.from(serviceAdmission);
        if (admission == Admission.ACCEPTED) {
            RequestContext old = state.requestsBySequence.put(
                    request.requestId(), new RequestContext(request, diagnostics, stamp));
            if (old != null) {
                throw new IllegalStateException("Accepted process-solve request replaced existing context");
            }
        }
        return new AdmissionResult(admission, diagnostics);
    }

    /** Drains safely-published worker completions on the logical server thread. */
    public static List<ProcessSolveCompletion> drainCompletions(MinecraftServer server, int maximum) {
        requireServerThread(server);
        ServerState state = STATES.get(server);
        if (state == null) {
            return List.of();
        }
        return drain(state, maximum);
    }

    /** Stops admission and performs the owned bounded two-phase shutdown. */
    public static StopResult stopServer(MinecraftServer server) {
        requireServerThread(server);
        ServerState state = STATES.get(server);
        if (state == null) {
            return StopResult.missing();
        }
        if (state.stopResult != null) {
            return StopResult.alreadyStopped(state.stopResult.shutdownReport());
        }

        BoundedCpuSolveService.ShutdownReport shutdown =
                state.service.shutdown(state.config.gracefulShutdown(), state.config.forcedShutdown());
        List<ProcessSolveCompletion> completions = drain(state, Integer.MAX_VALUE);
        List<ProcessSolveRequest> abandoned = state.requestsBySequence.values().stream()
                .map(RequestContext::request)
                .toList();
        state.requestsBySequence.clear();
        state.stopResult = new StopResult(true, shutdown, completions, abandoned);
        return state.stopResult;
    }

    /** Removes the stopped server identity after the final NeoForge lifecycle event. */
    public static int removeStoppedServer(MinecraftServer server) {
        requireServerThread(server);
        ServerState state = STATES.remove(server);
        if (state == null) {
            return 0;
        }
        if (state.stopResult == null) {
            BoundedCpuSolveService.ShutdownReport ignored =
                    state.service.shutdown(state.config.gracefulShutdown(), state.config.forcedShutdown());
            drain(state, Integer.MAX_VALUE);
            int abandoned = state.requestsBySequence.size();
            state.requestsBySequence.clear();
            return abandoned;
        }
        return state.requestsBySequence.size();
    }

    private static ProcessSolveResult solveCommand(
            ProcessSolveCommand command, BoundedCpuSolveService.CancellationToken cancellationToken) {
        return command.solve(cancellationToken);
    }

    private static List<ProcessSolveCompletion> drain(ServerState state, int maximum) {
        List<BoundedCpuSolveService.Completion<ColumnTarget, ProcessSolveResult>> completed =
                state.service.drainCompletions(maximum);
        if (completed.isEmpty()) {
            return List.of();
        }
        List<ProcessSolveCompletion> result = new ArrayList<>(completed.size());
        for (var completion : completed) {
            RequestContext context = state.requestsBySequence.remove(completion.stamp().sequence());
            if (context == null) {
                throw new IllegalStateException("Process-solve completion has no request context");
            }
            if (context.request() instanceof V3ColumnRequest v3Request) {
                result.add(new V3ColumnCompletion(v3Request, context.admissionDiagnostics(), v3Completion(completion)));
            } else {
                throw new IllegalStateException("Unknown process-solve request family");
            }
        }
        return List.copyOf(result);
    }

    private static BoundedCpuSolveService.Completion<ColumnTarget, V3ColumnOutcome> v3Completion(
            BoundedCpuSolveService.Completion<ColumnTarget, ProcessSolveResult> completion) {
        Optional<V3ColumnOutcome> outcome = completion.result().map(result -> {
            if (!(result instanceof V3ColumnSolveResult v3)) {
                throw new IllegalStateException("V3 request completed with a non-V3 process result");
            }
            return v3.outcome();
        });
        return new BoundedCpuSolveService.Completion<>(
                completion.stamp(), completion.status(), outcome, completion.failure(), completion.detail(),
                completion.enqueuedNanos(), completion.startedNanos(), completion.completedNanos());
    }

    private static void requireServerThread(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        if (!server.isSameThread()) {
            throw new IllegalStateException("Process solve services must be coordinated on the server thread");
        }
    }

    public enum Admission {
        ACCEPTED,
        OWNER_BUSY,
        QUEUE_FULL,
        STALE_EPOCH,
        STOPPING,
        SERVICE_UNAVAILABLE;

        private static Admission from(BoundedCpuSolveService.Admission admission) {
            return switch (admission) {
                case ACCEPTED -> ACCEPTED;
                case OWNER_BUSY -> OWNER_BUSY;
                case QUEUE_FULL -> QUEUE_FULL;
                case STALE_EPOCH -> STALE_EPOCH;
                case STOPPING -> STOPPING;
            };
        }
    }

    /** Immutable worker envelope. Adding a job family extends this sealed protocol, never the executor count. */
    public sealed interface ProcessSolveCommand permits V3ColumnCommand {
        ProcessSolveResult solve(BoundedCpuSolveService.CancellationToken cancellationToken);
    }

    /** Immutable worker result envelope routed only after main-thread completion draining. */
    public sealed interface ProcessSolveResult permits V3ColumnSolveResult {}

    /** Immutable worker snapshot; configuration has already been read and converted by server-thread admission. */
    record V3ColumnCommand(
            V3ColumnInput input, double stageTraceCutoffMoleFraction, double convergenceClosureFraction)
            implements ProcessSolveCommand {
        /** Cutoff-only snapshot at the frozen default convergence closure. */
        V3ColumnCommand(V3ColumnInput input, double stageTraceCutoffMoleFraction) {
            this(input, stageTraceCutoffMoleFraction, 0.0);
        }

        V3ColumnCommand {
            Objects.requireNonNull(input, "input");
            if (!Double.isFinite(stageTraceCutoffMoleFraction) || stageTraceCutoffMoleFraction < 0.0
                    || stageTraceCutoffMoleFraction > 0.01) {
                throw new IllegalArgumentException("V3 stage-trace cutoff must be finite and in [0, 0.01] mole fraction");
            }
            if (!Double.isFinite(convergenceClosureFraction) || convergenceClosureFraction < 0.0
                    || convergenceClosureFraction > 1.0e-3) {
                throw new IllegalArgumentException("V3 convergence closure must be finite and in [0, 1e-3]");
            }
        }

        @Override
        public ProcessSolveResult solve(BoundedCpuSolveService.CancellationToken cancellationToken) {
            cancellationToken.throwIfCancellationRequested();
            V3ColumnOutcome outcome = V3HollandExample32.isPackage(input.packageId())
                    ? V3HollandExample32.calculate(input, cancellationToken::throwIfCancellationRequested)
                    : V3ColumnCalculator.calculate(input, cancellationToken::throwIfCancellationRequested,
                            stageTraceCutoffMoleFraction, convergenceClosureFraction);
            cancellationToken.throwIfCancellationRequested();
            return new V3ColumnSolveResult(outcome);
        }
    }

    record V3ColumnSolveResult(V3ColumnOutcome outcome) implements ProcessSolveResult {
        V3ColumnSolveResult {
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    /** Stable block identity; no loaded level or block entity is retained. */
    public record ColumnTarget(ResourceKey<Level> dimension, BlockPos blockPos) {
        public ColumnTarget {
            Objects.requireNonNull(dimension, "dimension");
            blockPos = Objects.requireNonNull(blockPos, "blockPos").immutable();
        }
    }

    /** Immutable server-thread request context retained until exactly one terminal completion is drained. */
    public sealed interface ProcessSolveRequest permits V3ColumnRequest {
        long requestId();

        ColumnTarget target();

        long inputRevision();
    }

    /** Immutable V3 pilot metadata retained only on the server thread until worker completion drains. */
    public record V3ColumnRequest(
            long requestId, ColumnTarget target, V3Operation operation, long receivedNanos) implements ProcessSolveRequest {
        public V3ColumnRequest {
            if (requestId <= 0L || receivedNanos <= 0L) {
                throw new IllegalArgumentException("V3 request identifiers must be positive");
            }
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(operation, "operation");
            if (requestId != operation.operationId()) {
                throw new IllegalArgumentException("V3 request identity disagrees with its operation");
            }
        }

        @Override
        public long inputRevision() {
            return operation.inputRevision();
        }
    }

    /** One V3 completion joined to immutable server-thread metadata for stale-operation rejection. */
    public record V3ColumnCompletion(
            V3ColumnRequest request,
            Diagnostics admissionDiagnostics,
            BoundedCpuSolveService.Completion<ColumnTarget, V3ColumnOutcome> completion)
            implements ProcessSolveCompletion {
        public V3ColumnCompletion {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(admissionDiagnostics, "admissionDiagnostics");
            Objects.requireNonNull(completion, "completion");
            if (request.requestId() != completion.stamp().sequence()
                    || !request.target().equals(completion.stamp().owner())
                    || request.inputRevision() != completion.stamp().inputRevision()) {
                throw new IllegalArgumentException("Completion stamp does not match V3 request context");
            }
        }
    }

    /** Marker for the central router; only it may drain the shared completion queue. */
    public sealed interface ProcessSolveCompletion permits V3ColumnCompletion {}

    public record AdmissionResult(Admission admission, Diagnostics diagnostics) {
        public AdmissionResult {
            Objects.requireNonNull(admission, "admission");
            Objects.requireNonNull(diagnostics, "diagnostics");
        }

        public boolean accepted() {
            return admission == Admission.ACCEPTED;
        }
    }

    public record Diagnostics(
            int workerCount,
            int activeWorkers,
            int readyJobs,
            int readyCapacity,
            int outstandingJobs,
            int pendingCompletions) {
        public static final Diagnostics EMPTY = new Diagnostics(0, 0, 0, 0, 0, 0);

        private static Diagnostics from(BoundedCpuSolveService.Diagnostics diagnostics) {
            return new Diagnostics(
                    diagnostics.workerCount(),
                    diagnostics.activeWorkers(),
                    diagnostics.readyJobs(),
                    diagnostics.readyCapacity(),
                    diagnostics.outstandingJobs(),
                    diagnostics.pendingCompletions());
        }
    }

    public record ServerStarted(
            long serverEpoch,
            int workerCount,
            int readyCapacity,
            long solveDeadlineMilliseconds,
            long gracefulShutdownMilliseconds,
            long forcedShutdownMilliseconds) {}

    public record Config(
            int workerCount,
            int readyCapacity,
            Duration solveDeadline,
            Duration gracefulShutdown,
            Duration forcedShutdown) {
        public Config {
            if (workerCount < 1 || readyCapacity < 1) {
                throw new IllegalArgumentException("Worker and ready capacities must be positive");
            }
            Objects.requireNonNull(solveDeadline, "solveDeadline");
            Objects.requireNonNull(gracefulShutdown, "gracefulShutdown");
            Objects.requireNonNull(forcedShutdown, "forcedShutdown");
            if (solveDeadline.isZero()
                    || solveDeadline.isNegative()
                    || gracefulShutdown.isNegative()
                    || forcedShutdown.isNegative()) {
                throw new IllegalArgumentException("Invalid solve-service duration");
            }
        }
    }

    public record StopResult(
            boolean shutdownPerformed,
            BoundedCpuSolveService.ShutdownReport shutdownReport,
            List<ProcessSolveCompletion> completions,
            List<ProcessSolveRequest> abandonedRequests) {
        public StopResult {
            completions = List.copyOf(Objects.requireNonNull(completions, "completions"));
            abandonedRequests = List.copyOf(Objects.requireNonNull(abandonedRequests, "abandonedRequests"));
        }

        private static StopResult missing() {
            return new StopResult(
                    false,
                    new BoundedCpuSolveService.ShutdownReport(true, false, false, 0),
                    List.of(),
                    List.of());
        }

        private static StopResult alreadyStopped(BoundedCpuSolveService.ShutdownReport shutdownReport) {
            return new StopResult(false, shutdownReport, List.of(), List.of());
        }
    }

    private record RequestContext(
            ProcessSolveRequest request,
            Diagnostics admissionDiagnostics,
            BoundedCpuSolveService.JobStamp<ColumnTarget> stamp) {}

    private static final class ServerState {
        private final BoundedCpuSolveService<ColumnTarget, ProcessSolveCommand, ProcessSolveResult> service;
        private final Config config;
        private final Map<Long, RequestContext> requestsBySequence = new LinkedHashMap<>();
        private StopResult stopResult;

        private ServerState(
                BoundedCpuSolveService<ColumnTarget, ProcessSolveCommand, ProcessSolveResult> service,
                Config config) {
            this.service = service;
            this.config = config;
        }
    }

}
