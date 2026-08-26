package com.wormzjl.createcheme.runtime;

import com.wormzjl.createcheme.science.column.ColumnSimulation;
import com.wormzjl.createcheme.science.column.TiaJuanaLight12PropertyPackage;
import com.wormzjl.createcheme.science.column.ColumnSimulation.ColumnInput;
import com.wormzjl.createcheme.science.column.ColumnSimulation.ColumnSolveOutcome;
import com.wormzjl.createcheme.science.column.nextgen.ColumnProblem;
import com.wormzjl.createcheme.science.column.nextgen.DryColumnOutcome;
import com.wormzjl.createcheme.science.column.nextgen.DryInsideOutColumnSolver;
import com.wormzjl.createcheme.science.column.nextgen.DrySolveControl;
import com.wormzjl.createcheme.science.column.nextgen.ExactResultCache;
import com.wormzjl.createcheme.science.column.nextgen.NextInputDigest;
import com.wormzjl.createcheme.science.column.nextgen.NextWarmState;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorBlockEntity.CalculationTicket;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorNextBlockEntity.Operation;
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
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * NeoForge lifecycle adapter for the pure, bounded CPU solve service.
 *
 * <p>Every method is server-thread confined. The worker receives only a deeply immutable
 * {@link ProcessSolveCommand}; Minecraft objects and request-delivery metadata stay in the server-thread context
 * map. Both legacy and next jobs enter the one owned bounded service through this sealed envelope.</p>
 */
public final class ProcessSolveServices {
    public static final int MAXIMUM_COMPLETIONS_PER_TICK = 64;

    private static final AtomicLong SERVER_EPOCH_SEQUENCE = new AtomicLong();
    private static final AtomicLong PROCESS_REQUEST_SEQUENCE = new AtomicLong();
    private static final Map<MinecraftServer, ServerState> STATES = new IdentityHashMap<>();
    private static final DryInsideOutColumnSolver NEXT_DRY_SOLVER = new DryInsideOutColumnSolver();
    private static final String NEXT_ASSUMPTIONS_REVISION = "next-cdu-assumptions-r1";

    private ProcessSolveServices() {}

    /**
     * Returns the only process-wide request identity used by both calculator families.
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

    /**
     * Admits a validated immutable column snapshot. This method never calculates on the server thread.
     */
    public static AdmissionResult submitColumn(MinecraftServer server, ColumnRequest request) {
        requireServerThread(server);
        Objects.requireNonNull(request, "request");
        return submit(server, request, new LegacyColumnCommand(request.ticket().input()),
                expectedDatasetRevision(request.ticket().input()));
    }

    /**
     * Admits one already-resolved immutable next-column problem through the same bounded service as legacy work.
     * The worker receives no level, block entity, player, menu, or packet object.
     */
    public static AdmissionResult submitNextColumn(MinecraftServer server, NextColumnRequest request) {
        requireServerThread(server);
        Objects.requireNonNull(request, "request");
        return submit(server, request, new NextColumnCommand(request.problem(), request.warmStart().orElse(null)),
                request.problem().propertyPackage().datasetRevision());
    }

    /**
     * Looks up a compact committed next-column result on the server thread. A hit never seeds a numerical warm
     * state and callers must still take their normal block ticket/revision commit path.
     */
    public static Optional<DryColumnOutcome.Success> findExactNextResult(MinecraftServer server, ColumnProblem problem) {
        requireServerThread(server);
        Objects.requireNonNull(problem, "problem");
        ServerState state = STATES.get(server);
        return state == null ? Optional.empty() : Optional.ofNullable(state.exactNextResults.get(nextCacheKey(problem)));
    }

    /** Installs only a result that the caller has already committed against its matching block operation. */
    public static void putCommittedExactNextResult(
            MinecraftServer server, ColumnProblem problem, DryColumnOutcome.Success success) {
        requireServerThread(server);
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(success, "success");
        ServerState state = STATES.get(server);
        if (state != null && state.stopResult == null) {
            state.exactNextResults.putCommittedSuccess(nextCacheKey(problem), success);
        }
    }

