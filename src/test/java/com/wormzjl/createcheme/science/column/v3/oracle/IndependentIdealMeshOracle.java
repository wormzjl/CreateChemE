package com.wormzjl.createcheme.science.column.v3.oracle;

import java.util.Arrays;
import java.util.Objects;

/**
 * Test-only complete dry MESH oracle for small manufactured columns.
 *
 * <p>This class deliberately has no dependency on production V3 topology, residual, audit,
 * thermodynamic, or linear-algebra code.  It uses its own log-flow coordinate layout, central
 * finite-difference Jacobian, dense partial-pivot solve, and Armijo globalization.</p>
 */
final class IndependentIdealMeshOracle {
    private static final double MINIMUM_FLOW = 1.0e-12;
    private static final double MINIMUM_TEMPERATURE_KELVIN = 250.0;
    private static final double MAXIMUM_TEMPERATURE_KELVIN = 1_000.0;

    private final Problem problem;
    private final double[] sideDrawRates;

    IndependentIdealMeshOracle(Problem problem) {
        this(problem, new double[problem.nodeCount()]);
    }

    IndependentIdealMeshOracle(Problem problem, double[] sideDrawRates) {
        this.problem = Objects.requireNonNull(problem, "problem");
        this.sideDrawRates = sideDrawRates.clone();
        if (sideDrawRates.length != problem.nodeCount() || sideDrawRates[0] != 0.0
                || sideDrawRates[problem.nodeCount() - 1] != 0.0
                || Arrays.stream(sideDrawRates).anyMatch(rate -> rate < 0 || !Double.isFinite(rate))) {
            throw new IllegalArgumentException("Invalid independent side draw rates");
        }
    }

    int coordinateCount() {
        return 2 * problem.componentCount() * problem.nodeCount() + problem.nodeCount() - 1;
    }

    double[] coordinatesOf(State state) {
        state = Objects.requireNonNull(state, "state");
        validateStateShape(state);
        double[] coordinates = new double[coordinateCount()];
        int index = 0;
        for (int node = 0; node < problem.nodeCount(); node++) {
            for (int component = 0; component < problem.componentCount(); component++) {
                coordinates[index++] = Math.log(requirePositive(state.liquidComponentFlows()[node][component], "liquid flow"));
            }
        }
        for (int node = 0; node < problem.nodeCount(); node++) {
            for (int component = 0; component < problem.componentCount(); component++) {
                coordinates[index++] = Math.log(requirePositive(state.vaporComponentFlows()[node][component], "vapor flow"));
            }
        }
        for (int node = 1; node < problem.nodeCount(); node++) coordinates[index++] = state.temperaturesKelvin()[node];
        return coordinates;
    }

    State stateOf(double[] coordinates) {
        coordinates = validatedCoordinates(coordinates);
        int index = 0;
        int nodes = problem.nodeCount();
        int components = problem.componentCount();
        double[][] liquid = new double[nodes][components];
        double[][] vapor = new double[nodes][components];
        for (int node = 0; node < nodes; node++) {
            for (int component = 0; component < components; component++) liquid[node][component] = Math.exp(coordinates[index++]);
        }
        for (int node = 0; node < nodes; node++) {
            for (int component = 0; component < components; component++) vapor[node][component] = Math.exp(coordinates[index++]);
        }
        double[] temperatures = new double[nodes];
        temperatures[0] = problem.condenserTemperatureKelvin();
        for (int node = 1; node < nodes; node++) temperatures[node] = coordinates[index++];
        return new State(liquid, vapor, temperatures);
    }

