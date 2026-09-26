package com.wormzjl.createcheme.science.material;

import com.google.gson.JsonObject;
import com.wormzjl.createcheme.science.thermo.PengRobinsonKernel;

/**
 * The {@code volume_translation} of a property record (P3 WP3, batch 2026-09-24-coolprop-low-temperature, plan
 * {@code P3_PILOT_ENGINE_PLAN.md} section 3; rule (b) of the P1 study): the reference saturated-liquid state at a
 * reduced temperature (0.8) of the record's own PR78 critical temperature, from the Java Helmholtz oracle, and the
 * constant Peneloux shift it implies on the untranslated PR78 kernel,
 * {@code c = v_ref - v_PR,L(T, Psat_ref)}.
 *
 * <p>Only the numbers are carried (and so hashed into the package's physics fingerprint through the property record);
 * the record's {@code reference}, {@code oracle} and {@code source} texts are required but not kept. A package's
 * translation of a component is this record when present; the global {@code liquid_calibration.json} point of the
 * same component is then not that package's anchor ({@link MaterialCatalog#fluidThermoFingerprint}), so each package
 * has exactly one anchor per species.</p>
 *
 * @param reducedTemperature T / Tc of the anchor, in (0, 1)
 * @param temperatureKelvin the anchor temperature, {@code reducedTemperature * Tc} of the record's PR78 constants
 * @param pressurePascal the reference saturation pressure at that temperature
 * @param molarVolumeCubicMetresPerMol the reference saturated-liquid molar volume
 */
public record VolumeTranslation(double reducedTemperature, double temperatureKelvin, double pressurePascal,
        double molarVolumeCubicMetresPerMol) {
    /** The one anchor type P3 defines. */
    public static final String TYPE = "saturated_liquid_reduced_temperature";

    /**
     * The shift {@code c = v_ref - v_PR,L(T, P)}, m3/mol, with {@code v_PR,L} the liquid root of the untranslated PR78
     * kernel (Soave alpha, the kernel's constants) for the record's critical constants. Deterministic; one cubic solve.
     */
    public double shift(MaterialCatalog.Pr78 pr) {
        var kernel = new PengRobinsonKernel(new double[] {pr.criticalTemperature()}, new double[] {pr.criticalPressure()},
                new double[] {pr.acentricFactor()}, new double[][] {{0.0}}, 0.5 * temperatureKelvin, 2.0 * temperatureKelvin,
                0.5 * pressurePascal, 2.0 * pressurePascal);
        var output = kernel.newEvaluation();
        kernel.evaluate(temperatureKelvin, pressurePascal, new double[] {1.0}, PengRobinsonKernel.Root.LIQUID, kernel.newWorkspace(), output);
        if (output.physicalRootCount() < 2)
            throw new IllegalArgumentException("volume_translation: the PR78 kernel has no separate liquid root at " + temperatureKelvin + " K and "
                    + pressurePascal + " Pa");
        double liquid = output.compressibility() * PengRobinsonKernel.GAS_CONSTANT * temperatureKelvin / pressurePascal;
        return molarVolumeCubicMetresPerMol - liquid;
    }

    static VolumeTranslation read(JsonObject property, MaterialCatalog.Pr78 pr) {
        try {
            JsonObject o = MaterialCatalog.object(property, "volume_translation");
            String type = MaterialCatalog.string(o, "type");
            if (!type.equals(TYPE)) throw new IllegalArgumentException("type: expected " + TYPE + ", got " + type);
            if (pr == null) throw new IllegalArgumentException("models.pr78: required, the anchor is 0.8 x its critical temperature");
            double reduced = MaterialCatalog.positive(o, "reduced_temperature");
            if (!(reduced < 1)) throw new IllegalArgumentException("reduced_temperature: expected a value in (0, 1), got " + reduced);
            double temperature = MaterialCatalog.positive(o, "temperature_kelvin");
            double expected = reduced * pr.criticalTemperature();
            if (Math.abs(temperature / expected - 1) > 1e-9)
                throw new IllegalArgumentException("temperature_kelvin: " + temperature + " K is not reduced_temperature x Tc = " + expected
                        + " K of the record's PR78 constants (1e-9 relative)");
            var translation = new VolumeTranslation(reduced, temperature, MaterialCatalog.positive(o, "pressure_pascal"),
                    MaterialCatalog.positive(o, "molar_volume_m3_per_mol"));
            MaterialCatalog.string(o, "reference");
            MaterialCatalog.string(o, "oracle");
            MaterialCatalog.string(o, "source");
            double shift = translation.shift(pr);
            if (!Double.isFinite(shift)) throw new IllegalArgumentException("the shift is not finite");
            return translation;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(invalid.getMessage().startsWith("volume_translation") ? invalid.getMessage()
                    : "volume_translation." + invalid.getMessage(), invalid);
        }
    }
}
