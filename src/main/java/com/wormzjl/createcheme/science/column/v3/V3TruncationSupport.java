package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3TraceTruncationPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, attempt-local component/stage support. A removed point has neither flow coordinates nor
 * material/VLE rows. Support is derived from the final deciding seed and frozen throughout Newton.
 */
final class V3TruncationSupport {
    static final double MAX_CUTOFF_MOLE_FRACTION = V3TraceTruncationPolicy.MAX_CUTOFF_MOLE_FRACTION;
    /**
     * Relative flow below which a component is not an independent unknown on a stage, and below which a
     * component material balance is not scaled any further down.
     *
     * <p>The fraction multiplies the component's flow scale (its authored feed flow, guarded by
     * {@code F_total * 1e-12}). At 1e-10 the documented TJL19 trace spike of 2.3e-11 mol/s in a 45.8 mol/s
     * feed component (5e-13 relative) is two hundred times below the floor, so the two balances whose
     * equilibrated rows collapsed onto one unit vector are not equations at all and the Jacobian is no longer
     * rank deficient. Choosing 1e-11 instead would leave only a factor twenty of margin against a spike whose
     * position is known to move by tens of orders of magnitude between JVMs.</p>
     *
     * <p>The floor is a support rule, not a conditioning trick. The banded solver equilibrates every row by
     * its own maximum before pivoting, so its 1e-13 pivot tolerance sees only the physical structure of the
     * Jacobian and cannot be improved by rescaling rows; removing the points is what removes the dependency.
     * The price is bounded by construction: at most one floor of a component's feed per removed point, four
     * orders below any physically meaningful flow and two orders below the 1e-8 relative residual tolerance,
     * which is what lets the mass-defect audit stay tight.</p>
     */
    static final double TRACE_FLOOR_FRACTION = 1.0e-10;
    /**
     * Hysteresis between removal and reinsertion of a floor-supported point.
     *
     * <p>A point is removed when both of its phase flows are below the floor, so the material it was passing
     * on is also of the order of one floor. Reinserting at the same threshold would put every boundary point
     * into a remove/reinsert cycle across support refreshes, and each reinsertion perturbs an otherwise
     * converged state. A removed point is therefore only reinserted once its retained neighbours deliver ten
     * floors, which is above anything it could have been carrying when it was removed.</p>
     */
    static final double FLOOR_REINSERTION_FACTOR = 10.0;
    /** Roundoff and withdrawal-fraction slack on the per-edge floor bound of the mass-defect audit. */
    private static final double FLOOR_DEFECT_SLACK = 2.0;

    private final V3ColumnTopology topology;
    private final int componentCount;
    private final double cutoffMoleFraction;
    // Null represents identity without allocating a full retained-point matrix.
    private final boolean[][] retained;
    private final int truncatedPointCount;
    private final int closurePrunedCount;
    private final String note;
    private final double organicRefluxRatio;
    private final double[] nodeSideDrawRates;
    private final List<SinkEdge> sinkEdges;
    private final double[] componentFloors;
    private final double totalFeedFlowMolPerSecond;

    private V3TruncationSupport(V3ColumnTopology topology, int componentCount) {
        this.topology = Objects.requireNonNull(topology, "topology");
        requireComponentCount(componentCount);
        this.componentCount = componentCount;
        cutoffMoleFraction = 0.0;
        retained = null;
        truncatedPointCount = 0;
        closurePrunedCount = 0;
        note = "";
        organicRefluxRatio = 0.0;
        nodeSideDrawRates = new double[topology.nodeCount()];
        sinkEdges = List.of();
        componentFloors = new double[componentCount];
        totalFeedFlowMolPerSecond = 0.0;
    }

