package com.wormzjl.createcheme.science.column.v3;

import java.util.Objects;

/** Fully resolved immutable M0/M1 numerical contract ready for later solver admission. */
public final class V3ColumnProblem {
    private final V3ColumnInput input;
    private final V3ColumnTopology topology;
    private final V3ActiveComponentBasis activeComponentBasis;
    private final double[] nodePressuresPascal;
    private final V3DegreeOfFreedomLedger degreeOfFreedomLedger;

    V3ColumnProblem(
            V3ColumnInput input, V3ColumnTopology topology, V3ActiveComponentBasis activeComponentBasis, double[] nodePressuresPascal,
            V3DegreeOfFreedomLedger degreeOfFreedomLedger) {
        this.input = Objects.requireNonNull(input, "input");
        this.topology = Objects.requireNonNull(topology, "topology");
        this.activeComponentBasis = Objects.requireNonNull(activeComponentBasis, "activeComponentBasis");
        this.nodePressuresPascal = Objects.requireNonNull(nodePressuresPascal, "nodePressuresPascal").clone();
        this.degreeOfFreedomLedger = Objects.requireNonNull(degreeOfFreedomLedger, "degreeOfFreedomLedger");
        if (this.nodePressuresPascal.length != topology.nodeCount()) {
            throw new IllegalArgumentException("V3 pressure profile does not match the resolved topology");
        }
        for (double pressure : this.nodePressuresPascal) {
            if (!Double.isFinite(pressure) || pressure <= 0.0) {
                throw new IllegalArgumentException("V3 pressure profile must be finite and physically positive");
            }
        }
        if (!degreeOfFreedomLedger.topology().equals(topology)
                || degreeOfFreedomLedger.componentCount() != activeComponentBasis.componentCount()
                || !degreeOfFreedomLedger.specifications().equals(input.specifications())) {
            throw new IllegalArgumentException("V3 degree-of-freedom ledger does not describe this resolved problem");
        }
    }

    public V3ColumnInput input() {
        return input;
    }

    public V3ColumnTopology topology() {
        return topology;
    }

    V3ActiveComponentBasis activeComponentBasis() {
        return activeComponentBasis;
    }

    public double[] nodePressuresPascal() {
        return nodePressuresPascal.clone();
    }

    public double nodePressurePascal(int node) {
        return nodePressuresPascal[node];
    }

    public V3DegreeOfFreedomLedger degreeOfFreedomLedger() {
        return degreeOfFreedomLedger;
    }
}
