package com.wormzjl.createcheme.science.column.v3;

import java.util.Objects;

/** Fully resolved immutable M0/M1 numerical contract ready for later solver admission. */
public final class V3ColumnProblem {
    private final V3ColumnInput input;
    private final V3ColumnTopology topology;
    private final V3ActiveComponentBasis activeComponentBasis;
    private final V3CondenserComponentPhases condenserComponentPhases;
    private final double[] nodePressuresPascal;
    private final double[] nodeSideDrawMolPerSecond;
    private final V3DegreeOfFreedomLedger degreeOfFreedomLedger;
    private final V3TruncationSupport truncationSupport;

    V3ColumnProblem(
            V3ColumnInput input, V3ColumnTopology topology, V3ActiveComponentBasis activeComponentBasis,
            V3CondenserComponentPhases condenserComponentPhases, double[] nodePressuresPascal,
            V3DegreeOfFreedomLedger degreeOfFreedomLedger, V3TruncationSupport truncationSupport) {
        this.input = Objects.requireNonNull(input, "input");
        this.topology = Objects.requireNonNull(topology, "topology");
        this.activeComponentBasis = Objects.requireNonNull(activeComponentBasis, "activeComponentBasis");
        this.condenserComponentPhases = Objects.requireNonNull(condenserComponentPhases, "condenserComponentPhases");
        this.nodePressuresPascal = Objects.requireNonNull(nodePressuresPascal, "nodePressuresPascal").clone();
        this.nodeSideDrawMolPerSecond = new double[topology.nodeCount()];
        for (V3SideDrawSpec draw : input.sideDraws()) {
            this.nodeSideDrawMolPerSecond[draw.trayNumber()] = draw.molarFlowMolPerSecond();
        }
        this.degreeOfFreedomLedger = Objects.requireNonNull(degreeOfFreedomLedger, "degreeOfFreedomLedger");
        this.truncationSupport = Objects.requireNonNull(truncationSupport, "truncationSupport");
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
                || !degreeOfFreedomLedger.specifications().equals(input.specifications())
                || degreeOfFreedomLedger.truncationSupport() != truncationSupport) {
            throw new IllegalArgumentException("V3 degree-of-freedom ledger does not describe this resolved problem");
        }
        truncationSupport.requireCompatible(this);
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

    V3CondenserComponentPhases condenserComponentPhases() {
        return condenserComponentPhases;
    }

    V3TruncationSupport truncationSupport() {
        return truncationSupport;
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

    public boolean hasSideDraws() {
        return !input.sideDraws().isEmpty();
    }

    public double nodeSideDrawMolPerSecond(int node) {
        return nodeSideDrawMolPerSecond[node];
    }

    /** Recomputed from the candidate; intentionally not capped during Newton iteration. */
    double liquidWithdrawalFraction(V3DryMeshState state, int node) {
        double rate = nodeSideDrawMolPerSecond[node];
        if (rate == 0.0) return 0.0;
        return V3SideDraws.withdrawal(state, node, rate).fraction();
    }
}