    Evaluation evaluate(double[] coordinates) {
        State state = stateOf(coordinates);
        int components = problem.componentCount();
        int nodes = problem.nodeCount();
        double[] raw = new double[coordinateCount()];
        double[] scaled = new double[coordinateCount()];
        RowFamily[] families = new RowFamily[coordinateCount()];
        int index = 0;
        double materialScale = problem.totalFeedFlowMolPerSecond();
        for (int node = 0; node < nodes; node++) {
            for (int component = 0; component < components; component++) {
                raw[index] = materialResidual(state, node, component);
                scaled[index] = raw[index] / materialScale;
                families[index++] = RowFamily.COMPONENT_MATERIAL_BALANCE;
            }
        }
        for (int node = 0; node < nodes; node++) {
            double liquidTotal = sum(state.liquidComponentFlows()[node]);
            double vaporTotal = sum(state.vaporComponentFlows()[node]);
            for (int component = 0; component < components; component++) {
                raw[index] = Math.log(state.vaporComponentFlows()[node][component] / vaporTotal)
                        - Math.log(problem.equilibriumRatio(node, component, state.temperaturesKelvin()[node])
                        * state.liquidComponentFlows()[node][component] / liquidTotal);
                scaled[index] = raw[index];
                families[index++] = RowFamily.VAPOR_LIQUID_EQUILIBRIUM;
            }
        }
        double energyScale = problem.energyScaleWatts();
        for (int node = 1; node < nodes; node++) {
            raw[index] = energyResidual(state, node);
            scaled[index] = raw[index] / energyScale;
            families[index++] = RowFamily.ENERGY_BALANCE;
        }
        return new Evaluation(raw, scaled, families);
    }

    double[][] finiteDifferenceJacobian(double[] coordinates) {
        coordinates = validatedCoordinates(coordinates);
        double[] preservedInput = coordinates.clone();
        int dimension = coordinates.length;
        double[][] jacobian = new double[dimension][dimension];
        for (int column = 0; column < dimension; column++) {
            double step = column < 2 * problem.componentCount() * problem.nodeCount()
                    ? 1.0e-6 : Math.max(1.0e-4, Math.abs(coordinates[column]) * 1.0e-6);
            double[] higher = coordinates.clone();
            double[] lower = coordinates.clone();
            higher[column] += step;
            lower[column] -= step;
            double[] highResidual = evaluate(higher).scaledResiduals();
            double[] lowResidual = evaluate(lower).scaledResiduals();
            for (int row = 0; row < dimension; row++) jacobian[row][column] = (highResidual[row] - lowResidual[row]) / (2.0 * step);
        }
        if (!Arrays.equals(preservedInput, coordinates)) throw new IllegalStateException("Finite-difference Jacobian mutated coordinates");
        return jacobian;
    }

    SolveResult solve(double[] initialCoordinates, int maximumIterations, double scaledTolerance) {
        if (maximumIterations < 1 || !Double.isFinite(scaledTolerance) || scaledTolerance <= 0.0) {
            throw new IllegalArgumentException("Oracle solve limits are invalid");
        }
        double[] coordinates = validatedCoordinates(initialCoordinates).clone();
        for (int iteration = 0; iteration <= maximumIterations; iteration++) {
            Evaluation evaluation = evaluate(coordinates);
            if (evaluation.maximumAbsoluteScaledResidual() <= scaledTolerance) {
                return new SolveResult(coordinates, stateOf(coordinates), evaluation, iteration);
            }
            if (iteration == maximumIterations) break;
            double[] correction = densePivotedSolve(finiteDifferenceJacobian(coordinates), negate(evaluation.scaledResiduals()));
            double merit = evaluation.scaledSquaredNorm();
            boolean accepted = false;
            double fraction = 1.0;
            for (int lineSearchStep = 0; lineSearchStep < 24; lineSearchStep++) {
                double[] candidate = addScaled(coordinates, correction, fraction);
                if (admissible(candidate)) {
                    Evaluation candidateEvaluation = evaluate(candidate);
                    if (candidateEvaluation.scaledSquaredNorm() <= merit * (1.0 - 1.0e-4 * fraction)) {
                        coordinates = candidate;
                        accepted = true;
                        break;
                    }
                }
                fraction *= 0.5;
            }
            if (!accepted) throw new IllegalStateException("Independent oracle line search exhausted");
        }
        throw new IllegalStateException("Independent oracle reached its iteration limit");
    }

