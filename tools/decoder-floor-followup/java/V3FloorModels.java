package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Pipeline identity includes the weights, the completion policy and the decoder variant.
 *
 * <p>The decoder variant lives in the pipeline manifest, never in the model document, so every
 * pipeline in this study loads the same frozen weight bytes and differs only in the decode rule.</p>
 */
final class V3FloorModels {
    private V3FloorModels() {}

    static V3NeuralInitializer read(Path path) throws Exception {
        var doc = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        if (!doc.get("revision").getAsString().equals("decoder-floor-pipeline-v1"))
            throw new IllegalArgumentException("Unknown pipeline revision");
        if (doc.get("materialCompletion").getAsBoolean())
            throw new IllegalArgumentException("Decoder floor study forbids the completion wrapper");
        var weights = doc.getAsJsonObject("weights");
        var file = Path.of(weights.get("path").getAsString());
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        if (!sha.equals(weights.get("sha256").getAsString()))
            throw new IllegalArgumentException("Pipeline weights changed");
        var decode = decodeOptions(doc.getAsJsonObject("decoder"));
        try (var stream = Files.newInputStream(file)) {
            return switch (doc.get("kind").getAsString()) {
                case "anchor-augmented" -> V3FloorInitializer.read(stream, decode);
                case "transformer" -> V3ColumnTransformerInitializer.read(stream, decode);
                default -> throw new IllegalArgumentException("Unknown pipeline kind");
            };
        }
    }

    static V3FactorizedNeuralFeatures.DecodeOptions decodeOptions(JsonObject decoder) {
        if (decoder == null) throw new IllegalArgumentException("Pipeline must state its decoder rule");
        return switch (decoder.get("rule").getAsString()) {
            case "prune" -> {
                if (decoder.has("liftFactor") || decoder.has("liftPresenceProbability"))
                    throw new IllegalArgumentException("The default decoder rule takes no lift parameters");
                yield V3FactorizedNeuralFeatures.DecodeOptions.NONE;
            }
            case "presence-floor-lift" -> V3FactorizedNeuralFeatures.DecodeOptions.lift(
                    decoder.get("liftFactor").getAsDouble(), decoder.get("liftPresenceProbability").getAsDouble());
            default -> throw new IllegalArgumentException("Unknown decoder rule");
        };
    }
}
