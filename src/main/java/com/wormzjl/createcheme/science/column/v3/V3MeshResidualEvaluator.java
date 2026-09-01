package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3FugacityResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3Phase;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoModel;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoWorkspace;
import com.wormzjl.createcheme.science.column.v3.thermo.V3WaterProperties;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Full dry MESH residual assembly; this class assembles equations only and cannot publish a solver result. */
final class V3MeshResidualEvaluator {
    private final V3ColumnProblem problem;
    private final V3ThermoModel thermo;
    private final double feedMolarEnthalpyJoulesPerMol;
    private final double organicRefluxRatio;
    private final double reboilerDutyWatts;
    private final double totalFeedFlow;
    private final V3ActiveComponentBasis activeComponentBasis;

    V3MeshResidualEvaluator(V3ColumnProblem problem, V3ThermoModel thermo, double feedMolarEnthalpyJoulesPerMol) {
        this.problem = Objects.requireNonNull(problem, "problem");
        this.thermo = Objects.requireNonNull(thermo, "thermo");
        if (!problem.input().componentBasis().equals(thermo.componentBasis())) {
            throw new IllegalArgumentException("V3 MESH thermodynamic basis differs from the resolved problem basis");
        }
        if (!Double.isFinite(feedMolarEnthalpyJoulesPerMol)) {
            throw new IllegalArgumentException("V3 MESH feed enthalpy must be finite");
        }
        this.feedMolarEnthalpyJoulesPerMol = feedMolarEnthalpyJoulesPerMol;
        this.organicRefluxRatio = specification(V3ColumnSpecification.OrganicRefluxRatio.class).ratio();
        this.reboilerDutyWatts = specification(V3ColumnSpecification.ReboilerDuty.class).watts();
        this.activeComponentBasis = problem.activeComponentBasis();
        this.totalFeedFlow = activeComponentBasis.totalFeedFlowMolPerSecond();
    }

    V3MeshResidual evaluate(V3DryMeshState state, V3ThermoWorkspace workspace) {
        state = Objects.requireNonNull(state, "state");
        workspace = Objects.requireNonNull(workspace, "workspace");
        if (state.nodeCount() != problem.topology().nodeCount()
                || state.componentCount() != activeComponentBasis.componentCount()) {
            throw new IllegalArgumentException("V3 MESH state does not match the resolved problem");
        }
        NodeProperties[] properties = nodeProperties(state, workspace);
        List<V3MeshResidual.Row> rows = new ArrayList<>(problem.degreeOfFreedomLedger().equationCount());
        for (V3DegreeOfFreedomLedger.Equation equation : problem.degreeOfFreedomLedger().equations()) {
            V3DegreeOfFreedomLedger.EquationId id = equation.id();
            double physicalValue = switch (id.family()) {
                case COMPONENT_MATERIAL_BALANCE -> materialResidual(state, id.node(), id.component());
                case VAPOR_LIQUID_EQUILIBRIUM -> equilibriumResidual(state, id.node(), id.component(), properties[id.node()]);
                case ENERGY_BALANCE -> energyResidual(state, id.node(), properties);
            };
            rows.add(new V3MeshResidual.Row(id, physicalValue, scale(id)));
        }
        return new V3MeshResidual(rows);
    }

    /**
     * Returns the thermodynamic terms whose value can change when only one stage block is perturbed.
     *
     * <p>Material rows are intentionally absent: their log-flow derivatives are exact and are assembled without a
     * property call. The returned phase-energy terms are total phase enthalpy rates, so adjacent energy rows can use
     * their difference directly.</p>
     */
    LocalNodeTerms localTerms(V3DryMeshState state, int node, V3ThermoWorkspace workspace) {
        state = Objects.requireNonNull(state, "state");
        workspace = Objects.requireNonNull(workspace, "workspace");
        if (state.nodeCount() != problem.topology().nodeCount()
                || state.componentCount() != activeComponentBasis.componentCount()
                || node < problem.topology().condenserNode() || node > problem.topology().reboilerNode()) {
            throw new IllegalArgumentException("V3 local MESH thermodynamic probe does not match its problem");
        }
        NodeProperties properties = nodeProperties(state, node, workspace);
        double[] equilibrium = new double[state.componentCount()];
        Arrays.fill(equilibrium, Double.NaN);
        for (int component = 0; component < equilibrium.length; component++) {
            if (problem.truncationSupport().retains(node, component)
                    && problem.condenserComponentPhases().hasVaporLiquidEquilibrium(problem.topology(), node, component)) {
                equilibrium[component] = equilibriumResidual(state, node, component, properties);
            }
        }
        double liquidEnergy = problem.topology().hasLiquidPhase(node)
                ? phaseEnergy(state, node, true, properties) : 0.0;
        double vaporEnergy = phaseEnergy(state, node, false, properties);
        return new LocalNodeTerms(equilibrium, liquidEnergy, vaporEnergy);
    }

