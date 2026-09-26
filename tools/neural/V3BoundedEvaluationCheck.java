package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Deterministic lifecycle checks for the actual concurrent evaluator's scheduler. */
public final class V3BoundedEvaluationCheck {
    private V3BoundedEvaluationCheck() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("report-json");
        var seen = new HashSet<Integer>();
        var tasks = new ArrayList<Callable<Integer>>();
        CountDownLatch firstTen = new CountDownLatch(10);
        for (int i = 0; i < 25_000; i++) {
            int value = i;
            tasks.add(() -> {
                if (value < 10) {
                    firstTen.countDown();
                    require(firstTen.await(10, TimeUnit.SECONDS), "Scheduling barrier did not fill ten workers");
                }
                return value;
            });
        }
        var scheduling = V3BoundedEvaluation.run(tasks, 10, (value, wait) -> {
            require(wait >= 0 && seen.add(value), "Duplicate result or invalid queue wait");
        });
        require(seen.size() == tasks.size() && scheduling.completed() == tasks.size(), "Lost completion");
        require(scheduling.maximumInFlight() <= 10 && scheduling.maximumActive() <= 10
                && scheduling.distinctWorkerThreads() == 10 && scheduling.terminated(), "Worker bound or lifecycle violation");

        CountDownLatch allRunning = new CountDownLatch(10);
        CountDownLatch stopped = new CountDownLatch(10);
        CountDownLatch block = new CountDownLatch(1);
        var failures = new ArrayList<Callable<Integer>>();
        for (int i = 0; i < 10; i++) {
            int index = i;
            failures.add(() -> {
                allRunning.countDown();
                try {
                    require(allRunning.await(10, TimeUnit.SECONDS), "Ten workers did not start");
                    if (index == 0) throw new IOException("injected worker failure");
                    block.await();
                    return index;
                } finally { stopped.countDown(); }
            });
        }
        try {
            V3BoundedEvaluation.run(failures, 10, (value, wait) -> { throw new AssertionError("Unexpected result"); });
            throw new AssertionError("Worker failure was hidden");
        } catch (ExecutionException expected) {
            require(expected.getCause() instanceof IOException, "Wrong worker failure");
        }
        require(stopped.getCount() == 0, "Siblings survived worker failure");

        CountDownLatch entered = new CountDownLatch(10), exited = new CountDownLatch(10);
        var blocking = new ArrayList<Callable<Integer>>();
        for (int i = 0; i < 10; i++) blocking.add(() -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); return 0; }
            finally { exited.countDown(); }
        });
        AtomicBoolean restored = new AtomicBoolean();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        Thread caller = Thread.ofPlatform().name("v3-evaluation-interruption-check").start(() -> {
            try { V3BoundedEvaluation.run(blocking, 10, (value, wait) -> {}); unexpected.set(new AssertionError("Interruption ignored")); }
            catch (InterruptedException expected) { restored.set(Thread.currentThread().isInterrupted()); }
            catch (Throwable failure) { unexpected.set(failure); }
        });
        require(entered.await(10, TimeUnit.SECONDS), "Interruption workers did not start");
        caller.interrupt(); caller.join(15_000);
        require(!caller.isAlive() && exited.getCount() == 0 && restored.get() && unexpected.get() == null,
                "Caller interruption or worker termination failed: " + unexpected.get());

        shutdownInterruptionCheck();

        AtomicInteger delivered = new AtomicInteger();
        try {
            V3BoundedEvaluation.run(List.of(() -> 1, () -> 2), 10, (value, wait) -> {
                delivered.incrementAndGet(); throw new IOException("injected journal failure");
            });
            throw new AssertionError("Journal failure was hidden");
        } catch (IOException expected) { require(delivered.get() == 1, "Consumer continued after failure"); }
        require(Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.isAlive()
                && t.getName().startsWith("v3-column-evaluation-")), "Leaked owned workers");

        Path output = Path.of(args[0]); Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(Map.of(
                "allPassed", true, "scheduling", scheduling, "workerFailureCancellation", true,
                "callerInterruptionRestored", true, "shutdownInterruptionWaitedForTermination", true,
                "journalFailurePropagation", true, "noWorkerLeaks", true)),
                StandardOpenOption.CREATE_NEW);
        System.out.println("Concurrent evaluation scheduling and lifecycle checks passed: " + output);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void shutdownInterruptionCheck() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), cleanupEntered = new CountDownLatch(1);
        CountDownLatch cleanupRelease = new CountDownLatch(1), firstWait = new CountDownLatch(1);
        CountDownLatch secondWait = new CountDownLatch(1), thirdWait = new CountDownLatch(1);
        AtomicInteger waits = new AtomicInteger();
        var executor = new ThreadPoolExecutor(10,10,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(10),
                Thread.ofPlatform().name("v3-shutdown-check-",0).daemon(false).factory()) {
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
                int count = waits.incrementAndGet();
                if (count == 1) firstWait.countDown();
                if (count == 2) secondWait.countDown();
                if (count == 3) thirdWait.countDown();
                return super.awaitTermination(timeout, unit);
            }
        };
        var cleanupWorker = executor.submit(() -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException cancellation) {
                cleanupEntered.countDown();
                boolean interrupted = true;
                while (cleanupRelease.getCount() > 0) {
                    try { cleanupRelease.await(); }
                    catch (InterruptedException again) { interrupted = true; }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
        });
        AtomicBoolean restored = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread closer = Thread.ofPlatform().name("v3-shutdown-owner-check").start(() -> {
            try { V3BoundedEvaluation.close(executor,false); restored.set(Thread.currentThread().isInterrupted()); }
            catch (Throwable problem) { failure.set(problem); }
        });
        try {
            require(entered.await(10,TimeUnit.SECONDS) && firstWait.await(10,TimeUnit.SECONDS), "Shutdown did not start");
            closer.interrupt();
            require(cleanupEntered.await(10,TimeUnit.SECONDS) && secondWait.await(10,TimeUnit.SECONDS),
                    "Shutdown returned before waiting for cancellation cleanup");
            closer.interrupt();
            require(thirdWait.await(10,TimeUnit.SECONDS), "Second shutdown interruption skipped termination wait");
            require(closer.isAlive() && !executor.isTerminated(), "Owner exited while cleanup was held");
            cleanupRelease.countDown(); closer.join(10_000);
            require(!closer.isAlive() && executor.isTerminated() && restored.get() && failure.get() == null,
                    "Interrupted shutdown did not preserve termination/interrupt contract: " + failure.get());
            cleanupWorker.get(10,TimeUnit.SECONDS);
        } finally {
            cleanupRelease.countDown(); executor.shutdownNow(); closer.interrupt(); closer.join(10_000);
            require(executor.awaitTermination(10,TimeUnit.SECONDS), "Shutdown test leaked workers");
        }
    }
}
