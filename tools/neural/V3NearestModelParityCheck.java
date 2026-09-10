package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Actual nearest-profile inference on frozen TRAIN-origin parity inputs; never invokes a native solve. */
public final class V3NearestModelParityCheck {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private V3NearestModelParityCheck() {}

    /** Usage: model-json fixture-json output-json. The Python checker independently reconstructs each guess. */
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("model-json fixture-json output-json");
        Path modelPath = Path.of(args[0]), fixturePath = Path.of(args[1]), output = Path.of(args[2]);
        if (Files.size(modelPath) > 32L * 1024 * 1024) throw new IllegalArgumentException("Oversized nearest profile model");
        if (Files.size(fixturePath) > 8L * 1024 * 1024) throw new IllegalArgumentException("Oversized parity fixture");
        String modelHash = sha(modelPath), fixtureHash = sha(fixturePath);
        V3NearestProfileInitializer model;
        try (var stream = Files.newInputStream(modelPath)) { model = V3NearestProfileInitializer.read(stream); }
        JsonObject fixture = JsonParser.parseString(Files.readString(fixturePath)).getAsJsonObject();
        if (!"nearest-profile-parity-1".equals(fixture.get("revision").getAsString())
                || fixture.get("heldOutProfilesUsed").getAsBoolean()
                || !modelHash.equals(fixture.getAsJsonObject("modelSha256ById").get(model.modelId()).getAsString()))
            throw new IllegalArgumentException("Parity fixture does not belong to the frozen model");
        var expected = fixture.getAsJsonArray("rows");
        if (expected.isEmpty() || expected.size() > 16) throw new IllegalArgumentException("Expected 1..16 parity inputs");
        Map<String, Origin> origins = origins(modelPath);
        var ids = new HashSet<String>(); var rows = new ArrayList<Map<String, Object>>();
        for (JsonElement element : expected) {
            JsonObject row = element.getAsJsonObject(), source = row.getAsJsonObject("origin");
            String id = row.get("id").getAsString(), sourceId = source.get("sourceId").getAsString();
            Origin origin = origins.get(sourceId);
            if (!ids.add(id) || !"train".equals(source.get("sourceSplit").getAsString()) || origin == null
                    || !origin.inputHash().equals(source.get("sourceInputSha256").getAsString()))
                throw new IllegalArgumentException("Parity input must have a distinct ID and a frozen TRAIN reference origin");
            String kind = row.get("kind").getAsString();
            if (!kind.equals("train-exact") && !kind.equals("train-derived-flow") && !kind.equals("train-derived-geometry"))
                throw new IllegalArgumentException("Unknown parity input origin kind");
            V3ColumnInput input = V3NeuralMvpProbe.input(row.getAsJsonObject("input"));
            var prediction = model.predict(input, () -> {});
            var result = new LinkedHashMap<String, Object>();
            result.put("id", id); result.put("kind", kind); result.put("origin", source); result.put("input", input);
            result.put("formulationRevision", V3ColumnCalculator.formulationRevision(input, 0,
                    V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE));
            result.put("global", V3GeneralNeuralFeatures.global(input));
            result.put("predictionSupported", prediction.isPresent()); result.put("prediction", prediction.orElse(null));
            rows.add(result);
        }
        if (!modelHash.equals(sha(modelPath)) || !fixtureHash.equals(sha(fixturePath)))
            throw new IllegalStateException("A frozen parity input changed during inference");
        var report = new LinkedHashMap<String, Object>();
        report.put("modelId", model.modelId()); report.put("modelSha256", modelHash); report.put("fixtureSha256", fixtureHash);
        report.put("referenceCount", model.referenceCount()); report.put("primitiveStorageBytes", model.parameterStorageBytes());
        report.put("heldOutProfilesUsed", false); report.put("nativeSolverInvoked", false); report.put("rows", rows);
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, JSON.toJson(report), StandardOpenOption.CREATE_NEW);
        System.out.println("Nearest-profile Java inference exported " + rows.size() + " TRAIN-origin inputs for "
                + model.modelId() + "; independent Python comparison remains required: " + output);
    }

    /** Reads reference provenance only; profile arrays are owned and validated by the actual implementation. */
    private static Map<String, Origin> origins(Path path) throws Exception {
        Map<String, Origin> origins = new HashMap<>();
        try (var reader = new JsonReader(Files.newBufferedReader(path))) {
            reader.beginObject();
            while (reader.hasNext()) {
                if (!reader.nextName().equals("references")) { reader.skipValue(); continue; }
                reader.beginArray();
                while (reader.hasNext()) {
                    String id = null, hash = null, split = null; boolean qualified = false;
                    reader.beginObject();
                    while (reader.hasNext()) {
                        switch (reader.nextName()) {
                            case "id" -> id = reader.nextString();
                            case "inputSha256" -> hash = reader.nextString();
                            case "sourceSplit" -> split = reader.nextString();
                            case "equilibriumQualified" -> qualified = reader.nextBoolean();
                            default -> reader.skipValue();
                        }
                    }
                    reader.endObject();
                    if (id == null || hash == null || !"train".equals(split) || !qualified
                            || origins.put(id, new Origin(hash)) != null)
                        throw new IllegalArgumentException("Invalid or repeated TRAIN reference provenance");
                }
                reader.endArray();
            }
            reader.endObject();
        }
        return origins;
    }

    private static String sha(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
    private record Origin(String inputHash) {}
}
