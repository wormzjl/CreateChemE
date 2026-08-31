package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3FlashResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3FeedPhase;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * Stateless dry-V3 calculation façade for a fully immutable input snapshot.
 *
 * <p>This class has no Minecraft, cache, warm-state, executor, or packet dependency. A caller must arrange any
 * cancellation/deadline policy outside this direct numerical boundary. Every candidate is freshly audited before a
 * {@link V3ColumnOutcome.Success} can be returned.</p>
 */
public final class V3ColumnCalculator {
    public static final String FORMULATION_REVISION = "v3-dry-mesh-r2";
    public static final String ASSUMPTIONS_REVISION = "v3-dry-assumptions-r3";
    public static final int MAXIMUM_NEWTON_ITERATIONS = 128;
    public static final double SCALED_RESIDUAL_TOLERANCE = 1.0e-8;
    private static final double PRESSURE_CONTINUATION_TRIGGER_PASCAL = 100_000.0;
    private static final double PRESSURE_CONTINUATION_ANCHOR_PASCAL = 150_000.0;
    private static final double PRESSURE_CONTINUATION_STEP_PASCAL = 10_000.0;
    private static final double PRESSURE_CONTINUATION_FINE_STEP_PASCAL = 5_000.0;
    private static final double PRESSURE_CONTINUATION_FINE_STEP_FROM_PASCAL = 110_000.0;
    private static final int PRESSURE_CONTINUATION_CORRECTOR_MAXIMUM_ITERATIONS = 12;
    private static final int PRESSURE_CONTINUATION_RECOVERY_MAXIMUM_ITERATIONS = 24;

    private enum ContinuationJacobianPolicy {
        NONE,
        STAGE_LOCAL_BLOCKS,
        PRESSURE_LOCAL_PREDICTOR
    }

    private V3ColumnCalculator() {}

    /** Calculates one dry V3 problem without sharing mutable numerical state across callers. */
    public static V3ColumnOutcome calculate(V3ColumnInput input) {
        return calculate(input, V3SolveControl.UNBOUNDED);
    }

    /**
     * Calculates one dry V3 problem with caller-owned cooperative cancellation.
     *
     * <p>A cancellation exception from {@code control} intentionally escapes unchanged so an outer service can
     * publish its own typed deadline or cancellation completion. Direct callers that do not need cancellation use
     * {@link #calculate(V3ColumnInput)}.</p>
     */
    public static V3ColumnOutcome calculate(V3ColumnInput input, V3SolveControl control) {
        return calculate(input, control, V3ColumnInitializer.Mode.SEQUENTIAL_MATERIAL_VLE);
    }

