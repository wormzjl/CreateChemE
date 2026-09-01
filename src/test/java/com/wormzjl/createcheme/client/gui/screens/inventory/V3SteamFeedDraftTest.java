package com.wormzjl.createcheme.client.gui.screens.inventory;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.createcheme.science.column.v3.V3SteamFeedSpec;
import java.util.List;
import org.junit.jupiter.api.Test;

class V3SteamFeedDraftTest {
    @Test
    void blankAndZeroRatesDisableSteamGroups() {
        assertEquals(List.of(), V3SteamFeedDraft.parse(row("", "", ""), row("invalid", "0", ""), 4, 100));
        assertEquals(List.of(new V3SteamFeedSpec(5, 10.0, 450.0)),
                V3SteamFeedDraft.parse(row("", "36", "176.85"), row("", "", ""), 4, 100));
    }

    @Test
    void rejectsIncompleteAndOutOfRangeTraySteam() {
        assertThrows(IllegalArgumentException.class,
                () -> V3SteamFeedDraft.parse(row("", "1", ""), row("", "", ""), 4, 100));
        assertThrows(IllegalArgumentException.class,
                () -> V3SteamFeedDraft.parse(row("", "", ""), row("5", "1", "200"), 4, 100));
        assertThrows(IllegalArgumentException.class,
                () -> V3SteamFeedDraft.parse(row("", "", ""), row("2", "Infinity", "200"), 4, 100));
    }

    private static V3SteamFeedDraft.Row row(String stage, String rate, String temperature) {
        return new V3SteamFeedDraft.Row(stage, rate, temperature);
    }
}
