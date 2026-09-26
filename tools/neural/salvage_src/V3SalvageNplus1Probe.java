package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.*;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoException;
import com.wormzjl.createcheme.science.column.v3.thermo.V3TraceTruncationPolicy;
import java.lang.management.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;

/** Additive salvage runner. Caller journals; ten owned workers run one LNN_FIRST each. */
public final class V3SalvageNplus1Probe {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final com.sun.management.ThreadMXBean ALLOCATIONS = THREADS instanceof com.sun.management.ThreadMXBean bean ? bean : null;
    private static final MemoryMXBean MEMORY = ManagementFactory.getMemoryMXBean();
    private V3SalvageNplus1Probe() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("output source model");
        Path directory=Path.of(args[0]), source=Path.of(args[1]), modelPath=Path.of(args[2]);
        if (Files.exists(directory)) throw new IllegalArgumentException("Preserve existing attempt; use registered recovery revision");
        List<JsonObject> requests;
        try (var lines=Files.lines(source)) { requests=lines.filter(s -> !s.isBlank()).map(s -> JsonParser.parseString(s).getAsJsonObject()).toList(); }
        Set<String> ids=new HashSet<>();
        for (var row:requests) {
            if (!ids.add(row.get("id").getAsString()) || row.has("exclusion")) throw new IllegalArgumentException("Invalid population");
            V3NeuralMvpProbe.input(row.getAsJsonObject("input"));
        }
        if (requests.isEmpty()) throw new IllegalArgumentException("Empty population");
        V3NeuralInitializer model=V3CandidateModels.read(modelPath);
        Files.createDirectories(directory);
        var tasks=new ArrayList<Callable<Map<String,Object>>>();
        for (var request:requests) tasks.add(() -> {
            var input=V3NeuralMvpProbe.input(request.getAsJsonObject("input"));
            var result=run(input,model,V3InitializationOptions.Mode.LNN_FIRST,30000,2000,16,true);
            result.put("id",request.get("id")); result.put("split",request.get("split"));
            result.put("input",request.get("input")); result.put("design",request.get("design"));
            return result;
        });
        int[] finished={0}; long started=System.nanoTime();
        V3BoundedEvaluation.Summary scheduling=null;
        try(var out=Files.newBufferedWriter(directory.resolve("evaluation.jsonl"),StandardOpenOption.CREATE_NEW)) {
            scheduling=V3BoundedEvaluation.run(tasks,10,(result,queueWait)-> {
                result.put("queueWaitMillis",queueWait);
                out.write(JSON.toJson(result)); out.newLine(); out.flush(); finished[0]++;
                if(finished[0]%25==0 || finished[0]==requests.size()) System.out.printf(Locale.ROOT,"salvage %d/%d elapsed=%.1fs%n",finished[0],requests.size(),(System.nanoTime()-started)/1e9);
            });
        } finally {
            var metadata=new LinkedHashMap<String,Object>();
            metadata.put("completed",finished[0]); metadata.put("complete",scheduling!=null && scheduling.terminated() && finished[0]==requests.size());
            metadata.put("scheduling",scheduling); metadata.put("sourceSha256",sha256(source)); metadata.put("modelSha256",sha256(modelPath));
            metadata.put("mode","LNN_FIRST"); metadata.put("workers",10); metadata.put("deadlineMillis",30000); metadata.put("neuralBudgetMillis",2000); metadata.put("maximumIterations",16);
            metadata.put("elapsedSeconds",(System.nanoTime()-started)/1e9); metadata.put("java",System.getProperty("java.version"));
            Files.writeString(directory.resolve("run.json"),JSON.toJson(metadata),StandardOpenOption.CREATE_NEW);
        }
    }
    private static Map<String, Object> run(V3ColumnInput input, V3NeuralInitializer model, V3InitializationOptions.Mode mode,
            long deadlineMillis, long neuralBudgetMillis, int maximumIterations, boolean capture) {
        long start = System.nanoTime(), allocationStart = allocation(), cpuStart = cpu();
        var row = new LinkedHashMap<String, Object>();
        row.put("heapUsedBeforeBytes", MEMORY.getHeapMemoryUsage().getUsed());
        try {
            V3NeuralSeed[] accepted = {null};
            var control = deadline(start, deadlineMillis);
            var outcome = mode == V3InitializationOptions.Mode.CURRENT_ONLY
                    ? V3ColumnCalculator.calculateWithAcceptedProfile(input, control, profile -> accepted[0] = profile)
                    : V3ColumnCalculator.calculateWithAcceptedProfile(input, control,
                            new V3InitializationOptions(mode, V3InitializationOptions.WetStart.AUTO, maximumIterations, Math.toIntExact(neuralBudgetMillis)), model, profile -> accepted[0] = profile);
            row.put("success", outcome.isSuccess()); row.put("diagnostics", outcome.diagnostics());
            if (outcome instanceof V3ColumnOutcome.Success success) {
                if (accepted[0] == null) throw new IllegalStateException("Accepted native solve omitted its profile");
                row.put("status", "ACCEPTED");
                var water = V3WaterPhaseQualification.assess(accepted[0]);
                row.put("waterQualification", water.grade().name()); row.put("equilibriumQualified", water.qualified());
                row.put("wetTrayCount", water.wetTrayCount()); row.put("waterEvidence", water);
                row.put("formulationRevision", success.result().formulationRevision());
                if (capture) { row.put("seed", accepted[0]); row.put("streams", success.result().streams()); }
            } else {
                var failure = (V3ColumnOutcome.Failure) outcome;
                row.put("status", failure.code().name()); row.put("failure", failure.summary());
                row.put("failureClass", failure.code() == V3SolverFailureCode.INFEASIBLE_SPECIFICATION ? "NATIVE_SOLVER_ADMISSION_OR_PATH_BOUND" : "NATIVE_SOLVER_FAILURE");
            }
        } catch (CancellationException cancelled) {
            row.put("success", false); row.put("status", Thread.currentThread().isInterrupted() ? "CANCELLED" : "DEADLINE_EXCEEDED"); row.put("failure", cancelled.getMessage());
        } catch (V3ThermoException | IllegalArgumentException unsupported) {
            row.put("success", false); row.put("status", "PROPERTY_OR_INPUT_REJECTION"); row.put("failure", unsupported.getMessage());
        }
        row.put("ms", (System.nanoTime() - start) / 1e6); row.put("cold_ms", row.get("ms"));
        row.put("cpuMillis", nanosDifference(cpuStart, cpu())); row.put("allocatedBytes", difference(allocationStart, allocation()));
        row.put("heapUsedAfterBytes", MEMORY.getHeapMemoryUsage().getUsed());
        return row;
    }

    private static V3SolveControl deadline(long start, long milliseconds) {
        return () -> {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("offline worker interrupted");
            if (System.nanoTime() - start >= milliseconds * 1_000_000L) throw new CancellationException("offline wall deadline");
        };
    }
    private static long cpu() { return THREADS.isCurrentThreadCpuTimeSupported() ? THREADS.getCurrentThreadCpuTime() : -1; }
    private static long allocation() { return ALLOCATIONS != null && ALLOCATIONS.isThreadAllocatedMemorySupported() ? ALLOCATIONS.getThreadAllocatedBytes(Thread.currentThread().threadId()) : -1; }
    private static Long difference(long before, long after) { return before >= 0 && after >= before ? after - before : null; }
    private static Double nanosDifference(long before, long after) { Long value = difference(before, after); return value == null ? null : value / 1e6; }
    private static String sha256(Path path) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
}
