import com.wormzjl.createcheme.fluid.support.FluidTestSupport;
import com.wormzjl.createcheme.science.fluid.network.*;
import java.util.*;

/** WP7 scratch: a nitrogen tank filled from a fixed generator through a narrow pipe. Args: T0 P0 V0 Tg Pg bore intervals [voidP]. */
public class Wp7FillProbe {
    public static void main(String[] args) {
        var model = FluidTestSupport.networkModel();
        int n2 = model.components().indexOf("Nitrogen");
        double t0 = Double.parseDouble(args[0]), p0 = Double.parseDouble(args[1]), v0 = Double.parseDouble(args[2]);
        double tg = Double.parseDouble(args[3]), pg = Double.parseDouble(args[4]), bore = Double.parseDouble(args[5]);
        int intervals = Integer.parseInt(args[6]);
        double[] one = new double[model.componentCount()]; one[n2] = 1;
        double[] ta = one.clone(); ta[n2] = v0 / model.flashTP(t0, p0, one, () -> {}).volume();
        double[] ga = one.clone(); ga[n2] = 1 / model.flashTP(tg, pg, one, () -> {}).volume();
        var geometry = new PipeResistance.Geometry(1, bore, .000045, 0);
        var graph = new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1, 0, model.flashTP(tg, pg, ga, () -> {}), System.getProperty("kind","GENERATOR").equals("VOID")?PassiveNetwork.NodeKind.VOID:PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(2, 0, model.flashTP(t0, p0, ta, () -> {}))), List.of(new PassiveNetwork.Pipe(1, 0, 1, geometry)));
        var solver = new PassiveIntervalSolver(model);
        double step = 1;
        long started = System.nanoTime();
        for (int k = 0; k < intervals; k++) {
            try {
                long[] cp = {0};
                var result = solver.solve(graph, 5, PassiveIntervalSolver.Settings.defaults(), () -> cp[0]++, Math.min(step, 5));
                graph = result.graph(); step = solver.nextStepEstimate();
                var s = graph.reservoirs().get(1).state();
                double tr = s.temperature() / 126.192, pr = s.pressure() / 3395800;
                boolean band = tr >= .95 && tr <= 1.1 && pr >= .8 && pr <= 1.5 || tr >= .9 && tr <= .95 && pr >= .58 && pr <= .74 || tr >= 1.05 && tr <= 1.2 && pr >= 2 && pr <= 3;
                System.out.printf("%2d tank %.3f K %.4f MPa %s L=%.2f V=%.2f rho=%.1f flow=%.4e acc=%d rej=%d cp=%d %s%n", k + 1, s.temperature(), s.pressure() / 1e6,
                        band ? "BAND" : "    ", Arrays.stream(s.liquidView()).sum(), Arrays.stream(s.vaporView()).sum(), s.mass() / s.volume(),
                        result.averageMassFlows()[0], result.acceptedSubsteps(), result.rejectedSubsteps(), cp[0], result.rejectionReasons());
            } catch (RuntimeException held) {
                System.out.printf("%2d HELD %s: %s%n", k + 1, held.getClass().getSimpleName(), held.getMessage());
                break;
            }
        }
        System.out.printf("wall %.1f s%n", (System.nanoTime() - started) / 1e9);
    }
}
