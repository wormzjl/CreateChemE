package com.wormzjl.createcheme.science.thermo.phase;

import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.thermo.TangentPlaneStability;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * WP7d scan: the pilot engine (network contract of createcheme:pilot_cryogenic) over N2/CH4 fields: (a) WP9b's finer scan
 * at 150 K (x_N2 0.55-0.80 by 0.01, 4.0-5.2 MPa by 10 kPa, 3,146 states), (b) the P2 near-critical layout on the pilot
 * package (130-185 K by 5 K, 2.5-6 MPa by 20 kPa, x_N2 0.01-0.99 by 0.02, 105,600 states). Counts outcomes, NOT_CONVERGED
 * inside/outside the band, feeds whose default stability test is unresolved, liquid-liquid answers.
 */
public final class Wp7dScan {
    public static void main(String[] args) {
        var catalog = MaterialCatalog.bundled();
        var evaluator = CubicPhaseEvaluator.forPackage(catalog, "createcheme:pilot_cryogenic");
        var engine = new FluidTpEquilibrium(PhaseContract.forNetworkPackage(catalog, "createcheme:pilot_cryogenic"), evaluator);
        scan("WP9b finer scan 150 K", engine, evaluator, 150, 150, 5, 4.0e6, 5.2e6, 1e4, 0.55, 0.80, 0.01);
        scan("P2 near-critical layout on pilot", engine, evaluator, 130, 185, 5, 2.5e6, 6.0e6, 2e4, 0.01, 0.99, 0.02);
    }

    static void scan(String name, FluidTpEquilibrium engine, CubicPhaseEvaluator evaluator, double t0, double t1, double dt, double p0,
                     double p1, double dp, double x0, double x1, double dx) {
        var ws = engine.newWorkspace();
        var plain = new TangentPlaneStability(evaluator.kernel());
        var sws = plain.newWorkspace();
        var species = engine.contract().components();
        Map<String, Integer> outcomes = new TreeMap<>();
        int states = 0, ncIn = 0, ncOut = 0, unresolved = 0, unresolvedBand = 0, liquidLiquid = 0;
        long kernel = 0, kernelUnresolved = 0;
        int kernelMax = 0;
        long start = System.nanoTime();
        for (int it = 0; t0 + it * dt <= t1 + 1e-9; it++) {
            double t = t0 + it * dt;
            for (int ip = 0; p0 + ip * dp <= p1 + 1e-3; ip++) {
                double p = p0 + ip * dp;
                for (int ix = 0; x0 + ix * dx <= x1 + 1e-9; ix++) {
                    double x = x0 + ix * dx;
                    double[] z = {x, 1 - x, 0, 0, 0};
                    var r = engine.tp(EquilibriumRequest.tp(t, p, species, z, PhaseCompetition.FLUID_ONLY), ws);
                    states++;
                    var d = r.diagnostics();
                    outcomes.merge(r.status() + (r.converged() ? "/" + r.classification() : ""), 1, Integer::sum);
                    kernel += d.kernelEvaluations();
                    kernelMax = Math.max(kernelMax, d.kernelEvaluations());
                    if (r.classification().name().equals("LIQUID_LIQUID")) { liquidLiquid++; if (liquidLiquid <= 60) System.out.printf(Locale.ROOT, "  LLE %.0f K %.2f MPa x_N2 %.2f tie %.4e band %s vol %.4e / %.4e amounts %.4f / %.4f%n", t, p / 1e6, x, d.tieLine(), d.criticalBand(), r.phases().get(0).state().molarVolume(), r.phases().get(1).state().molarVolume(), r.phases().get(0).total(), r.phases().get(1).total()); }
                    var s = plain.test(t, p, new double[] {x, 1 - x, 0, 0}, sws);
                    if (s.verdict() == TangentPlaneStability.Verdict.UNRESOLVED) {
                        unresolved++;
                        kernelUnresolved += d.kernelEvaluations();
                        if (d.criticalBand()) unresolvedBand++;
                        System.out.printf(Locale.ROOT, "  default test UNRESOLVED at %.0f K %.2f MPa x_N2 %.2f: engine %s %s %s kernel %d%n", t, p / 1e6, x,
                                r.status(), r.classification(), d.criticalBand() ? "band" : "outside band", d.kernelEvaluations());
                    }
                    if (!r.converged()) {
                        if (d.criticalBand()) ncIn++; else ncOut++;
                        System.out.printf(Locale.ROOT, "  NOT_CONVERGED %.0f K %.2f MPa x_N2 %.2f band %s: %s%n", t, p / 1e6, x, d.criticalBand(), r.detail());
                    }
                }
            }
        }
        System.out.printf(Locale.ROOT, "%s: %d states in %.1f s; %s; NOT_CONVERGED in band %d, outside %d; default stability test unresolved on %d"
                        + " (in band %d; engine kernel calls there mean %.0f); liquid-liquid %d; kernel calls mean %.1f max %d%n", name, states,
                (System.nanoTime() - start) / 1e9, outcomes, ncIn, ncOut, unresolved, unresolvedBand,
                unresolved == 0 ? 0.0 : (double) kernelUnresolved / unresolved, liquidLiquid, (double) kernel / states, kernelMax);
    }
}
