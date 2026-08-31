package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.Objects;
import java.util.List;

/** Minimal immutable accepted-result envelope; physical profiles are added with the MESH solver. */
public final class V3ColumnResult {
    private final V3ColumnProblem problem;
    private final V3InputDigest inputDigest;
    private final V3AcceptanceAudit acceptanceAudit;
    private final V3ConvergenceEvidence convergenceEvidence;
    private final List<V3ColumnStreamProperties> streams;
    private final String formulationRevision;

    private V3ColumnResult(
            V3ColumnProblem problem, V3InputDigest inputDigest, V3AcceptanceAudit acceptanceAudit,
            V3ConvergenceEvidence convergenceEvidence, List<V3ColumnStreamProperties> streams, String formulationRevision) {
        this.problem = Objects.requireNonNull(problem, "problem");
        this.inputDigest = Objects.requireNonNull(inputDigest, "inputDigest");
        this.acceptanceAudit = Objects.requireNonNull(acceptanceAudit, "acceptanceAudit");
        this.convergenceEvidence = Objects.requireNonNull(convergenceEvidence, "convergenceEvidence");
        this.streams = List.copyOf(Objects.requireNonNull(streams, "streams"));
        this.formulationRevision = Objects.requireNonNull(formulationRevision, "formulationRevision");
        if (formulationRevision.isBlank() || formulationRevision.length() > 128) {
            throw new IllegalArgumentException("V3 result formulation revision is outside the bounded contract");
        }
        if (this.streams.size() > V3ColumnStreamProperties.MAX_STREAMS) {
            throw new IllegalArgumentException("V3 result exceeds the bounded accepted stream contract");
        }
        if (!acceptanceAudit.accepted() || !convergenceEvidence.satisfiesGates()) {
            throw new IllegalArgumentException("A V3 result requires a passing acceptance audit and convergence evidence");
        }
    }

    /** Package-private until the independent numerical audit owns the accepted-result gate. */
    static V3ColumnResult accepted(
            V3ColumnProblem problem, V3InputDigest inputDigest, V3AcceptanceAudit acceptanceAudit,
            V3ConvergenceEvidence convergenceEvidence) {
        return new V3ColumnResult(problem, inputDigest, acceptanceAudit, convergenceEvidence, List.of(),
                V3ColumnCalculator.formulationRevision(problem.truncationSupport().cutoffMoleFraction()));
    }

    /** Extracts product properties only from the rigorously accepted final MESH state. */
    static V3ColumnResult accepted(
            V3ColumnProblem problem, V3InputDigest inputDigest, V3AcceptanceAudit acceptanceAudit,
            V3ConvergenceEvidence convergenceEvidence, V3DryMeshState state, V3PengRobinsonThermo thermo) {
        return accepted(problem, inputDigest, acceptanceAudit, convergenceEvidence, state, thermo,
                V3ColumnCalculator.formulationRevision(problem.truncationSupport().cutoffMoleFraction()));
    }

    static V3ColumnResult accepted(
            V3ColumnProblem problem, V3InputDigest inputDigest, V3AcceptanceAudit acceptanceAudit,
            V3ConvergenceEvidence convergenceEvidence, V3DryMeshState state, V3PengRobinsonThermo thermo,
            String formulationRevision) {
        return new V3ColumnResult(problem, inputDigest, acceptanceAudit, convergenceEvidence,
                V3ColumnStreamProperties.fromAccepted(problem, state, thermo), formulationRevision);
    }

    public V3ColumnProblem problem() {
        return problem;
    }

    public V3InputDigest inputDigest() {
        return inputDigest;
    }

    /** Revision used for this result's digest, including a cutoff-enabled request's untruncated retry. */
    public String formulationRevision() {
        return formulationRevision;
    }

    public V3AcceptanceAudit acceptanceAudit() {
        return acceptanceAudit;
    }

    public V3ConvergenceEvidence convergenceEvidence() {
        return convergenceEvidence;
    }

    public List<V3ColumnStreamProperties> streams() {
        return streams;
    }
}
