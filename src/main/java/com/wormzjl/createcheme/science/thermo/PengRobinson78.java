package com.wormzjl.createcheme.science.thermo;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Peng-Robinson 1978 cubic equation of state with classical quadratic mixing rules. */
public final class PengRobinson78 {
    public static final double GAS_CONSTANT = 8.31446261815324;

    private static final double OMEGA_SPLIT = 0.491;
    private static final double SQRT_TWO = Math.sqrt(2.0);
    private static final double ROOT_EPSILON = 1.0e-12;

    private final List<ThermoComponent> components;
    private final double[][] binaryInteractions;
    private final double[] criticalA;
    private final double[] coVolumes;
    private final double[] kappas;

    public PengRobinson78(List<ThermoComponent> components, double[][] binaryInteractions) {
        this.components = List.copyOf(Objects.requireNonNull(components, "components"));
        if (this.components.isEmpty()) {
            throw new IllegalArgumentException("At least one component is required");
        }
        int count = this.components.size();
        if (binaryInteractions.length != count) {
            throw new IllegalArgumentException("Binary-interaction matrix size must match the component count");
        }

        this.binaryInteractions = new double[count][count];
        this.criticalA = new double[count];
        this.coVolumes = new double[count];
        this.kappas = new double[count];
        for (int i = 0; i < count; i++) {
            if (binaryInteractions[i].length != count) {
                throw new IllegalArgumentException("Binary-interaction matrix must be square");
            }
            this.binaryInteractions[i] = binaryInteractions[i].clone();
            ThermoComponent component = this.components.get(i);
            criticalA[i] = 0.45724 * square(GAS_CONSTANT)
                    * square(component.criticalTemperatureKelvin())
                    / component.criticalPressurePascal();
            coVolumes[i] = 0.07780 * GAS_CONSTANT
                    * component.criticalTemperatureKelvin()
                    / component.criticalPressurePascal();
            kappas[i] = kappa(component.acentricFactor());
        }
    }

    public static PengRobinson78 withoutBinaryInteractions(List<ThermoComponent> components) {
        return new PengRobinson78(components, new double[components.size()][components.size()]);
    }

    public int componentCount() {
        return components.size();
    }

    public List<ThermoComponent> components() {
        return components;
    }

    /** Wilson correlation used only to initialize phase-equilibrium iterations. */
    public double[] initialEquilibriumRatios(double temperatureKelvin, double pressurePascal) {
        requireState(temperatureKelvin, pressurePascal);
        double[] ratios = new double[components.size()];
        for (int i = 0; i < ratios.length; i++) {
            ThermoComponent component = components.get(i);
            double logRatio = Math.log(component.criticalPressurePascal() / pressurePascal)
                    + 5.373 * (1.0 + component.acentricFactor())
                    * (1.0 - component.criticalTemperatureKelvin() / temperatureKelvin);
            ratios[i] = Math.exp(Math.clamp(logRatio, -40.0, 40.0));
        }
        return ratios;
    }

    public PhaseProperties evaluate(
            double temperatureKelvin,
            double pressurePascal,
            double[] moleFractions,
            PhaseRoot phaseRoot) {
        requireState(temperatureKelvin, pressurePascal);
        Objects.requireNonNull(phaseRoot, "phaseRoot");
        double[] composition = normalizedComposition(moleFractions);
        int count = components.size();

        double[] a = new double[count];
        double[] daDt = new double[count];
        double[] sumA = new double[count];
        double bMix = 0.0;
        for (int i = 0; i < count; i++) {
            ThermoComponent component = components.get(i);
            double sqrtReducedTemperature = Math.sqrt(
                    temperatureKelvin / component.criticalTemperatureKelvin());
            double alphaTerm = 1.0 + kappas[i] * (1.0 - sqrtReducedTemperature);
            a[i] = criticalA[i] * square(alphaTerm);
            daDt[i] = -criticalA[i] * kappas[i] * alphaTerm
                    / Math.sqrt(temperatureKelvin * component.criticalTemperatureKelvin());
            bMix += composition[i] * coVolumes[i];
        }

        double aMix = 0.0;
        double daMixDt = 0.0;
        for (int i = 0; i < count; i++) {
            double row = 0.0;
            double derivativeRow = 0.0;
            for (int j = 0; j < count; j++) {
                double aij = Math.sqrt(a[i] * a[j]) * (1.0 - binaryInteractions[i][j]);
                row += composition[j] * aij;
                derivativeRow += composition[j] * 0.5 * aij
                        * (daDt[i] / a[i] + daDt[j] / a[j]);
            }
            sumA[i] = row;
            aMix += composition[i] * row;
            daMixDt += composition[i] * derivativeRow;
        }

        double reducedA = aMix * pressurePascal
                / (square(GAS_CONSTANT) * square(temperatureKelvin));
        double reducedB = bMix * pressurePascal / (GAS_CONSTANT * temperatureKelvin);
        if (Math.abs(reducedA) < 1.0e-14 && Math.abs(reducedB) < 1.0e-14) {
            return new PhaseProperties(1.0, new double[count], 0.0);
        }

        double compressibility = selectRoot(reducedA, reducedB, phaseRoot);
        double logRatio = Math.log(
                (compressibility + (1.0 + SQRT_TWO) * reducedB)
                        / (compressibility + (1.0 - SQRT_TWO) * reducedB));
        double attraction = reducedA / (2.0 * SQRT_TWO * reducedB);
        double[] logFugacity = new double[count];
        for (int i = 0; i < count; i++) {
            double bRatio = coVolumes[i] / bMix;
            double attractionRatio = 2.0 * sumA[i] / aMix - bRatio;
            logFugacity[i] = bRatio * (compressibility - 1.0)
                    - Math.log(compressibility - reducedB)
                    - attraction * attractionRatio * logRatio;
        }
        double residualEnthalpy = GAS_CONSTANT * temperatureKelvin * (compressibility - 1.0)
                + (temperatureKelvin * daMixDt - aMix)
                        / (2.0 * SQRT_TWO * bMix) * logRatio;
        return new PhaseProperties(compressibility, logFugacity, residualEnthalpy);
    }

