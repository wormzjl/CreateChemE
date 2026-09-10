package com.wormzjl.createcheme.science.column.v3;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Serial diagnostic observer used only by generated, instrumented solver classes. */
public final class V3ContinuationProfile {
    public static final int RESIDUAL = 0, LOCAL_TERMS = 1, FUGACITY = 2, FD = 3, LOCAL_JACOBIAN = 4,
            LINEAR = 5, LINE_TRIAL = 6, LOCAL_REJECTED = 7, VERIFY = 8, ITERATION_STATES = 9;
    private static final String[] COUNTERS = {"residualEvaluations", "localTermEvaluations", "prPhaseEvaluations",
            "finiteDifferenceJacobians", "localJacobians", "linearSolves", "lineSearchTrials",
            "rejectedLocalDirections", "finalVerificationCalls", "observedNewtonStates"};
    private static final ThreadMXBean BEAN = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static Session active;
    private static Scope current;

    private V3ContinuationProfile() {}

    static Session start(String label) {
        if (active != null) throw new IllegalStateException("Profiling is serial");
        BEAN.setThreadAllocatedMemoryEnabled(true);
        active = new Session(label);
        return active;
    }

    public static Scope enter(String kind, V3ColumnProblem problem, String path, int limit, String budget) {
        if (active == null) throw new IllegalStateException("Missing diagnostic session");
        Scope scope = new Scope(kind, problem, path, limit, budget);
        current = scope;
        return scope;
    }

    public static void count(int slot) { if (current != null) current.counters[slot]++; }

    static void published(V3ColumnProblem problem, V3DryMeshState state, V3ColumnResult result) {
        if (active != null) active.saved = new Saved(problem, state, result);
    }

    record Saved(V3ColumnProblem problem, V3DryMeshState state, V3ColumnResult result) {}

    static final class Session {
        final String label;
        final List<Scope> scopes = new ArrayList<>();
        Saved saved;
        Session(String label) { this.label = label; }

        Map<String, Object> finish() {
            if (current != null) throw new IllegalStateException("Unclosed diagnostic scopes");
            var report = new LinkedHashMap<String, Object>();
            report.put("label", label);
            report.put("scopes", scopes.stream().map(scope -> scope.data).toList());
            active = null;
            return report;
        }
    }

    public static final class Scope implements AutoCloseable {
        private final Scope parent = current;
        private final long[] counters = new long[COUNTERS.length];
        private final Map<String, Object> data = new LinkedHashMap<>();
        private final long startBytes;
        private final long startNanos;
        private long childrenBytes;
        private long childrenNanos;
        private long totalIterations;
        private final String path;

        private Scope(String kind, V3ColumnProblem problem, String requestedPath, int limit, String budget) {
            path = requestedPath != null ? requestedPath : parent == null ? active.label : parent.path;
            int id = active.scopes.size();
            data.put("id", id);
            data.put("parent", parent == null ? -1 : parent.data.get("id"));
            data.put("kind", kind);
            data.put("path", path);
            data.put("iterationLimit", limit);
            data.put("budget", budget);
            if (problem != null) {
                var input = problem.input();
                data.put("trays", input.stageCount());
                data.put("unknowns", problem.degreeOfFreedomLedger().unknownCount());
                data.put("pressurePa", input.topPressurePascal());
                data.put("feedTemperatureK", input.feedTemperatureKelvin());
                data.put("steamMolPerSecond", input.steamFeeds().stream().mapToDouble(V3SteamFeedSpec::molarFlowMolPerSecond).sum());
                data.put("coolingWatts", input.pumparounds().stream().mapToDouble(V3PumparoundSpec::dutyWatts).sum());
                data.put("sideDrawMolPerSecond", input.sideDraws().stream().mapToDouble(V3SideDrawSpec::molarFlowMolPerSecond).sum());
                data.put("wetTrays", problem.wetTraySet().wetTrayCount());
            }
            active.scopes.add(this);
            startBytes = BEAN.getThreadAllocatedBytes(Thread.currentThread().threadId());
            startNanos = System.nanoTime();
        }

        public void iteration(int index, double residual, double merit) {
            counters[ITERATION_STATES]++;
            data.putIfAbsent("initialResidual", residual);
            data.put("lastObservedResidual", residual);
            data.put("lastObservedIteration", index);
        }

        public <T extends V3SimultaneousColumnSolver.Attempt> T finishAttempt(T attempt) {
            totalIterations += attempt.evidence().iterations();
            data.put("outcome", attempt instanceof V3SimultaneousColumnSolver.Attempt.Converged
                    ? "CONVERGED" : ((V3SimultaneousColumnSolver.Attempt.Failure) attempt).code());
            data.put("finalResidual", attempt.evidence().maximumScaledResidual());
            data.put("termination", attempt.evidence().termination());
            return attempt;
        }

        public void finishPass(V3SimultaneousColumnSolver.Attempt attempt, V3AcceptanceAudit audit) {
            data.put("outcome", attempt instanceof V3SimultaneousColumnSolver.Attempt.Converged ? "CONVERGED"
                    : ((V3SimultaneousColumnSolver.Attempt.Failure) attempt).code());
            data.put("auditAccepted", audit.accepted());
            data.put("finalResidual", attempt.evidence().maximumScaledResidual());
        }

        @Override public void close() {
            long elapsed = System.nanoTime() - startNanos;
            long bytes = BEAN.getThreadAllocatedBytes(Thread.currentThread().threadId()) - startBytes;
            if (current != this) throw new IllegalStateException("Profiling scope mismatch");
            data.put("allocatedBytes", bytes);
            data.put("exclusiveAllocatedBytes", bytes - childrenBytes);
            data.put("wallMs", elapsed / 1e6);
            data.put("exclusiveWallMs", (elapsed - childrenNanos) / 1e6);
            data.put("newtonIterations", totalIterations);
            for (int slot = 0; slot < counters.length; slot++) data.put(COUNTERS[slot], counters[slot]);
            current = parent;
            if (parent != null) {
                parent.childrenBytes += bytes;
                parent.childrenNanos += elapsed;
                parent.totalIterations += totalIterations;
                for (int slot = 0; slot < counters.length; slot++) parent.counters[slot] += counters[slot];
            }
        }
    }
}
