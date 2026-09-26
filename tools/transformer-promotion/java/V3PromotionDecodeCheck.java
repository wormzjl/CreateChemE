package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;

/**
 * Solver-free decode dump through the production model, for the seed-parity gate.
 *
 * <p>The serializer settings match the neural-budget study's {@code V3BudgetDecodeCheck} exactly, so the
 * emitted seeds are directly comparable to that study's committed decode-seed digests. What differs is
 * where the model comes from: there, a study pipeline manifest naming weights, a decoder rule and a
 * budget; here, {@link V3NeuralModels#bundled()} with nothing to state, because all three are now what
 * the mod ships.</p>
 */
public final class V3PromotionDecodeCheck {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();

    private V3PromotionDecodeCheck() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("output-jsonl input-jsonl");
        Path output = Path.of(args[0]), source = Path.of(args[1]);
        if (Files.exists(output)) throw new IllegalArgumentException("Decode dump already exists");
        V3NeuralInitializer model = V3NeuralModels.bundled();
        if (model == V3NeuralInitializer.UNAVAILABLE)
            throw new IllegalArgumentException("The bundled model did not load; there is nothing to decode");
        int supported = 0, total = 0;
        Files.createDirectories(output.getParent());
        try (var out = Files.newBufferedWriter(output, StandardOpenOption.CREATE_NEW);
             var lines = Files.lines(source)) {
            for (String line : lines.filter(s -> !s.isBlank()).toList()) {
                JsonObject request = JsonParser.parseString(line).getAsJsonObject();
                V3ColumnInput input = V3PromotionEvaluationProbe.input(request.getAsJsonObject("input"));
                var row = new LinkedHashMap<String, Object>();
                row.put("id", request.get("id").getAsString());
                V3NeuralSeed seed = null;
                try { seed = model.predict(input, V3SolveControl.UNBOUNDED).orElse(null); }
                catch (RuntimeException unavailable) { row.put("failure", unavailable.getMessage()); }
                row.put("supported", seed != null);
                row.put("seed", seed);
                out.write(JSON.toJson(row)); out.newLine();
                total++; if (seed != null) supported++;
            }
        }
        System.out.printf("Decoded %d inputs (%d supported) through V3NeuralModels.bundled()%n", total, supported);
    }
}
