package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoException;
import com.wormzjl.createcheme.science.column.v3.thermo.V3TraceTruncationPolicy;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;

/**
 * Bounded correction-trajectory diagnostic for the learned seed. Measures; selects nothing.
 *
 * <p>The archived campaigns record a capped neural-only failure as one word — the iteration cap or the
 * two-second wall — and nothing about how the correction was travelling when it stopped. That is the gap
 * recommendation R2 has to close before any budget can be reshaped: a seed whose residual is still
 * contracting when the cap arrives needs more steps, a seed whose residual has flattened needs a
 * different seed or an earlier stop, and the two are indistinguishable in the published evidence.</p>
 *
 * <p>This runs one LNN_ONLY request per case through the unchanged production path, with the calculator's
 * package-local observation trace attached. Per attempt it keeps the iteration budget the attempt was
 * given, the support and wet-tray retention it was prepared on, the maximum scaled residual and scaled
 * merit of every iteration, the direction evidence, and the solver code that ended it. An attempt left
 * open at the end of a case is the one the budget interrupted. Nothing here changes a step, a support, a
 * tolerance or an outcome; the trace is an observer and the request is the ordinary one.</p>
 */
public final class V3BudgetTraceProbe {
    static final String REVISION = "neural-budget-trace-v1";
    /** Per-attempt iteration samples kept. Forty-eight is the widest diagnostic budget plus its final sample. */
    private static final int MAXIMUM_SAMPLES = 64;
    private static final int MAXIMUM_ATTEMPTS = 32;
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    private V3BudgetTraceProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 8)
            throw new IllegalArgumentException("directory input-jsonl pipeline workers deadline-seconds neural-budget-ms iterations warmup-json");
        Path directory = Path.of(args[0]), source = Path.of(args[1]), modelPath = Path.of(args[2]);
        int workers = Integer.parseInt(args[3]);
        long deadlineMillis = Math.round(Double.parseDouble(args[4]) * 1000);
        int budgetMillis = Integer.parseInt(args[5]);
        int maximumIterations = Integer.parseInt(args[6]);
        if (workers != 10 || deadlineMillis != 30_000)
            throw new IllegalArgumentException("The diagnostic runs on ten owned workers with 30 s requests");
        if (Files.exists(directory)) throw new IllegalArgumentException("Trace output already exists");
        List<JsonObject> requests;
        try (var lines = Files.lines(source)) {
            requests = lines.filter(s -> !s.isBlank()).map(s -> JsonParser.parseString(s).getAsJsonObject()).toList();
        }
        if (requests.isEmpty()) throw new IllegalArgumentException("Empty diagnostic population");
        Set<String> ids = new HashSet<>();
        for (var row : requests) {
            if (!ids.add(row.get("id").getAsString())) throw new IllegalArgumentException("Duplicate case ID");
            V3NeuralMvpProbe.input(row.getAsJsonObject("input"));
        }
        if (THREADS.isThreadCpuTimeSupported() && !THREADS.isThreadCpuTimeEnabled()) THREADS.setThreadCpuTimeEnabled(true);
        V3NeuralInitializer model = V3BudgetModels.read(modelPath);
        var options = new V3InitializationOptions(V3InitializationOptions.Mode.LNN_ONLY,
                V3InitializationOptions.WetStart.AUTO, maximumIterations, budgetMillis);
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("revision", REVISION);
        metadata.put("mode", "concurrent-diagnostic");
        metadata.put("strategy", "LNN_ONLY");
        metadata.put("source", source.toString()); metadata.put("sourceSha256", sha256(source));
        metadata.put("modelSha256", sha256(modelPath)); metadata.put("modelId", model.modelId());
        metadata.put("pipelineManifest", JsonParser.parseString(Files.readString(modelPath)));
        metadata.put("warmupSha256", sha256(Path.of(args[7])));
        metadata.put("workers", workers); metadata.put("deadlineMillis", deadlineMillis);
        metadata.put("neuralBudgetMillis", budgetMillis);
        metadata.put("neuralMaximumIterations", maximumIterations);
        metadata.put("caseCount", requests.size());
        metadata.put("maximumSamplesPerAttempt", MAXIMUM_SAMPLES);
        metadata.put("maximumAttemptsPerCase", MAXIMUM_ATTEMPTS);
        metadata.put("java", System.getProperty("java.version"));
        metadata.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        metadata.put("maximumHeapBytes", Runtime.getRuntime().maxMemory());
        metadata.put("timingScope", "ten-worker concurrent load; per-request wall time includes CPU contention");
        metadata.put("observation", "package-local Newton trace; observes only, and cannot change a step or an outcome");
        var warmup = V3NeuralMvpProbe.input(JsonParser.parseString(Files.readString(Path.of(args[7])))
                .getAsJsonObject().getAsJsonObject("input"));
        for (int i = 0; i < 2; i++) request(warmup, model, options, deadlineMillis, new Recorder());
        Files.createDirectories(directory);
        var tasks = new ArrayList<Callable<Map<String, Object>>>();
        for (JsonObject row : requests) tasks.add(() -> evaluate(row, model, options, deadlineMillis));
        int[] finished = {0};
        long started = System.nanoTime();
        V3BoundedEvaluation.Summary scheduling = null;
        try (var out = Files.newBufferedWriter(directory.resolve("trace.jsonl"), StandardOpenOption.CREATE_NEW)) {
            scheduling = V3BoundedEvaluation.run(tasks, workers, (result, queueWait) -> {
                result.put("queueWaitMillis", queueWait);
                out.write(JSON.toJson(result)); out.newLine(); out.flush();
                finished[0]++;
                if (finished[0] <= 5 || finished[0] % 25 == 0 || finished[0] == requests.size())
                    System.out.printf(Locale.ROOT, "traced %d/%d id=%s elapsed=%.1fs%n", finished[0], requests.size(),
                            result.get("id"), (System.nanoTime() - started) / 1e9);
            });
        } finally {
            metadata.put("completed", finished[0]);
            metadata.put("complete", scheduling != null && scheduling.terminated() && finished[0] == requests.size());
            metadata.put("scheduling", scheduling);
            metadata.put("elapsedSeconds", (System.nanoTime() - started) / 1e9);
            Files.writeString(directory.resolve("run.json"), JSON.toJson(metadata), StandardOpenOption.CREATE_NEW);
        }
    }

    private static Map<String, Object> evaluate(JsonObject source, V3NeuralInitializer model,
            V3InitializationOptions options, long deadlineMillis) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", source.get("id").getAsString());
        row.put("split", source.get("split"));
        // The strict audit of this study reads the same keys the campaign journals carry, so a traced
        // request is qualified by exactly the definition the campaign counts use.
        row.put("input", source.get("input"));
        V3ColumnInput input = V3NeuralMvpProbe.input(source.getAsJsonObject("input"));
        row.put("condition", condition(input));
        long predictionStart = System.nanoTime();
        V3NeuralSeed raw = null;
        try {
            raw = model.predict(input, V3SolveControl.UNBOUNDED).orElse(null);
        } catch (IllegalArgumentException | V3ThermoException | CancellationException unavailable) {
            row.put("predictionFailure", unavailable.getMessage());
        }
        row.put("seedAvailable", raw != null);
        row.put("predictionMillis", (System.nanoTime() - predictionStart) / 1e6);
        if (raw != null) {
            row.put("seedSupport", seedSupport(raw));
            row.put("initialProjectedResidual", projectedResidual(raw));
        }
        var recorder = new Recorder();
        long start = System.nanoTime(), cpuStart = cpu();
        var result = request(input, model, options, deadlineMillis, recorder);
        row.put("milliseconds", (System.nanoTime() - start) / 1e6);
        row.put("cpuMillis", cpuDifference(cpuStart, cpu()));
        row.putAll(result);
        row.put("attempts", recorder.finish());
        return row;
    }

    /** One ordinary LNN_ONLY request with the observation trace attached. */
    private static Map<String, Object> request(V3ColumnInput input, V3NeuralInitializer model,
            V3InitializationOptions options, long deadlineMillis, Recorder recorder) {
        var row = new LinkedHashMap<String, Object>();
        long start = System.nanoTime();
        try {
            V3NeuralSeed[] accepted = {null};
            var outcome = V3ColumnCalculator.calculateWithNeuralTrace(input, deadline(start, deadlineMillis),
                    options, model, recorder, profile -> accepted[0] = profile);
            row.put("success", outcome.isSuccess());
            row.put("diagnostics", outcome.diagnostics());
            row.put("publishedNewtonIterations", outcome.diagnostics().newtonIterations());
            row.put("publishedMaximumScaledResidual", outcome.diagnostics().maximumScaledResidual());
            if (outcome instanceof V3ColumnOutcome.Success success) {
                if (accepted[0] == null) throw new IllegalStateException("Accepted native solve omitted its profile");
                row.put("status", "ACCEPTED");
                row.put("formulationRevision", success.result().formulationRevision());
                var water = V3WaterPhaseQualification.assess(accepted[0]);
                row.put("waterQualification", water.grade().name());
                row.put("equilibriumQualified", water.qualified());
                row.put("wetTrayCount", water.wetTrayCount());
                row.put("seed", accepted[0]);
            } else {
                var failure = (V3ColumnOutcome.Failure) outcome;
                row.put("status", failure.code().name());
                row.put("failure", failure.summary());
            }
        } catch (CancellationException cancelled) {
            row.put("success", false);
            row.put("status", Thread.currentThread().isInterrupted() ? "CANCELLED" : "DEADLINE_EXCEEDED");
            row.put("failure", cancelled.getMessage());
        } catch (V3ThermoException | IllegalArgumentException unsupported) {
            row.put("success", false);
            row.put("status", "PROPERTY_OR_INPUT_REJECTION");
            row.put("failure", unsupported.getMessage());
        }
        return row;
    }

    /**
     * One case's correction trajectory. Written only by the worker thread that owns the case.
     *
     * <p>An attempt is opened by the calculator's attempt boundary and closed by its terminal evidence.
     * Iterations sampled before any boundary belong to the fixed-water wet prepass, which is a solve of
     * its own; the attempt left open when the case ends is the one the budget interrupted.</p>
     */
    private static final class Recorder implements V3NewtonTrace {
        private final List<Map<String, Object>> attempts = new ArrayList<>();
        private Map<String, Object> current;
        private List<Double> residuals = new ArrayList<>(), merits = new ArrayList<>(), elapsed = new ArrayList<>();
        private int acceptedDirections, rejectedDirections, freshJacobians, reusedJacobians;
        private int lastIteration = -1, samples;
        private long attemptStart;

        @Override
        public void sampledIteration(int iteration, V3MeshResidual residual, double scaledMerit) {
            if (current == null) open(0, "wet-prepass", -1, -1, -1, -1, -1, -1);
            samples++;
            lastIteration = iteration;
            if (residuals.size() < MAXIMUM_SAMPLES) {
                residuals.add(residual.maximumAbsoluteScaledResidual());
                merits.add(scaledMerit);
                // When each iteration arrived, so the time an early stop would have returned is measured
                // rather than inferred from an average rate.
                elapsed.add((System.nanoTime() - attemptStart) / 1e6);
            }
        }

        @Override
        public void sampledState(int iteration, V3DryMeshState state, V3MeshResidual residual, double scaledMerit) {
            sampledIteration(iteration, residual, scaledMerit);
        }

        @Override
        public void localBlockDirection(int iteration, boolean accepted) {
            if (accepted) acceptedDirections++; else rejectedDirections++;
        }

        @Override
        public void finiteDifferenceJacobian(int iteration, boolean reused) {
            if (reused) reusedJacobians++; else freshJacobians++;
        }

        @Override
        public void beganAttempt(int attempt, int supportRefreshes, int wetTrayRefreshes, int iterationBudget,
                int retainedPoints, int totalPoints, int wetTrayCount) {
            open(attempt, "attempt", supportRefreshes, wetTrayRefreshes, iterationBudget,
                    retainedPoints, totalPoints, wetTrayCount);
        }

        @Override
        public void finishedAttempt(int attempt, String code, int iterations, double maximumScaledResidual) {
            close(code, iterations, maximumScaledResidual);
        }

        private void open(int attempt, String label, int supportRefreshes, int wetTrayRefreshes, int iterationBudget,
                int retainedPoints, int totalPoints, int wetTrayCount) {
            close(null, -1, Double.NaN);
            residuals = new ArrayList<>();
            merits = new ArrayList<>();
            elapsed = new ArrayList<>();
            acceptedDirections = rejectedDirections = freshJacobians = reusedJacobians = 0;
            lastIteration = -1;
            samples = 0;
            attemptStart = System.nanoTime();
            current = new LinkedHashMap<>();
            current.put("attempt", attempt);
            current.put("label", label);
            current.put("supportRefreshes", supportRefreshes);
            current.put("wetTrayRefreshes", wetTrayRefreshes);
            current.put("iterationBudget", iterationBudget);
            current.put("retainedPoints", retainedPoints);
            current.put("totalPoints", totalPoints);
            current.put("wetTrayCount", wetTrayCount);
        }

        private void close(String code, int iterations, double maximumScaledResidual) {
            if (current == null) return;
            current.put("stopCode", code);
            current.put("interrupted", code == null);
            current.put("iterations", iterations >= 0 ? iterations : lastIteration);
            current.put("sampledIterations", samples);
            current.put("finalMaximumScaledResidual", Double.isNaN(maximumScaledResidual) ? null : maximumScaledResidual);
            current.put("milliseconds", (System.nanoTime() - attemptStart) / 1e6);
            current.put("maximumScaledResiduals", residuals);
            current.put("scaledMerits", merits);
            current.put("iterationElapsedMillis", elapsed);
            current.put("localBlockDirections", Map.of("accepted", acceptedDirections, "rejected", rejectedDirections));
            current.put("fineFiniteDifferenceJacobians", Map.of("fresh", freshJacobians, "reused", reusedJacobians));
            if (attempts.size() < MAXIMUM_ATTEMPTS) attempts.add(current);
            current = null;
        }

        private List<Map<String, Object>> finish() {
            close(null, -1, Double.NaN);
            return attempts;
        }
    }

    /** Phases the decoder left at exactly zero, and the entries it kept, without consulting any reference. */
    private static Map<String, Object> seedSupport(V3NeuralSeed seed) {
        double[][] liquid = seed.liquid(), vapor = seed.vapor();
        int zeroPhases = 0, positiveEntries = 0, entries = 0, nodes = liquid.length;
        for (int n = 0; n < nodes; n++) {
            for (double[][] phase : new double[][][]{liquid, vapor}) {
                double total = 0;
                for (int c = 0; c < phase[n].length; c++) {
                    total += phase[n][c];
                    entries++;
                    if (phase[n][c] > 0) positiveEntries++;
                }
                if (total == 0) zeroPhases++;
            }
        }
        return Map.of("branch", seed.branch().name(), "nodes", nodes, "phases", 2 * nodes,
                "zeroPhases", zeroPhases, "positiveEntries", positiveEntries, "entries", entries);
    }

    /** The seed's maximum scaled MESH residual, projected through the native trace support. */
    private static Object projectedResidual(V3NeuralSeed seed) {
        try {
            var thermo = V3PengRobinsonThermo.fromRegisteredPackage(seed.input().packageId());
            var full = V3ColumnProblemResolver.resolve(seed.input(), seed.branch());
            var state = seed.stateFor(full);
            var wet = seed.wetSetFor(full, V3InitializationOptions.WetStart.PREDICTED_WET);
            var support = V3TruncationSupport.derive(full, 0, state);
            var problem = V3ColumnProblemResolver.withTruncation(full, support, wet);
            state = support.projectSeed(problem, state);
            var flash = thermo.flashTP(seed.input().feedTemperatureKelvin(),
                    full.nodePressurePascal(seed.input().feedStageNumber()),
                    seed.input().feedComponentMolarFlowsMolPerSecond(), V3TraceTruncationPolicy.of(0),
                    thermo.newWorkspace(), () -> {});
            var value = new V3MeshResidualEvaluator(problem, thermo, flash.molarEnthalpyJoulesPerMol())
                    .evaluate(state, thermo.newWorkspace());
            return value.maximumAbsoluteScaledResidual();
        } catch (V3ThermoException | IllegalArgumentException invalid) {
            return null;
        }
    }

    private static Map<String, Object> condition(V3ColumnInput input) {
        return Map.of("stageCount", input.stageCount(), "steam", !input.steamFeeds().isEmpty(),
                "sideDrawCount", input.sideDraws().size(), "heatLoopCount", input.pumparounds().size());
    }

    private static V3SolveControl deadline(long start, long milliseconds) {
        return () -> {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("offline worker interrupted");
            if (System.nanoTime() - start >= milliseconds * 1_000_000L) throw new CancellationException("offline wall deadline");
        };
    }

    private static long cpu() { return THREADS.isCurrentThreadCpuTimeSupported() ? THREADS.getCurrentThreadCpuTime() : -1; }

    private static Double cpuDifference(long before, long after) {
        return before >= 0 && after >= before ? (after - before) / 1e6 : null;
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
