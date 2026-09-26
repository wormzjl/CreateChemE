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
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Fixed TRAIN-only paired experiment; all request state is owned by one worker. */
public final class V3MechanisticSeedProbe {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final int WORKERS = 10;
    private static final int CASES = 20;
    private static final List<String> TREATMENTS = List.of("raw", "materialCompletion");

    private V3MechanisticSeedProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 5 || !(args[0].equals("check") || args[0].equals("run")))
            throw new IllegalArgumentException("check|run fixtures model output plan-or-dash");
        Path source = Path.of(args[1]), modelPath = Path.of(args[2]), output = Path.of(args[3]);
        if (Files.exists(output)) throw new IllegalArgumentException("Probe output already exists");
        List<JsonObject> rows;
        try (var lines = Files.lines(source)) {
            rows = lines.filter(s -> !s.isBlank()).map(s -> JsonParser.parseString(s).getAsJsonObject()).toList();
        }
        if (rows.size() != CASES || rows.stream().map(r -> r.get("id").getAsString()).distinct().count() != CASES)
            throw new IllegalArgumentException("Expected twenty distinct fixed TRAIN inputs");
        var inputs = new ArrayList<V3ColumnInput>();
        for (var row : rows) {
            if (!row.get("split").getAsString().equals("train") || row.has("seed"))
                throw new IllegalArgumentException("Input-only TRAIN fixtures required");
            V3ColumnInput input = V3NeuralMvpProbe.input(row.getAsJsonObject("input"));
            if (!input.steamFeeds().isEmpty() || !input.sideDraws().isEmpty())
                throw new IllegalArgumentException("Probe is restricted to dry inputs without side draws");
            inputs.add(input);
        }
        V3NeuralInitializer raw = V3CandidateModels.read(modelPath);
        var hybrid = new V3MechanisticTransformerInitializer(raw);
        if (args[0].equals("check")) {
            var checked = check(inputs, raw, hybrid);
            checked.put("sourceSha256", sha(source));
            checked.put("modelSha256", sha(modelPath));
            Files.writeString(output, JSON.toJson(checked), StandardOpenOption.CREATE_NEW);
            System.out.println(JSON.toJson(checked));
            return;
        }
        if (args[4].equals("-")) throw new IllegalArgumentException("Measured requests require frozen registration");
        if (THREADS.isThreadCpuTimeSupported() && !THREADS.isThreadCpuTimeEnabled())
            THREADS.setThreadCpuTimeEnabled(true);
        for (int i = 0; i < 2; i++) {
            raw.predict(inputs.getFirst(), deadline(30_000));
            hybrid.predict(inputs.getFirst(), deadline(30_000));
        }
        Files.createDirectories(output);
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("revision", "hybrid-initializer-feasibility-v1");
        metadata.put("sourceSha256", sha(source));
        metadata.put("modelSha256", sha(modelPath));
        metadata.put("planSha256", sha(Path.of(args[4])));
        metadata.put("workers", WORKERS);
        metadata.put("caseCount", CASES);
        metadata.put("treatments", TREATMENTS);
        metadata.put("mode", "LNN_FIRST");
        metadata.put("requestDeadlineMillis", 30_000);
        metadata.put("neuralBudgetMillis", 2_000);
        metadata.put("maximumIterations", 16);
        metadata.put("java", System.getProperty("java.version"));
        metadata.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        metadata.put("maximumHeapBytes", Runtime.getRuntime().maxMemory());
        metadata.put("diagnosticsOutsideMeasuredRequests", true);
        metadata.put("order", "case ID hash parity; sequential treatments within each case");
        metadata.put("warmup", "two prediction-only repetitions per treatment on first fixed TRAIN input");
        var tasks = new ArrayList<Callable<Map<String, Object>>>();
        for (int i = 0; i < CASES; i++) {
            JsonObject row = rows.get(i);
            V3ColumnInput input = inputs.get(i);
            tasks.add(() -> evaluate(row, input, raw, hybrid));
        }
        int[] completed = {0};
        long started = System.nanoTime();
        V3BoundedEvaluation.Summary scheduling = null;
        try (var writer = Files.newBufferedWriter(output.resolve("evaluation.jsonl"), StandardOpenOption.CREATE_NEW)) {
            scheduling = V3BoundedEvaluation.run(tasks, WORKERS, (row, wait) -> {
                row.put("queueWaitMillis", wait);
                writer.write(JSON.toJson(row));
                writer.newLine();
                writer.flush();
                System.out.printf(Locale.ROOT, "hybrid probe %d/%d id=%s elapsed=%.1fs%n",
                        ++completed[0], CASES, row.get("id"), (System.nanoTime() - started) / 1e9);
            });
        } finally {
            metadata.put("scheduling", scheduling);
            metadata.put("completed", completed[0]);
            metadata.put("complete", scheduling != null && scheduling.terminated() && completed[0] == CASES);
            metadata.put("elapsedSeconds", (System.nanoTime() - started) / 1e9);
            Files.writeString(output.resolve("run.json"), JSON.toJson(metadata), StandardOpenOption.CREATE_NEW);
        }
    }

    private static Map<String, Object> evaluate(JsonObject fixture, V3ColumnInput input,
            V3NeuralInitializer raw, V3MechanisticTransformerInitializer hybrid) {
        var row = new LinkedHashMap<String, Object>();
        String id = fixture.get("id").getAsString();
        row.put("id", id);
        row.put("split", "train");
        row.put("input", fixture.get("input"));
        var diagnostic = new LinkedHashMap<String, Object>();
        V3NeuralSeed initial = raw.predict(input, deadline(30_000)).orElse(null);
        diagnostic.put("rawSupported", initial != null);
        if (initial != null) {
            V3MechanisticTransformerInitializer.Evidence[] evidence = {null};
            V3NeuralSeed prepared = V3MechanisticTransformerInitializer.prepare(
                    initial, deadline(30_000), value -> evidence[0] = value);
            diagnostic.put("preparation", evidence[0]);
            diagnostic.put("raw", describe(initial));
            diagnostic.put("prepared", describe(prepared));
            diagnostic.put("profileChange", profileChange(initial, prepared));
        }
        row.put("diagnostics", diagnostic);
        int offset = Math.floorMod(id.hashCode(), 2);
        for (int index = 0; index < 2; index++) {
            String treatment = TREATMENTS.get((offset + index) % 2);
            row.put(treatment, request(input, raw, hybrid, treatment.equals("materialCompletion")));
        }
        return row;
    }

    private static Map<String, Object> request(V3ColumnInput input, V3NeuralInitializer raw,
            V3MechanisticTransformerInitializer hybrid, boolean prepared) {
        var row = new LinkedHashMap<String, Object>();
        var preparation = new LinkedHashMap<String, Object>();
        V3NeuralInitializer candidate = new V3NeuralInitializer() {
            @Override public String modelId() { return prepared ? hybrid.modelId() : raw.modelId(); }
            @Override public Optional<V3NeuralSeed> predict(V3ColumnInput requested, V3SolveControl control) {
                long started = System.nanoTime();
                try {
                    return prepared ? hybrid.predict(requested, control, evidence -> preparation.put("evidence", evidence))
                            : raw.predict(requested, control);
                } finally {
                    preparation.put("predictionAndPreparationMillis", (System.nanoTime() - started) / 1e6);
                }
            }
        };
        long started = System.nanoTime(), cpuStarted = cpu();
        try {
            V3NeuralSeed[] accepted = {null};
            var outcome = V3ColumnCalculator.calculateWithAcceptedProfile(input, deadline(30_000),
                    new V3InitializationOptions(V3InitializationOptions.Mode.LNN_FIRST,
                            V3InitializationOptions.WetStart.AUTO, 16, 2_000), candidate, seed -> accepted[0] = seed);
            row.put("success", outcome.isSuccess());
            row.put("diagnostics", outcome.diagnostics());
            row.put("classicalFallback", outcome.diagnostics().events().stream()
                    .anyMatch(event -> event.contains("initializer=CURRENT_BACKUP")));
            if (outcome instanceof V3ColumnOutcome.Success) {
                if (accepted[0] == null) throw new IllegalStateException("Accepted solve omitted its profile");
                var water = V3WaterPhaseQualification.assess(accepted[0]);
                row.put("status", "ACCEPTED");
                row.put("equilibriumQualified", water.qualified());
                row.put("waterQualification", water.grade().name());
                row.put("seed", accepted[0]);
            } else {
                var failure = (V3ColumnOutcome.Failure) outcome;
                row.put("status", failure.code().name());
                row.put("failure", failure.summary());
            }
        } catch (CancellationException cancelled) {
            if (Thread.currentThread().isInterrupted()) throw cancelled;
            row.put("success", false);
            row.put("status", "DEADLINE_EXCEEDED");
            row.put("failure", cancelled.getMessage());
        } catch (V3ThermoException | IllegalArgumentException rejected) {
            row.put("success", false);
            row.put("status", "PROPERTY_OR_INPUT_REJECTION");
            row.put("failure", rejected.getMessage());
        }
        row.put("ms", (System.nanoTime() - started) / 1e6);
        long cpuEnded = cpu();
        row.put("cpuMillis", cpuStarted < 0 || cpuEnded < 0 ? null : (cpuEnded - cpuStarted) / 1e6);
        row.put("preprocessing", preparation);
        return row;
    }

    /** Dry/no-draw native material rows on the public component axis; one owned copy per diagnostic. */
    private record MaterialRows(double[][] liquid, double[][] vapor, double[] feed,
                                boolean[] condenserLiquid, int feedNode, double refluxFraction) {
        static MaterialRows of(V3NeuralSeed seed) {
            var problem = V3ColumnProblemResolver.resolve(seed.input(), seed.branch());
            double[] feed = seed.input().feedComponentMolarFlowsMolPerSecond();
            boolean[] liquid = new boolean[feed.length];
            for (int c = 0; c < problem.activeComponentBasis().componentCount(); c++)
                liquid[problem.activeComponentBasis().publicIndex(c)] = problem.hasLiquidUnknown(0, c);
            double reflux = seed.input().specifications().stream()
                    .filter(V3ColumnSpecification.OrganicRefluxRatio.class::isInstance)
                    .map(V3ColumnSpecification.OrganicRefluxRatio.class::cast).findFirst().orElseThrow().ratio();
            return new MaterialRows(seed.liquid(), seed.vapor(), feed, liquid,
                    seed.input().feedStageNumber(), reflux / (1 + reflux));
        }

        double physical(int node, int component) {
            double overheadLiquid = condenserLiquid[component] ? liquid[0][component] : 0;
            if (node == 0) return vapor[1][component] - vapor[0][component] - overheadLiquid;
            if (node == liquid.length - 1)
                return liquid[node - 1][component] - liquid[node][component] - vapor[node][component];
            double arriving = node == 1 ? refluxFraction * overheadLiquid : liquid[node - 1][component];
            double enteringFeed = node == feedNode ? feed[component] : 0;
            return arriving + vapor[node + 1][component] + enteringFeed - liquid[node][component] - vapor[node][component];
        }
    }

    private static double materialDefect(V3NeuralSeed seed) {
        MaterialRows material = MaterialRows.of(seed);
        double[] feed = material.feed();
        double total = Arrays.stream(feed).sum(), maximum = 0;
        for (int n = 0; n < seed.input().stageCount() + 2; n++)
            for (int c = 0; c < feed.length; c++)
                maximum = Math.max(maximum, Math.abs(material.physical(n, c)) / Math.max(feed[c], total * 1e-12));
        return maximum;
    }

    private static Map<String, Object> describe(V3NeuralSeed seed) {
        var description = new LinkedHashMap<String, Object>();
        description.put("maximumMaterialDefectOverInputComponentScale", materialDefect(seed));
        description.put("branch", seed.branch().name());
        try {
            var full = V3ColumnProblemResolver.resolve(seed.input(), seed.branch());
            var state = seed.stateFor(full);
            var support = V3TruncationSupport.derive(full, 0, state);
            var problem = V3ColumnProblemResolver.withTruncation(full, support);
            state = support.projectSeed(problem, state);
            var thermo = V3PengRobinsonThermo.fromRegisteredPackage(seed.input().packageId());
            var flash = thermo.flashTP(seed.input().feedTemperatureKelvin(), full.nodePressurePascal(seed.input().feedStageNumber()),
                    seed.input().feedComponentMolarFlowsMolPerSecond(), V3TraceTruncationPolicy.of(0), thermo.newWorkspace(), deadline(30_000)::checkpoint);
            var residual = new V3MeshResidualEvaluator(problem, thermo, flash.molarEnthalpyJoulesPerMol())
                    .evaluate(state, thermo.newWorkspace());
            var families = new LinkedHashMap<String, Map<String, Object>>();
            for (var row : residual.rows()) {
                var family = families.computeIfAbsent(row.equation().family().name(), ignored -> {
                    var value = new LinkedHashMap<String, Object>();
                    value.put("rows", 0);
                    value.put("maximumAbsolutePhysical", 0.0);
                    value.put("maximumAbsoluteScaled", 0.0);
                    return value;
                });
                family.put("rows", (int) family.get("rows") + 1);
                family.put("maximumAbsolutePhysical", Math.max((double) family.get("maximumAbsolutePhysical"), Math.abs(row.physicalValue())));
                family.put("maximumAbsoluteScaled", Math.max((double) family.get("maximumAbsoluteScaled"), Math.abs(row.scaledValue())));
            }
            description.put("nativeFamilies", families);
            description.put("nativeRowCount", residual.rows().size());
            description.put("projectedThroughNativeSupport", true);
            description.put("nativeSupport", Map.of("truncatedPoints", support.truncatedPointCount(),
                    "onePhasePoints", support.onePhasePointCount(), "presentPhases", support.presentPhaseCount(),
                    "note", support.note()));
        } catch (V3ThermoException | IllegalArgumentException unavailable) {
            description.put("nativeResidualUnavailable", unavailable.getClass().getSimpleName() + ": " + unavailable.getMessage());
        }
        return description;
    }

    private static Map<String, Object> profileChange(V3NeuralSeed raw, V3NeuralSeed prepared) {
        double maximumTemperatureChange = 0, flowSquares = 0, maximumFlow = 0;
        int count = 0, supportChanges = 0;
        double[] rawT = raw.temperatures(), preparedT = prepared.temperatures();
        for (int i = 0; i < rawT.length; i++) maximumTemperatureChange = Math.max(maximumTemperatureChange, Math.abs(rawT[i] - preparedT[i]));
        for (boolean liquid : List.of(true, false)) {
            double[][] a = liquid ? raw.liquid() : raw.vapor(), b = liquid ? prepared.liquid() : prepared.vapor();
            for (int n = 0; n < a.length; n++) for (int c = 0; c < a[n].length; c++) {
                double change = b[n][c] - a[n][c];
                flowSquares += change * change;
                maximumFlow = Math.max(maximumFlow, Math.abs(change));
                if ((a[n][c] == 0) != (b[n][c] == 0)) supportChanges++;
                count++;
            }
        }
        return Map.of("maximumTemperatureChangeKelvin", maximumTemperatureChange,
                "componentFlowRmseChangeMolPerSecond", Math.sqrt(flowSquares / count),
                "maximumComponentFlowChangeMolPerSecond", maximumFlow, "exactZeroPatternChanges", supportChanges);
    }

    private static Map<String, Object> check(List<V3ColumnInput> inputs, V3NeuralInitializer raw,
            V3MechanisticTransformerInitializer hybrid) throws Exception {
        var originals = new ArrayList<V3NeuralSeed>();
        var expected = new ArrayList<String>();
        var supported = new ArrayList<Integer>();
        int[] nativeParityRows = {0}, nonzeroParityRows = {0};
        double maximumClosure = 0;
        for (V3ColumnInput input : inputs) {
            V3NeuralSeed seed = raw.predict(input, deadline(30_000)).orElseThrow();
            String before = JSON.toJson(seed);
            V3MechanisticTransformerInitializer.Evidence[] evidence = {null};
            V3NeuralSeed prepared = V3MechanisticTransformerInitializer.prepare(seed, deadline(30_000), value -> evidence[0] = value);
            if (!before.equals(JSON.toJson(seed))) throw new IllegalStateException("Preparation mutated source seed");
            originals.add(seed);
            expected.add(JSON.toJson(prepared));
            if (evidence[0].prepared()) {
                supported.add(originals.size() - 1);
                maximumClosure = Math.max(maximumClosure, materialDefect(prepared));
            }
            checkNativeMaterialRows(seed, nativeParityRows, nonzeroParityRows);
        }
        if (supported.isEmpty() || maximumClosure > 1e-8 || nativeParityRows[0] == 0 || nonzeroParityRows[0] == 0)
            throw new IllegalStateException("Insufficient material-completion/parity evidence: prepared=" + supported.size()
                    + ", closure=" + maximumClosure + ", nativeRows=" + nativeParityRows[0] + ", nonzeroRows=" + nonzeroParityRows[0]);
        var entered = new CountDownLatch(WORKERS);
        var tasks = new ArrayList<Callable<Boolean>>();
        for (int i = 0; i < 40; i++) {
            int request = i, fixture = supported.get(i % supported.size());
            tasks.add(() -> {
                int[] checkpoints = {0};
                V3SolveControl limit = deadline(30_000);
                V3SolveControl synchronizedControl = () -> {
                    limit.checkpoint();
                    // Checkpoint 2 is immediately before the first PR call.
                    if (request < WORKERS && ++checkpoints[0] == 2) {
                        entered.countDown();
                        try {
                            if (!entered.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Preparation barrier timed out");
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new CancellationException("Preparation check interrupted");
                        }
                    }
                };
                V3MechanisticTransformerInitializer.Evidence[] evidence = {null};
                var result = V3MechanisticTransformerInitializer.prepare(originals.get(fixture), synchronizedControl, value -> evidence[0] = value);
                if (!evidence[0].prepared() || !expected.get(fixture).equals(JSON.toJson(result)))
                    throw new IllegalStateException("Serial/parallel completion mismatch");
                return true;
            });
        }
        var scheduling = V3BoundedEvaluation.run(tasks, WORKERS, (ignored, wait) -> {});
        if (!scheduling.terminated() || scheduling.maximumActive() != WORKERS
                || scheduling.distinctWorkerThreads() != WORKERS || entered.getCount() != 0)
            throw new IllegalStateException("Incomplete ten-worker preparation check");
        int[] checkpoints = {0};
        V3MechanisticTransformerInitializer.Evidence[] cancelledEvidence = {null};
        boolean cancelled = false;
        try {
            V3MechanisticTransformerInitializer.prepare(originals.get(supported.getFirst()), () -> {
                if (++checkpoints[0] == 3) throw new CancellationException("injected after first PR call");
            }, evidence -> cancelledEvidence[0] = evidence);
        } catch (CancellationException expectedCancellation) { cancelled = true; }
        if (!cancelled || cancelledEvidence[0] == null || cancelledEvidence[0].propertyCalls() != 1
                || !cancelledEvidence[0].status().equals("ABORTED"))
            throw new IllegalStateException("Thermodynamic boundary did not propagate cancellation");
        for (int i = 0; i < inputs.size(); i++) {
            if (!JSON.toJson(originals.get(i)).equals(JSON.toJson(raw.predict(inputs.get(i), deadline(30_000)).orElseThrow()))
                    || !expected.get(i).equals(JSON.toJson(hybrid.predict(inputs.get(i), deadline(30_000)).orElseThrow())))
                throw new IllegalStateException("Subsequent prediction changed");
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("passed", true);
        result.put("trainFixtures", inputs.size());
        result.put("preparedFixtures", supported.size());
        result.put("parallelPreparationCalls", 40);
        result.put("synchronizedInsidePropertyBoundary", true);
        result.put("cancellationAfterFirstPropertyCall", true);
        result.put("sourceAndSubsequentPredictionsUnchanged", true);
        result.put("maximumPreparedMaterialDefect", maximumClosure);
        result.put("nativeMaterialParityRows", nativeParityRows[0]);
        result.put("nonzeroNativeMaterialParityRows", nonzeroParityRows[0]);
        result.put("materialParityUsesNativeSupportProjectedStates", true);
        result.put("scheduling", scheduling);
        return result;
    }

    private static void checkNativeMaterialRows(V3NeuralSeed seed, int[] checked, int[] nonzero) {
        try {
            var full = V3ColumnProblemResolver.resolve(seed.input(), seed.branch());
            var state = seed.stateFor(full);
            var support = V3TruncationSupport.derive(full, 0, state);
            var problem = V3ColumnProblemResolver.withTruncation(full, support);
            state = support.projectSeed(problem, state);
            var thermo = V3PengRobinsonThermo.fromRegisteredPackage(seed.input().packageId());
            var flash = thermo.flashTP(seed.input().feedTemperatureKelvin(), problem.nodePressurePascal(seed.input().feedStageNumber()),
                    seed.input().feedComponentMolarFlowsMolPerSecond(), thermo.newWorkspace());
            var residual = new V3MeshResidualEvaluator(problem, thermo, flash.molarEnthalpyJoulesPerMol())
                    .evaluate(state, thermo.newWorkspace());
            MaterialRows material = MaterialRows.of(V3NeuralSeed.capture(problem, state, seed.propertyRevision()));
            for (var row : residual.rows()) if (row.equation().family() == V3DegreeOfFreedomLedger.EquationFamily.COMPONENT_MATERIAL_BALANCE) {
                int component = problem.activeComponentBasis().publicIndex(row.equation().component());
                double actual = material.physical(row.equation().node(), component);
                if (Math.abs(actual - row.physicalValue()) > 1e-10 * Math.max(1, Math.abs(row.physicalValue())))
                    throw new IllegalStateException("Diagnostic material equation differs from native row");
                checked[0]++;
                if (Math.abs(row.physicalValue()) > 1e-5) nonzero[0]++;
            }
        } catch (V3ThermoException | IllegalArgumentException unavailable) {
            // Raw property/support failures remain observable in the actual probe.
            // Preflight requires a nonempty, nontrivial native parity sample overall.
        }
    }

    private static V3SolveControl deadline(long milliseconds) {
        long started = System.nanoTime();
        return () -> {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("Worker interrupted");
            if (System.nanoTime() - started >= milliseconds * 1_000_000)
                throw new CancellationException("Probe request deadline exceeded");
        };
    }

    private static long cpu() { return THREADS.isCurrentThreadCpuTimeSupported() ? THREADS.getCurrentThreadCpuTime() : -1; }
    private static String sha(Path path) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
}
