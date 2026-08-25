package com.wormzjl.createcheme.science.thermo;

import java.util.List;
import java.util.Objects;

/** Adds ideal-gas reference enthalpy to Peng-Robinson residual properties. */
public final class PengRobinsonCaloricModel {
    private final PengRobinson78 equationOfState;
    private final List<IdealGasHeatCapacity> heatCapacities;

    public PengRobinsonCaloricModel(
            PengRobinson78 equationOfState, List<IdealGasHeatCapacity> heatCapacities) {
        this.equationOfState = Objects.requireNonNull(equationOfState, "equationOfState");
        this.heatCapacities = List.copyOf(Objects.requireNonNull(heatCapacities, "heatCapacities"));
        if (this.heatCapacities.size() != equationOfState.componentCount()) {
            throw new IllegalArgumentException("Heat-capacity count must match the component count");
        }
    }

    public CaloricPhaseProperties evaluate(
            double temperatureKelvin,
            double pressurePascal,
            double[] moleFractions,
            PhaseRoot phaseRoot) {
        PhaseProperties phaseProperties = equationOfState.evaluate(
                temperatureKelvin, pressurePascal, moleFractions, phaseRoot);

        double fractionSum = 0.0;
        double idealGasEnthalpy = 0.0;
        for (int i = 0; i < moleFractions.length; i++) {
            fractionSum += moleFractions[i];
            idealGasEnthalpy += moleFractions[i]
                    * heatCapacities.get(i).enthalpyJoulesPerMol(temperatureKelvin);
        }
        return new CaloricPhaseProperties(phaseProperties, idealGasEnthalpy / fractionSum);
    }
}
