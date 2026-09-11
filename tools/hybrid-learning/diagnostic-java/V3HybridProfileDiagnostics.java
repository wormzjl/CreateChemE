package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.*;
import com.wormzjl.createcheme.science.column.v3.thermo.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Serial input-only profile diagnostics after timed campaigns; no correction or selection. */
public final class V3HybridProfileDiagnostics {
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private V3HybridProfileDiagnostics() {}
    public static void main(String[] args) throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("pipeline source-jsonl output-jsonl");
        var pipeline=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
        var weight=pipeline.getAsJsonObject("weights");var path=Path.of(weight.get("path").getAsString());
        String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        if(!sha.equals(weight.get("sha256").getAsString()))throw new IllegalArgumentException("Diagnostic weights changed");
        V3NeuralInitializer model;
        if(pipeline.get("kind").getAsString().equals("hybrid-residual"))
            try(var stream=Files.newInputStream(path)){model=V3HybridResidualInitializer.read(stream);}
        else model=V3CandidateModels.read(path);
        boolean completion=pipeline.get("materialCompletion").getAsBoolean();int count=0;
        try(var lines=Files.lines(Path.of(args[1]));var writer=Files.newBufferedWriter(Path.of(args[2]),StandardOpenOption.CREATE_NEW)) {
            for(var iterator=lines.iterator();iterator.hasNext();) {
                var source=JsonParser.parseString(iterator.next()).getAsJsonObject();
                var input=V3NeuralMvpProbe.input(source.getAsJsonObject("input"));
                var row=new LinkedHashMap<String,Object>();row.put("id",source.get("id"));row.put("input",source.get("input"));
                row.put("pipeline",pipeline.get("id"));row.put("correctedRequest",false);row.put("usedForSelection",false);
                try {
                    var raw=model.predict(input,deadline(30_000)).orElse(null);row.put("rawSupported",raw!=null);
                    if(raw!=null) {
                        row.put("raw",describe(raw));V3NeuralSeed prepared=raw;
                        if(completion)prepared=V3MechanisticTransformerInitializer.prepare(raw,deadline(30_000),e->row.put("preparation",e));
                        else row.put("preparation",Map.of("status","DISABLED","prepared",false));
                        row.put("final",describe(prepared));row.put("profileChange",profileChange(raw,prepared));
                        if(source.has("referenceCertified")&&source.get("referenceCertified").getAsBoolean()) {
                            var reference=seed(input,source.getAsJsonObject("seed"));
                            row.put("rawVsOriginalCertifiedReference",profileDifference(raw,reference));
                            row.put("finalVsOriginalCertifiedReference",profileDifference(prepared,reference));
                        }
                    }
                } catch(V3ThermoException|IllegalArgumentException|CancellationException unavailable) {
                    row.put("diagnosticUnavailable",unavailable.getClass().getSimpleName()+": "+unavailable.getMessage());
                }
                writer.write(JSON.toJson(row));writer.newLine();writer.flush();
                if(++count%100==0)System.out.println("diagnostic inputs="+count);
            }
        }
        System.out.println("complete diagnostic inputs="+count);
    }
    private record MaterialRows(double[][] liquid, double[][] vapor, double[] feed,
                                boolean[] condenserLiquid, int feedNode, double refluxFraction) {
        static MaterialRows of(V3NeuralSeed seed) {
            var problem = V3ColumnProblemResolver.resolve(seed.input(), seed.branch());
            double[] feed = seed.input().feedComponentMolarFlowsMolPerSecond();
            boolean[] liquid = new boolean[feed.length];
            for (int c = 0; c < problem.activeComponentBasis().componentCount(); c++)
                liquid[problem.activeComponentBasis().publicIndex(c)] = problem.hasLiquidUnknown(0, c);
            double reflux = seed.input().specifications().stream()
                    .filter(V3ColumnSpecification.OrganicRefluxRatio.class::isInstance)
                    .map(V3ColumnSpecification.OrganicRefluxRatio.class::cast).findFirst().orElseThrow().ratio();
            return new MaterialRows(seed.liquid(), seed.vapor(), feed, liquid,
                    seed.input().feedStageNumber(), reflux / (1 + reflux));
        }

        double physical(int node, int component) {
            double overheadLiquid = condenserLiquid[component] ? liquid[0][component] : 0;
            if (node == 0) return vapor[1][component] - vapor[0][component] - overheadLiquid;
            if (node == liquid.length - 1)
                return liquid[node - 1][component] - liquid[node][component] - vapor[node][component];
            double arriving = node == 1 ? refluxFraction * overheadLiquid : liquid[node - 1][component];
            double enteringFeed = node == feedNode ? feed[component] : 0;
            return arriving + vapor[node + 1][component] + enteringFeed - liquid[node][component] - vapor[node][component];
        }
    }

    private static double materialDefect(V3NeuralSeed seed) {
        MaterialRows material = MaterialRows.of(seed);
        double[] feed = material.feed();
        double total = Arrays.stream(feed).sum(), maximum = 0;
        for (int n = 0; n < seed.input().stageCount() + 2; n++)
            for (int c = 0; c < feed.length; c++)
                maximum = Math.max(maximum, Math.abs(material.physical(n, c)) / Math.max(feed[c], total * 1e-12));
        return maximum;
    }

    private static Map<String, Object> describe(V3NeuralSeed seed) {
        var description = new LinkedHashMap<String, Object>();
        if (seed.input().steamFeeds().isEmpty() && seed.input().sideDraws().isEmpty())
            description.put("maximumMaterialDefectOverInputComponentScale", materialDefect(seed));
        else description.put("fullGridMaterialDiagnostic", "dry/no-side-draw formula inapplicable");
        description.put("branch", seed.branch().name());
        try {
            var full = V3ColumnProblemResolver.resolve(seed.input(), seed.branch());
            var state = seed.stateFor(full);
            var support = V3TruncationSupport.derive(full, 0, state);
            var wet = seed.wetSetFor(full, V3InitializationOptions.WetStart.PREDICTED_WET);
            var problem = V3ColumnProblemResolver.withTruncation(full, support, wet);
            state = support.projectSeed(problem, state);
            var thermo = V3PengRobinsonThermo.fromRegisteredPackage(seed.input().packageId());
            var flash = thermo.flashTP(seed.input().feedTemperatureKelvin(), full.nodePressurePascal(seed.input().feedStageNumber()),
                    seed.input().feedComponentMolarFlowsMolPerSecond(), V3TraceTruncationPolicy.of(0), thermo.newWorkspace(), deadline(30_000)::checkpoint);
            var residual = new V3MeshResidualEvaluator(problem, thermo, flash.molarEnthalpyJoulesPerMol())
                    .evaluate(state, thermo.newWorkspace());
            var families = new LinkedHashMap<String, Map<String, Object>>();
            for (var row : residual.rows()) {
                var family = families.computeIfAbsent(row.equation().family().name(), ignored -> {
                    var value = new LinkedHashMap<String, Object>();
                    value.put("rows", 0);
                    value.put("maximumAbsolutePhysical", 0.0);
                    value.put("maximumAbsoluteScaled", 0.0);
                    return value;
                });
                family.put("rows", (int) family.get("rows") + 1);
                family.put("maximumAbsolutePhysical", Math.max((double) family.get("maximumAbsolutePhysical"), Math.abs(row.physicalValue())));
                family.put("maximumAbsoluteScaled", Math.max((double) family.get("maximumAbsoluteScaled"), Math.abs(row.scaledValue())));
            }
            description.put("nativeFamilies", families);
            description.put("nativeRowCount", residual.rows().size());
            description.put("projectedThroughNativeSupport", true);
            description.put("nativeSupport", Map.of("truncatedPoints", support.truncatedPointCount(),
                    "onePhasePoints", support.onePhasePointCount(), "presentPhases", support.presentPhaseCount(),
                    "note", support.note()));
        } catch (V3ThermoException | IllegalArgumentException unavailable) {
            description.put("nativeResidualUnavailable", unavailable.getClass().getSimpleName() + ": " + unavailable.getMessage());
        }
        return description;
    }

    private static Map<String, Object> profileChange(V3NeuralSeed raw, V3NeuralSeed prepared) {
        double maximumTemperatureChange = 0, flowSquares = 0, maximumFlow = 0;
        int count = 0, supportChanges = 0;
        double[] rawT = raw.temperatures(), preparedT = prepared.temperatures();
        for (int i = 0; i < rawT.length; i++) maximumTemperatureChange = Math.max(maximumTemperatureChange, Math.abs(rawT[i] - preparedT[i]));
        for (boolean liquid : List.of(true, false)) {
            double[][] a = liquid ? raw.liquid() : raw.vapor(), b = liquid ? prepared.liquid() : prepared.vapor();
            for (int n = 0; n < a.length; n++) for (int c = 0; c < a[n].length; c++) {
                double change = b[n][c] - a[n][c];
                flowSquares += change * change;
                maximumFlow = Math.max(maximumFlow, Math.abs(change));
                if ((a[n][c] == 0) != (b[n][c] == 0)) supportChanges++;
                count++;
            }
        }
        return Map.of("maximumTemperatureChangeKelvin", maximumTemperatureChange,
                "componentFlowRmseChangeMolPerSecond", Math.sqrt(flowSquares / count),
                "maximumComponentFlowChangeMolPerSecond", maximumFlow, "exactZeroPatternChanges", supportChanges);
    }

    private static Map<String, Object> profileDifference(V3NeuralSeed raw, V3NeuralSeed actual) {
        double t2 = 0, tMax = 0, f2 = 0, fMax = 0, log2 = 0, w2 = 0, wMax = 0, total2 = 0, totalMax = 0;
        int mismatches = 0, flowCount = 0;
        var rt = raw.temperatures(); var at = actual.temperatures(); var rw = raw.freeWater(); var aw = actual.freeWater();
        var rm = raw.wetTrays(); var am = actual.wetTrays();
        double referenceFlow = Arrays.stream(actual.input().feedComponentMolarFlowsMolPerSecond()).sum();
        for (int n = 0; n < rt.length; n++) {
            double dt = rt[n] - at[n], dw = rw[n] - aw[n]; t2 += dt * dt; w2 += dw * dw;
            tMax = Math.max(tMax, Math.abs(dt)); wMax = Math.max(wMax, Math.abs(dw)); if (rm[n] != am[n]) mismatches++;
        }
        for (boolean liquid : new boolean[]{true, false}) {
            double[][] rf = liquid ? raw.liquid() : raw.vapor(), af = liquid ? actual.liquid() : actual.vapor();
            for (int n = 0; n < rf.length; n++) {
                double totalRaw = 0, totalActual = 0;
                for (int c = 0; c < rf[n].length; c++) {
                    double d = rf[n][c] - af[n][c], dl = Math.log1p(rf[n][c]) - Math.log1p(af[n][c]);
                    f2 += d * d; log2 += dl * dl; fMax = Math.max(fMax, Math.abs(d)); flowCount++;
                    totalRaw += rf[n][c]; totalActual += af[n][c];
                }
                double d = (totalRaw - totalActual) / referenceFlow; total2 += d * d; totalMax = Math.max(totalMax, Math.abs(d));
            }
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("temperatureRmseKelvin", Math.sqrt(t2 / rt.length)); result.put("temperatureMaxAbsKelvin", tMax);
        result.put("componentFlowRmseMolPerSecond", Math.sqrt(f2 / flowCount)); result.put("componentFlowMaxAbsMolPerSecond", fMax);
        result.put("log1pComponentFlowRmse", Math.sqrt(log2 / flowCount)); result.put("phaseTotalFlowRelativeToFeedRmse", Math.sqrt(total2 / (2 * rt.length)));
        result.put("phaseTotalFlowRelativeToFeedMaxAbs", totalMax); result.put("freeWaterRmseMolPerSecond", Math.sqrt(w2 / rt.length));
        result.put("freeWaterMaxAbsMolPerSecond", wMax); result.put("wetMaskMismatches", mismatches); result.put("branchMatches", raw.branch() == actual.branch());
        return result;
    }

    private static V3NeuralSeed seed(V3ColumnInput input, JsonObject json) {
        return new V3NeuralSeed(input, json.get("propertyRevision").getAsString(), V3CondenserPhaseBranch.valueOf(json.get("branch").getAsString()),
                JSON.fromJson(json.get("liquid"), double[][].class), JSON.fromJson(json.get("vapor"), double[][].class),
                JSON.fromJson(json.get("temperatures"), double[].class), JSON.fromJson(json.get("freeWater"), double[].class),
                JSON.fromJson(json.get("wetTrays"), boolean[].class));
    }

    private static V3SolveControl deadline(long milliseconds) {
        long start=System.nanoTime();return ()->{
            if(Thread.currentThread().isInterrupted()||System.nanoTime()-start>=milliseconds*1_000_000L)
                throw new CancellationException("post-campaign diagnostic budget");
        };
    }
}
