package com.wormzjl.createcheme.science.thermo.phase;

import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.thermo.TangentPlaneStability;

/** WP7d side probe: the survey's out-of-band Newton failures (CH4/CO2, N2/C2H6) with 50 (default) and 500 Newton steps. */
public final class Wp7dNewtonBudget {
    public static void main(String[] args) {
        var catalog = MaterialCatalog.bundled();
        String pilotId = "createcheme:pilot_cryogenic";
        var evaluator = CubicPhaseEvaluator.forPackage(catalog, pilotId);
        var contract = PhaseContract.forNetworkPackage(catalog, pilotId);
        var base = new FluidTpEquilibrium(contract, evaluator);
        var d = FluidTpEquilibrium.Settings.DEFAULT;
        var longer = new FluidTpEquilibrium(contract, evaluator, new FluidTpEquilibrium.Settings(d.lnKTolerance(), d.maximumIterations(),
                d.accelerationCycle(), d.coexistenceTolerance(), d.trivialDistance(), d.stability(), d.slowSubstitutionSteps(), d.slowEigenvalue(),
                d.substitutionLimit(), 500, d.fugacityTolerance(), d.bandTieLine(), d.bandStationaryDistance(), d.mergeTieLine(), d.mergeVolumeGap()));
        double[][] states = {{250, 8e6, 1, 3, 0.55}, {220, 6e6, 1, 3, 0.55}, {265, 8e6, 1, 3, 0.35}, {100, 4e6, 0, 2, 0.85}, {105, 2e6, 0, 2, 0.85}};
        for (double[] s : states) {
            double[] z = new double[5];
            z[(int) s[2]] = s[4];
            z[(int) s[3]] = 1 - s[4];
            for (var engine : new FluidTpEquilibrium[] {base, longer}) {
                var r = engine.tp(EquilibriumRequest.tp(s[0], s[1], contract.components(), z, PhaseCompetition.FLUID_ONLY), engine.newWorkspace());
                System.out.printf("%.0f K %.1f MPa z %s newton limit %d: %s %s newton %d tie %.4f band %s | %s%n", s[0], s[1] / 1e6,
                        java.util.Arrays.toString(z), engine.settings().maximumNewtonIterations(), r.status(), r.classification(),
                        r.diagnostics().newtonIterations(), r.diagnostics().tieLine(), r.diagnostics().criticalBand(),
                        r.detail().length() > 160 ? r.detail().substring(0, 160) : r.detail());
                if (r.converged()) for (var ph : r.phases()) System.out.printf("    %s amount %.4f x %s v %.4e%n", ph.kind(), ph.total(),
                        java.util.Arrays.toString(java.util.Arrays.stream(ph.amountsView()).map(v -> Math.round(v / ph.total() * 1e4) / 1e4).toArray()), ph.state().molarVolume());
            }
        }
    }
}
