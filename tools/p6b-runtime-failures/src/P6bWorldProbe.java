package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.createcheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.createcheme.science.fluid.network.ScheduledTransfer;
import com.wormzjl.createcheme.science.fluid.network.TrBdf2StepSolver;
import com.wormzjl.createcheme.science.fluid.network.PassiveStepSolver;
import com.wormzjl.createcheme.science.fluid.network.InventoryEquilibrium;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.PilotCryogenicTestCatalog;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/**
 * P6b probe (tools/p6b-runtime-failures/, never committed): reads the P6 dev world's saved fluid checkpoint (a copy in
 * research/.../p6-pilot-acceptance/world-final/data) with the pilot model, prints every island's topology (nodes, pipes,
 * scheduled transfers) and times consecutive 5 s intervals of the selected islands through the retained solver
 * (checkpoints counted). Arguments: [island ids, comma separated, or all] [intervals] [trace]. With "trace" a refused
 * interval is followed by single TR-BDF2 steps of decreasing size, printing the first exception's stack.
 * -Dp6b.world=<data folder> overrides the checkpoint.
 */
public final class P6bWorldProbe {
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        String only = args.length > 0 ? args[0] : "all";
        int intervals = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        boolean trace = args.length > 2 && args[2].equals("trace");
        var model = new FluidThermodynamics(PilotCryogenicTestCatalog.catalog(), PilotCryogenicTestCatalog.PACKAGE_ID);
        Path data = Path.of(System.getProperty("p6b.world", "D:/Minecraft/Modding/1.21/CreateChemE/research/2026-09-24-coolprop-low-temperature/p6-pilot-acceptance/world-final/data"));
        var core = NbtIo.readCompressed(data.resolve(FluidCheckpointStore.CORE_NAME), NbtAccounter.unlimitedHeap()).getCompound("data");
        var saved = FluidSavedData.load(core, key -> model, FluidCheckpointStore.directory(data, Runnable::run, false));
        for (var entry : saved.checkpoint().islands()) {
            var snapshot = entry.snapshot();
            if (!only.equals("all") && Arrays.stream(only.split(",")).noneMatch(s -> Long.parseLong(s) == snapshot.id())) continue;
            System.out.printf("island %d committed %d status %s%n", snapshot.id(), snapshot.clock().committedTick(), snapshot.status());
            print(snapshot.graph());
            var solver = new RetainedSolver();
            PassiveNetwork graph = snapshot.graph();
            int strip = Integer.getInteger("p6b.strip", -1);
            if (strip >= 0) {
                // Measurement only (failure 1, option A proxy): the vessel with its non-CO2 traces removed, a pure CO2 feed P5 answers exactly.
                var nodes = new java.util.ArrayList<>(graph.reservoirs());
                var old = nodes.get(strip);
                double[] moles = old.inventory().moles();
                for (int c = 0; c < moles.length; c++) if (c != 3) moles[c] = 0;
                var inventory = new PassiveNetwork.Inventory(old.inventory().volume(), moles, old.inventory().internalEnergy(), old.inventory().solids(), old.inventory().crystals());
                nodes.set(strip, new PassiveNetwork.Reservoir(old.id(), old.elevation(), model.flashTP(old.state().temperature(), old.state().pressure(), moles, () -> {}), old.kind(), inventory));
                graph = new PassiveNetwork(nodes, graph.pipes(), graph.scheduledTransfers());
                System.out.println("  (vessel [" + strip + "] traces removed: " + Arrays.toString(moles) + ")");
            }
            for (int k = 0; k < intervals; k++) {
                long[] calls = {0};
                long start = System.nanoTime();
                try {
                    var result = solver.solve(model, graph, 5, PassiveIntervalSolver.Settings.defaults(), () -> calls[0]++);
                    System.out.printf("  interval %d: %.1f ms, %d checkpoints, substeps %d rejected %d reasons %s%n", k, (System.nanoTime() - start) / 1e6, calls[0],
                            result.acceptedSubsteps(), result.rejectedSubsteps(), result.rejectionReasons());
                    graph = result.graph();
                    print(graph);
                } catch (RuntimeException e) {
                    System.out.printf("  interval %d refused after %.1f ms, %d checkpoints: %s%n", k, (System.nanoTime() - start) / 1e6, calls[0], e.getMessage());
                    if (e.getCause() != null) System.out.println("    cause: " + e.getCause());
                    var domain = com.wormzjl.createcheme.science.fluid.solver.SparseNewton.domainViolation(e);
                    System.out.println("    typed domain cause: " + (domain == null ? "none (a numerical hold)" : domain.getMessage()));
                    if (trace) trace(model, graph);
                    break;
                }
            }
        }
    }

    static void print(PassiveNetwork graph) {
        int index = 0;
        for (var node : graph.reservoirs()) {
            var s = node.state();
            var inv = node.inventory();
            System.out.printf("  [%d] %s id %d: %.4f K %.1f Pa L %.3e V %.3e W %.3e (Lv %.3e Vv %.3e Wv %.3e m3) liq %s vap %s crystals %s solids %s moles %s U %.6e vol %.4e%n", index++, node.kind(), node.id(),
                    s.temperature(), s.pressure(), sum(s.liquidView()), sum(s.vaporView()), s.waterLiquid() + s.waterVapor(), s.liquidVolume(), s.vaporVolume(), s.waterVolume(),
                    Arrays.toString(s.liquidView()), Arrays.toString(s.vaporView()), inv.crystals().stocks(), inv.solids().populations().size() + " pops mass " + s.solidMoments().mass(),
                    Arrays.toString(inv.moles()), inv.internalEnergy(), inv.volume());
        }
        for (var pipe : graph.pipes()) System.out.printf("  pipe %d: %d -> %d %s blocked %d sections %s%n", pipe.id(), pipe.first(), pipe.second(), pipe.control(), pipe.blockedDirections(), pipe.sections());
        for (var t : graph.scheduledTransfers()) {
            if (t instanceof ScheduledTransfer.Injection i) System.out.printf("  injection %d at [%d]: %s mol/s, %.6e W, solids %s%n", i.id(), i.node(), Arrays.toString(i.molesPerSecond()), i.totalEnergyPerSecond(), i.solidsPerSecond());
            else System.out.printf("  transfer %s%n", t);
        }
    }

    static double sum(double[] values) { double s = 0; for (double v : values) s += v; return s; }

    static void trace(FluidThermodynamics model, PassiveNetwork graph) {
        var step = new TrBdf2StepSolver(model);
        PassiveNetwork start = InventoryEquilibrium.refresh(graph, model, () -> {});
        for (double dt = 1.0; dt > 1e-7; dt /= 4) {
            try {
                var r = step.trial(start, dt, () -> {}, PassiveStepSolver.Acceptance.FULL, TrBdf2StepSolver.StageGuard.NONE);
                System.out.printf("    step %.3e s ok%n", dt);
            } catch (RuntimeException e) {
                System.out.printf("    step %.3e s: %s%n", dt, e);
                Throwable t = e;
                while (t != null) {
                    for (var el : t.getStackTrace()) { if (el.getClassName().contains("createcheme")) System.out.println("        at " + el); }
                    t = t.getCause();
                    if (t != null) System.out.println("      caused by " + t);
                }
            }
        }
    }
}
