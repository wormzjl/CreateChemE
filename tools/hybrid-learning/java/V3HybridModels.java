package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.JsonParser;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Pipeline identity includes weights and completion policy. */
final class V3HybridModels {
    private V3HybridModels() {}
    static V3NeuralInitializer read(Path path) throws Exception {
        var doc=JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        if (!doc.get("revision").getAsString().equals("hybrid-pipeline-v1"))
            throw new IllegalArgumentException("Unknown pipeline revision");
        var weights=doc.getAsJsonObject("weights");var file=Path.of(weights.get("path").getAsString());
        String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        if (!sha.equals(weights.get("sha256").getAsString()))throw new IllegalArgumentException("Pipeline weights changed");
        V3NeuralInitializer model;
        if (doc.get("kind").getAsString().equals("hybrid-residual"))
            try(var stream=Files.newInputStream(file)){model=V3HybridResidualInitializer.read(stream);}
        else if (doc.get("kind").getAsString().equals("transformer")) model=V3CandidateModels.read(file);
        else throw new IllegalArgumentException("Unknown pipeline kind");
        return doc.get("materialCompletion").getAsBoolean() ? new V3MechanisticTransformerInitializer(model) : model;
    }
}
