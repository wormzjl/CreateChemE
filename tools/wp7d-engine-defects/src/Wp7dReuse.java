package com.wormzjl.createcheme.science.thermo.phase;

import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.Locale;

/**
 * WP7d side probe: the survey grid of one pair with one reused workspace; every answer compared with a fresh workspace's
 * (status, classification, tie line bits). Prints the first differences and the previous state of each.
 */
public final class Wp7dReuse {
    public static void main(String[] args) {
        var catalog = MaterialCatalog.bundled();
        String pilotId = "createcheme:pilot_cryogenic";
        var contract = PhaseContract.forNetworkPackage(catalog, pilotId);
        var engine = new FluidTpEquilibrium(contract, CubicPhaseEvaluator.forPackage(catalog, pilotId));
        int a = Integer.parseInt(args[0]), b = Integer.parseInt(args[1]);
        double[] pressures = {0.1e6, 0.2e6, 0.5e6, 1e6, 2e6, 3e6, 4e6, 5e6, 6e6, 7e6, 8e6, 9e6, 10e6};
        var ws = engine.newWorkspace();
        int states = 0, differ = 0;
        String previous = "-";
        for (double t = 90; t <= 400; t += 5) for (double p : pressures) for (double x = 0.05; x < 0.96; x += 0.1) {
            double[] z = new double[5];
            z[a] = x; z[b] = 1 - x;
            var request = EquilibriumRequest.tp(t, p, contract.components(), z, PhaseCompetition.FLUID_ONLY);
            var reused = engine.tp(request, ws);
            var fresh = engine.tp(request, engine.newWorkspace());
            states++;
            String here = String.format(Locale.ROOT, "%.0f K %.1f MPa x %.17g", t, p / 1e6, x);
            boolean same = reused.status() == fresh.status() && reused.classification() == fresh.classification()
                    && Double.doubleToLongBits(reused.diagnostics().tieLine()) == Double.doubleToLongBits(fresh.diagnostics().tieLine())
                    && reused.diagnostics().kernelEvaluations() == fresh.diagnostics().kernelEvaluations();
            if (!same && ++differ <= 6) {
                System.out.println("DIFFER at " + here + " (previous " + previous + ")\n  reused " + reused.status() + " " + reused.classification()
                        + " kernel " + reused.diagnostics().kernelEvaluations() + " " + cut(reused.detail()) + "\n  fresh  " + fresh.status() + " "
                        + fresh.classification() + " kernel " + fresh.diagnostics().kernelEvaluations() + " " + cut(fresh.detail()));
            }
            previous = here + " " + reused.status();
        }
        System.out.println(states + " states, " + differ + " differ between a reused and a fresh workspace");
    }

    static String cut(String s) { return s.length() > 140 ? s.substring(0, 140) : s; }
}
