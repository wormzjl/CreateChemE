import com.wormzjl.createcheme.fluid.support.FluidTestSupport;
import com.wormzjl.createcheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.createcheme.science.fluid.network.*;
import java.util.*;

/** WP7 scratch: one generator-to-tank nitrogen step, attempt by attempt. Args: T0 P0 Tg Pg [startStep]. */
public class Wp7StepProbe {
    public static void main(String[] args) {
        var model = FluidTestSupport.networkModel();
        int n2 = model.components().indexOf("Nitrogen");
        double t0 = Double.parseDouble(args[0]), p0 = Double.parseDouble(args[1]), tg = Double.parseDouble(args[2]), pg = Double.parseDouble(args[3]);
        double start = args.length > 4 ? Double.parseDouble(args[4]) : 1;
        var geometry = new PipeResistance.Geometry(1, .05, .000045, 0);
        double[] one = new double[model.componentCount()]; one[n2] = 1;
        double[] ta = one.clone(); ta[n2] = 1 / model.flashTP(t0, p0, one, () -> {}).volume();
        double[] ga = one.clone(); ga[n2] = 1 / model.flashTP(tg, pg, one, () -> {}).volume();
        var graph = new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1, 0, model.flashTP(tg, pg, ga, () -> {}), PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(2, 0, model.flashTP(t0, p0, ta, () -> {}))), List.of(new PassiveNetwork.Pipe(1, 0, 1, geometry)));
        SolverDiagnostics.reset(); SolverDiagnostics.ENABLED = true;
        try {
            var result = new PassiveIntervalSolver(model).solve(graph, 5, PassiveIntervalSolver.Settings.defaults(), () -> {}, start);
            var s = result.graph().reservoirs().get(1).state();
            System.out.printf("OK tank %.3f K %.4f MPa acc=%d rej=%d reasons=%s%n", s.temperature(), s.pressure() / 1e6, result.acceptedSubsteps(), result.rejectedSubsteps(), result.rejectionReasons());
        } catch (RuntimeException failed) { System.out.println("FAILED " + failed.getMessage()); }
        var sample = SolverDiagnostics.sample();
        for (var at : sample.attempts()) System.out.printf("  #%d step=%.3e accepted=%s %s err=%.3e%n", at.index(), at.step(), at.accepted(), at.dominant(), at.error());
        for (String name : SolverDiagnostics.names()) { long v = sample.value(name); if (v != 0 && !name.endsWith("Nanos")) System.out.println("  " + name + "=" + v); }
    }
}
