package com.wormzjl.createcheme.science.thermo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class PengRobinsonCaloricModelTest {
    private static final ThermoComponent METHANE = new ThermoComponent(
            "methane", 190.564, 4_599_200.0, 0.01142, 0.016043);

    @Test
    void heatCapacityPolynomialIntegratesFromItsReferenceState() {
        IdealGasHeatCapacity heatCapacity =
                new IdealGasHeatCapacity(300.0, 1_000.0, 40.0, 0.2, 0.01, 0.001);

        assertEquals(44.0, heatCapacity.heatCapacityJoulesPerMolKelvin(310.0), 1.0e-12);
        assertEquals(1_415.8333333333333, heatCapacity.enthalpyJoulesPerMol(310.0), 1.0e-12);
    }

    @Test
    void totalEnthalpyCombinesIdealAndResidualContributions() {
        PengRobinson78 equationOfState =
                PengRobinson78.withoutBinaryInteractions(List.of(METHANE));
        PengRobinsonCaloricModel model = new PengRobinsonCaloricModel(
                equationOfState,
                List.of(IdealGasHeatCapacity.constant(300.0, 12_500.0, 35.0)));

        CaloricPhaseProperties properties =
                model.evaluate(320.0, 5_000_000.0, new double[] {1.0}, PhaseRoot.VAPOR);

        assertEquals(13_200.0, properties.idealGasEnthalpyJoulesPerMol(), 1.0e-12);
        assertEquals(
                properties.idealGasEnthalpyJoulesPerMol()
                        + properties.residualEnthalpyJoulesPerMol(),
                properties.enthalpyJoulesPerMol(),
                1.0e-12);
    }

    @Test
    void caloricComponentCountMustMatchEquationOfState() {
        PengRobinson78 equationOfState =
                PengRobinson78.withoutBinaryInteractions(List.of(METHANE));

        assertThrows(
                IllegalArgumentException.class,
                () -> new PengRobinsonCaloricModel(equationOfState, List.of()));
    }
}
