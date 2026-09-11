package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.*;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoException;
import com.wormzjl.createcheme.science.column.v3.thermo.V3TraceTruncationPolicy;
import java.lang.management.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;

/**
 * Concurrent evaluation revision 1. Ten independent cases share immutable
 * weights; each case owns its input, prediction, solver state and diagnostics.
 * Each strategy starts its own request deadline after executor queue wait.
 * Native measurement helpers below preserve the frozen serial probe's equations.
 */
public final class V3TraceEvaluationProbe {
    static final String REVISION = "trace-followup-column-evaluation-v1";
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final com.sun.management.ThreadMXBean ALLOCATIONS = THREADS instanceof com.sun.management.ThreadMXBean bean ? bean : null;
    private static final MemoryMXBean MEMORY = ManagementFactory.getMemoryMXBean();

    private V3TraceEvaluationProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 8) throw new IllegalArgumentException("directory input-jsonl pipeline workers deadline-seconds neural-budget-ms iterations warmup-json");
        Path directory = Path.of(args[0]), source = Path.of(args[1]), modelPath = Path.of(args[2]);
        int workers = Integer.parseInt(args[3]);
        long deadlineMillis = Math.round(Double.parseDouble(args[4]) * 1000);
        long neuralBudgetMillis = Long.parseLong(args[5]);
        int maximumIterations = Integer.parseInt(args[6]);
        if (workers != 10 || deadlineMillis != 30_000 || neuralBudgetMillis != 2_000 || maximumIterations != 16)
            throw new IllegalArgumentException("Revision 1 requires ten workers, 30s requests, 2s neural and 16 iterations");
        if (Files.exists(directory)) throw new IllegalArgumentException("Evaluation output already exists");
        List<JsonObject> requests;
        try (var lines = Files.lines(source)) {
            requests = lines.filter(s -> !s.isBlank()).map(s -> JsonParser.parseString(s).getAsJsonObject()).toList();
        }
        Set<String> ids = new HashSet<>();
        for (var row : requests) {
            if (!ids.add(row.get("id").getAsString())) throw new IllegalArgumentException("Duplicate case ID");
            if (row.has("exclusion") || (row.has("excluded") && row.get("excluded").getAsBoolean()))
                throw new IllegalArgumentException("Excluded input in fixed evaluation population");
            V3NeuralMvpProbe.input(row.getAsJsonObject("input"));
        }
        if (requests.isEmpty()) throw new IllegalArgumentException("Empty evaluation population");
        if (ALLOCATIONS != null && ALLOCATIONS.isThreadAllocatedMemorySupported() && !ALLOCATIONS.isThreadAllocatedMemoryEnabled())
            ALLOCATIONS.setThreadAllocatedMemoryEnabled(true);
        if (THREADS.isThreadCpuTimeSupported() && !THREADS.isThreadCpuTimeEnabled()) THREADS.setThreadCpuTimeEnabled(true);
        long loadStart = System.nanoTime(), loadAllocation = allocation();
        V3NeuralInitializer model = V3TraceModels.read(modelPath);
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("revision", REVISION); metadata.put("mode", "concurrent-benchmark");
        metadata.put("source", source.toString()); metadata.put("sourceSha256", sha256(source));
        metadata.put("modelSha256", sha256(modelPath)); metadata.put("modelId", model.modelId());
        metadata.put("pipelineManifest", JsonParser.parseString(Files.readString(modelPath)));
        metadata.put("warmupSha256", sha256(Path.of(args[7])));
        metadata.put("modelLoadMillis", (System.nanoTime() - loadStart) / 1e6);
        metadata.put("modelLoadAllocatedBytes", difference(loadAllocation, allocation()));
        metadata.put("pipelineManifestBytes", Files.size(modelPath));
        metadata.put("workers", workers); metadata.put("queueCapacity", workers); metadata.put("maximumInFlight", workers);
        metadata.put("deadlineMillis", deadlineMillis); metadata.put("neuralBudgetMillis", neuralBudgetMillis);
        metadata.put("neuralMaximumIterations", maximumIterations); metadata.put("caseCount", requests.size());
        metadata.put("strategies", List.of("current", "neural", "neuralFirst"));
        metadata.put("strategyOrder", "case-id hash rotation of native mode enum; sequential within each case");
        metadata.put("requestDeadlineStarts", "immediately before each strategy; excludes case queue wait and raw diagnostics");
        metadata.put("timingScope", "ten-worker concurrent load; per-strategy service wall time includes CPU contention; case queue wait separate");
        metadata.put("memoryScope", "thread allocated bytes are volume, not retained RAM; heap and pool peaks are whole-process measurements");
        metadata.put("java", System.getProperty("java.version"));
        metadata.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        metadata.put("maximumHeapBytes", Runtime.getRuntime().maxMemory());
        metadata.put("warmup", "two sequential repetitions of three strategies on fixed original input; excluded from campaign metrics");
        var warmup = V3NeuralMvpProbe.input(JsonParser.parseString(Files.readString(Path.of(args[7])))
                .getAsJsonObject().getAsJsonObject("input"));
        for (int i = 0; i < 2; i++) for (var strategy : V3InitializationOptions.Mode.values())
            run(warmup, model, strategy, deadlineMillis, neuralBudgetMillis, maximumIterations, false);
        for (var pool : ManagementFactory.getMemoryPoolMXBeans()) pool.resetPeakUsage();
        Files.createDirectories(directory);
        var tasks = new ArrayList<Callable<Map<String, Object>>>();
        for (JsonObject request : requests) tasks.add(() -> evaluate(request, model, deadlineMillis, neuralBudgetMillis, maximumIterations));
        int[] finished = {0};
        long started = System.nanoTime();
        V3BoundedEvaluation.Summary scheduling = null;
        try (var out = Files.newBufferedWriter(directory.resolve("evaluation.jsonl"), StandardOpenOption.CREATE_NEW)) {
            scheduling = V3BoundedEvaluation.run(tasks, workers, (result, queueWait) -> {
                result.put("queueWaitMillis", queueWait);
                out.write(JSON.toJson(result)); out.newLine(); out.flush();
                finished[0]++;
                if (finished[0] <= 10 || finished[0] % 25 == 0 || finished[0] == requests.size())
                    System.out.printf(Locale.ROOT, "concurrent %d/%d id=%s elapsed=%.1fs%n", finished[0], requests.size(),
                            result.get("id"), (System.nanoTime() - started) / 1e9);
            });
        } finally {
            metadata.put("completed", finished[0]);
            metadata.put("complete", scheduling != null && scheduling.terminated() && finished[0] == requests.size());
            metadata.put("scheduling", scheduling); metadata.put("elapsedSeconds", (System.nanoTime() - started) / 1e9);
            metadata.put("heapUsedAtEndBytes", MEMORY.getHeapMemoryUsage().getUsed());
            metadata.put("heapCommittedAtEndBytes", MEMORY.getHeapMemoryUsage().getCommitted());
            metadata.put("poolPeakUsage", ManagementFactory.getMemoryPoolMXBeans().stream().map(pool -> Map.of(
                    "name", pool.getName(), "type", pool.getType().name(), "peakUsedBytes", pool.getPeakUsage().getUsed())).toList());
            Files.writeString(directory.resolve("run.json"), JSON.toJson(metadata), StandardOpenOption.CREATE_NEW);
        }
    }

    private static Map<String, Object> evaluate(JsonObject source, V3NeuralInitializer model,
            long deadlineMillis, long neuralBudgetMillis, int maximumIterations) {
        long caseStart = System.nanoTime();
        var row = new LinkedHashMap<String, Object>();
        row.put("id", source.get("id")); row.put("split", source.get("split")); row.put("design", source.get("design"));
        row.put("input", source.get("input")); row.put("status", "BENCHMARKED");
        V3ColumnInput input = V3NeuralMvpProbe.input(source.getAsJsonObject("input"));
        V3NeuralSeed raw = null;
        var prediction = new LinkedHashMap<String, Object>();
        long start = System.nanoTime(), allocationStart = allocation(), cpuStart = cpu();
        try {
            raw = (model instanceof V3MechanisticTransformerInitializer wrapped
                    ? wrapped.predict(input, deadline(start, deadlineMillis), evidence -> prediction.put("materialCompletion", evidence))
                    : model.predict(input, deadline(start, deadlineMillis))).orElse(null);
        }
        catch (IllegalArgumentException | V3ThermoException | CancellationException unavailable) {
            prediction.put("failure", unavailable.getMessage());
        }
        prediction.put("supported", raw != null); prediction.put("ms", (System.nanoTime() - start) / 1e6);
        prediction.put("allocatedBytes", difference(allocationStart, allocation())); prediction.put("cpuMillis", nanosDifference(cpuStart, cpu()));
        if (raw != null) {
            prediction.put("nativeResidual", residual(raw));
            long baselineStart = System.nanoTime();
            var anchor = V3HybridBaseline.build(input, raw.branch(), deadline(baselineStart, deadlineMillis));
            prediction.put("separateAnchorDiagnostic", Map.of("available", anchor.available(), "reason", anchor.reason(),
                    "milliseconds", (System.nanoTime() - baselineStart) / 1e6, "insideRequestTiming", false));
        }
        if (raw != null) prediction.put("seedPresentedByPipeline", raw);
        row.put("rawPrediction", prediction);
        var modes = V3InitializationOptions.Mode.values();
        int offset = Math.floorMod(source.get("id").getAsString().hashCode(), modes.length);
        for (int j = 0; j < modes.length; j++) {
            var strategy = modes[(offset + j) % modes.length];
            var result = run(input, model, strategy, deadlineMillis, neuralBudgetMillis, maximumIterations, true);
            compareRaw(raw, result);
            row.put(switch (strategy) {
                case CURRENT_ONLY -> "current";
                case LNN_ONLY -> "neural";
                case LNN_FIRST -> "neuralFirst";
            }, result);
        }
        if (raw != null && source.has("seed")) row.put("rawVsTeacher", profileDifference(raw, seed(input, source.getAsJsonObject("seed"))));
        row.put("caseServiceMillis", (System.nanoTime() - caseStart) / 1e6);
        return row;
    }

    // Copied measurement helpers keep serial v1 immutable and preserve its scientific definitions.
    private static void compareRaw(V3NeuralSeed raw, Map<String, Object> result) {
        if (raw != null && result.get("seed") instanceof V3NeuralSeed accepted) result.put("rawVsFinal", profileDifference(raw, accepted));
    }

    private static Map<String, Object> run(V3ColumnInput input, V3NeuralInitializer model, V3InitializationOptions.Mode mode,
            long deadlineMillis, long neuralBudgetMillis, int maximumIterations, boolean capture) {
        long start = System.nanoTime(), allocationStart = allocation(), cpuStart = cpu();
        var row = new LinkedHashMap<String, Object>();
        row.put("heapUsedBeforeBytes", MEMORY.getHeapMemoryUsage().getUsed());
        try {
            V3NeuralSeed[] accepted = {null};
            var control = deadline(start, deadlineMillis);
            var outcome = mode == V3InitializationOptions.Mode.CURRENT_ONLY
                    ? V3ColumnCalculator.calculateWithAcceptedProfile(input, control, profile -> accepted[0] = profile)
                    : V3ColumnCalculator.calculateWithAcceptedProfile(input, control,
                            new V3InitializationOptions(mode, V3InitializationOptions.WetStart.AUTO, maximumIterations, Math.toIntExact(neuralBudgetMillis)), model, profile -> accepted[0] = profile);
            row.put("success", outcome.isSuccess()); row.put("diagnostics", outcome.diagnostics());
            if (outcome instanceof V3ColumnOutcome.Success success) {
                if (accepted[0] == null) throw new IllegalStateException("Accepted native solve omitted its profile");
                row.put("status", "ACCEPTED");
                var water = V3WaterPhaseQualification.assess(accepted[0]);
                row.put("waterQualification", water.grade().name()); row.put("equilibriumQualified", water.qualified());
                row.put("wetTrayCount", water.wetTrayCount()); row.put("waterEvidence", water);
                row.put("formulationRevision", success.result().formulationRevision());
                if (capture) { row.put("seed", accepted[0]); row.put("streams", success.result().streams()); }
            } else {
                var failure = (V3ColumnOutcome.Failure) outcome;
                row.put("status", failure.code().name()); row.put("failure", failure.summary());
                row.put("failureClass", failure.code() == V3SolverFailureCode.INFEASIBLE_SPECIFICATION ? "NATIVE_SOLVER_ADMISSION_OR_PATH_BOUND" : "NATIVE_SOLVER_FAILURE");
            }
        } catch (CancellationException cancelled) {
            row.put("success", false); row.put("status", Thread.currentThread().isInterrupted() ? "CANCELLED" : "DEADLINE_EXCEEDED"); row.put("failure", cancelled.getMessage());
        } catch (V3ThermoException | IllegalArgumentException unsupported) {
            row.put("success", false); row.put("status", "PROPERTY_OR_INPUT_REJECTION"); row.put("failure", unsupported.getMessage());
        }
        row.put("ms", (System.nanoTime() - start) / 1e6); row.put("cold_ms", row.get("ms"));
        row.put("cpuMillis", nanosDifference(cpuStart, cpu())); row.put("allocatedBytes", difference(allocationStart, allocation()));
        row.put("heapUsedAfterBytes", MEMORY.getHeapMemoryUsage().getUsed());
        return row;
    }

    private static Map<String, Object> residual(V3NeuralSeed seed) {
        try {
            var thermo = V3PengRobinsonThermo.fromRegisteredPackage(seed.input().packageId());
            var full = V3ColumnProblemResolver.resolve(seed.input(), seed.branch());
            var state = seed.stateFor(full);
            var wet = seed.wetSetFor(full, V3InitializationOptions.WetStart.PREDICTED_WET);
            var support = V3TruncationSupport.derive(full, 0, state);
            var problem = V3ColumnProblemResolver.withTruncation(full, support, wet);
            state = support.projectSeed(problem, state);
            var flash = thermo.flashTP(seed.input().feedTemperatureKelvin(), full.nodePressurePascal(seed.input().feedStageNumber()),
                    seed.input().feedComponentMolarFlowsMolPerSecond(), V3TraceTruncationPolicy.of(0), thermo.newWorkspace(), () -> {});
            var value = new V3MeshResidualEvaluator(problem, thermo, flash.molarEnthalpyJoulesPerMol()).evaluate(state, thermo.newWorkspace());
            return Map.of("maximumScaledMeshResidual", value.maximumAbsoluteScaledResidual(), "projectedThroughNativeTraceSupport", true);
        } catch (V3ThermoException | IllegalArgumentException invalid) { return Map.of("unavailable", invalid.getMessage()); }
    }

    private static Map<String, Object> profileDifference(V3NeuralSeed raw, V3NeuralSeed actual) {
        double t2 = 0, tMax = 0, f2 = 0, fMax = 0, log2 = 0, w2 = 0, wMax = 0, total2 = 0, totalMax = 0;
        int mismatches = 0, flowCount = 0;
        var rt = raw.temperatures(); var at = actual.temperatures(); var rw = raw.freeWater(); var aw = actual.freeWater();
        var rm = raw.wetTrays(); var am = actual.wetTrays();
        double referenceFlow = Arrays.stream(actual.input().feedComponentMolarFlowsMolPerSecond()).sum();
        for (int n = 0; n < rt.length; n++) {
            double dt = rt[n] - at[n], dw = rw[n] - aw[n]; t2 += dt * dt; w2 += dw * dw;
            tMax = Math.max(tMax, Math.abs(dt)); wMax = Math.max(wMax, Math.abs(dw)); if (rm[n] != am[n]) mismatches++;
        }
        for (boolean liquid : new boolean[]{true, false}) {
            double[][] rf = liquid ? raw.liquid() : raw.vapor(), af = liquid ? actual.liquid() : actual.vapor();
            for (int n = 0; n < rf.length; n++) {
                double totalRaw = 0, totalActual = 0;
                for (int c = 0; c < rf[n].length; c++) {
                    double d = rf[n][c] - af[n][c], dl = Math.log1p(rf[n][c]) - Math.log1p(af[n][c]);
                    f2 += d * d; log2 += dl * dl; fMax = Math.max(fMax, Math.abs(d)); flowCount++;
                    totalRaw += rf[n][c]; totalActual += af[n][c];
                }
                double d = (totalRaw - totalActual) / referenceFlow; total2 += d * d; totalMax = Math.max(totalMax, Math.abs(d));
            }
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("temperatureRmseKelvin", Math.sqrt(t2 / rt.length)); result.put("temperatureMaxAbsKelvin", tMax);
        result.put("componentFlowRmseMolPerSecond", Math.sqrt(f2 / flowCount)); result.put("componentFlowMaxAbsMolPerSecond", fMax);
        result.put("log1pComponentFlowRmse", Math.sqrt(log2 / flowCount)); result.put("phaseTotalFlowRelativeToFeedRmse", Math.sqrt(total2 / (2 * rt.length)));
        result.put("phaseTotalFlowRelativeToFeedMaxAbs", totalMax); result.put("freeWaterRmseMolPerSecond", Math.sqrt(w2 / rt.length));
        result.put("freeWaterMaxAbsMolPerSecond", wMax); result.put("wetMaskMismatches", mismatches); result.put("branchMatches", raw.branch() == actual.branch());
        return result;
    }

    private static V3NeuralSeed seed(V3ColumnInput input, JsonObject json) {
        return new V3NeuralSeed(input, json.get("propertyRevision").getAsString(), V3CondenserPhaseBranch.valueOf(json.get("branch").getAsString()),
                JSON.fromJson(json.get("liquid"), double[][].class), JSON.fromJson(json.get("vapor"), double[][].class),
                JSON.fromJson(json.get("temperatures"), double[].class), JSON.fromJson(json.get("freeWater"), double[].class),
                JSON.fromJson(json.get("wetTrays"), boolean[].class));
    }

    private static V3SolveControl deadline(long start, long milliseconds) {
        return () -> {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("offline worker interrupted");
            if (System.nanoTime() - start >= milliseconds * 1_000_000L) throw new CancellationException("offline wall deadline");
        };
    }
    private static long cpu() { return THREADS.isCurrentThreadCpuTimeSupported() ? THREADS.getCurrentThreadCpuTime() : -1; }
    private static long allocation() { return ALLOCATIONS != null && ALLOCATIONS.isThreadAllocatedMemorySupported() ? ALLOCATIONS.getThreadAllocatedBytes(Thread.currentThread().threadId()) : -1; }
    private static Long difference(long before, long after) { return before >= 0 && after >= before ? after - before : null; }
    private static Double nanosDifference(long before, long after) { Long value = difference(before, after); return value == null ? null : value / 1e6; }
    private static String sha256(Path path) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
}
