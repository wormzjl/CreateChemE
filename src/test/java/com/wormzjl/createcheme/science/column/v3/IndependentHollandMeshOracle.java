package com.wormzjl.createcheme.science.column.v3;

import java.util.Arrays;
import java.util.Objects;

/**
 * Independent test-only MESH implementation for Holland Example 3-2.
 *
 * <p>It deliberately does not call V3 topology, residual, thermodynamic, coordinate, Jacobian,
 * linear-solver, or audit code. The only shared input is the scan-transcribed fixture.</p>
 */
final class IndependentHollandMeshOracle {
    private static final int NODE_COUNT = 13;
    private static final int MAXIMUM_LINE_SEARCH_STEPS = 40;
    private static final double MINIMUM_TEMPERATURE_FAHRENHEIT = -100.0;
    private static final double MAXIMUM_TEMPERATURE_FAHRENHEIT = 700.0;
    private static final double ENERGY_SCALE_BTU_PER_HOUR = 10_000_000.0;

    private final HollandExample32Data data;
    private final int componentCount;
    private final double refluxFraction;
    private final double[] feed;
    private final double[][] kCoefficients;
    private final double[][] liquidEnthalpyCoefficients;
    private final double[][] vaporEnthalpyCoefficients;
    private final double feedTemperatureFahrenheit;
    private final double feedEnthalpyRateBtuPerHour;

    IndependentHollandMeshOracle(HollandExample32Data data, double condenserSplitRatio) {
        this.data = Objects.requireNonNull(data, "data");
        if (!Double.isFinite(condenserSplitRatio) || condenserSplitRatio <= 0.0) {
            throw new IllegalArgumentException("Independent Holland condenser split ratio must be positive");
        }
        componentCount = data.componentCount();
        refluxFraction = condenserSplitRatio / (1.0 + condenserSplitRatio);
        feed = data.feedLbMolPerHour();
        kCoefficients = data.kCoefficients();
        liquidEnthalpyCoefficients = data.liquidEnthalpyCoefficients();
        vaporEnthalpyCoefficients = data.vaporEnthalpyCoefficients();
        feedTemperatureFahrenheit = bubblePointTemperatureFahrenheit(feed);
        feedEnthalpyRateBtuPerHour = componentEnergy(feed, feedTemperatureFahrenheit, false);
    }

    int coordinateCount() {
        return 2 * NODE_COUNT * componentCount + NODE_COUNT - 1;
    }

    double feedTemperatureFahrenheit() {
        return feedTemperatureFahrenheit;
    }

    SolveResult solve(int maximumIterations, double scaledTolerance) {
        if (maximumIterations < 1 || !Double.isFinite(scaledTolerance) || scaledTolerance <= 0.0) {
            throw new IllegalArgumentException("Independent Holland solve limits are invalid");
        }
        double[] coordinates = coordinatesOf(initialState());
        for (int iteration = 0; iteration <= maximumIterations; iteration++) {
            double[] residual = residual(coordinates);
            double maximum = maximumAbsolute(residual);
            if (maximum <= scaledTolerance) {
                return new SolveResult(stateOf(coordinates), residual, iteration);
            }
            if (iteration == maximumIterations) break;
            double[][] jacobian = finiteDifferenceJacobian(coordinates);
            double[] correction = densePivotedSolve(jacobian, negate(residual));
            double merit = squaredNorm(residual);
            boolean accepted = false;
            double fraction = 1.0;
            for (int lineSearch = 0; lineSearch < MAXIMUM_LINE_SEARCH_STEPS; lineSearch++) {
                double[] candidate = addScaled(coordinates, correction, fraction);
                try {
                    double[] candidateResidual = residual(candidate);
                    if (squaredNorm(candidateResidual) <= merit * (1.0 - 1.0e-4 * fraction)) {
                        coordinates = candidate;
                        accepted = true;
                        break;
                    }
                } catch (IllegalArgumentException outsideDomain) {
                    // Backtrack until every flow and temperature is in the independent domain.
                }
                fraction *= 0.5;
            }
            if (!accepted) throw new IllegalStateException("Independent Holland oracle line search exhausted");
        }
        throw new IllegalStateException("Independent Holland oracle reached its iteration limit");
    }

