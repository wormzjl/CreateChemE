package com.wormzjl.createcheme.science.thermo.phase;

import java.util.List;

/** WP6b probe: PH on the binary at a given T, P, x_N2 (args), printing the outer iteration count. */
public final class PhDebug {
    public static void main(String[] args) {
        var evaluator = PhaseTestSupport.methaneNitrogen();
        var service = new FluidTpEquilibrium(PhaseTestSupport.openContract(evaluator), evaluator);
        var ws = service.newWorkspace();
        double t = Double.parseDouble(args[0]), p = Double.parseDouble(args[1]), x = Double.parseDouble(args[2]);
        double[] z = {1 - x, x};
        List<String> binary = List.of("Methane", "Nitrogen");
        var tp = service.tp(EquilibriumRequest.tp(t, p, binary, z, PhaseCompetition.FLUID_ONLY), ws);
        var ph = service.ph(EquilibriumRequest.ph(p, tp.enthalpy(), binary, z, PhaseCompetition.FLUID_ONLY), ws);
        System.out.println(tp.classification() + " PH " + ph.status() + " " + ph.diagnostics().outerIterations() + " dT " + (ph.phases().get(0).temperature() / t - 1));
    }
}
