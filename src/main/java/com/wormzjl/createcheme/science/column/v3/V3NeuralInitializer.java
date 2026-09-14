package com.wormzjl.createcheme.science.column.v3;

import java.util.List;
import java.util.Optional;

/**
 * Supplies a physical initial guess, never an accepted result. Implementations must be immutable/thread-safe,
 * keep scratch state local to a request, and poll the supplied control during bounded inference work.
 */
public interface V3NeuralInitializer {
    String modelId();

    /** Empty means unsupported coverage or no compatible model, not physical infeasibility. */
    Optional<V3NeuralSeed> predict(V3ColumnInput input, V3SolveControl control);

    /**
     * Ranked alternatives for one request, sharing the caller's single inference/correction budget.
     *
     * <p>A single-seed model offers exactly what it predicts, which is what the bundled Transformer does.
     * An offline bundle of experts overrides this to offer more than one, and the learned entry then tries
     * them in order. Implementations must keep the same immutability and cancellation contract as
     * {@link #predict}.</p>
     */
    default List<V3NeuralSeed> candidates(V3ColumnInput input, V3SolveControl control) {
        return predict(input, control).stream().toList();
    }

    V3NeuralInitializer UNAVAILABLE = new V3NeuralInitializer() {
        @Override public String modelId() { return "unavailable"; }
        @Override public Optional<V3NeuralSeed> predict(V3ColumnInput input, V3SolveControl control) {
            control.checkpoint();
            return Optional.empty();
        }
    };
}
