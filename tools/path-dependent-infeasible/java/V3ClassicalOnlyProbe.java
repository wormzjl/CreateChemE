package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * Classical-only (CURRENT_ONLY) terminal-status recorder on a fixed input population.
 *
 * <p>Deliberately narrower than {@code V3CapacityEvaluationProbe}: no model, no neural strategies and no
 * per-case profile capture, so it is not a benchmark and its timings are not comparable to a campaign. It
 * exists to answer one question — which failure code and which detail string the classical path publishes
 * for each request — under the same ten-worker concurrency, 30 s request deadline and 4 GiB heap the
 * campaign used, so that the same population can be replayed before and after a solver change.</p>
 *
 * <p>Usage: {@code V3ClassicalOnlyProbe <out-directory> <inputs.jsonl> <workers> <deadline-seconds>}</p>
 */
public final class V3ClassicalOnlyProbe {
    private static final String REVISION = "path-dependent-infeasible-classical-v1";
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();

    private V3ClassicalOnlyProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("out-directory inputs-jsonl workers deadline-seconds");
        Path directory = Path.of(args[0]);
        Path source = Path.of(args[1]);
        int workers = Integer.parseInt(args[2]);
        long deadlineMillis = Math.round(Double.parseDouble(args[3]) * 1000);
        if (workers != 10 || deadlineMillis != 30_000)
            throw new IllegalArgumentException("This probe replays the campaign's ten workers and 30 s requests");
        if (Files.exists(directory)) throw new IllegalArgumentException("Output already exists; never overwrite a run");
        List<JsonObject> requests;
        try (var lines = Files.lines(source)) {
            requests = lines.filter(line -> !line.isBlank())
                    .map(line -> JsonParser.parseString(line).getAsJsonObject()).toList();
        }
        if (requests.isEmpty()) throw new IllegalArgumentException("Empty population");
        Set<String> ids = new HashSet<>();
        for (JsonObject request : requests) {
            if (!ids.add(request.get("id").getAsString())) throw new IllegalArgumentException("Duplicate case ID");
            V3NeuralMvpProbe.input(request.getAsJsonObject("input"));
        }
        Files.createDirectories(directory);
        var tasks = new ArrayList<java.util.concurrent.Callable<Map<String, Object>>>();
        for (JsonObject request : requests) tasks.add(() -> evaluate(request, deadlineMillis));
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("revision", REVISION);
        metadata.put("source", source.toString());
        metadata.put("sourceSha256", sha256(source));
        metadata.put("caseCount", requests.size());
        metadata.put("workers", workers);
        metadata.put("deadlineMillis", deadlineMillis);
        metadata.put("strategies", List.of("current"));
        metadata.put("java", System.getProperty("java.version"));
        metadata.put("maximumHeapBytes", Runtime.getRuntime().maxMemory());
        metadata.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        metadata.put("timingScope", "ten-worker concurrent load; status recorder, not a benchmark");
        int[] finished = {0};
        long started = System.nanoTime();
        V3BoundedEvaluation.Summary scheduling = null;
        try (var out = Files.newBufferedWriter(directory.resolve("classical.jsonl"), StandardOpenOption.CREATE_NEW)) {
            scheduling = V3BoundedEvaluation.run(tasks, workers, (result, queueWait) -> {
                result.put("queueWaitMillis", queueWait);
                out.write(JSON.toJson(result));
                out.newLine();
                out.flush();
                finished[0]++;
                if (finished[0] <= 5 || finished[0] % 25 == 0 || finished[0] == requests.size())
                    System.out.printf(Locale.ROOT, "classical %d/%d id=%s status=%s elapsed=%.1fs%n",
                            finished[0], requests.size(), result.get("id"), result.get("status"),
                            (System.nanoTime() - started) / 1e9);
            });
        } finally {
            metadata.put("completed", finished[0]);
            metadata.put("complete", scheduling != null && scheduling.terminated() && finished[0] == requests.size());
            metadata.put("scheduling", scheduling);
            metadata.put("elapsedSeconds", (System.nanoTime() - started) / 1e9);
            Files.writeString(directory.resolve("run.json"), JSON.toJson(metadata), StandardOpenOption.CREATE_NEW);
        }
    }

    private static Map<String, Object> evaluate(JsonObject request, long deadlineMillis) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", request.get("id").getAsString());
        V3ColumnInput input = V3NeuralMvpProbe.input(request.getAsJsonObject("input"));
        long start = System.nanoTime();
        try {
            V3ColumnOutcome outcome = V3ColumnCalculator.calculateWithAcceptedProfile(
                    input, deadline(start, deadlineMillis), profile -> { });
            row.put("success", outcome.isSuccess());
            V3SolverDiagnostics diagnostics = outcome.diagnostics();
            row.put("solvePath", diagnostics.solvePath());
            row.put("newtonIterations", diagnostics.newtonIterations());
            row.put("maximumScaledResidual", diagnostics.maximumScaledResidual());
            row.put("events", diagnostics.events());
            if (outcome instanceof V3ColumnOutcome.Success success) {
                row.put("status", "ACCEPTED");
                row.put("failure", null);
                row.put("advisoryEvidence", success.result().acceptanceAudit().advisoryEvidence());
                row.put("formulationRevision", success.result().formulationRevision());
            } else {
                var failure = (V3ColumnOutcome.Failure) outcome;
                row.put("status", failure.code().name());
                row.put("failure", failure.summary());
                row.put("advisoryEvidence", failure.diagnostics().acceptanceAudit().advisoryEvidence());
                row.put("formulationRevision", null);
            }
        } catch (CancellationException cancelled) {
            row.put("success", false);
            row.put("status", Thread.currentThread().isInterrupted() ? "CANCELLED" : "DEADLINE_EXCEEDED");
            row.put("failure", cancelled.getMessage());
        } catch (V3ThermoException | IllegalArgumentException unsupported) {
            row.put("success", false);
            row.put("status", "PROPERTY_OR_INPUT_REJECTION");
            row.put("failure", unsupported.getMessage());
        }
        row.put("ms", (System.nanoTime() - start) / 1e6);
        return row;
    }

    private static V3SolveControl deadline(long start, long deadlineMillis) {
        return () -> {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("interrupted");
            if (System.nanoTime() - start >= deadlineMillis * 1_000_000L)
                throw new CancellationException("request deadline exceeded");
        };
    }

    private static String sha256(Path path) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
        var text = new StringBuilder();
        for (byte value : digest) text.append(String.format(Locale.ROOT, "%02x", value));
        return text.toString();
    }
}
