package com.wormzjl.createcheme.science.column;

import com.wormzjl.createcheme.science.equipment.EquipmentType;

import static com.wormzjl.createcheme.science.column.ColumnSimulation.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

class ColumnSimulationTest {
    private static final double TOLERANCE = 1.0e-12;

    @Test
    void validInputIsSquareAndAccepted() {
        ColumnValidationResult result = validate(validInput());

        assertTrue(result.isValid());
        assertTrue(result.degreesOfFreedom().isSquare());
        assertEquals(5, result.degreesOfFreedom().requiredSpecifications());
        assertEquals(5, result.degreesOfFreedom().suppliedSpecifications());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void stableScientificIdentifiersRoundTripWithoutEnumOrdinals() {
        assertEquals(
                RefluxMode.SATURATED_LIQUID,
                RefluxMode.fromSerializedName("saturated_liquid").orElseThrow());
        assertTrue(RefluxMode.fromSerializedName("future_unknown_mode").isEmpty());

        assertEquals(13, EquipmentType.values().length);
        for (EquipmentType type : EquipmentType.values()) {
            assertEquals(type, EquipmentType.fromSerializedName(type.serializedName()).orElseThrow());
        }
        assertEquals(
                EquipmentType.Family.ADSORPTION,
                EquipmentType.PRESSURE_SWING_ADSORBER.family());
        assertTrue(EquipmentType.fromSerializedName("future_unknown_equipment").isEmpty());
    }

    @Test
    void inputDefensivelyCopiesSideDraws() {
        List<SideDrawSpec> mutable = new ArrayList<>();
        mutable.add(new SideDrawSpec(8, 5.0));
        ColumnInput input = new ColumnInput(
                INPUT_SCHEMA_VERSION,
                "createcheme:tia_juana_light_12",
                100.0,
                620.0,
                30,
                24,
                8.0e6,
                3.0,
                RefluxCondition.saturatedLiquid(),
                mutable);

        mutable.add(new SideDrawSpec(16, 5.0));

        assertEquals(1, input.sideDraws().size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> input.sideDraws().add(new SideDrawSpec(20, 5.0)));
    }

    @Test
    void rejectsNonFiniteAndOutOfRangeValuesWithoutMisreportingDof() {
        ColumnInput input = new ColumnInput(
                INPUT_SCHEMA_VERSION,
                "createcheme:tia_juana_light_12",
                Double.NaN,
                Double.POSITIVE_INFINITY,
                65,
                0,
                -1.0,
                101.0,
                RefluxCondition.saturatedLiquid(),
                List.of());

        ColumnValidationResult result = validate(input);

        assertFalse(result.isValid());
        assertTrue(result.degreesOfFreedom().isSquare());
        assertTrue(hasCode(result, ColumnFaultCode.NON_FINITE_VALUE));
        assertTrue(hasCode(result, ColumnFaultCode.VALUE_OUT_OF_RANGE));
        assertTrue(hasCode(result, ColumnFaultCode.INVALID_STAGE_COUNT));
        assertTrue(hasCode(result, ColumnFaultCode.INVALID_FEED_STAGE));
        assertEquals("column.input.non_finite", ColumnFaultCode.NON_FINITE_VALUE.wireCode());
    }

    @Test
    void detectsOverSpecifiedSaturatedReflux() {
        ColumnInput valid = validInput();
        ColumnInput input = new ColumnInput(
                valid.schemaVersion(),
                valid.assayId(),
                valid.feedMolarFlowMolPerSecond(),
                valid.feedTemperatureKelvin(),
                valid.stageCount(),
                valid.feedStage(),
                valid.reboilerDutyWatts(),
                valid.refluxRatio(),
                new RefluxCondition(
                        RefluxMode.SATURATED_LIQUID, OptionalDouble.of(320.0)),
                valid.sideDraws());

        ColumnValidationResult result = validate(input);

        assertFalse(result.isValid());
        assertEquals(-1, result.degreesOfFreedom().remainingDegreesOfFreedom());
        assertTrue(hasCode(result, ColumnFaultCode.REFLUX_TEMPERATURE_NOT_ALLOWED));
        assertTrue(hasCode(result, ColumnFaultCode.OVER_SPECIFIED));
    }

    @Test
    void detectsDuplicateAndExcessiveSideDraws() {
        ColumnInput input = new ColumnInput(
                INPUT_SCHEMA_VERSION,
                "createcheme:tia_juana_light_12",
                100.0,
                620.0,
                30,
                24,
                8.0e6,
                3.0,
                RefluxCondition.saturatedLiquid(),
                List.of(new SideDrawSpec(8, 60.0), new SideDrawSpec(8, 40.0)));

        ColumnValidationResult result = validate(input);

        assertFalse(result.isValid());
        assertTrue(hasCode(result, ColumnFaultCode.DUPLICATE_SIDE_DRAW_STAGE));
        assertTrue(hasCode(result, ColumnFaultCode.TOTAL_SIDE_DRAW_RATE_EXCEEDS_FEED));
    }

    @Test
    void subcooledRefluxTemperatureClosesBoundarySpecification() {
        ColumnInput valid = validInput();
        ColumnInput input = new ColumnInput(
                valid.schemaVersion(),
                valid.assayId(),
                valid.feedMolarFlowMolPerSecond(),
                valid.feedTemperatureKelvin(),
                valid.stageCount(),
                valid.feedStage(),
                valid.reboilerDutyWatts(),
                valid.refluxRatio(),
                RefluxCondition.subcooledLiquid(315.0),
                valid.sideDraws());

        ColumnValidationResult result = validate(input);

        assertTrue(result.isValid());
        assertEquals(6, result.degreesOfFreedom().requiredSpecifications());
        assertEquals(6, result.degreesOfFreedom().suppliedSpecifications());
    }

    @Test
    void identicalInputProducesIdenticalDummyResultAndDigest() {
        ColumnSolveOutcome first = calculate(validInput());
        ColumnSolveOutcome second = calculate(validInput());

        assertEquals(ColumnSolveStatus.DUMMY_RESULT, first.status());
        assertEquals(first, second);
        assertEquals(
                first.result().orElseThrow().resultDigest(),
                second.result().orElseThrow().resultDigest());
        assertEquals(64, first.result().orElseThrow().resultDigest().length());
        assertTrue(first.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.code() == ColumnFaultCode.DUMMY_SOLVER_ACTIVE));
    }

