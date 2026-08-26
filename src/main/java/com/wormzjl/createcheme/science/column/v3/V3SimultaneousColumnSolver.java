package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.linalg.V3BandedMatrix;
import com.wormzjl.createcheme.science.column.v3.linalg.V3BandedPivotedSolver;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoException;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoWorkspace;
import java.util.Objects;

/** Damped dry-MESH Newton correction path that emits attempt evidence only; it cannot publish a V3 success. */
final class V3SimultaneousColumnSolver {
    private static final double OFF_BAND_TOLERANCE = 1.0e-10;
    private static final double ARMIJO_COEFFICIENT = 1.0e-4;
    private static final int FINE_MAXIMUM_LINE_SEARCH_STEPS = 20;
    private static final int COARSE_RECOVERY_MAXIMUM_LINE_SEARCH_STEPS = 40;
    private static final double FALLBACK_MAXIMUM_LOG_FLOW_CHANGE = 0.25;
    private static final double FALLBACK_MAXIMUM_TEMPERATURE_CHANGE_KELVIN = 10.0;
    private static final double INITIAL_GAUSS_NEWTON_DAMPING = 1.0e-8;
    private static final int MAXIMUM_GAUSS_NEWTON_DAMPING_STEPS = 8;

    private V3SimultaneousColumnSolver() {}

    static Attempt solve(
            V3ColumnProblem problem, V3MeshResidualEvaluator evaluator, V3DryMeshCoordinateMap coordinates,
            V3DryMeshState initialState, V3FiniteDifferenceJacobian.V3ThermoWorkspaceFactory workspaceFactory,
            int maximumIterations, double scaledTolerance) {
        return solve(problem, evaluator, coordinates, initialState, workspaceFactory, V3ConvergenceEvidence.unavailable(),
                maximumIterations, scaledTolerance, V3FiniteDifferenceJacobian.DifferenceScale.FINE);
    }

    static Attempt solve(
            V3ColumnProblem problem, V3MeshResidualEvaluator evaluator, V3DryMeshCoordinateMap coordinates,
            V3DryMeshState initialState, V3FiniteDifferenceJacobian.V3ThermoWorkspaceFactory workspaceFactory,
            V3ConvergenceEvidence initialConvergenceEvidence, int maximumIterations, double scaledTolerance) {
        problem = Objects.requireNonNull(problem, "problem");
        evaluator = Objects.requireNonNull(evaluator, "evaluator");
        coordinates = Objects.requireNonNull(coordinates, "coordinates");
        initialState = Objects.requireNonNull(initialState, "initialState");
        workspaceFactory = Objects.requireNonNull(workspaceFactory, "workspaceFactory");
        initialConvergenceEvidence = Objects.requireNonNull(initialConvergenceEvidence, "initialConvergenceEvidence");
        return solve(problem, evaluator, coordinates, initialState, workspaceFactory, initialConvergenceEvidence,
                maximumIterations, scaledTolerance, V3FiniteDifferenceJacobian.DifferenceScale.FINE);
    }