    static Problem manufacturedFourTrayProblem() {
        return new Problem(2, 4, 2, 1.0, 400.0, new double[] {30.0, 60.0}, 400.0,
                new double[][] {
                        {1.6, 0.4},
                        {1.2, 0.8},
                        {12.0 / 7.0, 8.0 / 13.0},
                        {12.0 / 7.0, 8.0 / 13.0},
                        {12.0 / 7.0, 8.0 / 13.0},
                        {42.0 / 17.0, 28.0 / 53.0}
                }, new double[] {0.002, -0.001}, new double[] {30.0, 40.0}, 0.0);
    }

    static State manufacturedFourTrayState() {
        return new State(new double[][] {
                {10.0, 10.0}, {5.0, 5.0}, {35.0, 65.0}, {35.0, 65.0}, {35.0, 65.0}, {17.0, 53.0}
        }, new double[][] {
                {8.0, 2.0}, {18.0, 12.0}, {18.0, 12.0}, {18.0, 12.0}, {18.0, 12.0}, {18.0, 12.0}
        }, new double[] {400.0, 400.0, 400.0, 400.0, 400.0, 400.0});
    }

    static double[] deliberatelyPerturbedCoordinates(IndependentIdealMeshOracle oracle) {
        double[] coordinates = oracle.coordinatesOf(manufacturedFourTrayState());
        int flowCoordinates = 2 * oracle.problem.componentCount() * oracle.problem.nodeCount();
        for (int index = 0; index < flowCoordinates; index++) coordinates[index] += ((index % 5) - 2) * 0.025;
        for (int index = flowCoordinates; index < coordinates.length; index++) coordinates[index] += (index % 2 == 0 ? 1.5 : -1.5);
        return coordinates;
    }

    private double materialResidual(State state, int node, int component) {
        if (node == 0) {
            return state.vaporComponentFlows()[1][component] - state.liquidComponentFlows()[0][component]
                    - state.vaporComponentFlows()[0][component];
        }
        if (node <= problem.trayCount()) {
            double liquidIn = node == 1 ? problem.refluxFraction() * state.liquidComponentFlows()[0][component]
                    : liquidToBelow(state, node - 1, component);
            double feed = node == problem.feedTrayNumber() ? problem.feedComponentFlowsMolPerSecond()[component] : 0.0;
            return liquidIn + state.vaporComponentFlows()[node + 1][component] + feed
                    - state.liquidComponentFlows()[node][component] - state.vaporComponentFlows()[node][component];
        }
        return liquidToBelow(state, node - 1, component) - state.liquidComponentFlows()[node][component]
                - state.vaporComponentFlows()[node][component];
    }

    private double energyResidual(State state, int node) {
        if (node <= problem.trayCount()) {
            double liquidIn = node == 1
                    ? liquidEnergy(state.liquidComponentFlows()[0], problem.condenserTemperatureKelvin()) * problem.refluxFraction()
                    : liquidEnergyToBelow(state, node - 1);
            double vaporIn = vaporEnergy(state.vaporComponentFlows()[node + 1], state.temperaturesKelvin()[node + 1]);
            double feed = node == problem.feedTrayNumber()
                    ? liquidEnergy(problem.feedComponentFlowsMolPerSecond(), problem.feedTemperatureKelvin()) : 0.0;
            return liquidIn + vaporIn + feed - liquidEnergy(state.liquidComponentFlows()[node], state.temperaturesKelvin()[node])
                    - vaporEnergy(state.vaporComponentFlows()[node], state.temperaturesKelvin()[node]);
        }
        return liquidEnergyToBelow(state, node - 1)
                + problem.reboilerDutyWatts() - liquidEnergy(state.liquidComponentFlows()[node], state.temperaturesKelvin()[node])
                - vaporEnergy(state.vaporComponentFlows()[node], state.temperaturesKelvin()[node]);
    }

