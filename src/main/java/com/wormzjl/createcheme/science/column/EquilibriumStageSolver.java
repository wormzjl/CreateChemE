package com.wormzjl.createcheme.science.column;

import com.wormzjl.createcheme.science.thermo.CaloricFlashResult;
import com.wormzjl.createcheme.science.thermo.PhFlashResult;
import com.wormzjl.createcheme.science.thermo.PhFlashSolver;
import java.util.List;
import java.util.Objects;

/** Mixes inlet streams and solves one equilibrium stage from its material and energy balances. */
public final class EquilibriumStageSolver {
    private final PhFlashSolver flashSolver;

    public EquilibriumStageSolver(PhFlashSolver flashSolver) {
        this.flashSolver = Objects.requireNonNull(flashSolver, "flashSolver");
    }

    public Result solve(
            double pressurePascal,
            List<Inlet> inlets,
            double heatDutyWatts,
            double minimumTemperatureKelvin,
            double maximumTemperatureKelvin) {
        Objects.requireNonNull(inlets, "inlets");
        if (inlets.isEmpty() || !Double.isFinite(heatDutyWatts)) {
            throw new IllegalArgumentException("A stage requires inlet material and a finite heat duty");
        }

        int componentCount = inlets.getFirst().componentCount();
        double[] componentMolarFlows = new double[componentCount];
        double totalMolarFlow = 0.0;
        double inletEnthalpyFlowWatts = heatDutyWatts;
        for (Inlet inlet : inlets) {
            if (inlet.componentCount() != componentCount) {
                throw new IllegalArgumentException("All stage inlets must use the same component set");
            }
            totalMolarFlow += inlet.molarFlowMolPerSecond();
            inletEnthalpyFlowWatts +=
                    inlet.molarFlowMolPerSecond() * inlet.enthalpyJoulesPerMol();
            for (int i = 0; i < componentCount; i++) {
                componentMolarFlows[i] +=
                        inlet.molarFlowMolPerSecond() * inlet.moleFraction(i);
            }
        }
        if (!(totalMolarFlow > 0.0)) {
            throw new IllegalArgumentException("Stage inlet flow must be positive");
        }

        double[] feedMoleFractions = new double[componentCount];
        for (int i = 0; i < componentCount; i++) {
            feedMoleFractions[i] = componentMolarFlows[i] / totalMolarFlow;
        }
        double targetEnthalpyJoulesPerMol = inletEnthalpyFlowWatts / totalMolarFlow;
        PhFlashResult flash = flashSolver.solve(
                pressurePascal,
                feedMoleFractions,
                targetEnthalpyJoulesPerMol,
                minimumTemperatureKelvin,
                maximumTemperatureKelvin);
        CaloricFlashResult equilibrium = flash.flashResult();
        double liquidMolarFlow = totalMolarFlow * (1.0 - equilibrium.equilibrium().vaporFraction());
        double vaporMolarFlow = totalMolarFlow * equilibrium.equilibrium().vaporFraction();
        double energyResidualWatts = totalMolarFlow * equilibrium.enthalpyJoulesPerMol()
                - inletEnthalpyFlowWatts;
        double maximumComponentResidual = maximumComponentResidual(
                componentMolarFlows, liquidMolarFlow, vaporMolarFlow, equilibrium);
        return new Result(
                totalMolarFlow,
                liquidMolarFlow,
                vaporMolarFlow,
                targetEnthalpyJoulesPerMol,
                energyResidualWatts,
                maximumComponentResidual,
                flash);
    }

    private static double maximumComponentResidual(
            double[] inletComponentFlows,
            double liquidMolarFlow,
            double vaporMolarFlow,
            CaloricFlashResult equilibrium) {
        double[] liquid = equilibrium.equilibrium().liquidMoleFractions();
        double[] vapor = equilibrium.equilibrium().vaporMoleFractions();
        double maximumResidual = 0.0;
        for (int i = 0; i < inletComponentFlows.length; i++) {
            double outletFlow = liquidMolarFlow * liquid[i] + vaporMolarFlow * vapor[i];
            maximumResidual = Math.max(
                    maximumResidual, Math.abs(outletFlow - inletComponentFlows[i]));
        }
        return maximumResidual;
    }

