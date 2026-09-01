package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.createcheme.science.column.v3.thermo.V3FeedPhase;
import com.wormzjl.createcheme.science.column.v3.thermo.V3FlashResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3FugacityResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3Phase;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoModel;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoWorkspace;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class V3CondenserPhaseTransitionTest {
    @Test
    void successfulPhaseCorrectionRestoresLaterNumericalRecoveryWithoutForgettingAttemptedBranches() {
        V3ColumnCalculator.CondenserAttempts policy = new V3ColumnCalculator.CondenserAttempts();
        policy.recordAttempt(V3CondenserPhaseBranch.LIQUID_ONLY);
        assertTrue(policy.allowsColdRecovery());
        policy.beginPhaseCorrection();
        policy.recordAttempt(V3CondenserPhaseBranch.TWO_PHASE);
        assertFalse(policy.allowsColdRecovery());

        policy.finishPhaseCorrection(true);

        assertTrue(policy.allowsColdRecovery(), "an unrelated later-rung numerical failure may use same-branch recovery");
        assertTrue(policy.hasAttempted(V3CondenserPhaseBranch.LIQUID_ONLY));
        assertTrue(policy.hasAttempted(V3CondenserPhaseBranch.TWO_PHASE), "the outer dispatcher must not duplicate a cold branch retry");
    }

    @Test
    void unresolvedOrNewPhaseMismatchContinuesToBlockColdRecovery() {
        V3ColumnCalculator.CondenserAttempts policy = new V3ColumnCalculator.CondenserAttempts();
        policy.beginPhaseCorrection();
        policy.finishPhaseCorrection(false);
        assertFalse(policy.allowsColdRecovery());
        policy.finishPhaseCorrection(true);
        assertTrue(policy.allowsColdRecovery());
        policy.beginPhaseCorrection();
        assertFalse(policy.allowsColdRecovery(), "a later, distinct phase rejection must establish its own unresolved marker");
    }

    @Test
    void overheadFlashRebuildsTheBranchAndPreservesComponentsAndInteriorExactly() {
        V3ColumnProblem source = sourceProblem();
        V3DryMeshState state = sourceState(source);
        FlashThermo thermo = new FlashThermo(source.input().componentBasis(), twoPhaseFlash());

        V3CondenserPhaseTransition.Prepared prepared = V3CondenserPhaseTransition.prepare(source, state, thermo, V3SolveControl.UNBOUNDED);

        assertEquals(V3CondenserPhaseBranch.TWO_PHASE, prepared.problem().topology().condenserPhaseBranch());
        assertSame(source.input(), prepared.problem().input());
        assertTrue(prepared.problem().truncationSupport().isIdentity());
        assertNotSame(source.truncationSupport(), prepared.problem().truncationSupport());
        assertThrows(IllegalArgumentException.class, () -> source.truncationSupport().requireCompatible(prepared.problem()));
        assertArrayEquals(new double[] {8.1, 0.0, 3.9}, thermo.overhead, 0.0);
        assertEquals(0.25, prepared.vaporFraction());
        assertEquals(5.4, prepared.seed().liquidFlow(0, 0), 1.0e-14);
        assertEquals(2.7, prepared.seed().vaporFlow(0, 0), 1.0e-14);
        assertEquals(3.6, prepared.seed().liquidFlow(0, 1), 1.0e-14);
        assertEquals(0.3, prepared.seed().vaporFlow(0, 1), 1.0e-14);
        for (int component = 0; component < state.componentCount(); component++) {
            assertEquals(state.vaporFlow(1, component), prepared.seed().liquidFlow(0, component)
                    + prepared.seed().vaporFlow(0, component), 1.0e-14);
            assertEquals(0.0, state.vaporFlow(0, component));
        }
        assertInteriorUnchanged(state, prepared.seed());
        assertEquals(prepared.problem().degreeOfFreedomLedger().unknownCount(),
                new V3DryMeshCoordinateMap(prepared.problem()).encode(prepared.seed()).length);
    }

    @Test
    void liquidEndpointCanRemoveTheCondenserVaporCoordinatesWithoutChangingTheInterior() {
        V3ColumnProblem source = sourceProblem();
        V3CondenserPhaseTransition.Prepared twoPhase = V3CondenserPhaseTransition.prepare(source, sourceState(source),
                new FlashThermo(source.input().componentBasis(), twoPhaseFlash()), V3SolveControl.UNBOUNDED);
        FlashThermo liquid = new FlashThermo(source.input().componentBasis(), new V3FlashResult(V3FeedPhase.LIQUID,
                3, 0.0, new double[] {0.675, 0.0, 0.325}, new double[0], 0.0, "manufactured liquid endpoint"));

        V3CondenserPhaseTransition.Prepared prepared = V3CondenserPhaseTransition.prepare(twoPhase.problem(),
                twoPhase.seed(), liquid, V3SolveControl.UNBOUNDED);

        assertEquals(V3CondenserPhaseBranch.LIQUID_ONLY, prepared.problem().topology().condenserPhaseBranch());
        for (int component = 0; component < prepared.seed().componentCount(); component++) {
            assertEquals(twoPhase.seed().vaporFlow(1, component), prepared.seed().liquidFlow(0, component));
            assertEquals(0L, Double.doubleToLongBits(prepared.seed().vaporFlow(0, component)));
        }
        assertInteriorUnchanged(twoPhase.seed(), prepared.seed());
    }

    @Test
    void allVaporAndNonrepresentableTwoPhaseSplitsDoNotInventLiquidOrFlowFloors() {
        V3ColumnProblem source = sourceProblem();
        V3DryMeshState state = sourceState(source);
        V3FlashResult vapor = new V3FlashResult(V3FeedPhase.VAPOR, 0, 1.0, new double[0],
                new double[] {0.675, 0.0, 0.325}, 0.0, "manufactured vapor endpoint");
        assertThrows(IllegalArgumentException.class, () -> V3CondenserPhaseTransition.prepare(source, state,
                new FlashThermo(source.input().componentBasis(), vapor), V3SolveControl.UNBOUNDED));
        V3FlashResult zeros = new V3FlashResult(V3FeedPhase.TWO_PHASE, 1, 0.25,
                new double[] {1.0, 0.0, 0.0}, new double[] {0.0, 0.0, 1.0}, 0.0, "manufactured phase zeros");
        assertThrows(IllegalArgumentException.class, () -> V3CondenserPhaseTransition.prepare(source, state,
                new FlashThermo(source.input().componentBasis(), zeros), V3SolveControl.UNBOUNDED));
    }

    @Test
    void cancellationAfterTheFlashEscapesUnchanged() {
        V3ColumnProblem source = sourceProblem();
        CancellationException expected = new CancellationException("cancel phase transition");
        AtomicInteger checkpoints = new AtomicInteger();
        CancellationException actual = assertThrows(CancellationException.class, () -> V3CondenserPhaseTransition.prepare(
                source, sourceState(source), new FlashThermo(source.input().componentBasis(), twoPhaseFlash()), () -> {
                    if (checkpoints.incrementAndGet() == 2) throw expected;
                }));
        assertSame(expected, actual);
        assertEquals(2, checkpoints.get());
    }

    private static void assertInteriorUnchanged(V3DryMeshState before, V3DryMeshState after) {
        for (int node = 0; node < before.nodeCount(); node++) {
            assertEquals(before.temperatureKelvin(node), after.temperatureKelvin(node));
            if (node == 0) continue;
            for (int component = 0; component < before.componentCount(); component++) {
                assertEquals(before.liquidFlow(node, component), after.liquidFlow(node, component));
                assertEquals(before.vaporFlow(node, component), after.vaporFlow(node, component));
            }
        }
    }

    private static V3ColumnProblem sourceProblem() {
        V3ColumnInput input = new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, "test:phase-transition", "test:binary",
                new V3ComponentBasis(List.of("component-a", "inactive", "component-b")), new double[] {10.0, 0.0, 5.0},
                638.15, 2, 1, 110000.0, 750.0, List.of(new V3ColumnSpecification.CondenserOutletTemperature(323.15),
                        new V3ColumnSpecification.OrganicRefluxRatio(2.0), new V3ColumnSpecification.ReboilerDuty(0.0)));
        return V3ColumnProblemResolver.resolve(input, V3CondenserPhaseBranch.LIQUID_ONLY);
    }

    private static V3DryMeshState sourceState(V3ColumnProblem problem) {
        return new V3DryMeshState(problem.topology(), 2,
                new double[][] {{8.1, 3.9}, {5.0, 3.0}, {10.0, 5.0}, {8.0, 6.0}},
                new double[][] {{0.0, 0.0}, {8.1, 3.9}, {5.0, 4.0}, {3.0, 2.0}},
                new double[] {323.15, 410.0, 500.0, 600.0});
    }

    private static V3FlashResult twoPhaseFlash() {
        return new V3FlashResult(V3FeedPhase.TWO_PHASE, 4, 0.25,
                new double[] {0.6, 0.0, 0.4}, new double[] {0.9, 0.0, 0.1}, 0.0, "manufactured two-phase split");
    }

    private static final class FlashThermo implements V3ThermoModel {
        private final V3ComponentBasis basis;
        private final V3FlashResult result;
        private double[] overhead;

        private FlashThermo(V3ComponentBasis basis, V3FlashResult result) { this.basis = basis; this.result = result; }
        @Override public V3ComponentBasis componentBasis() { return basis; }
        @Override public V3ThermoWorkspace newWorkspace() { return new V3ThermoWorkspace(basis.componentCount()); }
        @Override public V3FlashResult flashTP(double temperature, double pressure, double[] composition, V3ThermoWorkspace workspace) {
            assertEquals(323.15, temperature);
            assertEquals(110000.0, pressure);
            overhead = composition.clone();
            return result;
        }
        @Override public V3FugacityResult fugacity(double temperature, double pressure, double[] composition,
                                                  V3Phase phase, V3ThermoWorkspace workspace) {
            throw new UnsupportedOperationException("seed transition needs only the TP flash");
        }
        @Override public double molarEnthalpy(double temperature, double pressure, double[] composition,
                                              V3Phase phase, V3ThermoWorkspace workspace) {
            throw new UnsupportedOperationException("seed transition needs only the TP flash");
        }
    }
}
