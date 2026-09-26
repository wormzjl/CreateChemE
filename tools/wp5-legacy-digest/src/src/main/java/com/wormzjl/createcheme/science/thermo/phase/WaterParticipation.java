package com.wormzjl.createcheme.science.thermo.phase;

/**
 * How water takes part in a package's equilibrium (plan P2: "decide how water participates").
 *
 * <p>The decision encoded in P2: water is either absent from the package or the existing separate free-water
 * approximation. No P2 package puts water into the equation of state, and nothing in P2 models aqueous chemistry.
 * A request that would need it is refused with
 * {@link EquilibriumResult.UnsupportedReason#WATER_CHEMISTRY_NOT_MODELLED}: water co-present with a species the
 * package declares water-reactive ({@link PhaseContract#waterChemistrySpecies()}, e.g. ammonia, whose dissolution
 * and hydrates the approximation cannot describe), or a competition with gas hydrates.</p>
 */
public enum WaterParticipation {
    /** Water is not a component of the package; a request carrying water names a species outside the package. */
    NONE,
    /**
     * Water is a basis component that forms its own pure liquid, never dissolved in the hydrocarbon phases and never
     * dissolving them (the fluid network's model since the free-water batch). Qualified only inside the water model's
     * declared domain (from its triple point, 273.16 K, ice not modelled, to its enthalpy correlation's limit) and
     * only for species declared immiscible with water; it describes no ammonia dissolution, no aqueous non-ideality
     * and no hydrates.
     */
    SEPARATE_FREE_WATER
}
