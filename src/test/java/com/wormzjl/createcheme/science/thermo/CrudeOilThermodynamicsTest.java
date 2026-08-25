package com.wormzjl.createcheme.science.thermo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CrudeOilThermodynamicsTest {
    private static final double[][] COLUMN_STATES = {
        {359.28, 135_800.0, 0.008592280},
        {420.00, 160_000.0, 0.194016557},
        {500.00, 190_000.0, 0.421213263},
        {578.55, 225_500.0, 0.605725830}
    };

    @Test
    void tiaJuanaLightFlashConvergesAcrossTheColumnTemperatureRange() {
        PengRobinson78 equationOfState = TiaJuanaLightCrudeFixture.equationOfState();
        PengRobinsonCaloricModel caloricModel =
                TiaJuanaLightCrudeFixture.caloricModel(equationOfState);
        TpFlashSolver solver = new TpFlashSolver(equationOfState);
        double[] feed = TiaJuanaLightCrudeFixture.feedMoleFractions();

        assertEquals(12, feed.length);
        assertEquals(1.0, sum(feed), 1.0e-12);
        for (double[] state : COLUMN_STATES) {
            double temperature = state[0];
            double pressure = state[1];
            FlashResult result = solver.solve(temperature, pressure, feed);

            assertEquals(FlashResult.PhaseState.TWO_PHASE, result.phaseState());
            assertEquals(state[2], result.vaporFraction(), 1.0e-8);
            assertTrue(result.maximumLogFugacityResidual() <= TpFlashSolver.DEFAULT_TOLERANCE);
            assertMaterialBalance(feed, result);

            CaloricPhaseProperties liquid = caloricModel.evaluate(
                    temperature,
                    pressure,
                    result.liquidMoleFractions(),
                    PhaseRoot.LIQUID);
            CaloricPhaseProperties vapor = caloricModel.evaluate(
                    temperature,
                    pressure,
                    result.vaporMoleFractions(),
                    PhaseRoot.VAPOR);
            assertTrue(Double.isFinite(liquid.enthalpyJoulesPerMol()));
            assertTrue(Double.isFinite(vapor.enthalpyJoulesPerMol()));
            assertTrue(vapor.enthalpyJoulesPerMol() > liquid.enthalpyJoulesPerMol());
        }
    }

    @Test
    void crudePressureEnthalpyFlashRecoversAColumnTemperature() {
        PengRobinson78 equationOfState = TiaJuanaLightCrudeFixture.equationOfState();
        PengRobinsonCaloricModel caloricModel =
                TiaJuanaLightCrudeFixture.caloricModel(equationOfState);
        TpCaloricFlashSolver tpFlash = new TpCaloricFlashSolver(
                new TpFlashSolver(equationOfState), caloricModel);
        PhFlashSolver phFlash = new PhFlashSolver(tpFlash);
        double[] feed = TiaJuanaLightCrudeFixture.feedMoleFractions();
        double pressure = 190_000.0;
        double targetEnthalpy = tpFlash.solve(487.3, pressure, feed).enthalpyJoulesPerMol();

        PhFlashResult result = phFlash.solve(
                pressure, feed, targetEnthalpy, 350.0, 650.0);

        assertTrue(result.converged());
        assertTrue(result.iterations() > 1 && result.iterations() <= 8);
        assertEquals(487.3, result.temperatureKelvin(), 1.0e-5);
        assertEquals(0.0, result.enthalpyResidualJoulesPerMol(), 1.0e-3);
        assertEquals(FlashResult.PhaseState.TWO_PHASE, result.flashResult().equilibrium().phaseState());
    }

    private static void assertMaterialBalance(double[] feed, FlashResult result) {
        double[] liquid = result.liquidMoleFractions();
        double[] vapor = result.vaporMoleFractions();
        assertEquals(1.0, sum(liquid), 1.0e-12);
        assertEquals(1.0, sum(vapor), 1.0e-12);
        for (int i = 0; i < feed.length; i++) {
            assertEquals(
                    feed[i],
                    (1.0 - result.vaporFraction()) * liquid[i]
                            + result.vaporFraction() * vapor[i],
                    1.0e-10);
        }
    }

    private static double sum(double[] values) {
        double sum = 0.0;
        for (double value : values) {
            sum += value;
        }
        return sum;
    }
}
