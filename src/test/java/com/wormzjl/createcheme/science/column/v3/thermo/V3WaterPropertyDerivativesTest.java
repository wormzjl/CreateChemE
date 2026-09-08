package com.wormzjl.createcheme.science.column.v3.thermo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Holds each pinned water correlation's temperature derivative against a difference of the value it belongs to.
 *
 * <p>A step of a milli-kelvin is small enough that the truncation of a central difference — which grows as the
 * square of the step — stays far below the tolerance, and large enough that the cancellation between two
 * nearly equal values stays near the {@code 1e-13} level of relative rounding. The Watson vaporisation
 * enthalpy is the one correlation whose slope diverges, at its own critical point, so the sweep below stops
 * short of it and the behaviour at and beyond it is asserted separately.</p>
 *
 * <p>Observed maxima at the time of writing, relative to the value of the derivative itself:
 * {@code d ln(P_sat)/dT} 1.2e-10, vapour heat capacity 3.8e-10, vaporisation enthalpy slope 5.6e-10, and
 * liquid heat capacity 4.7e-10.</p>
 */
class V3WaterPropertyDerivativesTest {
    private static final double STEP_KELVIN = 1.0e-3;
    private static final double TOLERANCE = 1.0e-7;

    @Test
    void saturationPressureLogarithmicSlopeMatchesACentralDifference() {
        for (double temperature = 280.0; temperature <= 640.0; temperature += 5.0) {
            double difference = (Math.log(V3WaterProperties.saturationPressurePascal(temperature + STEP_KELVIN))
                    - Math.log(V3WaterProperties.saturationPressurePascal(temperature - STEP_KELVIN)))
                    / (2.0 * STEP_KELVIN);
            assertEquals(difference, V3WaterProperties.dLogSaturationPressureDT(temperature),
                    TOLERANCE * Math.abs(difference), "T=" + temperature);
        }
    }

    @Test
    void waterVapourHeatCapacityMatchesACentralDifferenceOfItsEnthalpy() {
        for (double temperature = 280.0; temperature <= 890.0; temperature += 5.0) {
            double difference = (V3WaterProperties.vaporMolarEnthalpy(temperature + STEP_KELVIN)
                    - V3WaterProperties.vaporMolarEnthalpy(temperature - STEP_KELVIN)) / (2.0 * STEP_KELVIN);
            assertEquals(difference, V3WaterProperties.dVaporMolarEnthalpyDT(temperature),
                    TOLERANCE * Math.abs(difference), "T=" + temperature);
        }
    }

    @Test
    void vaporisationEnthalpySlopeMatchesACentralDifferenceBelowTheCriticalPoint() {
        for (double temperature = 280.0; temperature <= 630.0; temperature += 5.0) {
            double difference = (V3WaterProperties.vaporizationEnthalpy(temperature + STEP_KELVIN)
                    - V3WaterProperties.vaporizationEnthalpy(temperature - STEP_KELVIN)) / (2.0 * STEP_KELVIN);
            assertEquals(difference, V3WaterProperties.dVaporizationEnthalpyDT(temperature),
                    TOLERANCE * Math.abs(difference), "T=" + temperature);
            assertTrue(V3WaterProperties.dVaporizationEnthalpyDT(temperature) < 0.0, "T=" + temperature);
        }
    }

    @Test
    void liquidWaterHeatCapacityMatchesACentralDifferenceOfItsEnthalpy() {
        for (double temperature = 280.0; temperature <= 630.0; temperature += 5.0) {
            double difference = (V3WaterProperties.liquidMolarEnthalpy(temperature + STEP_KELVIN)
                    - V3WaterProperties.liquidMolarEnthalpy(temperature - STEP_KELVIN)) / (2.0 * STEP_KELVIN);
            assertEquals(difference, V3WaterProperties.dLiquidMolarEnthalpyDT(temperature),
                    TOLERANCE * Math.abs(difference), "T=" + temperature);
        }
    }

    /** Above the critical point the vaporisation enthalpy is the constant zero, and so is its slope. */
    @Test
    void vaporisationEnthalpySlopeIsZeroFromTheCriticalPointUpward() {
        for (double temperature : new double[] {
                V3WaterProperties.CRITICAL_TEMPERATURE_KELVIN, 700.0, V3WaterProperties.MAX_ENTHALPY_TEMPERATURE_KELVIN}) {
            assertEquals(0.0, V3WaterProperties.vaporizationEnthalpy(temperature), 0.0);
            assertEquals(0.0, V3WaterProperties.dVaporizationEnthalpyDT(temperature), 0.0);
        }
    }

    /** Every derivative refuses exactly the envelope its value refuses. */
    @Test
    void derivativesShareTheDomainChecksOfTheirValues() {
        for (double outside : new double[] {273.0, 901.0, Double.NaN}) {
            assertThrows(IllegalArgumentException.class, () -> V3WaterProperties.vaporMolarEnthalpy(outside));
            assertThrows(IllegalArgumentException.class, () -> V3WaterProperties.dVaporMolarEnthalpyDT(outside));
            assertThrows(IllegalArgumentException.class, () -> V3WaterProperties.vaporizationEnthalpy(outside));
            assertThrows(IllegalArgumentException.class, () -> V3WaterProperties.dVaporizationEnthalpyDT(outside));
            assertThrows(IllegalArgumentException.class, () -> V3WaterProperties.dLiquidMolarEnthalpyDT(outside));
        }
        for (double outside : new double[] {273.0, 648.0, Double.NaN}) {
            assertThrows(IllegalArgumentException.class, () -> V3WaterProperties.saturationPressurePascal(outside));
            assertThrows(IllegalArgumentException.class, () -> V3WaterProperties.dLogSaturationPressureDT(outside));
        }
    }
}
