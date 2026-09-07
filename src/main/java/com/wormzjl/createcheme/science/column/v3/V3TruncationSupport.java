package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3TraceTruncationPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, attempt-local component/stage support. Presence is per phase: a stage point is BOTH,
 * LIQUID_ONLY, VAPOR_ONLY or ABSENT. An ABSENT point has neither flow coordinate nor material/VLE row; a
 * one-phase point keeps its material row, which conserves the component into the present phase alone, and
 * loses its equilibrium row. Support is derived from the final deciding seed and frozen throughout Newton.
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
     * The price is bounded by construction: at most one floor of a component's feed per removed phase, four
     * orders below any physically meaningful flow and two orders below the 1e-8 relative residual tolerance,
     * which is what lets the mass-defect audit stay tight.</p>
     */
    static final double TRACE_FLOOR_FRACTION = 1.0e-10;
    /**
     * Hysteresis between removal and reinsertion of a floor-supported phase.
     *
     * <p>A phase is removed when its flow is below the floor, so the material it was passing on is also of
     * the order of one floor. Reinserting at the same threshold would put every boundary point into a
     * remove/reinsert cycle across support refreshes, and each reinsertion perturbs an otherwise converged
     * state. A removed phase is therefore only reinserted once it would carry ten floors, which is above
     * anything it could have been carrying when it was removed.</p>
     */
    static final double FLOOR_REINSERTION_FACTOR = 10.0;
    /** Roundoff and withdrawal-fraction slack on the per-edge floor bound of the mass-defect audit. */
    static final double FLOOR_DEFECT_SLACK = 2.0;

    private static final int LIQUID_BIT = 1;
    private static final int VAPOR_BIT = 2;

    /**
     * Per-point phase presence.
     *
     * <p>{@link #ABSENT} keeps the meaning it has always had: no unknown, no equation, and the material a
     * retained neighbour sends into the point is a sink counted by the mass-defect audit. A one-phase point
     * is a different approximation: the component does not evaporate there ({@link #LIQUID_ONLY}) or does
     * not condense there ({@link #VAPOR_ONLY}), it keeps its material row, and no mass is lost.</p>
     */
    enum PointPhases {
        ABSENT(0),
        LIQUID_ONLY(LIQUID_BIT),
        VAPOR_ONLY(VAPOR_BIT),
        BOTH(LIQUID_BIT | VAPOR_BIT);

        private final byte mask;

        PointPhases(int mask) {
            this.mask = (byte) mask;
        }

        byte mask() { return mask; }
        boolean retainsLiquid() { return (mask & LIQUID_BIT) != 0; }
        boolean retainsVapor() { return (mask & VAPOR_BIT) != 0; }
        boolean isOnePhase() { return this == LIQUID_ONLY || this == VAPOR_ONLY; }

        static PointPhases of(boolean liquid, boolean vapor) {
            return liquid ? (vapor ? BOTH : LIQUID_ONLY) : (vapor ? VAPOR_ONLY : ABSENT);
        }

        static PointPhases ofMask(byte mask) {
            return switch (mask) {
                case 0 -> ABSENT;
                case LIQUID_BIT -> LIQUID_ONLY;
                case VAPOR_BIT -> VAPOR_ONLY;
                case LIQUID_BIT | VAPOR_BIT -> BOTH;
                default -> throw new IllegalArgumentException("V3 truncation phase mask is not a phase set");
            };
        }
    }

    private final V3ColumnTopology topology;
    private final int componentCount;
    private final double cutoffMoleFraction;
    // Null represents identity without allocating a full phase-mask matrix.
    private final byte[][] phases;
    private final int truncatedPointCount;
    private final int onePhasePointCount;
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
        phases = null;
        truncatedPointCount = 0;
        onePhasePointCount = 0;
        closurePrunedCount = 0;
        note = "";
        organicRefluxRatio = 0.0;
        nodeSideDrawRates = new double[topology.nodeCount()];
        sinkEdges = List.of();
        componentFloors = new double[componentCount];
        totalFeedFlowMolPerSecond = 0.0;
    }

    private V3TruncationSupport(V3ColumnProblem problem, double cutoffMoleFraction,
                                byte[][] phases, int closurePrunedCount, String note) {
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
        byte[][] copy = copyPhases(phases);
        int truncated = 0;
        int onePhase = 0;
        if (copy != null) {
            for (byte[] row : copy) {
                for (byte mask : row) {
                    PointPhases point = PointPhases.ofMask(mask);
                    if (point == PointPhases.ABSENT) truncated++;
                    else if (point.isOnePhase()) onePhase++;
                }
            }
        }
        truncatedPointCount = truncated;
        onePhasePointCount = onePhase;
        this.phases = truncated == 0 && onePhase == 0 ? null : copy;
        componentFloors = new double[componentCount];
        for (int component = 0; component < componentCount; component++) {
            componentFloors[component] = problem.activeComponentBasis().flowScale(component) * TRACE_FLOOR_FRACTION;
        }
        totalFeedFlowMolPerSecond = problem.activeComponentBasis().totalFeedFlowMolPerSecond();
        requireCompatible(problem);
        sinkEdges = truncatedPointCount == 0 ? List.of() : enumerateSinkEdges(problem);
    }

    static V3TruncationSupport identity(V3ColumnTopology topology, int componentCount) {
        return new V3TruncationSupport(topology, componentCount);
    }

    /**
     * Freezes support from the final seed; the seed is read only and no state reference is retained.
     *
     * <p>The {@link #TRACE_FLOOR_FRACTION} floor is always applied, and it is applied per phase: a phase
     * whose flow is below the floor is not an unknown, and a point below the floor in both of its testable
     * phases is removed entirely, whatever the requested cutoff is. An authored cutoff composes with the
     * floor on the point as a whole — it can only turn a point ABSENT — so a point survives the cutoff only
     * when it passes the mole-fraction rule in a testable phase.</p>
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
        byte[][] phases = new byte[topology.nodeCount()][components];
        for (int node = 0; node < topology.nodeCount(); node++) {
            double liquidTotal = phaseTotal(problem, decidingState, node, true);
            double vaporTotal = phaseTotal(problem, decidingState, node, false);
            for (int component = 0; component < components; component++) {
                // The feed tray is the root of every material path and is retained whole, whatever its
                // flows are. The trays between it and a side draw are decided by the flow like any other
                // tray: the draw's own supply is guaranteed by keeping the liquid it receives from above
                // (see ensureSideDrawLiquid), and the path back to the feed by reachability pruning.
                if (node == topology.feedTrayNumber()) {
                    phases[node][component] = PointPhases.BOTH.mask();
                    continue;
                }
                boolean testLiquid = problem.condenserComponentPhases().hasLiquid(topology, node, component)
                        && topology.hasLiquidPhase(node) && liquidTotal > 0.0;
                boolean testVapor = topology.hasVaporPhase(node) && vaporTotal > 0.0;
                double floor = problem.activeComponentBasis().flowScale(component) * TRACE_FLOOR_FRACTION;
                boolean liquidAboveFloor = testLiquid && decidingState.liquidFlow(node, component) >= floor;
                boolean vaporAboveFloor = testVapor && decidingState.vaporFlow(node, component) >= floor;
                // A stage with no flow at all in either present phase is untestable, exactly as it is for the
                // mole-fraction cutoff, and is conservatively retained in both phases. A phase that is not
                // testable here is not removable either: its absence is already structural.
                PointPhases point = !testLiquid && !testVapor ? PointPhases.BOTH
                        : !liquidAboveFloor && !vaporAboveFloor ? PointPhases.ABSENT
                        : PointPhases.of(!testLiquid || liquidAboveFloor, !testVapor || vaporAboveFloor);
                boolean passesCutoff = cutoffMoleFraction == 0.0
                        || (!testLiquid && !testVapor)
                        || (testLiquid && decidingState.liquidFlow(node, component) / liquidTotal >= cutoffMoleFraction)
                        || (testVapor && decidingState.vaporFlow(node, component) / vaporTotal >= cutoffMoleFraction);
                phases[node][component] = (passesCutoff ? point : PointPhases.ABSENT).mask();
            }
        }
        breakEquilibriumFreeCycles(problem, phases);
        restoreEmptiedPhases(problem, phases);
        int pruned = pruneUnreachable(problem, phases);
        // Reachability has to be settled first: the guarantee is over the liquid the tray above actually
        // keeps, and pruning is what decides that. The repair only adds phases, so nothing it writes can
        // become unreachable, and a point it lifts out of ABSENT is reachable by the very edge that lifted it.
        ensureSideDrawLiquid(problem, phases);
        breakEquilibriumFreeCycles(problem, phases);
        if (!phasesNonempty(problem, phases)) {
            return new V3TruncationSupport(problem, cutoffMoleFraction, null, pruned,
                    "Stage-trace support fell back to identity: feed reachability emptied a structural phase");
        }
        // The floor alone removing nothing is the common case on a well-populated state. Reusing the problem's
        // own identity support then keeps the exact off switch: no new support, ledger or problem is built.
        if (cutoffMoleFraction == 0.0 && pruned == 0 && allBoth(phases)) return problem.truncationSupport();
        return new V3TruncationSupport(problem, cutoffMoleFraction, phases, pruned, "");
    }

    PointPhases pointPhases(int node, int component) {
        if (node < 0 || node >= topology.nodeCount() || component < 0 || component >= componentCount) {
            throw new IndexOutOfBoundsException("V3 truncation point is outside the topology/component basis");
        }
        return phases == null ? PointPhases.BOTH : PointPhases.ofMask(phases[node][component]);
    }

    /** The point is not ABSENT: it carries a material row and at least one flow unknown. */
    boolean retains(int node, int component) {
        return pointPhases(node, component) != PointPhases.ABSENT;
    }

    /** The support keeps this point's liquid phase; the structural phase rules still apply on top. */
    boolean retainsLiquid(int node, int component) {
        return pointPhases(node, component).retainsLiquid();
    }

    /** The support keeps this point's vapour phase; the structural phase rules still apply on top. */
    boolean retainsVapor(int node, int component) {
        return pointPhases(node, component).retainsVapor();
    }

    /**
     * The single liquid-unknown query of the package: structural liquid phase, condenser component rule and
     * support mask together.
     */
    boolean hasLiquidUnknown(V3CondenserComponentPhases condenserComponentPhases, int node, int component) {
        return topology.hasLiquidPhase(node)
                && condenserComponentPhases.hasLiquid(topology, node, component)
                && retainsLiquid(node, component);
    }

    /** The single vapour-unknown query of the package. */
    boolean hasVaporUnknown(int node, int component) {
        return topology.hasVaporPhase(node) && retainsVapor(node, component);
    }

    /** A VLE row exists exactly where both phases of the point are unknowns. */
    boolean hasEquilibriumRow(V3CondenserComponentPhases condenserComponentPhases, int node, int component) {
        return hasLiquidUnknown(condenserComponentPhases, node, component) && hasVaporUnknown(node, component);
    }

    boolean isIdentity() { return truncatedPointCount == 0 && onePhasePointCount == 0; }
    double cutoffMoleFraction() { return cutoffMoleFraction; }
    int totalPointCount() { return topology.nodeCount() * componentCount; }
    int truncatedPointCount() { return truncatedPointCount; }
    /** Points that keep exactly one of two testable phases; the set the phase-defect audit bounds. */
    int onePhasePointCount() { return onePhasePointCount; }
    /** Points that are not ABSENT. */
    int retainedPointCount() { return totalPointCount() - truncatedPointCount; }

    /**
     * Phases the support keeps over all points; the quantity a refresh must increase to be worth a re-solve.
     *
     * <p>Counting points is not enough once presence is per phase: a refresh that restores one phase of a
     * one-phase point changes nothing about the retained-point count and is still exactly the kind of
     * refresh that carries new information into a stalled attempt. Structurally absent phases are counted as
     * present because they never move within one attempt chain, so only the support's own decisions can
     * change this number.</p>
     */
    int presentPhaseCount() {
        return 2 * totalPointCount() - 2 * truncatedPointCount - onePhasePointCount;
    }
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
                // Preserve positivity even for a subnormal component flow scale. A present phase is at or
                // above the support floor by construction, so the same floor is the reinsertion value; an
                // absent phase is left at exactly zero.
                double floor = Math.max(Double.MIN_VALUE,
                        problem.activeComponentBasis().flowScale(component) * TRACE_FLOOR_FRACTION);
                if (hasLiquidUnknown(problem.condenserComponentPhases(), node, component)) {
                    double flow = seed.liquidFlow(node, component);
                    liquid[node][component] = flow > 0.0 ? flow : floor;
                }
                if (hasVaporUnknown(node, component)) {
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
        if (phases == null || other.phases == null) return (phases == null) == (other.phases == null);
        for (int node = 0; node < phases.length; node++) {
            if (!java.util.Arrays.equals(phases[node], other.phases[node])) return false;
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
        if (truncatedPointCount == 0 || totalFeedFlowMolPerSecond <= 0.0) return 0.0;
        double bound = 0.0;
        for (SinkEdge edge : sinkEdges) bound += componentFloors[edge.component()];
        return FLOOR_DEFECT_SLACK * FLOOR_REINSERTION_FACTOR * bound / totalFeedFlowMolPerSecond;
    }

    /**
     * Construction bound on the equilibrium-implied flow of an absent phase, relative to its component's feed.
     *
     * <p>A one-phase point loses no mass — its material row conserves the component into the present phase —
     * so it has no sink edge. What it approximates away is the flow the absent phase would have carried, and
     * the reinsertion rule bounds exactly that: a phase is restored once its implied flow reaches
     * {@link #FLOOR_REINSERTION_FACTOR} floors, so after a refresh no absent phase can be implying more.</p>
     */
    double phaseDefectBoundFraction() {
        return FLOOR_DEFECT_SLACK * FLOOR_REINSERTION_FACTOR * TRACE_FLOOR_FRACTION;
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
        V3CondenserComponentPhases condenserPhases = problem.condenserComponentPhases();
        for (int node = 0; node < topology.nodeCount(); node++) {
            for (int component = 0; component < componentCount; component++) {
                if (!retains(node, component)) continue;
                // Only a present phase of the source can deliver material into a removed neighbour.
                if (node > topology.condenserNode() && !retains(node - 1, component)
                        && hasVaporUnknown(node, component)) {
                    edges.add(new SinkEdge(SinkKind.VAPOR_TO_ABOVE, node, node - 1, component));
                }
                if (node < topology.reboilerNode() && !retains(node + 1, component)
                        && hasLiquidUnknown(condenserPhases, node, component)) {
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
        if (!phasesNonempty(problem, phases)) {
            throw new IllegalArgumentException("V3 truncation support empties a structural phase");
        }
        double refluxRatio = refluxRatio(problem);
        if (refluxRatio != organicRefluxRatio) {
            throw new IllegalArgumentException("V3 truncation support has a different reflux control");
        }
        boolean[][] reachable = reachableFromFeed(problem, phases, refluxRatio);
        for (int node = 0; node < topology.nodeCount(); node++) {
            if (nodeSideDrawRates[node] != problem.nodeSideDrawMolPerSecond(node)) {
                throw new IllegalArgumentException("V3 truncation support has different side draw rates");
            }
            for (int component = 0; component < componentCount; component++) {
                // A side-draw tray keeps the liquid it receives, not every point on the way to the feed.
                if (nodeSideDrawRates[node] > 0.0
                        && requiresSideDrawLiquid(topology, problem.condenserComponentPhases(), phases,
                                refluxRatio, node, component)
                        && !retainsLiquid(node, component)) {
                    throw new IllegalArgumentException(
                            "V3 truncation support cannot remove the liquid a side-draw tray receives");
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

    private byte[][] copyPhases(byte[][] source) {
        if (source == null) return null;
        if (source.length != topology.nodeCount()) {
            throw new IllegalArgumentException("V3 phase-mask matrix does not match the topology");
        }
        byte[][] copy = new byte[source.length][];
        for (int node = 0; node < source.length; node++) {
            if (source[node] == null || source[node].length != componentCount) {
                throw new IllegalArgumentException("V3 phase-mask row does not match the component basis");
            }
            copy[node] = source[node].clone();
            for (byte mask : copy[node]) PointPhases.ofMask(mask);
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

    /**
     * Keeps an equilibrium row on every internal liquid/vapour cycle a component can circulate in.
     *
     * <p>Two adjacent stages exchange one component in a closed loop: its liquid falls from {@code n} to
     * {@code n+1} and its vapour rises from {@code n+1} back to {@code n}. Adding the same {@code δ} to both
     * of those flows leaves the two material rows reading
     * {@code +δ − δ = 0} and {@code +(1−w)δ − δ = −wδ}, so with no side draw on {@code n} the pair is an
     * exact null direction of the material block and the only rows that can pin it are the two equilibrium
     * rows — which is why a fully retained point, carrying both flows and its own VLE row, never produced
     * one. A LIQUID_ONLY stage directly above a VAPOR_ONLY stage has neither row and reintroduces exactly the
     * rank deficiency the flow floor exists to remove: the observed symptom is a trace material balance that
     * admits no Armijo-reducing Newton step.</p>
     *
     * <p>So a one-phase point may not sit on the liquid side of such a pair unless the other end keeps its
     * equilibrium row; the upper stage, whose vapour the floor removed, is restored. The condenser pair is
     * exempt because the reflux split makes the same determinant {@code 1 − R/(1+R)}, which is positive for
     * every authored reflux ratio.</p>
     */
    private static void breakEquilibriumFreeCycles(V3ColumnProblem problem, byte[][] phases) {
        V3ColumnTopology topology = problem.topology();
        V3CondenserComponentPhases condenserPhases = problem.condenserComponentPhases();
        for (int node = topology.condenserNode() + 1; node < topology.reboilerNode(); node++) {
            for (int component = 0; component < phases[node].length; component++) {
                boolean liquidDown = topology.hasLiquidPhase(node)
                        && condenserPhases.hasLiquid(topology, node, component)
                        && PointPhases.ofMask(phases[node][component]).retainsLiquid();
                boolean vaporUp = topology.hasVaporPhase(node + 1)
                        && PointPhases.ofMask(phases[node + 1][component]).retainsVapor();
                if (!liquidDown || !vaporUp) continue;
                if (equilibriumRow(problem, phases, node, component)
                        || equilibriumRow(problem, phases, node + 1, component)) {
                    continue;
                }
                phases[node][component] = PointPhases.BOTH.mask();
            }
        }
    }

    private static boolean equilibriumRow(
            V3ColumnProblem problem, byte[][] phases, int node, int component) {
        V3ColumnTopology topology = problem.topology();
        PointPhases point = PointPhases.ofMask(phases[node][component]);
        return topology.hasLiquidPhase(node) && topology.hasVaporPhase(node)
                && problem.condenserComponentPhases().hasLiquid(topology, node, component)
                && point.retainsLiquid() && point.retainsVapor();
    }

    /**
     * The floor may thin a node's phase but never empty it.
     *
     * <p>A node whose every component is below the floor in one phase is a node whose whole phase has
     * collapsed, and a structural phase with no unknown has no composition, no enthalpy and no equilibrium
     * row to write. Restoring the phase on the points that are still present leaves exactly the support the
     * point-level floor produced before phases were separable — such a point used to be retained in both
     * phases on the strength of the other one — so this is a superset of the previous rule, not a new
     * approximation. A node that has no present point left at all is a reachability question and is handled
     * by the identity fallback in {@link #derive}.</p>
     */
    private static void restoreEmptiedPhases(V3ColumnProblem problem, byte[][] phases) {
        V3ColumnTopology topology = problem.topology();
        V3CondenserComponentPhases condenserPhases = problem.condenserComponentPhases();
        for (int node = 0; node < topology.nodeCount(); node++) {
            boolean liquid = false;
            boolean vapor = false;
            for (int component = 0; component < phases[node].length; component++) {
                PointPhases point = PointPhases.ofMask(phases[node][component]);
                liquid |= point.retainsLiquid() && topology.hasLiquidPhase(node)
                        && condenserPhases.hasLiquid(topology, node, component);
                vapor |= point.retainsVapor() && topology.hasVaporPhase(node);
            }
            boolean restoreLiquid = topology.hasLiquidPhase(node) && !liquid;
            boolean restoreVapor = topology.hasVaporPhase(node) && !vapor;
            if (!restoreLiquid && !restoreVapor) continue;
            for (int component = 0; component < phases[node].length; component++) {
                PointPhases point = PointPhases.ofMask(phases[node][component]);
                if (point == PointPhases.ABSENT) continue;
                phases[node][component] = PointPhases.of(point.retainsLiquid() || restoreLiquid,
                        point.retainsVapor() || restoreVapor).mask();
            }
        }
    }

    /**
     * A side draw keeps every component the tray above delivers to it in the liquid.
     *
     * <p>This replaces the forced product-path band. The band retained every point on every tray from the
     * feed tray to the outermost draw tray in both phases regardless of flow, which was measured at 36 to 59
     * below-floor points on a case with draws — the largest single block of trace unknowns left, and the only
     * points the flow floor could not remove, hence the only place the trace-pair rank deficiency could still
     * form. The draw's actual requirement is narrower: what a liquid side draw withdraws is a share of the
     * liquid arriving from the tray above, so exactly those components must stay in that tray's liquid, and
     * only on the draw tray itself. Everything else on the path is a reachability question, which
     * {@link #pruneUnreachable} already answers.</p>
     *
     * <p>The sweep runs downward so that a component lifted onto one draw tray is seen by the next draw tray
     * below it. The pass only ever adds a liquid phase.</p>
     */
    private static void ensureSideDrawLiquid(V3ColumnProblem problem, byte[][] phases) {
        V3ColumnTopology topology = problem.topology();
        V3CondenserComponentPhases condenserPhases = problem.condenserComponentPhases();
        double refluxRatio = refluxRatio(problem);
        for (int node = 0; node < topology.nodeCount(); node++) {
            if (!(problem.nodeSideDrawMolPerSecond(node) > 0.0)) continue;
            for (int component = 0; component < phases[node].length; component++) {
                if (!requiresSideDrawLiquid(topology, condenserPhases, phases, refluxRatio, node, component)) continue;
                PointPhases point = PointPhases.ofMask(phases[node][component]);
                phases[node][component] = PointPhases.of(true, point.retainsVapor()).mask();
            }
        }
    }

    /**
     * The side-draw rule, as one predicate shared by {@link #ensureSideDrawLiquid} and
     * {@link #requireCompatible(V3ColumnProblem)} so that the two can never disagree.
     */
    private static boolean requiresSideDrawLiquid(
            V3ColumnTopology topology, V3CondenserComponentPhases condenserPhases, byte[][] phases,
            double refluxRatio, int node, int component) {
        int above = node - 1;
        if (above < 0 || !topology.hasLiquidPhase(node)
                || !condenserPhases.hasLiquid(topology, node, component)) {
            return false;
        }
        if (above == topology.condenserNode() && !(refluxRatio > 0.0)) return false;
        return topology.hasLiquidPhase(above)
                && condenserPhases.hasLiquid(topology, above, component)
                && PointPhases.ofMask(phases[above][component]).retainsLiquid();
    }

    private static int pruneUnreachable(V3ColumnProblem problem, byte[][] phases) {
        boolean[][] reachable = reachableFromFeed(problem, phases, refluxRatio(problem));
        int pruned = 0;
        for (int node = 0; node < phases.length; node++) {
            for (int component = 0; component < phases[node].length; component++) {
                if (phases[node][component] != PointPhases.ABSENT.mask() && !reachable[node][component]) {
                    phases[node][component] = PointPhases.ABSENT.mask();
                    pruned++;
                }
            }
        }
        return pruned;
    }

    /**
     * A recirculating group cannot supply itself: every retained point needs a directed path from the feed.
     *
     * <p>The path is per present phase. Liquid can only leave a node whose liquid phase the support keeps,
     * and vapour only a node whose vapour phase it keeps, so a LIQUID_ONLY point passes material downward
     * only and a VAPOR_ONLY point upward only.</p>
     */
    private static boolean[][] reachableFromFeed(V3ColumnProblem problem, byte[][] phases, double refluxRatio) {
        V3ColumnTopology topology = problem.topology();
        V3CondenserComponentPhases condenserPhases = problem.condenserComponentPhases();
        int components = problem.activeComponentBasis().componentCount();
        boolean[][] reachable = new boolean[topology.nodeCount()][components];
        int[] pending = new int[topology.nodeCount()];
        for (int component = 0; component < components; component++) {
            int feed = topology.feedTrayNumber();
            if (phases[feed][component] == PointPhases.ABSENT.mask()) continue;
            int next = 0;
            int count = 1;
            pending[0] = feed;
            reachable[feed][component] = true;
            while (next < count) {
                int node = pending[next++];
                if (node > topology.condenserNode()
                        && topology.hasVaporPhase(node)
                        && PointPhases.ofMask(phases[node][component]).retainsVapor()
                        && phases[node - 1][component] != PointPhases.ABSENT.mask()
                        && !reachable[node - 1][component]) {
                    reachable[node - 1][component] = true;
                    pending[count++] = node - 1;
                }
                if (node < topology.reboilerNode()
                        && topology.hasLiquidPhase(node)
                        && condenserPhases.hasLiquid(topology, node, component)
                        && PointPhases.ofMask(phases[node][component]).retainsLiquid()
                        && (node != topology.condenserNode() || refluxRatio > 0.0)
                        && phases[node + 1][component] != PointPhases.ABSENT.mask()
                        && !reachable[node + 1][component]) {
                    reachable[node + 1][component] = true;
                    pending[count++] = node + 1;
                }
            }
        }
        return reachable;
    }

    private static boolean allBoth(byte[][] phases) {
        for (byte[] row : phases) for (byte mask : row) if (mask != PointPhases.BOTH.mask()) return false;
        return true;
    }

    private static boolean phasesNonempty(V3ColumnProblem problem, byte[][] phases) {
        V3ColumnTopology topology = problem.topology();
        V3CondenserComponentPhases condenserPhases = problem.condenserComponentPhases();
        for (int node = 0; node < topology.nodeCount(); node++) {
            boolean liquid = false;
            boolean vapor = false;
            for (int component = 0; component < phases[node].length; component++) {
                PointPhases point = PointPhases.ofMask(phases[node][component]);
                liquid |= point.retainsLiquid() && topology.hasLiquidPhase(node)
                        && condenserPhases.hasLiquid(topology, node, component);
                vapor |= point.retainsVapor() && topology.hasVaporPhase(node);
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
