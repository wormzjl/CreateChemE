package com.wormzjl.createcheme.science.thermo.phase;

import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.thermo.PengRobinsonKernel;
import java.util.Arrays;
import java.util.Locale;

/**
 * WP7d probe: every two-phase answer of the survey grid (pilot contract, six binaries and the equimolar quaternary, 90-400 K,
 * 0.1-10 MPa) and of the F7 states, with each phase's PIP, Z and untranslated v/b, and the tie line: where do splits
 * whose two products are both liquid-like by PIP > max(1, Z) lie, against the near-critical ones?
 */
public final class Wp7dLleMargins {
    public static void main(String[] args) {
        var catalog = MaterialCatalog.bundled();
        String pilotId = "createcheme:pilot_cryogenic";
        var evaluator = CubicPhaseEvaluator.forPackage(catalog, pilotId);
        var engine = new FluidTpEquilibrium(PhaseContract.forNetworkPackage(catalog, pilotId), evaluator);
        var ws = engine.newWorkspace();
        var species = engine.contract().components();
        String[] names = {"N2", "CH4", "C2H6", "CO2"};
        double[] pressures = {0.1e6, 0.2e6, 0.5e6, 1e6, 2e6, 3e6, 4e6, 5e6, 6e6, 7e6, 8e6, 9e6, 10e6};
        for (int a = 0; a < 4; a++) for (int b = a + 1; b < 5; b++) {
            if (b == 4 && a > 0) continue;
            for (double t = 90; t <= 400; t += 5) for (double p : pressures) for (double x = 0.05; x < 0.96; x += 0.1) {
                double[] z = new double[5];
                if (b == 4) z[0] = z[1] = z[2] = z[3] = 0.25; else { z[a] = x; z[b] = 1 - x; }
                var r = engine.tp(EquilibriumRequest.tp(t, p, species, z, PhaseCompetition.FLUID_ONLY), ws);
                if (r.converged() && r.phases().size() == 2) {
                    double[] light = measure(r.phases().get(0), evaluator), dense = measure(r.phases().get(1), evaluator);
                    boolean bothPip = light[0] > Math.max(1, light[1]) && dense[0] > Math.max(1, dense[1]);
                    if (bothPip) System.out.printf(Locale.ROOT, "%s %.0f K %.1f MPa x %.2f %s tie %.4f band %s | lighter PIP %.3f Z %.4f v/b %.3f | denser PIP %.3f Z %.4f v/b %.3f%n",
                            b == 4 ? "quaternary" : names[a] + "/" + names[b], t, p / 1e6, x, r.classification(), r.diagnostics().tieLine(),
                            r.diagnostics().criticalBand(), light[0], light[1], light[2], dense[0], dense[1], dense[2]);
                }
                if (b == 4) break;
            }
        }
    }

    /** {PIP, Z, v/b} of a phase on its own root (untranslated). */
    static double[] measure(PhaseAmounts ph, CubicPhaseEvaluator evaluator) {
        var kernel = evaluator.kernel();
        double[] x = Arrays.copyOf(ph.amountsView(), evaluator.componentCount());
        var e = kernel.newEvaluation();
        var root = ph.state().root() == com.wormzjl.createcheme.science.thermo.PhaseRoot.VAPOR ? PengRobinsonKernel.Root.VAPOR : PengRobinsonKernel.Root.LIQUID;
        kernel.evaluate(ph.temperature(), ph.pressure(), x, root, kernel.newWorkspace(), e);
        double v = e.compressibility() * PengRobinsonKernel.GAS_CONSTANT * ph.temperature() / ph.pressure();
        return new double[] {PhaseIdentification.parameter(e, ph.temperature(), ph.pressure()), e.compressibility(), v / e.bMix()};
    }
}
