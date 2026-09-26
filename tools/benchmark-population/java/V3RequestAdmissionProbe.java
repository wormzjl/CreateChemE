package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Records the solver's request-only admission verdict for every case in a fixed input population.
 *
 * <p>This probe never solves. It calls {@link V3ColumnCalculator#requestOnlyAdmission} — the very method the
 * production {@code calculate} path calls — so the recorded verdict is production's verdict rather than a
 * second implementation of the same three gates. The only thermodynamics evaluated is the single feed flash
 * the static cooling admission performs on its own; no continuation, no initializer and no Newton step runs,
 * which is why the whole 3,300-case sweep finishes in seconds and needs no worker pool or deadline.</p>
 *
 * <p>Each output row also carries the {@link V3LiquidSupplyScreen} statistic for the case, whether or not it
 * fired, so a population can be re-screened at a different calibrated ratio without re-running Java.</p>
 *
 * <p>Usage: {@code V3RequestAdmissionProbe <out-directory> <inputs.jsonl> <screen-ratio>}</p>
 */
public final class V3RequestAdmissionProbe {
    private static final String REVISION = "benchmark-population-request-admission-v1";
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();

    private V3RequestAdmissionProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("out-directory inputs-jsonl screen-ratio");
        Path directory = Path.of(args[0]);
        Path source = Path.of(args[1]);
        double ratio = V3LiquidSupplyScreen.requireRatio(Double.parseDouble(args[2]));
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
        }
        Files.createDirectories(directory);
        var counts = new LinkedHashMap<String, Integer>();
        int typed = 0;
        long started = System.nanoTime();
        try (var out = Files.newBufferedWriter(directory.resolve("admission.jsonl"), StandardOpenOption.CREATE_NEW)) {
            for (JsonObject request : requests) {
                Map<String, Object> row = evaluate(request, ratio);
                String gate = (String) row.get("gate");
                if (gate != null) {
                    typed++;
                    counts.merge(gate, 1, Integer::sum);
                }
                out.write(JSON.toJson(row));
                out.newLine();
            }
        }
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("revision", REVISION);
        metadata.put("source", source.toString());
        metadata.put("sourceSha256", sha256(source));
        metadata.put("caseCount", requests.size());
        metadata.put("liquidSupplyScreenRatio", ratio);
        metadata.put("productionDefaultRatio", V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO);
        metadata.put("typedInfeasible", typed);
        metadata.put("typedByGate", counts);
        metadata.put("solvesPerformed", 0);
        metadata.put("java", System.getProperty("java.version"));
        metadata.put("elapsedSeconds", (System.nanoTime() - started) / 1e9);
        Files.writeString(directory.resolve("run.json"), JSON.toJson(metadata), StandardOpenOption.CREATE_NEW);
        System.out.printf(Locale.ROOT, "admission %d cases, %d typed %s, %.2fs%n",
                requests.size(), typed, counts, (System.nanoTime() - started) / 1e9);
    }

    private static Map<String, Object> evaluate(JsonObject request, double ratio) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", request.get("id").getAsString());
        V3ColumnInput input;
        try {
            input = V3NeuralMvpProbe.input(request.getAsJsonObject("input"));
        } catch (RuntimeException malformed) {
            // Input validation is not part of the admission; it publishes its own typed outcome. Recording the
            // rejection keeps a malformed population visible instead of silently admitting it.
            row.put("verdict", "INVALID_INPUT");
            row.put("gate", null);
            row.put("detail", malformed.getMessage());
            row.put("solvePath", null);
            row.put("liquidSupplyRatio", null);
            row.put("liquidSupplyLimitingTray", null);
            return row;
        }
        V3ColumnCalculator.RequestAdmission admission;
        try {
            admission = V3ColumnCalculator.requestOnlyAdmission(input, ratio);
        } catch (V3ThermoException | IllegalArgumentException unsupported) {
            row.put("verdict", "PROPERTY_OR_INPUT_REJECTION");
            row.put("gate", null);
            row.put("detail", unsupported.getMessage());
            row.put("solvePath", null);
            row.put("liquidSupplyRatio", null);
            row.put("liquidSupplyLimitingTray", null);
            return row;
        }
        if (admission.typed()) {
            V3ColumnOutcome.Failure failure = admission.failure();
            row.put("verdict", failure.code().name());
            row.put("gate", admission.gate());
            row.put("detail", failure.summary());
            row.put("solvePath", failure.diagnostics().solvePath());
        } else {
            row.put("verdict", "ADMITTED");
            row.put("gate", null);
            row.put("detail", null);
            row.put("solvePath", null);
        }
        V3LiquidSupplyScreen.Verdict screen = V3LiquidSupplyScreen.evaluate(input);
        row.put("liquidSupplyRatio", Double.isFinite(screen.ratio()) ? screen.ratio() : null);
        row.put("liquidSupplyLimitingTray", screen.limitingTray());
        return row;
    }

    private static String sha256(Path path) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
        var text = new StringBuilder();
        for (byte value : digest) text.append(String.format(Locale.ROOT, "%02x", value));
        return text.toString();
    }
}
