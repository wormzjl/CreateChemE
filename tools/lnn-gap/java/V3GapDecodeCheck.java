package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Solver-free decode dump of one arm, for the seed-parity gate.
 *
 * <p>The serializer settings match every predecessor decode check, so the emitted seeds are directly
 * comparable to the promotion campaign's committed decode-seed digests. Only two of this study's arms can
 * move a seed at all — the decode-variant arm offers a second one — so the dump publishes the whole ordered
 * candidate list rather than the single prediction, and the parity gate reads its first entry.</p>
 */
public final class V3GapDecodeCheck {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();

    private V3GapDecodeCheck() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("output-jsonl input-jsonl decoder:candidates");
        Path output = Path.of(args[0]), source = Path.of(args[1]);
        if (Files.exists(output)) throw new IllegalArgumentException("Decode dump already exists");
        String[] rule = args[2].split(":", 2);
        var decode = "prune".equals(rule[0]) ? V3FactorizedNeuralFeatures.DecodeOptions.NONE
                : V3FactorizedNeuralFeatures.DecodeOptions.zeroPhaseFloor(Double.parseDouble(rule[0]));
        var candidates = V3AnchorTransformerInitializer.CandidateRule.valueOf(rule[1]);
        V3NeuralInitializer model = decode.equals(V3FactorizedNeuralFeatures.DecodeOptions.zeroPhaseFloor(
                V3NeuralModels.QUALIFIED_ZERO_PHASE_FLOOR_FACTOR))
                && candidates == V3AnchorTransformerInitializer.CandidateRule.SINGLE
                ? V3NeuralModels.bundled() : V3NeuralModels.load(decode, candidates);
        if (model == V3NeuralInitializer.UNAVAILABLE)
            throw new IllegalArgumentException("The bundled model did not load; there is nothing to decode");
        int supported = 0, total = 0, offered = 0;
        Files.createDirectories(output.getParent());
        try (var out = Files.newBufferedWriter(output, StandardOpenOption.CREATE_NEW);
             var lines = Files.lines(source)) {
            for (String line : lines.filter(s -> !s.isBlank()).toList()) {
                JsonObject request = JsonParser.parseString(line).getAsJsonObject();
                V3ColumnInput input = V3GapEvaluationProbe.input(request.getAsJsonObject("input"));
                var row = new LinkedHashMap<String, Object>();
                row.put("id", request.get("id").getAsString());
                List<V3NeuralSeed> seeds = List.of();
                try { seeds = model.candidates(input, V3SolveControl.UNBOUNDED); }
                catch (RuntimeException unavailable) { row.put("failure", unavailable.getMessage()); }
                row.put("supported", !seeds.isEmpty());
                row.put("candidateCount", seeds.size());
                row.put("seed", seeds.isEmpty() ? null : seeds.getFirst());
                // The first candidate is the parity gate and is always published. The rest are published
                // only where they exist, so a single-candidate arm's dump is the size of its predecessor's.
                row.put("alternates", seeds.size() > 1 ? seeds.subList(1, seeds.size()) : null);
                out.write(JSON.toJson(row)); out.newLine();
                total++; offered += seeds.size(); if (!seeds.isEmpty()) supported++;
            }
        }
        System.out.printf("Decoded %d inputs (%d supported, %d seeds offered) under %s%n",
                total, supported, offered, args[2]);
    }
}
