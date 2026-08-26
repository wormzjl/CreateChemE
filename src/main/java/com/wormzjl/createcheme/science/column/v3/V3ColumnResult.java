package com.wormzjl.createcheme.science.column.v3;

import java.util.Objects;

/** Minimal immutable accepted-result envelope; physical profiles are added with the MESH solver. */
public final class V3ColumnResult {
    private final V3ColumnProblem problem;
    private final V3InputDigest inputDigest;
    private final V3AcceptanceAudit acceptanceAudit;

    private V3ColumnResult(V3ColumnProblem problem, V3InputDigest inputDigest, V3AcceptanceAudit acceptanceAudit) {
        this.problem = Objects.requireNonNull(problem, "problem");
        this.inputDigest = Objects.requireNonNull(inputDigest, "inputDigest");
        this.acceptanceAudit = Objects.requireNonNull(acceptanceAudit, "acceptanceAudit");
        if (!acceptanceAudit.accepted()) {
            throw new IllegalArgumentException("A V3 result cannot be constructed from a failed acceptance audit");
        }
    }

    /** Package-private until the independent numerical audit owns the accepted-result gate. */
    static V3ColumnResult accepted(
            V3ColumnProblem problem, V3InputDigest inputDigest, V3AcceptanceAudit acceptanceAudit) {
        return new V3ColumnResult(problem, inputDigest, acceptanceAudit);
    }

    public V3ColumnProblem problem() {
        return problem;
    }

    public V3InputDigest inputDigest() {
        return inputDigest;
    }

    public V3AcceptanceAudit acceptanceAudit() {
        return acceptanceAudit;
    }
}
