package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.GsonBuilder;
import com.sun.jna.Native;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.BaseTSD.SIZE_T;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jdk.jfr.Recording;

/** Standalone diagnostic: samples heap on solve checkpoints and reads Windows process memory counters. */
public final class V3MemoryProbe {
    private static final MemoryMXBean MEMORY = ManagementFactory.getMemoryMXBean();
    private static final ThreadMXBean THREAD = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final ProcessMemory PROCESS = Native.load("psapi", ProcessMemory.class);
    private static final Counters COUNTERS = new Counters();
    private static final List<Map<String, Object>> SAMPLES = new ArrayList<>();
    private static final long DEADLINE_NANOS = Long.getLong("probeDeadlineMillis", 60_000L) * 1_000_000L;
    private static long peakHeap;
    private static long peakCommitted;
    private static long peakWorkingSet;
    private static long peakPrivate;
    private static long nextSample;
    private static long deadline;

    private V3MemoryProbe() {}

    public static void main(String[] args) throws Exception {
        String label = args[0];
        Path report = Path.of(args[1]);
        int count = Integer.parseInt(args[2]);
        boolean profile = Boolean.parseBoolean(args[3]);
        Files.createDirectories(report.toAbsolutePath().getParent());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("case", label);
        result.put("pid", ProcessHandle.current().pid());
        result.put("java", System.getProperty("java.runtime.version"));
        result.put("vmArguments", ManagementFactory.getRuntimeMXBean().getInputArguments());
        result.put("heapMaximum", MEMORY.getHeapMemoryUsage().getMax());
        result.put("profiled", profile);
        THREAD.setThreadAllocatedMemoryEnabled(true);
        THREAD.setThreadCpuTimeEnabled(true);
        V3ColumnOutcome held = null;
        try {
            V3ColumnInput input = input(label);
            result.put("input", input.toString());
            Recording recording = null;
            if (profile) {
                recording = new Recording();
                recording.enable("jdk.ObjectAllocationSample").withStackTrace().with("throttle", "1000/s");
                recording.enable("jdk.GCHeapSummary");
                recording.enable("jdk.GarbageCollection");
                recording.enable("jdk.GCPhasePause");
                // Start before warmups so the first measured allocation sample cannot inherit
                // an entire warmup interval's allocation credit. The summary filters by timestamps.
                recording.start();
            }
            int warmupCount = Integer.getInteger("memoryWarmup", 3);
            if (warmupCount < 0) throw new IllegalArgumentException("Negative memory warmup count");
            result.put("warmupCount", warmupCount);
            for (int i = 0; i < warmupCount; i++) {
                deadline = System.nanoTime() + DEADLINE_NANOS;
                held = calculate(label, input, () -> {
                    if (System.nanoTime() > deadline) throw new java.util.concurrent.CancellationException("Probe deadline");
                });
                if (!(held instanceof V3ColumnOutcome.Success)) break;
            }
            held = null;
            System.gc();
            result.put("before", snapshot());
            result.put("measuredStart", Instant.now().toString());
            for (int i = 0; i < count; i++) {
                held = null;
                peakHeap = peakCommitted = peakWorkingSet = peakPrivate = nextSample = 0;
                long gcCount = gcCount();
                long gcMillis = gcMillis();
                long allocated = THREAD.getThreadAllocatedBytes(Thread.currentThread().threadId());
                long cpu = THREAD.getCurrentThreadCpuTime();
                long start = System.nanoTime();
                deadline = start + DEADLINE_NANOS;
                held = calculate(label, input, V3MemoryProbe::checkpoint);
                checkpoint();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("sample", i);
                row.put("wallMs", (System.nanoTime() - start) / 1e6);
                row.put("cpuMs", (THREAD.getCurrentThreadCpuTime() - cpu) / 1e6);
                row.put("allocatedBytes", THREAD.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocated);
                row.put("gcCount", gcCount() - gcCount);
                row.put("gcMillis", gcMillis() - gcMillis);
                row.put("peakSampledHeapUsed", peakHeap);
                row.put("peakSampledHeapCommitted", peakCommitted);
                row.put("peakSampledWorkingSet", peakWorkingSet);
                row.put("peakSampledPrivateBytes", peakPrivate);
                row.put("kind", held instanceof V3ColumnOutcome.Success ? "SUCCESS" : "FAILURE");
                row.put("diagnostics", held.diagnostics());
                if (held instanceof V3ColumnOutcome.Success success) {
                    row.put("auditPassed", success.result().acceptanceAudit().accepted());
                    row.put("certificate", success.result().convergenceEvidence());
                    row.put("streams", success.result().streams());
                } else row.put("failure", held.toString());
                SAMPLES.add(row);
                System.out.printf("%s sample=%d heapPeak=%.1f MiB allocated=%.1f MiB gc=%d/%dms%n",
                        label, i, peakHeap / 1048576.0, (long) row.get("allocatedBytes") / 1048576.0,
                        row.get("gcCount"), row.get("gcMillis"));
            }
            result.put("measuredEnd", Instant.now().toString());
            if (recording != null) {
                recording.stop();
                recording.dump(Path.of(report.toString().replace(".json", ".jfr")));
                recording.close();
            }
            result.put("afterBeforeGC", snapshot());
            System.gc();
            result.put("afterForcedGC", snapshot());
            result.put("status", "COMPLETED");
        } catch (OutOfMemoryError exhausted) {
            held = null;
            System.gc();
            result.put("status", "OUT_OF_MEMORY");
            result.put("oomMessage", exhausted.getMessage());
            result.put("oomFrames", java.util.Arrays.stream(exhausted.getStackTrace()).limit(8).map(Object::toString).toList());
        } catch (java.util.concurrent.CancellationException timeout) {
            result.put("status", "DEADLINE");
        }
        result.put("samples", SAMPLES);
        Files.writeString(report, new GsonBuilder().setPrettyPrinting().create().toJson(result));
        if (profile || Boolean.getBoolean("memoryNativeSummary")) {
            new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "jcmd.exe").toString(),
                    Long.toString(ProcessHandle.current().pid()), "VM.native_memory", "summary", "scale=KB")
                    .redirectErrorStream(true)
                    .redirectOutput(Path.of(report.toString().replace(".json", "-native.txt")).toFile())
                    .start().waitFor();
        }
        java.lang.ref.Reference.reachabilityFence(held);
    }

    private static V3ColumnOutcome calculate(String label, V3ColumnInput input, V3SolveControl control) {
        return label.equals("Holland") ? V3HollandExample32.calculate(input, control)
                : V3ColumnCalculator.calculate(input, control);
    }

    private static V3ColumnInput input(String label) throws Exception {
        if (label.equals("Default")) return V3DiagnosticFixtures.shippedDefault();
        if (label.startsWith("Default")) {
            V3ColumnInput input = V3DiagnosticFixtures.defaultPerturbations().get(label);
            if (input == null) input = V3DiagnosticFixtures.largeDefaultPerturbations().get(label);
            if (input == null) throw new IllegalArgumentException("Unknown default perturbation: " + label);
            return input;
        }
        if (label.equals("Holland")) return V3HollandExample32.input();
        var factory = V3ConvergenceClosureTest.class.getDeclaredMethod("evaluationCase", String.class);
        factory.setAccessible(true);
        V3ColumnInput base = (V3ColumnInput) factory.invoke(null, label.substring(0, 1));
        if (!label.endsWith("64")) return base;
        return V3DiagnosticFixtures.with64Trays(base);
    }

    private static void checkpoint() {
        long now = System.nanoTime();
        if (now > deadline) throw new java.util.concurrent.CancellationException("Probe deadline");
        if (now < nextSample) return;
        nextSample = now + 2_000_000;
        var heap = MEMORY.getHeapMemoryUsage();
        peakHeap = Math.max(peakHeap, heap.getUsed());
        peakCommitted = Math.max(peakCommitted, heap.getCommitted());
        readCounters();
        peakWorkingSet = Math.max(peakWorkingSet, COUNTERS.workingSetSize.longValue());
        peakPrivate = Math.max(peakPrivate, COUNTERS.privateUsage.longValue());
    }

    private static Map<String, Object> snapshot() {
        readCounters();
        var heap = MEMORY.getHeapMemoryUsage();
        return Map.of("heapUsed", heap.getUsed(), "heapCommitted", heap.getCommitted(),
                "nonHeapUsed", MEMORY.getNonHeapMemoryUsage().getUsed(),
                "workingSet", COUNTERS.workingSetSize.longValue(),
                "lifetimePeakWorkingSet", COUNTERS.peakWorkingSetSize.longValue(),
                "privateBytes", COUNTERS.privateUsage.longValue());
    }

    private static void readCounters() {
        if (!PROCESS.GetProcessMemoryInfo(Kernel32.INSTANCE.GetCurrentProcess(), COUNTERS, COUNTERS.size()))
            throw new IllegalStateException("GetProcessMemoryInfo failed: " + Native.getLastError());
    }

    private static long gcCount() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> bean.getCollectionCount()).sum(); }
    private static long gcMillis() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> bean.getCollectionTime()).sum(); }

    public interface ProcessMemory extends StdCallLibrary {
        boolean GetProcessMemoryInfo(HANDLE process, Counters counters, int size);
    }

    @Structure.FieldOrder({"cb", "pageFaultCount", "peakWorkingSetSize", "workingSetSize", "quotaPeakPagedPoolUsage",
            "quotaPagedPoolUsage", "quotaPeakNonPagedPoolUsage", "quotaNonPagedPoolUsage", "pagefileUsage", "peakPagefileUsage", "privateUsage"})
    public static final class Counters extends Structure {
        public int cb;
        public int pageFaultCount;
        public SIZE_T peakWorkingSetSize = new SIZE_T();
        public SIZE_T workingSetSize = new SIZE_T();
        public SIZE_T quotaPeakPagedPoolUsage = new SIZE_T();
        public SIZE_T quotaPagedPoolUsage = new SIZE_T();
        public SIZE_T quotaPeakNonPagedPoolUsage = new SIZE_T();
        public SIZE_T quotaNonPagedPoolUsage = new SIZE_T();
        public SIZE_T pagefileUsage = new SIZE_T();
        public SIZE_T peakPagefileUsage = new SIZE_T();
        public SIZE_T privateUsage = new SIZE_T();
        public Counters() { cb = size(); }
    }
}
