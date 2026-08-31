package com.wormzjl.createcheme.science.column.v3;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, attempt-local component/stage support. A removed point has neither flow coordinates nor
 * material/VLE rows. Derivation is not wired into production solves until the numerical/audit phases.
 */
final class V3TruncationSupport {
    static final double MAX_CUTOFF_MOLE_FRACTION = 0.01;

    private final V3ColumnTopology topology;
    private final int componentCount;
    private final double cutoffMoleFraction;
    // Null represents identity without allocating a full retained-point matrix.
    private final boolean[][] retained;
    private final int truncatedPointCount;
    private final int closurePrunedCount;
    private final String note;
    private final double organicRefluxRatio;
    private final List<SinkEdge> sinkEdges;

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
        sinkEdges = List.of();
    }

    private V3TruncationSupport(V3ColumnProblem problem, double cutoffMoleFraction,
                                boolean[][] retained, int closurePrunedCount, String note) {
        topology = problem.topology();
        componentCount = problem.activeComponentBasis().componentCount();
        requireCutoff(cutoffMoleFraction);
        this.cutoffMoleFraction = cutoffMoleFraction;
        organicRefluxRatio = refluxRatio(problem);
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
        if (cutoffMoleFraction == 0.0 && !isIdentity()) {
            throw new IllegalArgumentException("V3 zero cutoff must retain every point");
        }
        requireCompatible(problem);
        sinkEdges = isIdentity() ? List.of() : enumerateSinkEdges(problem);
    }

    static V3TruncationSupport identity(V3ColumnTopology topology, int componentCount) {
        return new V3TruncationSupport(topology, componentCount);
    }

    /** Freezes support from the final seed; the seed is read only and no state reference is retained. */
    static V3TruncationSupport derive(V3ColumnProblem problem, double cutoffMoleFraction,
                                      V3DryMeshState decidingState) {
        Objects.requireNonNull(problem, "problem");
        requireCutoff(cutoffMoleFraction);
        if (!problem.truncationSupport().isIdentity()) {
            throw new IllegalArgumentException("V3 support must be derived from an untruncated problem");
        }
        // The exact off switch does not inspect a seed or allocate support/ledger/problem objects.
        if (cutoffMoleFraction == 0.0) return problem.truncationSupport();
        Objects.requireNonNull(decidingState, "decidingState");
        V3ColumnTopology topology = problem.topology();
        int components = problem.activeComponentBasis().componentCount();
        if (decidingState.nodeCount() != topology.nodeCount() || decidingState.componentCount() != components) {
            throw new IllegalArgumentException("V3 deciding state does not match the truncation support");
        }
        boolean[][] retained = new boolean[topology.nodeCount()][components];
        for (int node = 0; node < topology.nodeCount(); node++) {
            double liquidTotal = phaseTotal(problem, decidingState, node, true);
            double vaporTotal = phaseTotal(problem, decidingState, node, false);
            for (int component = 0; component < components; component++) {
                boolean testLiquid = problem.condenserComponentPhases().hasLiquid(topology, node, component)
                        && liquidTotal > 0.0;
                boolean testVapor = topology.hasVaporPhase(node) && vaporTotal > 0.0;
                retained[node][component] = node == topology.feedTrayNumber()
                        || (!testLiquid && !testVapor)
                        || (testLiquid && decidingState.liquidFlow(node, component) / liquidTotal >= cutoffMoleFraction)
                        || (testVapor && decidingState.vaporFlow(node, component) / vaporTotal >= cutoffMoleFraction);
            }
        }
        int pruned = pruneUnsupported(problem, retained);
        if (!phasesNonempty(problem, retained)) {
            return new V3TruncationSupport(problem, cutoffMoleFraction, null, pruned,
                    "Stage-trace support fell back to identity: inflow closure emptied a structural phase");
        }
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
        if (cutoffMoleFraction == 0.0) return seed;
        double[][] liquid = new double[topology.nodeCount()][componentCount];
        double[][] vapor = new double[topology.nodeCount()][componentCount];
        double[] temperatures = new double[topology.nodeCount()];
        for (int node = 0; node < topology.nodeCount(); node++) {
            temperatures[node] = seed.temperatureKelvin(node);
            for (int component = 0; component < componentCount; component++) {
                if (!retains(node, component)) continue;
                // Preserve positivity even for a subnormal component flow scale.
                double floor = Math.max(Double.MIN_VALUE, problem.activeComponentBasis().flowScale(component) * 1.0e-10);
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

    /** Total solved molar flow into removed points. Product exits are deliberately excluded. */
    double massDefectMolPerSecond(V3DryMeshState state) {
        requireState(state);
        double defect = 0.0;
        for (SinkEdge edge : sinkEdges) {
            defect += switch (edge.kind()) {
                case LIQUID_TO_BELOW -> state.liquidFlow(edge.sourceNode(), edge.component());
                case VAPOR_TO_ABOVE -> state.vaporFlow(edge.sourceNode(), edge.component());
                case REFLUX_TO_TRAY_ONE -> organicRefluxRatio / (1.0 + organicRefluxRatio)
                        * state.liquidFlow(edge.sourceNode(), edge.component());
            };
        }
        if (!Double.isFinite(defect)) throw new IllegalArgumentException("V3 truncation mass defect must be finite");
        return defect;
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
        for (int node = 0; node < topology.nodeCount(); node++) {
            for (int component = 0; component < componentCount; component++) {
                if (node == topology.feedTrayNumber()) {
                    if (!retains(node, component)) {
                        throw new IllegalArgumentException("V3 truncation support cannot remove a feed-tray point");
                    }
                } else if (retains(node, component)
                        && !hasRetainedInflow(problem, retained, refluxRatio, node, component)) {
                    throw new IllegalArgumentException("V3 retained point has no retained inflow source");
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

    private static int pruneUnsupported(V3ColumnProblem problem, boolean[][] retained) {
        int pruned = 0;
        boolean changed;
        double refluxRatio = refluxRatio(problem);
        do {
            changed = false;
            for (int node = 0; node < retained.length; node++) {
                if (node == problem.topology().feedTrayNumber()) continue;
                for (int component = 0; component < retained[node].length; component++) {
                    if (retained[node][component]
                            && !hasRetainedInflow(problem, retained, refluxRatio, node, component)) {
                        retained[node][component] = false;
                        pruned++;
                        changed = true;
                    }
                }
            }
        } while (changed);
        return pruned;
    }

    private static boolean hasRetainedInflow(V3ColumnProblem problem, boolean[][] retained,
                                             double refluxRatio, int node, int component) {
        V3ColumnTopology topology = problem.topology();
        boolean fromAbove = node > topology.condenserNode() && retained[node - 1][component]
                && problem.condenserComponentPhases().hasLiquid(topology, node - 1, component)
                && (node != 1 || refluxRatio > 0.0);
        boolean fromBelow = node < topology.reboilerNode() && retained[node + 1][component]
                && topology.hasVaporPhase(node + 1);
        return fromAbove || fromBelow;
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
        if (!Double.isFinite(cutoff) || cutoff < 0.0 || cutoff > MAX_CUTOFF_MOLE_FRACTION) {
            throw new IllegalArgumentException("V3 stage-trace cutoff must be finite and in [0, 0.01] mole fraction");
        }
    }

    private static void requireComponentCount(int componentCount) {
        if (componentCount < 1 || componentCount > V3ComponentBasis.MAX_COMPONENTS) {
            throw new IllegalArgumentException("V3 truncation component count is outside the supported contract range");
        }
    }
}
