package com.wormzjl.createcheme.science.column.v3;

import java.util.Optional;

/**
 * Supplies a physical initial guess, never an accepted result. Implementations must be immutable/thread-safe,
 * keep scratch state local to a request, and poll the supplied control during bounded inference work.
 */
public interface V3NeuralInitializer {
    String modelId();

    /** Empty means unsupported coverage or no compatible model, not physical infeasibility. */
    Optional<V3NeuralSeed> predict(V3ColumnInput input, V3SolveControl control);

    V3NeuralInitializer UNAVAILABLE = new V3NeuralInitializer() {
        @Override public String modelId() { return "unavailable"; }
        @Override public Optional<V3NeuralSeed> predict(V3ColumnInput input, V3SolveControl control) {
            control.checkpoint();
            return Optional.empty();
        }
    };
}
