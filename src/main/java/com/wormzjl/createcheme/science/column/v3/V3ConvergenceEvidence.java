package com.wormzjl.createcheme.science.column.v3;

/**
 * Immutable final-Newton-step certificate required in addition to the independently recomputed acceptance audit.
 *
 * <p>The temperature criterion is assessed per temperature coordinate against
 * {@code 1e-6 K + 1e-9 * T}; {@link #maximumTemperatureStepRatio()} is the largest such normalized step.</p>
 *
 * <p>{@link #closureTolerance()} is the relative convergence closure this certificate was produced at: the
 * scaled-residual tolerance of the Newton solve that accepted the step. It is carried here so that a gate
 * can never be evaluated at a closure other than the one the state was solved to. The default
 * {@link #MAXIMUM_LOG_FLOW_CHANGE} is the historical fixed value and is also the floor of the range.</p>
 */
public record V3ConvergenceEvidence(
        boolean hasFinalNewtonStep,
        double finalLinearBackwardError,
        double maximumLogFlowChange,
        double maximumTemperatureChangeKelvin,
        double maximumTemperatureStepRatio,
        double closureTolerance) {
    public static final double MAXIMUM_LINEAR_BACKWARD_ERROR = 1.0e-12;
    public static final double MAXIMUM_LOG_FLOW_CHANGE = 1.0e-8;
    public static final double MAXIMUM_TEMPERATURE_STEP_RATIO = 1.0;
    /** Loosest convergence closure any V3 solve may be admitted at; 0.1 mol% of every row's own scale. */
    public static final double MAXIMUM_CLOSURE_TOLERANCE = 1.0e-3;

    public V3ConvergenceEvidence {
        if (!Double.isFinite(finalLinearBackwardError) || finalLinearBackwardError < 0.0
                || !Double.isFinite(maximumLogFlowChange) || maximumLogFlowChange < 0.0
                || !Double.isFinite(maximumTemperatureChangeKelvin) || maximumTemperatureChangeKelvin < 0.0
                || !Double.isFinite(maximumTemperatureStepRatio) || maximumTemperatureStepRatio < 0.0) {
            throw new IllegalArgumentException("V3 convergence evidence must be finite and nonnegative");
        }
        requireClosure(closureTolerance);
        if (!hasFinalNewtonStep && (finalLinearBackwardError != 0.0 || maximumLogFlowChange != 0.0
                || maximumTemperatureChangeKelvin != 0.0 || maximumTemperatureStepRatio != 0.0)) {
            throw new IllegalArgumentException("Unavailable V3 convergence evidence cannot contain a correction metric");
        }
    }

    /** Certificate at the default closure; the historical five-component form. */
    public V3ConvergenceEvidence(
            boolean hasFinalNewtonStep, double finalLinearBackwardError, double maximumLogFlowChange,
            double maximumTemperatureChangeKelvin, double maximumTemperatureStepRatio) {
        this(hasFinalNewtonStep, finalLinearBackwardError, maximumLogFlowChange, maximumTemperatureChangeKelvin,
                maximumTemperatureStepRatio, MAXIMUM_LOG_FLOW_CHANGE);
    }

    /**
     * Validates one convergence closure and returns it.
     *
     * @throws IllegalArgumentException if the closure is nonfinite or outside
     *         [{@link #MAXIMUM_LOG_FLOW_CHANGE}, {@link #MAXIMUM_CLOSURE_TOLERANCE}]
     */
    public static double requireClosure(double closureTolerance) {
        if (!Double.isFinite(closureTolerance) || closureTolerance < MAXIMUM_LOG_FLOW_CHANGE
                || closureTolerance > MAXIMUM_CLOSURE_TOLERANCE) {
            throw new IllegalArgumentException("V3 convergence closure must be finite and in [1e-8, 1e-3]");
        }
        return closureTolerance;
    }

    /**
     * Closure a Newton solve at {@code scaledTolerance} certifies its final step against.
     *
     * <p>The final-step gate never tightens below the historical {@link #MAXIMUM_LOG_FLOW_CHANGE}: a solve run
     * at a residual tolerance below it (unit fixtures do) keeps the same step gate it always had. It never
     * loosens past {@link #MAXIMUM_CLOSURE_TOLERANCE} either, which the authored range already enforces.</p>
     */
    public static double closureOf(double scaledTolerance) {
        if (!Double.isFinite(scaledTolerance) || scaledTolerance <= 0.0) {
            throw new IllegalArgumentException("V3 scaled residual tolerance must be finite and positive");
        }
        return Math.min(MAXIMUM_CLOSURE_TOLERANCE, Math.max(MAXIMUM_LOG_FLOW_CHANGE, scaledTolerance));
    }

    /** Evidence for a terminal failure that has no accepted final Newton correction. */
    public static V3ConvergenceEvidence unavailable() {
        return unavailable(MAXIMUM_LOG_FLOW_CHANGE);
    }

    /** Unavailable evidence that still records the closure the failed solve was asking for. */
    public static V3ConvergenceEvidence unavailable(double closureTolerance) {
        return new V3ConvergenceEvidence(false, 0.0, 0.0, 0.0, 0.0, requireClosure(closureTolerance));
    }

    /** True only when the final accepted correction satisfies every gate at this certificate's own closure. */
    public boolean satisfiesGates() {
        return satisfiesGates(closureTolerance);
    }

    /**
     * True only when the final accepted correction satisfies every frozen convergence threshold at
     * {@code closure}, normalized by {@link #closureOf(double)}. The linear backward-error and
     * temperature-step gates are qualities of the linear solve and of the coordinate step, not of the
     * closure, and never move.
     */
    public boolean satisfiesGates(double closure) {
        return hasFinalNewtonStep && finalLinearBackwardError <= MAXIMUM_LINEAR_BACKWARD_ERROR
                && maximumLogFlowChange <= closureOf(closure)
                && maximumTemperatureStepRatio <= MAXIMUM_TEMPERATURE_STEP_RATIO;
    }
}
