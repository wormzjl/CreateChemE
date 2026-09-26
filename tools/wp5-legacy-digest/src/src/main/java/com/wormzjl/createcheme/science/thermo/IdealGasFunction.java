package com.wormzjl.createcheme.science.thermo;

/**
 * Ideal-gas reference functions of one species: the data spine of the unified multiphase thermodynamics plan
 * (batch 2026-09-24-coolprop-low-temperature, plan section 4, decisions D1 and D6).
 *
 * <p>The convention is shared by every evaluator family: {@code enthalpy(298.15 K)} is the standard enthalpy of
 * formation of the species, {@code entropy(298.15 K, 1e5 Pa)} its standard entropy, both per mole. A phase
 * evaluator adds its residual part to these values, so the same species has one enthalpy and entropy origin in
 * every phase. Calls outside {@code [minimumTemperature(), maximumTemperature()]} are refused with an
 * {@link IllegalArgumentException}; the implementation never extrapolates.</p>
 */
public interface IdealGasFunction {
    /** Ideal-gas isobaric heat capacity, J/(mol K). */
    double heatCapacity(double temperatureKelvin);

    /** Ideal-gas enthalpy, J/mol, equal to the standard enthalpy of formation at 298.15 K. */
    double enthalpy(double temperatureKelvin);

    /** Ideal-gas entropy, J/(mol K), at the given pressure; equal to the standard entropy at 298.15 K and 1e5 Pa. */
    double entropy(double temperatureKelvin, double pressurePascal);

    /** Lowest temperature of the declared segments, K. */
    double minimumTemperature();

    /** Highest temperature of the declared segments, K. */
    double maximumTemperature();

    /** Revision of the record the function was read from; part of the thermodynamic identity of a package. */
    String revision();
}
