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

    /**
     * A draw one tray below the feed keeps the trace; a draw two trays below does not receive it at all.
     *
     * <p>The product-path band used to force every point from the feed tray to the outermost draw tray into
     * both phases regardless of flow. The rule now is the draw tray's own liquid supply: on tray three the
     * feed tray above still carries the trace, so the draw keeps it (in the liquid only — its vapor is
     * below the floor); on tray four the intervening tray three carries none, so nothing reaches the draw
     * and the point is removed like any other, with its inflow counted by the sink-edge defect audit.</p>
     */
    @Test
    void aDrawTrayKeepsOnlyTheTraceItsOwnLiquidSupplyDelivers() {
        var fixture = V3TruncationNumericsTest.fixture(V3CondenserPhaseBranch.TWO_PHASE, 0.01);
        V3ColumnProblem connected = drawn(fixture.original(), 3, 5);
        V3TruncationSupport support = V3TruncationSupport.derive(connected, 0.01, fixture.exact());
        assertFalse(support.isIdentity());
        assertTrue(support.retainsLiquid(3, 2));
        assertFalse(support.retainsVapor(3, 2));
        assertFalse(support.retains(4, 2));
        assertTrue(V3ColumnProblemResolver.withTruncation(connected, support).degreeOfFreedomLedger().isValid());

        V3ColumnProblem distant = drawn(fixture.original(), 4, 5);
        V3TruncationSupport path = V3TruncationSupport.derive(distant, 0.01, fixture.exact());
        assertFalse(path.isIdentity());
        assertTrue(path.retains(2, 2), "the feed tray is still retained whole");
        for (int tray : new int[] {0, 1, 3, 4, 5}) assertFalse(path.retains(tray, 2), "tray " + tray);
        assertEquals("", path.note());
        assertTrue(V3ColumnProblemResolver.withTruncation(distant, path).degreeOfFreedomLedger().isValid());
        V3DryMeshState projected = path.projectSeed(distant, fixture.exact());
        assertEquals(0.0, projected.liquidFlow(3, 2));
        assertEquals(0.0, projected.vaporFlow(3, 2));
    }

    /** A draw above the feed is decided the same way, over the liquid the tray above it keeps. */
    @Test
    void aDrawAboveTheFeedIsDecidedByItsOwnLiquidSupplyToo() {
        var fixture = V3TruncationNumericsTest.fixture(V3CondenserPhaseBranch.TWO_PHASE, 0.01);
        V3ColumnInput base = fixture.original().input();
        V3ColumnProblem problem = V3ColumnProblemResolver.resolve(new V3ColumnInput(base.schemaVersion(), base.packageId(),
                base.assayId(), base.componentBasis(), base.feedComponentMolarFlowsMolPerSecond(), base.feedTemperatureKelvin(),
                base.stageCount(), 4, base.topPressurePascal(), base.stagePressureDropPascal(), base.specifications(),
                List.of(new V3SideDrawSpec(1, 5))), V3CondenserPhaseBranch.TWO_PHASE);
        V3TruncationSupport support = V3TruncationSupport.derive(problem, 0.01, fixture.exact());
        assertFalse(support.isIdentity());
        for (int tray = 1; tray <= 4; tray++) {
            for (int component = 0; component < 2; component++) assertTrue(support.retains(tray, component));
        }
        assertTrue(support.retains(4, 2), "the feed tray is still retained whole");
        for (int tray : new int[] {0, 1, 2, 3, 5}) assertFalse(support.retains(tray, 2), "tray " + tray);
        assertTrue(V3ColumnProblemResolver.withTruncation(problem, support).degreeOfFreedomLedger().isValid());
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
