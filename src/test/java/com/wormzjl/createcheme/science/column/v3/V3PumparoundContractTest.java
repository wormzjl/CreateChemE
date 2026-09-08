package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class V3PumparoundContractTest {
    /** Pinned before the pumparound field block existed; an empty list must not touch the byte stream. */
    private static final String HEAT_FREE_DIGEST = "f4dd1da57a23ee9895335a199b51b8a9fecf7388160f28b8cc2f34bdc1064ec6";

    @Test
    void specRejectsInvalidGeometryDutyAndSplit() {
        assertThrows(IllegalArgumentException.class,
                () -> new V3PumparoundSpec(0, 3, -1.0, V3PumparoundSpec.Split.UNIFORM));
        assertThrows(IllegalArgumentException.class,
                () -> new V3PumparoundSpec(4, 3, -1.0, V3PumparoundSpec.Split.UNIFORM));
        assertThrows(IllegalArgumentException.class,
                () -> new V3PumparoundSpec(2, 3, 0.0, V3PumparoundSpec.Split.UNIFORM));
        assertThrows(IllegalArgumentException.class,
                () -> new V3PumparoundSpec(2, 3, Double.NaN, V3PumparoundSpec.Split.UNIFORM));
        assertThrows(NullPointerException.class, () -> new V3PumparoundSpec(2, 3, -1.0, null));
        assertDoesNotThrow(() -> new V3PumparoundSpec(3, 3, 1_000.0, V3PumparoundSpec.Split.RETURN_TRAY));
    }

    @Test
    void inputCanonicalisesPumparoundsAndBoundsTheirCount() {
        V3PumparoundSpec low = new V3PumparoundSpec(2, 4, -1.0e6, V3PumparoundSpec.Split.UNIFORM);
        V3PumparoundSpec high = new V3PumparoundSpec(1, 4, -2.0e6, V3PumparoundSpec.Split.RETURN_TRAY);

        assertEquals(List.of(high, low), input(List.of(low, high)).pumparounds());
        assertEquals(List.of(), input(List.of()).pumparounds());
        assertThrows(IllegalArgumentException.class, () -> input(List.of(
                new V3PumparoundSpec(1, 2, -1.0e6, V3PumparoundSpec.Split.UNIFORM),
                new V3PumparoundSpec(1, 2, -2.0e6, V3PumparoundSpec.Split.RETURN_TRAY))));
        assertThrows(IllegalArgumentException.class, () -> input(List.of(
                new V3PumparoundSpec(1, 2, -1.0e6, V3PumparoundSpec.Split.UNIFORM),
                new V3PumparoundSpec(1, 3, -1.0e6, V3PumparoundSpec.Split.UNIFORM),
                new V3PumparoundSpec(2, 3, -1.0e6, V3PumparoundSpec.Split.UNIFORM),
                new V3PumparoundSpec(2, 4, -1.0e6, V3PumparoundSpec.Split.UNIFORM))));
    }

    @Test
    void expansionPlacesTheWholeDutyOnOneTraySpreadsItUniformlyAndAddsOverlappingZones() {
        V3ColumnTopology topology = V3ColumnTopology.twoPhase(4, 2);

        double[] single = V3Pumparounds.nodeDutyWatts(
                input(List.of(new V3PumparoundSpec(2, 4, -900.0, V3PumparoundSpec.Split.RETURN_TRAY))), topology);
        double[] uniform = V3Pumparounds.nodeDutyWatts(
                input(List.of(new V3PumparoundSpec(2, 4, -900.0, V3PumparoundSpec.Split.UNIFORM))), topology);
        double[] overlapping = V3Pumparounds.nodeDutyWatts(input(List.of(
                new V3PumparoundSpec(2, 4, -900.0, V3PumparoundSpec.Split.UNIFORM),
                new V3PumparoundSpec(3, 4, -400.0, V3PumparoundSpec.Split.UNIFORM))), topology);

        assertEquals(6, single.length);
        assertArrayEqualsExactly(new double[] {0.0, 0.0, -900.0, 0.0, 0.0, 0.0}, single);
        assertArrayEqualsExactly(new double[] {0.0, 0.0, -300.0, -300.0, -300.0, 0.0}, uniform);
        assertArrayEqualsExactly(new double[] {0.0, 0.0, -300.0, -500.0, -500.0, 0.0}, overlapping);
        assertEquals(-1_300.0, V3Pumparounds.totalCoolingWatts(input(List.of(
                new V3PumparoundSpec(2, 4, -900.0, V3PumparoundSpec.Split.UNIFORM),
                new V3PumparoundSpec(3, 4, -400.0, V3PumparoundSpec.Split.UNIFORM)))));
    }

    /**
     * The two gross halves of the authored heat, which the cooling admission bounds compare against each other.
     *
     * <p>A cooler paired with an equal heater expands to exactly zero net stage heat, so no energy bound may
     * read its gross cooling without the matching heating credit.</p>
     */
    @Test
    void theGrossCoolingAndHeatingHalvesSplitTheAuthoredDutiesAndCancelOnTheExpandedProfile() {
        V3ColumnTopology topology = V3ColumnTopology.twoPhase(4, 2);
        V3ColumnInput mixed = input(List.of(
                new V3PumparoundSpec(2, 4, -900.0, V3PumparoundSpec.Split.UNIFORM),
                new V3PumparoundSpec(3, 4, 400.0, V3PumparoundSpec.Split.RETURN_TRAY)));

        assertEquals(-900.0, V3Pumparounds.totalCoolingWatts(mixed));
        assertEquals(400.0, V3Pumparounds.totalHeatingWatts(mixed));
        assertEquals(-500.0, V3Pumparounds.totalDutyWatts(mixed), 1.0e-12);
        assertEquals(400.0, V3HeatFeasibility.heatingCreditWatts(mixed));
        assertEquals(0.0, V3Pumparounds.totalHeatingWatts(input(List.of(
                new V3PumparoundSpec(2, 4, -900.0, V3PumparoundSpec.Split.UNIFORM)))));
        assertEquals(0.0, V3Pumparounds.totalCoolingWatts(input(List.of(
                new V3PumparoundSpec(2, 4, 900.0, V3PumparoundSpec.Split.UNIFORM)))));

        // Two distinct return/draw pairs that both place their whole duty on tray two cancel node by node.
        assertArrayEqualsExactly(new double[] {0.0, 0.0, 0.0, 0.0, 0.0, 0.0}, V3Pumparounds.nodeDutyWatts(
                input(List.of(new V3PumparoundSpec(2, 3, -900.0, V3PumparoundSpec.Split.RETURN_TRAY),
                        new V3PumparoundSpec(2, 4, 900.0, V3PumparoundSpec.Split.RETURN_TRAY))), topology));
    }

    /** The heating credit only ever grows the budget; it never turns a heater into extra cooling. */
    @Test
    void theHeatingCreditIsNonNegativeAndTheAdmissionDetailNamesItOnlyWhenItIsSpent() {
        String withoutHeater = V3HeatFeasibility.staticAdmissionDetail(2.0e6, 1.0e6, 0.0);
        String withHeater = V3HeatFeasibility.staticAdmissionDetail(2.0e6, 1.0e6, 5.0e5);

        assertEquals(0.0, V3HeatFeasibility.heatingCreditWatts(input(List.of(
                new V3PumparoundSpec(2, 4, -900.0, V3PumparoundSpec.Split.UNIFORM)))));
        assertTrue(withoutHeater.contains("exceeds"), withoutHeater);
        assertTrue(withoutHeater.endsWith("condenser outlet temperature"), withoutHeater);
        assertTrue(withHeater.startsWith(withoutHeater), withHeater);
        assertTrue(withHeater.contains("0.5000 MW of authored stage heating credited"), withHeater);
        assertTrue(V3HeatFeasibility.condenserBoundDetail(2.0e6, -1.0e6, 0.0)
                .endsWith("without stage heat; the overhead would vanish"));
        assertTrue(V3HeatFeasibility.condenserBoundDetail(2.0e6, -1.0e6, 5.0e5)
                .contains("0.5000 MW of authored stage heating credited"));
    }

    @Test
    void resolverRejectsAPumparoundOutsideTheEquilibriumTrayRangeAndKeepsEveryDegreeOfFreedom() {
        V3ColumnProblem plain = V3ColumnProblemResolver.resolve(input(List.of()), V3CondenserPhaseBranch.TWO_PHASE);
        V3ColumnProblem heated = V3ColumnProblemResolver.resolve(
                input(List.of(new V3PumparoundSpec(2, 4, -900.0, V3PumparoundSpec.Split.UNIFORM))),
                V3CondenserPhaseBranch.TWO_PHASE);

        assertThrows(IllegalArgumentException.class, () -> V3ColumnProblemResolver.resolve(
                input(List.of(new V3PumparoundSpec(2, 5, -900.0, V3PumparoundSpec.Split.UNIFORM))),
                V3CondenserPhaseBranch.TWO_PHASE));
        assertEquals(plain.degreeOfFreedomLedger().unknownCount(), heated.degreeOfFreedomLedger().unknownCount());
        assertEquals(plain.degreeOfFreedomLedger().equationCount(), heated.degreeOfFreedomLedger().equationCount());
        assertEquals(plain.degreeOfFreedomLedger().structuralRank(), heated.degreeOfFreedomLedger().structuralRank());
        assertTrue(heated.degreeOfFreedomLedger().isValid());
        assertTrue(heated.hasPumparounds());
        assertEquals(-300.0, heated.stageHeatWatts(3));
        assertEquals(0.0, heated.stageHeatWatts(1));
        assertEquals(0.0, plain.stageHeatWatts(3));
    }

    @Test
    void heatFreeDigestIsUnchangedAndEveryPumparoundFieldChangesIt() {
        V3InputDigest heatFree = digest(List.of());
        V3InputDigest base = digest(List.of(new V3PumparoundSpec(2, 4, -900.0, V3PumparoundSpec.Split.UNIFORM)));

        assertEquals(HEAT_FREE_DIGEST, heatFree.hexadecimalSha256());
        assertEquals(heatFree, digest(List.of()));
        assertNotEquals(heatFree, base);
        assertNotEquals(base, digest(List.of(new V3PumparoundSpec(1, 4, -900.0, V3PumparoundSpec.Split.UNIFORM))));
        assertNotEquals(base, digest(List.of(new V3PumparoundSpec(2, 3, -900.0, V3PumparoundSpec.Split.UNIFORM))));
        assertNotEquals(base, digest(List.of(new V3PumparoundSpec(2, 4, -901.0, V3PumparoundSpec.Split.UNIFORM))));
        assertNotEquals(base, digest(List.of(new V3PumparoundSpec(2, 4, -900.0, V3PumparoundSpec.Split.RETURN_TRAY))));
    }

    private static void assertArrayEqualsExactly(double[] expected, double[] actual) {
        assertEquals(expected.length, actual.length);
        for (int index = 0; index < expected.length; index++) {
            assertEquals(expected[index], actual[index], 1.0e-12, "node " + index);
        }
    }

    private static V3InputDigest digest(List<V3PumparoundSpec> pumparounds) {
        return V3InputDigest.of(V3ColumnProblemResolver.resolve(input(pumparounds), V3CondenserPhaseBranch.TWO_PHASE),
                "mesh-v1", "ideal-v1", "m0-v1");
    }

    private static V3ColumnInput input(List<V3PumparoundSpec> pumparounds) {
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, "test:ideal_binary", "test:pumparound",
                new V3ComponentBasis(List.of("methane", "n-pentane")), new double[] {40.0, 60.0}, 450.0, 4, 2,
                250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(332.15),
                        new V3ColumnSpecification.OrganicRefluxRatio(4.17),
                        new V3ColumnSpecification.ReboilerDuty(8_000_000.0)), List.of(), List.of(), pumparounds);
    }
}
