package com.wormzjl.createcheme.science.column.v3;

import java.util.Locale;
import java.util.Objects;

/** Immutable component-level condenser phase rule for one resolved V3 problem. */
final class V3CondenserComponentPhases {
    private final boolean[] liquidAtCondenser;

    private V3CondenserComponentPhases(boolean[] liquidAtCondenser) {
        this.liquidAtCondenser = liquidAtCondenser.clone();
    }

    static V3CondenserComponentPhases from(V3ActiveComponentBasis activeComponentBasis) {
        activeComponentBasis = Objects.requireNonNull(activeComponentBasis, "activeComponentBasis");
        boolean[] liquid = new boolean[activeComponentBasis.componentCount()];
        for (int component = 0; component < liquid.length; component++) {
            String id = activeComponentBasis.publicBasis().componentId(activeComponentBasis.publicIndex(component))
                    .toLowerCase(Locale.ROOT);
            liquid[component] = !id.equals("methane") && !id.equals("ethane");
        }
        return new V3CondenserComponentPhases(liquid);
    }

    static V3CondenserComponentPhases allLiquid(int componentCount) {
        if (componentCount < 1) throw new IllegalArgumentException("V3 condenser component count must be positive");
        boolean[] liquid = new boolean[componentCount];
        java.util.Arrays.fill(liquid, true);
        return new V3CondenserComponentPhases(liquid);
    }

    boolean hasLiquid(V3ColumnTopology topology, int node, int component) {
        requireComponent(component);
        return node != topology.condenserNode()
                || (topology.hasLiquidPhase(node) && liquidAtCondenser[component]);
    }

    boolean hasVaporLiquidEquilibrium(V3ColumnTopology topology, int node, int component) {
        return hasLiquid(topology, node, component);
    }

    boolean isVaporOnlyAtCondenser(int component) {
        requireComponent(component);
        return !liquidAtCondenser[component];
    }

    private void requireComponent(int component) {
        if (component < 0 || component >= liquidAtCondenser.length) {
            throw new IndexOutOfBoundsException("V3 condenser component is outside the active basis");
        }
    }
}
