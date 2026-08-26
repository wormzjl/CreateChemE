package com.wormzjl.createcheme.science.column.nextgen;

import java.util.List;
import java.util.Objects;

/** Bounded diagnostic evidence emitted with either a successful or failed dry solve. */
public record DrySolverDiagnostics(
        int outerIterations,
        int innerIterations,
        int thomasSolves,
        int energyThomasSolves,
        int propertyPhaseEvaluations,
        double maximumThomasBackwardError,
        double maximumInnerSumRatesResidual,
        double maximumInnerEnergyResidual,
        double finalFlowStateChange,
        double finalTemperatureStateChangeKelvin,
        String recoveryPath,
        List<String> events,
        DryAcceptanceAudit acceptanceAudit) {
    public static final int MAX_EVENTS = 32;

    public DrySolverDiagnostics {
        if (outerIterations < 0 || innerIterations < 0 || thomasSolves < 0 || energyThomasSolves < 0
                || propertyPhaseEvaluations < 0) {
            throw new IllegalArgumentException("Iteration and property counters cannot be negative");
        }
        if (!Double.isFinite(maximumThomasBackwardError) || !Double.isFinite(maximumInnerSumRatesResidual)
                || !Double.isFinite(maximumInnerEnergyResidual) || !Double.isFinite(finalFlowStateChange)
                || !Double.isFinite(finalTemperatureStateChangeKelvin)) {
            throw new IllegalArgumentException("Diagnostics must be finite");
        }
        recoveryPath = Objects.requireNonNull(recoveryPath, "recoveryPath");
        events = List.copyOf(events);
        if (events.size() > MAX_EVENTS || events.stream().anyMatch(event -> event == null || event.length() > 256)) {
            throw new IllegalArgumentException("Diagnostic events exceed the bounded contract");
        }
        acceptanceAudit = Objects.requireNonNull(acceptanceAudit, "acceptanceAudit");
    }
}
