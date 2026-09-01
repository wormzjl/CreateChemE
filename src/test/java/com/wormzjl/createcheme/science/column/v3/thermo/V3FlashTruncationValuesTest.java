package com.wormzjl.createcheme.science.column.v3.thermo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class V3FlashTruncationValuesTest {
    private static final double CUTOFF = 1.0e-3;

    @Test
    void policyAcceptsBothBoundsAndCanonicalizesNegativeZero() {
        assertEquals(1.0e-2, V3TraceTruncationPolicy.MAX_CUTOFF);
        for (double cutoff : new double[]{0.0, -0.0, V3TraceTruncationPolicy.MAX_CUTOFF}) {
            V3TraceTruncationPolicy policy = new V3TraceTruncationPolicy(cutoff);
            assertDoesNotThrow(() -> V3TraceTruncationPolicy.requireCutoff(cutoff));
            assertEquals(policy, V3TraceTruncationPolicy.of(cutoff));
            assertEquals(cutoff > 0.0, policy.enabled());
            if (cutoff == 0.0) {
                assertEquals(0L, Double.doubleToRawLongBits(policy.cutoffMoleFraction()));
                assertEquals(0L, Double.doubleToRawLongBits(
                        V3TraceTruncationPolicy.of(cutoff).cutoffMoleFraction()));
                assertEquals(V3TraceTruncationPolicy.OFF, policy);
            }
        }
    }

    @Test
    void policyRejectsOutOfRangeAndNonfiniteCutoffsAtEveryEntryPoint() {
        for (double cutoff : new double[]{-Double.MIN_VALUE,
                Math.nextUp(V3TraceTruncationPolicy.MAX_CUTOFF), Double.NaN,
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new V3TraceTruncationPolicy(cutoff));
            assertThrows(IllegalArgumentException.class, () -> V3TraceTruncationPolicy.of(cutoff));
            assertThrows(IllegalArgumentException.class, () -> V3TraceTruncationPolicy.requireCutoff(cutoff));
        }
    }

    @Test
    void enabledBudgetsUseOverallAllocationAndDimensionedEnthalpyScales() {
        V3TraceTruncationPolicy policy = V3TraceTruncationPolicy.of(CUTOFF);

        assertTrue(policy.enabled());
        assertEquals(8.0 * CUTOFF, policy.maximumPhaseAllocationError());
        assertEquals(8.0 * CUTOFF, policy.maximumVaporFractionError());
        assertEquals(CUTOFF, policy.maximumPhaseCompositionError());
        assertEquals(8.0 * CUTOFF, policy.maximumEnthalpyErrorJoulesPerMol(0.0));
        assertEquals(8.0 * CUTOFF, policy.maximumEnthalpyErrorJoulesPerMol(0.25));
        assertEquals(8.0 * CUTOFF * 12_345.0, policy.maximumEnthalpyErrorJoulesPerMol(12_345.0));
        assertEquals(policy.maximumEnthalpyErrorJoulesPerMol(12_345.0),
                policy.maximumEnthalpyErrorJoulesPerMol(-12_345.0));
        assertEquals(1.0e-6,
                V3TraceTruncationPolicy.of(1.0e-12).maximumEnthalpyErrorJoulesPerMol(0.0));
    }

    @Test
    void disabledPolicyHasZeroBudgetsIncludingTheEnthalpyFloor() {
        V3TraceTruncationPolicy policy = V3TraceTruncationPolicy.OFF;

        assertFalse(policy.enabled());
        assertEquals(0.0, policy.maximumPhaseAllocationError());
        assertEquals(0.0, policy.maximumVaporFractionError());
        assertEquals(0.0, policy.maximumPhaseCompositionError());
        assertEquals(0.0, policy.maximumEnthalpyErrorJoulesPerMol(0.0));
        assertEquals(0.0, policy.maximumEnthalpyErrorJoulesPerMol(-12_345.0));
    }

    @Test
    void enthalpyBudgetRejectsNonfiniteReferencesEvenWhenDisabled() {
        for (V3TraceTruncationPolicy policy : new V3TraceTruncationPolicy[]{
                V3TraceTruncationPolicy.OFF, V3TraceTruncationPolicy.of(CUTOFF)}) {
            for (double enthalpy : new double[]{Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
                assertThrows(IllegalArgumentException.class,
                        () -> policy.maximumEnthalpyErrorJoulesPerMol(enthalpy));
            }
        }
    }

    @Test
    void supportUsesPhaseCompositionAndRetainsAmbiguousBothTraceComponents() {
        double[] liquid = {0.0, 0.0001, 0.03, 0.0002, CUTOFF, 0.9687};
        double[] vapor = {0.0, 0.02, 0.0001, 0.0003, CUTOFF, 0.9786};
        double[] overall = overallComposition(0.25, liquid, vapor);
        V3FlashResult reference = V3FlashResult.twoPhase(7, 0.25, liquid, vapor, 1_234.0, "reference");

        V3FlashPhaseSupport support = V3FlashPhaseSupport.derive(
                overall, reference, V3TraceTruncationPolicy.of(CUTOFF));

        assertEquals(6, support.componentCount());
        assertEquals(V3FlashPhaseSupport.PhaseSupport.ABSENT, support.phaseSupport(0));
        assertEquals(V3FlashPhaseSupport.PhaseSupport.VAPOR_ONLY, support.phaseSupport(1));
        assertEquals(V3FlashPhaseSupport.PhaseSupport.LIQUID_ONLY, support.phaseSupport(2));
        assertEquals(V3FlashPhaseSupport.PhaseSupport.BOTH, support.phaseSupport(3));
        assertEquals(V3FlashPhaseSupport.PhaseSupport.BOTH, support.phaseSupport(4));
        assertEquals(V3FlashPhaseSupport.PhaseSupport.BOTH, support.phaseSupport(5));
        assertEquals(1, support.omittedLiquidCount());
        assertEquals(1, support.omittedVaporCount());
        assertFalse(support.isIdentity());
        assertTrue(overall[1] > CUTOFF, "An overall-above-cutoff species can be trace in one phase");
        assertTrue(overall[3] > 0.0 && overall[3] < CUTOFF,
                "A positive both-trace species must not become absent");
    }

    @Test
    void supportTreatsTheExactCutoffAsRetainedInEachPhase() {
        double below = Math.nextDown(CUTOFF);
        double[] liquid = {CUTOFF, below, CUTOFF, 1.0 - 2.0 * CUTOFF - below};
        double[] vapor = {below, CUTOFF, CUTOFF, 1.0 - 2.0 * CUTOFF - below};
        V3FlashResult reference = V3FlashResult.twoPhase(7, 0.5, liquid, vapor, 1_234.0, "threshold");

        V3FlashPhaseSupport support = V3FlashPhaseSupport.derive(
                overallComposition(0.5, liquid, vapor), reference, V3TraceTruncationPolicy.of(CUTOFF));

        assertEquals(V3FlashPhaseSupport.PhaseSupport.LIQUID_ONLY, support.phaseSupport(0));
        assertEquals(V3FlashPhaseSupport.PhaseSupport.VAPOR_ONLY, support.phaseSupport(1));
        assertEquals(V3FlashPhaseSupport.PhaseSupport.BOTH, support.phaseSupport(2));
        assertEquals(V3FlashPhaseSupport.PhaseSupport.BOTH, support.phaseSupport(3));
        assertEquals(1, support.omittedLiquidCount());
        assertEquals(1, support.omittedVaporCount());
    }

    @Test
    void disabledSupportIsIdentityAndDoesNotCountAuthoredZerosAsOmissions() {
        double[] liquid = {0.0, 0.0001, 0.9999};
        double[] vapor = {0.0, 0.8, 0.2};
        V3FlashResult reference = V3FlashResult.twoPhase(7, 0.25, liquid, vapor, 1_234.0, "reference");

        V3FlashPhaseSupport support = V3FlashPhaseSupport.derive(
                overallComposition(0.25, liquid, vapor), reference, V3TraceTruncationPolicy.OFF);

        assertTrue(support.isIdentity());
        assertEquals(V3FlashPhaseSupport.PhaseSupport.ABSENT, support.phaseSupport(0));
        assertEquals(V3FlashPhaseSupport.PhaseSupport.BOTH, support.phaseSupport(1));
        assertEquals(V3FlashPhaseSupport.PhaseSupport.BOTH, support.phaseSupport(2));
        assertEquals(0, support.omittedLiquidCount());
        assertEquals(0, support.omittedVaporCount());
    }

    @Test
    void enabledSupportWithoutOneSidedTraceCandidatesIsIdentity() {
        double[] liquid = {0.0001, 0.9999};
        double[] vapor = {0.0002, 0.9998};
        V3FlashResult reference = V3FlashResult.twoPhase(7, 0.25, liquid, vapor, 1_234.0, "reference");

        V3FlashPhaseSupport support = V3FlashPhaseSupport.derive(
                overallComposition(0.25, liquid, vapor), reference, V3TraceTruncationPolicy.of(CUTOFF));

        assertTrue(support.isIdentity());
        assertEquals(V3FlashPhaseSupport.PhaseSupport.BOTH, support.phaseSupport(0));
        assertEquals(V3FlashPhaseSupport.PhaseSupport.BOTH, support.phaseSupport(1));
        assertEquals(0, support.omittedLiquidCount());
        assertEquals(0, support.omittedVaporCount());
    }

    @Test
    void singlePhaseSupportIsIdentityWithOnlyItsNaturalPhase() {
        double[] composition = {0.0, 0.0001, 0.9999};
        V3TraceTruncationPolicy policy = V3TraceTruncationPolicy.of(CUTOFF);
        V3FlashPhaseSupport liquidSupport = V3FlashPhaseSupport.derive(composition,
                V3FlashResult.liquid(4, composition, -1_234.0, "liquid"), policy);
        V3FlashPhaseSupport vaporSupport = V3FlashPhaseSupport.derive(composition,
                V3FlashResult.vapor(5, composition, 1_234.0, "vapor"), policy);

        assertTrue(liquidSupport.isIdentity());
        assertTrue(vaporSupport.isIdentity());
        assertEquals(V3FlashPhaseSupport.PhaseSupport.ABSENT, liquidSupport.phaseSupport(0));
        assertEquals(V3FlashPhaseSupport.PhaseSupport.ABSENT, vaporSupport.phaseSupport(0));
        for (int i = 1; i < composition.length; i++) {
            assertEquals(V3FlashPhaseSupport.PhaseSupport.LIQUID_ONLY, liquidSupport.phaseSupport(i));
            assertEquals(V3FlashPhaseSupport.PhaseSupport.VAPOR_ONLY, vaporSupport.phaseSupport(i));
        }
        assertEquals(0, liquidSupport.omittedLiquidCount());
        assertEquals(0, liquidSupport.omittedVaporCount());
        assertEquals(0, vaporSupport.omittedLiquidCount());
        assertEquals(0, vaporSupport.omittedVaporCount());
    }

    @Test
    void derivedSupportCannotBeChangedThroughAnyCompositionArray() {
        double[] liquid = {0.0001, 0.03, 0.9699};
        double[] vapor = {0.02, 0.0001, 0.9799};
        double[] overall = overallComposition(0.25, liquid, vapor);
        V3FlashResult reference = V3FlashResult.twoPhase(7, 0.25, liquid, vapor, 1_234.0, "reference");
        V3FlashPhaseSupport support = V3FlashPhaseSupport.derive(
                overall, reference, V3TraceTruncationPolicy.of(CUTOFF));

        liquid[0] = 1.0;
        vapor[1] = 1.0;
        overall[0] = 0.0;
        overall[1] = 0.0;
        reference.liquidComposition()[0] = 1.0;
        reference.vaporComposition()[1] = 1.0;

        assertEquals(V3FlashPhaseSupport.PhaseSupport.VAPOR_ONLY, support.phaseSupport(0));
        assertEquals(V3FlashPhaseSupport.PhaseSupport.LIQUID_ONLY, support.phaseSupport(1));
        assertEquals(V3FlashPhaseSupport.PhaseSupport.BOTH, support.phaseSupport(2));
        assertEquals(1, support.omittedLiquidCount());
        assertEquals(1, support.omittedVaporCount());
    }

    @Test
    void supportRejectsMalformedOverallCompositionAndInconsistentBasis() {
        V3FlashResult reference = V3FlashResult.twoPhase(7, 0.25,
                new double[]{0.4, 0.6}, new double[]{0.8, 0.2}, 1_234.0, "reference");
        V3TraceTruncationPolicy policy = V3TraceTruncationPolicy.of(CUTOFF);
        for (double[] overall : new double[][]{new double[0], {0.0, 0.0}, {0.3, 0.3},
                {-0.1, 1.1}, {Double.NaN, 1.0}, {Double.POSITIVE_INFINITY, 0.0}, {1.0}}) {
            assertThrows(IllegalArgumentException.class,
                    () -> V3FlashPhaseSupport.derive(overall, reference, policy));
        }
        assertThrows(NullPointerException.class, () -> V3FlashPhaseSupport.derive(null, reference, policy));
        assertThrows(NullPointerException.class,
                () -> V3FlashPhaseSupport.derive(new double[]{0.5, 0.5}, null, policy));
        assertThrows(NullPointerException.class,
                () -> V3FlashPhaseSupport.derive(new double[]{0.5, 0.5}, reference, null));
    }

    @Test
    void oldResultConstructorAndDisabledWrappingPreserveLegacyEvidence() {
        V3FlashResult original = new V3FlashResult(V3FeedPhase.TWO_PHASE, 19, 0.25,
                new double[]{0.4, 0.6}, new double[]{0.8, 0.2}, -1_800.0, "legacy detail");

        V3FlashResult wrapped = original.withTruncationEvidence(V3FlashTruncationEvidence.DISABLED);

        assertSame(V3FlashTruncationEvidence.DISABLED, original.truncationEvidence());
        assertSame(V3FlashTruncationEvidence.DISABLED, wrapped.truncationEvidence());
        assertEquals(19, wrapped.iterations());
        assertEquals(-1_800.0, original.referenceMolarEnthalpyJoulesPerMol());
        assertEquals(-1_800.0, wrapped.referenceMolarEnthalpyJoulesPerMol());
        assertSamePhysicalResult(original, wrapped);
    }

    @Test
    void appliedEvidencePreservesReducedResultAndUsesReferencePlusReducedIterations() {
        V3FlashResult reduced = V3FlashResult.twoPhase(3, 0.25,
                new double[]{0.4, 0.6}, new double[]{0.8, 0.2}, 1_234.25, "reduced detail");
        V3FlashTruncationEvidence evidence = appliedEvidenceWithCounters(1, 1, 7, 3);

        V3FlashResult wrapped = reduced.withTruncationEvidence(evidence);
        V3FlashResult wrappedAgain = wrapped.withTruncationEvidence(evidence);

        assertEquals(evidence, wrapped.truncationEvidence());
        assertEquals(10, wrapped.iterations());
        assertEquals(10, wrappedAgain.iterations(), "Repeated wrapping must not count earlier wrapping as work");
        assertEquals(1_234.0, wrapped.referenceMolarEnthalpyJoulesPerMol());
        assertEquals(1_234.25, wrapped.molarEnthalpyJoulesPerMol());
        assertSamePhysicalResult(reduced, wrapped);
        assertEquals(3, reduced.iterations());
        wrapped.liquidComposition()[0] = 0.0;
        wrapped.vaporComposition()[0] = 0.0;
        assertArrayEquals(new double[]{0.4, 0.6}, wrapped.liquidComposition());
        assertArrayEquals(new double[]{0.8, 0.2}, wrapped.vaporComposition());
    }

    @Test
    void fallbackKeepsReferenceThermodynamicsAndCountsFailedReducedWork() {
        V3FlashResult reference = V3FlashResult.twoPhase(7, 0.25,
                new double[]{0.4, 0.6}, new double[]{0.8, 0.2}, 1_234.0, "reference detail");
        V3FlashTruncationEvidence evidence = new V3FlashTruncationEvidence(
                V3FlashTruncationEvidence.Status.FALLBACK, CUTOFF, 1, 0, 7, 4, false,
                0.0, 0.0, 0.0, 0.0, 0.0, 1_234.0, "reduced solve failed before error evaluation");

        V3FlashResult wrapped = reference.withTruncationEvidence(evidence);

        assertSamePhysicalResult(reference, wrapped);
        assertEquals(11, wrapped.iterations());
        assertEquals(1_234.0, wrapped.referenceMolarEnthalpyJoulesPerMol());
        assertFalse(wrapped.truncationEvidence().errorsEvaluated());
        assertEquals(V3FlashTruncationEvidence.Status.FALLBACK, wrapped.truncationEvidence().status());
        assertEquals(7, reference.iterations());
    }

    @Test
    void referenceOnlyEvidencePreservesReferenceResultsWithoutInventingReducedWork() {
        for (V3FlashTruncationEvidence.Status status : new V3FlashTruncationEvidence.Status[]{
                V3FlashTruncationEvidence.Status.NO_CANDIDATES, V3FlashTruncationEvidence.Status.SINGLE_PHASE}) {
            V3FlashResult reference = status == V3FlashTruncationEvidence.Status.SINGLE_PHASE
                    ? V3FlashResult.liquid(7, new double[]{1.0}, -1_234.0, "liquid reference")
                    : V3FlashResult.twoPhase(7, 0.25, new double[]{0.4, 0.6},
                            new double[]{0.8, 0.2}, -1_234.0, "two-phase reference");
            V3FlashTruncationEvidence evidence = new V3FlashTruncationEvidence(status,
                    CUTOFF, 0, 0, 7, 0, false, 0.0, 0.0, 0.0, 0.0, 0.0, -1_234.0, "reference only");

            V3FlashResult wrapped = reference.withTruncationEvidence(evidence);

            assertSamePhysicalResult(reference, wrapped);
            assertEquals(7, wrapped.iterations());
            assertEquals(-1_234.0, wrapped.referenceMolarEnthalpyJoulesPerMol());
            assertFalse(wrapped.truncationEvidence().errorsEvaluated());
            assertEquals(0, wrapped.truncationEvidence().reducedIterations());
            assertEquals(0, wrapped.truncationEvidence().omittedLiquidComponents());
            assertEquals(0, wrapped.truncationEvidence().omittedVaporComponents());
        }
    }

    @Test
    void appliedEvidenceRequiresEnabledPolicyActualOmissionsAndEvaluatedErrors() {
        assertThrows(IllegalArgumentException.class, () -> new V3FlashTruncationEvidence(
                V3FlashTruncationEvidence.Status.APPLIED, 0.0, 1, 0, 7, 3, true,
                0.0, 0.0, 0.0, 0.0, 0.0, 1_234.0, "disabled cannot apply"));
        assertThrows(IllegalArgumentException.class, () -> appliedEvidenceWithCounters(0, 0, 7, 3));
        assertThrows(IllegalArgumentException.class, () -> new V3FlashTruncationEvidence(
                V3FlashTruncationEvidence.Status.APPLIED, CUTOFF, 1, 0, 7, 3, false,
                0.0, 0.0, 0.0, 0.0, 0.0, 1_234.0, "unevaluated cannot apply"));
    }

    @Test
    void evidenceRejectsNegativeCounters() {
        assertThrows(IllegalArgumentException.class, () -> appliedEvidenceWithCounters(-1, 1, 7, 3));
        assertThrows(IllegalArgumentException.class, () -> appliedEvidenceWithCounters(1, -1, 7, 3));
        assertThrows(IllegalArgumentException.class, () -> appliedEvidenceWithCounters(1, 1, -1, 3));
        assertThrows(IllegalArgumentException.class, () -> appliedEvidenceWithCounters(1, 1, 7, -1));
        assertThrows(IllegalArgumentException.class,
                () -> appliedEvidenceWithCounters(1, 1, Integer.MAX_VALUE, 1));
        assertDoesNotThrow(() -> appliedEvidenceWithCounters(1, 1, Integer.MAX_VALUE - 1, 1));
    }

    @Test
    void appliedEvidenceAcceptsBudgetBoundariesButRejectsTheNextRepresentableValue() {
        V3TraceTruncationPolicy policy = V3TraceTruncationPolicy.of(CUTOFF);
        double[] budgets = {policy.maximumPhaseAllocationError(), policy.maximumVaporFractionError(),
                policy.maximumPhaseCompositionError(), 0.0, policy.maximumEnthalpyErrorJoulesPerMol(1_234.0)};

        assertDoesNotThrow(() -> appliedEvidenceWithErrors(budgets));
        for (int metric : new int[]{0, 1, 2, 4}) {
            double[] exceeded = budgets.clone();
            exceeded[metric] = Math.nextUp(exceeded[metric]);
            assertThrows(IllegalArgumentException.class, () -> appliedEvidenceWithErrors(exceeded));
        }
    }

    @Test
    void unevaluatedErrorsCannotMasqueradeAsMeasurements() {
        for (int metric = 0; metric < 5; metric++) {
            double[] errors = new double[5];
            errors[metric] = 1.0e-12;
            assertThrows(IllegalArgumentException.class, () -> new V3FlashTruncationEvidence(
                    V3FlashTruncationEvidence.Status.FALLBACK, CUTOFF, 1, 0, 7, 3, false,
                    errors[0], errors[1], errors[2], errors[3], errors[4], 1_234.0, "unavailable errors"));
        }
        assertDoesNotThrow(() -> new V3FlashTruncationEvidence(
                V3FlashTruncationEvidence.Status.FALLBACK, CUTOFF, 1, 0, 7, 3, true,
                1.0, 1.0, 1.0, 1.0, 1_000.0, 1_234.0, "evaluated candidate exceeded budgets"));
    }

    @Test
    void referenceOnlyStatusesRejectOmissionsReducedWorkAndEvaluatedErrors() {
        for (V3FlashTruncationEvidence.Status status : new V3FlashTruncationEvidence.Status[]{
                V3FlashTruncationEvidence.Status.NO_CANDIDATES, V3FlashTruncationEvidence.Status.SINGLE_PHASE}) {
            assertThrows(IllegalArgumentException.class, () -> new V3FlashTruncationEvidence(status,
                    CUTOFF, 1, 0, 7, 0, false, 0.0, 0.0, 0.0, 0.0, 0.0, 1_234.0, "unexpected omission"));
            assertThrows(IllegalArgumentException.class, () -> new V3FlashTruncationEvidence(status,
                    CUTOFF, 0, 0, 7, 1, false, 0.0, 0.0, 0.0, 0.0, 0.0, 1_234.0, "unexpected reduced work"));
            assertThrows(IllegalArgumentException.class, () -> new V3FlashTruncationEvidence(status,
                    CUTOFF, 0, 0, 7, 0, true, 0.0, 0.0, 0.0, 0.0, 0.0, 1_234.0, "unexpected evaluation"));
        }
    }

    @Test
    void evidenceRejectsNegativeAndNonfiniteErrorMetrics() {
        for (double invalid : new double[]{-Double.MIN_VALUE, Double.NaN,
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            for (int metric = 0; metric < 5; metric++) {
                double[] errors = new double[5];
                errors[metric] = invalid;
                assertThrows(IllegalArgumentException.class, () -> appliedEvidenceWithErrors(errors));
            }
        }
    }

    @Test
    void evidenceRejectsNonfiniteReferenceEnthalpyAndMalformedCutoff() {
        for (double enthalpy : new double[]{Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new V3FlashTruncationEvidence(
                    V3FlashTruncationEvidence.Status.APPLIED, CUTOFF, 1, 0, 7, 3, true,
                    0.0, 0.0, 0.0, 0.0, 0.0, enthalpy, "invalid enthalpy"));
        }
        for (double cutoff : new double[]{-Double.MIN_VALUE, Math.nextUp(V3TraceTruncationPolicy.MAX_CUTOFF),
                Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new V3FlashTruncationEvidence(
                    V3FlashTruncationEvidence.Status.APPLIED, cutoff, 1, 0, 7, 3, true,
                    0.0, 0.0, 0.0, 0.0, 0.0, 1_234.0, "invalid cutoff"));
        }
    }

    @Test
    void evidenceAndResultWrappingRejectNullEvidenceState() {
        assertThrows(NullPointerException.class, () -> new V3FlashTruncationEvidence(
                null, CUTOFF, 1, 0, 7, 3, true,
                0.0, 0.0, 0.0, 0.0, 0.0, 1_234.0, "missing status"));
        V3FlashResult reference = V3FlashResult.liquid(7, new double[]{1.0}, 1_234.0, "reference");
        assertThrows(NullPointerException.class, () -> reference.withTruncationEvidence(null));
    }

    @Test
    void evidenceDetailIsRequiredNonblankAndBounded() {
        assertThrows(NullPointerException.class, () -> new V3FlashTruncationEvidence(
                V3FlashTruncationEvidence.Status.NO_CANDIDATES, CUTOFF, 0, 0, 7, 0, false,
                0.0, 0.0, 0.0, 0.0, 0.0, 1_234.0, null));
        for (String detail : new String[]{"", " \t\n", "a".repeat(257)}) {
            assertThrows(IllegalArgumentException.class, () -> new V3FlashTruncationEvidence(
                    V3FlashTruncationEvidence.Status.NO_CANDIDATES, CUTOFF, 0, 0, 7, 0, false,
                    0.0, 0.0, 0.0, 0.0, 0.0, 1_234.0, detail));
        }
        assertDoesNotThrow(() -> new V3FlashTruncationEvidence(
                V3FlashTruncationEvidence.Status.NO_CANDIDATES, CUTOFF, 0, 0, 7, 0, false,
                0.0, 0.0, 0.0, 0.0, 0.0, 1_234.0, "a".repeat(256)));
    }

    private static V3FlashTruncationEvidence appliedEvidenceWithCounters(
            int omittedLiquid, int omittedVapor, int referenceIterations, int reducedIterations) {
        return new V3FlashTruncationEvidence(V3FlashTruncationEvidence.Status.APPLIED,
                CUTOFF, omittedLiquid, omittedVapor, referenceIterations, reducedIterations, true,
                2.0e-4, 3.0e-5, 2.0e-4, 1.0e-16, 0.25, 1_234.0, "within budgets");
    }

    private static V3FlashTruncationEvidence appliedEvidenceWithErrors(double... errors) {
        return new V3FlashTruncationEvidence(V3FlashTruncationEvidence.Status.APPLIED,
                CUTOFF, 1, 0, 7, 3, true, errors[0], errors[1], errors[2], errors[3], errors[4],
                1_234.0, "error metric validation");
    }

    private static double[] overallComposition(double beta, double[] liquid, double[] vapor) {
        double[] overall = new double[liquid.length];
        for (int i = 0; i < overall.length; i++) {
            overall[i] = (1.0 - beta) * liquid[i] + beta * vapor[i];
        }
        return overall;
    }

    private static void assertSamePhysicalResult(V3FlashResult expected, V3FlashResult actual) {
        assertEquals(expected.phase(), actual.phase());
        assertEquals(expected.vaporFraction(), actual.vaporFraction());
        assertArrayEquals(expected.liquidComposition(), actual.liquidComposition());
        assertArrayEquals(expected.vaporComposition(), actual.vaporComposition());
        assertEquals(expected.molarEnthalpyJoulesPerMol(), actual.molarEnthalpyJoulesPerMol());
        assertEquals(expected.detail(), actual.detail());
    }
}
