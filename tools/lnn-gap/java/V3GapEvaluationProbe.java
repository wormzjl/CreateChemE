package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.*;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;

/**
 * The LNN-gap campaign: one registered arm of the production path over one cleaned population.
 *
 * <p>The promotion campaign measured the shipped defaults and had nothing to state. This one measures the
 * shipped weights under a stated solver or decoder rule, so it takes an arm manifest and builds exactly what
 * that manifest says: a correction budget, a decoder rule, a candidate rule and a recovery rule. Nothing
 * else moves. The weights are the bundled artifact, the entry point is the same public learned entry the
 * mod's solver service calls, and the strict definition is the one every campaign in this line has used.</p>
 *
 * <p>The baseline arm is required to be the promoted defaults, and it asks {@link V3NeuralModels#bundled()}
 * for its model rather than reconstructing one, so the arm the whole study is measured against is literally
 * the production holder.</p>
 */
public final class V3GapEvaluationProbe {
    static final String REVISION = "lnn-gap-column-evaluation-v1";
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    private V3GapEvaluationProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 6)
            throw new IllegalArgumentException("directory input-jsonl arm-json workers deadline-seconds warmup-json");
        Path directory = Path.of(args[0]), source = Path.of(args[1]), armPath = Path.of(args[2]);
        int workers = Integer.parseInt(args[3]);
        long deadlineMillis = Math.round(Double.parseDouble(args[4]) * 1000);
        if (workers != 10 || deadlineMillis != 30_000)
            throw new IllegalArgumentException("Revision 1 requires ten workers and thirty-second requests");
        if (Files.exists(directory)) throw new IllegalArgumentException("Evaluation output already exists");

        // The promoted defaults, asserted rather than assumed. A build whose shipped defaults drifted from
        // what was promoted must not be able to publish a campaign that looks like a study of that promotion.
        var shipped = V3InitializationOptions.DEFAULT;
        if (shipped.maximumIterations() != 16 || shipped.budgetMilliseconds() != 2_000
                || shipped.wetStart() != V3InitializationOptions.WetStart.AUTO
                || shipped.mode() != V3InitializationOptions.Mode.LNN_FIRST
                || shipped.recovery() != V3InitializationOptions.Recovery.NONE
                || !shipped.correction().equals(new V3InitializationOptions.Correction(8, 48, 8, .5, 8, .9, 1e-6)))
            throw new IllegalArgumentException("Shipped defaults are not the promoted ones: " + shipped);

        if (THREADS.isThreadCpuTimeSupported() && !THREADS.isThreadCpuTimeEnabled()) THREADS.setThreadCpuTimeEnabled(true);
        JsonObject arm = JsonParser.parseString(Files.readString(armPath)).getAsJsonObject();
        Arm pipeline = Arm.of(arm);
        V3NeuralInitializer model = pipeline.model();
        if (model == V3NeuralInitializer.UNAVAILABLE)
            throw new IllegalArgumentException("The bundled model did not load; there is nothing to measure");

        List<JsonObject> requests;
        try (var lines = Files.lines(source)) {
            requests = lines.filter(s -> !s.isBlank()).map(s -> JsonParser.parseString(s).getAsJsonObject()).toList();
        }
        Set<String> ids = new HashSet<>();
        for (var row : requests)
            if (!ids.add(row.get("id").getAsString())) throw new IllegalArgumentException("Duplicate case ID");
        if (requests.isEmpty()) throw new IllegalArgumentException("Empty evaluation population");

        long loadStart = System.nanoTime();
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("revision", REVISION);
        metadata.put("arm", pipeline.name());
        metadata.put("armManifest", arm);
        metadata.put("armManifestSha256", sha256(armPath));
        metadata.put("entryPoint", "V3ColumnCalculator.calculateWithAcceptedProfile(input, control, options, model, observer)");
        metadata.put("entryPointNote", "the public learned entry at cutoff 0, closure 0 and the default liquid-supply "
                + "screen ratio; the observer publishes the accepted profile and changes no arithmetic");
        metadata.put("source", source.toString()); metadata.put("sourceSha256", sha256(source));
        metadata.put("modelSource", pipeline.usesBundledHolder()
                ? "V3NeuralModels.bundled()" : "V3NeuralModels.load(decode, candidates)");
        metadata.put("modelId", model.modelId());
        metadata.put("modelArtifact", V3NeuralModels.ARTIFACT);
        metadata.put("modelArtifactSha256", bundledSha256());
        metadata.put("modelLoadMillis", (System.nanoTime() - loadStart) / 1e6);
        metadata.put("decoderRule", pipeline.decoderRule());
        metadata.put("zeroPhaseFloorFactor", pipeline.zeroPhaseFloorFactor());
        metadata.put("candidateRule", pipeline.candidateRule().name());
        metadata.put("recovery", pipeline.recovery().name());
        metadata.put("rampHandoffBudgetMillis", V3ColumnCalculator.NEURAL_RAMP_HANDOFF_BUDGET_MILLIS);
        metadata.put("correction", JSON.toJsonTree(pipeline.correction()));
        metadata.put("correctionIterations", pipeline.maximumIterations());
        metadata.put("correctionBudgetMillis", pipeline.budgetMillis());
        metadata.put("warmupSha256", sha256(Path.of(args[5])));
        metadata.put("workers", workers); metadata.put("queueCapacity", workers); metadata.put("maximumInFlight", workers);
        metadata.put("deadlineMillis", deadlineMillis); metadata.put("caseCount", requests.size());
        metadata.put("strategies", List.of("current", "neural", "neuralFirst"));
        metadata.put("strategyOrder", "case-id hash rotation of the native mode enum; sequential within each case");
        metadata.put("strictDefinition", "acceptance audit all passed, final Newton certificate at closure 1e-8 with "
                + "backward error <= 1e-12, log flow change <= 1e-8 and temperature step ratio <= 1, and a "
                + "DRY_EQUILIBRIUM or WET_EQUILIBRIUM water qualification of the accepted profile");
        metadata.put("java", System.getProperty("java.version"));
        metadata.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        metadata.put("maximumHeapBytes", Runtime.getRuntime().maxMemory());
        metadata.put("warmup", "two sequential repetitions of three strategies on a fixed input; excluded from metrics");

        var warmup = input(JsonParser.parseString(Files.readString(Path.of(args[5])))
                .getAsJsonObject().getAsJsonObject("input"));
        for (int i = 0; i < 2; i++) for (var strategy : V3InitializationOptions.Mode.values())
            run(warmup, model, strategy, deadlineMillis, pipeline);

        Files.createDirectories(directory);
        var tasks = new ArrayList<Callable<Map<String, Object>>>();
        for (JsonObject request : requests)
            tasks.add(() -> evaluate(request, model, deadlineMillis, pipeline));
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
            Files.writeString(directory.resolve("run.json"), JSON.toJson(metadata), StandardOpenOption.CREATE_NEW);
        }
    }

    /** One registered arm: everything the campaign is allowed to vary, and nothing else. */
    private record Arm(String name, String decoderRule, double zeroPhaseFloorFactor,
            V3AnchorTransformerInitializer.CandidateRule candidateRule, int maximumIterations, int budgetMillis,
            V3InitializationOptions.Correction correction, V3InitializationOptions.Recovery recovery) {
        static Arm of(JsonObject manifest) {
            JsonObject decoder = manifest.getAsJsonObject("decoder");
            String rule = decoder.get("rule").getAsString();
            double floor = switch (rule) {
                case "zero-phase-floor" -> decoder.get("zeroPhaseFloorFactor").getAsDouble();
                case "prune" -> 0.0;
                default -> throw new IllegalArgumentException("Unknown decoder rule " + rule);
            };
            JsonObject c = manifest.getAsJsonObject("correction");
            return new Arm(manifest.get("pipeline").getAsString(), rule, floor,
                    V3AnchorTransformerInitializer.CandidateRule.valueOf(manifest.get("candidates").getAsString()),
                    c.get("maximumIterations").getAsInt(), c.get("budgetMillis").getAsInt(),
                    new V3InitializationOptions.Correction(c.get("extensionBlock").getAsInt(),
                            c.get("extensionMaximumIterations").getAsInt(), c.get("contractionWindow").getAsInt(),
                            c.get("contractionFactor").getAsDouble(), c.get("stallWindow").getAsInt(),
                            c.get("stallFactor").getAsDouble(), c.get("stallResidualFloor").getAsDouble()),
                    V3InitializationOptions.Recovery.valueOf(manifest.get("recovery").getAsString()));
        }

        /** The promoted decoder and candidate rules; only then may the arm use the production holder itself. */
        boolean usesBundledHolder() {
            return "zero-phase-floor".equals(decoderRule)
                    && zeroPhaseFloorFactor == V3NeuralModels.QUALIFIED_ZERO_PHASE_FLOOR_FACTOR
                    && candidateRule == V3AnchorTransformerInitializer.CandidateRule.SINGLE;
        }

        V3NeuralInitializer model() {
            if (usesBundledHolder()) return V3NeuralModels.bundled();
            return V3NeuralModels.load("prune".equals(decoderRule)
                    ? V3FactorizedNeuralFeatures.DecodeOptions.NONE
                    : V3FactorizedNeuralFeatures.DecodeOptions.zeroPhaseFloor(zeroPhaseFloorFactor), candidateRule);
        }

        V3InitializationOptions options(V3InitializationOptions.Mode mode) {
            return new V3InitializationOptions(mode, V3InitializationOptions.WetStart.AUTO,
                    maximumIterations, budgetMillis, correction, recovery);
        }
    }

    private static Map<String, Object> evaluate(JsonObject source, V3NeuralInitializer model,
            long deadlineMillis, Arm arm) {
        long caseStart = System.nanoTime();
        var row = new LinkedHashMap<String, Object>();
        String id = source.get("id").getAsString();
        row.put("id", id);
        V3ColumnInput input = input(source.getAsJsonObject("input"));
        row.put("condition", Map.of("stageCount", input.stageCount(), "heatLoopCount", input.pumparounds().size(),
                "sideDrawCount", input.sideDraws().size(), "steam", !input.steamFeeds().isEmpty()));
        var modes = V3InitializationOptions.Mode.values();
        int offset = Math.floorMod(id.hashCode(), modes.length);
        var byMode = new LinkedHashMap<String, Object>();
        for (int j = 0; j < modes.length; j++) {
            var strategy = modes[(offset + j) % modes.length];
            byMode.put(switch (strategy) {
                case CURRENT_ONLY -> "current";
                case LNN_ONLY -> "neural";
                case LNN_FIRST -> "neuralFirst";
            }, run(input, model, strategy, deadlineMillis, arm));
        }
        // Publish in a fixed key order whatever order they ran in, so journals diff cleanly between blocks.
        var ordered = new LinkedHashMap<String, Object>();
        for (String mode : List.of("current", "neural", "neuralFirst")) ordered.put(mode, byMode.get(mode));
        row.put("modes", ordered);
        row.put("caseServiceMillis", (System.nanoTime() - caseStart) / 1e6);
        return row;
    }

    /** One request, exactly as the mod would admit it under this arm's registered rules. */
    private static Map<String, Object> run(V3ColumnInput input, V3NeuralInitializer model,
            V3InitializationOptions.Mode mode, long deadlineMillis, Arm arm) {
        var options = arm.options(mode);
        long start = System.nanoTime(), cpuStart = cpu();
        var row = new LinkedHashMap<String, Object>();
        try {
            V3NeuralSeed[] accepted = {null};
            var control = deadline(start, deadlineMillis);
            var outcome = V3ColumnCalculator.calculateWithAcceptedProfile(input, control, options, model,
                    profile -> accepted[0] = profile);
            row.put("success", outcome.isSuccess());
            row.put("newtonIterations", outcome.diagnostics().newtonIterations());
            row.put("solvePath", outcome.diagnostics().solvePath());
            row.put("events", outcome.diagnostics().events());
            if (outcome instanceof V3ColumnOutcome.Success success) {
                if (accepted[0] == null) throw new IllegalStateException("Accepted native solve omitted its profile");
                row.put("status", "ACCEPTED");
                var water = V3WaterPhaseQualification.assess(accepted[0]);
                row.put("waterQualification", water.grade().name());
                row.put("equilibriumQualified", water.qualified());
                row.put("wetTrayCount", water.wetTrayCount());
                row.put("formulationRevision", success.result().formulationRevision());
                row.put("outcome", strict(success, water) ? "strict" : "advisory");
            } else {
                var failure = (V3ColumnOutcome.Failure) outcome;
                row.put("status", failure.code().name());
                row.put("failure", failure.summary());
                row.put("outcome", "failed");
            }
        } catch (CancellationException cancelled) {
            row.put("success", false);
            row.put("status", Thread.currentThread().isInterrupted() ? "CANCELLED" : "DEADLINE_EXCEEDED");
            row.put("failure", cancelled.getMessage());
            row.put("outcome", "failed");
        } catch (V3ThermoException | IllegalArgumentException unsupported) {
            row.put("success", false);
            row.put("status", "PROPERTY_OR_INPUT_REJECTION");
            row.put("failure", unsupported.getMessage());
            row.put("outcome", "failed");
        }
        row.put("milliseconds", (System.nanoTime() - start) / 1e6);
        long cpuEnd = cpu();
        row.put("cpuMillis", cpuStart < 0 || cpuEnd < 0 ? null : (cpuEnd - cpuStart) / 1e6);
        return row;
    }

    private static long cpu() {
        return THREADS.isCurrentThreadCpuTimeSupported() ? THREADS.getCurrentThreadCpuTime() : -1;
    }

    /**
     * The study's strict definition, ported from {@code prepare_transformer_data.strict}.
     *
     * <p>A success alone is not strict. Every acceptance-audit check must have passed, the certificate must
     * be a real final Newton step at the frozen 1e-8 closure inside the published gates, and the accepted
     * profile's water phase must be a qualified equilibrium.</p>
     */
    private static boolean strict(V3ColumnOutcome.Success success, V3WaterPhaseQualification.Assessment water) {
        if (!water.qualified()) return false;
        var checks = success.diagnostics().acceptanceAudit().checks();
        if (checks.isEmpty() || !checks.stream().allMatch(V3AcceptanceAudit.Check::passed)) return false;
        var cert = success.diagnostics().convergenceEvidence();
        return cert.hasFinalNewtonStep()
                && cert.closureTolerance() == 1e-8
                && cert.finalLinearBackwardError() <= 1e-12
                && cert.maximumLogFlowChange() <= 1e-8
                && cert.maximumTemperatureStepRatio() <= 1;
    }

    private static V3SolveControl deadline(long start, long milliseconds) {
        return () -> {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("offline worker interrupted");
            if (System.nanoTime() - start >= milliseconds * 1_000_000L) throw new CancellationException("offline wall deadline");
        };
    }

    /** The frozen population's input schema, as every offline probe in this line reads it. */
    static V3ColumnInput input(JsonObject json) {
        var specs = new ArrayList<V3ColumnSpecification>();
        for (JsonElement e : json.getAsJsonArray("specifications")) {
            JsonObject s = e.getAsJsonObject();
            if (s.has("kelvin")) specs.add(new V3ColumnSpecification.CondenserOutletTemperature(s.get("kelvin").getAsDouble()));
            else if (s.has("ratio")) specs.add(new V3ColumnSpecification.OrganicRefluxRatio(s.get("ratio").getAsDouble()));
            else specs.add(new V3ColumnSpecification.ReboilerDuty(s.get("watts").getAsDouble()));
        }
        return new V3ColumnInput(json.get("schemaVersion").getAsInt(), json.get("packageId").getAsString(),
                json.get("assayId").getAsString(),
                new V3ComponentBasis(Arrays.asList(JSON.fromJson(json.getAsJsonObject("componentBasis").get("componentIds"), String[].class))),
                JSON.fromJson(json.get("feedComponentMolarFlowsMolPerSecond"), double[].class),
                json.get("feedTemperatureKelvin").getAsDouble(),
                json.get("stageCount").getAsInt(), json.get("feedStageNumber").getAsInt(),
                json.get("topPressurePascal").getAsDouble(), json.get("stagePressureDropPascal").getAsDouble(), specs,
                Arrays.asList(JSON.fromJson(json.get("sideDraws"), V3SideDrawSpec[].class)),
                Arrays.asList(JSON.fromJson(json.get("steamFeeds"), V3SteamFeedSpec[].class)),
                Arrays.asList(JSON.fromJson(json.get("pumparounds"), V3PumparoundSpec[].class)));
    }

    private static String bundledSha256() throws Exception {
        try (var stream = V3NeuralModels.class.getResourceAsStream(V3NeuralModels.ARTIFACT)) {
            if (stream == null) throw new IllegalStateException("The bundled artifact is not on the classpath");
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
        }
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
