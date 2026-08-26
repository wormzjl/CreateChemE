package com.wormzjl.createcheme.science.column.nextgen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Bounded hydrocarbon/water implementation of the canonical inside-out/Sum-Rates solve.
 *
 * <p>The production path is deliberately one structured solver: per-component Thomas material
 * solves, Sum-Rates total updates, a tridiagonal temperature preconditioner, and rigorous PR
 * refreshes outside the inner loop.  It has no legacy, dense, or flash-column fallback.  Until
 * every final acceptance family passes, this class returns {@link DryColumnOutcome.Failure} and
 * exposes no partially converged product result.</p>
 */
public final class DryInsideOutColumnSolver {
    public static final String SOLVER_REVISION = "next-inside-out-sum-rates-r2-water-active-set";
    private static final int HYDROCARBON_COMPONENTS = 16;
    private static final double FLOW_FLOOR = 1.0e-18;
    private static final double NEGATIVE_FLOW_TOLERANCE = 1.0e-12;
    private static final double MAXIMUM_LOG_K = 40.0;
    private static final double MINIMUM_CP = 1.0;
    private static final double PR_DEPARTURE_CP_STEP_KELVIN = 0.25;
    private static final double[] BACKTRACK_FACTORS = {1.0, 0.5, 0.25, 0.125, 0.0625, 0.03125};
    private static final double[] CONTINUATION_TARGETS = {0.25, 0.50, 0.75, 1.0};
    private static final double MINIMUM_CONTINUATION_INTERVAL = 1.0 / 32.0;

    private final DrySolverLimits limits;

    public DryInsideOutColumnSolver() {
        this(DrySolverLimits.DEFAULT);
    }

