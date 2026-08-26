package com.wormzjl.createcheme.science.column.nextgen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Independent dense material calculation for the exact default cold local model.  It is test-only
 * evidence: it neither calls the production inside-out solver nor establishes full-column
 * feasibility, because energy and phase/traffic globalization remain coupled unknowns.
 */
class DefaultColdModelDenseOracleTest {
    private static final int COMPONENTS = 16;

    @Test
    void exactDefaultColdMaterialModelIsAlgebraicallySolvedButNotSumRatesClosed() {
        ColdModelEvidence evidence = new DenseColdModelOracle().evaluate(ColumnProblem.resolve(ColumnNextInput.defaults()),
                0.20, 0.12);

        assertTrue(evidence.maximumDenseResidual() <= 1.0e-12);
        assertTrue(evidence.maximumExternalComponentBalance() <= 1.0e-12, evidence::toString);
        // The methane feed component is exactly zero.  Pivoted dense elimination may expose only
        // roundoff there; this oracle never feeds that value back into production or clamps it.
        assertTrue(evidence.minimumComponentFlow() >= -1.0e-24, evidence::toString);
        assertTrue(evidence.maximumSumRatesMismatch() > 1.0,
                () -> "cold model unexpectedly closed: " + evidence.maximumSumRatesMismatch());
    }

    @Test
    void boundaryTrafficScanShowsWhetherTheCurrentHeuristicIsLocallyCompetitive() {
        DenseColdModelOracle oracle = new DenseColdModelOracle();
        ColumnProblem problem = ColumnProblem.resolve(ColumnNextInput.defaults());
        ColdModelEvidence heuristic = oracle.evaluate(problem, 0.20, 0.12);
        ColdModelEvidence best = oracle.scanBoundaryTraffic(problem);

        assertTrue(best.maximumSumRatesMismatch() < heuristic.maximumSumRatesMismatch(),
                () -> "best=" + best + " heuristic=" + heuristic);
    }

    @Test
    void zeroRampColdTrafficCandidatePreservesExternalComponentClosure() {
        ColumnProblem zeroRamp = ColumnProblem.resolve(
                DryInsideOutColumnSolver.continuationInput(ColumnNextInput.defaults(), 0.0));
        ColdModelEvidence evidence = new DenseColdModelOracle().evaluate(zeroRamp, 0.125, 0.125);

        // The deliberately ill-conditioned zero-ramp matrix still closes external balance to its
        // independently measured dense-solve backward-error scale.
        assertTrue(evidence.maximumExternalComponentBalance() <= 1.0e-9, evidence::toString);
    }

    private record ColdModelEvidence(
            double liquidProductFraction, double vaporProductFraction, double maximumDenseResidual,
            double minimumComponentFlow, double maximumSumRatesMismatch, double maximumExternalComponentBalance) {}

