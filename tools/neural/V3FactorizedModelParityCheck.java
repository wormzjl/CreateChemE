package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Real gen3 Java inference and feature/target parity on the embedded accepted training fixture only. */
public final class V3FactorizedModelParityCheck {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private V3FactorizedModelParityCheck() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("model-directory output-json");
        Path directory = Path.of(args[0]), output = Path.of(args[1]), modelPath = directory.resolve("factorized-model.json");
        var fixture = JsonParser.parseString(Files.readString(directory.resolve("factorized-feature-parity.json"))).getAsJsonArray();
        if (fixture.isEmpty() || fixture.size() > 16) throw new IllegalArgumentException("Expected 1..16 training fixture rows");
        V3FactorizedNeuralInitializer model;
        try (var stream = Files.newInputStream(modelPath)) { model = V3FactorizedNeuralInitializer.read(stream); }
        var ids = new HashSet<String>(); var rows = new ArrayList<Map<String, Object>>(); var errors = new ErrorSummary();
        for (JsonElement element : fixture) {
            JsonObject row = element.getAsJsonObject();
            if (!"train".equals(row.get("split").getAsString()) || !ids.add(row.get("id").toString()))
                throw new IllegalArgumentException("Parity fixture must contain distinct training rows only");
            V3ColumnInput input = V3NeuralMvpProbe.input(row.getAsJsonObject("input"));
            JsonObject state = row.getAsJsonObject("seed");
            V3NeuralSeed teacher = new V3NeuralSeed(input, state.get("propertyRevision").getAsString(),
                    V3CondenserPhaseBranch.valueOf(state.get("branch").getAsString()), JSON.fromJson(state.get("liquid"), double[][].class),
                    JSON.fromJson(state.get("vapor"), double[][].class), JSON.fromJson(state.get("temperatures"), double[].class),
                    JSON.fromJson(state.get("freeWater"), double[].class), JSON.fromJson(state.get("wetTrays"), boolean[].class));
            double[] global = V3FactorizedNeuralFeatures.global(input);
            double[][] nodes = V3FactorizedNeuralFeatures.nodes(input, teacher.branch()), targets = V3FactorizedNeuralFeatures.targets(teacher);
            compare(global, JSON.fromJson(row.get("global"), double[].class), "global", errors);
            compare(nodes, JSON.fromJson(row.get("nodes"), double[][].class), "nodes", errors);
            compare(targets, JSON.fromJson(row.get("targets"), double[][].class), "targets", errors);
            var seed = model.predict(input, () -> {}).orElseThrow(() -> new IllegalStateException("No real prediction for a parity training case"));
            var measurement = new LinkedHashMap<String, Object>();
            measurement.put("id", row.get("id")); measurement.put("split", "train"); measurement.put("input", input);
            measurement.put("teacherBranch", teacher.branch()); measurement.put("global", global); measurement.put("nodes", nodes);
            measurement.put("targets", targets); measurement.put("prediction", seed); rows.add(measurement);
        }
        var report = new LinkedHashMap<String, Object>(); report.put("modelId", model.modelId());
        report.put("modelSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(modelPath))));
        report.put("absoluteTolerance", 1e-8); report.put("relativeTolerance", 1e-8); report.put("featureValuesCompared", errors.count);
        report.put("maximumFeatureAbsoluteError", errors.maximumAbsolute); report.put("rows", rows);
        Files.writeString(output, JSON.toJson(report), StandardOpenOption.CREATE_NEW);
        System.out.println("Factorized Java feature parity passed on " + rows.size() + " training columns and " + errors.count
                + " values; maximum absolute error=" + errors.maximumAbsolute + "; real predictions written to " + output);
    }

    private static void compare(double[][] actual, double[][] expected, String name, ErrorSummary error) {
        if (actual.length != expected.length) throw new IllegalArgumentException(name + " node mismatch");
        for (int n = 0; n < actual.length; n++) compare(actual[n], expected[n], name + "[" + n + "]", error);
    }
    private static void compare(double[] actual, double[] expected, String name, ErrorSummary error) {
        if (actual.length != expected.length) throw new IllegalArgumentException(name + " width mismatch");
        for (int i = 0; i < actual.length; i++) {
            double difference = Math.abs(actual[i]-expected[i]);
            if (!Double.isFinite(difference) || difference > 1e-8 + 1e-8*Math.max(Math.abs(actual[i]), Math.abs(expected[i])))
                throw new IllegalStateException(name + "[" + i + "] differs: Java=" + actual[i] + ", Python=" + expected[i]);
            error.count++; error.maximumAbsolute = Math.max(error.maximumAbsolute, difference);
        }
    }
    private static final class ErrorSummary { long count; double maximumAbsolute; }
}
