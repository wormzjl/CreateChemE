package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * P5 probe (batch 2026-09-24-coolprop-low-temperature; detached to tools/p5-solid-liquid-scans/ after the run): liquid-full
 * pilot vessels cooled through the network's energy input, 5 s intervals of -Q W carried by a methane trace. (1) pure
 * liquid methane cooled to its bubble point; (2) methane with 3 % CO2, compressed, cooled below its liquidus while still
 * liquid-full; (3) methane with 2 % CO2 at 2.45 MPa liquid-full. Prints every interval or the refusal.
 */
class P5LiquidFullVesselProbe {
    static final String PILOT = "createcheme:pilot_cryogenic";
    static final String CO2_I = "createcheme:crystal_carbon_dioxide_i";
    final FluidThermodynamics model = new FluidThermodynamics(MaterialCatalog.bundled(), PILOT);

    FluidThermodynamics.State charge(double t, double p, double[] z, double volume) {
        var unit = model.flashTP(t, p, z, () -> {});
        double[] n = z.clone();
        for (int i = 0; i < n.length; i++) n[i] *= volume / unit.volume();
        return model.flashTP(t, p, n, () -> {});
    }

    PassiveIntervalSolver.Result heat(PassiveNetwork graph, double watts) {
        double[] rate = new double[5];
        rate[1] = 1e-12;
        var withHeat = new PassiveNetwork(graph.reservoirs(), graph.pipes(), List.of(new ScheduledTransfer.Injection(100, 0, rate, watts)));
        var r = new PassiveIntervalSolver(model).solve(withHeat, 5, PassiveIntervalSolver.Settings.defaults(), () -> {}, 1);
        return r;
    }

    void run(String name, double t, double p, double[] z, double watts, int intervals) {
        var state = charge(t, p, z, 1e-3);
        var graph = new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1, 0, state)), List.of());
        System.out.printf(Locale.ROOT, "%s: charged %.3f K %.1f Pa, liquid %s vapour %s%n", name, state.temperature(), state.pressure(),
                state.liquidProperties() != null, state.vaporProperties() != null);
        for (int k = 0; k < intervals; k++) {
            try {
                var r = heat(graph, watts);
                graph = new PassiveNetwork(r.graph().reservoirs(), r.graph().pipes());
                var node = graph.reservoirs().getFirst();
                double[] l = node.state().liquidView(), v = node.state().vaporView();
                double ls = Arrays.stream(l).sum(), vs = Arrays.stream(v).sum();
                System.out.printf(Locale.ROOT, "%s interval %d: %.6f K %.1f Pa, liquid %.6f mol (x_CO2 %.6f), vapour %.3e mol, crystal %.6e mol, substeps %d rejected %d%n",
                        name, k + 1, node.state().temperature(), node.state().pressure(), ls, ls > 0 ? l[3] / ls : 0, vs, node.inventory().crystals().moles(CO2_I),
                        r.acceptedSubsteps(), r.rejectedSubsteps());
            } catch (RuntimeException e) {
                System.out.printf(Locale.ROOT, "%s interval %d: refused %s: %s%n", name, k + 1, e.getClass().getSimpleName(), e.getMessage());
                break;
            }
        }
    }

    @Test
    void liquidFullVessels() {
        run("(1) pure CH4 liquid-full", 150, 5e6, new double[] {0, 1, 0, 0, 0}, -200, 12);
        run("(2) CH4 + 3 % CO2 compressed", 170, 9e6, new double[] {0, .97, 0, .03, 0}, -200, 12);
        run("(3) CH4 + 2 % CO2 liquid-full", 172, 5e6, new double[] {0, .98, 0, .02, 0}, -200, 12);
        run("(4) CH4 + 2 % C2H6 liquid-full", 172, 5e6, new double[] {0, .98, .02, 0, 0}, -200, 12);
        run("(5) CH4 + 2 % N2 liquid-full", 160, 5e6, new double[] {.02, .98, 0, 0, 0}, -200, 12);
        run("(6) pure CH4 liquid-full at 172 K", 172, 5e6, new double[] {0, 1, 0, 0, 0}, -200, 12);
        run("(7) CH4 + 0.5 % CO2 liquid-full", 172, 5e6, new double[] {0, .995, 0, .005, 0}, -200, 12);
        run("(8) CH4 + 2 % CO2 liquid-full, -50 W", 172, 5e6, new double[] {0, .98, 0, .02, 0}, -50, 40);
    }
}