    private static final class DenseColdModelOracle {
        ColdModelEvidence evaluate(ColumnProblem problem, double liquidProductFraction, double vaporProductFraction) {
            ColumnNextInput input = problem.input();
            ColumnTopology topology = problem.topology();
            int nodes = topology.nodeCount();
            double[] feed = new double[COMPONENTS];
            for (int component = 0; component < COMPONENTS; component++) {
                feed[component] = problem.feed().moleFraction(component);
            }
            NextPengRobinsonKernel kernel = new NextPengRobinsonKernel(problem.propertyPackage());
            NextFeedFlash.Workspace flashWorkspace = new NextFeedFlash.Workspace(kernel);
            NextFeedFlash.Result flash = NextFeedFlash.resolve(kernel, input.crudeFeed().temperatureKelvin(),
                    problem.nodePressurePascal(topology.feedStage()), feed, flashWorkspace);
            if (!flash.converged()) throw new AssertionError(flash.detail());

            double[] liquidTotals = new double[nodes];
            double[] vaporTotals = new double[nodes];
            buildBalanceClosedTraffic(input, topology, flash.vaporFraction(), liquidProductFraction,
                    vaporProductFraction, liquidTotals, vaporTotals);
            double[] temperatures = coldTemperatures(problem);
            double[] localK = rigorousColdK(problem, kernel, temperatures,
                    flashWorkspace.liquidComposition, flashWorkspace.vaporComposition);
            double[] liquidFlows = new double[nodes * COMPONENTS];
            double[] vaporFlows = new double[nodes * COMPONENTS];
            double[] sideFlows = new double[(topology.stageCount() + 1) * COMPONENTS];
            double[] sideRates = sideRates(input, topology.stageCount());
            double maximumDenseResidual = 0.0;
            for (int component = 0; component < COMPONENTS; component++) {
                double[] lower = new double[nodes];
                double[] diagonal = new double[nodes];
                double[] upper = new double[nodes];
                double[] rhs = new double[nodes];
                double[] componentK = new double[nodes];
                for (int node = 0; node < nodes; node++) componentK[node] = localK[node * COMPONENTS + component];
                topology.assembleHydrocarbonRows(componentK, liquidTotals, vaporTotals, liquidTotals[0],
                        liquidTotals[nodes - 1], input.organicRefluxRatio() / (1.0 + input.organicRefluxRatio()),
                        sideRates, input.crudeFeed().molarFlowMolPerSecond() * feed[component], lower, diagonal,
                        upper, rhs);
                double[] solution = denseSolve(lower, diagonal, upper, rhs);
                maximumDenseResidual = Math.max(maximumDenseResidual, residual(lower, diagonal, upper, rhs, solution));
                for (int node = 0; node < nodes; node++) liquidFlows[node * COMPONENTS + component] = solution[node];
            }
            double minimumComponentFlow = Double.POSITIVE_INFINITY;
            double maximumSumRatesMismatch = 0.0;
            for (int node = 0; node < nodes; node++) {
                double rawLiquid = 0.0;
                double rawVapor = 0.0;
                for (int component = 0; component < COMPONENTS; component++) {
                    int index = node * COMPONENTS + component;
                    rawLiquid += liquidFlows[index];
                    vaporFlows[index] = localK[index] * vaporTotals[node] / liquidTotals[node] * liquidFlows[index];
                    rawVapor += vaporFlows[index];
                    minimumComponentFlow = Math.min(minimumComponentFlow,
                            Math.min(liquidFlows[index], vaporFlows[index]));
                }
                maximumSumRatesMismatch = Math.max(maximumSumRatesMismatch,
                        Math.abs(Math.log(rawLiquid / liquidTotals[node])));
                maximumSumRatesMismatch = Math.max(maximumSumRatesMismatch,
                        Math.abs(Math.log(rawVapor / vaporTotals[node])));
            }
            for (int stage = 1; stage <= topology.stageCount(); stage++) {
                double ratio = sideRates[stage] / liquidTotals[stage];
                int offset = stage * COMPONENTS;
                for (int component = 0; component < COMPONENTS; component++) {
                    sideFlows[offset + component] = ratio * liquidFlows[offset + component];
                }
            }
            double maximumExternalComponentBalance = 0.0;
            double beta = input.organicRefluxRatio() / (1.0 + input.organicRefluxRatio());
            for (int component = 0; component < COMPONENTS; component++) {
                double side = 0.0;
                for (int stage = 1; stage <= topology.stageCount(); stage++) {
                    side += sideFlows[stage * COMPONENTS + component];
                }
                double overhead = vaporFlows[component] + (1.0 - beta) * liquidFlows[component];
                double bottoms = liquidFlows[(nodes - 1) * COMPONENTS + component];
                maximumExternalComponentBalance = Math.max(maximumExternalComponentBalance,
                        Math.abs(feed[component] * input.crudeFeed().molarFlowMolPerSecond() - overhead - side - bottoms));
            }
            return new ColdModelEvidence(liquidProductFraction, vaporProductFraction, maximumDenseResidual,
                    minimumComponentFlow, maximumSumRatesMismatch, maximumExternalComponentBalance);
        }

        ColdModelEvidence scanBoundaryTraffic(ColumnProblem problem) {
            ColdModelEvidence best = null;
            for (int liquidTenths = 1; liquidTenths <= 7; liquidTenths++) {
                for (int vaporTenths = 1; vaporTenths + liquidTenths <= 8; vaporTenths++) {
                    double liquidFraction = liquidTenths / 10.0;
                    double vaporFraction = vaporTenths / 10.0;
                    try {
                        ColdModelEvidence candidate = evaluate(problem, liquidFraction, vaporFraction);
                        if (candidate.minimumComponentFlow() < -1.0e-12) continue;
                        if (best == null || candidate.maximumSumRatesMismatch() < best.maximumSumRatesMismatch()) {
                            best = candidate;
                        }
                    } catch (IllegalArgumentException | IllegalStateException ignored) {
                        // Outside the positive cold-traffic domain; the scan records only admissible points.
                    }
                }
            }
            if (best == null) throw new AssertionError("No positive dense-oracle cold boundary traffic point");
            return best;
        }