    private double liquidToBelow(State state, int tray, int component) {
        double total = sum(state.liquidComponentFlows()[tray]);
        return state.liquidComponentFlows()[tray][component]
                - sideDrawRates[tray] * state.liquidComponentFlows()[tray][component] / total;
    }

    private double liquidEnergyToBelow(State state, int tray) {
        double energy = 0;
        for (int component = 0; component < problem.componentCount(); component++) {
            energy += liquidToBelow(state, tray, component) * problem.componentHeatCapacitiesJPerMolKelvin()[component]
                    * state.temperaturesKelvin()[tray];
        }
        return energy;
    }

    private double liquidEnergy(double[] componentFlows, double temperatureKelvin) {
        return componentEnergy(componentFlows, temperatureKelvin);
    }

    private double vaporEnergy(double[] componentFlows, double temperatureKelvin) {
        return componentEnergy(componentFlows, temperatureKelvin);
    }

    private double componentEnergy(double[] componentFlows, double temperatureKelvin) {
        double energy = 0.0;
        for (int component = 0; component < problem.componentCount(); component++) {
            energy += componentFlows[component] * problem.componentHeatCapacitiesJPerMolKelvin()[component] * temperatureKelvin;
        }
        return energy;
    }

    private boolean admissible(double[] coordinates) {
        try {
            State state = stateOf(coordinates);
            for (double[] flows : state.liquidComponentFlows()) for (double flow : flows) if (flow < MINIMUM_FLOW || !Double.isFinite(flow)) return false;
            for (double[] flows : state.vaporComponentFlows()) for (double flow : flows) if (flow < MINIMUM_FLOW || !Double.isFinite(flow)) return false;
            for (double temperature : state.temperaturesKelvin()) {
                if (!Double.isFinite(temperature) || temperature < MINIMUM_TEMPERATURE_KELVIN
                        || temperature > MAXIMUM_TEMPERATURE_KELVIN) return false;
            }
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private double[] validatedCoordinates(double[] coordinates) {
        coordinates = Objects.requireNonNull(coordinates, "coordinates");
        if (coordinates.length != coordinateCount()) throw new IllegalArgumentException("Independent oracle coordinate length is invalid");
        for (double coordinate : coordinates) if (!Double.isFinite(coordinate)) throw new IllegalArgumentException("Independent oracle coordinates must be finite");
        return coordinates;
    }

    private void validateStateShape(State state) {
        if (state.liquidComponentFlows().length != problem.nodeCount() || state.vaporComponentFlows().length != problem.nodeCount()
                || state.temperaturesKelvin().length != problem.nodeCount()) {
            throw new IllegalArgumentException("Independent oracle state node count is invalid");
        }
        for (double[] flow : state.liquidComponentFlows()) if (flow.length != problem.componentCount()) throw new IllegalArgumentException("Independent oracle liquid axis is invalid");
        for (double[] flow : state.vaporComponentFlows()) if (flow.length != problem.componentCount()) throw new IllegalArgumentException("Independent oracle vapor axis is invalid");
    }

    private static double requirePositive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0.0) throw new IllegalArgumentException(name + " must be finite and positive");
        return value;
    }

    private static double sum(double[] values) {
        double total = 0.0;
        for (double value : values) total += value;
        return total;
    }

    private static double[] negate(double[] values) {
        double[] negated = values.clone();
        for (int index = 0; index < negated.length; index++) negated[index] = -negated[index];
        return negated;
    }

    private static double[] addScaled(double[] base, double[] correction, double fraction) {
        double[] result = base.clone();
        for (int index = 0; index < result.length; index++) result[index] += fraction * correction[index];
        return result;
    }

