package com.wormzjl.createcheme.science.thermo.phase;

import com.wormzjl.createcheme.science.material.MaterialCatalog;

/**
 * WP7d side probe: a workspace that last split N2/CH4 by the Newton finish, then a CH4/CO2 (and an N2/C2H6 after CH4/C2H6)
 * feed that needs the finish: reused against fresh workspace.
 */
public final class Wp7dStaleAmounts {
    public static void main(String[] args) {
        var catalog = MaterialCatalog.bundled();
        String pilotId = "createcheme:pilot_cryogenic";
        var contract = PhaseContract.forNetworkPackage(catalog, pilotId);
        var engine = new FluidTpEquilibrium(contract, CubicPhaseEvaluator.forPackage(catalog, pilotId));
        var species = contract.components();
        double[][][] cases = {
                {{150, 4.56e6}, {0.67, 0.33, 0, 0, 0}, {250, 8e6}, {0, 0.55, 0, 0.45, 0}},
                {{150, 4.56e6}, {0.67, 0.33, 0, 0, 0}, {220, 6e6}, {0, 0.55, 0, 0.45, 0}},
                {{220, 6e6}, {0, 0.85, 0.15, 0, 0}, {100, 4e6}, {0.85, 0, 0.15, 0, 0}}};
        for (double[][] c : cases) {
            var ws = engine.newWorkspace();
            var first = engine.tp(EquilibriumRequest.tp(c[0][0], c[0][1], species, c[1], PhaseCompetition.FLUID_ONLY), ws);
            var reused = engine.tp(EquilibriumRequest.tp(c[2][0], c[2][1], species, c[3], PhaseCompetition.FLUID_ONLY), ws);
            var fresh = engine.tp(EquilibriumRequest.tp(c[2][0], c[2][1], species, c[3], PhaseCompetition.FLUID_ONLY), engine.newWorkspace());
            System.out.println("after " + java.util.Arrays.toString(c[1]) + " at " + c[0][0] + " K (" + first.classification() + ", newton "
                    + first.diagnostics().newtonIterations() + "): " + java.util.Arrays.toString(c[3]) + " at " + c[2][0] + " K " + c[2][1] / 1e6 + " MPa"
                    + "\n  reused: " + reused.status() + " " + reused.classification() + " newton " + reused.diagnostics().newtonIterations() + " "
                    + (reused.detail().length() > 150 ? reused.detail().substring(0, 150) : reused.detail())
                    + "\n  fresh:  " + fresh.status() + " " + fresh.classification() + " newton " + fresh.diagnostics().newtonIterations());
        }
    }
}