    private V3TruncationSupport(V3ColumnProblem problem, double cutoffMoleFraction,
                                boolean[][] retained, int closurePrunedCount, String note) {
        topology = problem.topology();
        componentCount = problem.activeComponentBasis().componentCount();
        requireCutoff(cutoffMoleFraction);
        this.cutoffMoleFraction = cutoffMoleFraction;
        organicRefluxRatio = refluxRatio(problem);
        nodeSideDrawRates = new double[topology.nodeCount()];
        for (V3SideDrawSpec draw : problem.input().sideDraws()) {
            nodeSideDrawRates[draw.trayNumber()] = draw.molarFlowMolPerSecond();
        }
        if (closurePrunedCount < 0 || closurePrunedCount > totalPointCount()) {
            throw new IllegalArgumentException("V3 closure-pruned point count is outside the support");
        }
        this.closurePrunedCount = closurePrunedCount;
        this.note = Objects.requireNonNull(note, "note");
        if (note.length() > 256) throw new IllegalArgumentException("V3 truncation note exceeds its bound");
        boolean[][] copy = copyRetained(retained);
        int truncated = 0;
        if (copy != null) {
            for (boolean[] row : copy) {
                for (boolean keep : row) if (!keep) truncated++;
            }
        }
        truncatedPointCount = truncated;
        this.retained = truncated == 0 ? null : copy;
        componentFloors = new double[componentCount];
        for (int component = 0; component < componentCount; component++) {
            componentFloors[component] = problem.activeComponentBasis().flowScale(component) * TRACE_FLOOR_FRACTION;
        }
        totalFeedFlowMolPerSecond = problem.activeComponentBasis().totalFeedFlowMolPerSecond();
        requireCompatible(problem);
        sinkEdges = isIdentity() ? List.of() : enumerateSinkEdges(problem);
    }

    static V3TruncationSupport identity(V3ColumnTopology topology, int componentCount) {
        return new V3TruncationSupport(topology, componentCount);
    }

    /**
     * Freezes support from the final seed; the seed is read only and no state reference is retained.
     *
     * <p>The {@link #TRACE_FLOOR_FRACTION} floor is always applied: a point whose flow is below the floor in
     * both present phases is not an unknown and carries no equation, whatever the requested cutoff is. An
     * authored cutoff composes with it, so a point is retained only when it is above the floor <em>and</em>
     * passes the mole-fraction rule.</p>
     */
    static V3TruncationSupport derive(V3ColumnProblem problem, double cutoffMoleFraction,
                                      V3DryMeshState decidingState) {
        Objects.requireNonNull(problem, "problem");
        requireCutoff(cutoffMoleFraction);
        if (!problem.truncationSupport().isIdentity()) {
            throw new IllegalArgumentException("V3 support must be derived from an untruncated problem");
        }
        Objects.requireNonNull(decidingState, "decidingState");
        V3ColumnTopology topology = problem.topology();
        int components = problem.activeComponentBasis().componentCount();
        if (decidingState.nodeCount() != topology.nodeCount() || decidingState.componentCount() != components) {
            throw new IllegalArgumentException("V3 deciding state does not match the truncation support");
        }
        boolean[][] retained = new boolean[topology.nodeCount()][components];
        // A specified draw needs a material path back to the feed, including trace components.
        // Retaining isolated draw points makes later grids expand abruptly to the full problem.
        int firstProductPathNode = topology.feedTrayNumber();
        int lastProductPathNode = topology.feedTrayNumber();
        for (V3SideDrawSpec draw : problem.input().sideDraws()) {
            firstProductPathNode = Math.min(firstProductPathNode, draw.trayNumber());
            lastProductPathNode = Math.max(lastProductPathNode, draw.trayNumber());
        }
        for (int node = 0; node < topology.nodeCount(); node++) {
            double liquidTotal = phaseTotal(problem, decidingState, node, true);
            double vaporTotal = phaseTotal(problem, decidingState, node, false);
            for (int component = 0; component < components; component++) {
                boolean testLiquid = problem.condenserComponentPhases().hasLiquid(topology, node, component)
                        && liquidTotal > 0.0;
                boolean testVapor = topology.hasVaporPhase(node) && vaporTotal > 0.0;
                double floor = problem.activeComponentBasis().flowScale(component) * TRACE_FLOOR_FRACTION;
                // A stage with no flow at all in either present phase is untestable, exactly as it is for the
                // mole-fraction cutoff, and is conservatively retained.
                boolean aboveFloor = (!testLiquid && !testVapor)
                        || (testLiquid && decidingState.liquidFlow(node, component) >= floor)
                        || (testVapor && decidingState.vaporFlow(node, component) >= floor);
                boolean passesCutoff = cutoffMoleFraction == 0.0
                        || (!testLiquid && !testVapor)
                        || (testLiquid && decidingState.liquidFlow(node, component) / liquidTotal >= cutoffMoleFraction)
                        || (testVapor && decidingState.vaporFlow(node, component) / vaporTotal >= cutoffMoleFraction);
                retained[node][component] = (node >= firstProductPathNode && node <= lastProductPathNode)
                        || (aboveFloor && passesCutoff);
            }
        }
        int pruned = pruneUnreachable(problem, retained);
        for (V3SideDrawSpec draw : problem.input().sideDraws()) {
            if (draw.trayNumber() == topology.feedTrayNumber()) continue;
            for (int component = 0; component < components; component++) {
                if (!retained[draw.trayNumber()][component]) {
                    return new V3TruncationSupport(problem, cutoffMoleFraction, null, pruned,
                            "Stage-trace support fell back to identity: a forced side-draw point has no retained feed path");
                }
            }
        }
        if (!phasesNonempty(problem, retained)) {
            return new V3TruncationSupport(problem, cutoffMoleFraction, null, pruned,
                    "Stage-trace support fell back to identity: feed reachability emptied a structural phase");
        }
        // The floor alone removing nothing is the common case on a well-populated state. Reusing the problem's
        // own identity support then keeps the exact off switch: no new support, ledger or problem is built.
        if (cutoffMoleFraction == 0.0 && pruned == 0 && allRetained(retained)) return problem.truncationSupport();
        return new V3TruncationSupport(problem, cutoffMoleFraction, retained, pruned, "");
    }

