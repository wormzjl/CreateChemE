package com.wormzjl.createcheme.science.thermo;

import java.util.Objects;

/** Immutable equation-of-state and caloric properties for one phase. */
public final class CaloricPhaseProperties {
    private final PhaseProperties phaseProperties;
    private final double idealGasEnthalpyJoulesPerMol;

    CaloricPhaseProperties(
            PhaseProperties phaseProperties, double idealGasEnthalpyJoulesPerMol) {
        this.phaseProperties = Objects.requireNonNull(phaseProperties, "phaseProperties");
        this.idealGasEnthalpyJoulesPerMol = idealGasEnthalpyJoulesPerMol;
    }

    public PhaseProperties phaseProperties() {
        return phaseProperties;
    }

    public double idealGasEnthalpyJoulesPerMol() {
        return idealGasEnthalpyJoulesPerMol;
    }

    public double residualEnthalpyJoulesPerMol() {
        return phaseProperties.residualEnthalpyJoulesPerMol();
    }

    public double enthalpyJoulesPerMol() {
        return idealGasEnthalpyJoulesPerMol + residualEnthalpyJoulesPerMol();
    }

    @Override
    public String toString() {
        return "CaloricPhaseProperties[phaseProperties=" + phaseProperties
                + ", idealGasEnthalpyJoulesPerMol=" + idealGasEnthalpyJoulesPerMol
                + ", enthalpyJoulesPerMol=" + enthalpyJoulesPerMol() + ']';
    }
}
