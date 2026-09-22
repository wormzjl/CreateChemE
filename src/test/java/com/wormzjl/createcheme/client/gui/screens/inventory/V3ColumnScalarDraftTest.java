package com.wormzjl.createcheme.client.gui.screens.inventory;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class V3ColumnScalarDraftTest {
    private static final List<String> VALID = List.of(
            "2610.7", "365", "30", "24", "126.85", "8", "2", "2.5", "0.75", "8");

    @Test
    void parsesUnitsIndependentlyOfSideDrawRows() {
        V3ColumnScalarDraft.Values values = V3ColumnScalarDraft.parse(VALID);
        assertEquals(2610.7 / 3.6, values.feedMolPerSecond(), 1e-12);
        assertEquals(638.15, values.feedTemperatureKelvin(), 1e-12);
        assertEquals(30, values.stageCount());
        assertEquals(24, values.feedStage());
        assertEquals(400.0, values.condenserTemperatureKelvin(), 1e-12);
        assertEquals(8_000_000, values.reboilerDutyWatts());
        assertEquals(250_000, values.topPressurePascal());
        assertEquals(750, values.pressureDropPascal());
        assertEquals(8.0, values.columnDiameterMetres(), 1e-12);
    }

    @Test
    void identifiesTheInvalidScalarInsteadOfBlamingDraws() {
        assertInvalid(1, "-274", "Feed temperature");
        assertInvalid(2, "30.5", "Stage count");
        assertInvalid(3, "31", "Feed stage");
        assertInvalid(6, "NaN", "Reflux ratio");
        assertInvalid(6, "-0.01", "Reflux ratio");
        assertInvalid(7, "0", "Top pressure");
        assertInvalid(9, "-1", "Column diameter");
        assertInvalid(9, "16", "Column diameter");
    }

    @Test
    void acceptsTrainingRangeEndpointsIncludingExactZeroRefluxAndPressureDrop() {
        for (int stages : List.of(2, 64)) {
            for (double reflux : List.of(0.0, 10.0)) {
                List<String> fields = new ArrayList<>(VALID);
                fields.set(2, Integer.toString(stages));
                fields.set(3, Integer.toString(stages));
                fields.set(6, Double.toString(reflux));
                fields.set(7, stages == 2 ? "1" : "3");
                fields.set(8, "0");
                fields.set(9, "0");
                V3ColumnScalarDraft.Values values = V3ColumnScalarDraft.parse(fields);
                assertEquals(stages, values.stageCount());
                assertEquals(reflux, values.refluxRatio());
                assertEquals(stages == 2 ? 100_000.0 : 300_000.0, values.topPressurePascal());
                assertEquals(0.0, values.pressureDropPascal());
                assertEquals(0.0, values.columnDiameterMetres());
            }
        }
    }

    private static void assertInvalid(int index, String value, String expectedField) {
        List<String> fields = new ArrayList<>(VALID);
        fields.set(index, value);
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> V3ColumnScalarDraft.parse(fields));
        assertTrue(failure.getMessage().contains(expectedField), failure::getMessage);
        assertFalse(failure.getMessage().contains("draw"), failure::getMessage);
    }
}
