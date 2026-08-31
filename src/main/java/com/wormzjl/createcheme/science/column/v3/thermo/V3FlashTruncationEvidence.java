package com.wormzjl.createcheme.science.column.v3.thermo;

import java.util.Objects;

/**
 * Immutable reference-versus-candidate flash evidence; unavailable errors are explicitly distinguished from zero.
 *
 * <p>Allocation error is the L1 sum over liquid and vapor component amounts normalized to overall feed.
 * Beta and phase-composition errors are absolute mole-fraction differences. Material closure is the maximum
 * absolute component-allocation defect; its per-component roundoff guard is independent of the cutoff.
 * Enthalpy error and reference enthalpy are J/mol. Iterations count reference and reduced work separately.</p>
 */
public record V3FlashTruncationEvidence(
        Status status,
        double cutoffMoleFraction,
        int omittedLiquidComponents,
        int omittedVaporComponents,
        int referenceIterations,
        int reducedIterations,
        boolean errorsEvaluated,
        double allocationError,
        double betaError,
        double maxPhaseCompositionError,
        double maxMaterialClosureError,
        double enthalpyErrorJoulesPerMol,
        double referenceMolarEnthalpyJoulesPerMol,
        String detail) {
    public static final V3FlashTruncationEvidence DISABLED = new V3FlashTruncationEvidence(
            Status.DISABLED, 0.0, 0, 0, 0, 0, false, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
            "flash truncation disabled");

    public enum Status { DISABLED, NO_CANDIDATES, SINGLE_PHASE, APPLIED, FALLBACK }

    public V3FlashTruncationEvidence {
        Objects.requireNonNull(status, "status");
        V3TraceTruncationPolicy.requireCutoff(cutoffMoleFraction);
        if (cutoffMoleFraction == 0.0) cutoffMoleFraction = 0.0;
        if (omittedLiquidComponents < 0 || omittedVaporComponents < 0
                || referenceIterations < 0 || reducedIterations < 0
                || (long) referenceIterations + reducedIterations > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("V3 flash truncation counters must be nonnegative and bounded");
        }
        requireError(allocationError);
        requireError(betaError);
        requireError(maxPhaseCompositionError);
        requireError(maxMaterialClosureError);
        requireError(enthalpyErrorJoulesPerMol);
        if (!Double.isFinite(referenceMolarEnthalpyJoulesPerMol)) {
            throw new IllegalArgumentException("V3 flash reference molar enthalpy must be finite");
        }
        detail = Objects.requireNonNull(detail, "detail");
        if (detail.isBlank() || detail.length() > 256) {
            throw new IllegalArgumentException("V3 flash truncation detail must be nonblank and at most 256 characters");
        }
        if (!errorsEvaluated && (allocationError != 0.0 || betaError != 0.0 || maxPhaseCompositionError != 0.0
                || maxMaterialClosureError != 0.0 || enthalpyErrorJoulesPerMol != 0.0)) {
            throw new IllegalArgumentException("Unevaluated V3 flash errors cannot contain numerical measurements");
        }
        boolean omissions = omittedLiquidComponents != 0 || omittedVaporComponents != 0;
        if (status == Status.DISABLED) {
            if (cutoffMoleFraction != 0.0 || omissions || reducedIterations != 0 || errorsEvaluated) {
                throw new IllegalArgumentException("Disabled V3 flash truncation cannot contain reduced work or a cutoff");
            }
        } else if (cutoffMoleFraction == 0.0) {
            throw new IllegalArgumentException("Enabled V3 flash truncation evidence requires a positive cutoff");
        }
        if ((status == Status.NO_CANDIDATES || status == Status.SINGLE_PHASE)
                && (omissions || reducedIterations != 0 || errorsEvaluated)) {
            throw new IllegalArgumentException("Reference-only V3 flash evidence cannot contain reduced work");
        }
        if (status == Status.APPLIED) {
            if (!omissions || !errorsEvaluated) {
                throw new IllegalArgumentException("Applied V3 flash truncation needs omissions and evaluated errors");
            }
            V3TraceTruncationPolicy policy = V3TraceTruncationPolicy.of(cutoffMoleFraction);
            if (allocationError > policy.maximumPhaseAllocationError()
                    || betaError > policy.maximumVaporFractionError()
                    || maxPhaseCompositionError > policy.maximumPhaseCompositionError()
                    || enthalpyErrorJoulesPerMol > policy.maximumEnthalpyErrorJoulesPerMol(referenceMolarEnthalpyJoulesPerMol)) {
                throw new IllegalArgumentException("Applied V3 flash truncation exceeds its reference-error budget");
            }
        }
    }

    private static void requireError(double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException("V3 flash truncation error measurements must be finite and nonnegative");
        }
    }
}
