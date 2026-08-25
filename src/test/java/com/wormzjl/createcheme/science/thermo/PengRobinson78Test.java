package com.wormzjl.createcheme.science.thermo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PengRobinson78Test {
    private static final ThermoComponent METHANE = new ThermoComponent(
            "methane", 190.564, 4_599_200.0, 0.01142, 0.016043);
    private static final ThermoComponent PROPANE = new ThermoComponent(
            "propane", 369.83, 4_248_000.0, 0.1523, 0.044097);
    private static final ThermoComponent N_BUTANE = new ThermoComponent(
            "n_butane", 425.12, 3_796_000.0, 0.2002, 0.058124);

    @Test
    void methaneReferencePointMatchesPengRobinsonCalculation() {
        PengRobinson78 model = PengRobinson78.withoutBinaryInteractions(List.of(METHANE));

        PhaseProperties properties = model.evaluate(
                300.0, 5_000_000.0, new double[] {1.0}, PhaseRoot.VAPOR);

        assertEquals(0.9018307109528809, properties.compressibilityFactor(), 1.0e-12);
        assertEquals(-0.10383585031450415, properties.logFugacityCoefficient(0), 1.0e-12);
    }

    @Test
    void liquidAndVaporRootsAreSelectedAtAThreeRootState() {
        PengRobinson78 model = PengRobinson78.withoutBinaryInteractions(List.of(PROPANE));

        PhaseProperties liquid = model.evaluate(
                300.0, 1_000_000.0, new double[] {1.0}, PhaseRoot.LIQUID);
        PhaseProperties vapor = model.evaluate(
                300.0, 1_000_000.0, new double[] {1.0}, PhaseRoot.VAPOR);

        assertEquals(0.03478320039102689, liquid.compressibilityFactor(), 1.0e-12);
        assertEquals(0.8146036935018395, vapor.compressibilityFactor(), 1.0e-12);
        assertTrue(liquid.compressibilityFactor() < vapor.compressibilityFactor());
    }

    @Test
    void diluteGasApproachesTheIdealGasLimit() {
        PengRobinson78 model = PengRobinson78.withoutBinaryInteractions(List.of(METHANE, N_BUTANE));

        PhaseProperties properties = model.evaluate(
                500.0, 1.0, new double[] {0.7, 0.3}, PhaseRoot.VAPOR);

        assertEquals(1.0, properties.compressibilityFactor(), 1.0e-7);
        assertEquals(0.0, properties.logFugacityCoefficient(0), 1.0e-7);
        assertEquals(0.0, properties.logFugacityCoefficient(1), 1.0e-7);
    }

    @Test
    void returnedFugacityVectorCannotMutateTheResult() {
        PengRobinson78 model = PengRobinson78.withoutBinaryInteractions(List.of(METHANE));
        PhaseProperties properties = model.evaluate(
                300.0, 5_000_000.0, new double[] {1.0}, PhaseRoot.VAPOR);

        double[] copy = properties.logFugacityCoefficients();
        copy[0] = 123.0;

        assertArrayEquals(
                new double[] {-0.10383585031450415},
                properties.logFugacityCoefficients(),
                1.0e-12);
    }
}
