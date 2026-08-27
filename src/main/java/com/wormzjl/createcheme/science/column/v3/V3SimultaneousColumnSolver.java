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
    private static final String NEWTON_OFF_BAND_MESSAGE =
            "V3 Newton Jacobian contains an unexpected off-band coupling";
    private static final String DAMPED_NORMAL_OFF_BAND_MESSAGE =
            "V3 damped normal matrix contains an unexpected off-band coupling";

    private V3SimultaneousColumnSolver() {}

    static Attempt solve(
            V3ColumnProblem problem, V3MeshResidualEvaluator evaluator, V3DryMeshCoordinateMap coordinates,
            V3DryMeshState initialState, V3FiniteDifferenceJacobian.V3ThermoWorkspaceFactory workspaceFactory,
            int maximumIterations, double scaledTolerance) {
        return solve(problem, evaluator, coordinates, initialState, workspaceFactory, V3ConvergenceEvidence.unavailable(),
                maximumIterations, scaledTolerance, V3FiniteDifferenceJacobian.DifferenceScale.FINE, V3SolveControl.UNBOUNDED);
    }

    static Attempt solve(
            V3ColumnProblem problem, V3MeshResidualEvaluator evaluator, V3DryMeshCoordinateMap coordinates,
            V3DryMeshState initialState, V3FiniteDifferenceJacobian.V3ThermoWorkspaceFactory workspaceFactory,
            int maximumIterations, double scaledTolerance, V3SolveControl control) {
        return solve(problem, evaluator, coordinates, initialState, workspaceFactory, V3ConvergenceEvidence.unavailable(),
                maximumIterations, scaledTolerance, V3FiniteDifferenceJacobian.DifferenceScale.FINE, control);
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
                maximumIterations, scaledTolerance, V3FiniteDifferenceJacobian.DifferenceScale.FINE, V3SolveControl.UNBOUNDED);
    }

    static Attempt solve(
            V3ColumnProblem problem, V3MeshResidualEvaluator evaluator, V3DryMeshCoordinateMap coordinates,
            V3DryMeshState initialState, V3FiniteDifferenceJacobian.V3ThermoWorkspaceFactory workspaceFactory,
            V3ConvergenceEvidence initialConvergenceEvidence, int maximumIterations, double scaledTolerance,
            V3FiniteDifferenceJacobian.DifferenceScale differenceScale) {
        return solve(problem, evaluator, coordinates, initialState, workspaceFactory, initialConvergenceEvidence,
                maximumIterations, scaledTolerance, differenceScale, V3SolveControl.UNBOUNDED);
    }

    static Attempt solve(
            V3ColumnProblem problem,
            V3MeshResidualEvaluator evaluator,
            V3DryMeshCoordinateMap coordinates,
            V3DryMeshState initialState,
            V3FiniteDifferenceJacobian.V3ThermoWorkspaceFactory workspaceFactory,
            V3ConvergenceEvidence initialConvergenceEvidence,
            int maximumIterations,
            double scaledTolerance,
            V3FiniteDifferenceJacobian.DifferenceScale differenceScale,
            V3SolveControl control) {
        return solve(problem, evaluator, coordinates, initialState, workspaceFactory, initialConvergenceEvidence,
                maximumIterations, scaledTolerance, differenceScale, control, V3NewtonTrace.NONE);
    }

    static Attempt solve(
            V3ColumnProblem problem,
            V3MeshResidualEvaluator evaluator,
            V3DryMeshCoordinateMap coordinates,
            V3DryMeshState initialState,
            V3FiniteDifferenceJacobian.V3ThermoWorkspaceFactory workspaceFactory,
            V3ConvergenceEvidence initialConvergenceEvidence,
            int maximumIterations,
            double scaledTolerance,
            V3FiniteDifferenceJacobian.DifferenceScale differenceScale,
            V3SolveControl control,
            V3NewtonTrace trace) {
        problem = Objects.requireNonNull(problem, "problem");
        evaluator = Objects.requireNonNull(evaluator, "evaluator");
        coordinates = Objects.requireNonNull(coordinates, "coordinates");
        initialState = Objects.requireNonNull(initialState, "initialState");
        workspaceFactory = Objects.requireNonNull(workspaceFactory, "workspaceFactory");
        initialConvergenceEvidence = Objects.requireNonNull(initialConvergenceEvidence, "initialConvergenceEvidence");
        differenceScale = Objects.requireNonNull(differenceScale, "differenceScale");
        control = Objects.requireNonNull(control, "control");
        trace = Objects.requireNonNull(trace, "trace");
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
            control.checkpoint();
            V3MeshResidual residual;
            try {
                residual = evaluator.evaluate(state, workspaceFactory.newWorkspace());
            } catch (IllegalArgumentException invalidState) {
                String detail = invalidState.getMessage();
                if (detail == null || detail.isBlank()) detail = "V3 MESH state is outside the logarithmic phase-flow domain";
                if (detail.length() > 192) detail = detail.substring(0, 192);
                return new Attempt.Failure("STATE_DOMAIN", state,
                        new Evidence(iteration, 0.0, 0.0, 0.0, 0.0, detail,
                                lastConvergenceEvidence));
            }
            control.checkpoint();
            double maximumResidual = residual.maximumAbsoluteScaledResidual();
            double merit = scaledSquaredNorm(residual);
            trace.sampledIteration(iteration, residual, merit);
            if (maximumResidual <= scaledTolerance && lastConvergenceEvidence.satisfiesGates()) {
                return new Attempt.Converged(state, new Evidence(iteration, maximumResidual, merit, 0.0, 0.0,
                        "residual and final-step tolerances", lastConvergenceEvidence));
            }
            if (maximumResidual <= scaledTolerance) {
                VerifiedFinalNewton verified = verifyFinalNewtonCorrection(
                        evaluator, coordinates, state, residual, merit, workspaceFactory, layout, control, scaledTolerance);
                if (verified != null) {
                    return new Attempt.Converged(verified.state(), new Evidence(iteration,
                            verified.maximumScaledResidual(), verified.merit(), verified.step(),
                            verified.backwardError(), "verified final Newton correction", verified.evidence()));
                }
            }
            if (iteration == maximumIterations) {
                return new Attempt.Failure("MAX_ITERATIONS", state,
                        new Evidence(iteration, maximumResidual, merit, 0.0, 0.0, "iteration budget exhausted",
                                lastConvergenceEvidence));
            }
            V3FiniteDifferenceJacobian.Jacobian jacobian = V3FiniteDifferenceJacobian.evaluate(
                    evaluator, coordinates, state, workspaceFactory, differenceScale, control);
            V3BandedPivotedSolver.Result linearResult;
            try {
                linearResult = V3BandedPivotedSolver.solve(
                        toBandedMatrix(jacobian, layout), negativeScaledResidual(residual));
            } catch (IllegalStateException unavailable) {
                if (!NEWTON_OFF_BAND_MESSAGE.equals(unavailable.getMessage())) throw unavailable;
                return new Attempt.Failure("JACOBIAN_BAND_STRUCTURE", state,
                        new Evidence(iteration, maximumResidual, merit, 0.0, 0.0, unavailable.getMessage(),
                                lastConvergenceEvidence));
            }
            if (!(linearResult instanceof V3BandedPivotedSolver.Result.Success linearSuccess)) {
                V3BandedPivotedSolver.Result.Failure failure = (V3BandedPivotedSolver.Result.Failure) linearResult;
                double[] baseCoordinates = coordinates.encode(state);
                AcceptedTrial descentTrial = dampedGaussNewtonTrial(
                        evaluator, coordinates, baseCoordinates, jacobian, residual, merit, layout, workspaceFactory,
                        maximumLineSearchSteps, control);
                if (descentTrial == null) {
                    descentTrial = armijoTrial(evaluator, coordinates, baseCoordinates,
                            normalizedNegativeGradient(jacobian, residual, coordinates), merit, workspaceFactory,
                            maximumLineSearchSteps, control);
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
                    maximumLineSearchSteps, control);
            boolean usedDescentFallback = acceptedTrial == null;
            if (usedDescentFallback) {
                acceptedTrial = dampedGaussNewtonTrial(
                        evaluator, coordinates, baseCoordinates, jacobian, residual, merit, layout, workspaceFactory,
                        maximumLineSearchSteps, control);
                if (acceptedTrial == null) {
                    acceptedTrial = armijoTrial(evaluator, coordinates, baseCoordinates,
                            normalizedNegativeGradient(jacobian, residual, coordinates), merit, workspaceFactory,
                            maximumLineSearchSteps, control);
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

    /**
     * Produces the required final Newton certificate when a fallback step reaches the residual gate but has no
     * Newton-step evidence of its own. The candidate is decoded, independently re-evaluated, and accepted only when
     * the actual final correction satisfies the unchanged step/backward-error gates.
     */
    private static VerifiedFinalNewton verifyFinalNewtonCorrection(
            V3MeshResidualEvaluator evaluator,
            V3DryMeshCoordinateMap coordinates,
            V3DryMeshState state,
            V3MeshResidual residual,
            double merit,
            V3FiniteDifferenceJacobian.V3ThermoWorkspaceFactory workspaceFactory,
            V3StageBlockLayout layout,
            V3SolveControl control,
            double scaledTolerance) {
        try {
            control.checkpoint();
            V3FiniteDifferenceJacobian.Jacobian jacobian = V3FiniteDifferenceJacobian.evaluate(
                    evaluator, coordinates, state, workspaceFactory, V3FiniteDifferenceJacobian.DifferenceScale.FINE, control);
            V3BandedPivotedSolver.Result linear = V3BandedPivotedSolver.solve(
                    toBandedMatrix(jacobian, layout), negativeScaledResidual(residual));
            if (linear instanceof V3BandedPivotedSolver.Result.Success success) {
                VerifiedFinalNewton direct = verifiedCandidate(evaluator, coordinates, state, workspaceFactory,
                        scaledTolerance, merit, success.solution(), success.backwardError());
                if (direct != null) return direct;
            }
            double damping = INITIAL_GAUSS_NEWTON_DAMPING;
            for (int attempt = 0; attempt < MAXIMUM_GAUSS_NEWTON_DAMPING_STEPS; attempt++) {
                control.checkpoint();
                V3BandedPivotedSolver.Result regularized = V3BandedPivotedSolver.solve(
                        dampedNormalMatrix(jacobian, layout, damping, control), negativeGradient(residual, jacobian));
                if (regularized instanceof V3BandedPivotedSolver.Result.Success success) {
                    VerifiedFinalNewton verified = verifiedCandidate(evaluator, coordinates, state, workspaceFactory,
                            scaledTolerance, merit, success.solution(), success.backwardError());
                    if (verified != null) return verified;
                }
                damping *= 10.0;
            }
            return null;
        } catch (IllegalArgumentException | V3ThermoException unavailable) {
            return null;
        }
    }

    private static VerifiedFinalNewton verifiedCandidate(
            V3MeshResidualEvaluator evaluator,
            V3DryMeshCoordinateMap coordinates,
            V3DryMeshState state,
            V3FiniteDifferenceJacobian.V3ThermoWorkspaceFactory workspaceFactory,
            double scaledTolerance,
            double merit,
            double[] correction,
            double backwardError) {
        double[] baseCoordinates = coordinates.encode(state);
        double[] candidateCoordinates = addScaled(baseCoordinates, correction, 1.0);
        V3DryMeshState candidate = coordinates.decode(candidateCoordinates);
        V3MeshResidual candidateResidual = evaluator.evaluate(candidate, workspaceFactory.newWorkspace());
        double maximumResidual = candidateResidual.maximumAbsoluteScaledResidual();
        double candidateMerit = scaledSquaredNorm(candidateResidual);
        V3ConvergenceEvidence evidence = convergenceEvidence(
                coordinates, baseCoordinates, candidateCoordinates, backwardError);
        if (maximumResidual > scaledTolerance || candidateMerit > merit || !evidence.satisfiesGates()) return null;
        return new VerifiedFinalNewton(candidate, maximumResidual, candidateMerit, 1.0, backwardError, evidence);
    }

    private static V3BandedMatrix toBandedMatrix(
            V3FiniteDifferenceJacobian.Jacobian jacobian, V3StageBlockLayout layout) {
        return stageBandedMatrix(jacobian.values(), layout, 1, NEWTON_OFF_BAND_MESSAGE);
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
            int maximumLineSearchSteps, V3SolveControl control) {
        for (int lineSearch = 0; lineSearch < maximumLineSearchSteps; lineSearch++) {
            control.checkpoint();
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
            int maximumLineSearchSteps, V3SolveControl control) {
        double damping = INITIAL_GAUSS_NEWTON_DAMPING;
        for (int attempt = 0; attempt < MAXIMUM_GAUSS_NEWTON_DAMPING_STEPS; attempt++) {
            control.checkpoint();
            V3BandedPivotedSolver.Result result;
            try {
                result = V3BandedPivotedSolver.solve(
                        dampedNormalMatrix(jacobian, layout, damping, control), negativeGradient(residual, jacobian));
            } catch (IllegalStateException unavailable) {
                if (!DAMPED_NORMAL_OFF_BAND_MESSAGE.equals(unavailable.getMessage())) throw unavailable;
                // The normal-equation fallback is optional. Its finite-difference off-band noise must not turn a
                // primary Newton failure into an INTERNAL_ERROR; the caller will still try gradient descent.
                return null;
            }
            if (result instanceof V3BandedPivotedSolver.Result.Success success) {
                AcceptedTrial trial = armijoTrial(
                        evaluator, coordinates, baseCoordinates, success.solution(), merit, workspaceFactory,
                        maximumLineSearchSteps, control);
                if (trial != null) return trial;
            }
            damping *= 10.0;
        }
        return null;
    }

    private static V3BandedMatrix dampedNormalMatrix(
            V3FiniteDifferenceJacobian.Jacobian jacobian,
            V3StageBlockLayout layout,
            double damping,
            V3SolveControl control) {
        double[][] values = jacobian.values();
        double[][] normal = new double[values.length][values.length];
        for (int row = 0; row < normal.length; row++) {
            control.checkpoint();
            for (int column = row; column < normal.length; column++) {
                double value = 0.0;
                for (double[] jacobianRow : values) value += jacobianRow[row] * jacobianRow[column];
                normal[row][column] = value;
                normal[column][row] = value;
            }
            normal[row][row] += damping * Math.max(1.0, normal[row][row]);
        }
        return stageBandedMatrix(normal, layout, 2, DAMPED_NORMAL_OFF_BAND_MESSAGE);
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

    private record VerifiedFinalNewton(
            V3DryMeshState state,
            double maximumScaledResidual,
            double merit,
            double step,
            double backwardError,
            V3ConvergenceEvidence evidence) {}

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