    double[] liquidTotals(State state) {
        return totals(state.liquidComponentFlows());
    }

    double[] vaporTotals(State state) {
        return totals(state.vaporComponentFlows());
    }

    double[] vaporDistillate(State state) {
        return state.vaporComponentFlows()[0].clone();
    }

    double[] sideDraw(State state) {
        int tray = data.sideDrawTray();
        double total = sum(state.liquidComponentFlows()[tray]);
        double[] product = state.liquidComponentFlows()[tray].clone();
        for (int component = 0; component < product.length; component++) product[component] *= 25.0 / total;
        return product;
    }

    double[] bottoms(State state) {
        return state.liquidComponentFlows()[NODE_COUNT - 1].clone();
    }

    double emergentRefluxToVaporDistillate(State state) {
        return refluxFraction * sum(state.liquidComponentFlows()[0]) / sum(state.vaporComponentFlows()[0]);
    }

    double condenserDutyBtuPerHour(State state) {
        return componentEnergy(state.vaporComponentFlows()[1], state.temperaturesFahrenheit()[1], true)
                - componentEnergy(state.liquidComponentFlows()[0], state.temperaturesFahrenheit()[0], false)
                - componentEnergy(state.vaporComponentFlows()[0], state.temperaturesFahrenheit()[0], true);
    }

    private State initialState() {
        double[] temperatures = data.solution().temperatureFahrenheit().clone();
        double[] targetLiquid = data.solution().v3LiquidStateLbMolPerHour();
        double[] targetVapor = data.solution().vaporLbMolPerHour();
        double[][] liquid = new double[NODE_COUNT][componentCount];
        double[][] vapor = new double[NODE_COUNT][componentCount];
        double[][] ratios = new double[NODE_COUNT][];
        for (int node = 0; node < NODE_COUNT; node++) ratios[node] = equilibriumRatios(temperatures[node]);
        for (int component = 0; component < componentCount; component++) {
            double[][] matrix = new double[NODE_COUNT][NODE_COUNT];
            double[] right = new double[NODE_COUNT];
            double[] vaporPerLiquid = new double[NODE_COUNT];
            for (int node = 0; node < NODE_COUNT; node++) {
                vaporPerLiquid[node] = ratios[node][component] * targetVapor[node] / targetLiquid[node];
            }
            matrix[0][0] = -1.0 - vaporPerLiquid[0];
            matrix[0][1] = vaporPerLiquid[1];
            for (int node = 1; node < NODE_COUNT - 1; node++) {
                matrix[node][node - 1] = node == 1 ? refluxFraction
                        : 1.0 - targetWithdrawalFraction(node - 1, targetLiquid[node - 1]);
                matrix[node][node] = -1.0 - vaporPerLiquid[node];
                matrix[node][node + 1] = vaporPerLiquid[node + 1];
                if (node == data.feedTray()) right[node] = -feed[component];
            }
            matrix[NODE_COUNT - 1][NODE_COUNT - 2] = 1.0;
            matrix[NODE_COUNT - 1][NODE_COUNT - 1] = -1.0 - vaporPerLiquid[NODE_COUNT - 1];
            double[] componentLiquid = densePivotedSolve(matrix, right);
            for (int node = 0; node < NODE_COUNT; node++) {
                liquid[node][component] = componentLiquid[node];
                vapor[node][component] = vaporPerLiquid[node] * componentLiquid[node];
                requirePositive(liquid[node][component], "independent initial liquid flow");
                requirePositive(vapor[node][component], "independent initial vapor flow");
            }
        }
        return new State(liquid, vapor, temperatures);
    }

