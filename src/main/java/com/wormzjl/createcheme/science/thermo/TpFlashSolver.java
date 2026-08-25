package com.wormzjl.createcheme.science.thermo;

import java.util.Objects;

/** Successive-substitution TP flash using Peng-Robinson fugacity coefficients. */
public final class TpFlashSolver {
    public static final int DEFAULT_MAXIMUM_ITERATIONS = 100;
    public static final double DEFAULT_TOLERANCE = 1.0e-8;

    private static final int RACHFORD_RICE_ITERATIONS = 80;
    private static final double LOG_K_LIMIT = 40.0;
    private static final double UPDATE_DAMPING = 0.5;

    private final PengRobinson78 equationOfState;
    private final int maximumIterations;
    private final double tolerance;

    public TpFlashSolver(PengRobinson78 equationOfState) {
        this(equationOfState, DEFAULT_MAXIMUM_ITERATIONS, DEFAULT_TOLERANCE);
    }

    public TpFlashSolver(PengRobinson78 equationOfState, int maximumIterations, double tolerance) {
        this.equationOfState = Objects.requireNonNull(equationOfState, "equationOfState");
        if (maximumIterations < 1 || !Double.isFinite(tolerance) || tolerance <= 0.0) {
            throw new IllegalArgumentException("Invalid TP-flash iteration settings");
        }
        this.maximumIterations = maximumIterations;
        this.tolerance = tolerance;
    }

    public FlashResult solve(double temperatureKelvin, double pressurePascal, double[] feedMoleFractions) {
        double[] feed = normalized(feedMoleFractions, equationOfState.componentCount());
        double[] ratios = equationOfState.initialEquilibriumRatios(temperatureKelvin, pressurePascal);
        double[] logRatios = new double[ratios.length];
        for (int i = 0; i < ratios.length; i++) {
            logRatios[i] = Math.log(ratios[i]);
        }

        double[] liquid = feed.clone();
        double[] vapor = feed.clone();
        double vaporFraction = 0.0;
        double maximumResidual = Double.POSITIVE_INFINITY;

        for (int iteration = 1; iteration <= maximumIterations; iteration++) {
            for (int i = 0; i < ratios.length; i++) {
                ratios[i] = Math.exp(logRatios[i]);
            }

            double atLiquidLimit = rachfordRice(feed, ratios, 0.0);
            double atVaporLimit = rachfordRice(feed, ratios, 1.0);
            if (atLiquidLimit <= 0.0) {
                return singlePhase(FlashResult.PhaseState.LIQUID, feed, iteration);
            }
            if (atVaporLimit >= 0.0) {
                return singlePhase(FlashResult.PhaseState.VAPOR, feed, iteration);
            }

            vaporFraction = solveVaporFraction(feed, ratios);
            for (int i = 0; i < feed.length; i++) {
                liquid[i] = feed[i] / (1.0 + vaporFraction * (ratios[i] - 1.0));
                vapor[i] = ratios[i] * liquid[i];
            }
            normalizeInPlace(liquid);
            normalizeInPlace(vapor);

            PhaseProperties liquidProperties = equationOfState.evaluate(
                    temperatureKelvin, pressurePascal, liquid, PhaseRoot.LIQUID);
            PhaseProperties vaporProperties = equationOfState.evaluate(
                    temperatureKelvin, pressurePascal, vapor, PhaseRoot.VAPOR);

            maximumResidual = 0.0;
            for (int i = 0; i < ratios.length; i++) {
                double targetLogRatio = liquidProperties.logFugacityCoefficient(i)
                        - vaporProperties.logFugacityCoefficient(i);
                double residual = targetLogRatio - logRatios[i];
                maximumResidual = Math.max(maximumResidual, Math.abs(residual));
                logRatios[i] = Math.clamp(
                        logRatios[i] + UPDATE_DAMPING * residual,
                        -LOG_K_LIMIT,
                        LOG_K_LIMIT);
            }
            if (maximumResidual <= tolerance) {
                return new FlashResult(
                        FlashResult.PhaseState.TWO_PHASE,
                        vaporFraction,
                        liquid,
                        vapor,
                        iteration,
                        maximumResidual);
            }
        }

        return new FlashResult(
                FlashResult.PhaseState.NO_CONVERGENCE,
                vaporFraction,
                liquid,
                vapor,
                maximumIterations,
                maximumResidual);
    }

    private static FlashResult singlePhase(
            FlashResult.PhaseState phaseState, double[] composition, int iterations) {
        return new FlashResult(
                phaseState,
                phaseState == FlashResult.PhaseState.VAPOR ? 1.0 : 0.0,
                composition,
                composition,
                iterations,
                0.0);
    }

    private static double solveVaporFraction(double[] feed, double[] ratios) {
        double low = 0.0;
        double high = 1.0;
        for (int iteration = 0; iteration < RACHFORD_RICE_ITERATIONS; iteration++) {
            double midpoint = 0.5 * (low + high);
            if (rachfordRice(feed, ratios, midpoint) > 0.0) {
                low = midpoint;
            } else {
                high = midpoint;
            }
        }
        return 0.5 * (low + high);
    }

    private static double rachfordRice(double[] feed, double[] ratios, double vaporFraction) {
        double value = 0.0;
        for (int i = 0; i < feed.length; i++) {
            double shiftedRatio = ratios[i] - 1.0;
            value += feed[i] * shiftedRatio / (1.0 + vaporFraction * shiftedRatio);
        }
        return value;
    }

    private static double[] normalized(double[] composition, int expectedSize) {
        Objects.requireNonNull(composition, "composition");
        if (composition.length != expectedSize) {
            throw new IllegalArgumentException("Composition size must match the component count");
        }
        double[] normalized = composition.clone();
        normalizeInPlace(normalized);
        return normalized;
    }

    private static void normalizeInPlace(double[] composition) {
        double sum = 0.0;
        for (double value : composition) {
            if (!Double.isFinite(value) || value < 0.0) {
                throw new IllegalArgumentException("Composition must be finite and nonnegative");
            }
            sum += value;
        }
        if (!(sum > 0.0)) {
            throw new IllegalArgumentException("Composition must contain material");
        }
        for (int i = 0; i < composition.length; i++) {
            composition[i] /= sum;
        }
    }
}