    public DryInsideOutColumnSolver(DrySolverLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /** Resolves accepted input and returns an input-typed outcome rather than leaking resolution exceptions. */
    public DryColumnOutcome solve(ColumnNextInput input) {
        return solve(input, DrySolveControl.unbounded());
    }

    /** Resolves accepted input and polls the supplied control only at bounded safe points. */
    public DryColumnOutcome solve(ColumnNextInput input, DrySolveControl control) {
        if (input == null) {
            return immediateFailure(DrySolverFailureCode.INVALID_INPUT, "A next-column input is required");
        }
        try {
            return solve(ColumnProblem.resolve(input), control);
        } catch (IllegalArgumentException exception) {
            return immediateFailure(DrySolverFailureCode.INVALID_INPUT, boundedMessage(exception));
        }
    }

    /** Solves one previously resolved immutable problem without accessing runtime, packets, NBT, or world state. */
    public DryColumnOutcome solve(ColumnProblem problem) {
        return solve(problem, DrySolveControl.unbounded());
    }

    /**
     * Performs the canonical dry solve.  A damped cold retry changes only damping/trust-region
     * policy within this same solver; it is not a fallback model.
     */
    public DryColumnOutcome solve(ColumnProblem problem, DrySolveControl control) {
        return solve(problem, control, null);
    }

    /** Uses a compatible accepted block-local profile only as an initialization seed for this same solver. */
    public DryColumnOutcome solve(ColumnProblem problem, DrySolveControl control, NextWarmState warmStart) {
        if (problem == null) {
            return immediateFailure(DrySolverFailureCode.INVALID_INPUT, "A resolved column problem is required");
        }
        if (control == null) {
            return immediateFailure(DrySolverFailureCode.INVALID_INPUT, "A dry solve control is required");
        }
        boolean compatibleWarmStart = warmStart != null && warmStart.isCompatibleWith(problem);
        Attempt first = attempt(problem, control, limits.normalDamping(), limits.normalTemperatureTrustRegionKelvin(),
                compatibleWarmStart ? "WARM" : "COLD", compatibleWarmStart ? warmStart : null);
        if (first.outcome() instanceof DryColumnOutcome.Success) {
            return first.outcome();
        }
        if (!recoverable(first.code()) || control.checkpoint() != DrySolveControl.Signal.CONTINUE) {
            return first.outcome();
        }
        Attempt retry = attempt(problem, control, limits.recoveryDamping(), limits.recoveryTemperatureTrustRegionKelvin(),
                "DAMPED_COLD_RETRY", null);
        if (retry.outcome() instanceof DryColumnOutcome.Success || !recoverable(retry.code())) {
            return retry.outcome();
        }
        return continueFromDrySeed(problem, control);
    }

    /**
     * Recovery continuation remains inside the canonical solver.  It starts from the specified
     * dry, zero-drop, zero-draw state, then restores pressure drop, draws, and water feeds in the
     * prescribed milestones.  Only a fully accepted intermediate profile can seed a later step.
     */
    private DryColumnOutcome continueFromDrySeed(ColumnProblem fullProblem, DrySolveControl control) {
        ColumnNextInput fullInput = fullProblem.input();
        Attempt last = continuationAttempt(fullInput, 0.0, control, null, "CONTINUATION_SEED");
        if (isTerminalControl(last.code())) return last.outcome();
        if (!(last.outcome() instanceof DryColumnOutcome.Success seed)) {
            return continuationFailure(last, 0.0, "dry zero-drop/zero-draw seed did not converge");
        }

        double acceptedLambda = 0.0;
        NextWarmState warm = NextWarmState.fromCommitted(ColumnProblem.resolve(continuationInput(fullInput, 0.0)), seed);
        for (double target : CONTINUATION_TARGETS) {
            double candidate = target;
            while (true) {
                Attempt step = continuationAttempt(fullInput, candidate, control, warm,
                        "CONTINUATION_" + compactDiagnosticNumber(candidate));
                last = step;
                if (isTerminalControl(step.code())) return step.outcome();
                if (step.outcome() instanceof DryColumnOutcome.Success success) {
                    acceptedLambda = candidate;
                    if (acceptedLambda == 1.0) return success;
                    ColumnProblem acceptedProblem = ColumnProblem.resolve(continuationInput(fullInput, acceptedLambda));
                    warm = NextWarmState.fromCommitted(acceptedProblem, success);
                    if (acceptedLambda == target) break;
                    candidate = target;
                    continue;
                }
                if (candidate - acceptedLambda <= MINIMUM_CONTINUATION_INTERVAL) {
                    return continuationFailure(last, candidate,
                            "no accepted continuation step at or above the minimum interval");
                }
                candidate = acceptedLambda + 0.5 * (candidate - acceptedLambda);
            }
        }
        return continuationFailure(last, acceptedLambda, "continuation did not reach the requested operating point");
    }

    private Attempt continuationAttempt(
            ColumnNextInput fullInput, double lambda, DrySolveControl control, NextWarmState warmStart,
            String recoveryPath) {
        try {
            ColumnProblem problem = ColumnProblem.resolve(continuationInput(fullInput, lambda));
            return attempt(problem, control, limits.recoveryDamping(), limits.recoveryTemperatureTrustRegionKelvin(),
                    recoveryPath, warmStart);
        } catch (IllegalArgumentException exception) {
            DryColumnOutcome failure = immediateFailure(DrySolverFailureCode.CONTINUATION_FAILURE,
                    "Continuation input resolution failed: " + boundedMessage(exception));
            return new Attempt(failure, DrySolverFailureCode.CONTINUATION_FAILURE);
        }
    }

    static ColumnNextInput continuationInput(ColumnNextInput input, double lambda) {
        if (!Double.isFinite(lambda) || lambda < 0.0 || lambda > 1.0) {
            throw new IllegalArgumentException("Continuation fraction must be finite and in [0, 1]");
        }
        List<ColumnNextInput.SideDrawInput> sideDraws = new ArrayList<>(input.sideDraws().size());
        for (ColumnNextInput.SideDrawInput draw : input.sideDraws()) {
            sideDraws.add(new ColumnNextInput.SideDrawInput(draw.stageNumber(), draw.basis(), draw.authoredRate() * lambda));
        }
        List<ColumnNextInput.WaterSteamFeedInput> utilities = new ArrayList<>(input.utilityFeeds().size());
        for (ColumnNextInput.WaterSteamFeedInput utility : input.utilityFeeds()) {
            utilities.add(new ColumnNextInput.WaterSteamFeedInput(utility.mode(), utility.stageNumber(),
                    utility.molarFlowMolPerSecond() * lambda, utility.temperatureKelvin(), utility.upstreamPressurePascal()));
        }
        return new ColumnNextInput(input.schemaVersion(), input.packageId(), input.assayId(), input.crudeFeed(),
                input.stageCount(), input.crudeFeedStageNumber(), input.topPressurePascal(),
                input.stagePressureDropPascal() * lambda, input.condenserOutletTemperatureKelvin(),
                input.reboilerDutyWatts(), input.organicRefluxRatio(), sideDraws, utilities);
    }

    private static boolean isTerminalControl(DrySolverFailureCode code) {
        return code == DrySolverFailureCode.CANCELLED || code == DrySolverFailureCode.DEADLINE_EXCEEDED;
    }

    private static DryColumnOutcome continuationFailure(Attempt attempt, double lambda, String detail) {
        DrySolverDiagnostics previous = attempt.outcome().diagnostics();
        List<String> events = new ArrayList<>(previous.events());
        if (events.size() < DrySolverDiagnostics.MAX_EVENTS) {
            events.add("CONTINUATION_FAILURE lambda=" + compactDiagnosticNumber(lambda) + ": " + detail);
        }
        DrySolverDiagnostics diagnostics = new DrySolverDiagnostics(previous.outerIterations(), previous.innerIterations(),
                previous.thomasSolves(), previous.energyThomasSolves(), previous.propertyPhaseEvaluations(),
                previous.maximumThomasBackwardError(), previous.maximumInnerSumRatesResidual(),
                previous.maximumInnerEnergyResidual(), previous.finalFlowStateChange(),
                previous.finalTemperatureStateChangeKelvin(), "CONTINUATION", events, previous.acceptanceAudit());
        return new DryColumnOutcome.Failure(DrySolverFailureCode.CONTINUATION_FAILURE,
                "Continuation failed at lambda=" + compactDiagnosticNumber(lambda) + ": " + detail, diagnostics);
    }

    private Attempt attempt(
            ColumnProblem problem, DrySolveControl control, double damping, double temperatureTrustRegion,
            String recoveryPath, NextWarmState warmStart) {
        Workspace workspace = new Workspace(problem, recoveryPath, warmStart);
        try {
            checkpoint(control, workspace);
            initialize(workspace);
            solveWaterBalances(workspace);
            workspace.feedEnthalpy = feedEnthalpy(workspace);
            checkpoint(control, workspace);
            refreshRigorousModels(workspace, control);

            double previousOuterMetric = 1.0e-2;
            DryAcceptanceAudit lastAudit = nonconvergedAudit();
            for (int outer = 1; outer <= limits.maximumOuterIterations(); outer++) {
                workspace.outerIterations = outer;
                checkpoint(control, workspace);
                double innerTolerance = adaptiveInnerTolerance(previousOuterMetric);
                boolean innerConverged = false;
                for (int inner = 1; inner <= limits.maximumInnerIterations(); inner++) {
                    workspace.innerIterations++;
                    if (inner % limits.checkpointStride() == 0) checkpoint(control, workspace);
                    updateMassBasisSideDrawRates(workspace, damping);
                    refreshLocalK(workspace);
                    solveHydrocarbonBalances(workspace, damping);
                    solveWaterBalances(workspace);
                    double energyRatio = assembleEnergyResidualAndCorrection(
                            workspace, damping, temperatureTrustRegion);
                    workspace.currentInnerEnergyResidual = energyRatio;
                    // Snapshot only after the accepted energy step; otherwise a temperature correction could be
                    // hidden from the independent final state-change audit.
                    updateStateChange(workspace);
                    workspace.maximumInnerEnergyResidual = Math.max(workspace.maximumInnerEnergyResidual, energyRatio);
                    workspace.maximumInnerSumRatesResidual = Math.max(
                            workspace.maximumInnerSumRatesResidual, workspace.currentSumRatesResidual);
                    if (workspace.currentSumRatesResidual <= innerTolerance
                            && energyRatio <= innerTolerance
                            && workspace.currentFlowStateChange <= Math.max(1.0e-8, innerTolerance)
                            && workspace.currentTemperatureStateChange <= 1.0e-5) {
                        innerConverged = true;
                        break;
                    }
                }
                if (!innerConverged) {
                    boolean energyLimited = workspace.currentInnerEnergyResidual > workspace.currentSumRatesResidual;
                    throw abort(DrySolverFailureCode.INNER_NONCONVERGENCE,
                            energyLimited ? DryResidualFamily.ENERGY_BALANCE : DryResidualFamily.SUM_RATES,
                            energyLimited ? workspace.currentInnerEnergyResidual : workspace.currentSumRatesResidual,
                            innerTolerance, -1, -1,
                            "Inner forcing tolerance was not reached: sumRates="
                                    + compactDiagnosticNumber(workspace.currentSumRatesResidual)
                                    + " energy=" + compactDiagnosticNumber(workspace.currentInnerEnergyResidual)
                                    + " flowChange=" + compactDiagnosticNumber(workspace.currentFlowStateChange)
                                    + " temperatureChange=" + compactDiagnosticNumber(workspace.currentTemperatureStateChange)
                                    + " maximumTemperature=" + compactDiagnosticNumber(maximum(workspace.temperatures)));
                }

                checkpoint(control, workspace);
                solveWaterBalances(workspace);
                workspace.feedEnthalpy = feedEnthalpy(workspace);
                refreshRigorousModels(workspace, control);
                lastAudit = finalAcceptanceAudit(workspace);
                if (lastAudit.accepted()) {
                    // Section 7.6 final Thomas polish with the freshly rigorous K values.  If it
                    // perturbs any independent family, the next outer pass continues the same solver.
                    refreshLocalK(workspace);
                    solveHydrocarbonBalances(workspace, 1.0);
                    solveWaterBalances(workspace);
                    updateStateChange(workspace);
                    workspace.feedEnthalpy = feedEnthalpy(workspace);
                    refreshRigorousModels(workspace, control);
                    lastAudit = finalAcceptanceAudit(workspace);
                    if (lastAudit.accepted()) {
                        DrySolverDiagnostics diagnostics = diagnostics(workspace, lastAudit);
                        return new Attempt(new DryColumnOutcome.Success(toResult(workspace, lastAudit), diagnostics), null);
                    }
                }
                previousOuterMetric = maximumAuditRatio(lastAudit);
            }
            DryAcceptanceAudit audit = lastAudit;
            DryAcceptanceAudit.Check failure = audit.firstFailure().orElseThrow();
            throw abort(codeFor(failure.family()), failure.family(), failure.value(), failure.limit(),
                    failure.node(), failure.component(), "Outer iteration cap reached: " + failure.detail());
        } catch (Abort abort) {
            addEvent(workspace, abort.code() + ": " + abort.detail());
            DryAcceptanceAudit audit = abort.audit() == null ? failedAudit(abort) : abort.audit();
            return new Attempt(new DryColumnOutcome.Failure(abort.code(), abort.detail(), diagnostics(workspace, audit)), abort.code());
        } catch (IllegalArgumentException exception) {
            addEvent(workspace, "PROPERTY_OR_INPUT: " + boundedMessage(exception));
            Abort abort = abort(DrySolverFailureCode.PROPERTY_OUT_OF_RANGE, DryResidualFamily.PHASE_VALIDITY,
                    1.0, 0.0, -1, -1, boundedMessage(exception));
            return new Attempt(new DryColumnOutcome.Failure(abort.code(), abort.detail(), diagnostics(workspace, failedAudit(abort))),
                    abort.code());
        } catch (IllegalStateException exception) {
            DrySolverFailureCode code = exception.getMessage() != null && exception.getMessage().contains("TRIDIAGONAL_BREAKDOWN")
                    ? DrySolverFailureCode.TRIDIAGONAL_BREAKDOWN : DrySolverFailureCode.EOS_ROOT_FAILURE;
            addEvent(workspace, code + ": " + boundedMessage(exception));
            Abort abort = abort(code, DryResidualFamily.PHASE_VALIDITY, 1.0, 0.0, -1, -1, boundedMessage(exception));
            return new Attempt(new DryColumnOutcome.Failure(code, abort.detail(), diagnostics(workspace, failedAudit(abort))), code);
        } catch (RuntimeException exception) {
            addEvent(workspace, "INTERNAL: " + boundedMessage(exception));
            Abort abort = abort(DrySolverFailureCode.INTERNAL_INVARIANT_FAILURE, DryResidualFamily.PHASE_VALIDITY,
                    1.0, 0.0, -1, -1, boundedMessage(exception));
            return new Attempt(new DryColumnOutcome.Failure(abort.code(), abort.detail(), diagnostics(workspace, failedAudit(abort))),
                    abort.code());
        }
    }

    private void initialize(Workspace w) {
        ColumnNextInput input = w.problem.input();
        w.feedFlow = input.crudeFeed().molarFlowMolPerSecond();
        for (int component = 0; component < HYDROCARBON_COMPONENTS; component++) {
            w.feedFractions[component] = w.problem.feed().moleFraction(component);
            w.feedComponentFlows[component] = w.feedFlow * w.feedFractions[component];
        }
        double feedFractionTotal = sum(w.feedFractions);
        if (!(feedFractionTotal > 0.0) || Math.abs(feedFractionTotal - 1.0) > 1.0e-12) {
            throw abort(DrySolverFailureCode.PROPERTY_PACKAGE_MISMATCH, DryResidualFamily.INPUT_VALIDITY,
                    Math.abs(feedFractionTotal - 1.0), 1.0e-12, -1, -1,
                    "Dry hydrocarbon feed fractions must close on the 16-component PR basis");
        }

        w.betaReflux = input.organicRefluxRatio() / (1.0 + input.organicRefluxRatio());
        w.vaporOnlyOverhead = input.organicRefluxRatio() == 0.0;
        initializeSideDrawRates(w);
        double sideTotal = sumStages(w.sideDrawMolarRates, w.stages);
        if (!(sideTotal < w.feedFlow)) {
            throw abort(DrySolverFailureCode.INFEASIBLE_SPECIFICATION, DryResidualFamily.SPECIFICATION,
                    sideTotal, w.feedFlow, -1, -1, "Side draws leave no positive hydrocarbon product flow");
        }

        double remaining = w.feedFlow - sideTotal;
        double externalLiquid = w.vaporOnlyOverhead ? 0.0 : 0.20 * remaining;
        double condensate = w.vaporOnlyOverhead ? 0.0 : externalLiquid / (1.0 - w.betaReflux);
        double overheadVapor = (w.vaporOnlyOverhead ? 0.30 : 0.12) * remaining;
        double bottoms = remaining - externalLiquid - overheadVapor;
        if (!(bottoms > FLOW_FLOOR) || !Double.isFinite(condensate) || !Double.isFinite(overheadVapor)) {
            throw abort(DrySolverFailureCode.INITIALIZATION_FAILURE, DryResidualFamily.PHASE_VALIDITY,
                    1.0, 0.0, -1, -1, "Initial product traffic is not positive");
        }

        double topTemperature = input.condenserOutletTemperatureKelvin();
        double bottomTemperature = clamp(
                input.crudeFeed().temperatureKelvin(),
                topTemperature + 20.0,
                Math.min(w.problem.propertyPackage().maximumTemperatureKelvin() - 2.0, 875.0));
        for (int node = 0; node < w.nodes; node++) {
            double position = node / (double) (w.nodes - 1);
            w.temperatures[node] = node == 0 ? topTemperature : topTemperature + position * (bottomTemperature - topTemperature);
            w.previousTemperatures[node] = w.temperatures[node];
        }
        w.temperatures[0] = topTemperature;

        w.liquidTotals[0] = condensate;
        w.vaporTotals[0] = overheadVapor;
        double refluxFlow = w.betaReflux * condensate;
        double cumulativeDraw = 0.0;
        for (int stage = 1; stage <= w.stages; stage++) {
            cumulativeDraw += w.sideDrawMolarRates[stage];
            double feedContribution = stage >= w.topology.feedStage() ? 0.72 * w.feedFlow : 0.0;
            w.liquidTotals[stage] = Math.max(FLOW_FLOOR, refluxFlow + feedContribution - cumulativeDraw + 0.05 * w.feedFlow);
            w.vaporTotals[stage] = Math.max(FLOW_FLOOR, overheadVapor + 0.35 * w.feedFlow);
        }
        w.liquidTotals[w.nodes - 1] = bottoms;
        w.vaporTotals[w.nodes - 1] = Math.max(FLOW_FLOOR, 0.18 * w.feedFlow);
        if (w.vaporOnlyOverhead) w.liquidTotals[0] = 0.0;

        for (int node = 0; node < w.nodes; node++) {
            int offset = node * HYDROCARBON_COMPONENTS;
            for (int component = 0; component < HYDROCARBON_COMPONENTS; component++) {
                w.liquidComponentFlows[offset + component] = w.liquidTotals[node] * w.feedFractions[component];
                w.vaporComponentFlows[offset + component] = w.vaporTotals[node] * w.feedFractions[component];
            }
            w.rawLiquidTotals[node] = w.liquidTotals[node];
            w.rawVaporTotals[node] = w.vaporTotals[node];
        }
        if (w.vaporOnlyOverhead) {
            copyNode(w.vaporComponentFlows, 1, w.vaporComponentFlows, 0);
            w.rawVaporTotals[0] = w.rawVaporTotals[1];
            w.vaporTotals[0] = w.vaporTotals[1];
        }
        if (w.warmStart != null) {
            restoreWarmProfiles(w);
            addEvent(w, "WARM_START");
        }
        System.arraycopy(w.liquidComponentFlows, 0, w.previousLiquidComponentFlows, 0, w.liquidComponentFlows.length);
        System.arraycopy(w.vaporComponentFlows, 0, w.previousVaporComponentFlows, 0, w.vaporComponentFlows.length);

        w.waterFeeds = WaterFeedProfile.resolve(input, w.basis, w.problem.feed(), w.pressures);
        System.arraycopy(w.waterFeeds.molarFeedByNode(), 0, w.waterFeedMolarByNode, 0, w.nodes);
        System.arraycopy(w.waterFeeds.enthalpyFlowWattsByNode(), 0, w.waterFeedEnthalpyWattsByNode, 0, w.nodes);
        for (int node = 0; node < w.nodes; node++) {
            if (w.vaporOnlyOverhead && node == 0) continue;
            w.kernel.wilsonK(w.temperatures[node], w.hydrocarbonPartialPressures[node], w.componentScratch);
            w.kReferenceTemperatures[node] = w.temperatures[node];
            for (int component = 0; component < HYDROCARBON_COMPONENTS; component++) {
                int index = node * HYDROCARBON_COMPONENTS + component;
                w.localK[index] = w.componentScratch[component];
                w.lnKReference[index] = Math.log(w.componentScratch[component]);
                ComponentDescriptor descriptor = w.basis.hydrocarbon(component);
                w.lnKSlope[index] = -5.373 * (1.0 + descriptor.acentricFactor())
                        * descriptor.criticalTemperatureKelvin();
            }
        }
        for (int component = 0; component < HYDROCARBON_COMPONENTS; component++) {
            if (w.basis.hydrocarbon(component).estimatedHeavyResidue()
                    && w.feedFractions[component] > 0.0) {
                addEvent(w, "ESTIMATED_HEAVY_RESIDUE");
                break;
            }
        }
    }

    private void initializeSideDrawRates(Workspace w) {
        double feedMw = mixtureMolecularWeight(w, w.feedFractions);
        for (ColumnNextInput.SideDrawInput draw : w.topology.authoredSideDraws()) {
            double rate = draw.basis() == ColumnNextInput.AuthoredBasis.MOLAR
                    ? draw.authoredRate() : draw.authoredRate() / feedMw;
            if (!Double.isFinite(rate) || rate < 0.0) {
                throw abort(DrySolverFailureCode.INFEASIBLE_SPECIFICATION, DryResidualFamily.SPECIFICATION,
                        Math.abs(rate), 0.0, draw.stageNumber(), -1, "Side-draw rate cannot be represented on the dry basis");
            }
            w.sideDrawMolarRates[draw.stageNumber()] = rate;
            w.previousSideDrawMolarRates[draw.stageNumber()] = rate;
        }
    }

    private void restoreWarmProfiles(Workspace w) {
        double[] temperatures = w.warmStart.temperaturesKelvin();
        double[][] liquid = w.warmStart.liquidFlows();
        double[][] vapor = w.warmStart.vaporFlows();
        boolean[] wetMask = w.warmStart.wetWaterMask();
        if (temperatures.length != w.nodes || liquid.length != w.nodes || vapor.length != w.nodes
                || wetMask.length != w.nodes) {
            throw abort(DrySolverFailureCode.INTERNAL_INVARIANT_FAILURE, DryResidualFamily.INPUT_VALIDITY,
                    1.0, 0.0, -1, -1, "Compatible warm profile dimensions do not match the resolved topology");
        }
        for (int node = 0; node < w.nodes; node++) {
            if (liquid[node].length != HYDROCARBON_COMPONENTS || vapor[node].length != HYDROCARBON_COMPONENTS
                    || !Double.isFinite(temperatures[node])) {
                throw abort(DrySolverFailureCode.INTERNAL_INVARIANT_FAILURE, DryResidualFamily.INPUT_VALIDITY,
                        1.0, 0.0, node, -1, "Warm profile contains invalid component dimensions or temperature");
            }
            w.temperatures[node] = node == 0 ? w.problem.input().condenserOutletTemperatureKelvin() : temperatures[node];
            w.previousTemperatures[node] = w.temperatures[node];
            int offset = node * HYDROCARBON_COMPONENTS;
            double liquidTotal = 0.0;
            double vaporTotal = 0.0;
            for (int component = 0; component < HYDROCARBON_COMPONENTS; component++) {
                double liquidFlow = liquid[node][component];
                double vaporFlow = vapor[node][component];
                if (!Double.isFinite(liquidFlow) || !Double.isFinite(vaporFlow) || liquidFlow < 0.0 || vaporFlow < 0.0) {
                    throw abort(DrySolverFailureCode.INTERNAL_INVARIANT_FAILURE, DryResidualFamily.INPUT_VALIDITY,
                            1.0, 0.0, node, component, "Warm profile contains invalid component flow");
                }
                w.liquidComponentFlows[offset + component] = liquidFlow;
                w.vaporComponentFlows[offset + component] = vaporFlow;
                liquidTotal += liquidFlow;
                vaporTotal += vaporFlow;
            }
            if (w.vaporOnlyOverhead && node == 0) {
                w.liquidTotals[node] = 0.0;
                w.rawLiquidTotals[node] = 0.0;
            } else if (!(liquidTotal > FLOW_FLOOR)) {
                throw abort(DrySolverFailureCode.INTERNAL_INVARIANT_FAILURE, DryResidualFamily.INPUT_VALIDITY,
                        liquidTotal, FLOW_FLOOR, node, -1, "Warm profile has no required hydrocarbon liquid phase");
            } else {
                w.liquidTotals[node] = liquidTotal;
                w.rawLiquidTotals[node] = liquidTotal;
            }
            if (!(vaporTotal > FLOW_FLOOR)) {
                throw abort(DrySolverFailureCode.INTERNAL_INVARIANT_FAILURE, DryResidualFamily.INPUT_VALIDITY,
                        vaporTotal, FLOW_FLOOR, node, -1, "Warm profile has no required hydrocarbon vapor phase");
            }
            w.vaporTotals[node] = vaporTotal;
            w.rawVaporTotals[node] = vaporTotal;
            w.wetWaterMask[node] = wetMask[node];
        }
    }

    private double feedEnthalpy(Workspace w) {
        try {
            w.kernel.evaluate(w.problem.input().crudeFeed().temperatureKelvin(),
                    w.hydrocarbonPartialPressures[w.topology.feedStage()], w.feedFractions,
                    NextPengRobinsonKernel.Root.LIQUID, w.prWorkspace, w.feedLiquidEvaluation);
            w.kernel.evaluate(w.problem.input().crudeFeed().temperatureKelvin(),
                    w.hydrocarbonPartialPressures[w.topology.feedStage()], w.feedFractions,
                    NextPengRobinsonKernel.Root.VAPOR, w.prWorkspace, w.feedVaporEvaluation);
            w.propertyPhaseEvaluations += 2;
            double liquidPotential = phasePotential(w.feedFractions, w.feedLiquidEvaluation);
            double vaporPotential = phasePotential(w.feedFractions, w.feedVaporEvaluation);
            if (!Double.isFinite(liquidPotential) || !Double.isFinite(vaporPotential)) {
                throw abort(DrySolverFailureCode.PHASE_REGIME_MISMATCH, DryResidualFamily.PHASE_VALIDITY,
                        Double.MAX_VALUE, 0.0, w.topology.feedStage(), -1,
                        "Connected-stage feed PR phase comparison is non-finite");
            }
            NextPengRobinsonKernel.Evaluation selected = liquidPotential <= vaporPotential
                    ? w.feedLiquidEvaluation : w.feedVaporEvaluation;
            addEvent(w, liquidPotential <= vaporPotential ? "FEED_PHASE=LIQUID_OR_MERGED" : "FEED_PHASE=VAPOR");
            return mixtureIdealEnthalpy(w, w.feedFractions, w.problem.input().crudeFeed().temperatureKelvin())
                    + selected.residualEnthalpyJoulesPerMol();
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw abort(DrySolverFailureCode.PROPERTY_OUT_OF_RANGE, DryResidualFamily.PHASE_VALIDITY,
                    1.0, 0.0, w.topology.feedStage(), -1,
                    "Feed property initialization failed: " + boundedMessage(exception));
        }
    }

    private static double phasePotential(double[] composition, NextPengRobinsonKernel.Evaluation evaluation) {
        double potential = 0.0;
        double[] logPhi = evaluation.logFugacityCoefficients();
        for (int component = 0; component < HYDROCARBON_COMPONENTS; component++) {
            potential += composition[component] * logPhi[component];
        }
        return potential;
    }

    private void refreshRigorousModels(Workspace w, DrySolveControl control) {
        for (int node = 0; node < w.nodes; node++) {
            checkpoint(control, w);
            int offset = node * HYDROCARBON_COMPONENTS;
            if (w.vaporOnlyOverhead && node == 0) {
                normalizeNode(w.vaporComponentFlows, offset, w.rawVaporTotals[node], w.vaporCompositionScratch);
                evaluateVaporOnlyNode(w, node, offset);
                continue;
            }
            requireActiveTwoHydrocarbonPhases(w, node);
            normalizeNode(w.liquidComponentFlows, offset, w.rawLiquidTotals[node], w.liquidCompositionScratch);
            normalizeNode(w.vaporComponentFlows, offset, w.rawVaporTotals[node], w.vaporCompositionScratch);
            try {
                w.kernel.evaluatePair(w.temperatures[node], w.hydrocarbonPartialPressures[node], w.liquidCompositionScratch,
                        w.vaporCompositionScratch, w.prWorkspace, w.liquidEvaluation, w.vaporEvaluation);
            } catch (IllegalArgumentException | IllegalStateException exception) {
                throw abort(DrySolverFailureCode.EOS_ROOT_FAILURE, DryResidualFamily.PHASE_VALIDITY,
                        1.0, 0.0, node, -1, "PR refresh failed: " + boundedMessage(exception));
            }
            w.propertyPhaseEvaluations += 2;
            w.rigorousLiquidEnthalpy[node] = mixtureIdealEnthalpy(w, w.liquidCompositionScratch, w.temperatures[node])
                    + w.liquidEvaluation.residualEnthalpyJoulesPerMol();
            w.rigorousVaporEnthalpy[node] = mixtureIdealEnthalpy(w, w.vaporCompositionScratch, w.temperatures[node])
                    + w.vaporEvaluation.residualEnthalpyJoulesPerMol();
            w.liquidEnthalpyReference[node] = w.rigorousLiquidEnthalpy[node];
            w.vaporEnthalpyReference[node] = w.rigorousVaporEnthalpy[node];
            refreshCaloricSlopes(w, node);
            w.liquidCompressibility[node] = w.liquidEvaluation.compressibility();
            w.vaporCompressibility[node] = w.vaporEvaluation.compressibility();

            double oldTemperature = w.kReferenceTemperatures[node];
            double inverseTemperatureChange = 1.0 / w.temperatures[node] - 1.0 / oldTemperature;
            for (int component = 0; component < HYDROCARBON_COMPONENTS; component++) {
                int index = offset + component;
                double logK = w.liquidEvaluation.logFugacityCoefficients()[component]
                        - w.vaporEvaluation.logFugacityCoefficients()[component];
                if (!Double.isFinite(logK)) {
                    throw abort(DrySolverFailureCode.EOS_ROOT_FAILURE, DryResidualFamily.EQUILIBRIUM,
                            Double.MAX_VALUE, 1.0e-8, node, component, "Non-finite rigorous log K");
                }
                if (w.hasRigorousModel[node] && Math.abs(inverseTemperatureChange) > 1.0e-10) {
                    double secant = (logK - w.lnKReference[index]) / inverseTemperatureChange;
                    if (Double.isFinite(secant) && Math.abs(secant) <= 100_000.0) {
                        w.lnKSlope[index] = secant;
                    }
                }
                w.rigorousLnK[index] = logK;
                w.lnKReference[index] = logK;
            }
            w.kReferenceTemperatures[node] = w.temperatures[node];
            w.hasRigorousModel[node] = true;
        }
    }

    /**
     * The affine inner energy model uses a rigorous PR-departure derivative refreshed only in the outer property
     * pass. This is deliberately not a nested flash: it is two paired EOS evaluations at frozen compositions.
     */
    private void refreshCaloricSlopes(Workspace w, int node) {
        double temperature = w.temperatures[node];
        double lowerTemperature = temperature - PR_DEPARTURE_CP_STEP_KELVIN;
        double upperTemperature = temperature + PR_DEPARTURE_CP_STEP_KELVIN;
        if (lowerTemperature < w.problem.propertyPackage().minimumTemperatureKelvin()
                || upperTemperature > w.problem.propertyPackage().maximumTemperatureKelvin()) {
            // At a package endpoint retain the ideal contribution and let the final rigorous energy audit guard it.
            w.liquidHeatCapacity[node] = Math.max(MINIMUM_CP,
                    mixtureIdealHeatCapacity(w, w.liquidCompositionScratch, temperature));
            w.vaporHeatCapacity[node] = Math.max(MINIMUM_CP,
                    mixtureIdealHeatCapacity(w, w.vaporCompositionScratch, temperature));
            return;
        }
        try {
            w.kernel.evaluatePair(lowerTemperature, w.hydrocarbonPartialPressures[node], w.liquidCompositionScratch,
                    w.vaporCompositionScratch, w.prWorkspace, w.lowerLiquidDerivativeEvaluation,
                    w.lowerVaporDerivativeEvaluation);
            w.kernel.evaluatePair(upperTemperature, w.hydrocarbonPartialPressures[node], w.liquidCompositionScratch,
                    w.vaporCompositionScratch, w.prWorkspace, w.upperLiquidDerivativeEvaluation,
                    w.upperVaporDerivativeEvaluation);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw abort(DrySolverFailureCode.EOS_ROOT_FAILURE, DryResidualFamily.ENERGY_BALANCE,
                    Double.MAX_VALUE, 0.0, node, -1,
                    "PR departure heat-capacity refresh failed: " + boundedMessage(exception));
        }
        w.propertyPhaseEvaluations += 4;
        double denominator = upperTemperature - lowerTemperature;
        double liquidDepartureCp = (w.upperLiquidDerivativeEvaluation.residualEnthalpyJoulesPerMol()
                - w.lowerLiquidDerivativeEvaluation.residualEnthalpyJoulesPerMol()) / denominator;
        double vaporDepartureCp = (w.upperVaporDerivativeEvaluation.residualEnthalpyJoulesPerMol()
                - w.lowerVaporDerivativeEvaluation.residualEnthalpyJoulesPerMol()) / denominator;
        w.liquidHeatCapacity[node] = Math.max(MINIMUM_CP,
                mixtureIdealHeatCapacity(w, w.liquidCompositionScratch, temperature) + liquidDepartureCp);
        w.vaporHeatCapacity[node] = Math.max(MINIMUM_CP,
                mixtureIdealHeatCapacity(w, w.vaporCompositionScratch, temperature) + vaporDepartureCp);
    }

    private void evaluateVaporOnlyNode(Workspace w, int node, int offset) {
        try {
            w.kernel.evaluate(w.temperatures[node], w.hydrocarbonPartialPressures[node], w.vaporCompositionScratch,
                    NextPengRobinsonKernel.Root.VAPOR, w.prWorkspace, w.vaporEvaluation);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw abort(DrySolverFailureCode.EOS_ROOT_FAILURE, DryResidualFamily.PHASE_VALIDITY,
                    1.0, 0.0, node, -1, "Vapor-only condenser PR refresh failed: " + boundedMessage(exception));
        }
        w.propertyPhaseEvaluations++;
        w.rigorousVaporEnthalpy[node] = mixtureIdealEnthalpy(w, w.vaporCompositionScratch, w.temperatures[node])
                + w.vaporEvaluation.residualEnthalpyJoulesPerMol();
        w.vaporEnthalpyReference[node] = w.rigorousVaporEnthalpy[node];
        w.vaporHeatCapacity[node] = Math.max(MINIMUM_CP,
                mixtureIdealHeatCapacity(w, w.vaporCompositionScratch, w.temperatures[node]));
        w.vaporCompressibility[node] = w.vaporEvaluation.compressibility();
        Arrays.fill(w.rigorousLnK, offset, offset + HYDROCARBON_COMPONENTS, 0.0);
    }

    private void refreshLocalK(Workspace w) {
        for (int node = 0; node < w.nodes; node++) {
            if (w.vaporOnlyOverhead && node == 0) continue;
            double deltaInverseTemperature = 1.0 / w.temperatures[node] - 1.0 / w.kReferenceTemperatures[node];
            int offset = node * HYDROCARBON_COMPONENTS;
            for (int component = 0; component < HYDROCARBON_COMPONENTS; component++) {
                double logK = clamp(w.lnKReference[offset + component]
                        + w.lnKSlope[offset + component] * deltaInverseTemperature, -MAXIMUM_LOG_K, MAXIMUM_LOG_K);
                w.localK[offset + component] = Math.exp(logK);
            }
        }
    }

    private void updateMassBasisSideDrawRates(Workspace w, double damping) {
        for (ColumnNextInput.SideDrawInput draw : w.topology.authoredSideDraws()) {
            if (draw.basis() == ColumnNextInput.AuthoredBasis.MOLAR) {
                w.sideDrawMolarRates[draw.stageNumber()] = draw.authoredRate();
                continue;
            }
            int stage = draw.stageNumber();
            double liquidTotal = w.rawLiquidTotals[stage];
            if (!(liquidTotal > FLOW_FLOOR)) {
                throw abort(DrySolverFailureCode.NEGATIVE_PHASE_FLOW, DryResidualFamily.PHASE_VALIDITY,
                        1.0, 0.0, stage, -1, "Mass-basis side draw has no liquid phase");
            }
            int offset = stage * HYDROCARBON_COMPONENTS;
            for (int component = 0; component < HYDROCARBON_COMPONENTS; component++) {
                w.componentScratch[component] = w.liquidComponentFlows[offset + component] / liquidTotal;
            }
            double molecularWeight = mixtureMolecularWeight(w, w.componentScratch);
            double targetRate = draw.authoredRate() / molecularWeight;
            if (!Double.isFinite(targetRate) || targetRate < 0.0) {
                throw abort(DrySolverFailureCode.INFEASIBLE_SPECIFICATION, DryResidualFamily.SPECIFICATION,
                        Math.abs(targetRate), 0.0, stage, -1, "Mass side draw cannot be converted to molar rate");
            }
            double previous = w.previousSideDrawMolarRates[stage];
            double updated = previous + damping * (targetRate - previous);
            w.sideDrawMolarRates[stage] = updated;
            w.previousSideDrawMolarRates[stage] = updated;
        }
        if (!(sumStages(w.sideDrawMolarRates, w.stages) < w.feedFlow)) {
            throw abort(DrySolverFailureCode.INFEASIBLE_SPECIFICATION, DryResidualFamily.SPECIFICATION,
                    sumStages(w.sideDrawMolarRates, w.stages), w.feedFlow, -1, -1,
                    "Iteratively coupled side draws leave no positive product flow");
        }
    }

    private void solveHydrocarbonBalances(Workspace w, double damping) {
        double maximumBackwardError = 0.0;
        for (int component = 0; component < HYDROCARBON_COMPONENTS; component++) {
            for (int node = 0; node < w.nodes; node++) {
                w.componentKByNode[node] = w.localK[node * HYDROCARBON_COMPONENTS + component];
            }
            double error;
            try {
                if (w.vaporOnlyOverhead) {
                    w.topology.assembleVaporOnlyHydrocarbonRows(w.componentKByNode, w.liquidTotals, w.vaporTotals,
                            w.liquidTotals[w.nodes - 1], w.sideDrawMolarRates, w.feedComponentFlows[component],
                            w.vaporOnlyLower, w.vaporOnlyDiagonal, w.vaporOnlyUpper, w.vaporOnlyRhs);
                    error = ThomasTridiagonalSolver.solve(w.vaporOnlyLower, w.vaporOnlyDiagonal, w.vaporOnlyUpper,
                            w.vaporOnlyRhs, w.vaporOnlySolution, w.vaporOnlyCPrime, w.vaporOnlyDPrime);
                    w.liquidComponentFlows[component] = 0.0;
                    for (int stage = 1; stage <= w.stages; stage++) {
                        w.liquidComponentFlows[stage * HYDROCARBON_COMPONENTS + component]
                                = w.vaporOnlySolution[stage - 1];
                    }
                    w.liquidComponentFlows[(w.nodes - 1) * HYDROCARBON_COMPONENTS + component]
                            = w.vaporOnlySolution[w.vaporOnlySolution.length - 1];
                } else {
                    w.topology.assembleHydrocarbonRows(w.componentKByNode, w.liquidTotals, w.vaporTotals,
                            w.liquidTotals[0], w.liquidTotals[w.nodes - 1], w.betaReflux, w.sideDrawMolarRates,
                            w.feedComponentFlows[component], w.lower, w.diagonal, w.upper, w.rhs);
                    error = ThomasTridiagonalSolver.solve(w.lower, w.diagonal, w.upper, w.rhs, w.solution,
                            w.cPrime, w.dPrime);
                    for (int node = 0; node < w.nodes; node++) {
                        w.liquidComponentFlows[node * HYDROCARBON_COMPONENTS + component] = w.solution[node];
                    }
                }
            } catch (IllegalArgumentException | IllegalStateException exception) {
                throw abort(DrySolverFailureCode.TRIDIAGONAL_BREAKDOWN, DryResidualFamily.THOMAS_BACKWARD_ERROR,
                        Double.MAX_VALUE, 1.0e-12, -1, component,
                        "Hydrocarbon Thomas solve failed: " + boundedMessage(exception));
            }
            w.thomasSolves++;
            maximumBackwardError = Math.max(maximumBackwardError, error);
            if (!(error <= 1.0e-12)) {
                throw abort(DrySolverFailureCode.TRIDIAGONAL_BREAKDOWN, DryResidualFamily.THOMAS_BACKWARD_ERROR,
                        error, 1.0e-12, -1, component, "Hydrocarbon Thomas backward error exceeds the strict guard");
            }
        }
        w.maximumThomasBackwardError = Math.max(w.maximumThomasBackwardError, maximumBackwardError);
        deriveRawVaporFlowsAndTotals(w);
        relaxTrialTotals(w, damping);
    }

    /** Solves water separately from the PR basis, then publishes only its Dalton hydrocarbon partial pressure. */
    private void solveWaterBalances(Workspace w) {
        WaterActiveSet.Outcome outcome = WaterActiveSet.solve(new WaterActiveSet.Input(
                w.temperatures, w.pressures, w.rawVaporTotals, w.waterFeedMolarByNode, w.wetWaterMask),
                w.waterActiveSetWorkspace);
        if (outcome instanceof WaterActiveSet.Failure failure) {
            DrySolverFailureCode code = switch (failure.code()) {
                case WATER_ACTIVE_SET_FAILURE -> DrySolverFailureCode.WATER_ACTIVE_SET_FAILURE;
                case WATER_BALANCE_FAILURE, WATER_COMPLEMENTARITY_FAILURE -> DrySolverFailureCode.WATER_BALANCE_FAILURE;
                case WATER_PROPERTY_FAILURE -> DrySolverFailureCode.WATER_PROPERTY_FAILURE;
                default -> DrySolverFailureCode.PHASE_VALIDITY_FAILURE;
            };
            throw abort(code, DryResidualFamily.WATER_BALANCE, Double.MAX_VALUE, 0.0, -1, -1,
                    "Water active set failed: " + failure.detail());
        }
        WaterActiveSet.Result result = (WaterActiveSet.Result) outcome;
        System.arraycopy(result.waterVaporMolPerSecond(), 0, w.waterVaporFlows, 0, w.nodes);
        System.arraycopy(result.aqueousLiquidMolPerSecond(), 0, w.aqueousWaterFlows, 0, w.nodes);
        System.arraycopy(result.wetMask(), 0, w.wetWaterMask, 0, w.nodes);
        System.arraycopy(result.waterPartialPressurePascal(), 0, w.waterPartialPressures, 0, w.nodes);
        System.arraycopy(result.hydrocarbonPartialPressurePascal(), 0, w.hydrocarbonPartialPressures, 0, w.nodes);
        w.maximumWaterBalanceResidual = result.diagnostics().maximumWaterBalanceResidual();
        w.maximumWaterComplementarityResidual = result.diagnostics().maximumComplementarityResidual();
        refreshWaterEnthalpies(w);
    }

    private void refreshWaterEnthalpies(Workspace w) {
        for (int node = 0; node < w.nodes; node++) {
            w.waterVaporEnthalpies[node] = 0.0;
            w.aqueousWaterEnthalpies[node] = 0.0;
            if (w.waterVaporFlows[node] > FLOW_FLOOR) {
                w.waterVaporEnthalpies[node] = WaterFeedProfile.alignedVaporEnthalpy(
                        w.temperatures[node], w.waterPartialPressures[node]);
            }
            if (w.aqueousWaterFlows[node] > FLOW_FLOOR) {
                w.aqueousWaterEnthalpies[node] = WaterFeedProfile.alignedLiquidEnthalpy(
                        w.temperatures[node], w.pressures[node]);
            }
            w.waterVaporHeatCapacities[node] = waterHeatCapacity(
                    w.temperatures[node], w.waterPartialPressures[node], true, w.waterVaporFlows[node]);
            w.aqueousWaterHeatCapacities[node] = waterHeatCapacity(
                    w.temperatures[node], w.pressures[node], false, w.aqueousWaterFlows[node]);
        }
    }

    private static double waterHeatCapacity(
            double temperatureKelvin, double pressurePascal, boolean vapor, double flowMolPerSecond) {
        if (flowMolPerSecond <= FLOW_FLOOR) return 0.0;
        double step = temperatureKelvin + PR_DEPARTURE_CP_STEP_KELVIN <= 900.0
                ? PR_DEPARTURE_CP_STEP_KELVIN : -PR_DEPARTURE_CP_STEP_KELVIN;
        double base = vapor ? WaterFeedProfile.alignedVaporEnthalpy(temperatureKelvin, pressurePascal)
                : WaterFeedProfile.alignedLiquidEnthalpy(temperatureKelvin, pressurePascal);
        double neighbour = vapor
                ? WaterFeedProfile.alignedVaporEnthalpy(temperatureKelvin + step, pressurePascal)
                : WaterFeedProfile.alignedLiquidEnthalpy(temperatureKelvin + step, pressurePascal);
        double heatCapacity = (neighbour - base) / step;
        if (!Double.isFinite(heatCapacity) || heatCapacity <= 0.0) {
            throw new IllegalArgumentException("Water heat-capacity slope is outside the IF97 phase envelope");
        }
        return heatCapacity;
    }

    private void deriveRawVaporFlowsAndTotals(Workspace w) {
        Arrays.fill(w.rawLiquidTotals, 0.0);
        Arrays.fill(w.rawVaporTotals, 0.0);
        Arrays.fill(w.sideDrawComponentFlows, 0.0);
        for (int node = 0; node < w.nodes; node++) {
            int offset = node * HYDROCARBON_COMPONENTS;
            double liquidTotal = 0.0;
            for (int component = 0; component < HYDROCARBON_COMPONENTS; component++) {
                double value = requireNonnegative(w.liquidComponentFlows[offset + component], w, node, component);
                w.liquidComponentFlows[offset + component] = value;
                liquidTotal += value;
            }
            w.rawLiquidTotals[node] = liquidTotal;
        }
        if (w.vaporOnlyOverhead) {
            w.rawLiquidTotals[0] = 0.0;
        }
        for (int node = w.vaporOnlyOverhead ? 1 : 0; node < w.nodes; node++) {
            int offset = node * HYDROCARBON_COMPONENTS;
            double liquidTrial = w.liquidTotals[node];
            double vaporTrial = w.vaporTotals[node];
            if (!(liquidTrial > FLOW_FLOOR) || !(vaporTrial > FLOW_FLOOR)) {
                throw abort(DrySolverFailureCode.NEGATIVE_PHASE_FLOW, DryResidualFamily.PHASE_VALIDITY,
                        1.0, 0.0, node, -1, "A hydrocarbon trial total is not positive");
            }
            double vaporTotal = 0.0;
            for (int component = 0; component < HYDROCARBON_COMPONENTS; component++) {
                double value = w.localK[offset + component] * vaporTrial / liquidTrial
                        * w.liquidComponentFlows[offset + component];
                value = requireNonnegative(value, w, node, component);
                w.vaporComponentFlows[offset + component] = value;
                vaporTotal += value;
            }
            w.rawVaporTotals[node] = vaporTotal;
        }
        if (w.vaporOnlyOverhead) {
            copyNode(w.vaporComponentFlows, 1, w.vaporComponentFlows, 0);
            w.rawVaporTotals[0] = w.rawVaporTotals[1];
        }
        for (int stage = 1; stage <= w.stages; stage++) {
            double ratio = w.sideDrawMolarRates[stage] / w.liquidTotals[stage];
            int offset = stage * HYDROCARBON_COMPONENTS;
            int drawOffset = stage * HYDROCARBON_COMPONENTS;
            for (int component = 0; component < HYDROCARBON_COMPONENTS; component++) {
                w.sideDrawComponentFlows[drawOffset + component] = ratio * w.liquidComponentFlows[offset + component];
            }
        }
    }

    private void relaxTrialTotals(Workspace w, double damping) {
        double maximum = 0.0;
        for (int node = 0; node < w.nodes; node++) {
            if (!(w.vaporOnlyOverhead && node == 0)) {
                double newLiquid = geometricRelax(w.liquidTotals[node], w.rawLiquidTotals[node], damping, node);
                maximum = Math.max(maximum, Math.abs(Math.log(w.rawLiquidTotals[node] / newLiquid)));
                w.liquidTotals[node] = newLiquid;
            } else {
                w.liquidTotals[node] = 0.0;
            }
            double newVapor = geometricRelax(w.vaporTotals[node], w.rawVaporTotals[node], damping, node);
            maximum = Math.max(maximum, Math.abs(Math.log(w.rawVaporTotals[node] / newVapor)));
            w.vaporTotals[node] = newVapor;
        }
        if (w.vaporOnlyOverhead) w.vaporTotals[0] = w.vaporTotals[1];
        w.currentSumRatesResidual = maximum;
    }

    /** Returns the scaled energy residual after accepting at most one merit-reducing tridiagonal step. */
    private double assembleEnergyResidualAndCorrection(Workspace w, double damping, double trustRegion) {
        double energyRatio = computeLocalEnergyResiduals(w);
        int thermalNodes = w.nodes - 1; // T1 through TN; T0 is specified.
        Arrays.fill(w.energyLower, 0.0);
        Arrays.fill(w.energyDiagonal, 0.0);
        Arrays.fill(w.energyUpper, 0.0);
        Arrays.fill(w.energyRhs, 0.0);
        for (int stage = 1; stage <= w.stages; stage++) {
            int row = stage - 1;
            double liquidOut = w.rawLiquidTotals[stage] + totalSideDrawAtStage(w, stage);
            w.energyDiagonal[row] = -(liquidOut * w.liquidHeatCapacity[stage]
                    + w.rawVaporTotals[stage] * w.vaporHeatCapacity[stage]
                    + w.aqueousWaterFlows[stage] * w.aqueousWaterHeatCapacities[stage]
                    + w.waterVaporFlows[stage] * w.waterVaporHeatCapacities[stage]);
            if (stage > 1) {
                w.energyLower[row] = w.rawLiquidTotals[stage - 1] * w.liquidHeatCapacity[stage - 1]
                        + w.aqueousWaterFlows[stage - 1] * w.aqueousWaterHeatCapacities[stage - 1];
            }
            w.energyUpper[row] = w.rawVaporTotals[stage + 1] * w.vaporHeatCapacity[stage + 1]
                    + w.waterVaporFlows[stage + 1] * w.waterVaporHeatCapacities[stage + 1];
            w.energyRhs[row] = -w.energyResiduals[stage];
        }
        int reboilerRow = thermalNodes - 1;
        w.energyLower[reboilerRow] = w.rawLiquidTotals[w.stages] * w.liquidHeatCapacity[w.stages];
        w.energyDiagonal[reboilerRow] = -(w.rawLiquidTotals[w.nodes - 1] * w.liquidHeatCapacity[w.nodes - 1]
                + w.rawVaporTotals[w.nodes - 1] * w.vaporHeatCapacity[w.nodes - 1]
                + w.aqueousWaterFlows[w.nodes - 1] * w.aqueousWaterHeatCapacities[w.nodes - 1]
                + w.waterVaporFlows[w.nodes - 1] * w.waterVaporHeatCapacities[w.nodes - 1]);
        w.energyRhs[reboilerRow] = -w.energyResiduals[w.nodes - 1];

        try {
            double error = ThomasTridiagonalSolver.solve(w.energyLower, w.energyDiagonal, w.energyUpper,
                    w.energyRhs, w.temperatureCorrection, w.energyCPrime, w.energyDPrime);
            w.energyThomasSolves++;
            w.maximumThomasBackwardError = Math.max(w.maximumThomasBackwardError, error);
            if (!(error <= 1.0e-12)) {
                throw abort(DrySolverFailureCode.TRIDIAGONAL_BREAKDOWN, DryResidualFamily.THOMAS_BACKWARD_ERROR,
                        error, 1.0e-12, -1, -1, "Energy Thomas backward error exceeds the strict guard");
            }
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw abort(DrySolverFailureCode.TRIDIAGONAL_BREAKDOWN, DryResidualFamily.THOMAS_BACKWARD_ERROR,
                    Double.MAX_VALUE, 1.0e-12, -1, -1, "Energy Thomas solve failed: " + boundedMessage(exception));
        }
        globallyLimitTemperatureCorrection(w.temperatureCorrection, damping, trustRegion);
        double baselineMerit = energyMerit(w.energyResiduals, w.energyScales, w.nodes);
        boolean accepted = false;
        double acceptedFactor = 0.0;
        int validCandidates = 0;
        double bestCandidateMerit = Double.POSITIVE_INFINITY;
        for (double factor : BACKTRACK_FACTORS) {
            if (!temperatureCandidateIsValid(w, factor)) continue;
            validCandidates++;
            double candidateMerit = predictedEnergyMerit(w, factor);
            bestCandidateMerit = Math.min(bestCandidateMerit, candidateMerit);
            if (candidateMerit < baselineMerit) {
                for (int node = 1; node < w.nodes; node++) {
                    w.temperatures[node] += factor * w.temperatureCorrection[node - 1];
                }
                acceptedFactor = factor;
                accepted = true;
                break;
            }
        }
        if (!accepted && energyRatio > 1.0e-12) {
            if (w.events.stream().noneMatch(event -> event.startsWith("ENERGY_BACKTRACK_REJECTED"))) {
                addEvent(w, "ENERGY_BACKTRACK_REJECTED valid=" + validCandidates
                        + " base=" + compactDiagnosticNumber(baselineMerit)
                        + " best=" + compactDiagnosticNumber(bestCandidateMerit));
            }
        }
        return accepted ? predictedMaximumInnerEnergyRatio(w, acceptedFactor) : energyRatio;
    }

    private double computeLocalEnergyResiduals(Workspace w) {
        Arrays.fill(w.energyResiduals, 0.0);
        Arrays.fill(w.energyScales, 0.0);
        double maximumRatio = 0.0;
        for (int stage = 1; stage <= w.stages; stage++) {
            double incomingLiquidFlow = stage == 1 ? w.betaReflux * w.rawLiquidTotals[0] : w.rawLiquidTotals[stage - 1];
            double incomingLiquidH = stage == 1 ? localLiquidEnthalpy(w, 0) : localLiquidEnthalpy(w, stage - 1);
            double incomingVaporFlow = w.rawVaporTotals[stage + 1];
            double incomingVaporH = localVaporEnthalpy(w, stage + 1);
            double feedEnthalpy = stage == w.topology.feedStage() ? w.feedFlow * w.feedEnthalpy : 0.0;
            double incomingWater = (stage == 1 ? 0.0
                    : w.aqueousWaterFlows[stage - 1] * w.aqueousWaterEnthalpies[stage - 1])
                    + w.waterVaporFlows[stage + 1] * w.waterVaporEnthalpies[stage + 1]
                    + w.waterFeedEnthalpyWattsByNode[stage];
            double outgoingLiquidFlow = w.rawLiquidTotals[stage] + totalSideDrawAtStage(w, stage);
            double outgoingVaporFlow = w.rawVaporTotals[stage];
            double incoming = incomingLiquidFlow * incomingLiquidH + incomingVaporFlow * incomingVaporH
                    + feedEnthalpy + incomingWater;
            double outgoing = outgoingLiquidFlow * localLiquidEnthalpy(w, stage)
                    + outgoingVaporFlow * localVaporEnthalpy(w, stage)
                    + w.aqueousWaterFlows[stage] * w.aqueousWaterEnthalpies[stage]
                    + w.waterVaporFlows[stage] * w.waterVaporEnthalpies[stage];
            w.energyResiduals[stage] = incoming - outgoing;
            w.energyScales[stage] = Math.abs(incoming) + Math.abs(outgoing);
            maximumRatio = Math.max(maximumRatio, innerEnergyRatio(w, stage));
        }
        int node = w.nodes - 1;
        double incoming = w.rawLiquidTotals[w.stages] * localLiquidEnthalpy(w, w.stages)
                + w.aqueousWaterFlows[w.stages] * w.aqueousWaterEnthalpies[w.stages]
                + w.problem.input().reboilerDutyWatts();
        double outgoing = w.rawLiquidTotals[node] * localLiquidEnthalpy(w, node)
                + w.rawVaporTotals[node] * localVaporEnthalpy(w, node)
                + w.aqueousWaterFlows[node] * w.aqueousWaterEnthalpies[node]
                + w.waterVaporFlows[node] * w.waterVaporEnthalpies[node];
        w.energyResiduals[node] = incoming - outgoing;
        w.energyScales[node] = Math.abs(incoming) + Math.abs(outgoing);
        return Math.max(maximumRatio, innerEnergyRatio(w, node));
    }

    private DryAcceptanceAudit finalAcceptanceAudit(Workspace w) {
        DryAcceptanceAudit.Builder builder = DryAcceptanceAudit.builder();
        auditComponentBalances(w, builder);
        auditEnergy(w, builder);
        auditEquilibriumAndRawVle(w, builder);
        auditSumRatesAndCompositions(w, builder);
        auditSpecificationsAndPhaseState(w, builder);
        auditStateChange(w, builder);
        return builder.build();
    }

    private void auditComponentBalances(Workspace w, DryAcceptanceAudit.Builder builder) {
        double fAbs = 1.0e-12 * w.feedFlow;
        double maximumLocalRatio = 0.0;
        double maximumLocalValue = 0.0;
        double maximumLocalLimit = fAbs;
        int limitingLocalNode = -1;
        int limitingLocalComponent = -1;
        double maximumGlobalRatio = 0.0;
        double maximumGlobalValue = 0.0;
        double maximumGlobalLimit = fAbs;
        int limitingGlobalComponent = -1;
        double totalFeed = 0.0;
        double totalProducts = 0.0;
        for (int component = 0; component < HYDROCARBON_COMPONENTS; component++) {
            copyComponentNodeVector(w.liquidComponentFlows, component, w.componentLiquidScratch);
            copyComponentNodeVector(w.vaporComponentFlows, component, w.componentVaporScratch);
            copyComponentStageVector(w.sideDrawComponentFlows, component, w.componentSideDrawScratch);
            if (w.vaporOnlyOverhead) {
                w.topology.evaluateVaporOnlyHydrocarbonBalanceResiduals(w.componentLiquidScratch,
                        w.componentVaporScratch, w.componentSideDrawScratch, w.feedComponentFlows[component], w.componentResidualScratch);
            } else {
                w.topology.evaluateHydrocarbonBalanceResiduals(w.componentLiquidScratch, w.componentVaporScratch,
                        w.componentSideDrawScratch, w.betaReflux, w.feedComponentFlows[component], w.componentResidualScratch);
            }
            for (int node = 0; node < w.nodes; node++) {
                double scale = localMaterialScale(w, component, node);
                double limit = fAbs + 1.0e-9 * scale;
                double value = Math.abs(w.componentResidualScratch[node]);
                double ratio = value / Math.max(limit, FLOW_FLOOR);
                if (ratio > maximumLocalRatio) {
                    maximumLocalRatio = ratio;
                    maximumLocalValue = value;
                    maximumLocalLimit = limit;
                    limitingLocalNode = node;
                    limitingLocalComponent = component;
                }
            }
            double overhead = w.vaporComponentFlows[component]
                    + (w.vaporOnlyOverhead ? 0.0 : (1.0 - w.betaReflux) * w.liquidComponentFlows[component]);
            double side = sumComponentSideDraws(w, component);
            double bottoms = w.liquidComponentFlows[(w.nodes - 1) * HYDROCARBON_COMPONENTS + component];
            double global = w.feedComponentFlows[component] - overhead - side - bottoms;
            double limit = fAbs + 1.0e-9 * (Math.abs(w.feedComponentFlows[component]) + Math.abs(overhead)
                    + Math.abs(side) + Math.abs(bottoms));
            double value = Math.abs(global);
            double ratio = value / Math.max(limit, FLOW_FLOOR);
            if (ratio > maximumGlobalRatio) {
                maximumGlobalRatio = ratio;
                maximumGlobalValue = value;
                maximumGlobalLimit = limit;
                limitingGlobalComponent = component;
            }
            totalFeed += w.feedComponentFlows[component];
            totalProducts += overhead + side + bottoms;
        }
        builder.add(DryResidualFamily.LOCAL_COMPONENT_BALANCE, maximumLocalValue, maximumLocalLimit,
                limitingLocalNode, limitingLocalComponent, "maximum node/component hydrocarbon balance residual");
        builder.add(DryResidualFamily.GLOBAL_COMPONENT_BALANCE, maximumGlobalValue, maximumGlobalLimit,
                -1, limitingGlobalComponent, "maximum external component balance residual");
        double totalValue = Math.abs(totalFeed - totalProducts);
        double totalLimit = fAbs + 1.0e-9 * (Math.abs(totalFeed) + Math.abs(totalProducts));
        builder.add(DryResidualFamily.TOTAL_HYDROCARBON_BALANCE, totalValue, totalLimit, -1, -1,
                "total hydrocarbon external balance residual");
        double totalWaterFeed = sum(w.waterFeedMolarByNode);
        double totalWaterProducts = w.waterVaporFlows[0] + w.aqueousWaterFlows[0]
                + w.aqueousWaterFlows[w.nodes - 1];
        double waterValue = Math.abs(totalWaterFeed - totalWaterProducts);
        double waterLimit = fAbs + 1.0e-9 * (Math.abs(totalWaterFeed) + Math.abs(totalWaterProducts));
        builder.add(DryResidualFamily.WATER_BALANCE, waterValue, waterLimit, -1, -1,
                "external water balance residual after the bounded aqueous/steam active set");
        builder.add(DryResidualFamily.THOMAS_BACKWARD_ERROR, w.maximumThomasBackwardError, 1.0e-12,
                -1, -1, "maximum material/energy Thomas backward error");
    }

    private void auditEnergy(Workspace w, DryAcceptanceAudit.Builder builder) {
        double maximumRatio = 0.0;
        double maximumValue = 0.0;
        double maximumLimit = 0.0;
        int limitingNode = -1;
        for (int stage = 1; stage <= w.stages; stage++) {
            double incomingLiquidFlow = stage == 1 ? w.betaReflux * w.rawLiquidTotals[0] : w.rawLiquidTotals[stage - 1];
            double incomingLiquidH = stage == 1 ? w.rigorousLiquidEnthalpy[0] : w.rigorousLiquidEnthalpy[stage - 1];
            double incomingVaporFlow = w.rawVaporTotals[stage + 1];
            double incomingVaporH = w.rigorousVaporEnthalpy[stage + 1];
            double feed = stage == w.topology.feedStage() ? w.feedFlow * w.feedEnthalpy : 0.0;
            double incomingWater = (stage == 1 ? 0.0
                    : w.aqueousWaterFlows[stage - 1] * w.aqueousWaterEnthalpies[stage - 1])
                    + w.waterVaporFlows[stage + 1] * w.waterVaporEnthalpies[stage + 1]
                    + w.waterFeedEnthalpyWattsByNode[stage];
            double incoming = incomingLiquidFlow * incomingLiquidH + incomingVaporFlow * incomingVaporH
                    + feed + incomingWater;
            double outgoing = (w.rawLiquidTotals[stage] + totalSideDrawAtStage(w, stage)) * w.rigorousLiquidEnthalpy[stage]
                    + w.rawVaporTotals[stage] * w.rigorousVaporEnthalpy[stage]
                    + w.aqueousWaterFlows[stage] * w.aqueousWaterEnthalpies[stage]
                    + w.waterVaporFlows[stage] * w.waterVaporEnthalpies[stage];
            double value = Math.abs(incoming - outgoing);
            double limit = energyLimit(w, Math.abs(incoming) + Math.abs(outgoing));
            double ratio = value / Math.max(limit, FLOW_FLOOR);
            if (ratio > maximumRatio) {
                maximumRatio = ratio;
                maximumValue = value;
                maximumLimit = limit;
                limitingNode = stage;
            }
        }
        int node = w.nodes - 1;
        double incoming = w.rawLiquidTotals[w.stages] * w.rigorousLiquidEnthalpy[w.stages]
                + w.aqueousWaterFlows[w.stages] * w.aqueousWaterEnthalpies[w.stages]
                + w.problem.input().reboilerDutyWatts();
        double outgoing = w.rawLiquidTotals[node] * w.rigorousLiquidEnthalpy[node]
                + w.rawVaporTotals[node] * w.rigorousVaporEnthalpy[node]
                + w.aqueousWaterFlows[node] * w.aqueousWaterEnthalpies[node]
                + w.waterVaporFlows[node] * w.waterVaporEnthalpies[node];
        double value = Math.abs(incoming - outgoing);
        double limit = energyLimit(w, Math.abs(incoming) + Math.abs(outgoing));
        if (value / Math.max(limit, FLOW_FLOOR) > maximumRatio) {
            maximumValue = value;
            maximumLimit = limit;
            limitingNode = node;
        }
        builder.add(DryResidualFamily.ENERGY_BALANCE, maximumValue, maximumLimit, limitingNode, -1,
                "maximum rigorous tray/reboiler energy residual");
    }

    private void auditEquilibriumAndRawVle(Workspace w, DryAcceptanceAudit.Builder builder) {
        double maximumEquilibrium = 0.0;
        int equilibriumNode = -1;
        int equilibriumComponent = -1;
        double maximumRawVleRatio = 0.0;
        double maximumRawVleValue = 0.0;
        double maximumRawVleLimit = 0.0;
        int rawVleNode = -1;
        int rawVleComponent = -1;
        for (int node = w.vaporOnlyOverhead ? 1 : 0; node < w.nodes; node++) {
            int offset = node * HYDROCARBON_COMPONENTS;
            double liquidTotal = w.rawLiquidTotals[node];
            double vaporTotal = w.rawVaporTotals[node];
            for (int component = 0; component < HYDROCARBON_COMPONENTS; component++) {
                double liquid = w.liquidComponentFlows[offset + component];
                double vapor = w.vaporComponentFlows[offset + component];
                if (liquid <= FLOW_FLOOR && vapor <= FLOW_FLOOR) continue;
                if (!(liquid > FLOW_FLOOR) || !(vapor > FLOW_FLOOR)) {
                    maximumEquilibrium = Double.MAX_VALUE;
                    equilibriumNode = node;
                    equilibriumComponent = component;
                    continue;
                }
                double equilibrium = Math.abs(Math.log(liquid / liquidTotal) + w.rigorousLnK[offset + component]
                        - Math.log(vapor / vaporTotal));
                if (equilibrium > maximumEquilibrium) {
                    maximumEquilibrium = equilibrium;
                    equilibriumNode = node;
                    equilibriumComponent = component;
                }
                double k = Math.exp(clamp(w.rigorousLnK[offset + component], -MAXIMUM_LOG_K, MAXIMUM_LOG_K));
                double rawValue = Math.abs(vapor * liquidTotal - k * liquid * vaporTotal);
                double rawLimit = 1.0e-12 * w.feedFlow + 1.0e-9
                        * (Math.abs(vapor * liquidTotal) + Math.abs(k * liquid * vaporTotal));
                double rawRatio = rawValue / Math.max(rawLimit, FLOW_FLOOR);
                if (rawRatio > maximumRawVleRatio) {
                    maximumRawVleRatio = rawRatio;
                    maximumRawVleValue = rawValue;
                    maximumRawVleLimit = rawLimit;
                    rawVleNode = node;
                    rawVleComponent = component;
                }
            }
        }
        builder.add(DryResidualFamily.EQUILIBRIUM, maximumEquilibrium, 1.0e-8,
                equilibriumNode, equilibriumComponent, "maximum rigorous hydrocarbon fugacity residual");
        builder.add(DryResidualFamily.RAW_VLE, maximumRawVleValue, maximumRawVleLimit,
                rawVleNode, rawVleComponent, "maximum raw-flow VLE residual");
    }

    private void auditSumRatesAndCompositions(Workspace w, DryAcceptanceAudit.Builder builder) {
        double maximumSumRates = 0.0;
        int sumRateNode = -1;
        double maximumComposition = 0.0;
        int compositionNode = -1;
        for (int node = 0; node < w.nodes; node++) {
            if (!(w.vaporOnlyOverhead && node == 0)) {
                double residual = Math.abs(Math.log(w.rawLiquidTotals[node] / w.liquidTotals[node]));
                if (residual > maximumSumRates) {
                    maximumSumRates = residual;
                    sumRateNode = node;
                }
                double sum = sumNode(w.liquidComponentFlows, node);
                double composition = Math.abs(sum / w.rawLiquidTotals[node] - 1.0);
                if (composition > maximumComposition) {
                    maximumComposition = composition;
                    compositionNode = node;
                }
            }
            double vaporResidual = Math.abs(Math.log(w.rawVaporTotals[node] / w.vaporTotals[node]));
            if (vaporResidual > maximumSumRates) {
                maximumSumRates = vaporResidual;
                sumRateNode = node;
            }
            double vaporSum = sumNode(w.vaporComponentFlows, node);
            double vaporComposition = Math.abs(vaporSum / w.rawVaporTotals[node] - 1.0);
            if (vaporComposition > maximumComposition) {
                maximumComposition = vaporComposition;
                compositionNode = node;
            }
        }
        builder.add(DryResidualFamily.SUM_RATES, maximumSumRates, 1.0e-10, sumRateNode, -1,
                "maximum raw-flow versus Sum-Rates trial-total log residual");
        builder.add(DryResidualFamily.COMPOSITION_SUM, maximumComposition, 1.0e-12, compositionNode, -1,
                "composition sum used by property/reporting evaluation");
    }

    private void auditSpecificationsAndPhaseState(Workspace w, DryAcceptanceAudit.Builder builder) {
        double fAbs = 1.0e-12 * w.feedFlow;
        double maximumSpecification = 0.0;
        double maximumSpecificationLimit = fAbs;
        int limitingStage = -1;
        for (ColumnNextInput.SideDrawInput draw : w.topology.authoredSideDraws()) {
            int stage = draw.stageNumber();
            double actual = draw.basis() == ColumnNextInput.AuthoredBasis.MOLAR
                    ? totalSideDrawAtStage(w, stage) : massSideDrawAtStage(w, stage);
            double expected = draw.authoredRate();
            double value = Math.abs(actual - expected);
            double scale = draw.basis() == ColumnNextInput.AuthoredBasis.MOLAR ? w.feedFlow
                    : w.feedFlow * mixtureMolecularWeight(w, w.feedFractions);
            double limit = 1.0e-12 * scale + 1.0e-10 * Math.max(Math.abs(expected), FLOW_FLOOR);
            if (value / Math.max(limit, FLOW_FLOOR) > maximumSpecification / Math.max(maximumSpecificationLimit, FLOW_FLOOR)) {
                maximumSpecification = value;
                maximumSpecificationLimit = limit;
                limitingStage = stage;
            }
        }
        if (!w.vaporOnlyOverhead) {
            double externalCondensate = (1.0 - w.betaReflux) * w.rawLiquidTotals[0];
            double threshold = Math.max(1.0e-8 * w.feedFlow, 1.0e-9);
            if (externalCondensate < threshold) {
                maximumSpecification = Math.max(maximumSpecification, threshold - externalCondensate);
                maximumSpecificationLimit = 0.0;
                limitingStage = 0;
            }
            double refluxResidual = Math.abs(w.betaReflux * w.rawLiquidTotals[0]
                    - w.problem.input().organicRefluxRatio() * externalCondensate);
            double refluxLimit = fAbs + 1.0e-10 * Math.max(w.feedFlow, externalCondensate);
            if (refluxResidual / Math.max(refluxLimit, FLOW_FLOOR)
                    > maximumSpecification / Math.max(maximumSpecificationLimit, FLOW_FLOOR)) {
                maximumSpecification = refluxResidual;
                maximumSpecificationLimit = refluxLimit;
                limitingStage = 0;
            }
        } else {
            double value = Math.abs(w.rawLiquidTotals[0]);
            if (value > maximumSpecification) {
                maximumSpecification = value;
                maximumSpecificationLimit = fAbs;
                limitingStage = 0;
            }
        }
        builder.add(DryResidualFamily.SPECIFICATION, maximumSpecification, maximumSpecificationLimit,
                limitingStage, -1, "side-draw, reflux, and vapor-only-overhead specifications");

        double phaseViolation = 0.0;
        int limitingNode = -1;
        for (int node = 0; node < w.nodes; node++) {
            boolean liquidRequired = !(w.vaporOnlyOverhead && node == 0);
            if (!Double.isFinite(w.temperatures[node]) || w.temperatures[node] < w.problem.propertyPackage().minimumTemperatureKelvin()
                    || w.temperatures[node] > w.problem.propertyPackage().maximumTemperatureKelvin()
                    || !(w.hydrocarbonPartialPressures[node] >= w.problem.propertyPackage().minimumPressurePascal())
                    || !(w.rawVaporTotals[node] > FLOW_FLOOR)
                    || (liquidRequired && !(w.rawLiquidTotals[node] > FLOW_FLOOR))) {
                phaseViolation = 1.0;
                limitingNode = node;
                break;
            }
            if (liquidRequired && Math.abs(w.liquidCompressibility[node] - w.vaporCompressibility[node]) < 1.0e-8) {
                phaseViolation = 1.0;
                limitingNode = node;
                break;
            }
        }
        builder.add(DryResidualFamily.PHASE_VALIDITY, phaseViolation, 0.0, limitingNode, -1,
                "finite nonnegative two-hydrocarbon-phase state and PR-root separation");
    }

    private void auditStateChange(Workspace w, DryAcceptanceAudit.Builder builder) {
        builder.add(DryResidualFamily.FLOW_STATE_CHANGE, w.currentFlowStateChange, 1.0e-8, -1, -1,
                "maximum final log phase-component-flow change");
        double limit = 1.0e-6 + 1.0e-9 * maximum(w.temperatures);
        builder.add(DryResidualFamily.TEMPERATURE_STATE_CHANGE, w.currentTemperatureStateChange, limit, -1, -1,
                "maximum final temperature-state change");
    }

    private DryColumnResult toResult(Workspace w, DryAcceptanceAudit audit) {
        double[][] liquid = unpackNodeMajor(w.liquidComponentFlows, w.nodes);
        double[][] vapor = unpackNodeMajor(w.vaporComponentFlows, w.nodes);
        double[][] sideDraws = unpackNodeMajor(w.sideDrawComponentFlows, w.stages + 1);
        double[] reflux = new double[HYDROCARBON_COMPONENTS];
        double[] overhead = new double[HYDROCARBON_COMPONENTS];
        double[] bottoms = new double[HYDROCARBON_COMPONENTS];
        for (int component = 0; component < HYDROCARBON_COMPONENTS; component++) {
            reflux[component] = w.vaporOnlyOverhead ? 0.0 : w.betaReflux * w.liquidComponentFlows[component];
            overhead[component] = w.vaporComponentFlows[component]
                    + (w.vaporOnlyOverhead ? 0.0 : (1.0 - w.betaReflux) * w.liquidComponentFlows[component]);
            bottoms[component] = w.liquidComponentFlows[(w.nodes - 1) * HYDROCARBON_COMPONENTS + component];
        }
        double condenserDuty = w.rawVaporTotals[1] * w.rigorousVaporEnthalpy[1]
                - w.rawVaporTotals[0] * w.rigorousVaporEnthalpy[0]
                - (w.vaporOnlyOverhead ? 0.0 : w.rawLiquidTotals[0] * w.rigorousLiquidEnthalpy[0])
                + w.waterVaporFlows[1] * w.waterVaporEnthalpies[1]
                - w.waterVaporFlows[0] * w.waterVaporEnthalpies[0]
                - w.aqueousWaterFlows[0] * w.aqueousWaterEnthalpies[0];
        return new DryColumnResult(w.stages, w.temperatures, w.pressures, liquid, vapor, sideDraws, reflux,
                overhead, bottoms, w.waterVaporFlows, w.aqueousWaterFlows, w.wetWaterMask, condenserDuty, audit);
    }

    private DrySolverDiagnostics diagnostics(Workspace w, DryAcceptanceAudit audit) {
        return new DrySolverDiagnostics(w.outerIterations, w.innerIterations, w.thomasSolves, w.energyThomasSolves,
                w.propertyPhaseEvaluations, w.maximumThomasBackwardError, w.maximumInnerSumRatesResidual,
                w.maximumInnerEnergyResidual, w.currentFlowStateChange, w.currentTemperatureStateChange,
                w.recoveryPath, List.copyOf(w.events), audit);
    }

    private static boolean recoverable(DrySolverFailureCode code) {
        return code == DrySolverFailureCode.INNER_NONCONVERGENCE || code == DrySolverFailureCode.OUTER_NONCONVERGENCE
                || code == DrySolverFailureCode.NEGATIVE_PHASE_FLOW || code == DrySolverFailureCode.TRIDIAGONAL_BREAKDOWN
                || code == DrySolverFailureCode.EQUILIBRIUM_FAILURE || code == DrySolverFailureCode.ENERGY_BALANCE_FAILURE
                || code == DrySolverFailureCode.SUM_RATES_CLOSURE_FAILURE || code == DrySolverFailureCode.RAW_VLE_FAILURE;
    }

    private static double adaptiveInnerTolerance(double previousOuterMetric) {
        return clamp(previousOuterMetric * 0.10, 1.0e-10, 1.0e-4);
    }

    private static double maximumAuditRatio(DryAcceptanceAudit audit) {
        double maximum = 1.0e-10;
        for (DryAcceptanceAudit.Check check : audit.checks()) {
            maximum = Math.max(maximum, check.value() / Math.max(check.limit(), FLOW_FLOOR));
        }
        return maximum;
    }

    private static double requireNonnegative(double value, Workspace w, int node, int component) {
        if (!Double.isFinite(value) || value < -NEGATIVE_FLOW_TOLERANCE * Math.max(1.0, w.feedFlow)) {
            throw abort(DrySolverFailureCode.NEGATIVE_PHASE_FLOW, DryResidualFamily.PHASE_VALIDITY,
                    Math.abs(value), 0.0, node, component, "Thomas material solve produced a negative/non-finite phase flow");
        }
        return value < 0.0 ? 0.0 : value;
    }

    private static void requireActiveTwoHydrocarbonPhases(Workspace w, int node) {
        if (!(w.rawLiquidTotals[node] > FLOW_FLOOR) || !(w.rawVaporTotals[node] > FLOW_FLOOR)) {
            throw abort(DrySolverFailureCode.NEGATIVE_PHASE_FLOW, DryResidualFamily.PHASE_VALIDITY,
                    1.0, 0.0, node, -1, "Tray/reboiler requires positive hydrocarbon liquid and vapor flows");
        }
    }

    private static void normalizeNode(double[] source, int offset, double total, double[] target) {
        if (!(total > FLOW_FLOOR) || !Double.isFinite(total)) {
            throw abort(DrySolverFailureCode.NEGATIVE_PHASE_FLOW, DryResidualFamily.PHASE_VALIDITY,
                    1.0, 0.0, -1, -1, "Cannot normalize an absent hydrocarbon phase");
        }
        for (int component = 0; component < HYDROCARBON_COMPONENTS; component++) {
            double value = source[offset + component];
            if (!Double.isFinite(value) || value < 0.0) {
                throw abort(DrySolverFailureCode.NEGATIVE_PHASE_FLOW, DryResidualFamily.PHASE_VALIDITY,
                        Math.abs(value), 0.0, -1, component, "Cannot normalize an invalid hydrocarbon component flow");
            }
            target[component] = value / total;
        }
    }

    private static double localLiquidEnthalpy(Workspace w, int node) {
        return w.liquidEnthalpyReference[node]
                + w.liquidHeatCapacity[node] * (w.temperatures[node] - w.kReferenceTemperatures[node]);
    }

    private static double localVaporEnthalpy(Workspace w, int node) {
        return w.vaporEnthalpyReference[node]
                + w.vaporHeatCapacity[node] * (w.temperatures[node] - w.kReferenceTemperatures[node]);
    }

    private static double totalSideDrawAtStage(Workspace w, int stage) {
        return sumNode(w.sideDrawComponentFlows, stage);
    }

    private static double massSideDrawAtStage(Workspace w, int stage) {
        int offset = stage * HYDROCARBON_COMPONENTS;
        double total = 0.0;
        for (int component = 0; component < HYDROCARBON_COMPONENTS; component++) {
            total += w.sideDrawComponentFlows[offset + component] * w.basis.hydrocarbon(component).molecularWeightKgPerMol();
        }
        return total;
    }

    private static double innerEnergyRatio(Workspace w, int node) {
        return Math.abs(w.energyResiduals[node]) / Math.max(w.energyScales[node], 1.0);
    }

    private static double energyLimit(Workspace w, double scale) {
        return 1.0e-12 * w.feedFlow * 100_000.0 + 1.0e-7 * scale;
    }

    private static double energyMerit(double[] residuals, double[] scales, int nodes) {
        double total = 0.0;
        for (int node = 1; node < nodes; node++) {
            double scaled = residuals[node] / Math.max(scales[node], 1.0);
            total += scaled * scaled;
        }
        return total;
    }

    private static boolean temperatureCandidateIsValid(Workspace w, double factor) {
        double min = w.problem.propertyPackage().minimumTemperatureKelvin();
        double max = w.problem.propertyPackage().maximumTemperatureKelvin();
        for (int node = 1; node < w.nodes; node++) {
            double candidate = w.temperatures[node] + factor * w.temperatureCorrection[node - 1];
            if (!Double.isFinite(candidate) || candidate < min || candidate > max) return false;
        }
        return true;
    }

    private static double predictedEnergyMerit(Workspace w, double factor) {
        double total = 0.0;
        int thermalNodes = w.nodes - 1;
        for (int row = 0; row < thermalNodes; row++) {
            double prediction = w.energyResiduals[row + 1] + factor * w.energyDiagonal[row] * w.temperatureCorrection[row];
            if (row > 0) prediction += factor * w.energyLower[row] * w.temperatureCorrection[row - 1];
            if (row + 1 < thermalNodes) prediction += factor * w.energyUpper[row] * w.temperatureCorrection[row + 1];
            double scaled = prediction / Math.max(w.energyScales[row + 1], 1.0);
            total += scaled * scaled;
        }
        return total;
    }

    private static double predictedMaximumInnerEnergyRatio(Workspace w, double factor) {
        double maximum = 0.0;
        int thermalNodes = w.nodes - 1;
        for (int row = 0; row < thermalNodes; row++) {
            double prediction = w.energyResiduals[row + 1] + factor * w.energyDiagonal[row] * w.temperatureCorrection[row];
            if (row > 0) prediction += factor * w.energyLower[row] * w.temperatureCorrection[row - 1];
            if (row + 1 < thermalNodes) prediction += factor * w.energyUpper[row] * w.temperatureCorrection[row + 1];
            maximum = Math.max(maximum, Math.abs(prediction) / Math.max(w.energyScales[row + 1], 1.0));
        }
        return maximum;
    }

    /** Scales the coupled Newton vector as one direction; per-node clipping can turn it uphill. */
    static double globallyLimitTemperatureCorrection(double[] correction, double damping, double trustRegion) {
        double maximum = 0.0;
        for (double value : correction) maximum = Math.max(maximum, Math.abs(value));
        double scale = maximum == 0.0 ? damping : Math.min(damping, trustRegion / maximum);
        for (int index = 0; index < correction.length; index++) correction[index] *= scale;
        return scale;
    }

    private static double localMaterialScale(Workspace w, int component, int node) {
        int offset = node * HYDROCARBON_COMPONENTS;
        if (node == 0) {
            return Math.abs(w.liquidComponentFlows[component]) + Math.abs(w.vaporComponentFlows[component])
                    + Math.abs(w.vaporComponentFlows[HYDROCARBON_COMPONENTS + component]);
        }
        if (node == w.nodes - 1) {
            return Math.abs(w.liquidComponentFlows[w.stages * HYDROCARBON_COMPONENTS + component])
                    + Math.abs(w.liquidComponentFlows[offset + component]) + Math.abs(w.vaporComponentFlows[offset + component]);
        }
        double liquidIn = node == 1 ? w.betaReflux * w.liquidComponentFlows[component]
                : w.liquidComponentFlows[(node - 1) * HYDROCARBON_COMPONENTS + component];
        return Math.abs(liquidIn) + Math.abs(w.vaporComponentFlows[(node + 1) * HYDROCARBON_COMPONENTS + component])
                + Math.abs(w.feedComponentFlows[component]) + Math.abs(w.liquidComponentFlows[offset + component])
                + Math.abs(w.vaporComponentFlows[offset + component])
                + Math.abs(w.sideDrawComponentFlows[offset + component]);
    }

    private static void copyComponentNodeVector(double[] source, int component, double[] target) {
        for (int node = 0; node < target.length; node++) target[node] = source[node * HYDROCARBON_COMPONENTS + component];
    }

    private static void copyComponentStageVector(double[] source, int component, double[] target) {
        for (int stage = 0; stage < target.length; stage++) target[stage] = source[stage * HYDROCARBON_COMPONENTS + component];
    }

    private static double sumComponentSideDraws(Workspace w, int component) {
        double total = 0.0;
        for (int stage = 1; stage <= w.stages; stage++) total += w.sideDrawComponentFlows[stage * HYDROCARBON_COMPONENTS + component];
        return total;
    }

    private static void updateStateChange(Workspace w) {
        double maximumFlow = 0.0;
        for (int index = 0; index < w.liquidComponentFlows.length; index++) {
            maximumFlow = Math.max(maximumFlow, logFlowChange(w.liquidComponentFlows[index], w.previousLiquidComponentFlows[index]));
            maximumFlow = Math.max(maximumFlow, logFlowChange(w.vaporComponentFlows[index], w.previousVaporComponentFlows[index]));
        }
        double maximumTemperature = 0.0;
        for (int node = 1; node < w.nodes; node++) {
            maximumTemperature = Math.max(maximumTemperature, Math.abs(w.temperatures[node] - w.previousTemperatures[node]));
        }
        w.currentFlowStateChange = maximumFlow;
        w.currentTemperatureStateChange = maximumTemperature;
        System.arraycopy(w.liquidComponentFlows, 0, w.previousLiquidComponentFlows, 0, w.liquidComponentFlows.length);
        System.arraycopy(w.vaporComponentFlows, 0, w.previousVaporComponentFlows, 0, w.vaporComponentFlows.length);
        System.arraycopy(w.temperatures, 0, w.previousTemperatures, 0, w.temperatures.length);
    }

    private static double logFlowChange(double current, double previous) {
        return Math.abs(Math.log(Math.max(current, FLOW_FLOOR) / Math.max(previous, FLOW_FLOOR)));
    }

    private static double mixtureIdealEnthalpy(Workspace w, double[] composition, double temperature) {
        double total = 0.0;
        for (int component = 0; component < HYDROCARBON_COMPONENTS; component++) {
            total += composition[component] * w.basis.hydrocarbon(component).idealGasEnthalpy(temperature);
        }
        return total;
    }

    private static double mixtureIdealHeatCapacity(Workspace w, double[] composition, double temperature) {
        double total = 0.0;
        for (int component = 0; component < HYDROCARBON_COMPONENTS; component++) {
            total += composition[component] * w.basis.hydrocarbon(component).idealGasHeatCapacity(temperature);
        }
        return total;
    }

    private static double mixtureMolecularWeight(Workspace w, double[] composition) {
        double total = 0.0;
        for (int component = 0; component < HYDROCARBON_COMPONENTS; component++) {
            total += composition[component] * w.basis.hydrocarbon(component).molecularWeightKgPerMol();
        }
        if (!(total > 0.0) || !Double.isFinite(total)) {
            throw abort(DrySolverFailureCode.PROPERTY_PACKAGE_MISMATCH, DryResidualFamily.INPUT_VALIDITY,
                    Math.abs(total), 0.0, -1, -1, "A dry hydrocarbon molecular weight must be positive");
        }
        return total;
    }

    private static double geometricRelax(double previous, double target, double damping, int node) {
        if (!(previous > FLOW_FLOOR) || !(target > FLOW_FLOOR) || !Double.isFinite(previous) || !Double.isFinite(target)) {
            throw abort(DrySolverFailureCode.NEGATIVE_PHASE_FLOW, DryResidualFamily.SUM_RATES,
                    Math.abs(target), FLOW_FLOOR, node, -1, "Sum-Rates update requires positive finite raw and trial totals");
        }
        return Math.exp(Math.log(previous) + damping * (Math.log(target) - Math.log(previous)));
    }

    private static double sum(double[] values) {
        double total = 0.0;
        for (double value : values) total += value;
        return total;
    }

    private static double sumStages(double[] values, int stages) {
        double total = 0.0;
        for (int stage = 1; stage <= stages; stage++) total += values[stage];
        return total;
    }

    private static double sumNode(double[] values, int node) {
        int offset = node * HYDROCARBON_COMPONENTS;
        double total = 0.0;
        for (int component = 0; component < HYDROCARBON_COMPONENTS; component++) total += values[offset + component];
        return total;
    }

    private static double maximum(double[] values) {
        double maximum = Double.NEGATIVE_INFINITY;
        for (double value : values) maximum = Math.max(maximum, value);
        return maximum;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static void copyNode(double[] source, int sourceNode, double[] target, int targetNode) {
        System.arraycopy(source, sourceNode * HYDROCARBON_COMPONENTS, target, targetNode * HYDROCARBON_COMPONENTS,
                HYDROCARBON_COMPONENTS);
    }

    private static double[][] unpackNodeMajor(double[] source, int rows) {
        double[][] result = new double[rows][HYDROCARBON_COMPONENTS];
        for (int row = 0; row < rows; row++) {
            System.arraycopy(source, row * HYDROCARBON_COMPONENTS, result[row], 0, HYDROCARBON_COMPONENTS);
        }
        return result;
    }

    private static DrySolverFailureCode codeFor(DryResidualFamily family) {
        return switch (family) {
            case INPUT_VALIDITY -> DrySolverFailureCode.INVALID_INPUT;
            case CANCELLATION -> DrySolverFailureCode.CANCELLED;
            case LOCAL_COMPONENT_BALANCE, GLOBAL_COMPONENT_BALANCE -> DrySolverFailureCode.COMPONENT_BALANCE_FAILURE;
            case TOTAL_HYDROCARBON_BALANCE -> DrySolverFailureCode.TOTAL_HYDROCARBON_BALANCE_FAILURE;
            case WATER_BALANCE -> DrySolverFailureCode.WATER_BALANCE_FAILURE;
            case THOMAS_BACKWARD_ERROR -> DrySolverFailureCode.TRIDIAGONAL_BREAKDOWN;
            case ENERGY_BALANCE -> DrySolverFailureCode.ENERGY_BALANCE_FAILURE;
            case EQUILIBRIUM -> DrySolverFailureCode.EQUILIBRIUM_FAILURE;
            case RAW_VLE -> DrySolverFailureCode.RAW_VLE_FAILURE;
            case SUM_RATES -> DrySolverFailureCode.SUM_RATES_CLOSURE_FAILURE;
            case COMPOSITION_SUM -> DrySolverFailureCode.COMPOSITION_SUM_FAILURE;
            case SPECIFICATION -> DrySolverFailureCode.SPECIFICATION_FAILURE;
            case PHASE_VALIDITY -> DrySolverFailureCode.PHASE_VALIDITY_FAILURE;
            case FLOW_STATE_CHANGE, TEMPERATURE_STATE_CHANGE -> DrySolverFailureCode.STATE_CHANGE_FAILURE;
        };
    }

    private static void checkpoint(DrySolveControl control, Workspace workspace) {
        DrySolveControl.Signal signal = control.checkpoint();
        if (signal == DrySolveControl.Signal.CANCELLED) {
            throw abort(DrySolverFailureCode.CANCELLED, DryResidualFamily.CANCELLATION,
                    1.0, 0.0, -1, -1, "Dry solve cancelled at a bounded safe point");
        }
        if (signal == DrySolveControl.Signal.DEADLINE_EXCEEDED) {
            throw abort(DrySolverFailureCode.DEADLINE_EXCEEDED, DryResidualFamily.CANCELLATION,
                    1.0, 0.0, -1, -1, "Dry solve deadline expired at a bounded safe point");
        }
    }

    private static DryColumnOutcome immediateFailure(DrySolverFailureCode code, String summary) {
        DryResidualFamily family = code == DrySolverFailureCode.CANCELLED || code == DrySolverFailureCode.DEADLINE_EXCEEDED
                ? DryResidualFamily.CANCELLATION : DryResidualFamily.INPUT_VALIDITY;
        DryAcceptanceAudit audit = new DryAcceptanceAudit(List.of(
                DryAcceptanceAudit.Check.fail(family, 1.0, 0.0, -1, -1, summary)));
        DrySolverDiagnostics diagnostics = new DrySolverDiagnostics(0, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0,
                "NOT_STARTED", List.of(), audit);
        return new DryColumnOutcome.Failure(code, summary, diagnostics);
    }

    private static DryAcceptanceAudit nonconvergedAudit() {
        return new DryAcceptanceAudit(List.of(DryAcceptanceAudit.Check.fail(DryResidualFamily.SUM_RATES,
                1.0, 1.0e-10, -1, -1, "No converged inner state has been accepted")));
    }

    private static DryAcceptanceAudit failedAudit(Abort abort) {
        return new DryAcceptanceAudit(List.of(DryAcceptanceAudit.Check.fail(abort.family(), finite(abort.value()),
                finiteLimit(abort.limit()), abort.node(), abort.component(), abort.detail())));
    }

    private static Abort abort(DrySolverFailureCode code, DryResidualFamily family, double value, double limit,
                               int node, int component, String detail) {
        return new Abort(code, family, value, limit, node, component, detail);
    }

    private static void addEvent(Workspace workspace, String event) {
        if (workspace.events.size() < DrySolverDiagnostics.MAX_EVENTS) workspace.events.add(event.length() > 256 ? event.substring(0, 256) : event);
    }

    private static String boundedMessage(Throwable exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) message = exception.getClass().getSimpleName();
        return message.length() > 320 ? message.substring(0, 320) : message;
    }

    private static String compactDiagnosticNumber(double value) {
        return Double.isFinite(value) ? String.format(java.util.Locale.ROOT, "%.3e", value) : "INF";
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? Math.max(0.0, value) : Double.MAX_VALUE;
    }

    private static double finiteLimit(double value) {
        return Double.isFinite(value) && value >= 0.0 ? value : 0.0;
    }

    private record Attempt(DryColumnOutcome outcome, DrySolverFailureCode code) {}

    private static final class Abort extends RuntimeException {
        private final DrySolverFailureCode code;
        private final DryResidualFamily family;
        private final double value;
        private final double limit;
        private final int node;
        private final int component;
        private final String detail;

        Abort(DrySolverFailureCode code, DryResidualFamily family, double value, double limit,
              int node, int component, String detail) {
            super(null, null, false, false);
            this.code = Objects.requireNonNull(code, "code");
            this.family = Objects.requireNonNull(family, "family");
            this.value = value;
            this.limit = limit;
            this.node = node;
            this.component = component;
            this.detail = boundedMessage(detail == null ? new IllegalStateException("Missing solver detail")
                    : new IllegalStateException(detail));
        }

        DrySolverFailureCode code() { return code; }
        DryResidualFamily family() { return family; }
        double value() { return value; }
        double limit() { return limit; }
        int node() { return node; }
        int component() { return component; }
        String detail() { return detail; }
        DryAcceptanceAudit audit() { return null; }
    }

    /** One caller-owned primitive workspace per submitted attempt; no cell objects are allocated in inner loops. */
    private static final class Workspace {
        final ColumnProblem problem;
        final ColumnTopology topology;
        final ComponentBasis basis;
        final int stages;
        final int nodes;
        final double[] pressures;
        final double[] hydrocarbonPartialPressures;
        final NextPengRobinsonKernel kernel;
        final NextPengRobinsonKernel.Workspace prWorkspace;
        final NextPengRobinsonKernel.Evaluation liquidEvaluation;
        final NextPengRobinsonKernel.Evaluation vaporEvaluation;
        final NextPengRobinsonKernel.Evaluation feedLiquidEvaluation;
        final NextPengRobinsonKernel.Evaluation feedVaporEvaluation;
        final NextPengRobinsonKernel.Evaluation lowerLiquidDerivativeEvaluation;
        final NextPengRobinsonKernel.Evaluation lowerVaporDerivativeEvaluation;
        final NextPengRobinsonKernel.Evaluation upperLiquidDerivativeEvaluation;
        final NextPengRobinsonKernel.Evaluation upperVaporDerivativeEvaluation;
        final String recoveryPath;
        final NextWarmState warmStart;
        final List<String> events = new ArrayList<>();

        final double[] temperatures;
        final double[] previousTemperatures;
        final double[] liquidTotals;
        final double[] vaporTotals;
        final double[] rawLiquidTotals;
        final double[] rawVaporTotals;
        final double[] liquidComponentFlows;
        final double[] vaporComponentFlows;
        final double[] previousLiquidComponentFlows;
        final double[] previousVaporComponentFlows;
        final double[] sideDrawComponentFlows;
        final double[] sideDrawMolarRates;
        final double[] previousSideDrawMolarRates;
        final double[] feedFractions = new double[HYDROCARBON_COMPONENTS];
        final double[] feedComponentFlows = new double[HYDROCARBON_COMPONENTS];
        final double[] localK;
        final double[] lnKReference;
        final double[] lnKSlope;
        final double[] rigorousLnK;
        final double[] kReferenceTemperatures;
        final boolean[] hasRigorousModel;
        final double[] liquidEnthalpyReference;
        final double[] vaporEnthalpyReference;
        final double[] rigorousLiquidEnthalpy;
        final double[] rigorousVaporEnthalpy;
        final double[] liquidHeatCapacity;
        final double[] vaporHeatCapacity;
        final double[] liquidCompressibility;
        final double[] vaporCompressibility;
        final double[] energyResiduals;
        final double[] energyScales;
        final WaterActiveSet.Workspace waterActiveSetWorkspace;
        final double[] waterFeedMolarByNode;
        final double[] waterFeedEnthalpyWattsByNode;
        final double[] waterVaporFlows;
        final double[] aqueousWaterFlows;
        final boolean[] wetWaterMask;
        final double[] waterPartialPressures;
        final double[] waterVaporEnthalpies;
        final double[] aqueousWaterEnthalpies;
        final double[] waterVaporHeatCapacities;
        final double[] aqueousWaterHeatCapacities;

        final double[] lower;
        final double[] diagonal;
        final double[] upper;
        final double[] rhs;
        final double[] solution;
        final double[] cPrime;
        final double[] dPrime;
        final double[] vaporOnlyLower;
        final double[] vaporOnlyDiagonal;
        final double[] vaporOnlyUpper;
        final double[] vaporOnlyRhs;
        final double[] vaporOnlySolution;
        final double[] vaporOnlyCPrime;
        final double[] vaporOnlyDPrime;
        final double[] energyLower;
        final double[] energyDiagonal;
        final double[] energyUpper;
        final double[] energyRhs;
        final double[] temperatureCorrection;
        final double[] energyCPrime;
        final double[] energyDPrime;
        final double[] componentScratch = new double[HYDROCARBON_COMPONENTS];
        final double[] liquidCompositionScratch = new double[HYDROCARBON_COMPONENTS];
        final double[] vaporCompositionScratch = new double[HYDROCARBON_COMPONENTS];
        final double[] componentKByNode;
        final double[] componentLiquidScratch;
        final double[] componentVaporScratch;
        final double[] componentSideDrawScratch;
        final double[] componentResidualScratch;

        boolean vaporOnlyOverhead;
        double betaReflux;
        double feedFlow;
        double feedEnthalpy;
        WaterFeedProfile waterFeeds;
        int outerIterations;
        int innerIterations;
        int thomasSolves;
        int energyThomasSolves;
        int propertyPhaseEvaluations;
        double maximumThomasBackwardError;
        double maximumInnerSumRatesResidual;
        double maximumInnerEnergyResidual;
        double currentSumRatesResidual;
        double currentInnerEnergyResidual;
        double currentFlowStateChange;
        double currentTemperatureStateChange;
        double maximumWaterBalanceResidual;
        double maximumWaterComplementarityResidual;

        Workspace(ColumnProblem problem, String recoveryPath, NextWarmState warmStart) {
            this.problem = problem;
            topology = problem.topology();
            basis = problem.propertyPackage().basis();
            stages = topology.stageCount();
            nodes = topology.nodeCount();
            pressures = new double[nodes];
            for (int node = 0; node < nodes; node++) pressures[node] = problem.nodePressurePascal(node);
            hydrocarbonPartialPressures = pressures.clone();
            kernel = new NextPengRobinsonKernel(problem.propertyPackage());
            prWorkspace = kernel.newWorkspace();
            liquidEvaluation = kernel.newEvaluation();
            vaporEvaluation = kernel.newEvaluation();
            feedLiquidEvaluation = kernel.newEvaluation();
            feedVaporEvaluation = kernel.newEvaluation();
            lowerLiquidDerivativeEvaluation = kernel.newEvaluation();
            lowerVaporDerivativeEvaluation = kernel.newEvaluation();
            upperLiquidDerivativeEvaluation = kernel.newEvaluation();
            upperVaporDerivativeEvaluation = kernel.newEvaluation();
            this.recoveryPath = recoveryPath;
            this.warmStart = warmStart;
            temperatures = new double[nodes];
            previousTemperatures = new double[nodes];
            liquidTotals = new double[nodes];
            vaporTotals = new double[nodes];
            rawLiquidTotals = new double[nodes];
            rawVaporTotals = new double[nodes];
            int profileLength = nodes * HYDROCARBON_COMPONENTS;
            liquidComponentFlows = new double[profileLength];
            vaporComponentFlows = new double[profileLength];
            previousLiquidComponentFlows = new double[profileLength];
            previousVaporComponentFlows = new double[profileLength];
            sideDrawComponentFlows = new double[(stages + 1) * HYDROCARBON_COMPONENTS];
            sideDrawMolarRates = new double[stages + 1];
            previousSideDrawMolarRates = new double[stages + 1];
            localK = new double[profileLength];
            lnKReference = new double[profileLength];
            lnKSlope = new double[profileLength];
            rigorousLnK = new double[profileLength];
            kReferenceTemperatures = new double[nodes];
            hasRigorousModel = new boolean[nodes];
            liquidEnthalpyReference = new double[nodes];
            vaporEnthalpyReference = new double[nodes];
            rigorousLiquidEnthalpy = new double[nodes];
            rigorousVaporEnthalpy = new double[nodes];
            liquidHeatCapacity = new double[nodes];
            vaporHeatCapacity = new double[nodes];
            liquidCompressibility = new double[nodes];
            vaporCompressibility = new double[nodes];
            energyResiduals = new double[nodes];
            energyScales = new double[nodes];
            waterActiveSetWorkspace = new WaterActiveSet.Workspace(nodes);
            waterFeedMolarByNode = new double[nodes];
            waterFeedEnthalpyWattsByNode = new double[nodes];
            waterVaporFlows = new double[nodes];
            aqueousWaterFlows = new double[nodes];
            wetWaterMask = new boolean[nodes];
            waterPartialPressures = new double[nodes];
            waterVaporEnthalpies = new double[nodes];
            aqueousWaterEnthalpies = new double[nodes];
            waterVaporHeatCapacities = new double[nodes];
            aqueousWaterHeatCapacities = new double[nodes];
            lower = new double[nodes];
            diagonal = new double[nodes];
            upper = new double[nodes];
            rhs = new double[nodes];
            solution = new double[nodes];
            cPrime = new double[nodes];
            dPrime = new double[nodes];
            int vaporOnlySize = nodes - 1;
            vaporOnlyLower = new double[vaporOnlySize];
            vaporOnlyDiagonal = new double[vaporOnlySize];
            vaporOnlyUpper = new double[vaporOnlySize];
            vaporOnlyRhs = new double[vaporOnlySize];
            vaporOnlySolution = new double[vaporOnlySize];
            vaporOnlyCPrime = new double[vaporOnlySize];
            vaporOnlyDPrime = new double[vaporOnlySize];
            energyLower = new double[vaporOnlySize];
            energyDiagonal = new double[vaporOnlySize];
            energyUpper = new double[vaporOnlySize];
            energyRhs = new double[vaporOnlySize];
            temperatureCorrection = new double[vaporOnlySize];
            energyCPrime = new double[vaporOnlySize];
            energyDPrime = new double[vaporOnlySize];
            componentKByNode = new double[nodes];
            componentLiquidScratch = new double[nodes];
            componentVaporScratch = new double[nodes];
            componentSideDrawScratch = new double[stages + 1];
            componentResidualScratch = new double[nodes];
        }
    }
}
