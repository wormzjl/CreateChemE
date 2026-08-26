package com.wormzjl.createcheme.science.column.v3;

/**
 * Immutable final-Newton-step certificate required in addition to the independently recomputed acceptance audit.
 *
 * <p>The temperature criterion is assessed per temperature coordinate against
 * {@code 1e-6 K + 1e-9 * T}; {@link #maximumTemperatureStepRatio()} is the largest such normalized step.</p>
 */
public record V3ConvergenceEvidence(
        boolean hasFinalNewtonStep,
        double finalLinearBackwardError,
        double maximumLogFlowChange,
        double maximumTemperatureChangeKelvin,
        double maximumTemperatureStepRatio) {
    public static final double MAXIMUM_LINEAR_BACKWARD_ERROR = 1.0e-12;
    public static final double MAXIMUM_LOG_FLOW_CHANGE = 1.0e-8;
    public static final double MAXIMUM_TEMPERATURE_STEP_RATIO = 1.0;

    public V3ConvergenceEvidence {
        if (!Double.isFinite(finalLinearBackwardError) || finalLinearBackwardError < 0.0
                || !Double.isFinite(maximumLogFlowChange) || maximumLogFlowChange < 0.0
                || !Double.isFinite(maximumTemperatureChangeKelvin) || maximumTemperatureChangeKelvin < 0.0
                || !Double.isFinite(maximumTemperatureStepRatio) || maximumTemperatureStepRatio < 0.0) {
            throw new IllegalArgumentException("V3 convergence evidence must be finite and nonnegative");
        }
        if (!hasFinalNewtonStep && (finalLinearBackwardError != 0.0 || maximumLogFlowChange != 0.0
                || maximumTemperatureChangeKelvin != 0.0 || maximumTemperatureStepRatio != 0.0)) {
            throw new IllegalArgumentException("Unavailable V3 convergence evidence cannot contain a correction metric");
        }
    }

    /** Evidence for a terminal failure that has no accepted final Newton correction. */
    public static V3ConvergenceEvidence unavailable() {
        return new V3ConvergenceEvidence(false, 0.0, 0.0, 0.0, 0.0);
    }

    /** True only when the final accepted correction satisfies every frozen convergence threshold. */
    public boolean satisfiesGates() {
        return hasFinalNewtonStep && finalLinearBackwardError <= MAXIMUM_LINEAR_BACKWARD_ERROR
                && maximumLogFlowChange <= MAXIMUM_LOG_FLOW_CHANGE
                && maximumTemperatureStepRatio <= MAXIMUM_TEMPERATURE_STEP_RATIO;
    }
}
