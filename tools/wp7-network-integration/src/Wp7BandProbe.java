import com.wormzjl.createcheme.fluid.support.FluidTestSupport;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;

/** WP7 scratch: a nitrogen tank fed by a generator whose state walks through the critical band. */
public class Wp7BandProbe {
    public static void main(String[] args) {
        var model = FluidTestSupport.networkModel();
        int n2 = model.components().indexOf("Nitrogen");
        double[][] path = {{145, 1.5e6}, {140, 2.0e6}, {136, 2.6e6}, {132, 3.0e6}, {129, 3.4e6}, {127, 3.7e6}, {126, 4.0e6},
                {124, 4.4e6}, {122, 4.8e6}, {120, 5.3e6}, {118, 5.8e6}, {116, 6.4e6}, {115, 7.0e6}};
        int intervals = args.length > 0 ? Integer.parseInt(args[0]) : 2;
        var geometry = new PipeResistance.Geometry(1, .05, .000045, 0);
        double[] one = new double[model.componentCount()]; one[n2] = 1;
        var unit = model.flashTP(path[0][0], path[0][1], one, () -> {});
        double[] tankAmounts = one.clone(); tankAmounts[n2] = 1 / unit.volume();
        var tank = model.flashTP(path[0][0], path[0][1], tankAmounts, () -> {});
        var solver = new PassiveIntervalSolver(model);
        PassiveNetwork graph = null;
        for (double[] g : path) {
            double[] ga = one.clone(); ga[n2] = 1 / model.flashTP(g[0], g[1], one, () -> {}).volume();
            var generator = model.flashTP(g[0], g[1], ga, () -> {});
            var tankNode = graph == null ? new PassiveNetwork.Reservoir(2, 0, tank) : graph.reservoirs().get(1);
            graph = new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1, 0, generator, PassiveNetwork.NodeKind.GENERATOR), tankNode),
                    List.of(new PassiveNetwork.Pipe(1, 0, 1, geometry)));
            for (int k = 0; k < intervals; k++) {
                try {
                    long[] cp = {0};
                    var result = solver.solve(graph, 5, PassiveIntervalSolver.Settings.defaults(), () -> cp[0]++, Math.min(solver.nextStepEstimate() > 0 ? solver.nextStepEstimate() : 1, 5));
                    graph = result.graph();
                    var s = graph.reservoirs().get(1).state();
                    System.out.printf("gen %.1f K %.2f MPa -> tank %.3f K %.4f MPa L=%.3f V=%.3f rho=%.1f acc=%d rej=%d cp=%d reasons=%s%n", g[0], g[1] / 1e6,
                            s.temperature(), s.pressure() / 1e6, Arrays.stream(s.liquidView()).sum(), Arrays.stream(s.vaporView()).sum(),
                            s.mass() / s.volume(), result.acceptedSubsteps(), result.rejectedSubsteps(), cp[0], result.rejectionReasons());
                } catch (RuntimeException held) {
                    System.out.printf("gen %.1f K %.2f MPa HELD %s: %s%n", g[0], g[1] / 1e6, held.getClass().getSimpleName(), held.getMessage());
                }
            }
        }
    }
}
