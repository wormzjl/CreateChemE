package com.wormzjl.createcheme.science.thermo;

import java.util.Objects;

/** Couples phase equilibrium and caloric property evaluation at fixed temperature and pressure. */
public final class TpCaloricFlashSolver {
    private final TpFlashSolver flashSolver;
    private final PengRobinsonCaloricModel caloricModel;

    public TpCaloricFlashSolver(
            TpFlashSolver flashSolver, PengRobinsonCaloricModel caloricModel) {
        this.flashSolver = Objects.requireNonNull(flashSolver, "flashSolver");
        this.caloricModel = Objects.requireNonNull(caloricModel, "caloricModel");
    }

    public CaloricFlashResult solve(
            double temperatureKelvin, double pressurePascal, double[] feedMoleFractions) {
        FlashResult equilibrium =
                flashSolver.solve(temperatureKelvin, pressurePascal, feedMoleFractions);
        CaloricPhaseProperties liquidProperties = caloricModel.evaluate(
                temperatureKelvin,
                pressurePascal,
                equilibrium.liquidMoleFractions(),
                PhaseRoot.LIQUID);
        CaloricPhaseProperties vaporProperties = caloricModel.evaluate(
                temperatureKelvin,
                pressurePascal,
                equilibrium.vaporMoleFractions(),
                PhaseRoot.VAPOR);
        return new CaloricFlashResult(
                temperatureKelvin,
                pressurePascal,
                equilibrium,
                liquidProperties,
                vaporProperties);
    }
}
