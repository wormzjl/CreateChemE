package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3FlashResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3FlashTruncationEvidence;
import com.wormzjl.createcheme.science.column.v3.thermo.V3FeedPhase;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoException;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoModel;
import com.wormzjl.createcheme.science.column.v3.thermo.V3TraceTruncationPolicy;
import com.wormzjl.createcheme.science.column.v3.thermo.V3WaterProperties;
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
    public static final String FORMULATION_REVISION = "v3-dry-mesh-r24-flash-trace";
    private static final String LEGACY_FORMULATION_REVISION = "v3-dry-mesh-r23";
    public static final String ASSUMPTIONS_REVISION = "v3-dry-assumptions-r4";
    /** r2: free water is allowed on a tray, is immiscible with the hydrocarbon liquid, and is not side-drawn. */
    public static final String WET_ASSUMPTIONS_REVISION = "v3-wet-assumptions-r2";
    /**
     * Prescribed stage heat: a signed duty with positive adding heat, no circulating pumparound stream,
     * no return-temperature specification, and the {@link V3PumparoundSpec.Split} tray placement rule.
     */
    public static final String HEAT_ASSUMPTIONS_REVISION = "v3-heat-assumptions-r1";
    private static final String HEAT_FORMULATION_SUFFIX = "-stage-heat";
    private static final String HEAT_RAMP_LABEL = "heat-ramp";
    private static final int HEAT_RAMP_STEPS = 4;
    /** Side-draw rungs once a stage heat is authored; a cooled zone needs finer withdrawal increments. */
    private static final int HEAT_BEARING_DRAW_RAMP_STEPS = 8;
    private static final int MAXIMUM_HEAT_SUBDIVISIONS = 4;
    private static final int MAXIMUM_HEAT_SUBDIVISIONS_PER_RUNG = 2;
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
    /** Bounded reinsertion/removal passes over the always-on relative flow floor within one attempt. */
    private static final int MAXIMUM_FLOOR_SUPPORT_REFRESHES = 3;
    /** Only one of those passes may follow a stalled attempt; a second stall in a row is not new information. */
    private static final int MAXIMUM_STALLED_FLOOR_SUPPORT_REFRESHES = 1;
    /** Free-water tray-set refreshes within one attempt; only a converged attempt spends one. */
    private static final int MAXIMUM_WET_TRAY_REFRESHES = 3;
    /**
     * Newton budget of the one refresh that follows a stalled attempt and only removes points.
     *
     * <p>Measured on the 40 MW return-tray case: all four such refreshes stalled again with a worse final
     * residual than the attempt they repeated (0.070 to 0.220, 0.006 to 0.108, 0.007 to 0.127, 0.008 to
     * 0.148) and each spent a full 40-iteration budget, 2.7 s of an 11.1 s solve. A drop-only refresh cannot
     * repair a stalled attempt: the stalled state is what decided the removal, so the repeat starts from the
     * same place with fewer degrees of freedom. What it does contribute is state motion the continuation
     * ramp then re-derives its next rung's support from, and that is worth a bounded polish but not a second
     * full budget. Cutting it off entirely was measured and costs two cases (the 40 MW return-tray case and
     * {@code V3PumparoundSteamCalculatorTest.tjl19SolvesSumpSteamWithThreePumparounds}); one eighth of the
     * Newton budget keeps both and still removes about a quarter of the 40 MW case's cost. A refresh that
     * reinserts a point is different — it carries information the stalled state did not have — and keeps the
     * full budget.</p>
     */
    private static final int STALLED_DROP_REFRESH_ITERATIONS = MAXIMUM_NEWTON_ITERATIONS / 8;

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
        return calculate(input, control, stageTraceCutoffMoleFraction, 0.0);
    }

    /**
     * Calculates at an authored convergence closure.
     *
     * <p>{@code convergenceClosureFraction} is the relative closure every residual row must reach: a component
     * balance closes to that fraction of its own local throughput, an equilibrium row to that difference in log
     * composition, a tray energy row to that fraction of {@code F_total * 1e5 W}. Zero selects the frozen
     * default {@link V3ConvergenceEvidence#MAXIMUM_LOG_FLOW_CHANGE} and reproduces the default path bit for
     * bit, including its digest and formulation label. A positive value loosens the Newton stop, the final-step
     * gate, the equilibrium and condenser-split audit limits and the two energy-closure audit limits together,
     * and appends a closure suffix to the formulation label so an accepted result at one closure can never be
     * mistaken for one at another.</p>
     *
     * @throws IllegalArgumentException if the cutoff is nonfinite or outside [0, 0.01], or if the closure is
     *         nonfinite or outside [0, {@link V3ConvergenceEvidence#MAXIMUM_CLOSURE_TOLERANCE}]
     */
    public static V3ColumnOutcome calculate(
            V3ColumnInput input, V3SolveControl control, double stageTraceCutoffMoleFraction,
            double convergenceClosureFraction) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(control, "control");
        V3TruncationSupport.requireCutoff(stageTraceCutoffMoleFraction);
        double closure = closureTolerance(convergenceClosureFraction);
        if (stageTraceCutoffMoleFraction == 0.0) {
            return closure == V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE
                    ? calculate(input, control)
                    : calculate(input, control, V3ColumnInitializer.Mode.SEQUENTIAL_MATERIAL_VLE,
                            new SolvePolicy(0.0, 0.0, closure));
        }
        return V3TruncationFallback.calculate(stageTraceCutoffMoleFraction, attemptCutoff -> calculate(
                input, control, V3ColumnInitializer.Mode.SEQUENTIAL_MATERIAL_VLE,
                new SolvePolicy(stageTraceCutoffMoleFraction, attemptCutoff, closure)));
    }

    /**
     * Converts an authored closure request into the tolerance every gate uses.
     *
     * @throws IllegalArgumentException if the request is nonfinite or outside
     *         [0, {@link V3ConvergenceEvidence#MAXIMUM_CLOSURE_TOLERANCE}]
     */
    public static double closureTolerance(double convergenceClosureFraction) {
        if (!Double.isFinite(convergenceClosureFraction) || convergenceClosureFraction < 0.0
                || convergenceClosureFraction > V3ConvergenceEvidence.MAXIMUM_CLOSURE_TOLERANCE) {
            throw new IllegalArgumentException("V3 convergence closure fraction must be finite and in [0, 1e-3]");
        }
        return Math.max(V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE, convergenceClosureFraction);
    }

    /** Package-private cold-start qualifier for reviewed initializer modes; production uses the sequential MESH path. */
    static V3ColumnOutcome calculate(
            V3ColumnInput input, V3SolveControl control, V3ColumnInitializer.Mode initializerMode) {
        return calculate(input, control, initializerMode, SolvePolicy.OFF);
    }

    private static V3ColumnOutcome calculate(
            V3ColumnInput input, V3SolveControl control, V3ColumnInitializer.Mode initializerMode, SolvePolicy policy) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(control, "control");
        Objects.requireNonNull(initializerMode, "initializerMode");
        double totalDraw = input.sideDraws().stream().mapToDouble(V3SideDrawSpec::molarFlowMolPerSecond).sum();
        double totalFeed = java.util.Arrays.stream(input.feedComponentMolarFlowsMolPerSecond()).sum();
        if (totalDraw >= totalFeed) {
            return terminalFailure(V3SolverFailureCode.INFEASIBLE_SPECIFICATION,
                    "V3 total side draw rate must be less than the feed rate", "input/draws-" + input.sideDraws().size(), List.of());
        }
        V3ColumnOutcome.Failure inadmissibleCooling = staticCoolingAdmission(input);
        if (inadmissibleCooling != null) return inadmissibleCooling;
        CondenserAttempts condenserAttempts = new CondenserAttempts();
        if (initializerMode != V3ColumnInitializer.Mode.SEQUENTIAL_MATERIAL_VLE) {
            return calculateBranch(input, control, initializerMode, V3CondenserPhaseBranch.TWO_PHASE, policy, condenserAttempts);
        }
        V3CondenserPhaseBranch preferred = preferredCondenserBranch(input, control);
        V3ColumnOutcome outcome = calculateBranch(input, control, initializerMode, preferred, policy, condenserAttempts);
        if (outcome instanceof V3ColumnOutcome.Success
                || outcome instanceof V3ColumnOutcome.Failure failure
                && (failure.code() == V3SolverFailureCode.INVALID_INPUT
                || failure.code() == V3SolverFailureCode.PROPERTY_OUT_OF_RANGE
                || failure.code() == V3SolverFailureCode.INFEASIBLE_SPECIFICATION)) return outcome;
        V3CondenserPhaseBranch alternate = preferred == V3CondenserPhaseBranch.LIQUID_ONLY
                ? V3CondenserPhaseBranch.TWO_PHASE : V3CondenserPhaseBranch.LIQUID_ONLY;
        // A known phase mismatch has its bounded same-rung warm correction. Restarting either branch
        // from the smallest grid discards that useful profile and can revisit an inappropriate phase.
        if (!condenserAttempts.allowsColdRecovery() || condenserAttempts.hasAttempted(alternate)) return outcome;
        V3ColumnOutcome alternative = calculateBranch(input, control, initializerMode, alternate, policy, condenserAttempts);
        return alternative instanceof V3ColumnOutcome.Success ? alternative : outcome;
    }

    /**
     * Necessary static bound on the authored cooling, evaluated with one feed flash before any solve.
     *
     * <p>Returns {@code null} when the input is admissible or when the property package is unavailable; in the
     * latter case the ordinary admission path publishes the typed property failure instead.</p>
     *
     * <p>The bound is on the <em>net</em> authored heat: an authored stage heater adds enthalpy the coolers
     * below it can remove again, so its duty is credited to the heat-free column's budget. Without that
     * credit a cooler paired with an equal heater — no net stage heat anywhere — is rejected here.</p>
     */
    private static V3ColumnOutcome.Failure staticCoolingAdmission(V3ColumnInput input) {
        if (input.pumparounds().isEmpty()) return null;
        double cooling = -V3Pumparounds.totalCoolingWatts(input);
        if (!(cooling > 0.0)) return null;
        double heating = V3HeatFeasibility.heatingCreditWatts(input);
        double available;
        try {
            available = V3HeatFeasibility.availableCoolingWatts(
                    input, V3PengRobinsonThermo.fromRegisteredPackage(input.packageId()));
        } catch (V3ThermoException | IllegalArgumentException unavailableProperties) {
            return null;
        }
        if (!Double.isFinite(available) || cooling <= available + heating) return null;
        return terminalFailure(V3SolverFailureCode.INFEASIBLE_SPECIFICATION,
                V3HeatFeasibility.staticAdmissionDetail(cooling, available, heating),
                "input/heat-" + input.pumparounds().size(), List.of());
    }

    /** A seed flash only orders the branch attempts; the solved liquid outlet is independently audited. */
    private static V3CondenserPhaseBranch preferredCondenserBranch(V3ColumnInput input, V3SolveControl control) {
        control.checkpoint();
        try {
            V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(input.packageId());
            V3ColumnInput noDrawInput = withoutPumparounds(withoutSteamWithSurrogateDuty(withoutSideDraws(input)));
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
            V3CondenserPhaseBranch condenserBranch, SolvePolicy policy, CondenserAttempts condenserAttempts) {
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
                    // The material-closed fallback may rescue the dry surrogate after an early stage rung
                    // fails. It is still only a seed for an authored request that carries draws, steam or
                    // stage heat, and must never publish a no-draw, dry surrogate in place of that ramp.
                    if ((!input.steamFeeds().isEmpty() || !input.pumparounds().isEmpty()
                            || !input.sideDraws().isEmpty())
                            && publishesSuccess(pass.attempt(), pass.audit())
                            && !pass.prepared().problem().hasSideDraws()
                            && !pass.prepared().problem().hasSteamFeeds()
                            && !pass.prepared().problem().hasPumparounds()) {
                        V3ColumnProblem requested = V3ColumnProblemResolver.resolve(input,
                                pass.prepared().problem().topology().condenserPhaseBranch());
                        pass = recoverWithDrawRamp(requested, thermo, pass, control, policy, condenserAttempts);
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
                    PreparedAttempt coarsePrepared = prepareAttempt(originalProblem(pass), thermo, pass.recoverySeed(), policy);
                    V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(
                            coarsePrepared.problem(), thermo, pass.feedMolarEnthalpyJoulesPerMol());
                    V3SimultaneousColumnSolver.Attempt coarseAttempt = V3SimultaneousColumnSolver.solve(
                            coarsePrepared.problem(), evaluator, new V3DryMeshCoordinateMap(coarsePrepared.problem()),
                            coarsePrepared.seed(), thermo::newWorkspace,
                            V3ConvergenceEvidence.unavailable(policy.closureTolerance()), MAXIMUM_NEWTON_ITERATIONS,
                            policy.closureTolerance(),
                            V3FiniteDifferenceJacobian.DifferenceScale.COARSE, control);
                    control.checkpoint();
                    V3AcceptanceAudit coarseAudit = audit(coarsePrepared.problem(), thermo,
                            pass.feedMolarEnthalpyJoulesPerMol(), coarseAttempt.state(), control, policy);
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
            if (!input.steamFeeds().isEmpty()) solvePath += "/steam-" + input.steamFeeds().size();
            if (!input.pumparounds().isEmpty()) solvePath += "/heat-" + input.pumparounds().size();
            List<String> solverEvents = !selected.support().isIdentity()
                    ? mergedEvents(List.of(stageTraceEvent(selected.support(), attempt.state(), selected.problem())), pass.solverEvents())
                    : pass.solverEvents();
            V3SolverDiagnostics diagnostics = diagnostics(attempt, audit, solvePath, solverEvents, policy);
            if (pass.reachedRequestedProblem()
                    && attempt instanceof V3SimultaneousColumnSolver.Attempt.Converged converged && audit.accepted()
                    && converged.evidence().convergenceEvidence().satisfiesGates(policy.closureTolerance())) {
                String revision = formulationRevision(input, policy.requestedCutoff(), policy.closureTolerance());
                V3InputDigest digest = V3InputDigest.of(selected.problem(), revision,
                        thermo.datasetRevision(), assumptionsRevision(input), policy.requestedCutoff(),
                        policy.closureTolerance());
                V3ColumnResult result = V3ColumnResult.accepted(
                        selected.problem(), digest, audit, converged.evidence().convergenceEvidence(), converged.state(), thermo,
                        revision,
                        V3ColumnDutyLedger.fromAccepted(selected.problem(), converged.state(), thermo,
                                pass.feedMolarEnthalpyJoulesPerMol()));
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
                String operatingDetail = sideDrawDiagnostic(selected.problem(), attempt.state(), input.stageCount())
                        + waterDiagnostic(selected.problem(), attempt.state());
                if (!operatingDetail.isEmpty() && detail.length() + operatingDetail.length() > 512) {
                    detail = detail.substring(0, Math.max(0, 512 - operatingDetail.length()));
                }
                return new V3ColumnOutcome.Failure(failureCode(failure.code()), detail + operatingDetail, diagnostics);
            }
            return new V3ColumnOutcome.Failure(V3SolverFailureCode.ACCEPTANCE_AUDIT_FAILURE,
                    "The fresh V3 acceptance audit rejected the converged candidate"
                            + sideDrawDiagnostic(selected.problem(), attempt.state(), input.stageCount())
                            + waterDiagnostic(selected.problem(), attempt.state()), diagnostics);
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (InitializationFailure initialization) {
            return terminalFailure(V3SolverFailureCode.INITIALIZATION_FAILURE,
                    initialization.getMessage(), "initialization", advisoryEvidence);
        } catch (InfeasibleSpecification infeasible) {
            return terminalFailure(V3SolverFailureCode.INFEASIBLE_SPECIFICATION, infeasible.getMessage(),
                    infeasible.solvePath(), advisoryEvidence);
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
            V3CondenserPhaseBranch condenserBranch, SolvePolicy policy, CondenserAttempts condenserAttempts) {
        List<Integer> stageCounts = dwsimStageCounts(input.stageCount());
        String stagePath = dwsimStagePath(stageCounts);
        V3DryMeshState previousState = null;
        V3SolvePass lastPass = null;
        boolean featureRampRequired = featureRampRequired(input);
        V3ColumnInput continuationInput = withoutPumparounds(
                input.steamFeeds().isEmpty() ? input : withoutSteamWithSurrogateDuty(input));
        for (int stageCount : stageCounts) {
            control.checkpoint();
            V3ColumnInput stageInput = !featureRampRequired && stageCount == input.stageCount()
                    ? continuationInput : withStageGeometry(continuationInput, stageCount);
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
        if (featureRampRequired) {
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
            V3CondenserPhaseBranch condenserBranch, SolvePolicy policy, CondenserAttempts condenserAttempts) {
        if (input.topPressurePascal() > PRESSURE_CONTINUATION_TRIGGER_PASCAL) {
            throw new IllegalArgumentException("V3 pressure continuation was requested outside its low-pressure lane");
        }
        boolean steamRampRequired = !input.steamFeeds().isEmpty() || !input.pumparounds().isEmpty();
        V3ColumnInput continuationInput = steamRampRequired
                ? withoutPumparounds(withoutSideDraws(withoutSteamWithSurrogateDuty(input))) : input;
        boolean finePressureSteps = !input.sideDraws().isEmpty() || steamRampRequired;
        V3ColumnInput anchorInput = withTopPressure(continuationInput, PRESSURE_CONTINUATION_ANCHOR_PASCAL);
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
            V3ColumnInput stepInput = withTopPressure(continuationInput, pressure);
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
        if (steamRampRequired) {
            V3ColumnProblem requested = V3ColumnProblemResolver.resolve(input,
                    pass.prepared().problem().topology().condenserPhaseBranch());
            pass = recoverWithDrawRamp(requested, thermo, pass, control, policy, condenserAttempts);
            if (!publishesSuccess(pass.attempt(), pass.audit())) {
                return new V3SolvePass(pass.attempt(), pass.audit(), pass.feedMolarEnthalpyJoulesPerMol(),
                        "cold/dwsim-pressure/" + pressurePath + "/wet-ramp-failed", pass.recoverySeed(),
                        input.stageCount(), false, true, false, mergedEvents(pressureEvents, pass.solverEvents()), pass.prepared());
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
                boolean vaporPhase = problem.hasVaporUnknown(node, component);
                if (vaporPhase && (!Double.isFinite(state.vaporFlow(node, component)) || state.vaporFlow(node, component) <= 0.0)) {
                    return false;
                }
                if (!vaporPhase && state.vaporFlow(node, component) != 0.0) return false;
                boolean liquidPhase = problem.hasLiquidUnknown(node, component);
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
            SolvePolicy policy) {
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
            SolvePolicy policy) {
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
            SolvePolicy policy) {
        return solveSingleProblem(problem, thermo, seed, control, solvePath, ContinuationJacobianPolicy.NONE, policy);
    }

    private static V3SolvePass solveSingleProblem(
            V3ColumnProblem problem,
            V3PengRobinsonThermo thermo,
            V3DryMeshState seed,
            V3SolveControl control,
            String solvePath,
            ContinuationJacobianPolicy jacobianPolicy,
            SolvePolicy policy) {
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
            SolvePolicy policy) {
        if (maximumIterations < 1 || maximumIterations > MAXIMUM_NEWTON_ITERATIONS) {
            throw new IllegalArgumentException("V3 simultaneous solve iteration limit is invalid");
        }
        jacobianPolicy = Objects.requireNonNull(jacobianPolicy, "jacobianPolicy");
        control.checkpoint();
        V3FlashResult feedFlash = feedFlash(problem, thermo, control, policy);
        // A phase-allocation approximation must never change the authored feed's physical energy.
        double feedMolarEnthalpy = feedFlash.referenceMolarEnthalpyJoulesPerMol();
        V3DryMeshState recoverySeed = seed;
        V3ColumnProblem untruncated = problem;
        PreparedAttempt prepared = prepareAttempt(untruncated, thermo, seed, policy);
        SolveTelemetry telemetry;
        V3SimultaneousColumnSolver.Attempt attempt;
        V3AcceptanceAudit audit;
        int nextIterations = maximumIterations;
        int refreshes = 0;
        int stalledRefreshes = 0;
        int wetTrayRefreshes = 0;
        List<String> wetTrayEvents = new ArrayList<>();
        // The floor support is frozen for the length of one Newton solve, so it is re-derived from the
        // solved state afterwards: a point that fell below the floor is dropped, and a removed point whose
        // retained neighbours now deliver at least its floor is reinserted at the floor and the solve is
        // repeated from that state. Without the reinsertion pass the omitted mass would not be bounded;
        // with it, the audited sink-edge defect can never exceed one floor per edge.
        while (true) {
            V3ColumnProblem attemptProblem = prepared.problem();
            V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(
                    attemptProblem, thermo, feedMolarEnthalpy);
            telemetry = new SolveTelemetry(attemptProblem);
            V3DryMeshCoordinateMap coordinates = new V3DryMeshCoordinateMap(attemptProblem);
            V3DryMeshState attemptSeed = prepared.seed();
            attempt = switch (jacobianPolicy) {
                case NONE -> V3SimultaneousColumnSolver.solve(
                        attemptProblem, evaluator, coordinates, attemptSeed, thermo::newWorkspace,
                        V3ConvergenceEvidence.unavailable(policy.closureTolerance()), nextIterations,
                        policy.closureTolerance(),
                        V3FiniteDifferenceJacobian.DifferenceScale.FINE, control, telemetry);
                case STAGE_LOCAL_BLOCKS -> V3SimultaneousColumnSolver.solveWithContinuationLocalBlocks(
                        attemptProblem, evaluator, coordinates, attemptSeed, thermo::newWorkspace,
                        nextIterations, policy.closureTolerance(), control, telemetry);
                case PRESSURE_LOCAL_PREDICTOR -> V3SimultaneousColumnSolver.solveWithOneLocalBlockPredictor(
                        attemptProblem, evaluator, coordinates, attemptSeed, thermo::newWorkspace,
                        nextIterations, policy.closureTolerance(), control, telemetry);
            };
            control.checkpoint();
            audit = audit(attemptProblem, thermo, feedMolarEnthalpy, attempt.state(), control, policy);
            // A stalled attempt is refreshed too, and measurably must be: a stall is often exactly the state
            // that has just dried a point out or started feeding a removed one, and one repeat from the
            // corrected support is what converges the rung. Only one, though: a second stall in a row is a
            // rung the continuation is going to subdivide or abandon anyway, and re-solving it three times
            // triples the cost of every case that is on its way to a typed failure.
            boolean converged = attempt instanceof V3SimultaneousColumnSolver.Attempt.Converged;
            boolean floorBudget = refreshes < MAXIMUM_FLOOR_SUPPORT_REFRESHES
                    && (converged || stalledRefreshes < MAXIMUM_STALLED_FLOOR_SUPPORT_REFRESHES);
            // The wet set is re-derived only from a state that solved its own equations, and carries its own
            // small budget so it never has to compete with the support refreshes for one. How far a tray sits
            // below the water dew point, and how much water it must therefore shed, are readings of a
            // solution; taken off a stalled iterate they are noise, and a free-water flow seeded from noise
            // is megawatts of latent heat in the wrong place.
            boolean wetBudget = converged && wetTrayRefreshes < MAXIMUM_WET_TRAY_REFRESHES;
            if (!floorBudget && !wetBudget) break;
            PreparedAttempt refreshed = refreshFloorSupport(
                    untruncated, thermo, prepared, attempt.state(), policy, wetBudget);
            if (refreshed == null) break;
            // See STALLED_DROP_REFRESH_ITERATIONS: a refresh that only removes points from a stalled attempt
            // is a bounded polish, not a second full Newton solve.
            // A refresh that only removes — points from the support, trays from the wet set — carries no
            // information the stalled state did not already have. One that adds a wet tray does: the water
            // that tray sheds is a degree of freedom the stalled solve never had, so it keeps the full budget.
            nextIterations = !converged
                    && refreshed.support().presentPhaseCount() <= prepared.support().presentPhaseCount()
                    && refreshed.wetTrays().wetTrayCount() <= prepared.wetTrays().wetTrayCount()
                    ? Math.min(maximumIterations, STALLED_DROP_REFRESH_ITERATIONS)
                    : maximumIterations;
            if (!refreshed.support().sameRetention(prepared.support())) {
                refreshes++;
                if (!converged) stalledRefreshes++;
            }
            if (!refreshed.wetTrays().sameSet(prepared.wetTrays())) {
                wetTrayRefreshes++;
                if (!refreshed.wetTrays().hasWetTrays()) {
                    // A refresh that only takes trays away is an energy step like a heat rung, and the
                    // predictor is what re-levels the profile for it.
                    refreshed = withWetEnergyShift(refreshed, thermo, feedMolarEnthalpy, control, wetTrayEvents);
                } else {
                    // A refresh that admits one places its free water by continuation instead: the
                    // simultaneous system cannot see past the energy plateau the free-water unknown creates,
                    // while a parametric solve re-solves every energy row exactly at each step.
                    PreparedAttempt continued = withFreeWaterContinuation(
                            untruncated, thermo, feedMolarEnthalpy, refreshed, control, policy, wetTrayEvents);
                    if (continued != null) {
                        refreshed = continued;
                    } else if (refreshed.support().sameRetention(prepared.support())) {
                        // The derived wet set has no realisable free-water flow and nothing else moved. The
                        // converged candidate this refresh came from is the answer the column has, and its
                        // WATER_DEW_POINT check reports the supersaturated stage by name.
                        break;
                    } else {
                        // The support still has to be refreshed, but a set the continuation has refused is
                        // not derived again on this solve: it would cost the same solves for the same answer.
                        wetTrayRefreshes = MAXIMUM_WET_TRAY_REFRESHES;
                        refreshed = prepareAttempt(
                                untruncated, thermo, attempt.state(), policy, prepared.wetTrays(), false);
                    }
                }
            }
            prepared = refreshed;
        }
        problem = prepared.problem();
        List<String> events = telemetry.events();
        if (refreshes > 0) {
            events = mergedEvents(List.of("floor support refreshed " + refreshes + " time(s); retained="
                    + (prepared.support().totalPointCount() - prepared.support().truncatedPointCount())
                    + "/" + prepared.support().totalPointCount()), events);
        }
        // The wet-tray events are published even when the set was refused: why a supersaturated stage did
        // not take a free-water phase is exactly what an operator reading a failed dew-point check needs.
        if (prepared.wetTrays().hasWetTrays()) {
            events = mergedEvents(mergedEvents(List.of(prepared.wetTrays().event(attempt.state())), wetTrayEvents), events);
        } else if (!wetTrayEvents.isEmpty()) {
            events = mergedEvents(wetTrayEvents, events);
        }
        if (policy.attemptCutoff() > 0.0) events = mergedEvents(List.of(flashTraceEvent(feedFlash)), events);
        if (!prepared.support().note().isEmpty()) {
            events = mergedEvents(List.of("stage-trace support: " + prepared.support().note()), events);
        }
        return new V3SolvePass(attempt, audit, feedMolarEnthalpy, solvePath,
                recoverySeed, problem.input().stageCount(), true, true, true, events, prepared);
    }

    /** The authored feed's flash at the feed tray, truncated exactly as this attempt's policy asks. */
    private static V3FlashResult feedFlash(
            V3ColumnProblem problem, V3PengRobinsonThermo thermo, V3SolveControl control, SolvePolicy policy) {
        return policy.attemptCutoff() > 0.0
                ? thermo.flashTP(problem.input().feedTemperatureKelvin(),
                        problem.nodePressurePascal(problem.topology().feedTrayNumber()),
                        problem.input().feedComponentMolarFlowsMolPerSecond(), V3TraceTruncationPolicy.of(policy.attemptCutoff()),
                        thermo.newWorkspace(), control::checkpoint)
                : thermo.flashTP(problem.input().feedTemperatureKelvin(),
                        problem.nodePressurePascal(problem.topology().feedTrayNumber()),
                        problem.input().feedComponentMolarFlowsMolPerSecond(), thermo.newWorkspace());
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
            SolvePolicy policy, CondenserAttempts condenserAttempts) {
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

    /** One immutable authored-feature continuation point. */
    private record RampStep(
            double steamFraction, double heatFraction, double drawFraction, double labelFraction,
            String pathLabel, String description) {
        private RampStep {
            if (!Double.isFinite(steamFraction) || steamFraction < 0.0 || steamFraction > 1.0
                    || !Double.isFinite(heatFraction) || heatFraction < 0.0 || heatFraction > 1.0
                    || !Double.isFinite(drawFraction) || drawFraction < 0.0 || drawFraction > 1.0
                    || !Double.isFinite(labelFraction) || labelFraction < 0.0 || labelFraction > 1.0) {
                throw new IllegalArgumentException("V3 continuation ramp fractions must be finite and within zero to one");
            }
            pathLabel = Objects.requireNonNull(pathLabel, "pathLabel");
            description = Objects.requireNonNull(description, "description");
        }

        /** Heat-free continuation point; the label fraction preserves the historical path strings. */
        private static RampStep legacy(double steamFraction, double drawFraction, String pathLabel, String description) {
            return new RampStep(steamFraction, 0.0, drawFraction,
                    Math.max(steamFraction, drawFraction), pathLabel, description);
        }

        boolean requested(V3ColumnInput input) {
            return (input.steamFeeds().isEmpty() || steamFraction == 1.0)
                    && (input.pumparounds().isEmpty() || heatFraction == 1.0)
                    && (input.sideDraws().isEmpty() || drawFraction == 1.0);
        }

        boolean heatRung() {
            return pathLabel.equals(HEAT_RAMP_LABEL);
        }

        double progress() {
            return labelFraction;
        }
    }

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

    /** Bounded authored-parameter ramp from a dry surrogate seed to liquid draws and free-water steam. */
    private static V3SolvePass recoverWithDrawRamp(
            V3ColumnProblem requested, V3PengRobinsonThermo thermo, V3SolvePass seedBase,
            V3SolveControl control, SolvePolicy policy, CondenserAttempts condenserAttempts) {
        V3ColumnInput input = requested.input();
        seedBase = Objects.requireNonNull(seedBase, "seedBase");
        if (!publishesSuccess(seedBase.attempt(), seedBase.audit())
                || seedBase.prepared().problem().hasSideDraws() || seedBase.prepared().problem().hasSteamFeeds()
                || seedBase.prepared().problem().hasPumparounds()) {
            throw new IllegalArgumentException("V3 feature ramp requires an accepted dry no-draw seed at requested geometry");
        }
        String rampPath = seedBase.solvePath();
        V3SolvePass previous = seedBase;
        CondenserAttempts rampAttempts = new CondenserAttempts();
        List<String> rampEvents = new ArrayList<>();
        boolean intermediateFailed = false;
        // The first fixed-geometry handoff uses the material projection. Later parameter rungs retain the
        // accepted state directly; reprojecting every rung was measured to cause residual stagnation.
        double totalSteamMolPerSecond = input.steamFeeds().stream()
                .mapToDouble(V3SteamFeedSpec::molarFlowMolPerSecond).sum();
        // Water is a known profile, so a rung adds its whole increment to the VLE dilution term and the
        // condenser split at once: aim for 4 mol/s per rung. The count is capped so a large stripping rate
        // cannot make the ramp unbounded, and at the cap the physical increment grows again. Twelve rungs
        // put 27.8 mol/s on the first rung of a 1200 kmol/h sump-steam column and diverged there whenever
        // the authored reboiler duty was small enough that the boilup surrogate dominated it; twenty-four
        // rungs halve that increment and converge, at about three seconds of extra continuation.
        int steamRampSteps = Math.max(4, Math.min(24, (int) Math.ceil(totalSteamMolPerSecond / 4.0)));
        List<RampStep> rampSteps = new ArrayList<>(rampSteps(input, steamRampSteps));
        HeatSubdivisions subdivisions = new HeatSubdivisions();
        EnergyShiftLog energyShift = new EnergyShiftLog();
        // The heat and steam fractions the seed profile currently belongs to. The dry surrogate seed carries
        // no stage heat and no water, so both start at zero, and it is accepted by this method's precondition.
        double seedHeatFraction = 0.0;
        double seedSteamFraction = 0.0;
        boolean seedAccepted = true;
        double[] rungFeedEnthalpy = {Double.NaN, Double.NaN};
        double acceptedHeatFraction = 0.0;
        boolean condenserBoundChecked = false;
        for (int index = 0; index < rampSteps.size(); index++) {
            RampStep rampStep = rampSteps.get(index);
            if (intermediateFailed && !rampStep.requested(input)) continue;
            control.checkpoint();
            // Q_cond0 is the condenser duty of the same column without stage heat, which on a wet column
            // includes the water-vapor slip and the decanted free water. The dry surrogate seed carries a
            // replacement boilup and no condenser water at all, so the bound is taken from the last
            // accepted heat-free rung: for a steam-free input that is still the seed itself.
            if (!condenserBoundChecked && rampStep.heatFraction() > 0.0
                    && publishesSuccess(previous.attempt(), previous.audit())) {
                condenserBoundChecked = true;
                requireCoolingBelowBaseCondenserDuty(input, thermo, previous);
            }
            if (rampStep.heatRung()) {
                int cappedTray = condensationCappedTray(previous, thermo, input, rampStep.heatFraction());
                if (cappedTray > 0) {
                    RampStep midpoint = midpointHeatStep(rampStep, acceptedHeatFraction);
                    if (midpoint != null && subdivisions.allows(rampStep.heatFraction())) {
                        subdivisions.record(rampStep.heatFraction());
                        rampEvents.add(boundedEvent("stage-heat ramp subdivided at " + rampStep.heatFraction()
                                + ": tray " + cappedTray + " condensation cap"));
                        rampSteps.add(index, midpoint);
                        index--;
                        continue;
                    }
                    throw new InfeasibleSpecification(V3HeatFeasibility.condensationCapDetail(cappedTray,
                            condensationCapacityWatts(previous, thermo, cappedTray),
                            rampStep.heatFraction() * trayDutyWatts(input, cappedTray)),
                            "cold/heat-cap/heat-" + input.pumparounds().size());
                }
            }
            List<V3SideDrawSpec> draws = rampStep.drawFraction() == 0.0 ? List.of() : input.sideDraws().stream()
                    .map(draw -> new V3SideDrawSpec(draw.trayNumber(),
                            rampStep.drawFraction() * draw.molarFlowMolPerSecond())).toList();
            List<V3SteamFeedSpec> steam = rampStep.steamFraction() == 0.0 ? List.of() : input.steamFeeds().stream()
                    .map(feed -> new V3SteamFeedSpec(feed.stageNumber(),
                            rampStep.steamFraction() * feed.molarFlowMolPerSecond(),
                            feed.temperatureKelvin())).toList();
            List<V3PumparoundSpec> heat = V3Pumparounds.scaled(input, rampStep.heatFraction());
            double reboilerDuty = reboilerDutyWatts(input)
                    + (1.0 - rampStep.steamFraction()) * surrogateSteamDutyWatts(input);
            V3ColumnInput rampInput = new V3ColumnInput(input.schemaVersion(), input.packageId(), input.assayId(),
                    input.componentBasis(), input.feedComponentMolarFlowsMolPerSecond(), input.feedTemperatureKelvin(),
                    input.stageCount(), input.feedStageNumber(), input.topPressurePascal(), input.stagePressureDropPascal(),
                    withReboilerDuty(input, reboilerDuty), draws, steam, heat);
            V3CondenserPhaseBranch branch = previous.prepared().problem().topology().condenserPhaseBranch();
            V3ColumnProblem problem = V3ColumnProblemResolver.resolve(rampInput, branch);
            rampAttempts.recordAttempt(branch);
            V3DryMeshState seed = previous == seedBase
                    ? continuationSeed(problem, previous.attempt().state(), thermo, control)
                    : previous.attempt().state();
            SolvePolicy rampPolicy = rampStep.requested(input) ? policy : policy.withoutCutoff();
            // A rung that changes the stage heat or the steam moves the whole temperature profile while the
            // flows barely move, and leaves every energy row short by the same amount: a long flat valley in
            // the merit that the line search crawls along. Predict that common shift before Newton starts.
            // A pure draw rung changes flows rather than heat and is left alone, and so is every continuation
            // grid, whose seed comes from a different geometry rather than from a different duty.
            //
            // The seed must also be an accepted state. The prediction linearises the energy rows around a
            // profile that satisfies its own rung with those flows; a state that failed its rung does not,
            // and its residual is not the duty increment the linearisation is solving for. Measured: the
            // 40-tray steam-plus-cooler column stalls its steam ramp at 5/12 and then jumps to the requested
            // input, and predicting a 40 K shift from that stalled state cost the case its convergence
            // (SUCCESS at 6.8e-14 became NONCONVERGENCE at 6.3e-7); skipping the prediction there keeps it.
            if (seedAccepted
                    && (rampStep.heatFraction() != seedHeatFraction || rampStep.steamFraction() != seedSteamFraction)) {
                seed = withEnergyShiftPrediction(problem, thermo, seed, control, rampPolicy, rampStep,
                        energyShift, rungFeedEnthalpy);
            }
            V3SolvePass pass = solveSingleProblem(problem, thermo, seed, control,
                    rampPath + "/" + rampStep.pathLabel() + "-" + rampStep.progress(),
                    ContinuationJacobianPolicy.STAGE_LOCAL_BLOCKS,
                    rampStep.requested(input) ? DRAW_RAMP_REQUESTED_MAXIMUM_ITERATIONS
                            : DRAW_RAMP_INTERMEDIATE_MAXIMUM_ITERATIONS, rampPolicy);
            pass = correctCondenserPhase(pass, thermo, control, rampPolicy, rampAttempts).pass();
            if (!publishesSuccess(pass.attempt(), pass.audit())) {
                if (rampStep.requested(input)) {
                    condenserAttempts.recordRequestedDrawRampFailure();
                    rampEvents.add(boundedEvent(
                            rampStep.description() + " reached the requested input and failed: " + rampEvidence(pass)));
                    V3SolvePass annotated = withRampEvents(pass, energyShift.merged(rampEvents));
                    return withPriorSupportNotes(seedBase, annotated);
                }
                String event = rampStep.description() + " stopped at " + rampStep.progress() + ": " + rampEvidence(pass)
                        + "; failed checks=" + pass.audit().checks().stream().filter(check -> !check.passed())
                        .map(V3AcceptanceAudit.Check::family).toList();
                rampEvents.add(boundedEvent(event));
                RampStep midpoint = rampStep.heatRung() ? midpointHeatStep(rampStep, acceptedHeatFraction) : null;
                if (midpoint != null && subdivisions.allows(rampStep.heatFraction())) {
                    // The last accepted state is still the better seed; retry the smaller heat increment.
                    subdivisions.record(rampStep.heatFraction());
                    rampSteps.add(index, midpoint);
                    index--;
                    continue;
                }
                // A failed intermediate fraction is still a finite fixed-geometry seed. The authored
                // full-rate problem must be attempted before returning a terminal diagnostic.
                previous = pass;
                seedHeatFraction = rampStep.heatFraction();
                seedSteamFraction = rampStep.steamFraction();
                seedAccepted = false;
                intermediateFailed = true;
                continue;
            }
            previous = pass;
            seedHeatFraction = rampStep.heatFraction();
            seedSteamFraction = rampStep.steamFraction();
            seedAccepted = true;
            if (rampStep.heatRung()) acceptedHeatFraction = rampStep.heatFraction();
        }
        condenserAttempts.recordAttempt(previous.prepared().problem().topology().condenserPhaseBranch());
        condenserAttempts.finishPhaseCorrection(true);
        return withPriorSupportNotes(seedBase, withRampEvents(previous, energyShift.merged(rampEvents)));
    }

    /**
     * Applies the enthalpy-consistent temperature shift of one heat or steam rung to that rung's seed.
     *
     * <p>The prediction is measured on the pair the attempt will actually solve — the truncation support the
     * rung's own policy derives, and the seed projected onto it — because a state whose truncated points hold
     * exact zeros cannot be evaluated against an identity-support problem. Only the temperature vector is
     * carried back to the caller's seed: the flows are frozen through the whole prediction, so the shift is
     * the same for the projected state and for the untruncated one the attempt will prepare for itself.</p>
     */
    private static V3DryMeshState withEnergyShiftPrediction(
            V3ColumnProblem problem, V3PengRobinsonThermo thermo, V3DryMeshState seed, V3SolveControl control,
            SolvePolicy policy, RampStep rampStep, EnergyShiftLog log, double[] feedEnthalpyCache) {
        int slot = policy.attemptCutoff() > 0.0 ? 1 : 0;
        if (Double.isNaN(feedEnthalpyCache[slot])) {
            feedEnthalpyCache[slot] = feedFlash(problem, thermo, control, policy).referenceMolarEnthalpyJoulesPerMol();
        }
        PreparedAttempt prepared = prepareAttempt(problem, thermo, seed, policy);
        V3EnergyShiftPredictor.Prediction prediction = V3EnergyShiftPredictor.predict(prepared.problem(), thermo,
                feedEnthalpyCache[slot], prepared.seed(), thermo.newWorkspace(), control);
        log.record(rampStep, prediction);
        return prediction.applyTo(seed, problem.topology());
    }

    /**
     * Re-levels the temperature profile after a free-water tray leaves the frozen set.
     *
     * <p>Losing a wet tray is an energy step of exactly the kind the ramp's rungs make: the free water it was
     * shedding carried its latent heat out of the tray below and into the tray itself, so the seed's energy
     * rows move by that duty while its temperatures still belong to the wet solution. That is the common mode
     * {@link V3EnergyShiftPredictor} solves for, and the gate it requires is satisfied here by construction: a
     * wet refresh only follows a converged attempt.</p>
     *
     * <p>A refresh that <em>admits</em> a tray is handled by {@link V3FreeWaterContinuation} instead, which
     * re-solves every energy row exactly at each of its steps rather than linearising them once.</p>
     */
    private static PreparedAttempt withWetEnergyShift(
            PreparedAttempt prepared, V3PengRobinsonThermo thermo, double feedMolarEnthalpyJoulesPerMol,
            V3SolveControl control, List<String> events) {
        V3EnergyShiftPredictor.Prediction prediction = V3EnergyShiftPredictor.predict(prepared.problem(), thermo,
                feedMolarEnthalpyJoulesPerMol, prepared.seed(), thermo.newWorkspace(), control);
        if (events.size() < V3SolverDiagnostics.MAX_EVENTS) {
            events.add(bounded(prediction.applied()
                    ? String.format(Locale.ROOT,
                            "free-water energy shift: largest %.3f K, scaled energy %.6g -> %.6g",
                            prediction.largestShiftKelvin(), prediction.scaledEnergyBefore(),
                            prediction.scaledEnergyAfter())
                    : "free-water energy shift declined: " + prediction.note()));
        }
        if (!prediction.applied()) return prepared;
        return new PreparedAttempt(prepared.problem(), prepared.support(), prepared.wetTrays(),
                prediction.applyTo(prepared.seed(), prepared.problem().topology()));
    }

    /**
     * Places the free water by continuation before the simultaneous wet system is asked to certify it.
     *
     * <p>Returns null when there is nothing to continue or the continuation declined, in which case the
     * caller keeps the existing path. On success the attempt is rebuilt around the set the continuation
     * ended on — which may be deeper than the refresh derived, because a parametric tray costs no ledger
     * change and the continuation may therefore admit the trays the falling water wets. The seed it returns
     * satisfies every row of the wet system already, so the certifying Newton starts inside its own
     * tolerance. See {@link V3FreeWaterContinuation}.</p>
     */
    private static PreparedAttempt withFreeWaterContinuation(
            V3ColumnProblem untruncated, V3PengRobinsonThermo thermo, double feedMolarEnthalpyJoulesPerMol,
            PreparedAttempt prepared, V3SolveControl control, SolvePolicy policy, List<String> events) {
        if (!prepared.wetTrays().hasWetTrays()) return null;
        V3FreeWaterContinuation.Result result = V3FreeWaterContinuation.run(untruncated, prepared.support(),
                prepared.wetTrays(), prepared.seed(), thermo, feedMolarEnthalpyJoulesPerMol,
                policy.closureTolerance(), control);
        if (events.size() < V3SolverDiagnostics.MAX_EVENTS) events.add(bounded(result.event()));
        if (!result.converged()) return null;
        V3ColumnProblem problem;
        try {
            problem = V3ColumnProblemResolver.withTruncation(untruncated, prepared.support(), result.wetTrays());
        } catch (IllegalArgumentException invalidLedger) {
            return null;
        }
        return new PreparedAttempt(problem, prepared.support(), result.wetTrays(),
                result.wetTrays().seed(problem, result.state()));
    }

    private static String bounded(String event) {
        return event.length() <= 256 ? event : event.substring(0, 256);
    }

    /** Bounded record of one ramp's energy-shift predictions, published as a single diagnostic event. */
    private static final class EnergyShiftLog {
        private int applied;
        private int declined;
        private double largestShiftKelvin;
        private String first = "";
        private String firstDeclined = "";

        void record(RampStep step, V3EnergyShiftPredictor.Prediction prediction) {
            if (!prediction.applied()) {
                declined++;
                if (firstDeclined.isEmpty()) firstDeclined = prediction.note();
                return;
            }
            applied++;
            largestShiftKelvin = Math.max(largestShiftKelvin, prediction.largestShiftKelvin());
            if (first.isEmpty()) {
                first = step.pathLabel() + "-" + step.progress() + " scaled energy "
                        + prediction.scaledEnergyBefore() + " -> " + prediction.scaledEnergyAfter();
            }
        }

        /** The predictor's own line first, then the ramp's, bounded by the published event contract. */
        List<String> merged(List<String> rampEvents) {
            if (applied == 0 && declined == 0) return rampEvents;
            List<String> events = new ArrayList<>();
            events.add(boundedEvent("energy-shift predictor: applied=" + applied + ", declined=" + declined
                    + ", largest shift=" + largestShiftKelvin + " K"
                    + (first.isEmpty() ? "" : ", first " + first)
                    + (firstDeclined.isEmpty() ? "" : ", declined because " + firstDeclined)));
            for (String event : rampEvents) {
                if (events.size() >= V3SolverDiagnostics.MAX_EVENTS) break;
                events.add(event);
            }
            return events;
        }
    }

    /** Separates water/phase continuation from draw withdrawal when both authored features are present. */
    private static List<RampStep> rampSteps(V3ColumnInput input, int steamRampSteps) {
        if (!input.pumparounds().isEmpty()) return heatBearingRampSteps(input, steamRampSteps);
        boolean hasSteam = !input.steamFeeds().isEmpty();
        boolean hasDraws = !input.sideDraws().isEmpty();
        if (hasSteam && hasDraws) {
            List<RampStep> steps = new ArrayList<>(steamRampSteps + 4);
            for (int step = 1; step <= steamRampSteps; step++) {
                steps.add(RampStep.legacy(step / (double) steamRampSteps, 0.0, "steam-ramp", "steam ramp"));
            }
            for (int step = 1; step <= 4; step++) {
                steps.add(RampStep.legacy(1.0, step / 4.0, "draw-ramp", "side-draw ramp"));
            }
            return List.copyOf(steps);
        }
        int steps = hasSteam ? steamRampSteps : 4;
        List<RampStep> result = new ArrayList<>(steps);
        String pathLabel = hasSteam ? "wet-ramp" : "draw-ramp";
        String description = hasSteam ? "wet ramp" : "side-draw ramp";
        for (int step = 1; step <= steps; step++) {
            double fraction = step / (double) steps;
            result.add(RampStep.legacy(hasSteam ? fraction : 0.0, hasDraws ? fraction : 0.0,
                    pathLabel, description));
        }
        return List.copyOf(result);
    }

    /**
     * Rung order for an authored stage heat: steam, then heat, then draws.
     *
     * <p>The surrogate boilup must be removed before anything else changes, and cooling above a draw tray
     * increases the liquid arriving there, which is what the draws need.</p>
     */
    private static List<RampStep> heatBearingRampSteps(V3ColumnInput input, int steamRampSteps) {
        boolean hasSteam = !input.steamFeeds().isEmpty();
        boolean hasDraws = !input.sideDraws().isEmpty();
        List<RampStep> steps = new ArrayList<>(steamRampSteps + 2 * HEAT_RAMP_STEPS);
        if (hasSteam) {
            for (int step = 1; step <= steamRampSteps; step++) {
                double fraction = step / (double) steamRampSteps;
                steps.add(new RampStep(fraction, 0.0, 0.0, fraction, "steam-ramp", "steam ramp"));
            }
        }
        for (int step = 1; step <= HEAT_RAMP_STEPS; step++) {
            double fraction = step / (double) HEAT_RAMP_STEPS;
            steps.add(new RampStep(hasSteam ? 1.0 : 0.0, fraction, 0.0, fraction, HEAT_RAMP_LABEL, "stage-heat ramp"));
        }
        if (hasDraws) {
            // Cooling has already changed the liquid traffic arriving at every draw tray, so a quarter of the
            // authored withdrawal is a larger perturbation here than on a heat-free column. The 30-stage CDU17
            // column with sump steam, three pumparounds and the three preset draws stalls at 1.5e-2 on the
            // last of four rungs and converges on eight.
            for (int step = 1; step <= HEAT_BEARING_DRAW_RAMP_STEPS; step++) {
                double fraction = step / (double) HEAT_BEARING_DRAW_RAMP_STEPS;
                steps.add(new RampStep(hasSteam ? 1.0 : 0.0, 1.0, fraction, fraction, "draw-ramp", "side-draw ramp"));
            }
        }
        return List.copyOf(steps);
    }

    /** Halves the remaining heat increment; null when the increment can no longer be split. */
    private static RampStep midpointHeatStep(RampStep failed, double acceptedHeatFraction) {
        double midpoint = 0.5 * (acceptedHeatFraction + failed.heatFraction());
        if (!(midpoint > acceptedHeatFraction) || !(midpoint < failed.heatFraction())) return null;
        return new RampStep(failed.steamFraction(), midpoint, failed.drawFraction(), midpoint,
                failed.pathLabel(), failed.description());
    }

    /** Bounded midpoint budget: at most two subdivisions of one rung and four in a whole ramp. */
    private static final class HeatSubdivisions {
        private final java.util.Map<Double, Integer> perRung = new java.util.HashMap<>();
        private int total;

        boolean allows(double heatFraction) {
            return total < MAXIMUM_HEAT_SUBDIVISIONS
                    && perRung.getOrDefault(heatFraction, 0) < MAXIMUM_HEAT_SUBDIVISIONS_PER_RUNG;
        }

        void record(double heatFraction) {
            perRung.merge(heatFraction, 1, Integer::sum);
            total++;
        }
    }

    /**
     * Rejects an authored cooling that is not below the base condenser duty of the same column without heat.
     *
     * <p>{@code Q_cond0} is recomputed from the last accepted heat-free state at the requested geometry, so
     * this is a state-based bound rather than a correlation. On a wet column that state already carries the
     * authored steam, so the duty includes the water-vapor slip and the free water leaving the drum.</p>
     *
     * <p>Like the static gate this compares the net authored heat: {@code Q_cond0} belongs to a column without
     * stage heat, so any authored heating is credited to it before the gross cooling is measured against it.</p>
     */
    private static void requireCoolingBelowBaseCondenserDuty(
            V3ColumnInput input, V3PengRobinsonThermo thermo, V3SolvePass heatFreeBase) {
        double cooling = -V3Pumparounds.totalCoolingWatts(input);
        if (!(cooling > 0.0)) return;
        double heating = V3HeatFeasibility.heatingCreditWatts(input);
        V3ColumnProblem base = heatFreeBase.prepared().problem();
        if (base.hasPumparounds()) {
            throw new IllegalStateException("V3 base condenser duty requires a heat-free continuation state");
        }
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(
                base, thermo, heatFreeBase.feedMolarEnthalpyJoulesPerMol());
        double baseCondenserDuty = V3ColumnDutyLedger.condenserDutyWatts(
                base, heatFreeBase.attempt().state(), evaluator, thermo.newWorkspace());
        if (!Double.isFinite(baseCondenserDuty) || cooling < Math.abs(baseCondenserDuty) + heating) return;
        throw new InfeasibleSpecification(
                V3HeatFeasibility.condenserBoundDetail(cooling, baseCondenserDuty, heating),
                "cold/heat-condenser-bound/heat-" + input.pumparounds().size());
    }

    /** First tray whose arriving vapor cannot release the duty of the next heat rung; zero when none. */
    private static int condensationCappedTray(
            V3SolvePass accepted, V3PengRobinsonThermo thermo, V3ColumnInput input, double heatFraction) {
        V3ColumnProblem problem = accepted.prepared().problem();
        var workspace = thermo.newWorkspace();
        for (int tray = 1; tray <= Math.min(problem.topology().trayCount(), input.stageCount()); tray++) {
            double duty = heatFraction * trayDutyWatts(input, tray);
            if (duty >= 0.0) continue;
            double capacity = V3HeatFeasibility.condensationCapacityWatts(
                    problem, accepted.attempt().state(), thermo, workspace, tray);
            if (-duty > capacity) return tray;
        }
        return 0;
    }

    private static double condensationCapacityWatts(V3SolvePass accepted, V3PengRobinsonThermo thermo, int tray) {
        return V3HeatFeasibility.condensationCapacityWatts(accepted.prepared().problem(), accepted.attempt().state(),
                thermo, thermo.newWorkspace(), tray);
    }

    private static double trayDutyWatts(V3ColumnInput input, int trayNumber) {
        double duty = 0.0;
        for (V3PumparoundSpec pumparound : input.pumparounds()) duty += pumparound.trayDutyWatts(trayNumber);
        return duty;
    }

    private static boolean featureRampRequired(V3ColumnInput input) {
        return !input.sideDraws().isEmpty() || !input.steamFeeds().isEmpty() || !input.pumparounds().isEmpty();
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

    /**
     * Formulation label of one authored input.
     *
     * <p>r9 to r15 replaced r2 to r8 across every family: component material balances are scaled by their own
     * local throughput instead of by the component's feed flow, and every stage point whose flow is below
     * {@link V3TruncationSupport#TRACE_FLOOR_FRACTION} of that component's feed is removed from the unknowns
     * and equations. r16 to r22 made that presence per phase. r23 to r29 retire the forced product-path
     * band: the trays between the feed tray and a side draw are decided by the flow like any other tray, and
     * a draw tray keeps the liquid it receives from above. Accepted trace profiles differ at each step, so
     * the digest must differ.</p>
     */
    static String formulationRevision(V3ColumnInput input, double requestedCutoff) {
        return formulationRevision(input, requestedCutoff, V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE);
    }

    /**
     * Formulation label of one authored input solved at {@code closureTolerance}.
     *
     * <p>A closure above the frozen default appends {@code -closure<mantissa>e<exponent>} (for example
     * {@code -closure1e-3}). Accepted states differ between closures — the whole point of the knob is that a
     * looser one accepts a state the default rejects — so the label, and therefore the digest, must differ.
     * The default closure appends nothing and keeps every historical label byte for byte.</p>
     */
    static String formulationRevision(V3ColumnInput input, double requestedCutoff, double closureTolerance) {
        return formulationRevisionWithoutClosure(input, requestedCutoff) + closureSuffix(closureTolerance);
    }

    /** Canonical {@code -closureMeN} suffix, or the empty string at the frozen default closure. */
    static String closureSuffix(double closureTolerance) {
        V3ConvergenceEvidence.requireClosure(closureTolerance);
        if (closureTolerance <= V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE) return "";
        int exponent = (int) Math.floor(Math.log10(closureTolerance));
        double mantissa = closureTolerance / Math.pow(10.0, exponent);
        String digits = new java.math.BigDecimal(mantissa)
                .round(new java.math.MathContext(6)).stripTrailingZeros().toPlainString();
        return "-closure" + digits + "e" + exponent;
    }

    private static String formulationRevisionWithoutClosure(V3ColumnInput input, double requestedCutoff) {
        V3TruncationSupport.requireCutoff(requestedCutoff);
        boolean heat = !input.pumparounds().isEmpty();
        String trace = requestedCutoff > 0.0 ? "-flash-trace" : "";
        if (!input.steamFeeds().isEmpty()) {
            // r30/r31: a tray below the water dew point carries a free-water phase. Water vapour is a
            // function of the state rather than a parameter, wet trays gain a free-water unknown and a
            // saturation row, and the tray energy rows carry the aqueous liquid in and out.
            return (heat ? "v3-wet-mesh-r31-steam" : "v3-wet-mesh-r30-steam")
                    + (!input.sideDraws().isEmpty() ? "-side-draws" : "") + trace
                    + (heat ? HEAT_FORMULATION_SUFFIX : "");
        }
        if (!input.sideDraws().isEmpty()) {
            return (heat ? "v3-dry-mesh-r26-side-draws" : "v3-dry-mesh-r25-side-draws") + trace
                    + (heat ? HEAT_FORMULATION_SUFFIX : "");
        }
        if (!heat) return formulationRevision(requestedCutoff);
        return "v3-dry-mesh-r27" + trace + HEAT_FORMULATION_SUFFIX;
    }

    static String assumptionsRevision(V3ColumnInput input) {
        String base = input.steamFeeds().isEmpty() ? ASSUMPTIONS_REVISION : WET_ASSUMPTIONS_REVISION;
        return input.pumparounds().isEmpty() ? base : base + "+" + HEAT_ASSUMPTIONS_REVISION;
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

    private static String waterDiagnostic(V3ColumnProblem problem, V3DryMeshState state) {
        if (!problem.hasSteamFeeds()) return "";
        double maximumRatio = 0.0;
        for (int node = 1; node <= problem.topology().reboilerNode(); node++) {
            double water = problem.waterVaporFlow(state, node);
            double temperature = state.temperatureKelvin(node);
            if (water == 0.0 || temperature >= 640.0 || temperature < V3WaterProperties.TRIPLE_POINT_KELVIN) continue;
            double hydrocarbon = 0.0;
            for (int component = 0; component < state.componentCount(); component++) hydrocarbon += state.vaporFlow(node, component);
            maximumRatio = Math.max(maximumRatio, problem.nodePressurePascal(node) * water / (hydrocarbon + water)
                    / V3WaterProperties.saturationPressurePascal(temperature));
        }
        // A wet tray sits exactly on the saturation line, so this ratio reads one there by construction.
        return String.format(Locale.ROOT, "; water dew-point saturation ratio %.5g (wet trays: %d)",
                maximumRatio, problem.wetTraySet().wetTrayCount());
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

    /**
     * The deciding seed is final here: interpolation/preconditioning happens outside this frozen attempt.
     *
     * <p>The seed is lifted first. A point removed by an earlier attempt or an earlier continuation grid
     * holds exact zeros, so it could never re-enter from the state alone; lifting restores every point whose
     * retained neighbours are delivering material to it before the support is derived, which keeps the
     * decision self-consistent at every rung instead of only at a refresh.</p>
     */
    private static PreparedAttempt prepareAttempt(
            V3ColumnProblem original, V3PengRobinsonThermo thermo, V3DryMeshState seed, SolvePolicy policy) {
        return prepareAttempt(original, thermo, seed, policy, V3WetTraySet.dry(original.topology()), false);
    }

    /**
     * Freezes both attempt-local decisions — the flow-floor support and the free-water tray set — together.
     *
     * <p>{@code previousWetTrays} is the set the candidate state belongs to, and it is what gives the wet
     * decision its hysteresis: a tray that is already wet stays wet until its solved free water falls below
     * a floor, while a dry tray needs its saturation ratio to clear one by a margin.</p>
     *
     * <p>With {@code deriveWetTrays} false the set is taken as given, and the first attempt of every solve
     * therefore runs <strong>dry</strong>. The free-water decision is not a structural one like the flow
     * floor: it asks how far a tray sits below the water dew point and how much water it must therefore
     * shed, and both readings come out of a state that has not satisfied its own equations as nonsense.
     * Measured on the literature column at its published duties, deriving the set from a stalled iterate put
     * 773 kmol/h of free water on tray one against a true value near 210, poisoned the top three energy rows
     * by 10, 6 and 4 MW, and left Newton accepting every step while the residual moved from 0.8454 to 0.8448
     * in 32 iterations. Only a converged attempt spends a wet refresh, which is the rule the energy-shift
     * predictor already follows for the same reason.</p>
     */
    private static PreparedAttempt prepareAttempt(
            V3ColumnProblem original, V3PengRobinsonThermo thermo, V3DryMeshState seed, SolvePolicy policy,
            V3WetTraySet currentWetTrays, boolean deriveWetTrays) {
        V3DryMeshState lifted = liftFloorSupport(original, thermo, seed);
        V3TruncationSupport support = V3TruncationSupport.derive(original, policy.attemptCutoff(), lifted);
        V3WetTraySet wetTrays = deriveWetTrays
                ? V3WetTraySet.derive(original, lifted, currentWetTrays) : currentWetTrays;
        V3ColumnProblem problem;
        try {
            problem = V3ColumnProblemResolver.withTruncation(original, support, wetTrays);
        } catch (IllegalArgumentException invalidLedger) {
            support = support.fallbackToIdentity(original,
                    "Stage-trace support fell back to identity: reduced ledger validation failed");
            wetTrays = V3WetTraySet.dry(original.topology());
            problem = original;
        }
        return new PreparedAttempt(problem, support, wetTrays,
                wetTrays.seed(problem, support.projectSeed(original, lifted)));
    }

    /**
     * Re-derives the floor support and the wet-tray set from a solved state.
     *
     * <p>Returns null when neither moved. The wet set is refreshed on exactly the same schedule as the
     * support and for the same reason: both are frozen for the length of one Newton solve, so the state that
     * solve produced is the first honest evidence about whether the decision was right.</p>
     */
    private static PreparedAttempt refreshFloorSupport(
            V3ColumnProblem untruncated, V3PengRobinsonThermo thermo, PreparedAttempt prepared,
            V3DryMeshState state, SolvePolicy policy, boolean deriveWetTrays) {
        PreparedAttempt refreshed = prepareAttempt(
                untruncated, thermo, state, policy, prepared.wetTrays(), deriveWetTrays);
        return refreshed.support().sameRetention(prepared.support())
                && refreshed.wetTrays().sameSet(prepared.wetTrays()) ? null : refreshed;
    }

    /**
     * Restores every phase the support floor removed that the current state says would carry material again.
     *
     * <p>Two rules, because a removed point and a removed phase are decided by different quantities.</p>
     *
     * <p>A point that is below the floor in both phases carries only what its retained neighbours deliver,
     * so the material inflow is the criterion, and the delivered material {@code I} is split by the local
     * equilibrium rather than evenly: {@code l = I / (1 + K_c V/L)}, {@code v = I − l}. The reinserted point
     * then satisfies its material row <em>and</em> its equilibrium row at the seed, so the refresh that
     * follows is the same one-to-three-iteration polish a drop is, instead of the five-to-eleven-iteration
     * restart an even split produced (measured: every reinsertion used to begin above a scaled residual of
     * two). Where the split gives one phase less than a floor the point simply re-enters as a one-phase
     * point, which is consistent: the same comparison decides it again in {@code derive}.</p>
     *
     * <p>The points are swept in flow direction, downward for the liquid the tray above delivers and then
     * upward for the vapour the tray below delivers, each pass reading the values the earlier lifts wrote.
     * A profile that re-enters over several trays is therefore restored in one pass; reading the unlifted
     * state instead made reinsertion a front that could only advance one tray per refresh, which is what
     * exhausted the refresh cap on the wet TJL19 case and lost it entirely at a cap of one.</p>
     *
     * <p>A one-phase point is different and is lifted first, by {@link #liftAbsentPhase}.</p>
     */
    static V3DryMeshState liftFloorSupport(
            V3ColumnProblem problem, V3ThermoModel thermo, V3DryMeshState state) {
        V3ColumnTopology topology = problem.topology();
        int components = problem.activeComponentBasis().componentCount();
        double refluxRatio = problem.input().specifications().stream()
                .filter(V3ColumnSpecification.OrganicRefluxRatio.class::isInstance)
                .map(V3ColumnSpecification.OrganicRefluxRatio.class::cast).findFirst().orElseThrow().ratio();
        double refluxFraction = refluxRatio / (1.0 + refluxRatio);
        double[][] liquid = new double[topology.nodeCount()][components];
        double[][] vapor = new double[topology.nodeCount()][components];
        double[] temperatures = new double[topology.nodeCount()];
        for (int node = 0; node < topology.nodeCount(); node++) {
            temperatures[node] = state.temperatureKelvin(node);
            for (int component = 0; component < components; component++) {
                liquid[node][component] = state.liquidFlow(node, component);
                vapor[node][component] = state.vaporFlow(node, component);
            }
        }
        V3StageEquilibriumRatios equilibrium =
                V3StageEquilibriumRatios.of(problem, thermo, state, thermo.newWorkspace());
        double[] withdrawalRetained = new double[topology.nodeCount()];
        for (int node = 0; node < topology.nodeCount(); node++) {
            withdrawalRetained[node] = 1.0 - problem.liquidWithdrawalFraction(state, node);
        }
        // One-phase points first: a phase restored here can feed a removed neighbour in the sweeps below.
        for (int node = 0; node < topology.nodeCount(); node++) {
            for (int component = 0; component < components; component++) {
                double floor = componentFloor(problem, component);
                boolean hasLiquid = hasLiquidPhase(problem, node, component);
                boolean hasVapor = topology.hasVaporPhase(node);
                boolean liquidAbove = hasLiquid && liquid[node][component] >= floor;
                boolean vaporAbove = hasVapor && vapor[node][component] >= floor;
                if (liquidAbove == vaporAbove) continue;
                liftAbsentPhase(equilibrium.node(node), liquid, vapor, node, component, floor,
                        hasLiquid, hasVapor, liquidAbove);
            }
        }
        for (boolean downward : new boolean[] {true, false}) {
            for (int index = 0; index < topology.nodeCount(); index++) {
                int node = downward ? index : topology.nodeCount() - 1 - index;
                for (int component = 0; component < components; component++) {
                    double floor = componentFloor(problem, component);
                    boolean hasLiquid = hasLiquidPhase(problem, node, component);
                    boolean hasVapor = topology.hasVaporPhase(node);
                    if ((hasLiquid && liquid[node][component] >= floor)
                            || (hasVapor && vapor[node][component] >= floor)) {
                        continue;
                    }
                    double inflow;
                    if (node == topology.condenserNode()) {
                        inflow = vapor[1][component];
                    } else {
                        inflow = node == 1
                                ? refluxFraction * liquid[0][component]
                                : withdrawalRetained[node - 1] * liquid[node - 1][component];
                        if (node < topology.reboilerNode()) inflow += vapor[node + 1][component];
                        if (node == topology.feedTrayNumber()) {
                            inflow += problem.activeComponentBasis().feedFlowMolPerSecond(component);
                        }
                    }
                    if (inflow < V3TruncationSupport.FLOOR_REINSERTION_FACTOR * floor) continue;
                    reinsertRemovedPoint(equilibrium.node(node), liquid, vapor, node, component, inflow,
                            hasLiquid, hasVapor);
                }
            }
        }
        return new V3DryMeshState(topology, components, liquid, vapor, temperatures,
                V3ColumnInitializer.freeWaterFlows(state));
    }

    /** Splits the delivered material over the point's present phases the way its equilibrium row would. */
    private static void reinsertRemovedPoint(
            V3StageEquilibriumRatios.Node ratios, double[][] liquid, double[][] vapor, int node, int component,
            double inflow, boolean hasLiquid, boolean hasVapor) {
        if (!hasLiquid || !hasVapor) {
            if (hasLiquid) liquid[node][component] = Math.max(liquid[node][component], inflow);
            if (hasVapor) vapor[node][component] = Math.max(vapor[node][component], inflow);
            return;
        }
        // Without properties the even split is the only defensible allocation, as it was before.
        double liquidShare = ratios == null ? 0.5 * inflow
                : inflow / (1.0 + ratios.equilibriumRatio(component) * ratios.vaporTotalMolPerSecond()
                        / ratios.liquidTotalMolPerSecond());
        if (!Double.isFinite(liquidShare) || liquidShare < 0.0) liquidShare = 0.0;
        liquidShare = Math.min(liquidShare, inflow);
        liquid[node][component] = Math.max(liquid[node][component], liquidShare);
        vapor[node][component] = Math.max(vapor[node][component], inflow - liquidShare);
    }

    private static double componentFloor(V3ColumnProblem problem, int component) {
        return problem.activeComponentBasis().flowScale(component) * V3TruncationSupport.TRACE_FLOOR_FRACTION;
    }

    private static boolean hasLiquidPhase(V3ColumnProblem problem, int node, int component) {
        return problem.topology().hasLiquidPhase(node)
                && problem.condenserComponentPhases().hasLiquid(problem.topology(), node, component);
    }

    /**
     * Restores the absent phase of a one-phase point when equilibrium implies it would carry ten floors.
     *
     * <p>An inflow test is the wrong criterion here and the delivered material is the wrong value: the phase
     * that is present already carries the whole inflow, so splitting it again would put a bulk flow into a
     * phase the state says is empty. What decides a one-phase point is the flow its own equilibrium row
     * would give the absent phase — {@code v* = K_c (V/L) l}, or {@code l* = v L / (K_c V)} — and lifting to
     * exactly that value leaves the point's material row off by {@code v*} against a throughput of order
     * {@code l}, which is small by construction. It is also the quantity {@code PHASE_TRUNCATION_DEFECT}
     * audits, so a phase this rule declines to restore is one the audit can bound.</p>
     */
    private static void liftAbsentPhase(
            V3StageEquilibriumRatios.Node ratios, double[][] liquid, double[][] vapor,
            int node, int component, double floor, boolean hasLiquid, boolean hasVapor, boolean liquidAbove) {
        if (ratios == null || !hasLiquid || !hasVapor) return;
        double implied = liquidAbove
                ? ratios.impliedVaporFlowMolPerSecond(component, liquid[node][component])
                : ratios.impliedLiquidFlowMolPerSecond(component, vapor[node][component]);
        if (implied < V3TruncationSupport.FLOOR_REINSERTION_FACTOR * floor) return;
        if (liquidAbove) vapor[node][component] = Math.max(vapor[node][component], implied);
        else liquid[node][component] = Math.max(liquid[node][component], implied);
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
                + support.truncatedPointCount() + "/" + support.totalPointCount() + "; one-phase="
                + support.onePhasePointCount() + "; closure-pruned="
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
    /**
     * Per-chain solve policy: the authored stage-trace cutoff and the convergence closure.
     *
     * <p>Both travel together into every {@code solveSingleProblem}, ramp rung, continuation stage, recovery,
     * condenser-phase correction and warm-start solve, so that no solve on a chain can be run at a different
     * closure from the one the chain publishes. The cutoff can be disabled for one rung
     * ({@link #withoutCutoff()}); the closure never can.</p>
     */
    private record SolvePolicy(double requestedCutoff, double attemptCutoff, double closureTolerance) {
        private static final SolvePolicy OFF =
                new SolvePolicy(0.0, 0.0, V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE);

        private SolvePolicy {
            V3TruncationSupport.requireCutoff(requestedCutoff);
            V3TruncationSupport.requireCutoff(attemptCutoff);
            V3ConvergenceEvidence.requireClosure(closureTolerance);
            if (attemptCutoff != 0.0 && attemptCutoff != requestedCutoff) {
                throw new IllegalArgumentException("V3 attempt cutoff must match the request or be disabled for fallback");
            }
        }

        /** Same closure, no stage-trace cutoff; the ramp's intermediate rungs run untruncated by design. */
        private SolvePolicy withoutCutoff() {
            return requestedCutoff == 0.0 && attemptCutoff == 0.0
                    ? this : new SolvePolicy(0.0, 0.0, closureTolerance);
        }
    }

    /** Keeps the frozen problem/support/wet set/seed together through correction, audit and publication. */
    private record PreparedAttempt(
            V3ColumnProblem problem, V3TruncationSupport support, V3WetTraySet wetTrays, V3DryMeshState seed) {
        private PreparedAttempt {
            Objects.requireNonNull(problem, "problem");
            Objects.requireNonNull(support, "support");
            Objects.requireNonNull(wetTrays, "wetTrays");
            Objects.requireNonNull(seed, "seed");
        }
    }

    /**
     * Grid schedule of the stage continuation: the seed grids 4, 8 and 15 where they are below the request,
     * then a doubling of 15 for as long as the doubled grid is strictly below the request, and the request
     * itself last.
     *
     * <p>A request of 30 or fewer stages therefore produces exactly the historical {@code {4, 8, 15}} prefix
     * plus the request, because the first doubled grid is already 30: no digest, solve path or timing of an
     * existing case moves. Above 30 the schedule gains 30, then 60: a request of 40 becomes 4-8-15-30-40 and
     * a request of 64 becomes 4-8-15-30-60-64.</p>
     *
     * <p>Measured on the plain TJL19 40-tray literature column (feed tray 37, 250 kPa, reflux 4.17, condenser
     * 332.15 K, 8 MW reboiler): the 15 to 40 jump hands Newton a seed whose energy rows are all short by the
     * same 285 kW and whose first direction the line search rejects at iteration 0, so the request fails after
     * 287 s. With the intermediate 30-stage grid the same column converges in 5.4 s.</p>
     */
    static List<Integer> dwsimStageCounts(int requestedStageCount) {
        List<Integer> result = new java.util.ArrayList<>();
        for (int stageCount : new int[] {4, 8, 15}) {
            if (stageCount < requestedStageCount) result.add(stageCount);
        }
        for (int stageCount = 30; stageCount < requestedStageCount; stageCount *= 2) result.add(stageCount);
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
                input.specifications(), List.of(), input.steamFeeds(), input.pumparounds());
    }

    /** Stage heat is never present in a cold seed; it is introduced only by continuation from an accepted state. */
    private static V3ColumnInput withoutPumparounds(V3ColumnInput input) {
        if (input.pumparounds().isEmpty()) return input;
        return new V3ColumnInput(input.schemaVersion(), input.packageId(), input.assayId(), input.componentBasis(),
                input.feedComponentMolarFlowsMolPerSecond(), input.feedTemperatureKelvin(), input.stageCount(),
                input.feedStageNumber(), input.topPressurePascal(), input.stagePressureDropPascal(),
                input.specifications(), input.sideDraws(), input.steamFeeds(), List.of());
    }

    /** Supplies a dry boilup surrogate only for continuation seeds; publication always returns to authored duty. */
    private static V3ColumnInput withoutSteamWithSurrogateDuty(V3ColumnInput input) {
        if (input.steamFeeds().isEmpty()) return input;
        return new V3ColumnInput(input.schemaVersion(), input.packageId(), input.assayId(), input.componentBasis(),
                input.feedComponentMolarFlowsMolPerSecond(), input.feedTemperatureKelvin(), input.stageCount(),
                input.feedStageNumber(), input.topPressurePascal(), input.stagePressureDropPascal(),
                withReboilerDuty(input, reboilerDutyWatts(input)
                        + surrogateSteamDutyWatts(input)), input.sideDraws(), List.of(), input.pumparounds());
    }

    private static double surrogateSteamDutyWatts(V3ColumnInput input) {
        return input.steamFeeds().stream().mapToDouble(V3SteamFeedSpec::molarFlowMolPerSecond).sum()
                * V3WaterProperties.vaporizationEnthalpy(450.0);
    }

    private static List<V3ColumnSpecification> withReboilerDuty(V3ColumnInput input, double watts) {
        return input.specifications().stream().map(specification -> specification instanceof V3ColumnSpecification.ReboilerDuty
                ? new V3ColumnSpecification.ReboilerDuty(watts) : specification).toList();
    }

    private static double reboilerDutyWatts(V3ColumnInput input) {
        return input.specifications().stream().filter(V3ColumnSpecification.ReboilerDuty.class::isInstance)
                .map(V3ColumnSpecification.ReboilerDuty.class::cast).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("V3 input is missing a reboiler-duty specification")).watts();
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
                input.feedStageNumber(), topPressurePascal, input.stagePressureDropPascal(), input.specifications(),
                input.sideDraws(), input.steamFeeds(), input.pumparounds());
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
            V3SolveControl control,
            SolvePolicy policy) {
        try {
            return new V3AcceptanceAuditor(problem, thermo, feedMolarEnthalpy, policy.closureTolerance())
                    .audit(state, thermo.newWorkspace(), control);
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
            List<String> solverEvents,
            SolvePolicy policy) {
        V3SimultaneousColumnSolver.Evidence evidence = attempt.evidence();
        double finalStepNorm = Math.max(evidence.convergenceEvidence().maximumLogFlowChange(),
                evidence.convergenceEvidence().maximumTemperatureChangeKelvin());
        List<String> events = new ArrayList<>(solverEvents);
        if (events.size() < V3SolverDiagnostics.MAX_EVENTS) events.add(evidence.termination());
        return new V3SolverDiagnostics(0, evidence.iterations(), 0, 0, evidence.maximumScaledResidual(), finalStepNorm,
                solvePath, events, audit, evidence.convergenceEvidence(), policy.closureTolerance());
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

    /** A physically impossible authored duty; reported as a typed specification failure, not nonconvergence. */
    private static final class InfeasibleSpecification extends RuntimeException {
        private final String solvePath;

        private InfeasibleSpecification(String message, String solvePath) {
            super(message);
            this.solvePath = Objects.requireNonNull(solvePath, "solvePath");
        }

        private String solvePath() {
            return solvePath;
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
