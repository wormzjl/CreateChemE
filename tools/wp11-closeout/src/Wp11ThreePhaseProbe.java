package com.wormzjl.createcheme.science.thermo.phase;

import com.wormzjl.createcheme.science.material.MaterialCatalog;

/** WP11 probe: where the pilot network contract meets an unstable product (three phases) for N2/C2H6 near 125 K, 3 MPa. */
public final class Wp11ThreePhaseProbe {
    public static void main(String[] args) {
        var catalog = MaterialCatalog.bundled();
        String pilot = "createcheme:pilot_cryogenic";
        var engine = new FluidTpEquilibrium(PhaseContract.forNetworkPackage(catalog, pilot), CubicPhaseEvaluator.forPackage(catalog, pilot));
        var basis = engine.contract().components();
        for (double t : new double[] {115, 120, 125, 130}) for (double p : new double[] {2e6, 2.5e6, 3e6, 3.5e6}) {
            for (double x : new double[] {0.85, 0.88, 0.9, 0.92, 0.93, 0.94, 0.9499999999999998, 0.95}) {
                var r = engine.tp(EquilibriumRequest.tp(t, p, basis, new double[] {x, 0, 1 - x, 0, 0}, PhaseCompetition.FLUID_ONLY));
                System.out.printf("%.0f K %.1f MPa x_N2 %.16g: %s %s band %s | %s%n", t, p / 1e6, x, r.status(), r.classification(),
                        r.diagnostics().criticalBand(), r.detail().length() > 110 ? r.detail().substring(0, 110) : r.detail());
            }
        }
    }
}
