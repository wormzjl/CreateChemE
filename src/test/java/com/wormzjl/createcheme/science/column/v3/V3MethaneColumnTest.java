package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.GsonBuilder;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class V3MethaneColumnTest {
    @Test void freshPresetAndSavedInputRetainTheMethaneAxis() throws Exception {
        var owner = ColumnCalculatorV3BlockEntity.class;
        var fresh = owner.getDeclaredMethod("freshInput");
        fresh.setAccessible(true);
        var input = ColumnCalculatorV3BlockEntity.methaneCduInput();
        assertEquals(input, fresh.invoke(null));
        var write = owner.getDeclaredMethod("writeInput", V3ColumnInput.class);
        var read = owner.getDeclaredMethod("readInput", net.minecraft.nbt.CompoundTag.class);
        write.setAccessible(true);
        read.setAccessible(true);
        assertEquals(input, read.invoke(null, write.invoke(null, input)));
        var original = ColumnCalculatorV3BlockEntity.literatureCduInput();
        assertEquals(original, read.invoke(null, write.invoke(null, original)), "old saved feeds retain their 19-component axis");
        assertEquals(original.specifications(), input.specifications());
        assertEquals(com.wormzjl.createcheme.science.material.MaterialCatalog.bundled().columnSideDrawRates(input.packageId()),input.sideDraws().stream().map(V3SideDrawSpec::molarFlowMolPerSecond).toList());
        assertEquals(original.steamFeeds(), input.steamFeeds());
        assertEquals(original.pumparounds(), input.pumparounds());
    }

    @Test void methaneFeedConvergesWithCurrentInitializer() throws Exception {
        var input = ColumnCalculatorV3BlockEntity.methaneCduInput();
        assertEquals("Methane", input.componentBasis().componentId(0));
        assertEquals(com.wormzjl.createcheme.science.material.MaterialTestBasis.CRUDE, input.componentBasis().componentCount());
        assertEquals(java.util.Arrays.stream(input.feedComponentMolarFlowsMolPerSecond()).sum() * 0.005, input.feedComponentMolarFlowsMolPerSecond()[0], 1e-12);
        long start = System.nanoTime();
        var outcome = V3ColumnCalculator.calculate(input, () -> {
            if (System.nanoTime() - start > 60_000_000_000L) throw new java.util.concurrent.CancellationException("methane qualification deadline");
        }, 0, 0, V3InitializationOptions.CURRENT, new V3NeuralInitializer() {
            @Override public String modelId() { throw new AssertionError("CURRENT_ONLY must not access the model"); }
            @Override public java.util.Optional<V3NeuralSeed> predict(V3ColumnInput ignored, V3SolveControl control) {
                throw new AssertionError("CURRENT_ONLY must not infer");
            }
        });
        var directory = Path.of("build", "methane-qualification");
        Files.createDirectories(directory);
        var json = new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create();
        Files.writeString(directory.resolve("input.json"), json.toJson(input));
        Files.writeString(directory.resolve("outcome.json"), json.toJson(outcome));
        double elapsedMillis = (System.nanoTime() - start) / 1e6;
        Files.writeString(directory.resolve("timing.json"), json.toJson(java.util.Map.of("elapsedMilliseconds", elapsedMillis)));
        System.out.println("Methane CURRENT_ONLY elapsed ms=" + elapsedMillis);
        var success = assertInstanceOf(V3ColumnOutcome.Success.class, outcome, outcome::toString);
        assertTrue(success.result().acceptanceAudit().accepted());
        assertTrue(success.result().convergenceEvidence().satisfiesGates());
        double methaneOut = success.result().streams().stream()
                .mapToDouble(stream -> stream.molarFlowMolPerSecond() * stream.moleFractions().stream()
                        .filter(fraction -> fraction.componentId().equals("Methane"))
                        .mapToDouble(V3ColumnStreamProperties.ComponentFraction::moleFraction).sum()).sum();
        assertEquals(input.feedComponentMolarFlowsMolPerSecond()[0], methaneOut, 1e-7);
        var overhead = success.result().streams().stream().filter(stream -> stream.streamId().equals("overhead_vapor"))
                .findFirst().orElseThrow();
        assertTrue(overhead.moleFractions().stream().anyMatch(fraction -> fraction.componentId().equals("Methane")
                && fraction.moleFraction() > 0.01));
    }

    @Test void defaultNeuralFirstModeSolvesTheMethaneColumnThroughTheBundledModel() {
        var input = ColumnCalculatorV3BlockEntity.methaneCduInput();
        long start = System.nanoTime();
        var outcome = V3ColumnCalculator.calculate(input, () -> {
            if (System.nanoTime() - start > 60_000_000_000L) throw new java.util.concurrent.CancellationException("methane fallback deadline");
        }, 0, 0, V3InitializationOptions.DEFAULT, V3NeuralModels.bundled());
        var success = assertInstanceOf(V3ColumnOutcome.Success.class, outcome, outcome::toString);
        assertTrue(success.result().convergenceEvidence().satisfiesGates());
        // One model ships, so the initialization event names it whichever route won. Which route wins on
        // this preset is a coverage question the 405-input campaign answers, not an invariant of this test.
        String event = success.diagnostics().events().getFirst();
        assertTrue(event.contains("model=" + V3NeuralModels.bundled().bind(com.wormzjl.createcheme.science.material.MaterialRuntime.current(), input).modelId()), event);
    }
}
