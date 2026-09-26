import com.wormzjl.createcheme.science.thermo.phase.EquilibriumRequest;
import com.wormzjl.createcheme.science.thermo.phase.EquilibriumResult;
import com.wormzjl.createcheme.science.thermo.phase.PhaseCompetition;
import java.util.Locale;

/** Exploratory: the binary's three-phase UV at a temperature (args: co2 fraction, T). */
public final class P5BinaryUvProbe {
    public static void main(String[] a) {
        double x = Double.parseDouble(a[0]), t = Double.parseDouble(a[1]);
        double[] z = P5Probe.z(0, 1 - x, 0, x);
        var s = P5Probe.S;
        var ws = s.newWorkspace();
        // The three-phase pressure by bisection on the classification.
        double lo = 1e5, hi = 5e6;
        EquilibriumResult below = null, above = null;
        for (int k = 0; k < 60; k++) {
            double p = Math.sqrt(lo * hi);
            var r = s.tp(EquilibriumRequest.tp(t, p, P5Probe.B, z, PhaseCompetition.FLUID_AND_CRYSTALS), ws);
            boolean liquid = false;
            for (var ph : r.phases()) liquid |= ph.kind() == com.wormzjl.createcheme.science.thermo.phase.PhaseKind.LIQUID;
            if (liquid) { hi = p; above = r; } else { lo = p; below = r; }
        }
        System.out.printf(Locale.ROOT, "P3(%.3f K) between %.6f and %.6f Pa%n below: %s%n above: %s%n", t, lo, hi, P5Probe.show(below), P5Probe.show(above));
        for (double w : new double[] {0.1, 0.5, 0.9}) {
            double u = (1 - w) * below.internalEnergy() + w * above.internalEnergy();
            double v = (1 - w) * below.volume() + w * above.volume();
            var uv = s.uv(EquilibriumRequest.uv(u, v, P5Probe.B, z, PhaseCompetition.FLUID_AND_CRYSTALS), ws);
            System.out.printf(Locale.ROOT, "UV mix %.1f: %s (%d TP) %s%n", w, P5Probe.show(uv), uv.diagnostics().outerIterations(), uv.converged() ? "" : uv.detail());
        }
    }
}
