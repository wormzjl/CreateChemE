package com.wormzjl.createcheme.science.thermo.phase;

import java.util.List;

/** Lists the NOT_CONVERGED binary states of the P2 scans (baseline of WP6a), one line each. */
public final class BaselineFailures {
    public static void main(String[] args) {
        scan(95, 190, 5, 0.1e6, 5.0e6, 0.1e6, 0.02, 0.98, 0.04);
        scan(130, 185, 5, 2.5e6, 6.0e6, 0.02e6, 0.01, 0.99, 0.02);
    }

    static void scan(double t0, double t1, double dt, double p0, double p1, double dp, double x0, double x1, double dx) {
        var evaluator = PhaseTestSupport.methaneNitrogen();
        var service = new FluidTpEquilibrium(PhaseTestSupport.openContract(evaluator), evaluator);
        var w = service.newWorkspace();
        List<String> basis = List.of("Methane", "Nitrogen");
        for (int it = 0; t0 + it * dt <= t1 + 1e-9; it++) {
            double t = t0 + it * dt;
            for (int ip = 0; p0 + ip * dp <= p1 + 1e-3; ip++) {
                double p = p0 + ip * dp;
                for (int ix = 0; x0 + ix * dx <= x1 + 1e-9; ix++) {
                    double x = x0 + ix * dx;
                    double[] z = {1 - x, x};
                    var r = service.tp(EquilibriumRequest.tp(t, p, basis, z, PhaseCompetition.FLUID_ONLY), w);
                    if (!r.converged()) {
                        System.out.printf("FAIL %.0f %.0f %.2f %d %s%n", t, p / 1e3, x, r.diagnostics().flashIterations(), r.detail());
                    }
                }
            }
        }
    }
}
