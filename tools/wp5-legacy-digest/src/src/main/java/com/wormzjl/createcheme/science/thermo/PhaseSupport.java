package com.wormzjl.createcheme.science.thermo;

/**
 * Which of the two hydrocarbon phases one component's amount may occupy, for a point whose support
 * was selected once from a full-basis reference and is then frozen.
 *
 * <p>{@code LIQUID_ONLY} does not say the component is absent from the vapor in nature; it says the
 * engine is not going to carry an unknown for a vapor amount whose mole fraction the reference put
 * below the cutoff. Such a component keeps its material balance - the whole of it is in the retained
 * phase - and loses its equilibrium row, which is the one row that needed the omitted amount.</p>
 */
public enum PhaseSupport {
    ABSENT, BOTH, LIQUID_ONLY, VAPOR_ONLY;

    public boolean liquid() { return this == BOTH || this == LIQUID_ONLY; }
    public boolean vapor() { return this == BOTH || this == VAPOR_ONLY; }
    /** One unknown and no equilibrium row: the component is present, in exactly one phase. */
    public boolean singlePhase() { return this == LIQUID_ONLY || this == VAPOR_ONLY; }

    /**
     * The support of a present component from one reference point's phase compositions: the phase
     * whose mole fraction is below the cutoff is dropped, unless the other one is below it too, in
     * which case the reference cannot say which phase the component belongs to and both are kept.
     * A zero cutoff keeps both, always.
     */
    public static PhaseSupport of(double liquidMoleFraction, double vaporMoleFraction, double cutoffMoleFraction) {
        if (!(cutoffMoleFraction > 0.0)) return BOTH;
        boolean liquidTrace = liquidMoleFraction < cutoffMoleFraction;
        boolean vaporTrace = vaporMoleFraction < cutoffMoleFraction;
        if (liquidTrace && !vaporTrace) return VAPOR_ONLY;
        if (vaporTrace && !liquidTrace) return LIQUID_ONLY;
        return BOTH;
    }
}