    /** Immutable inlet stream using molar flow, molar enthalpy, and composition. */
    public static final class Inlet {
        private final double molarFlowMolPerSecond;
        private final double enthalpyJoulesPerMol;
        private final double[] moleFractions;

        public Inlet(
                double molarFlowMolPerSecond,
                double enthalpyJoulesPerMol,
                double[] moleFractions) {
            if (!Double.isFinite(molarFlowMolPerSecond)
                    || molarFlowMolPerSecond < 0.0
                    || !Double.isFinite(enthalpyJoulesPerMol)) {
                throw new IllegalArgumentException("Invalid stage inlet flow or enthalpy");
            }
            this.molarFlowMolPerSecond = molarFlowMolPerSecond;
            this.enthalpyJoulesPerMol = enthalpyJoulesPerMol;
            this.moleFractions = normalized(moleFractions);
        }

        public double molarFlowMolPerSecond() {
            return molarFlowMolPerSecond;
        }

        public double enthalpyJoulesPerMol() {
            return enthalpyJoulesPerMol;
        }

        public int componentCount() {
            return moleFractions.length;
        }

        public double moleFraction(int component) {
            return moleFractions[component];
        }

        public double[] moleFractions() {
            return moleFractions.clone();
        }

        private static double[] normalized(double[] composition) {
            Objects.requireNonNull(composition, "composition");
            double[] normalized = composition.clone();
            double sum = 0.0;
            for (double fraction : normalized) {
                if (!Double.isFinite(fraction) || fraction < 0.0) {
                    throw new IllegalArgumentException(
                            "Stage inlet composition must be finite and nonnegative");
                }
                sum += fraction;
            }
            if (!(sum > 0.0)) {
                throw new IllegalArgumentException("Stage inlet composition must contain material");
            }
            for (int i = 0; i < normalized.length; i++) {
                normalized[i] /= sum;
            }
            return normalized;
        }
    }

    /** Immutable converged or unconverged state result for one equilibrium stage. */
    public static final class Result {
        private final double totalMolarFlowMolPerSecond;
        private final double liquidMolarFlowMolPerSecond;
        private final double vaporMolarFlowMolPerSecond;
        private final double targetEnthalpyJoulesPerMol;
        private final double energyResidualWatts;
        private final double maximumComponentResidualMolPerSecond;
        private final PhFlashResult flashResult;

        private Result(
                double totalMolarFlowMolPerSecond,
                double liquidMolarFlowMolPerSecond,
                double vaporMolarFlowMolPerSecond,
                double targetEnthalpyJoulesPerMol,
                double energyResidualWatts,
                double maximumComponentResidualMolPerSecond,
                PhFlashResult flashResult) {
            this.totalMolarFlowMolPerSecond = totalMolarFlowMolPerSecond;
            this.liquidMolarFlowMolPerSecond = liquidMolarFlowMolPerSecond;
            this.vaporMolarFlowMolPerSecond = vaporMolarFlowMolPerSecond;
            this.targetEnthalpyJoulesPerMol = targetEnthalpyJoulesPerMol;
            this.energyResidualWatts = energyResidualWatts;
            this.maximumComponentResidualMolPerSecond = maximumComponentResidualMolPerSecond;
            this.flashResult = flashResult;
        }

        public double totalMolarFlowMolPerSecond() {
            return totalMolarFlowMolPerSecond;
        }

        public double liquidMolarFlowMolPerSecond() {
            return liquidMolarFlowMolPerSecond;
        }

        public double vaporMolarFlowMolPerSecond() {
            return vaporMolarFlowMolPerSecond;
        }

        public double targetEnthalpyJoulesPerMol() {
            return targetEnthalpyJoulesPerMol;
        }

        public double energyResidualWatts() {
            return energyResidualWatts;
        }

        public double maximumComponentResidualMolPerSecond() {
            return maximumComponentResidualMolPerSecond;
        }

        public PhFlashResult flashResult() {
            return flashResult;
        }
    }
}
