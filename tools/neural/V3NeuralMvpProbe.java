package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Offline MVP dataset and correction benchmark. Never promotes a failed physical state to a label. */
public final class V3NeuralMvpProbe {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[1]); Files.createDirectories(directory);
        if (args[0].equals("generate")) generate(directory, Path.of(args[2]), Integer.parseInt(args[3]));
        else if (args[0].equals("evaluate")) evaluate(directory, Path.of(args[2]));
        else throw new IllegalArgumentException("generate|evaluate directory input-or-model [count]");
    }

    private static void generate(Path directory, Path original, int count) throws Exception {
        JsonObject document = JsonParser.parseString(Files.readString(original)).getAsJsonObject();
        V3ColumnInput base = input(document.getAsJsonObject("input"));
        if (count < 12) throw new IllegalArgumentException("Need at least twelve points");
        Files.writeString(directory.resolve("source-input.json"), JSON.toJson(document));
        try (var out = Files.newBufferedWriter(directory.resolve("cases.jsonl"))) {
            for (int i = 0; i < count; i++) {
                // One bounded temperature slice for the prototype, NOT refinery-wide training coverage.
                double temperature = base.feedTemperatureKelvin() - 4 + 8.0*i/(count-1);
                V3ColumnInput request = new V3ColumnInput(base.schemaVersion(), base.packageId(), base.assayId(),
                        base.componentBasis(), base.feedComponentMolarFlowsMolPerSecond(), temperature, base.stageCount(),
                        base.feedStageNumber(), base.topPressurePascal(), base.stagePressureDropPascal(),
                        base.specifications(), base.sideDraws(), base.steamFeeds(), base.pumparounds());
                String split = i % 8 == 3 || i % 8 == 4 ? "test" : i % 8 == 6 ? "validation" : "train";
                var row = new LinkedHashMap<String, Object>();
                row.put("id", i); row.put("split", split); row.put("input", request);
                row.put("features", V3NeuralFeatures.encode(request));
                long start = System.nanoTime();
                final V3NeuralSeed[] captured = {null};
                try {
                    V3ColumnOutcome outcome = V3ColumnCalculator.calculateWithAcceptedProfile(request,
                            deadline(start, 15_000), seed -> captured[0] = seed);
                    row.put("success", outcome.isSuccess()); row.put("diagnostics", outcome.diagnostics());
                    if (outcome instanceof V3ColumnOutcome.Success accepted) {
                        if (captured[0] == null || !accepted.result().acceptanceAudit().accepted()) throw new IllegalStateException("Missing accepted profile");
                        row.put("seed", captured[0]); row.put("targets", V3NeuralFeatures.targets(captured[0]));
                        row.put("formulationRevision", accepted.result().formulationRevision());
                    } else row.put("failure", ((V3ColumnOutcome.Failure)outcome).summary());
                } catch (CancellationException timeout) { row.put("success", false); row.put("failure", "deadline"); }
                row.put("cold_ms", (System.nanoTime()-start)/1e6);
                out.write(JSON.toJson(row)); out.newLine(); out.flush();
                System.out.printf(Locale.ROOT, "%d/%d T=%.4f %s success=%s ms=%.1f%n", i+1,count,temperature,split,row.get("success"),row.get("cold_ms"));
            }
        }
    }

    private static void evaluate(Path directory, Path modelFile) throws Exception {
        V3DenseNeuralInitializer model;
        try (var stream = Files.newInputStream(modelFile)) { model = V3DenseNeuralInitializer.read(stream); }
        var rows = new ArrayList<Object>();
        for (String line : Files.readAllLines(directory.resolve("cases.jsonl"))) {
            JsonObject sample = JsonParser.parseString(line).getAsJsonObject();
            if (!sample.get("split").getAsString().equals("test")) continue;
            V3ColumnInput request = input(sample.getAsJsonObject("input"));
            var row = new LinkedHashMap<String,Object>(); row.put("id",sample.get("id").getAsInt());
            // Refresh the cold timing in the same benchmark run, alternating order to reduce order bias.
            boolean learnedFirst = sample.get("id").getAsInt() % 2 == 0;
            for (boolean neural : learnedFirst ? new boolean[]{true,false} : new boolean[]{false,true}) {
                long start=System.nanoTime();
                var result=new LinkedHashMap<String,Object>();
                try {
                    var outcome=V3ColumnCalculator.calculate(request,deadline(start,15_000),0,0,
                            new V3InitializationOptions(neural ? V3InitializationOptions.Mode.LNN_ONLY : V3InitializationOptions.Mode.CURRENT_ONLY,
                                    V3InitializationOptions.WetStart.AUTO,16,2_000),model);
                    result.put("success",outcome.isSuccess()); result.put("diagnostics",outcome.diagnostics());
                    if(outcome instanceof V3ColumnOutcome.Success success) result.put("streams",success.result().streams());
                } catch (CancellationException timeout) {
                    result.put("success",false); result.put("failure","deadline"); result.put("diagnostics",null);
                }
                result.put("ms",(System.nanoTime()-start)/1e6);
                row.put(neural?"neural":"current",result);
            }
            rows.add(row); Files.writeString(directory.resolve("evaluation.json"),JSON.toJson(rows));
            System.out.println(JSON.toJson(row).substring(0,Math.min(200,JSON.toJson(row).length())));
        }
    }

    private static V3SolveControl deadline(long start,long milliseconds) {
        return () -> {if(System.nanoTime()-start>milliseconds*1_000_000)throw new CancellationException("offline deadline");};
    }

    static V3ColumnInput input(JsonObject json) {
        var specs = new ArrayList<V3ColumnSpecification>();
        for(JsonElement e:json.getAsJsonArray("specifications")) {
            JsonObject s=e.getAsJsonObject();
            if(s.has("kelvin"))specs.add(new V3ColumnSpecification.CondenserOutletTemperature(s.get("kelvin").getAsDouble()));
            else if(s.has("ratio"))specs.add(new V3ColumnSpecification.OrganicRefluxRatio(s.get("ratio").getAsDouble()));
            else specs.add(new V3ColumnSpecification.ReboilerDuty(s.get("watts").getAsDouble()));
        }
        return new V3ColumnInput(json.get("schemaVersion").getAsInt(),json.get("packageId").getAsString(),json.get("assayId").getAsString(),
                new V3ComponentBasis(Arrays.asList(JSON.fromJson(json.getAsJsonObject("componentBasis").get("componentIds"),String[].class))),
                JSON.fromJson(json.get("feedComponentMolarFlowsMolPerSecond"),double[].class),json.get("feedTemperatureKelvin").getAsDouble(),
                json.get("stageCount").getAsInt(),json.get("feedStageNumber").getAsInt(),json.get("topPressurePascal").getAsDouble(),
                json.get("stagePressureDropPascal").getAsDouble(),specs,
                Arrays.asList(JSON.fromJson(json.get("sideDraws"),V3SideDrawSpec[].class)),
                Arrays.asList(JSON.fromJson(json.get("steamFeeds"),V3SteamFeedSpec[].class)),
                Arrays.asList(JSON.fromJson(json.get("pumparounds"),V3PumparoundSpec[].class)));
    }
}