    private static String nextCacheKey(ColumnProblem problem) {
        return NextInputDigest.of(problem, DryInsideOutColumnSolver.SOLVER_REVISION, NEXT_ASSUMPTIONS_REVISION);
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

    /** Cancels the exact outstanding block ticket without affecting a newer solve for that block. */
    public static BoundedCpuSolveService.CancellationResult cancelColumn(
            MinecraftServer server,
            ColumnTarget target,
            CalculationTicket ticket,
            BoundedCpuSolveService.CancelReason reason) {
        requireServerThread(server);
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(reason, "reason");
        ServerState state = STATES.get(server);
        if (state == null) {
            return BoundedCpuSolveService.CancellationResult.NOT_FOUND;
        }
        for (RequestContext context : state.requestsBySequence.values()) {
            if (context.request() instanceof ColumnRequest request
                    && request.target().equals(target)
                    && request.ticket().equals(ticket)) {
                return state.service.cancel(context.stamp(), reason);
            }
        }
        return BoundedCpuSolveService.CancellationResult.NOT_FOUND;
    }

    /** Cancels exactly one active next-column operation without touching a newer input revision. */
    public static BoundedCpuSolveService.CancellationResult cancelNextColumn(
            MinecraftServer server,
            ColumnTarget target,
            Operation operation,
            BoundedCpuSolveService.CancelReason reason) {
        requireServerThread(server);
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(reason, "reason");
        ServerState state = STATES.get(server);
        if (state == null) {
            return BoundedCpuSolveService.CancellationResult.NOT_FOUND;
        }
        RequestContext context = state.requestsBySequence.get(operation.operationId());
        if (context == null
                || !(context.request() instanceof NextColumnRequest request)
                || !request.target().equals(target)
                || !request.operation().equals(operation)) {
            return BoundedCpuSolveService.CancellationResult.NOT_FOUND;
        }
        return state.service.cancel(context.stamp(), reason);
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
            if (context.request() instanceof ColumnRequest legacyRequest) {
                result.add(new ColumnCompletion(
                        legacyRequest, context.admissionDiagnostics(), legacyCompletion(completion)));
            } else if (context.request() instanceof NextColumnRequest nextRequest) {
                result.add(new NextColumnCompletion(
                        nextRequest, context.admissionDiagnostics(), nextCompletion(completion)));
            } else {
                throw new IllegalStateException("Unknown process-solve request family");
            }
        }
        return List.copyOf(result);
    }

    private static String expectedDatasetRevision(ColumnInput input) {
        return TiaJuanaLight12PropertyPackage.DATASET_REVISION;
    }

    private static BoundedCpuSolveService.Completion<ColumnTarget, ColumnSolveOutcome> legacyCompletion(
            BoundedCpuSolveService.Completion<ColumnTarget, ProcessSolveResult> completion) {
        Optional<ColumnSolveOutcome> outcome = completion.result().map(result -> {
            if (!(result instanceof LegacyColumnSolveResult legacy)) {
                throw new IllegalStateException("Legacy request completed with a non-legacy process result");
            }
            return legacy.outcome();
        });
        return new BoundedCpuSolveService.Completion<>(
                completion.stamp(), completion.status(), outcome, completion.failure(), completion.detail(),
                completion.enqueuedNanos(), completion.startedNanos(), completion.completedNanos());
    }

