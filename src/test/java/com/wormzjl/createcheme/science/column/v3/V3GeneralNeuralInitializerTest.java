package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class V3GeneralNeuralInitializerTest {
    @Test void trainedExperimentalModelCorrectsSevenTraysAndFourPumparoundsWithoutClassicalInitialization() {
        // Frozen case gd-s07-w0-p4-d1-r00. No held-out target was fitted or used to choose the model.
        var base = ColumnCalculatorV3BlockEntity.methaneCduInput();
        var input = new V3ColumnInput(base.schemaVersion(), base.packageId(), base.assayId(), base.componentBasis(),
                new double[] {4.300584236689579, .9240862427156051, 7.987153409622299, 4.347655851953866,
                    14.46158367826537, 13.209318479848802, 20.295117898374304, 137.7605766474092,
                    99.61202621700629, 72.99896151763119, 71.70769821029337, 73.36132157362472,
                    47.064205250652854, 38.51907509770175, 39.54776517781833, 45.31623633905252,
                    29.543129084328818, 19.177746245451754, 5.755941746390144, .8435790479845781},
                743.3800035599858, 7, 7, 221644.71342114633, 642.755428978284,
                List.of(new V3ColumnSpecification.CondenserOutletTemperature(354.6217639729441),
                    new V3ColumnSpecification.OrganicRefluxRatio(4.779280882876468),
                    new V3ColumnSpecification.ReboilerDuty(9646033.29694271)),
                List.of(new V3SideDrawSpec(5, 137.02980499189115)), List.of(),
                List.of(new V3PumparoundSpec(4, 5, -12882591.669633321, V3PumparoundSpec.Split.UNIFORM),
                    new V3PumparoundSpec(4, 6, -14947918.120327517, V3PumparoundSpec.Split.UNIFORM),
                    new V3PumparoundSpec(6, 6, -18929390.53043788, V3PumparoundSpec.Split.UNIFORM),
                    new V3PumparoundSpec(7, 7, -13575020.291918833, V3PumparoundSpec.Split.UNIFORM)));
        var outcome = V3ColumnCalculator.calculate(input, () -> {}, 0, 0,
                new V3InitializationOptions(V3InitializationOptions.Mode.LNN_ONLY, V3InitializationOptions.WetStart.AUTO, 16, 5000),
                V3NeuralModels.forFamily(V3NeuralModels.Family.GENERALIZED_EXPERIMENTAL));
        var success = assertInstanceOf(V3ColumnOutcome.Success.class, outcome);
        assertTrue(success.result().acceptanceAudit().accepted());
        assertTrue(success.result().convergenceEvidence().satisfiesGates());
        assertTrue(success.diagnostics().events().stream().noneMatch(event -> event.contains("CURRENT_BACKUP")));
    }
    @Test void experimentalFamilyIsExplicitAndSeparateFromExistingDefaultExperts() {
        assertSame(V3NeuralModels.bundled(), V3NeuralModels.forFamily(V3NeuralModels.Family.LOCAL_EXPERTS));
        var experimental = V3NeuralModels.forFamily(V3NeuralModels.Family.GENERALIZED_EXPERIMENTAL);
        assertEquals("tjl20-general-stage-v1", experimental.modelId());
        assertNotSame(V3NeuralModels.bundled(), experimental);
        assertSame(experimental, V3NeuralModels.forFamily(V3NeuralModels.Family.GENERALIZED_EXPERIMENTAL));
        assertTrue(experimental.predict(ColumnCalculatorV3BlockEntity.methaneCduInput(), () -> {}).isPresent());
    }
    @Test void sameWeightsProduceVariableGeometrySeedsAndPreserveTerminalConstraints() throws Exception {
        var model = V3GeneralNeuralInitializer.read(bytes(manifest().toString()));
        for (int stages : new int[] {2, 17, 64}) {
            var input = input(stages, 200_000, false);
            var seed = model.predict(input, () -> {}).orElseThrow();
            assertEquals(stages + 2, seed.temperatures().length);
            assertEquals(input, seed.input());
            assertEquals(V3CondenserPhaseBranch.LIQUID_ONLY, seed.branch());
            assertEquals(313.15, seed.temperatures()[0]);
            assertEquals(400, seed.temperatures()[stages + 1]);
            assertEquals(0, Arrays.stream(seed.vapor()[0]).sum());
            for (boolean wet : seed.wetTrays()) assertFalse(wet);
            for (double water : seed.freeWater()) assertEquals(0, water);
        }
    }

    @Test void componentZerosRemainExactAndEveryCompositionFractionIsEncoded() throws Exception {
        var original = input(20, 200_000, false);
        double[] feed = original.feedComponentMolarFlowsMolPerSecond();
        feed[0] = 0;
        var input = new V3ColumnInput(original.schemaVersion(), original.packageId(), original.assayId(), original.componentBasis(),
                feed, original.feedTemperatureKelvin(), original.stageCount(), original.feedStageNumber(), original.topPressurePascal(),
                original.stagePressureDropPascal(), original.specifications());
        var seed = V3GeneralNeuralInitializer.read(bytes(manifest().toString())).predict(input, () -> {}).orElseThrow();
        double[] global = V3GeneralNeuralFeatures.global(input);
        double total = Arrays.stream(feed).sum();
        for (int c = 0; c < feed.length; c++) assertEquals(feed[c] / total, global[17+c], 1e-14);
        for (int n = 0; n < 22; n++) {
            assertEquals(0, seed.liquid()[n][0]); assertEquals(0, seed.vapor()[n][0]);
        }
    }

    @Test void stageFeaturesMatchNativePressureAndDistinguishLocalEquipmentFromItsTotal() {
        var input = input(17, 200_000, true);
        double[][] nodes = V3GeneralNeuralFeatures.nodes(input, V3CondenserPhaseBranch.TWO_PHASE);
        int k = V3GeneralNeuralFeatures.globalWidth(input.componentBasis().componentCount());
        assertEquals(2, nodes[0][k+6]); assertEquals(2, nodes[1][k+6]);
        assertEquals(2.016, nodes[17][k+6], 1e-12); assertEquals(nodes[17][k+6], nodes[18][k+6]);
        assertEquals(0, nodes[1][k+7]); assertTrue(nodes[2][k+7] < 0);
        assertEquals(nodes[2][k+7], nodes[3][k+7]);
        assertTrue(nodes[5][k+10] > 0); assertEquals(0, nodes[4][k+10]);
        assertTrue(nodes[18][k+13] > 0); assertEquals(0, nodes[17][k+13]);
        assertTrue(nodes[18][k+17] > 0);
    }

    @Test void targetRoundTripPreservesActiveFlowsAndWetTopology() {
        var input = input(17, 200_000, true);
        int count = input.stageCount()+2, components = input.componentBasis().componentCount();
        double[][] l = new double[count][components], v = new double[count][components];
        double[] t = filled(count, 400), w = new double[count]; boolean[] wet = new boolean[count];
        t[0] = 313.15;
        for (int n = 0; n < count; n++) for (int c = 0; c < components; c++) { l[n][c] = .001*(n+1)*(c+1); v[n][c] = .002*(n+1)*(c+1); }
        wet[1] = true; w[1] = 1.25;
        var seed = new V3NeuralSeed(input, "test-property", V3CondenserPhaseBranch.TWO_PHASE, l, v, t, w, wet);
        var decoded = V3GeneralNeuralFeatures.decode(input, seed.propertyRevision(), seed.branch(), V3GeneralNeuralFeatures.targets(seed));
        assertArrayEquals(seed.temperatures(), decoded.temperatures());
        assertArrayEquals(wet, decoded.wetTrays()); assertArrayEquals(w, decoded.freeWater(), 1e-12);
        for (int n = 0; n < count; n++) { assertArrayEquals(l[n], decoded.liquid()[n], 1e-12); assertArrayEquals(v[n], decoded.vapor()[n], 1e-12); }
    }

    @Test void coverageRejectsOutsideDomainAndCancellationInterruptsMidInference() throws Exception {
        var document = manifest();
        int globalWidth = V3GeneralNeuralFeatures.globalWidth(input(2, 200_000, false).componentBasis().componentCount());
        double[] lower = filled(globalWidth, -1e6), upper = filled(globalWidth, 1e6); lower[2] = 1; upper[2] = 3;
        document.add("globalMin", new Gson().toJsonTree(lower)); document.add("globalMax", new Gson().toJsonTree(upper));
        var model = V3GeneralNeuralInitializer.read(bytes(document.toString()));
        assertTrue(model.predict(input(64, 300_001, false), () -> {}).isEmpty());
        assertTrue(model.predict(input(64, 100_000, false), () -> {}).isPresent());
        var calls = new AtomicInteger(); var cancelled = new CancellationException("mid-inference");
        assertSame(cancelled, assertThrows(CancellationException.class, () -> model.predict(input(64, 200_000, false), () -> {
            if (calls.incrementAndGet() >= 12) throw cancelled;
        })));
    }

    @Test void malformedArtifactsAndUnboundedPredictionsAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> V3GeneralNeuralInitializer.read(bytes("{bad")));
        var document = manifest(); document.getAsJsonArray("nodeScale").set(0, new com.google.gson.JsonPrimitive(0));
        assertThrows(IllegalArgumentException.class, () -> V3GeneralNeuralInitializer.read(bytes(document.toString())));
        var unbounded = manifest(); unbounded.getAsJsonArray("outputMean").set(0, new com.google.gson.JsonPrimitive(5000));
        assertTrue(V3GeneralNeuralInitializer.read(bytes(unbounded.toString())).predict(input(2, 200_000, false), () -> {}).isEmpty());
    }

    @Test void vaporOnlyBranchRequiresZeroOrganicReflux() throws Exception {
        var document = manifest(); document.add("branchesSeen", new Gson().toJsonTree(new boolean[] {false, false, true}));
        var model = V3GeneralNeuralInitializer.read(bytes(document.toString()));
        var base = input(2, 200_000, false);
        assertTrue(model.predict(base, () -> {}).isEmpty());
        var zeroReflux = new V3ColumnInput(base.schemaVersion(), base.packageId(), base.assayId(), base.componentBasis(),
                base.feedComponentMolarFlowsMolPerSecond(), base.feedTemperatureKelvin(), base.stageCount(), base.feedStageNumber(),
                base.topPressurePascal(), base.stagePressureDropPascal(), List.of(new V3ColumnSpecification.CondenserOutletTemperature(313.15),
                        new V3ColumnSpecification.OrganicRefluxRatio(0), new V3ColumnSpecification.ReboilerDuty(1e6)));
        var seed = model.predict(zeroReflux, () -> {}).orElseThrow();
        assertEquals(V3CondenserPhaseBranch.VAPOR_ONLY, seed.branch());
        assertEquals(0, Arrays.stream(seed.liquid()[0]).sum());
    }

    @Test void compoundDesignConstraintsRejectUntrainedTopologyAndExcessBottomPressure() throws Exception {
        var base = input(64, 290_000, true);
        var document = manifest();
        document.add("formulationRevisions", new Gson().toJsonTree(List.of(V3ColumnCalculator.formulationRevision(base, 0))));
        document.add("designConstraints", new Gson().toJsonTree(Map.of("minimumNodePressurePascal", 100_000,
                "maximumNodePressurePascal", 300_000, "steamAtSumpOnly", true, "pumparoundSplits", List.of("UNIFORM"))));
        var model = V3GeneralNeuralInitializer.read(bytes(document.toString()));
        assertTrue(model.predict(base, () -> {}).isPresent());
        var excessBottomPressure = new V3ColumnInput(base.schemaVersion(), base.packageId(), base.assayId(), base.componentBasis(),
                base.feedComponentMolarFlowsMolPerSecond(), base.feedTemperatureKelvin(), base.stageCount(), base.feedStageNumber(),
                base.topPressurePascal(), 900, base.specifications(), base.sideDraws(), base.steamFeeds(), base.pumparounds());
        assertTrue(model.predict(excessBottomPressure, () -> {}).isEmpty());
        var movedSteam = new V3ColumnInput(base.schemaVersion(), base.packageId(), base.assayId(), base.componentBasis(),
                base.feedComponentMolarFlowsMolPerSecond(), base.feedTemperatureKelvin(), base.stageCount(), base.feedStageNumber(),
                base.topPressurePascal(), base.stagePressureDropPascal(), base.specifications(), base.sideDraws(),
                List.of(new V3SteamFeedSpec(5, 2, 533.15)), base.pumparounds());
        assertTrue(model.predict(movedSteam, () -> {}).isEmpty());
        var changedHeatPlacement = new V3ColumnInput(base.schemaVersion(), base.packageId(), base.assayId(), base.componentBasis(),
                base.feedComponentMolarFlowsMolPerSecond(), base.feedTemperatureKelvin(), base.stageCount(), base.feedStageNumber(),
                base.topPressurePascal(), base.stagePressureDropPascal(), base.specifications(), base.sideDraws(), base.steamFeeds(),
                List.of(new V3PumparoundSpec(2, 3, -1000, V3PumparoundSpec.Split.RETURN_TRAY)));
        assertTrue(model.predict(changedHeatPlacement, () -> {}).isEmpty());
    }

    @Test void malformedCompoundConstraintsAreRejectedAtLoad() {
        var document = manifest();
        document.add("designConstraints", new Gson().toJsonTree(Map.of("minimumNodePressurePascal", 300_000,
                "maximumNodePressurePascal", 100_000, "steamAtSumpOnly", true, "pumparoundSplits", List.of("UNIFORM"))));
        assertThrows(IllegalArgumentException.class, () -> V3GeneralNeuralInitializer.read(bytes(document.toString())));
        document.getAsJsonObject("designConstraints").addProperty("maximumNodePressurePascal", 300_000);
        document.getAsJsonObject("designConstraints").add("pumparoundSplits", new Gson().toJsonTree(List.of("UNKNOWN")));
        assertThrows(IllegalArgumentException.class, () -> V3GeneralNeuralInitializer.read(bytes(document.toString())));
    }

    @Test void inactiveComponentStillRejectsUnboundedRawPrediction() throws Exception {
        var base = input(2, 200_000, false); double[] feed = base.feedComponentMolarFlowsMolPerSecond(); feed[0] = 0;
        var zeroComponent = new V3ColumnInput(base.schemaVersion(), base.packageId(), base.assayId(), base.componentBasis(), feed,
                base.feedTemperatureKelvin(), base.stageCount(), base.feedStageNumber(), base.topPressurePascal(),
                base.stagePressureDropPascal(), base.specifications());
        var document = manifest(); document.getAsJsonArray("outputMean").set(1, new com.google.gson.JsonPrimitive(31));
        var model = V3GeneralNeuralInitializer.read(bytes(document.toString()));
        assertTrue(model.predict(zeroComponent, () -> {}).isEmpty());
    }

    private static V3ColumnInput input(int stages, double pressure, boolean equipment) {
        var base = ColumnCalculatorV3BlockEntity.methaneCduInput();
        return new V3ColumnInput(base.schemaVersion(), base.packageId(), base.assayId(), base.componentBasis(),
                base.feedComponentMolarFlowsMolPerSecond(), base.feedTemperatureKelvin(), stages, Math.max(1, stages/2), pressure, 100,
                List.of(new V3ColumnSpecification.CondenserOutletTemperature(313.15), new V3ColumnSpecification.OrganicRefluxRatio(2),
                        new V3ColumnSpecification.ReboilerDuty(1e6)),
                equipment ? List.of(new V3SideDrawSpec(5, 1)) : List.of(),
                equipment ? List.of(new V3SteamFeedSpec(stages+1, 2, 533.15)) : List.of(),
                equipment ? List.of(new V3PumparoundSpec(2, 3, -1000, V3PumparoundSpec.Split.UNIFORM)) : List.of());
    }

    private static JsonObject manifest() {
        var input = input(2, 200_000, false);
        int c = input.componentBasis().componentCount(), g = V3GeneralNeuralFeatures.globalWidth(c);
        int n = V3GeneralNeuralFeatures.nodeWidth(c), o = V3GeneralNeuralFeatures.outputWidth(c);
        var doc = new java.util.LinkedHashMap<String, Object>();
        doc.put("featureRevision", V3GeneralNeuralFeatures.REVISION); doc.put("modelId", "test-general");
        doc.put("packageId", input.packageId()); doc.put("propertyRevision", "test-property");
        doc.put("components", input.componentBasis().componentIds()); doc.put("minimumStages", 2); doc.put("maximumStages", 64);
        doc.put("formulationRevisions", List.of(V3ColumnCalculator.formulationRevision(input, 0)));
        doc.put("branchesSeen", new boolean[] {true, true, false});
        doc.put("globalMean", new double[g]); doc.put("globalScale", filled(g, 1));
        doc.put("globalMin", filled(g, -1e6)); doc.put("globalMax", filled(g, 1e6));
        doc.put("nodeMean", new double[n]); doc.put("nodeScale", filled(n, 1));
        double[] mean = filled(o, 10); mean[0] = 400; mean[o-1] = 1;
        doc.put("outputMean", mean); doc.put("outputScale", filled(o, 1));
        doc.put("branchLayers", List.of(Map.of("weights", new double[3][g], "bias", new double[] {2, 1, 0}, "activation", "linear")));
        doc.put("nodeLayers", List.of(Map.of("weights", new double[o][n], "bias", new double[o], "activation", "linear")));
        return JsonParser.parseString(new Gson().toJson(doc)).getAsJsonObject();
    }

    private static double[] filled(int count, double value) { double[] a = new double[count]; Arrays.fill(a, value); return a; }
    private static ByteArrayInputStream bytes(String value) { return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)); }
}
