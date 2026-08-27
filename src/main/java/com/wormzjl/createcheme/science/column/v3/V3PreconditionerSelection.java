package com.wormzjl.createcheme.science.column.v3;

import java.util.List;
import java.util.Objects;

/** Immutable ordered strategy plan chosen from a fresh local thermodynamic profile. */
record V3PreconditionerSelection(double logKSpread, List<V3PreconditionerId> order) {
    V3PreconditionerSelection {
        if (!Double.isFinite(logKSpread) || logKSpread < 0.0) {
            throw new IllegalArgumentException("V3 preconditioner log-K spread is invalid");
        }
        order = List.copyOf(Objects.requireNonNull(order, "order"));
        if (order.isEmpty() || order.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("V3 preconditioner selection has no usable strategy order");
        }
    }
}