    @Test
    void dummyResultIsBoundedConservativeAndGuiReady() {
        ColumnInput input = validInput();
        ColumnResult result = calculate(input).result().orElseThrow();

        assertEquals(input.sideDraws().size() + 2, result.products().size());
        assertEquals(input.stageCount(), result.stages().size());
        assertEquals(ProductKind.TOP, result.products().get(0).kind());
        assertEquals(ProductKind.BOTTOM, result.products().get(result.products().size() - 1).kind());
        assertEquals(RateSpecification.CALCULATED, result.products().get(0).rateSpecification());
        assertEquals(RateSpecification.SPECIFIED, result.products().get(1).rateSpecification());

        double totalFlow = result.products().stream()
                .mapToDouble(ProductStream::molarFlowMolPerSecond)
                .sum();
        assertEquals(input.feedMolarFlowMolPerSecond(), totalFlow, TOLERANCE);
        for (int side = 0; side < input.sideDraws().size(); side++) {
            assertEquals(
                    input.sideDraws().get(side).molarFlowMolPerSecond(),
                    result.products().get(side + 1).molarFlowMolPerSecond(),
                    TOLERANCE);
        }
        for (ProductStream product : result.products()) {
            assertTrue(Double.isFinite(product.molarFlowMolPerSecond()));
            assertTrue(product.molarFlowMolPerSecond() > 0.0);
            assertEquals(12, product.composition().size());
            assertEquals(
                    1.0,
                    product.composition().stream()
                            .mapToDouble(ComponentFraction::moleFraction)
                            .sum(),
                    TOLERANCE);
            assertEquals(
                    1.0,
                    product.composition().stream()
                            .mapToDouble(ComponentFraction::massFraction)
                            .sum(),
                    TOLERANCE);
            assertTrue(product.composition().stream()
                    .allMatch(fraction -> fraction.moleFraction() >= 0.0
                            && fraction.massFraction() >= 0.0));
        }

        ColumnResiduals residuals = result.diagnostics().residuals();
        assertTrue(residuals.maximumComponentMaterialResidual().orElseThrow() < 1.0e-10);
        assertTrue(residuals.overallMaterialResidual().orElseThrow() < 1.0e-12);
        assertTrue(residuals.relativeEnergyResidual().isEmpty());
        assertTrue(residuals.maximumEquilibriumResidual().isEmpty());
    }

