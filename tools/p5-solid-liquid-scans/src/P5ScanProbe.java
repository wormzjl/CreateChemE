import com.wormzjl.createcheme.science.thermo.phase.EquilibriumRequest;
import com.wormzjl.createcheme.science.thermo.phase.PhaseCompetition;
import java.util.Locale;

/** Exploratory: TP classifications of a feed over a (T, P) list (args: n2 ch4 c2h6 co2 T P...). */
public final class P5ScanProbe {
    public static void main(String[] a) {
        double[] z = P5Probe.z(Double.parseDouble(a[0]), Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3]));
        var ws = P5Probe.S.newWorkspace();
        for (int k = 4; k + 1 < a.length; k += 2) {
            double t = Double.parseDouble(a[k]), p = Double.parseDouble(a[k + 1]);
            var r = P5Probe.S.tp(EquilibriumRequest.tp(t, p, P5Probe.B, z, PhaseCompetition.FLUID_AND_CRYSTALS), ws);
            var f = P5Probe.S.tp(EquilibriumRequest.tp(t, p, P5Probe.B, z, PhaseCompetition.FLUID_AND_CRYSTALS), ws);
            var held = P5Probe.S.tpCrystalsHeldOut(EquilibriumRequest.tp(t, p, P5Probe.B, z, PhaseCompetition.FLUID_AND_CRYSTALS), ws);
            System.out.printf(Locale.ROOT, "%.2f K %.4g Pa: %s | held out: %s%n", t, p, P5Probe.show(r), P5Probe.show(held));
        }
    }
}
