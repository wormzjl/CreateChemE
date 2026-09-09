package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class V3VerificationAcceptanceTest {
    private static final V3ConvergenceEvidence SMALL_STEP = new V3ConvergenceEvidence(true, 1e-14, 1e-12, 1e-12, 1e-6);

    @Test
    void allowanceRequiresBothStatesWithinFixedCeilingAndADirectCorrection() {
        assertTrue(accept(1e-27, 2e-27, Math.nextDown(1e-12), 1e-12, 1e-8, true, SMALL_STEP));
        assertFalse(accept(1e-27, 2e-27, Math.nextUp(1e-12), 1e-12, 1e-8, true, SMALL_STEP));
        assertFalse(accept(1e-27, 2e-27, 1e-12, Math.nextUp(1e-12), 1e-8, true, SMALL_STEP));
        assertFalse(accept(1e-27, 2e-27, 1e-14, 1e-14, 1e-8, false, SMALL_STEP));
        assertFalse(accept(1e-27, 2e-27, 2e-13, 1e-14, 1e-13, true, SMALL_STEP));
        assertFalse(accept(1e-27, 2e-27, 1e-14, 2e-13, 1e-13, true, SMALL_STEP));
        assertFalse(accept(1e-27, 2e-27, 1e-10, 1e-10, 1e-3, true, SMALL_STEP));
        assertTrue(accept(2e-12, 1e-12, 1e-6, 1e-6, 1e-3, false, SMALL_STEP));
    }

    @Test
    void nearRootMeritCannotOverrideAnyActualCorrectionGateOrNonfiniteValues() {
        for (V3ConvergenceEvidence invalid : new V3ConvergenceEvidence[]{
                V3ConvergenceEvidence.unavailable(),
                new V3ConvergenceEvidence(true, Math.nextUp(1e-12), 0, 0, 0),
                new V3ConvergenceEvidence(true, 0, Math.nextUp(1e-8), 0, 0),
                new V3ConvergenceEvidence(true, 0, 0, 1e-5, Math.nextUp(1.0))}) {
            assertFalse(accept(1e-27, 2e-27, 1e-14, 1e-14, 1e-8, true, invalid));
        }
        for (double nonfinite : new double[]{Double.NaN, Double.POSITIVE_INFINITY}) {
            assertFalse(accept(nonfinite, 2e-27, 1e-14, 1e-14, 1e-8, true, SMALL_STEP));
            assertFalse(accept(1e-27, nonfinite, 1e-14, 1e-14, 1e-8, true, SMALL_STEP));
            assertFalse(accept(1e-27, 2e-27, nonfinite, 1e-14, 1e-8, true, SMALL_STEP));
            assertFalse(accept(1e-27, 2e-27, 1e-14, nonfinite, 1e-8, true, SMALL_STEP));
        }
    }

    private static boolean accept(double baseMerit, double candidateMerit, double baseMaximum, double candidateMaximum,
            double tolerance, boolean direct, V3ConvergenceEvidence evidence) {
        return V3SimultaneousColumnSolver.verificationCandidateAcceptable(
                baseMerit, candidateMerit, baseMaximum, candidateMaximum, tolerance, direct, evidence);
    }
}
