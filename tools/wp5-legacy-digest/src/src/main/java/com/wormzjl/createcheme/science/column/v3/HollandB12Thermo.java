package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3FeedPhase;
import com.wormzjl.createcheme.science.column.v3.thermo.V3FlashResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3FugacityResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3Phase;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoModel;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoWorkspace;
import java.util.Objects;

/** Benchmark-only implementation of Holland's Appendix B ideal-solution K and enthalpy fits. */
final class HollandB12Thermo implements V3ThermoModel {
    private static final double MINIMUM_TEMPERATURE_KELVIN = 300.0;
    private static final double MAXIMUM_TEMPERATURE_KELVIN = 550.0;
    private static final double MINIMUM_PRESSURE_PASCAL = 100_000.0;
    private static final double MAXIMUM_PRESSURE_PASCAL = 5_000_000.0;
    private static final double BTU_PER_LB_MOL_TO_JOULES_PER_MOL = 1_055.05585262 / 453.59237;
    private static final double FLASH_BOUNDARY_TOLERANCE = 1.0e-10;

    private final HollandExample32Data data;
    private final double[][] kCoefficients;
    private final double[][] liquidEnthalpyCoefficients;
    private final double[][] vaporEnthalpyCoefficients;

    HollandB12Thermo(HollandExample32Data data) {
        this.data = Objects.requireNonNull(data, "data");
        kCoefficients = data.kCoefficients();
        liquidEnthalpyCoefficients = data.liquidEnthalpyCoefficients();
        vaporEnthalpyCoefficients = data.vaporEnthalpyCoefficients();
    }

    @Override
    public V3ComponentBasis componentBasis() {
        return data.basis();
    }

    @Override
    public V3ThermoWorkspace newWorkspace() {
        return new V3ThermoWorkspace(data.componentCount());
    }

    @Override
    public V3FugacityResult fugacity(
            double temperatureKelvin, double pressurePascal, double[] composition, V3Phase phase,
            V3ThermoWorkspace workspace) {
        requireState(temperatureKelvin, pressurePascal, composition, workspace);
        phase = Objects.requireNonNull(phase, "phase");
        double[] normalized = normalized(composition);
        double[] logPhi = new double[data.componentCount()];
        if (phase == V3Phase.LIQUID) {
            double[] ratios = equilibriumRatios(temperatureKelvin, pressurePascal);
            for (int component = 0; component < ratios.length; component++) logPhi[component] = Math.log(ratios[component]);
        }
        return new V3FugacityResult(phase, logPhi, phase == V3Phase.LIQUID ? 0.1 : 1.0,
                molarEnthalpy(temperatureKelvin, pressurePascal, normalized, phase, workspace), 1, 0.0);
    }

    @Override
    public double molarEnthalpy(
            double temperatureKelvin, double pressurePascal, double[] composition, V3Phase phase,
            V3ThermoWorkspace workspace) {
        requireState(temperatureKelvin, pressurePascal, composition, workspace);
        phase = Objects.requireNonNull(phase, "phase");
        double[] normalized = normalized(composition);
        double[] componentEnthalpies = componentEnthalpiesBtuPerLbMol(temperatureKelvin, phase);
        double mixture = 0.0;
        for (int component = 0; component < normalized.length; component++) {
            mixture += normalized[component] * componentEnthalpies[component];
        }
        return mixture * BTU_PER_LB_MOL_TO_JOULES_PER_MOL;
    }

    @Override
    public V3FlashResult flashTP(
            double temperatureKelvin, double pressurePascal, double[] overallComposition,
            V3ThermoWorkspace workspace) {
        requireState(temperatureKelvin, pressurePascal, overallComposition, workspace);
        double[] z = normalized(overallComposition);
        double[] k = equilibriumRatios(temperatureKelvin, pressurePascal);
        double atLiquidBoundary = rachfordRice(z, k, 0.0);
        double atVaporBoundary = rachfordRice(z, k, 1.0);
        if (atLiquidBoundary <= FLASH_BOUNDARY_TOLERANCE) {
            return new V3FlashResult(V3FeedPhase.LIQUID, 0, 0.0, z, new double[0],
                    molarEnthalpy(temperatureKelvin, pressurePascal, z, V3Phase.LIQUID, workspace),
                    "Holland B-1/B-2 ideal liquid");
        }
        if (atVaporBoundary >= -FLASH_BOUNDARY_TOLERANCE) {
            return new V3FlashResult(V3FeedPhase.VAPOR, 0, 1.0, new double[0], z,
                    molarEnthalpy(temperatureKelvin, pressurePascal, z, V3Phase.VAPOR, workspace),
                    "Holland B-1/B-2 ideal vapor");
        }
        double lower = 0.0;
        double upper = 1.0;
        int iterations = 0;
        for (; iterations < 100; iterations++) {
            double beta = 0.5 * (lower + upper);
            double residual = rachfordRice(z, k, beta);
            if (Math.abs(residual) <= 1.0e-14 || upper - lower <= 1.0e-14) break;
            if (residual > 0.0) lower = beta;
            else upper = beta;
        }
        double beta = 0.5 * (lower + upper);
        double[] liquid = new double[z.length];
        double[] vapor = new double[z.length];
        for (int component = 0; component < z.length; component++) {
            liquid[component] = z[component] / (1.0 + beta * (k[component] - 1.0));
            vapor[component] = k[component] * liquid[component];
        }
        normalizeInPlace(liquid);
        normalizeInPlace(vapor);
        double liquidEnthalpy = molarEnthalpy(
                temperatureKelvin, pressurePascal, liquid, V3Phase.LIQUID, workspace);
        double vaporEnthalpy = molarEnthalpy(
                temperatureKelvin, pressurePascal, vapor, V3Phase.VAPOR, workspace);
        return new V3FlashResult(V3FeedPhase.TWO_PHASE, iterations, beta, liquid, vapor,
                (1.0 - beta) * liquidEnthalpy + beta * vaporEnthalpy,
                "Holland B-1/B-2 ideal two-phase flash");
    }

