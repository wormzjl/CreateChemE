package com.wormzjl.createcheme.science.thermo;

import java.util.Objects;

/** Isobaric flash that adjusts temperature to reach a specified equilibrium vapor fraction. */
public final class PressureVaporFractionFlashSolver {
    public static final int DEFAULT_MAXIMUM_ITERATIONS = 32;
    public static final double DEFAULT_VAPOR_FRACTION_TOLERANCE = 1.0e-6;

    private static final double INITIAL_SEARCH_STEP_KELVIN = 5.0;

    private final TpFlashSolver tpFlashSolver;
    private final int maximumIterations;
    private final double vaporFractionTolerance;

    public PressureVaporFractionFlashSolver(PengRobinson78 equationOfState) {
        this(
                new TpFlashSolver(equationOfState),
                DEFAULT_MAXIMUM_ITERATIONS,
                DEFAULT_VAPOR_FRACTION_TOLERANCE);
    }

    public PressureVaporFractionFlashSolver(
            TpFlashSolver tpFlashSolver,
            int maximumIterations,
            double vaporFractionTolerance) {
        this.tpFlashSolver = Objects.requireNonNull(tpFlashSolver, "tpFlashSolver");
        if (maximumIterations < 1
                || !Double.isFinite(vaporFractionTolerance)
                || vaporFractionTolerance <= 0.0) {
            throw new IllegalArgumentException("Invalid pressure-vapor-fraction flash settings");
        }
        this.maximumIterations = maximumIterations;
        this.vaporFractionTolerance = vaporFractionTolerance;
    }

    public Result solve(
            double pressurePascal,
            double targetVaporFraction,
            double[] feedMoleFractions,
            double minimumTemperatureKelvin,
            double maximumTemperatureKelvin,
            double initialTemperatureKelvin) {
        if (!(targetVaporFraction > 0.0 && targetVaporFraction < 1.0)
                || !(minimumTemperatureKelvin > 0.0)
                || !(maximumTemperatureKelvin > minimumTemperatureKelvin)) {
            throw new IllegalArgumentException("Invalid pressure-vapor-fraction flash target");
        }

        double temperature = Math.clamp(
                initialTemperatureKelvin, minimumTemperatureKelvin, maximumTemperatureKelvin);
        FlashResult flash = tpFlashSolver.solve(temperature, pressurePascal, feedMoleFractions);
        int evaluations = 1;
        double residual = flash.vaporFraction() - targetVaporFraction;
        if (Math.abs(residual) <= vaporFractionTolerance) {
            return new Result(true, temperature, residual, evaluations, flash);
        }

        double previousTemperature = temperature;
        double previousResidual = residual;
        double step = Math.copySign(INITIAL_SEARCH_STEP_KELVIN, -residual);
        for (int search = 0; search < maximumIterations; search++) {
            temperature = Math.clamp(
                    previousTemperature + step,
                    minimumTemperatureKelvin,
                    maximumTemperatureKelvin);
            flash = tpFlashSolver.solve(temperature, pressurePascal, feedMoleFractions);
            evaluations++;
            residual = flash.vaporFraction() - targetVaporFraction;
            if (Math.abs(residual) <= vaporFractionTolerance) {
                return new Result(true, temperature, residual, evaluations, flash);
            }
            if (Math.copySign(1.0, residual) != Math.copySign(1.0, previousResidual)) {
                break;
            }
            if (temperature == minimumTemperatureKelvin
                    || temperature == maximumTemperatureKelvin) {
                throw new IllegalArgumentException(
                        "Temperature bounds do not bracket the vapor fraction");
            }
            previousTemperature = temperature;
            previousResidual = residual;
            step *= 2.0;
        }

        double lowerTemperature;
        double lowerResidual;
        double upperTemperature;
        double upperResidual;
        if (residual < 0.0) {
            lowerTemperature = temperature;
            lowerResidual = residual;
            upperTemperature = previousTemperature;
            upperResidual = previousResidual;
        } else {
            lowerTemperature = previousTemperature;
            lowerResidual = previousResidual;
            upperTemperature = temperature;
            upperResidual = residual;
        }

        for (int iteration = 0; iteration < maximumIterations; iteration++) {
            temperature = upperTemperature
                    - upperResidual * (upperTemperature - lowerTemperature)
                            / (upperResidual - lowerResidual);
            double margin = 0.05 * (upperTemperature - lowerTemperature);
            temperature = Math.clamp(
                    temperature, lowerTemperature + margin, upperTemperature - margin);
            flash = tpFlashSolver.solve(temperature, pressurePascal, feedMoleFractions);
            evaluations++;
            residual = flash.vaporFraction() - targetVaporFraction;
            if (Math.abs(residual) <= vaporFractionTolerance) {
                return new Result(true, temperature, residual, evaluations, flash);
            }
            if (residual < 0.0) {
                lowerTemperature = temperature;
                lowerResidual = residual;
            } else {
                upperTemperature = temperature;
                upperResidual = residual;
            }
        }
        return new Result(false, temperature, residual, evaluations, flash);
    }

    public record Result(
            boolean converged,
            double temperatureKelvin,
            double vaporFractionResidual,
            int propertyEvaluations,
            FlashResult equilibrium) {}
}
