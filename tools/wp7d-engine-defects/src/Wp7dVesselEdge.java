package com.wormzjl.createcheme.science.thermo.phase;

import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.Locale;

/**
 * WP7d probe: the F8 liquid-rich CH4/C2H6 0.5/0.5 vessel (vapour fraction 0.03 at 150 K) heated at fixed volume by
 * 120 J steps on the pilot engine's UV until its pressure passes the 10 MPa ceiling; prints the last steps and the
 * answer beyond the ceiling (status, violation, TP calls).
 */
public final class Wp7dVesselEdge {
    public static void main(String[] args) {
        var catalog = MaterialCatalog.bundled();
        var engine = new FluidTpEquilibrium(PhaseContract.forNetworkPackage(catalog, "createcheme:pilot_cryogenic"),
                CubicPhaseEvaluator.forPackage(catalog, "createcheme:pilot_cryogenic"));
        var ws = engine.newWorkspace();
        var species = engine.contract().components();
        double[] z = {0, 0.5, 0.5, 0, 0};
        double lo = 1e4, hi = 8e6;
        EquilibriumResult start = null;
        for (int k = 0; k < 80; k++) {
            double p = Math.sqrt(lo * hi);
            var r = engine.tp(EquilibriumRequest.tp(150, p, species, z, PhaseCompetition.FLUID_ONLY), ws);
            double beta = 0;
            if (r.phases().size() == 2) {
                for (var ph : r.phases()) if (ph.kind() == PhaseKind.VAPOR) beta = ph.total() / r.totalAmount();
            } else beta = r.phases().get(0).kind() == PhaseKind.VAPOR ? 1 : 0;
            if (beta > 0.03) lo = p; else hi = p;
            start = r;
        }
        double volume = start.volume(), energy = start.internalEnergy();
        System.out.printf(Locale.ROOT, "start %.4f K %.4f MPa V %.6e m3 U %.4f J%n", start.phases().get(0).temperature(),
                start.phases().get(0).pressure() / 1e6, volume, energy);
        for (int k = 1; k <= 140; k++) {
            var r = engine.uv(EquilibriumRequest.uv(energy + k * 120.0, volume, species, z, PhaseCompetition.FLUID_ONLY), ws);
            if (!r.converged()) {
                System.out.println("step " + k + ": " + r.status() + " outer " + r.diagnostics().outerIterations() + " kernel "
                        + r.diagnostics().kernelEvaluations() + " band " + r.diagnostics().criticalBand() + "\n  detail " + r.detail()
                        + (r.violation() == null ? "" : "\n  violation " + r.violation().component() + " " + r.violation().property()
                        + " value " + r.violation().value() + " max " + r.violation().maximum()));
                // Beyond: a few more steps, each should be refused the same way.
                for (int j = 1; j <= 3; j++) {
                    var s = engine.uv(EquilibriumRequest.uv(energy + (k + 10 * j) * 120.0, volume, species, z, PhaseCompetition.FLUID_ONLY), ws);
                    System.out.println("  step " + (k + 10 * j) + ": " + s.status() + " outer " + s.diagnostics().outerIterations() + " "
                            + (s.violation() == null ? s.detail() : s.violation().getMessage()));
                }
                break;
            }
            double t = r.phases().get(0).temperature(), p = r.phases().get(0).pressure();
            if (k % 20 == 0 || p > 9.5e6) System.out.printf(Locale.ROOT, "step %d: %.4f K %.6f MPa phases %d outer %d%n", k, t, p / 1e6,
                    r.phases().size(), r.diagnostics().outerIterations());
        }
    }
}
