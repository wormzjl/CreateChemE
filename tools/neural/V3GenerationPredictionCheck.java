package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** TRAIN-only shared-model check using the existing owned ten-worker scheduler. */
public final class V3GenerationPredictionCheck {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static final int WORKERS = 10;
    private static final int CALLS = 40;

    private V3GenerationPredictionCheck() {}

    private record Prediction(int fixture, String value, boolean synchronizedBatch) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("model-json train-fixture-jsonl output-json");
        Path modelPath = Path.of(args[0]), fixturePath = Path.of(args[1]), output = Path.of(args[2]);
        if (Files.exists(output)) throw new IllegalArgumentException("Check output already exists");
        List<JsonObject> fixtures;
        try (var lines = Files.lines(fixturePath)) {
            fixtures = lines.filter(s -> !s.isBlank()).map(s -> JsonParser.parseString(s).getAsJsonObject()).toList();
        }
        if (fixtures.size() != WORKERS || fixtures.stream().map(r -> r.get("id").getAsString()).distinct().count() != WORKERS)
            throw new IllegalArgumentException("Expected ten distinct TRAIN fixtures");
        var inputs = new ArrayList<V3ColumnInput>();
        for (var row : fixtures) {
            if (!"train".equals(row.get("split").getAsString())) throw new IllegalArgumentException("TRAIN fixtures only");
            inputs.add(V3NeuralMvpProbe.input(row.getAsJsonObject("input")));
        }
        V3NeuralInitializer model = V3CandidateModels.read(modelPath);
        var expected = new ArrayList<String>();
        var supportedFixtures = new ArrayList<Integer>();
        for (int index = 0; index < inputs.size(); index++) {
            var value = model.predict(inputs.get(index), control());
            expected.add(JSON.toJson(value.orElse(null)));
            if (value.isPresent()) supportedFixtures.add(index);
        }
        if (supportedFixtures.isEmpty()) throw new IllegalStateException("No supported TRAIN fixture");
        var entered = new CountDownLatch(WORKERS);
        var calls = new ArrayList<Callable<Prediction>>();
        for (int index = 0; index < CALLS; index++) {
            int request = index, fixture = supportedFixtures.get(index % supportedFixtures.size());
            calls.add(() -> {
                V3SolveControl deadline = control();
                int[] checkpoints = {0};
                V3SolveControl synchronizedControl = () -> {
                    deadline.checkpoint();
                    // The second checkpoint is inside coverage/network work, after
                    // the predictor's initial cancellation and structural checks.
                    if (request < WORKERS && ++checkpoints[0] == 2) {
                        entered.countDown();
                        try {
                            if (!entered.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Ten supported predictions did not enter");
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new CancellationException("Prediction barrier interrupted");
                        }
                    }
                };
                var value = model.predict(inputs.get(fixture), synchronizedControl)
                        .orElseThrow(() -> new IllegalStateException("Supported TRAIN prediction became unavailable"));
                if (request < WORKERS && checkpoints[0] < 2) throw new IllegalStateException("Prediction did not reach internal barrier");
                return new Prediction(fixture, JSON.toJson(value), request < WORKERS);
            });
        }
        int[] checked = {0}, synchronizedPredictions = {0};
        var scheduling = V3BoundedEvaluation.run(calls, WORKERS, (prediction, ignoredQueueWait) -> {
            if (!expected.get(prediction.fixture()).equals(prediction.value()))
                throw new IllegalStateException("Serial/parallel prediction mismatch");
            checked[0]++;
            if (prediction.synchronizedBatch()) synchronizedPredictions[0]++;
        });
        if (checked[0] != CALLS || scheduling.maximumActive() != WORKERS
                || synchronizedPredictions[0] != WORKERS || entered.getCount() != 0
                || scheduling.distinctWorkerThreads() != WORKERS || !scheduling.terminated())
            throw new IllegalStateException("Incomplete owned-worker check");
        boolean cancelled = false;
        try { model.predict(inputs.get(supportedFixtures.getFirst()), () -> { throw new CancellationException("injected check"); }); }
        catch (CancellationException expectedCancellation) { cancelled = true; }
        if (!cancelled) throw new IllegalStateException("Prediction ignored cancellation");
        for (int index = 0; index < inputs.size(); index++) {
            if (!expected.get(index).equals(JSON.toJson(model.predict(inputs.get(index), control()).orElse(null))))
                throw new IllegalStateException("Model state changed after parallel calls or cancellation");
        }
        var report = new LinkedHashMap<String, Object>();
        report.put("passed", true); report.put("modelSha256", sha256(modelPath));
        report.put("fixturesSha256", sha256(fixturePath)); report.put("trainingFixtures", fixtures.size());
        report.put("supportedFixtures", supportedFixtures.size()); report.put("parallelPredictions", CALLS);
        report.put("supportedParallelPredictions", CALLS); report.put("synchronizedSupportedPredictions", WORKERS);
        report.put("barrierInsidePrediction", true); report.put("usedFixtureIndices", supportedFixtures);
        report.put("serialParallelBitIdentical", true); report.put("cancellationPassed", true);
        report.put("subsequentPredictionsUnchanged", true); report.put("scheduling", scheduling);
        report.put("java", System.getProperty("java.version"));
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, JSON.toJson(report), StandardOpenOption.CREATE_NEW);
        System.out.println("Generation prediction check passed: 40 predictions, ten workers, model=" + model.modelId());
    }

    private static V3SolveControl control() {
        long start = System.nanoTime();
        return () -> {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() - start >= TimeUnit.SECONDS.toNanos(30))
                throw new CancellationException("Prediction check interrupted or timed out");
        };
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
