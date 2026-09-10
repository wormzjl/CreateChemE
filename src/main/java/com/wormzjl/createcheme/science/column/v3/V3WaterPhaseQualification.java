package com.wormzjl.createcheme.science.column.v3;

/** Independent label qualification; a legacy dry dew-point advisory is never a wet-equilibrium label. */
final class V3WaterPhaseQualification {
    enum Grade { DRY_EQUILIBRIUM, WET_EQUILIBRIUM, DRY_SUPERSATURATED, INVALID }
    record Assessment(Grade grade, int wetTrayCount, double maximumDrySaturationRatio, double maximumWetSaturationError) {
        boolean qualified() { return grade == Grade.DRY_EQUILIBRIUM || grade == Grade.WET_EQUILIBRIUM; }
    }

    private V3WaterPhaseQualification() {}

    static Assessment assess(V3NeuralSeed seed) {
        var problem = V3ColumnProblemResolver.resolve(seed.input(), seed.branch());
        var state = seed.stateFor(problem);
        boolean[] wet = seed.wetTrays();
        double[] free = seed.freeWater();
        int wetCount = 0;
        double dryMaximum = 0, wetError = 0;
        for (int node = 1; node < state.nodeCount(); node++) {
            if (wet[node]) wetCount++;
            double water = problem.waterVaporFlowMolPerSecond(node) + (node > 1 ? free[node - 1] : 0);
            if (water == 0 || state.temperatureKelvin(node) >= 640) {
                if (wet[node]) return new Assessment(Grade.INVALID, wetCount, dryMaximum, Double.MAX_VALUE);
                continue;
            }
            double ratio;
            try {
                double saturationPressure = com.wormzjl.createcheme.science.column.v3.thermo.V3WaterProperties
                        .saturationPressurePascal(state.temperatureKelvin(node));
                ratio = problem.nodePressurePascal(node) * water
                        / ((V3WetTraySet.hydrocarbonVaporTotal(state, node) + water) * saturationPressure);
            } catch (IllegalArgumentException invalidTemperature) {
                return new Assessment(Grade.INVALID, wetCount, dryMaximum, Double.MAX_VALUE);
            }
            if (!Double.isFinite(ratio)) return new Assessment(Grade.INVALID, wetCount, dryMaximum, Double.MAX_VALUE);
            if (wet[node]) {
                if (!(free[node] > 0)) return new Assessment(Grade.INVALID, wetCount, dryMaximum, Double.MAX_VALUE);
                wetError = Math.max(wetError, Math.abs(ratio - 1));
            } else dryMaximum = Math.max(dryMaximum, ratio);
        }
        double tolerance = V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE;
        Grade grade = wetError > tolerance ? Grade.INVALID : dryMaximum > 1 + tolerance ? Grade.DRY_SUPERSATURATED
                : wetCount > 0 ? Grade.WET_EQUILIBRIUM : Grade.DRY_EQUILIBRIUM;
        return new Assessment(grade, wetCount, dryMaximum, wetError);
    }
}
