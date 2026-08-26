package com.wormzjl.createcheme.science.column.nextgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class WaterFeedProfileTest {
    @Test
    void utilityFeedsAreAddedAtTheirTrayWithUpstreamEnthalpyPreservedThroughThrottle() {
        ColumnNextInput input = withUtility(new ColumnNextInput.WaterSteamFeedInput(
                ColumnNextInput.UtilityFeedMode.STEAM, 10, 2.5, 500.0, 500_000.0));
        ColumnProblem problem = ColumnProblem.resolve(input);
        WaterFeedProfile profile = WaterFeedProfile.resolve(input, problem.propertyPackage().basis(), problem.feed(),
                problem.nodePressuresPascal());

        assertEquals(2.5, profile.molarFeedByNode()[10], 0.0);
        assertEquals(2.5 * WaterFeedProfile.alignedVaporEnthalpy(500.0, 500_000.0),
                profile.enthalpyFlowWattsByNode()[10], 1.0e-8);
    }

    @Test
    void explicitlyAuthoredSteamMustActuallyBeStableAtItsUpstreamState() {
        ColumnNextInput input = withUtility(new ColumnNextInput.WaterSteamFeedInput(
                ColumnNextInput.UtilityFeedMode.STEAM, 10, 1.0, 350.0, 500_000.0));

        assertThrows(IllegalArgumentException.class, () -> ColumnProblem.resolve(input));
    }

    private static ColumnNextInput withUtility(ColumnNextInput.WaterSteamFeedInput utility) {
        ColumnNextInput defaults = ColumnNextInput.defaults();
        return new ColumnNextInput(
                defaults.schemaVersion(), defaults.packageId(), defaults.assayId(), defaults.crudeFeed(),
                defaults.stageCount(), defaults.crudeFeedStageNumber(), defaults.topPressurePascal(),
                defaults.stagePressureDropPascal(), defaults.condenserOutletTemperatureKelvin(),
                defaults.reboilerDutyWatts(), defaults.organicRefluxRatio(), defaults.sideDraws(), List.of(utility));
    }
}
