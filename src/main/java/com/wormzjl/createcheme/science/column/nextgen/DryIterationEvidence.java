package com.wormzjl.createcheme.science.column.nextgen;

/** A compact immutable checkpoint from the canonical inner iteration. */
public record DryIterationEvidence(
        int outerIteration,
        int innerIteration,
        double sumRatesResidual,
        int limitingSumRatesNode,
        String limitingSumRatesPhase,
        double trialLiquidTotal,
        double trialVaporTotal,
        double rawLiquidTotal,
        double rawVaporTotal,
        double minimumComponentFlow,
        int minimumComponentNode,
        int minimumComponent,
        double minimumTemperatureKelvin,
        double maximumTemperatureKelvin,
        int mergedRootNodes,
        double minimumRootSeparation,
        double minimumLocalK,
        double maximumLocalK,
        int pivotComponent,
        int pivotRow,
        double minimumPositivePivot,
        double energyResidual,
        double acceptedEnergyFactor) {
    public DryIterationEvidence {
        if (outerIteration < 0 || innerIteration < 0 || limitingSumRatesNode < -1 || minimumComponentNode < -1
                || minimumComponent < -1 || mergedRootNodes < 0 || pivotComponent < -1 || pivotRow < -1) {
            throw new IllegalArgumentException("Iteration evidence indices are invalid");
        }
        if (limitingSumRatesPhase == null || limitingSumRatesPhase.length() > 16) {
            throw new IllegalArgumentException("Iteration evidence phase is invalid");
        }
        if (!Double.isFinite(sumRatesResidual) || !Double.isFinite(trialLiquidTotal) || !Double.isFinite(trialVaporTotal)
                || !Double.isFinite(rawLiquidTotal) || !Double.isFinite(rawVaporTotal) || !Double.isFinite(minimumComponentFlow)
                || !Double.isFinite(minimumTemperatureKelvin) || !Double.isFinite(maximumTemperatureKelvin)
                || !Double.isFinite(minimumLocalK) || !Double.isFinite(maximumLocalK) || !Double.isFinite(energyResidual)
                || !Double.isFinite(acceptedEnergyFactor)) {
            throw new IllegalArgumentException("Iteration evidence must be finite");
        }
        if (!(minimumRootSeparation >= 0.0) && !Double.isInfinite(minimumRootSeparation)) {
            throw new IllegalArgumentException("Root separation is invalid");
        }
        if (!(minimumPositivePivot > 0.0) && !Double.isNaN(minimumPositivePivot)) {
            throw new IllegalArgumentException("Positive-pivot evidence is invalid");
        }
    }
}
