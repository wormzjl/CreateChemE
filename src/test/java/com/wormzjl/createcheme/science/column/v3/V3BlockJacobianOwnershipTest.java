package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class V3BlockJacobianOwnershipTest {
    @Test
    void copyingConstructorAndOwnedAssemblyBothKeepAccessorSnapshotsIndependent() {
        V3StageBlockLayout layout = new V3StageBlockLayout(
                V3TruncationSupportTest.problem(V3CondenserPhaseBranch.TWO_PHASE, 0.0, 2));
        for (boolean owned : new boolean[]{false, true}) {
            double[][][] lower = blocks(layout, -1);
            double[][][] diagonal = blocks(layout, 0);
            double[][][] upper = blocks(layout, 1);
            diagonal[0][0][0] = -0.0;
            diagonal[0][0][1] = 3.5;
            V3BlockJacobian matrix = owned ? V3BlockJacobian.fromOwnedBlocks(layout, lower, diagonal, upper, 0)
                    : new V3BlockJacobian(layout, lower, diagonal, upper, 0);
            if (!owned) diagonal[0][0][1] = 99;
            double[][] snapshot = matrix.diagonal(0);
            snapshot[0][0] = 9;
            snapshot[0][1] = 99;
            snapshot[1] = new double[1];
            assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(matrix.diagonal(0)[0][0]));
            assertEquals(3.5, matrix.diagonal(0)[0][1]);
            assertEquals(3.5, matrix.toBandedMatrix().get(0, 1));
        }
    }

    @Test
    void ownedAssemblyStillRejectsInvalidShapesAndNonfiniteEntries() {
        V3StageBlockLayout layout = new V3StageBlockLayout(
                V3TruncationSupportTest.problem(V3CondenserPhaseBranch.TWO_PHASE, 0.0, 2));
        double[][][] diagonal = blocks(layout, 0);
        diagonal[0][0][0] = Double.NaN;
        assertThrows(IllegalArgumentException.class,
                () -> V3BlockJacobian.fromOwnedBlocks(layout, blocks(layout, -1), diagonal, blocks(layout, 1), 0));
        assertThrows(IllegalArgumentException.class,
                () -> V3BlockJacobian.fromOwnedBlocks(layout, blocks(layout, 0), blocks(layout, 0), blocks(layout, 1), 0));
    }

    private static double[][][] blocks(V3StageBlockLayout layout, int offset) {
        double[][][] blocks = new double[layout.nodeCount()][][];
        for (int node = 0; node < blocks.length; node++) {
            int columnNode = node + offset;
            blocks[node] = new double[layout.size(node)][columnNode < 0 || columnNode >= blocks.length ? 0 : layout.size(columnNode)];
        }
        return blocks;
    }
}
