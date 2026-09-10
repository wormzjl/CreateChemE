package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class V3NearestProfileInitializerTest {
    private static final Gson JSON = new Gson();

    @Test void exactMatchKeepsTheReferenceAndReturnedArraysCannotMutateTheLibrary() throws Exception {
        V3ColumnInput input = input(4, 3, 650, false);
        JsonObject document = manifest(3, input);
        V3NearestProfileInitializer model = read(document);
        V3NeuralSeed first = model.predict(input, () -> {}).orElseThrow();
        assertEquals(1, model.referenceCount());
        assertEquals("test-nearest", model.modelId());
        assertTrue(model.parameterStorageBytes() > 6 * 40 * Double.BYTES);
        assertEquals(4 * input.feedComponentMolarFlowsMolPerSecond()[2], first.liquid()[3][2]);
        double[][] changed = first.liquid(); changed[3][2] = 999999;
        document.getAsJsonArray("references").get(0).getAsJsonObject().getAsJsonArray("temperatures").set(1, JSON.toJsonTree(1000));
        V3NeuralSeed again = model.predict(input, () -> {}).orElseThrow();
        assertArrayEquals(first.liquid()[3], again.liquid()[3]);
        assertArrayEquals(first.temperatures(), again.temperatures());
    }

    @Test void feedAlignmentPreventsUpstreamBulkSmearingAndScalesEveryComponentEqually() throws Exception {
        V3ColumnInput source = input(4, 3, 650, false), target = input(8, 6, 650, false);
        double[] feed = target.feedComponentMolarFlowsMolPerSecond();
        for (int component = 0; component < feed.length; component++) feed[component] *= 2;
        feed[0] = 0;
        target = withFeed(target, feed);
        JsonObject document = manifest(1, source);
        JsonObject reference = document.getAsJsonArray("references").get(0).getAsJsonObject();
        double[] spike = source.feedComponentMolarFlowsMolPerSecond();
        for (int component = 0; component < spike.length; component++) spike[component] *= 100;
        reference.getAsJsonArray("liquid").set(3, JSON.toJsonTree(spike));
        V3NeuralSeed seed = read(document).predict(target, () -> {}).orElseThrow();
        assertEquals(10, seed.liquid().length);
        for (int node = 0; node < 10; node++) assertEquals(0, seed.liquid()[node][0]);
        for (int component = 1; component < feed.length; component++) {
            assertEquals(feed[component], seed.liquid()[0][component]);
            assertEquals(6 * feed[component], seed.liquid()[9][component]);
            assertEquals(100 * feed[component], seed.liquid()[6][component]);
            assertEquals(3 * feed[component], seed.liquid()[5][component]);
        }
        assertEquals(313.15, seed.temperatures()[0]);
        assertEquals(3, V3NearestProfileInitializer.sourcePosition(6, 4, 3, 8, 6));
        assertEquals(2, V3NearestProfileInitializer.sourcePosition(5, 4, 3, 8, 6));
    }

    @Test void neighborAverageNeverCrossesCondenserBranches() throws Exception {
        V3ColumnInput a = input(4, 3, 600, false), b = input(4, 3, 610, false), c = input(4, 3, 620, false);
        JsonObject document = manifest(3, a, b, c);
        setInnerTemperature(document, 0, 400); setInnerTemperature(document, 1, 500); setInnerTemperature(document, 2, 1000);
        JsonObject otherBranch = document.getAsJsonArray("references").get(2).getAsJsonObject();
        otherBranch.addProperty("branch", "LIQUID_ONLY");
        otherBranch.getAsJsonArray("vapor").set(0, JSON.toJsonTree(new double[20]));
        V3NeuralSeed seed = read(document).predict(input(4, 3, 608, false), () -> {}).orElseThrow();
        assertEquals(V3CondenserPhaseBranch.TWO_PHASE, seed.branch());
        assertTrue(seed.temperatures()[1] > 400 && seed.temperatures()[1] < 500);
    }

    @Test void wetTopologyIsKeptSeparateAndFreeWaterScalesWithAuthoredSteam() throws Exception {
        V3ColumnInput a = input(4, 3, 600, true), b = input(4, 3, 620, true);
        JsonObject document = manifest(3, a, b);
        JsonObject wet = document.getAsJsonArray("references").get(0).getAsJsonObject();
        wet.getAsJsonArray("wetTrays").set(1, JSON.toJsonTree(true));
        wet.getAsJsonArray("freeWater").set(1, JSON.toJsonTree(.4));
        V3ColumnInput base = input(4, 3, 600.1, true);
        V3ColumnInput target = new V3ColumnInput(base.schemaVersion(), base.packageId(), base.assayId(), base.componentBasis(),
                base.feedComponentMolarFlowsMolPerSecond(), base.feedTemperatureKelvin(), base.stageCount(), base.feedStageNumber(),
                base.topPressurePascal(), base.stagePressureDropPascal(), base.specifications(), List.of(),
                List.of(new V3SteamFeedSpec(base.stageCount()+1, 4, 533.15)), List.of());
        V3NeuralSeed seed = read(document).predict(target, () -> {}).orElseThrow();
        assertTrue(seed.wetTrays()[1]); assertEquals(.8, seed.freeWater()[1], 1e-12);
        assertFalse(seed.wetTrays()[0]); assertFalse(seed.wetTrays()[5]);
        assertEquals(0, seed.freeWater()[0]); assertEquals(0, seed.freeWater()[5]);
    }

    @Test void incompatibleSteamEquipmentAndNewComponentsAreDeclined() throws Exception {
        V3ColumnInput source = input(4, 3, 650, false);
        V3NearestProfileInitializer model = read(manifest(1, source));
        assertTrue(model.predict(input(4, 3, 650, true), () -> {}).isEmpty());
        V3ColumnInput drawn = new V3ColumnInput(source.schemaVersion(), source.packageId(), source.assayId(), source.componentBasis(),
                source.feedComponentMolarFlowsMolPerSecond(), source.feedTemperatureKelvin(), source.stageCount(), source.feedStageNumber(),
                source.topPressurePascal(), source.stagePressureDropPascal(), source.specifications(), List.of(new V3SideDrawSpec(2, 1)));
        assertTrue(model.predict(drawn, () -> {}).isEmpty());
        double[] feed = source.feedComponentMolarFlowsMolPerSecond(); feed[0] = 0;
        V3NearestProfileInitializer zeroSource = read(manifest(1, withFeed(source, feed)));
        assertTrue(zeroSource.predict(source, () -> {}).isEmpty());
    }

    @Test void firstTrayFeedReferenceCannotInventAnUpstreamSection() throws Exception {
        V3NearestProfileInitializer model = read(manifest(1, input(2, 1, 650, false)));
        assertTrue(model.predict(input(4, 3, 650, false), () -> {}).isEmpty());
    }

    @Test void everyReferenceMustBeQualifiedAndFromTheTrainingFold() {
        for (String split : List.of("validation", "test")) {
            JsonObject document = manifest(1, input(4, 3, 650, false));
            document.getAsJsonArray("references").get(0).getAsJsonObject().addProperty("sourceSplit", split);
            assertThrows(IllegalArgumentException.class, () -> read(document));
        }
        JsonObject document = manifest(1, input(4, 3, 650, false));
        document.getAsJsonArray("references").get(0).getAsJsonObject().addProperty("equilibriumQualified", false);
        assertThrows(IllegalArgumentException.class, () -> read(document));
    }

    @Test void malformedReferencesAndLibraryCountOverflowAreRejected() {
        JsonObject document = manifest(1, input(4, 3, 650, false));
        JsonArray oversized = new JsonArray();
        for (int index = 0; index < 2049; index++) oversized.add(JsonNull.INSTANCE);
        document.add("references", oversized);
        assertThrows(IllegalArgumentException.class, () -> read(document));
        JsonObject unknown = manifest(1, input(4, 3, 650, false)); unknown.addProperty("modelType", "unrelated");
        assertThrows(IllegalArgumentException.class, () -> read(unknown));
        JsonObject invalid = manifest(1, input(4, 3, 650, false));
        invalid.getAsJsonArray("references").get(0).getAsJsonObject().getAsJsonArray("freeWater").set(1, JSON.toJsonTree(1));
        assertThrows(IllegalArgumentException.class, () -> read(invalid));
    }

    @Test void cancellationPropagatesDuringReferenceSearch() throws Exception {
        V3NearestProfileInitializer model = read(manifest(1,
                input(4, 3, 600, false), input(4, 3, 610, false), input(4, 3, 620, false)));
        AtomicInteger count = new AtomicInteger();
        CancellationException cancelled = new CancellationException("test reference search cancellation");
        assertSame(cancelled, assertThrows(CancellationException.class, () -> model.predict(input(4, 3, 608, false), () -> {
            if (count.incrementAndGet() == 4) throw cancelled;
        })));
    }

    private static V3ColumnInput input(int stages, int feedStage, double temperature, boolean steam) {
        List<String> components = new ArrayList<>(); double[] feed = new double[20];
        for (int index = 0; index < 20; index++) { components.add("c"+index); feed[index] = index+1; }
        return new V3ColumnInput(1, "test:nearest", "test:nearest", new V3ComponentBasis(components), feed, temperature,
                stages, feedStage, 200_000, 100, List.of(new V3ColumnSpecification.CondenserOutletTemperature(313.15),
                        new V3ColumnSpecification.OrganicRefluxRatio(2), new V3ColumnSpecification.ReboilerDuty(1e6)), List.of(),
                steam ? List.of(new V3SteamFeedSpec(stages+1, 2, 533.15)) : List.of(), List.of());
    }

    private static V3ColumnInput withFeed(V3ColumnInput base, double[] feed) {
        return new V3ColumnInput(base.schemaVersion(), base.packageId(), base.assayId(), base.componentBasis(), feed,
                base.feedTemperatureKelvin(), base.stageCount(), base.feedStageNumber(), base.topPressurePascal(),
                base.stagePressureDropPascal(), base.specifications(), base.sideDraws(), base.steamFeeds(), base.pumparounds());
    }

    private static JsonObject manifest(int neighbors, V3ColumnInput... inputs) {
        int width = V3GeneralNeuralFeatures.globalWidth(20);
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("modelType", V3NearestProfileInitializer.MODEL_TYPE); model.put("featureRevision", V3GeneralNeuralFeatures.REVISION);
        model.put("transferRevision", V3NearestProfileInitializer.TRANSFER_REVISION); model.put("modelId", "test-nearest");
        model.put("packageId", "test:nearest"); model.put("propertyRevision", "test-properties");
        model.put("components", inputs[0].componentBasis().componentIds());
        model.put("formulationRevisions", List.of(V3ColumnCalculator.formulationRevision(inputs[0], 0),
                V3ColumnCalculator.formulationRevision(input(4, 3, 650, true), 0)));
        model.put("minimumStages", 2); model.put("maximumStages", 64); model.put("neighbors", neighbors);
        model.put("cohortPolicy", "steam-equipment-counts"); model.put("maximumMeanSquareDistance", 100.0);
        model.put("globalMean", new double[width]); model.put("globalScale", filled(width, 1));
        model.put("globalMin", filled(width, -1e6)); model.put("globalMax", filled(width, 1e6)); model.put("featureWeights", filled(width, 1));
        model.put("designConstraints", Map.of("minimumNodePressurePascal", 100000, "maximumNodePressurePascal", 300000,
                "steamAtSumpOnly", true, "pumparoundSplits", List.of("UNIFORM", "RETURN_TRAY")));
        List<Map<String, Object>> references = new ArrayList<>();
        for (int index = 0; index < inputs.length; index++) {
            V3ColumnInput input = inputs[index]; int nodes = input.stageCount()+2;
            double[] feed = input.feedComponentMolarFlowsMolPerSecond();
            double[][] liquid = new double[nodes][20], vapor = new double[nodes][20];
            for (int node = 0; node < nodes; node++) for (int component = 0; component < 20; component++) {
                liquid[node][component] = feed[component]*(node+1); vapor[node][component] = liquid[node][component]/2;
            }
            double[] temperature = filled(nodes, 500); temperature[0] = 313.15;
            Map<String, Object> reference = new LinkedHashMap<>();
            reference.put("id", "ref-"+index); reference.put("inputSha256", String.format("%064x", index+1));
            reference.put("sourceSplit", "train"); reference.put("equilibriumQualified", true);
            reference.put("stageCount", input.stageCount()); reference.put("feedStageNumber", input.feedStageNumber());
            reference.put("steamEnabled", !input.steamFeeds().isEmpty()); reference.put("paCount", 0); reference.put("sideDrawCount", 0);
            reference.put("totalSteamFlow", input.steamFeeds().isEmpty() ? 0 : 2); reference.put("branch", "TWO_PHASE");
            reference.put("normalizedGlobal", V3GeneralNeuralFeatures.global(input)); reference.put("componentFeed", feed);
            reference.put("temperatures", temperature); reference.put("freeWater", new double[nodes]); reference.put("wetTrays", new boolean[nodes]);
            reference.put("liquid", liquid); reference.put("vapor", vapor); references.add(reference);
        }
        model.put("references", references);
        return JSON.toJsonTree(model).getAsJsonObject();
    }

    private static void setInnerTemperature(JsonObject document, int index, double temperature) {
        JsonArray values = document.getAsJsonArray("references").get(index).getAsJsonObject().getAsJsonArray("temperatures");
        for (int node = 1; node < values.size(); node++) values.set(node, JSON.toJsonTree(temperature));
    }

    private static V3NearestProfileInitializer read(JsonObject model) throws Exception {
        return V3NearestProfileInitializer.read(new ByteArrayInputStream(model.toString().getBytes(StandardCharsets.UTF_8)));
    }
    private static double[] filled(int width, double value) { double[] result = new double[width]; Arrays.fill(result, value); return result; }
}
