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
    private static final int MAXIMUM_FROZEN_FINE_JACOBIAN_STEPS = 1;
    private static final int UNLIMITED_LOCAL_BLOCK_ATTEMPTS = Integer.MAX_VALUE;
    private static final int LOCAL_BLOCK_JACOBIAN_MINIMUM_COORDINATES = 96;
    private static final double FALLBACK_MAXIMUM_LOG_FLOW_CHANGE = 0.25;
    private static final double FALLBACK_MAXIMUM_TEMPERATURE_CHANGE_KELVIN = 10.0;
    private static final double INITIAL_GAUSS_NEWTON_DAMPING = 1.0e-8;
    private static final int MAXIMUM_GAUSS_NEWTON_DAMPING_STEPS = 8;
    private static final String NEWTON_OFF_BAND_MESSAGE =
            "V3 Newton Jacobian contains an unexpected off-band coupling";
    private static final String DAMPED_NORMAL_OFF_BAND_MESSAGE =
            V3NormalEquations.OFF_BAND_MESSAGE;

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
        return solve(problem, evaluator, coordinates, initialState, workspaceFactory, initialConvergenceEvidence,
                maximumIterations, scaledTolerance, differenceScale, control, trace, 0, 0, RungBudget.DEFAULT);
    }

    /**
     * Uses stage-local block directions between fresh fine finite-difference Jacobians for a close stage-grid seed.
     *
     * <p>Each local step remains Armijo-gated. Rejected local directions fall through to the authoritative full
     * coloured finite-difference correction; final convergence certification remains fresh Newton.</p>
     */
    static Attempt solveWithContinuationLocalBlocks(
            V3ColumnProblem problem,
            V3MeshResidualEvaluator evaluator,
            V3DryMeshCoordinateMap coordinates,
            V3DryMeshState initialState,
            V3FiniteDifferenceJacobian.V3ThermoWorkspaceFactory workspaceFactory,
            int maximumIterations,
            double scaledTolerance,
            V3SolveControl control) {
        return solveWithContinuationLocalBlocks(problem, evaluator, coordinates, initialState, workspaceFactory,
                maximumIterations, scaledTolerance, control, V3NewtonTrace.NONE);
    }

    static Attempt solveWithContinuationLocalBlocks(
            V3ColumnProblem problem,
            V3MeshResidualEvaluator evaluator,
            V3DryMeshCoordinateMap coordinates,
            V3DryMeshState initialState,
            V3FiniteDifferenceJacobian.V3ThermoWorkspaceFactory workspaceFactory,
            int maximumIterations,
            double scaledTolerance,
            V3SolveControl control,
            V3NewtonTrace trace) {
        return solveWithContinuationLocalBlocks(problem, evaluator, coordinates, initialState, workspaceFactory,
                maximumIterations, scaledTolerance, control, trace, RungBudget.DEFAULT);
    }

    static Attempt solveWithContinuationLocalBlocks(
            V3ColumnProblem problem,
            V3MeshResidualEvaluator evaluator,
            V3DryMeshCoordinateMap coordinates,
            V3DryMeshState initialState,
            V3FiniteDifferenceJacobian.V3ThermoWorkspaceFactory workspaceFactory,
            int maximumIterations,
            double scaledTolerance,
            V3SolveControl control,
            V3NewtonTrace trace,
            RungBudget budget) {
        return solve(problem, evaluator, coordinates, initialState, workspaceFactory, V3ConvergenceEvidence.unavailable(),
                maximumIterations, scaledTolerance, V3FiniteDifferenceJacobian.DifferenceScale.FINE,
                control, trace, MAXIMUM_FROZEN_FINE_JACOBIAN_STEPS, UNLIMITED_LOCAL_BLOCK_ATTEMPTS, budget);
    }

    /** Uses exactly one local block predictor before the full finite-difference pressure-leg corrector. */
    static Attempt solveWithOneLocalBlockPredictor(
            V3ColumnProblem problem,
            V3MeshResidualEvaluator evaluator,
            V3DryMeshCoordinateMap coordinates,
            V3DryMeshState initialState,
            V3FiniteDifferenceJacobian.V3ThermoWorkspaceFactory workspaceFactory,
            int maximumIterations,
            double scaledTolerance,
            V3SolveControl control,
            V3NewtonTrace trace) {
        return solve(problem, evaluator, coordinates, initialState, workspaceFactory, V3ConvergenceEvidence.unavailable(),
                maximumIterations, scaledTolerance, V3FiniteDifferenceJacobian.DifferenceScale.FINE,
                control, trace, MAXIMUM_FROZEN_FINE_JACOBIAN_STEPS, 1, RungBudget.DEFAULT);
    }

    private static Attempt solve(
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
            V3NewtonTrace trace,
            int maximumFrozenFineJacobianSteps,
            int maximumLocalBlockAttempts,
            RungBudget budget) {
        problem = Objects.requireNonNull(problem, "problem");
        evaluator = Objects.requireNonNull(evaluator, "evaluator");
        coordinates = Objects.requireNonNull(coordinates, "coordinates");
        initialState = Objects.requireNonNull(initialState, "initialState");
        workspaceFactory = Objects.requireNonNull(workspaceFactory, "workspaceFactory");
        initialConvergenceEvidence = Objects.requireNonNull(initialConvergenceEvidence, "initialConvergenceEvidence");
        differenceScale = Objects.requireNonNull(differenceScale, "differenceScale");
        control = Objects.requireNonNull(control, "control");
        trace = Objects.requireNonNull(trace, "trace");
        budget = Objects.requireNonNull(budget, "budget");
        if (maximumIterations < 1 || !Double.isFinite(scaledTolerance) || scaledTolerance <= 0.0
                || maximumFrozenFineJacobianSteps < 0
                || maximumFrozenFineJacobianSteps > MAXIMUM_FROZEN_FINE_JACOBIAN_STEPS
                || maximumLocalBlockAttempts < 0) {
            throw new IllegalArgumentException("V3 Newton solve limits are invalid");
        }
        V3StageBlockLayout layout = new V3StageBlockLayout(problem);
        V3BandedPivotedSolver.Workspace linearWorkspace = new V3BandedPivotedSolver.Workspace();
        int maximumLineSearchSteps = differenceScale == V3FiniteDifferenceJacobian.DifferenceScale.COARSE
                ? COARSE_RECOVERY_MAXIMUM_LINE_SEARCH_STEPS : FINE_MAXIMUM_LINE_SEARCH_STEPS;
        // Only a budget that asks for the stall stop pays for its history; null is the frozen default path.
        double[] stallResiduals = budget.stallWindow() > 0 ? new double[maximumIterations + 1] : null;
        V3DryMeshState state = initialState;
        double lastMerit = Double.NaN;
        V3ConvergenceEvidence lastConvergenceEvidence = initialConvergenceEvidence;
        V3FiniteDifferenceJacobian.Jacobian cachedFineJacobian = null;
        int frozenFineJacobianSteps = 0;
        int localBlockAttempts = 0;
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
            // Every merit comparison inside one iteration is taken against this state's row scales. The
            // convergence gate above uses each state's own scales, as the independent acceptance audit does.
            double[] frozenScales = residual.scales();
            double merit = scaledSquaredNorm(residual);
            trace.sampledState(iteration, state, residual, merit);
            if (maximumResidual <= scaledTolerance && lastConvergenceEvidence.satisfiesGates(scaledTolerance)) {
                return new Attempt.Converged(state, new Evidence(iteration, maximumResidual, merit, 0.0, 0.0,
                        "residual and final-step tolerances", lastConvergenceEvidence));
            }
            if (maximumResidual <= scaledTolerance) {
                VerifiedFinalNewton verified = verifyFinalNewtonCorrection(
                        evaluator, coordinates, state, residual, merit, workspaceFactory, layout, control,
                        scaledTolerance, budget, linearWorkspace);
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
            // A rung whose residual has not fallen by the budget's factor over its window is not converging;
            // spending the rest of the iteration budget on it only delays the caller's own recovery. The floor
            // keeps this away from a rung that is merely closing slowly just above its tolerance.
            if (stallResiduals != null) {
                stallResiduals[iteration] = maximumResidual;
                int earlier = iteration - budget.stallWindow();
                if (earlier >= 0 && maximumResidual > budget.stallResidualFloor()
                        && maximumResidual > budget.stallFactor() * stallResiduals[earlier]) {
                    return new Attempt.Failure("STALLED", state, new Evidence(iteration, maximumResidual, merit,
                            0.0, 0.0, "maximum scaled residual " + stallResiduals[earlier] + " at iteration "
                                    + earlier + " and " + maximumResidual + " now", lastConvergenceEvidence));
                }
            }
            if (maximumLocalBlockAttempts > localBlockAttempts
                    && differenceScale == V3FiniteDifferenceJacobian.DifferenceScale.FINE
                    && coordinates.coordinateCount() >= LOCAL_BLOCK_JACOBIAN_MINIMUM_COORDINATES) {
                localBlockAttempts++;
                boolean localBlockAccepted = false;
                try {
                    V3BlockJacobian localJacobian = V3BlockJacobianAssembler.assembleLocal(
                            problem, evaluator, coordinates, state, workspaceFactory, differenceScale, control);
                    V3BandedPivotedSolver.Result localLinear = V3BandedPivotedSolver.solve(
                            localJacobian.toBandedMatrix(linearWorkspace), negativeScaledResidual(residual), linearWorkspace);
                    if (localLinear instanceof V3BandedPivotedSolver.Result.Success localSuccess) {
                        double[] baseCoordinates = coordinates.encode(state);
                        AcceptedTrial localTrial = armijoTrial(
                                evaluator, coordinates, baseCoordinates, localSuccess.solution(), frozenScales, merit, workspaceFactory,
                                maximumLineSearchSteps, control);
                        if (localTrial != null) {
                            localBlockAccepted = true;
                            trace.localBlockDirection(iteration, true);
                            state = localTrial.state();
                            lastMerit = localTrial.merit();
                            cachedFineJacobian = null;
                            frozenFineJacobianSteps = 0;
                            lastConvergenceEvidence = V3ConvergenceEvidence.unavailable(V3ConvergenceEvidence.closureOf(scaledTolerance));
                            continue;
                        }
                    }
                } catch (IllegalArgumentException | V3ThermoException localUnavailable) {
                    // The full coloured finite-difference Jacobian remains the authoritative fallback when a local
                    // one-sided PR probe crosses a property/root boundary.
                }
                if (!localBlockAccepted) trace.localBlockDirection(iteration, false);
            }
            boolean usesFrozenFineJacobian = differenceScale == V3FiniteDifferenceJacobian.DifferenceScale.FINE
                    && cachedFineJacobian != null && frozenFineJacobianSteps < maximumFrozenFineJacobianSteps;
            V3FiniteDifferenceJacobian.Jacobian jacobian = usesFrozenFineJacobian
                    ? cachedFineJacobian
                    : V3FiniteDifferenceJacobian.evaluateCompact(
                            evaluator, coordinates, state, workspaceFactory, differenceScale, control);
            trace.finiteDifferenceJacobian(iteration, usesFrozenFineJacobian);
            if (!usesFrozenFineJacobian && differenceScale == V3FiniteDifferenceJacobian.DifferenceScale.FINE) {
                cachedFineJacobian = jacobian;
                frozenFineJacobianSteps = 0;
            }
            V3BandedPivotedSolver.Result linearResult;
            try {
                linearResult = V3BandedPivotedSolver.solve(
                        toBandedMatrix(jacobian, layout, linearWorkspace), negativeScaledResidual(residual), linearWorkspace);
            } catch (IllegalStateException unavailable) {
                if (usesFrozenFineJacobian) {
                    cachedFineJacobian = null;
                    frozenFineJacobianSteps = 0;
                    iteration--;
                    continue;
                }
                if (!NEWTON_OFF_BAND_MESSAGE.equals(unavailable.getMessage())) throw unavailable;
                return new Attempt.Failure("JACOBIAN_BAND_STRUCTURE", state,
                        new Evidence(iteration, maximumResidual, merit, 0.0, 0.0, unavailable.getMessage(),
                                lastConvergenceEvidence));
            }
            if (!(linearResult instanceof V3BandedPivotedSolver.Result.Success linearSuccess)) {
                if (usesFrozenFineJacobian) {
                    cachedFineJacobian = null;
                    frozenFineJacobianSteps = 0;
                    iteration--;
                    continue;
                }
                V3BandedPivotedSolver.Result.Failure failure = (V3BandedPivotedSolver.Result.Failure) linearResult;
                double[] baseCoordinates = coordinates.encode(state);
                AcceptedTrial descentTrial = dampedGaussNewtonTrial(
                        evaluator, coordinates, baseCoordinates, jacobian, residual, frozenScales, merit, layout, workspaceFactory,
                        maximumLineSearchSteps, control, budget, linearWorkspace);
                if (descentTrial == null && budget.gradientFallback()) {
                    descentTrial = armijoTrial(evaluator, coordinates, baseCoordinates,
                            normalizedNegativeGradient(jacobian, residual, coordinates), frozenScales, merit, workspaceFactory,
                            maximumLineSearchSteps, control);
                }
                if (descentTrial == null) {
                    return new Attempt.Failure("LINEAR_" + failure.code(), state,
                            new Evidence(iteration, maximumResidual, merit, 0.0, failure.pivotGrowth(), failure.detail(),
                                    lastConvergenceEvidence));
                }
                state = descentTrial.state();
                lastMerit = descentTrial.merit();
                lastConvergenceEvidence = V3ConvergenceEvidence.unavailable(V3ConvergenceEvidence.closureOf(scaledTolerance));
                continue;
            }
            double[] baseCoordinates = coordinates.encode(state);
            AcceptedTrial acceptedTrial = armijoTrial(
                    evaluator, coordinates, baseCoordinates, linearSuccess.solution(), frozenScales, merit, workspaceFactory,
                    maximumLineSearchSteps, control);
            boolean usedDescentFallback = acceptedTrial == null;
            if (usedDescentFallback && usesFrozenFineJacobian) {
                cachedFineJacobian = null;
                frozenFineJacobianSteps = 0;
                iteration--;
                continue;
            }
            if (usedDescentFallback) {
                acceptedTrial = dampedGaussNewtonTrial(
                        evaluator, coordinates, baseCoordinates, jacobian, residual, frozenScales, merit, layout, workspaceFactory,
                        maximumLineSearchSteps, control, budget, linearWorkspace);
                if (acceptedTrial == null && budget.gradientFallback()) {
                    acceptedTrial = armijoTrial(evaluator, coordinates, baseCoordinates,
                            normalizedNegativeGradient(jacobian, residual, coordinates), frozenScales, merit, workspaceFactory,
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
            if (usedDescentFallback) {
                cachedFineJacobian = null;
                frozenFineJacobianSteps = 0;
                lastConvergenceEvidence = V3ConvergenceEvidence.unavailable(V3ConvergenceEvidence.closureOf(scaledTolerance));
            } else if (usesFrozenFineJacobian) {
                frozenFineJacobianSteps++;
                lastConvergenceEvidence = V3ConvergenceEvidence.unavailable(V3ConvergenceEvidence.closureOf(scaledTolerance));
            } else {
                lastConvergenceEvidence = convergenceEvidence(
                        coordinates, baseCoordinates, acceptedTrial.coordinates(), linearSuccess.backwardError(),
                        scaledTolerance);
            }
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
     *
     * <p>The damped cascade behind the direct correction is what a rung whose certificate must be published pays
     * for. A rung whose caller only wants its state as the next rung's seed does not need a certificate at all,
     * so its budget may set {@code verificationDampingSteps} to zero: the direct fine correction is still tried,
     * and when it fails the gates the ordinary iteration simply continues, exactly as it does today whenever the
     * whole cascade fails.</p>
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
            double scaledTolerance,
            RungBudget budget, V3BandedPivotedSolver.Workspace linearWorkspace) {
        try {
            control.checkpoint();
            V3FiniteDifferenceJacobian.Jacobian jacobian = V3FiniteDifferenceJacobian.evaluateCompact(
                    evaluator, coordinates, state, workspaceFactory, V3FiniteDifferenceJacobian.DifferenceScale.FINE, control);
            V3BandedPivotedSolver.Result linear = V3BandedPivotedSolver.solve(
                    toBandedMatrix(jacobian, layout, linearWorkspace), negativeScaledResidual(residual), linearWorkspace);
            if (linear instanceof V3BandedPivotedSolver.Result.Success success) {
                VerifiedFinalNewton direct = verifiedCandidate(evaluator, coordinates, state, workspaceFactory,
                        scaledTolerance, residual.maximumAbsoluteScaledResidual(), true,
                        merit, success.solution(), success.backwardError());
                if (direct != null) return direct;
            }
            if (budget.verificationDampingSteps() == 0) return null;
            V3NormalEquations normal = V3NormalEquations.prepare(jacobian, residual, layout, control);
            double damping = INITIAL_GAUSS_NEWTON_DAMPING;
            for (int attempt = 0; attempt < budget.verificationDampingSteps(); attempt++) {
                control.checkpoint();
                V3BandedPivotedSolver.Result regularized = V3BandedPivotedSolver.solve(
                        normal.dampedMatrix(damping, control, linearWorkspace), normal.negativeGradient(), linearWorkspace);
                if (regularized instanceof V3BandedPivotedSolver.Result.Success success) {
                    VerifiedFinalNewton verified = verifiedCandidate(evaluator, coordinates, state, workspaceFactory,
                            scaledTolerance, residual.maximumAbsoluteScaledResidual(), false,
                            merit, success.solution(), success.backwardError());
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
            double maximumBaseResidual,
            boolean directCorrection,
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
                coordinates, baseCoordinates, candidateCoordinates, backwardError, scaledTolerance);
        if (!verificationCandidateAcceptable(merit, candidateMerit, maximumBaseResidual, maximumResidual,
                scaledTolerance, directCorrection, evidence)) {
            return null;
        }
        return new VerifiedFinalNewton(candidate, maximumResidual, candidateMerit, 1.0, backwardError, evidence);
    }

    /**
     * A direct, fresh correction may move within a fixed near-root residual ceiling even when its
     * squared merit increases. This bounds every native scaled row, not just the aggregate norm.
     * The ceiling is four orders below the default closure and never widens a tighter request.
     * Regularized corrections still require non-increasing merit; publication requires the independent audit.
     */
    static boolean verificationCandidateAcceptable(
            double baseMerit, double candidateMerit, double maximumBaseResidual, double maximumCandidateResidual,
            double scaledTolerance, boolean directCorrection, V3ConvergenceEvidence evidence) {
        if (!Double.isFinite(baseMerit) || !Double.isFinite(candidateMerit)
                || !Double.isFinite(maximumBaseResidual) || !Double.isFinite(maximumCandidateResidual)
                || maximumCandidateResidual > scaledTolerance || !evidence.satisfiesGates(scaledTolerance)) {
            return false;
        }
        double nearRootCeiling = Math.min(1.0e-12, scaledTolerance);
        return candidateMerit <= baseMerit || directCorrection
                && maximumBaseResidual <= nearRootCeiling && maximumCandidateResidual <= nearRootCeiling;
    }

    /** Keeps every coefficient in the declared adjacent-stage coupling, independent of its magnitude. */
    static V3BandedMatrix toBandedMatrix(
            V3FiniteDifferenceJacobian.Jacobian jacobian, V3StageBlockLayout layout) {
        return toBandedMatrix(jacobian, layout, null);
    }

    private static V3BandedMatrix toBandedMatrix(
            V3FiniteDifferenceJacobian.Jacobian jacobian, V3StageBlockLayout layout,
            V3BandedPivotedSolver.Workspace workspace) {
        int lastNode = layout.nodeCount() - 1;
        int size = layout.start(lastNode) + layout.size(lastNode);
        if (jacobian.unknowns().size() != size) {
            throw new IllegalArgumentException("V3 Newton Jacobian does not match its stage layout");
        }
        int lowerBandwidth = 0;
        int upperBandwidth = 0;
        for (int node = 0; node <= lastNode; node++) {
            int firstCoupled = layout.start(Math.max(0, node - 1));
            int lastCoupledNode = Math.min(lastNode, node + 1);
            int coupledEnd = layout.start(lastCoupledNode) + layout.size(lastCoupledNode);
            lowerBandwidth = Math.max(lowerBandwidth, layout.start(node) + layout.size(node) - 1 - firstCoupled);
            upperBandwidth = Math.max(upperBandwidth, coupledEnd - 1 - layout.start(node));
        }
        V3BandedMatrix matrix = workspace == null ? new V3BandedMatrix(size, lowerBandwidth, upperBandwidth)
                : workspace.clearedMatrix(size, lowerBandwidth, upperBandwidth);
        for (int node = 0; node <= lastNode; node++) {
            int firstCoupled = layout.start(Math.max(0, node - 1));
            int lastCoupledNode = Math.min(lastNode, node + 1);
            int coupledEnd = layout.start(lastCoupledNode) + layout.size(lastCoupledNode);
            int rowEnd = layout.start(node) + layout.size(node);
            for (int row = layout.start(node); row < rowEnd; row++) {
                for (int column = jacobian.firstStoredColumn(row); column < jacobian.storedColumnEnd(row); column++) {
                    double value = jacobian.value(row, column);
                    if (column >= firstCoupled && column < coupledEnd) {
                        matrix.set(row, column, value);
                    } else if (Math.abs(value) > OFF_BAND_TOLERANCE) {
                        throw new IllegalStateException(NEWTON_OFF_BAND_MESSAGE);
                    }
                    // Nonadjacent stages have no physical coupling; only their FD noise is discarded.
                }
            }
        }
        return matrix;
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

    /** Merit of a candidate state measured with the scales of the state the Jacobian was assembled at. */
    private static double frozenSquaredNorm(V3MeshResidual residual, double[] frozenScales) {
        double norm = 0.0;
        for (int row = 0; row < frozenScales.length; row++) {
            double scaled = residual.rows().get(row).physicalValue() / frozenScales[row];
            norm += scaled * scaled;
        }
        return norm;
    }

    private static double[] addScaled(double[] base, double[] correction, double step) {
        double[] candidate = base.clone();
        for (int index = 0; index < candidate.length; index++) candidate[index] += step * correction[index];
        return candidate;
    }

    /**
     * Armijo backtracking against the base state's row scales.
     *
     * <p>{@code merit} is the base merit under {@code frozenScales}, and every candidate is measured with the
     * same scales, so the sufficient-decrease test compares like with like on the system the Jacobian was
     * assembled for. Re-scaling each candidate by its own local throughput would let a step be accepted for
     * enlarging a denominator instead of closing a balance.</p>
     */
    private static AcceptedTrial armijoTrial(
            V3MeshResidualEvaluator evaluator, V3DryMeshCoordinateMap coordinates, double[] baseCoordinates,
            double[] direction, double[] frozenScales, double merit,
            V3FiniteDifferenceJacobian.V3ThermoWorkspaceFactory workspaceFactory,
            int maximumLineSearchSteps, V3SolveControl control) {
        for (int lineSearch = 0; lineSearch < maximumLineSearchSteps; lineSearch++) {
            control.checkpoint();
            double step = Math.scalb(1.0, -lineSearch);
            try {
                double[] candidateCoordinates = addScaled(baseCoordinates, direction, step);
                V3DryMeshState candidate = coordinates.decode(candidateCoordinates);
                double candidateMerit = frozenSquaredNorm(
                        evaluator.evaluate(candidate, workspaceFactory.newWorkspace()), frozenScales);
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
        double[] direction = new double[jacobian.unknowns().size()];
        for (int row = 0; row < direction.length; row++) {
            double scaledResidual = residual.rows().get(row).scaledValue();
            int first = Double.isFinite(scaledResidual) ? jacobian.firstStoredColumn(row) : 0;
            int end = Double.isFinite(scaledResidual) ? jacobian.storedColumnEnd(row) : direction.length;
            for (int column = first; column < end; column++) {
                direction[column] -= jacobian.value(row, column) * scaledResidual;
            }
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
            V3FiniteDifferenceJacobian.Jacobian jacobian, V3MeshResidual residual, double[] frozenScales, double merit,
            V3StageBlockLayout layout, V3FiniteDifferenceJacobian.V3ThermoWorkspaceFactory workspaceFactory,
            int maximumLineSearchSteps, V3SolveControl control, RungBudget budget,
            V3BandedPivotedSolver.Workspace linearWorkspace) {
        if (budget.maximumDampingSteps() == 0) return null;
        V3NormalEquations normal;
        try {
            normal = V3NormalEquations.prepare(jacobian, residual, layout, control);
        } catch (IllegalStateException unavailable) {
            if (!DAMPED_NORMAL_OFF_BAND_MESSAGE.equals(unavailable.getMessage())) throw unavailable;
            // Preserve the optional fallback's existing off-band rejection before trying gradient descent.
            return null;
        }
        double damping = INITIAL_GAUSS_NEWTON_DAMPING;
        for (int attempt = 0; attempt < budget.maximumDampingSteps(); attempt++) {
            control.checkpoint();
            V3BandedPivotedSolver.Result result;
            try {
                result = V3BandedPivotedSolver.solve(
                        normal.dampedMatrix(damping, control, linearWorkspace), normal.negativeGradient(), linearWorkspace);
            } catch (IllegalStateException unavailable) {
                if (!DAMPED_NORMAL_OFF_BAND_MESSAGE.equals(unavailable.getMessage())) throw unavailable;
                // The normal-equation fallback is optional. Its finite-difference off-band noise must not turn a
                // primary Newton failure into an INTERNAL_ERROR; the caller will still try gradient descent.
                return null;
            }
            if (result instanceof V3BandedPivotedSolver.Result.Success success) {
                AcceptedTrial trial = armijoTrial(
                        evaluator, coordinates, baseCoordinates, success.solution(), frozenScales, merit, workspaceFactory,
                        maximumLineSearchSteps, control);
                if (trial != null) return trial;
            }
            damping *= 10.0;
        }
        return null;
    }

    private static V3ConvergenceEvidence convergenceEvidence(
            V3DryMeshCoordinateMap coordinates, double[] baseCoordinates, double[] candidateCoordinates,
            double backwardError, double scaledTolerance) {
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
                maximumTemperatureStepRatio, V3ConvergenceEvidence.closureOf(scaledTolerance));
    }

    /**
     * Newton budget of one continuation rung.
     *
     * <p>{@link #DEFAULT} is the only budget a published attempt may run under: the full damped normal-equation
     * cascade, the gradient fallback behind it, the full verification cascade, and no stall stop. A continuation
     * rung whose failure the caller can absorb — it keeps its predecessor's state and skips ahead — pays for
     * those fallbacks without needing them, so the caller may hand such a rung a smaller budget. Every field is
     * a bound on optional work: nothing here can make an attempt accept a step it would otherwise reject, and
     * nothing here changes the residual, evidence or acceptance gates.</p>
     *
     * @param maximumDampingSteps damped normal-equation solves tried per Newton fallback, at most
     *        {@link #MAXIMUM_GAUSS_NEWTON_DAMPING_STEPS}
     * @param gradientFallback whether the normalized steepest-descent direction is tried after them
     * @param verificationDampingSteps damped solves the final-Newton certificate may try after its direct
     *        correction fails the step gates; zero leaves the ordinary iteration to continue instead
     * @param stallWindow iterations over which the maximum scaled residual must fall by {@code stallFactor};
     *        zero disables the stall stop entirely
     * @param stallFactor required residual ratio over that window
     * @param stallResidualFloor residual below which no stall is declared, so a rung that is merely closing
     *        slowly near its tolerance is never cut off
     */
    record RungBudget(
            int maximumDampingSteps, boolean gradientFallback, int verificationDampingSteps,
            int stallWindow, double stallFactor, double stallResidualFloor) {
        static final RungBudget DEFAULT = new RungBudget(MAXIMUM_GAUSS_NEWTON_DAMPING_STEPS, true,
                MAXIMUM_GAUSS_NEWTON_DAMPING_STEPS, 0, 0.0, 0.0);

        RungBudget {
            if (maximumDampingSteps < 0 || maximumDampingSteps > MAXIMUM_GAUSS_NEWTON_DAMPING_STEPS
                    || verificationDampingSteps < 0 || verificationDampingSteps > MAXIMUM_GAUSS_NEWTON_DAMPING_STEPS
                    || stallWindow < 0 || !Double.isFinite(stallFactor) || stallFactor < 0.0 || stallFactor > 1.0
                    || !Double.isFinite(stallResidualFloor) || stallResidualFloor < 0.0) {
                throw new IllegalArgumentException("V3 Newton rung budget is invalid");
            }
        }

        /** Same fallbacks, no damped certificate cascade: for a rung whose state is a seed, not a published result. */
        RungBudget withoutVerificationCascade() {
            return new RungBudget(maximumDampingSteps, gradientFallback, 0,
                    stallWindow, stallFactor, stallResidualFloor);
        }
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
