package com.wormzjl.createcheme.science.column.nextgen;

import java.util.Objects;

/** A dry solve either yields a fully accepted immutable result or a typed non-success outcome. */
public sealed interface DryColumnOutcome permits DryColumnOutcome.Success, DryColumnOutcome.Failure {
    DrySolverDiagnostics diagnostics();

    default boolean isSuccess() {
        return this instanceof Success;
    }

    record Success(DryColumnResult result, DrySolverDiagnostics diagnostics) implements DryColumnOutcome {
        public Success {
            result = Objects.requireNonNull(result, "result");
            diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
            if (!diagnostics.acceptanceAudit().accepted()) {
                throw new IllegalArgumentException("A successful outcome requires a passing acceptance audit");
            }
        }
    }

    record Failure(DrySolverFailureCode code, String summary, DrySolverDiagnostics diagnostics)
            implements DryColumnOutcome {
        public Failure {
            code = Objects.requireNonNull(code, "code");
            summary = Objects.requireNonNull(summary, "summary");
            diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
            if (summary.isBlank() || summary.length() > 512) {
                throw new IllegalArgumentException("Failure summary is blank or exceeds the bounded contract");
            }
        }
    }
}
