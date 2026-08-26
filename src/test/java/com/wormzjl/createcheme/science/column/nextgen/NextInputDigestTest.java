package com.wormzjl.createcheme.science.column.nextgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class NextInputDigestTest {
    @Test
    void canonicalizedDrawOrderDoesNotChangeDigestButPressureDoes() {
        ColumnNextInput defaults = ColumnNextInput.defaults();
        ColumnNextInput shuffled = new ColumnNextInput(
                defaults.schemaVersion(), defaults.packageId(), defaults.assayId(), defaults.crudeFeed(),
                defaults.stageCount(), defaults.crudeFeedStageNumber(), defaults.topPressurePascal(),
                defaults.stagePressureDropPascal(), defaults.condenserOutletTemperatureKelvin(),
                defaults.reboilerDutyWatts(), defaults.organicRefluxRatio(),
                List.of(ColumnNextInput.SideDrawInput.molar(22, 8.0),
                        ColumnNextInput.SideDrawInput.molar(8, 10.0),
                        ColumnNextInput.SideDrawInput.molar(15, 12.0)), List.of());
        String first = NextInputDigest.of(ColumnProblem.resolve(defaults), "solver-v1", "assumptions-v1");
        String same = NextInputDigest.of(ColumnProblem.resolve(shuffled), "solver-v1", "assumptions-v1");
        ColumnNextInput pressureChanged = new ColumnNextInput(
                defaults.schemaVersion(), defaults.packageId(), defaults.assayId(), defaults.crudeFeed(),
                defaults.stageCount(), defaults.crudeFeedStageNumber(), defaults.topPressurePascal() + 1.0,
                defaults.stagePressureDropPascal(), defaults.condenserOutletTemperatureKelvin(),
                defaults.reboilerDutyWatts(), defaults.organicRefluxRatio(), defaults.sideDraws(), defaults.utilityFeeds());
        assertEquals(first, same);
        assertNotEquals(first, NextInputDigest.of(ColumnProblem.resolve(pressureChanged), "solver-v1", "assumptions-v1"));
    }
}
