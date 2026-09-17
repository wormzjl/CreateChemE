package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/** Bounded, SHA-pinned trained payload plus explicit inference policy. No field merging or defaults. */
final class V3TransformerArtifact {
    private static final Set<String> PAYLOAD = Set.of("schemaVersion", "featureRevision", "modelType",
            "anchorLayout", "baselineRevision", "components", "normalization", "weights");
    private static final Set<String> SIDECAR = Set.of("schemaVersion", "payloadSha256", "modelId", "packageId",
            "propertyRevision", "physicsFingerprint", "formulationRevisions",
            "allowExtraZeroComponents", "allowMissingZeroComponents",
            "branchesSeen", "presenceThreshold", "traceFloorFraction", "globalMin", "globalMax",
            "designConstraints", "compositionEdges", "decoder", "candidateRule", "correctionRule");
    record Loaded(V3AnchorTransformerInitializer initializer, String pipelineSha256, boolean allowExtraZeroComponents, boolean allowMissingZeroComponents) {}
    private V3TransformerArtifact() {}

    static Loaded read(InputStream payloadStream, InputStream sidecarStream) throws IOException {
        byte[] payload = bounded(payloadStream, 8 * 1024 * 1024);
        byte[] sidecar = bounded(sidecarStream, 256 * 1024);
        try {
            JsonObject weights = object(payload, PAYLOAD, 1), policy = object(sidecar, SIDECAR, 2);
            if (!sha256(payload).equals(policy.get("payloadSha256").getAsString()))
                throw new IllegalArgumentException("Transformer payload SHA-256 mismatch");
            if (!"ZERO_PHASE_FLOOR_10".equals(policy.get("decoder").getAsString())
                    || !"SINGLE".equals(policy.get("candidateRule").getAsString())
                    || !"PROGRESS".equals(policy.get("correctionRule").getAsString()))
                throw new IllegalArgumentException("Unsupported transformer inference policy");
            for(String key : Set.of("allowExtraZeroComponents", "allowMissingZeroComponents"))
                if(!policy.get(key).isJsonPrimitive() || !policy.getAsJsonPrimitive(key).isBoolean())
                    throw new IllegalArgumentException("Expected boolean " + key);
            JsonObject document = new JsonObject();
            weights.entrySet().stream().filter(e -> !e.getKey().equals("schemaVersion"))
                    .forEach(e -> document.add(e.getKey(), e.getValue()));
            policy.entrySet().stream().filter(e -> !Set.of("schemaVersion", "payloadSha256", "decoder",
                    "candidateRule", "correctionRule").contains(e.getKey()))
                    .forEach(e -> document.add(e.getKey(), e.getValue()));
            var model = V3AnchorTransformerInitializer.read(new ByteArrayInputStream(
                    document.toString().getBytes(StandardCharsets.UTF_8)),
                    V3FactorizedNeuralFeatures.DecodeOptions.zeroPhaseFloor(10),
                    V3AnchorTransformerInitializer.CandidateRule.SINGLE);
            return new Loaded(model, sha256((sha256(payload) + ":" + sha256(sidecar)).getBytes(StandardCharsets.US_ASCII)),
                    policy.get("allowExtraZeroComponents").getAsBoolean(), policy.get("allowMissingZeroComponents").getAsBoolean());
        } catch (com.google.gson.JsonParseException | IllegalStateException | NullPointerException invalid) {
            throw new IllegalArgumentException("Invalid transformer payload or sidecar", invalid);
        }
    }

    private static JsonObject object(byte[] bytes, Set<String> fields, int schema) {
        var result = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        if (!result.keySet().equals(fields) || !result.get("schemaVersion").toString().equals(Integer.toString(schema)))
            throw new IllegalArgumentException("Invalid transformer record fields or schema version");
        return result;
    }

    private static byte[] bounded(InputStream stream, int limit) throws IOException {
        if (stream == null) throw new IllegalArgumentException("Missing transformer record");
        byte[] bytes = stream.readNBytes(limit + 1);
        if (bytes.length > limit) throw new IllegalArgumentException("Transformer record exceeds size limit");
        return bytes;
    }

    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
