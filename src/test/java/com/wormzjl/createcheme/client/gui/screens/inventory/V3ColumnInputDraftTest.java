package com.wormzjl.createcheme.client.gui.screens.inventory;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.createcheme.science.column.v3.V3ColumnInput;
import com.wormzjl.createcheme.science.column.v3.V3ColumnSpecification;
import com.wormzjl.createcheme.science.column.v3.V3ComponentBasis;
import com.wormzjl.createcheme.science.column.v3.V3PumparoundSpec;
import com.wormzjl.createcheme.science.column.v3.V3SideDrawSpec;
import java.util.List;
import org.junit.jupiter.api.Test;

class V3ColumnInputDraftTest {
    private static final List<String> SCALARS =
            List.of("360", "365", "24", "12", "29", "8", "2", "1.5", "0.75");

    @Test
    void assembledInputCarriesTheAuthoredCoolersWithTheirSplits() {
        V3ColumnInput assembled = V3ColumnInputDraft.assemble(base(), SCALARS, blankDraws(), blankSteam(), blankSteam(),
                List.of(
                        new V3PumparoundDraft.Row("12", "9", "2.5", V3PumparoundSpec.Split.RETURN_TRAY),
                        new V3PumparoundDraft.Row("20", "16", "5", V3PumparoundSpec.Split.UNIFORM),
                        new V3PumparoundDraft.Row("", "", "", V3PumparoundSpec.Split.UNIFORM)));
        assertEquals(List.of(
                new V3PumparoundSpec(9, 12, -2.5e6, V3PumparoundSpec.Split.RETURN_TRAY),
                new V3PumparoundSpec(16, 20, -5.0e6, V3PumparoundSpec.Split.UNIFORM)), assembled.pumparounds());
        assertEquals(24, assembled.stageCount());
        assertEquals(12, assembled.feedStageNumber());
        assertEquals(100.0, totalFeed(assembled), 1.0e-9);
    }

    @Test
    void emptyCoolerRowsKeepTheDryPumparoundContract() {
        V3ColumnInput assembled = V3ColumnInputDraft.assemble(base(), SCALARS, blankDraws(), blankSteam(), blankSteam(),
                List.of(new V3PumparoundDraft.Row("", "", "", V3PumparoundSpec.Split.UNIFORM)));
        assertEquals(List.of(), assembled.pumparounds());
    }

    @Test
    void coolerRowsAreAssembledAlongsideSideDrawsAndReportTheirOwnDiagnostics() {
        V3ColumnInput assembled = V3ColumnInputDraft.assemble(base(), SCALARS,
                List.of(new V3SideDrawDraft.Row("18", "36"), new V3SideDrawDraft.Row("", ""),
                        new V3SideDrawDraft.Row("", "")),
                blankSteam(), blankSteam(),
                List.of(new V3PumparoundDraft.Row("20", "16", "5", V3PumparoundSpec.Split.UNIFORM)));
        assertEquals(List.of(new V3SideDrawSpec(18, 10.0)), assembled.sideDraws());
        assertEquals(1, assembled.pumparounds().size());
        assertEquals("Heat: Cooler 1 cooling must be a positive number of MW",
                assertThrows(IllegalArgumentException.class, () -> V3ColumnInputDraft.assemble(base(), SCALARS,
                        blankDraws(), blankSteam(), blankSteam(),
                        List.of(new V3PumparoundDraft.Row("20", "16", "-5", V3PumparoundSpec.Split.UNIFORM))))
                        .getMessage());
    }

    private static double totalFeed(V3ColumnInput input) {
        double total = 0.0;
        for (double flow : input.feedComponentMolarFlowsMolPerSecond()) total += flow;
        return total;
    }

    private static List<V3SideDrawDraft.Row> blankDraws() {
        return List.of(new V3SideDrawDraft.Row("", ""), new V3SideDrawDraft.Row("", ""), new V3SideDrawDraft.Row("", ""));
    }

    private static V3SteamFeedDraft.Row blankSteam() {
        return new V3SteamFeedDraft.Row("", "", "");
    }

    private static V3ColumnInput base() {
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, "test:gui", "test:draft",
                new V3ComponentBasis(List.of("a", "b")), new double[] {4.0, 6.0}, 450.0, 24, 12, 250_000.0, 750.0,
                List.of(new V3ColumnSpecification.CondenserOutletTemperature(332.15),
                        new V3ColumnSpecification.OrganicRefluxRatio(4.17),
                        new V3ColumnSpecification.ReboilerDuty(8_000_000.0)));
    }
}
