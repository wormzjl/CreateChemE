import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.material.VolumeTranslation;
import com.wormzjl.createcheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.createcheme.science.thermo.reference.HelmholtzFluid;
import java.util.Locale;

/**
 * P3 WP3 one-off printer (batch 2026-09-24-coolprop-low-temperature, plan P3_PILOT_ENGINE_PLAN.md section 3): the
 * Tr = 0.8 saturated-liquid anchors of the four pilot property records from the Java Helmholtz oracle
 * (src/test/java/.../science/thermo/reference, CoolProp dev/fluids files at ae81610e), the shift they imply on the
 * untranslated PR78 kernel (VolumeTranslation.shift), and the old anchor for comparison (the liquid_calibration.json
 * point at 2 MPa, or for CO2 the P2 standard density at 250 K and 2 MPa, through the same kernel at 2 MPa). Prints JSON
 * on stdout; every number is Double.toString, so the output is byte-for-byte reproducible.
 */
public final class PrintPilotVolumeAnchors {
    private record Species(String record, String fluid, double tc, double pc, double omega, double oldT, double oldP, double oldV, String oldSource) {}

    private static final Species[] SPECIES = {
        new Species("pilot_nitrogen", "Nitrogen", 126.192, 3395800, 0.0372, 90, 2000000, 3.7280361401499997e-05, "liquid_calibration.json (NIST 90 K, 2 MPa)"),
        new Species("pilot_methane", "Methane", 190.564, 4599200.0, 0.01142, 150.0, 2000000.0, 4.45368580951e-05, "liquid_calibration.json (NIST 150 K, 2 MPa)"),
        new Species("pilot_ethane", "Ethane", 305.32, 4872000.0, 0.099, 240.0, 2000000.0, 6.42773629991e-05, "liquid_calibration.json (NIST 240 K, 2 MPa)"),
        new Species("pilot_carbon_dioxide", "CarbonDioxide", 304.1282, 7377300, 0.22394, 250, 2000000, 0.0440098 / 1046.8822148292945,
                "P2 pilot_carbon_dioxide standard density 1046.8822148292945 kg/m3 at 250 K, 2 MPa (oracle)"),
    };

    public static void main(String[] args) {
        StringBuilder out = new StringBuilder("[\n");
        for (int s = 0; s < SPECIES.length; s++) {
            Species sp = SPECIES[s];
            var fluid = HelmholtzFluid.load(sp.fluid);
            double t = 0.8 * sp.tc;
            var saturation = fluid.saturation(t);
            double v = 1.0 / saturation.liquid().density();
            var pr = new MaterialCatalog.Pr78(sp.tc, sp.pc, sp.omega);
            var anchor = new VolumeTranslation(0.8, t, saturation.pressure(), v);
            double shift = anchor.shift(pr);
            double oldShift = sp.oldV - liquidVolume(pr, sp.oldT, sp.oldP);
            var fields = new java.util.ArrayList<String>();
            field(fields, "record", "\"createcheme:" + sp.record + "\"");
            field(fields, "fluid_file", "\"" + sp.fluid + ".json\"");
            field(fields, "oracle_critical_temperature_kelvin", Double.toString(fluid.criticalTemperature()));
            field(fields, "record_critical_temperature_kelvin", Double.toString(sp.tc));
            field(fields, "reduced_temperature", "0.8");
            field(fields, "temperature_kelvin", Double.toString(t));
            field(fields, "pressure_pascal", Double.toString(saturation.pressure()));
            field(fields, "liquid_density_mol_per_m3", Double.toString(saturation.liquid().density()));
            field(fields, "molar_volume_m3_per_mol", Double.toString(v));
            field(fields, "shift_m3_per_mol", Double.toString(shift));
            field(fields, "shift_cm3_per_mol", String.format(Locale.ROOT, "%.6f", shift * 1e6));
            field(fields, "old_anchor", "\"" + sp.oldSource + "\"");
            field(fields, "old_shift_cm3_per_mol", String.format(Locale.ROOT, "%.6f", oldShift * 1e6));
            out.append("  {\n").append(String.join(",\n", fields)).append("\n  }").append(s + 1 < SPECIES.length ? ",\n" : "\n");
        }
        System.out.print(out.append("]\n"));
    }

    private static void field(java.util.List<String> fields, String key, String value) {
        fields.add("    \"" + key + "\": " + value);
    }

    /** Liquid root of the untranslated kernel at (T, P), m3/mol. */
    private static double liquidVolume(MaterialCatalog.Pr78 pr, double t, double p) {
        var kernel = new PengRobinsonKernel(new double[] {pr.criticalTemperature()}, new double[] {pr.criticalPressure()},
                new double[] {pr.acentricFactor()}, new double[][] {{0.0}}, 0.5 * t, 2 * t, 0.5 * p, 2 * p);
        var output = kernel.newEvaluation();
        kernel.evaluate(t, p, new double[] {1.0}, PengRobinsonKernel.Root.LIQUID, kernel.newWorkspace(), output);
        return output.compressibility() * PengRobinsonKernel.GAS_CONSTANT * t / p;
    }
}
