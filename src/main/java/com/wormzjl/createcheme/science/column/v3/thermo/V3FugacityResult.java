package com.wormzjl.createcheme.science.column.v3.thermo;

import java.util.Arrays;
import java.util.Objects;

/** Immutable Peng–Robinson phase result in the V3 SI/property convention. */
public final class V3FugacityResult {
    private final V3Phase phase;
    private final double[] logFugacityCoefficients;
    private final double compressibilityFactor;
    private final double molarEnthalpyJoulesPerMol;
    private final int physicalRootCount;
    private final double rootSeparation;

    public V3FugacityResult(
            V3Phase phase, double[] logFugacityCoefficients, double compressibilityFactor,
            double molarEnthalpyJoulesPerMol, int physicalRootCount, double rootSeparation) {
        this.phase = Objects.requireNonNull(phase, "phase");
        this.logFugacityCoefficients = Objects.requireNonNull(logFugacityCoefficients, "logFugacityCoefficients").clone();
        this.compressibilityFactor = compressibilityFactor;
        this.molarEnthalpyJoulesPerMol = molarEnthalpyJoulesPerMol;
        this.physicalRootCount = physicalRootCount;
        this.rootSeparation = rootSeparation;
        if (this.logFugacityCoefficients.length == 0 || Arrays.stream(this.logFugacityCoefficients).anyMatch(value -> !Double.isFinite(value))
                || !Double.isFinite(compressibilityFactor) || compressibilityFactor <= 0.0
                || !Double.isFinite(molarEnthalpyJoulesPerMol) || physicalRootCount < 1
                || !Double.isFinite(rootSeparation) || rootSeparation < 0.0) {
            throw new IllegalArgumentException("V3 fugacity result is not finite and physically valid");
        }
    }

    public V3Phase phase() {
        return phase;
    }

    public double[] logFugacityCoefficients() {
        return logFugacityCoefficients.clone();
    }

    public double logFugacityCoefficient(int component) {
        return logFugacityCoefficients[component];
    }

    public double compressibilityFactor() {
        return compressibilityFactor;
    }

    public double molarEnthalpyJoulesPerMol() {
        return molarEnthalpyJoulesPerMol;
    }

    public int physicalRootCount() {
        return physicalRootCount;
    }

    public double rootSeparation() {
        return rootSeparation;
    }
}