    static Attempt solve(
            V3ColumnProblem problem, V3MeshResidualEvaluator evaluator, V3DryMeshCoordinateMap coordinates,
            V3DryMeshState initialState, V3FiniteDifferenceJacobian.V3ThermoWorkspaceFactory workspaceFactory,
            V3ConvergenceEvidence initialConvergenceEvidence, int maximumIterations, double scaledTolerance,
            V3FiniteDifferenceJacobian.DifferenceScale differenceScale) {
        problem = Objects.requireNonNull(problem, "problem");
        evaluator = Objects.requireNonNull(evaluator, "evaluator");
        coordinates = Objects.requireNonNull(coordinates, "coordinates");
        initialState = Objects.requireNonNull(initialState, "initialState");
        workspaceFactory = Objects.requireNonNull(workspaceFactory, "workspaceFactory");
        initialConvergenceEvidence = Objects.requireNonNull(initialConvergenceEvidence, "initialConvergenceEvidence");
        differenceScale = Objects.requireNonNull(differenceScale, "differenceScale");
        if (maximumIterations < 1 || !Double.isFinite(scaledTolerance) || scaledTolerance <= 0.0) {
            throw new IllegalArgumentException("V3 Newton solve limits are invalid");
        }
        V3StageBlockLayout layout = new V3StageBlockLayout(problem);
        int maximumLineSearchSteps = differenceScale == V3FiniteDifferenceJacobian.DifferenceScale.COARSE
                ? COARSE_RECOVERY_MAXIMUM_LINE_SEARCH_STEPS : FINE_MAXIMUM_LINE_SEARCH_STEPS;
        V3DryMeshState state = initialState;
        double lastMerit = Double.NaN;
        V3ConvergenceEvidence lastConvergenceEvidence = initialConvergenceEvidence;
        for (int iteration = 0; iteration <= maximumIterations; iteration++) {
            V3MeshResidual residual = evaluator.evaluate(state, workspaceFactory.newWorkspace());
            double maximumResidual = residual.maximumAbsoluteScaledResidual();
            double merit = scaledSquaredNorm(residual);
            if (maximumResidual <= scaledTolerance && lastConvergenceEvidence.satisfiesGates()) {
                return new Attempt.Converged(state, new Evidence(iteration, maximumResidual, merit, 0.0, 0.0,
                        "residual and final-step tolerances", lastConvergenceEvidence));
            }
            if (iteration == maximumIterations) {
                return new Attempt.Failure("MAX_ITERATIONS", state,
                        new Evidence(iteration, maximumResidual, merit, 0.0, 0.0, "iteration budget exhausted",
                                lastConvergenceEvidence));
            }
            V3FiniteDifferenceJacobian.Jacobian jacobian = V3FiniteDifferenceJacobian.evaluate(
                    evaluator, coordinates, state, workspaceFactory, differenceScale);
            V3BandedPivotedSolver.Result linearResult = V3BandedPivotedSolver.solve(
                    toBandedMatrix(jacobian, layout), negativeScaledResidual(residual));
            if (!(linearResult instanceof V3BandedPivotedSolver.Result.Success linearSuccess)) {
                V3BandedPivotedSolver.Result.Failure failure = (V3BandedPivotedSolver.Result.Failure) linearResult;
                double[] baseCoordinates = coordinates.encode(state);
                AcceptedTrial descentTrial = dampedGaussNewtonTrial(
                        evaluator, coordinates, baseCoordinates, jacobian, residual, merit, layout, workspaceFactory,
                        maximumLineSearchSteps);
                if (descentTrial == null) {
                    descentTrial = armijoTrial(evaluator, coordinates, baseCoordinates,
                            normalizedNegativeGradient(jacobian, residual, coordinates), merit, workspaceFactory,
                            maximumLineSearchSteps);
                }
                if (descentTrial == null) {
                    return new Attempt.Failure("LINEAR_" + failure.code(), state,
                            new Evidence(iteration, maximumResidual, merit, 0.0, failure.pivotGrowth(), failure.detail(),
                                    lastConvergenceEvidence));
                }
                state = descentTrial.state();
                lastMerit = descentTrial.merit();
                lastConvergenceEvidence = V3ConvergenceEvidence.unavailable();
                continue;
            }
            double[] baseCoordinates = coordinates.encode(state);
            AcceptedTrial acceptedTrial = armijoTrial(
                    evaluator, coordinates, baseCoordinates, linearSuccess.solution(), merit, workspaceFactory,
                    maximumLineSearchSteps);
            boolean usedDescentFallback = acceptedTrial == null;
            if (usedDescentFallback) {
                acceptedTrial = dampedGaussNewtonTrial(
                        evaluator, coordinates, baseCoordinates, jacobian, residual, merit, layout, workspaceFactory,
                        maximumLineSearchSteps);
                if (acceptedTrial == null) {
                    acceptedTrial = armijoTrial(evaluator, coordinates, baseCoordinates,
                            normalizedNegativeGradient(jacobian, residual, coordinates), merit, workspaceFactory,
                            maximumLineSearchSteps);
                }
            }
            if (acceptedTrial == null) {
                return new Attempt.Failure("LINE_SEARCH_EXHAUSTED", state,
                        new Evidence(iteration, maximumResidual, merit, 0.0, linearSuccess.pivotGrowth(),
                                "no admissible Armijo-reducing Newton or descent step", lastConvergenceEvidence));
            }
            state = acceptedTrial.state();
            lastMerit = acceptedTrial.merit();
            lastConvergenceEvidence = usedDescentFallback ? V3ConvergenceEvidence.unavailable()
                    : convergenceEvidence(coordinates, baseCoordinates, acceptedTrial.coordinates(), linearSuccess.backwardError());
            double acceptedStep = acceptedTrial.step();
            if (!Double.isFinite(lastMerit)) {
                return new Attempt.Failure("INTERNAL_INVARIANT", state,
                        new Evidence(iteration, maximumResidual, merit, acceptedStep, linearSuccess.pivotGrowth(),
                                "accepted Newton merit is not finite", lastConvergenceEvidence));
            }
        }
        throw new IllegalStateException("V3 Newton solve escaped its bounded iteration loop");
    }

