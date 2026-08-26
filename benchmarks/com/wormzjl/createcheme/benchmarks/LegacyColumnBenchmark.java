package com.wormzjl.createcheme.benchmarks;

import com.sun.management.ThreadMXBean;
import com.wormzjl.createcheme.runtime.BoundedCpuSolveService;
import com.wormzjl.createcheme.science.column.ColumnSimulation;
import com.wormzjl.createcheme.science.column.CounterCurrentColumnSolver;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Reproducible baseline measurement for the legacy calculator.
 *
 * <p>The three scopes intentionally do not share a timing result: numerical core invokes the legacy cascade,
 * scientific facade invokes {@link ColumnSimulation#calculate(ColumnSimulation.ColumnInput)}, and the final
 * scope passes the facade through the same bounded-service shape used by the server. This program only observes
 * the legacy implementation; it does not modify its algorithm or acceptance behavior.</p>
 *
 * <p>A {@code NO_CONVERGENCE} facade outcome is a measured diagnostic, not a benchmark failure by itself. The
 * report therefore keeps the core's final residuals, the facade diagnostics, and the executor terminal state
 * separate. In particular, a bounded-service {@code SUCCESS} says only that the worker completed; it never
 * changes a legacy {@code NO_CONVERGENCE} into an accepted column result.</p>
 */
public final class LegacyColumnBenchmark {
    private static final int REPORT_SCHEMA_VERSION = 1;
    private static final ThreadMXBean THREAD_BEAN = allocationBean();
    private static volatile long blackhole;

    private LegacyColumnBenchmark() {}

    public static void main(String[] args) {
        Arguments arguments = Arguments.parse(args);
        if (arguments.selfTest()) {
            runHarnessSelfTest();
            return;
        }

        BenchmarkReport benchmarkReport = BenchmarkReport.running(arguments);
        try {
            printRunHeader(arguments);
            for (ColumnSimulation.ColumnInput input : List.of(case30(), case64())) {
                if (arguments.stages() == 0 || arguments.stages() == input.stageCount()) {
                    CaseReport caseReport = runCase(input, arguments);
                    benchmarkReport.addCase(caseReport);
                    writeReportIfRequested(arguments, benchmarkReport);
                    if (caseReport.integrityFailure() != null) {
                        throw new IllegalStateException(caseReport.integrityFailure());
                    }
                }
            }
            benchmarkReport.complete();
            System.out.printf("blackhole=%d%n", blackhole);
        } catch (RuntimeException | Error exception) {
            benchmarkReport.fail(exception);
            throw exception;
        } finally {
            writeReportIfRequested(arguments, benchmarkReport);
        }
    }

    private static void printRunHeader(Arguments arguments) {
        System.out.printf("benchmark=legacy-column-baseline java=%s vm=%s cpu=%s processors=%d%n",
                System.getProperty("java.version"),
                System.getProperty("java.vm.name"),
                System.getenv().getOrDefault("PROCESSOR_IDENTIFIER", "unknown"),
                Runtime.getRuntime().availableProcessors());
        System.out.printf("samples=%d warmup=%d allocationMethod=%s report=%s jfr=%s%n",
                arguments.samples(), arguments.warmup(), allocationMethod(),
                pathOrNone(arguments.reportPath()), pathOrNone(arguments.jfrPath()));
    }

    private static CaseReport runCase(ColumnSimulation.ColumnInput input, Arguments arguments) {
        ColumnSimulation.ColumnSolveOutcome baseline = facade(input);
        String canonicalInputDigest = legacyInputDigest(input);
        String projectedInputDigest = baseline.result()
                .map(ColumnSimulation.ColumnResult::inputDigest)
                .orElse(null);
        String expectedResultDigest = baseline.result()
                .map(ColumnSimulation.ColumnResult::resultDigest)
                .orElse(null);
        BaselineObservation baselineObservation = BaselineObservation.from(baseline);
        System.out.printf("%nstages=%d inputDigest=%s baselineStatus=%s expectedResultDigest=%s diagnostics=%s%n",
                input.stageCount(), canonicalInputDigest, baseline.status(),
                textOrNone(expectedResultDigest), diagnosticSummary(baselineObservation.diagnostics()));

        String integrityFailure = null;
        if (projectedInputDigest != null && !canonicalInputDigest.equals(projectedInputDigest)) {
            integrityFailure = "Legacy input-digest mirror differs from the projected result input digest";
        }

        for (int index = 0; index < arguments.warmup(); index++) {
            consume(core(input));
            consume(facade(input));
            Sample serviceWarmup = serviceSamples(input, 1).getFirst();
            if (!"SUCCESS".equals(serviceWarmup.executionStatus())) {
                integrityFailure = firstFailure(integrityFailure,
                        "Warm-up bounded-service worker ended " + serviceWarmup.executionStatus()
                                + ": " + serviceWarmup.executionDetail());
            }
            blackhole += serviceWarmup.outcome().metrics().reportedTpCalls()
                    + Objects.hashCode(serviceWarmup.outcome().resultDigest());
        }

        ScopeReport coreScope = report("numerical-core", directSamples(input, arguments.samples(), true),
                expectedResultDigest);
        ScopeReport facadeScope = report("scientific-facade", directSamples(input, arguments.samples(), false),
                expectedResultDigest);
        ScopeReport serviceScope = report("bounded-service queue-to-commit",
                serviceSamples(input, arguments.samples()), expectedResultDigest);

        integrityFailure = firstFailure(integrityFailure, coreScope.integrityFailure());
        integrityFailure = firstFailure(integrityFailure, facadeScope.integrityFailure());
        integrityFailure = firstFailure(integrityFailure, serviceScope.integrityFailure());
        String caseStatus = baseline.hasResult()
                ? "RESULT_AVAILABLE"
                : "NO_RESULT_" + baseline.status().name();
        return new CaseReport(input.stageCount(), canonicalInputDigest, projectedInputDigest,
                expectedResultDigest, caseStatus, baselineObservation,
                List.of(coreScope, facadeScope, serviceScope), integrityFailure);
    }

    private static List<Sample> directSamples(
            ColumnSimulation.ColumnInput input, int sampleCount, boolean coreOnly) {
        List<Sample> samples = new ArrayList<>(sampleCount);
        for (int index = 0; index < sampleCount; index++) {
            long allocationBefore = allocatedBytes();
            long started = System.nanoTime();
            if (coreOnly) {
                CounterCurrentColumnSolver.Result result = core(input);
                long elapsed = System.nanoTime() - started;
                samples.add(new Sample(elapsed, 0L, elapsed, allocatedBytesDelta(allocationBefore),
                        "DIRECT", "", OutcomeObservation.fromCore(result)));
            } else {
                ColumnSimulation.ColumnSolveOutcome result = facade(input);
                long elapsed = System.nanoTime() - started;
                samples.add(new Sample(elapsed, 0L, elapsed, allocatedBytesDelta(allocationBefore),
                        "DIRECT", "", OutcomeObservation.fromFacade(result)));
            }
        }
        return List.copyOf(samples);
    }

    private static List<Sample> serviceSamples(ColumnSimulation.ColumnInput input, int sampleCount) {
        List<Sample> samples = new ArrayList<>(sampleCount);
        try (BoundedCpuSolveService<Owner, ColumnSimulation.ColumnInput, WorkerResult> service =
                new BoundedCpuSolveService<>(1L, new BoundedCpuSolveService.Config(
                        1, 1, "createcheme-legacy-benchmark-", true,
                        Duration.ofSeconds(2), Duration.ofSeconds(2)))) {
            for (int index = 0; index < sampleCount; index++) {
                Owner owner = new Owner(index);
                long enqueued = System.nanoTime();
                var stamp = new BoundedCpuSolveService.JobStamp<>(
                        1L, index, owner, 0L, "legacy-baseline", enqueued + Duration.ofSeconds(30).toNanos());
                BoundedCpuSolveService.Admission admission = service.trySubmit(
                        stamp, input, LegacyColumnBenchmark::workerFacade);
                if (admission != BoundedCpuSolveService.Admission.ACCEPTED) {
                    samples.add(Sample.notAdmitted(admission.name()));
                    continue;
                }
                BoundedCpuSolveService.Completion<Owner, WorkerResult> completion = await(service);
                long wallNanos = Math.max(0L, completion.completedNanos() - completion.enqueuedNanos());
                long queueNanos = completion.started()
                        ? Math.max(0L, completion.startedNanos() - completion.enqueuedNanos())
                        : wallNanos;
                long workerNanos = completion.started()
                        ? Math.max(0L, completion.completedNanos() - completion.startedNanos())
                        : 0L;
                if (completion.status() == BoundedCpuSolveService.TerminalStatus.SUCCESS) {
                    WorkerResult result = completion.result().orElseThrow();
                    samples.add(new Sample(wallNanos, queueNanos, workerNanos, result.allocatedBytes(),
                            completion.status().name(), completion.detail(), result.outcome()));
                } else {
                    String detail = completion.detail();
                    if (completion.failure().isPresent()) {
                        BoundedCpuSolveService.Failure failure = completion.failure().orElseThrow();
                        detail = failure.type() + ": " + failure.message();
                    }
                    samples.add(new Sample(wallNanos, queueNanos, workerNanos, -1L,
                            completion.status().name(), detail,
                            OutcomeObservation.notProduced(completion.status().name(), detail)));
                }
            }
        }
        return List.copyOf(samples);
    }

    private static BoundedCpuSolveService.Completion<Owner, WorkerResult> await(
            BoundedCpuSolveService<Owner, ColumnSimulation.ColumnInput, WorkerResult> service) {
        while (true) {
            List<BoundedCpuSolveService.Completion<Owner, WorkerResult>> completions = service.drainCompletions(1);
            if (!completions.isEmpty()) {
                return completions.getFirst();
            }
            Thread.onSpinWait();
        }
    }

    private static WorkerResult workerFacade(
            ColumnSimulation.ColumnInput input, BoundedCpuSolveService.CancellationToken token) {
        token.throwIfCancellationRequested();
        long before = allocatedBytes();
        ColumnSimulation.ColumnSolveOutcome outcome = facade(input);
        token.throwIfCancellationRequested();
        return new WorkerResult(OutcomeObservation.fromFacade(outcome), allocatedBytesDelta(before));
    }

    private static ScopeReport report(String scope, List<Sample> samples, String expectedDigest) {
        ScopeSummary summary = ScopeSummary.from(samples);
        String integrityFailure = null;
        if (!"numerical-core".equals(scope) && expectedDigest != null) {
            for (Sample sample : samples) {
                if (!expectedDigest.equals(sample.outcome().resultDigest())) {
                    integrityFailure = scope + " changed the legacy result digest or did not produce a result"
                            + " (expected=" + expectedDigest + ", observed="
                            + textOrNone(sample.outcome().resultDigest()) + ", solverStatus="
                            + sample.outcome().solverStatus() + ')';
                    break;
                }
            }
        }
        for (Sample sample : samples) {
            if (!"DIRECT".equals(sample.executionStatus()) && !"SUCCESS".equals(sample.executionStatus())) {
                integrityFailure = firstFailure(integrityFailure,
                        scope + " worker execution ended " + sample.executionStatus()
                                + ": " + sample.executionDetail());
            }
        }
        System.out.printf("  scope=%s p50=%.3fms p95=%.3fms max=%.3fms queueP50=%.3fms workerP50=%.3fms "
                        + "allocationP50=%.3fMiB allocationMax=%.3fMiB reportedTpCallsP50=%d "
                        + "accepted=%d/%d solverStatuses=%s executionStatuses=%s%n",
                scope,
                nanosToMillis(summary.wallP50Nanos()), nanosToMillis(summary.wallP95Nanos()),
                nanosToMillis(summary.wallMaxNanos()), nanosToMillis(summary.queueP50Nanos()),
                nanosToMillis(summary.workerP50Nanos()), bytesToMiB(summary.allocationP50Bytes()),
                bytesToMiB(summary.allocationMaxBytes()), summary.reportedTpCallsP50(),
                summary.acceptedCount(), samples.size(), summary.solverStatuses(), summary.executionStatuses());
        return new ScopeReport(scope, summary, samples, integrityFailure);
    }

    private static CounterCurrentColumnSolver.Result core(ColumnSimulation.ColumnInput input) {
        CounterCurrentColumnSolver.Result result = new CounterCurrentColumnSolver().solve(input);
        consume(result);
        return result;
    }

    private static ColumnSimulation.ColumnSolveOutcome facade(ColumnSimulation.ColumnInput input) {
        ColumnSimulation.ColumnSolveOutcome result = ColumnSimulation.calculate(input);
        consume(result);
        return result;
    }

    /**
     * Mirrors the legacy private input-digest format solely because a failed facade outcome has no result record
     * from which to retrieve its digest. Successful samples are checked against the legacy-projected digest, so a
     * format drift fails the harness instead of silently mislabelling a failed run.
     */
    private static String legacyInputDigest(ColumnSimulation.ColumnInput input) {
        StringBuilder value = new StringBuilder(256)
                .append(input.schemaVersion()).append('|')
                .append(input.assayId()).append('|')
                .append(hex(input.feedMolarFlowMolPerSecond())).append('|')
                .append(hex(input.feedTemperatureKelvin())).append('|')
                .append(input.stageCount()).append('|')
                .append(input.feedStage()).append('|')
                .append(hex(input.reboilerDutyWatts())).append('|')
                .append(hex(input.refluxRatio())).append('|')
                .append(input.refluxCondition().mode().serializedName());
        input.refluxCondition()
                .temperatureKelvin()
                .ifPresentOrElse(
                        temperature -> value.append('|').append(hex(temperature)),
                        () -> value.append("|none"));
        for (ColumnSimulation.SideDrawSpec side : input.sideDraws()) {
            value.append('|')
                    .append(side.stage())
                    .append(':')
                    .append(hex(side.molarFlowMolPerSecond()));
        }
        return sha256(value);
    }

    private static String hex(double value) {
        return Long.toHexString(Double.doubleToLongBits(value));
    }

    private static String sha256(CharSequence value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java runtime lacks SHA-256", impossible);
        }
    }

    private static void consume(CounterCurrentColumnSolver.Result result) {
        blackhole += result.sweeps() + result.propertyEvaluations();
    }

    private static void consume(ColumnSimulation.ColumnSolveOutcome outcome) {
        blackhole += propertyEvaluations(outcome) + Objects.hashCode(resultDigest(outcome));
    }

    private static int propertyEvaluations(ColumnSimulation.ColumnSolveOutcome outcome) {
        return outcome.result().map(result -> result.diagnostics().propertyEvaluations()).orElse(0);
    }

    private static String resultDigest(ColumnSimulation.ColumnSolveOutcome outcome) {
        return outcome.result().map(ColumnSimulation.ColumnResult::resultDigest).orElse(null);
    }

    private static ThreadMXBean allocationBean() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (bean instanceof ThreadMXBean threadBean && threadBean.isThreadAllocatedMemorySupported()) {
            if (!threadBean.isThreadAllocatedMemoryEnabled()) {
                threadBean.setThreadAllocatedMemoryEnabled(true);
            }
            return threadBean;
        }
        return null;
    }

    private static long allocatedBytes() {
        return THREAD_BEAN == null ? -1L : THREAD_BEAN.getThreadAllocatedBytes(Thread.currentThread().threadId());
    }

    private static long allocatedBytesDelta(long before) {
        if (before < 0L) {
            return -1L;
        }
        return Math.max(0L, allocatedBytes() - before);
    }

    private static String allocationMethod() {
        return THREAD_BEAN == null ? "unavailable" : "com.sun.management.ThreadMXBean per executing thread";
    }

    private static long percentile(long[] sorted, double probability) {
        int nearestRank = (int) Math.ceil(probability * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, nearestRank - 1))];
    }

    private static long availablePercentile(List<Sample> samples, double probability, LongMetric metric) {
        long[] values = samples.stream().mapToLong(metric::value).filter(value -> value >= 0L).sorted().toArray();
        return values.length == 0 ? -1L : percentile(values, probability);
    }

    private static double nanosToMillis(long nanos) {
        return nanos < 0L ? Double.NaN : nanos / 1_000_000.0;
    }

    private static double bytesToMiB(long bytes) {
        return bytes < 0L ? Double.NaN : bytes / (1024.0 * 1024.0);
    }

    private static String diagnosticSummary(List<DiagnosticReport> diagnostics) {
        if (diagnostics.isEmpty()) {
            return "none";
        }
        return diagnostics.stream()
                .map(diagnostic -> diagnostic.code() + "@" + diagnostic.field())
                .reduce((left, right) -> left + "," + right)
                .orElse("none");
    }

    private static String firstFailure(String existing, String next) {
        return existing == null ? next : existing;
    }

    private static String textOrNone(String value) {
        return value == null || value.isEmpty() ? "none" : value;
    }

    private static String pathOrNone(Path path) {
        return path == null ? "none" : path.toString();
    }

    private static ColumnSimulation.ColumnInput case30() {
        return input(30, 24);
    }

    private static ColumnSimulation.ColumnInput case64() {
        return input(64, 24);
    }

    private static ColumnSimulation.ColumnInput input(int stages, int feedStage) {
        return new ColumnSimulation.ColumnInput(
                ColumnSimulation.INPUT_SCHEMA_VERSION,
                "createcheme:tia_juana_light_12",
                100.0,
                620.0,
                stages,
                feedStage,
                8.0e6,
                3.0,
                ColumnSimulation.RefluxCondition.saturatedLiquid(),
                List.of(
                        new ColumnSimulation.SideDrawSpec(8, 10.0),
                        new ColumnSimulation.SideDrawSpec(15, 12.0),
                        new ColumnSimulation.SideDrawSpec(22, 8.0)));
    }

    private static void writeReportIfRequested(Arguments arguments, BenchmarkReport report) {
        if (arguments.reportPath() != null) {
            writeReport(arguments.reportPath(), report);
        }
    }

    /** Writes a complete JSON document to a sibling temporary file before atomically replacing the destination. */
    private static void writeReport(Path destination, BenchmarkReport report) {
        Path normalizedDestination = destination.toAbsolutePath().normalize();
        Path parent = normalizedDestination.getParent();
        if (parent == null || normalizedDestination.getFileName() == null) {
            throw new IllegalArgumentException("Report destination must name a file: " + destination);
        }
        Path temporary = null;
        try {
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, normalizedDestination.getFileName() + ".", ".tmp");
            Files.writeString(temporary, toJson(report), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, normalizedDestination,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, normalizedDestination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not write legacy-column benchmark report to "
                    + normalizedDestination, exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The completed destination remains authoritative; a future run can clean its own temporary file.
                }
            }
        }
    }

    private static String toJson(BenchmarkReport report) {
        Json json = new Json();
        json.object()
                .name("reportSchemaVersion").number(REPORT_SCHEMA_VERSION)
                .name("status").string(report.status())
                .name("startedAtUtc").string(report.startedAtUtc())
                .name("completedAtUtc").stringOrNull(report.completedAtUtc())
                .name("failure").stringOrNull(report.failure())
                .name("environment").object()
                    .name("javaVersion").string(report.javaVersion())
                    .name("vmName").string(report.vmName())
                    .name("processorIdentifier").string(report.processorIdentifier())
                    .name("availableProcessors").number(report.availableProcessors())
                    .name("allocationMethod").string(report.allocationMethod())
                    .name("jfrArtifact").stringOrNull(pathString(report.jfrPath()))
                .endObject()
                .name("configuration").object()
                    .name("samples").number(report.samples())
                    .name("warmup").number(report.warmup())
                    .name("stageFilter").number(report.stageFilter())
                .endObject()
                .name("cases").array();
        for (CaseReport caseReport : report.cases()) {
            appendCase(json, caseReport);
        }
        return json.endArray().endObject().toString();
    }

    private static void appendCase(Json json, CaseReport caseReport) {
        json.object()
                .name("stages").number(caseReport.stages())
                .name("caseStatus").string(caseReport.caseStatus())
                .name("canonicalLegacyInputDigest").string(caseReport.canonicalInputDigest())
                .name("projectedLegacyInputDigest").stringOrNull(caseReport.projectedInputDigest())
                .name("expectedResultDigest").stringOrNull(caseReport.expectedResultDigest())
                .name("integrityFailure").stringOrNull(caseReport.integrityFailure())
                .name("baseline");
        appendBaseline(json, caseReport.baseline());
        json.name("scopes").array();
        for (ScopeReport scope : caseReport.scopes()) {
            appendScope(json, scope);
        }
        json.endArray().endObject();
    }

    private static void appendBaseline(Json json, BaselineObservation baseline) {
        json.object()
                .name("solverStatus").string(baseline.solverStatus())
                .name("acceptedResult").bool(baseline.acceptedResult())
                .name("resultDigest").stringOrNull(baseline.resultDigest())
                .name("diagnostics");
        appendDiagnostics(json, baseline.diagnostics());
        json.endObject();
    }

    private static void appendScope(Json json, ScopeReport scope) {
        json.object()
                .name("scope").string(scope.scope())
                .name("integrityFailure").stringOrNull(scope.integrityFailure())
                .name("summary");
        appendSummary(json, scope.summary());
        json.name("samples").array();
        for (int index = 0; index < scope.samples().size(); index++) {
            appendSample(json, index, scope.samples().get(index));
        }
        json.endArray().endObject();
    }

    private static void appendSummary(Json json, ScopeSummary summary) {
        json.object()
                .name("wallP50Nanos").number(summary.wallP50Nanos())
                .name("wallP95Nanos").number(summary.wallP95Nanos())
                .name("wallMaxNanos").number(summary.wallMaxNanos())
                .name("queueP50Nanos").number(summary.queueP50Nanos())
                .name("workerP50Nanos").number(summary.workerP50Nanos())
                .name("allocationP50Bytes").numberOrNull(summary.allocationP50Bytes())
                .name("allocationMaxBytes").numberOrNull(summary.allocationMaxBytes())
                .name("reportedTpCallsP50").numberOrNull(summary.reportedTpCallsP50())
                .name("acceptedCount").number(summary.acceptedCount())
                .name("sampleCount").number(summary.sampleCount())
                .name("solverStatuses").string(summary.solverStatuses())
                .name("executionStatuses").string(summary.executionStatuses())
                .endObject();
    }

    private static void appendSample(Json json, int index, Sample sample) {
        json.object()
                .name("sampleIndex").number(index)
                .name("wallNanos").number(sample.wallNanos())
                .name("queueNanos").number(sample.queueNanos())
                .name("workerNanos").number(sample.workerNanos())
                .name("allocatedBytes").numberOrNull(sample.allocatedBytes())
                .name("executionStatus").string(sample.executionStatus())
                .name("executionDetail").stringOrNull(emptyToNull(sample.executionDetail()))
                .name("outcome");
        appendOutcome(json, sample.outcome());
        json.endObject();
    }

    private static void appendOutcome(Json json, OutcomeObservation outcome) {
        json.object()
                .name("solverStatus").string(outcome.solverStatus())
                .name("acceptedResult").bool(outcome.acceptedResult())
                .name("resultDigest").stringOrNull(outcome.resultDigest())
                .name("metrics");
        appendMetrics(json, outcome.metrics());
        json.name("diagnostics");
        appendDiagnostics(json, outcome.diagnostics());
        json.endObject();
    }

    private static void appendMetrics(Json json, SolverMetrics metrics) {
        json.object()
                .name("sweeps").numberOrNull(metrics.sweeps())
                .name("reportedTpCalls").numberOrNull(metrics.reportedTpCalls())
                .name("maximumCompositionChange").finiteNumberOrNull(metrics.maximumCompositionChange())
                .name("maximumEquilibriumResidual").finiteNumberOrNull(metrics.maximumEquilibriumResidual())
                .name("maximumVaporFractionResidual").finiteNumberOrNull(metrics.maximumVaporFractionResidual())
                .name("maximumStageComponentResidual").finiteNumberOrNull(metrics.maximumStageComponentResidual())
                .endObject();
    }

    private static void appendDiagnostics(Json json, List<DiagnosticReport> diagnostics) {
        json.array();
        for (DiagnosticReport diagnostic : diagnostics) {
            json.object()
                    .name("severity").string(diagnostic.severity())
                    .name("code").string(diagnostic.code())
                    .name("field").string(diagnostic.field())
                    .name("itemIndex").number(diagnostic.itemIndex())
                    .name("detail").string(diagnostic.detail())
                    .endObject();
        }
        json.endArray();
    }

    private static String pathString(Path path) {
        return path == null ? null : path.toString();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static void runHarnessSelfTest() {
        Arguments parsed = Arguments.parse(new String[] {
                "--samples=2", "--warmup=1", "--stages=64", "--report=build/test-report.json"});
        require(parsed.samples() == 2 && parsed.warmup() == 1 && parsed.stages() == 64,
                "benchmark arguments did not round-trip");
        require(Arguments.parse(new String[] {"--self-test"}).selfTest(),
                "self-test argument was not recognized");

        ColumnSimulation.ColumnInput baselineInput = case64();
        String digest = legacyInputDigest(baselineInput);
        String changedDigest = legacyInputDigest(new ColumnSimulation.ColumnInput(
                baselineInput.schemaVersion(), baselineInput.assayId(), baselineInput.feedMolarFlowMolPerSecond(),
                baselineInput.feedTemperatureKelvin(), baselineInput.stageCount(), baselineInput.feedStage(),
                baselineInput.reboilerDutyWatts(), baselineInput.refluxRatio() + 0.25,
                baselineInput.refluxCondition(), baselineInput.sideDraws()));
        require(!digest.equals(changedDigest), "input digest did not change for a changed scientific input");

        DiagnosticReport diagnostic = new DiagnosticReport("ERROR",
                ColumnSimulation.ColumnFaultCode.NO_CONVERGENCE.wireCode(), "solver", -1,
                "Thermodynamic cascade did not stabilize within 400 sweeps");
        OutcomeObservation outcome = new OutcomeObservation("NO_CONVERGENCE", false, null, List.of(diagnostic),
                new SolverMetrics(400, 17, 2.1e-3, 1.0e-4, 1.0e-8, 7.0e-4));
        List<Sample> samples = List.of(new Sample(
                4_000_000_000L, 0L, 4_000_000_000L, 3_000_000_000L, "DIRECT", "", outcome));
        ScopeReport scope = new ScopeReport("numerical-core", ScopeSummary.from(samples), samples, null);
        BenchmarkReport report = BenchmarkReport.running(parsed);
        report.addCase(new CaseReport(64, digest, null, null, "NO_RESULT_NO_CONVERGENCE",
                new BaselineObservation("NO_CONVERGENCE", false, null, List.of(diagnostic)),
                List.of(scope), null));
        report.complete();

        Path directory = null;
        try {
            directory = Files.createTempDirectory("createcheme-legacy-benchmark-harness-");
            Path reportPath = directory.resolve("legacy-column-report.json");
            writeReport(reportPath, report);
            String contents = Files.readString(reportPath, StandardCharsets.UTF_8);
            require(contents.contains("\"solverStatus\":\"NO_CONVERGENCE\""),
                    "report omitted the no-convergence status");
            require(contents.contains("\"acceptedResult\":false"),
                    "report represented a no-convergence outcome as accepted");
            require(contents.contains("\"canonicalLegacyInputDigest\":\"" + digest + '\"'),
                    "report omitted the recoverable input digest");
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not run legacy benchmark harness self-test", exception);
        } finally {
            if (directory != null) {
                try {
                    Files.deleteIfExists(directory.resolve("legacy-column-report.json"));
                    Files.deleteIfExists(directory);
                } catch (IOException ignored) {
                    // The self-test's temporary directory is isolated from workspace artifacts.
                }
            }
        }
        System.out.println("LegacyColumnBenchmark harness self-test passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private record Owner(int sample) {}

    private record WorkerResult(OutcomeObservation outcome, long allocatedBytes) {}

    /** {@code DIRECT} means no executor was involved; {@code SUCCESS} only means the executor completed. */
    private record Sample(
            long wallNanos,
            long queueNanos,
            long workerNanos,
            long allocatedBytes,
            String executionStatus,
            String executionDetail,
            OutcomeObservation outcome) {
        private static Sample notAdmitted(String admission) {
            return new Sample(0L, 0L, 0L, -1L, "NOT_ADMITTED", admission,
                    OutcomeObservation.notProduced("NOT_ADMITTED", admission));
        }
    }

    /**
     * {@code acceptedResult} is a usable legacy result, not a worker-completion flag. For core samples it equals
     * {@link CounterCurrentColumnSolver.Result#converged()}; facade and service samples use
     * {@link ColumnSimulation.ColumnSolveOutcome#hasResult()}.
     */
    private record OutcomeObservation(
            String solverStatus,
            boolean acceptedResult,
            String resultDigest,
            List<DiagnosticReport> diagnostics,
            SolverMetrics metrics) {
        private OutcomeObservation {
            Objects.requireNonNull(solverStatus, "solverStatus");
            diagnostics = List.copyOf(diagnostics);
            Objects.requireNonNull(metrics, "metrics");
        }

        private static OutcomeObservation fromCore(CounterCurrentColumnSolver.Result result) {
            String status = result.converged() ? "CONVERGED" : "NO_CONVERGENCE";
            List<DiagnosticReport> diagnostics = result.converged()
                    ? List.of()
                    : List.of(new DiagnosticReport("ERROR",
                            ColumnSimulation.ColumnFaultCode.NO_CONVERGENCE.wireCode(), "solver", -1,
                            "Legacy core did not meet its convergence tolerances within "
                                    + result.sweeps() + " sweeps"));
            return new OutcomeObservation(status, result.converged(), null, diagnostics,
                    new SolverMetrics(result.sweeps(), result.propertyEvaluations(),
                            result.maximumCompositionChange(), result.maximumEquilibriumResidual(),
                            result.maximumVaporFractionResidual(), result.maximumRelativeStageComponentResidual()));
        }

        private static OutcomeObservation fromFacade(ColumnSimulation.ColumnSolveOutcome outcome) {
            SolverMetrics metrics = outcome.result()
                    .map(result -> new SolverMetrics(
                            result.diagnostics().iterations(), result.diagnostics().propertyEvaluations(),
                            Double.NaN,
                            result.diagnostics().residuals().maximumEquilibriumResidual().orElse(Double.NaN),
                            Double.NaN, Double.NaN))
                    .orElse(SolverMetrics.unavailable());
            return new OutcomeObservation(outcome.status().name(), outcome.hasResult(),
                    LegacyColumnBenchmark.resultDigest(outcome),
                    outcome.diagnostics().stream().map(DiagnosticReport::from).toList(), metrics);
        }

        private static OutcomeObservation notProduced(String executionStatus, String detail) {
            return new OutcomeObservation("NO_SOLVER_OUTCOME", false, null,
                    List.of(new DiagnosticReport("ERROR", "benchmark.executor." + executionStatus.toLowerCase(),
                            "worker", -1, detail == null ? "" : detail)), SolverMetrics.unavailable());
        }
    }

    private record SolverMetrics(
            int sweeps,
            int reportedTpCalls,
            double maximumCompositionChange,
            double maximumEquilibriumResidual,
            double maximumVaporFractionResidual,
            double maximumStageComponentResidual) {
        private static SolverMetrics unavailable() {
            return new SolverMetrics(-1, -1, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
    }

    private record DiagnosticReport(String severity, String code, String field, int itemIndex, String detail) {
        private DiagnosticReport {
            severity = Objects.requireNonNull(severity, "severity");
            code = Objects.requireNonNull(code, "code");
            field = field == null ? "" : field;
            detail = detail == null ? "" : detail;
        }

        private static DiagnosticReport from(ColumnSimulation.ColumnDiagnostic diagnostic) {
            return new DiagnosticReport(diagnostic.severity().name(), diagnostic.code().wireCode(),
                    diagnostic.field(), diagnostic.itemIndex(), diagnostic.detail());
        }
    }

    private record BaselineObservation(
            String solverStatus,
            boolean acceptedResult,
            String resultDigest,
            List<DiagnosticReport> diagnostics) {
        private BaselineObservation {
            Objects.requireNonNull(solverStatus, "solverStatus");
            diagnostics = List.copyOf(diagnostics);
        }

        private static BaselineObservation from(ColumnSimulation.ColumnSolveOutcome outcome) {
            return new BaselineObservation(outcome.status().name(), outcome.hasResult(),
                    LegacyColumnBenchmark.resultDigest(outcome),
                    outcome.diagnostics().stream().map(DiagnosticReport::from).toList());
        }
    }

    private record ScopeReport(
            String scope,
            ScopeSummary summary,
            List<Sample> samples,
            String integrityFailure) {
        private ScopeReport {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(summary, "summary");
            samples = List.copyOf(samples);
        }
    }

    private record ScopeSummary(
            long wallP50Nanos,
            long wallP95Nanos,
            long wallMaxNanos,
            long queueP50Nanos,
            long workerP50Nanos,
            long allocationP50Bytes,
            long allocationMaxBytes,
            long reportedTpCallsP50,
            long acceptedCount,
            int sampleCount,
            String solverStatuses,
            String executionStatuses) {
        private static ScopeSummary from(List<Sample> samples) {
            long[] wallNanos = samples.stream().mapToLong(Sample::wallNanos).sorted().toArray();
            long[] queueNanos = samples.stream().mapToLong(Sample::queueNanos).sorted().toArray();
            long[] workerNanos = samples.stream().mapToLong(Sample::workerNanos).sorted().toArray();
            return new ScopeSummary(
                    percentile(wallNanos, 0.50), percentile(wallNanos, 0.95), wallNanos[wallNanos.length - 1],
                    percentile(queueNanos, 0.50), percentile(workerNanos, 0.50),
                    availablePercentile(samples, 0.50, Sample::allocatedBytes),
                    availablePercentile(samples, 1.0, Sample::allocatedBytes),
                    availablePercentile(samples, 0.50, sample -> sample.outcome().metrics().reportedTpCalls()),
                    samples.stream().filter(sample -> sample.outcome().acceptedResult()).count(), samples.size(),
                    distinctStatuses(samples, sample -> sample.outcome().solverStatus()),
                    distinctStatuses(samples, Sample::executionStatus));
        }
    }

    private record CaseReport(
            int stages,
            String canonicalInputDigest,
            String projectedInputDigest,
            String expectedResultDigest,
            String caseStatus,
            BaselineObservation baseline,
            List<ScopeReport> scopes,
            String integrityFailure) {
        private CaseReport {
            Objects.requireNonNull(canonicalInputDigest, "canonicalInputDigest");
            Objects.requireNonNull(caseStatus, "caseStatus");
            Objects.requireNonNull(baseline, "baseline");
            scopes = List.copyOf(scopes);
        }
    }

    private static String distinctStatuses(List<Sample> samples, SampleStringMetric metric) {
        return samples.stream().map(metric::value).distinct().sorted()
                .reduce((left, right) -> left + "," + right).orElse("none");
    }

    private interface LongMetric {
        long value(Sample sample);
    }

    private interface SampleStringMetric {
        String value(Sample sample);
    }

    private static final class BenchmarkReport {
        private final String startedAtUtc;
        private final String javaVersion;
        private final String vmName;
        private final String processorIdentifier;
        private final int availableProcessors;
        private final String allocationMethod;
        private final Path jfrPath;
        private final int samples;
        private final int warmup;
        private final int stageFilter;
        private final List<CaseReport> cases = new ArrayList<>();
        private String status = "RUNNING";
        private String completedAtUtc;
        private String failure;

        private BenchmarkReport(Arguments arguments) {
            startedAtUtc = Instant.now().toString();
            javaVersion = System.getProperty("java.version");
            vmName = System.getProperty("java.vm.name");
            processorIdentifier = System.getenv().getOrDefault("PROCESSOR_IDENTIFIER", "unknown");
            availableProcessors = Runtime.getRuntime().availableProcessors();
            allocationMethod = LegacyColumnBenchmark.allocationMethod();
            jfrPath = arguments.jfrPath();
            samples = arguments.samples();
            warmup = arguments.warmup();
            stageFilter = arguments.stages();
        }

        private static BenchmarkReport running(Arguments arguments) {
            return new BenchmarkReport(arguments);
        }

        private void addCase(CaseReport caseReport) {
            cases.add(Objects.requireNonNull(caseReport, "caseReport"));
        }

        private void complete() {
            status = cases.stream().anyMatch(caseReport -> !caseReport.baseline().acceptedResult())
                    ? "COMPLETE_WITH_UNACCEPTED_SOLVER_OUTCOMES"
                    : "COMPLETE";
            completedAtUtc = Instant.now().toString();
        }

        private void fail(Throwable throwable) {
            status = "FAILED";
            completedAtUtc = Instant.now().toString();
            failure = throwable.getClass().getName() + ": " + String.valueOf(throwable.getMessage());
        }

        private String status() { return status; }

        private String startedAtUtc() { return startedAtUtc; }

        private String completedAtUtc() { return completedAtUtc; }

        private String failure() { return failure; }

        private String javaVersion() { return javaVersion; }

        private String vmName() { return vmName; }

        private String processorIdentifier() { return processorIdentifier; }

        private int availableProcessors() { return availableProcessors; }

        private String allocationMethod() { return allocationMethod; }

        private Path jfrPath() { return jfrPath; }

        private int samples() { return samples; }

        private int warmup() { return warmup; }

        private int stageFilter() { return stageFilter; }

        private List<CaseReport> cases() { return List.copyOf(cases); }
    }

    private record Arguments(int samples, int warmup, int stages, Path reportPath, Path jfrPath, boolean selfTest) {
        private static Arguments parse(String[] args) {
            int samples = 7;
            int warmup = 1;
            int stages = 0;
            Path reportPath = null;
            Path jfrPath = null;
            boolean selfTest = false;
            boolean regularOption = false;
            for (String arg : args) {
                if (arg.startsWith("--samples=")) {
                    samples = positive(arg, "--samples=");
                    regularOption = true;
                } else if (arg.startsWith("--warmup=")) {
                    warmup = positive(arg, "--warmup=");
                    regularOption = true;
                } else if (arg.startsWith("--stages=")) {
                    stages = Integer.parseInt(arg.substring("--stages=".length()));
                    if (stages != 30 && stages != 64) {
                        throw new IllegalArgumentException("--stages must be 30 or 64");
                    }
                    regularOption = true;
                } else if (arg.startsWith("--report=")) {
                    reportPath = pathArgument(arg, "--report=");
                    regularOption = true;
                } else if (arg.startsWith("--jfr=")) {
                    jfrPath = pathArgument(arg, "--jfr=");
                    regularOption = true;
                } else if ("--self-test".equals(arg)) {
                    selfTest = true;
                } else {
                    throw new IllegalArgumentException("Unknown benchmark argument: " + arg);
                }
            }
            if (selfTest && regularOption) {
                throw new IllegalArgumentException("--self-test cannot be combined with benchmark options");
            }
            return new Arguments(samples, warmup, stages, reportPath, jfrPath, selfTest);
        }

        private static int positive(String arg, String prefix) {
            int parsed = Integer.parseInt(arg.substring(prefix.length()));
            if (parsed < 1) {
                throw new IllegalArgumentException(prefix + " must be positive");
            }
            return parsed;
        }

        private static Path pathArgument(String arg, String prefix) {
            String value = arg.substring(prefix.length());
            if (value.isBlank()) {
                throw new IllegalArgumentException(prefix + " must name a file");
            }
            return Path.of(value).toAbsolutePath().normalize();
        }
    }

    /** Small dependency-free JSON writer so the benchmark artifact never relies on a game/runtime codec. */
    private static final class Json {
        private final StringBuilder builder = new StringBuilder(16_384);
        private final ArrayDeque<Boolean> firstValues = new ArrayDeque<>();
        private boolean awaitingValue;

        private Json object() {
            beforeValue();
            builder.append('{');
            firstValues.addLast(true);
            return this;
        }

        private Json endObject() {
            require(!awaitingValue, "JSON object ended while awaiting a value");
            builder.append('}');
            firstValues.removeLast();
            return this;
        }

        private Json array() {
            beforeValue();
            builder.append('[');
            firstValues.addLast(true);
            return this;
        }

        private Json endArray() {
            require(!awaitingValue, "JSON array ended while awaiting a value");
            builder.append(']');
            firstValues.removeLast();
            return this;
        }

        private Json name(String name) {
            require(!awaitingValue, "JSON name written while awaiting a value");
            beforeElement();
            appendString(name);
            builder.append(':');
            awaitingValue = true;
            return this;
        }

        private Json string(String value) {
            beforeValue();
            appendString(Objects.requireNonNull(value, "value"));
            return this;
        }

        private Json stringOrNull(String value) {
            if (value == null) {
                return nullValue();
            }
            return string(value);
        }

        private Json bool(boolean value) {
            beforeValue();
            builder.append(value);
            return this;
        }

        private Json number(long value) {
            beforeValue();
            builder.append(value);
            return this;
        }

        private Json numberOrNull(long value) {
            return value < 0L ? nullValue() : number(value);
        }

        private Json finiteNumberOrNull(double value) {
            if (!Double.isFinite(value)) {
                return nullValue();
            }
            beforeValue();
            builder.append(Double.toString(value));
            return this;
        }

        private Json nullValue() {
            beforeValue();
            builder.append("null");
            return this;
        }

        private void beforeValue() {
            if (!awaitingValue) {
                beforeElement();
            }
            awaitingValue = false;
        }

        private void beforeElement() {
            if (firstValues.isEmpty()) {
                return;
            }
            boolean first = firstValues.removeLast();
            if (!first) {
                builder.append(',');
            }
            firstValues.addLast(false);
        }

        private void appendString(String value) {
            builder.append('"');
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                switch (character) {
                    case '"' -> builder.append("\\\"");
                    case '\\' -> builder.append("\\\\");
                    case '\b' -> builder.append("\\b");
                    case '\f' -> builder.append("\\f");
                    case '\n' -> builder.append("\\n");
                    case '\r' -> builder.append("\\r");
                    case '\t' -> builder.append("\\t");
                    default -> {
                        if (character < 0x20) {
                            builder.append(String.format("\\u%04x", (int) character));
                        } else {
                            builder.append(character);
                        }
                    }
                }
            }
            builder.append('"');
        }

        @Override
        public String toString() {
            require(firstValues.isEmpty() && !awaitingValue, "JSON document is incomplete");
            return builder.toString();
        }
    }
}
