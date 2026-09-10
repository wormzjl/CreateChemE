package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Checks the exported Python feature fixture and records real Java inference on its training rows only. */
public final class V3GeneralModelParityCheck {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static final double ABSOLUTE_TOLERANCE = 1e-8;
    private static final double RELATIVE_TOLERANCE = 1e-8;

    private V3GeneralModelParityCheck() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("model-directory output-json");
        Path directory = Path.of(args[0]), output = Path.of(args[1]);
        var expected = new LinkedHashMap<String, JsonObject>();
        for (JsonElement element : JsonParser.parseString(Files.readString(directory.resolve("general-feature-parity.json"))).getAsJsonArray()) {
            JsonObject row = element.getAsJsonObject();
            if (expected.put(row.get("id").toString(), row) != null) throw new IllegalArgumentException("Duplicate parity case ID");
        }
        if (expected.isEmpty() || expected.size() > 16) throw new IllegalArgumentException("Parity check requires 1..16 training cases");
        var selected = new LinkedHashMap<String, JsonObject>();
        try (var lines = Files.newBufferedReader(directory.resolve("cases.jsonl"))) {
            String line;
            while ((line = lines.readLine()) != null) {
                if (line.isBlank()) continue;
                // Stop parsing an unrelated journal row after its ID; held-out targets are never inspected.
                String id = readId(line);
                if (!expected.containsKey(id)) continue;
                JsonObject row = JsonParser.parseString(line).getAsJsonObject();
                if (!"train".equals(row.get("split").getAsString()) || !row.get("success").getAsBoolean() || !row.has("seed"))
                    throw new IllegalArgumentException("Parity fixture must select accepted training rows only");
                if (selected.put(id, row) != null) throw new IllegalArgumentException("Duplicate source case ID");
            }
        }
        if (selected.size() != expected.size()) throw new IllegalArgumentException("Missing parity training row");
        Path modelPath = directory.resolve("general-model.json");
        V3GeneralNeuralInitializer model;
        try (var stream = Files.newInputStream(modelPath)) { model = V3GeneralNeuralInitializer.read(stream); }
        var measurements = new ArrayList<Map<String, Object>>();
        var error = new ErrorSummary();
        for (var entry : expected.entrySet()) {
            JsonObject row = selected.get(entry.getKey()), fixture = entry.getValue();
            V3ColumnInput input = V3NeuralMvpProbe.input(row.getAsJsonObject("input"));
            JsonObject state = row.getAsJsonObject("seed");
            V3NeuralSeed teacher = new V3NeuralSeed(input, state.get("propertyRevision").getAsString(),
                    V3CondenserPhaseBranch.valueOf(state.get("branch").getAsString()),
                    JSON.fromJson(state.get("liquid"), double[][].class), JSON.fromJson(state.get("vapor"), double[][].class),
                    JSON.fromJson(state.get("temperatures"), double[].class), JSON.fromJson(state.get("freeWater"), double[].class),
                    JSON.fromJson(state.get("wetTrays"), boolean[].class));
            double[] global = V3GeneralNeuralFeatures.global(input);
            double[][] nodes = V3GeneralNeuralFeatures.nodes(input, teacher.branch());
            double[][] targets = V3GeneralNeuralFeatures.targets(teacher);
            compare(global, JSON.fromJson(fixture.get("global"), double[].class), "global", error);
            compare(nodes, JSON.fromJson(fixture.get("nodes"), double[][].class), "nodes", error);
            compare(targets, JSON.fromJson(fixture.get("targets"), double[][].class), "targets", error);
            var prediction = model.predict(input, () -> {});
            var measurement = new LinkedHashMap<String, Object>();
            measurement.put("id", row.get("id")); measurement.put("split", "train");
            measurement.put("input", input); measurement.put("teacherBranch", teacher.branch());
            measurement.put("global", global); measurement.put("nodes", nodes); measurement.put("targets", targets);
            measurement.put("predictionSupported", prediction.isPresent()); measurement.put("prediction", prediction.orElse(null));
            measurements.add(measurement);
        }
        var report = new LinkedHashMap<String, Object>();
        report.put("modelId", model.modelId());
        report.put("modelSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(modelPath))));
        report.put("absoluteTolerance", ABSOLUTE_TOLERANCE); report.put("relativeTolerance", RELATIVE_TOLERANCE);
        report.put("featureValuesCompared", error.count); report.put("maximumFeatureAbsoluteError", error.maximumAbsolute);
        report.put("maximumFeatureNormalizedError", error.maximumNormalized);
        report.put("scope", "Export parity on selected accepted training columns only; no solver or held-out target evaluation");
        report.put("rows", measurements);
        Files.writeString(output, JSON.toJson(report), StandardOpenOption.CREATE_NEW);
        System.out.println("Java feature parity passed for " + measurements.size() + " training rows and " + error.count
                + " values; maximum absolute error=" + error.maximumAbsolute + "; wrote real inference to " + output);
    }

    private static String readId(String line) throws Exception {
        try (var reader = new JsonReader(new StringReader(line))) {
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if ("id".equals(name)) return JsonParser.parseReader(reader).toString();
                reader.skipValue();
            }
        }
        throw new IllegalArgumentException("Journal row has no ID");
    }

    private static void compare(double[][] actual, double[][] expected, String field, ErrorSummary error) {
        if (actual.length != expected.length) throw new IllegalArgumentException(field + " node shape mismatch");
        for (int n = 0; n < actual.length; n++) compare(actual[n], expected[n], field + "[" + n + "]", error);
    }

    private static void compare(double[] actual, double[] expected, String field, ErrorSummary error) {
        if (actual.length != expected.length) throw new IllegalArgumentException(field + " width mismatch");
        for (int i = 0; i < actual.length; i++) {
            double absolute = Math.abs(actual[i] - expected[i]);
            double normalized = absolute / (ABSOLUTE_TOLERANCE + RELATIVE_TOLERANCE * Math.max(Math.abs(actual[i]), Math.abs(expected[i])));
            if (!Double.isFinite(normalized) || normalized > 1)
                throw new IllegalStateException(field + "[" + i + "] differs: Java=" + actual[i] + ", Python=" + expected[i]);
            error.count++; error.maximumAbsolute = Math.max(error.maximumAbsolute, absolute);
            error.maximumNormalized = Math.max(error.maximumNormalized, normalized);
        }
    }

    private static final class ErrorSummary { long count; double maximumAbsolute, maximumNormalized; }
}
