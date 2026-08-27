package com.wormzjl.createcheme.science.column.v3;

/** Package-local, solve-confined Newton observation hook for deterministic cold diagnostics. */
@FunctionalInterface
interface V3NewtonTrace {
    V3NewtonTrace NONE = (iteration, residual, scaledMerit) -> {};

    void sampledIteration(int iteration, V3MeshResidual residual, double scaledMerit);

    /** Optional state-bearing observation for bounded solver diagnostics. */
    default void sampledState(
            int iteration, V3DryMeshState state, V3MeshResidual residual, double scaledMerit) {
        sampledIteration(iteration, residual, scaledMerit);
    }

    /** Records whether the request-local local-block direction passed the same Armijo gate as a full Newton step. */
    default void localBlockDirection(int iteration, boolean accepted) {}

    /** Records a fresh or one-step-reused full finite-difference Jacobian fallback. */
    default void finiteDifferenceJacobian(int iteration, boolean reused) {}
}
