package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Pipeline identity includes the weights, the completion policy, the decoder rule and the correction budget.
 *
 * <p>Every pipeline in this study loads the same frozen weight bytes. What differs is stated in the
 * manifest, never in the model document: which decode rule builds the seed, and which budget the native
 * correction of that seed is allowed to spend. A manifest that states neither reproduces the production
 * path exactly, which is what the baseline pipeline's archive parity measures.</p>
 */
final class V3BudgetModels {
    private V3BudgetModels() {}

    static V3NeuralInitializer read(Path path) throws Exception {
        var doc = manifest(path);
        var weights = doc.getAsJsonObject("weights");
        var file = Path.of(weights.get("path").getAsString());
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        if (!sha.equals(weights.get("sha256").getAsString()))
            throw new IllegalArgumentException("Pipeline weights changed");
        var decode = decodeOptions(doc.getAsJsonObject("decoder"));
        try (var stream = Files.newInputStream(file)) {
            return switch (doc.get("kind").getAsString()) {
                case "anchor-augmented" -> V3BudgetInitializer.read(stream, decode);
                case "transformer" -> V3ColumnTransformerInitializer.read(stream, decode);
                default -> throw new IllegalArgumentException("Unknown pipeline kind");
            };
        }
    }

    static JsonObject manifest(Path path) throws Exception {
        var doc = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        if (!doc.get("revision").getAsString().equals("neural-budget-pipeline-v1"))
            throw new IllegalArgumentException("Unknown pipeline revision");
        if (doc.get("materialCompletion").getAsBoolean())
            throw new IllegalArgumentException("Neural budget study forbids the completion wrapper");
        return doc;
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

    /**
     * The correction budget one pipeline asks for, resolved once per process before any worker exists.
     *
     * <p>{@code fixed} is the production wall pair: a hard iteration cap and a hard wall-clock allowance,
     * both stated in the manifest so the journal records what was actually spent rather than what the
     * command line happened to pass.</p>
     */
    record Correction(String rule, int maximumIterations, int budgetMillis, JsonObject manifest) {
        V3InitializationOptions options(V3InitializationOptions.Mode mode) {
            return new V3InitializationOptions(mode, V3InitializationOptions.WetStart.AUTO,
                    maximumIterations, budgetMillis);
        }
    }

    static Correction correction(Path path, int defaultIterations, int defaultBudgetMillis) throws Exception {
        var doc = manifest(path);
        var correction = doc.getAsJsonObject("correction");
        if (correction == null) throw new IllegalArgumentException("Pipeline must state its correction rule");
        return switch (correction.get("rule").getAsString()) {
            case "fixed" -> new Correction("fixed",
                    correction.has("maximumIterations") ? correction.get("maximumIterations").getAsInt() : defaultIterations,
                    correction.has("budgetMillis") ? correction.get("budgetMillis").getAsInt() : defaultBudgetMillis,
                    correction);
            // The progress rule reshapes walls into a windowed contraction test with an early stall stop. It
            // is registered together with the solver change that implements it, and only if the bounded
            // diagnostic's pre-declared decision rule selects it.
            case "progress" -> throw new IllegalArgumentException(
                    "The progress correction rule is registered with the solver change that implements it");
            default -> throw new IllegalArgumentException("Unknown correction rule");
        };
    }
}