        private static void buildBalanceClosedTraffic(
                ColumnNextInput input, ColumnTopology topology, double feedVaporFraction,
                double liquidProductFraction, double vaporProductFraction,
                double[] liquidTotals, double[] vaporTotals) {
            double feed = input.crudeFeed().molarFlowMolPerSecond();
            double[] side = sideRates(input, topology.stageCount());
            double sideTotal = 0.0;
            for (int stage = 1; stage <= topology.stageCount(); stage++) sideTotal += side[stage];
            double remaining = feed - sideTotal;
            double beta = input.organicRefluxRatio() / (1.0 + input.organicRefluxRatio());
            if (!(liquidProductFraction > 0.0) || !(vaporProductFraction > 0.0)
                    || liquidProductFraction + vaporProductFraction >= 1.0) {
                throw new IllegalArgumentException("Cold boundary product fractions are not positive and feasible");
            }
            double condensate = liquidProductFraction * remaining / (1.0 - beta);
            double overheadVapor = vaporProductFraction * remaining;
            liquidTotals[0] = condensate;
            vaporTotals[0] = overheadVapor;
            double incomingLiquid = beta * condensate;
            double upwardVapor = condensate + overheadVapor;
            for (int stage = 1; stage <= topology.stageCount(); stage++) {
                double liquidFeed = stage == topology.feedStage() ? (1.0 - feedVaporFraction) * feed : 0.0;
                double vaporFeed = stage == topology.feedStage() ? feedVaporFraction * feed : 0.0;
                liquidTotals[stage] = incomingLiquid - side[stage] + liquidFeed;
                vaporTotals[stage] = upwardVapor;
                incomingLiquid = liquidTotals[stage];
                upwardVapor -= vaporFeed;
            }
            liquidTotals[liquidTotals.length - 1] = incomingLiquid - upwardVapor;
            vaporTotals[vaporTotals.length - 1] = upwardVapor;
        }

        private static double[] coldTemperatures(ColumnProblem problem) {
            int nodes = problem.topology().nodeCount();
            double[] result = new double[nodes];
            double top = problem.input().condenserOutletTemperatureKelvin();
            double bottom = Math.clamp(problem.input().crudeFeed().temperatureKelvin(), top + 20.0,
                    Math.min(problem.propertyPackage().maximumTemperatureKelvin() - 2.0, 875.0));
            for (int node = 0; node < nodes; node++) result[node] = top + node * (bottom - top) / (nodes - 1.0);
            return result;
        }

        private static double[] rigorousColdK(
                ColumnProblem problem, NextPengRobinsonKernel kernel, double[] temperatures,
                double[] liquidComposition, double[] vaporComposition) {
            double[] values = new double[temperatures.length * COMPONENTS];
            NextPengRobinsonKernel.Workspace workspace = kernel.newWorkspace();
            NextPengRobinsonKernel.Evaluation liquid = kernel.newEvaluation();
            NextPengRobinsonKernel.Evaluation vapor = kernel.newEvaluation();
            for (int node = 0; node < temperatures.length; node++) {
                kernel.evaluatePair(temperatures[node], problem.nodePressurePascal(node), liquidComposition, vaporComposition,
                        workspace, liquid, vapor);
                for (int component = 0; component < COMPONENTS; component++) {
                    values[node * COMPONENTS + component] = Math.exp(liquid.logFugacityCoefficient(component)
                            - vapor.logFugacityCoefficient(component));
                }
            }
            return values;
        }

        private static double[] sideRates(ColumnNextInput input, int stages) {
            double[] result = new double[stages + 1];
            for (ColumnNextInput.SideDrawInput side : input.sideDraws()) {
                if (side.basis() != ColumnNextInput.AuthoredBasis.MOLAR) {
                    throw new IllegalArgumentException("This exact default oracle expects molar side draws");
                }
                result[side.stageNumber()] = side.authoredRate();
            }
            return result;
        }

        private static double[] denseSolve(double[] lower, double[] diagonal, double[] upper, double[] rhs) {
            int size = diagonal.length;
            double[][] matrix = new double[size][size + 1];
            for (int row = 0; row < size; row++) {
                matrix[row][row] = diagonal[row];
                if (row > 0) matrix[row][row - 1] = lower[row];
                if (row + 1 < size) matrix[row][row + 1] = upper[row];
                matrix[row][size] = rhs[row];
            }
            for (int pivot = 0; pivot < size; pivot++) {
                int selected = pivot;
                for (int row = pivot + 1; row < size; row++) {
                    if (Math.abs(matrix[row][pivot]) > Math.abs(matrix[selected][pivot])) selected = row;
                }
                double[] swap = matrix[pivot];
                matrix[pivot] = matrix[selected];
                matrix[selected] = swap;
                if (Math.abs(matrix[pivot][pivot]) < 1.0e-14) throw new IllegalStateException("singular dense oracle matrix");
                for (int row = pivot + 1; row < size; row++) {
                    double scale = matrix[row][pivot] / matrix[pivot][pivot];
                    for (int column = pivot; column <= size; column++) matrix[row][column] -= scale * matrix[pivot][column];
                }
            }
            double[] solution = new double[size];
            for (int row = size - 1; row >= 0; row--) {
                double value = matrix[row][size];
                for (int column = row + 1; column < size; column++) value -= matrix[row][column] * solution[column];
                solution[row] = value / matrix[row][row];
            }
            return solution;
        }

        private static double residual(double[] lower, double[] diagonal, double[] upper, double[] rhs, double[] solution) {
            double maximum = 0.0;
            for (int row = 0; row < solution.length; row++) {
                double value = diagonal[row] * solution[row] - rhs[row];
                if (row > 0) value += lower[row] * solution[row - 1];
                if (row + 1 < solution.length) value += upper[row] * solution[row + 1];
                maximum = Math.max(maximum, Math.abs(value));
            }
            return maximum;
        }
    }
}