    private double[] residual(double[] coordinates) {
        State state = stateOf(coordinates);
        double[][] liquid = state.liquidComponentFlows();
        double[][] vapor = state.vaporComponentFlows();
        double[] temperatures = state.temperaturesFahrenheit();
        double[] residual = new double[coordinateCount()];
        int row = 0;
        for (int node = 0; node < NODE_COUNT; node++) {
            for (int component = 0; component < componentCount; component++) {
                double value;
                if (node == 0) {
                    value = vapor[1][component] - liquid[0][component] - vapor[0][component];
                } else if (node < NODE_COUNT - 1) {
                    double liquidIn = node == 1 ? refluxFraction * liquid[0][component]
                            : (1.0 - withdrawalFraction(state, node - 1)) * liquid[node - 1][component];
                    value = liquidIn + vapor[node + 1][component]
                            + (node == data.feedTray() ? feed[component] : 0.0)
                            - liquid[node][component] - vapor[node][component];
                } else {
                    value = liquid[node - 1][component] - liquid[node][component] - vapor[node][component];
                }
                residual[row++] = value / Math.max(feed[component], 1.0e-10);
            }
        }
        for (int node = 0; node < NODE_COUNT; node++) {
            double liquidTotal = sum(liquid[node]);
            double vaporTotal = sum(vapor[node]);
            double[] ratios = equilibriumRatios(temperatures[node]);
            for (int component = 0; component < componentCount; component++) {
                residual[row++] = Math.log(vapor[node][component] / vaporTotal)
                        - Math.log(ratios[component] * liquid[node][component] / liquidTotal);
            }
        }
        for (int node = 1; node < NODE_COUNT; node++) {
            double value;
            if (node < NODE_COUNT - 1) {
                double liquidIn = node == 1
                        ? refluxFraction * componentEnergy(liquid[0], temperatures[0], false)
                        : (1.0 - withdrawalFraction(state, node - 1))
                        * componentEnergy(liquid[node - 1], temperatures[node - 1], false);
                value = liquidIn + componentEnergy(vapor[node + 1], temperatures[node + 1], true)
                        + (node == data.feedTray() ? feedEnthalpyRateBtuPerHour : 0.0)
                        - componentEnergy(liquid[node], temperatures[node], false)
                        - componentEnergy(vapor[node], temperatures[node], true);
            } else {
                value = componentEnergy(liquid[node - 1], temperatures[node - 1], false)
                        + data.solution().reboilerDutyBtuPerHour()
                        - componentEnergy(liquid[node], temperatures[node], false)
                        - componentEnergy(vapor[node], temperatures[node], true);
            }
            residual[row++] = value / ENERGY_SCALE_BTU_PER_HOUR;
        }
        if (row != residual.length) throw new IllegalStateException("Independent Holland residual is not square");
        return residual;
    }

    private double[][] finiteDifferenceJacobian(double[] coordinates) {
        double[][] jacobian = new double[coordinates.length][coordinates.length];
        int flowCoordinates = 2 * NODE_COUNT * componentCount;
        for (int column = 0; column < coordinates.length; column++) {
            double step = column < flowCoordinates ? 1.0e-6
                    : Math.max(1.0e-4, Math.abs(coordinates[column]) * 1.0e-6);
            double[] higher = coordinates.clone();
            double[] lower = coordinates.clone();
            higher[column] += step;
            lower[column] -= step;
            double[] highResidual = residual(higher);
            double[] lowResidual = residual(lower);
            for (int row = 0; row < coordinates.length; row++) {
                jacobian[row][column] = (highResidual[row] - lowResidual[row]) / (2.0 * step);
            }
        }
        return jacobian;
    }

    private double[] coordinatesOf(State state) {
        double[] coordinates = new double[coordinateCount()];
        int index = 0;
        for (double[] node : state.liquidComponentFlows()) {
            for (double flow : node) coordinates[index++] = Math.log(requirePositive(flow, "independent liquid flow"));
        }
        for (double[] node : state.vaporComponentFlows()) {
            for (double flow : node) coordinates[index++] = Math.log(requirePositive(flow, "independent vapor flow"));
        }
        for (int node = 1; node < NODE_COUNT; node++) coordinates[index++] = state.temperaturesFahrenheit()[node];
        return coordinates;
    }

