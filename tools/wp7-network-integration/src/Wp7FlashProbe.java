import com.wormzjl.createcheme.fluid.support.FluidTestSupport;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.thermo.ThermoDomainViolation;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.Arrays;

/** WP7 scratch probe: the network flash on a few states, old classes against new (run with either on the class path). */
public class Wp7FlashProbe {
    public static void main(String[] args) {
        var model = FluidTestSupport.networkModel();
        int n = model.componentCount();
        var names = model.components();
        double[] wet = FluidTestSupport.oneCubicMetreState(model, FluidTestSupport.Mixture.WET_CRUDE, 350, 150000).liquid();
        // States: WET_CRUDE composition at a few (T, P); N2 with trace water; pure N2.
        double[][] feeds = new double[5][];
        var crude = FluidTestSupport.oneCubicMetreState(model, FluidTestSupport.Mixture.WET_CRUDE, 350, 150000);
        double[] totals = new double[n];
        for (int i = 0; i < n - 1; i++) totals[i] = crude.liquidView()[i] + crude.vaporView()[i];
        totals[n - 1] = crude.waterLiquid() + crude.waterVapor();
        feeds[0] = totals;
        double[] dry = totals.clone(); dry[n - 1] = 0; feeds[1] = dry;
        double[] trace = new double[n]; trace[names.indexOf("Nitrogen")] = 40; trace[n - 1] = 40e-12; feeds[2] = trace;
        double[] humid = new double[n]; humid[names.indexOf("Nitrogen")] = 40; humid[n - 1] = 0.4; feeds[3] = humid;
        double[] nitrogen = new double[n]; nitrogen[names.indexOf("Nitrogen")] = 40; feeds[4] = nitrogen;
        double[][] tp = {{350, 150000}, {350, 150000}, {298.15, 101325}, {298.15, 101325}, {298.15, 101325}};
        String[] label = {"wet crude 350 K 150 kPa", "dry crude 350 K 150 kPa", "N2 + 1e-12 water 298 K 1 atm", "N2 + 1% water 298 K 1 atm", "pure N2 298 K 1 atm"};
        for (int s = 0; s < feeds.length; s++) {
            long[] count = {0};
            var state = model.flashTP(tp[s][0], tp[s][1], feeds[s], () -> count[0]++);
            double nl = Arrays.stream(state.liquidView()).sum(), nv = Arrays.stream(state.vaporView()).sum();
            System.out.printf("%-32s L=%.9e V=%.9e W=%.9e S=%.9e pc=%.6f vol=%.9e h=%.9e checkpoints=%d%n", label[s], nl, nv,
                    state.waterLiquid(), state.waterVapor(), state.hydrocarbonPartialPressure(), state.volume(), state.enthalpy(), count[0]);
            // Warm timing.
            for (int r = 0; r < 2000; r++) model.flashTP(tp[s][0], tp[s][1], feeds[s], () -> {});
            long start = System.nanoTime();
            int reps = 2000;
            for (int r = 0; r < reps; r++) model.flashTP(tp[s][0], tp[s][1], feeds[s], () -> {});
            System.out.printf("    warm %.2f us per flash%n", (System.nanoTime() - start) / 1e3 / reps);
        }
        // The WP5 pilot grid.
        String pilot = "createcheme:pilot_cryogenic";
        var pm = new FluidThermodynamics(MaterialCatalog.bundled(), pilot);
        double[][] pfeeds = {
                {1, 0, 0, 0, 0}, {0, 1, 0, 0, 0}, {0, 0, 1, 0, 0}, {0, 0, 0, 1, 0},
                {0.5, 0.5, 0, 0, 0}, {0.1, 0.7, 0.2, 0, 0}, {0, 0.8, 0, 0.2, 0}, {0.2, 0, 0, 0.8, 0}, {0, 0, 0.6, 0.4, 0},
                {0.05, 0.8, 0.1, 0.05, 0}, {0.1, 0.6, 0.1, 0.1, 0.1}};
        double[] temperatures = {80, 100, 120, 150, 180, 230, 260, 300, 400, 700, 1100};
        double[] pressures = {1.0e5, 3.0e5, 1.0e6, 2.0e6, 5.0e6, 1.0e7};
        int evaluated = 0, domain = 0, steam = 0, twoPhase = 0, failed = 0;
        for (double t : temperatures) for (double p : pressures) for (double[] feed : pfeeds) {
            try {
                var state = pm.flashTP(t, p, feed, () -> {});
                evaluated++;
                if (Arrays.stream(state.liquidView()).sum() > 0 && Arrays.stream(state.vaporView()).sum() > 0) twoPhase++;
            } catch (ThermoDomainViolation outside) { domain++; }
            catch (IllegalArgumentException refused) {
                if (refused.getMessage().startsWith("Water-vapor approximation outside")) steam++;
                else { failed++; System.out.println("  failed " + t + " K " + p + " Pa " + Arrays.toString(feed) + ": " + refused.getMessage()); }
            }
        }
        System.out.printf("pilot grid: evaluated %d (two-phase %d), domain %d, steam %d, failed %d%n", evaluated, twoPhase, domain, steam, failed);
        var open = pm.flashTP(230, 2e6, new double[] {0, 0.8, 0, 0.2, 0}, () -> {});
        System.out.printf("CH4/CO2 80/20 230 K 2 MPa: liquid %s vapour %s%n", Arrays.toString(open.liquidView()), Arrays.toString(open.vaporView()));
    }
}
