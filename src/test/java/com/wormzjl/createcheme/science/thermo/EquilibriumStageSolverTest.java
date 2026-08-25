package com.wormzjl.createcheme.science.thermo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.createcheme.science.column.EquilibriumStageSolver;
import java.util.List;
import org.junit.jupiter.api.Test;

class EquilibriumStageSolverTest {
    private static final ThermoComponent METHANE = new ThermoComponent(
            "methane", 190.564, 4_599_200.0, 0.01142, 0.016043);
    private static final ThermoComponent N_BUTANE = new ThermoComponent(
            "n_butane", 425.12, 3_796_000.0, 0.2002, 0.058124);

    @Test
    void stageClosesBinaryMaterialAndEnergyBalances() {
        PengRobinson78 equationOfState =
                PengRobinson78.withoutBinaryInteractions(List.of(METHANE, N_BUTANE));
        PengRobinsonCaloricModel caloricModel = new PengRobinsonCaloricModel(
                equationOfState,
                List.of(
                        IdealGasHeatCapacity.constant(298.15, 0.0, 35.0),
                        IdealGasHeatCapacity.constant(298.15, 0.0, 100.0)));
        TpCaloricFlashSolver tpFlash = new TpCaloricFlashSolver(
                new TpFlashSolver(equationOfState), caloricModel);
        EquilibriumStageSolver stage =
                new EquilibriumStageSolver(new PhFlashSolver(tpFlash));

        double pressure = 2_000_000.0;
        double[] firstComposition = {0.8, 0.2};
        double[] secondComposition = {0.2, 0.8};
        double firstFlow = 10.0;
        double secondFlow = 5.0;
        double firstEnthalpy =
                tpFlash.solve(230.0, pressure, firstComposition).enthalpyJoulesPerMol();
        double secondEnthalpy =
                tpFlash.solve(300.0, pressure, secondComposition).enthalpyJoulesPerMol();
        double[] mixedComposition = {0.6, 0.4};
        double targetEnthalpy =
                tpFlash.solve(270.0, pressure, mixedComposition).enthalpyJoulesPerMol();
        double heatDuty = (firstFlow + secondFlow) * targetEnthalpy
                - firstFlow * firstEnthalpy
                - secondFlow * secondEnthalpy;

        EquilibriumStageSolver.Result result = stage.solve(
                pressure,
                List.of(
                        new EquilibriumStageSolver.Inlet(
                                firstFlow, firstEnthalpy, firstComposition),
                        new EquilibriumStageSolver.Inlet(
                                secondFlow, secondEnthalpy, secondComposition)),
                heatDuty,
                200.0,
                350.0);

        assertTrue(result.flashResult().converged());
        assertEquals(270.0, result.flashResult().temperatureKelvin(), 1.0e-5);
        assertEquals(15.0, result.totalMolarFlowMolPerSecond(), 1.0e-12);
        assertEquals(
                result.totalMolarFlowMolPerSecond(),
                result.liquidMolarFlowMolPerSecond() + result.vaporMolarFlowMolPerSecond(),
                1.0e-12);
        assertEquals(0.0, result.energyResidualWatts(), 0.02);
        assertEquals(0.0, result.maximumComponentResidualMolPerSecond(), 1.0e-9);
    }

    @Test
    void stageClosesBalancesForTheTwelveCutCrudeFixture() {
        PengRobinson78 equationOfState = TiaJuanaLightCrudeFixture.equationOfState();
        PengRobinsonCaloricModel caloricModel =
                TiaJuanaLightCrudeFixture.caloricModel(equationOfState);
        TpCaloricFlashSolver tpFlash = new TpCaloricFlashSolver(
                new TpFlashSolver(equationOfState), caloricModel);
        EquilibriumStageSolver stage =
                new EquilibriumStageSolver(new PhFlashSolver(tpFlash));

        double[] feed = TiaJuanaLightCrudeFixture.feedMoleFractions();
        double pressure = 190_000.0;
        double flow = 100.0;
        double inletEnthalpy = tpFlash.solve(420.0, pressure, feed).enthalpyJoulesPerMol();
        double targetEnthalpy = tpFlash.solve(520.0, pressure, feed).enthalpyJoulesPerMol();
        double heatDuty = flow * (targetEnthalpy - inletEnthalpy);

        EquilibriumStageSolver.Result result = stage.solve(
                pressure,
                List.of(new EquilibriumStageSolver.Inlet(flow, inletEnthalpy, feed)),
                heatDuty,
                350.0,
                650.0);

        assertTrue(result.flashResult().converged());
        assertEquals(520.0, result.flashResult().temperatureKelvin(), 1.0e-5);
        assertEquals(flow, result.totalMolarFlowMolPerSecond(), 1.0e-12);
        assertEquals(0.0, result.energyResidualWatts(), 0.2);
        assertEquals(0.0, result.maximumComponentResidualMolPerSecond(), 1.0e-8);
    }
}
