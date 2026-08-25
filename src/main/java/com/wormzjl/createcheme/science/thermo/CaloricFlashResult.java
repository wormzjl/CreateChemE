package com.wormzjl.createcheme.science.thermo;

import java.util.Objects;

/** Immutable equilibrium and enthalpy result from a TP flash. */
public final class CaloricFlashResult {
    private final double temperatureKelvin;
    private final double pressurePascal;
    private final FlashResult equilibrium;
    private final CaloricPhaseProperties liquidProperties;
    private final CaloricPhaseProperties vaporProperties;
    private final double enthalpyJoulesPerMol;

    CaloricFlashResult(
            double temperatureKelvin,
            double pressurePascal,
            FlashResult equilibrium,
            CaloricPhaseProperties liquidProperties,
            CaloricPhaseProperties vaporProperties) {
        this.temperatureKelvin = temperatureKelvin;
        this.pressurePascal = pressurePascal;
        this.equilibrium = Objects.requireNonNull(equilibrium, "equilibrium");
        this.liquidProperties = Objects.requireNonNull(liquidProperties, "liquidProperties");
        this.vaporProperties = Objects.requireNonNull(vaporProperties, "vaporProperties");
        this.enthalpyJoulesPerMol = (1.0 - equilibrium.vaporFraction())
                        * liquidProperties.enthalpyJoulesPerMol()
                + equilibrium.vaporFraction() * vaporProperties.enthalpyJoulesPerMol();
    }

    public double temperatureKelvin() {
        return temperatureKelvin;
    }

    public double pressurePascal() {
        return pressurePascal;
    }

    public FlashResult equilibrium() {
        return equilibrium;
    }

    public CaloricPhaseProperties liquidProperties() {
        return liquidProperties;
    }

    public CaloricPhaseProperties vaporProperties() {
        return vaporProperties;
    }

    public double enthalpyJoulesPerMol() {
        return enthalpyJoulesPerMol;
    }

    @Override
    public String toString() {
        return "CaloricFlashResult[temperatureKelvin=" + temperatureKelvin
                + ", pressurePascal=" + pressurePascal
                + ", equilibrium=" + equilibrium
                + ", enthalpyJoulesPerMol=" + enthalpyJoulesPerMol + ']';
    }
}