    private double[] normalizedComposition(double[] moleFractions) {
        Objects.requireNonNull(moleFractions, "moleFractions");
        if (moleFractions.length != components.size()) {
            throw new IllegalArgumentException("Composition size must match the component count");
        }
        double sum = 0.0;
        for (double fraction : moleFractions) {
            if (!Double.isFinite(fraction) || fraction < 0.0) {
                throw new IllegalArgumentException("Mole fractions must be finite and nonnegative");
            }
            sum += fraction;
        }
        if (!(sum > 0.0)) {
            throw new IllegalArgumentException("Composition must contain material");
        }
        double[] normalized = moleFractions.clone();
        for (int i = 0; i < normalized.length; i++) {
            normalized[i] /= sum;
        }
        return normalized;
    }

    private static double selectRoot(double reducedA, double reducedB, PhaseRoot phaseRoot) {
        double c2 = -(1.0 - reducedB);
        double c1 = reducedA - 3.0 * square(reducedB) - 2.0 * reducedB;
        double c0 = -(reducedA * reducedB - square(reducedB) - reducedB * square(reducedB));
        double[] roots = realCubicRoots(c2, c1, c0);
        Arrays.sort(roots);

        if (phaseRoot == PhaseRoot.VAPOR) {
            return roots[roots.length - 1];
        }
        for (double root : roots) {
            if (root > reducedB + ROOT_EPSILON) {
                return root;
            }
        }
        throw new IllegalStateException("Peng-Robinson equation has no physical liquid root");
    }

    /** Returns the real roots of z^3 + c2*z^2 + c1*z + c0 = 0. */
    static double[] realCubicRoots(double c2, double c1, double c0) {
        double p = c1 - square(c2) / 3.0;
        double q = 2.0 * c2 * square(c2) / 27.0 - c2 * c1 / 3.0 + c0;
        double discriminant = square(q) / 4.0 + p * p * p / 27.0;
        double offset = c2 / 3.0;

        if (discriminant > 1.0e-16) {
            double root = Math.sqrt(discriminant);
            return new double[] {
                    Math.cbrt(-q / 2.0 + root) + Math.cbrt(-q / 2.0 - root) - offset
            };
        }
        if (Math.abs(discriminant) <= 1.0e-16) {
            double u = Math.cbrt(-q / 2.0);
            return new double[] {2.0 * u - offset, -u - offset};
        }

        double radius = 2.0 * Math.sqrt(-p / 3.0);
        double angle = Math.acos(Math.clamp(
                (3.0 * q / (2.0 * p)) * Math.sqrt(-3.0 / p), -1.0, 1.0)) / 3.0;
        return new double[] {
                radius * Math.cos(angle) - offset,
                radius * Math.cos(angle - 2.0 * Math.PI / 3.0) - offset,
                radius * Math.cos(angle - 4.0 * Math.PI / 3.0) - offset
        };
    }

    private static double kappa(double acentricFactor) {
        if (acentricFactor <= OMEGA_SPLIT) {
            return 0.37464 + 1.54226 * acentricFactor
                    - 0.26992 * square(acentricFactor);
        }
        return 0.379642 + 1.48503 * acentricFactor
                - 0.164423 * square(acentricFactor)
                + 0.016666 * acentricFactor * square(acentricFactor);
    }

    private static void requireState(double temperatureKelvin, double pressurePascal) {
        if (!Double.isFinite(temperatureKelvin) || temperatureKelvin <= 0.0
                || !Double.isFinite(pressurePascal) || pressurePascal <= 0.0) {
            throw new IllegalArgumentException("Temperature and pressure must be finite and positive");
        }
    }

    private static double square(double value) {
        return value * value;
    }
}