    boolean retains(int node, int component) {
        if (node < 0 || node >= topology.nodeCount() || component < 0 || component >= componentCount) {
            throw new IndexOutOfBoundsException("V3 truncation point is outside the topology/component basis");
        }
        return retained == null || retained[node][component];
    }

    boolean isIdentity() { return truncatedPointCount == 0; }
    double cutoffMoleFraction() { return cutoffMoleFraction; }
    int totalPointCount() { return topology.nodeCount() * componentCount; }
    int truncatedPointCount() { return truncatedPointCount; }
    /** Points that are not ABSENT; the quantity a refresh must increase to be worth a re-solve. */
    int retainedPointCount() { return totalPointCount() - truncatedPointCount; }
    int closurePrunedCount() { return closurePrunedCount; }
    String note() { return note; }
    List<SinkEdge> sinkEdges() { return sinkEdges; }

    V3TruncationSupport fallbackToIdentity(V3ColumnProblem problem, String reason) {
        requireCompatible(problem);
        return new V3TruncationSupport(problem, cutoffMoleFraction, null, closurePrunedCount, reason);
    }

    /** Projects only the seed; it never imposes a floor on solved flows or changes the authored feed. */
    V3DryMeshState projectSeed(V3ColumnProblem problem, V3DryMeshState seed) {
        requireCompatible(Objects.requireNonNull(problem, "problem"));
        requireState(seed);
        if (isIdentity()) return seed;
        double[][] liquid = new double[topology.nodeCount()][componentCount];
        double[][] vapor = new double[topology.nodeCount()][componentCount];
        double[] temperatures = new double[topology.nodeCount()];
        for (int node = 0; node < topology.nodeCount(); node++) {
            temperatures[node] = seed.temperatureKelvin(node);
            for (int component = 0; component < componentCount; component++) {
                if (!retains(node, component)) continue;
                // Preserve positivity even for a subnormal component flow scale. A retained point is at or
                // above the support floor by construction, so the same floor is the reinsertion value.
                double floor = Math.max(Double.MIN_VALUE,
                        problem.activeComponentBasis().flowScale(component) * TRACE_FLOOR_FRACTION);
                if (problem.condenserComponentPhases().hasLiquid(topology, node, component)) {
                    double flow = seed.liquidFlow(node, component);
                    liquid[node][component] = flow > 0.0 ? flow : floor;
                }
                if (topology.hasVaporPhase(node)) {
                    double flow = seed.vaporFlow(node, component);
                    vapor[node][component] = flow > 0.0 ? flow : floor;
                }
            }
        }
        return new V3DryMeshState(topology, componentCount, liquid, vapor, temperatures);
    }

