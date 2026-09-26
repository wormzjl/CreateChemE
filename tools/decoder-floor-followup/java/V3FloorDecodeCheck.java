package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.*;
import java.nio.file.*;
import java.util.LinkedHashMap;

/**
 * Solver-free decode dump: the complete pipeline seed for every evaluation input.
 *
 * <p>This is the campaign's parity gate. The default variant must reproduce the archived F0 seeds of
 * the predecessor campaign exactly, which proves that the rebuilt core, the derived initializer and
 * the added decode option leave the production decode untouched before any solver time is spent. The
 * serializer settings match the evaluation probe so the emitted seeds are directly comparable.</p>
 */
public final class V3FloorDecodeCheck {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();

    private V3FloorDecodeCheck() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("output-jsonl input-jsonl pipeline");
        Path output = Path.of(args[0]), source = Path.of(args[1]), modelPath = Path.of(args[2]);
        if (Files.exists(output)) throw new IllegalArgumentException("Decode dump already exists");
        V3NeuralInitializer model = V3FloorModels.read(modelPath);
        int supported = 0, total = 0;
        Files.createDirectories(output.getParent());
        try (var out = Files.newBufferedWriter(output, StandardOpenOption.CREATE_NEW);
             var lines = Files.lines(source)) {
            for (String line : lines.filter(s -> !s.isBlank()).toList()) {
                JsonObject request = JsonParser.parseString(line).getAsJsonObject();
                V3ColumnInput input = V3NeuralMvpProbe.input(request.getAsJsonObject("input"));
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
        System.out.printf("Decoded %d inputs (%d supported) with pipeline %s%n", total, supported, modelPath);
    }
}
