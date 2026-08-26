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
        this.totalFeedFlow = sum(problem.input().feedComponentMolarFlowsMolPerSecond());
    }

    V3MeshResidual evaluate(V3DryMeshState state, V3ThermoWorkspace workspace) {
        state = Objects.requireNonNull(state, "state");
        workspace = Objects.requireNonNull(workspace, "workspace");
        if (state.nodeCount() != problem.topology().nodeCount()
                || state.componentCount() != problem.input().componentBasis().componentCount()) {
            throw new IllegalArgumentException("V3 MESH state does not match the resolved problem");
        }
        List<V3MeshResidual.Row> rows = new ArrayList<>(problem.degreeOfFreedomLedger().equationCount());
        for (V3DegreeOfFreedomLedger.Equation equation : problem.degreeOfFreedomLedger().equations()) {
            V3DegreeOfFreedomLedger.EquationId id = equation.id();
            double physicalValue = switch (id.family()) {
                case COMPONENT_MATERIAL_BALANCE -> materialResidual(state, id.node(), id.component());
                case VAPOR_LIQUID_EQUILIBRIUM -> equilibriumResidual(state, id.node(), id.component(), workspace);
                case ENERGY_BALANCE -> energyResidual(state, id.node(), workspace);
            };
            rows.add(new V3MeshResidual.Row(id, physicalValue, scale(id)));
        }
        return new V3MeshResidual(rows);
    }

    private double materialResidual(V3DryMeshState state, int node, int component) {
        V3ColumnTopology topology = problem.topology();
        if (node == topology.condenserNode()) {
            return state.vaporFlow(1, component) - state.vaporFlow(0, component)
                    - (topology.hasLiquidPhase(0) ? state.liquidFlow(0, component) : 0.0);
        }
        if (node <= topology.trayCount()) {
            double liquidIn = node == 1
                    ? (topology.hasLiquidPhase(0) ? organicRefluxFraction() * state.liquidFlow(0, component) : 0.0)
                    : state.liquidFlow(node - 1, component);
            double feed = node == topology.feedTrayNumber()
                    ? problem.input().feedComponentMolarFlowsMolPerSecond()[component] : 0.0;
            return liquidIn + state.vaporFlow(node + 1, component) + feed - state.liquidFlow(node, component)
                    - state.vaporFlow(node, component);
        }
        return state.liquidFlow(node - 1, component) - state.liquidFlow(node, component) - state.vaporFlow(node, component);
    }

    private double equilibriumResidual(V3DryMeshState state, int node, int component, V3ThermoWorkspace workspace) {
        double[] liquid = normalizedPhaseComposition(state, node, true);
        double[] vapor = normalizedPhaseComposition(state, node, false);
        double temperature = state.temperatureKelvin(node);
        double pressure = problem.nodePressurePascal(node);
        V3FugacityResult liquidResult = thermo.fugacity(temperature, pressure, liquid, V3Phase.LIQUID, workspace);
        V3FugacityResult vaporResult = thermo.fugacity(temperature, pressure, vapor, V3Phase.VAPOR, workspace);
        return Math.log(vapor[component]) + vaporResult.logFugacityCoefficient(component)
                - Math.log(liquid[component]) - liquidResult.logFugacityCoefficient(component);
    }

    private double energyResidual(V3DryMeshState state, int node, V3ThermoWorkspace workspace) {
        V3ColumnTopology topology = problem.topology();
        if (node <= topology.trayCount()) {
            double liquidIn = node == 1
                    ? (topology.hasLiquidPhase(0) ? organicRefluxFraction() * phaseEnergy(state, 0, true, workspace) : 0.0)
                    : phaseEnergy(state, node - 1, true, workspace);
            double vaporIn = phaseEnergy(state, node + 1, false, workspace);
            double feed = node == topology.feedTrayNumber() ? totalFeedFlow * feedMolarEnthalpyJoulesPerMol : 0.0;
            return liquidIn + vaporIn + feed - phaseEnergy(state, node, true, workspace)
                    - phaseEnergy(state, node, false, workspace);
        }
        return phaseEnergy(state, node - 1, true, workspace) + reboilerDutyWatts
                - phaseEnergy(state, node, true, workspace) - phaseEnergy(state, node, false, workspace);
    }

    private double phaseEnergy(V3DryMeshState state, int node, boolean liquid, V3ThermoWorkspace workspace) {
        double totalFlow = phaseTotal(state, node, liquid);
        if (totalFlow == 0.0) return 0.0;
        return totalFlow * thermo.molarEnthalpy(state.temperatureKelvin(node), problem.nodePressurePascal(node),
                normalizedPhaseComposition(state, node, liquid), liquid ? V3Phase.LIQUID : V3Phase.VAPOR, workspace);
    }

    private double[] normalizedPhaseComposition(V3DryMeshState state, int node, boolean liquid) {
        double total = phaseTotal(state, node, liquid);
        if (!Double.isFinite(total) || total <= 0.0) {
            throw new IllegalArgumentException("V3 MESH equilibrium phase has no positive hydrocarbon flow");
        }
        double[] composition = new double[state.componentCount()];
        for (int component = 0; component < composition.length; component++) {
            double flow = liquid ? state.liquidFlow(node, component) : state.vaporFlow(node, component);
            if (flow <= 0.0) throw new IllegalArgumentException("V3 MESH active component flow must be positive for logarithmic VLE");
            composition[component] = flow / total;
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
                    Math.abs(problem.input().feedComponentMolarFlowsMolPerSecond()[equation.component()]), totalFeedFlow * 1.0e-12);
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

    private static double sum(double[] values) {
        double total = 0.0;
        for (double value : values) total += value;
        return total;
    }
}
