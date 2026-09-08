package com.wormzjl.createcheme.science.column.v3;

import java.util.Objects;

/**
 * Solver-owned mutable stage state; it must never cross a public result boundary.
 *
 * <p>"Dry" names the hydrocarbon basis: the component flows are hydrocarbon only and water is carried
 * separately. Free water is the one water quantity that is a state variable — the aqueous liquid leaving a
 * wet tray downward. It is zero on every node of a dry column and on every dry tray of a wet one; the
 * upward water vapour of a node stays a function of it and of the authored steam
 * ({@link V3ColumnProblem#waterVaporFlow}).</p>
 */
final class V3DryMeshState {
    private final double[][] liquidComponentFlows;
    private final double[][] vaporComponentFlows;
    private final double[] temperaturesKelvin;
    private final double[] freeWaterFlowsMolPerSecond;

    V3DryMeshState(V3ColumnTopology topology, int componentCount, double[][] liquidComponentFlows,
                   double[][] vaporComponentFlows, double[] temperaturesKelvin) {
        this(topology, componentCount, liquidComponentFlows, vaporComponentFlows, temperaturesKelvin,
                new double[Objects.requireNonNull(topology, "topology").nodeCount()]);
    }

    V3DryMeshState(V3ColumnTopology topology, int componentCount, double[][] liquidComponentFlows,
                   double[][] vaporComponentFlows, double[] temperaturesKelvin,
                   double[] freeWaterFlowsMolPerSecond) {
        Objects.requireNonNull(topology, "topology");
        if (componentCount < 1 || liquidComponentFlows == null || vaporComponentFlows == null || temperaturesKelvin == null
                || freeWaterFlowsMolPerSecond == null
                || liquidComponentFlows.length != topology.nodeCount() || vaporComponentFlows.length != topology.nodeCount()
                || temperaturesKelvin.length != topology.nodeCount()
                || freeWaterFlowsMolPerSecond.length != topology.nodeCount()) {
            throw new IllegalArgumentException("V3 dry MESH state does not match its topology");
        }
        this.liquidComponentFlows = copyFlows(liquidComponentFlows, topology, componentCount, true);
        this.vaporComponentFlows = copyFlows(vaporComponentFlows, topology, componentCount, false);
        this.temperaturesKelvin = temperaturesKelvin.clone();
        for (double temperature : this.temperaturesKelvin) {
            if (!Double.isFinite(temperature) || temperature <= 0.0) {
                throw new IllegalArgumentException("V3 dry MESH temperatures must be finite and positive");
            }
        }
        this.freeWaterFlowsMolPerSecond = freeWaterFlowsMolPerSecond.clone();
        for (int node = 0; node < this.freeWaterFlowsMolPerSecond.length; node++) {
            double flow = this.freeWaterFlowsMolPerSecond[node];
            if (!Double.isFinite(flow) || flow < 0.0) {
                throw new IllegalArgumentException("V3 dry MESH free water must be finite and nonnegative");
            }
            if (flow != 0.0 && (node < 1 || node > topology.trayCount())) {
                throw new IllegalArgumentException("V3 dry MESH free water is only carried by equilibrium trays");
            }
        }
    }

    double liquidFlow(int node, int component) { return liquidComponentFlows[node][component]; }
    double vaporFlow(int node, int component) { return vaporComponentFlows[node][component]; }
    double temperatureKelvin(int node) { return temperaturesKelvin[node]; }
    /** Aqueous liquid leaving this tray downward; exactly zero on a dry tray and on both terminal nodes. */
    double freeWaterFlow(int node) { return freeWaterFlowsMolPerSecond[node]; }
    int nodeCount() { return temperaturesKelvin.length; }
    int componentCount() { return vaporComponentFlows[0].length; }

    private static double[][] copyFlows(
            double[][] flows, V3ColumnTopology topology, int componentCount, boolean liquid) {
        double[][] copy = new double[flows.length][componentCount];
        for (int node = 0; node < flows.length; node++) {
            if (flows[node] == null || flows[node].length != componentCount) {
                throw new IllegalArgumentException("V3 dry MESH flow axis does not match its component basis");
            }
            for (int component = 0; component < componentCount; component++) {
                double value = flows[node][component];
                if (!Double.isFinite(value) || value < 0.0) {
                    throw new IllegalArgumentException("V3 dry MESH flows must be finite and nonnegative");
                }
                if (liquid && !topology.hasLiquidPhase(node) && value != 0.0) {
                    throw new IllegalArgumentException("V3 dry MESH state supplies liquid flow for an absent condenser phase");
                }
                if (!liquid && !topology.hasVaporPhase(node) && value != 0.0) {
                    throw new IllegalArgumentException("V3 dry MESH state supplies vapor flow for an absent condenser phase");
                }
                copy[node][component] = value;
            }
        }
        return copy;
    }
}
