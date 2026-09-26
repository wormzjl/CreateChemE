package com.wormzjl.createcheme.science.thermo.phase;

import java.util.Objects;

/**
 * One phase of an equilibrium result: its label, its crystal (for a solid), its state and the mole numbers it holds
 * over the contract's shared basis. The same species conserves across every phase through this one basis.
 *
 * <p>{@link #kind()} is the equilibrium's label: in a two-phase fluid split the denser phase is
 * {@link PhaseKind#LIQUID} and the other {@link PhaseKind#VAPOR}; a single phase is labelled by its classification.
 * {@link PhaseState#kind()} of {@link #state()} keeps the root situation the evaluator met, which can be
 * {@link PhaseKind#FLUID} for a phase the result labels liquid.</p>
 *
 * @param amounts mole numbers over the contract basis: finite, nonnegative, some positive
 * @param state a frozen state at the same temperature and pressure
 */
public record PhaseAmounts(PhaseKind kind, String crystal, double temperature, double pressure, double[] amounts,
                           PhaseState state) {
    public PhaseAmounts {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(state, "state");
        if ((kind == PhaseKind.SOLID) != (crystal != null)) {
            throw new IllegalArgumentException("A solid phase names its crystal and a fluid phase names none");
        }
        if (!state.frozen()) throw new IllegalArgumentException("A result holds frozen phase states");
        if ((kind == PhaseKind.SOLID) != (state.kind() == PhaseKind.SOLID)
                || kind == PhaseKind.SOLID && !crystal.equals(state.crystal())) {
            throw new IllegalArgumentException("Phase label and state disagree on the crystal");
        }
        if (temperature != state.temperature() || pressure != state.pressure()) {
            throw new IllegalArgumentException("Phase state is at another temperature or pressure");
        }
        amounts = amounts.clone();
        double total = 0.0;
        for (double amount : amounts) {
            if (!Double.isFinite(amount) || amount < 0.0) throw new IllegalArgumentException("Phase amounts must be finite and nonnegative");
            total += amount;
        }
        if (!(total > 0.0)) throw new IllegalArgumentException("A phase holds material");
    }

    @Override public double[] amounts() { return amounts.clone(); }
    /** The amounts themselves; the caller must not mutate them. */
    public double[] amountsView() { return amounts; }
    public double amount(int component) { return amounts[component]; }
    /** Moles in the phase. */
    public double total() {
        double total = 0.0;
        for (double amount : amounts) total += amount;
        return total;
    }
}
