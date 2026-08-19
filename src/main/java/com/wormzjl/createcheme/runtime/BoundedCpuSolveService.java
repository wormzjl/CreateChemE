package com.wormzjl.createcheme.runtime;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * A bounded execution service for independent, CPU-bound, snapshot-to-result solves.
 *
 * <p>The thread that constructs this service owns admission, cancellation, completion draining, and shutdown.
 * Those methods fail fast when called by another thread. Worker threads receive only the supplied immutable snapshot
 * and a cooperative cancellation token. They publish immutable terminal messages through a blocking queue and never
 * invoke a caller callback.</p>
 *
 * <p>Owner keys must have stable {@code equals}/{@code hashCode}; owner keys, snapshots, and results must be deeply
 * immutable for the duration of a job. One job per owner remains outstanding until its terminal completion is
 * drained. The executor uses a {@link SynchronousQueue}, so its only backlog is the bounded, owner-thread-confined
 * ready queue exposed by {@link Diagnostics}.</p>
 *
 * @param <O> immutable owner-key type
 * @param <S> immutable solve-snapshot type
 * @param <R> immutable solve-result type
 */
public final class BoundedCpuSolveService<O, S, R> implements AutoCloseable {
    public static final int DEFAULT_WORKER_COUNT = 1;
    public static final int DEFAULT_READY_CAPACITY = 8;
    public static final long NO_DEADLINE = Long.MAX_VALUE;

    private static final long NOT_STARTED = -1L;
    private static final int MAX_FAILURE_STACK_FRAMES = 32;
    private static final int MAX_FAILURE_MESSAGE_LENGTH = 512;
    private static final Object ACTIVE = new Object();
    private static final Object TERMINAL = new Object();

    private final long serverEpoch;
    private final Config config;
    private final Thread ownerThread;
    private final LongSupplier nanoTime;
    private final int maximumOutstanding;
    private final ArrayDeque<JobControl> readyJobs;
    private final Map<O, JobControl> jobsByOwner;
    private final ArrayBlockingQueue<CompletedJob> completions;
    private final ThreadPoolExecutor executor;
    private final AtomicReference<Failure> lastUncaughtWorkerFailure;

    private boolean accepting = true;
    private int activeWorkers;

    /** Creates a service with one daemon platform worker and an eight-job ready queue. */
    public BoundedCpuSolveService(long serverEpoch) {
        this(serverEpoch, Config.defaults());
    }

    /** Creates a service owned by the calling thread. */
    public BoundedCpuSolveService(long serverEpoch, Config config) {
        this(serverEpoch, config, System::nanoTime);
    }

