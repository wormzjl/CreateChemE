package com.wormzjl.createcheme.science.column.v3;

import java.util.Objects;

/** Typed outcome from a sequential preconditioner; normal inapplicability is not an exception. */
sealed interface V3PreconditionerResult
        permits V3PreconditionerResult.Prepared, V3PreconditionerResult.NotApplicable, V3PreconditionerResult.Failed {
    V3PreconditionerEvidence evidence();

    /** A finite candidate state for a later rigorous MESH correction. */
    record Prepared(V3DryMeshState state, V3PreconditionerEvidence evidence) implements V3PreconditionerResult {
        public Prepared {
            state = Objects.requireNonNull(state, "state");
            evidence = Objects.requireNonNull(evidence, "evidence");
        }
    }

    /** The strategy is not valid for this physical/topological state and must not invent a seed. */
    record NotApplicable(V3PreconditionerFailure reason, V3PreconditionerEvidence evidence)
            implements V3PreconditionerResult {
        public NotApplicable {
            reason = Objects.requireNonNull(reason, "reason");
            evidence = Objects.requireNonNull(evidence, "evidence");
        }
    }

    /** The strategy was applicable but did not create a finite usable candidate. */
    record Failed(V3PreconditionerFailure reason, V3PreconditionerEvidence evidence)
            implements V3PreconditionerResult {
        public Failed {
            reason = Objects.requireNonNull(reason, "reason");
            evidence = Objects.requireNonNull(evidence, "evidence");
        }
    }
}
