package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Independent PyTorch-double/Java parity, parser validation, cancellation and shared-model checks. */
public final class V3TransformerParityCheck {
    private static final Gson JSON = new Gson();
    private V3TransformerParityCheck() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("model-directory");
        Path dir = Path.of(args[0]);
        var model = (V3ColumnTransformerInitializer) V3CandidateModels.read(dir.resolve("model.json"));
        var fixture = JsonParser.parseString(Files.readString(dir.resolve("fixture.json"))).getAsJsonArray();
        if (fixture.isEmpty() || fixture.size() > 16) throw new IllegalArgumentException("Invalid fixture size");
        long[] compared = {0}; double[] maximum = {0}; var predictions = new ArrayList<Map<String, Object>>();
        for (var item : fixture) {
            var row = item.getAsJsonObject();
            if (!row.get("split").getAsString().equals("train")) throw new IllegalArgumentException("Fixture is not training-only");
            var input = V3NeuralMvpProbe.input(row.getAsJsonObject("input"));
            compare(V3GeneralNeuralFeatures.global(input), JSON.fromJson(row.get("global"), double[].class), 1e-8, compared, maximum);
            var nodes = V3GeneralNeuralFeatures.nodes(input, V3CondenserPhaseBranch.TWO_PHASE);
            var wanted = JSON.fromJson(row.get("nodes"), double[][].class);
            for (int n = 0; n < nodes.length; n++) compare(Arrays.copyOf(nodes[n], 96), wanted[n], 1e-8, compared, maximum);
            var raw = model.raw(input, () -> {});
            wanted = JSON.fromJson(row.get("raw"), double[][].class);
            for (int n = 0; n < wanted.length; n++) compare(raw.values()[n], wanted[n], 2e-5, compared, maximum);
            compare(raw.branchLogits(), JSON.fromJson(row.get("branchLogits"), double[].class), 2e-5, compared, maximum);
            var seed = model.predict(input, () -> {}).orElseThrow();
            predictions.add(Map.of("id", row.get("id"), "prediction", seed));
            int[] checkpoints = {0};
            try {
                model.predict(input, () -> { if (++checkpoints[0] == 100) throw new CancellationException("parity cancellation"); });
                throw new IllegalStateException("Prediction swallowed cancellation");
            } catch (CancellationException expected) { if (checkpoints[0] != 100) throw new IllegalStateException("Wrong cancellation boundary"); }
        }
        var input = V3NeuralMvpProbe.input(fixture.get(0).getAsJsonObject().getAsJsonObject("input"));
        String expected = JSON.toJson(model.predict(input, () -> {}).orElseThrow());
        var pool = Executors.newFixedThreadPool(8);
        try {
            var calls = new ArrayList<Callable<String>>();
            for (int i = 0; i < 32; i++) calls.add(() -> JSON.toJson(model.predict(input, () -> {}).orElseThrow()));
            for (var future : pool.invokeAll(calls)) if (!expected.equals(future.get())) throw new IllegalStateException("Concurrent inference differs");
        } finally {
            pool.shutdown();
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) { pool.shutdownNow(); throw new IllegalStateException("Parity workers did not stop"); }
        }
        var malformed = JsonParser.parseString(Files.readString(dir.resolve("model.json"))).getAsJsonObject();
        malformed.getAsJsonObject("weights").getAsJsonObject("embed.weight").getAsJsonArray("shape").set(0, JSON.toJsonTree(63));
        try (var bytes = new ByteArrayInputStream(JSON.toJson(malformed).getBytes(StandardCharsets.UTF_8))) {
            try { V3ColumnTransformerInitializer.read(bytes); throw new IllegalStateException("Malformed dimensions accepted"); }
            catch (IllegalArgumentException expectedFailure) { /* Expected fail-fast parser contract. */ }
        }
        var report = new LinkedHashMap<String, Object>();
        report.put("modelId", model.modelId()); report.put("trainingFixtures", fixture.size());
        report.put("valuesCompared", compared[0]); report.put("maximumAbsoluteError", maximum[0]);
        report.put("rawAbsoluteTolerance", 2e-5); report.put("relativeTolerance", 1e-7);
        report.put("parallelIdenticalPredictions", 32); report.put("cancellationPassed", true); report.put("malformedShapeRejected", true);
        report.put("predictions", predictions);
        Files.writeString(dir.resolve("java-parity.json"), JSON.toJson(report), StandardOpenOption.CREATE_NEW);
        System.out.println(JSON.toJson(Map.of("model", model.modelId(), "values", compared[0], "maxAbs", maximum[0], "parallelPredictions", 32)));
    }
    private static void compare(double[] a, double[] b, double absolute, long[] count, double[] max) {
        if (a.length != b.length) throw new IllegalStateException("Parity width mismatch");
        for (int i = 0; i < a.length; i++) {
            double diff = Math.abs(a[i] - b[i]);
            if (!Double.isFinite(diff) || diff > absolute + 1e-7 * Math.abs(b[i])) throw new IllegalStateException("Parity differs at " + i + ": " + a[i] + " versus " + b[i]);
            count[0]++; max[0] = Math.max(max[0], diff);
        }
    }
}
