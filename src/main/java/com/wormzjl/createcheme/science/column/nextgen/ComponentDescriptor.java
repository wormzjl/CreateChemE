package com.wormzjl.createcheme.science.column.nextgen;

import java.util.Objects;

/** Immutable SI component data used by one registered column package. */
public record ComponentDescriptor(
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
        boolean hydrocarbon,
        boolean estimatedHeavyResidue) {
    public ComponentDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        if (id.isBlank() || !Double.isFinite(molecularWeightKgPerMol) || molecularWeightKgPerMol <= 0.0
                || !Double.isFinite(normalBoilingPointKelvin) || !Double.isFinite(criticalTemperatureKelvin)
                || !Double.isFinite(criticalPressurePascal) || criticalPressurePascal <= 0.0
                || !Double.isFinite(acentricFactor) || !Double.isFinite(standardLiquidDensityKgPerCubicMetre)
                || standardLiquidDensityKgPerCubicMetre <= 0.0 || !Double.isFinite(cpA) || !Double.isFinite(cpB)
                || !Double.isFinite(cpC) || !Double.isFinite(cpD)) {
            throw new IllegalArgumentException("Invalid component descriptor: " + id);
        }
    }

    /** Ideal-gas heat capacity in J/(mol K) about the shared 298.15 K reporting datum. */
    public double idealGasHeatCapacity(double temperatureKelvin) {
        double delta = temperatureKelvin - 298.15;
        return cpA + cpB * delta + cpC * delta * delta + cpD * delta * delta * delta;
    }

    /** Analytic ideal-gas enthalpy relative to 298.15 K in J/mol. */
    public double idealGasEnthalpy(double temperatureKelvin) {
        double delta = temperatureKelvin - 298.15;
        return cpA * delta + 0.5 * cpB * delta * delta + cpC * delta * delta * delta / 3.0
                + 0.25 * cpD * delta * delta * delta * delta;
    }
}
