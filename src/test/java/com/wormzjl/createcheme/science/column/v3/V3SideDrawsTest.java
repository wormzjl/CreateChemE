package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class V3SideDrawsTest {
    @Test
    void centralizesLiquidTotalAndWithdrawalArithmetic() {
        V3ColumnProblem problem = V3ColumnProblemResolver.resolve(
                V3SideDrawContractTest.input(java.util.List.of()), V3CondenserPhaseBranch.TWO_PHASE);
        double[][] liquid = new double[problem.topology().nodeCount()][2];
        double[][] vapor = new double[problem.topology().nodeCount()][2];
        double[] temperatures = new double[problem.topology().nodeCount()];
        for (int node = 0; node < temperatures.length; node++) {
            temperatures[node] = 400;
            liquid[node] = new double[] {30, 70};
            vapor[node] = new double[] {40, 60};
        }
        V3DryMeshState state = new V3DryMeshState(problem.topology(), 2, liquid, vapor, temperatures);
        V3SideDraws.Withdrawal withdrawal = V3SideDraws.withdrawal(state, 2, 25);
        assertEquals(100, withdrawal.liquidTotalMolPerSecond());
        assertEquals(0.25, withdrawal.fraction());
        assertThrows(IllegalArgumentException.class, () -> V3SideDraws.withdrawal(state, 2, -1));
    }
}
