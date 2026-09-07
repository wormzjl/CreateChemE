package com.wormzjl.createcheme.client.gui.screens.inventory;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.createcheme.science.column.v3.V3PumparoundSpec;
import java.util.List;
import org.junit.jupiter.api.Test;

class V3PumparoundDraftTest {
    @Test
    void authoredCoolingIsNegatedIntoTheScienceDutyAndRoundTrips() {
        assertEquals(-5.0e6, V3PumparoundDraft.coolingMegawattsToDutyWatts(5.0));
        assertEquals(5.0, V3PumparoundDraft.dutyWattsToCoolingMegawatts(-5.0e6));
        assertEquals(-1.0e6, V3PumparoundDraft.coolingMegawattsToDutyWatts(1.0));
        for (double megawatts : List.of(0.1, 0.5, 3.3, 5.0, 12.75, 250.0)) {
            assertEquals(megawatts, V3PumparoundDraft.dutyWattsToCoolingMegawatts(
                    V3PumparoundDraft.coolingMegawattsToDutyWatts(megawatts)), () -> "round trip " + megawatts);
        }
        // A server-authored heater is positive in the contract and therefore shows as negative cooling.
        assertEquals(-2.0, V3PumparoundDraft.dutyWattsToCoolingMegawatts(2.0e6));
    }

    @Test
    void bothTraysBlankDisablesTheRowWithoutReadingTheDuty() {
        assertEquals(List.of(), V3PumparoundDraft.parse(List.of(
                row("", "", "", V3PumparoundSpec.Split.UNIFORM),
                row("  ", " ", "5", V3PumparoundSpec.Split.RETURN_TRAY)), 24));
    }

    @Test
    void completeRowsBecomeSignedSpecificationsInRowOrder() {
        List<V3PumparoundSpec> coolers = V3PumparoundDraft.parse(List.of(
                row("20", "16", "5.0", V3PumparoundSpec.Split.UNIFORM),
                row("", "", "", V3PumparoundSpec.Split.UNIFORM),
                row("12", "9", "2.5", V3PumparoundSpec.Split.RETURN_TRAY)), 24);
        assertEquals(List.of(
                new V3PumparoundSpec(16, 20, -5.0e6, V3PumparoundSpec.Split.UNIFORM),
                new V3PumparoundSpec(9, 12, -2.5e6, V3PumparoundSpec.Split.RETURN_TRAY)), coolers);
    }

    @Test
    void oneBlankTrayIsAnErrorRatherThanADisabledRow() {
        assertEquals("Cooler 1 needs both a draw tray and a return tray", message(
                row("20", "", "5", V3PumparoundSpec.Split.UNIFORM)));
        assertEquals("Cooler 1 needs both a draw tray and a return tray", message(
                row("", "16", "5", V3PumparoundSpec.Split.UNIFORM)));
    }

    @Test
    void nonPositiveOrUnreadableCoolingIsRejectedWithTheFieldName() {
        assertEquals("Cooler 1 needs a cooling duty in MW", message(
                row("20", "16", "", V3PumparoundSpec.Split.UNIFORM)));
        assertEquals("Cooler 1 cooling must be a number of MW", message(
                row("20", "16", "warm", V3PumparoundSpec.Split.UNIFORM)));
        for (String cooling : List.of("0", "-5", "NaN", "Infinity")) {
            assertEquals("Cooler 1 cooling must be a positive number of MW", message(
                    row("20", "16", cooling, V3PumparoundSpec.Split.UNIFORM)));
        }
    }

    @Test
    void traysMustBeWholeNumbersInsideTheColumnAndOrderedDrawBelowReturn() {
        assertEquals("Cooler 1 draw tray must be a whole tray number", message(
                row("20.5", "16", "5", V3PumparoundSpec.Split.UNIFORM)));
        assertEquals("Cooler 1 must stay within trays 1..24", message(
                row("25", "16", "5", V3PumparoundSpec.Split.UNIFORM)));
        assertEquals("Cooler 1 must stay within trays 1..24", message(
                row("20", "0", "5", V3PumparoundSpec.Split.UNIFORM)));
        assertEquals("Cooler 1 must return above the tray it draws from", message(
                row("12", "16", "5", V3PumparoundSpec.Split.UNIFORM)));
    }

    @Test
    void repeatedTrayPairsAndOverlongRowListsAreRejected() {
        assertEquals("Cooler 2 repeats an existing draw and return tray pair",
                assertThrows(IllegalArgumentException.class, () -> V3PumparoundDraft.parse(List.of(
                        row("20", "16", "5", V3PumparoundSpec.Split.UNIFORM),
                        row("20", "16", "3", V3PumparoundSpec.Split.RETURN_TRAY)), 24)).getMessage());
        assertThrows(IllegalArgumentException.class, () -> V3PumparoundDraft.parse(List.of(
                row("20", "16", "5", V3PumparoundSpec.Split.UNIFORM),
                row("19", "15", "5", V3PumparoundSpec.Split.UNIFORM),
                row("18", "14", "5", V3PumparoundSpec.Split.UNIFORM),
                row("17", "13", "5", V3PumparoundSpec.Split.UNIFORM)), 24));
    }

    @Test
    void aSingleTrayZoneIsAllowedForBothSplits() {
        assertEquals(List.of(new V3PumparoundSpec(16, 16, -1.0e6, V3PumparoundSpec.Split.RETURN_TRAY)),
                V3PumparoundDraft.parse(List.of(row("16", "16", "1", V3PumparoundSpec.Split.RETURN_TRAY)), 24));
    }

    private static String message(V3PumparoundDraft.Row row) {
        return assertThrows(IllegalArgumentException.class, () -> V3PumparoundDraft.parse(List.of(row), 24))
                .getMessage();
    }

    private static V3PumparoundDraft.Row row(
            String drawTray, String returnTray, String cooling, V3PumparoundSpec.Split split) {
        return new V3PumparoundDraft.Row(drawTray, returnTray, cooling, split);
    }
}
