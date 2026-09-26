package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.JsonParser;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Pipeline identity includes weights and completion policy. */
final class V3CapacityModels {
    private V3CapacityModels() {}
    static V3NeuralInitializer read(Path path) throws Exception {
        var doc=JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        if (!doc.get("revision").getAsString().equals("hybrid-pipeline-v1"))
            throw new IllegalArgumentException("Unknown pipeline revision");
        var weights=doc.getAsJsonObject("weights");var file=Path.of(weights.get("path").getAsString());
        String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        if (!sha.equals(weights.get("sha256").getAsString()))throw new IllegalArgumentException("Pipeline weights changed");
        if (!doc.get("kind").getAsString().equals("capacity-transformer")) return V3TraceModels.read(path);
        if (doc.get("materialCompletion").getAsBoolean()) throw new IllegalArgumentException("Capacity wrapper forbidden");
        V3NeuralInitializer model;
        try(var stream=Files.newInputStream(file)){model=V3CapacityInitializer.read(stream);}
        return doc.get("materialCompletion").getAsBoolean() ? new V3MechanisticTransformerInitializer(model) : model;
    }
}
