package com.wormzjl.createcheme.science.column.v3;

import java.util.Objects;

/** Immutable node and boundary-phase map for one compiled dry V3 column branch. */
public final class V3ColumnTopology {
    private final int trayCount;
    private final int feedTrayNumber;
    private final V3CondenserPhaseBranch condenserPhaseBranch;

    private V3ColumnTopology(int trayCount, int feedTrayNumber, V3CondenserPhaseBranch condenserPhaseBranch) {
        if (trayCount < V3ColumnInput.MIN_STAGE_COUNT || trayCount > V3ColumnInput.MAX_STAGE_COUNT) {
            throw new IllegalArgumentException("V3 tray count must be in " + V3ColumnInput.MIN_STAGE_COUNT + ".."
                    + V3ColumnInput.MAX_STAGE_COUNT);
        }
        if (feedTrayNumber < 1 || feedTrayNumber > trayCount) {
            throw new IllegalArgumentException("V3 feed tray must be an equilibrium tray");
        }
        this.trayCount = trayCount;
        this.feedTrayNumber = feedTrayNumber;
        this.condenserPhaseBranch = Objects.requireNonNull(condenserPhaseBranch, "condenserPhaseBranch");
    }

    public static V3ColumnTopology twoPhase(int trayCount, int feedTrayNumber) {
        return new V3ColumnTopology(trayCount, feedTrayNumber, V3CondenserPhaseBranch.TWO_PHASE);
    }

    public static V3ColumnTopology vaporOnly(int trayCount, int feedTrayNumber) {
        return new V3ColumnTopology(trayCount, feedTrayNumber, V3CondenserPhaseBranch.VAPOR_ONLY);
    }

    public static V3ColumnTopology totalLiquid(int trayCount, int feedTrayNumber) {
        return new V3ColumnTopology(trayCount, feedTrayNumber, V3CondenserPhaseBranch.TOTAL_LIQUID);
    }

    public int trayCount() {
        return trayCount;
    }

    public int feedTrayNumber() {
        return feedTrayNumber;
    }

    /** Number of physical nodes including the partial condenser and partial reboiler. */
    public int nodeCount() {
        return trayCount + 2;
    }

    public int condenserNode() {
        return 0;
    }

    public int reboilerNode() {
        return trayCount + 1;
    }

    public V3CondenserPhaseBranch condenserPhaseBranch() {
        return condenserPhaseBranch;
    }

    public boolean hasLiquidPhase(int node) {
        requireNode(node);
        return node != condenserNode() || condenserPhaseBranch != V3CondenserPhaseBranch.VAPOR_ONLY;
    }

    public boolean hasVaporPhase(int node) {
        requireNode(node);
        return node != condenserNode() || condenserPhaseBranch != V3CondenserPhaseBranch.TOTAL_LIQUID;
    }

    public boolean hasVaporLiquidEquilibriumEquation(int node) {
        return hasLiquidPhase(node) && hasVaporPhase(node);
    }

    public boolean hasTemperatureUnknown(int node) {
        requireNode(node);
        return node != condenserNode();
    }

    public boolean hasEnergyEquation(int node) {
        return hasTemperatureUnknown(node);
    }

    private void requireNode(int node) {
        if (node < condenserNode() || node > reboilerNode()) {
            throw new IndexOutOfBoundsException("V3 node " + node + " is outside 0.." + reboilerNode());
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof V3ColumnTopology topology && trayCount == topology.trayCount
                && feedTrayNumber == topology.feedTrayNumber && condenserPhaseBranch == topology.condenserPhaseBranch;
    }

    @Override
    public int hashCode() {
        return Objects.hash(trayCount, feedTrayNumber, condenserPhaseBranch);
    }

    @Override
    public String toString() {
        return "V3ColumnTopology[trayCount=" + trayCount + ", feedTrayNumber=" + feedTrayNumber
                + ", condenserPhaseBranch=" + condenserPhaseBranch + "]";
    }
}
