package com.wormzjl.createcheme.science.column.v3;

import java.util.List;
import java.util.Objects;

/** Stable ledger-ordered map between physical dry-MESH states and scaled log-flow Newton coordinates. */
final class V3DryMeshCoordinateMap {
    private final V3ColumnProblem problem;
    private final List<V3DegreeOfFreedomLedger.Unknown> unknowns;
    private final double[] componentFlowScales;
    private final double condenserTemperatureKelvin;

    V3DryMeshCoordinateMap(V3ColumnProblem problem) {
        this.problem = Objects.requireNonNull(problem, "problem");
        this.unknowns = problem.degreeOfFreedomLedger().unknowns();
        int componentCount = problem.activeComponentBasis().componentCount();
        this.componentFlowScales = new double[componentCount];
        for (int component = 0; component < componentCount; component++) {
            componentFlowScales[component] = problem.activeComponentBasis().flowScale(component);
        }
        this.condenserTemperatureKelvin = specification(V3ColumnSpecification.CondenserOutletTemperature.class).kelvin();
    }

    int coordinateCount() {
        return unknowns.size();
    }

    List<V3DegreeOfFreedomLedger.Unknown> unknowns() {
        return unknowns;
    }

    double[] encode(V3DryMeshState state) {
        state = Objects.requireNonNull(state, "state");
        if (state.nodeCount() != problem.topology().nodeCount()
                || state.componentCount() != componentFlowScales.length) {
            throw new IllegalArgumentException("V3 MESH state does not match its coordinate map");
        }
        double[] coordinates = new double[coordinateCount()];
        for (int index = 0; index < coordinates.length; index++) {
            V3DegreeOfFreedomLedger.UnknownId id = unknowns.get(index).id();
            coordinates[index] = switch (id.family()) {
                case LIQUID_COMPONENT_FLOW -> logFlow(state.liquidFlow(id.node(), id.component()), id.component());
                case VAPOR_COMPONENT_FLOW -> logFlow(state.vaporFlow(id.node(), id.component()), id.component());
                case TEMPERATURE -> state.temperatureKelvin(id.node());
            };
        }
        return coordinates;
    }

    V3DryMeshState decode(double[] coordinates) {
        coordinates = Objects.requireNonNull(coordinates, "coordinates");
        if (coordinates.length != coordinateCount()) throw new IllegalArgumentException("V3 MESH coordinate length is invalid");
        int nodes = problem.topology().nodeCount();
        int components = componentFlowScales.length;
        double[][] liquid = new double[nodes][components];
        double[][] vapor = new double[nodes][components];
        double[] temperatures = new double[nodes];
        temperatures[problem.topology().condenserNode()] = condenserTemperatureKelvin;
        for (int index = 0; index < coordinates.length; index++) {
            double coordinate = coordinates[index];
            if (!Double.isFinite(coordinate)) throw new IllegalArgumentException("V3 MESH coordinates must be finite");
            V3DegreeOfFreedomLedger.UnknownId id = unknowns.get(index).id();
            switch (id.family()) {
                case LIQUID_COMPONENT_FLOW -> liquid[id.node()][id.component()] = flow(coordinate, id.component());
                case VAPOR_COMPONENT_FLOW -> vapor[id.node()][id.component()] = flow(coordinate, id.component());
                case TEMPERATURE -> temperatures[id.node()] = coordinate;
            }
        }
        return new V3DryMeshState(problem.topology(), components, liquid, vapor, temperatures);
    }

    private double logFlow(double flow, int component) {
        if (!Double.isFinite(flow) || flow <= 0.0) {
            throw new IllegalArgumentException("V3 MESH active flow must be positive before entering log coordinates");
        }
        return Math.log(flow / componentFlowScales[component]);
    }

    private double flow(double coordinate, int component) {
        double flow = componentFlowScales[component] * Math.exp(coordinate);
        if (!Double.isFinite(flow) || flow <= 0.0) {
            throw new IllegalArgumentException("V3 MESH log coordinate decodes outside the finite positive flow domain");
        }
        return flow;
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
