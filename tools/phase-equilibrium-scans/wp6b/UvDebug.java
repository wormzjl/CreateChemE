package com.wormzjl.createcheme.science.thermo.phase;

import java.util.List;

/** WP6b probe: UV (and PH) outer iteration counts on the 60 binary states; with -Dwp6b.debug=true traces one state. */
public final class UvDebug {
    public static void main(String[] args) {
        var evaluator = PhaseTestSupport.methaneNitrogen();
        var service = new FluidTpEquilibrium(PhaseTestSupport.openContract(evaluator), evaluator);
        var ws = service.newWorkspace();
        List<String> binary = List.of("Methane", "Nitrogen");
        double[] only = args.length >= 3 ? new double[] {Double.parseDouble(args[0]), Double.parseDouble(args[1]), Double.parseDouble(args[2])} : null;
        for (double t : new double[] {110.0, 120.0}) {
            for (double p : new double[] {0.5e6, 1.0e6, 1.5e6, 2.0e6, 2.5e6, 3.0e6}) {
                for (double x : new double[] {0.1, 0.3, 0.5, 0.7, 0.9}) {
                    if (only != null && (t != only[0] || p != only[1] || x != only[2])) continue;
                    double[] z = {1 - x, x};
                    var tp = service.tp(EquilibriumRequest.tp(t, p, binary, z, PhaseCompetition.FLUID_ONLY), ws);
                    var ph = service.ph(EquilibriumRequest.ph(p, tp.enthalpy(), binary, z, PhaseCompetition.FLUID_ONLY), ws);
                    var uv = service.uv(EquilibriumRequest.uv(tp.internalEnergy(), tp.volume(), binary, z, PhaseCompetition.FLUID_ONLY), ws);
                    System.out.printf("%5.1f %8.0f %.1f %-14s PH %3d  UV %3d %s dT %.1e dP %.1e%n", t, p, x, tp.classification(),
                            ph.diagnostics().outerIterations(), uv.diagnostics().outerIterations(), uv.status(),
                            uv.converged() ? uv.phases().get(0).temperature() / t - 1 : Double.NaN,
                            uv.converged() ? uv.phases().get(0).pressure() / p - 1 : Double.NaN);
                    if (!uv.converged()) System.out.println("   UV: " + uv.detail());
                    if (!ph.converged()) System.out.println("   PH: " + ph.detail());
                }
            }
        }
    }
}