    private double materialResidual(V3DryMeshState state, int node, int component) {
        V3ColumnTopology topology = problem.topology();
        if (node == topology.condenserNode()) {
            return state.vaporFlow(1, component) - state.vaporFlow(0, component)
                    - (problem.condenserComponentPhases().hasLiquid(topology, 0, component)
                    ? state.liquidFlow(0, component) : 0.0);
        }
        if (node <= topology.trayCount()) {
            double liquidIn = node == 1
                    ? (problem.condenserComponentPhases().hasLiquid(topology, 0, component)
                    ? organicRefluxFraction() * state.liquidFlow(0, component) : 0.0)
                    : (1.0 - problem.liquidWithdrawalFraction(state, node - 1)) * state.liquidFlow(node - 1, component);
            double feed = node == topology.feedTrayNumber() ? activeComponentBasis.feedFlowMolPerSecond(component) : 0.0;
            return liquidIn + state.vaporFlow(node + 1, component) + feed - state.liquidFlow(node, component)
                    - state.vaporFlow(node, component);
        }
        return (1.0 - problem.liquidWithdrawalFraction(state, node - 1)) * state.liquidFlow(node - 1, component)
                - state.liquidFlow(node, component) - state.vaporFlow(node, component);
    }

    private double equilibriumResidual(V3DryMeshState state, int node, int component, NodeProperties properties) {
        int publicComponent = activeComponentBasis.publicIndex(component);
        double residual = Math.log(properties.vaporComposition()[publicComponent])
                + properties.vaporResult().logFugacityCoefficient(publicComponent)
                - Math.log(properties.liquidComposition()[publicComponent])
                - properties.liquidResult().logFugacityCoefficient(publicComponent);
        return problem.hasSteamFeeds() ? residual + waterDilutionLogTerm(state, node, properties.vaporTotal()) : residual;
    }

    private double energyResidual(V3DryMeshState state, int node, NodeProperties[] properties) {
        V3ColumnTopology topology = problem.topology();
        if (node <= topology.trayCount()) {
            double liquidIn = node == 1
                    ? (topology.hasLiquidPhase(0) ? organicRefluxFraction() * phaseEnergy(state, 0, true, properties) : 0.0)
                    : (1.0 - problem.liquidWithdrawalFraction(state, node - 1)) * phaseEnergy(state, node - 1, true, properties);
            double vaporIn = phaseEnergy(state, node + 1, false, properties);
            double feed = node == topology.feedTrayNumber() ? totalFeedFlow * feedMolarEnthalpyJoulesPerMol : 0.0;
            return liquidIn + vaporIn + feed + problem.steamFeedEnthalpyWatts(node) - phaseEnergy(state, node, true, properties)
                    - phaseEnergy(state, node, false, properties);
        }
        return (1.0 - problem.liquidWithdrawalFraction(state, node - 1)) * phaseEnergy(state, node - 1, true, properties) + reboilerDutyWatts
                + problem.steamFeedEnthalpyWatts(node) - phaseEnergy(state, node, true, properties) - phaseEnergy(state, node, false, properties);
    }

    private double phaseEnergy(V3DryMeshState state, int node, boolean liquid, NodeProperties[] properties) {
        return phaseEnergy(state, node, liquid, properties[node]);
    }

    private double phaseEnergy(V3DryMeshState state, int node, boolean liquid, NodeProperties properties) {
        double totalFlow = phaseTotal(state, node, liquid);
        if (totalFlow == 0.0) return 0.0;
        double energy = totalFlow * (liquid ? properties.liquidResult().molarEnthalpyJoulesPerMol()
                : properties.vaporResult().molarEnthalpyJoulesPerMol());
        if (!liquid && problem.hasSteamFeeds()) {
            energy += waterVaporFlow(state, node) * V3WaterProperties.vaporMolarEnthalpy(state.temperatureKelvin(node));
        }
        return energy;
    }

    /** Builds immutable per-node property snapshots once per residual evaluation. */
    private NodeProperties[] nodeProperties(V3DryMeshState state, V3ThermoWorkspace workspace) {
        NodeProperties[] properties = new NodeProperties[state.nodeCount()];
        for (int node = 0; node < properties.length; node++) {
            properties[node] = nodeProperties(state, node, workspace);
        }
        return properties;
    }

