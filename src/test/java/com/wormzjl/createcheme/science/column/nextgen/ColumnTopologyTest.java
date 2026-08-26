package com.wormzjl.createcheme.science.column.nextgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ColumnTopologyTest {
    @Test
    void normalCondenserRowsCloseBothLocalAndExternalComponentBalances() {
        ColumnNextInput defaults = ColumnNextInput.defaults();
        ColumnNextInput input = new ColumnNextInput(
                defaults.schemaVersion(), defaults.packageId(), defaults.assayId(), defaults.crudeFeed(),
                4, 2, defaults.topPressurePascal(), 0.0, defaults.condenserOutletTemperatureKelvin(),
                defaults.reboilerDutyWatts(), 1.0, List.of(), List.of());
        ColumnTopology topology = ColumnTopology.create(input);
        int nodes = topology.nodeCount();
        double[] k = new double[nodes];
        double[] liquidTotals = new double[nodes];
        double[] vaporTotals = new double[nodes];
        for (int node = 0; node < nodes; node++) {
            k[node] = 0.90;
            liquidTotals[node] = 1.0;
            vaporTotals[node] = 1.0;
        }
        double[] lower = new double[nodes];
        double[] diagonal = new double[nodes];
        double[] upper = new double[nodes];
        double[] rhs = new double[nodes];
        double feed = 10.0;
        topology.assembleHydrocarbonRows(k, liquidTotals, vaporTotals, liquidTotals[0], liquidTotals[nodes - 1],
                0.5, new double[topology.stageCount() + 1], feed, lower, diagonal, upper, rhs);
        double[] liquid = new double[nodes];
        assertTrue(ThomasTridiagonalSolver.solve(lower, diagonal, upper, rhs, liquid,
                new double[nodes], new double[nodes]) <= 1.0e-12);
        double[] vapor = new double[nodes];
        for (int node = 0; node < nodes; node++) vapor[node] = k[node] * liquid[node];
        double[] residual = new double[nodes];
        topology.evaluateHydrocarbonBalanceResiduals(liquid, vapor, new double[topology.stageCount() + 1],
                0.5, feed, residual);
        for (double value : residual) assertEquals(0.0, value, 1.0e-11);
        assertEquals(0.0, feed - vapor[0] - 0.5 * liquid[0] - liquid[nodes - 1], 1.0e-11);
    }
}