    /** Support floor of one active component: {@link #TRACE_FLOOR_FRACTION} of its flow scale. */
    double componentFloorMolPerSecond(int component) {
        if (component < 0 || component >= componentCount) {
            throw new IndexOutOfBoundsException("V3 truncation component is outside the basis");
        }
        return componentFloors[component];
    }

    boolean sameRetention(V3TruncationSupport other) {
        Objects.requireNonNull(other, "other");
        if (other.topology != topology || other.componentCount != componentCount) return false;
        if (retained == null || other.retained == null) return (retained == null) == (other.retained == null);
        for (int node = 0; node < retained.length; node++) {
            if (!java.util.Arrays.equals(retained[node], other.retained[node])) return false;
        }
        return true;
    }

    /**
     * Construction bound on the sink-edge defect the floor support can leave, as a fraction of the feed.
     *
     * <p>An attempt is only published after a support refresh has confirmed that no retained neighbour
     * delivers more than a component's own floor into a removed point, so the whole omitted mass is bounded
     * by one floor per sink edge. That is what makes the mass-defect audit a real check rather than a
     * formality: exceeding this bound means a removed point was carrying material.</p>
     */
    double floorDefectBoundFraction() {
        if (isIdentity() || totalFeedFlowMolPerSecond <= 0.0) return 0.0;
        double bound = 0.0;
        for (SinkEdge edge : sinkEdges) bound += componentFloors[edge.component()];
        return FLOOR_DEFECT_SLACK * FLOOR_REINSERTION_FACTOR * bound / totalFeedFlowMolPerSecond;
    }

    /** Total solved molar flow into removed points. Product exits are deliberately excluded. */
    double massDefectMolPerSecond(V3DryMeshState state) {
        requireState(state);
        double defect = 0.0;
        for (SinkEdge edge : sinkEdges) {
            defect += switch (edge.kind()) {
                case LIQUID_TO_BELOW -> liquidDownflowFraction(state, edge.sourceNode())
                        * state.liquidFlow(edge.sourceNode(), edge.component());
                case VAPOR_TO_ABOVE -> state.vaporFlow(edge.sourceNode(), edge.component());
                case REFLUX_TO_TRAY_ONE -> organicRefluxRatio / (1.0 + organicRefluxRatio)
                        * state.liquidFlow(edge.sourceNode(), edge.component());
            };
        }
        if (!Double.isFinite(defect)) throw new IllegalArgumentException("V3 truncation mass defect must be finite");
        return defect;
    }

    private double liquidDownflowFraction(V3DryMeshState state, int node) {
        if (nodeSideDrawRates[node] == 0.0) return 1.0;
        return 1.0 - V3SideDraws.withdrawal(state, node, nodeSideDrawRates[node]).fraction();
    }

    private void requireState(V3DryMeshState state) {
        Objects.requireNonNull(state, "state");
        if (state.nodeCount() != topology.nodeCount() || state.componentCount() != componentCount) {
            throw new IllegalArgumentException("V3 state does not match the truncation support");
        }
    }

