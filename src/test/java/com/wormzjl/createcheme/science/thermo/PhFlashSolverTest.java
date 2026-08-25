package com.wormzjl.createcheme.science.thermo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PhFlashSolverTest {
    private static final ThermoComponent METHANE = new ThermoComponent(
            "methane", 190.564, 4_599_200.0, 0.01142, 0.016043);
    private static final ThermoComponent N_BUTANE = new ThermoComponent(
            "n_butane", 425.12, 3_796_000.0, 0.2002, 0.058124);

    private final PengRobinson78 equationOfState =
            PengRobinson78.withoutBinaryInteractions(List.of(METHANE, N_BUTANE));
    private final PengRobinsonCaloricModel caloricModel = new PengRobinsonCaloricModel(
            equationOfState,
            List.of(
                    IdealGasHeatCapacity.constant(298.15, 0.0, 35.0),
                    IdealGasHeatCapacity.constant(298.15, 0.0, 100.0)));
    private final TpCaloricFlashSolver tpFlash =
            new TpCaloricFlashSolver(new TpFlashSolver(equationOfState), caloricModel);
    private final PhFlashSolver phFlash = new PhFlashSolver(tpFlash);

    @Test
    void caloricFlashClosesThePhaseEnthalpyBalance() {
        CaloricFlashResult result = tpFlash.solve(
                250.0, 2_000_000.0, new double[] {0.5, 0.5});
        double vaporFraction = result.equilibrium().vaporFraction();
        double reconstructed = (1.0 - vaporFraction)
                        * result.liquidProperties().enthalpyJoulesPerMol()
                + vaporFraction * result.vaporProperties().enthalpyJoulesPerMol();

        assertEquals(FlashResult.PhaseState.TWO_PHASE, result.equilibrium().phaseState());
        assertEquals(reconstructed, result.enthalpyJoulesPerMol(), 1.0e-12);
    }

    @Test
    void pressureEnthalpyFlashRecoversKnownTwoPhaseTemperature() {
        double[] feed = {0.5, 0.5};
        double targetEnthalpy = tpFlash.solve(250.0, 2_000_000.0, feed).enthalpyJoulesPerMol();

        PhFlashResult result = phFlash.solve(
                2_000_000.0, feed, targetEnthalpy, 200.0, 350.0);

        assertTrue(result.converged());
        assertEquals(250.0, result.temperatureKelvin(), 1.0e-5);
        assertEquals(0.0, result.enthalpyResidualJoulesPerMol(), 1.0e-3);
        assertEquals(FlashResult.PhaseState.TWO_PHASE, result.flashResult().equilibrium().phaseState());
    }

    @Test
    void pressureEnthalpyFlashRejectsAnUnbracketedTarget() {
        double[] feed = {0.5, 0.5};
        double belowBracket = tpFlash.solve(180.0, 2_000_000.0, feed).enthalpyJoulesPerMol();

        assertThrows(
                IllegalArgumentException.class,
                () -> phFlash.solve(2_000_000.0, feed, belowBracket, 200.0, 350.0));
    }
}
