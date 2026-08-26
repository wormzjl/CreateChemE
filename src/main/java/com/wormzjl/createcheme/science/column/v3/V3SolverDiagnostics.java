package com.wormzjl.createcheme.science.column.v3;

import java.util.List;
import java.util.Objects;

/** Bounded immutable numerical evidence included with every V3 terminal outcome. */
public record V3SolverDiagnostics(
        int initializerIterations,
        int newtonIterations,
        int residualEvaluations,
        int linearSolves,
        double maximumScaledResidual,
        double finalStepNorm,
        String solvePath,
        List<String> events,
        V3AcceptanceAudit acceptanceAudit,
        V3ConvergenceEvidence convergenceEvidence) {
    public static final int MAX_EVENTS = 32;

    public V3SolverDiagnostics {
        if (initializerIterations < 0 || newtonIterations < 0 || residualEvaluations < 0 || linearSolves < 0) {
            throw new IllegalArgumentException("V3 iteration and evaluation counters cannot be negative");
        }
        if (!Double.isFinite(maximumScaledResidual) || maximumScaledResidual < 0.0
                || !Double.isFinite(finalStepNorm) || finalStepNorm < 0.0) {
            throw new IllegalArgumentException("V3 diagnostic norms must be finite and nonnegative");
        }
        solvePath = bounded(solvePath, "solvePath", 128);
        events = List.copyOf(events);
        if (events.size() > MAX_EVENTS || events.stream().anyMatch(event -> event == null || event.length() > 256)) {
            throw new IllegalArgumentException("V3 diagnostic events exceed the bounded contract");
        }
        acceptanceAudit = Objects.requireNonNull(acceptanceAudit, "acceptanceAudit");
        convergenceEvidence = Objects.requireNonNull(convergenceEvidence, "convergenceEvidence");
    }

    private static String bounded(String value, String name, int maximumLength) {
        value = Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " is blank or exceeds the bounded contract");
        }
        return value;
    }
}
