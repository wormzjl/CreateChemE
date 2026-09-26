package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.network.ApproximationAnchor;
import com.wormzjl.createcheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.createcheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.PilotCryogenicTestCatalog;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/**
 * P6b probe (tools/p6b-runtime-failures/, never committed; needs p6b-probe-counters.patch applied): row E of the P6 dev
 * world replayed from the start of its flush. The saved island (liquid methane generator 115 K / 200 kPa, CO2 generator
 * 250 K / 250 kPa, the 1 m3 vessel, a void at 101.325 kPa) with its vessel reset to the reservoir's initial state
 * (nitrogen, 298.15 K, 101.325 kPa); 5 s intervals through the retained solver, printing per interval the wall time, the
 * checkpoints, the substeps, the crystal UV calls and their kernel evaluations (and failures), the TP flashes, and the
 * vessel's state. With "compare", each interval is also run as the approximate fallback from the same start (crystals
 * frozen; -Dp6b.approximateCrystals=true lifts the D18 refusal), and its end state is compared with the full one.
 * Arguments: [intervals] [compare] [island id] [vessel index].
 */
public final class P6bRowEFlushProbe {
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        int intervals = args.length > 0 ? Integer.parseInt(args[0]) : 40;
        boolean compare = args.length > 1 && args[1].equals("compare");
        long island = args.length > 2 ? Long.parseLong(args[2]) : 78;
        int vesselIndex = args.length > 3 ? Integer.parseInt(args[3]) : 2;
        var model = new FluidThermodynamics(PilotCryogenicTestCatalog.catalog(), PilotCryogenicTestCatalog.PACKAGE_ID);
        Path data = Path.of(System.getProperty("p6b.world", "D:/Minecraft/Modding/1.21/CreateChemE/research/2026-09-24-coolprop-low-temperature/p6-pilot-acceptance/world-final/data"));
        var core = NbtIo.readCompressed(data.resolve(FluidCheckpointStore.CORE_NAME), NbtAccounter.unlimitedHeap()).getCompound("data");
        var saved = FluidSavedData.load(core, key -> model, FluidCheckpointStore.directory(data, Runnable::run, false));
        PassiveNetwork graph = null;
        for (var entry : saved.checkpoint().islands()) if (entry.snapshot().id() == island) graph = entry.snapshot().graph();
        if (graph == null) throw new IllegalStateException("no island " + island);
        var nodes = new ArrayList<>(graph.reservoirs());
        var old = nodes.get(vesselIndex);
        var probe = model.flashTP(298.15, 101325, new double[] {1, 0, 0, 0, 0}, () -> {});
        double scale = 1.0 / probe.volume();
        var nitrogen = model.flashTP(298.15, 101325, new double[] {scale, 0, 0, 0, 0}, () -> {});
        nodes.set(vesselIndex, new PassiveNetwork.Reservoir(old.id(), old.elevation(), nitrogen));
        graph = new PassiveNetwork(nodes, graph.pipes(), graph.scheduledTransfers());
        var retained = new RetainedSolver();
        ApproximationAnchor anchor = null;
        double totalMs = 0;
        for (int k = 0; k < intervals; k++) {
            long[] calls = {0};
            reset();
            long start = System.nanoTime();
            PassiveIntervalSolver.Result result;
            try {
                result = retained.solve(model, graph, 5, PassiveIntervalSolver.Settings.defaults(), () -> calls[0]++);
            } catch (RuntimeException e) {
                System.out.printf("interval %d refused after %.1f ms, %d checkpoints (%s): %s%n", k + 1, (System.nanoTime() - start) / 1e6, calls[0], counters(), e.getMessage());
                break;
            }
            double ms = (System.nanoTime() - start) / 1e6;
            if (Boolean.getBoolean("p6b.uv")) System.out.println("   start vessel UV: " + com.wormzjl.createcheme.science.fluid.thermo.P6bUvDiagnosis.analyse(model, graph.reservoirs().get(vesselIndex)));
            totalMs += ms;
            var v = result.graph().reservoirs().get(vesselIndex);
            var s = v.state();
            System.out.printf("interval %d: %.1f ms, %d checkpoints, substeps %d rejected %d (%s) | vessel %.3f K %.0f Pa L %.4f V %.4f m3 crystal %.3f mol, fluid CH4 %.2f CO2 %.3f N2 %.4f | reasons %s%n",
                    k + 1, ms, calls[0], result.acceptedSubsteps(), result.rejectedSubsteps(), counters(), s.temperature(), s.pressure(), s.liquidVolume(), s.vaporVolume(),
                    v.inventory().crystals().stocks().stream().mapToDouble(x -> x.moles()).sum(), v.inventory().moles()[1], v.inventory().moles()[3], v.inventory().moles()[0], result.rejectionReasons());
            if (compare && anchor != null) {
                long[] approximateCalls = {0};
                reset();
                long a0 = System.nanoTime();
                try {
                    var guard = anchor.guard(model, graph);
                    var approximate = new PassiveIntervalSolver(model, PassiveIntervalSolver.ErrorControl.EMBEDDED)
                            .solveApproximate(graph, 5, new PassiveIntervalSolver.Settings(1, 20, .0025, 256), () -> approximateCalls[0]++, guard);
                    var av = approximate.graph().reservoirs().get(vesselIndex);
                    var as = av.state();
                    System.out.printf("   approximate: %.1f ms, %d checkpoints, substeps %d rejected %d (%s) | vessel %.3f K (%+.3f) %.0f Pa (%+.3f %%) crystal %.3f (frozen) fluid CO2 %.3f (full %.3f)%n",
                            (System.nanoTime() - a0) / 1e6, approximateCalls[0], approximate.acceptedSubsteps(), approximate.rejectedSubsteps(), counters(),
                            as.temperature(), as.temperature() - s.temperature(), as.pressure(), 100 * (as.pressure() - s.pressure()) / s.pressure(),
                            av.inventory().crystals().stocks().stream().mapToDouble(x -> x.moles()).sum(), av.inventory().moles()[3], v.inventory().moles()[3]);
                } catch (RuntimeException e) {
                    System.out.printf("   approximate refused after %.1f ms, %d checkpoints: %s%n", (System.nanoTime() - a0) / 1e6, approximateCalls[0], e.getMessage());
                }
            }
            System.out.println("   endpoint modes " + result.endpointModes() + " flows " + Arrays.toString(result.averageMassFlows()));
            anchor = ApproximationAnchor.fromFull(model, result);
            graph = result.graph();
        }
        System.out.printf("total %.1f ms%n", totalMs);
    }

    static void reset() {
        FluidThermodynamics.P6B_UV_CALLS.reset(); FluidThermodynamics.P6B_UV_KERNEL.reset(); FluidThermodynamics.P6B_UV_FAILED.reset();
        FluidThermodynamics.P6B_TP_CALLS.reset(); FluidThermodynamics.P6B_TP_KERNEL.reset();
        com.wormzjl.createcheme.science.thermo.phase.FluidTpEquilibrium.P6B_WARM_ATTEMPTS.reset(); com.wormzjl.createcheme.science.thermo.phase.FluidTpEquilibrium.P6B_WARM_HITS.reset();
    }

    static String counters() {
        return String.format("UV %d calls %d kernel %d failed, warm %d/%d; TP %d calls %d kernel", FluidThermodynamics.P6B_UV_CALLS.sum(), FluidThermodynamics.P6B_UV_KERNEL.sum(),
                FluidThermodynamics.P6B_UV_FAILED.sum(), com.wormzjl.createcheme.science.thermo.phase.FluidTpEquilibrium.P6B_WARM_HITS.sum(), com.wormzjl.createcheme.science.thermo.phase.FluidTpEquilibrium.P6B_WARM_ATTEMPTS.sum(), FluidThermodynamics.P6B_TP_CALLS.sum(), FluidThermodynamics.P6B_TP_KERNEL.sum());
    }
}
