package com.wormzjl.createcheme.science.thermo;

import java.util.Arrays;

/** Immutable phase properties returned by a cubic equation-of-state evaluation. */
public final class PhaseProperties {
    private final double compressibilityFactor;
    private final double[] logFugacityCoefficients;

    PhaseProperties(double compressibilityFactor, double[] logFugacityCoefficients) {
        this.compressibilityFactor = compressibilityFactor;
        this.logFugacityCoefficients = logFugacityCoefficients.clone();
    }

    public double compressibilityFactor() {
        return compressibilityFactor;
    }

    public int componentCount() {
        return logFugacityCoefficients.length;
    }

    public double logFugacityCoefficient(int component) {
        return logFugacityCoefficients[component];
    }

    public double[] logFugacityCoefficients() {
        return logFugacityCoefficients.clone();
    }

    @Override
    public String toString() {
        return "PhaseProperties[z=" + compressibilityFactor
                + ", logPhi=" + Arrays.toString(logFugacityCoefficients) + ']';
    }
}
