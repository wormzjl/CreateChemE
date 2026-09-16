package com.wormzjl.createcheme.science.thermo;

/**
 * Immutable request-level trace cutoff, expressed as a mole fraction rather than mol%.
 *
 * <p>Flash guards bound approximation relative to an independently calculated, unmasked reference.
 * They do not permit loss of authored material: component closure has a separate roundoff-only guard.
 * A zero cutoff is the exact off switch, including zero error budgets.</p>
 *
 * <p>{@link #MAX_CUTOFF_MOLE_FRACTION} is the absolute ceiling of the type, which is the column's:
 * a V3 request declares explicit error budgets and verifies a truncated flash against an unmasked
 * reference, so it may ask for a percent. An engine with no such reference has to be stricter, and
 * caps the value it accepts before it builds a policy - the fluid network at
 * {@code FluidThermodynamics.MAX_TRACE_CUTOFF_MOLE_FRACTION}, a thousand times smaller.</p>
 */
public record TraceTruncationPolicy(double cutoffMoleFraction) {
    public static final double MAX_CUTOFF_MOLE_FRACTION = 1.0e-2;
    public static final double MAX_CUTOFF = MAX_CUTOFF_MOLE_FRACTION;
    public static final TraceTruncationPolicy OFF = new TraceTruncationPolicy(0.0);

    public TraceTruncationPolicy {
        requireCutoff(cutoffMoleFraction);
        if (cutoffMoleFraction == 0.0) cutoffMoleFraction = 0.0;
    }

    public static TraceTruncationPolicy of(double cutoffMoleFraction) {
        requireCutoff(cutoffMoleFraction);
        return cutoffMoleFraction == 0.0 ? OFF : new TraceTruncationPolicy(cutoffMoleFraction);
    }

    public static void requireCutoff(double cutoffMoleFraction) {
        requireCutoff(cutoffMoleFraction, MAX_CUTOFF_MOLE_FRACTION);
    }

    /** A caller's own ceiling, at or below {@link #MAX_CUTOFF_MOLE_FRACTION}. */
    public static void requireCutoff(double cutoffMoleFraction, double maximumCutoffMoleFraction) {
        if (!(maximumCutoffMoleFraction > 0.0) || maximumCutoffMoleFraction > MAX_CUTOFF_MOLE_FRACTION) {
            throw new IllegalArgumentException("A trace cutoff ceiling must be in (0, " + MAX_CUTOFF_MOLE_FRACTION + "]");
        }
        if (!Double.isFinite(cutoffMoleFraction) || cutoffMoleFraction < 0.0
                || cutoffMoleFraction > maximumCutoffMoleFraction) {
            throw new IllegalArgumentException("Trace cutoff must be finite and in [0, "
                    + maximumCutoffMoleFraction + "] mole fraction, not " + cutoffMoleFraction);
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
