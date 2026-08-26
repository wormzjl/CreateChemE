package com.wormzjl.createcheme.science.column.nextgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ColumnNextInputTest {
    @Test
    void defaultsUseTheOneDerivedPressureProfile() {
        ColumnNextInput input = ColumnNextInput.defaults();

        assertEquals(30, input.stageCount());
        assertEquals(250_000.0, input.pressureAtStageNumber(1));
        assertEquals(271_750.0, input.pressureAtStageNumber(30));
        assertEquals(21_750.0, input.totalPressureDropPascal());
        assertTrue(ColumnNextValidation.validate(input).isValid());
    }

    @Test
    void sideDrawsCanonicalizeButDuplicateStagesFailBeforeAdmission() {
        ColumnNextInput defaults = ColumnNextInput.defaults();
        ColumnNextInput input = new ColumnNextInput(
                defaults.schemaVersion(), defaults.packageId(), defaults.assayId(), defaults.crudeFeed(),
                defaults.stageCount(), defaults.crudeFeedStageNumber(), defaults.topPressurePascal(),
                defaults.stagePressureDropPascal(), defaults.condenserOutletTemperatureKelvin(),
                defaults.reboilerDutyWatts(), defaults.organicRefluxRatio(),
                List.of(ColumnNextInput.SideDrawInput.molar(22, 1.0),
                        ColumnNextInput.SideDrawInput.molar(8, 1.0),
                        ColumnNextInput.SideDrawInput.molar(8, 1.0)),
                List.of());

        assertEquals(8, input.sideDraws().getFirst().stageNumber());
        assertFalse(ColumnNextValidation.validate(input).isValid());
        assertTrue(ColumnNextValidation.validate(input).diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.code().equals("DUPLICATE_SIDE_DRAW_STAGE")));
    }

    @Test
    void allPressureInputsAreScientificAndValidatedBeforeAllocation() {
        ColumnNextInput defaults = ColumnNextInput.defaults();
        ColumnNextInput invalid = new ColumnNextInput(
                defaults.schemaVersion(), defaults.packageId(), defaults.assayId(), defaults.crudeFeed(),
                defaults.stageCount(), defaults.crudeFeedStageNumber(), 20_000.0, 3_000.0,
                defaults.condenserOutletTemperatureKelvin(), defaults.reboilerDutyWatts(),
                defaults.organicRefluxRatio(), List.of(), List.of());

        ColumnNextValidation.Result validation = ColumnNextValidation.validate(invalid);
        assertFalse(validation.isValid());
        assertTrue(validation.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.code().equals("INVALID_TOP_PRESSURE")));
        assertTrue(validation.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.code().equals("INVALID_STAGE_PRESSURE_DROP")));
    }

    @Test
    void derivedBottomAndUtilityPressureMustRemainPhysicallyAdmissible() {
        ColumnNextInput defaults = ColumnNextInput.defaults();
        ColumnNextInput invalid = new ColumnNextInput(
                defaults.schemaVersion(), defaults.packageId(), defaults.assayId(), defaults.crudeFeed(),
                64, defaults.crudeFeedStageNumber(), 2_000_000.0, 750.0,
                defaults.condenserOutletTemperatureKelvin(), defaults.reboilerDutyWatts(),
                defaults.organicRefluxRatio(), List.of(),
                List.of(new ColumnNextInput.WaterSteamFeedInput(
                        ColumnNextInput.UtilityFeedMode.STEAM, 24, 1.0, 500.0, 100_000.0)));

        ColumnNextValidation.Result validation = ColumnNextValidation.validate(invalid);
        assertFalse(validation.isValid());
        assertTrue(validation.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.code().equals("INVALID_DERIVED_PRESSURE_PROFILE")));
        assertTrue(validation.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.code().equals("UTILITY_PRESSURE_BELOW_CONNECTED_STAGE")));
    }

    @Test
    void rZeroTopologyRemovesOnlyMatchingCondensateEquationAndUnknownPairs() {
        ColumnNextInput defaults = ColumnNextInput.defaults();
        ColumnNextInput vaporOnly = new ColumnNextInput(
                defaults.schemaVersion(), defaults.packageId(), defaults.assayId(), defaults.crudeFeed(),
                defaults.stageCount(), defaults.crudeFeedStageNumber(), defaults.topPressurePascal(),
                defaults.stagePressureDropPascal(), defaults.condenserOutletTemperatureKelvin(),
                defaults.reboilerDutyWatts(), 0.0, List.of(), List.of());
        ColumnTopology topology = ColumnProblem.resolve(vaporOnly).topology();

        assertTrue(topology.vaporOnlyOverhead());
        assertEquals(35 * topology.nodeCount() - 16, topology.equationCount());
        assertEquals(topology.equationCount(), topology.unknownCount());
        assertTrue(topology.hasSquareDegreesOfFreedom());
    }
}
