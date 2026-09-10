package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Offline concurrency check; never run alongside the teacher campaign or include it in latency statistics. */
public final class V3GeneralParallelCheck {
    private static final int WORKERS = 10;
    private static final int MAXIMUM_IN_FLIGHT = WORKERS * 2;
    private static final int STRESS_TASKS = 25_000;
    private static final long SOLVE_DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static final V3NeuralInitializer FORBIDDEN_NEURAL = new V3NeuralInitializer() {
        @Override public String modelId() { throw new AssertionError("CURRENT_ONLY inspected a neural model"); }
        @Override public Optional<V3NeuralSeed> predict(V3ColumnInput input, V3SolveControl control) {
            throw new AssertionError("CURRENT_ONLY invoked neural prediction");
        }
    };

    private V3GeneralParallelCheck() {}

    /** Usage: report-json [accepted-teacher-jsonl]. The optional journal supplies ten accepted 2..6-stage cases. */
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) throw new IllegalArgumentException("report-json [accepted-teacher-jsonl]");
        Path report = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(report.getParent());
        Map<String, Object> results = new LinkedHashMap<>();
        results.put("workers", WORKERS);
        results.put("java", System.getProperty("java.version"));
        results.put("allPassed", false);
        try {
            results.put("scheduling", schedulingCheck());
            results.put("failureCancellation", failureCancellationCheck());
            Map<String, Object> numerical = numericalCheck(args.length == 2 ? Path.of(args[1]) : null);
            results.put("numerical", numerical);
            require(Boolean.TRUE.equals(numerical.get("passed")), "Serial and parallel numerical results differ; see case metrics");
            results.put("allPassed", true);
        } catch (Exception | AssertionError failure) {
            results.put("failure", failure.toString());
            throw failure;
        } finally {
            Files.writeString(report, new GsonBuilder().setPrettyPrinting().create().toJson(results));
            System.out.println("Parallel check allPassed=" + results.get("allPassed") + " report=" + report);
        }
    }

    /** Exercises the same bounded completion-service refill used by the teacher runner. */
    public static Map<String, Object> schedulingCheck() throws Exception {
        ThreadPoolExecutor executor = executor("stress");
        ExecutorCompletionService<Integer> completed = new ExecutorCompletionService<>(executor);
        Set<Future<Integer>> pending = new HashSet<>();
        boolean[] seen = new boolean[STRESS_TASKS];
        int submitted = 0, finished = 0, maximumPending = 0;
        try {
            while (finished < STRESS_TASKS) {
                while (submitted < STRESS_TASKS && pending.size() < MAXIMUM_IN_FLIGHT) {
                    int id = submitted++;
                    pending.add(completed.submit(() -> id));
                    maximumPending = Math.max(maximumPending, pending.size());
                }
                Future<Integer> future = completed.poll(10, TimeUnit.SECONDS);
                require(future != null, "No scheduling completion within ten seconds");
                require(pending.remove(future), "Completion was not an owned pending future");
                int id = future.get();
                require(id >= 0 && id < seen.length && !seen[id], "Duplicate or foreign completion: " + id);
                seen[id] = true;
                finished++;
            }
        } finally {
            close(executor, pending);
        }
        require(finished == STRESS_TASKS && pending.isEmpty(), "Scheduling check lost a completion");
        require(maximumPending <= MAXIMUM_IN_FLIGHT, "In-flight limit exceeded");
        return Map.of("passed", true, "submitted", submitted, "observedCompletions", finished,
                "maximumInFlight", maximumPending, "queueCapacity", MAXIMUM_IN_FLIGHT,
                "executorTerminated", executor.isTerminated());
    }

    /** A worker exception must be observed; both queued and executing siblings must become terminal on abort. */
    public static Map<String, Object> failureCancellationCheck() throws Exception {
        ThreadPoolExecutor executor = executor("cancellation");
        ExecutorCompletionService<Integer> completed = new ExecutorCompletionService<>(executor);
        List<Future<Integer>> owned = new ArrayList<>();
        CountDownLatch running = new CountDownLatch(WORKERS);
        CountDownLatch fail = new CountDownLatch(1);
        CountDownLatch blocked = new CountDownLatch(1);
        Future<Integer> failed = null;
        int cancelled = 0;
        try {
            for (int index = 0; index < WORKERS; index++) {
                int id = index;
                owned.add(completed.submit(() -> {
                    running.countDown();
                    if (id == 0) { fail.await(); throw new InjectedWorkerFailure(); }
                    blocked.await();
                    return id;
                }));
            }
            require(running.await(10, TimeUnit.SECONDS), "Ten cancellation workers did not start");
            for (int index = WORKERS; index < MAXIMUM_IN_FLIGHT; index++) {
                int id = index;
                owned.add(completed.submit(() -> { blocked.await(); return id; }));
            }
            require(executor.getQueue().size() == WORKERS, "Expected ten queued siblings before injected failure");
            fail.countDown();
            failed = completed.poll(10, TimeUnit.SECONDS);
            require(failed != null, "Injected worker failure did not complete");
            try { failed.get(); throw new AssertionError("Injected failure was converted into success"); }
            catch (ExecutionException expected) {
                require(expected.getCause() instanceof InjectedWorkerFailure, "Wrong worker exception: " + expected.getCause());
            }
        } finally {
            close(executor, owned);
        }
        // Observe every returned future, including those whose ECS wrapper never reached a worker.
        for (Future<Integer> future : owned) {
            require(future.isDone(), "Owned future remained pending after shutdown");
            try { future.get(); throw new AssertionError("Blocked sibling unexpectedly completed normally"); }
            catch (CancellationException expected) { cancelled++; }
            catch (ExecutionException expected) {
                require(future == failed && expected.getCause() instanceof InjectedWorkerFailure,
                        "Unobserved or unexpected worker failure");
            }
        }
        require(cancelled == MAXIMUM_IN_FLIGHT - 1, "Not every sibling was cancelled");
        return Map.of("passed", true, "ownedFutures", owned.size(), "observedFailures", 1,
                "observedCancellations", cancelled, "executorTerminated", executor.isTerminated());
    }

    /** Compares each native accepted serial profile with a barrier-started CURRENT_ONLY replay. */
    public static Map<String, Object> numericalCheck(Path acceptedJournal) throws Exception {
        List<Case> cases = acceptedJournal == null ? fixtures() : journalCases(acceptedJournal);
        List<Accepted> serial = new ArrayList<>();
        for (Case testCase : cases) serial.add(solve(testCase, null));

        ThreadPoolExecutor executor = executor("solve");
        List<Future<Accepted>> futures = new ArrayList<>();
        CountDownLatch insideSolver = new CountDownLatch(WORKERS);
        CountDownLatch releaseSolver = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger(), maximumActive = new AtomicInteger();
        Set<Long> threadIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
        List<Map<String, Object>> comparisons = new ArrayList<>();
        try {
            for (Case testCase : cases) futures.add(executor.submit(() -> {
                threadIds.add(Thread.currentThread().threadId());
                maximumActive.accumulateAndGet(active.incrementAndGet(), Math::max);
                boolean[] firstCheckpoint = {true};
                V3SolveControl entry = () -> {
                    if (!firstCheckpoint[0]) return;
                    firstCheckpoint[0] = false;
                    insideSolver.countDown();
                    try { require(releaseSolver.await(10, TimeUnit.SECONDS), "Solver entry barrier was not released"); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new CancellationException("Parallel check entry interrupted");
                    }
                };
                try { return solve(testCase, entry); }
                finally { active.decrementAndGet(); }
            }));
            require(insideSolver.await(10, TimeUnit.SECONDS), "Ten calls did not reach the production solver checkpoint together");
            require(active.get() == WORKERS, "Fewer than ten solver calls are simultaneously active");
            releaseSolver.countDown();
            for (int index = 0; index < futures.size(); index++) {
                Accepted parallel = futures.get(index).get(65, TimeUnit.SECONDS);
                Map<String, Object> comparison = compare(cases.get(index), serial.get(index), parallel);
                comparisons.add(comparison);
            }
        } finally {
            releaseSolver.countDown();
            close(executor, futures);
        }
        require(threadIds.size() == WORKERS && maximumActive.get() == WORKERS, "Not ten independent concurrent worker threads");
        boolean passed = comparisons.stream().allMatch(comparison -> Boolean.TRUE.equals(comparison.get("passed")));
        return Map.of("passed", passed, "source", acceptedJournal == null ? "CDU17 binary contract variants" : acceptedJournal.toString(),
                "cases", comparisons, "distinctWorkerThreads", threadIds.size(), "maximumActiveSolverCalls", maximumActive.get(),
                "comparisonGate", "bit-identical accepted profiles, phase masks, audits, closure evidence and input digests",
                "executorTerminated", executor.isTerminated());
    }

    private static Accepted solve(Case testCase, V3SolveControl entry) {
        long start = System.nanoTime();
        V3NeuralSeed[] accepted = {null};
        V3SolveControl control = () -> {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("Parallel check worker interrupted");
            if (entry != null) entry.checkpoint();
            if (System.nanoTime() - start >= SOLVE_DEADLINE_NANOS) throw new CancellationException("Parallel check solve exceeded 60 seconds");
        };
        V3ColumnOutcome outcome = V3ColumnCalculator.calculateWithAcceptedProfile(testCase.input(), control,
                V3InitializationOptions.CURRENT, FORBIDDEN_NEURAL, profile -> accepted[0] = profile);
        require(outcome instanceof V3ColumnOutcome.Success, testCase.id() + " did not converge: " + outcome);
        require(accepted[0] != null, testCase.id() + " did not export the accepted profile");
        V3ColumnOutcome.Success success = (V3ColumnOutcome.Success) outcome;
        require(success.result().acceptanceAudit().accepted() && success.result().convergenceEvidence().satisfiesGates(),
                testCase.id() + " failed native acceptance gates");
        return new Accepted(accepted[0], success.result(), (System.nanoTime() - start) / 1e6);
    }

    private static Map<String, Object> compare(Case testCase, Accepted serial, Accepted parallel) {
        V3NeuralSeed a = serial.seed(), b = parallel.seed();
        boolean sameProvenance = a.input().equals(b.input()) && a.propertyRevision().equals(b.propertyRevision());
        boolean samePhases = a.branch() == b.branch() && Arrays.equals(a.wetTrays(), b.wetTrays());
        Difference temperature = difference(a.temperatures(), b.temperatures());
        Difference water = difference(a.freeWater(), b.freeWater());
        Difference liquid = difference(a.liquid(), b.liquid()), vapor = difference(a.vapor(), b.vapor());
        int bitDifferences = temperature.bitDifferences() + water.bitDifferences() + liquid.bitDifferences() + vapor.bitDifferences();
        boolean sameDigest = serial.result().inputDigest().equals(parallel.result().inputDigest());
        boolean sameAudit = serial.result().acceptanceAudit().equals(parallel.result().acceptanceAudit());
        boolean sameClosure = serial.result().convergenceEvidence().equals(parallel.result().convergenceEvidence());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", testCase.id()); result.put("input", testCase.input());
        result.put("passed", sameProvenance && samePhases && sameDigest && sameAudit && sameClosure && bitDifferences == 0);
        result.put("sameProvenance", sameProvenance); result.put("samePhaseBranchAndWetMask", samePhases);
        result.put("sameInputDigest", sameDigest); result.put("sameNativeAudit", sameAudit); result.put("sameClosureEvidence", sameClosure);
        result.put("serialMillis", serial.millis()); result.put("parallelMillis", parallel.millis());
        result.put("branch", a.branch().name()); result.put("differingProfileValues", bitDifferences);
        result.put("maximumTemperatureDifferenceKelvin", temperature.maximumAbsolute());
        result.put("maximumLiquidFlowDifferenceMolPerSecond", liquid.maximumAbsolute());
        result.put("maximumVaporFlowDifferenceMolPerSecond", vapor.maximumAbsolute());
        result.put("maximumFreeWaterDifferenceMolPerSecond", water.maximumAbsolute());
        result.put("inputDigest", serial.result().inputDigest().hexadecimalSha256());
        return result;
    }

    private static Difference difference(double[][] a, double[][] b) {
        require(a.length == b.length, "Profile node count changed");
        double maximum = 0; int bits = 0;
        for (int row = 0; row < a.length; row++) {
            Difference difference = difference(a[row], b[row]);
            maximum = Math.max(maximum, difference.maximumAbsolute()); bits += difference.bitDifferences();
        }
        return new Difference(maximum, bits);
    }

    private static Difference difference(double[] a, double[] b) {
        require(a.length == b.length, "Profile vector length changed");
        double maximum = 0; int bits = 0;
        for (int index = 0; index < a.length; index++) {
            require(Double.isFinite(a[index]) && Double.isFinite(b[index]), "Nonfinite accepted profile");
            maximum = Math.max(maximum, Math.abs(a[index] - b[index]));
            if (Double.doubleToLongBits(a[index]) != Double.doubleToLongBits(b[index])) bits++;
        }
        return new Difference(maximum, bits);
    }

    private static List<Case> journalCases(Path journal) throws Exception {
        List<Case> candidates = new ArrayList<>();
        try (var lines = Files.lines(journal)) {
            for (String line : (Iterable<String>) lines.filter(value -> !value.isBlank())::iterator) {
                JsonObject row = JsonParser.parseString(line).getAsJsonObject();
                if (!row.has("success") || !row.get("success").getAsBoolean()) continue;
                V3ColumnInput input = V3NeuralMvpProbe.input(row.getAsJsonObject("input"));
                if (input.stageCount() >= 2 && input.stageCount() <= 6 && input.componentBasis().componentCount() == 20)
                    candidates.add(new Case(row.get("id").getAsString(), input));
            }
        }
        // Stable selection does not depend on completion order or contention-sensitive measured solve times.
        candidates.sort(Comparator.comparingInt((Case value) -> value.input().stageCount()).thenComparing(Case::id));
        List<Case> selected = new ArrayList<>();
        Set<V3ColumnInput> seen = new HashSet<>();
        for (Case candidate : candidates) if (seen.add(candidate.input())) {
            selected.add(candidate);
            if (selected.size() == WORKERS) break;
        }
        require(selected.size() == WORKERS, "Journal needs ten distinct accepted 2..6-stage cases on the 20-component basis");
        return List.copyOf(selected);
    }

    private static List<Case> fixtures() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        List<Case> result = new ArrayList<>();
        for (int index = 0; index < WORKERS; index++) {
            double[] feed = new double[thermo.componentBasis().componentCount()];
            feed[6] = 48.0 + index * 0.4; feed[13] = 100.0 - feed[6];
            V3ColumnInput input = new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, thermo.packageId(), "test:parallel-pr-binary",
                    thermo.componentBasis(), feed, 549.0 + index * 0.2, 2, 1, 248_000.0 + index * 400.0, 750.0,
                    List.of(new V3ColumnSpecification.CondenserOutletTemperature(300.0),
                            new V3ColumnSpecification.OrganicRefluxRatio(2.0), new V3ColumnSpecification.ReboilerDuty(0.0)));
            result.add(new Case("registered-binary-" + index, input));
        }
        return List.copyOf(result);
    }

    private static ThreadPoolExecutor executor(String label) {
        AtomicInteger number = new AtomicInteger();
        // A completion can be observed before the worker leaves its ECS wrapper. The queue can hold the entire
        // logical in-flight bound, so an immediate refill cannot race that transition and reject an owned task.
        return new ThreadPoolExecutor(WORKERS, WORKERS, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAXIMUM_IN_FLIGHT),
                task -> new Thread(task, "v3-parallel-check-" + label + "-" + number.incrementAndGet()),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static void close(ThreadPoolExecutor executor, Iterable<? extends Future<?>> owned) throws InterruptedException {
        for (Future<?> future : owned) if (!future.isDone()) future.cancel(true);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                require(executor.awaitTermination(10, TimeUnit.SECONDS), "Owned executor ignored cancellation");
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private record Case(String id, V3ColumnInput input) {}
    private record Accepted(V3NeuralSeed seed, V3ColumnResult result, double millis) {}
    private record Difference(double maximumAbsolute, int bitDifferences) {}
    private static final class InjectedWorkerFailure extends RuntimeException {}
}
