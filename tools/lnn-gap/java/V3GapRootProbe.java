package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/**
 * Does a recovered solution sit on the root the classical route finds?
 *
 * <p>The campaign journals record whether a request was strict, not which solution it reached, so the
 * question the ramp handoff raises — it publishes an audited solution of the authored request, but is it
 * <em>the same</em> solution — is not answerable from them. This runs the two routes over a named handful of
 * cases with the accepted-profile observer attached and publishes the two profiles' node temperatures and
 * phase totals, so the comparison is arithmetic on published numbers rather than an argument.</p>
 *
 * <p>It is a side probe, not a campaign: it is compiled into its own output directory against the campaign's
 * own native core, it runs single-threaded over a handful of ids, and it publishes no strict count and no
 * timing anyone should read as a benchmark.</p>
 */
public final class V3GapRootProbe {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();

    private V3GapRootProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("output-json input-jsonl comma-separated-ids");
        Path output = Path.of(args[0]), source = Path.of(args[1]);
        var wanted = new LinkedHashSet<>(Arrays.asList(args[2].split(",")));
        var model = V3NeuralModels.bundled();
        if (model == V3NeuralInitializer.UNAVAILABLE) throw new IllegalArgumentException("No bundled model");
        var rows = new ArrayList<Map<String, Object>>();
        try (var lines = Files.lines(source)) {
            for (String line : lines.filter(s -> !s.isBlank()).toList()) {
                JsonObject request = JsonParser.parseString(line).getAsJsonObject();
                String id = request.get("id").getAsString();
                if (!wanted.contains(id)) continue;
                V3ColumnInput input = V3GapEvaluationProbe.input(request.getAsJsonObject("input"));
                var row = new LinkedHashMap<String, Object>();
                row.put("id", id);
                V3NeuralSeed classical = profile(input, null, V3InitializationOptions.CURRENT);
                V3NeuralSeed recovered = profile(input, model, new V3InitializationOptions(
                        V3InitializationOptions.Mode.LNN_ONLY, V3InitializationOptions.WetStart.AUTO, 16, 2_000,
                        V3InitializationOptions.Correction.PROGRESS, V3InitializationOptions.Recovery.RAMP_HANDOFF));
                row.put("classicalAccepted", classical != null);
                row.put("recoveredAccepted", recovered != null);
                if (classical != null && recovered != null) {
                    row.put("comparison", compare(classical, recovered));
                }
                rows.add(row);
                System.out.printf(Locale.ROOT, "%s classical=%b recovered=%b%n", id, classical != null, recovered != null);
            }
        }
        Files.createDirectories(output.getParent());
        Files.writeString(output, JSON.toJson(Map.of(
                "revision", "lnn-gap-root-probe-v1",
                "note", "Single-threaded side probe over named ids; no strict count and no timing here is a benchmark.",
                "cases", rows)), StandardOpenOption.CREATE_NEW);
    }

    /** The accepted profile of one route, or null when that route did not publish an accepted result. */
    private static V3NeuralSeed profile(V3ColumnInput input, V3NeuralInitializer model,
            V3InitializationOptions options) {
        V3NeuralSeed[] accepted = {null};
        try {
            var outcome = model == null
                    ? V3ColumnCalculator.calculateWithAcceptedProfile(input, () -> {}, seed -> accepted[0] = seed)
                    : V3ColumnCalculator.calculateWithAcceptedProfile(input, () -> {}, options, model,
                            seed -> accepted[0] = seed);
            return outcome.isSuccess() ? accepted[0] : null;
        } catch (RuntimeException | Error unavailable) {
            return null;
        }
    }

    /**
     * How far apart two accepted profiles of the same request are.
     *
     * <p>Node temperatures are compared in kelvin and the per-node phase totals relatively, against the
     * larger of the two so an empty phase cannot manufacture a ratio. Two states of the same root agree to
     * roughly the closure tolerance; two different roots do not agree at all, and the maximum is where that
     * shows.</p>
     */
    private static Map<String, Object> compare(V3NeuralSeed left, V3NeuralSeed right) {
        double[] a = left.temperatures(), b = right.temperatures();
        int nodes = Math.min(a.length, b.length);
        double maximumTemperature = 0, maximumPhase = 0;
        for (int n = 0; n < nodes; n++) {
            maximumTemperature = Math.max(maximumTemperature, Math.abs(a[n] - b[n]));
            maximumPhase = Math.max(maximumPhase, relative(total(left.liquid()[n]), total(right.liquid()[n])));
            maximumPhase = Math.max(maximumPhase, relative(total(left.vapor()[n]), total(right.vapor()[n])));
        }
        return Map.of("nodes", nodes,
                "sameNodeCount", a.length == b.length,
                "maximumTemperatureDifferenceKelvin", maximumTemperature,
                "maximumRelativePhaseTotalDifference", maximumPhase,
                "classicalCondenserLiquidMolPerSecond", total(left.liquid()[0]),
                "recoveredCondenserLiquidMolPerSecond", total(right.liquid()[0]),
                // The tolerance the certificate guarantees is 1e-8 on the residual, so agreement well inside
                // 1e-4 relative is the same root and anything above 1e-2 is plainly a different one.
                "sameRoot", maximumTemperature <= 1e-2 && maximumPhase <= 1e-4);
    }

    private static double relative(double left, double right) {
        double scale = Math.max(Math.max(Math.abs(left), Math.abs(right)), 1e-12);
        return Math.abs(left - right) / scale;
    }

    private static double total(double[] flows) {
        double sum = 0;
        for (double flow : flows) sum += flow;
        return sum;
    }
}
