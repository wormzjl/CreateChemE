package com.wormzjl.createcheme.science.column.v3;

import java.io.IOException;

/** Safely published immutable bundled model; no file reload or mutable workspace crosses admission. */
public final class V3NeuralModels {
    private V3NeuralModels() {}
    public static V3NeuralInitializer bundled() { return Holder.MODEL; }
    private static final class Holder {
        private static final V3NeuralInitializer MODEL = load();
        private static V3NeuralInitializer load() {
            var models = new java.util.ArrayList<V3NeuralInitializer>();
            for (String artifact : java.util.List.of("v3-mvp.json", "v3-tjl20-dry.json", "v3-tjl20-wet.json")) {
                try (var stream = V3NeuralModels.class.getResourceAsStream("/data/createcheme/neural/" + artifact)) {
                    if (stream != null) models.add(V3DenseNeuralInitializer.read(stream));
                } catch (IOException | IllegalArgumentException invalid) {
                    // A corrupt optional expert does not disable the other compatible experts or backup.
                }
            }
            return models.isEmpty() ? V3NeuralInitializer.UNAVAILABLE
                    : new V3PhaseAwareNeuralInitializer("v3-phase-aware-bundle-v2", models);
        }
    }
}
