package com.wormzjl.createcheme.science.column.v3.thermo;

import java.util.Objects;

/**
 * Immutable phase-only support selected once from a full flash reference, never from an in-flight iterate.
 * Positive-overall components always retain at least one phase; ambiguous traces retain both.
 */
final class V3FlashPhaseSupport {
    enum PhaseSupport { ABSENT, BOTH, LIQUID_ONLY, VAPOR_ONLY }

    private final PhaseSupport[] support;
    private final int omittedLiquidCount;
    private final int omittedVaporCount;

    private V3FlashPhaseSupport(PhaseSupport[] support, int omittedLiquidCount, int omittedVaporCount) {
        this.support = support.clone();
        this.omittedLiquidCount = omittedLiquidCount;
        this.omittedVaporCount = omittedVaporCount;
    }

    static V3FlashPhaseSupport derive(
            double[] normalizedOverall, V3FlashResult reference, V3TraceTruncationPolicy policy) {
        Objects.requireNonNull(normalizedOverall, "normalizedOverall");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(policy, "policy");
        if (normalizedOverall.length == 0) throw new IllegalArgumentException("V3 flash support needs a component basis");
        double total = 0.0;
        for (double value : normalizedOverall) {
            if (!Double.isFinite(value) || value < 0.0) {
                throw new IllegalArgumentException("V3 flash support needs a finite nonnegative overall composition");
            }
            total += value;
        }
        if (!Double.isFinite(total) || Math.abs(total - 1.0) > 1.0e-10) {
            throw new IllegalArgumentException("V3 flash support needs an already normalized overall composition");
        }
        double[] liquid = reference.liquidComposition();
        double[] vapor = reference.vaporComposition();
        if ((reference.phase() != V3FeedPhase.VAPOR && liquid.length != normalizedOverall.length)
                || (reference.phase() != V3FeedPhase.LIQUID && vapor.length != normalizedOverall.length)) {
            throw new IllegalArgumentException("V3 flash reference phases differ from the overall component basis");
        }
        PhaseSupport[] support = new PhaseSupport[normalizedOverall.length];
        int omittedLiquid = 0;
        int omittedVapor = 0;
        for (int component = 0; component < support.length; component++) {
            if (normalizedOverall[component] == 0.0) {
                support[component] = PhaseSupport.ABSENT;
            } else if (reference.phase() == V3FeedPhase.LIQUID) {
                support[component] = PhaseSupport.LIQUID_ONLY;
            } else if (reference.phase() == V3FeedPhase.VAPOR) {
                support[component] = PhaseSupport.VAPOR_ONLY;
            } else {
                boolean liquidTrace = policy.enabled() && liquid[component] < policy.cutoffMoleFraction();
                boolean vaporTrace = policy.enabled() && vapor[component] < policy.cutoffMoleFraction();
                if (liquidTrace && !vaporTrace) {
                    support[component] = PhaseSupport.VAPOR_ONLY;
                    omittedLiquid++;
                } else if (vaporTrace && !liquidTrace) {
                    support[component] = PhaseSupport.LIQUID_ONLY;
                    omittedVapor++;
                } else {
                    support[component] = PhaseSupport.BOTH;
                }
            }
        }
        return new V3FlashPhaseSupport(support, omittedLiquid, omittedVapor);
    }

    int componentCount() { return support.length; }
    PhaseSupport phaseSupport(int component) { return support[component]; }
    boolean isIdentity() { return omittedLiquidCount == 0 && omittedVaporCount == 0; }
    int omittedLiquidCount() { return omittedLiquidCount; }
    int omittedVaporCount() { return omittedVaporCount; }
}