    private static V3BandedMatrix toBandedMatrix(
            V3FiniteDifferenceJacobian.Jacobian jacobian, V3StageBlockLayout layout) {
        return stageBandedMatrix(jacobian.values(), layout, 1, "V3 Newton Jacobian contains an unexpected off-band coupling");
    }

    private static V3BandedMatrix stageBandedMatrix(
            double[][] values, V3StageBlockLayout layout, int maximumNodeSpan, String offBandMessage) {
        int lowerBandwidth = 0;
        int upperBandwidth = 0;
        for (int row = 0; row < values.length; row++) {
            int rowNode = nodeFor(row, layout);
            for (int column = 0; column < values.length; column++) {
                double value = values[row][column];
                int columnNode = nodeFor(column, layout);
                if (Math.abs(columnNode - rowNode) > maximumNodeSpan && Math.abs(value) > OFF_BAND_TOLERANCE) {
                    throw new IllegalStateException(offBandMessage);
                }
                if (Math.abs(value) <= OFF_BAND_TOLERANCE) continue;
                if (row >= column) lowerBandwidth = Math.max(lowerBandwidth, row - column);
                else upperBandwidth = Math.max(upperBandwidth, column - row);
            }
        }
        V3BandedMatrix matrix = new V3BandedMatrix(values.length, lowerBandwidth, upperBandwidth);
        for (int row = 0; row < values.length; row++) {
            for (int column = Math.max(0, row - lowerBandwidth); column <= Math.min(values.length - 1, row + upperBandwidth); column++) {
                matrix.set(row, column, values[row][column]);
            }
        }
        return matrix;
    }

    private static int nodeFor(int index, V3StageBlockLayout layout) {
        for (int node = 0; node < layout.nodeCount(); node++) {
            if (index >= layout.start(node) && index < layout.start(node) + layout.size(node)) return node;
        }
        throw new IllegalArgumentException("V3 Newton Jacobian index is outside the stage layout");
    }

    private static double[] negativeScaledResidual(V3MeshResidual residual) {
        double[] rightHandSide = new double[residual.rows().size()];
        for (int row = 0; row < rightHandSide.length; row++) rightHandSide[row] = -residual.rows().get(row).scaledValue();
        return rightHandSide;
    }

    private static double scaledSquaredNorm(V3MeshResidual residual) {
        double norm = 0.0;
        for (V3MeshResidual.Row row : residual.rows()) norm += row.scaledValue() * row.scaledValue();
        return norm;
    }

    private static double[] addScaled(double[] base, double[] correction, double step) {
        double[] candidate = base.clone();
        for (int index = 0; index < candidate.length; index++) candidate[index] += step * correction[index];
        return candidate;
    }

