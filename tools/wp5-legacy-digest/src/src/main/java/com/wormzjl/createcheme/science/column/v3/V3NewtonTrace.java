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

    /**
     * Marks the start of one Newton attempt inside a single correction pass.
     *
     * <p>A pass repeats its attempt whenever the frozen floor support or the free-water tray set is
     * refreshed, and each repeat restarts its own iteration counter from zero. Without this boundary an
     * observer cannot tell one long attempt from three short ones, and the retention the attempt was
     * prepared on is what decides whether a refresh reinserted a point or only dropped one.</p>
     */
    default void beganAttempt(int attempt, int supportRefreshes, int wetTrayRefreshes, int iterationBudget,
            int retainedPoints, int totalPoints, int wetTrayCount) {}

    /** Records how one attempt ended: its solver code, completed iterations and final scaled residual. */
    default void finishedAttempt(int attempt, String code, int iterations, double maximumScaledResidual) {}
}