    private State stateOf(double[] coordinates) {
        coordinates = Objects.requireNonNull(coordinates, "coordinates");
        if (coordinates.length != coordinateCount() || Arrays.stream(coordinates).anyMatch(value -> !Double.isFinite(value))) {
            throw new IllegalArgumentException("Independent Holland coordinates are invalid");
        }
        int index = 0;
        double[][] liquid = new double[NODE_COUNT][componentCount];
        double[][] vapor = new double[NODE_COUNT][componentCount];
        for (int node = 0; node < NODE_COUNT; node++) {
            for (int component = 0; component < componentCount; component++) {
                liquid[node][component] = requirePositive(Math.exp(coordinates[index++]), "independent liquid flow");
            }
        }
        for (int node = 0; node < NODE_COUNT; node++) {
            for (int component = 0; component < componentCount; component++) {
                vapor[node][component] = requirePositive(Math.exp(coordinates[index++]), "independent vapor flow");
            }
        }
        double[] temperatures = new double[NODE_COUNT];
        temperatures[0] = data.solution().temperatureFahrenheit()[0];
        for (int node = 1; node < NODE_COUNT; node++) {
            double temperature = coordinates[index++];
            if (temperature < MINIMUM_TEMPERATURE_FAHRENHEIT || temperature > MAXIMUM_TEMPERATURE_FAHRENHEIT) {
                throw new IllegalArgumentException("Independent Holland temperature is outside the fit range");
            }
            temperatures[node] = temperature;
        }
        return new State(liquid, vapor, temperatures);
    }

    private double withdrawalFraction(State state, int node) {
        return node == data.sideDrawTray() ? 25.0 / sum(state.liquidComponentFlows()[node]) : 0.0;
    }

    private double targetWithdrawalFraction(int node, double targetLiquid) {
        return node == data.sideDrawTray() ? 25.0 / targetLiquid : 0.0;
    }

    private double bubblePointTemperatureFahrenheit(double[] composition) {
        double[] z = normalized(composition);
        double lower = -100.0;
        double upper = 600.0;
        for (int iteration = 0; iteration < 120; iteration++) {
            double middle = 0.5 * (lower + upper);
            double residual = -1.0;
            double[] ratios = equilibriumRatios(middle);
            for (int component = 0; component < componentCount; component++) residual += z[component] * ratios[component];
            if (Math.abs(residual) <= 1.0e-14 || upper - lower <= 1.0e-12) return middle;
            if (residual > 0.0) upper = middle;
            else lower = middle;
        }
        return 0.5 * (lower + upper);
    }

    private double[] equilibriumRatios(double temperatureFahrenheit) {
        double rankine = temperatureFahrenheit + 459.67;
        double[] ratios = new double[componentCount];
        for (int component = 0; component < componentCount; component++) {
            double[] coefficient = kCoefficients[component];
            double cubeRoot = coefficient[0] + coefficient[1] * rankine
                    + coefficient[2] * rankine * rankine + coefficient[3] * rankine * rankine * rankine;
            ratios[component] = rankine * cubeRoot * cubeRoot * cubeRoot;
            requirePositive(ratios[component], "independent equilibrium ratio");
        }
        return ratios;
    }

    private double componentEnergy(double[] componentFlows, double temperatureFahrenheit, boolean vapor) {
        double rankine = temperatureFahrenheit + 459.67;
        double[][] coefficients = vapor ? vaporEnthalpyCoefficients : liquidEnthalpyCoefficients;
        double energy = 0.0;
        for (int component = 0; component < componentCount; component++) {
            double[] coefficient = coefficients[component];
            double squareRoot = coefficient[0] + coefficient[1] * rankine + coefficient[2] * rankine * rankine;
            energy += componentFlows[component] * squareRoot * squareRoot;
        }
        return energy;
    }