    private static AcceptedTrial armijoTrial(
            V3MeshResidualEvaluator evaluator, V3DryMeshCoordinateMap coordinates, double[] baseCoordinates,
            double[] direction, double merit, V3FiniteDifferenceJacobian.V3ThermoWorkspaceFactory workspaceFactory,
            int maximumLineSearchSteps) {
        for (int lineSearch = 0; lineSearch < maximumLineSearchSteps; lineSearch++) {
            double step = Math.scalb(1.0, -lineSearch);
            try {
                double[] candidateCoordinates = addScaled(baseCoordinates, direction, step);
                V3DryMeshState candidate = coordinates.decode(candidateCoordinates);
                double candidateMerit = scaledSquaredNorm(evaluator.evaluate(candidate, workspaceFactory.newWorkspace()));
                if (candidateMerit <= merit * (1.0 - ARMIJO_COEFFICIENT * step)) {
                    return new AcceptedTrial(candidate, candidateCoordinates, candidateMerit, step);
                }
            } catch (IllegalArgumentException | V3ThermoException ignored) {
                // An inadmissible coordinate or PR-domain trial is an ordinary rejected line-search point.
            }
        }
        return null;
    }

    private static double[] normalizedNegativeGradient(
            V3FiniteDifferenceJacobian.Jacobian jacobian, V3MeshResidual residual, V3DryMeshCoordinateMap coordinates) {
        double[][] values = jacobian.values();
        double[] direction = new double[values[0].length];
        for (int row = 0; row < values.length; row++) {
            double scaledResidual = residual.rows().get(row).scaledValue();
            for (int column = 0; column < direction.length; column++) direction[column] -= values[row][column] * scaledResidual;
        }
        double maximumLogFlowMagnitude = 0.0;
        double maximumTemperatureMagnitude = 0.0;
        for (int index = 0; index < direction.length; index++) {
            if (coordinates.unknowns().get(index).id().family() == V3DegreeOfFreedomLedger.UnknownFamily.TEMPERATURE) {
                maximumTemperatureMagnitude = Math.max(maximumTemperatureMagnitude, Math.abs(direction[index]));
            } else {
                maximumLogFlowMagnitude = Math.max(maximumLogFlowMagnitude, Math.abs(direction[index]));
            }
        }
        for (int index = 0; index < direction.length; index++) {
            if (coordinates.unknowns().get(index).id().family() == V3DegreeOfFreedomLedger.UnknownFamily.TEMPERATURE) {
                if (maximumTemperatureMagnitude > 0.0) {
                    direction[index] *= FALLBACK_MAXIMUM_TEMPERATURE_CHANGE_KELVIN / maximumTemperatureMagnitude;
                }
            } else if (maximumLogFlowMagnitude > 0.0) {
                direction[index] *= FALLBACK_MAXIMUM_LOG_FLOW_CHANGE / maximumLogFlowMagnitude;
            }
        }
        return direction;
    }

    private static AcceptedTrial dampedGaussNewtonTrial(
            V3MeshResidualEvaluator evaluator, V3DryMeshCoordinateMap coordinates, double[] baseCoordinates,
            V3FiniteDifferenceJacobian.Jacobian jacobian, V3MeshResidual residual, double merit,
            V3StageBlockLayout layout, V3FiniteDifferenceJacobian.V3ThermoWorkspaceFactory workspaceFactory,
            int maximumLineSearchSteps) {
        double damping = INITIAL_GAUSS_NEWTON_DAMPING;
        for (int attempt = 0; attempt < MAXIMUM_GAUSS_NEWTON_DAMPING_STEPS; attempt++) {
            V3BandedPivotedSolver.Result result = V3BandedPivotedSolver.solve(
                    dampedNormalMatrix(jacobian, layout, damping), negativeGradient(residual, jacobian));
            if (result instanceof V3BandedPivotedSolver.Result.Success success) {
                AcceptedTrial trial = armijoTrial(
                        evaluator, coordinates, baseCoordinates, success.solution(), merit, workspaceFactory,
                        maximumLineSearchSteps);
                if (trial != null) return trial;
            }
            damping *= 10.0;
        }
        return null;
    }

