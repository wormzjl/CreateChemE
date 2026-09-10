package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.*;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Reproducible operating-region dataset. Each accepted label retains its water-phase qualification. */
public final class V3MethaneTrainingProbe {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static final int DIMENSIONS = 9;
    private static final double[] METHANE = {.003, .004, .005, .006, .007, .0045, .0055};

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[1]);
        Files.createDirectories(directory);
        switch (args[0]) {
            case "generate" -> generate(directory, Integer.parseInt(args[2]));
            case "generate-wet" -> generateWet(directory, Integer.parseInt(args[2]),
                    args.length > 3 ? Integer.parseInt(args[3]) : 0, args.length > 4 ? Integer.parseInt(args[4]) : METHANE.length);
            case "evaluate" -> evaluate(directory, Path.of(args[2]), Integer.parseInt(args[3]), args.length > 4 ? args[4] : "test");
            default -> throw new IllegalArgumentException("generate directory samplesPerGroup | evaluate directory model repeats");
        }
    }

    private static void generateWet(Path directory, int count, int fromGroup, int toGroup) throws Exception {
        requireFreshJournal(directory);
        if (count < 8 || count > 1024) throw new IllegalArgumentException("samplesPerGroup must be 8..1024");
        if (fromGroup < 0 || toGroup > METHANE.length || fromGroup >= toGroup) throw new IllegalArgumentException("Invalid group range");
        var base = ColumnCalculatorV3BlockEntity.methaneCduInput();
        Files.writeString(directory.resolve("source-input.json"), JSON.toJson(Map.of("input", base)));
        double[] min = new double[V3NeuralFeatures.encode(base).length], max = min.clone();
        Arrays.fill(min, Double.POSITIVE_INFINITY); Arrays.fill(max, Double.NEGATIVE_INFINITY);
        for (int corner = 0; corner < (1 << DIMENSIONS); corner++) {
            double[] u = new double[DIMENSIONS];
            for (int j = 0; j < u.length; j++) u[j] = (corner & (1 << j)) == 0 ? -1 : 1;
            u[0] = 0; u[3] = 0; u[7] = 0; u[8] = 0;
            for (double methane : new double[]{METHANE[0], METHANE[4]}) {
                double[] x = V3NeuralFeatures.encode(request(base, methane, u));
                for (int j = 0; j < x.length; j++) { min[j] = Math.min(min[j], x[j]); max[j] = Math.max(max[j], x[j]); }
            }
        }
        Files.writeString(directory.resolve("design.json"), JSON.toJson(Map.of(
                "seed", 20260911, "samplesPerGroup", count, "inputMin", min, "inputMax", max,
                "split", "Methane fractions 0.3/0.4/0.5/0.6/0.7 mol% train; 0.45% validation; 0.55% test. Whole coupled boundaries withheld.",
                "coverage", "TJL20, 40 trays; wet boundary conditioned on methane composition, feed T 633.15..643.15 K, R 4.02..4.32, steam +/-3%, top PA 80..100%; condenser refined inside 40..75 C. Other inputs fixed.")));
        try (var out = Files.newBufferedWriter(directory.resolve("cases.jsonl"))) {
            int id = fromGroup * count;
            for (int group = fromGroup; group < toGroup; group++) {
                for (double[] u : latinHypercube(count, new SplittableRandom(20260911L + group))) {
                    // Joint energy-condition variations; temperature is solved on each different boundary.
                    u[0] = 0; u[2] = 0; u[3] = 0; u[7] = 0; u[8] = 0;
                    var planned = request(base, METHANE[group], u);
                    String split = group == 5 ? "validation" : group == 6 ? "test" : "train";
                    var row = new LinkedHashMap<String, Object>();
                    row.put("id", id++); row.put("split", split); row.put("input", planned);
                    long start = System.nanoTime();
                    try {
                        var result = V3WetBoundaryTeacher.generate(planned, deadline(start, 30_000));
                        row.put("input", result.input()); row.put("success", result.profile() != null);
                        row.put("teacher", result.detail());
                        if (result.outcome() != null) row.put("diagnostics", result.outcome().diagnostics());
                        if (result.profile() != null) {
                            var qualification = V3WaterPhaseQualification.assess(result.profile());
                            row.put("waterQualification", qualification.grade().name()); row.put("waterEvidence", qualification);
                            row.put("equilibriumQualified", qualification.qualified()); row.put("wetTrayCount", qualification.wetTrayCount());
                            row.put("seed", result.profile()); row.put("targets", V3NeuralFeatures.targets(result.profile()));
                            row.put("formulationRevision", ((V3ColumnOutcome.Success)result.outcome()).result().formulationRevision());
                        } else row.put("failure", result.detail());
                    } catch (CancellationException timeout) { row.put("success", false); row.put("failure", "deadline"); }
                    catch (IllegalArgumentException | com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoException unavailable) {
                        row.put("success", false); row.put("failure", unavailable.getMessage());
                    }
                    var actual = (V3ColumnInput)row.get("input");
                    row.put("features", V3NeuralFeatures.encode(actual)); row.put("cold_ms", (System.nanoTime() - start) / 1e6);
                    out.write(JSON.toJson(row)); out.newLine(); out.flush();
                    System.out.printf(Locale.ROOT, "wet id=%s %s accepted=%s Tc=%.7f ms=%.0f reason=%s%n", row.get("id"), split,
                            row.get("success"), V3NeuralFeatures.encode(actual)[5], row.get("cold_ms"), row.get("failure"));
                }
            }
        }
    }

    private static void generate(Path directory, int count) throws Exception {
        requireFreshJournal(directory);
        if (count < 8 || count > 1024) throw new IllegalArgumentException("samplesPerGroup must be 8..1024");
        V3ColumnInput base = ColumnCalculatorV3BlockEntity.methaneCduInput();
        Files.writeString(directory.resolve("source-input.json"), JSON.toJson(Map.of("input", base)));
        // Bounds derive from a predeclared design, never from held-out labels or statistics.
        double[] min = new double[V3NeuralFeatures.encode(base).length], max = min.clone();
        Arrays.fill(min, Double.POSITIVE_INFINITY); Arrays.fill(max, Double.NEGATIVE_INFINITY);
        for (int corner = 0; corner < (1 << DIMENSIONS); corner++) {
            double[] u = new double[DIMENSIONS];
            for (int j = 0; j < u.length; j++) u[j] = (corner & (1 << j)) == 0 ? -1 : 1;
            for (double methane : new double[]{METHANE[0], METHANE[4]}) {
                double[] x = V3NeuralFeatures.encode(request(base, methane, u));
                for (int j = 0; j < x.length; j++) { min[j] = Math.min(min[j], x[j]); max[j] = Math.max(max[j], x[j]); }
            }
        }
        Files.writeString(directory.resolve("design.json"), JSON.toJson(Map.of(
                "seed", 20260910, "samplesPerGroup", count, "inputMin", min, "inputMax", max,
                "split", "Methane fractions 0.3/0.4/0.5/0.6/0.7 mol% train; 0.45% validation; 0.55% test. Whole columns withheld.",
                "coverage", "TJL20, 40 trays; condenser 40..75 C; feed T 633.15..643.15 K; P 237.5..262.5 kPa; flow +/-2%; R 4.02..4.32; steam +/-3%; top PA 80..100%; side draws +/-1%; light/heavy composition tilt +/-2%.")));
        try (var out = Files.newBufferedWriter(directory.resolve("cases.jsonl"))) {
            int id = 0;
            for (int group = 0; group < METHANE.length; group++) {
                double[][] points = latinHypercube(count, new SplittableRandom(20260910L + group));
                String split = group == 5 ? "validation" : group == 6 ? "test" : "train";
                for (double[] u : points) {
                    sample(out, id++, split, request(base, METHANE[group], u));
                }
            }
            sample(out, id, "train", base);
        }
    }

    private static void requireFreshJournal(Path directory) {
        if (Files.exists(directory.resolve("cases.jsonl")))
            throw new IllegalArgumentException("Dataset journal already exists; choose a new output directory");
    }

    private static double[][] latinHypercube(int count, SplittableRandom random) {
        double[][] u = new double[count][DIMENSIONS];
        for (int j = 0; j < DIMENSIONS; j++) {
            List<Integer> bins = new ArrayList<>();
            for (int i = 0; i < count; i++) bins.add(i);
            for (int i = count - 1; i > 0; i--) Collections.swap(bins, i, random.nextInt(i + 1));
            for (int i = 0; i < count; i++) u[i][j] = 2 * (bins.get(i) + random.nextDouble()) / count - 1;
        }
        return u;
    }

    private static V3ColumnInput request(V3ColumnInput base, double methane, double[] u) {
        double[] feed = base.feedComponentMolarFlowsMolPerSecond();
        double total = Arrays.stream(feed).sum() * (1 + .02 * u[0]);
        double hydrocarbon = 0;
        for (int c = 1; c < feed.length; c++) {
            feed[c] *= 1 + .02 * u[8] * (c <= 8 ? 1 : -1);
            hydrocarbon += feed[c];
        }
        feed[0] = total * methane;
        for (int c = 1; c < feed.length; c++) feed[c] *= total * (1 - methane) / hydrocarbon;
        var specs = List.<V3ColumnSpecification>of(
                new V3ColumnSpecification.CondenserOutletTemperature(330.65 + 17.5 * u[2]),
                new V3ColumnSpecification.OrganicRefluxRatio(4.17 + .15 * u[4]),
                new V3ColumnSpecification.ReboilerDuty(0));
        return new V3ColumnInput(base.schemaVersion(), base.packageId(), base.assayId(), base.componentBasis(),
                feed, 638.15 + 5 * u[1], base.stageCount(), base.feedStageNumber(), 250_000 * (1 + .05 * u[3]),
                base.stagePressureDropPascal(), specs,
                base.sideDraws().stream().map(d -> new V3SideDrawSpec(d.trayNumber(), d.molarFlowMolPerSecond() * (1 + .01 * u[7]))).toList(),
                base.steamFeeds().stream().map(s -> new V3SteamFeedSpec(s.stageNumber(), s.molarFlowMolPerSecond() * (1 + .03 * u[5]), s.temperatureKelvin())).toList(),
                base.pumparounds().stream().map(p -> new V3PumparoundSpec(p.returnTray(), p.drawTray(),
                        p.dutyWatts() * (p.drawTray() == 10 ? .9 + .1 * u[6] : 1), p.split())).toList());
    }

    private static void sample(java.io.BufferedWriter out, int id, String split, V3ColumnInput input) throws Exception {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", id); row.put("split", split); row.put("input", input);
        row.put("features", V3NeuralFeatures.encode(input));
        long start = System.nanoTime();
        V3NeuralSeed[] captured = {null};
        try {
            var outcome = V3ColumnCalculator.calculateWithAcceptedProfile(input, deadline(start, 15_000), seed -> captured[0] = seed);
            row.put("success", outcome.isSuccess()); row.put("diagnostics", outcome.diagnostics());
            if (outcome instanceof V3ColumnOutcome.Success success) {
                if (captured[0] == null) throw new IllegalStateException("Accepted profile missing");
                var qualification = V3WaterPhaseQualification.assess(captured[0]);
                row.put("waterQualification", qualification.grade().name()); row.put("waterEvidence", qualification);
                row.put("equilibriumQualified", qualification.qualified());
                row.put("wetTrayCount", qualification.wetTrayCount());
                row.put("seed", captured[0]); row.put("targets", V3NeuralFeatures.targets(captured[0]));
                row.put("formulationRevision", success.result().formulationRevision());
            } else row.put("failure", ((V3ColumnOutcome.Failure)outcome).summary());
        } catch (CancellationException timeout) { row.put("success", false); row.put("failure", "deadline"); }
        row.put("cold_ms", (System.nanoTime() - start) / 1e6);
        out.write(JSON.toJson(row)); out.newLine(); out.flush();
        System.out.printf(Locale.ROOT, "id=%d %s Tc=%.2f methane=%.4f success=%s water=%s ms=%.0f%n", id, split,
                V3NeuralFeatures.encode(input)[5], V3NeuralFeatures.encode(input)[8], row.get("success"), row.get("waterQualification"), row.get("cold_ms"));
    }

    private static void evaluate(Path directory, Path modelFile, int repeats, String split) throws Exception {
        if (repeats < 1 || repeats > 10) throw new IllegalArgumentException("repeats must be 1..10");
        if (!split.equals("validation") && !split.equals("test")) throw new IllegalArgumentException("Expected validation or test split");
        V3NeuralInitializer model;
        if (modelFile.toString().equals("bundled")) model = V3NeuralModels.bundled();
        else try (var in = Files.newInputStream(modelFile)) { model = V3DenseNeuralInitializer.read(in); }
        var base = ColumnCalculatorV3BlockEntity.methaneCduInput();
        // Explicit untimed warm-up, shared by all comparison modes; never uses a held-out label.
        for (var mode : V3InitializationOptions.Mode.values()) run(base, model, mode);
        try (var out = Files.newBufferedWriter(directory.resolve("evaluation-" + split + ".jsonl"))) {
            for (String line : Files.readAllLines(directory.resolve("cases.jsonl"))) {
                var sample = JsonParser.parseString(line).getAsJsonObject();
                if (!sample.get("split").getAsString().equals(split)) continue;
                var input = V3NeuralMvpProbe.input(sample.getAsJsonObject("input"));
                for (int repeat = 0; repeat < repeats; repeat++) {
                    var row = new LinkedHashMap<String, Object>();
                    row.put("id", sample.get("id").getAsInt()); row.put("repeat", repeat);
                    row.put("teacherWaterQualification", sample.get("waterQualification"));
                    var modes = V3InitializationOptions.Mode.values();
                    int offset = (sample.get("id").getAsInt() + repeat) % modes.length;
                    for (int j = 0; j < modes.length; j++) {
                        var mode = modes[(offset + j) % modes.length];
                        row.put(mode.name(), run(input, model, mode));
                    }
                    out.write(JSON.toJson(row)); out.newLine(); out.flush();
                    System.out.printf("evaluated id=%s repeat=%d%n", sample.get("id"), repeat);
                }
            }
        }
    }

    private static Map<String, Object> run(V3ColumnInput input, V3NeuralInitializer model, V3InitializationOptions.Mode mode) {
        long start = System.nanoTime();
        var row = new LinkedHashMap<String, Object>();
        try {
            V3NeuralSeed[] profile = {null};
            var outcome = V3ColumnCalculator.calculateWithAcceptedProfile(input, deadline(start, 15_000),
                    new V3InitializationOptions(mode, V3InitializationOptions.WetStart.AUTO, 16, 2_000), model, seed -> profile[0] = seed);
            row.put("success", outcome.isSuccess()); row.put("diagnostics", outcome.diagnostics());
            if (outcome instanceof V3ColumnOutcome.Success success) {
                row.put("streams", success.result().streams());
                var qualification = V3WaterPhaseQualification.assess(profile[0]);
                row.put("waterQualification", qualification.grade().name());
                row.put("wetTrayCount", qualification.wetTrayCount());
            }
            else row.put("failure", ((V3ColumnOutcome.Failure)outcome).summary());
        } catch (CancellationException timeout) { row.put("success", false); row.put("failure", "deadline"); }
        row.put("ms", (System.nanoTime() - start) / 1e6);
        return row;
    }

    private static V3SolveControl deadline(long start, long milliseconds) {
        return () -> { if (System.nanoTime() - start > milliseconds * 1_000_000L) throw new CancellationException("offline deadline"); };
    }
}
