package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class V3DryMeshStateOwnershipTest {
    @Test
    void copiedAndAdoptedStatesPreserveBitsAndIndependentPerturbations() {
        var topology = V3ColumnTopology.twoPhase(2, 1);
        double[][] liquid = flows();
        double[][] vapor = flows();
        double[] temperatures = {300, 310, 320, 330};
        double[] water = {-0.0, 1, 2, 0};
        var copied = new V3DryMeshState(topology, 2, liquid, vapor, temperatures, water);
        liquid[1][0] = 999;
        vapor[2][1] = 999;
        temperatures[1] = 999;
        water[1] = 999;
        var adopted = V3DryMeshState.fromOwnedArrays(topology, 2, flows(), flows(),
                new double[] {300, 310, 320, 330}, new double[] {-0.0, 1, 2, 0});
        var moved = adopted.withLiquidFlow(1, 0, 8).withVaporFlow(2, 1, 9)
                .withTemperatureKelvin(1, 350).withFreeWaterFlow(2, 3);
        for (int node = 0; node < 4; node++) {
            assertEquals(copied.temperatureKelvin(node), adopted.temperatureKelvin(node));
            assertEquals(Double.doubleToRawLongBits(copied.freeWaterFlow(node)),
                    Double.doubleToRawLongBits(adopted.freeWaterFlow(node)));
            for (int component = 0; component < 2; component++) {
                assertEquals(copied.liquidFlow(node, component), adopted.liquidFlow(node, component));
                assertEquals(copied.vaporFlow(node, component), adopted.vaporFlow(node, component));
            }
        }
        assertEquals(8, moved.liquidFlow(1, 0));
        assertEquals(9, moved.vaporFlow(2, 1));
        assertEquals(350, moved.temperatureKelvin(1));
        assertEquals(3, moved.freeWaterFlow(2));
    }

    @Test
    void adoptionRetainsEveryShapeFiniteDomainAndPhasePlacementCheck() {
        for (boolean adopt : new boolean[] {false, true}) {
            for (int fault = 0; fault < 10; fault++) {
                var topology = fault == 8 ? V3ColumnTopology.liquidOnly(2, 1)
                        : fault == 9 ? V3ColumnTopology.vaporOnly(2, 1) : V3ColumnTopology.twoPhase(2, 1);
                double[][] liquid = flows();
                double[][] vapor = flows();
                double[] temperatures = {300, 310, 320, 330};
                double[] water = {0, 1, 2, 0};
                switch (fault) {
                    case 0 -> liquid[1] = null;
                    case 1 -> vapor[1] = new double[1];
                    case 2 -> liquid[1][0] = Double.NaN;
                    case 3 -> vapor[1][0] = -1;
                    case 4 -> temperatures[2] = Double.POSITIVE_INFINITY;
                    case 5 -> temperatures[2] = 0;
                    case 6 -> water[2] = -1;
                    case 7 -> water[0] = 1;
                    default -> { } // The terminal phase is absent but its input flow is nonzero.
                }
                assertThrows(IllegalArgumentException.class, () -> {
                    if (adopt) V3DryMeshState.fromOwnedArrays(topology, 2, liquid, vapor, temperatures, water);
                    else new V3DryMeshState(topology, 2, liquid, vapor, temperatures, water);
                });
            }
        }
    }

    private static double[][] flows() {
        return new double[][] {{1, 2}, {3, 4}, {5, 6}, {7, 8}};
    }
}
