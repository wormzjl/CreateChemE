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


    public static String revision() { return data().revision(); }
    public static double molarMass() { return data().molarMass(); }
    public static double triplePoint() { return data().triplePoint(); }
    public static double criticalTemperature() { return data().criticalTemperature(); }
    public static double criticalPressure() { return data().criticalPressure(); }
    public static double maximumTemperature() { return data().maximumTemperature(); }
    private V3WaterProperties() {}

    private static com.wormzjl.createcheme.science.material.MaterialCatalog.Water data() {
        return com.wormzjl.createcheme.science.material.MaterialRuntime.water();
    }


    /** Saturation pressure in Pa, valid from the triple point through the critical point. */
    public static double saturationPressurePascal(double temperatureKelvin) {
        requireSaturationTemperature(temperatureKelvin);
        if (temperatureKelvin == data().criticalTemperature()) return data().criticalPressure();
        double theta = 1.0 - temperatureKelvin / data().criticalTemperature();
        double polynomial = data().saturation().get(0) * theta + data().saturation().get(1) * Math.pow(theta, 1.5)
                + data().saturation().get(2) * theta * theta * theta + data().saturation().get(3) * Math.pow(theta, 3.5)
                + data().saturation().get(4) * theta * theta * theta * theta + data().saturation().get(5) * Math.pow(theta, 7.5);
        return data().criticalPressure() * Math.exp(data().criticalTemperature() / temperatureKelvin * polynomial);
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
        double theta = 1.0 - temperatureKelvin / data().criticalTemperature();
        double polynomial = data().saturation().get(0) * theta + data().saturation().get(1) * Math.pow(theta, 1.5)
                + data().saturation().get(2) * theta * theta * theta + data().saturation().get(3) * Math.pow(theta, 3.5)
                + data().saturation().get(4) * theta * theta * theta * theta + data().saturation().get(5) * Math.pow(theta, 7.5);
        double slope = data().saturation().get(0) + 1.5 * data().saturation().get(1) * Math.sqrt(theta)
                + 3.0 * data().saturation().get(2) * theta * theta + 3.5 * data().saturation().get(3) * Math.pow(theta, 2.5)
                + 4.0 * data().saturation().get(4) * theta * theta * theta + 7.5 * data().saturation().get(5) * Math.pow(theta, 6.5);
        return -data().criticalTemperature() * polynomial / (temperatureKelvin * temperatureKelvin)
                - slope / temperatureKelvin;
    }

    /** Inverts {@link #saturationPressurePascal(double)} by bounded bisection. */
    public static double saturationTemperatureKelvin(double pressurePascal) {
        if (!Double.isFinite(pressurePascal) || pressurePascal <= 0.0 || pressurePascal > data().criticalPressure()) {
            throw new IllegalArgumentException("Water saturation pressure is outside the correlation envelope");
        }
        double minimumPressure = saturationPressurePascal(data().triplePoint());
        if (pressurePascal < minimumPressure) {
            throw new IllegalArgumentException("Water saturation pressure is below the triple-point envelope");
        }
        double low = data().triplePoint();
        double high = data().criticalTemperature();
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
        double kiloJoulesPerMol = data().shomate().get(0) * t + data().shomate().get(1) * t * t / 2.0 + data().shomate().get(2) * t * t * t / 3.0
                + data().shomate().get(3) * t * t * t * t / 4.0 - data().shomate().get(4) / t + data().shomate().get(5) + data().shomate().get(6);
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
        return data().shomate().get(0) + data().shomate().get(1) * t + data().shomate().get(2) * t * t + data().shomate().get(3) * t * t * t + data().shomate().get(4) / (t * t);
    }

    public static double vaporizationEnthalpy(double temperatureKelvin) {
        requireEnthalpyTemperature(temperatureKelvin);
        if (temperatureKelvin >= data().criticalTemperature()) return 0.0;
        double reduced = (1.0 - temperatureKelvin / data().criticalTemperature())
                / (1.0 - data().boilingTemperature() / data().criticalTemperature());
        return data().vaporizationEnthalpy() * Math.pow(Math.max(0.0, reduced), data().watsonExponent());
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
        if (temperatureKelvin >= data().criticalTemperature()) return 0.0;
        double span = 1.0 - data().boilingTemperature() / data().criticalTemperature();
        double reduced = (1.0 - temperatureKelvin / data().criticalTemperature()) / span;
        return data().vaporizationEnthalpy() * data().watsonExponent()
                * Math.pow(reduced, data().watsonExponent() - 1.0) * (-1.0 / (data().criticalTemperature() * span));
    }

    public static double liquidMolarEnthalpy(double temperatureKelvin) {
        return vaporMolarEnthalpy(temperatureKelvin) - vaporizationEnthalpy(temperatureKelvin);
    }

    /** The liquid-water heat capacity in J/(mol K), consistent with {@link #liquidMolarEnthalpy} by construction. */
    public static double dLiquidMolarEnthalpyDT(double temperatureKelvin) {
        return dVaporMolarEnthalpyDT(temperatureKelvin) - dVaporizationEnthalpyDT(temperatureKelvin);
    }

    private static void requireSaturationTemperature(double temperatureKelvin) {
        if (!Double.isFinite(temperatureKelvin) || temperatureKelvin < data().triplePoint()
                || temperatureKelvin > data().criticalTemperature()) {
            throw new IllegalArgumentException("Water saturation temperature is outside the correlation envelope");
        }
    }

    private static void requireEnthalpyTemperature(double temperatureKelvin) {
        if (!Double.isFinite(temperatureKelvin) || temperatureKelvin < data().triplePoint()
                || temperatureKelvin > data().maximumTemperature()) {
            throw new IllegalArgumentException("Water enthalpy temperature is outside the correlation envelope");
        }
    }
}