    private static V3BandedMatrix dampedNormalMatrix(
            V3FiniteDifferenceJacobian.Jacobian jacobian, V3StageBlockLayout layout, double damping) {
        double[][] values = jacobian.values();
        double[][] normal = new double[values.length][values.length];
        for (int row = 0; row < normal.length; row++) {
            for (int column = row; column < normal.length; column++) {
                double value = 0.0;
                for (double[] jacobianRow : values) value += jacobianRow[row] * jacobianRow[column];
                normal[row][column] = value;
                normal[column][row] = value;
            }
            normal[row][row] += damping * Math.max(1.0, normal[row][row]);
        }
        return stageBandedMatrix(normal, layout, 2, "V3 damped normal matrix contains an unexpected off-band coupling");
    }

    private static double[] negativeGradient(V3MeshResidual residual, V3FiniteDifferenceJacobian.Jacobian jacobian) {
        double[][] values = jacobian.values();
        double[] gradient = new double[values[0].length];
        for (int row = 0; row < values.length; row++) {
            double scaledResidual = residual.rows().get(row).scaledValue();
            for (int column = 0; column < gradient.length; column++) gradient[column] -= values[row][column] * scaledResidual;
        }
        return gradient;
    }

    private static V3ConvergenceEvidence convergenceEvidence(
            V3DryMeshCoordinateMap coordinates, double[] baseCoordinates, double[] candidateCoordinates,
            double backwardError) {
        double maximumLogFlowChange = 0.0;
        double maximumTemperatureChange = 0.0;
        double maximumTemperatureStepRatio = 0.0;
        for (int index = 0; index < candidateCoordinates.length; index++) {
            double change = Math.abs(candidateCoordinates[index] - baseCoordinates[index]);
            V3DegreeOfFreedomLedger.UnknownFamily family = coordinates.unknowns().get(index).id().family();
            if (family == V3DegreeOfFreedomLedger.UnknownFamily.TEMPERATURE) {
                maximumTemperatureChange = Math.max(maximumTemperatureChange, change);
                double limit = 1.0e-6 + 1.0e-9 * candidateCoordinates[index];
                maximumTemperatureStepRatio = Math.max(maximumTemperatureStepRatio, change / limit);
            } else {
                maximumLogFlowChange = Math.max(maximumLogFlowChange, change);
            }
        }
        return new V3ConvergenceEvidence(true, backwardError, maximumLogFlowChange, maximumTemperatureChange,
                maximumTemperatureStepRatio);
    }

    private record AcceptedTrial(V3DryMeshState state, double[] coordinates, double merit, double step) {}

    sealed interface Attempt permits Attempt.Converged, Attempt.Failure {
        V3DryMeshState state();
        Evidence evidence();

        record Converged(V3DryMeshState state, Evidence evidence) implements Attempt {
            public Converged {
                state = Objects.requireNonNull(state, "state");
                evidence = Objects.requireNonNull(evidence, "evidence");
            }
        }

        record Failure(String code, V3DryMeshState state, Evidence evidence) implements Attempt {
            public Failure {
                if (code == null || code.isBlank() || code.length() > 64) throw new IllegalArgumentException("V3 Newton failure code is invalid");
                state = Objects.requireNonNull(state, "state");
                evidence = Objects.requireNonNull(evidence, "evidence");
            }
        }
    }

    record Evidence(
            int iterations, double maximumScaledResidual, double scaledMerit, double acceptedStep,
            double pivotGrowth, String termination, V3ConvergenceEvidence convergenceEvidence) {
        Evidence {
            if (iterations < 0 || !Double.isFinite(maximumScaledResidual) || maximumScaledResidual < 0.0
                    || !Double.isFinite(scaledMerit) || scaledMerit < 0.0 || !Double.isFinite(acceptedStep)
                    || acceptedStep < 0.0 || !Double.isFinite(pivotGrowth) || pivotGrowth < 0.0
                    || termination == null || termination.isBlank() || termination.length() > 256
                    || convergenceEvidence == null) {
                throw new IllegalArgumentException("V3 Newton evidence is invalid");
            }
        }
    }
}