    /** Package-private cold-start qualifier for reviewed initializer modes; production uses the sequential MESH path. */
    static V3ColumnOutcome calculate(
            V3ColumnInput input, V3SolveControl control, V3ColumnInitializer.Mode initializerMode) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(control, "control");
        Objects.requireNonNull(initializerMode, "initializerMode");
        if (initializerMode != V3ColumnInitializer.Mode.SEQUENTIAL_MATERIAL_VLE) {
            return calculateBranch(input, control, initializerMode, V3CondenserPhaseBranch.TWO_PHASE);
        }
        V3CondenserPhaseBranch preferred = preferredCondenserBranch(input, control);
        V3ColumnOutcome outcome = calculateBranch(input, control, initializerMode, preferred);
        if (outcome instanceof V3ColumnOutcome.Success
                || outcome instanceof V3ColumnOutcome.Failure failure
                && (failure.code() == V3SolverFailureCode.INVALID_INPUT
                || failure.code() == V3SolverFailureCode.PROPERTY_OUT_OF_RANGE)) return outcome;
        V3CondenserPhaseBranch alternate = preferred == V3CondenserPhaseBranch.LIQUID_ONLY
                ? V3CondenserPhaseBranch.TWO_PHASE : V3CondenserPhaseBranch.LIQUID_ONLY;
        V3ColumnOutcome alternative = calculateBranch(input, control, initializerMode, alternate);
        return alternative instanceof V3ColumnOutcome.Success ? alternative : outcome;
    }

    /** A seed flash only orders the branch attempts; the solved liquid outlet is independently audited. */
    private static V3CondenserPhaseBranch preferredCondenserBranch(V3ColumnInput input, V3SolveControl control) {
        control.checkpoint();
        try {
            V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(input.packageId());
            V3ColumnInput probeInput = input.stageCount() <= 4 ? input : withStageGeometry(input, 4);
            V3ColumnProblem probe = V3ColumnProblemResolver.resolve(probeInput, V3CondenserPhaseBranch.TWO_PHASE);
            if (V3OperatingDomainValidator.assess(probe, thermo) instanceof V3OperatingDomainValidator.Assessment.Rejected) {
                return V3CondenserPhaseBranch.TWO_PHASE;
            }
            V3DryMeshState seed = V3ColumnInitializer.initialize(probe, thermo, thermo.newWorkspace(),
                    V3ColumnInitializer.Mode.SEQUENTIAL_MATERIAL_VLE).state();
            double[] overhead = new double[input.componentBasis().componentCount()];
            for (int component = 0; component < seed.componentCount(); component++) {
                overhead[probe.activeComponentBasis().publicIndex(component)] = seed.vaporFlow(1, component);
            }
            control.checkpoint();
            V3FlashResult flash = thermo.flashTP(seed.temperatureKelvin(0), probe.nodePressurePascal(0),
                    overhead, thermo.newWorkspace());
            return flash.phase() == V3FeedPhase.LIQUID
                    ? V3CondenserPhaseBranch.LIQUID_ONLY : V3CondenserPhaseBranch.TWO_PHASE;
        } catch (V3ThermoException | IllegalArgumentException unavailableSeedFlash) {
            return V3CondenserPhaseBranch.TWO_PHASE;
        }
    }

    private static V3ColumnOutcome calculateBranch(
            V3ColumnInput input, V3SolveControl control, V3ColumnInitializer.Mode initializerMode,
            V3CondenserPhaseBranch condenserBranch) {
        input = Objects.requireNonNull(input, "input");
        control = Objects.requireNonNull(control, "control");
        initializerMode = Objects.requireNonNull(initializerMode, "initializerMode");
        List<String> advisoryEvidence = List.of();
        try {
            control.checkpoint();
            V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(input.packageId());
            advisoryEvidence = thermo.advisoryEvidence();
            V3ColumnProblem problem = V3ColumnProblemResolver.resolve(input, condenserBranch);
            V3OperatingDomainValidator.Assessment admission = V3OperatingDomainValidator.assess(problem, thermo);
            if (admission instanceof V3OperatingDomainValidator.Assessment.Rejected rejected) {
                return terminalFailure(V3SolverFailureCode.PROPERTY_OUT_OF_RANGE, rejected.detail(), "admission", advisoryEvidence);
            }
            V3InputDigest digest = V3InputDigest.of(
                    problem, FORMULATION_REVISION, thermo.datasetRevision(), ASSUMPTIONS_REVISION);
            V3SolvePass pass;
            if (initializerMode == V3ColumnInitializer.Mode.SEQUENTIAL_MATERIAL_VLE) {
                pass = requiresPressureContinuation(admission)
                        ? solveDwsimPressureContinuation(input, thermo, control, condenserBranch)
                        : solveDwsimStageContinuation(input, thermo, control, condenserBranch);
                if (!publishesSuccess(pass.attempt(), pass.audit())
                        && pass.attemptedRequestedProblem()
                        && pass.terminalStageCount() >= input.stageCount()
                        && pass.allowsFreshMaterialClosedFallback()) {
                    // The sequential material/VLE preconditioner is deliberately optional. It must not turn an
                    // otherwise solvable MESH problem into a failure; this is a fresh V3 material-closed seed,
                    // never a V1 approximation or a retained warm state.
                    V3DryMeshState fallbackSeed = V3ColumnInitializer.initialize(
                            problem, thermo, thermo.newWorkspace(), V3ColumnInitializer.Mode.MATERIAL_CLOSED).state();
                    pass = solveSingleProblem(problem, thermo, fallbackSeed,
                            control, "cold/dwsim-material-closed-fallback/fine-fd");
                    if (!publishesSuccess(pass.attempt(), pass.audit())) {
                        V3SolvePass recovered = recoverWithBubblePointProjection(
                                problem, thermo, pass.attempt().state(), control,
                                "cold/dwsim-material-closed-fallback/material-vle-recovery/fine-fd",
                                ContinuationJacobianPolicy.STAGE_LOCAL_BLOCKS, MAXIMUM_NEWTON_ITERATIONS);
                        if (publishesSuccess(recovered.attempt(), recovered.audit())
                                || recovered.attempt().evidence().maximumScaledResidual()
                                < pass.attempt().evidence().maximumScaledResidual()) {
                            pass = recovered;
                        }
                    }
                }
            } else {
                pass = solveSingleProblem(problem, thermo,
                        V3ColumnInitializer.initialize(problem, thermo, thermo.newWorkspace(), initializerMode).state(),
                        control, "cold/fine-fd");
            }
            V3SimultaneousColumnSolver.Attempt attempt = pass.attempt();
            V3AcceptanceAudit audit = pass.audit();
            String solvePath = pass.solvePath();
            if (pass.reachedRequestedProblem() && !publishesSuccess(attempt, audit)) {
                try {
                    control.checkpoint();
                    V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(
                            problem, thermo, pass.feedMolarEnthalpyJoulesPerMol());
                    V3SimultaneousColumnSolver.Attempt coarseAttempt = V3SimultaneousColumnSolver.solve(
                            problem, evaluator, new V3DryMeshCoordinateMap(problem), pass.recoverySeed(), thermo::newWorkspace,
                            V3ConvergenceEvidence.unavailable(), MAXIMUM_NEWTON_ITERATIONS, SCALED_RESIDUAL_TOLERANCE,
                            V3FiniteDifferenceJacobian.DifferenceScale.COARSE, control);
                    control.checkpoint();
                    V3AcceptanceAudit coarseAudit = audit(
                            problem, thermo, pass.feedMolarEnthalpyJoulesPerMol(), coarseAttempt.state(), control);
                    if (publishesSuccess(coarseAttempt, coarseAudit)
                            || coarseAttempt.evidence().maximumScaledResidual() < attempt.evidence().maximumScaledResidual()) {
                        attempt = coarseAttempt;
                        audit = coarseAudit;
                        solvePath = "cold/coarse-fd-recovery";
                    }
                } catch (CancellationException cancelled) {
                    throw cancelled;
                } catch (IllegalStateException unavailableRecovery) {
                    // The primary fine attempt remains a complete typed result when this optional stencil violates
                    // the band-structure guard because of finite-difference noise.
                }
            }
            if (condenserBranch == V3CondenserPhaseBranch.LIQUID_ONLY) solvePath += "/liquid-only-condenser";
            V3SolverDiagnostics diagnostics = diagnostics(attempt, audit, solvePath, pass.solverEvents());
            if (pass.reachedRequestedProblem()
                    && attempt instanceof V3SimultaneousColumnSolver.Attempt.Converged converged && audit.accepted()) {
                V3ColumnResult result = V3ColumnResult.accepted(
                        problem, digest, audit, converged.evidence().convergenceEvidence(), converged.state(), thermo);
                return new V3ColumnOutcome.Success(result, diagnostics);
            }
            if (attempt instanceof V3SimultaneousColumnSolver.Attempt.Failure failure) {
                String detail = !pass.attemptedRequestedProblem()
                        ? "DWSIM pressure continuation stalled before the requested operating point at "
                        + pass.solvePath() + " after " + failure.evidence().iterations()
                        + " Newton iterations; maximum scaled residual "
                        + failure.evidence().maximumScaledResidual() + ": " + failure.evidence().termination()
                        : pass.terminalStageCount() < input.stageCount()
                        ? "DWSIM continuation stalled at " + pass.terminalStageCount() + " stages after "
                        + failure.evidence().iterations() + " Newton iterations; maximum scaled residual "
                        + failure.evidence().maximumScaledResidual() + ": " + failure.evidence().termination()
                        : failure.evidence().termination();
                return new V3ColumnOutcome.Failure(failureCode(failure.code()), detail, diagnostics);
            }
            return new V3ColumnOutcome.Failure(V3SolverFailureCode.ACCEPTANCE_AUDIT_FAILURE,
                    "The fresh V3 acceptance audit rejected the converged candidate", diagnostics);
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (V3ThermoException thermoFailure) {
            return terminalFailure(V3SolverFailureCode.PROPERTY_OUT_OF_RANGE, thermoFailure.getMessage(), "property", advisoryEvidence);
        } catch (IllegalArgumentException invalid) {
            return terminalFailure(V3SolverFailureCode.INVALID_INPUT, invalid.getMessage(), "input", advisoryEvidence);
        } catch (IllegalStateException internal) {
            return terminalFailure(V3SolverFailureCode.INTERNAL_ERROR, internal.getMessage(), "internal", advisoryEvidence);
        }
    }

    private static boolean requiresPressureContinuation(V3OperatingDomainValidator.Assessment admission) {
        return admission instanceof V3OperatingDomainValidator.Assessment.Eligible eligible
                && eligible.minimumNodePressurePascal() <= PRESSURE_CONTINUATION_TRIGGER_PASCAL;
    }

    /**
     * Runs a bounded Wang-Henke-style stage continuation entirely within this request.
     *
     * <p>Every lower-stage solve must pass the same simultaneous-MESH and fresh-audit gates before its profile may
     * seed the next grid. The intermediate states are local variables only: neither they nor their thermodynamic
     * workspaces are retained after this calculation returns.</p>
     */
    private static V3SolvePass solveDwsimStageContinuation(
            V3ColumnInput input, V3PengRobinsonThermo thermo, V3SolveControl control,
            V3CondenserPhaseBranch condenserBranch) {
        List<Integer> stageCounts = dwsimStageCounts(input.stageCount());
        String stagePath = dwsimStagePath(stageCounts);
        V3DryMeshState previousState = null;
        V3SolvePass lastPass = null;
        for (int stageCount : stageCounts) {
            control.checkpoint();
            V3ColumnInput stageInput = stageCount == input.stageCount()
                    ? input : withStageGeometry(input, stageCount);
            V3ColumnProblem stageProblem = V3ColumnProblemResolver.resolve(stageInput, condenserBranch);
            V3DryMeshState seed = previousState == null
                    ? V3ColumnInitializer.initialize(stageProblem, thermo, thermo.newWorkspace(),
                            V3ColumnInitializer.Mode.SEQUENTIAL_MATERIAL_VLE).state()
                    : continuationSeed(stageProblem, previousState, thermo, control);
            lastPass = solveSingleProblem(stageProblem, thermo,
                    seed, control, "cold/dwsim-sequential/" + stagePath + "/fine-fd",
                    ContinuationJacobianPolicy.STAGE_LOCAL_BLOCKS);
            if (!publishesSuccess(lastPass.attempt(), lastPass.audit())) {
                boolean phaseMismatch = lastPass.attempt() instanceof V3SimultaneousColumnSolver.Attempt.Converged
                        && lastPass.audit().checks().stream().anyMatch(check -> check.family().equals("CONDENSER_PHASE")
                        && !check.passed());
                if (!phaseMismatch) {
                    lastPass = recoverDwsimContinuationStage(stageProblem, thermo, lastPass, control, stagePath, stageCount);
                }
                if (!publishesSuccess(lastPass.attempt(), lastPass.audit())) {
                    return new V3SolvePass(lastPass.attempt(), lastPass.audit(), lastPass.feedMolarEnthalpyJoulesPerMol(),
                            "cold/dwsim-sequential/" + stagePath + "/failed-stage-" + stageCount,
                            lastPass.recoverySeed(), stageCount, false, stageCount == input.stageCount(),
                            stageCount == input.stageCount(), lastPass.solverEvents());
                }
            }
            previousState = lastPass.attempt().state();
        }
        if (lastPass == null) throw new IllegalStateException("V3 DWSIM continuation has no stage grid");
        return new V3SolvePass(lastPass.attempt(), lastPass.audit(), lastPass.feedMolarEnthalpyJoulesPerMol(),
                lastPass.solvePath(), lastPass.recoverySeed(), lastPass.terminalStageCount(), true, true, false,
                lastPass.solverEvents());
    }

    /**
     * Continues a qualified 150 kPa(a) cold solution downward in bounded request-local pressure steps.
     *
     * <p>Each leg solves the complete requested-stage MESH system and independently audits it before its state may
     * seed the next leg. No accepted state survives this calculation call.</p>
     */
    private static V3SolvePass solveDwsimPressureContinuation(
            V3ColumnInput input, V3PengRobinsonThermo thermo, V3SolveControl control,
            V3CondenserPhaseBranch condenserBranch) {
        if (input.topPressurePascal() > PRESSURE_CONTINUATION_TRIGGER_PASCAL) {
            throw new IllegalArgumentException("V3 pressure continuation was requested outside its low-pressure lane");
        }
        V3ColumnInput anchorInput = withTopPressure(input, PRESSURE_CONTINUATION_ANCHOR_PASCAL);
        V3SolvePass pass = solveDwsimStageContinuation(anchorInput, thermo, control, condenserBranch);
        List<String> pressureEvents = new ArrayList<>();
        String pressurePath = dwsimPressurePath(input.topPressurePascal());
        if (!publishesSuccess(pass.attempt(), pass.audit())) {
            return new V3SolvePass(pass.attempt(), pass.audit(), pass.feedMolarEnthalpyJoulesPerMol(),
                    "cold/dwsim-pressure/" + pressurePath + "/anchor-failed", pass.recoverySeed(),
                    pass.terminalStageCount(), false, false, false,
                    mergedEvents(pressureEvents, pass.solverEvents()));
        }
        for (double pressure : dwsimPressureSteps(input.topPressurePascal())) {
            control.checkpoint();
            V3ColumnInput stepInput = withTopPressure(input, pressure);
            V3ColumnProblem stepProblem = V3ColumnProblemResolver.resolve(stepInput, condenserBranch);
            V3DryMeshState seed = pass.attempt().state();
            String stepPath = "cold/dwsim-pressure/" + pressurePath + "/top-"
                    + Math.round(pressure / 1_000.0) + "kpa/fine-fd";
            pass = solveSingleProblem(stepProblem, thermo, seed, control, stepPath,
                    ContinuationJacobianPolicy.PRESSURE_LOCAL_PREDICTOR,
                    PRESSURE_CONTINUATION_CORRECTOR_MAXIMUM_ITERATIONS);
            pressureEvents.add(pressureEvent(pressure, "predictor", pass));
            if (!publishesSuccess(pass.attempt(), pass.audit())) {
                pass = recoverWithBubblePointProjection(stepProblem, thermo, seed, control,
                        "cold/dwsim-pressure/" + pressurePath + "/top-"
                                + Math.round(pressure / 1_000.0) + "kpa/material-vle-recovery/fine-fd",
                        ContinuationJacobianPolicy.PRESSURE_LOCAL_PREDICTOR,
                        PRESSURE_CONTINUATION_RECOVERY_MAXIMUM_ITERATIONS);
                pressureEvents.add(pressureEvent(pressure, "Wang-Henke material/VLE recovery", pass));
                if (!publishesSuccess(pass.attempt(), pass.audit())) {
                    boolean attemptedRequestedProblem = Double.compare(pressure, input.topPressurePascal()) == 0;
                    return new V3SolvePass(pass.attempt(), pass.audit(), pass.feedMolarEnthalpyJoulesPerMol(),
                            "cold/dwsim-pressure/" + pressurePath + "/failed-top-"
                                    + Math.round(pressure / 1_000.0) + "kpa",
                            pass.recoverySeed(), input.stageCount(), false, attemptedRequestedProblem, false,
                            mergedEvents(pressureEvents, pass.solverEvents()));
                }
            }
        }
        return new V3SolvePass(pass.attempt(), pass.audit(), pass.feedMolarEnthalpyJoulesPerMol(),
                pass.solvePath(), pass.recoverySeed(), input.stageCount(), true, true, false,
                mergedEvents(pressureEvents, pass.solverEvents()));
    }

    /** Applies the material/VLE hand-off projection only in the qualified low-pressure operating region. */
    private static V3DryMeshState continuationSeed(
            V3ColumnProblem targetProblem,
            V3DryMeshState previousState,
            V3PengRobinsonThermo thermo,
            V3SolveControl control) {
        V3DryMeshState interpolated = interpolate(previousState, targetProblem);
        return projectedSeedOrPrevious(targetProblem, thermo, interpolated, control);
    }

    private static V3DryMeshState projectedSeedOrPrevious(
            V3ColumnProblem problem, V3PengRobinsonThermo thermo, V3DryMeshState previousState, V3SolveControl control) {
        V3SequentialPreconditioner.Result result = V3BubblePointPreconditioner.INSTANCE.prepare(
                new V3SequentialPreconditioner.Request(problem, previousState, control), thermo, thermo.newWorkspace());
        return preparedSeedOrPrevious(problem, previousState, result);
    }

    private static V3DryMeshState preparedSeedOrPrevious(
            V3ColumnProblem problem, V3DryMeshState previousState, V3SequentialPreconditioner.Result result) {
        if (result instanceof V3SequentialPreconditioner.Result.Prepared prepared
                && isLogCoordinateFeasible(problem, prepared.state())) {
            return prepared.state();
        }
        return previousState;
    }

    private static boolean isLogCoordinateFeasible(V3ColumnProblem problem, V3DryMeshState state) {
        for (int node = 0; node < state.nodeCount(); node++) {
            if (!Double.isFinite(state.temperatureKelvin(node)) || state.temperatureKelvin(node) <= 0.0) return false;
            for (int component = 0; component < state.componentCount(); component++) {
                boolean vaporPhase = problem.topology().hasVaporPhase(node);
                if (vaporPhase && (!Double.isFinite(state.vaporFlow(node, component)) || state.vaporFlow(node, component) <= 0.0)) {
                    return false;
                }
                if (!vaporPhase && state.vaporFlow(node, component) != 0.0) return false;
                boolean liquidPhase = problem.condenserComponentPhases().hasLiquid(problem.topology(), node, component);
                if (liquidPhase && (!Double.isFinite(state.liquidFlow(node, component))
                        || state.liquidFlow(node, component) <= 0.0)) {
                    return false;
                }
                if (!liquidPhase && state.liquidFlow(node, component) != 0.0) return false;
            }
        }
        return true;
    }

    /**
     * Re-establishes component material closure after a failed auxiliary continuation solve, then retries MESH.
     *
     * <p>This mirrors the DWSIM-style sequential material/VLE phase of a Wang-Henke solve. It is deliberately
     * bounded and local to the current request; the recovered state cannot be published until the unchanged
     * simultaneous solver and independent acceptance audit both pass.</p>
     */
    private static V3SolvePass recoverDwsimContinuationStage(
            V3ColumnProblem problem,
            V3PengRobinsonThermo thermo,
            V3SolvePass failedPass,
            V3SolveControl control,
            String stagePath,
        int stageCount) {
        control.checkpoint();
        return recoverWithBubblePointProjection(problem, thermo, failedPass.attempt().state(), control,
                "cold/dwsim-sequential/" + stagePath + "/material-vle-recovery-stage-" + stageCount + "/fine-fd",
                ContinuationJacobianPolicy.STAGE_LOCAL_BLOCKS,
                MAXIMUM_NEWTON_ITERATIONS);
    }

    private static V3SolvePass recoverWithBubblePointProjection(
            V3ColumnProblem problem,
            V3PengRobinsonThermo thermo,
            V3DryMeshState projectionSource,
            V3SolveControl control,
            String solvePath,
            ContinuationJacobianPolicy jacobianPolicy,
            int maximumIterations) {
        control.checkpoint();
        V3DryMeshState projected = projectedSeedOrPrevious(problem, thermo, projectionSource, control);
        return solveSingleProblem(problem, thermo, projected, control, solvePath, jacobianPolicy, maximumIterations);
    }

    private static V3SolvePass solveSingleProblem(
            V3ColumnProblem problem,
            V3PengRobinsonThermo thermo,
            V3DryMeshState seed,
            V3SolveControl control,
            String solvePath) {
        return solveSingleProblem(problem, thermo, seed, control, solvePath, ContinuationJacobianPolicy.NONE);
    }

    private static V3SolvePass solveSingleProblem(
            V3ColumnProblem problem,
            V3PengRobinsonThermo thermo,
            V3DryMeshState seed,
            V3SolveControl control,
            String solvePath,
            ContinuationJacobianPolicy jacobianPolicy) {
        return solveSingleProblem(problem, thermo, seed, control, solvePath, jacobianPolicy,
                MAXIMUM_NEWTON_ITERATIONS);
    }

    private static V3SolvePass solveSingleProblem(
            V3ColumnProblem problem,
            V3PengRobinsonThermo thermo,
            V3DryMeshState seed,
            V3SolveControl control,
            String solvePath,
            ContinuationJacobianPolicy jacobianPolicy,
            int maximumIterations) {
        if (maximumIterations < 1 || maximumIterations > MAXIMUM_NEWTON_ITERATIONS) {
            throw new IllegalArgumentException("V3 simultaneous solve iteration limit is invalid");
        }
        jacobianPolicy = Objects.requireNonNull(jacobianPolicy, "jacobianPolicy");
        control.checkpoint();
        V3FlashResult feedFlash = thermo.flashTP(problem.input().feedTemperatureKelvin(),
                problem.nodePressurePascal(problem.topology().feedTrayNumber()),
                problem.input().feedComponentMolarFlowsMolPerSecond(), thermo.newWorkspace());
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(
                problem, thermo, feedFlash.molarEnthalpyJoulesPerMol());
        SolveTelemetry telemetry = new SolveTelemetry(problem);
        V3DryMeshCoordinateMap coordinates = new V3DryMeshCoordinateMap(problem);
        V3SimultaneousColumnSolver.Attempt attempt = switch (jacobianPolicy) {
            case NONE -> V3SimultaneousColumnSolver.solve(
                    problem, evaluator, coordinates, seed, thermo::newWorkspace,
                    V3ConvergenceEvidence.unavailable(), maximumIterations, SCALED_RESIDUAL_TOLERANCE,
                    V3FiniteDifferenceJacobian.DifferenceScale.FINE, control, telemetry);
            case STAGE_LOCAL_BLOCKS -> V3SimultaneousColumnSolver.solveWithContinuationLocalBlocks(
                    problem, evaluator, coordinates, seed, thermo::newWorkspace,
                    maximumIterations, SCALED_RESIDUAL_TOLERANCE, control, telemetry);
            case PRESSURE_LOCAL_PREDICTOR -> V3SimultaneousColumnSolver.solveWithOneLocalBlockPredictor(
                    problem, evaluator, coordinates, seed, thermo::newWorkspace,
                    maximumIterations, SCALED_RESIDUAL_TOLERANCE, control, telemetry);
        };
        control.checkpoint();
        V3AcceptanceAudit audit = audit(problem, thermo, feedFlash.molarEnthalpyJoulesPerMol(), attempt.state(), control);
        return new V3SolvePass(attempt, audit, feedFlash.molarEnthalpyJoulesPerMol(), solvePath,
                seed, problem.input().stageCount(), true, true, true, telemetry.events());
    }

    private static List<Integer> dwsimStageCounts(int requestedStageCount) {
        List<Integer> result = new java.util.ArrayList<>();
        for (int stageCount : new int[] {4, 8, 15}) {
            if (stageCount < requestedStageCount) result.add(stageCount);
        }
        result.add(requestedStageCount);
        return List.copyOf(result);
    }

    private static String dwsimStagePath(List<Integer> stageCounts) {
        StringBuilder path = new StringBuilder();
        for (int index = 0; index < stageCounts.size(); index++) {
            if (index > 0) path.append('-');
            path.append(stageCounts.get(index));
        }
        return path.toString();
    }

    private static V3ColumnInput withStageGeometry(V3ColumnInput input, int stageCount) {
        int feedStage = Math.clamp((int) Math.round(
                stageCount * input.feedStageNumber() / (double) input.stageCount()), 1, stageCount);
        return new V3ColumnInput(input.schemaVersion(), input.packageId(), input.assayId(), input.componentBasis(),
                input.feedComponentMolarFlowsMolPerSecond(), input.feedTemperatureKelvin(), stageCount, feedStage,
                input.topPressurePascal(), input.stagePressureDropPascal(), input.specifications());
    }

    private static V3ColumnInput withTopPressure(V3ColumnInput input, double topPressurePascal) {
        if (!Double.isFinite(topPressurePascal) || topPressurePascal <= 0.0) {
            throw new IllegalArgumentException("V3 pressure-continuation top pressure is invalid");
        }
        return new V3ColumnInput(input.schemaVersion(), input.packageId(), input.assayId(), input.componentBasis(),
                input.feedComponentMolarFlowsMolPerSecond(), input.feedTemperatureKelvin(), input.stageCount(),
                input.feedStageNumber(), topPressurePascal, input.stagePressureDropPascal(), input.specifications());
    }

    private static List<Double> dwsimPressureSteps(double requestedTopPressurePascal) {
        List<Double> pressures = new java.util.ArrayList<>();
        double pressure = PRESSURE_CONTINUATION_ANCHOR_PASCAL;
        while (pressure > requestedTopPressurePascal) {
            double step = pressure > PRESSURE_CONTINUATION_FINE_STEP_FROM_PASCAL
                    ? PRESSURE_CONTINUATION_STEP_PASCAL : PRESSURE_CONTINUATION_FINE_STEP_PASCAL;
            pressure = Math.max(requestedTopPressurePascal, pressure - step);
            pressures.add(pressure);
        }
        return List.copyOf(pressures);
    }

    private static String dwsimPressurePath(double requestedTopPressurePascal) {
        StringBuilder path = new StringBuilder();
        path.append(Math.round(PRESSURE_CONTINUATION_ANCHOR_PASCAL / 1_000.0));
        for (double pressure : dwsimPressureSteps(requestedTopPressurePascal)) {
            path.append('-').append(Math.round(pressure / 1_000.0));
        }
        return path.toString();
    }

    private static V3DryMeshState interpolate(V3DryMeshState source, V3ColumnProblem target) {
        int nodes = target.topology().nodeCount();
        int components = source.componentCount();
        double[][] liquid = new double[nodes][components];
        double[][] vapor = new double[nodes][components];
        double[] temperatures = new double[nodes];
        for (int node = 0; node < nodes; node++) {
            double position = node * (source.nodeCount() - 1.0) / (nodes - 1.0);
            int lower = (int) Math.floor(position);
            int upper = Math.min(source.nodeCount() - 1, lower + 1);
            double fraction = position - lower;
            temperatures[node] = source.temperatureKelvin(lower)
                    + fraction * (source.temperatureKelvin(upper) - source.temperatureKelvin(lower));
            for (int component = 0; component < components; component++) {
                liquid[node][component] = source.liquidFlow(lower, component)
                        + fraction * (source.liquidFlow(upper, component) - source.liquidFlow(lower, component));
                vapor[node][component] = source.vaporFlow(lower, component)
                        + fraction * (source.vaporFlow(upper, component) - source.vaporFlow(lower, component));
            }
        }
        temperatures[target.topology().condenserNode()] = specification(
                target.input(), V3ColumnSpecification.CondenserOutletTemperature.class).kelvin();
        return new V3DryMeshState(target.topology(), components, liquid, vapor, temperatures);
    }

    private static <S extends V3ColumnSpecification> S specification(V3ColumnInput input, Class<S> type) {
        return input.specifications().stream().filter(type::isInstance).map(type::cast).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("V3 input is missing " + type.getSimpleName()));
    }

    private static V3AcceptanceAudit audit(
            V3ColumnProblem problem,
            V3PengRobinsonThermo thermo,
            double feedMolarEnthalpy,
            V3DryMeshState state,
            V3SolveControl control) {
        try {
            return new V3AcceptanceAuditor(problem, thermo, feedMolarEnthalpy).audit(state, thermo.newWorkspace(), control);
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (RuntimeException unavailable) {
            return failedAudit("UNAVAILABLE", unavailable.getMessage());
        }
    }

    private static boolean publishesSuccess(V3SimultaneousColumnSolver.Attempt attempt, V3AcceptanceAudit audit) {
        return attempt instanceof V3SimultaneousColumnSolver.Attempt.Converged converged
                && converged.evidence().convergenceEvidence().satisfiesGates() && audit.accepted();
    }

    private static V3SolverDiagnostics diagnostics(
            V3SimultaneousColumnSolver.Attempt attempt,
            V3AcceptanceAudit audit,
            String solvePath,
            List<String> solverEvents) {
        V3SimultaneousColumnSolver.Evidence evidence = attempt.evidence();
        double finalStepNorm = Math.max(evidence.convergenceEvidence().maximumLogFlowChange(),
                evidence.convergenceEvidence().maximumTemperatureChangeKelvin());
        List<String> events = new ArrayList<>(solverEvents);
        if (events.size() < V3SolverDiagnostics.MAX_EVENTS) events.add(evidence.termination());
        return new V3SolverDiagnostics(0, evidence.iterations(), 0, 0, evidence.maximumScaledResidual(), finalStepNorm,
                solvePath, events, audit, evidence.convergenceEvidence());
    }

    private static V3ColumnOutcome.Failure terminalFailure(
            V3SolverFailureCode code, String detail, String solvePath, List<String> advisoryEvidence) {
        String summary = boundedSummary(detail);
        V3AcceptanceAudit audit = failedAudit("UNAVAILABLE", summary, advisoryEvidence);
        V3SolverDiagnostics diagnostics = new V3SolverDiagnostics(0, 0, 0, 0, 0.0, 0.0, solvePath, List.of(summary),
                audit, V3ConvergenceEvidence.unavailable());
        return new V3ColumnOutcome.Failure(code, summary, diagnostics);
    }

    private static V3AcceptanceAudit failedAudit(String family, String detail) {
        return failedAudit(family, detail, List.of());
    }

    private static V3AcceptanceAudit failedAudit(String family, String detail, List<String> advisoryEvidence) {
        return new V3AcceptanceAudit(List.of(V3AcceptanceAudit.Check.fail(family, 1.0, 0.0, boundedSummary(detail))),
                advisoryEvidence);
    }

    private static V3SolverFailureCode failureCode(String code) {
        if (code.startsWith("LINEAR_") || code.startsWith("JACOBIAN_")) {
            return V3SolverFailureCode.LINEAR_SOLVE_FAILURE;
        }
        if (code.startsWith("MAX_ITERATIONS") || code.startsWith("LINE_SEARCH")
                || code.startsWith("CONVERGENCE_EVIDENCE") || code.startsWith("STATE_DOMAIN")) {
            return V3SolverFailureCode.NONCONVERGENCE;
        }
        return V3SolverFailureCode.INTERNAL_ERROR;
    }

    private static String boundedSummary(String value) {
        if (value == null || value.isBlank()) return "V3 calculation failed without a detail string";
        return value.length() <= 512 ? value : value.substring(0, 512);
    }

    private record V3SolvePass(
            V3SimultaneousColumnSolver.Attempt attempt,
            V3AcceptanceAudit audit,
            double feedMolarEnthalpyJoulesPerMol,
            String solvePath,
            V3DryMeshState recoverySeed,
            int terminalStageCount,
            boolean reachedRequestedProblem,
            boolean attemptedRequestedProblem,
            boolean allowsFreshMaterialClosedFallback,
            List<String> solverEvents) {
        private V3SolvePass {
            attempt = Objects.requireNonNull(attempt, "attempt");
            audit = Objects.requireNonNull(audit, "audit");
            recoverySeed = Objects.requireNonNull(recoverySeed, "recoverySeed");
            solverEvents = List.copyOf(Objects.requireNonNull(solverEvents, "solverEvents"));
            if (!Double.isFinite(feedMolarEnthalpyJoulesPerMol) || solvePath == null || solvePath.isBlank()
                    || terminalStageCount < V3ColumnInput.MIN_STAGE_COUNT
                    || solverEvents.size() > V3SolverDiagnostics.MAX_EVENTS
                    || solverEvents.stream().anyMatch(event -> event == null || event.length() > 256)) {
                throw new IllegalArgumentException("V3 solve pass evidence is invalid");
            }
        }
    }

    private static String pressureEvent(double pressurePascal, String phase, V3SolvePass pass) {
        V3SimultaneousColumnSolver.Evidence evidence = pass.attempt().evidence();
        return "pressure " + Math.round(pressurePascal / 1_000.0) + " kPa " + phase
                + ": " + evidence.termination() + ", iterations=" + evidence.iterations()
                + ", residual=" + evidence.maximumScaledResidual();
    }

    private static List<String> mergedEvents(List<String> continuationEvents, List<String> solverEvents) {
        List<String> result = new ArrayList<>(continuationEvents);
        for (String event : solverEvents) {
            if (result.size() >= V3SolverDiagnostics.MAX_EVENTS) break;
            result.add(event);
        }
        return List.copyOf(result);
    }

    /** Bounded per-solve direction counters, surfaced through the existing diagnostics event contract. */
    private static final class SolveTelemetry implements V3NewtonTrace {
        private final V3ColumnProblem problem;
        private int acceptedLocalBlockDirections;
        private int rejectedLocalBlockDirections;
        private int freshFiniteDifferenceJacobians;
        private int reusedFiniteDifferenceJacobians;
        private double initialMaximumScaledResidual = Double.NaN;
        private double finalMaximumScaledResidual = Double.NaN;
        private V3DegreeOfFreedomLedger.EquationId finalDominantEquation;
        private double finalDominantPhysicalResidual;
        private V3DryMeshState finalState;

        private SolveTelemetry(V3ColumnProblem problem) {
            this.problem = Objects.requireNonNull(problem, "problem");
        }

        @Override
        public void sampledIteration(int iteration, V3MeshResidual residual, double scaledMerit) {
            // The state-bearing callback below owns production telemetry. This method preserves lambda compatibility.
        }

        @Override
        public void sampledState(int iteration, V3DryMeshState state, V3MeshResidual residual, double scaledMerit) {
            double maximum = residual.maximumAbsoluteScaledResidual();
            if (Double.isNaN(initialMaximumScaledResidual)) initialMaximumScaledResidual = maximum;
            finalMaximumScaledResidual = maximum;
            finalState = state;
            V3MeshResidual.Row dominant = null;
            for (V3MeshResidual.Row row : residual.rows()) {
                if (dominant == null || Math.abs(row.scaledValue()) > Math.abs(dominant.scaledValue())) dominant = row;
            }
            if (dominant != null) {
                finalDominantEquation = dominant.equation();
                finalDominantPhysicalResidual = dominant.physicalValue();
            }
        }

        @Override
        public void localBlockDirection(int iteration, boolean accepted) {
            if (accepted) acceptedLocalBlockDirections++;
            else rejectedLocalBlockDirections++;
        }

        @Override
        public void finiteDifferenceJacobian(int iteration, boolean reused) {
            if (reused) reusedFiniteDifferenceJacobians++;
            else freshFiniteDifferenceJacobians++;
        }

        List<String> events() {
            List<String> events = new ArrayList<>();
            if (Double.isFinite(initialMaximumScaledResidual)) {
                events.add("scaled residual: initial=" + initialMaximumScaledResidual
                        + ", final=" + finalMaximumScaledResidual);
            }
            if (finalDominantEquation != null) {
                events.add("dominant residual: " + finalDominantEquation + ", physical="
                        + finalDominantPhysicalResidual);
                if (finalState != null && finalDominantEquation.family()
                        == V3DegreeOfFreedomLedger.EquationFamily.VAPOR_LIQUID_EQUILIBRIUM) {
                    int activeComponent = finalDominantEquation.component();
                    int publicComponent = problem.activeComponentBasis().publicIndex(activeComponent);
                    int node = finalDominantEquation.node();
                    events.add("dominant VLE state: component=" + problem.input().componentBasis().componentId(publicComponent)
                            + ", node=" + node + ", temperature=" + finalState.temperatureKelvin(node)
                            + ", liquid-flow=" + finalState.liquidFlow(node, activeComponent)
                            + ", vapor-flow=" + finalState.vaporFlow(node, activeComponent));
                }
            }
            if (acceptedLocalBlockDirections + rejectedLocalBlockDirections > 0) {
                events.add("local-block directions: accepted=" + acceptedLocalBlockDirections
                        + ", rejected=" + rejectedLocalBlockDirections);
            }
            if (freshFiniteDifferenceJacobians + reusedFiniteDifferenceJacobians > 0) {
                events.add("fine finite-difference Jacobians: fresh=" + freshFiniteDifferenceJacobians
                        + ", reused=" + reusedFiniteDifferenceJacobians);
            }
            return List.copyOf(events);
        }
    }
}
