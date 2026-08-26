package com.wormzjl.createcheme.science.column.nextgen;

/** Bounded iteration, damping, and trust-region policy for the one canonical dry production solver. */
public record DrySolverLimits(
        int maximumOuterIterations,
        int maximumInnerIterations,
        int checkpointStride,
        double normalDamping,
        double recoveryDamping,
        double normalTemperatureTrustRegionKelvin,
        double recoveryTemperatureTrustRegionKelvin) {
    public static final DrySolverLimits DEFAULT = new DrySolverLimits(24, 64, 4, 0.60, 0.25, 10.0, 2.0);

    public DrySolverLimits {
        if (maximumOuterIterations < 1 || maximumOuterIterations > 128 || maximumInnerIterations < 1
                || maximumInnerIterations > 512 || checkpointStride < 1 || checkpointStride > 32
                || !finiteFraction(normalDamping) || !finiteFraction(recoveryDamping)
                || !Double.isFinite(normalTemperatureTrustRegionKelvin) || normalTemperatureTrustRegionKelvin <= 0.0
                || !Double.isFinite(recoveryTemperatureTrustRegionKelvin)
                || recoveryTemperatureTrustRegionKelvin <= 0.0) {
            throw new IllegalArgumentException("Invalid bounded dry solver limits");
        }
    }

    private static boolean finiteFraction(double value) {
        return Double.isFinite(value) && value > 0.0 && value <= 1.0;
    }
}