    private NodeProperties nodeProperties(V3DryMeshState state, int node, V3ThermoWorkspace workspace) {
        double temperature = state.temperatureKelvin(node);
        double pressure = problem.nodePressurePascal(node);
        double[] vaporComposition = null;
        V3FugacityResult vaporResult = null;
        if (problem.topology().hasVaporPhase(node)) {
            vaporComposition = normalizedPublicPhaseComposition(state, node, false);
            vaporResult = thermo.fugacity(temperature, pressure, vaporComposition, V3Phase.VAPOR, workspace);
        }
        double[] liquidComposition = null;
        V3FugacityResult liquidResult = null;
        if (problem.topology().hasLiquidPhase(node)) {
            liquidComposition = normalizedPublicPhaseComposition(state, node, true);
            liquidResult = thermo.fugacity(temperature, pressure, liquidComposition, V3Phase.LIQUID, workspace);
        }
        return new NodeProperties(liquidComposition, vaporComposition, liquidResult, vaporResult,
                problem.topology().hasVaporPhase(node) ? phaseTotal(state, node, false) : 0.0);
    }

    private double[] normalizedPublicPhaseComposition(V3DryMeshState state, int node, boolean liquid) {
        double total = phaseTotal(state, node, liquid);
        if (!Double.isFinite(total) || total <= 0.0) {
            throw new IllegalArgumentException("V3 MESH equilibrium phase has no positive hydrocarbon flow");
        }
        double[] composition = new double[problem.input().componentBasis().componentCount()];
        for (int component = 0; component < state.componentCount(); component++) {
            double flow = liquid ? state.liquidFlow(node, component) : state.vaporFlow(node, component);
            if (!problem.truncationSupport().retains(node, component)) {
                if (flow != 0.0) throw new IllegalArgumentException("V3 truncated component flow must be exactly zero");
                continue;
            }
            if (flow == 0.0 && liquid && !problem.condenserComponentPhases().hasLiquid(problem.topology(), node, component)) {
                continue;
            }
            if (flow <= 0.0) throw new IllegalArgumentException("V3 MESH active component flow must be positive for logarithmic VLE");
            composition[activeComponentBasis.publicIndex(component)] = flow / total;
        }
        return composition;
    }

    private double phaseTotal(V3DryMeshState state, int node, boolean liquid) {
        double total = 0.0;
        for (int component = 0; component < state.componentCount(); component++) {
            total += liquid ? state.liquidFlow(node, component) : state.vaporFlow(node, component);
        }
        return total;
    }

    private double waterVaporFlow(V3DryMeshState state, int node) {
        return node == problem.topology().condenserNode()
                ? problem.waterCondenserSplit(state).vaporFlowMolPerSecond()
                : problem.waterVaporFlowMolPerSecond(node);
    }

    private double waterDilutionLogTerm(V3DryMeshState state, int node, double hydrocarbonVaporTotal) {
        double water = waterVaporFlow(state, node);
        return water == 0.0 ? 0.0 : Math.log(hydrocarbonVaporTotal / (hydrocarbonVaporTotal + water));
    }

    private double scale(V3DegreeOfFreedomLedger.EquationId equation) {
        return switch (equation.family()) {
            case COMPONENT_MATERIAL_BALANCE -> Math.max(
                    Math.abs(activeComponentBasis.feedFlowMolPerSecond(equation.component())), totalFeedFlow * 1.0e-12);
            case VAPOR_LIQUID_EQUILIBRIUM -> 1.0;
            case ENERGY_BALANCE -> Math.max(1.0, totalFeedFlow * 100_000.0);
        };
    }

    private double organicRefluxFraction() {
        return organicRefluxRatio / (1.0 + organicRefluxRatio);
    }

    private <T extends V3ColumnSpecification> T specification(Class<T> type) {
        return problem.input().specifications().stream().filter(type::isInstance).map(type::cast).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("V3 MESH problem is missing " + type.getSimpleName()));
    }

    private record NodeProperties(
            double[] liquidComposition,
            double[] vaporComposition,
            V3FugacityResult liquidResult,
            V3FugacityResult vaporResult,
            double vaporTotal) {}

    /** Package-local response of one node to a local coordinate perturbation. */
    record LocalNodeTerms(double[] equilibriumResiduals, double liquidPhaseEnergy, double vaporPhaseEnergy) {
        LocalNodeTerms {
            equilibriumResiduals = Objects.requireNonNull(equilibriumResiduals, "equilibriumResiduals").clone();
            if (!Double.isFinite(liquidPhaseEnergy) || !Double.isFinite(vaporPhaseEnergy)) {
                throw new IllegalArgumentException("V3 local MESH phase energies must be finite");
            }
        }

        @Override public double[] equilibriumResiduals() { return equilibriumResiduals.clone(); }

        double equilibriumResidual(int component) { return equilibriumResiduals[component]; }
    }
}
