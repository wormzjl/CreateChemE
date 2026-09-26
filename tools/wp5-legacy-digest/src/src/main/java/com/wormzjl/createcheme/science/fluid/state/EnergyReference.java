package com.wormzjl.createcheme.science.fluid.state;

import java.util.List;
import java.util.Objects;

/** Immutable energy datum for one ordered component basis. Sensible zero is not formation-enthalpy data. */
public final class EnergyReference {
    private final String revision;
    private final List<String> components;
    private final double[] offsetsJoulesPerMole;
    private final boolean formationDataQualified;

    public EnergyReference(String revision, List<String> components, double[] offsetsJoulesPerMole,
                           boolean formationDataQualified) {
        this.revision = Objects.requireNonNull(revision, "revision");
        this.components = List.copyOf(components);
        this.offsetsJoulesPerMole = offsetsJoulesPerMole.clone();
        this.formationDataQualified = formationDataQualified;
        if (revision.isBlank() || components.isEmpty() || components.size() != offsetsJoulesPerMole.length
                || components.stream().anyMatch(String::isBlank) || components.stream().distinct().count() != components.size()) {
            throw new IllegalArgumentException("Invalid energy-reference identity or basis");
        }
        for (double offset : this.offsetsJoulesPerMole) {
            if (!Double.isFinite(offset)) throw new IllegalArgumentException("Energy offsets must be finite");
        }
    }

    public static EnergyReference sensible(List<String> components) {
        return new EnergyReference("createcheme:sensible-298.15K-v1", components, new double[components.size()], false);
    }
    public String revision() { return revision; }
    public List<String> components() { return components; }
    public double offsetJoulesPerMole(int component) { return offsetsJoulesPerMole[component]; }
    public boolean formationDataQualified() { return formationDataQualified; }

    public double rebase(double energyJoules, double[] moles, EnergyReference target) {
        Objects.requireNonNull(target, "target");
        if (!components.equals(target.components) || moles.length != components.size() || !Double.isFinite(energyJoules)) {
            throw new IllegalArgumentException("Energy migration requires matching component identities and finite energy");
        }
        double rebased = energyJoules;
        for (int i = 0; i < moles.length; i++) {
            if (!Double.isFinite(moles[i]) || moles[i] < 0) throw new IllegalArgumentException("Invalid mole inventory");
            rebased = Math.fma(moles[i], target.offsetsJoulesPerMole[i] - offsetsJoulesPerMole[i], rebased);
        }
        if (!Double.isFinite(rebased)) throw new IllegalArgumentException("Energy migration overflow");
        return rebased;
    }
}
