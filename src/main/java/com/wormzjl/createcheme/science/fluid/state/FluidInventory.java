package com.wormzjl.createcheme.science.fluid.state;

import java.util.Objects;

/** Conserved state of a rigid adiabatic reservoir. Temperature, pressure, and phase allocations are derived. */
public final class FluidInventory {
    private final double volumeCubicMetres;
    private final double internalEnergyJoules;
    private final double[] moles;
    private final EnergyReference energyReference;

    public FluidInventory(double volumeCubicMetres, double[] moles, double internalEnergyJoules,
                          EnergyReference energyReference) {
        this.energyReference = Objects.requireNonNull(energyReference, "energyReference");
        this.moles = moles.clone();
        this.volumeCubicMetres = volumeCubicMetres;
        this.internalEnergyJoules = internalEnergyJoules;
        if (!Double.isFinite(volumeCubicMetres) || volumeCubicMetres <= 0
                || !Double.isFinite(internalEnergyJoules) || moles.length != energyReference.components().size()) {
            throw new IllegalArgumentException("Invalid volume, energy, or component basis");
        }
        double total = 0;
        for (double amount : this.moles) {
            if (!Double.isFinite(amount) || amount < 0) throw new IllegalArgumentException("Invalid component inventory");
            total += amount;
        }
        if (!Double.isFinite(total) || (total == 0 && internalEnergyJoules != 0)) {
            throw new IllegalArgumentException("Empty fluid has zero energy; inventory totals must be finite");
        }
    }
    public double volumeCubicMetres() { return volumeCubicMetres; }
    public double internalEnergyJoules() { return internalEnergyJoules; }
    public double[] moles() { return moles.clone(); }
    public double moles(int component) { return moles[component]; }
    public EnergyReference energyReference() { return energyReference; }
    public boolean isEmpty() {
        for (double amount : moles) if (amount != 0) return false;
        return true;
    }
    public FluidInventory rebase(EnergyReference target) {
        return new FluidInventory(volumeCubicMetres, moles,
                energyReference.rebase(internalEnergyJoules, moles, target), target);
    }
}