    private static BoundedCpuSolveService.Completion<ColumnTarget, DryColumnOutcome> nextCompletion(
            BoundedCpuSolveService.Completion<ColumnTarget, ProcessSolveResult> completion) {
        Optional<DryColumnOutcome> outcome = completion.result().map(result -> {
            if (!(result instanceof NextColumnSolveResult next)) {
                throw new IllegalStateException("Next request completed with a non-next process result");
            }
            return next.outcome();
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
    public sealed interface ProcessSolveCommand permits LegacyColumnCommand, NextColumnCommand {
        ProcessSolveResult solve(BoundedCpuSolveService.CancellationToken cancellationToken);
    }

    /** Immutable worker result envelope routed only after main-thread completion draining. */
    public sealed interface ProcessSolveResult permits LegacyColumnSolveResult, NextColumnSolveResult {}

    private record LegacyColumnCommand(ColumnInput input) implements ProcessSolveCommand {
        private LegacyColumnCommand {
            Objects.requireNonNull(input, "input");
        }

        @Override
        public ProcessSolveResult solve(BoundedCpuSolveService.CancellationToken cancellationToken) {
            cancellationToken.throwIfCancellationRequested();
            ColumnSolveOutcome outcome = ColumnSimulation.calculate(input);
            cancellationToken.throwIfCancellationRequested();
            return new LegacyColumnSolveResult(outcome);
        }
    }

    private record LegacyColumnSolveResult(ColumnSolveOutcome outcome) implements ProcessSolveResult {
        private LegacyColumnSolveResult {
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    private record NextColumnCommand(ColumnProblem problem, NextWarmState warmStart) implements ProcessSolveCommand {
        private NextColumnCommand {
            Objects.requireNonNull(problem, "problem");
        }

        @Override
        public ProcessSolveResult solve(BoundedCpuSolveService.CancellationToken cancellationToken) {
            cancellationToken.throwIfCancellationRequested();
            DryColumnOutcome outcome = NEXT_DRY_SOLVER.solve(
                    problem,
                    new DrySolveControl(
                            cancellationToken::isCancellationRequested,
                            cancellationToken.deadlineNanos()),
                    warmStart);
            cancellationToken.throwIfCancellationRequested();
            return new NextColumnSolveResult(outcome);
        }
    }

    private record NextColumnSolveResult(DryColumnOutcome outcome) implements ProcessSolveResult {
        private NextColumnSolveResult {
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
    public sealed interface ProcessSolveRequest permits ColumnRequest, NextColumnRequest {
        long requestId();

        ColumnTarget target();

        long inputRevision();
    }

    /** Preserved legacy submission facade. */
    public record ColumnRequest(
            long requestId,
            long clientRequestId,
            int containerId,
            UUID playerId,
            ColumnTarget target,
            CalculationTicket ticket,
            long receivedNanos) implements ProcessSolveRequest {
        public ColumnRequest {
            if (requestId < 0L || clientRequestId < 0L || containerId < 0) {
                throw new IllegalArgumentException("Request identifiers must not be negative");
            }
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(ticket, "ticket");
        }

        @Override
        public long inputRevision() {
            return ticket.inputRevision();
        }
    }

    /** Immutable next-column request metadata; the worker sees only its resolved scientific problem. */
    public record NextColumnRequest(
            long requestId,
            long clientNonce,
            int containerId,
            UUID playerId,
            ColumnTarget target,
            Operation operation,
            ColumnProblem problem,
            Optional<NextWarmState> warmStart,
            long receivedNanos) implements ProcessSolveRequest {
        public NextColumnRequest {
            if (requestId <= 0L || clientNonce < 0L || containerId < 0) {
                throw new IllegalArgumentException("Request identifiers must be positive/nonnegative as applicable");
            }
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(problem, "problem");
            warmStart = Objects.requireNonNull(warmStart, "warmStart");
            if (requestId != operation.operationId()
                    || !operation.input().equals(problem.input())) {
                throw new IllegalArgumentException("Next request identity and resolved problem disagree");
            }
            if (warmStart.isPresent() && !warmStart.orElseThrow().isCompatibleWith(problem)) {
                throw new IllegalArgumentException("Next request warm state is not structurally compatible");
            }
        }

        @Override
        public long inputRevision() {
            return operation.inputRevision();
        }
    }

    /** One completion joined with its immutable main-thread request metadata. */
    public record ColumnCompletion(
            ColumnRequest request,
            Diagnostics admissionDiagnostics,
            BoundedCpuSolveService.Completion<ColumnTarget, ColumnSolveOutcome> completion)
            implements ProcessSolveCompletion {
        public ColumnCompletion {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(admissionDiagnostics, "admissionDiagnostics");
            Objects.requireNonNull(completion, "completion");
            if (request.requestId() != completion.stamp().sequence()
                    || !request.target().equals(completion.stamp().owner())
                    || request.ticket().inputRevision() != completion.stamp().inputRevision()) {
                throw new IllegalArgumentException("Completion stamp does not match request context");
            }
        }

        public double queueMilliseconds() {
            long end = completion.started()
                    ? completion.startedNanos()
                    : completion.completedNanos();
            return nanosToMilliseconds(end - completion.enqueuedNanos());
        }

        public double workerMilliseconds() {
            return completion.started()
                    ? nanosToMilliseconds(completion.completedNanos() - completion.startedNanos())
                    : 0.0;
        }

        public double wallMilliseconds(long nowNanos) {
            return nanosToMilliseconds(nowNanos - request.receivedNanos());
        }
    }

    /** One next completion joined with its immutable server-thread request metadata. */
    public record NextColumnCompletion(
            NextColumnRequest request,
            Diagnostics admissionDiagnostics,
            BoundedCpuSolveService.Completion<ColumnTarget, DryColumnOutcome> completion)
            implements ProcessSolveCompletion {
        public NextColumnCompletion {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(admissionDiagnostics, "admissionDiagnostics");
            Objects.requireNonNull(completion, "completion");
            if (request.requestId() != completion.stamp().sequence()
                    || !request.target().equals(completion.stamp().owner())
                    || request.operation().inputRevision() != completion.stamp().inputRevision()
                    || !request.problem().propertyPackage().datasetRevision()
                            .equals(completion.stamp().datasetRevision())) {
                throw new IllegalArgumentException("Completion stamp does not match next request context");
            }
        }

        public double queueMilliseconds() {
            long end = completion.started()
                    ? completion.startedNanos()
                    : completion.completedNanos();
            return nanosToMilliseconds(end - completion.enqueuedNanos());
        }

        public double workerMilliseconds() {
            return completion.started()
                    ? nanosToMilliseconds(completion.completedNanos() - completion.startedNanos())
                    : 0.0;
        }

        public double wallMilliseconds(long nowNanos) {
            return nanosToMilliseconds(nowNanos - request.receivedNanos());
        }
    }

    /** Marker for the central router; only it may drain the shared completion queue. */
    public sealed interface ProcessSolveCompletion permits ColumnCompletion, NextColumnCompletion {}

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

    private static double nanosToMilliseconds(long nanos) {
        return nanos / (double) TimeUnit.MILLISECONDS.toNanos(1L);
    }

    private record RequestContext(
            ProcessSolveRequest request,
            Diagnostics admissionDiagnostics,
            BoundedCpuSolveService.JobStamp<ColumnTarget> stamp) {}

    private static final class ServerState {
        private final BoundedCpuSolveService<ColumnTarget, ProcessSolveCommand, ProcessSolveResult> service;
        private final Config config;
        private final Map<Long, RequestContext> requestsBySequence = new LinkedHashMap<>();
        private final ExactResultCache<String, DryColumnOutcome.Success> exactNextResults =
                new ExactResultCache<>(ProcessSolveServices::compactNextResultBytes);
        private StopResult stopResult;

        private ServerState(
                BoundedCpuSolveService<ColumnTarget, ProcessSolveCommand, ProcessSolveResult> service,
                Config config) {
            this.service = service;
            this.config = config;
        }
    }

    /** Conservative accounting of the immutable result's primitive arrays plus bounded diagnostics. */
    private static long compactNextResultBytes(DryColumnOutcome.Success success) {
        int stages = success.result().stageCount();
        long nodes = stages + 2L;
        long doubles = nodes * (16L + 16L + 2L + 2L) // HC liquid/vapor, temperature/pressure, water phases
                + (stages + 1L) * 16L // side draws
                + 16L * 3L; // reflux, overhead, bottoms
        long booleans = nodes;
        long diagnostics = 4_096L;
        return Math.addExact(Math.addExact(doubles * Double.BYTES, booleans), diagnostics);
    }
}
