package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3FlashResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoException;
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
    public static final String FORMULATION_REVISION = "v3-dry-mesh-r1";
    public static final String ASSUMPTIONS_REVISION = "v3-dry-assumptions-r2";
    public static final int MAXIMUM_NEWTON_ITERATIONS = 128;
    public static final double SCALED_RESIDUAL_TOLERANCE = 1.0e-8;

    private V3ColumnCalculator() {}

    /** Calculates one dry two-phase-condenser V3 problem without sharing mutable numerical state across callers. */
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
        input = Objects.requireNonNull(input, "input");
        control = Objects.requireNonNull(control, "control");
        initializerMode = Objects.requireNonNull(initializerMode, "initializerMode");
        try {
            control.checkpoint();
            V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(input.packageId());
            V3ColumnProblem problem = V3ColumnProblemResolver.resolve(input, V3CondenserPhaseBranch.TWO_PHASE);
            V3InputDigest digest = V3InputDigest.of(
                    problem, FORMULATION_REVISION, thermo.datasetRevision(), ASSUMPTIONS_REVISION);
            V3SolvePass pass;
            if (initializerMode == V3ColumnInitializer.Mode.SEQUENTIAL_MATERIAL_VLE) {
                pass = solveDwsimStageContinuation(input, thermo, control);
                if (!publishesSuccess(pass.attempt(), pass.audit())) {
                    // The sequential material/VLE preconditioner is deliberately optional. It must not turn an
                    // otherwise solvable MESH problem into a failure; this is a fresh V3 material-closed seed,
                    // never a V1 approximation or a retained warm state.
                    V3DryMeshState fallbackSeed = V3ColumnInitializer.initialize(
                            problem, thermo, thermo.newWorkspace(), V3ColumnInitializer.Mode.MATERIAL_CLOSED).state();
                    pass = solveSingleProblem(problem, thermo, fallbackSeed,
                            control, "cold/dwsim-material-closed-fallback/fine-fd");
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
                    V3ColumnInitializer.Seed seed = V3ColumnInitializer.initialize(
                            problem, thermo, thermo.newWorkspace(), initializerMode);
                    V3SimultaneousColumnSolver.Attempt coarseAttempt = V3SimultaneousColumnSolver.solve(
                            problem, evaluator, new V3DryMeshCoordinateMap(problem), seed.state(), thermo::newWorkspace,
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
            V3SolverDiagnostics diagnostics = diagnostics(attempt, audit, solvePath);
            if (pass.reachedRequestedProblem()
                    && attempt instanceof V3SimultaneousColumnSolver.Attempt.Converged converged && audit.accepted()) {
                V3ColumnResult result = V3ColumnResult.accepted(
                        problem, digest, audit, converged.evidence().convergenceEvidence(), converged.state());
                return new V3ColumnOutcome.Success(result, diagnostics);
            }
            if (attempt instanceof V3SimultaneousColumnSolver.Attempt.Failure failure) {
                return new V3ColumnOutcome.Failure(failureCode(failure.code()), failure.evidence().termination(), diagnostics);
            }
            return new V3ColumnOutcome.Failure(V3SolverFailureCode.ACCEPTANCE_AUDIT_FAILURE,
                    "The fresh V3 acceptance audit rejected the converged candidate", diagnostics);
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (V3ThermoException thermoFailure) {
            return terminalFailure(V3SolverFailureCode.PROPERTY_OUT_OF_RANGE, thermoFailure.getMessage(), "property");
        } catch (IllegalArgumentException invalid) {
            return terminalFailure(V3SolverFailureCode.INVALID_INPUT, invalid.getMessage(), "input");
        } catch (IllegalStateException internal) {
            return terminalFailure(V3SolverFailureCode.INTERNAL_ERROR, internal.getMessage(), "internal");
        }
    }

    /**
     * Runs a bounded Wang-Henke-style stage continuation entirely within this request.
     *
     * <p>Every lower-stage solve must pass the same simultaneous-MESH and fresh-audit gates before its profile may
     * seed the next grid. The intermediate states are local variables only: neither they nor their thermodynamic
     * workspaces are retained after this calculation returns.</p>
     */
    private static V3SolvePass solveDwsimStageContinuation(
            V3ColumnInput input, V3PengRobinsonThermo thermo, V3SolveControl control) {
        List<Integer> stageCounts = dwsimStageCounts(input.stageCount());
        String stagePath = dwsimStagePath(stageCounts);
        V3DryMeshState previousState = null;
        V3SolvePass lastPass = null;
        for (int stageCount : stageCounts) {
            control.checkpoint();
            V3ColumnInput stageInput = stageCount == input.stageCount()
                    ? input : withStageGeometry(input, stageCount);
            V3ColumnProblem stageProblem = V3ColumnProblemResolver.resolve(stageInput, V3CondenserPhaseBranch.TWO_PHASE);
            V3DryMeshState seed = previousState == null
                    ? V3ColumnInitializer.initialize(stageProblem, thermo, thermo.newWorkspace(),
                            V3ColumnInitializer.Mode.SEQUENTIAL_MATERIAL_VLE).state()
                    : interpolate(previousState, stageProblem);
            lastPass = solveSingleProblem(stageProblem, thermo,
                    seed, control, "cold/dwsim-sequential/" + stagePath + "/fine-fd");
            if (!publishesSuccess(lastPass.attempt(), lastPass.audit())) {
                return new V3SolvePass(lastPass.attempt(), lastPass.audit(), lastPass.feedMolarEnthalpyJoulesPerMol(),
                        "cold/dwsim-sequential/" + stagePath + "/failed-stage-" + stageCount, false);
            }
            previousState = lastPass.attempt().state();
        }
        if (lastPass == null) throw new IllegalStateException("V3 DWSIM continuation has no stage grid");
        return new V3SolvePass(lastPass.attempt(), lastPass.audit(), lastPass.feedMolarEnthalpyJoulesPerMol(),
                lastPass.solvePath(), true);
    }

    private static V3SolvePass solveSingleProblem(
            V3ColumnProblem problem,
            V3PengRobinsonThermo thermo,
            V3DryMeshState seed,
            V3SolveControl control,
            String solvePath) {
        control.checkpoint();
        V3FlashResult feedFlash = thermo.flashTP(problem.input().feedTemperatureKelvin(),
                problem.nodePressurePascal(problem.topology().feedTrayNumber()),
                problem.input().feedComponentMolarFlowsMolPerSecond(), thermo.newWorkspace());
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(
                problem, thermo, feedFlash.molarEnthalpyJoulesPerMol());
        V3SimultaneousColumnSolver.Attempt attempt = V3SimultaneousColumnSolver.solve(
                problem, evaluator, new V3DryMeshCoordinateMap(problem), seed, thermo::newWorkspace,
                MAXIMUM_NEWTON_ITERATIONS, SCALED_RESIDUAL_TOLERANCE, control);
        control.checkpoint();
        V3AcceptanceAudit audit = audit(problem, thermo, feedFlash.molarEnthalpyJoulesPerMol(), attempt.state(), control);
        return new V3SolvePass(attempt, audit, feedFlash.molarEnthalpyJoulesPerMol(), solvePath, true);
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
            V3SimultaneousColumnSolver.Attempt attempt, V3AcceptanceAudit audit, String solvePath) {
        V3SimultaneousColumnSolver.Evidence evidence = attempt.evidence();
        double finalStepNorm = Math.max(evidence.convergenceEvidence().maximumLogFlowChange(),
                evidence.convergenceEvidence().maximumTemperatureChangeKelvin());
        return new V3SolverDiagnostics(0, evidence.iterations(), 0, 0, evidence.maximumScaledResidual(), finalStepNorm,
                solvePath, List.of(evidence.termination()), audit, evidence.convergenceEvidence());
    }

    private static V3ColumnOutcome.Failure terminalFailure(
            V3SolverFailureCode code, String detail, String solvePath) {
        String summary = boundedSummary(detail);
        V3AcceptanceAudit audit = failedAudit("UNAVAILABLE", summary);
        V3SolverDiagnostics diagnostics = new V3SolverDiagnostics(0, 0, 0, 0, 0.0, 0.0, solvePath, List.of(summary),
                audit, V3ConvergenceEvidence.unavailable());
        return new V3ColumnOutcome.Failure(code, summary, diagnostics);
    }

    private static V3AcceptanceAudit failedAudit(String family, String detail) {
        return new V3AcceptanceAudit(List.of(V3AcceptanceAudit.Check.fail(family, 1.0, 0.0, boundedSummary(detail))));
    }

    private static V3SolverFailureCode failureCode(String code) {
        if (code.startsWith("LINEAR_") || code.startsWith("JACOBIAN_")) {
            return V3SolverFailureCode.LINEAR_SOLVE_FAILURE;
        }
        if (code.startsWith("MAX_ITERATIONS") || code.startsWith("LINE_SEARCH")
                || code.startsWith("CONVERGENCE_EVIDENCE")) return V3SolverFailureCode.NONCONVERGENCE;
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
            boolean reachedRequestedProblem) {
        private V3SolvePass {
            attempt = Objects.requireNonNull(attempt, "attempt");
            audit = Objects.requireNonNull(audit, "audit");
            if (!Double.isFinite(feedMolarEnthalpyJoulesPerMol) || solvePath == null || solvePath.isBlank()) {
                throw new IllegalArgumentException("V3 solve pass evidence is invalid");
            }
        }
    }
}
