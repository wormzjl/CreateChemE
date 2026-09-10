package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.stream.JsonReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Offline candidate dispatch; every implementation still supplies only an initial physical guess. */
final class V3CandidateModels {
    private V3CandidateModels() {}

    static V3NeuralInitializer read(Path path) throws IOException {
        if (Files.size(path) > 32L * 1024 * 1024) throw new IllegalArgumentException("Candidate model exceeds size limit");
        String type = "", revision = "";
        try (var reader = new JsonReader(Files.newBufferedReader(path))) {
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (name.equals("modelType")) type = reader.nextString();
                else if (name.equals("featureRevision")) revision = reader.nextString();
                else reader.skipValue();
            }
            reader.endObject();
        }
        try (var stream = Files.newInputStream(path)) {
            if (type.equals("nearest-profile")) return V3NearestProfileInitializer.read(stream);
            if (revision.equals("v3-factorized-stage-1")) return V3FactorizedNeuralInitializer.read(stream);
            if (revision.equals(V3GeneralNeuralFeatures.REVISION)) return V3GeneralNeuralInitializer.read(stream);
            throw new IllegalArgumentException("Unsupported candidate model format");
        }
    }

    static long parameterStorageBytes(V3NeuralInitializer model) {
        if (model instanceof V3GeneralNeuralInitializer general) return general.parameterStorageBytes();
        if (model instanceof V3FactorizedNeuralInitializer factorized) return factorized.parameterStorageBytes();
        if (model instanceof V3NearestProfileInitializer nearest) return nearest.parameterStorageBytes();
        return 0;
    }
}
