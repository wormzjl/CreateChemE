package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.createcheme.science.column.v3.thermo.V3CrudeFeed;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A solve path is a diagnostic. Its length must never decide what a solve publishes.
 *
 * <p>The diagnostics record bounds the path at {@link V3SolverDiagnostics#MAX_SOLVE_PATH_LENGTH}. A low-pressure
 * request that also ramps a draw, steam and stage heat writes a longer one, and the record's rejection used to
 * surface as {@code INVALID_INPUT: solvePath is blank or exceeds the bounded contract}, discarding the completed
 * solve and blaming the request.</p>
 */
class V3BoundedSolvePathTest {
    private static final int LIMIT = V3SolverDiagnostics.MAX_SOLVE_PATH_LENGTH;

    @Test
    void aPathInsideTheContractIsReturnedUntouched() {
        String path = "cold/stage-continuation/4-8-15-30-40/fine-fd/draw-ramp-1.0/draws-1/steam-1/heat-1";
        assertEquals(path, V3ColumnCalculator.boundedSolvePath(path));
        assertNull(V3ColumnCalculator.boundedSolvePath(null));
        assertEquals(" ", V3ColumnCalculator.boundedSolvePath(" "));
    }

    @Test
    void aLongPressureLadderIsReducedToItsEndsAndKeepsEverythingAroundIt() {
        String ladder = "150-145-140-135-130-125-120-115-110-105-100-95-90-85-80-75";
        String head = "cold/pressure-continuation/";
        String tail = "/top-75kpa/fine-fd/draw-ramp-1.0/liquid-only-condenser/draws-1/steam-1/heat-1";
        String path = head + ladder + tail;
        assertTrue(path.length() > LIMIT, "fixture must exceed the contract");

        assertEquals(head + "150..75" + tail, V3ColumnCalculator.boundedSolvePath(path));
    }

    @Test
    void aPathThatIsStillTooLongKeepsItsHeadAndItsTail() {
        String path = "cold/" + "segment/".repeat(40) + "failed-stage-40";

        String bounded = V3ColumnCalculator.boundedSolvePath(path);

        assertEquals(LIMIT, bounded.length());
        assertTrue(bounded.startsWith(path.substring(0, 80)));
        assertTrue(bounded.contains("/.../"));
        assertTrue(bounded.endsWith("failed-stage-40"));
    }

    @Test
    void aCompressedLadderThatIsStillTooLongIsClampedAsWell() {
        String path = "cold/pressure-continuation/150-140-130-120-110-105-100/" + "recovery/".repeat(20) + "fine-fd";

        String bounded = V3ColumnCalculator.boundedSolvePath(path);

        assertEquals(LIMIT, bounded.length());
        assertTrue(bounded.startsWith("cold/pressure-continuation/150..100/"));
        assertTrue(bounded.endsWith("fine-fd"));
    }

    /**
     * Measured 2026-09-18 on main: this request completes its low-pressure ladder and feature ramp, then published
     * {@code INVALID_INPUT} because the path it wrote was longer than the diagnostics contract.
     */
    @Test
    void aLongContinuationPathPublishesTheSolvesOwnOutcome() {
        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(longPathInput());

        String path = outcome.diagnostics().solvePath();
        assertTrue(path.length() <= LIMIT, path);
        assertTrue(path.contains(".."), "fixture no longer writes a path beyond the contract: " + path);
        if (outcome instanceof V3ColumnOutcome.Failure failure) {
            assertNotEquals(V3SolverFailureCode.INVALID_INPUT, failure.code(), failure.summary());
            assertFalse(failure.summary().contains("bounded contract"), failure.summary());
        }
    }

    /** 18 trays at 90 kPa(a): the fine pressure ladder, then a draw, sump steam and a cooled zone to ramp. */
    private static V3ColumnInput longPathInput() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:tjl19_dwsim");
        V3CrudeFeed crude = thermo.crudeFeed("createcheme:tia_juana_light");
        double total = 0.85 * 737.6996333000835;
        double[] feed = crude.moleFractions();
        for (int i = 0; i < feed.length; i++) feed[i] *= total;
        return new V3ColumnInput(1, crude.packageId(), crude.assayId(), crude.componentBasis(), feed,
                653.15, 18, 14, 90_000.0, 750.0, List.of(
                new V3ColumnSpecification.CondenserOutletTemperature(332.15),
                new V3ColumnSpecification.OrganicRefluxRatio(5.0),
                new V3ColumnSpecification.ReboilerDuty(6_000_000.0)),
                List.of(new V3SideDrawSpec(9, 0.03 * total)),
                List.of(new V3SteamFeedSpec(19, 0.10 * total, 533.15)),
                List.of(new V3PumparoundSpec(7, 9, -2_000_000.0, V3PumparoundSpec.Split.UNIFORM)));
    }
}
