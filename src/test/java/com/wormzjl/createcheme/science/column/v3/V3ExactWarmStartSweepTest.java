package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3FlashResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exact-input hot-start companion to {@link V3ColdStartSweepTest}.
 *
 * <p>Each source point first converges from a fresh initializer. The hot attempt receives only that accepted
 * solver-owned state and convergence certificate, while problem resolution, evaluator construction, thermodynamic
 * workspaces, residual recomputation, and acceptance audit are all fresh. Nearby-input reuse is deliberately out of
 * scope for this exact-compatibility test.</p>
 */
class V3ExactWarmStartSweepTest {
    @Test
    void coldQualifiedMatrixPointsAlsoPassFreshExactInputHotStarts() {
        for (V3ColumnInput input : List.of(
                binaryInput(100.0, 550.0, 250_000.0, 750.0, 300.0, 2.0, 0.0),
                binaryInput(100.0, 551.0, 250_000.0, 750.0, 300.0, 2.0, 0.0),
                binaryInput(100.0, 550.0, 250_000.0, 0.0, 300.0, 2.0, 0.0),
                binaryInput(100.0, 550.0, 250_000.0, 750.0, 300.0, 2.0, 20_000.0),
                binaryInput(100.0, 550.0, 250_000.0, 750.0, 300.0, 2.0, 50_000.0))) {
            ColdAttempt cold = coldAttempt(input);
            V3SimultaneousColumnSolver.Attempt.Converged coldConverged = assertInstanceOf(
                    V3SimultaneousColumnSolver.Attempt.Converged.class, cold.attempt());
            assertTrue(cold.audit().accepted());
            assertTrue(coldConverged.evidence().convergenceEvidence().satisfiesGates());

            V3PengRobinsonThermo freshThermo = V3PengRobinsonThermo.fromRegisteredPackage(input.packageId());
            V3ColumnProblem freshProblem = V3ColumnProblemResolver.resolve(input, V3CondenserPhaseBranch.TWO_PHASE);
            V3FlashResult freshFlash = freshThermo.flashTP(input.feedTemperatureKelvin(),
                    freshProblem.nodePressurePascal(freshProblem.topology().feedTrayNumber()),
                    input.feedComponentMolarFlowsMolPerSecond(), freshThermo.newWorkspace());
            V3MeshResidualEvaluator freshEvaluator = new V3MeshResidualEvaluator(
                    freshProblem, freshThermo, freshFlash.molarEnthalpyJoulesPerMol());
            V3SimultaneousColumnSolver.Attempt.Converged hotConverged = assertInstanceOf(
                    V3SimultaneousColumnSolver.Attempt.Converged.class,
                    V3SimultaneousColumnSolver.solve(
                            freshProblem,
                            freshEvaluator,
                            new V3DryMeshCoordinateMap(freshProblem),
                            coldConverged.state(),
                            freshThermo::newWorkspace,
                            coldConverged.evidence().convergenceEvidence(),
                            128,
                            1.0e-8));
            V3AcceptanceAudit hotAudit = new V3AcceptanceAuditor(
                    freshProblem, freshThermo, freshFlash.molarEnthalpyJoulesPerMol())
                    .audit(hotConverged.state(), freshThermo.newWorkspace());
            assertEquals(0, hotConverged.evidence().iterations());
            assertTrue(hotConverged.evidence().convergenceEvidence().satisfiesGates());
            assertTrue(hotAudit.accepted());
        }
    }

    private static ColdAttempt coldAttempt(V3ColumnInput input) {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(input.packageId());
        V3ColumnProblem problem = V3ColumnProblemResolver.resolve(input, V3CondenserPhaseBranch.TWO_PHASE);
        V3FlashResult flash = thermo.flashTP(input.feedTemperatureKelvin(),
                problem.nodePressurePascal(problem.topology().feedTrayNumber()), input.feedComponentMolarFlowsMolPerSecond(),
                thermo.newWorkspace());
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(problem, thermo, flash.molarEnthalpyJoulesPerMol());
        V3ColumnInitializer.Seed seed = V3ColumnInitializer.initialize(problem, thermo, thermo.newWorkspace());
        V3SimultaneousColumnSolver.Attempt attempt = V3SimultaneousColumnSolver.solve(
                problem, evaluator, new V3DryMeshCoordinateMap(problem), seed.state(), thermo::newWorkspace, 128, 1.0e-8);
        V3AcceptanceAuditor auditor = new V3AcceptanceAuditor(problem, thermo, flash.molarEnthalpyJoulesPerMol());
        V3AcceptanceAudit audit = auditor.audit(attempt.state(), thermo.newWorkspace());
        if (!(attempt instanceof V3SimultaneousColumnSolver.Attempt.Converged converged
                && converged.evidence().convergenceEvidence().satisfiesGates() && audit.accepted())) {
            V3SimultaneousColumnSolver.Attempt coarseAttempt = V3SimultaneousColumnSolver.solve(
                    problem, evaluator, new V3DryMeshCoordinateMap(problem), seed.state(), thermo::newWorkspace,
                    V3ConvergenceEvidence.unavailable(), 128, 1.0e-8,
                    V3FiniteDifferenceJacobian.DifferenceScale.COARSE);
            V3AcceptanceAudit coarseAudit = auditor.audit(coarseAttempt.state(), thermo.newWorkspace());
            if (coarseAttempt instanceof V3SimultaneousColumnSolver.Attempt.Converged coarseConverged
                    && coarseConverged.evidence().convergenceEvidence().satisfiesGates() && coarseAudit.accepted()) {
                attempt = coarseAttempt;
                audit = coarseAudit;
            }
        }
        return new ColdAttempt(attempt, audit);
    }

    private static V3ColumnInput binaryInput(
            double feedFlow,
            double feedTemperature,
            double topPressure,
            double pressureDrop,
            double condenserTemperature,
            double reflux,
            double reboilerDuty) {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        double[] feedFlows = new double[thermo.componentBasis().componentCount()];
        feedFlows[6] = feedFlow / 2.0;
        feedFlows[13] = feedFlow / 2.0;
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, thermo.packageId(), "test:registered-pr-binary",
                thermo.componentBasis(), feedFlows, feedTemperature, 2, 1, topPressure, pressureDrop, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(condenserTemperature),
                        new V3ColumnSpecification.OrganicRefluxRatio(reflux),
                        new V3ColumnSpecification.ReboilerDuty(reboilerDuty)));
    }

    private record ColdAttempt(V3SimultaneousColumnSolver.Attempt attempt, V3AcceptanceAudit audit) {}
}
