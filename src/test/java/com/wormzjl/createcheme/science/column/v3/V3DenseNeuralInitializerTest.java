package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class V3DenseNeuralInitializerTest {
    @Test void bundledModelSeedsTheCurrentFullCaseAndPassesUnchangedAudits() {
        V3ColumnInput input=ColumnCalculatorV3BlockEntity.literatureCduInput();
        var outcome=V3ColumnCalculator.calculate(input,()->{},0,0,
                new V3InitializationOptions(V3InitializationOptions.Mode.LNN_ONLY,V3InitializationOptions.WetStart.AUTO,16,10_000),
                V3NeuralModels.bundled());
        var success=assertInstanceOf(V3ColumnOutcome.Success.class,outcome,()->outcome.toString());
        assertTrue(success.result().acceptanceAudit().accepted());
        assertTrue(success.result().convergenceEvidence().satisfiesGates());
        assertTrue(success.diagnostics().events().getFirst().contains("initializer=LNN;"));
    }

    @Test void coverageRejectsChangedConditionsAndGeometry() {
        var base=ColumnCalculatorV3BlockEntity.literatureCduInput();
        var model=V3NeuralModels.bundled();
        assertTrue(model.predict(base,()->{}).isPresent());
        var changed=new V3ColumnInput(base.schemaVersion(),base.packageId(),base.assayId(),base.componentBasis(),
                base.feedComponentMolarFlowsMolPerSecond(),base.feedTemperatureKelvin()+20,base.stageCount(),base.feedStageNumber(),
                base.topPressurePascal(),base.stagePressureDropPascal(),base.specifications(),base.sideDraws(),base.steamFeeds(),base.pumparounds());
        assertTrue(model.predict(changed,()->{}).isEmpty());
        changed=new V3ColumnInput(base.schemaVersion(),base.packageId(),base.assayId(),base.componentBasis(),
                base.feedComponentMolarFlowsMolPerSecond(),base.feedTemperatureKelvin(),39,base.feedStageNumber(),
                base.topPressurePascal(),base.stagePressureDropPascal(),base.specifications(),base.sideDraws(),base.steamFeeds(),base.pumparounds());
        assertTrue(model.predict(changed,()->{}).isEmpty());
    }

    @Test void malformedWeightsAndScalesAreRejectedAtLoad() throws Exception {
        assertThrows(IllegalArgumentException.class,()->V3DenseNeuralInitializer.read(bytes("{not json")));
        String original;
        try(var in=getClass().getResourceAsStream("/data/createcheme/neural/v3-mvp.json")) {
            original=new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
        var json=JsonParser.parseString(original).getAsJsonObject();
        json.getAsJsonArray("inputScale").set(0,new com.google.gson.JsonPrimitive(0));
        String invalidScale=json.toString();
        assertThrows(IllegalArgumentException.class,()->V3DenseNeuralInitializer.read(bytes(invalidScale)));
        json=JsonParser.parseString(original).getAsJsonObject();
        json.getAsJsonArray("layers").get(0).getAsJsonObject().add("weights",new com.google.gson.JsonArray());
        String invalid=json.toString();
        assertThrows(IllegalArgumentException.class,()->V3DenseNeuralInitializer.read(bytes(invalid)));
    }

    @Test void inferencePollsCallerCancellation() {
        var cancelled=new CancellationException("stop inference");
        assertSame(cancelled,assertThrows(CancellationException.class,()->V3NeuralModels.bundled().predict(
                ColumnCalculatorV3BlockEntity.literatureCduInput(),()->{throw cancelled;})));
    }

    private static ByteArrayInputStream bytes(String value) { return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)); }
}
