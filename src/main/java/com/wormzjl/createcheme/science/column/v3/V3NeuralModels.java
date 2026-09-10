package com.wormzjl.createcheme.science.column.v3;

import java.io.IOException;

/** Safely published immutable bundled model; no file reload or mutable workspace crosses admission. */
public final class V3NeuralModels {
    private V3NeuralModels() {}
    public static V3NeuralInitializer bundled() { return Holder.MODEL; }
    private static final class Holder {
        private static final V3NeuralInitializer MODEL = load();
        private static V3NeuralInitializer load() {
            try (var stream = V3NeuralModels.class.getResourceAsStream("/data/createcheme/neural/v3-mvp.json")) {
                return stream == null ? V3NeuralInitializer.UNAVAILABLE : V3DenseNeuralInitializer.read(stream);
            } catch (IOException | IllegalArgumentException invalid) {
                // Optional optimization is unavailable. Forced LNN reports this; LNN_FIRST retains backup.
                return V3NeuralInitializer.UNAVAILABLE;
            }
        }
    }
}
