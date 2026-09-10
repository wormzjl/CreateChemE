package com.wormzjl.createcheme.science.column.v3;

import java.io.IOException;

/** Safely published immutable bundled model; no file reload or mutable workspace crosses admission. */
public final class V3NeuralModels {
    public enum Family { LOCAL_EXPERTS, GENERALIZED_EXPERIMENTAL, GENERALIZED_GEN3_EXPERIMENTAL }
    private V3NeuralModels() {}
    public static V3NeuralInitializer bundled() { return Holder.MODEL; }
    public static V3NeuralInitializer forFamily(Family family) {
        return switch (java.util.Objects.requireNonNull(family)) {
            case LOCAL_EXPERTS -> bundled();
            case GENERALIZED_EXPERIMENTAL -> GeneralHolder.MODEL;
            case GENERALIZED_GEN3_EXPERIMENTAL -> Gen3Holder.MODEL;
        };
    }
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
    private static final class GeneralHolder {
        private static final V3NeuralInitializer MODEL = load();
        private static V3NeuralInitializer load() {
            try (var stream = V3NeuralModels.class.getResourceAsStream("/data/createcheme/neural/v3-general-stage.json")) {
                if (stream != null) return V3GeneralNeuralInitializer.read(stream);
            } catch (IOException | IllegalArgumentException invalid) {
                // Optional experimental coverage may be unavailable; LNN_FIRST retains classical fallback.
            }
            return V3NeuralInitializer.UNAVAILABLE;
        }
    }
    private static final class Gen3Holder {
        private static final V3NeuralInitializer MODEL = load();
        private static V3NeuralInitializer load() {
            try (var stream = V3NeuralModels.class.getResourceAsStream("/data/createcheme/neural/v3-general-gen3-factorized.json")) {
                if (stream != null) return V3FactorizedNeuralInitializer.read(stream);
            } catch (IOException | IllegalArgumentException invalid) {
                // Optional experimental coverage may be unavailable; LNN_FIRST retains classical fallback.
            }
            return V3NeuralInitializer.UNAVAILABLE;
        }
    }
}
