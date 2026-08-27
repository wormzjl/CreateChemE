package com.wormzjl.createcheme.science.column.v3;

import java.util.Objects;

/** Bounded immutable evidence from one sequential seed attempt. */
record V3PreconditionerEvidence(V3PreconditionerId id, int sweeps, String detail) {
    V3PreconditionerEvidence {
        id = Objects.requireNonNull(id, "id");
        if (sweeps < 0) throw new IllegalArgumentException("V3 preconditioner sweeps cannot be negative");
        detail = Objects.requireNonNull(detail, "detail");
        if (detail.isBlank() || detail.length() > 256) {
            throw new IllegalArgumentException("V3 preconditioner evidence detail is invalid");
        }
    }
}
