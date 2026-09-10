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

class V3FactorizedNeuralInitializerTest {
    @Test void gen3FamilyIsExplicitAndPublishesOneImmutablePredictorToConcurrentRequests() throws Exception {
        var request = ColumnCalculatorV3BlockEntity.methaneCduInput();
        List<java.util.concurrent.Callable<Published>> calls = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) calls.add(() -> {
            var model = V3NeuralModels.forFamily(V3NeuralModels.Family.GENERALIZED_GEN3_EXPERIMENTAL);
            return new Published(model, model.predict(request, () -> {}).orElseThrow());
        });
        try (var executor = new java.util.concurrent.ThreadPoolExecutor(4, 4, 0, java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(8),
                Thread.ofPlatform().name("v3-gen3-publication-test-", 0).daemon(false).factory(),
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy())) {
            var results = executor.invokeAll(calls);
            Published first = results.getFirst().get();
            assertInstanceOf(V3FactorizedNeuralInitializer.class, first.model());
            assertEquals("tjl20-gen3-factorized-v1", first.model().modelId());
            for (var future : results) {
                Published published = future.get();
                assertSame(first.model(), published.model());
                assertEquals(request, published.seed().input());
                assertArrayEquals(first.seed().temperatures(), published.seed().temperatures());
                assertArrayEquals(first.seed().liquid()[1], published.seed().liquid()[1]);
            }
            first.seed().liquid()[1][1] = Double.NaN;
            var repeated = first.model().predict(request, () -> {}).orElseThrow();
            assertArrayEquals(first.seed().liquid()[1], repeated.liquid()[1]);
            assertSame(V3NeuralModels.bundled(), V3NeuralModels.forFamily(V3NeuralModels.Family.LOCAL_EXPERTS));
            assertNotSame(V3NeuralModels.bundled(), first.model());
            var gen2 = V3NeuralModels.forFamily(V3NeuralModels.Family.GENERALIZED_EXPERIMENTAL);
            assertEquals("tjl20-general-stage-v1", gen2.modelId());
            assertNotSame(gen2, first.model());
        }
    }

    @Test void bundledGen3BytesMatchTheFrozenValidationSelectedArtifact() throws Exception {
        try (var stream = getClass().getResourceAsStream("/data/createcheme/neural/v3-general-gen3-factorized.json")) {
            assertNotNull(stream);
            String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
            assertEquals("19ed50603c2955841a9dd8e5a539aa076b1d8d59a09304b2bc0c613cc520e512", digest);
        }
    }

    @Test void targetRoundTripPreservesTotalsComponentsAndLearnedAbsence() {
        var input = input(17); var teacher = teacher(input);
        var decoded = V3FactorizedNeuralFeatures.decode(input, teacher.propertyRevision(), teacher.branch(),
                V3FactorizedNeuralFeatures.targets(teacher), .02);
        for (int n = 0; n < input.stageCount()+2; n++) {
            assertArrayEquals(teacher.liquid()[n], decoded.liquid()[n], 1e-9);
            assertArrayEquals(teacher.vapor()[n], decoded.vapor()[n], 1e-9);
        }
        assertArrayEquals(teacher.temperatures(), decoded.temperatures());
        assertEquals(0, decoded.liquid()[1][1]); assertEquals(0, decoded.vapor()[2][2]);
    }

    @Test void compositionAndPresenceChangesPreservePredictedPhaseTotals() {
        var input = input(17); var teacher = teacher(input);
        double[][] raw = V3FactorizedNeuralFeatures.targets(teacher);
        int components = input.componentBasis().componentCount();
        raw[5][3+4] += 2; raw[5][5+2*components+5] = -20;
        var decoded = V3FactorizedNeuralFeatures.decode(input, teacher.propertyRevision(), teacher.branch(), raw, .02);
        var noPresence = V3FactorizedNeuralFeatures.decode(input, teacher.propertyRevision(), teacher.branch(), raw, 0);
        assertEquals(Arrays.stream(teacher.liquid()[5]).sum(), Arrays.stream(decoded.liquid()[5]).sum(), 1e-9);
        assertEquals(0, decoded.liquid()[5][5]); assertTrue(noPresence.liquid()[5][5] > 0);
        assertTrue(decoded.liquid()[5][4] > teacher.liquid()[5][4]);
    }

    @Test void boundaryConstraintsAndZeroFeedComponentsRemainExact() {
        var input = input(2); var teacher = teacher(input);
        double[][] raw = V3FactorizedNeuralFeatures.targets(teacher); raw[0][0] = 999;
        var decoded = V3FactorizedNeuralFeatures.decode(input, teacher.propertyRevision(), V3CondenserPhaseBranch.LIQUID_ONLY, raw, .02);
        assertEquals(313.15, decoded.temperatures()[0]); assertEquals(0, Arrays.stream(decoded.vapor()[0]).sum());
        for (int n = 0; n < 4; n++) {
            assertEquals(0, decoded.liquid()[n][0]); assertEquals(0, decoded.vapor()[n][0]);
            assertFalse(decoded.wetTrays()[n]); assertEquals(0, decoded.freeWater()[n]);
        }
        raw[0][3] = Double.NaN;
        assertThrows(IllegalArgumentException.class, () -> V3FactorizedNeuralFeatures.decode(input, "test", V3CondenserPhaseBranch.TWO_PHASE, raw, .02));
    }

    @Test void decoderUsesTheFrozenNativeTraceFloor() {
        assertEquals(V3TruncationSupport.TRACE_FLOOR_FRACTION, V3FactorizedNeuralFeatures.TRACE_FLOOR_FRACTION);
        var input = input(2); var teacher = teacher(input);
        double[][] raw = V3FactorizedNeuralFeatures.targets(teacher);
        raw[1][3+3] = -100;
        var decoded = V3FactorizedNeuralFeatures.decode(input, "test", teacher.branch(), raw, 0);
        assertEquals(0, decoded.liquid()[1][3]);
        assertEquals(Arrays.stream(teacher.liquid()[1]).sum(), Arrays.stream(decoded.liquid()[1]).sum(), 1e-9);
    }

    @Test void runtimeUsesSameBoundedWeightsForTwoAndSixtyFourStages() throws Exception {
        var model = V3FactorizedNeuralInitializer.read(bytes(manifest().toString()));
        for (int stages : new int[] {2, 64}) {
            var input = input(stages); var seed = model.predict(input, () -> {}).orElseThrow();
            assertEquals(stages+2, seed.temperatures().length); assertEquals(input, seed.input());
            double total = Arrays.stream(input.feedComponentMolarFlowsMolPerSecond()).sum();
            assertEquals(2*total, Arrays.stream(seed.liquid()[1]).sum(), 1e-9);
            assertEquals(.5*total, Arrays.stream(seed.vapor()[1]).sum(), 1e-9);
        }
        assertTrue(model.parameterStorageBytes() > 0);
    }

    @Test void incompatibleFloorAndMalformedWeightsFailClosedAndCancellationIsPolled() throws Exception {
        var wrongFloor = manifest(); wrongFloor.addProperty("traceFloorFraction", 1e-8);
        assertThrows(IllegalArgumentException.class, () -> V3FactorizedNeuralInitializer.read(bytes(wrongFloor.toString())));
        var badThreshold = manifest(); badThreshold.addProperty("presenceThreshold", .9);
        assertThrows(IllegalArgumentException.class, () -> V3FactorizedNeuralInitializer.read(bytes(badThreshold.toString())));
        var badScale = manifest(); badScale.getAsJsonArray("outputScale").set(0, new com.google.gson.JsonPrimitive(0));
        assertThrows(IllegalArgumentException.class, () -> V3FactorizedNeuralInitializer.read(bytes(badScale.toString())));
        var model = V3FactorizedNeuralInitializer.read(bytes(manifest().toString()));
        var count = new AtomicInteger(); var cancelled = new CancellationException("stop inference");
        assertSame(cancelled, assertThrows(CancellationException.class, () -> model.predict(input(64), () -> {
            if (count.incrementAndGet() >= 12) throw cancelled;
        })));
    }

    private static V3ColumnInput input(int stages) {
        var base = ColumnCalculatorV3BlockEntity.methaneCduInput();
        double[] feed = base.feedComponentMolarFlowsMolPerSecond(); feed[0] = 0;
        return new V3ColumnInput(base.schemaVersion(), base.packageId(), base.assayId(), base.componentBasis(), feed,
                base.feedTemperatureKelvin(), stages, Math.max(1, stages/2), 200_000, 100,
                List.of(new V3ColumnSpecification.CondenserOutletTemperature(313.15), new V3ColumnSpecification.OrganicRefluxRatio(2),
                        new V3ColumnSpecification.ReboilerDuty(1e6)));
    }

    private static V3NeuralSeed teacher(V3ColumnInput input) {
        int nodes = input.stageCount()+2; double[] feed = input.feedComponentMolarFlowsMolPerSecond();
        double[][] l = new double[nodes][feed.length], v = new double[nodes][feed.length];
        double[] t = filled(nodes, 400); t[0] = 313.15;
        for (int n = 0; n < nodes; n++) for (int c = 0; c < feed.length; c++) { l[n][c] = 2*feed[c]; v[n][c] = .5*feed[c]; }
        l[1][1] = 0; v[2][2] = 0;
        return new V3NeuralSeed(input, "test-property", V3CondenserPhaseBranch.TWO_PHASE, l, v, t, new double[nodes], new boolean[nodes]);
    }

    private static JsonObject manifest() {
        var input = input(2); int c = input.componentBasis().componentCount();
        int g = V3FactorizedNeuralFeatures.globalWidth(c), n = V3FactorizedNeuralFeatures.nodeWidth(c), o = V3FactorizedNeuralFeatures.outputWidth(c);
        var document = new java.util.LinkedHashMap<String, Object>();
        document.put("featureRevision", V3FactorizedNeuralFeatures.REVISION); document.put("modelId", "test-factorized");
        document.put("packageId", input.packageId()); document.put("propertyRevision", "test-property");
        document.put("components", input.componentBasis().componentIds()); document.put("minimumStages", 2); document.put("maximumStages", 64);
        document.put("formulationRevisions", List.of(V3ColumnCalculator.formulationRevision(input, 0)));
        document.put("branchesSeen", new boolean[] {true, true, false}); document.put("traceFloorFraction", 1e-10); document.put("presenceThreshold", .02);
        document.put("globalMean", new double[g]); document.put("globalScale", filled(g, 1));
        document.put("globalMin", filled(g, -1e6)); document.put("globalMax", filled(g, 1e6));
        document.put("nodeMean", new double[n]); document.put("nodeScale", filled(n, 1));
        double[] means = new double[o]; means[0] = 400; means[1] = Math.log1p(2); means[2] = Math.log1p(.5);
        Arrays.fill(means, 5+2*c, means.length, 8);
        document.put("outputMean", means); document.put("outputScale", filled(o, 1));
        document.put("branchLayers", List.of(Map.of("weights", new double[3][g], "bias", new double[] {1, 2, 0}, "activation", "linear")));
        document.put("nodeLayers", List.of(Map.of("weights", new double[o][n], "bias", new double[o], "activation", "linear")));
        return JsonParser.parseString(new Gson().toJson(document)).getAsJsonObject();
    }

    private static double[] filled(int count, double value) { double[] result = new double[count]; Arrays.fill(result, value); return result; }
    private static ByteArrayInputStream bytes(String value) { return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)); }
    private record Published(V3NeuralInitializer model, V3NeuralSeed seed) {}
}
