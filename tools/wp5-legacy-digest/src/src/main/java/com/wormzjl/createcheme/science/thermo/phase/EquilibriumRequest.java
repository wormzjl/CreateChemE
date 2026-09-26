package com.wormzjl.createcheme.science.thermo.phase;

import java.util.List;
import java.util.Objects;

/**
 * One equilibrium question: a specification pair, the amounts of named species, and the phase competition asked for.
 *
 * <p>The species are named, not positional, so a request can name a species the package lacks and be told so
 * ({@link EquilibriumResult.UnsupportedReason#SPECIES_NOT_IN_PACKAGE}); a species with zero amount is absent and may be
 * named whether the package has it or not. A request over exactly the contract's basis list is the fast path.</p>
 *
 * @param specification which pair {@code first}, {@code second} is: (T, P) K and Pa; (P, H) Pa and J; (U, V) J and m3
 * @param amounts mole numbers per species: finite, nonnegative, some positive
 */
public record EquilibriumRequest(Specification specification, double first, double second, List<String> species,
                                 double[] amounts, PhaseCompetition competition) {
    public enum Specification { TP, PH, UV }

    public EquilibriumRequest {
        Objects.requireNonNull(specification, "specification");
        Objects.requireNonNull(competition, "competition");
        if (!Double.isFinite(first) || !Double.isFinite(second)) throw new IllegalArgumentException("Specification must be finite");
        if (specification == Specification.TP && (!(first > 0.0) || !(second > 0.0))) {
            throw new IllegalArgumentException("Temperature and pressure must be positive");
        }
        if (specification == Specification.PH && !(first > 0.0)) throw new IllegalArgumentException("Pressure must be positive");
        if (specification == Specification.UV && !(second > 0.0)) throw new IllegalArgumentException("Volume must be positive");
        species = List.copyOf(species);
        if (species.stream().distinct().count() != species.size()) throw new IllegalArgumentException("Duplicate species");
        amounts = amounts.clone();
        if (amounts.length != species.size()) throw new IllegalArgumentException("One amount per species");
        double total = 0.0;
        for (double amount : amounts) {
            if (!Double.isFinite(amount) || amount < 0.0) throw new IllegalArgumentException("Amounts must be finite and nonnegative");
            total += amount;
        }
        if (!(total > 0.0)) throw new IllegalArgumentException("A request needs material");
    }

    public static EquilibriumRequest tp(double temperature, double pressure, List<String> species, double[] amounts,
                                        PhaseCompetition competition) {
        return new EquilibriumRequest(Specification.TP, temperature, pressure, species, amounts, competition);
    }

    public static EquilibriumRequest ph(double pressure, double enthalpy, List<String> species, double[] amounts,
                                        PhaseCompetition competition) {
        return new EquilibriumRequest(Specification.PH, pressure, enthalpy, species, amounts, competition);
    }

    public static EquilibriumRequest uv(double internalEnergy, double volume, List<String> species, double[] amounts,
                                        PhaseCompetition competition) {
        return new EquilibriumRequest(Specification.UV, internalEnergy, volume, species, amounts, competition);
    }

    @Override public double[] amounts() { return amounts.clone(); }
    /** The amounts themselves; the caller must not mutate them. */
    public double[] amountsView() { return amounts; }

    public double temperature() { require(Specification.TP); return first; }
    public double pressure() {
        if (specification == Specification.UV) throw new IllegalStateException("A UV request specifies no pressure");
        return specification == Specification.TP ? second : first;
    }
    public double enthalpy() { require(Specification.PH); return second; }
    public double internalEnergy() { require(Specification.UV); return first; }
    public double volume() { require(Specification.UV); return second; }

    private void require(Specification expected) {
        if (specification != expected) throw new IllegalStateException("A " + specification + " request has no such value");
    }
}
