package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.GsonBuilder;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Opt-in serial public-calculator benchmark. Fixture construction and JSON encoding are not timed. */
public final class V3MainOptimizationProbe {
    private V3MainOptimizationProbe() {}

    public static void main(String[] args) throws Exception {
        Path report = Path.of(args[0]);
        int warmup = Integer.parseInt(args[1]);
        int samples = Integer.parseInt(args[2]);
        if (warmup < 0 || samples < 1) throw new IllegalArgumentException("Invalid sample count");
        var factory = V3ConvergenceClosureTest.class.getDeclaredMethod("evaluationCase", String.class);
        factory.setAccessible(true);
        Map<String, V3ColumnInput> inputs = new LinkedHashMap<>();
        for (String label : List.of("A", "B", "C", "D", "E")) {
            inputs.put(label, (V3ColumnInput) factory.invoke(null, label));
        }
        inputs.put("Holland", V3HollandExample32.input());
        if (args.length > 3) inputs.keySet().retainAll(List.of(args[3].split(",")));
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadCpuTimeEnabled(true);
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        List<Map<String, Object>> rows = new ArrayList<>();
        var gson = new GsonBuilder().setPrettyPrinting().create();
        for (var entry : inputs.entrySet()) {
            for (int iteration = -warmup; iteration < samples; iteration++) {
                long deadline = System.nanoTime() + 120_000_000_000L;
                V3SolveControl control = () -> {
                    if (System.nanoTime() > deadline) throw new java.util.concurrent.CancellationException("Probe deadline");
                };
                long allocated = bean.getThreadAllocatedBytes(thread);
                long cpu = bean.getCurrentThreadCpuTime();
                long start = System.nanoTime();
                V3ColumnOutcome outcome = entry.getKey().equals("Holland")
                        ? V3HollandExample32.calculate(entry.getValue(), control)
                        : V3ColumnCalculator.calculate(entry.getValue(), control, 0.0, 0.0);
                long elapsed = System.nanoTime() - start;
                long cpuElapsed = bean.getCurrentThreadCpuTime() - cpu;
                long bytes = bean.getThreadAllocatedBytes(thread) - allocated;
                if (iteration < 0) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("case", entry.getKey());
                row.put("sample", iteration);
                row.put("wallMs", elapsed / 1e6);
                row.put("cpuMs", cpuElapsed / 1e6);
                row.put("allocatedBytes", bytes);
                row.put("diagnostics", outcome.diagnostics());
                if (outcome instanceof V3ColumnOutcome.Success success) {
                    row.put("kind", "SUCCESS");
                    row.put("streams", success.result().streams());
                    row.put("evidence", success.result().convergenceEvidence());
                    row.put("audit", success.result().acceptanceAudit());
                    row.put("duty", success.result().dutyLedger().orElse(null));
                } else {
                    row.put("kind", "FAILURE");
                    row.put("failure", outcome.toString());
                }
                rows.add(row);
                System.out.printf("%s sample %d: %.1f ms%n", entry.getKey(), iteration, elapsed / 1e6);
            }
            Files.createDirectories(report.toAbsolutePath().getParent());
            Files.writeString(report, gson.toJson(rows));
        }
    }
}
