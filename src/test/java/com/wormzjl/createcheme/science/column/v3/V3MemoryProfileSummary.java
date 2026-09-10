package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import jdk.jfr.consumer.RecordingFile;

/** Summarizes allocation sample weights and GC heap events only inside the measured solve interval. */
public final class V3MemoryProfileSummary {
    private V3MemoryProfileSummary() {}

    public static void main(String[] args) throws Exception {
        Path metadata = Path.of(args[0]);
        var json = new Gson().fromJson(Files.readString(metadata), com.google.gson.JsonObject.class);
        Instant start = Instant.parse(json.get("measuredStart").getAsString());
        Instant end = Instant.parse(json.get("measuredEnd").getAsString());
        Map<String, Long> classes = new HashMap<>();
        Map<String, Long> sites = new HashMap<>();
        Map<String, Long> groups = new HashMap<>();
        long totalWeight = 0;
        long samples = 0;
        long discardedBoundaryWeight = 0;
        boolean allocationBoundarySeen = false;
        long beforeGcPeak = 0;
        long afterGcPeak = 0;
        double pauseMillis = 0;
        double maximumPauseMillis = 0;
        long pauseCount = 0;
        try (RecordingFile recording = new RecordingFile(Path.of(args[0].replace(".json", ".jfr")))) {
            while (recording.hasMoreEvents()) {
                var event = recording.readEvent();
                // The first event can carry the thread's allocation credit from before recording
                // started (including warmups). Exclude that boundary sample from measured shares.
                if (event.getEventType().getName().equals("jdk.ObjectAllocationSample")
                        && event.getThread().getJavaName().equals("main") && !allocationBoundarySeen) {
                    allocationBoundarySeen = true;
                    discardedBoundaryWeight = event.getLong("weight");
                    continue;
                }
                if (event.getStartTime().isBefore(start) || event.getStartTime().isAfter(end)) continue;
                switch (event.getEventType().getName()) {
                    case "jdk.ObjectAllocationSample" -> {
                        if (!event.getThread().getJavaName().equals("main")) continue;
                        long weight = event.getLong("weight");
                        totalWeight += weight;
                        samples++;
                        classes.merge(event.getClass("objectClass").getName(), weight, Long::sum);
                        String site = "other";
                        if (event.getStackTrace() != null) {
                            for (var frame : event.getStackTrace().getFrames()) {
                                String owner = frame.getMethod().getType().getName();
                                if (owner.startsWith("com.wormzjl.createcheme.science")) {
                                    site = owner + "." + frame.getMethod().getName() + ":" + frame.getLineNumber();
                                    break;
                                }
                            }
                        }
                        sites.merge(site, weight, Long::sum);
                        groups.merge(group(site), weight, Long::sum);
                    }
                    case "jdk.GCHeapSummary" -> {
                        long bytes = event.getLong("heapUsed");
                        if (event.getString("when").equals("Before GC")) beforeGcPeak = Math.max(beforeGcPeak, bytes);
                        else afterGcPeak = Math.max(afterGcPeak, bytes);
                    }
                    case "jdk.GCPhasePause" -> {
                        double duration = event.getDuration().toNanos() / 1e6;
                        pauseMillis += duration;
                        maximumPauseMillis = Math.max(maximumPauseMillis, duration);
                        pauseCount++;
                    }
                    default -> { }
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("allocationSamples", samples);
        result.put("discardedFirstAllocationWeight", discardedBoundaryWeight);
        result.put("estimatedAllocationWeight", totalWeight);
        result.put("beforeGcHeapPeak", beforeGcPeak);
        result.put("afterGcHeapPeak", afterGcPeak);
        result.put("gcPauseMillis", pauseMillis);
        result.put("maximumGcPauseMillis", maximumPauseMillis);
        result.put("gcPauseCount", pauseCount);
        result.put("classes", sorted(classes));
        result.put("sites", sorted(sites));
        result.put("groups", sorted(groups));
        Files.writeString(Path.of(args[0].replace(".json", "-summary.json")), new GsonBuilder().setPrettyPrinting().create().toJson(result));
    }

    private static String group(String site) {
        if (site.contains(".V3Banded") || site.contains(".V3BlockJacobian")
                || site.contains(".V3FiniteDifferenceJacobian") || site.contains(".V3NormalEquations")) {
            return "Jacobian assembly and linear-system work";
        }
        if (site.contains(".thermo.") || site.contains("V3MeshResidualEvaluator.normalizedPublicPhaseComposition")
                || site.contains("V3MeshResidualEvaluator.local") || site.contains("V3MeshResidualEvaluator.nodeProperties")) {
            return "Thermodynamic evaluation and result copies";
        }
        if (site.contains(".V3MeshResidual.") || site.contains(".V3MeshResidual$")
                || site.contains("V3MeshResidualEvaluator.evaluate")) {
            return "Residual rows and containers";
        }
        if (site.contains(".V3DryMeshState") || site.contains(".V3DryMeshCoordinateMap")
                || site.contains(".V3SimultaneousColumnSolver")) {
            return "Trial states and solver coordinate arrays";
        }
        if (site.contains(".V3DegreeOfFreedomLedger") || site.contains(".V3StageBlockLayout")
                || site.contains(".V3ColumnProblem") || site.contains(".V3TruncationSupport")
                || site.contains(".V3ActiveComponentBasis") || site.contains(".V3WetTraySet")) {
            return "Topology and validation metadata";
        }
        return "Other and diagnostic overhead";
    }

    private static Map<String, Long> sorted(Map<String, Long> entries) {
        Map<String, Long> result = new LinkedHashMap<>();
        entries.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(20)
                .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }
}
