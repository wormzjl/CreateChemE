package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.createcheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.PilotCryogenicTestCatalog;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;

/**
 * P6 probe (detached to tools/p6-pilot-acceptance/, never committed): reads the fluid checkpoint of the P6 dev world
 * (research/.../p6-pilot-acceptance/world-final/data, format 6, the pilot package selected by the dev datapack) with the
 * pilot model, prints every island's vessels (T, P, phases, crystals) and times one 5 s interval of each through the
 * retained solver the island command uses, counting checkpoints (one per solver checkpoint and kernel evaluation) and
 * substeps, to explain the in-game budget holds.
 */
class P6WorldIslandProbe {
    @Test void islandsOfTheDevWorld() throws Exception {
        Locale.setDefault(Locale.ROOT);
        var model = new FluidThermodynamics(PilotCryogenicTestCatalog.catalog(), PilotCryogenicTestCatalog.PACKAGE_ID);
        Path data = Path.of(System.getProperty("p6.world", "D:/Minecraft/Modding/1.21/CreateChemE/research/2026-09-24-coolprop-low-temperature/p6-pilot-acceptance/world-final/data"));
        var core = NbtIo.readCompressed(data.resolve(FluidCheckpointStore.CORE_NAME), NbtAccounter.unlimitedHeap()).getCompound("data");
        var saved = FluidSavedData.load(core, key -> model, FluidCheckpointStore.directory(data, Runnable::run, false));
        for (var entry : saved.checkpoint().islands()) {
            var snapshot = entry.snapshot();
            System.out.printf("island %d committed %d status %s%n", snapshot.id(), snapshot.clock().committedTick(), snapshot.status());
            for (var node : snapshot.graph().reservoirs()) {
                if (node.kind() != PassiveNetwork.NodeKind.RESERVOIR) continue;
                var s = node.state();
                System.out.printf("  vessel %d: %.3f K %.1f Pa liquid %s vapour %s water %.3e crystals %s moles %s%n", node.id(), s.temperature(), s.pressure(),
                        s.liquidProperties() != null, s.vaporProperties() != null, s.waterLiquid() + s.waterVapor(), node.inventory().crystals().stocks(),
                        Arrays.toString(node.inventory().moles()));
            }
            long[] calls = {0};
            long start = System.nanoTime();
            try {
                var result = new RetainedSolver().solve(model, snapshot.graph(), 5, PassiveIntervalSolver.Settings.defaults(), () -> calls[0]++);
                System.out.printf("  one 5 s interval: %.1f ms, %d checkpoints, substeps %d rejected %d%n", (System.nanoTime() - start) / 1e6, calls[0],
                        result.acceptedSubsteps(), result.rejectedSubsteps());
            } catch (RuntimeException e) {
                System.out.printf("  one 5 s interval refused after %.1f ms, %d checkpoints: %s%n", (System.nanoTime() - start) / 1e6, calls[0], e.getMessage());
            }
        }
    }
}
