package com.wormzjl.createcheme.client.gui.screens.inventory;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.createcheme.science.column.v3.V3SideDrawSpec;
import java.util.List;
import org.junit.jupiter.api.Test;

class V3SideDrawDraftTest {
    @Test
    void blankAndZeroRatesDisableRowsWithoutRequiringATray() {
        assertEquals(List.of(), V3SideDrawDraft.parse(List.of(row("", ""), row("invalid", "0"), row("-1", "-0")), 4, 100));
        assertEquals(List.of(new V3SideDrawSpec(2, 10)), V3SideDrawDraft.parse(List.of(row(" 2 ", " 36 ")), 4, 100));
    }

    @Test
    void rejectsInvalidOrDuplicateTraysNonfiniteRatesAndExcessiveTotal() {
        for (String tray : List.of("0", "5", "2.5", "")) {
            assertThrows(IllegalArgumentException.class, () -> V3SideDrawDraft.parse(List.of(row(tray, "1")), 4, 100));
        }
        for (String rate : List.of("NaN", "Infinity", "-1", "360")) {
            assertThrows(IllegalArgumentException.class, () -> V3SideDrawDraft.parse(List.of(row("1", rate)), 4, 100));
        }
        assertThrows(IllegalArgumentException.class, () -> V3SideDrawDraft.parse(List.of(row("2", "1"), row("2", "2")), 4, 100));
    }

    private static V3SideDrawDraft.Row row(String tray, String rate) { return new V3SideDrawDraft.Row(tray, rate); }
}
