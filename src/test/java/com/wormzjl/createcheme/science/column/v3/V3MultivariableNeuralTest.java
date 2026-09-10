package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class V3MultivariableNeuralTest {
    @Test void malformedCorrelatedCoverageIsRejectedAtLoad() throws Exception {
        com.google.gson.JsonObject json;
        try (var in = getClass().getResourceAsStream("/data/createcheme/neural/v3-tjl20-dry.json")) {
            json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        json.getAsJsonObject("coverageGuard").addProperty("maximumNearestMeanSquare", -1);
        assertThrows(IllegalArgumentException.class, () -> V3DenseNeuralInitializer.read(
                new java.io.ByteArrayInputStream(json.toString().getBytes(StandardCharsets.UTF_8))));
    }

    @Test void methanePredictorRespondsToFeedEnergyAtTheSameCondenserTemperature() throws Exception {
        var base = ColumnCalculatorV3BlockEntity.methaneCduInput();
        var changed = new V3ColumnInput(base.schemaVersion(), base.packageId(), base.assayId(), base.componentBasis(),
                base.feedComponentMolarFlowsMolPerSecond(), base.feedTemperatureKelvin() + 1, base.stageCount(),
                base.feedStageNumber(), base.topPressurePascal(), base.stagePressureDropPascal(), base.specifications(),
                base.sideDraws(), base.steamFeeds(), base.pumparounds());
        try (var in = getClass().getResourceAsStream("/data/createcheme/neural/v3-tjl20-dry.json")) {
            var model = V3DenseNeuralInitializer.read(in);
            var a = model.predict(base, () -> {}).orElseThrow();
            var b = model.predict(changed, () -> {}).orElseThrow();
            assertEquals(a.temperatures()[0], b.temperatures()[0]);
            assertTrue(Math.abs(a.temperatures()[10] - b.temperatures()[10]) > 1e-4);
        }
    }

    @Test void correlatedGuardRejectsAnUntrainedDutyPatternInsideTheIndividualFeatureBounds() throws Exception {
        var base = ColumnCalculatorV3BlockEntity.methaneCduInput();
        var middle = base.pumparounds().get(1);
        var changed = new V3ColumnInput(base.schemaVersion(), base.packageId(), base.assayId(), base.componentBasis(),
                base.feedComponentMolarFlowsMolPerSecond(), base.feedTemperatureKelvin(), base.stageCount(),
                base.feedStageNumber(), base.topPressurePascal(), base.stagePressureDropPascal(), base.specifications(),
                base.sideDraws(), base.steamFeeds(), List.of(base.pumparounds().get(0),
                        new V3PumparoundSpec(middle.returnTray(), middle.drawTray(), middle.dutyWatts() * 1.005, middle.split()),
                        base.pumparounds().get(2)));
        var resource = "/data/createcheme/neural/v3-tjl20-dry.json";
        try (var in = getClass().getResourceAsStream(resource)) {
            var document = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            double[] features = V3NeuralFeatures.encode(changed);
            for (int i = 0; i < features.length; i++) {
                assertTrue(features[i] >= document.getAsJsonArray("inputMin").get(i).getAsDouble() - 1e-10);
                assertTrue(features[i] <= document.getAsJsonArray("inputMax").get(i).getAsDouble() + 1e-10);
            }
        }
        try (var in = getClass().getResourceAsStream(resource)) {
            assertTrue(V3DenseNeuralInitializer.read(in).predict(changed, () -> {}).isEmpty());
        }
    }

    @Test void newlyTrainedMethaneModelCorrectsThePresetWithoutClassicalBackup() {
        var outcome = V3ColumnCalculator.calculate(ColumnCalculatorV3BlockEntity.methaneCduInput(), () -> {}, 0, 0,
                new V3InitializationOptions(V3InitializationOptions.Mode.LNN_ONLY, V3InitializationOptions.WetStart.AUTO, 16, 10_000),
                V3NeuralModels.bundled());
        assertInstanceOf(V3ColumnOutcome.Success.class, outcome, outcome::toString);
        assertTrue(outcome.diagnostics().events().getFirst().contains("initializer=LNN;"));
        assertTrue(outcome.diagnostics().convergenceEvidence().satisfiesGates());
    }
}
