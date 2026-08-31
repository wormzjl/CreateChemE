package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.management.ThreadMXBean;
import com.wormzjl.createcheme.science.column.v3.thermo.V3CrudeFeed;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.ToLongFunction;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

/** Standalone serial façade benchmark. No Minecraft queue, production deadline change, or solver modification. */
public final class V3TimeoutBenchmark {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("250-off", 250, 2610.7, 8, 0),
            new Scenario("150-off", 150, 2610.7, 8, 0),
            new Scenario("150-on", 150, 2610.7, 8, 1.0e-6),
            new Scenario("110-off", 110, 2610.7, 8, 0),
            new Scenario("110-on", 110, 2610.7, 8, 1.0e-6),
            new Scenario("100-off", 100, 2610.7, 8, 0),
            new Scenario("100-on", 100, 2610.7, 8, 1.0e-6),
            new Scenario("70-off", 70, 2610.7, 8, 0),
            new Scenario("70-on", 70, 2610.7, 8, 1.0e-6),
            new Scenario("50-off", 50, 2610.7, 8, 0),
            new Scenario("50-on", 50, 2610.7, 8, 1.0e-6),
            new Scenario("100001-off", 100.001, 2610.7, 8, 0),
            new Scenario("110-test-feed", 110, 2000, 8, 0),
            new Scenario("110-matched-duty", 110, 2610.7, 10.4428, 0));

    private V3TimeoutBenchmark() {}

    public static void main(String[] args) throws Exception {
        Arguments options = Arguments.parse(args);
        if (options.selfTest()) {
            selfTest();
            return;
        }
        if (THREADS.isThreadCpuTimeSupported() && !THREADS.isThreadCpuTimeEnabled()) THREADS.setThreadCpuTimeEnabled(true);
        if (THREADS.isThreadAllocatedMemorySupported() && !THREADS.isThreadAllocatedMemoryEnabled()) {
            THREADS.setThreadAllocatedMemoryEnabled(true);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema", 1);
        report.put("started", Instant.now().toString());
        report.put("java", System.getProperty("java.version"));
        report.put("vm", System.getProperty("java.vm.name"));
        report.put("processors", Runtime.getRuntime().availableProcessors());
        report.put("maximumHeapBytes", Runtime.getRuntime().maxMemory());
        report.put("cpu", System.getenv("PROCESSOR_IDENTIFIER"));
        report.put("arguments", Arrays.asList(args));
        report.put("notes", List.of("Serial public V3 façade calls, no game/executor queue.",
                "Warmup uses the same model and 30-stage 150 kPa case; sample order reverses on alternate rounds.",
                "The diagnostic deadline is separate from the unchanged 45-second game deadline.",
                "Facade iteration counters describe its selected terminal attempt, not aggregate continuation work.",
                "Checkpoint stacks are breadcrumbs, not statistical CPU samples. Optional JFR samples supply CPU attribution."));
        List<Map<String, Object>> cells = new ArrayList<>();
        report.put("cells", cells);
        Files.createDirectories(options.report().toAbsolutePath().getParent());
        for (int warmup = 0; warmup < options.warmup(); warmup++) {
            cells.add(run(SCENARIOS.get(1), options, warmup, true));
            write(options.report(), report);
        }
        List<Scenario> selected = options.cases().stream().map(name -> SCENARIOS.stream()
                .filter(scenario -> scenario.name().equals(name)).findFirst().orElseThrow()).toList();
        for (int sample = 0; sample < options.samples(); sample++) {
            List<Scenario> order = sample % 2 == 0 ? selected : selected.reversed();
            for (Scenario scenario : order) {
                cells.add(run(scenario, options, sample, false));
                write(options.report(), report);
            }
        }
        report.put("finished", Instant.now().toString());
        write(options.report(), report);
        System.out.println("REPORT " + options.report().toAbsolutePath());
    }

    private static Map<String, Object> run(Scenario scenario, Arguments options, int sample, boolean warmup) throws Exception {
        V3ColumnInput input = input(scenario);
        Path recordingPath = options.report().resolveSibling(options.report().getFileName() + "-" + scenario.name() + "-" + sample + ".jfr");
        Recording recording = null;
        if (options.profile() && !warmup) {
            recording = new Recording(Configuration.getConfiguration("profile"));
            recording.setName("V3 " + scenario.name() + " sample " + sample);
            recording.start();
        }
        System.out.printf("BEGIN case=%s sample=%d warmup=%s pressure_kpa=%.3f feed_kmol_h=%.4f duty_mw=%.6f cutoff=%g%n",
                scenario.name(), sample, warmup, scenario.pressureKpa(), scenario.feedKmolH(), scenario.dutyMW(), scenario.cutoff());
        Map<String, Object> cell = new LinkedHashMap<>();
        cell.put("scenario", scenario);
        cell.put("sample", sample);
        cell.put("warmup", warmup);
        cell.put("input", input);
        long threadId = Thread.currentThread().threadId();
        long cpuBefore = cpuTime();
        long allocatedBefore = allocatedBytes(threadId);
        long gcBefore = gcTotal(java.lang.management.GarbageCollectorMXBean::getCollectionTime);
        long collectionsBefore = gcTotal(java.lang.management.GarbageCollectorMXBean::getCollectionCount);
        long started = System.nanoTime();
        Deadline control = new Deadline(started, options.deadlineSeconds(), scenario.name());
        V3ColumnOutcome outcome = null;
        try {
            outcome = V3ColumnCalculator.calculate(input, control, scenario.cutoff());
            // Match the service's post-command deadline boundary, including result construction.
            control.checkpoint();
            cell.put("status", outcome instanceof V3ColumnOutcome.Success ? "SUCCESS" : ((V3ColumnOutcome.Failure) outcome).code().name());
            cell.put("diagnostics", outcome.diagnostics());
            if (outcome instanceof V3ColumnOutcome.Success success) {
                cell.put("branch", success.result().problem().topology().condenserPhaseBranch().name());
                cell.put("streams", success.result().streams());
                cell.put("inputDigest", success.result().inputDigest().hexadecimalSha256());
                cell.put("removedPoints", success.result().problem().truncationSupport().truncatedPointCount());
            } else {
                cell.put("summary", ((V3ColumnOutcome.Failure) outcome).summary());
            }
        } catch (BenchmarkDeadline deadline) {
            cell.put("status", "DIAGNOSTIC_DEADLINE");
            cell.put("deadlineStack", Arrays.stream(deadline.getStackTrace()).map(StackTraceElement::toString).toList());
        } catch (RuntimeException failure) {
            cell.put("status", "EXCEPTION");
            cell.put("summary", failure.toString());
            cell.put("failureStack", Arrays.stream(failure.getStackTrace()).map(StackTraceElement::toString).toList());
        } finally {
            cell.put("wallSeconds", (System.nanoTime() - started) / 1.0e9);
            cell.put("cpuSeconds", delta(cpuBefore, cpuTime()) / 1.0e9);
            cell.put("allocatedBytes", delta(allocatedBefore, allocatedBytes(threadId)));
            cell.put("gcMilliseconds", gcTotal(java.lang.management.GarbageCollectorMXBean::getCollectionTime) - gcBefore);
            cell.put("gcCollections", gcTotal(java.lang.management.GarbageCollectorMXBean::getCollectionCount) - collectionsBefore);
            cell.put("checkpoints", control.checkpoints);
            cell.put("checkpointBreadcrumbs", control.breadcrumbs);
            if (recording != null) {
                recording.stop();
                recording.dump(recordingPath);
                recording.close();
                cell.put("jfr", recordingPath.toAbsolutePath().toString());
                cell.put("jfrSummary", summarizeRecording(recordingPath, threadId));
            }
        }
        System.out.printf("END case=%s sample=%d status=%s wall_s=%.3f cpu_s=%.3f allocated_mb=%.1f gc_ms=%s%n",
                scenario.name(), sample, cell.get("status"), cell.get("wallSeconds"), cell.get("cpuSeconds"),
                ((Long) cell.get("allocatedBytes")) / 1.0e6, cell.get("gcMilliseconds"));
        if (outcome != null) System.out.println("DIAGNOSTICS " + JSON.toJson(outcome.diagnostics()));
        return cell;
    }

    static V3ColumnInput input(Scenario scenario) {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        V3CrudeFeed crude = thermo.crudeFeed("createcheme:tia_juana_light");
        double[] flows = crude.moleFractions();
        double total = scenario.feedKmolH() * 1000.0 / 3600.0;
        for (int component = 0; component < flows.length; component++) flows[component] *= total;
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(), crude.componentBasis(),
                flows, 638.15, 30, 24, scenario.pressureKpa() * 1000.0, 750.0, List.of(
                new V3ColumnSpecification.CondenserOutletTemperature(323.15),
                new V3ColumnSpecification.OrganicRefluxRatio(2.0),
                new V3ColumnSpecification.ReboilerDuty(scenario.dutyMW() * 1.0e6)));
    }

    private static Map<String, Object> summarizeRecording(Path path, long threadId) throws IOException {
        Map<String, Long> leaf = new LinkedHashMap<>();
        Map<String, Long> inclusive = new LinkedHashMap<>();
        long samples = 0;
        try (RecordingFile file = new RecordingFile(path)) {
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                if (!event.getEventType().getName().equals("jdk.ExecutionSample") || event.getStackTrace() == null) continue;
                RecordedThread sampled = event.getValue("sampledThread");
                if (sampled == null || sampled.getJavaThreadId() != threadId) continue;
                List<RecordedFrame> frames = event.getStackTrace().getFrames();
                if (frames.isEmpty()) continue;
                samples++;
                leaf.merge(method(frames.getFirst()), 1L, Long::sum);
                frames.stream().map(V3TimeoutBenchmark::method).distinct().forEach(name -> inclusive.merge(name, 1L, Long::sum));
            }
        }
        return Map.of("mainThreadSamples", samples, "leafTop20", top(leaf), "inclusiveTop20", top(inclusive));
    }

    private static String method(RecordedFrame frame) {
        return frame.getMethod().getType().getName() + "." + frame.getMethod().getName();
    }

    private static Map<String, Long> top(Map<String, Long> counts) {
        Map<String, Long> sorted = new LinkedHashMap<>();
        counts.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .limit(20).forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return sorted;
    }

    private static long cpuTime() { return THREADS.isCurrentThreadCpuTimeSupported() ? THREADS.getCurrentThreadCpuTime() : -1; }
    private static long allocatedBytes(long id) { return THREADS.isThreadAllocatedMemorySupported() ? THREADS.getThreadAllocatedBytes(id) : -1; }
    private static long delta(long before, long after) { return before < 0 || after < 0 ? -1 : after - before; }
    private static long gcTotal(ToLongFunction<java.lang.management.GarbageCollectorMXBean> metric) {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(metric).filter(value -> value >= 0).sum();
    }

    private static void write(Path path, Map<String, Object> report) throws IOException {
        Files.writeString(path, JSON.toJson(report));
    }

    record Scenario(String name, double pressureKpa, double feedKmolH, double dutyMW, double cutoff) {}

    private record Arguments(int samples, int warmup, double deadlineSeconds, List<String> cases, boolean profile,
                             Path report, boolean selfTest) {
        static Arguments parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            for (String arg : args) {
                if (arg.equals("--self-test")) { values.put("self-test", "true"); continue; }
                int equals = arg.indexOf('=');
                if (!arg.startsWith("--") || equals < 3) throw new IllegalArgumentException("Expected --name=value: " + arg);
                String key = arg.substring(2, equals);
                if (!List.of("samples", "warmup", "deadlineSeconds", "cases", "profile", "report").contains(key)) {
                    throw new IllegalArgumentException("Unknown benchmark option " + key);
                }
                if (values.put(key, arg.substring(equals + 1)) != null) throw new IllegalArgumentException("Duplicate option " + key);
            }
            int samples = Integer.parseInt(values.getOrDefault("samples", "1"));
            int warmup = Integer.parseInt(values.getOrDefault("warmup", "1"));
            double deadline = Double.parseDouble(values.getOrDefault("deadlineSeconds", "90"));
            List<String> cases = List.of(values.getOrDefault("cases", "150-off,110-off,110-on,100-off,110-test-feed,110-matched-duty").split(","));
            if (samples < 1 || samples > 10 || warmup < 0 || warmup > 10 || !Double.isFinite(deadline) || deadline < 1 || deadline > 600) {
                throw new IllegalArgumentException("Invalid benchmark sampling or deadline bounds");
            }
            for (String name : cases) if (SCENARIOS.stream().noneMatch(scenario -> scenario.name().equals(name))) {
                throw new IllegalArgumentException("Unknown benchmark case " + name);
            }
            return new Arguments(samples, warmup, deadline, cases, Boolean.parseBoolean(values.getOrDefault("profile", "false")),
                    Path.of(values.getOrDefault("report", "build/reports/benchmarks/v3-timeout.json")), values.containsKey("self-test"));
        }
    }

    private static final class Deadline implements V3SolveControl {
        private final long started;
        private final long deadlineNanos;
        private final String name;
        private long nextSample;
        private long nextHeartbeat;
        private long checkpoints;
        private final List<Map<String, Object>> breadcrumbs = new ArrayList<>();

        Deadline(long started, double seconds, String name) {
            this.started = started;
            this.deadlineNanos = (long) (seconds * 1.0e9);
            this.name = name;
            nextSample = started;
            nextHeartbeat = started + 10_000_000_000L;
        }

        @Override public void checkpoint() {
            checkpoints++;
            long now = System.nanoTime();
            if (now - started >= deadlineNanos) throw new BenchmarkDeadline();
            if (now >= nextSample) {
                List<String> frames = StackWalker.getInstance().walk(stack -> stack
                        .filter(frame -> frame.getClassName().startsWith("com.wormzjl.createcheme.science.column.v3.V3ColumnCalculator")
                                || frame.getClassName().endsWith("V3SimultaneousColumnSolver"))
                        .limit(10).map(frame -> frame.getMethodName() + ":" + frame.getLineNumber()).toList());
                breadcrumbs.add(Map.of("seconds", (now - started) / 1.0e9, "frames", frames));
                nextSample = now + 1_000_000_000L;
                if (now >= nextHeartbeat) {
                    System.out.printf("PROGRESS case=%s seconds=%.1f checkpoint=%s%n", name, (now - started) / 1.0e9, frames);
                    nextHeartbeat = now + 10_000_000_000L;
                }
            }
        }
    }

    private static final class BenchmarkDeadline extends CancellationException {
        private BenchmarkDeadline() { super("Benchmark diagnostic deadline reached"); }
    }

    private static void selfTest() {
        Arguments args = Arguments.parse(new String[] {"--cases=110-off,110-on", "--samples=2", "--deadlineSeconds=45"});
        if (args.samples() != 2 || args.deadlineSeconds() != 45 || args.cases().size() != 2) throw new AssertionError("argument parsing");
        for (String invalid : List.of("--samples=0", "--deadlineSeconds=NaN", "--cases=unknown", "--unknown=1")) {
            try { Arguments.parse(new String[] {invalid}); throw new AssertionError("accepted " + invalid); }
            catch (IllegalArgumentException expected) { /* checked rejection */ }
        }
        String serialized = JSON.toJson(Map.of("status", "DIAGNOSTIC_DEADLINE", "wallSeconds", 45.0,
                "checkpointBreadcrumbs", List.of(Map.of("seconds", 44.5, "frames", List.of("solve:1")))));
        if (!serialized.contains("DIAGNOSTIC_DEADLINE")) throw new AssertionError("failure serialization");
        System.out.println("V3 timeout benchmark self-test passed");
    }
}
