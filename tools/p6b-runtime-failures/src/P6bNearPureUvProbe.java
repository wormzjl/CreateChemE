package com.wormzjl.createcheme.science.fluid.thermo;

import com.wormzjl.createcheme.science.material.PilotCryogenicTestCatalog;
import com.wormzjl.createcheme.science.thermo.phase.EquilibriumResult;
import com.wormzjl.createcheme.science.thermo.phase.PhaseAmounts;
import java.util.Locale;

/**
 * P6b probe (tools/p6b-runtime-failures/, never committed): the engine's crystal UV of row A's vessel (near-pure CO2 with
 * crystals at its sublimation line, the failing specification of the P6 world) cold and warm, then the same energy and
 * volume with the nitrogen trace scanned from zero (pure) to 1 %, and the same for a methane trace (row E's vessel).
 * Prints status, T, P, crystal amount, kernel evaluations and time per call.
 */
public final class P6bNearPureUvProbe {
    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        var model = new FluidThermodynamics(PilotCryogenicTestCatalog.catalog(), PilotCryogenicTestCatalog.PACKAGE_ID);
        var engine = model.phaseEngine();
        double u = -9.972099514183599E7, v = 1.0;
        double[] totals = {2.3343237938573707E-5, 0.0, 0.0, 249.14350068449468, 0.0};
        run("row A cold", engine, u, v, totals, Double.NaN, Double.NaN);
        run("row A warm", engine, u, v, totals, 212.2218, 381703.8);
        double co2 = totals[3];
        for (int trace = 0; trace < 2; trace++) {
            int k = trace == 0 ? 0 : 1;
            for (double f : new double[] {0, 1e-12, 1e-10, 1e-9, 3e-9, 1e-8, 3e-8, 1e-7, 3e-7, 1e-6, 1e-5, 1e-4, 1e-3, 1e-2}) {
                double[] z = {0, 0, 0, co2, 0};
                z[k] = f * co2 / (1 - f);
                run((k == 0 ? "N2 " : "CH4 ") + f, engine, u, v, z, Double.NaN, Double.NaN);
            }
        }
    }

    static void run(String label, NetworkPhaseEngine engine, double u, double v, double[] totals, double hintT, double hintP) {
        EquilibriumResult r = null;
        long best = Long.MAX_VALUE;
        for (int rep = 0; rep < 3; rep++) {
            long start = System.nanoTime();
            r = Double.isNaN(hintT) ? engine.uv(u, v, totals, true) : engine.uv(u, v, totals, true, hintT, hintP);
            best = Math.min(best, System.nanoTime() - start);
        }
        double crystal = 0, t = Double.NaN, p = Double.NaN;
        if (r.converged()) {
            t = r.phases().get(0).temperature();
            p = r.phases().get(0).pressure();
            for (PhaseAmounts a : r.phases()) if (a.kind() == com.wormzjl.createcheme.science.thermo.phase.PhaseKind.SOLID) crystal += a.amount(3);
        }
        String detail = r.detail();
        if (detail != null && detail.length() > 260) detail = detail.substring(0, 260) + "...";
        System.out.printf("%-14s %-14s %-18s T %.9f P %.3f crystal %.9f kernel %d outer %d  %.3f ms  %s%n", label, r.status(), r.classification(), t, p, crystal,
                r.diagnostics().kernelEvaluations(), r.diagnostics().outerIterations(), best / 1e6, detail);
    }
}
