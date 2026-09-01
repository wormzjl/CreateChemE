package com.wormzjl.createcheme.science.column.v3.thermo;

/**
 * Immutable request-level trace cutoff, expressed as a mole fraction rather than mol%.
 *
 * <p>Flash guards bound approximation relative to an independently calculated, unmasked reference.
 * They do not permit loss of authored material: component closure has a separate roundoff-only guard.
 * A zero cutoff is the exact off switch, including zero error budgets.</p>
 */
public record V3TraceTruncationPolicy(double cutoffMoleFraction) {
    public static final double MAX_CUTOFF_MOLE_FRACTION = 1.0e-2;
    public static final double MAX_CUTOFF = MAX_CUTOFF_MOLE_FRACTION;
    public static final V3TraceTruncationPolicy OFF = new V3TraceTruncationPolicy(0.0);

    public V3TraceTruncationPolicy {
        requireCutoff(cutoffMoleFraction);
        if (cutoffMoleFraction == 0.0) cutoffMoleFraction = 0.0;
    }

    public static V3TraceTruncationPolicy of(double cutoffMoleFraction) {
        requireCutoff(cutoffMoleFraction);
        return cutoffMoleFraction == 0.0 ? OFF : new V3TraceTruncationPolicy(cutoffMoleFraction);
    }

    public static void requireCutoff(double cutoffMoleFraction) {
        if (!Double.isFinite(cutoffMoleFraction) || cutoffMoleFraction < 0.0
                || cutoffMoleFraction > MAX_CUTOFF_MOLE_FRACTION) {
            throw new IllegalArgumentException("V3 trace cutoff must be finite and in [0, 0.01] mole fraction");
        }
    }

    public boolean enabled() {
        return cutoffMoleFraction > 0.0;
    }

    /** Sum of absolute liquid and vapor component-allocation errors, normalized to overall feed. */
    public double maximumPhaseAllocationError() {
        return 8.0 * cutoffMoleFraction;
    }

    public double maximumVaporFractionError() {
        return 8.0 * cutoffMoleFraction;
    }

    /** Maximum absolute component mole-fraction error within either present phase. */
    public double maximumPhaseCompositionError() {
        return cutoffMoleFraction;
    }

    public double maximumEnthalpyErrorJoulesPerMol(double referenceMolarEnthalpyJoulesPerMol) {
        if (!Double.isFinite(referenceMolarEnthalpyJoulesPerMol)) {
            throw new IllegalArgumentException("V3 flash reference molar enthalpy must be finite");
        }
        if (!enabled()) return 0.0;
        return Math.max(1.0e-6, 8.0 * cutoffMoleFraction
                * Math.max(1.0, Math.abs(referenceMolarEnthalpyJoulesPerMol)));
    }
}
