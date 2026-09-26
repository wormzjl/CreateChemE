package com.wormzjl.createcheme.science.thermo.phase;

/**
 * What kind of phase a state is.
 *
 * <p>On a {@link PhaseState} the kind states the root situation the evaluator actually met, not a classification:
 * a cubic with three physical roots gives {@link #VAPOR} for its largest and {@link #LIQUID} for its smallest root;
 * a cubic with one physical root gives {@link #FLUID} whatever the density (a dilute gas, a compressed liquid or a
 * supercritical fluid alike), because one root alone does not say which. On a {@link PhaseAmounts} of an
 * {@link EquilibriumResult} the kind is the equilibrium's label of the phase (see there): from P3 a single phase with
 * one physical root is labelled {@link #VAPOR} or {@link #LIQUID} by its phase identification parameter
 * ({@link PhaseIdentification}), and {@link #FLUID} is kept for a pure supercritical fluid and for a near-critical
 * split merged into one fluid.</p>
 */
public enum PhaseKind {
    VAPOR,
    LIQUID,
    /** One physical root: dense, dilute or supercritical, not classified. */
    FLUID,
    /** A pure crystal; the state names the crystal. */
    SOLID,
    /**
     * The separate free-water liquid of {@link WaterParticipation#SEPARATE_FREE_WATER}: pure liquid water, never mixing
     * with the hydrocarbon phases (P3). Its state has the one-component basis {@code [Water]}.
     */
    FREE_WATER;

    public boolean fluid() { return this != SOLID; }
}
