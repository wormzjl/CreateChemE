package com.wormzjl.createcheme.science.column.v3.thermo;

/**
 * Pinned, dependency-free pure-water correlations for V3's free-water steam contract.
 *
 * <p>The saturation-pressure relation is the IAPWS Wagner--Pruss auxiliary equation. Vapor
 * enthalpy uses the NIST H2O(g) Shomate heat-capacity integral; its arbitrary reference cancels
 * in column energy differences. Liquid enthalpy is deliberately reference-consistent through the
 * vaporization enthalpy correlation.</p>
 */
public final class V3WaterProperties {
    public static final String DATA_REVISION = "water-iapws-shomate-r1";
    public static final double MOLAR_MASS_KG_PER_MOL = 0.01801528;
    public static final double TRIPLE_POINT_KELVIN = 273.16;
    public static final double CRITICAL_TEMPERATURE_KELVIN = 647.096;
    public static final double CRITICAL_PRESSURE_PASCAL = 22.064e6;
    public static final double MAX_ENTHALPY_TEMPERATURE_KELVIN = 900.0;

    private static final double REFERENCE_BOILING_TEMPERATURE_KELVIN = 373.15;
    private static final double REFERENCE_VAPORIZATION_ENTHALPY_JOULES_PER_MOL = 40_660.0;
    private static final double WATSON_EXPONENT = 0.38;

    private V3WaterProperties() {}

    /** Saturation pressure in Pa, valid from the triple point through the critical point. */
    public static double saturationPressurePascal(double temperatureKelvin) {
        requireSaturationTemperature(temperatureKelvin);
        if (temperatureKelvin == CRITICAL_TEMPERATURE_KELVIN) return CRITICAL_PRESSURE_PASCAL;
        double theta = 1.0 - temperatureKelvin / CRITICAL_TEMPERATURE_KELVIN;
        double polynomial = -7.85951783 * theta + 1.84408259 * Math.pow(theta, 1.5)
                - 11.7866497 * theta * theta * theta + 22.6807411 * Math.pow(theta, 3.5)
                - 15.9618719 * theta * theta * theta * theta + 1.80122502 * Math.pow(theta, 7.5);
        return CRITICAL_PRESSURE_PASCAL * Math.exp(CRITICAL_TEMPERATURE_KELVIN / temperatureKelvin * polynomial);
    }

    /** Inverts {@link #saturationPressurePascal(double)} by bounded bisection. */
    public static double saturationTemperatureKelvin(double pressurePascal) {
        if (!Double.isFinite(pressurePascal) || pressurePascal <= 0.0 || pressurePascal > CRITICAL_PRESSURE_PASCAL) {
            throw new IllegalArgumentException("Water saturation pressure is outside the correlation envelope");
        }
        double minimumPressure = saturationPressurePascal(TRIPLE_POINT_KELVIN);
        if (pressurePascal < minimumPressure) {
            throw new IllegalArgumentException("Water saturation pressure is below the triple-point envelope");
        }
        double low = TRIPLE_POINT_KELVIN;
        double high = CRITICAL_TEMPERATURE_KELVIN;
        for (int iteration = 0; iteration < 80; iteration++) {
            double middle = 0.5 * (low + high);
            if (saturationPressurePascal(middle) < pressurePascal) low = middle;
            else high = middle;
        }
        return 0.5 * (low + high);
    }

    /** Ideal-gas water-vapor molar enthalpy in J/mol, valid from 273.16 K through 900 K. */
    public static double vaporMolarEnthalpy(double temperatureKelvin) {
        requireEnthalpyTemperature(temperatureKelvin);
        double t = temperatureKelvin / 1_000.0;
        // NIST H2O(g), Shomate 500--1700 K; the smooth ideal-gas continuation is pinned for V3's lower envelope.
        double kiloJoulesPerMol = 30.09200 * t + 6.832514 * t * t / 2.0 + 6.793435 * t * t * t / 3.0
                - 2.534480 * t * t * t * t / 4.0 - 0.082139 / t - 250.8810 + 241.8264;
        return 1_000.0 * kiloJoulesPerMol;
    }

    public static double vaporizationEnthalpy(double temperatureKelvin) {
        requireEnthalpyTemperature(temperatureKelvin);
        if (temperatureKelvin >= CRITICAL_TEMPERATURE_KELVIN) return 0.0;
        double reduced = (1.0 - temperatureKelvin / CRITICAL_TEMPERATURE_KELVIN)
                / (1.0 - REFERENCE_BOILING_TEMPERATURE_KELVIN / CRITICAL_TEMPERATURE_KELVIN);
        return REFERENCE_VAPORIZATION_ENTHALPY_JOULES_PER_MOL * Math.pow(Math.max(0.0, reduced), WATSON_EXPONENT);
    }

    public static double liquidMolarEnthalpy(double temperatureKelvin) {
        return vaporMolarEnthalpy(temperatureKelvin) - vaporizationEnthalpy(temperatureKelvin);
    }

    private static void requireSaturationTemperature(double temperatureKelvin) {
        if (!Double.isFinite(temperatureKelvin) || temperatureKelvin < TRIPLE_POINT_KELVIN
                || temperatureKelvin > CRITICAL_TEMPERATURE_KELVIN) {
            throw new IllegalArgumentException("Water saturation temperature is outside the correlation envelope");
        }
    }

    private static void requireEnthalpyTemperature(double temperatureKelvin) {
        if (!Double.isFinite(temperatureKelvin) || temperatureKelvin < TRIPLE_POINT_KELVIN
                || temperatureKelvin > MAX_ENTHALPY_TEMPERATURE_KELVIN) {
            throw new IllegalArgumentException("Water enthalpy temperature is outside the correlation envelope");
        }
    }
}
