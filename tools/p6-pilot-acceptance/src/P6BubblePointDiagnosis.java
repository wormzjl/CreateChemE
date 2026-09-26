package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.List;
import java.util.Locale;

/** P6 diagnosis of the liquid-full mixture bubble-point limit (P5 section 6.3): one case, the failing interval traced. */
public final class P6BubblePointDiagnosis {
    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        var model = new FluidThermodynamics(MaterialCatalog.bundled(), "createcheme:pilot_cryogenic");
        double[] z = {Double.parseDouble(args[0]), Double.parseDouble(args[1]), Double.parseDouble(args[2]), Double.parseDouble(args[3]), 0};
        double t = Double.parseDouble(args[4]), p = Double.parseDouble(args[5]), watts = Double.parseDouble(args[6]);
        int intervals = Integer.parseInt(args[7]);
        var unit = model.flashTP(t, p, z, () -> {});
        double[] n = z.clone();
        for (int i = 0; i < n.length; i++) n[i] *= 1e-3 / unit.volume();
        var state = model.flashTP(t, p, n, () -> {});
        PassiveNetwork graph = new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1, 0, state)), List.of());
        for (int k = 0; k < intervals; k++) {
            double[] rate = new double[5];
            rate[1] = 1e-12;
            var withHeat = new PassiveNetwork(graph.reservoirs(), graph.pipes(), List.of(new ScheduledTransfer.Injection(100, 0, rate, watts)));
            // (the traced run used a temporary PassiveStepSolver.DEBUG switch, removed after the diagnosis)
            try {
                var r = new PassiveIntervalSolver(model).solve(withHeat, 5, PassiveIntervalSolver.Settings.defaults(), () -> {}, 1);
                graph = new PassiveNetwork(r.graph().reservoirs(), r.graph().pipes());
                var s = graph.reservoirs().getFirst().state();
                System.out.printf("interval %d: %.6f K %.1f Pa liquid %s vapour %s, substeps %d rejected %d%n", k + 1, s.temperature(), s.pressure(),
                        s.liquidProperties() != null, s.vaporProperties() != null, r.acceptedSubsteps(), r.rejectedSubsteps());
            } catch (RuntimeException e) {
                System.out.printf("interval %d refused: %s%n", k + 1, e.getMessage());
                break;
            }
        }
    }
}
