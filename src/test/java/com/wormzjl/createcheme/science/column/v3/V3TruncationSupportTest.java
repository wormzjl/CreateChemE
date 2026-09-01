package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class V3TruncationSupportTest {
    @Test
    void zeroCutoffReusesIdentityWithoutInspectingASeedOrRebuildingTheProblem() {
        V3ColumnProblem problem = problem(V3CondenserPhaseBranch.TWO_PHASE, 1.0, 2);
        V3TruncationSupport support = V3TruncationSupport.derive(problem, 0.0, null);

        assertSame(problem.truncationSupport(), support);
        assertSame(support, V3TruncationSupport.derive(problem, -0.0, null));
        assertSame(problem, V3ColumnProblemResolver.withTruncation(problem, support));
        assertSame(problem.degreeOfFreedomLedger(),
                V3ColumnProblemResolver.withTruncation(problem, support).degreeOfFreedomLedger());
        assertSame(problem, V3ColumnProblemResolver.withTruncation(problem,
                V3TruncationSupport.identity(problem.topology(), 2)));
        assertEquals(12, support.totalPointCount());
        assertEquals(0, support.truncatedPointCount());
        assertEquals(0, support.closurePrunedCount());
        assertEquals("", support.note());
        assertTrue(support.isIdentity());
        for (int node = 0; node < 6; node++) {
            for (int component = 0; component < 2; component++) assertTrue(support.retains(node, component));
        }
    }

    @Test
    void exactlyCutoffInEitherPhaseRetainsTheWholePointAndFeedTrayIsExempt() {
        V3ColumnProblem problem = problem(V3CondenserPhaseBranch.TWO_PHASE, 1.0, 2);
        double[][] liquid = uniformFlows(problem);
        double[][] vapor = uniformFlows(problem);
        liquid[0] = new double[] {1.0, 99.0};
        vapor[0] = new double[] {0.5, 99.5};
        liquid[2] = new double[] {0.0, 100.0};
        vapor[2] = new double[] {0.0, 100.0};
        liquid[4] = new double[] {0.5, 99.5};
        vapor[4] = new double[] {1.0, 99.0};
        V3TruncationSupport support = V3TruncationSupport.derive(problem, 0.01, state(problem, liquid, vapor));

        assertTrue(support.retains(0, 0));
        assertTrue(support.retains(2, 0));
        assertTrue(support.retains(4, 0));
        assertTrue(support.isIdentity());
        assertEquals(0.01, support.cutoffMoleFraction());
    }

    @Test
    void strictlyBelowCutoffInBothPhasesRemovesThePointOnlyAtThoseStages() {
        V3ColumnProblem problem = problem(V3CondenserPhaseBranch.TWO_PHASE, 1.0, 2);
        V3TruncationSupport support = topTailSupport(problem);

        assertFalse(support.isIdentity());
        assertFalse(support.retains(0, 0));
        assertFalse(support.retains(1, 0));
        assertTrue(support.retains(2, 0));
        assertTrue(support.retains(3, 0));
        for (int node = 0; node < 6; node++) assertTrue(support.retains(node, 1));
        assertEquals(2, support.truncatedPointCount());
        assertEquals(0, support.closurePrunedCount());
    }

    @ParameterizedTest
    @EnumSource(V3CondenserPhaseBranch.class)
    void condenserUsesOnlyItsStructurallyPresentPhases(V3CondenserPhaseBranch branch) {
        V3ColumnProblem problem = problem(branch, 0.0, 2);
        V3TruncationSupport support = topTailSupport(problem);

        assertFalse(support.retains(0, 0));
        assertTrue(support.retains(0, 1));
        assertEquals(2, support.truncatedPointCount());
    }

    @Test
    void zeroTotalPhaseIsUntestableAndNoTestablePhaseConservativelyRetains() {
        V3ColumnProblem problem = problem(V3CondenserPhaseBranch.TWO_PHASE, 1.0, 2);
        double[][] liquid = uniformFlows(problem);
        double[][] vapor = uniformFlows(problem);
        liquid[0] = new double[] {0.0, 0.0};
        vapor[0] = new double[] {0.1, 99.9};
        liquid[4] = new double[] {0.0, 0.0};
        vapor[4] = new double[] {0.0, 0.0};
        V3TruncationSupport support = V3TruncationSupport.derive(problem, 0.01, state(problem, liquid, vapor));

        assertFalse(support.retains(0, 0));
        assertTrue(support.retains(0, 1));
        assertTrue(support.retains(4, 0));
        assertTrue(support.retains(4, 1));
    }

    @Test
    void zeroRefluxPrunesTrayOneThenCondenserOnTheNextFixpointPass() {
        V3ColumnProblem noReflux = problem(V3CondenserPhaseBranch.TWO_PHASE, 0.0, 3);
        double[][] flows = uniformFlows(noReflux);
        flows[2] = new double[] {0.1, 99.9};
        V3TruncationSupport support = V3TruncationSupport.derive(noReflux, 0.01, state(noReflux, flows, flows));

        assertFalse(support.retains(0, 0));
        assertFalse(support.retains(1, 0));
        assertFalse(support.retains(2, 0));
        assertTrue(support.retains(3, 0));
        assertEquals(2, support.closurePrunedCount());
        assertEquals(3, support.truncatedPointCount());

        V3ColumnProblem reflux = problem(V3CondenserPhaseBranch.TWO_PHASE, 1.0, 3);
        V3TruncationSupport withReflux = V3TruncationSupport.derive(reflux, 0.01, state(reflux, flows, flows));
        assertTrue(withReflux.retains(0, 0));
        assertTrue(withReflux.retains(1, 0));
        assertEquals(0, withReflux.closurePrunedCount());
        assertThrows(IllegalArgumentException.class,
                () -> V3ColumnProblemResolver.withTruncation(noReflux, withReflux));
    }

    @Test
    void condenserAndReboilerLoseSupportWhenTheirOnlyInflowNeighborIsRemoved() {
        V3ColumnProblem problem = problem(V3CondenserPhaseBranch.TWO_PHASE, 1.0, 2);
        double[][] flows = uniformFlows(problem);
        flows[1] = new double[] {0.1, 99.9};
        flows[4] = new double[] {0.1, 99.9};
        V3TruncationSupport support = V3TruncationSupport.derive(problem, 0.01, state(problem, flows, flows));

        assertFalse(support.retains(0, 0));
        assertFalse(support.retains(5, 0));
        assertTrue(support.retains(2, 0));
        assertEquals(2, support.closurePrunedCount());
        assertEquals(4, support.truncatedPointCount());
    }

    @ParameterizedTest
    @EnumSource(V3CondenserPhaseBranch.class)
    void emptyStructuralPhaseFallsBackToIdentityWithBoundedProvenance(V3CondenserPhaseBranch branch) {
        V3ColumnProblem problem = problem(branch, 0.0, 2);
        double[][] flows = uniformFlows(problem);
        flows[0] = new double[] {99.9, 0.1};
        flows[1] = new double[] {0.1, 99.9};
        V3TruncationSupport support = V3TruncationSupport.derive(problem, 0.01, state(problem, flows, flows));

        assertTrue(support.isIdentity());
        assertEquals(0, support.truncatedPointCount());
        assertEquals(1, support.closurePrunedCount());
        assertEquals(0.01, support.cutoffMoleFraction());
        assertTrue(support.note().contains("identity"));
        assertTrue(support.note().length() <= 256);
        assertSame(problem, V3ColumnProblemResolver.withTruncation(problem, support));
    }

    @Test
    void validatesCutoffShapeAndPointAccessWithoutAcceptingNonfinitePhaseTotals() {
        V3ColumnProblem problem = problem(V3CondenserPhaseBranch.TWO_PHASE, 1.0, 2);
        V3DryMeshState state = state(problem, uniformFlows(problem), uniformFlows(problem));
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                -Double.MIN_VALUE, Math.nextUp(0.01)}) {
            assertThrows(IllegalArgumentException.class, () -> V3TruncationSupport.derive(problem, invalid, state));
        }
        assertThrows(IllegalArgumentException.class, () -> V3TruncationSupport.identity(problem.topology(), 0));
        assertThrows(IllegalArgumentException.class, () -> V3TruncationSupport.identity(problem.topology(), 65));
        V3DryMeshState wrongAxis = new V3DryMeshState(problem.topology(), 1, new double[6][1],
                new double[6][1], temperatures(problem));
        assertThrows(IllegalArgumentException.class, () -> V3TruncationSupport.derive(problem, 0.01, wrongAxis));
        V3TruncationSupport support = problem.truncationSupport();
        assertThrows(IndexOutOfBoundsException.class, () -> support.retains(-1, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> support.retains(6, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> support.retains(0, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> support.retains(0, 2));
        double[][] overflowing = uniformFlows(problem);
        overflowing[0] = new double[] {Double.MAX_VALUE, Double.MAX_VALUE};
        V3DryMeshState overflow = state(problem, overflowing, uniformFlows(problem));
        assertThrows(IllegalArgumentException.class, () -> V3TruncationSupport.derive(problem, 0.01, overflow));
    }

    static V3ColumnProblem problem(V3CondenserPhaseBranch branch, double reflux, int feedTray) {
        V3ColumnInput input = new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, "test:manufactured", "test:binary",
                new V3ComponentBasis(List.of("component-a", "component-b")), new double[] {1.0e-6, 100.0}, 400.0,
                4, feedTray, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(400.0),
                        new V3ColumnSpecification.OrganicRefluxRatio(reflux),
                        new V3ColumnSpecification.ReboilerDuty(0.0)));
        return V3ColumnProblemResolver.resolve(input, branch);
    }

    static V3TruncationSupport topTailSupport(V3ColumnProblem problem) {
        double[][] flows = uniformFlows(problem);
        flows[0] = new double[] {0.1, 99.9};
        flows[1] = new double[] {0.1, 99.9};
        return V3TruncationSupport.derive(problem, 0.01, state(problem, flows, flows));
    }

    static double[][] uniformFlows(V3ColumnProblem problem) {
        double[][] flows = new double[problem.topology().nodeCount()][2];
        for (double[] row : flows) Arrays.fill(row, 50.0);
        return flows;
    }

    static V3DryMeshState state(V3ColumnProblem problem, double[][] liquid, double[][] vapor) {
        // Do not mutate caller arrays when the condenser has only one phase.
        liquid = liquid.clone();
        vapor = vapor.clone();
        if (!problem.topology().hasLiquidPhase(0)) liquid[0] = new double[2];
        if (!problem.topology().hasVaporPhase(0)) vapor[0] = new double[2];
        return new V3DryMeshState(problem.topology(), 2, liquid, vapor, temperatures(problem));
    }

    private static double[] temperatures(V3ColumnProblem problem) {
        double[] temperatures = new double[problem.topology().nodeCount()];
        Arrays.fill(temperatures, 400.0);
        return temperatures;
    }
}