    @Test
    void invalidInputProducesStableRejectionWithoutResult() {
        ColumnInput valid = validInput();
        ColumnInput invalid = new ColumnInput(
                valid.schemaVersion(),
                valid.assayId(),
                valid.feedMolarFlowMolPerSecond(),
                valid.feedTemperatureKelvin(),
                valid.stageCount(),
                0,
                valid.reboilerDutyWatts(),
                valid.refluxRatio(),
                valid.refluxCondition(),
                List.of());

        ColumnSolveOutcome outcome = calculate(invalid);

        assertEquals(ColumnSolveStatus.REJECTED_INPUT, outcome.status());
        assertFalse(outcome.hasResult());
        assertTrue(outcome.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.code() == ColumnFaultCode.INVALID_FEED_STAGE));
    }

    @Test
    void resultCollectionsAreImmutable() {
        ColumnResult result = calculate(validInput()).result().orElseThrow();

        assertThrows(UnsupportedOperationException.class, () -> result.products().clear());
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.products().get(0).composition().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.stages().clear());
    }

    @Test
    void boundaryCasesRemainFiniteAndConservative() {
        List<ColumnInput> cases = List.of(
                new ColumnInput(
                        INPUT_SCHEMA_VERSION,
                        "createcheme:tia_juana_light_12",
                        1.0e-6,
                        200.0,
                        2,
                        1,
                        1.0,
                        0.0,
                        RefluxCondition.saturatedLiquid(),
                        List.of()),
                new ColumnInput(
                        INPUT_SCHEMA_VERSION,
                        "createcheme:tia_juana_light_12",
                        1.0e7,
                        1_200.0,
                        64,
                        64,
                        1.0e12,
                        100.0,
                        RefluxCondition.saturatedLiquid(),
                        List.of(
                                new SideDrawSpec(5, 1.0e6),
                                new SideDrawSpec(15, 1.0e6),
                                new SideDrawSpec(25, 1.0e6),
                                new SideDrawSpec(35, 1.0e6),
                                new SideDrawSpec(45, 1.0e6),
                                new SideDrawSpec(55, 1.0e6))));

        for (ColumnInput input : cases) {
            ColumnSolveOutcome outcome = calculate(input);
            assertEquals(ColumnSolveStatus.DUMMY_RESULT, outcome.status());
            ColumnResult result = outcome.result().orElseThrow();
            double output = result.products().stream()
                    .mapToDouble(ProductStream::molarFlowMolPerSecond)
                    .sum();
            assertEquals(input.feedMolarFlowMolPerSecond(), output, input.feedMolarFlowMolPerSecond() * 1.0e-12);
            assertTrue(result.products().stream().allMatch(product ->
                    Double.isFinite(product.molarFlowMolPerSecond())
                            && product.molarFlowMolPerSecond() > 0.0));
            assertTrue(result.diagnostics().residuals()
                    .maximumComponentMaterialResidual()
                    .orElseThrow() < 1.0e-9);
        }
    }

    private static boolean hasCode(ColumnValidationResult result, ColumnFaultCode code) {
        return result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code() == code);
    }

    private static ColumnInput validInput() {
        return new ColumnInput(
                INPUT_SCHEMA_VERSION,
                "createcheme:tia_juana_light_12",
                100.0,
                620.0,
                30,
                24,
                8.0e6,
                3.0,
                RefluxCondition.saturatedLiquid(),
                List.of(
                        new SideDrawSpec(8, 10.0),
                        new SideDrawSpec(15, 12.0),
                        new SideDrawSpec(22, 8.0)));
    }
}
