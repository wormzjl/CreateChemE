package com.wormzjl.createcheme.science.fluid.state;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class FluidInventoryTest {
    @Test void ownsInventoryAndOffsetArrays() {
        double[] offsets = {1, 2};
        var reference = new EnergyReference("r1", List.of("a", "b"), offsets, false);
        double[] moles = {3, 4};
        var inventory = new FluidInventory(1, moles, 100, reference);
        offsets[0] = 99; moles[0] = 99; inventory.moles()[1] = 99;
        assertEquals(3, inventory.moles(0)); assertEquals(4, inventory.moles(1));
        assertEquals(1, reference.offsetJoulesPerMole(0));
    }
    @Test void rebasesEnergyWithoutChangingInventoryAndReversesTheMigration() {
        var old = EnergyReference.sensible(List.of("a", "b"));
        var updated = new EnergyReference("r2", old.components(), new double[] {-1000, 2000}, true);
        var inventory = new FluidInventory(2, new double[] {2, 3}, 100, old);
        var migrated = inventory.rebase(updated);
        assertEquals(4100, migrated.internalEnergyJoules());
        assertArrayEquals(inventory.moles(), migrated.moles());
        assertEquals(100, migrated.rebase(old).internalEnergyJoules());
        assertFalse(old.formationDataQualified());
    }
    @Test void rejectsReorderedBasisInvalidInventoryAndEnergyInAnEmptyVessel() {
        var basis = EnergyReference.sensible(List.of("a", "b"));
        var inventory = new FluidInventory(1, new double[] {1, 0}, -20, basis);
        assertThrows(IllegalArgumentException.class, () -> inventory.rebase(EnergyReference.sensible(List.of("b", "a"))));
        assertThrows(IllegalArgumentException.class, () -> new FluidInventory(1, new double[] {-1, 2}, 0, basis));
        assertThrows(IllegalArgumentException.class, () -> new FluidInventory(1, new double[] {0, 0}, 1, basis));
        assertTrue(new FluidInventory(1, new double[] {0, 0}, 0, basis).isEmpty());
    }
}