    BoundedCpuSolveService(long serverEpoch, Config config, LongSupplier nanoTime) {
        this.serverEpoch = serverEpoch;
        this.config = Objects.requireNonNull(config, "config");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.ownerThread = Thread.currentThread();
        this.maximumOutstanding = Math.addExact(config.workerCount(), config.readyCapacity());
        this.readyJobs = new ArrayDeque<>(config.readyCapacity());
        this.jobsByOwner = new HashMap<>(maximumOutstanding);
        this.completions = new ArrayBlockingQueue<>(maximumOutstanding);

        AtomicReference<Failure> uncaughtFailure = new AtomicReference<>();
        this.lastUncaughtWorkerFailure = uncaughtFailure;
        ThreadFactory threadFactory = Thread.ofPlatform()
                .name(config.threadNamePrefix(), 0)
                .daemon(config.daemonThreads())
                .uncaughtExceptionHandler((thread, throwable) ->
                        uncaughtFailure.compareAndSet(null, Failure.from(throwable)))
                .factory();
        this.executor = new ThreadPoolExecutor(
                config.workerCount(),
                config.workerCount(),
                0L,
                TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    public long serverEpoch() {
        return serverEpoch;
    }

    /**
     * Admits a pure solve without ever running it on the caller thread.
     *
     * <p>An {@link Admission#ACCEPTED} job always produces exactly one terminal completion unless the process is
     * forcibly terminated. A completed job still owns its capacity and owner slot until the completion is drained.</p>
     */
    public Admission trySubmit(JobStamp<O> stamp, S snapshot, SolveKernel<S, R> kernel) {
        requireOwnerThread();
        Objects.requireNonNull(stamp, "stamp");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(kernel, "kernel");

        if (!accepting) {
            return Admission.STOPPING;
        }
        if (stamp.serverEpoch() != serverEpoch) {
            return Admission.STALE_EPOCH;
        }
        if (jobsByOwner.containsKey(stamp.owner())) {
            return Admission.OWNER_BUSY;
        }

        expireReadyJobs();
        dispatchReadyJobs();
        if (jobsByOwner.size() >= maximumOutstanding) {
            return Admission.QUEUE_FULL;
        }

        JobControl control = new JobControl(stamp, snapshot, kernel, nanoTime.getAsLong());
        jobsByOwner.put(stamp.owner(), control);
        if (control.token.isDeadlineExceeded()) {
            control.publish(deadlineCompletion(control, nanoTime.getAsLong()));
            return Admission.ACCEPTED;
        }

        if (readyJobs.isEmpty() && activeWorkers < config.workerCount()) {
            DispatchResult dispatched = dispatch(control);
            if (dispatched == DispatchResult.DISPATCHED) {
                return Admission.ACCEPTED;
            }
            if (dispatched == DispatchResult.STOPPED) {
                jobsByOwner.remove(stamp.owner(), control);
                accepting = false;
                return Admission.STOPPING;
            }
        }

        if (readyJobs.size() >= config.readyCapacity()) {
            jobsByOwner.remove(stamp.owner(), control);
            return Admission.QUEUE_FULL;
        }
        readyJobs.addLast(control);
        return Admission.ACCEPTED;
    }

    /** Requests cancellation of exactly the stamped job, protecting a newer job for the same owner. */
    public CancellationResult cancel(JobStamp<O> stamp, CancelReason reason) {
        requireOwnerThread();
        Objects.requireNonNull(stamp, "stamp");
        Objects.requireNonNull(reason, "reason");
        JobControl control = jobsByOwner.get(stamp.owner());
        if (control == null) {
            return CancellationResult.NOT_FOUND;
        }
        if (!control.stamp.equals(stamp)) {
            return CancellationResult.STALE_STAMP;
        }

        CancellationResult result = control.requestCancellation(reason);
        if (result != CancellationResult.REQUESTED) {
            return result;
        }
        if (!control.dispatched) {
            readyJobs.remove(control);
            control.publish(cancellationCompletion(control, reason, nanoTime.getAsLong()));
        } else {
            Thread runner = control.runner.get();
            if (runner != null) {
                runner.interrupt();
            }
        }
        return CancellationResult.REQUESTED;
    }

    /**
     * Drains up to {@code maximum} safely-published completions and then dispatches ready work into free slots.
     * This method is intended to be called once per server tick even when no new work is admitted.
     */
    public List<Completion<O, R>> drainCompletions(int maximum) {
        requireOwnerThread();
        if (maximum < 1) {
            throw new IllegalArgumentException("maximum must be positive");
        }

        expireReadyJobs();
        List<Completion<O, R>> drained = new ArrayList<>(Math.min(maximum, completions.size()));
        for (int index = 0; index < maximum; index++) {
            CompletedJob completed = completions.poll();
            if (completed == null) {
                break;
            }
            JobControl control = completed.control;
            if (!jobsByOwner.remove(control.stamp.owner(), control)) {
                throw new IllegalStateException("Completion does not match the outstanding owner job");
            }
            if (control.dispatched) {
                activeWorkers--;
                if (activeWorkers < 0) {
                    throw new IllegalStateException("Active worker count became negative");
                }
            }
            drained.add(completed.completion);
        }
        if (accepting) {
            dispatchReadyJobs();
        }
        return List.copyOf(drained);
    }

    /** Owner-thread diagnostics. Completed-but-undrained jobs are included in {@code outstandingJobs}. */
    public Diagnostics diagnostics() {
        requireOwnerThread();
        return new Diagnostics(
                accepting,
                config.workerCount(),
                activeWorkers,
                readyJobs.size(),
                config.readyCapacity(),
                jobsByOwner.size(),
                completions.size());
    }

    /** Catastrophic worker errors are retained even though ordinary task failures arrive as completions. */
    public Optional<Failure> lastUncaughtWorkerFailure() {
        return Optional.ofNullable(lastUncaughtWorkerFailure.get());
    }

    public boolean isTerminated() {
        return executor.isTerminated();
    }

    /**
     * Stops admission and performs an owned two-phase shutdown.
     *
     * <p>Ready jobs become {@link TerminalStatus#SHUTDOWN}. Running jobs receive the grace period first; after it
     * expires they receive a cooperative shutdown request plus interruption, followed by a second bounded wait.
     * Completions remain drainable after this method returns.</p>
     */
    public ShutdownReport shutdown(Duration gracefulTimeout, Duration forcedTimeout) {
        requireOwnerThread();
        long gracefulNanos = nonnegativeNanos(gracefulTimeout, "gracefulTimeout");
        long forcedNanos = nonnegativeNanos(forcedTimeout, "forcedTimeout");
        accepting = false;
        cancelReadyForShutdown();
        executor.shutdown();

        boolean interrupted = false;
        boolean forced = false;
        boolean terminated;
        int neverStartedTasks = 0;
        try {
            terminated = executor.awaitTermination(gracefulNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            interrupted = true;
            terminated = false;
        }

        if (!terminated) {
            forced = true;
            requestRunningShutdown();
            List<Runnable> neverStarted = executor.shutdownNow();
            neverStartedTasks = neverStarted.size();
            for (Runnable runnable : neverStarted) {
                if (runnable instanceof BoundedCpuSolveService<?, ?, ?>.WorkerTask workerTask) {
                    workerTask.cancelBeforeRun();
                }
            }
            try {
                terminated = executor.awaitTermination(forcedNanos, TimeUnit.NANOSECONDS);
            } catch (InterruptedException exception) {
                interrupted = true;
                terminated = executor.isTerminated();
            }
            if (!terminated) {
                terminalizeRemainingForShutdown();
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return new ShutdownReport(terminated, forced, interrupted, neverStartedTasks);
    }

    @Override
    public void close() {
        shutdown(config.gracefulShutdownTimeout(), config.forcedShutdownTimeout());
    }

    private void expireReadyJobs() {
        if (readyJobs.isEmpty()) {
            return;
        }
        long now = nanoTime.getAsLong();
        Iterator<JobControl> iterator = readyJobs.iterator();
        while (iterator.hasNext()) {
            JobControl control = iterator.next();
            if (control.token.isDeadlineExceeded()) {
                iterator.remove();
                control.publish(deadlineCompletion(control, now));
            }
        }
    }

    private void dispatchReadyJobs() {
        while (activeWorkers < config.workerCount() && !readyJobs.isEmpty()) {
            JobControl control = readyJobs.removeFirst();
            if (control.token.isDeadlineExceeded()) {
                control.publish(deadlineCompletion(control, nanoTime.getAsLong()));
                continue;
            }
            DispatchResult result = dispatch(control);
            if (result == DispatchResult.TEMPORARILY_UNAVAILABLE) {
                readyJobs.addFirst(control);
                return;
            }
            if (result == DispatchResult.STOPPED) {
                accepting = false;
                readyJobs.addFirst(control);
                cancelReadyForShutdown();
                return;
            }
        }
    }

    private DispatchResult dispatch(JobControl control) {
        control.dispatched = true;
        activeWorkers++;
        try {
            executor.execute(new WorkerTask(control));
            return DispatchResult.DISPATCHED;
        } catch (RejectedExecutionException exception) {
            control.dispatched = false;
            activeWorkers--;
            return executor.isShutdown()
                    ? DispatchResult.STOPPED
                    : DispatchResult.TEMPORARILY_UNAVAILABLE;
        }
    }

    private void cancelReadyForShutdown() {
        long now = nanoTime.getAsLong();
        while (!readyJobs.isEmpty()) {
            JobControl control = readyJobs.removeFirst();
            control.requestCancellation(CancelReason.SHUTDOWN);
            control.publish(cancellationCompletion(control, CancelReason.SHUTDOWN, now));
        }
    }

    private void requestRunningShutdown() {
        for (JobControl control : jobsByOwner.values()) {
            if (!control.dispatched || control.isTerminal()) {
                continue;
            }
            control.requestCancellation(CancelReason.SHUTDOWN);
            Thread runner = control.runner.get();
            if (runner != null) {
                runner.interrupt();
            }
        }
    }

    private void terminalizeRemainingForShutdown() {
        long now = nanoTime.getAsLong();
        for (JobControl control : jobsByOwner.values()) {
            if (!control.dispatched || control.isTerminal()) {
                continue;
            }
            control.requestCancellation(CancelReason.SHUTDOWN);
            control.publish(cancellationCompletion(control, CancelReason.SHUTDOWN, now));
        }
    }

    private Completion<O, R> successCompletion(JobControl control, R result, long completedNanos) {
        return new Completion<>(
                control.stamp,
                TerminalStatus.SUCCESS,
                Optional.of(result),
                Optional.empty(),
                "",
                control.enqueuedNanos,
                control.startedNanos,
                completedNanos);
    }

    private Completion<O, R> failureCompletion(JobControl control, Throwable throwable, long completedNanos) {
        return new Completion<>(
                control.stamp,
                TerminalStatus.FAILED,
                Optional.empty(),
                Optional.of(Failure.from(throwable)),
                boundedMessage(throwable.getMessage()),
                control.enqueuedNanos,
                control.startedNanos,
                completedNanos);
    }

    private Completion<O, R> deadlineCompletion(JobControl control, long completedNanos) {
        return new Completion<>(
                control.stamp,
                TerminalStatus.DEADLINE_EXCEEDED,
                Optional.empty(),
                Optional.empty(),
                "solve deadline exceeded",
                control.enqueuedNanos,
                control.startedNanos,
                completedNanos);
    }

    private Completion<O, R> cancellationCompletion(
            JobControl control, CancelReason reason, long completedNanos) {
        TerminalStatus status = reason == CancelReason.SHUTDOWN
                ? TerminalStatus.SHUTDOWN
                : TerminalStatus.CANCELLED;
        return new Completion<>(
                control.stamp,
                status,
                Optional.empty(),
                Optional.empty(),
                reason.name(),
                control.enqueuedNanos,
                control.startedNanos,
                completedNanos);
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                    "BoundedCpuSolveService must be coordinated by its constructing thread");
        }
    }

    private static long nonnegativeNanos(Duration duration, String field) {
        Objects.requireNonNull(duration, field);
        if (duration.isNegative()) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        try {
            return duration.toNanos();
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static boolean deadlineReached(long now, long deadline) {
        return deadline != NO_DEADLINE && now - deadline >= 0L;
    }

    private static String boundedMessage(String message) {
        if (message == null || message.isEmpty()) {
            return "";
        }
        return message.length() <= MAX_FAILURE_MESSAGE_LENGTH
                ? message
                : message.substring(0, MAX_FAILURE_MESSAGE_LENGTH);
    }

    public enum Admission {
        ACCEPTED,
        OWNER_BUSY,
        QUEUE_FULL,
        STALE_EPOCH,
        STOPPING
    }

    public enum CancellationResult {
        REQUESTED,
        ALREADY_REQUESTED,
        ALREADY_TERMINAL,
        STALE_STAMP,
        NOT_FOUND
    }

    public enum CancelReason {
        OWNER_REQUEST,
        OWNER_UNLOADED,
        STALE_REVISION,
        SHUTDOWN,
        INTERRUPTED
    }

    public enum TerminalStatus {
        SUCCESS,
        FAILED,
        CANCELLED,
        DEADLINE_EXCEEDED,
        SHUTDOWN
    }

    /** Immutable version key transported unchanged from admission to completion. */
    public record JobStamp<O>(
            long serverEpoch,
            long sequence,
            O owner,
            long inputRevision,
            String datasetRevision,
            long deadlineNanos) {
        public JobStamp {
            if (sequence < 0L) {
                throw new IllegalArgumentException("sequence must not be negative");
            }
            if (inputRevision < 0L) {
                throw new IllegalArgumentException("inputRevision must not be negative");
            }
            Objects.requireNonNull(owner, "owner");
            datasetRevision = Objects.requireNonNull(datasetRevision, "datasetRevision");
        }
    }

    @FunctionalInterface
    public interface SolveKernel<S, R> {
        R solve(S immutableSnapshot, CancellationToken cancellationToken) throws Exception;
    }

    public interface CancellationToken {
        long deadlineNanos();

        boolean isDeadlineExceeded();

        boolean isCancellationRequested();

        void throwIfCancellationRequested();
    }

    public static final class SolveCancelledException extends CancellationException {
        private final CancelReason reason;

        private SolveCancelledException(CancelReason reason) {
            super("Solve cancelled: " + reason);
            this.reason = reason;
        }

        public CancelReason reason() {
            return reason;
        }
    }

    public static final class SolveDeadlineExceededException extends CancellationException {
        private SolveDeadlineExceededException() {
            super("Solve deadline exceeded");
        }
    }

    /** Immutable terminal result safely published through the completion queue. */
    public record Completion<O, R>(
            JobStamp<O> stamp,
            TerminalStatus status,
            Optional<R> result,
            Optional<Failure> failure,
            String detail,
            long enqueuedNanos,
            long startedNanos,
            long completedNanos) {
        public Completion {
            Objects.requireNonNull(stamp, "stamp");
            Objects.requireNonNull(status, "status");
            result = Objects.requireNonNull(result, "result");
            failure = Objects.requireNonNull(failure, "failure");
            detail = Objects.requireNonNull(detail, "detail");
            if ((status == TerminalStatus.SUCCESS) != result.isPresent()) {
                throw new IllegalArgumentException("Only successful completions contain a result");
            }
            if ((status == TerminalStatus.FAILED) != failure.isPresent()) {
                throw new IllegalArgumentException("Only failed completions contain failure details");
            }
        }

        public boolean started() {
            return startedNanos != NOT_STARTED;
        }
    }

    /** Immutable, bounded exception diagnostics; no mutable {@link Throwable} escapes a worker. */
    public record Failure(String type, String message, List<StackTraceElement> stackTrace) {
        public Failure {
            type = Objects.requireNonNull(type, "type");
            message = Objects.requireNonNull(message, "message");
            stackTrace = List.copyOf(Objects.requireNonNull(stackTrace, "stackTrace"));
        }

        private static Failure from(Throwable throwable) {
            StackTraceElement[] trace = throwable.getStackTrace();
            int frameCount = Math.min(trace.length, MAX_FAILURE_STACK_FRAMES);
            List<StackTraceElement> frames = new ArrayList<>(frameCount);
            for (int index = 0; index < frameCount; index++) {
                frames.add(trace[index]);
            }
            return new Failure(
                    throwable.getClass().getName(),
                    boundedMessage(throwable.getMessage()),
                    frames);
        }
    }

    public record Diagnostics(
            boolean accepting,
            int workerCount,
            int activeWorkers,
            int readyJobs,
            int readyCapacity,
            int outstandingJobs,
            int pendingCompletions) {}

    public record ShutdownReport(
            boolean terminated,
            boolean forced,
            boolean callerInterrupted,
            int neverStartedExecutorTasks) {}

    public record Config(
            int workerCount,
            int readyCapacity,
            String threadNamePrefix,
            boolean daemonThreads,
            Duration gracefulShutdownTimeout,
            Duration forcedShutdownTimeout) {
        public Config {
            if (workerCount < 1) {
                throw new IllegalArgumentException("workerCount must be positive");
            }
            if (readyCapacity < 1) {
                throw new IllegalArgumentException("readyCapacity must be positive");
            }
            threadNamePrefix = Objects.requireNonNull(threadNamePrefix, "threadNamePrefix");
            if (threadNamePrefix.isBlank()) {
                throw new IllegalArgumentException("threadNamePrefix must not be blank");
            }
            Objects.requireNonNull(gracefulShutdownTimeout, "gracefulShutdownTimeout");
            Objects.requireNonNull(forcedShutdownTimeout, "forcedShutdownTimeout");
            if (gracefulShutdownTimeout.isNegative() || forcedShutdownTimeout.isNegative()) {
                throw new IllegalArgumentException("shutdown timeouts must not be negative");
            }
        }

        public static Config defaults() {
            return new Config(
                    DEFAULT_WORKER_COUNT,
                    DEFAULT_READY_CAPACITY,
                    "createcheme-cpu-solve-",
                    true,
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1));
        }
    }

    private enum DispatchResult {
        DISPATCHED,
        TEMPORARILY_UNAVAILABLE,
        STOPPED
    }

    private final class JobControl {
        private final JobStamp<O> stamp;
        private final S snapshot;
        private final SolveKernel<S, R> kernel;
        private final long enqueuedNanos;
        private final AtomicReference<Object> state = new AtomicReference<>(ACTIVE);
        private final AtomicReference<Thread> runner = new AtomicReference<>();
        private final Token token = new Token();

        private long startedNanos = NOT_STARTED;
        private boolean dispatched;

        private JobControl(JobStamp<O> stamp, S snapshot, SolveKernel<S, R> kernel, long enqueuedNanos) {
            this.stamp = stamp;
            this.snapshot = snapshot;
            this.kernel = kernel;
            this.enqueuedNanos = enqueuedNanos;
        }

        private CancellationResult requestCancellation(CancelReason reason) {
            while (true) {
                Object current = state.get();
                if (current == TERMINAL) {
                    return CancellationResult.ALREADY_TERMINAL;
                }
                if (current instanceof CancelReason) {
                    return CancellationResult.ALREADY_REQUESTED;
                }
                if (state.compareAndSet(ACTIVE, reason)) {
                    return CancellationResult.REQUESTED;
                }
            }
        }

        private boolean isTerminal() {
            return state.get() == TERMINAL;
        }

        private void publish(Completion<O, R> desiredCompletion) {
            while (true) {
                Object current = state.get();
                if (current == TERMINAL) {
                    return;
                }
                Completion<O, R> actualCompletion = current instanceof CancelReason reason
                        ? cancellationCompletion(this, reason, nanoTime.getAsLong())
                        : desiredCompletion;
                if (state.compareAndSet(current, TERMINAL)) {
                    completions.add(new CompletedJob(this, actualCompletion));
                    return;
                }
            }
        }

        private final class Token implements CancellationToken {
            @Override
            public long deadlineNanos() {
                return stamp.deadlineNanos();
            }

            @Override
            public boolean isDeadlineExceeded() {
                return deadlineReached(nanoTime.getAsLong(), stamp.deadlineNanos());
            }

            @Override
            public boolean isCancellationRequested() {
                return state.get() instanceof CancelReason
                        || isDeadlineExceeded()
                        || Thread.currentThread().isInterrupted();
            }

            @Override
            public void throwIfCancellationRequested() {
                Object current = state.get();
                if (current instanceof CancelReason reason) {
                    throw new SolveCancelledException(reason);
                }
                if (isDeadlineExceeded()) {
                    throw new SolveDeadlineExceededException();
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw new SolveCancelledException(CancelReason.INTERRUPTED);
                }
            }
        }
    }

    private final class WorkerTask implements Runnable {
        private final JobControl control;

        private WorkerTask(JobControl control) {
            this.control = control;
        }

        @Override
        public void run() {
            Thread worker = Thread.currentThread();
            control.runner.set(worker);
            control.startedNanos = nanoTime.getAsLong();
            Completion<O, R> completion;
            Error fatalError = null;
            try {
                control.token.throwIfCancellationRequested();
                R result = Objects.requireNonNull(
                        control.kernel.solve(control.snapshot, control.token),
                        "Solve kernel returned null");
                control.token.throwIfCancellationRequested();
                completion = successCompletion(control, result, nanoTime.getAsLong());
            } catch (SolveDeadlineExceededException exception) {
                completion = deadlineCompletion(control, nanoTime.getAsLong());
            } catch (SolveCancelledException exception) {
                completion = cancellationCompletion(control, exception.reason(), nanoTime.getAsLong());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                Object state = control.state.get();
                CancelReason reason = state instanceof CancelReason cancelReason
                        ? cancelReason
                        : CancelReason.INTERRUPTED;
                completion = cancellationCompletion(control, reason, nanoTime.getAsLong());
            } catch (Throwable throwable) {
                completion = failureCompletion(control, throwable, nanoTime.getAsLong());
                if (throwable instanceof Error error) {
                    fatalError = error;
                }
            } finally {
                control.runner.compareAndSet(worker, null);
            }
            control.publish(completion);
            if (fatalError != null) {
                throw fatalError;
            }
        }

        private void cancelBeforeRun() {
            control.requestCancellation(CancelReason.SHUTDOWN);
            control.publish(cancellationCompletion(control, CancelReason.SHUTDOWN, nanoTime.getAsLong()));
        }
    }

    private final class CompletedJob {
        private final JobControl control;
        private final Completion<O, R> completion;

        private CompletedJob(JobControl control, Completion<O, R> completion) {
            this.control = control;
            this.completion = completion;
        }
    }
}
