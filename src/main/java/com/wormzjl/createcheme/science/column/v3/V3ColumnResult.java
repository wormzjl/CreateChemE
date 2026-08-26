package com.wormzjl.createcheme.science.column.v3;

import java.util.Objects;

/** Minimal immutable accepted-result envelope; physical profiles are added with the MESH solver. */
public final class V3ColumnResult {
    private final V3ColumnProblem problem;
    private final V3InputDigest inputDigest;
    private final V3AcceptanceAudit acceptanceAudit;
    private final V3ConvergenceEvidence convergenceEvidence;

    private V3ColumnResult(
            V3ColumnProblem problem, V3InputDigest inputDigest, V3AcceptanceAudit acceptanceAudit,
            V3ConvergenceEvidence convergenceEvidence) {
        this.problem = Objects.requireNonNull(problem, "problem");
        this.inputDigest = Objects.requireNonNull(inputDigest, "inputDigest");
        this.acceptanceAudit = Objects.requireNonNull(acceptanceAudit, "acceptanceAudit");
        this.convergenceEvidence = Objects.requireNonNull(convergenceEvidence, "convergenceEvidence");
        if (!acceptanceAudit.accepted() || !convergenceEvidence.satisfiesGates()) {
            throw new IllegalArgumentException("A V3 result requires a passing acceptance audit and convergence evidence");
        }
    }

    /** Package-private until the independent numerical audit owns the accepted-result gate. */
    static V3ColumnResult accepted(
            V3ColumnProblem problem, V3InputDigest inputDigest, V3AcceptanceAudit acceptanceAudit,
            V3ConvergenceEvidence convergenceEvidence) {
        return new V3ColumnResult(problem, inputDigest, acceptanceAudit, convergenceEvidence);
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

    public V3ConvergenceEvidence convergenceEvidence() {
        return convergenceEvidence;
    }
}
