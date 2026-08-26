package com.wormzjl.createcheme.science.column.nextgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class ColumnNextAuthoringTest {
    @Test
    void parsesMolarAndMassSideDraws() {
        List<ColumnNextInput.SideDrawInput> draws = ColumnNextAuthoring.parseSideDraws("8,m,10;15,kg,2.5");

        assertEquals(2, draws.size());
        assertEquals(ColumnNextInput.AuthoredBasis.MOLAR, draws.getFirst().basis());
        assertEquals(ColumnNextInput.AuthoredBasis.MASS, draws.get(1).basis());
        assertEquals(2.5, draws.get(1).authoredRate(), 0.0);
    }

    @Test
    void parsesWaterAndSteamWithKpaPressure() {
        List<ColumnNextInput.WaterSteamFeedInput> utilities = ColumnNextAuthoring.parseUtilities(
                "water,8,1.5,350,250;steam,24,2.0,500,350");

        assertEquals(2, utilities.size());
        assertEquals(ColumnNextInput.UtilityFeedMode.WATER, utilities.getFirst().mode());
        assertEquals(ColumnNextInput.UtilityFeedMode.STEAM, utilities.get(1).mode());
        assertEquals(350_000.0, utilities.get(1).upstreamPressurePascal(), 0.0);
    }

    @Test
    void rejectsMalformedRows() {
        assertThrows(IllegalArgumentException.class, () -> ColumnNextAuthoring.parseSideDraws("8,m"));
        assertThrows(IllegalArgumentException.class, () -> ColumnNextAuthoring.parseUtilities("steam,24,2"));
    }
}
