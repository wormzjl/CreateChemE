package com.wormzjl.createcheme.science.column.nextgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WaterActiveSetTest {
    private static final int NODES = 5; // condenser, three trays, reboiler
    private static final double PRESSURE = 1_000_000.0;
    private static final double TEMPERATURE = 400.0;

    @Test
    void undersaturatedWaterStaysVaporAndClosesEveryScalarBalance() {
        WaterActiveSet.Result result = successful(10.0, 1.0, TEMPERATURE);

        for (int node = 0; node < NODES; node++) {
            assertFalse(result.wetMask()[node]);
            assertEquals(0.0, result.aqueousLiquidMolPerSecond()[node], 1.0e-12);
        }
        assertEquals(1.0, result.waterVaporMolPerSecond()[0], 1.0e-12);
        assertEquals(0.0, result.waterVaporMolPerSecond()[3], 1.0e-12);
        assertEquals(0.0, result.waterVaporMolPerSecond()[4], 1.0e-12);
        assertTrue(result.diagnostics().maximumThomasBackwardError() <= 1.0e-12);
        assertTrue(result.diagnostics().maximumWaterBalanceResidual() <= 1.0e-12);
    }

    @Test
    void excessWaterCreatesPureAqueousLiquidWithoutEnteringHydrocarbonVapor() {
        WaterActiveSet.Result result = successful(10.0, 10.0, TEMPERATURE);

        assertTrue(result.wetMask()[2]);
        assertTrue(result.wetMask()[3]);
        assertTrue(result.aqueousLiquidMolPerSecond()[2] > 0.0);
        assertTrue(result.aqueousLiquidMolPerSecond()[3] > 0.0);
        for (int node = 0; node < NODES; node++) {
            if (result.wetMask()[node]) {
                assertEquals(result.saturationPressurePascal()[node], result.waterPartialPressurePascal()[node],
                        1.0e-4);
            }
            assertTrue(result.hydrocarbonPartialPressurePascal()[node] >= 0.0);
        }
        assertTrue(result.diagnostics().maskPasses() <= WaterActiveSet.MAX_WATER_MASK_PASSES);
        assertFalse(result.diagnostics().maskTransitions().isEmpty());
    }

    @Test
    void saturationPressureAtOrAboveTotalPressureRemainsDryAndFiniteWithHydrocarbonVapor() {
        WaterActiveSet.Result result = successful(10.0, 5.0, 500.0);

        for (int node = 0; node < NODES; node++) {
            assertFalse(result.wetMask()[node]);
            assertEquals(0.0, result.aqueousLiquidMolPerSecond()[node], 1.0e-12);
            assertTrue(Double.isFinite(result.waterPartialPressurePascal()[node]));
            assertTrue(result.hydrocarbonPartialPressurePascal()[node] > 0.0);
        }
    }

    @Test
    void drySupercriticalSteamNodeDoesNotAttemptAnUndefinedSaturationBranch() {
        WaterActiveSet.Result result = successful(10.0, 5.0, 700.0);

        for (int node = 0; node < NODES; node++) {
            assertFalse(result.wetMask()[node]);
            assertTrue(Double.isNaN(result.saturationPressurePascal()[node]));
            assertEquals(0.0, result.aqueousLiquidMolPerSecond()[node], 1.0e-12);
        }
    }

    @Test
    void coldPureSteamBranchFailsExplicitlyUntilCoupledEnergyCanResolveIt() {
        double[] temperature = filled(TEMPERATURE);
        double[] pressure = filled(PRESSURE);
        double[] hydrocarbon = new double[NODES];
        double[] feed = new double[NODES];
        feed[2] = 1.0;

        WaterActiveSet.Outcome outcome = WaterActiveSet.solve(
                new WaterActiveSet.Input(temperature, pressure, hydrocarbon, feed, new boolean[NODES]),
                new WaterActiveSet.Workspace(NODES));

        WaterActiveSet.Failure failure = assertInstanceOf(WaterActiveSet.Failure.class, outcome);
        assertEquals(WaterActiveSet.FailureCode.PURE_STEAM_SATURATION_REQUIRES_ENERGY, failure.code());
    }

    private static WaterActiveSet.Result successful(double hydrocarbonRate, double waterFeed, double temperatureKelvin) {
        double[] temperature = filled(temperatureKelvin);
        double[] pressure = filled(PRESSURE);
        double[] hydrocarbon = filled(hydrocarbonRate);
        double[] feed = new double[NODES];
        feed[2] = waterFeed;
        WaterActiveSet.Outcome outcome = WaterActiveSet.solve(
                new WaterActiveSet.Input(temperature, pressure, hydrocarbon, feed, new boolean[NODES]),
                new WaterActiveSet.Workspace(NODES));
        if (outcome instanceof WaterActiveSet.Failure failure) {
            throw new AssertionError(failure);
        }
        return assertInstanceOf(WaterActiveSet.Result.class, outcome);
    }

    private static double[] filled(double value) {
        double[] values = new double[NODES];
        for (int index = 0; index < values.length; index++) values[index] = value;
        return values;
    }
}
