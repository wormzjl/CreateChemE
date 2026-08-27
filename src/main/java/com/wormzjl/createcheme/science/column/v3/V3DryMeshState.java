package com.wormzjl.createcheme.science.column.v3;

import java.util.Objects;

/** Solver-owned mutable dry-hydrocarbon stage state; it must never cross a public result boundary. */
final class V3DryMeshState {
    private final double[][] liquidComponentFlows;
    private final double[][] vaporComponentFlows;
    private final double[] temperaturesKelvin;

    V3DryMeshState(V3ColumnTopology topology, int componentCount, double[][] liquidComponentFlows,
                   double[][] vaporComponentFlows, double[] temperaturesKelvin) {
        Objects.requireNonNull(topology, "topology");
        if (componentCount < 1 || liquidComponentFlows == null || vaporComponentFlows == null || temperaturesKelvin == null
                || liquidComponentFlows.length != topology.nodeCount() || vaporComponentFlows.length != topology.nodeCount()
                || temperaturesKelvin.length != topology.nodeCount()) {
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
    }

    double liquidFlow(int node, int component) { return liquidComponentFlows[node][component]; }
    double vaporFlow(int node, int component) { return vaporComponentFlows[node][component]; }
    double temperatureKelvin(int node) { return temperaturesKelvin[node]; }
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
                copy[node][component] = value;
            }
        }
        return copy;
    }
}