    private static double[] normalized(double[] values) {
        double[] normalized = values.clone();
        double total = sum(normalized);
        for (int index = 0; index < normalized.length; index++) normalized[index] /= total;
        return normalized;
    }

    private static double[] totals(double[][] values) {
        double[] totals = new double[values.length];
        for (int node = 0; node < values.length; node++) totals[node] = sum(values[node]);
        return totals;
    }

    private static double sum(double[] values) {
        double total = 0.0;
        for (double value : values) total += value;
        return total;
    }

    private static double maximumAbsolute(double[] values) {
        double maximum = 0.0;
        for (double value : values) maximum = Math.max(maximum, Math.abs(value));
        return maximum;
    }

    private static double squaredNorm(double[] values) {
        double total = 0.0;
        for (double value : values) total += value * value;
        return total;
    }

    private static double requirePositive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0.0) throw new IllegalArgumentException(name + " must be finite and positive");
        return value;
    }

    private static double[] negate(double[] values) {
        double[] result = values.clone();
        for (int index = 0; index < result.length; index++) result[index] = -result[index];
        return result;
    }

    private static double[] addScaled(double[] base, double[] correction, double fraction) {
        double[] result = base.clone();
        for (int index = 0; index < result.length; index++) result[index] += fraction * correction[index];
        return result;
    }

    private static double[] densePivotedSolve(double[][] matrix, double[] rightHandSide) {
        int size = rightHandSide.length;
        if (matrix.length != size) throw new IllegalArgumentException("Independent Holland matrix is not square");
        double[][] working = new double[size][size];
        double[] right = rightHandSide.clone();
        for (int row = 0; row < size; row++) {
            if (matrix[row].length != size) throw new IllegalArgumentException("Independent Holland matrix is not square");
            working[row] = matrix[row].clone();
        }
        for (int pivot = 0; pivot < size; pivot++) {
            int pivotRow = pivot;
            for (int row = pivot + 1; row < size; row++) {
                if (Math.abs(working[row][pivot]) > Math.abs(working[pivotRow][pivot])) pivotRow = row;
            }
            if (!Double.isFinite(working[pivotRow][pivot]) || Math.abs(working[pivotRow][pivot]) <= 1.0e-14) {
                throw new IllegalStateException("Independent Holland Jacobian is singular at pivot " + pivot);
            }
            if (pivotRow != pivot) {
                double[] temporaryRow = working[pivot];
                working[pivot] = working[pivotRow];
                working[pivotRow] = temporaryRow;
                double temporaryRight = right[pivot];
                right[pivot] = right[pivotRow];
                right[pivotRow] = temporaryRight;
            }
            for (int row = pivot + 1; row < size; row++) {
                double multiplier = working[row][pivot] / working[pivot][pivot];
                working[row][pivot] = 0.0;
                for (int column = pivot + 1; column < size; column++) {
                    working[row][column] -= multiplier * working[pivot][column];
                }
                right[row] -= multiplier * right[pivot];
            }
        }
        double[] solution = new double[size];
        for (int row = size - 1; row >= 0; row--) {
            double value = right[row];
            for (int column = row + 1; column < size; column++) value -= working[row][column] * solution[column];
            solution[row] = value / working[row][row];
            if (!Double.isFinite(solution[row])) throw new IllegalStateException("Independent Holland correction is nonfinite");
        }
        return solution;
    }

    record State(double[][] liquidComponentFlows, double[][] vaporComponentFlows, double[] temperaturesFahrenheit) {}

    record SolveResult(State state, double[] scaledResidual, int iterations) {
        SolveResult {
            Objects.requireNonNull(state, "state");
            scaledResidual = Objects.requireNonNull(scaledResidual, "scaledResidual").clone();
            if (iterations < 0) throw new IllegalArgumentException("Independent Holland iterations are negative");
        }

        @Override public double[] scaledResidual() { return scaledResidual.clone(); }

        double maximumScaledResidual() { return maximumAbsolute(scaledResidual); }
    }
}
