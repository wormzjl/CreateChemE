package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3FlashResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3FlashTruncationEvidence;
import com.wormzjl.createcheme.science.column.v3.thermo.V3FeedPhase;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoException;
import com.wormzjl.createcheme.science.column.v3.thermo.V3TraceTruncationPolicy;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
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
    /** Cutoff-enabled formulation; the exact-off path retains the legacy revision in its digest. */
    public static final String FORMULATION_REVISION = "v3-dry-mesh-r4-flash-trace";
    private static final String LEGACY_FORMULATION_REVISION = "v3-dry-mesh-r2";
    public static final String ASSUMPTIONS_REVISION = "v3-dry-assumptions-r4";
    public static final int MAXIMUM_NEWTON_ITERATIONS = 128;
    public static final double SCALED_RESIDUAL_TOLERANCE = 1.0e-8;
    private static final double PRESSURE_CONTINUATION_TRIGGER_PASCAL = 100_000.0;
    private static final double PRESSURE_CONTINUATION_ANCHOR_PASCAL = 150_000.0;
    private static final double PRESSURE_CONTINUATION_STEP_PASCAL = 10_000.0;
    private static final double PRESSURE_CONTINUATION_FINE_STEP_PASCAL = 5_000.0;
    private static final double PRESSURE_CONTINUATION_FINE_STEP_FROM_PASCAL = 110_000.0;
    private static final int PRESSURE_CONTINUATION_CORRECTOR_MAXIMUM_ITERATIONS = 12;
    private static final int PRESSURE_CONTINUATION_RECOVERY_MAXIMUM_ITERATIONS = 24;
    private static final int CONDENSER_PHASE_CORRECTOR_MAXIMUM_ITERATIONS = 24;
    private static final int DRAW_RAMP_INTERMEDIATE_MAXIMUM_ITERATIONS = 40;
    private static final int DRAW_RAMP_REQUESTED_MAXIMUM_ITERATIONS = 32;

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

    /**
     * Calculates with frozen per-attempt stage support and an audited molar defect. The cutoff is a mole
     * fraction in [0, 0.01], not a feed filter. Zero uses the exact legacy path. A failed truncated chain
     * retries once untruncated, except for admission failures and caller-owned cancellation.
     *
     * @throws IllegalArgumentException if the cutoff is nonfinite or outside [0, 0.01]
     */
    public static V3ColumnOutcome calculate(V3ColumnInput input, V3SolveControl control, double stageTraceCutoffMoleFraction) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(control, "control");
        V3TruncationSupport.requireCutoff(stageTraceCutoffMoleFraction);
        if (stageTraceCutoffMoleFraction == 0.0) return calculate(input, control);
        return V3TruncationFallback.calculate(stageTraceCutoffMoleFraction, attemptCutoff -> calculate(
                input, control, V3ColumnInitializer.Mode.SEQUENTIAL_MATERIAL_VLE,
                new TruncationPolicy(stageTraceCutoffMoleFraction, attemptCutoff)));
    }

    /** Package-private cold-start qualifier for reviewed initializer modes; production uses the sequential MESH path. */
    static V3ColumnOutcome calculate(
            V3ColumnInput input, V3SolveControl control, V3ColumnInitializer.Mode initializerMode) {
        return calculate(input, control, initializerMode, TruncationPolicy.OFF);
    }

    private static V3ColumnOutcome calculate(
            V3ColumnInput input, V3SolveControl control, V3ColumnInitializer.Mode initializerMode, TruncationPolicy policy) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(control, "control");
        Objects.requireNonNull(initializerMode, "initializerMode");
        double totalDraw = input.sideDraws().stream().mapToDouble(V3SideDrawSpec::molarFlowMolPerSecond).sum();
        double totalFeed = java.util.Arrays.stream(input.feedComponentMolarFlowsMolPerSecond()).sum();
        if (totalDraw >= totalFeed) {
            return terminalFailure(V3SolverFailureCode.INFEASIBLE_SPECIFICATION,
                    "V3 total side draw rate must be less than the feed rate", "input/draws-" + input.sideDraws().size(), List.of());
        }
        CondenserAttempts condenserAttempts = new CondenserAttempts();
        if (initializerMode != V3ColumnInitializer.Mode.SEQUENTIAL_MATERIAL_VLE) {
            return calculateBranch(input, control, initializerMode, V3CondenserPhaseBranch.TWO_PHASE, policy, condenserAttempts);
        }
        V3CondenserPhaseBranch preferred = preferredCondenserBranch(input, control);
        V3ColumnOutcome outcome = calculateBranch(input, control, initializerMode, preferred, policy, condenserAttempts);
        if (outcome instanceof V3ColumnOutcome.Success
                || outcome instanceof V3ColumnOutcome.Failure failure
                && (failure.code() == V3SolverFailureCode.INVALID_INPUT
                || failure.code() == V3SolverFailureCode.PROPERTY_OUT_OF_RANGE)) return outcome;
        V3CondenserPhaseBranch alternate = preferred == V3CondenserPhaseBranch.LIQUID_ONLY
                ? V3CondenserPhaseBranch.TWO_PHASE : V3CondenserPhaseBranch.LIQUID_ONLY;
        // A known phase mismatch has its bounded same-rung warm correction. Restarting either branch
        // from the smallest grid discards that useful profile and can revisit an inappropriate phase.
        if (!condenserAttempts.allowsColdRecovery() || condenserAttempts.hasAttempted(alternate)) return outcome;
        V3ColumnOutcome alternative = calculateBranch(input, control, initializerMode, alternate, policy, condenserAttempts);
        return alternative instanceof V3ColumnOutcome.Success ? alternative : outcome;
    }

    /** A seed flash only orders the branch attempts; the solved liquid outlet is independently audited. */
    private static V3CondenserPhaseBranch preferredCondenserBranch(V3ColumnInput input, V3SolveControl control) {
        control.checkpoint();
        try {
            V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(input.packageId());
            V3ColumnInput noDrawInput = withoutSideDraws(input);
            V3ColumnInput probeInput = noDrawInput.stageCount() <= 4 ? noDrawInput : withStageGeometry(noDrawInput, 4);
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
            V3CondenserPhaseBranch condenserBranch, TruncationPolicy policy, CondenserAttempts condenserAttempts) {
        input = Objects.requireNonNull(input, "input");
        control = Objects.requireNonNull(control, "control");
        initializerMode = Objects.requireNonNull(initializerMode, "initializerMode");
        List<String> advisoryEvidence = List.of();
        boolean admitted = false;
        try {
            condenserAttempts.recordAttempt(condenserBranch);
            control.checkpoint();
            V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(input.packageId());
            advisoryEvidence = thermo.advisoryEvidence();
            V3ColumnProblem problem = V3ColumnProblemResolver.resolve(input, condenserBranch);
            V3OperatingDomainValidator.Assessment admission = V3OperatingDomainValidator.assess(problem, thermo);
            if (admission instanceof V3OperatingDomainValidator.Assessment.Rejected rejected) {
                return terminalFailure(V3SolverFailureCode.PROPERTY_OUT_OF_RANGE, rejected.detail(), "admission", advisoryEvidence);
            }
            admitted = true;
            V3SolvePass pass;
            if (initializerMode == V3ColumnInitializer.Mode.SEQUENTIAL_MATERIAL_VLE) {
                pass = requiresPressureContinuation(admission)
                        ? solveDwsimPressureContinuation(input, thermo, control, condenserBranch, policy, condenserAttempts)
                        : solveDwsimStageContinuation(input, thermo, control, condenserBranch, policy, condenserAttempts);
                if (!publishesSuccess(pass.attempt(), pass.audit())
                        && !hasCondenserPhaseMismatch(pass)
                        && condenserAttempts.allowsColdRecovery()
                        && pass.attemptedRequestedProblem()
                        && pass.terminalStageCount() >= input.stageCount()
                        && pass.allowsFreshMaterialClosedFallback()) {
                    problem = originalProblem(pass);
                    // The sequential material/VLE preconditioner is deliberately optional. It must not turn an
                    // otherwise solvable MESH problem into a failure; this is a fresh V3 material-closed seed,
                    // never a V1 approximation or a retained warm state.
                    V3DryMeshState fallbackSeed = initializeForSolve(
                            problem, thermo, V3ColumnInitializer.Mode.MATERIAL_CLOSED);
                    pass = withPriorSupportNotes(pass, solveSingleProblem(problem, thermo, fallbackSeed,
                            control, "cold/dwsim-material-closed-fallback/fine-fd", policy));
                    PhaseCorrection fallbackPhase = correctCondenserPhase(pass, thermo, control, policy, condenserAttempts);
                    pass = fallbackPhase.pass();
                    if (!publishesSuccess(pass.attempt(), pass.audit())
                            && !fallbackPhase.attempted() && !hasCondenserPhaseMismatch(pass)) {
                        V3SolvePass recovered = recoverWithBubblePointProjection(
                                problem, thermo, pass.attempt().state(), control,
                                "cold/dwsim-material-closed-fallback/material-vle-recovery/fine-fd",
                                ContinuationJacobianPolicy.STAGE_LOCAL_BLOCKS, MAXIMUM_NEWTON_ITERATIONS, policy);
                        recovered = correctCondenserPhase(recovered, thermo, control, policy, condenserAttempts).pass();
                        if (publishesSuccess(recovered.attempt(), recovered.audit())
                                || recovered.attempt().evidence().maximumScaledResidual()
                                < pass.attempt().evidence().maximumScaledResidual()) {
                            pass = withPriorSupportNotes(pass, recovered);
                        }
                    }
                }
            } else {
                pass = solveSingleProblem(problem, thermo,
                        initializeForSolve(problem, thermo, initializerMode),
                        control, "cold/fine-fd", policy);
            }
            V3SimultaneousColumnSolver.Attempt attempt = pass.attempt();
            V3AcceptanceAudit audit = pass.audit();
            String solvePath = pass.solvePath();
            PreparedAttempt selected = pass.prepared();
            if (pass.reachedRequestedProblem() && !publishesSuccess(attempt, audit)
                    && !hasCondenserPhaseMismatch(pass) && condenserAttempts.allowsColdRecovery()) {
                try {
                    control.checkpoint();
                    PreparedAttempt coarsePrepared = prepareAttempt(originalProblem(pass), pass.recoverySeed(), policy);
                    V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(
                            coarsePrepared.problem(), thermo, pass.feedMolarEnthalpyJoulesPerMol());
                    V3SimultaneousColumnSolver.Attempt coarseAttempt = V3SimultaneousColumnSolver.solve(
                            coarsePrepared.problem(), evaluator, new V3DryMeshCoordinateMap(coarsePrepared.problem()),
                            coarsePrepared.seed(), thermo::newWorkspace,
                            V3ConvergenceEvidence.unavailable(), MAXIMUM_NEWTON_ITERATIONS, SCALED_RESIDUAL_TOLERANCE,
                            V3FiniteDifferenceJacobian.DifferenceScale.COARSE, control);
                    control.checkpoint();
                    V3AcceptanceAudit coarseAudit = audit(
                            coarsePrepared.problem(), thermo, pass.feedMolarEnthalpyJoulesPerMol(), coarseAttempt.state(), control);
                    V3SolvePass coarsePass = withPriorSupportNotes(pass, new V3SolvePass(coarseAttempt, coarseAudit,
                            pass.feedMolarEnthalpyJoulesPerMol(), "cold/coarse-fd-recovery", pass.recoverySeed(),
                            pass.terminalStageCount(), pass.reachedRequestedProblem(), pass.attemptedRequestedProblem(),
                            pass.allowsFreshMaterialClosedFallback(), List.of("coarse finite-difference recovery"), coarsePrepared));
                    PhaseCorrection coarsePhase = correctCondenserPhase(coarsePass, thermo, control, policy, condenserAttempts);
                    coarsePass = coarsePhase.pass();
                    if (coarsePhase.attempted() || publishesSuccess(coarsePass.attempt(), coarsePass.audit())
                            || coarsePass.attempt().evidence().maximumScaledResidual() < attempt.evidence().maximumScaledResidual()) {
                        pass = coarsePass;
                        attempt = coarsePass.attempt();
                        audit = coarsePass.audit();
                        solvePath = coarsePass.solvePath();
                        selected = coarsePass.prepared();
                    }
                } catch (CancellationException cancelled) {
                    throw cancelled;
                } catch (IllegalStateException unavailableRecovery) {
                    // The primary fine attempt remains a complete typed result when this optional stencil violates
                    // the band-structure guard because of finite-difference noise.
                }
            }
            if (selected.problem().topology().condenserPhaseBranch() == V3CondenserPhaseBranch.LIQUID_ONLY) {
                solvePath += "/liquid-only-condenser";
            }
            if (input.sideDraws().size() > 0) solvePath += "/draws-" + input.sideDraws().size();
            List<String> solverEvents = policy.attemptCutoff() > 0.0
                    ? mergedEvents(List.of(stageTraceEvent(selected.support(), attempt.state(), selected.problem())), pass.solverEvents())
                    : pass.solverEvents();
            V3SolverDiagnostics diagnostics = diagnostics(attempt, audit, solvePath, solverEvents);
            if (pass.reachedRequestedProblem()
                    && attempt instanceof V3SimultaneousColumnSolver.Attempt.Converged converged && audit.accepted()) {
                V3InputDigest digest = V3InputDigest.of(selected.problem(), formulationRevision(input, policy.requestedCutoff()),
                        thermo.datasetRevision(), ASSUMPTIONS_REVISION, policy.requestedCutoff());
                V3ColumnResult result = V3ColumnResult.accepted(
                        selected.problem(), digest, audit, converged.evidence().convergenceEvidence(), converged.state(), thermo,
                        formulationRevision(input, policy.requestedCutoff()));
                return new V3ColumnOutcome.Success(result, diagnostics);
            }
            if (attempt instanceof V3SimultaneousColumnSolver.Attempt.Failure failure) {
                String detail = !pass.attemptedRequestedProblem() && pass.terminalStageCount() >= input.stageCount()
                        ? "DWSIM pressure continuation stalled before the requested operating point at "
                        + pass.solvePath() + " after " + failure.evidence().iterations()
                        + " Newton iterations; maximum scaled residual "
                        + failure.evidence().maximumScaledResidual() + ": " + failure.evidence().termination()
                        : pass.terminalStageCount() < input.stageCount()
                        ? "DWSIM continuation stalled at " + pass.terminalStageCount() + " stages after "
                        + failure.evidence().iterations() + " Newton iterations; maximum scaled residual "
                        + failure.evidence().maximumScaledResidual() + ": " + failure.evidence().termination()
                        : failure.evidence().termination();
                String drawDetail = sideDrawDiagnostic(selected.problem(), attempt.state(), input.stageCount());
                if (!drawDetail.isEmpty() && detail.length() + drawDetail.length() > 512) {
                    detail = detail.substring(0, Math.max(0, 512 - drawDetail.length()));
                }
                return new V3ColumnOutcome.Failure(failureCode(failure.code()), detail + drawDetail, diagnostics);
            }
            return new V3ColumnOutcome.Failure(V3SolverFailureCode.ACCEPTANCE_AUDIT_FAILURE,
                    "The fresh V3 acceptance audit rejected the converged candidate"
                            + sideDrawDiagnostic(selected.problem(), attempt.state(), input.stageCount()), diagnostics);
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (InitializationFailure initialization) {
            return terminalFailure(V3SolverFailureCode.INITIALIZATION_FAILURE,
                    initialization.getMessage(), "initialization", advisoryEvidence);
        } catch (V3ThermoException thermoFailure) {
            return terminalFailure(admitted && policy.attemptCutoff() > 0.0 ? V3SolverFailureCode.NONCONVERGENCE
                    : V3SolverFailureCode.PROPERTY_OUT_OF_RANGE, thermoFailure.getMessage(), "property", advisoryEvidence);
        } catch (IllegalArgumentException invalid) {
            return terminalFailure(admitted && policy.attemptCutoff() > 0.0 ? V3SolverFailureCode.NONCONVERGENCE
                    : V3SolverFailureCode.INVALID_INPUT, invalid.getMessage(), "input", advisoryEvidence);
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
            V3CondenserPhaseBranch condenserBranch, TruncationPolicy policy, CondenserAttempts condenserAttempts) {
        List<Integer> stageCounts = dwsimStageCounts(input.stageCount());
        String stagePath = dwsimStagePath(stageCounts);
        V3DryMeshState previousState = null;
        V3SolvePass lastPass = null;
        for (int stageCount : stageCounts) {
            control.checkpoint();
            V3ColumnInput stageInput = input.sideDraws().isEmpty() && stageCount == input.stageCount()
                    ? input : withStageGeometry(input, stageCount);
            V3CondenserPhaseBranch currentBranch = lastPass == null ? condenserBranch
                    : lastPass.prepared().problem().topology().condenserPhaseBranch();
            V3ColumnProblem stageProblem = V3ColumnProblemResolver.resolve(stageInput, currentBranch);
            V3DryMeshState seed = previousState == null
                    ? initializeForSolve(stageProblem, thermo, V3ColumnInitializer.Mode.SEQUENTIAL_MATERIAL_VLE)
                    : continuationSeed(stageProblem, previousState, thermo, control);
            V3SolvePass preceding = lastPass;
            lastPass = solveSingleProblem(stageProblem, thermo,
                    seed, control, "cold/dwsim-sequential/" + stagePath + "/fine-fd",
                    ContinuationJacobianPolicy.STAGE_LOCAL_BLOCKS, policy);
            if (preceding != null) lastPass = withPriorSupportNotes(preceding, lastPass);
            PhaseCorrection phaseCorrection = correctCondenserPhase(lastPass, thermo, control, policy, condenserAttempts);
            lastPass = phaseCorrection.pass();
            if (!publishesSuccess(lastPass.attempt(), lastPass.audit())) {
                if (!phaseCorrection.attempted() && !hasCondenserPhaseMismatch(lastPass)) {
                    lastPass = withPriorSupportNotes(lastPass,
                            recoverDwsimContinuationStage(originalProblem(lastPass), thermo, lastPass, control, stagePath, stageCount, policy));
                    phaseCorrection = correctCondenserPhase(lastPass, thermo, control, policy, condenserAttempts);
                    lastPass = phaseCorrection.pass();
                }
                if (!publishesSuccess(lastPass.attempt(), lastPass.audit())) {
                    return new V3SolvePass(lastPass.attempt(), lastPass.audit(), lastPass.feedMolarEnthalpyJoulesPerMol(),
                            "cold/dwsim-sequential/" + stagePath + "/failed-stage-" + stageCount,
                            lastPass.recoverySeed(), stageCount, false, stageCount == input.stageCount(),
                            stageCount == input.stageCount() && !phaseCorrection.attempted()
                                    && !hasCondenserPhaseMismatch(lastPass), lastPass.solverEvents(), lastPass.prepared());
                }
            }
            previousState = lastPass.attempt().state();
        }
        if (lastPass == null) throw new IllegalStateException("V3 DWSIM continuation has no stage grid");
        if (!input.sideDraws().isEmpty()) {
            V3ColumnProblem requested = V3ColumnProblemResolver.resolve(input,
                    lastPass.prepared().problem().topology().condenserPhaseBranch());
            lastPass = recoverWithDrawRamp(requested, thermo, lastPass, control, policy, condenserAttempts);
            if (!publishesSuccess(lastPass.attempt(), lastPass.audit())) {
                return new V3SolvePass(lastPass.attempt(), lastPass.audit(), lastPass.feedMolarEnthalpyJoulesPerMol(),
                        "cold/dwsim-sequential/" + stagePath + "/failed-stage-" + input.stageCount(),
                        lastPass.recoverySeed(), input.stageCount(), false, true, false,
                        lastPass.solverEvents(), lastPass.prepared());
            }
        }
        return new V3SolvePass(lastPass.attempt(), lastPass.audit(), lastPass.feedMolarEnthalpyJoulesPerMol(),
                lastPass.solvePath(), lastPass.recoverySeed(), lastPass.terminalStageCount(), true, true, false,
                lastPass.solverEvents(), lastPass.prepared());
    }

    /**
     * Continues a qualified 150 kPa(a) cold solution downward in bounded request-local pressure steps.
     *
     * <p>Each leg solves the complete requested-stage MESH system and independently audits it before its state may
     * seed the next leg. No accepted state survives this calculation call.</p>
     */
    private static V3SolvePass solveDwsimPressureContinuation(
            V3ColumnInput input, V3PengRobinsonThermo thermo, V3SolveControl control,
            V3CondenserPhaseBranch condenserBranch, TruncationPolicy policy, CondenserAttempts condenserAttempts) {
        if (input.topPressurePascal() > PRESSURE_CONTINUATION_TRIGGER_PASCAL) {
            throw new IllegalArgumentException("V3 pressure continuation was requested outside its low-pressure lane");
        }
        boolean finePressureSteps = !input.sideDraws().isEmpty();
        V3ColumnInput anchorInput = withTopPressure(input, PRESSURE_CONTINUATION_ANCHOR_PASCAL);
        V3SolvePass pass = solveDwsimStageContinuation(anchorInput, thermo, control, condenserBranch, policy, condenserAttempts);
        List<String> pressureEvents = new ArrayList<>();
        String pressurePath = dwsimPressurePath(input.topPressurePascal(), finePressureSteps);
        if (!publishesSuccess(pass.attempt(), pass.audit())) {
            return new V3SolvePass(pass.attempt(), pass.audit(), pass.feedMolarEnthalpyJoulesPerMol(),
                    "cold/dwsim-pressure/" + pressurePath + "/anchor-failed", pass.recoverySeed(),
                    pass.terminalStageCount(), false, false, false,
                    mergedEvents(pressureEvents, pass.solverEvents()), pass.prepared());
        }
        for (double pressure : dwsimPressureSteps(input.topPressurePascal(), finePressureSteps)) {
            control.checkpoint();
            V3ColumnInput stepInput = withTopPressure(input, pressure);
            V3ColumnProblem stepProblem = V3ColumnProblemResolver.resolve(stepInput,
                    pass.prepared().problem().topology().condenserPhaseBranch());
            V3DryMeshState seed = pass.attempt().state();
            String stepPath = "cold/dwsim-pressure/" + pressurePath + "/top-"
                    + Math.round(pressure / 1_000.0) + "kpa/fine-fd";
            pass = withPriorSupportNotes(pass, solveSingleProblem(stepProblem, thermo, seed, control, stepPath,
                    ContinuationJacobianPolicy.PRESSURE_LOCAL_PREDICTOR,
                    PRESSURE_CONTINUATION_CORRECTOR_MAXIMUM_ITERATIONS, policy));
            pressureEvents.add(pressureEvent(pressure, "predictor", pass));
            PhaseCorrection phaseCorrection = correctCondenserPhase(pass, thermo, control, policy, condenserAttempts);
            pass = phaseCorrection.pass();
            if (!publishesSuccess(pass.attempt(), pass.audit())) {
                if (!phaseCorrection.attempted() && !hasCondenserPhaseMismatch(pass)) {
                    pass = withPriorSupportNotes(pass, recoverWithBubblePointProjection(stepProblem, thermo, seed, control,
                            "cold/dwsim-pressure/" + pressurePath + "/top-"
                                    + Math.round(pressure / 1_000.0) + "kpa/material-vle-recovery/fine-fd",
                            ContinuationJacobianPolicy.PRESSURE_LOCAL_PREDICTOR,
                            PRESSURE_CONTINUATION_RECOVERY_MAXIMUM_ITERATIONS, policy));
                    pressureEvents.add(pressureEvent(pressure, "Wang-Henke material/VLE recovery", pass));
                    pass = correctCondenserPhase(pass, thermo, control, policy, condenserAttempts).pass();
                }
                if (!publishesSuccess(pass.attempt(), pass.audit())) {
                    boolean attemptedRequestedProblem = Double.compare(pressure, input.topPressurePascal()) == 0;
                    return new V3SolvePass(pass.attempt(), pass.audit(), pass.feedMolarEnthalpyJoulesPerMol(),
                            "cold/dwsim-pressure/" + pressurePath + "/failed-top-"
                                    + Math.round(pressure / 1_000.0) + "kpa",
                            pass.recoverySeed(), input.stageCount(), false, attemptedRequestedProblem, false,
                            mergedEvents(pressureEvents, pass.solverEvents()), pass.prepared());
                }
            }
        }
        return new V3SolvePass(pass.attempt(), pass.audit(), pass.feedMolarEnthalpyJoulesPerMol(),
                pass.solvePath(), pass.recoverySeed(), input.stageCount(), true, true, false,
                mergedEvents(pressureEvents, pass.solverEvents()), pass.prepared());
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
            int stageCount,
            TruncationPolicy policy) {
        control.checkpoint();
        return recoverWithBubblePointProjection(problem, thermo, failedPass.attempt().state(), control,
                "cold/dwsim-sequential/" + stagePath + "/material-vle-recovery-stage-" + stageCount + "/fine-fd",
                ContinuationJacobianPolicy.STAGE_LOCAL_BLOCKS,
                MAXIMUM_NEWTON_ITERATIONS, policy);
    }

    private static V3SolvePass recoverWithBubblePointProjection(
            V3ColumnProblem problem,
            V3PengRobinsonThermo thermo,
            V3DryMeshState projectionSource,
            V3SolveControl control,
            String solvePath,
            ContinuationJacobianPolicy jacobianPolicy,
            int maximumIterations,
            TruncationPolicy policy) {
        control.checkpoint();
        V3DryMeshState projected = projectedSeedOrPrevious(problem, thermo, projectionSource, control);
        return solveSingleProblem(problem, thermo, projected, control, solvePath, jacobianPolicy, maximumIterations, policy);
    }

    private static V3SolvePass solveSingleProblem(
            V3ColumnProblem problem,
            V3PengRobinsonThermo thermo,
            V3DryMeshState seed,
            V3SolveControl control,
            String solvePath,
            TruncationPolicy policy) {
        return solveSingleProblem(problem, thermo, seed, control, solvePath, ContinuationJacobianPolicy.NONE, policy);
    }

    private static V3SolvePass solveSingleProblem(
            V3ColumnProblem problem,
            V3PengRobinsonThermo thermo,
            V3DryMeshState seed,
            V3SolveControl control,
            String solvePath,
            ContinuationJacobianPolicy jacobianPolicy,
            TruncationPolicy policy) {
        return solveSingleProblem(problem, thermo, seed, control, solvePath, jacobianPolicy,
                MAXIMUM_NEWTON_ITERATIONS, policy);
    }

    private static V3SolvePass solveSingleProblem(
            V3ColumnProblem problem,
            V3PengRobinsonThermo thermo,
            V3DryMeshState seed,
            V3SolveControl control,
            String solvePath,
            ContinuationJacobianPolicy jacobianPolicy,
            int maximumIterations,
            TruncationPolicy policy) {
        if (maximumIterations < 1 || maximumIterations > MAXIMUM_NEWTON_ITERATIONS) {
            throw new IllegalArgumentException("V3 simultaneous solve iteration limit is invalid");
        }
        jacobianPolicy = Objects.requireNonNull(jacobianPolicy, "jacobianPolicy");
        control.checkpoint();
        V3FlashResult feedFlash = policy.attemptCutoff() > 0.0
                ? thermo.flashTP(problem.input().feedTemperatureKelvin(),
                        problem.nodePressurePascal(problem.topology().feedTrayNumber()),
                        problem.input().feedComponentMolarFlowsMolPerSecond(), V3TraceTruncationPolicy.of(policy.attemptCutoff()),
                        thermo.newWorkspace(), control::checkpoint)
                : thermo.flashTP(problem.input().feedTemperatureKelvin(),
                        problem.nodePressurePascal(problem.topology().feedTrayNumber()),
                        problem.input().feedComponentMolarFlowsMolPerSecond(), thermo.newWorkspace());
        // A phase-allocation approximation must never change the authored feed's physical energy.
        double feedMolarEnthalpy = feedFlash.referenceMolarEnthalpyJoulesPerMol();
        V3DryMeshState recoverySeed = seed;
        PreparedAttempt prepared = prepareAttempt(problem, seed, policy);
        problem = prepared.problem();
        seed = prepared.seed();
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(
                problem, thermo, feedMolarEnthalpy);
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
        V3AcceptanceAudit audit = audit(problem, thermo, feedMolarEnthalpy, attempt.state(), control);
        List<String> events = telemetry.events();
        if (policy.attemptCutoff() > 0.0) events = mergedEvents(List.of(flashTraceEvent(feedFlash)), events);
        if (!prepared.support().note().isEmpty()) {
            events = mergedEvents(List.of("stage-trace support: " + prepared.support().note()), events);
        }
        return new V3SolvePass(attempt, audit, feedMolarEnthalpy, solvePath,
                recoverySeed, problem.input().stageCount(), true, true, true, events, prepared);
    }

    private static boolean hasCondenserPhaseMismatch(V3SolvePass pass) {
        return pass.attempt() instanceof V3SimultaneousColumnSolver.Attempt.Converged
                && pass.audit().checks().stream().anyMatch(check -> check.family().equals("CONDENSER_PHASE") && !check.passed());
    }

    /** Resolve the current branch without carrying an earlier attempt's frozen truncation support. */
    private static V3ColumnProblem originalProblem(V3SolvePass pass) {
        V3ColumnProblem selected = pass.prepared().problem();
        return V3ColumnProblemResolver.resolve(selected.input(), selected.topology().condenserPhaseBranch());
    }

    /** One warm condenser flash correction per rung; its candidate still needs the full fresh audit. */
    private static PhaseCorrection correctCondenserPhase(
            V3SolvePass pass, V3PengRobinsonThermo thermo, V3SolveControl control,
            TruncationPolicy policy, CondenserAttempts condenserAttempts) {
        if (!hasCondenserPhaseMismatch(pass)) return new PhaseCorrection(pass, false);
        condenserAttempts.beginPhaseCorrection();
        if (!pass.attempt().evidence().convergenceEvidence().satisfiesGates()
                || pass.audit().checks().stream().anyMatch(check -> !check.family().equals("CONDENSER_PHASE") && !check.passed())) {
            return new PhaseCorrection(withPhaseEvents(pass,
                    "condenser phase transition unavailable: other convergence or audit gates also failed"), true);
        }
        V3CondenserPhaseTransition.Prepared transition;
        try {
            transition = V3CondenserPhaseTransition.prepare(pass.prepared().problem(), pass.attempt().state(), thermo, control);
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (V3ThermoException | IllegalArgumentException unavailable) {
            String detail = "condenser phase transition unavailable: " + unavailable.getMessage();
            return new PhaseCorrection(withPhaseEvents(pass, detail.length() <= 256 ? detail : detail.substring(0, 256)), true);
        }
        V3CondenserPhaseBranch sourceBranch = pass.prepared().problem().topology().condenserPhaseBranch();
        V3CondenserPhaseBranch targetBranch = transition.problem().topology().condenserPhaseBranch();
        condenserAttempts.recordAttempt(targetBranch);
        String event = "condenser phase transition: " + sourceBranch + " -> " + targetBranch
                + "; overhead flash vapor fraction=" + transition.vaporFraction();
        V3SolvePass corrected = solveSingleProblem(transition.problem(), thermo, transition.seed(), control,
                pass.solvePath() + "/condenser-phase-correction",
                ContinuationJacobianPolicy.PRESSURE_LOCAL_PREDICTOR,
                CONDENSER_PHASE_CORRECTOR_MAXIMUM_ITERATIONS, policy);
        condenserAttempts.finishPhaseCorrection(publishesSuccess(corrected.attempt(), corrected.audit()));
        return new PhaseCorrection(withPhaseEvents(withPriorSupportNotes(pass, corrected), event), true);
    }

    private static V3SolvePass withPhaseEvents(V3SolvePass pass, String event) {
        return new V3SolvePass(pass.attempt(), pass.audit(), pass.feedMolarEnthalpyJoulesPerMol(), pass.solvePath(),
                pass.recoverySeed(), pass.terminalStageCount(), pass.reachedRequestedProblem(),
                pass.attemptedRequestedProblem(), false, mergedEvents(List.of(event), pass.solverEvents()), pass.prepared());
    }

    private record PhaseCorrection(V3SolvePass pass, boolean attempted) {}

    /** Solve-confined attempt history; prevents duplicate cold branch restarts after a phase correction. */
    static final class CondenserAttempts {
        private final EnumSet<V3CondenserPhaseBranch> branches = EnumSet.noneOf(V3CondenserPhaseBranch.class);
        private boolean unresolvedPhaseMismatch;
        private boolean requestedDrawRampFailed;

        void recordAttempt(V3CondenserPhaseBranch branch) { branches.add(Objects.requireNonNull(branch, "branch")); }
        boolean hasAttempted(V3CondenserPhaseBranch branch) { return branches.contains(branch); }
        void beginPhaseCorrection() { unresolvedPhaseMismatch = true; }
        void finishPhaseCorrection(boolean auditedSuccess) {
            if (auditedSuccess) unresolvedPhaseMismatch = false;
        }
        void recordRequestedDrawRampFailure() { requestedDrawRampFailed = true; }
        boolean allowsColdRecovery() { return !unresolvedPhaseMismatch && !requestedDrawRampFailed; }
    }

    /** Bounded extra continuation axis, attempted only after a draw-bearing grid fails. */
    private static V3SolvePass recoverWithDrawRamp(
            V3ColumnProblem requested, V3PengRobinsonThermo thermo, V3SolvePass seedBase,
            V3SolveControl control, TruncationPolicy policy, CondenserAttempts condenserAttempts) {
        V3ColumnInput input = requested.input();
        seedBase = Objects.requireNonNull(seedBase, "seedBase");
        if (!publishesSuccess(seedBase.attempt(), seedBase.audit())
                || seedBase.prepared().problem().hasSideDraws()) {
            throw new IllegalArgumentException("V3 draw ramp requires an accepted no-draw seed at requested geometry");
        }
        String rampPath = seedBase.solvePath();
        V3SolvePass previous = seedBase;
        CondenserAttempts rampAttempts = new CondenserAttempts();
        List<String> rampEvents = new ArrayList<>();
        boolean intermediateFailed = false;
        // The first fixed-geometry handoff uses the material projection. Later parameter rungs retain the
        // accepted state directly; reprojecting every rung was measured to cause residual stagnation.
        int rampSteps = 4;
        for (int rampStep = 1; rampStep <= rampSteps; rampStep++) {
            double fraction = rampStep / (double) rampSteps;
            if (intermediateFailed && fraction < 1.0) continue;
            control.checkpoint();
            List<V3SideDrawSpec> draws = input.sideDraws().stream()
                    .map(draw -> new V3SideDrawSpec(draw.trayNumber(), fraction * draw.molarFlowMolPerSecond())).toList();
            V3ColumnInput rampInput = new V3ColumnInput(input.schemaVersion(), input.packageId(), input.assayId(),
                    input.componentBasis(), input.feedComponentMolarFlowsMolPerSecond(), input.feedTemperatureKelvin(),
                    input.stageCount(), input.feedStageNumber(), input.topPressurePascal(), input.stagePressureDropPascal(),
                    input.specifications(), draws);
            V3CondenserPhaseBranch branch = previous.prepared().problem().topology().condenserPhaseBranch();
            V3ColumnProblem problem = V3ColumnProblemResolver.resolve(rampInput, branch);
            rampAttempts.recordAttempt(branch);
            V3DryMeshState seed = previous == seedBase
                    ? continuationSeed(problem, previous.attempt().state(), thermo, control)
                    : previous.attempt().state();
            TruncationPolicy rampPolicy = fraction == 1.0 ? policy : TruncationPolicy.OFF;
            V3SolvePass pass = solveSingleProblem(problem, thermo, seed, control,
                    rampPath + "/draw-ramp-" + fraction, ContinuationJacobianPolicy.STAGE_LOCAL_BLOCKS,
                    fraction == 1.0 ? DRAW_RAMP_REQUESTED_MAXIMUM_ITERATIONS
                            : DRAW_RAMP_INTERMEDIATE_MAXIMUM_ITERATIONS, rampPolicy);
            pass = correctCondenserPhase(pass, thermo, control, rampPolicy, rampAttempts).pass();
            if (!publishesSuccess(pass.attempt(), pass.audit())) {
                if (fraction == 1.0) {
                    condenserAttempts.recordRequestedDrawRampFailure();
                    rampEvents.add(boundedEvent(
                            "side-draw ramp reached 1.0 and failed: " + rampEvidence(pass)));
                    V3SolvePass annotated = withRampEvents(pass, rampEvents);
                    return withPriorSupportNotes(seedBase, annotated);
                }
                String event = "side-draw ramp stopped at " + fraction + ": " + rampEvidence(pass)
                        + "; failed checks=" + pass.audit().checks().stream().filter(check -> !check.passed())
                        .map(V3AcceptanceAudit.Check::family).toList();
                rampEvents.add(boundedEvent(event));
                // A failed intermediate fraction is still a finite fixed-geometry seed. The authored
                // full-rate problem must be attempted before returning a terminal diagnostic.
                previous = pass;
                intermediateFailed = true;
                continue;
            }
            previous = pass;
        }
        condenserAttempts.recordAttempt(previous.prepared().problem().topology().condenserPhaseBranch());
        condenserAttempts.finishPhaseCorrection(true);
        return withPriorSupportNotes(seedBase, withRampEvents(previous, rampEvents));
    }

    private static V3SolvePass withRampEvents(V3SolvePass pass, List<String> events) {
        if (events.isEmpty()) return pass;
        return new V3SolvePass(pass.attempt(), pass.audit(), pass.feedMolarEnthalpyJoulesPerMol(), pass.solvePath(),
                pass.recoverySeed(), pass.terminalStageCount(), pass.reachedRequestedProblem(),
                pass.attemptedRequestedProblem(), pass.allowsFreshMaterialClosedFallback(),
                mergedEvents(events, pass.solverEvents()), pass.prepared());
    }

    private static String boundedEvent(String event) {
        return event.length() <= 256 ? event : event.substring(0, 256);
    }

    private static String rampEvidence(V3SolvePass pass) {
        V3SimultaneousColumnSolver.Evidence evidence = pass.attempt().evidence();
        return evidence.termination() + ", iterations=" + evidence.iterations()
                + ", residual=" + evidence.maximumScaledResidual();
    }

    static String formulationRevision(double requestedCutoff) {
        V3TruncationSupport.requireCutoff(requestedCutoff);
        return requestedCutoff == 0.0 ? LEGACY_FORMULATION_REVISION : FORMULATION_REVISION;
    }

    static String formulationRevision(V3ColumnInput input, double requestedCutoff) {
        if (input.sideDraws().isEmpty()) return formulationRevision(requestedCutoff);
        V3TruncationSupport.requireCutoff(requestedCutoff);
        return "v3-dry-mesh-r5-side-draws" + (requestedCutoff > 0.0 ? "-flash-trace" : "");
    }

    private static String sideDrawDiagnostic(
            V3ColumnProblem problem, V3DryMeshState state, int requestedStageCount) {
        V3SideDrawSpec worst = null;
        double worstLiquid = 0.0;
        double largestFraction = -1.0;
        for (V3SideDrawSpec draw : problem.input().sideDraws()) {
            double liquid = V3SideDraws.liquidTotal(state, draw.trayNumber());
            double fraction = liquid > 0.0 && Double.isFinite(liquid)
                    ? draw.molarFlowMolPerSecond() / liquid : Double.MAX_VALUE;
            if (fraction > largestFraction) {
                worst = draw;
                worstLiquid = liquid;
                largestFraction = fraction;
            }
        }
        if (worst == null) return "";
        String geometry = problem.topology().trayCount() == requestedStageCount
                ? "authored tray " + worst.trayNumber()
                : "tray " + worst.trayNumber() + " at the " + problem.topology().trayCount()
                        + "-tray continuation grid (requested " + requestedStageCount + " trays)";
        return String.format(Locale.ROOT,
                "; side draw on %s requests %.6g kmol/h; final internal liquid %.6g kmol/h (withdrawal %.5g)",
                geometry, worst.molarFlowMolPerSecond() * 3.6, worstLiquid * 3.6, largestFraction);
    }

    private static V3SolvePass withPriorSupportNotes(V3SolvePass previous, V3SolvePass next) {
        // Preserve availability and phase-transition evidence without retaining earlier numerical states.
        List<String> notes = null;
        for (String event : previous.solverEvents()) {
            if (!event.startsWith("stage-trace support:") && !event.startsWith("condenser phase transition")
                    && !event.startsWith("flash-trace ")) continue;
            if (notes == null) notes = new ArrayList<>();
            if (!notes.contains(event) && !next.solverEvents().contains(event)) notes.add(event);
        }
        if (notes == null || notes.isEmpty()) return next;
        return new V3SolvePass(next.attempt(), next.audit(), next.feedMolarEnthalpyJoulesPerMol(), next.solvePath(),
                next.recoverySeed(), next.terminalStageCount(), next.reachedRequestedProblem(),
                next.attemptedRequestedProblem(), next.allowsFreshMaterialClosedFallback(),
                mergedEvents(notes, next.solverEvents()), next.prepared());
    }

    /** The deciding seed is final here: interpolation/preconditioning happens outside this frozen attempt. */
    private static PreparedAttempt prepareAttempt(V3ColumnProblem original, V3DryMeshState seed, TruncationPolicy policy) {
        V3TruncationSupport support = V3TruncationSupport.derive(original, policy.attemptCutoff(), seed);
        V3ColumnProblem problem;
        try {
            problem = V3ColumnProblemResolver.withTruncation(original, support);
        } catch (IllegalArgumentException invalidLedger) {
            support = support.fallbackToIdentity(original,
                    "Stage-trace support fell back to identity: reduced ledger validation failed");
            problem = original;
        }
        return new PreparedAttempt(problem, support, support.projectSeed(original, seed));
    }

    private static String stageTraceEvent(V3TruncationSupport support, V3DryMeshState state, V3ColumnProblem problem) {
        String defect;
        try {
            defect = String.format(Locale.ROOT, "%.6g", support.massDefectMolPerSecond(state)
                    / problem.activeComponentBasis().totalFeedFlowMolPerSecond());
        } catch (IllegalArgumentException unavailable) {
            defect = "unavailable";
        }
        String event = "stage-trace cutoff=" + support.cutoffMoleFraction() + "; truncated="
                + support.truncatedPointCount() + "/" + support.totalPointCount() + "; closure-pruned="
                + support.closurePrunedCount() + "; defect/feed=" + defect
                + (support.note().isEmpty() ? "" : "; " + support.note());
        return event.length() <= 256 ? event : event.substring(0, 256);
    }

    private static String flashTraceEvent(V3FlashResult flash) {
        V3FlashTruncationEvidence evidence = flash.truncationEvidence();
        String event = String.format(Locale.ROOT,
                "flash-trace cutoff=%.6g; status=%s; omitted=%dL/%dV; it=%d/%d",
                evidence.cutoffMoleFraction(), evidence.status(), evidence.omittedLiquidComponents(),
                evidence.omittedVaporComponents(), evidence.referenceIterations(), evidence.reducedIterations());
        event += evidence.errorsEvaluated() ? String.format(Locale.ROOT,
                "; alloc=%.3g; beta=%.3g; x/y=%.3g; mass=%.3g; dH=%.3g J/mol",
                evidence.allocationError(), evidence.betaError(), evidence.maxPhaseCompositionError(),
                evidence.maxMaterialClosureError(), evidence.enthalpyErrorJoulesPerMol()) : "; errors=not-evaluated";
        event += "; feed-H=reference";
        return event.length() <= 256 ? event : event.substring(0, 256);
    }

    /** Immutable per-chain policy separates scientific request provenance from an untruncated retry. */
    private record TruncationPolicy(double requestedCutoff, double attemptCutoff) {
        private static final TruncationPolicy OFF = new TruncationPolicy(0.0, 0.0);

        private TruncationPolicy {
            V3TruncationSupport.requireCutoff(requestedCutoff);
            V3TruncationSupport.requireCutoff(attemptCutoff);
            if (attemptCutoff != 0.0 && attemptCutoff != requestedCutoff) {
                throw new IllegalArgumentException("V3 attempt cutoff must match the request or be disabled for fallback");
            }
        }
    }

    /** Keeps the frozen problem/support/seed together through correction, audit and publication. */
    private record PreparedAttempt(V3ColumnProblem problem, V3TruncationSupport support, V3DryMeshState seed) {
        private PreparedAttempt {
            Objects.requireNonNull(problem, "problem");
            Objects.requireNonNull(support, "support");
            Objects.requireNonNull(seed, "seed");
        }
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

    static V3ColumnInput withStageGeometry(V3ColumnInput input, int stageCount) {
        int feedStage = Math.clamp((int) Math.round(
                stageCount * input.feedStageNumber() / (double) input.stageCount()), 1, stageCount);
        return new V3ColumnInput(input.schemaVersion(), input.packageId(), input.assayId(), input.componentBasis(),
                input.feedComponentMolarFlowsMolPerSecond(), input.feedTemperatureKelvin(), stageCount, feedStage,
                input.topPressurePascal(), input.stagePressureDropPascal(), input.specifications(), List.of());
    }

    private static V3ColumnInput withoutSideDraws(V3ColumnInput input) {
        if (input.sideDraws().isEmpty()) return input;
        return new V3ColumnInput(input.schemaVersion(), input.packageId(), input.assayId(), input.componentBasis(),
                input.feedComponentMolarFlowsMolPerSecond(), input.feedTemperatureKelvin(), input.stageCount(),
                input.feedStageNumber(), input.topPressurePascal(), input.stagePressureDropPascal(),
                input.specifications(), List.of());
    }

    private static V3DryMeshState initializeForSolve(
            V3ColumnProblem problem, V3PengRobinsonThermo thermo, V3ColumnInitializer.Mode mode) {
        try {
            return V3ColumnInitializer.initialize(problem, thermo, thermo.newWorkspace(), mode).state();
        } catch (V3ThermoException | IllegalArgumentException failure) {
            String draws = problem.input().sideDraws().isEmpty() ? ""
                    : "; authored side-draw trays=" + problem.input().sideDraws().stream()
                            .map(V3SideDrawSpec::trayNumber).toList();
            throw new InitializationFailure("V3 initialization failed for " + problem.topology().trayCount()
                    + " trays" + draws + ": " + boundedSummary(failure.getMessage()), failure);
        }
    }

    private static V3ColumnInput withTopPressure(V3ColumnInput input, double topPressurePascal) {
        if (!Double.isFinite(topPressurePascal) || topPressurePascal <= 0.0) {
            throw new IllegalArgumentException("V3 pressure-continuation top pressure is invalid");
        }
        return new V3ColumnInput(input.schemaVersion(), input.packageId(), input.assayId(), input.componentBasis(),
                input.feedComponentMolarFlowsMolPerSecond(), input.feedTemperatureKelvin(), input.stageCount(),
                input.feedStageNumber(), topPressurePascal, input.stagePressureDropPascal(), input.specifications(), input.sideDraws());
    }

    private static List<Double> dwsimPressureSteps(double requestedTopPressurePascal) {
        return dwsimPressureSteps(requestedTopPressurePascal, false);
    }

    private static List<Double> dwsimPressureSteps(
            double requestedTopPressurePascal, boolean fineStepsFromAnchor) {
        List<Double> pressures = new java.util.ArrayList<>();
        double pressure = PRESSURE_CONTINUATION_ANCHOR_PASCAL;
        while (pressure > requestedTopPressurePascal) {
            double step = !fineStepsFromAnchor && pressure > PRESSURE_CONTINUATION_FINE_STEP_FROM_PASCAL
                    ? PRESSURE_CONTINUATION_STEP_PASCAL : PRESSURE_CONTINUATION_FINE_STEP_PASCAL;
            pressure = Math.max(requestedTopPressurePascal, pressure - step);
            pressures.add(pressure);
        }
        return List.copyOf(pressures);
    }

    private static String dwsimPressurePath(double requestedTopPressurePascal) {
        return dwsimPressurePath(requestedTopPressurePascal, false);
    }

    private static String dwsimPressurePath(double requestedTopPressurePascal, boolean fineStepsFromAnchor) {
        StringBuilder path = new StringBuilder();
        path.append(Math.round(PRESSURE_CONTINUATION_ANCHOR_PASCAL / 1_000.0));
        for (double pressure : dwsimPressureSteps(requestedTopPressurePascal, fineStepsFromAnchor)) {
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
            List<String> solverEvents,
            PreparedAttempt prepared) {
        private V3SolvePass {
            attempt = Objects.requireNonNull(attempt, "attempt");
            audit = Objects.requireNonNull(audit, "audit");
            recoverySeed = Objects.requireNonNull(recoverySeed, "recoverySeed");
            prepared = Objects.requireNonNull(prepared, "prepared");
            solverEvents = List.copyOf(Objects.requireNonNull(solverEvents, "solverEvents"));
            if (!Double.isFinite(feedMolarEnthalpyJoulesPerMol) || solvePath == null || solvePath.isBlank()
                    || terminalStageCount < V3ColumnInput.MIN_STAGE_COUNT
                    || solverEvents.size() > V3SolverDiagnostics.MAX_EVENTS
                    || solverEvents.stream().anyMatch(event -> event == null || event.length() > 256)) {
                throw new IllegalArgumentException("V3 solve pass evidence is invalid");
            }
        }
    }

    /** Distinguishes numerical seed construction from malformed authored input. */
    private static final class InitializationFailure extends RuntimeException {
        private InitializationFailure(String message, Throwable cause) {
            super(message, cause);
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
