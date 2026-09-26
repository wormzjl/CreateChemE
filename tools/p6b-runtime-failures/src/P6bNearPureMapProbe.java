package com.wormzjl.createcheme.science.fluid.thermo;

import com.wormzjl.createcheme.science.material.PilotCryogenicTestCatalog;
import com.wormzjl.createcheme.science.thermo.phase.EquilibriumResult;
import com.wormzjl.createcheme.science.thermo.phase.PhaseAmounts;
import com.wormzjl.createcheme.science.thermo.phase.PhaseKind;
import java.util.Locale;

/**
 * P6b probe (tools/p6b-runtime-failures/, never committed), failure 1 diagnosis.
 * (1) TP answers of row A's material (CO2 with a nitrogen trace f) along the isotherm of its pure sublimation point,
 *     pressures from P_sub (1 - 2 f) to P_sub (1 + 30 f): the crystal amount and the volume against P, showing where the
 *     vapour-solid region of width about f is resolved and where it is a numerical step.
 * (2) The failure map of the crystal UV over the trace fraction f and the crystal share: row A's volume and material,
 *     energies from almost no crystal to mostly crystal; the pure answer (f = 0) as the reference crystal.
 */
public final class P6bNearPureMapProbe {
    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        var model = new FluidThermodynamics(PilotCryogenicTestCatalog.catalog(), PilotCryogenicTestCatalog.PACKAGE_ID);
        var engine = model.phaseEngine();
        double co2 = 249.14350068449468;
        double tSub = 212.2216420087663, pSub = 381699.5700105201;
        System.out.println("(1) TP along T = " + tSub + " K; x = (P/P_sub - 1)/f");
        for (double f : new double[] {1e-9, 1e-8, 1e-7, 1e-6}) {
            double[] z = {f * co2 / (1 - f), 0, 0, co2, 0};
            for (double x : new double[] {-2, -0.5, 0.5, 0.9, 1.0, 1.05, 1.1, 1.2, 1.5, 2, 3, 5, 10, 30}) {
                double p = pSub * (1 + x * f);
                EquilibriumResult r = engine.tp(tSub, p, z, true);
                double crystal = 0;
                if (r.converged()) for (PhaseAmounts a : r.phases()) if (a.kind() == PhaseKind.SOLID) crystal += a.amount(3);
                String d = r.detail();
                int k = d.indexOf("(mu - mu_s)/RT = ");
                String drive = k >= 0 ? d.substring(k + 17, Math.min(d.length(), k + 40)) : "";
                System.out.printf("  f %.0e x %6.2f  %-14s %-16s crystal %.9f  V %.9f m3  drive %s%n", f, x, r.status(), r.classification(), crystal,
                        r.converged() ? r.volume() : Double.NaN, drive.split("[,)]")[0]);
            }
        }
        System.out.println("(2) UV failure map at V = 1 m3, 249.14 mol CO2 + trace f (N2); U swept");
        double u0 = -9.972099514183599E7;
        for (double du : new double[] {+4.5e5, +4.0e5, +3e5, +1.5e5, 0, -1e6, -3e6}) {
            double u = u0 + du;
            EquilibriumResult pure = engine.uv(u, 1.0, new double[] {0, 0, 0, co2, 0}, true);
            double ref = crystal(pure);
            StringBuilder line = new StringBuilder(String.format("  dU %+.1e J: pure crystal %9.4f (%s)", du, ref, pure.status()));
            for (double f : new double[] {1e-10, 1e-8, 1e-7, 3e-7, 1e-6, 3e-6, 1e-5, 1e-4}) {
                double[] z = {f * co2 / (1 - f), 0, 0, co2, 0};
                long start = System.nanoTime();
                EquilibriumResult r = engine.uv(u, 1.0, z, true);
                double ms = (System.nanoTime() - start) / 1e6;
                line.append(String.format(" | %.0e %s %s k%d", f, r.converged() ? String.format("%.4f", crystal(r)) : "FAIL", String.format("%.1fms", ms), r.diagnostics().kernelEvaluations()));
            }
            System.out.println(line);
        }
    }

    static double crystal(EquilibriumResult r) {
        double c = 0;
        if (r.converged()) for (PhaseAmounts a : r.phases()) if (a.kind() == PhaseKind.SOLID) c += a.amount(3);
        return r.converged() ? c : Double.NaN;
    }
}
