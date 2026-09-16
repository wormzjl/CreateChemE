package com.wormzjl.createcheme.science.fluid;

import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.createcheme.fluid.support.ConservationAssertions;
import com.wormzjl.createcheme.fluid.support.FluidTestSupport.ReservoirFixture;
import org.junit.jupiter.api.Test;

class ConservationHarnessTest {
    @Test void balancesExternalTurnoverAndReferenceSignedEnergy() {
        ConservationAssertions.components(new double[] {5, 0}, new double[] {3, 4},
                new double[] {-2, 4}, new double[] {2, 4});
        ConservationAssertions.energy(-100, -80, 20, 20);
    }
    @Test void detectsMassCorruptionAndNegativeTraceInventory() {
        assertThrows(AssertionError.class, () -> ConservationAssertions.components(
                new double[] {1}, new double[] {1.001}, new double[] {0}, new double[] {0}));
        assertThrows(AssertionError.class, () -> ConservationAssertions.components(
                new double[] {0}, new double[] {-1e-15}, new double[] {0}, new double[] {0}));
    }
    @Test void detectsEnergyCorruptionAndNonfiniteArithmetic() {
        assertThrows(AssertionError.class, () -> ConservationAssertions.energy(100, 110, 0, 0));
        assertThrows(AssertionError.class, () -> ConservationAssertions.energy(100, Double.NaN, 0, 0));
        assertThrows(AssertionError.class, () -> ConservationAssertions.energy(
                -Double.MAX_VALUE, Double.MAX_VALUE, 0, 0));
    }
    @Test void fixtureOwnsItsArraysAndPreservesEmptyInventory() {
        double[] amounts = {0, 0};
        var fixture = new ReservoirFixture(1, amounts, 0, 20);
        amounts[0] = 7;
        fixture.componentMoles()[1] = 8;
        assertArrayEquals(new double[] {0, 0}, fixture.componentMoles());
        assertThrows(IllegalArgumentException.class, () -> new ReservoirFixture(0, amounts, 0, 0));
    }
}
