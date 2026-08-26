package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3FlashResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoException;
import java.util.List;
import java.util.Objects;

/**
 * Stateless dry-V3 calculation façade for a fully immutable input snapshot.
 *
 * <p>This class has no Minecraft, cache, warm-state, executor, or packet dependency. A caller must arrange any
 * cancellation/deadline policy outside this direct numerical boundary. Every candidate is freshly audited before a
 * {@link V3ColumnOutcome.Success} can be returned.</p>
 */
public final class V3ColumnCalculator {
    public static final String FORMULATION_REVISION = "v3-dry-mesh-r1";
    public static final String ASSUMPTIONS_REVISION = "v3-dry-assumptions-r1";
    public static final int MAXIMUM_NEWTON_ITERATIONS = 128;
    public static final double SCALED_RESIDUAL_TOLERANCE = 1.0e-8;

    private V3ColumnCalculator() {}

    /** Calculates one dry two-phase-condenser V3 problem without sharing mutable numerical state across callers. */
    public static V3ColumnOutcome calculate(V3ColumnInput input) {
        input = Objects.requireNonNull(input, "input");
        try {
            V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(input.packageId());
            V3ColumnProblem problem = V3ColumnProblemResolver.resolve(input, V3CondenserPhaseBranch.TWO_PHASE);
            V3InputDigest digest = V3InputDigest.of(
                    problem, FORMULATION_REVISION, thermo.datasetRevision(), ASSUMPTIONS_REVISION);
            V3FlashResult feedFlash = thermo.flashTP(input.feedTemperatureKelvin(),
                    problem.nodePressurePascal(problem.topology().feedTrayNumber()),
                    input.feedComponentMolarFlowsMolPerSecond(), thermo.newWorkspace());
            V3ColumnInitializer.Seed seed = V3ColumnInitializer.initialize(problem, thermo, thermo.newWorkspace());
            V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(
                    problem, thermo, feedFlash.molarEnthalpyJoulesPerMol());
            V3SimultaneousColumnSolver.Attempt attempt = V3SimultaneousColumnSolver.solve(
                    problem, evaluator, new V3DryMeshCoordinateMap(problem), seed.state(), thermo::newWorkspace,
                    MAXIMUM_NEWTON_ITERATIONS, SCALED_RESIDUAL_TOLERANCE);
            V3AcceptanceAudit audit = audit(problem, thermo, feedFlash.molarEnthalpyJoulesPerMol(), attempt.state());
            String solvePath = "cold/fine-fd";
            if (!publishesSuccess(attempt, audit)) {
                try {
                    V3SimultaneousColumnSolver.Attempt coarseAttempt = V3SimultaneousColumnSolver.solve(
                            problem, evaluator, new V3DryMeshCoordinateMap(problem), seed.state(), thermo::newWorkspace,
                            V3ConvergenceEvidence.unavailable(), MAXIMUM_NEWTON_ITERATIONS, SCALED_RESIDUAL_TOLERANCE,
                            V3FiniteDifferenceJacobian.DifferenceScale.COARSE);
                    V3AcceptanceAudit coarseAudit = audit(
                            problem, thermo, feedFlash.molarEnthalpyJoulesPerMol(), coarseAttempt.state());
                    if (publishesSuccess(coarseAttempt, coarseAudit)
                            || coarseAttempt.evidence().maximumScaledResidual() < attempt.evidence().maximumScaledResidual()) {
                        attempt = coarseAttempt;
                        audit = coarseAudit;
                        solvePath = "cold/coarse-fd-recovery";
                    }
                } catch (IllegalStateException unavailableRecovery) {
                    // The primary fine attempt remains a complete typed result when this optional stencil violates
                    // the band-structure guard because of finite-difference noise.
                }
            }
            V3SolverDiagnostics diagnostics = diagnostics(attempt, audit, solvePath);
            if (attempt instanceof V3SimultaneousColumnSolver.Attempt.Converged converged && audit.accepted()) {
                V3ColumnResult result = V3ColumnResult.accepted(
                        problem, digest, audit, converged.evidence().convergenceEvidence());
                return new V3ColumnOutcome.Success(result, diagnostics);
            }
            if (attempt instanceof V3SimultaneousColumnSolver.Attempt.Failure failure) {
                return new V3ColumnOutcome.Failure(failureCode(failure.code()), failure.evidence().termination(), diagnostics);
            }
            return new V3ColumnOutcome.Failure(V3SolverFailureCode.ACCEPTANCE_AUDIT_FAILURE,
                    "The fresh V3 acceptance audit rejected the converged candidate", diagnostics);
        } catch (V3ThermoException thermoFailure) {
            return terminalFailure(V3SolverFailureCode.PROPERTY_OUT_OF_RANGE, thermoFailure.getMessage(), "property");
        } catch (IllegalArgumentException invalid) {
            return terminalFailure(V3SolverFailureCode.INVALID_INPUT, invalid.getMessage(), "input");
        } catch (IllegalStateException internal) {
            return terminalFailure(V3SolverFailureCode.INTERNAL_ERROR, internal.getMessage(), "internal");
        }
    }

    private static V3AcceptanceAudit audit(
            V3ColumnProblem problem, V3PengRobinsonThermo thermo, double feedMolarEnthalpy, V3DryMeshState state) {
        try {
            return new V3AcceptanceAuditor(problem, thermo, feedMolarEnthalpy).audit(state, thermo.newWorkspace());
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
        if (code.startsWith("LINEAR_")) return V3SolverFailureCode.LINEAR_SOLVE_FAILURE;
        if (code.startsWith("MAX_ITERATIONS") || code.startsWith("LINE_SEARCH")
                || code.startsWith("CONVERGENCE_EVIDENCE")) return V3SolverFailureCode.NONCONVERGENCE;
        return V3SolverFailureCode.INTERNAL_ERROR;
    }

    private static String boundedSummary(String value) {
        if (value == null || value.isBlank()) return "V3 calculation failed without a detail string";
        return value.length() <= 512 ? value : value.substring(0, 512);
    }
}
