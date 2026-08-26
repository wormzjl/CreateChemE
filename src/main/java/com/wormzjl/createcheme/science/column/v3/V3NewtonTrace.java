package com.wormzjl.createcheme.science.column.v3;

/** Package-local, solve-confined Newton observation hook for deterministic cold diagnostics. */
@FunctionalInterface
interface V3NewtonTrace {
    V3NewtonTrace NONE = (iteration, residual, scaledMerit) -> {};

    void sampledIteration(int iteration, V3MeshResidual residual, double scaledMerit);
}