    double bubblePointTemperatureKelvin(double[] composition, double pressurePascal) {
        double[] z = normalized(composition);
        double lower = MINIMUM_TEMPERATURE_KELVIN;
        double upper = MAXIMUM_TEMPERATURE_KELVIN;
        double lowerResidual = bubbleResidual(z, lower, pressurePascal);
        double upperResidual = bubbleResidual(z, upper, pressurePascal);
        if (!(lowerResidual < 0.0 && upperResidual > 0.0)) {
            throw new IllegalArgumentException("Holland bubble point is outside the bounded fit range");
        }
        for (int iteration = 0; iteration < 120; iteration++) {
            double middle = 0.5 * (lower + upper);
            double residual = bubbleResidual(z, middle, pressurePascal);
            if (Math.abs(residual) <= 1.0e-14 || upper - lower <= 1.0e-12) return middle;
            if (residual > 0.0) upper = middle;
            else lower = middle;
        }
        return 0.5 * (lower + upper);
    }

    double[] equilibriumRatios(double temperatureKelvin, double pressurePascal) {
        requireTemperaturePressure(temperatureKelvin, pressurePascal);
        double rankine = temperatureKelvin * 1.8;
        double pressureScale = data.pressurePascal() / pressurePascal;
        double[] ratios = new double[data.componentCount()];
        for (int component = 0; component < ratios.length; component++) {
            double[] coefficient = kCoefficients[component];
            double cubeRoot = coefficient[0] + coefficient[1] * rankine
                    + coefficient[2] * rankine * rankine
                    + coefficient[3] * rankine * rankine * rankine;
            ratios[component] = rankine * cubeRoot * cubeRoot * cubeRoot * pressureScale;
            if (!Double.isFinite(ratios[component]) || ratios[component] <= 0.0) {
                throw new IllegalArgumentException("Holland B-1 fit produced a nonpositive K value");
            }
        }
        return ratios;
    }

    double[] componentEnthalpiesBtuPerLbMol(double temperatureKelvin, V3Phase phase) {
        requireTemperaturePressure(temperatureKelvin, data.pressurePascal());
        double rankine = temperatureKelvin * 1.8;
        double[][] coefficients = phase == V3Phase.LIQUID
                ? liquidEnthalpyCoefficients : vaporEnthalpyCoefficients;
        double[] enthalpies = new double[data.componentCount()];
        for (int component = 0; component < enthalpies.length; component++) {
            double[] coefficient = coefficients[component];
            double squareRoot = coefficient[0] + coefficient[1] * rankine
                    + coefficient[2] * rankine * rankine;
            enthalpies[component] = squareRoot * squareRoot;
        }
        return enthalpies;
    }

    private double bubbleResidual(double[] composition, double temperatureKelvin, double pressurePascal) {
        double[] ratios = equilibriumRatios(temperatureKelvin, pressurePascal);
        double residual = -1.0;
        for (int component = 0; component < ratios.length; component++) residual += composition[component] * ratios[component];
        return residual;
    }

    private void requireState(
            double temperatureKelvin, double pressurePascal, double[] composition,
            V3ThermoWorkspace workspace) {
        requireTemperaturePressure(temperatureKelvin, pressurePascal);
        Objects.requireNonNull(composition, "composition");
        workspace = Objects.requireNonNull(workspace, "workspace");
        if (composition.length != data.componentCount() || workspace.componentCount() != data.componentCount()) {
            throw new IllegalArgumentException("Holland thermodynamic basis or workspace differs from Example 3-2");
        }
    }

    private static void requireTemperaturePressure(double temperatureKelvin, double pressurePascal) {
        if (!Double.isFinite(temperatureKelvin) || temperatureKelvin < MINIMUM_TEMPERATURE_KELVIN
                || temperatureKelvin > MAXIMUM_TEMPERATURE_KELVIN
                || !Double.isFinite(pressurePascal) || pressurePascal < MINIMUM_PRESSURE_PASCAL
                || pressurePascal > MAXIMUM_PRESSURE_PASCAL) {
            throw new IllegalArgumentException("Holland B-1/B-2 state is outside the benchmark fit bounds");
        }
    }

    private static double[] normalized(double[] values) {
        values = Objects.requireNonNull(values, "values").clone();
        normalizeInPlace(values);
        return values;
    }

    private static void normalizeInPlace(double[] values) {
        double total = 0.0;
        for (double value : values) {
            if (!Double.isFinite(value) || value < 0.0) throw new IllegalArgumentException("Holland composition is invalid");
            total += value;
        }
        if (!Double.isFinite(total) || total <= 0.0) throw new IllegalArgumentException("Holland composition is empty");
        for (int component = 0; component < values.length; component++) values[component] /= total;
    }

    private static double rachfordRice(double[] z, double[] k, double beta) {
        double residual = 0.0;
        for (int component = 0; component < z.length; component++) {
            residual += z[component] * (k[component] - 1.0) / (1.0 + beta * (k[component] - 1.0));
        }
        return residual;
    }
}
