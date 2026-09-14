package com.wormzjl.createcheme.science.column.v3;

import java.io.IOException;

/**
 * The single bundled learned initializer. Safely published and immutable; no file reload or mutable
 * workspace crosses admission.
 *
 * <p>One model ships: the anchor-augmented Transformer {@code F-20260911-s4160}, qualified over the
 * frozen 405-input validation population with the phase-level decoder floor this holder states. The
 * earlier dense, generalized, factorized and nearest-profile families are no longer bundled; their
 * readers and artifacts live in the offline tools source set under {@code tools/neural/retired/} so the
 * archived studies still build. See {@code v3-column-transformer-f0.md} beside the artifact.</p>
 */
public final class V3NeuralModels {
    /**
     * The qualified decoder rule: a phase whose total decodes zero, whose branch admits it and whose
     * presence head kept at least one component, is seeded at ten support floors over those components.
     * Registered as {@code PHASE_FLOOR_DECODER} by the neural-budget study; changing it changes seeds.
     */
    static final double QUALIFIED_ZERO_PHASE_FLOOR_FACTOR = 10.0;

    /** The bundled artifact, relative to the classpath root. */
    public static final String ARTIFACT = "/data/createcheme/neural/v3-column-transformer-f0.json";

    private V3NeuralModels() {}

    public static V3NeuralInitializer bundled() { return Holder.MODEL; }

    /**
     * The same bundled bytes under an explicitly stated decode and candidate rule.
     *
     * <p>The decoder rule and the candidate rule are properties of the loading caller, not of the artifact,
     * so a study that measures another pair states it here rather than editing what the mod ships.
     * {@link #bundled()} is this method at the qualified pair, and nothing else in production calls it.</p>
     */
    static V3NeuralInitializer load(V3FactorizedNeuralFeatures.DecodeOptions decode,
            V3AnchorTransformerInitializer.CandidateRule candidates) {
        try (var stream = V3NeuralModels.class.getResourceAsStream(ARTIFACT)) {
            if (stream != null) return V3AnchorTransformerInitializer.read(stream, decode, candidates);
        } catch (IOException | IllegalArgumentException invalid) {
            // A corrupt or absent artifact never disables the solver: LNN_FIRST retains classical fallback
            // and LNN_ONLY publishes its own typed failure.
        }
        return V3NeuralInitializer.UNAVAILABLE;
    }

    private static final class Holder {
        private static final V3NeuralInitializer MODEL = load(
                V3FactorizedNeuralFeatures.DecodeOptions.zeroPhaseFloor(QUALIFIED_ZERO_PHASE_FLOOR_FACTOR),
                V3AnchorTransformerInitializer.CandidateRule.SINGLE);
    }
}