    private List<SinkEdge> enumerateSinkEdges(V3ColumnProblem problem) {
        List<SinkEdge> edges = new ArrayList<>();
        for (int node = 0; node < topology.nodeCount(); node++) {
            for (int component = 0; component < componentCount; component++) {
                if (!retains(node, component)) continue;
                if (node > topology.condenserNode() && !retains(node - 1, component)) {
                    edges.add(new SinkEdge(SinkKind.VAPOR_TO_ABOVE, node, node - 1, component));
                }
                if (node < topology.reboilerNode() && !retains(node + 1, component)
                        && problem.condenserComponentPhases().hasLiquid(topology, node, component)) {
                    if (node > topology.condenserNode()) {
                        edges.add(new SinkEdge(SinkKind.LIQUID_TO_BELOW, node, node + 1, component));
                    } else if (organicRefluxRatio > 0.0) {
                        edges.add(new SinkEdge(SinkKind.REFLUX_TO_TRAY_ONE, node, node + 1, component));
                    }
                }
            }
        }
        return List.copyOf(edges);
    }

    enum SinkKind { LIQUID_TO_BELOW, VAPOR_TO_ABOVE, REFLUX_TO_TRAY_ONE }

    record SinkEdge(SinkKind kind, int sourceNode, int targetNode, int component) {
        SinkEdge {
            Objects.requireNonNull(kind, "kind");
            if (sourceNode < 0 || targetNode < 0 || component < 0
                    || (kind == SinkKind.VAPOR_TO_ABOVE ? targetNode != sourceNode - 1
                    : targetNode != sourceNode + 1)
                    || (kind == SinkKind.REFLUX_TO_TRAY_ONE ? sourceNode != 0
                    : kind == SinkKind.LIQUID_TO_BELOW && sourceNode == 0)) {
                throw new IllegalArgumentException("V3 truncation sink edge is not an internal flow edge");
            }
        }
    }

    void requireCompatible(V3ColumnTopology topology, int componentCount) {
        if (!this.topology.equals(topology) || this.componentCount != componentCount) {
            throw new IllegalArgumentException("V3 truncation support does not match the topology/component basis");
        }
    }

    /** Rechecks physical support when a mask is attached to a problem (including its reflux control). */
    void requireCompatible(V3ColumnProblem problem) {
        requireCompatible(problem.topology(), problem.activeComponentBasis().componentCount());
        if (isIdentity()) return;
        if (!phasesNonempty(problem, retained)) {
            throw new IllegalArgumentException("V3 truncation support empties a structural phase");
        }
        double refluxRatio = refluxRatio(problem);
        if (refluxRatio != organicRefluxRatio) {
            throw new IllegalArgumentException("V3 truncation support has a different reflux control");
        }
        boolean[][] reachable = reachableFromFeed(problem, retained, refluxRatio);
        for (int node = 0; node < topology.nodeCount(); node++) {
            if (nodeSideDrawRates[node] != problem.nodeSideDrawMolPerSecond(node)) {
                throw new IllegalArgumentException("V3 truncation support has different side draw rates");
            }
            for (int component = 0; component < componentCount; component++) {
                if (nodeSideDrawRates[node] > 0.0 && !retains(node, component)) {
                    throw new IllegalArgumentException("V3 truncation support cannot remove a side-draw tray point");
                }
                if (node == topology.feedTrayNumber()) {
                    if (!retains(node, component)) {
                        throw new IllegalArgumentException("V3 truncation support cannot remove a feed-tray point");
                    }
                } else if (retains(node, component)
                        && !reachable[node][component]) {
                    throw new IllegalArgumentException("V3 retained point has no retained path from the feed");
                }
            }
        }
    }

    private boolean[][] copyRetained(boolean[][] source) {
        if (source == null) return null;
        if (source.length != topology.nodeCount()) {
            throw new IllegalArgumentException("V3 retained-point matrix does not match the topology");
        }
        boolean[][] copy = new boolean[source.length][];
        for (int node = 0; node < source.length; node++) {
            if (source[node] == null || source[node].length != componentCount) {
                throw new IllegalArgumentException("V3 retained-point row does not match the component basis");
            }
            copy[node] = source[node].clone();
        }
        return copy;
    }

