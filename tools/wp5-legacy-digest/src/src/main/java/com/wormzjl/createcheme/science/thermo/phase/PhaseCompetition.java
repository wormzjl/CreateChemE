package com.wormzjl.createcheme.science.thermo.phase;

import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Which phases compete in an equilibrium calculation: the fluid phases always, plus the named pure crystals, plus
 * gas hydrates if {@link #hydrates()}.
 *
 * <p>A package declares the competitions it is qualified for ({@link PhaseContract#qualifiedCompetitions()}); a
 * request names the competition it needs, and a request outside the package's set is refused
 * ({@link EquilibriumResult.UnsupportedReason#PHASE_COMPETITION_NOT_QUALIFIED}) rather than answered with fewer
 * phases. Competitions are declared as whole sets, never as independent per-crystal switches, so a package cannot admit
 * two crystals separately without admitting (and so qualifying) their joint competition.</p>
 *
 * <p>{@link #FLUID_ONLY} is an explicitly constrained calculation: its result says nothing about solids and is never
 * labelled a complete equilibrium ({@link EquilibriumResult#solidsAssessed()} is false).</p>
 *
 * @param crystals crystal ids, sorted; empty for fluid phases only
 * @param hydrates whether gas hydrates compete; water chemistry that no P2 water participation models
 */
public record PhaseCompetition(SortedSet<String> crystals, boolean hydrates) {
    public static final PhaseCompetition FLUID_ONLY = new PhaseCompetition(new TreeSet<>(), false);

    public PhaseCompetition {
        TreeSet<String> copy = new TreeSet<>();
        for (String crystal : Objects.requireNonNull(crystals, "crystals")) {
            if (crystal == null || crystal.isBlank()) throw new IllegalArgumentException("Blank crystal id");
            copy.add(crystal);
        }
        crystals = java.util.Collections.unmodifiableSortedSet(copy);
    }

    /** The fluid phases and these pure crystals. */
    public static PhaseCompetition withCrystals(String... crystals) {
        return new PhaseCompetition(new TreeSet<>(Set.of(crystals)), false);
    }

    /** The fluid phases, these crystals and gas hydrates. */
    public static PhaseCompetition withHydrates(String... crystals) {
        return new PhaseCompetition(new TreeSet<>(Set.of(crystals)), true);
    }

    public boolean fluidOnly() { return crystals.isEmpty() && !hydrates; }

    @Override
    public String toString() {
        if (fluidOnly()) return "fluid-only";
        return "fluid+" + String.join("+", crystals) + (hydrates ? "+hydrates" : "");
    }
}
