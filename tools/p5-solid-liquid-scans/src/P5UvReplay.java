import com.wormzjl.createcheme.science.thermo.phase.EquilibriumRequest;
import com.wormzjl.createcheme.science.thermo.phase.PhaseCompetition;
import java.util.Locale;

/** Exploratory: replay one UV request of the engine (args: U V n2 ch4 c2h6 co2). */
public final class P5UvReplay {
    public static void main(String[] a) {
        double u = Double.parseDouble(a[0]), v = Double.parseDouble(a[1]);
        double[] z = {Double.parseDouble(a[2]), Double.parseDouble(a[3]), Double.parseDouble(a[4]), Double.parseDouble(a[5]), 0};
        var s = P5Probe.S;
        var r = s.uv(EquilibriumRequest.uv(u, v, P5Probe.B, z, PhaseCompetition.FLUID_AND_CRYSTALS), s.newWorkspace());
        System.out.printf(Locale.ROOT, "%s (%d TP) %s%n", P5Probe.show(r), r.diagnostics().outerIterations(), r.detail());
    }
}
