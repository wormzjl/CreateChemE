package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class V3SideDrawAuditTest {
    @Test
    void splitAuditRequiresStrictlyPositiveDownflowEvenWhenStoredFlowsArePositive() {
        var fixture = V3TruncationNumericsTest.fixture(V3CondenserPhaseBranch.TWO_PHASE, 0.01);
        V3ColumnProblem problem = drawn(fixture.original(), 2, 20);
        problem = V3ColumnProblemResolver.withTruncation(problem, V3TruncationSupport.derive(problem, 0.01, fixture.exact()));
        V3AcceptanceAuditor auditor = new V3AcceptanceAuditor(problem, fixture.thermo(), 0);
        assertTrue(check(auditor.audit(fixture.exact(), fixture.thermo().newWorkspace()), "SIDE_DRAW_SPLIT").passed());
        for (double total : new double[] {20.0, 10.0}) {
            double[][] liquid = V3TruncationNumericsTest.copyFlows(fixture.exact(), true);
            liquid[2] = new double[] {total * 0.25, total * 0.5, total * 0.25};
            V3DryMeshState state = new V3DryMeshState(problem.topology(), 3, liquid,
                    V3TruncationNumericsTest.copyFlows(fixture.exact(), false), V3TruncationNumericsTest.temperatures());
            assertFalse(check(auditor.audit(state, fixture.thermo().newWorkspace()), "SIDE_DRAW_SPLIT").passed());
        }
    }

    @Test
    void drawnLiquidIsAProductRatherThanATruncationDefectAndLocalBlocksRemainCorrect() {
        var fixture = V3TruncationNumericsTest.fixture(V3CondenserPhaseBranch.TWO_PHASE, 0.01);
        V3ColumnProblem original = drawn(fixture.original(), 2, 20);
        V3TruncationSupport support = V3TruncationSupport.derive(original, 0.01, fixture.exact());
        V3ColumnProblem problem = V3ColumnProblemResolver.withTruncation(original, support);
        assertFalse(support.isIdentity());
        assertTrue(problem.degreeOfFreedomLedger().isValid());
        double expected = 0.004 + 0.006 * (1 - 20.0 / 100.006);
        assertEquals(expected, support.massDefectMolPerSecond(fixture.exact()), 1e-14);
        V3AcceptanceAudit audit = new V3AcceptanceAuditor(problem, fixture.thermo(), 0)
                .audit(fixture.exact(), fixture.thermo().newWorkspace());
        assertEquals(expected / 90.01, check(audit, "TRUNCATION_MASS_DEFECT").value(), 1e-14);
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(problem, fixture.thermo(), 0);
        V3DryMeshCoordinateMap coordinates = new V3DryMeshCoordinateMap(problem);
        var reference = V3BlockJacobianAssembler.assemble(problem, evaluator, coordinates, fixture.exact(), fixture.thermo()::newWorkspace);
        var local = V3BlockJacobianAssembler.assembleLocal(problem, evaluator, coordinates, fixture.exact(), fixture.thermo()::newWorkspace,
                V3FiniteDifferenceJacobian.DifferenceScale.FINE, V3SolveControl.UNBOUNDED).toBandedMatrix();
        var dense = reference.toBandedMatrix();
        for (int row = 0; row < local.size(); row++) {
            for (int col = 0; col < local.size(); col++) assertEquals(dense.get(row, col), local.get(row, col), 1e-5);
        }
    }

    @Test
    void nonFeedDrawTrayIsForcedRetainedAndAnIsolatedForcedPointFallsBackSafely() {
        var fixture = V3TruncationNumericsTest.fixture(V3CondenserPhaseBranch.TWO_PHASE, 0.01);
        V3ColumnProblem connected = drawn(fixture.original(), 3, 5);
        V3TruncationSupport support = V3TruncationSupport.derive(connected, 0.01, fixture.exact());
        assertFalse(support.isIdentity());
        assertTrue(support.retains(3, 2));
        assertFalse(support.retains(4, 2));
        assertTrue(V3ColumnProblemResolver.withTruncation(connected, support).degreeOfFreedomLedger().isValid());
        V3ColumnProblem isolated = drawn(fixture.original(), 4, 5);
        V3TruncationSupport fallback = V3TruncationSupport.derive(isolated, 0.01, fixture.exact());
        assertTrue(fallback.isIdentity());
        assertTrue(fallback.note().contains("inflow"));
    }

    private static V3AcceptanceAudit.Check check(V3AcceptanceAudit audit, String name) {
        return audit.checks().stream().filter(check -> check.family().equals(name)).findFirst().orElseThrow();
    }

    private static V3ColumnProblem drawn(V3ColumnProblem original, int tray, double rate) {
        V3ColumnInput input = original.input();
        return V3ColumnProblemResolver.resolve(new V3ColumnInput(input.schemaVersion(), input.packageId(), input.assayId(),
                input.componentBasis(), input.feedComponentMolarFlowsMolPerSecond(), input.feedTemperatureKelvin(),
                input.stageCount(), input.feedStageNumber(), input.topPressurePascal(), input.stagePressureDropPascal(),
                input.specifications(), List.of(new V3SideDrawSpec(tray, rate))), original.topology().condenserPhaseBranch());
    }
}
