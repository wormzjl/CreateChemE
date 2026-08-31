package com.wormzjl.createcheme.science.column.v3.thermo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class V3FlashTruncationTest {
    private static final String PACKAGE_ID = "createcheme:cdu17_tjl_acs2018";
    private static final String ASSAY_ID = "createcheme:tia_juana_light";
    private static final double CUTOFF = 1.0e-6;
    private static final int ETHANE = 1;
    private static final int PC12 = 15;

    @Test
    void explicitOffPreservesUnrestrictedValuesAndInputExactly() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE_ID);
        double[] input = binaryFeed(thermo, 5.0);
        double[] originalInput = input.clone();
        V3FlashResult unrestricted = thermo.flashTP(500.0, 250_000.0, input, thermo.newWorkspace());

        V3FlashResult off = thermo.flashTP(500.0, 250_000.0, input,
                V3TraceTruncationPolicy.OFF, thermo.newWorkspace());

        assertSameFlashValues(unrestricted, off);
        assertEquals(unrestricted.detail(), off.detail());
        assertEquals("DISABLED", off.truncationEvidence().status().name());
        assertEquals(0, off.truncationEvidence().omittedLiquidComponents());
        assertEquals(0, off.truncationEvidence().omittedVaporComponents());
        assertEquals(unrestricted.molarEnthalpyJoulesPerMol(), off.referenceMolarEnthalpyJoulesPerMol());
        assertArrayEquals(originalInput, input);
    }

    @Test
    void stateQualifiedHeavyVaporOmissionPreservesEveryComponentAndAuditsItsReferenceErrors() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE_ID);
        double[] feed = binaryFeed(thermo, 0.5);
        V3TraceTruncationPolicy policy = V3TraceTruncationPolicy.of(CUTOFF);
        V3FlashResult reference = thermo.flashTP(500.0, 250_000.0, feed, thermo.newWorkspace());
        assertEquals(V3FeedPhase.TWO_PHASE, reference.phase(), reference::detail);
        assertTrue(reference.vaporComposition()[PC12] > 0.0);
        assertTrue(reference.vaporComposition()[PC12] < CUTOFF,
                () -> "Fixture must qualify by its actual reference composition: " + reference.vaporComposition()[PC12]);
        assertTrue(reference.liquidComposition()[ETHANE] > CUTOFF);

        V3FlashResult truncated = thermo.flashTP(500.0, 250_000.0, feed, policy, thermo.newWorkspace());

        var evidence = truncated.truncationEvidence();
        assertEquals("APPLIED", evidence.status().name(), evidence::toString);
        assertEquals(V3FeedPhase.TWO_PHASE, truncated.phase());
        assertEquals(0, evidence.omittedLiquidComponents());
        assertEquals(1, evidence.omittedVaporComponents());
        assertEquals(0L, Double.doubleToLongBits(truncated.vaporComposition()[PC12]));
        assertTrue(truncated.liquidComposition()[PC12] > 0.0);
        assertTrue(evidence.errorsEvaluated());
        assertEquals(reference.iterations(), evidence.referenceIterations());
        assertTrue(evidence.reducedIterations() > 0);
        assertEquals(CUTOFF, evidence.cutoffMoleFraction());
        assertEquals(reference.molarEnthalpyJoulesPerMol(), truncated.referenceMolarEnthalpyJoulesPerMol());
        assertEquals(reference.molarEnthalpyJoulesPerMol(), evidence.referenceMolarEnthalpyJoulesPerMol());
        assertEquals(Math.abs(truncated.vaporFraction() - reference.vaporFraction()), evidence.betaError(), 1.0e-15);
        assertEquals(maximumPhaseCompositionError(reference, truncated), evidence.maxPhaseCompositionError(), 1.0e-15);
        assertEquals(Math.abs(truncated.molarEnthalpyJoulesPerMol() - reference.molarEnthalpyJoulesPerMol()),
                evidence.enthalpyErrorJoulesPerMol(), 1.0e-10);
        assertTrue(evidence.allocationError() <= policy.maximumPhaseAllocationError(), evidence::toString);
        assertTrue(evidence.betaError() <= policy.maximumVaporFractionError(), evidence::toString);
        assertTrue(evidence.maxPhaseCompositionError() <= policy.maximumPhaseCompositionError(), evidence::toString);
        assertTrue(evidence.enthalpyErrorJoulesPerMol()
                <= policy.maximumEnthalpyErrorJoulesPerMol(reference.molarEnthalpyJoulesPerMol()), evidence::toString);
        assertTrue(evidence.maxMaterialClosureError() <= 1.0e-12, evidence::toString);
        assertFullBasisMaterialClosure(feed, truncated);
    }

    @Test
    void hotCrudeRetainsPc12WhenItsVaporCompositionExceedsTheCutoff() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE_ID);
        double[] feed = thermo.crudeFeed(ASSAY_ID).moleFractions();
        V3FlashResult reference = thermo.flashTP(638.15, 137_250.0, feed, thermo.newWorkspace());
        assertEquals(V3FeedPhase.TWO_PHASE, reference.phase());
        assertTrue(reference.vaporComposition()[PC12] > CUTOFF);

        V3FlashResult requested = thermo.flashTP(638.15, 137_250.0, feed,
                V3TraceTruncationPolicy.of(CUTOFF), thermo.newWorkspace());

        assertEquals("NO_CANDIDATES", requested.truncationEvidence().status().name());
        assertEquals(0, requested.truncationEvidence().omittedLiquidComponents());
        assertEquals(0, requested.truncationEvidence().omittedVaporComponents());
        assertSameFlashValues(reference, requested);
        assertFullBasisMaterialClosure(feed, requested);
    }

    @Test
    void singlePhaseClassificationIsNotOverriddenByComponentIdentityOrTruncation() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE_ID);
        double[] feed = thermo.crudeFeed(ASSAY_ID).moleFractions();
        V3TraceTruncationPolicy policy = V3TraceTruncationPolicy.of(CUTOFF);

        V3FlashResult liquid = thermo.flashTP(298.15, 250_000.0, feed, policy, thermo.newWorkspace());
        V3FlashResult vapor = thermo.flashTP(900.0, 50_000.0, feed, policy, thermo.newWorkspace());

        assertEquals(V3FeedPhase.LIQUID, liquid.phase());
        assertEquals(V3FeedPhase.VAPOR, vapor.phase());
        assertEquals("SINGLE_PHASE", liquid.truncationEvidence().status().name());
        assertEquals("SINGLE_PHASE", vapor.truncationEvidence().status().name());
        assertEquals(0, liquid.vaporComposition().length);
        assertEquals(0, vapor.liquidComposition().length);
        assertTrue(vapor.vaporComposition()[PC12] > 0.0, "PC12 has no permanent vapor prohibition");
        assertEquals(0, vapor.truncationEvidence().omittedVaporComponents());
        assertFullBasisMaterialClosure(feed, liquid);
        assertFullBasisMaterialClosure(feed, vapor);
    }

    @Test
    void reusedWorkspaceAndReturnedArraysCannotCarryTruncationIntoTheDefaultFlash() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE_ID);
        V3ThermoWorkspace workspace = thermo.newWorkspace();
        double[] binary = binaryFeed(thermo, 0.5);
        V3FlashResult truncated = thermo.flashTP(500.0, 250_000.0, binary,
                V3TraceTruncationPolicy.of(CUTOFF), workspace);
        assertEquals("APPLIED", truncated.truncationEvidence().status().name());
        double[] savedLiquid = truncated.liquidComposition();
        double[] savedVapor = truncated.vaporComposition();
        truncated.liquidComposition()[PC12] = Double.NaN;
        truncated.vaporComposition()[ETHANE] = Double.NaN;
        double[] feed = thermo.crudeFeed(ASSAY_ID).moleFractions();

        V3FlashResult reused = thermo.flashTP(638.15, 137_250.0, feed, workspace);
        V3FlashResult fresh = thermo.flashTP(638.15, 137_250.0, feed, thermo.newWorkspace());

        assertSameFlashValues(fresh, reused);
        assertEquals("DISABLED", reused.truncationEvidence().status().name());
        assertArrayEquals(savedLiquid, truncated.liquidComposition());
        assertArrayEquals(savedVapor, truncated.vaporComposition());
        workspace.clear();
        assertSameFlashValues(fresh, thermo.flashTP(638.15, 137_250.0, feed, workspace));
    }

    @Test
    void cancellationEscapesUnchangedAndLeavesTheWorkspaceReusable() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE_ID);
        V3ThermoWorkspace workspace = thermo.newWorkspace();
        double[] feed = binaryFeed(thermo, 0.5);
        CancellationException cancelled = new CancellationException("cancel trace flash test");
        AtomicInteger checkpoints = new AtomicInteger();

        CancellationException actual = assertThrows(CancellationException.class,
                () -> thermo.flashTP(500.0, 250_000.0, feed, V3TraceTruncationPolicy.of(CUTOFF), workspace,
                        () -> { if (checkpoints.incrementAndGet() >= 3) throw cancelled; }));

        assertSame(cancelled, actual);
        assertSameFlashValues(thermo.flashTP(500.0, 250_000.0, feed, thermo.newWorkspace()),
                thermo.flashTP(500.0, 250_000.0, feed, workspace));
    }

    private static double[] binaryFeed(V3PengRobinsonThermo thermo, double amountPerComponent) {
        double[] feed = new double[thermo.componentBasis().componentCount()];
        feed[ETHANE] = amountPerComponent;
        feed[PC12] = amountPerComponent;
        return feed;
    }

    private static void assertSameFlashValues(V3FlashResult expected, V3FlashResult actual) {
        assertEquals(expected.phase(), actual.phase());
        assertEquals(expected.iterations(), actual.iterations());
        assertEquals(expected.vaporFraction(), actual.vaporFraction());
        assertArrayEquals(expected.liquidComposition(), actual.liquidComposition());
        assertArrayEquals(expected.vaporComposition(), actual.vaporComposition());
        assertEquals(expected.molarEnthalpyJoulesPerMol(), actual.molarEnthalpyJoulesPerMol());
        assertEquals(expected.referenceMolarEnthalpyJoulesPerMol(), actual.referenceMolarEnthalpyJoulesPerMol());
    }

    private static double maximumPhaseCompositionError(V3FlashResult reference, V3FlashResult candidate) {
        double maximum = 0.0;
        double[] referenceLiquid = reference.liquidComposition();
        double[] candidateLiquid = candidate.liquidComposition();
        double[] referenceVapor = reference.vaporComposition();
        double[] candidateVapor = candidate.vaporComposition();
        for (int component = 0; component < referenceLiquid.length; component++) {
            maximum = Math.max(maximum, Math.abs(referenceLiquid[component] - candidateLiquid[component]));
            maximum = Math.max(maximum, Math.abs(referenceVapor[component] - candidateVapor[component]));
        }
        return maximum;
    }

    private static void assertFullBasisMaterialClosure(double[] feed, V3FlashResult result) {
        double total = Arrays.stream(feed).sum();
        double[] liquid = result.liquidComposition();
        double[] vapor = result.vaporComposition();
        if (liquid.length > 0) assertEquals(1.0, Arrays.stream(liquid).sum(), 1.0e-12);
        if (vapor.length > 0) assertEquals(1.0, Arrays.stream(vapor).sum(), 1.0e-12);
        for (int component = 0; component < feed.length; component++) {
            double liquidAllocation = liquid.length == 0 ? 0.0 : (1.0 - result.vaporFraction()) * liquid[component];
            double vaporAllocation = vapor.length == 0 ? 0.0 : result.vaporFraction() * vapor[component];
            assertEquals(feed[component] / total, liquidAllocation + vaporAllocation, 1.0e-12, "component " + component);
            if (feed[component] == 0.0) {
                if (liquid.length > 0) assertEquals(0L, Double.doubleToLongBits(liquid[component]));
                if (vapor.length > 0) assertEquals(0L, Double.doubleToLongBits(vapor[component]));
            }
        }
    }
}
