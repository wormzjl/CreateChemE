import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.thermo.phase.EquilibriumRequest;
import com.wormzjl.createcheme.science.thermo.phase.EquilibriumResult;
import com.wormzjl.createcheme.science.thermo.phase.FluidTpEquilibrium;
import com.wormzjl.createcheme.science.thermo.phase.PhaseCompetition;
import java.util.List;
import java.util.Locale;

/** Exploratory P5 probe: binary three-phase steps in PH and UV, the pure triple point in UV (not a gate). */
public final class P5ThreePhaseProbe {
    static final FluidTpEquilibrium S = P5Probe.S;
    static final List<String> B = P5Probe.B;

    public static void main(String[] args) {
        var ws = S.newWorkspace();
        double[] z = P5Probe.z(0, 0.97, 0, 0.03);
        // A liquid with a crystal at 160 K, 3 MPa (above the three-phase pressure there), then energy out at fixed volume.
        var ls = S.tp(EquilibriumRequest.tp(160, 3e6, B, z, PhaseCompetition.FLUID_AND_CRYSTALS), ws);
        System.out.println("TP 160 K 3 MPa: " + P5Probe.show(ls));
        double u0 = ls.internalEnergy(), v0 = ls.volume(), h0 = ls.enthalpy();
        for (double du : new double[] {0, -50, -200, -500, -1000, -2000}) {
            long t0 = System.nanoTime();
            var uv = S.uv(EquilibriumRequest.uv(u0 + du, v0, B, z, PhaseCompetition.FLUID_AND_CRYSTALS), ws);
            long dt = System.nanoTime() - t0;
            System.out.printf(Locale.ROOT, "UV dU %+.0f -> %s (%d TP, %.0f us, spec %.2e)%n", du, P5Probe.show(uv), uv.diagnostics().outerIterations(), dt / 1e3,
                    uv.diagnostics().specificationResidual());
        }
        // PH at 2 MPa across the three-phase temperature of the binary (the liquidus side to the vapour side).
        for (double t : new double[] {150, 160, 165, 170, 175, 180, 185, 190}) {
            var r = S.tp(EquilibriumRequest.tp(t, 2e6, B, z, PhaseCompetition.FLUID_AND_CRYSTALS), ws);
            System.out.printf(Locale.ROOT, "TP %.0f K 2 MPa: %s H %.3f%n", t, P5Probe.show(r), r.converged() ? r.enthalpy() : Double.NaN);
        }
        var lo = S.tp(EquilibriumRequest.tp(165, 2e6, B, z, PhaseCompetition.FLUID_AND_CRYSTALS), ws);
        var hi = S.tp(EquilibriumRequest.tp(175, 2e6, B, z, PhaseCompetition.FLUID_AND_CRYSTALS), ws);
        for (int k = 0; k <= 10; k++) {
            double h = lo.enthalpy() + k / 10.0 * (hi.enthalpy() - lo.enthalpy());
            long t0 = System.nanoTime();
            var ph = S.ph(EquilibriumRequest.ph(2e6, h, B, z, PhaseCompetition.FLUID_AND_CRYSTALS), ws);
            long dt = System.nanoTime() - t0;
            System.out.printf(Locale.ROOT, "PH h %.3f -> %s (%d TP, %.0f us)%n", h, P5Probe.show(ph), ph.diagnostics().outerIterations(), dt / 1e3);
        }
        // Pure CO2 through its triple point in UV: a vessel of 1 mol at the triple point's volume range.
        double[] pure = P5Probe.z(0, 0, 0, 1);
        var sat = S.tp(EquilibriumRequest.tp(216.0, 4.0e5, B, pure, PhaseCompetition.FLUID_AND_CRYSTALS), ws);
        System.out.println("pure CO2 216 K 0.4 MPa: " + P5Probe.show(sat));
        for (double[] spec : new double[][] {{-1.5e3, 2.0e-3}, {-5e3, 1.0e-3}, {-1.0e4, 5.0e-4}, {-1.5e4, 3.0e-4}}) {
            // U relative to the triple point's vapour: search a few specifications through the triangle.
            var vap = S.tp(EquilibriumRequest.tp(216.6, 5.0e5, B, pure, PhaseCompetition.FLUID_AND_CRYSTALS), ws);
            double u = vap.internalEnergy() + spec[0];
            var uv = S.uv(EquilibriumRequest.uv(u, spec[1], B, pure, PhaseCompetition.FLUID_AND_CRYSTALS), ws);
            System.out.printf(Locale.ROOT, "pure UV u %.1f v %.2e -> %s%n", u, spec[1], P5Probe.show(uv));
        }
    }
}
