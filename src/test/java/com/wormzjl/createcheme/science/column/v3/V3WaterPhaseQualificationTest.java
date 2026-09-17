package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

@org.junit.jupiter.api.extension.ExtendWith(com.wormzjl.createcheme.science.material.TjlReferenceExtension.class)
class V3WaterPhaseQualificationTest {
    static V3NeuralSeed boundarySeed() throws Exception {
        try (var stream = V3WaterPhaseQualificationTest.class.getResourceAsStream("/science/column/v3/neural/tjl20-wet-boundary.json")) {
            var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            var seed = json.getAsJsonObject("seed");
            var base = ColumnCalculatorV3BlockEntity.methaneCduInput();
            double tc = json.getAsJsonObject("input").getAsJsonArray("specifications").get(0).getAsJsonObject().get("kelvin").getAsDouble();
            var input = new V3ColumnInput(base.schemaVersion(), base.packageId(), base.assayId(), base.componentBasis(),
                    base.feedComponentMolarFlowsMolPerSecond(), base.feedTemperatureKelvin(), base.stageCount(),
                    base.feedStageNumber(), base.topPressurePascal(), base.stagePressureDropPascal(),
                    base.specifications().stream().map(s -> s instanceof V3ColumnSpecification.CondenserOutletTemperature
                            ? (V3ColumnSpecification)new V3ColumnSpecification.CondenserOutletTemperature(tc) : s).toList(),
                    base.sideDraws(), base.steamFeeds(), base.pumparounds());
            var gson = new Gson();
            return new V3NeuralSeed(input, com.wormzjl.createcheme.science.material.MaterialRuntime.current().requirePackage(input.packageId()).scientificRevision(),
                    V3CondenserPhaseBranch.valueOf(seed.get("branch").getAsString()),
                    gson.fromJson(seed.get("liquid"), double[][].class), gson.fromJson(seed.get("vapor"), double[][].class),
                    gson.fromJson(seed.get("temperatures"), double[].class), gson.fromJson(seed.get("freeWater"), double[].class),
                    gson.fromJson(seed.get("wetTrays"), boolean[].class));
        }
    }

    @Test void certifiesWetTrayAndHotDryTraysAboveTheirPureWaterBoilingTemperature() throws Exception {
        var seed = boundarySeed();
        var qualification = V3WaterPhaseQualification.assess(seed);
        assertEquals(V3WaterPhaseQualification.Grade.WET_EQUILIBRIUM, qualification.grade());
        assertEquals(1, qualification.wetTrayCount());
        assertEquals(1, seed.freeWater()[1], 1e-6);
        assertTrue(qualification.maximumWetSaturationError() < 1e-8);
    }

    @Test void supersaturationIsNotAQualifiedDryOrWetLabel() throws Exception {
        var seed = boundarySeed();
        double[] t = seed.temperatures(); t[1] -= 5;
        var dry = new V3NeuralSeed(seed.input(), seed.propertyRevision(), seed.branch(), seed.liquid(), seed.vapor(), t,
                new double[t.length], new boolean[t.length]);
        assertEquals(V3WaterPhaseQualification.Grade.DRY_SUPERSATURATED, V3WaterPhaseQualification.assess(dry).grade());
        var invalidWet = new V3NeuralSeed(seed.input(), seed.propertyRevision(), seed.branch(), seed.liquid(), seed.vapor(), t,
                seed.freeWater(), seed.wetTrays());
        assertEquals(V3WaterPhaseQualification.Grade.INVALID, V3WaterPhaseQualification.assess(invalidWet).grade());
    }

    @Test void observerReceivesOnlyTheReleasedCertifiedWetProfile() throws Exception {
        var seed = boundarySeed();
        var captured = new AtomicReference<V3NeuralSeed>();
        V3NeuralInitializer fixed = new V3NeuralInitializer() {
            public String modelId() { return "test-certified-wet-profile"; }
            public Optional<V3NeuralSeed> predict(V3ColumnInput input, V3SolveControl control) { return Optional.of(seed); }
        };
        var outcome = V3ColumnCalculator.calculateWithAcceptedProfile(seed.input(), () -> {},
                new V3InitializationOptions(V3InitializationOptions.Mode.LNN_ONLY, V3InitializationOptions.WetStart.PREDICTED_WET, 16, 10_000),
                fixed, captured::set);
        assertInstanceOf(V3ColumnOutcome.Success.class, outcome, outcome::toString);
        assertNotNull(captured.get());
        assertEquals(V3WaterPhaseQualification.Grade.WET_EQUILIBRIUM, V3WaterPhaseQualification.assess(captured.get()).grade());
        assertTrue(outcome.diagnostics().convergenceEvidence().satisfiesGates());
    }
}
