package com.wormzjl.createcheme.science.column.v3.thermo;

import java.util.Arrays;
import java.util.Objects;

/** Immutable feed-flash outcome with an explicit normal single- or two-phase classification. */
public final class V3FlashResult {
    private final V3FeedPhase phase;
    private final int iterations;
    private final double vaporFraction;
    private final double[] liquidComposition;
    private final double[] vaporComposition;
    private final double molarEnthalpyJoulesPerMol;
    private final String detail;

    public V3FlashResult(
            V3FeedPhase phase, int iterations, double vaporFraction, double[] liquidComposition,
            double[] vaporComposition, double molarEnthalpyJoulesPerMol, String detail) {
        this.phase = Objects.requireNonNull(phase, "phase");
        this.iterations = iterations;
        this.vaporFraction = vaporFraction;
        this.liquidComposition = copyNormalized(liquidComposition, "liquidComposition");
        this.vaporComposition = copyNormalized(vaporComposition, "vaporComposition");
        this.molarEnthalpyJoulesPerMol = molarEnthalpyJoulesPerMol;
        this.detail = bounded(detail, "detail", 256);
        if (iterations < 0 || !Double.isFinite(vaporFraction) || vaporFraction < 0.0 || vaporFraction > 1.0
                || !Double.isFinite(molarEnthalpyJoulesPerMol)) {
            throw new IllegalArgumentException("V3 flash result has invalid numerical evidence");
        }
        if (phase == V3FeedPhase.LIQUID && (vaporFraction != 0.0 || vaporComposition.length != 0)) {
            throw new IllegalArgumentException("A liquid V3 flash must have exactly zero vapor fraction");
        }
        if (phase == V3FeedPhase.VAPOR && (vaporFraction != 1.0 || liquidComposition.length != 0)) {
            throw new IllegalArgumentException("A vapor V3 flash must have exactly one vapor fraction");
        }
        if (phase == V3FeedPhase.TWO_PHASE && (!(vaporFraction > 0.0 && vaporFraction < 1.0)
                || liquidComposition.length == 0 || vaporComposition.length == 0)) {
            throw new IllegalArgumentException("A two-phase V3 flash must contain both normalized phases");
        }
    }

    static V3FlashResult liquid(int iterations, double[] liquidComposition, double molarEnthalpyJoulesPerMol, String detail) {
        return new V3FlashResult(V3FeedPhase.LIQUID, iterations, 0.0, liquidComposition, new double[0],
                molarEnthalpyJoulesPerMol, detail);
    }

    static V3FlashResult twoPhase(
            int iterations, double vaporFraction, double[] liquidComposition, double[] vaporComposition,
            double molarEnthalpyJoulesPerMol, String detail) {
        return new V3FlashResult(V3FeedPhase.TWO_PHASE, iterations, vaporFraction, liquidComposition, vaporComposition,
                molarEnthalpyJoulesPerMol, detail);
    }

    static V3FlashResult vapor(int iterations, double[] vaporComposition, double molarEnthalpyJoulesPerMol, String detail) {
        return new V3FlashResult(V3FeedPhase.VAPOR, iterations, 1.0, new double[0], vaporComposition,
                molarEnthalpyJoulesPerMol, detail);
    }

    public V3FeedPhase phase() { return phase; }
    public int iterations() { return iterations; }
    public double vaporFraction() { return vaporFraction; }
    public double[] liquidComposition() { return liquidComposition.clone(); }
    public double[] vaporComposition() { return vaporComposition.clone(); }
    public double molarEnthalpyJoulesPerMol() { return molarEnthalpyJoulesPerMol; }
    public String detail() { return detail; }

    private static double[] copyNormalized(double[] composition, String name) {
        composition = Objects.requireNonNull(composition, name).clone();
        if (composition.length == 0) return composition;
        double total = 0.0;
        for (double value : composition) {
            if (!Double.isFinite(value) || value < 0.0) throw new IllegalArgumentException(name + " is invalid");
            total += value;
        }
        if (!Double.isFinite(total) || Math.abs(total - 1.0) > 1.0e-10) {
            throw new IllegalArgumentException(name + " is not normalized");
        }
        return composition;
    }

    private static String bounded(String value, String name, int maximumLength) {
        value = Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " is blank or exceeds the bounded contract");
        }
        return value;
    }
}
