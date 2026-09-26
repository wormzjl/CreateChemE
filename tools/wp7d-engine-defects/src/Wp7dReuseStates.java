package com.wormzjl.createcheme.science.thermo.phase;

import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.Arrays;

/** WP7d probe: the fresh answer of each state of the reused-workspace test, with its tie line. */
public final class Wp7dReuseStates {
    public static void main(String[] args) {
        var catalog = MaterialCatalog.bundled();
        String id = "createcheme:pilot_cryogenic";
        var engine = new FluidTpEquilibrium(PhaseContract.forNetworkPackage(catalog, id), CubicPhaseEvaluator.forPackage(catalog, id));
        double[][] states = {
                {150.0, 4.56e6, 0.67, 0.33, 0.0, 0.0}, {250.0, 8.0e6, 0.0, 0.55, 0.0, 0.45},
                {220.0, 6.0e6, 0.0, 0.85, 0.15, 0.0}, {100.0, 4.0e6, 0.85, 0.0, 0.15, 0.0},
                {220.0, 6.0e6, 0.0, 0.55, 0.0, 0.45}, {120.0, 2.0e6, 0.3, 0.7, 0.0, 0.0}, {165.0, 4.98e6, 0.47, 0.53, 0.0, 0.0},
                {150.0, 4.50e6, 0.67, 0.33, 0.0, 0.0}, {150.0, 4.40e6, 0.67, 0.33, 0.0, 0.0}, {150.0, 4.64e6, 0.67, 0.33, 0.0, 0.0}};
        for (double[] s : states) {
            var basis = engine.contract().components();
            var r = engine.tp(EquilibriumRequest.tp(s[0], s[1], basis, Arrays.copyOf(Arrays.copyOfRange(s, 2, 6), basis.size()),
                    PhaseCompetition.FLUID_ONLY), engine.newWorkspace());
            System.out.println(s[0] + " K " + s[1] + " Pa " + Arrays.toString(Arrays.copyOfRange(s, 2, 6)) + ": " + r.status() + " "
                    + r.classification() + " phases " + r.phases().size() + " tie " + r.diagnostics().tieLine() + " band "
                    + r.diagnostics().criticalBand() + " verdict " + r.diagnostics().feedVerdict() + " | " + r.detail());
        }
    }
}