    private static double phaseTotal(V3ColumnProblem problem, V3DryMeshState state, int node, boolean liquid) {
        double total = 0.0;
        for (int component = 0; component < state.componentCount(); component++) {
            if (liquid ? problem.condenserComponentPhases().hasLiquid(problem.topology(), node, component)
                    : problem.topology().hasVaporPhase(node)) {
                total += liquid ? state.liquidFlow(node, component) : state.vaporFlow(node, component);
            }
        }
        if (!Double.isFinite(total)) {
            throw new IllegalArgumentException("V3 deciding-state phase total must be finite");
        }
        return total;
    }

    private static int pruneUnreachable(V3ColumnProblem problem, boolean[][] retained) {
        boolean[][] reachable = reachableFromFeed(problem, retained, refluxRatio(problem));
        int pruned = 0;
        for (int node = 0; node < retained.length; node++) {
            for (int component = 0; component < retained[node].length; component++) {
                if (retained[node][component] && !reachable[node][component]) {
                    retained[node][component] = false;
                    pruned++;
                }
            }
        }
        return pruned;
    }

    /** A recirculating group cannot supply itself: every retained point needs a directed path from the feed. */
    private static boolean[][] reachableFromFeed(V3ColumnProblem problem, boolean[][] retained, double refluxRatio) {
        V3ColumnTopology topology = problem.topology();
        int components = problem.activeComponentBasis().componentCount();
        boolean[][] reachable = new boolean[topology.nodeCount()][components];
        int[] pending = new int[topology.nodeCount()];
        for (int component = 0; component < components; component++) {
            int feed = topology.feedTrayNumber();
            if (!retained[feed][component]) continue;
            int next = 0;
            int count = 1;
            pending[0] = feed;
            reachable[feed][component] = true;
            while (next < count) {
                int node = pending[next++];
                if (node > topology.condenserNode() && topology.hasVaporPhase(node)
                        && retained[node - 1][component] && !reachable[node - 1][component]) {
                    reachable[node - 1][component] = true;
                    pending[count++] = node - 1;
                }
                if (node < topology.reboilerNode()
                        && problem.condenserComponentPhases().hasLiquid(topology, node, component)
                        && (node != topology.condenserNode() || refluxRatio > 0.0)
                        && retained[node + 1][component] && !reachable[node + 1][component]) {
                    reachable[node + 1][component] = true;
                    pending[count++] = node + 1;
                }
            }
        }
        return reachable;
    }

    private static boolean allRetained(boolean[][] retained) {
        for (boolean[] row : retained) for (boolean keep : row) if (!keep) return false;
        return true;
    }

    private static boolean phasesNonempty(V3ColumnProblem problem, boolean[][] retained) {
        V3ColumnTopology topology = problem.topology();
        for (int node = 0; node < topology.nodeCount(); node++) {
            boolean liquid = false;
            boolean vapor = false;
            for (int component = 0; component < retained[node].length; component++) {
                if (!retained[node][component]) continue;
                liquid |= problem.condenserComponentPhases().hasLiquid(topology, node, component);
                vapor |= topology.hasVaporPhase(node);
            }
            if ((topology.hasLiquidPhase(node) && !liquid) || (topology.hasVaporPhase(node) && !vapor)) return false;
        }
        return true;
    }

    private static double refluxRatio(V3ColumnProblem problem) {
        return problem.input().specifications().stream()
                .filter(V3ColumnSpecification.OrganicRefluxRatio.class::isInstance)
                .map(V3ColumnSpecification.OrganicRefluxRatio.class::cast)
                .findFirst().orElseThrow().ratio();
    }

    static void requireCutoff(double cutoff) {
        V3TraceTruncationPolicy.requireCutoff(cutoff);
    }

    private static void requireComponentCount(int componentCount) {
        if (componentCount < 1 || componentCount > V3ComponentBasis.MAX_COMPONENTS) {
            throw new IllegalArgumentException("V3 truncation component count is outside the supported contract range");
        }
    }
}
