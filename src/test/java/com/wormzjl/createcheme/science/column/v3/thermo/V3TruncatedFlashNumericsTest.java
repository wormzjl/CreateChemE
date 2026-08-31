package com.wormzjl.createcheme.science.column.v3.thermo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Manufactured allocation checks isolate conservation/error policy from any particular crude data fit. */
class V3TruncatedFlashNumericsTest {
    private static final double TRACE = 1.0e-8;
    private static final double[] OVERALL = {0.4, 0.2, 0.4};
    private static final V3TraceTruncationPolicy POLICY = V3TraceTruncationPolicy.of(1.0e-6);

    @Test
    void phaseOnlyRachfordRiceTermsConserveEveryComponentWithoutInfiniteLogK() {
        V3FlashPhaseSupport support = support();
        double[] logK = {0.0, 0.0, 0.0}; // Inactive phase ratios are deliberately irrelevant finite values.
        double beta = V3TruncatedFlash.rachfordRiceRoot(OVERALL, logK, support);
        double[] liquid = new double[3];
        double[] vapor = new double[3];
        V3TruncatedFlash.split(OVERALL, logK, beta, support, liquid, vapor);

        assertEquals(0.5, beta, 0.0);
        assertArrayEquals(new double[] {0.0, 0.2, 0.8}, liquid, 0.0);
        assertArrayEquals(new double[] {0.8, 0.2, 0.0}, vapor, 0.0);
        for (int component = 0; component < OVERALL.length; component++) {
            assertEquals(OVERALL[component], (1.0 - beta) * liquid[component] + beta * vapor[component], 0.0);
        }
        assertEquals(Double.POSITIVE_INFINITY,
                V3TruncatedFlash.rachfordRiceResidual(OVERALL, logK, 0.0, support));
        assertEquals(Double.NEGATIVE_INFINITY,
                V3TruncatedFlash.rachfordRiceResidual(OVERALL, logK, 1.0, support));
    }

    @Test
    void retainedFiniteKDeterminesTheSplitInsidePhaseOnlyMaterialBounds() {
        V3FlashPhaseSupport support = support();
        double[] logK = {0.0, Math.log(2.0), 0.0};
        double beta = V3TruncatedFlash.rachfordRiceRoot(OVERALL, logK, support);
        assertTrue(beta > 0.4 && beta < 0.6);
        assertTrue(Math.abs(V3TruncatedFlash.rachfordRiceResidual(OVERALL, logK, beta, support)) < 1.0e-14);
        double[] liquid = new double[3];
        double[] vapor = new double[3];
        V3TruncatedFlash.split(OVERALL, logK, beta, support, liquid, vapor);
        assertEquals(2.0, vapor[1] / liquid[1], 1.0e-14);
        assertEquals(1.0, java.util.Arrays.stream(liquid).sum(), 1.0e-14);
        assertEquals(1.0, java.util.Arrays.stream(vapor).sum(), 1.0e-14);
        for (int component = 0; component < OVERALL.length; component++) {
            assertEquals(OVERALL[component], (1.0 - beta) * liquid[component] + beta * vapor[component], 1.0e-15);
        }
    }

    @Test
    void acceptedReducedSplitReportsBothPhaseAllocationErrorsAndOriginalReferenceEnthalpy() {
        V3FlashResult candidate = candidate(0.0);
        V3FlashTruncationEvidence evidence = assess(candidate, targetLogK());
        assertEquals(V3FlashTruncationEvidence.Status.APPLIED, evidence.status(), evidence::detail);
        assertTrue(evidence.errorsEvaluated());
        assertEquals(2.0 * TRACE, evidence.allocationError(), 2.0e-16);
        assertEquals(0.0, evidence.maxMaterialClosureError());
        assertEquals(1, evidence.omittedLiquidComponents());
        assertEquals(1, evidence.omittedVaporComponents());
        V3FlashResult published = candidate.withTruncationEvidence(evidence);
        assertEquals(reference().iterations() + candidate.iterations(), published.iterations());
        assertEquals(reference().molarEnthalpyJoulesPerMol(), published.referenceMolarEnthalpyJoulesPerMol());
    }

    @Test
    void materialClosureCannotSpendTheTraceApproximationBudget() {
        V3FlashResult candidate = V3FlashResult.twoPhase(2, 0.5,
                new double[] {0.0, 0.200001, 0.799999}, new double[] {0.8, 0.2, 0.0}, 0.0, "material defect fixture");
        V3FlashTruncationEvidence evidence = assess(candidate, targetLogK());
        assertEquals(V3FlashTruncationEvidence.Status.FALLBACK, evidence.status());
        assertTrue(evidence.detail().contains("material closure"));
        assertTrue(evidence.maxMaterialClosureError() < POLICY.cutoffMoleFraction());
        assertTrue(evidence.maxMaterialClosureError() > 1.0e-8);
    }

    @Test
    void omittedPhaseDrivingForceRequiresFullSupportReactivation() {
        double[] target = targetLogK();
        target[0] = 0.0; // The supposedly vapor-only component now demands a large liquid concentration.
        V3FlashTruncationEvidence evidence = assess(candidate(0.0), target);
        assertEquals(V3FlashTruncationEvidence.Status.FALLBACK, evidence.status());
        assertTrue(evidence.detail().contains("reactivation"));
        V3FlashResult fallback = reference().withTruncationEvidence(evidence);
        assertArrayEquals(reference().liquidComposition(), fallback.liquidComposition(), 0.0);
        assertArrayEquals(reference().vaporComposition(), fallback.vaporComposition(), 0.0);
    }

