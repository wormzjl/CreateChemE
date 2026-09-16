package com.wormzjl.createcheme.science.fluid.thermo;

/**
 * Shared liquid compressibility approximation, independent of material identity.
 * v(P)=v(ref)*exp(-k*(P-Pref)); pressure corrections are integrated consistently into Gibbs energy/enthalpy.
 */
public final class GlobalLiquidResponse {
    public static final double DEFAULT_COMPRESSIBILITY_PER_PASCAL = 1e-9;
    private static final double R = 8.31446261815324;
    private final double compressibility;

    public GlobalLiquidResponse(double compressibilityPerPascal) {
        if (!Double.isFinite(compressibilityPerPascal) || compressibilityPerPascal <= 0) {
            throw new IllegalArgumentException("Liquid compressibility must be finite and positive");
        }
        compressibility = compressibilityPerPascal;
    }
    public double compressibilityPerPascal() { return compressibility; }

    /** Reference values/derivatives are evaluated at the same T and composition, at a fixed reference pressure. */
    public State evaluate(double temperature, double pressure, double referencePressure,
                          double referenceVolume, double referenceVolumeTemperatureDerivative,
                          double referenceVolumeSecondTemperatureDerivative, double referenceEnthalpy,
                          double referenceHeatCapacity) {
        double[] inputs = {temperature,pressure,referencePressure,referenceVolume,
                referenceVolumeTemperatureDerivative,referenceVolumeSecondTemperatureDerivative,
                referenceEnthalpy,referenceHeatCapacity};
        for (double value : inputs) if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite liquid state");
        if (temperature <= 0 || pressure <= 0 || referencePressure <= 0 || referenceVolume <= 0) {
            throw new IllegalArgumentException("Nonpositive liquid state");
        }
        double exponent = -compressibility*(pressure-referencePressure);
        if (Math.abs(exponent) > 1) throw new IllegalArgumentException("Liquid compression approximation outside domain");
        double factor = Math.exp(exponent);
        double integral = -Math.expm1(exponent)/compressibility;
        double v = referenceVolume*factor;
        double vt = referenceVolumeTemperatureDerivative*factor;
        double h = referenceEnthalpy + (referenceVolume-temperature*referenceVolumeTemperatureDerivative)*integral;
        double cp = referenceHeatCapacity-temperature*referenceVolumeSecondTemperatureDerivative*integral;
        if (!(cp > 0) || !Double.isFinite(h) || !Double.isFinite(cp)) throw new IllegalArgumentException("Invalid liquid caloric state");
        return new State(v,h,h-pressure*v,vt,-compressibility*v,cp,v-temperature*vt,integral);
    }

    /** Chemical-potential correction matching the volume integral; arrays represent partial molar volumes at Pref. */
    public double[] logFugacity(double temperature,double pressure,double referencePressure,
                               double integral,double[] referenceLogPhi,double[] partialReferenceVolumes) {
        if (referenceLogPhi.length != partialReferenceVolumes.length || !(temperature > 0) || !(pressure > 0)
                || !(referencePressure > 0) || !Double.isFinite(temperature) || !Double.isFinite(pressure)
                || !Double.isFinite(referencePressure) || !Double.isFinite(integral)) throw new IllegalArgumentException("Invalid liquid fugacity inputs");
        double[] result = referenceLogPhi.clone();
        for (int i=0;i<result.length;i++) {
            result[i] += Math.log(referencePressure/pressure) + partialReferenceVolumes[i]*integral/(R*temperature);
            if (!Double.isFinite(result[i])) throw new IllegalArgumentException("Nonfinite liquid fugacity");
        }
        return result;
    }
    public record State(double molarVolume,double molarEnthalpy,double molarInternalEnergy,
                        double volumeTemperatureDerivative,double volumePressureDerivative,
                        double heatCapacity,double enthalpyPressureDerivative,double pressureIntegral) {}
}
