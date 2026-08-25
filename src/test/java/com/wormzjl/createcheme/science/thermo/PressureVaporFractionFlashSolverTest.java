package com.wormzjl.createcheme.science.thermo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PressureVaporFractionFlashSolverTest {
    private static final ThermoComponent METHANE = new ThermoComponent(
            "methane", 190.564, 4_599_200.0, 0.01142, 0.016043);
    private static final ThermoComponent N_BUTANE = new ThermoComponent(
            "n_butane", 425.12, 3_796_000.0, 0.2002, 0.058124);

    private final PressureVaporFractionFlashSolver solver =
            new PressureVaporFractionFlashSolver(
                    PengRobinson78.withoutBinaryInteractions(List.of(METHANE, N_BUTANE)));

    @Test
    void recoversTemperatureForSpecifiedEquilibriumVaporFraction() {
        PressureVaporFractionFlashSolver.Result result = solver.solve(
                2_000_000.0,
                0.41605097237284716,
                new double[] {0.5, 0.5},
                180.0,
                450.0,
                240.0);

        assertTrue(result.converged());
        assertEquals(250.0, result.temperatureKelvin(), 2.0e-4);
        assertTrue(Math.abs(result.vaporFractionResidual())
                <= PressureVaporFractionFlashSolver.DEFAULT_VAPOR_FRACTION_TOLERANCE);
        assertTrue(result.equilibrium().maximumLogFugacityResidual()
                <= TpFlashSolver.DEFAULT_TOLERANCE);
    }
}
