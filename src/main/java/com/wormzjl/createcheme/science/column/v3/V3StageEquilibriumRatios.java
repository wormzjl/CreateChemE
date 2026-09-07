package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3FugacityResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3Phase;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoException;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoModel;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoWorkspace;
import java.util.Objects;

/**
 * Per-node equilibrium ratios of one candidate state, for deciding and auditing an absent phase.
 *
 * <p>{@code K_c = exp(lnφ_L,c − lnφ_V,c)} is the ratio the VLE row drives {@code y_c/x_c} to. It is defined
 * for every active component of a two-phase node, including one whose flow the support removed from a phase:
 * a mixture fugacity coefficient is a property of the phase composition as a whole, not of the component's
 * own mole fraction, so the ratio still says what the absent phase would carry. That is the quantity the
 * phase reinsertion rule tests and the {@code PHASE_TRUNCATION_DEFECT} audit bounds.</p>
 *
 * <p>The compositions are the same normalised phase compositions
 * {@link V3MeshResidualEvaluator} assembles its residual from, read leniently: a node whose phase carries no
 * positive flow at all, or whose properties do not evaluate, simply has no ratios and no phase can be
 * decided there. That keeps this usable on a raw seed, before any support has been derived from it.</p>
 */
final class V3StageEquilibriumRatios {
    private final Node[] nodes;

    private V3StageEquilibriumRatios(Node[] nodes) {
        this.nodes = nodes;
    }

    static V3StageEquilibriumRatios of(
            V3ColumnProblem problem, V3ThermoModel thermo, V3DryMeshState state, V3ThermoWorkspace workspace) {
        problem = Objects.requireNonNull(problem, "problem");
        thermo = Objects.requireNonNull(thermo, "thermo");
        state = Objects.requireNonNull(state, "state");
        workspace = Objects.requireNonNull(workspace, "workspace");
        V3ColumnTopology topology = problem.topology();
        V3ActiveComponentBasis basis = problem.activeComponentBasis();
        if (state.nodeCount() != topology.nodeCount() || state.componentCount() != basis.componentCount()) {
            throw new IllegalArgumentException("V3 equilibrium-ratio state does not match the resolved problem");
        }
        Node[] nodes = new Node[topology.nodeCount()];
        for (int node = 0; node < nodes.length; node++) {
            if (!topology.hasLiquidPhase(node) || !topology.hasVaporPhase(node)) continue;
            double liquidTotal = phaseTotal(state, node, true);
            double vaporTotal = phaseTotal(state, node, false);
            if (!(liquidTotal > 0.0) || !(vaporTotal > 0.0)
                    || !Double.isFinite(state.temperatureKelvin(node)) || state.temperatureKelvin(node) <= 0.0) {
                continue;
            }
            double[] ratios = new double[basis.componentCount()];
            try {
                V3FugacityResult liquid = thermo.fugacity(state.temperatureKelvin(node),
                        problem.nodePressurePascal(node), composition(problem, state, node, true, liquidTotal),
                        V3Phase.LIQUID, workspace);
                V3FugacityResult vapor = thermo.fugacity(state.temperatureKelvin(node),
                        problem.nodePressurePascal(node), composition(problem, state, node, false, vaporTotal),
                        V3Phase.VAPOR, workspace);
                for (int component = 0; component < ratios.length; component++) {
                    int publicComponent = basis.publicIndex(component);
                    ratios[component] = Math.exp(liquid.logFugacityCoefficient(publicComponent)
                            - vapor.logFugacityCoefficient(publicComponent));
                }
            } catch (V3ThermoException | IllegalArgumentException unavailable) {
                continue;
            }
            // The wet VLE row carries a water dilution term, so the vapour total that balances the liquid is
            // the whole molecular vapour including water, not the hydrocarbon vapour alone.
            nodes[node] = new Node(ratios, liquidTotal, vaporTotal + waterVaporFlow(problem, state, node));
        }
        return new V3StageEquilibriumRatios(nodes);
    }

    /** Ratios of one node, or null where the node has no two-phase equilibrium at this state. */
    Node node(int node) {
        return nodes[node];
    }

    private static double waterVaporFlow(V3ColumnProblem problem, V3DryMeshState state, int node) {
        if (!problem.hasSteamFeeds()) return 0.0;
        return node == problem.topology().condenserNode()
                ? problem.waterCondenserSplit(state).vaporFlowMolPerSecond()
                : problem.waterVaporFlowMolPerSecond(node);
    }

    private static double[] composition(
            V3ColumnProblem problem, V3DryMeshState state, int node, boolean liquid, double total) {
        double[] composition = new double[problem.input().componentBasis().componentCount()];
        for (int component = 0; component < state.componentCount(); component++) {
            double flow = liquid ? state.liquidFlow(node, component) : state.vaporFlow(node, component);
            if (!(flow > 0.0)) continue;
            composition[problem.activeComponentBasis().publicIndex(component)] = flow / total;
        }
        return composition;
    }

    private static double phaseTotal(V3DryMeshState state, int node, boolean liquid) {
        double total = 0.0;
        for (int component = 0; component < state.componentCount(); component++) {
            total += liquid ? state.liquidFlow(node, component) : state.vaporFlow(node, component);
        }
        return Double.isFinite(total) ? total : 0.0;
    }

    /** Equilibrium ratios and the two phase totals of one two-phase node. */
    record Node(double[] equilibriumRatios, double liquidTotalMolPerSecond, double vaporTotalMolPerSecond) {
        Node {
            equilibriumRatios = Objects.requireNonNull(equilibriumRatios, "equilibriumRatios").clone();
            if (!Double.isFinite(liquidTotalMolPerSecond) || liquidTotalMolPerSecond <= 0.0
                    || !Double.isFinite(vaporTotalMolPerSecond) || vaporTotalMolPerSecond <= 0.0) {
                throw new IllegalArgumentException("V3 equilibrium-ratio phase totals must be finite and positive");
            }
        }

        @Override public double[] equilibriumRatios() { return equilibriumRatios.clone(); }

        double equilibriumRatio(int component) { return equilibriumRatios[component]; }

        /** Vapour flow the equilibrium row would give a component the state carries only in the liquid. */
        double impliedVaporFlowMolPerSecond(int component, double liquidFlowMolPerSecond) {
            double implied = equilibriumRatios[component] * vaporTotalMolPerSecond / liquidTotalMolPerSecond
                    * liquidFlowMolPerSecond;
            return Double.isFinite(implied) && implied > 0.0 ? implied : 0.0;
        }

        /** Liquid flow the equilibrium row would give a component the state carries only in the vapour. */
        double impliedLiquidFlowMolPerSecond(int component, double vaporFlowMolPerSecond) {
            double denominator = equilibriumRatios[component] * vaporTotalMolPerSecond;
            if (!(denominator > 0.0)) return 0.0;
            double implied = liquidTotalMolPerSecond / denominator * vaporFlowMolPerSecond;
            return Double.isFinite(implied) && implied > 0.0 ? implied : 0.0;
        }
    }
}
