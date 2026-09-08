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

    /**
     * {@code d ln(P_sat)/dT} in 1/K, differentiating the same Wagner--Pruss auxiliary equation.
     *
     * <p>The log is what the wet-tray saturation row carries, and it is also what stays finite here: the
     * pressure itself spans orders of magnitude over the correlation's range, while its logarithmic slope is
     * an order-one number everywhere including the critical point, where {@code theta} vanishes and only the
     * linear coefficient survives. The value function's exact-critical shortcut needs no counterpart, because
     * the expression below is already the limit that shortcut stands for.</p>
     */
    public static double dLogSaturationPressureDT(double temperatureKelvin) {
        requireSaturationTemperature(temperatureKelvin);
        double theta = 1.0 - temperatureKelvin / CRITICAL_TEMPERATURE_KELVIN;
        double polynomial = -7.85951783 * theta + 1.84408259 * Math.pow(theta, 1.5)
                - 11.7866497 * theta * theta * theta + 22.6807411 * Math.pow(theta, 3.5)
                - 15.9618719 * theta * theta * theta * theta + 1.80122502 * Math.pow(theta, 7.5);
        double slope = -7.85951783 + 1.5 * 1.84408259 * Math.sqrt(theta)
                - 3.0 * 11.7866497 * theta * theta + 3.5 * 22.6807411 * Math.pow(theta, 2.5)
                - 4.0 * 15.9618719 * theta * theta * theta + 7.5 * 1.80122502 * Math.pow(theta, 6.5);
        return -CRITICAL_TEMPERATURE_KELVIN * polynomial / (temperatureKelvin * temperatureKelvin)
                - slope / temperatureKelvin;
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

    /**
     * The water-vapour heat capacity in J/(mol K): the Shomate polynomial the enthalpy integrates.
     *
     * <p>Differentiating the integral rather than quoting the Shomate coefficients again keeps one set of
     * numbers in the file, so the capacity cannot drift away from the enthalpy it belongs to.</p>
     */
    public static double dVaporMolarEnthalpyDT(double temperatureKelvin) {
        requireEnthalpyTemperature(temperatureKelvin);
        double t = temperatureKelvin / 1_000.0;
        return 30.09200 + 6.832514 * t + 6.793435 * t * t - 2.534480 * t * t * t + 0.082139 / (t * t);
    }

    public static double vaporizationEnthalpy(double temperatureKelvin) {
        requireEnthalpyTemperature(temperatureKelvin);
        if (temperatureKelvin >= CRITICAL_TEMPERATURE_KELVIN) return 0.0;
        double reduced = (1.0 - temperatureKelvin / CRITICAL_TEMPERATURE_KELVIN)
                / (1.0 - REFERENCE_BOILING_TEMPERATURE_KELVIN / CRITICAL_TEMPERATURE_KELVIN);
        return REFERENCE_VAPORIZATION_ENTHALPY_JOULES_PER_MOL * Math.pow(Math.max(0.0, reduced), WATSON_EXPONENT);
    }

    /**
     * The Watson correlation's temperature slope in J/(mol K); zero from the critical point upward.
     *
     * <p>Zero above the critical temperature is the derivative of the constant zero the value returns there,
     * and is the correct one-sided answer at the critical point itself, where the true slope is unbounded.
     * Approaching it from below the slope does diverge, which is a property of the correlation and not of this
     * derivative: the free water that uses it exists only where liquid water does.</p>
     */
    public static double dVaporizationEnthalpyDT(double temperatureKelvin) {
        requireEnthalpyTemperature(temperatureKelvin);
        if (temperatureKelvin >= CRITICAL_TEMPERATURE_KELVIN) return 0.0;
        double span = 1.0 - REFERENCE_BOILING_TEMPERATURE_KELVIN / CRITICAL_TEMPERATURE_KELVIN;
        double reduced = (1.0 - temperatureKelvin / CRITICAL_TEMPERATURE_KELVIN) / span;
        return REFERENCE_VAPORIZATION_ENTHALPY_JOULES_PER_MOL * WATSON_EXPONENT
                * Math.pow(reduced, WATSON_EXPONENT - 1.0) * (-1.0 / (CRITICAL_TEMPERATURE_KELVIN * span));
    }

    public static double liquidMolarEnthalpy(double temperatureKelvin) {
        return vaporMolarEnthalpy(temperatureKelvin) - vaporizationEnthalpy(temperatureKelvin);
    }

    /** The liquid-water heat capacity in J/(mol K), consistent with {@link #liquidMolarEnthalpy} by construction. */
    public static double dLiquidMolarEnthalpyDT(double temperatureKelvin) {
        return dVaporMolarEnthalpyDT(temperatureKelvin) - dVaporizationEnthalpyDT(temperatureKelvin);
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
