package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Exploratory P5 probe: a closed pilot vessel cooled step by step (args: n2 ch4 c2h6 co2 T P watts intervals). */
public final class P5VesselProbe {
    public static void main(String[] a) {
        var model = new FluidThermodynamics(MaterialCatalog.bundled(), "createcheme:pilot_cryogenic");
        double[] z = {Double.parseDouble(a[0]), Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3]), 0};
        double t = Double.parseDouble(a[4]), p = Double.parseDouble(a[5]), watts = Double.parseDouble(a[6]);
        int intervals = Integer.parseInt(a[7]);
        double headspace = a.length > 8 ? Double.parseDouble(a[8]) : 1.0;
        var unit = model.flashTP(t, p, z, () -> { });
        var reservoir = new PassiveNetwork.Reservoir(1, 0, unit);
        if (headspace != 1.0) {
            var inv = reservoir.inventory();
            reservoir = new PassiveNetwork.Reservoir(1, 0, unit, PassiveNetwork.NodeKind.RESERVOIR,
                    new PassiveNetwork.Inventory(inv.volume() * headspace, inv.moles(), inv.internalEnergy(), inv.solids(), inv.crystals()));
        }
        var graph = new PassiveNetwork(List.of(reservoir), List.of());
        for (int k = 0; k < intervals; k++) {
            double[] rate = new double[5];
            rate[0] = 1e-12;
            var withHeat = new PassiveNetwork(graph.reservoirs(), graph.pipes(), List.of(new ScheduledTransfer.Injection(100, 0, rate, watts)));
            try {
                var result = new PassiveIntervalSolver(model).solve(withHeat, 5, PassiveIntervalSolver.Settings.defaults(), () -> { }, 1);
                graph = new PassiveNetwork(result.graph().reservoirs(), result.graph().pipes());
                var node = graph.reservoirs().getFirst();
                var s = node.state();
                System.out.printf(Locale.ROOT, "interval %d: %.6f K %.1f Pa liquid %s vapour %s crystal %.6g (%d substeps, %d rejected) %s%n", k, s.temperature(), s.pressure(),
                        Arrays.toString(s.liquidView()), Arrays.toString(s.vaporView()), node.inventory().crystals().moles("createcheme:crystal_carbon_dioxide_i"),
                        result.acceptedSubsteps(), result.rejectedSubsteps(), result.rejectionReasons());
            } catch (RuntimeException e) {
                System.out.println("interval " + k + ": " + e);
                break;
            }
        }
    }
}