    @Test
    void retainedComponentsStillRequireTheUnchangedEquilibriumTolerance() {
        double[] target = targetLogK();
        target[1] = 2.0e-10;
        V3FlashTruncationEvidence evidence = assess(candidate(0.0), target);
        assertEquals(V3FlashTruncationEvidence.Status.FALLBACK, evidence.status());
        assertTrue(evidence.detail().contains("equilibrium"));
    }

    @Test
    void dimensionedEnthalpyBudgetRejectsAnOtherwiseValidAllocation() {
        V3FlashTruncationEvidence evidence = assess(candidate(1.0), targetLogK());
        assertEquals(V3FlashTruncationEvidence.Status.FALLBACK, evidence.status());
        assertEquals(1.0, evidence.enthalpyErrorJoulesPerMol());
        assertTrue(evidence.detail().contains("error budget"));
    }

    @Test
    void enthalpyBudgetBoundaryIsInclusiveAndNextRepresentableExcessFallsBack() {
        double limit = POLICY.maximumEnthalpyErrorJoulesPerMol(reference().molarEnthalpyJoulesPerMol());
        assertEquals(V3FlashTruncationEvidence.Status.APPLIED, assess(candidate(limit), targetLogK()).status());
        assertEquals(V3FlashTruncationEvidence.Status.FALLBACK,
                assess(candidate(Math.nextUp(limit)), targetLogK()).status());
    }

    @Test
    void conservedAndEquilibratedCandidateStillCannotExceedPhaseAllocationBudgets() {
        double beta = 0.51;
        double[] liquid = {0.0, (1.0 - beta - 0.4) / (1.0 - beta), 0.4 / (1.0 - beta)};
        double[] vapor = {0.4 / beta, (beta - 0.4) / beta, 0.0};
        double[] target = targetLogK();
        target[1] = Math.log(vapor[1]) - Math.log(liquid[1]);
        V3FlashResult candidate = V3FlashResult.twoPhase(2, beta, liquid, vapor, 0.0, "allocation budget fixture");
        V3FlashTruncationEvidence evidence = assess(candidate, target);
        assertEquals(V3FlashTruncationEvidence.Status.FALLBACK, evidence.status());
        assertTrue(evidence.maxMaterialClosureError() < 1.0e-15);
        assertTrue(evidence.detail().contains("error budget"));
        assertTrue(evidence.allocationError() > POLICY.maximumPhaseAllocationError());
        assertTrue(evidence.maxPhaseCompositionError() > POLICY.maximumPhaseCompositionError());
        assertEquals(0.01, evidence.betaError(), 1.0e-14);
    }

    @Test
    void unavailableReferenceNeverGetsRescuedByAnApproximateFlash() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        double[] feed = thermo.crudeFeed("createcheme:tia_juana_light").moleFractions();
        V3ThermoException failure = assertThrows(V3ThermoException.class,
                () -> thermo.flashTP(901.0, 250000.0, feed, POLICY, thermo.newWorkspace()));
        assertEquals(V3ThermoException.Code.DOMAIN, failure.code());
    }

    @Test
    void phaseClassificationCannotBeChangedByTruncation() {
        V3FlashResult liquidOnly = V3FlashResult.liquid(1, OVERALL, 0.0, "invalid phase removal fixture");
        V3FlashTruncationEvidence evidence = assess(liquidOnly, targetLogK());
        assertEquals(V3FlashTruncationEvidence.Status.FALLBACK, evidence.status());
        assertFalse(evidence.errorsEvaluated());
        assertTrue(evidence.detail().contains("Phase classification"));
    }

    @Test
    void invalidRetainedKIsARecoverableNumericalFailureNotAnInfiniteSentinel() {
        assertThrows(V3ThermoException.class, () -> V3TruncatedFlash.rachfordRiceRoot(
                OVERALL, new double[] {0.0, Double.NaN, 0.0}, support()));
    }

    private static V3FlashTruncationEvidence assess(V3FlashResult candidate, double[] targetLogK) {
        return V3TruncatedFlash.assess(OVERALL, reference(), candidate, support(), POLICY, targetLogK);
    }

    private static V3FlashPhaseSupport support() {
        return V3FlashPhaseSupport.derive(OVERALL, reference(), POLICY);
    }

    private static V3FlashResult reference() {
        return V3FlashResult.twoPhase(36, 0.5, new double[] {TRACE, 0.2, 0.8 - TRACE},
                new double[] {0.8 - TRACE, 0.2, TRACE}, 0.0, "manufactured unrestricted reference");
    }

    private static V3FlashResult candidate(double enthalpy) {
        return V3FlashResult.twoPhase(2, 0.5, new double[] {0.0, 0.2, 0.8},
                new double[] {0.8, 0.2, 0.0}, enthalpy, "manufactured reduced split");
    }

    private static double[] targetLogK() {
        double logRatio = Math.log(0.8 - TRACE) - Math.log(TRACE);
        return new double[] {logRatio, 0.0, -logRatio};
    }
}
