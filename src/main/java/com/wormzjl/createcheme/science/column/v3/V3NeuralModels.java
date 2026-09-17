package com.wormzjl.createcheme.science.column.v3;

import java.io.IOException;

/** Immutable bundled initializer holder. Missing or incompatible weights retain classical fallback. */
public final class V3NeuralModels {
    public static final String ARTIFACT = "/data/createcheme/neural/v3-column-transformer-regrouped.payload.json";
    public static final String SIDECAR = "/data/createcheme/neural/v3-column-transformer-regrouped.sidecar.json";
    private V3NeuralModels() {}
    public static V3NeuralInitializer bundled() { return Holder.MODEL; }

    private static V3NeuralInitializer load() {
        try (var payload = V3NeuralModels.class.getResourceAsStream(ARTIFACT);
             var sidecar = V3NeuralModels.class.getResourceAsStream(SIDECAR)) {
            if (payload != null && sidecar != null) return V3TransformerArtifact.read(payload, sidecar).initializer();
        } catch (IOException | IllegalArgumentException invalid) {
            // A missing or rejected artifact never disables classical initialization.
        }
        return V3NeuralInitializer.UNAVAILABLE;
    }
    private static final class Holder { private static final V3NeuralInitializer MODEL = load(); }
}