    private static double[] densePivotedSolve(double[][] matrix, double[] rightHandSide) {
        int size = rightHandSide.length;
        if (matrix.length != size) throw new IllegalArgumentException("Independent dense matrix is not square");
        double[][] working = new double[size][size];
        double[] right = rightHandSide.clone();
        for (int row = 0; row < size; row++) {
            if (matrix[row].length != size) throw new IllegalArgumentException("Independent dense matrix is not square");
            working[row] = matrix[row].clone();
        }
        for (int pivot = 0; pivot < size; pivot++) {
            int pivotRow = pivot;
            for (int row = pivot + 1; row < size; row++) {
                if (Math.abs(working[row][pivot]) > Math.abs(working[pivotRow][pivot])) pivotRow = row;
            }
            if (!Double.isFinite(working[pivotRow][pivot]) || Math.abs(working[pivotRow][pivot]) <= 1.0e-14) {
                throw new IllegalStateException("Independent dense Jacobian is singular at pivot " + pivot
                        + " with value " + working[pivotRow][pivot]);
            }
            if (pivotRow != pivot) {
                double[] temporaryRow = working[pivot]; working[pivot] = working[pivotRow]; working[pivotRow] = temporaryRow;
                double temporaryRight = right[pivot]; right[pivot] = right[pivotRow]; right[pivotRow] = temporaryRight;
            }
            for (int row = pivot + 1; row < size; row++) {
                double multiplier = working[row][pivot] / working[pivot][pivot];
                working[row][pivot] = 0.0;
                for (int column = pivot + 1; column < size; column++) working[row][column] -= multiplier * working[pivot][column];
                right[row] -= multiplier * right[pivot];
            }
        }
        double[] solution = new double[size];
        for (int row = size - 1; row >= 0; row--) {
            double sum = right[row];
            for (int column = row + 1; column < size; column++) sum -= working[row][column] * solution[column];
            solution[row] = sum / working[row][row];
        }
        return solution;
    }

    record Problem(
            int componentCount, int trayCount, int feedTrayNumber, double organicRefluxRatio,
            double condenserTemperatureKelvin, double[] feedComponentFlowsMolPerSecond, double feedTemperatureKelvin,
            double[][] equilibriumRatios, double[] equilibriumTemperatureSlopesPerKelvin,
            double[] componentHeatCapacitiesJPerMolKelvin, double reboilerDutyWatts) {
        Problem {
            if (componentCount < 2 || componentCount > 4 || trayCount < 4 || trayCount > 8
                    || feedTrayNumber < 1 || feedTrayNumber > trayCount || !Double.isFinite(organicRefluxRatio)
                    || organicRefluxRatio < 0.0 || !Double.isFinite(condenserTemperatureKelvin)
                    || condenserTemperatureKelvin <= 0.0 || !Double.isFinite(feedTemperatureKelvin)
                    || feedTemperatureKelvin <= 0.0 || !Double.isFinite(reboilerDutyWatts)) {
                throw new IllegalArgumentException("Independent oracle problem is invalid");
            }
            feedComponentFlowsMolPerSecond = feedComponentFlowsMolPerSecond.clone();
            equilibriumTemperatureSlopesPerKelvin = equilibriumTemperatureSlopesPerKelvin.clone();
            componentHeatCapacitiesJPerMolKelvin = componentHeatCapacitiesJPerMolKelvin.clone();
            equilibriumRatios = copy(equilibriumRatios);
            if (feedComponentFlowsMolPerSecond.length != componentCount
                    || equilibriumTemperatureSlopesPerKelvin.length != componentCount
                    || componentHeatCapacitiesJPerMolKelvin.length != componentCount
                    || equilibriumRatios.length != trayCount + 2) {
                throw new IllegalArgumentException("Independent oracle problem axis is invalid");
            }
            for (double flow : feedComponentFlowsMolPerSecond) requirePositive(flow, "feed flow");
            for (double slope : equilibriumTemperatureSlopesPerKelvin) {
                if (!Double.isFinite(slope)) throw new IllegalArgumentException("Equilibrium temperature slope must be finite");
            }
            for (double heatCapacity : componentHeatCapacitiesJPerMolKelvin) requirePositive(heatCapacity, "heat capacity");
            for (double[] ratios : equilibriumRatios) {
                if (ratios.length != componentCount) throw new IllegalArgumentException("Independent K axis is invalid");
                for (double ratio : ratios) requirePositive(ratio, "equilibrium ratio");
            }
        }

        @Override public double[] feedComponentFlowsMolPerSecond() { return feedComponentFlowsMolPerSecond.clone(); }
        @Override public double[][] equilibriumRatios() { return copy(equilibriumRatios); }
        @Override public double[] equilibriumTemperatureSlopesPerKelvin() { return equilibriumTemperatureSlopesPerKelvin.clone(); }
        @Override public double[] componentHeatCapacitiesJPerMolKelvin() { return componentHeatCapacitiesJPerMolKelvin.clone(); }

        int nodeCount() { return trayCount + 2; }
        double refluxFraction() { return organicRefluxRatio / (1.0 + organicRefluxRatio); }
        double equilibriumRatio(int node, int component, double temperatureKelvin) {
            return equilibriumRatios[node][component] * Math.exp(
                    equilibriumTemperatureSlopesPerKelvin[component] * (temperatureKelvin - condenserTemperatureKelvin));
        }
        double totalFeedFlowMolPerSecond() { return sum(feedComponentFlowsMolPerSecond); }
        double energyScaleWatts() {
            double meanHeatCapacity = sum(componentHeatCapacitiesJPerMolKelvin) / componentCount;
            return Math.max(1.0, totalFeedFlowMolPerSecond() * meanHeatCapacity * feedTemperatureKelvin);
        }
    }

