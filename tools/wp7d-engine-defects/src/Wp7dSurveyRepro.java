package com.wormzjl.createcheme.science.thermo.phase;

import com.wormzjl.createcheme.science.material.MaterialCatalog;

/** WP7d side probe: one survey failure reproduced with the survey's accumulated x, fresh and reused workspaces. */
public final class Wp7dSurveyRepro {
    public static void main(String[] args) {
        var catalog = MaterialCatalog.bundled();
        String pilotId = "createcheme:pilot_cryogenic";
        var contract = PhaseContract.forNetworkPackage(catalog, pilotId);
        var engine = new FluidTpEquilibrium(contract, CubicPhaseEvaluator.forPackage(catalog, pilotId));
        int a = Integer.parseInt(args[0]), b = Integer.parseInt(args[1]);
        double t = Double.parseDouble(args[2]), p = Double.parseDouble(args[3]);
        int k = Integer.parseInt(args[4]);
        double x = 0.05;
        for (int i = 0; i < k; i++) x += 0.1;
        double[] z = new double[5];
        z[a] = x; z[b] = 1 - x;
        System.out.println("x = " + x + ", 1 - x = " + (1 - x));
        var r = engine.tp(EquilibriumRequest.tp(t, p, contract.components(), z, PhaseCompetition.FLUID_ONLY), engine.newWorkspace());
        System.out.println("fresh: " + r.status() + " " + r.classification() + " " + r.detail() + " newton " + r.diagnostics().newtonIterations()
                + " ss " + r.diagnostics().flashIterations() + " verdict " + r.diagnostics().feedVerdict() + " tm " + r.diagnostics().feedTangentPlaneDistance());
        double[] exact = new double[5];
        exact[a] = Math.round(x * 100) / 100.0; exact[b] = 1 - exact[a];
        var e = engine.tp(EquilibriumRequest.tp(t, p, contract.components(), exact, PhaseCompetition.FLUID_ONLY), engine.newWorkspace());
        System.out.println("rounded x " + exact[a] + ": " + e.status() + " " + e.classification() + " newton " + e.diagnostics().newtonIterations()
                + " ss " + e.diagnostics().flashIterations() + " verdict " + e.diagnostics().feedVerdict());
    }
}
