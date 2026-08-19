package com.wormzjl.createcheme.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.createcheme.runtime.BoundedCpuSolveService.Admission;
import com.wormzjl.createcheme.runtime.BoundedCpuSolveService.CancelReason;
import com.wormzjl.createcheme.runtime.BoundedCpuSolveService.CancellationResult;
import com.wormzjl.createcheme.runtime.BoundedCpuSolveService.Completion;
import com.wormzjl.createcheme.runtime.BoundedCpuSolveService.Config;
import com.wormzjl.createcheme.runtime.BoundedCpuSolveService.JobStamp;
import com.wormzjl.createcheme.runtime.BoundedCpuSolveService.ShutdownReport;
import com.wormzjl.createcheme.runtime.BoundedCpuSolveService.TerminalStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BoundedCpuSolveServiceTest {
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(3);

    @Test
    void saturatesWithoutCallerRunsAndKeepsOneOutstandingJobPerOwner() throws Exception {
        Config config = testConfig(1, 1, true);
        Thread caller = Thread.currentThread();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicReference<Thread> workerThread = new AtomicReference<>();
        JobStamp<Owner> firstStamp = stamp(7L, 1L, "first");
        JobStamp<Owner> secondStamp = stamp(7L, 2L, "second");

        try (BoundedCpuSolveService<Owner, Snapshot, SolveResult> service =
                new BoundedCpuSolveService<>(7L, config)) {
            assertEquals(Admission.ACCEPTED, service.trySubmit(
                    firstStamp,
                    new Snapshot("first"),
                    (snapshot, token) -> {
                        workerThread.set(Thread.currentThread());
                        firstStarted.countDown();
                        releaseFirst.await();
                        return new SolveResult(snapshot.value());
                    }));
            await(firstStarted);

            assertNotSame(caller, workerThread.get());
            assertTrue(workerThread.get().getName().startsWith("test-createcheme-solve-"));
            assertTrue(workerThread.get().isDaemon());
            assertEquals(Admission.OWNER_BUSY, service.trySubmit(
                    stamp(7L, 99L, "first"),
                    new Snapshot("duplicate"),
                    (snapshot, token) -> new SolveResult(snapshot.value())));
            assertEquals(Admission.ACCEPTED, service.trySubmit(
                    secondStamp,
                    new Snapshot("second"),
                    (snapshot, token) -> new SolveResult(snapshot.value())));
            assertEquals(Admission.QUEUE_FULL, service.trySubmit(
                    stamp(7L, 3L, "third"),
                    new Snapshot("third"),
                    (snapshot, token) -> new SolveResult(snapshot.value())));
            assertEquals(1, service.diagnostics().activeWorkers());
            assertEquals(1, service.diagnostics().readyJobs());
            assertEquals(2, service.diagnostics().outstandingJobs());

            releaseFirst.countDown();
            List<Completion<Owner, SolveResult>> completions = awaitCompletions(service, 2);
            assertEquals(List.of(firstStamp, secondStamp),
                    completions.stream().map(Completion::stamp).toList());
            assertTrue(completions.stream().allMatch(result -> result.status() == TerminalStatus.SUCCESS));
            assertEquals(0, service.diagnostics().outstandingJobs());

            assertEquals(Admission.ACCEPTED, service.trySubmit(
                    stamp(7L, 4L, "first"),
                    new Snapshot("owner-reused-after-drain"),
                    (snapshot, token) -> new SolveResult(snapshot.value())));
            assertEquals(1, awaitCompletions(service, 1).size());
        }
    }

    @Test
    void publishesSuccessfulResultsAndObservableWorkerFailuresWithTheirFullStamps() throws Exception {
        CountDownLatch successRan = new CountDownLatch(1);
        CountDownLatch failureRan = new CountDownLatch(1);
        JobStamp<Owner> successStamp = new JobStamp<>(
                12L, 40L, new Owner("success"), 8L, "dataset-v4", Long.MAX_VALUE);
        JobStamp<Owner> failureStamp = new JobStamp<>(
                12L, 41L, new Owner("failure"), 9L, "dataset-v5", Long.MAX_VALUE);

        try (BoundedCpuSolveService<Owner, Snapshot, SolveResult> service =
                new BoundedCpuSolveService<>(12L, testConfig(1, 1, true))) {
            assertEquals(Admission.ACCEPTED, service.trySubmit(
                    successStamp,
                    new Snapshot("ok"),
                    (snapshot, token) -> {
                        successRan.countDown();
                        return new SolveResult(snapshot.value());
                    }));
            assertEquals(Admission.ACCEPTED, service.trySubmit(
                    failureStamp,
                    new Snapshot("bad"),
                    (snapshot, token) -> {
                        failureRan.countDown();
                        throw new IllegalStateException("deterministic failure");
                    }));

            Completion<Owner, SolveResult> success = awaitCompletions(service, 1).getFirst();
            assertEquals(0L, successRan.getCount());
            assertEquals(successStamp, success.stamp());
            assertEquals(TerminalStatus.SUCCESS, success.status());
            assertEquals(new SolveResult("ok"), success.result().orElseThrow());
            assertTrue(success.failure().isEmpty());
            assertTrue(success.started());

            Completion<Owner, SolveResult> failure = awaitCompletions(service, 1).getFirst();
            assertEquals(0L, failureRan.getCount());
            assertEquals(failureStamp, failure.stamp());
            assertEquals(TerminalStatus.FAILED, failure.status());
            assertTrue(failure.result().isEmpty());
            assertEquals(IllegalStateException.class.getName(), failure.failure().orElseThrow().type());
            assertEquals("deterministic failure", failure.failure().orElseThrow().message());
            assertFalse(failure.failure().orElseThrow().stackTrace().isEmpty());
            assertTrue(service.lastUncaughtWorkerFailure().isEmpty());
        }
    }

    @Test
    void queuedCancellationIsImmediateAndExactlyOnce() throws Exception {
        CountDownLatch runningStarted = new CountDownLatch(1);
        CountDownLatch releaseRunning = new CountDownLatch(1);
        JobStamp<Owner> runningStamp = stamp(20L, 1L, "running");
        JobStamp<Owner> queuedStamp = stamp(20L, 2L, "queued");

        try (BoundedCpuSolveService<Owner, Snapshot, SolveResult> service =
                new BoundedCpuSolveService<>(20L, testConfig(1, 1, true))) {
            assertEquals(Admission.ACCEPTED, service.trySubmit(
                    runningStamp,
                    new Snapshot("running"),
                    (snapshot, token) -> {
                        runningStarted.countDown();
                        releaseRunning.await();
                        return new SolveResult(snapshot.value());
                    }));
            await(runningStarted);
            assertEquals(Admission.ACCEPTED, service.trySubmit(
                    queuedStamp,
                    new Snapshot("must-not-run"),
                    (snapshot, token) -> new SolveResult(snapshot.value())));

            assertEquals(CancellationResult.REQUESTED,
                    service.cancel(queuedStamp, CancelReason.OWNER_UNLOADED));
            assertEquals(CancellationResult.ALREADY_TERMINAL,
                    service.cancel(queuedStamp, CancelReason.OWNER_UNLOADED));
            Completion<Owner, SolveResult> cancelled = service.drainCompletions(10).getFirst();
            assertEquals(queuedStamp, cancelled.stamp());
            assertEquals(TerminalStatus.CANCELLED, cancelled.status());
            assertEquals("OWNER_UNLOADED", cancelled.detail());
            assertFalse(cancelled.started());
            assertTrue(service.drainCompletions(10).isEmpty());
            assertEquals(CancellationResult.NOT_FOUND,
                    service.cancel(queuedStamp, CancelReason.OWNER_REQUEST));

            releaseRunning.countDown();
            assertEquals(TerminalStatus.SUCCESS, awaitCompletions(service, 1).getFirst().status());
        }
    }

    @Test
    void runningCancellationInterruptsTheWorkerAndCannotPublishLateSuccess() throws Exception {
        CountDownLatch runningStarted = new CountDownLatch(1);
        CountDownLatch workerObservedInterrupt = new CountDownLatch(1);
        CountDownLatch neverReleasedNormally = new CountDownLatch(1);
        JobStamp<Owner> runningStamp = stamp(30L, 1L, "running");

        try (BoundedCpuSolveService<Owner, Snapshot, SolveResult> service =
                new BoundedCpuSolveService<>(30L, testConfig(1, 1, true))) {
            assertEquals(Admission.ACCEPTED, service.trySubmit(
                    runningStamp,
                    new Snapshot("running"),
                    (snapshot, token) -> {
                        runningStarted.countDown();
                        try {
                            neverReleasedNormally.await();
                            return new SolveResult("late-success");
                        } catch (InterruptedException exception) {
                            workerObservedInterrupt.countDown();
                            throw exception;
                        }
                    }));
            await(runningStarted);

            assertEquals(CancellationResult.REQUESTED,
                    service.cancel(runningStamp, CancelReason.OWNER_REQUEST));
            await(workerObservedInterrupt);
            Completion<Owner, SolveResult> cancelled = awaitCompletions(service, 1).getFirst();
            assertEquals(TerminalStatus.CANCELLED, cancelled.status());
            assertEquals("OWNER_REQUEST", cancelled.detail());
            assertTrue(cancelled.result().isEmpty());
            assertTrue(service.drainCompletions(10).isEmpty());
            assertEquals(CancellationResult.NOT_FOUND,
                    service.cancel(runningStamp, CancelReason.OWNER_REQUEST));
        }
    }

    @Test
    void staleEpochIsRejectedAndADeadlineIsCooperativelyObserved() throws Exception {
        AtomicLong clock = new AtomicLong(100L);
        AtomicInteger staleKernelRuns = new AtomicInteger();
        CountDownLatch deadlineKernelStarted = new CountDownLatch(1);
        CountDownLatch continueDeadlineKernel = new CountDownLatch(1);
        JobStamp<Owner> staleStamp = new JobStamp<>(
                99L, 1L, new Owner("stale"), 3L, "old-data", Long.MAX_VALUE);
        JobStamp<Owner> deadlineStamp = new JobStamp<>(
                40L, 2L, new Owner("deadline"), 4L, "current-data", 150L);

        try (BoundedCpuSolveService<Owner, Snapshot, SolveResult> service =
                new BoundedCpuSolveService<>(40L, testConfig(1, 1, true), clock::get)) {
            assertEquals(Admission.STALE_EPOCH, service.trySubmit(
                    staleStamp,
                    new Snapshot("stale"),
                    (snapshot, token) -> {
                        staleKernelRuns.incrementAndGet();
                        return new SolveResult(snapshot.value());
                    }));
            assertEquals(0, staleKernelRuns.get());

            assertEquals(Admission.ACCEPTED, service.trySubmit(
                    deadlineStamp,
                    new Snapshot("deadline"),
                    (snapshot, token) -> {
                        deadlineKernelStarted.countDown();
                        continueDeadlineKernel.await();
                        token.throwIfCancellationRequested();
                        return new SolveResult("must-not-complete");
                    }));
            await(deadlineKernelStarted);
            clock.set(150L);
            continueDeadlineKernel.countDown();

            Completion<Owner, SolveResult> expired = awaitCompletions(service, 1).getFirst();
            assertEquals(deadlineStamp, expired.stamp());
            assertEquals(TerminalStatus.DEADLINE_EXCEEDED, expired.status());
            assertTrue(expired.result().isEmpty());
        }
    }

    @Test
    void expiredQueuedJobNeverConsumesAWorkerSlot() throws Exception {
        AtomicLong clock = new AtomicLong(0L);
        AtomicInteger expiredKernelRuns = new AtomicInteger();
        CountDownLatch runningStarted = new CountDownLatch(1);
        CountDownLatch releaseRunning = new CountDownLatch(1);
        JobStamp<Owner> runningStamp = new JobStamp<>(
                50L, 1L, new Owner("running"), 1L, "data", Long.MAX_VALUE);
        JobStamp<Owner> expiringStamp = new JobStamp<>(
                50L, 2L, new Owner("expiring"), 1L, "data", 10L);

        try (BoundedCpuSolveService<Owner, Snapshot, SolveResult> service =
                new BoundedCpuSolveService<>(50L, testConfig(1, 1, true), clock::get)) {
            assertEquals(Admission.ACCEPTED, service.trySubmit(
                    runningStamp,
                    new Snapshot("running"),
                    (snapshot, token) -> {
                        runningStarted.countDown();
                        releaseRunning.await();
                        return new SolveResult(snapshot.value());
                    }));
            await(runningStarted);
            assertEquals(Admission.ACCEPTED, service.trySubmit(
                    expiringStamp,
                    new Snapshot("expired"),
                    (snapshot, token) -> {
                        expiredKernelRuns.incrementAndGet();
                        return new SolveResult(snapshot.value());
                    }));

            clock.set(10L);
            Completion<Owner, SolveResult> expired = service.drainCompletions(10).getFirst();
            assertEquals(expiringStamp, expired.stamp());
            assertEquals(TerminalStatus.DEADLINE_EXCEEDED, expired.status());
            assertFalse(expired.started());
            assertEquals(0, expiredKernelRuns.get());

            releaseRunning.countDown();
            assertEquals(TerminalStatus.SUCCESS, awaitCompletions(service, 1).getFirst().status());
        }
    }

    @Test
    void twoPhaseShutdownTerminalizesReadyWorkInterruptsRunningWorkAndLeaksNoWorker()
            throws Exception {
        CountDownLatch runningStarted = new CountDownLatch(1);
        CountDownLatch interruptObserved = new CountDownLatch(1);
        CountDownLatch neverReleasedNormally = new CountDownLatch(1);
        AtomicReference<Thread> workerThread = new AtomicReference<>();
        JobStamp<Owner> runningStamp = stamp(60L, 1L, "running");
        JobStamp<Owner> queuedStamp = stamp(60L, 2L, "queued");
        Config config = testConfig(1, 1, false);
        BoundedCpuSolveService<Owner, Snapshot, SolveResult> service =
                new BoundedCpuSolveService<>(60L, config);
        try {
            assertEquals(Admission.ACCEPTED, service.trySubmit(
                    runningStamp,
                    new Snapshot("running"),
                    (snapshot, token) -> {
                        workerThread.set(Thread.currentThread());
                        runningStarted.countDown();
                        try {
                            neverReleasedNormally.await();
                            return new SolveResult("late-success");
                        } catch (InterruptedException exception) {
                            interruptObserved.countDown();
                            throw exception;
                        }
                    }));
            await(runningStarted);
            assertEquals(Admission.ACCEPTED, service.trySubmit(
                    queuedStamp,
                    new Snapshot("queued"),
                    (snapshot, token) -> new SolveResult("must-not-run")));

            ShutdownReport report = service.shutdown(Duration.ZERO, TEST_TIMEOUT);
            assertTrue(report.forced());
            assertTrue(report.terminated());
            assertFalse(report.callerInterrupted());
            await(interruptObserved);
            assertTrue(service.isTerminated());
            assertFalse(workerThread.get().isAlive());
            assertEquals(Admission.STOPPING, service.trySubmit(
                    stamp(60L, 3L, "later"),
                    new Snapshot("later"),
                    (snapshot, token) -> new SolveResult(snapshot.value())));

            List<Completion<Owner, SolveResult>> completions = service.drainCompletions(10);
            assertEquals(2, completions.size());
            assertTrue(completions.stream().allMatch(result -> result.status() == TerminalStatus.SHUTDOWN));
            assertEquals(0, service.diagnostics().outstandingJobs());
        } finally {
            service.close();
        }
    }

    private static Config testConfig(int workerCount, int readyCapacity, boolean daemonThreads) {
        return new Config(
                workerCount,
                readyCapacity,
                "test-createcheme-solve-",
                daemonThreads,
                Duration.ZERO,
                TEST_TIMEOUT);
    }

    private static JobStamp<Owner> stamp(long epoch, long sequence, String owner) {
        return new JobStamp<>(
                epoch,
                sequence,
                new Owner(owner),
                sequence,
                "dataset-v1",
                BoundedCpuSolveService.NO_DEADLINE);
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(TEST_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS),
                "Timed out waiting for the coordinated worker state");
    }

    private static List<Completion<Owner, SolveResult>> awaitCompletions(
            BoundedCpuSolveService<Owner, Snapshot, SolveResult> service, int expected) {
        List<Completion<Owner, SolveResult>> results = new ArrayList<>(expected);
        long deadline = System.nanoTime() + TEST_TIMEOUT.toNanos();
        while (results.size() < expected && System.nanoTime() - deadline < 0L) {
            results.addAll(service.drainCompletions(expected - results.size()));
            if (results.size() < expected) {
                Thread.onSpinWait();
            }
        }
        assertEquals(expected, results.size(), "Timed out waiting for terminal completions");
        return List.copyOf(results);
    }

    private record Owner(String value) {}

    private record Snapshot(String value) {}

    private record SolveResult(String value) {}
}
