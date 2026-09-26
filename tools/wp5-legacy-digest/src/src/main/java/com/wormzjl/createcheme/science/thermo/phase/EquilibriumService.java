package com.wormzjl.createcheme.science.thermo.phase;

/**
 * One equilibrium service per package contract, with the three specifications the plan names (section 4): TP for
 * Gibbs stability and equilibrium at fixed temperature and pressure, PH and UV for the energy-specified problems.
 *
 * <p>Contract checks come before any calculation, in this order, and each is a typed result, never an exception:
 * species outside the package ({@link EquilibriumResult.UnsupportedReason#SPECIES_NOT_IN_PACKAGE}); water chemistry
 * ({@link EquilibriumResult.UnsupportedReason#WATER_CHEMISTRY_NOT_MODELLED}: a hydrate competition, or water together
 * with a declared water-chemistry species); the phase competition
 * ({@link EquilibriumResult.UnsupportedReason#PHASE_COMPETITION_NOT_QUALIFIED}); the domain
 * ({@link EquilibriumResult.Status#OUT_OF_DOMAIN}); then what this implementation does not yet do
 * ({@link EquilibriumResult.UnsupportedReason#NOT_IMPLEMENTED}). Malformed requests are refused when the request is
 * built.</p>
 *
 * <p>A {@link Workspace} is caller-owned scratch for one thread; reusing it makes a call allocate little more than its
 * result, and results do not depend on what the workspace computed before.</p>
 */
public interface EquilibriumService {
    /** Opaque, caller-owned scratch of one service for one thread. */
    interface Workspace {}

    PhaseContract contract();

    Workspace newWorkspace();

    EquilibriumResult tp(EquilibriumRequest request, Workspace workspace);

    EquilibriumResult ph(EquilibriumRequest request, Workspace workspace);

    EquilibriumResult uv(EquilibriumRequest request, Workspace workspace);

    default EquilibriumResult tp(EquilibriumRequest request) { return tp(request, newWorkspace()); }

    default EquilibriumResult ph(EquilibriumRequest request) { return ph(request, newWorkspace()); }

    default EquilibriumResult uv(EquilibriumRequest request) { return uv(request, newWorkspace()); }
}
