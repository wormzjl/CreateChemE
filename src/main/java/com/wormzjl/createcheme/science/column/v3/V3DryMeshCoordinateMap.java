package com.wormzjl.createcheme.science.column.v3;

import java.util.List;
import java.util.Objects;

/** Stable ledger-ordered map between physical dry-MESH states and scaled log-flow Newton coordinates. */
final class V3DryMeshCoordinateMap {
    private final V3ColumnProblem problem;
    private final List<V3DegreeOfFreedomLedger.Unknown> unknowns;
    private final double[] componentFlowScales;
    private final double freeWaterFlowScale;
    private final double[] parametricFreeWaterFlows;
    private final double condenserTemperatureKelvin;

    V3DryMeshCoordinateMap(V3ColumnProblem problem) {
        this.problem = Objects.requireNonNull(problem, "problem");
        this.unknowns = problem.degreeOfFreedomLedger().unknowns();
        int componentCount = problem.activeComponentBasis().componentCount();
        this.componentFlowScales = new double[componentCount];
        for (int component = 0; component < componentCount; component++) {
            componentFlowScales[component] = problem.activeComponentBasis().flowScale(component);
        }
        this.freeWaterFlowScale = problem.freeWaterFlowScaleMolPerSecond();
        // A parametric wet set has no free-water coordinate, so its frozen flows have to be restored by the
        // decode: they are part of the problem, not of the Newton vector.
        this.parametricFreeWaterFlows = problem.wetTraySet().parametricFreeWaterFlows();
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
                // A wet tray sheds a strictly positive free-water flow by definition, so it takes the same
                // log-flow coordinate a component flow does, scaled by the total steam fed to the column.
                case FREE_WATER_FLOW -> logFreeWater(state.freeWaterFlow(id.node()));
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
        double[] freeWater = parametricFreeWaterFlows.clone();
        temperatures[problem.topology().condenserNode()] = condenserTemperatureKelvin;
        for (int index = 0; index < coordinates.length; index++) {
            double coordinate = coordinates[index];
            if (!Double.isFinite(coordinate)) throw new IllegalArgumentException("V3 MESH coordinates must be finite");
            V3DegreeOfFreedomLedger.UnknownId id = unknowns.get(index).id();
            switch (id.family()) {
                case LIQUID_COMPONENT_FLOW -> liquid[id.node()][id.component()] = flow(coordinate, id.component());
                case VAPOR_COMPONENT_FLOW -> vapor[id.node()][id.component()] = flow(coordinate, id.component());
                case TEMPERATURE -> temperatures[id.node()] = coordinate;
                case FREE_WATER_FLOW -> freeWater[id.node()] = freeWaterFlow(coordinate);
            }
        }
        return new V3DryMeshState(problem.topology(), components, liquid, vapor, temperatures, freeWater);
    }

    /**
     * What {@link #decode} returns for {@code baseCoordinates} with one entry moved by {@code signedStep},
     * decoded from an already decoded base instead of from the whole vector again.
     *
     * <p>A finite-difference probe changes exactly one coordinate, and {@link #decode} is a coordinate-wise
     * map: every other entry goes through the very same {@code scale * exp(z)} of the very same {@code z} and
     * so lands on the very same bits it already has in {@code decodedBase}. Only the moved entry has to be
     * decoded, and — because a base that decodes has already cleared every check the untouched entries could
     * fail — only the checks that entry can fail have to be repeated, which is why this throws where
     * {@link #decode} would and a probe still reads the failure as an inadmissible perturbation.</p>
     *
     * <p>{@code decodedBase} must be {@code decode(baseCoordinates)}; a caller whose base vector does not
     * decode has no such state and has to keep using {@link #decode}.</p>
     */
    V3DryMeshState decodePerturbed(
            V3DryMeshState decodedBase, double[] baseCoordinates, int column, double signedStep) {
        decodedBase = Objects.requireNonNull(decodedBase, "decodedBase");
        baseCoordinates = Objects.requireNonNull(baseCoordinates, "baseCoordinates");
        if (baseCoordinates.length != coordinateCount()) throw new IllegalArgumentException("V3 MESH coordinate length is invalid");
        double coordinate = baseCoordinates[column] + signedStep;
        if (!Double.isFinite(coordinate)) throw new IllegalArgumentException("V3 MESH coordinates must be finite");
        V3DegreeOfFreedomLedger.UnknownId id = unknowns.get(column).id();
        return switch (id.family()) {
            case LIQUID_COMPONENT_FLOW ->
                    decodedBase.withLiquidFlow(id.node(), id.component(), flow(coordinate, id.component()));
            case VAPOR_COMPONENT_FLOW ->
                    decodedBase.withVaporFlow(id.node(), id.component(), flow(coordinate, id.component()));
            case TEMPERATURE -> decodedBase.withTemperatureKelvin(id.node(), coordinate);
            case FREE_WATER_FLOW -> decodedBase.withFreeWaterFlow(id.node(), freeWaterFlow(coordinate));
        };
    }

    private double logFlow(double flow, int component) {
        if (!Double.isFinite(flow) || flow <= 0.0) {
            throw new IllegalArgumentException("V3 MESH active flow must be positive before entering log coordinates");
        }
        return Math.log(flow / componentFlowScales[component]);
    }

    private double logFreeWater(double flow) {
        if (!Double.isFinite(flow) || flow <= 0.0) {
            throw new IllegalArgumentException("V3 MESH free water must be positive on a wet tray before entering log coordinates");
        }
        return Math.log(flow / freeWaterFlowScale);
    }

    private double freeWaterFlow(double coordinate) {
        double flow = freeWaterFlowScale * Math.exp(coordinate);
        if (!Double.isFinite(flow) || flow <= 0.0) {
            throw new IllegalArgumentException("V3 MESH free-water log coordinate decodes outside the finite positive flow domain");
        }
        return flow;
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
