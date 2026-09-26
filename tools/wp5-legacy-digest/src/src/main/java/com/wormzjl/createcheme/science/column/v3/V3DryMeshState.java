package com.wormzjl.createcheme.science.column.v3;

import java.util.Objects;

/**
 * Immutable internal stage state; it must never cross a public result boundary.
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
        this(topology, componentCount, liquidComponentFlows, vaporComponentFlows, temperaturesKelvin,
                freeWaterFlowsMolPerSecond, true);
    }

    /**
     * Validates and adopts freshly allocated decoder arrays. The caller must relinquish every array,
     * including all flow rows; ordinary callers keep using the defensive-copy constructors.
     */
    static V3DryMeshState fromOwnedArrays(V3ColumnTopology topology, int componentCount,
            double[][] liquid, double[][] vapor, double[] temperatures, double[] freeWater) {
        return new V3DryMeshState(topology, componentCount, liquid, vapor, temperatures, freeWater, false);
    }

    private V3DryMeshState(V3ColumnTopology topology, int componentCount, double[][] liquidComponentFlows,
                   double[][] vaporComponentFlows, double[] temperaturesKelvin,
                   double[] freeWaterFlowsMolPerSecond, boolean copy) {
        Objects.requireNonNull(topology, "topology");
        if (componentCount < 1 || liquidComponentFlows == null || vaporComponentFlows == null || temperaturesKelvin == null
                || freeWaterFlowsMolPerSecond == null
                || liquidComponentFlows.length != topology.nodeCount() || vaporComponentFlows.length != topology.nodeCount()
                || temperaturesKelvin.length != topology.nodeCount()
                || freeWaterFlowsMolPerSecond.length != topology.nodeCount()) {
            throw new IllegalArgumentException("V3 dry MESH state does not match its topology");
        }
        this.liquidComponentFlows = validatedFlows(liquidComponentFlows, topology, componentCount, true, copy);
        this.vaporComponentFlows = validatedFlows(vaporComponentFlows, topology, componentCount, false, copy);
        this.temperaturesKelvin = copy ? temperaturesKelvin.clone() : temperaturesKelvin;
        for (double temperature : this.temperaturesKelvin) {
            if (!Double.isFinite(temperature) || temperature <= 0.0) {
                throw new IllegalArgumentException("V3 dry MESH temperatures must be finite and positive");
            }
        }
        this.freeWaterFlowsMolPerSecond = copy ? freeWaterFlowsMolPerSecond.clone() : freeWaterFlowsMolPerSecond;
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

    /**
     * Adopts arrays that are already known to satisfy every invariant of the validating constructors.
     *
     * <p>Only the single-entry perturbation factories below reach this, and each of them hands it the arrays
     * of a state that has already been validated, with at most one entry replaced by a value it has just
     * checked itself.</p>
     */
    private V3DryMeshState(double[][] liquidComponentFlows, double[][] vaporComponentFlows,
                           double[] temperaturesKelvin, double[] freeWaterFlowsMolPerSecond) {
        this.liquidComponentFlows = liquidComponentFlows;
        this.vaporComponentFlows = vaporComponentFlows;
        this.temperaturesKelvin = temperaturesKelvin;
        this.freeWaterFlowsMolPerSecond = freeWaterFlowsMolPerSecond;
    }

    double liquidFlow(int node, int component) { return liquidComponentFlows[node][component]; }
    double vaporFlow(int node, int component) { return vaporComponentFlows[node][component]; }
    double temperatureKelvin(int node) { return temperaturesKelvin[node]; }
    /** Aqueous liquid leaving this tray downward; exactly zero on a dry tray and on both terminal nodes. */
    double freeWaterFlow(int node) { return freeWaterFlowsMolPerSecond[node]; }
    int nodeCount() { return temperaturesKelvin.length; }
    int componentCount() { return vaporComponentFlows[0].length; }

    /**
     * This state with one hydrocarbon liquid flow replaced, sharing every row it does not touch.
     *
     * <p>A finite-difference probe builds one such neighbour per coordinate and per sign, so copying and
     * revalidating the whole flow field for each of them costs far more than the single node the probe can
     * actually move. Sharing the untouched rows is safe precisely because a {@code V3DryMeshState} is
     * immutable from the outside: it never exposes an array, never writes one after construction, and the
     * ordinary constructors copy caller arrays and the decoder relinquishes its adopted arrays, so no row here is reachable
     * by anyone who could still change it. Two states sharing a row therefore observe the same numbers
     * forever, which is the only thing either of them promises.</p>
     */
    V3DryMeshState withLiquidFlow(int node, int component, double flowMolPerSecond) {
        requireReplacementFlow(liquidComponentFlows[node][component], flowMolPerSecond);
        return new V3DryMeshState(replaced(liquidComponentFlows, node, component, flowMolPerSecond),
                vaporComponentFlows, temperaturesKelvin, freeWaterFlowsMolPerSecond);
    }

    /** This state with one hydrocarbon vapor flow replaced; see {@link #withLiquidFlow} for the sharing rule. */
    V3DryMeshState withVaporFlow(int node, int component, double flowMolPerSecond) {
        requireReplacementFlow(vaporComponentFlows[node][component], flowMolPerSecond);
        return new V3DryMeshState(liquidComponentFlows,
                replaced(vaporComponentFlows, node, component, flowMolPerSecond),
                temperaturesKelvin, freeWaterFlowsMolPerSecond);
    }

    /** This state with one node temperature replaced; see {@link #withLiquidFlow} for the sharing rule. */
    V3DryMeshState withTemperatureKelvin(int node, double temperatureKelvin) {
        if (!Double.isFinite(temperatureKelvin) || temperatureKelvin <= 0.0) {
            throw new IllegalArgumentException("V3 dry MESH temperatures must be finite and positive");
        }
        double[] temperatures = temperaturesKelvin.clone();
        temperatures[node] = temperatureKelvin;
        return new V3DryMeshState(liquidComponentFlows, vaporComponentFlows, temperatures, freeWaterFlowsMolPerSecond);
    }

    /** This state with one tray's free water replaced; see {@link #withLiquidFlow} for the sharing rule. */
    V3DryMeshState withFreeWaterFlow(int node, double flowMolPerSecond) {
        requireReplacementFlow(freeWaterFlowsMolPerSecond[node], flowMolPerSecond);
        double[] freeWater = freeWaterFlowsMolPerSecond.clone();
        freeWater[node] = flowMolPerSecond;
        return new V3DryMeshState(liquidComponentFlows, vaporComponentFlows, temperaturesKelvin, freeWater);
    }

    /**
     * Guards the one placement invariant a single replacement cannot re-derive.
     *
     * <p>The constructors reject a nonzero flow wherever the topology has no such phase and a nonzero free
     * water anywhere but an equilibrium tray. Neither test can be repeated here, because a state does not keep
     * its topology; what a state does keep is the outcome of those tests. A slot that already carries a
     * nonzero flow has passed them, so a nonzero replacement in that slot passes them too, and a zero
     * replacement is admissible everywhere.</p>
     */
    private static void requireReplacementFlow(double existing, double replacement) {
        if (!Double.isFinite(replacement) || replacement < 0.0) {
            throw new IllegalArgumentException("V3 dry MESH flows must be finite and nonnegative");
        }
        if (replacement != 0.0 && existing == 0.0) {
            throw new IllegalArgumentException("V3 dry MESH state supplies flow for an absent phase");
        }
    }

    private static double[][] replaced(double[][] flows, int node, int component, double value) {
        double[][] copy = flows.clone();
        copy[node] = flows[node].clone();
        copy[node][component] = value;
        return copy;
    }

    private static double[][] validatedFlows(
            double[][] flows, V3ColumnTopology topology, int componentCount, boolean liquid, boolean copy) {
        double[][] validated = copy ? new double[flows.length][componentCount] : flows;
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
                if (copy) validated[node][component] = value;
            }
        }
        return validated;
    }
}
