package com.wormzjl.createcheme.science.column.v3.thermo;

import com.wormzjl.createcheme.science.column.v3.V3ComponentBasis;

/** Narrow immutable hydrocarbon thermodynamic boundary for V3; free-water steam is handled by {@link V3WaterProperties}. */
public interface V3ThermoModel {
    V3ComponentBasis componentBasis();

    V3ThermoWorkspace newWorkspace();

    V3FugacityResult fugacity(
            double temperatureKelvin, double pressurePascal, double[] composition, V3Phase phase,
            V3ThermoWorkspace workspace);

    double molarEnthalpy(
            double temperatureKelvin, double pressurePascal, double[] composition, V3Phase phase,
            V3ThermoWorkspace workspace);

    V3FlashResult flashTP(
            double temperatureKelvin, double pressurePascal, double[] overallComposition, V3ThermoWorkspace workspace);
}
