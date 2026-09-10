package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class V3PhaseAwareNeuralInitializerTest {
    @Test void multipleAcceptedAdvisoryCandidatesPublishOneObservedProfile() {
        var input = com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity.methaneCduInput();
        var reference = new AtomicReference<V3NeuralSeed>();
        var cold = V3ColumnCalculator.calculateWithAcceptedProfile(input, () -> {}, reference::set);
        assertInstanceOf(V3ColumnOutcome.Success.class, cold, cold::toString);
        var model = new V3PhaseAwareNeuralInitializer("test-two-candidates",
                List.of(expert("a", reference.get()), expert("b", reference.get())));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var result = V3ColumnCalculator.calculateWithAcceptedProfile(input, () -> {},
                new V3InitializationOptions(V3InitializationOptions.Mode.LNN_ONLY, V3InitializationOptions.WetStart.AUTO, 16, 10_000),
                model, profile -> calls.incrementAndGet());
        assertInstanceOf(V3ColumnOutcome.Success.class, result, result::toString);
        assertEquals(1, calls.get());
        assertTrue(result.diagnostics().events().getFirst().contains("candidates=2"));
    }

    @Test void theSameCondenserTemperatureCanSelectDifferentPhasesWhenTheEnergyConditionsChange() throws Exception {
        var wet = V3WaterPhaseQualificationTest.boundarySeed();
        var a = wet.input();
        var b = new V3ColumnInput(a.schemaVersion(), a.packageId(), a.assayId(), a.componentBasis(),
                a.feedComponentMolarFlowsMolPerSecond(), a.feedTemperatureKelvin(), a.stageCount(), a.feedStageNumber(),
                a.topPressurePascal(), a.stagePressureDropPascal(), a.specifications(), a.sideDraws(), a.steamFeeds(),
                a.pumparounds().stream().map(p -> p.drawTray() == 10
                        ? new V3PumparoundSpec(p.returnTray(), p.drawTray(), p.dutyWatts() * .9, p.split()) : p).toList());
        var captured = new AtomicReference<V3NeuralSeed>();
        var cold = V3ColumnCalculator.calculateWithAcceptedProfile(b, () -> {}, captured::set);
        assertInstanceOf(V3ColumnOutcome.Success.class, cold, cold::toString);
        var dry = captured.get();
        assertEquals(V3WaterPhaseQualification.Grade.DRY_EQUILIBRIUM, V3WaterPhaseQualification.assess(dry).grade());
        assertEquals(V3NeuralFeatures.encode(a)[5], V3NeuralFeatures.encode(b)[5]);
        var selector = new V3PhaseAwareNeuralInitializer("test-phase-selector", List.of(expert("dry", dry), expert("wet", wet)));
        assertTrue(selector.predict(a, () -> {}).orElseThrow().wetTrays()[1]);
        assertFalse(selector.predict(b, () -> {}).orElseThrow().wetTrays()[1]);
    }

    private static V3NeuralInitializer expert(String id, V3NeuralSeed prediction) {
        return new V3NeuralInitializer() {
            public String modelId() { return id; }
            public Optional<V3NeuralSeed> predict(V3ColumnInput input, V3SolveControl control) {
                return Optional.of(new V3NeuralSeed(input, prediction.propertyRevision(), prediction.branch(),
                        prediction.liquid(), prediction.vapor(), prediction.temperatures(), prediction.freeWater(), prediction.wetTrays()));
            }
        };
    }
}
