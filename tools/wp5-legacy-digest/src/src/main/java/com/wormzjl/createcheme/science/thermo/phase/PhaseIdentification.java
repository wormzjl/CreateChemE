package com.wormzjl.createcheme.science.thermo.phase;

import com.wormzjl.createcheme.science.thermo.PengRobinsonKernel;

/**
 * The phase identification parameter of Venkatarathnam and Oellrich (2011, Fluid Phase Equilibria 301, 225-233) on
 * the untranslated PR78 cubic: a liquid-like or vapour-like label for one fluid state that needs no saturation
 * property and no mixture critical point.
 *
 * <p>{@code PIP = v [ (d2P/dT dv) / (dP/dT)_v - (d2P/dv2) / (dP/dv)_T ]}, which is
 * {@code d ln[(dP/dT)_v / -(dP/dv)_T] / d ln v} at constant temperature. The ideal gas has {@code PIP = 1} exactly;
 * a dense liquid tends to {@code v/(v - b) > 1}; a dilute real gas is {@code 1 + (B - T dB/dT)/v < 1} with the second
 * virial coefficient {@code B = b - a/(R T)} of the cubic. So {@code PIP > 1} is liquid-like and {@code PIP < 1}
 * vapour-like (gas-like), as the paper states; the P3 plan's parenthesis "(> 1 vapour-like)" has the sign reversed and
 * is not followed.</p>
 *
 * <p>For the PR78 form {@code P = R T/(v - b) - a/D}, {@code D = v^2 + 2 b v - b^2}, the four derivatives are the ones
 * {@code TranslatedPengRobinson.fill} forms for its volumetric block:</p>
 * <pre>
 *   dP/dv    = -R T/(v - b)^2 + 2 a (v + b)/D^2
 *   dP/dT    =  R/(v - b) - (da/dT)/D
 *   d2P/dv2  =  2 R T/(v - b)^3 + 2 a/D^2 - 8 a (v + b)^2/D^3
 *   d2P/dTdv = -R/(v - b)^2 + 2 (da/dT)(v + b)/D^2
 * </pre>
 * <p>so the parameter needs the mixture's {@code a}, {@code b}, {@code da/dT} and the root's volume only: no second
 * temperature derivative of {@code a} and no composition derivative. It is evaluated on the untranslated volume
 * {@code v = Z R T/P}, the cubic on which roots are selected and equilibria computed (a constant translation shifts
 * the volume axis and so changes the {@code v} prefactor; the label belongs to the root, not to the density
 * correction). It is undefined where {@code (dP/dT)_v = 0} (never for a PR root with {@code v > b} and
 * {@code da/dT < 0}) and at a mechanically unstable root ({@code dP/dv >= 0}, refused by the evaluators anyway);
 * at a pure-component critical point {@code dP/dv} and {@code d2P/dv2} vanish together and the ratio has a finite
 * limit, but a state exactly there is not separated from its neighbours by this parameter, which is why the critical
 * band ({@link FluidTpEquilibrium}) grades such states research-only whatever their label.</p>
 */
public final class PhaseIdentification {
    private static final double R = PengRobinsonKernel.GAS_CONSTANT;

    private PhaseIdentification() {}

    /**
     * The parameter at molar volume {@code v} of the untranslated cubic with mixture parameters {@code a}, {@code b} and
     * {@code da/dT}; {@code NaN} where it is undefined.
     */
    public static double parameter(double temperatureKelvin, double volume, double a, double b, double dadt) {
        double free = volume - b;
        double denominator = volume * volume + 2.0 * b * volume - b * b;
        double dpdv = -R * temperatureKelvin / (free * free) + 2.0 * a * (volume + b) / (denominator * denominator);
        double dpdt = R / free - dadt / denominator;
        double dpdvv = 2.0 * R * temperatureKelvin / (free * free * free) + 2.0 * a / (denominator * denominator)
                - 8.0 * a * (volume + b) * (volume + b) / (denominator * denominator * denominator);
        double dpdtv = -R / (free * free) + 2.0 * dadt * (volume + b) / (denominator * denominator);
        if (!(dpdv < 0.0) || dpdt == 0.0) return Double.NaN;
        return volume * (dpdtv / dpdt - dpdvv / dpdv);
    }

    /** The parameter of a kernel evaluation at its temperature and pressure (its root's untranslated volume). */
    public static double parameter(PengRobinsonKernel.Evaluation evaluation, double temperatureKelvin, double pressurePascal) {
        double volume = evaluation.compressibility() * R * temperatureKelvin / pressurePascal;
        return parameter(temperatureKelvin, volume, evaluation.aMix(), evaluation.bMix(), evaluation.daMixDt());
    }

    /** Liquid-like: {@code PIP > 1}. An undefined parameter is not liquid-like. */
    public static boolean liquidLike(double parameter) { return parameter > 1.0; }

    /** Vapour-like: {@code PIP <= 1} (the ideal gas, exactly 1, is vapour). An undefined parameter is not vapour-like. */
    public static boolean vapourLike(double parameter) { return parameter <= 1.0; }
}
