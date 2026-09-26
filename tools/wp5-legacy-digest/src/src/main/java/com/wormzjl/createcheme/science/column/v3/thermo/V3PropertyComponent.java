package com.wormzjl.createcheme.science.column.v3.thermo;

import java.util.Objects;

/** Immutable SI property data for one hydrocarbon in a registered V3 package. */
record V3PropertyComponent(
        String id,
        String displayName,
        double molecularWeightKgPerMol,
        double normalBoilingPointKelvin,
        double criticalTemperatureKelvin,
        double criticalPressurePascal,
        double acentricFactor,
        double standardLiquidDensityKgPerCubicMetre,
        double cpA,
        double cpB,
        double cpC,
        double cpD,
        double cpE,
        double cpF,
        boolean estimatedHeavyResidue) {
    /** Preserves the existing cubic datasets and their arithmetic exactly. */
    V3PropertyComponent(String id, String displayName, double mw, double nbp, double tc, double pc,
            double omega, double density, double cpA, double cpB, double cpC, double cpD, boolean estimated) {
        this(id, displayName, mw, nbp, tc, pc, omega, density, cpA, cpB, cpC, cpD, 0.0, 0.0, estimated);
    }
    V3PropertyComponent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        if (id.isBlank() || !Double.isFinite(molecularWeightKgPerMol) || molecularWeightKgPerMol <= 0.0
                || !Double.isFinite(normalBoilingPointKelvin) || !Double.isFinite(criticalTemperatureKelvin)
                || !Double.isFinite(criticalPressurePascal) || criticalPressurePascal <= 0.0
                || !Double.isFinite(acentricFactor) || !Double.isFinite(standardLiquidDensityKgPerCubicMetre)
                || standardLiquidDensityKgPerCubicMetre <= 0.0 || !Double.isFinite(cpA) || !Double.isFinite(cpB)
                || !Double.isFinite(cpC) || !Double.isFinite(cpD) || !Double.isFinite(cpE)
                || !Double.isFinite(cpF)) {
            throw new IllegalArgumentException("Invalid V3 property component: " + id);
        }
    }

    /** Ideal-gas heat capacity in J/(mol K) about the shared 298.15 K reporting datum. */
    double idealGasHeatCapacity(double temperatureKelvin) {
        double delta = temperatureKelvin - 298.15;
        double cubic = cpA + cpB * delta + cpC * delta * delta + cpD * delta * delta * delta;
        if (cpE == 0.0 && cpF == 0.0) return cubic;
        return cubic + Math.pow(delta, 4) * (cpE + cpF * delta);
    }

    /** Analytic ideal-gas enthalpy relative to 298.15 K in J/mol. */
    double idealGasEnthalpy(double temperatureKelvin) {
        double delta = temperatureKelvin - 298.15;
        double cubicIntegral = cpA * delta + 0.5 * cpB * delta * delta + cpC * delta * delta * delta / 3.0
                + 0.25 * cpD * delta * delta * delta * delta;
        if (cpE == 0.0 && cpF == 0.0) return cubicIntegral;
        return cubicIntegral + Math.pow(delta, 5) * (cpE / 5.0 + cpF * delta / 6.0);
    }
}
