package com.wormzjl.createcheme.science.thermo;

import java.util.Objects;

/** Fixed-pressure enthalpy flash using a bracketed temperature solve. */
public final class PhFlashSolver {
    public static final int DEFAULT_MAXIMUM_ITERATIONS = 80;
    public static final double DEFAULT_ENTHALPY_TOLERANCE_JOULES_PER_MOL = 1.0e-3;

    private final TpCaloricFlashSolver flashSolver;
    private final int maximumIterations;
    private final double enthalpyToleranceJoulesPerMol;

    public PhFlashSolver(TpCaloricFlashSolver flashSolver) {
        this(
                flashSolver,
                DEFAULT_MAXIMUM_ITERATIONS,
                DEFAULT_ENTHALPY_TOLERANCE_JOULES_PER_MOL);
    }

    public PhFlashSolver(
            TpCaloricFlashSolver flashSolver,
            int maximumIterations,
            double enthalpyToleranceJoulesPerMol) {
        this.flashSolver = Objects.requireNonNull(flashSolver, "flashSolver");
        if (maximumIterations < 1
                || !Double.isFinite(enthalpyToleranceJoulesPerMol)
                || enthalpyToleranceJoulesPerMol <= 0.0) {
            throw new IllegalArgumentException("Invalid PH-flash iteration settings");
        }
        this.maximumIterations = maximumIterations;
        this.enthalpyToleranceJoulesPerMol = enthalpyToleranceJoulesPerMol;
    }

    public PhFlashResult solve(
            double pressurePascal,
            double[] feedMoleFractions,
            double targetEnthalpyJoulesPerMol,
            double minimumTemperatureKelvin,
            double maximumTemperatureKelvin) {
        if (!Double.isFinite(targetEnthalpyJoulesPerMol)
                || !(minimumTemperatureKelvin < maximumTemperatureKelvin)) {
            throw new IllegalArgumentException("Invalid PH-flash target or temperature bracket");
        }

        CaloricFlashResult lower =
                flashSolver.solve(minimumTemperatureKelvin, pressurePascal, feedMoleFractions);
        CaloricFlashResult upper =
                flashSolver.solve(maximumTemperatureKelvin, pressurePascal, feedMoleFractions);
        double lowerResidual = lower.enthalpyJoulesPerMol() - targetEnthalpyJoulesPerMol;
        double upperResidual = upper.enthalpyJoulesPerMol() - targetEnthalpyJoulesPerMol;
        if (lowerResidual == 0.0) {
            return new PhFlashResult(lower, 0, 0.0, true);
        }
        if (upperResidual == 0.0) {
            return new PhFlashResult(upper, 0, 0.0, true);
        }
        if (Math.copySign(1.0, lowerResidual) == Math.copySign(1.0, upperResidual)) {
            throw new IllegalArgumentException("Target enthalpy is outside the temperature bracket");
        }

        CaloricFlashResult current = lower;
        double currentResidual = lowerResidual;
        int lastReplacedEndpoint = 0;
        for (int iteration = 1; iteration <= maximumIterations; iteration++) {
            double temperatureSpan = upper.temperatureKelvin() - lower.temperatureKelvin();
            double temperature = upper.temperatureKelvin()
                    - upperResidual * temperatureSpan / (upperResidual - lowerResidual);
            if (!(temperature > lower.temperatureKelvin()
                    && temperature < upper.temperatureKelvin())) {
                temperature = 0.5
                        * (lower.temperatureKelvin() + upper.temperatureKelvin());
            }

            current = flashSolver.solve(temperature, pressurePascal, feedMoleFractions);
            currentResidual = current.enthalpyJoulesPerMol() - targetEnthalpyJoulesPerMol;
            if (Math.abs(currentResidual) <= enthalpyToleranceJoulesPerMol) {
                return new PhFlashResult(current, iteration, currentResidual, true);
            }
            if (Math.copySign(1.0, currentResidual) == Math.copySign(1.0, lowerResidual)) {
                lower = current;
                lowerResidual = currentResidual;
                if (lastReplacedEndpoint == -1) {
                    upperResidual *= 0.5;
                }
                lastReplacedEndpoint = -1;
            } else {
                upper = current;
                upperResidual = currentResidual;
                if (lastReplacedEndpoint == 1) {
                    lowerResidual *= 0.5;
                }
                lastReplacedEndpoint = 1;
            }
        }
        return new PhFlashResult(current, maximumIterations, currentResidual, false);
    }
}
