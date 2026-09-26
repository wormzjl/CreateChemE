package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.List;
import java.util.Locale;

/**
 * P6 probe (detached, never committed): CrystalDepositionIslandTest (c)'s pilot chain (a warm vessel of 10 % CO2 in N2 at
 * 250 K and 1.3 MPa, a junction, a cold N2 vessel at 140 K and 1 MPa, 4 mm pipes) for twelve 5 s intervals through
 * PassiveIntervalSolver, printing each interval or its refusal; run at HEAD and at 4ff68da to tell whether a failure
 * predates P6.
 */
public final class P6ChainProbe {
    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        var model = new FluidThermodynamics(MaterialCatalog.bundled(), "createcheme:pilot_cryogenic");
        var warm = charge(model, 250, 1.3e6, new double[] {.9, 0, 0, .1, 0}, .1);
        var cold = charge(model, 140, 1e6, new double[] {1, 0, 0, 0, 0}, .1);
        var pipe = new PipeResistance.Geometry(1, .004, .000045, 0);
        var graph = new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1, 0, warm), new PassiveNetwork.Reservoir(2, 0, warm, true),
                new PassiveNetwork.Reservoir(3, 0, cold)), List.of(new PassiveNetwork.Pipe(10, 0, 1, pipe), new PassiveNetwork.Pipe(11, 1, 2, pipe)));
        var solver = new PassiveIntervalSolver(model);
        double step = 1;
        for (int k = 0; k < Integer.getInteger("p6b.intervals", 12); k++) {
            System.setProperty("p6.debug", Boolean.toString(k == Integer.getInteger("p6b.traceInterval", 7) && Boolean.getBoolean("p6.trace")));
            try {
                var result = solver.solve(graph, 5, PassiveIntervalSolver.Settings.defaults(), () -> {}, step);
                step = solver.nextStepEstimate();
                graph = result.graph();
                var a = graph.reservoirs().get(0);
                var b = graph.reservoirs().get(2);
                System.out.printf("interval %d: warm %.3f K %.0f Pa | cold %.3f K %.0f Pa liquid %s crystal %.6e | substeps %d rejected %d%n", k + 1,
                        a.state().temperature(), a.state().pressure(), b.state().temperature(), b.state().pressure(), b.state().liquidProperties() != null,
                        b.inventory().crystals().stocks().stream().mapToDouble(s -> s.moles()).sum(), result.acceptedSubsteps(), result.rejectedSubsteps());
            } catch (RuntimeException e) {
                System.out.printf("interval %d refused: %s%n", k + 1, e.getMessage());
                break;
            }
        }
    }

    static FluidThermodynamics.State charge(FluidThermodynamics model, double t, double p, double[] z, double volume) {
        var unit = model.flashTP(t, p, z, () -> {});
        double[] n = z.clone();
        for (int i = 0; i < n.length; i++) n[i] *= volume / unit.volume();
        return model.flashTP(t, p, n, () -> {});
    }
}
