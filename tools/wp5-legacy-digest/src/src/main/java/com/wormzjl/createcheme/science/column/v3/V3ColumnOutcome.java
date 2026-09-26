package com.wormzjl.createcheme.science.column.v3;

import java.util.Objects;

/** A V3 solve either exposes an accepted immutable result or a bounded typed failure. */
public sealed interface V3ColumnOutcome permits V3ColumnOutcome.Success, V3ColumnOutcome.Failure {
    V3SolverDiagnostics diagnostics();

    default boolean isSuccess() {
        return this instanceof Success;
    }

    record Success(V3ColumnResult result, V3SolverDiagnostics diagnostics) implements V3ColumnOutcome {
        public Success {
            result = Objects.requireNonNull(result, "result");
            diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
            if (!result.acceptanceAudit().accepted() || !diagnostics.acceptanceAudit().accepted()
                    || !result.acceptanceAudit().equals(diagnostics.acceptanceAudit())
                    || !result.convergenceEvidence().satisfiesGates()
                    || !diagnostics.convergenceEvidence().satisfiesGates()
                    || !result.convergenceEvidence().equals(diagnostics.convergenceEvidence())) {
                throw new IllegalArgumentException("A V3 success requires matching passing acceptance and convergence evidence");
            }
        }
    }

    record Failure(V3SolverFailureCode code, String summary, V3SolverDiagnostics diagnostics)
            implements V3ColumnOutcome {
        public Failure {
            code = Objects.requireNonNull(code, "code");
            summary = Objects.requireNonNull(summary, "summary");
            diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
            if (summary.isBlank() || summary.length() > 512) {
                throw new IllegalArgumentException("V3 failure summary is blank or exceeds the bounded contract");
            }
        }
    }
}
