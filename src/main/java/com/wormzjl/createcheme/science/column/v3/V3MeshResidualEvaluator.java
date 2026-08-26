package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3FugacityResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3Phase;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoModel;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoWorkspace;
import java.util.ArrayList;
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
                case VAPOR_LIQUID_EQUILIBRIUM -> equilibriumResidual(id.node(), id.component(), properties[id.node()]);
                case ENERGY_BALANCE -> energyResidual(state, id.node(), properties);
            };
            rows.add(new V3MeshResidual.Row(id, physicalValue, scale(id)));
        }
        return new V3MeshResidual(rows);
    }

    private double materialResidual(V3DryMeshState state, int node, int component) {
        V3ColumnTopology topology = problem.topology();
        if (node == topology.condenserNode()) {
            double externalVapor = topology.hasVaporPhase(0) ? state.vaporFlow(0, component) : 0.0;
            double condensedLiquid = topology.hasLiquidPhase(0) ? state.liquidFlow(0, component) : 0.0;
            return state.vaporFlow(1, component) - externalVapor - condensedLiquid;
        }
        if (node <= topology.trayCount()) {
            double liquidIn = node == 1
                    ? (topology.hasLiquidPhase(0) ? organicRefluxFraction() * state.liquidFlow(0, component) : 0.0)
                    : state.liquidFlow(node - 1, component);
            double feed = node == topology.feedTrayNumber() ? activeComponentBasis.feedFlowMolPerSecond(component) : 0.0;
            return liquidIn + state.vaporFlow(node + 1, component) + feed - state.liquidFlow(node, component)
                    - state.vaporFlow(node, component);
        }
        return state.liquidFlow(node - 1, component) - state.liquidFlow(node, component) - state.vaporFlow(node, component);
    }

    private double equilibriumResidual(int node, int component, NodeProperties properties) {
        int publicComponent = activeComponentBasis.publicIndex(component);
        return Math.log(properties.vaporComposition()[publicComponent])
                + properties.vaporResult().logFugacityCoefficient(publicComponent)
                - Math.log(properties.liquidComposition()[publicComponent])
                - properties.liquidResult().logFugacityCoefficient(publicComponent);
    }

    private double energyResidual(V3DryMeshState state, int node, NodeProperties[] properties) {
        V3ColumnTopology topology = problem.topology();
        if (node <= topology.trayCount()) {
            double liquidIn = node == 1
                    ? (topology.hasLiquidPhase(0) ? organicRefluxFraction() * phaseEnergy(state, 0, true, properties) : 0.0)
                    : phaseEnergy(state, node - 1, true, properties);
            double vaporIn = phaseEnergy(state, node + 1, false, properties);
            double feed = node == topology.feedTrayNumber() ? totalFeedFlow * feedMolarEnthalpyJoulesPerMol : 0.0;
            return liquidIn + vaporIn + feed - phaseEnergy(state, node, true, properties)
                    - phaseEnergy(state, node, false, properties);
        }
        return phaseEnergy(state, node - 1, true, properties) + reboilerDutyWatts
                - phaseEnergy(state, node, true, properties) - phaseEnergy(state, node, false, properties);
    }

    private double phaseEnergy(V3DryMeshState state, int node, boolean liquid, NodeProperties[] properties) {
        if (liquid && !problem.topology().hasLiquidPhase(node)) return 0.0;
        if (!liquid && !problem.topology().hasVaporPhase(node)) return 0.0;
        double totalFlow = phaseTotal(state, node, liquid);
        if (totalFlow == 0.0) return 0.0;
        return totalFlow * (liquid ? properties[node].liquidResult().molarEnthalpyJoulesPerMol()
                : properties[node].vaporResult().molarEnthalpyJoulesPerMol());
    }

    /** Builds immutable per-node property snapshots once per residual evaluation. */
    private NodeProperties[] nodeProperties(V3DryMeshState state, V3ThermoWorkspace workspace) {
        NodeProperties[] properties = new NodeProperties[state.nodeCount()];
        for (int node = 0; node < properties.length; node++) {
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
            properties[node] = new NodeProperties(liquidComposition, vaporComposition, liquidResult, vaporResult);
        }
        return properties;
    }

    private double[] normalizedPublicPhaseComposition(V3DryMeshState state, int node, boolean liquid) {
        double total = phaseTotal(state, node, liquid);
        if (!Double.isFinite(total) || total <= 0.0) {
            throw new IllegalArgumentException("V3 MESH equilibrium phase has no positive hydrocarbon flow");
        }
        double[] composition = new double[problem.input().componentBasis().componentCount()];
        for (int component = 0; component < state.componentCount(); component++) {
            double flow = liquid ? state.liquidFlow(node, component) : state.vaporFlow(node, component);
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
            V3FugacityResult vaporResult) {}
}
