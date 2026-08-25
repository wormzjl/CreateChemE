package com.wormzjl.createcheme.science.thermo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TpFlashSolverTest {
    private static final ThermoComponent METHANE = new ThermoComponent(
            "methane", 190.564, 4_599_200.0, 0.01142, 0.016043);
    private static final ThermoComponent N_BUTANE = new ThermoComponent(
            "n_butane", 425.12, 3_796_000.0, 0.2002, 0.058124);

    private final PengRobinson78 model =
            PengRobinson78.withoutBinaryInteractions(List.of(METHANE, N_BUTANE));
    private final TpFlashSolver solver = new TpFlashSolver(model);

    @Test
    void methaneButaneFlashConvergesAndClosesMaterialAndFugacityBalances() {
        double[] feed = {0.5, 0.5};

        FlashResult result = solver.solve(250.0, 2_000_000.0, feed);

        assertEquals(FlashResult.PhaseState.TWO_PHASE, result.phaseState());
        assertEquals(0.41605097237284716, result.vaporFraction(), 1.0e-8);
        assertTrue(result.maximumLogFugacityResidual() <= TpFlashSolver.DEFAULT_TOLERANCE);

        double[] liquid = result.liquidMoleFractions();
        double[] vapor = result.vaporMoleFractions();
        assertEquals(1.0, liquid[0] + liquid[1], 1.0e-12);
        assertEquals(1.0, vapor[0] + vapor[1], 1.0e-12);
        for (int i = 0; i < feed.length; i++) {
            double reconstructed = (1.0 - result.vaporFraction()) * liquid[i]
                    + result.vaporFraction() * vapor[i];
            assertEquals(feed[i], reconstructed, 1.0e-10);
        }

        PhaseProperties liquidProperties = model.evaluate(250.0, 2_000_000.0, liquid, PhaseRoot.LIQUID);
        PhaseProperties vaporProperties = model.evaluate(250.0, 2_000_000.0, vapor, PhaseRoot.VAPOR);
        for (int i = 0; i < feed.length; i++) {
            double liquidLogFugacity = Math.log(liquid[i])
                    + liquidProperties.logFugacityCoefficient(i);
            double vaporLogFugacity = Math.log(vapor[i])
                    + vaporProperties.logFugacityCoefficient(i);
            assertEquals(liquidLogFugacity, vaporLogFugacity, 2.0e-8);
        }
    }

    @Test
    void obviousGasStateReturnsSingleVaporPhase() {
        FlashResult result = solver.solve(500.0, 100_000.0, new double[] {0.7, 0.3});

        assertEquals(FlashResult.PhaseState.VAPOR, result.phaseState());
        assertEquals(1.0, result.vaporFraction());
    }

    @Test
    void resultCompositionsAreDefensiveCopies() {
        FlashResult result = solver.solve(250.0, 2_000_000.0, new double[] {0.5, 0.5});
        double original = result.liquidMoleFractions()[0];

        double[] copy = result.liquidMoleFractions();
        copy[0] = 0.0;

        assertEquals(original, result.liquidMoleFractions()[0]);
    }
}
