package com.wormzjl.createcheme.science.thermo;

import java.util.Arrays;

/** Immutable result from an isothermal-isobaric flash calculation. */
public final class FlashResult {
    public enum PhaseState {
        LIQUID,
        VAPOR,
        TWO_PHASE,
        NO_CONVERGENCE
    }

    private final PhaseState phaseState;
    private final double vaporFraction;
    private final double[] liquidMoleFractions;
    private final double[] vaporMoleFractions;
    private final int iterations;
    private final double maximumLogFugacityResidual;

    FlashResult(
            PhaseState phaseState,
            double vaporFraction,
            double[] liquidMoleFractions,
            double[] vaporMoleFractions,
            int iterations,
            double maximumLogFugacityResidual) {
        this.phaseState = phaseState;
        this.vaporFraction = vaporFraction;
        this.liquidMoleFractions = liquidMoleFractions.clone();
        this.vaporMoleFractions = vaporMoleFractions.clone();
        this.iterations = iterations;
        this.maximumLogFugacityResidual = maximumLogFugacityResidual;
    }

    public PhaseState phaseState() {
        return phaseState;
    }

    public boolean converged() {
        return phaseState != PhaseState.NO_CONVERGENCE;
    }

    public double vaporFraction() {
        return vaporFraction;
    }

    public double[] liquidMoleFractions() {
        return liquidMoleFractions.clone();
    }

    public double[] vaporMoleFractions() {
        return vaporMoleFractions.clone();
    }

    public int iterations() {
        return iterations;
    }

    public double maximumLogFugacityResidual() {
        return maximumLogFugacityResidual;
    }

    @Override
    public String toString() {
        return "FlashResult[state=" + phaseState
                + ", vaporFraction=" + vaporFraction
                + ", iterations=" + iterations
                + ", residual=" + maximumLogFugacityResidual
                + ", x=" + Arrays.toString(liquidMoleFractions)
                + ", y=" + Arrays.toString(vaporMoleFractions) + ']';
    }
}
