package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.*;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoException;
import com.wormzjl.createcheme.science.column.v3.thermo.V3TraceTruncationPolicy;
import java.io.*;
import java.lang.management.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Owned, bounded offline CPU experiment. Journals preserve failed requests without inventing labels. */
public final class V3GeneralTrainingProbe {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final com.sun.management.ThreadMXBean ALLOCATIONS = THREADS instanceof com.sun.management.ThreadMXBean bean ? bean : null;
    private static final MemoryMXBean MEMORY = ManagementFactory.getMemoryMXBean();

    public static void main(String[] args) throws Exception {
        if (args.length < 5) throw new IllegalArgumentException("generate|evaluate directory input-jsonl threads deadline-seconds [model] [neural-budget-ms]");
        String mode = args[0];
        Path directory = Path.of(args[1]), source = Path.of(args[2]);
        int workers = Integer.parseInt(args[3]);
        long deadlineMillis = Math.round(Double.parseDouble(args[4]) * 1000);
        if (workers < 1 || workers > 12 || deadlineMillis < 1000 || deadlineMillis > 300_000) throw new IllegalArgumentException("Invalid resource budget");
        if (!Set.of("generate", "evaluate", "benchmark", "profile-neural", "profile-current").contains(mode)) throw new IllegalArgumentException("Invalid mode");
        boolean needsModel = !mode.equals("generate") && !mode.equals("profile-current");
        if (needsModel && args.length < 6) throw new IllegalArgumentException("Evaluation requires a model file");
        if ((mode.equals("benchmark") || mode.startsWith("profile-")) && workers != 1)
            throw new IllegalArgumentException("Latency/RAM benchmark must run serially");
        Files.createDirectories(directory);
        Path journal = directory.resolve(mode.equals("generate") ? "cases.jsonl" : "evaluation.jsonl");
        if (Files.exists(journal)) throw new IllegalArgumentException("Journal already exists: choose a fresh directory");
        if (ALLOCATIONS != null && ALLOCATIONS.isThreadAllocatedMemorySupported() && !ALLOCATIONS.isThreadAllocatedMemoryEnabled()) ALLOCATIONS.setThreadAllocatedMemoryEnabled(true);
        if (THREADS.isThreadCpuTimeSupported() && !THREADS.isThreadCpuTimeEnabled()) THREADS.setThreadCpuTimeEnabled(true);
        List<JsonObject> requests = new ArrayList<>();
        try (var lines = Files.lines(source)) { lines.filter(s -> !s.isBlank()).forEach(s -> requests.add(JsonParser.parseString(s).getAsJsonObject())); }
        Set<String> ids = new HashSet<>();
        for (var row : requests) if (!ids.add(row.get("id").toString())) throw new IllegalArgumentException("Duplicate case ID");
        V3NeuralInitializer model = V3NeuralInitializer.UNAVAILABLE;
        long modelLoadStart = System.nanoTime(), modelAllocationStart = allocation();
        if (needsModel) model = V3CandidateModels.read(Path.of(args[5]));
        long neuralBudgetMillis = args.length > 6 ? Long.parseLong(args[6]) : 2_000;
        if (neuralBudgetMillis < 1 || neuralBudgetMillis > 60_000) throw new IllegalArgumentException("Neural budget must be 1..60000 milliseconds");
        int maximumIterations = args.length > 7 ? Integer.parseInt(args[7]) : 16;
        new V3InitializationOptions(V3InitializationOptions.Mode.LNN_ONLY, V3InitializationOptions.WetStart.AUTO,
                maximumIterations, Math.toIntExact(neuralBudgetMillis));
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("mode", mode); metadata.put("source", source.toString()); metadata.put("sourceSha256", sha256(source));
        metadata.put("workers", workers); metadata.put("queueCapacity", workers * 2); metadata.put("maximumInFlight", workers * 2);
        metadata.put("deadlineMillis", deadlineMillis); metadata.put("neuralBudgetMillis", neuralBudgetMillis);
        metadata.put("neuralMaximumIterations", maximumIterations);
        metadata.put("caseCount", requests.size()); metadata.put("modelId", model.modelId());
        metadata.put("modelLoadMillis", (System.nanoTime() - modelLoadStart) / 1e6);
        metadata.put("modelLoadAllocatedBytes", difference(modelAllocationStart, allocation()));
        if (needsModel) metadata.put("modelParameterStorageBytes", V3CandidateModels.parameterStorageBytes(model));
        if (needsModel) metadata.put("modelSha256", sha256(Path.of(args[5])));
        metadata.put("java", System.getProperty("java.version")); metadata.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        metadata.put("maximumHeapBytes", Runtime.getRuntime().maxMemory());
        metadata.put("timingScope", workers == 1 ? "serial JVM, elapsed and thread CPU measured separately" : "concurrent throughput run; per-case wall latency includes CPU contention");
        metadata.put("memoryScope", "thread allocated bytes are allocation volume, not retained RAM; heap/RSS values are whole-process measurements");
        // Warm-up is deliberately a fixed original input, independent of every held-out label.
        if (mode.equals("benchmark") || mode.startsWith("profile-")) {
            var base = V3NeuralMvpProbe.input(JsonParser.parseString(Files.readString(Path.of("tools/neural/methane-qualification.json"))).getAsJsonObject().getAsJsonObject("input"));
            for (int i = 0; i < 2; i++) {
                if (!mode.equals("profile-neural")) run(base, model, V3InitializationOptions.Mode.CURRENT_ONLY, deadlineMillis, neuralBudgetMillis, maximumIterations, false);
                if (!mode.equals("profile-current")) run(base, model, V3InitializationOptions.Mode.LNN_ONLY, deadlineMillis, neuralBudgetMillis, maximumIterations, false);
                if (mode.equals("benchmark")) run(base, model, V3InitializationOptions.Mode.LNN_FIRST, deadlineMillis, neuralBudgetMillis, maximumIterations, false);
            }
        }
        for (var pool : ManagementFactory.getMemoryPoolMXBeans()) pool.resetPeakUsage();
        long started = System.nanoTime();
        AtomicInteger workerNumber = new AtomicInteger();
        // ECS publishes completion just before the worker exits its wrapper. Allow the full in-flight bound
        // in the queue so that this short transition cannot reject a replacement submission.
        var executor = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(workers * 2),
                task -> new Thread(task, "v3-general-teacher-" + workerNumber.incrementAndGet()), new ThreadPoolExecutor.AbortPolicy());
        var completed = new ExecutorCompletionService<Map<String, Object>>(executor);
        Set<Future<Map<String, Object>>> pending = new HashSet<>();
        int submitted = 0, finished = 0, accepted = 0, benchmarkCurrentAccepted = 0, benchmarkFirstAccepted = 0;
        V3NeuralInitializer sharedModel = model;
        try (var out = Files.newBufferedWriter(journal, StandardOpenOption.CREATE_NEW)) {
            while (finished < requests.size()) {
                while (submitted < requests.size() && pending.size() < workers * 2) {
                    JsonObject request = requests.get(submitted++);
                    pending.add(completed.submit(() -> execute(request, mode, sharedModel, deadlineMillis, neuralBudgetMillis, maximumIterations)));
                }
                Future<Map<String, Object>> future = completed.take();
                pending.remove(future);
                Map<String, Object> result = future.get(); // Every task exception is observed; errors abort rather than becoming scientific failures.
                out.write(JSON.toJson(result)); out.newLine(); out.flush();
                finished++;
                if (mode.equals("benchmark")) {
                    if (result.get("neural") instanceof Map<?, ?> neural && Boolean.TRUE.equals(neural.get("success"))) accepted++;
                    if (result.get("current") instanceof Map<?, ?> current && Boolean.TRUE.equals(current.get("success"))) benchmarkCurrentAccepted++;
                    if (result.get("neuralFirst") instanceof Map<?, ?> first && Boolean.TRUE.equals(first.get("success"))) benchmarkFirstAccepted++;
                } else if (Boolean.TRUE.equals(result.get("success"))) accepted++;
                if (finished <= 12 || finished % 10 == 0 || finished == requests.size()) System.out.printf(Locale.ROOT,
                        "%s %d/%d accepted=%d id=%s status=%s elapsed=%.1fs%n", mode, finished, requests.size(), accepted,
                        result.get("id"), result.get("status"), (System.nanoTime() - started) / 1e9);
            }
        } finally {
            for (var future : pending) future.cancel(true);
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    if (!executor.awaitTermination(10, TimeUnit.SECONDS)) throw new IllegalStateException("Owned workers ignored cancellation");
                }
            } catch (InterruptedException interrupted) {
                executor.shutdownNow(); Thread.currentThread().interrupt(); throw interrupted;
            }
            metadata.put("completed", finished); metadata.put("accepted", accepted); metadata.put("elapsedSeconds", (System.nanoTime() - started) / 1e9);
            metadata.put("acceptanceCounterScope", mode.equals("generate") || mode.equals("profile-current") ? "CURRENT_ONLY" : "LNN_ONLY");
            if (mode.equals("benchmark")) {
                metadata.put("currentAccepted", benchmarkCurrentAccepted);
                metadata.put("neuralFirstAccepted", benchmarkFirstAccepted);
            }
            metadata.put("heapUsedAtEndBytes", MEMORY.getHeapMemoryUsage().getUsed());
            metadata.put("heapCommittedAtEndBytes", MEMORY.getHeapMemoryUsage().getCommitted());
            metadata.put("poolPeakUsage", ManagementFactory.getMemoryPoolMXBeans().stream().map(pool -> Map.of(
                    "name", pool.getName(), "type", pool.getType().name(), "peakUsedBytes", pool.getPeakUsage().getUsed())).toList());
            Files.writeString(directory.resolve("run.json"), JSON.toJson(metadata));
        }
    }

    private static Map<String, Object> execute(JsonObject source, String mode, V3NeuralInitializer model, long deadlineMillis, long neuralBudgetMillis, int maximumIterations) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", source.get("id")); row.put("split", source.get("split")); row.put("design", source.get("design"));
        row.put("input", source.get("input")); row.put("sourceSuccess", source.get("success")); row.put("sourceStatus", source.get("status"));
        if ((source.has("excluded") && source.get("excluded").getAsBoolean()) || source.has("exclusion")) {
            row.put("success", false); row.put("status", "EXCLUDED_BY_DESIGN"); row.put("exclusion", source.get("exclusion")); return row;
        }
        final V3ColumnInput input;
        try { input = V3NeuralMvpProbe.input(source.getAsJsonObject("input")); }
        catch (IllegalArgumentException invalid) {
            row.put("success", false); row.put("status", "INVALID_INPUT"); row.put("failure", invalid.getMessage()); return row;
        }
        if (mode.equals("generate") || mode.equals("profile-current"))
            row.putAll(run(input, model, V3InitializationOptions.Mode.CURRENT_ONLY, deadlineMillis, neuralBudgetMillis, maximumIterations, true));
        else if (mode.equals("profile-neural"))
            row.putAll(run(input, model, V3InitializationOptions.Mode.LNN_ONLY, deadlineMillis, neuralBudgetMillis, maximumIterations, true));
        else {
            V3NeuralSeed[] raw = {null};
            var prediction = new LinkedHashMap<String, Object>();
            long start = System.nanoTime(), allocationStart = allocation(), cpuStart = cpu();
            try { raw[0] = model.predict(input, deadline(start, deadlineMillis)).orElse(null); }
            catch (IllegalArgumentException | V3ThermoException unavailable) { prediction.put("failure", unavailable.getMessage()); }
            catch (CancellationException timeout) { prediction.put("failure", timeout.getMessage()); }
            prediction.put("supported", raw[0] != null); prediction.put("ms", (System.nanoTime() - start) / 1e6);
            prediction.put("allocatedBytes", difference(allocationStart, allocation())); prediction.put("cpuMillis", nanosDifference(cpuStart, cpu()));
            if (raw[0] != null) prediction.put("nativeResidual", residual(raw[0]));
            row.put("rawPrediction", prediction);
            // Timing includes a fresh prediction, exactly as runtime does; raw diagnostics are measured separately.
            if (mode.equals("benchmark")) {
                var modes = V3InitializationOptions.Mode.values();
                int offset = Math.floorMod(source.get("id").getAsString().hashCode(), modes.length);
                for (int j = 0; j < modes.length; j++) {
                    var strategy = modes[(offset + j) % modes.length];
                    var result = run(input, model, strategy,
                            deadlineMillis, neuralBudgetMillis, maximumIterations, true);
                    compareRaw(raw[0], result);
                    row.put(switch (strategy) {
                        case CURRENT_ONLY -> "current";
                        case LNN_ONLY -> "neural";
                        case LNN_FIRST -> "neuralFirst";
                    }, result);
                }
                row.put("status", "BENCHMARKED");
            } else {
                var result = run(input, model, V3InitializationOptions.Mode.LNN_ONLY, deadlineMillis, neuralBudgetMillis, maximumIterations, true);
                compareRaw(raw[0], result); row.putAll(result);
            }
            // Compare raw output with the original initializer's accepted profile wherever one exists.
            if (raw[0] != null && source.has("seed")) {
                V3NeuralSeed teacher = seed(input, source.getAsJsonObject("seed"));
                row.put("rawVsTeacher", profileDifference(raw[0], teacher));
            }
        }
        return row;
    }

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