    record State(double[][] liquidComponentFlows, double[][] vaporComponentFlows, double[] temperaturesKelvin) {
        State {
            liquidComponentFlows = copy(liquidComponentFlows);
            vaporComponentFlows = copy(vaporComponentFlows);
            temperaturesKelvin = temperaturesKelvin.clone();
        }

        @Override public double[][] liquidComponentFlows() { return copy(liquidComponentFlows); }
        @Override public double[][] vaporComponentFlows() { return copy(vaporComponentFlows); }
        @Override public double[] temperaturesKelvin() { return temperaturesKelvin.clone(); }
    }

    record Evaluation(double[] rawResiduals, double[] scaledResiduals, RowFamily[] rowFamilies) {
        Evaluation {
            rawResiduals = rawResiduals.clone();
            scaledResiduals = scaledResiduals.clone();
            rowFamilies = rowFamilies.clone();
            if (rawResiduals.length != scaledResiduals.length || rawResiduals.length != rowFamilies.length) {
                throw new IllegalArgumentException("Independent evaluation shape is invalid");
            }
        }

        @Override public double[] rawResiduals() { return rawResiduals.clone(); }
        @Override public double[] scaledResiduals() { return scaledResiduals.clone(); }
        @Override public RowFamily[] rowFamilies() { return rowFamilies.clone(); }

        double maximumAbsoluteScaledResidual() {
            double maximum = 0.0;
            for (double residual : scaledResiduals) maximum = Math.max(maximum, Math.abs(residual));
            return maximum;
        }

        double scaledSquaredNorm() {
            double norm = 0.0;
            for (double residual : scaledResiduals) norm += residual * residual;
            return norm;
        }
    }

    record SolveResult(double[] coordinates, State state, Evaluation evaluation, int iterations) {
        SolveResult {
            coordinates = coordinates.clone();
            state = Objects.requireNonNull(state, "state");
            evaluation = Objects.requireNonNull(evaluation, "evaluation");
            if (iterations < 0) throw new IllegalArgumentException("Independent solver iteration count is negative");
        }

        @Override public double[] coordinates() { return coordinates.clone(); }
    }

    enum RowFamily {
        COMPONENT_MATERIAL_BALANCE,
        VAPOR_LIQUID_EQUILIBRIUM,
        ENERGY_BALANCE
    }

    private static double[][] copy(double[][] values) {
        values = Objects.requireNonNull(values, "values");
        double[][] copy = new double[values.length][];
        for (int index = 0; index < values.length; index++) copy[index] = Objects.requireNonNull(values[index], "row").clone();
        return copy;
    }
}
