package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.Objects;

/**
 * Compact, immutable presentation certificate for a committed accepted V3 result.
 *
 * <p>This deliberately excludes mutable solver workspaces and stage profiles. It is safe to persist and send as a
 * bounded screen summary, but it is not a numerical result and cannot be used as a warm start or success input.</p>
 */
public record V3ColumnDisplayResult(
        String inputDigest,
        String formulationRevision,
        String assumptionsRevision,
        String datasetRevision,
        int newtonIterations,
        double maximumScaledResidual,
        int acceptanceCheckCount) {
    public V3ColumnDisplayResult {
        inputDigest = boundedDigest(inputDigest);
        formulationRevision = boundedRevision(formulationRevision, "formulationRevision");
        assumptionsRevision = boundedRevision(assumptionsRevision, "assumptionsRevision");
        datasetRevision = boundedRevision(datasetRevision, "datasetRevision");
        if (newtonIterations < 0 || !Double.isFinite(maximumScaledResidual) || maximumScaledResidual < 0.0
                || acceptanceCheckCount < 1 || acceptanceCheckCount > 64) {
            throw new IllegalArgumentException("Invalid compact V3 display certificate");
        }
    }

    /** Creates the compact presentation certificate only from a rigorously accepted scientific result. */
    public static V3ColumnDisplayResult fromAccepted(V3ColumnOutcome.Success success) {
        success = Objects.requireNonNull(success, "success");
        V3ColumnResult result = success.result();
        return new V3ColumnDisplayResult(
                result.inputDigest().hexadecimalSha256(),
                V3ColumnCalculator.FORMULATION_REVISION,
                V3ColumnCalculator.ASSUMPTIONS_REVISION,
                V3PengRobinsonThermo.fromRegisteredPackage(result.problem().input().packageId()).datasetRevision(),
                success.diagnostics().newtonIterations(),
                success.diagnostics().maximumScaledResidual(),
                result.acceptanceAudit().checks().size());
    }

    private static String boundedDigest(String value) {
        value = Objects.requireNonNull(value, "inputDigest");
        if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("V3 display digest is invalid");
        return value;
    }

    private static String boundedRevision(String value, String name) {
        value = Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(name + " is outside the bounded display contract");
        }
        return value;
    }
}
