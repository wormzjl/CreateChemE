package com.wormzjl.createcheme.science.column.v3;

import java.util.Objects;

/** Immutable input to one request-local sequential preconditioner attempt. */
record V3PreconditionerRequest(V3ColumnProblem problem, V3DryMeshState seed, V3SolveControl control) {
    V3PreconditionerRequest {
        problem = Objects.requireNonNull(problem, "problem");
        seed = Objects.requireNonNull(seed, "seed");
        control = Objects.requireNonNull(control, "control");
        if (seed.nodeCount() != problem.topology().nodeCount()
                || seed.componentCount() != problem.activeComponentBasis().componentCount()) {
            throw new IllegalArgumentException("V3 preconditioner seed does not match its resolved problem");
        }
    }
}
