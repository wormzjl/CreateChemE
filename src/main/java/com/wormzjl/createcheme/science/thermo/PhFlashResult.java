package com.wormzjl.createcheme.science.thermo;

import java.util.Objects;

/** Immutable result from a fixed-pressure, fixed-enthalpy flash. */
public final class PhFlashResult {
    private final CaloricFlashResult flashResult;
    private final int iterations;
    private final double enthalpyResidualJoulesPerMol;
    private final boolean converged;

    PhFlashResult(
            CaloricFlashResult flashResult,
            int iterations,
            double enthalpyResidualJoulesPerMol,
            boolean converged) {
        this.flashResult = Objects.requireNonNull(flashResult, "flashResult");
        this.iterations = iterations;
        this.enthalpyResidualJoulesPerMol = enthalpyResidualJoulesPerMol;
        this.converged = converged;
    }

    public CaloricFlashResult flashResult() {
        return flashResult;
    }

    public double temperatureKelvin() {
        return flashResult.temperatureKelvin();
    }

    public int iterations() {
        return iterations;
    }

    public double enthalpyResidualJoulesPerMol() {
        return enthalpyResidualJoulesPerMol;
    }

    public boolean converged() {
        return converged;
    }

    @Override
    public String toString() {
        return "PhFlashResult[temperatureKelvin=" + temperatureKelvin()
                + ", iterations=" + iterations
                + ", enthalpyResidualJoulesPerMol=" + enthalpyResidualJoulesPerMol
                + ", converged=" + converged + ']';
    }
}
