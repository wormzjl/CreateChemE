package com.wormzjl.createcheme.science.thermo;

/**
 * Ideal-gas heat-capacity polynomial expressed around a reference temperature.
 * Coefficients define Cp = c0 + c1*dT + c2*dT^2 + c3*dT^3 in joules per mole-kelvin.
 */
public record IdealGasHeatCapacity(
        double referenceTemperatureKelvin,
        double referenceEnthalpyJoulesPerMol,
        double constantCoefficient,
        double linearCoefficient,
        double quadraticCoefficient,
        double cubicCoefficient) {

    public IdealGasHeatCapacity {
        if (!(referenceTemperatureKelvin > 0.0)
                || !Double.isFinite(referenceTemperatureKelvin)
                || !Double.isFinite(referenceEnthalpyJoulesPerMol)
                || !Double.isFinite(constantCoefficient)
                || !Double.isFinite(linearCoefficient)
                || !Double.isFinite(quadraticCoefficient)
                || !Double.isFinite(cubicCoefficient)) {
            throw new IllegalArgumentException("Heat-capacity parameters must be finite");
        }
    }

    public static IdealGasHeatCapacity constant(
            double referenceTemperatureKelvin,
            double referenceEnthalpyJoulesPerMol,
            double heatCapacityJoulesPerMolKelvin) {
        return new IdealGasHeatCapacity(
                referenceTemperatureKelvin,
                referenceEnthalpyJoulesPerMol,
                heatCapacityJoulesPerMolKelvin,
                0.0,
                0.0,
                0.0);
    }

    public double heatCapacityJoulesPerMolKelvin(double temperatureKelvin) {
        double deltaTemperature = temperatureKelvin - referenceTemperatureKelvin;
        return constantCoefficient
                + deltaTemperature * (linearCoefficient
                        + deltaTemperature * (quadraticCoefficient
                                + deltaTemperature * cubicCoefficient));
    }

    public double enthalpyJoulesPerMol(double temperatureKelvin) {
        double deltaTemperature = temperatureKelvin - referenceTemperatureKelvin;
        return referenceEnthalpyJoulesPerMol
                + deltaTemperature * (constantCoefficient
                        + deltaTemperature * (0.5 * linearCoefficient
                                + deltaTemperature * (quadraticCoefficient / 3.0
                                        + deltaTemperature * 0.25 * cubicCoefficient)));
    }
}
