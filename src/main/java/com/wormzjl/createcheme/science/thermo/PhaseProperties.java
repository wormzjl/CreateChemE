package com.wormzjl.createcheme.science.thermo;

import java.util.Arrays;

/** Immutable phase properties returned by a cubic equation-of-state evaluation. */
public final class PhaseProperties {
    private final double compressibilityFactor;
    private final double[] logFugacityCoefficients;
    private final double residualEnthalpyJoulesPerMol;

    PhaseProperties(
            double compressibilityFactor,
            double[] logFugacityCoefficients,
            double residualEnthalpyJoulesPerMol) {
        this.compressibilityFactor = compressibilityFactor;
        this.logFugacityCoefficients = logFugacityCoefficients.clone();
        this.residualEnthalpyJoulesPerMol = residualEnthalpyJoulesPerMol;
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

    public double residualEnthalpyJoulesPerMol() {
        return residualEnthalpyJoulesPerMol;
    }

    @Override
    public String toString() {
        return "PhaseProperties[z=" + compressibilityFactor
                + ", logPhi=" + Arrays.toString(logFugacityCoefficients)
                + ", residualEnthalpyJoulesPerMol=" + residualEnthalpyJoulesPerMol + ']';
    }
}
