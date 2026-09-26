import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.thermo.phase.EquilibriumRequest;
import com.wormzjl.createcheme.science.thermo.phase.EquilibriumResult;
import com.wormzjl.createcheme.science.thermo.phase.FluidTpEquilibrium;
import com.wormzjl.createcheme.science.thermo.phase.PhaseAmounts;
import com.wormzjl.createcheme.science.thermo.phase.PhaseCompetition;
import java.util.List;
import java.util.Locale;

/** Exploratory P5 probe: TP, onsets, PH/UV over the crystal-beside-liquid states (not a gate). */
public final class P5Probe {
    public static final FluidTpEquilibrium S = FluidTpEquilibrium.forPackage(MaterialCatalog.bundled(), "createcheme:pilot_cryogenic");
    public static final List<String> B = S.contract().components();

    public static double[] z(double n2, double ch4, double c2h6, double co2) { return new double[] {n2, ch4, c2h6, co2, 0}; }

    public static String show(EquilibriumResult r) {
        StringBuilder b = new StringBuilder(r.status() + " " + r.classification());
        if (r.converged()) {
            for (PhaseAmounts p : r.phases()) {
                b.append(String.format(Locale.ROOT, " [%s %.6g mol x_CO2=%.6g]", p.kind(), p.total(), p.state().componentCount() > 1 ? p.state().moleFraction(3) : 1.0));
            }
            b.append(String.format(Locale.ROOT, " T=%.6f P=%.2f", r.phases().get(0).temperature(), r.phases().get(0).pressure()));
            if (r.deposition() != null) b.append(" dep: iter=" + r.deposition().iterations() + " flashes=" + r.deposition().fluidEquilibria()
                    + " res=" + r.deposition().residual());
        } else {
            b.append(" : ").append(r.detail());
        }
        return b.toString();
    }

    public static void main(String[] args) {
        var ws = S.newWorkspace();
        double[][] states = {
                {130, 1e6, 0, 0.95, 0, 0.05},
                {130, 1e6, 0, 0.999, 0, 0.001},
                {200, 2e6, 0, 0, 0.3, 0.7},
                {100, 1e6, 0, 0.99, 0, 0.01},
                {100, 2e5, 0, 0.99, 0, 0.01},
                {150, 1e6, 0, 0.98, 0, 0.02},
                {150, 1.2e6, 0, 0.9, 0, 0.1},
                {180, 3e6, 0.05, 0.85, 0, 0.1},
                {140, 7e6, 0.99, 0, 0, 0.01},
                {217, 8e6, 0, 0, 0, 1},
                {200, 8e6, 0, 0, 0, 1},
                {218, 5e6, 0, 0, 0, 1},
                {95, 2e6, 0.4, 0, 0.55, 0.05},
                {125, 3e6, 0.94, 0, 0.05, 0.01},
        };
        for (double[] s : states) {
            long t0 = System.nanoTime();
            var r = S.tp(EquilibriumRequest.tp(s[0], s[1], B, z(s[2], s[3], s[4], s[5]), PhaseCompetition.FLUID_AND_CRYSTALS), ws);
            long dt = System.nanoTime() - t0;
            System.out.printf(Locale.ROOT, "T %.1f P %.3g z %s -> %s (%.0f us)%n", s[0], s[1], java.util.Arrays.toString(new double[] {s[2], s[3], s[4], s[5]}),
                    show(r), dt / 1e3);
            if (r.converged() && r.classification().holdsCrystal() && r.phases().size() > 1) {
                double[] feed = z(s[2], s[3], s[4], s[5]);
                double h = r.enthalpy(), u = r.internalEnergy(), v = r.volume();
                var ph = S.ph(EquilibriumRequest.ph(s[1], h, B, feed, PhaseCompetition.FLUID_AND_CRYSTALS), ws);
                var uv = S.uv(EquilibriumRequest.uv(u, v, B, feed, PhaseCompetition.FLUID_AND_CRYSTALS), ws);
                System.out.println("   PH -> " + show(ph));
                System.out.println("   UV -> " + show(uv));
            }
        }
        // Onsets.
        double[][] onsets = {{1e6, 0, 0.99, 0, 0.01}, {2.315e6, 0, 1 - 0.02896, 0, 0.02896}, {0.093e6, 0, 1 - 0.000213, 0, 0.000213},
                {5e6, 0, 0, 0, 1}, {1e7, 0, 0, 0, 1}, {6e6, 0.99, 0, 0, 0.01}, {4e6, 0, 0.9, 0, 0.1}};
        for (double[] o : onsets) {
            var f = S.freezingTemperature(null, o[0], B, z(o[1], o[2], o[3], o[4]), ws);
            System.out.printf(Locale.ROOT, "freezing P %.4g z_CO2 %.6g -> %s T=%.6f %s%n", o[0], o[4], f.status(), f.temperature(),
                    f.converged() ? f.fluid().classification() + " " + f.detail() : f.detail());
        }
    }
}
