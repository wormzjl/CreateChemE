package com.wormzjl.createcheme.science.column.v3;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.DoubleFunction;

/** Public-boundary retry policy; the supplied operation owns one complete condenser/continuation chain. */
final class V3TruncationFallback {
    private V3TruncationFallback() {}

    static V3ColumnOutcome calculate(double requestedCutoff, DoubleFunction<V3ColumnOutcome> attemptChain) {
        V3TruncationSupport.requireCutoff(requestedCutoff);
        Objects.requireNonNull(attemptChain, "attemptChain");
        V3ColumnOutcome first = Objects.requireNonNull(attemptChain.apply(requestedCutoff), "first outcome");
        if (requestedCutoff == 0.0 || first.isSuccess() || isAdmissionFailure(first)) return first;
        // Cancellation is intentionally not caught: the caller's deadline/cancellation contract still owns us.
        V3ColumnOutcome fallback = Objects.requireNonNull(attemptChain.apply(0.0), "fallback outcome");
        String summary = first.diagnostics().events().stream().filter(event -> event.startsWith("stage-trace cutoff="))
                .findFirst().orElse("stage-trace cutoff=" + requestedCutoff + "; support evidence unavailable");
        V3ColumnOutcome.Failure failure = (V3ColumnOutcome.Failure) first;
        String failedFamily = first.diagnostics().acceptanceAudit().checks().stream().filter(check -> !check.passed())
                .map(V3AcceptanceAudit.Check::family).findFirst().orElse("none");
        return prependEvents(fallback, List.of(summary, bounded("stage-trace fallback: retried untruncated after "
                + failure.code() + "; audit=" + failedFamily + "; outcome=" + (fallback.isSuccess() ? "success" : "failure"))));
    }

    static boolean isAdmissionFailure(V3ColumnOutcome outcome) {
        return outcome instanceof V3ColumnOutcome.Failure failure
                && (failure.code() == V3SolverFailureCode.INVALID_INPUT || failure.code() == V3SolverFailureCode.PROPERTY_OUT_OF_RANGE);
    }

    static V3ColumnOutcome prependEvents(V3ColumnOutcome outcome, List<String> leading) {
        List<String> events = new ArrayList<>();
        for (String event : leading) {
            if (events.size() == V3SolverDiagnostics.MAX_EVENTS) break;
            events.add(bounded(event));
        }
        for (String event : outcome.diagnostics().events()) {
            if (events.size() == V3SolverDiagnostics.MAX_EVENTS) break;
            events.add(event);
        }
        V3SolverDiagnostics previous = outcome.diagnostics();
        V3SolverDiagnostics diagnostics = new V3SolverDiagnostics(previous.initializerIterations(), previous.newtonIterations(),
                previous.residualEvaluations(), previous.linearSolves(), previous.maximumScaledResidual(), previous.finalStepNorm(),
                previous.solvePath(), events, previous.acceptanceAudit(), previous.convergenceEvidence());
        return outcome instanceof V3ColumnOutcome.Success success ? new V3ColumnOutcome.Success(success.result(), diagnostics)
                : new V3ColumnOutcome.Failure(((V3ColumnOutcome.Failure) outcome).code(),
                        ((V3ColumnOutcome.Failure) outcome).summary(), diagnostics);
    }

    private static String bounded(String event) { return event.length() <= 256 ? event : event.substring(0, 256); }
}
