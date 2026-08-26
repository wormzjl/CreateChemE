package com.wormzjl.createcheme.science.column.nextgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class If97WaterTest {
    private static final double WATER_MOLAR_MASS_KILOGRAMS_PER_MOL = 0.01801528;

    @Test
    void regionFourMatchesPublishedIapwsVerificationPointsAndItsDirectInverse() {
        // IAPWS R7-97(2012), Table 35, with pressure converted from MPa to Pa.
        assertEquals(3_536.58941, If97Water.saturationPressurePascal(300.0), 0.002);
        assertEquals(2_638_897.76, If97Water.saturationPressurePascal(500.0), 2.0);
        assertEquals(12_344_314.6, If97Water.saturationPressurePascal(600.0), 10.0);

        // IAPWS R7-97(2012), Table 36.
        assertEquals(372.755919, If97Water.saturationTemperatureKelvin(100_000.0), 0.00001);
        assertEquals(453.035632, If97Water.saturationTemperatureKelvin(1_000_000.0), 0.00001);
        assertEquals(584.149488, If97Water.saturationTemperatureKelvin(10_000_000.0), 0.00001);
    }

    @Test
    void regionOneEnthalpyMatchesPublishedIapwsVerificationPointsInMolarSiUnits() {
        // IAPWS R7-97(2012), Table 5, h values in kJ/kg converted to J/mol.
        assertEquals(115_331.273 * WATER_MOLAR_MASS_KILOGRAMS_PER_MOL,
                If97Water.liquidEnthalpyJoulesPerMol(300.0, 3_000_000.0), 0.00003);
        assertEquals(975_542.239 * WATER_MOLAR_MASS_KILOGRAMS_PER_MOL,
                If97Water.liquidEnthalpyJoulesPerMol(500.0, 3_000_000.0), 0.00003);
    }

    @Test
    void saturatedLiquidAndVaporRetainTheExpectedLatentEnthalpyGap() {
        double saturationPressure = If97Water.saturationPressurePascal(373.15);
        double liquid = If97Water.liquidEnthalpyJoulesPerMol(373.15, saturationPressure);
        double vapor = If97Water.vaporEnthalpyJoulesPerMol(373.15, saturationPressure);

        assertTrue(vapor > liquid);
        assertEquals(40_650.0, vapor - liquid, 250.0);
    }

    @Test
    void regionTwoEnthalpyMatchesThePublishedEquationCheckpointInMolarSiUnits() {
        // IAPWS R7-97(2012), Eqs. (15)-(17), evaluated at 700 K and 3 MPa:
        // h = 3_292_462.66058759 J/kg, converted to the package's molar SI basis.
        assertEquals(3_292_462.66058759 * WATER_MOLAR_MASS_KILOGRAMS_PER_MOL,
                If97Water.vaporEnthalpyJoulesPerMol(700.0, 3_000_000.0), 0.00003);
    }

    @Test
    void diluteWaterVaporUsesItsPartialPressureRatherThanTheColumnTotalPressureFloor() {
        double dilute = If97Water.vaporEnthalpyJoulesPerMol(450.0, 1_000.0);
        double denser = If97Water.vaporEnthalpyJoulesPerMol(450.0, 100_000.0);

        assertTrue(Double.isFinite(dilute));
        assertTrue(dilute > denser);
    }

    @Test
    void invalidOrUnsupportedPhaseStatesFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> If97Water.saturationPressurePascal(647.096));
        assertThrows(IllegalArgumentException.class, () -> If97Water.saturationTemperatureKelvin(611.0));
        assertThrows(IllegalArgumentException.class, () -> If97Water.liquidEnthalpyJoulesPerMol(700.0, 1_000_000.0));
        assertThrows(IllegalArgumentException.class, () -> If97Water.liquidEnthalpyJoulesPerMol(500.0, 50_000.0));
        assertThrows(IllegalArgumentException.class, () -> If97Water.vaporEnthalpyJoulesPerMol(500.0, 3_000_000.0));
        assertThrows(IllegalArgumentException.class, () -> If97Water.vaporEnthalpyJoulesPerMol(700.0, 10_000_001.0));
    }
}
