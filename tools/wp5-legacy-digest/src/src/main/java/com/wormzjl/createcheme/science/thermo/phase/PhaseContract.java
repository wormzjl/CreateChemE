package com.wormzjl.createcheme.science.thermo.phase;

import com.wormzjl.createcheme.science.fluid.thermo.FluidDomain;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What a package promises the equilibrium engine: its shared component basis, how water takes part, which phase
 * competitions it is qualified for, which species need water chemistry, where it may be evaluated, how good a fluid
 * answer inside that domain is, and its thermodynamic identity.
 *
 * <p>Rules the constructor enforces:</p>
 * <ul>
 *   <li>Water: {@link WaterParticipation#NONE} names no water component; {@link WaterParticipation#SEPARATE_FREE_WATER}
 *   names one in the basis. {@link #waterChemistrySpecies()} lie in the basis and are not the water itself.</li>
 *   <li>Competitions: at least one; none with gas hydrates, because no P2 water participation models them.</li>
 *   <li>The domain covers exactly the basis.</li>
 *   <li>The fluid coverage grade is one an answer can have ({@code QUALIFIED}, {@code ESTIMATED_DECLARED_ERROR} or
 *   {@code RESEARCH_ONLY}).</li>
 * </ul>
 *
 * @param components the shared conserved basis, EOS components and water (if any) alike
 * @param waterComponent the id of water in {@code components}, {@code null} for {@link WaterParticipation#NONE}
 * @param qualifiedCompetitions each competition the package is qualified for, as a whole
 * @param waterChemistrySpecies species whose presence together with water needs aqueous chemistry (ammonia and the
 *        like), refused under {@link WaterParticipation#SEPARATE_FREE_WATER}
 * @param fluidCoverage the grade and evidence of a converged fluid-phase answer inside the domain
 */
public record PhaseContract(String packageId, ThermoIdentity identity, List<String> components,
                            WaterParticipation water, String waterComponent,
                            Set<PhaseCompetition> qualifiedCompetitions, Set<String> waterChemistrySpecies,
                            PhaseDomain domain, EquilibriumResult.Coverage fluidCoverage) {
    public PhaseContract {
        Objects.requireNonNull(packageId, "packageId");
        Objects.requireNonNull(identity, "identity");
        components = List.copyOf(components);
        if (components.isEmpty() || components.stream().distinct().count() != components.size()) {
            throw new IllegalArgumentException("A contract needs a nonempty basis without duplicates");
        }
        Objects.requireNonNull(water, "water");
        if ((water == WaterParticipation.NONE) != (waterComponent == null)
                || waterComponent != null && !components.contains(waterComponent)) {
            throw new IllegalArgumentException("Separate free water names its basis component; no water names none");
        }
        qualifiedCompetitions = Set.copyOf(qualifiedCompetitions);
        if (qualifiedCompetitions.isEmpty()) throw new IllegalArgumentException("A package qualifies at least one competition");
        for (PhaseCompetition competition : qualifiedCompetitions) {
            if (competition.hydrates()) {
                throw new IllegalArgumentException("No water participation of P2 models hydrates; a package cannot qualify them");
            }
        }
        waterChemistrySpecies = Set.copyOf(waterChemistrySpecies);
        for (String species : waterChemistrySpecies) {
            if (!components.contains(species) || species.equals(waterComponent)) {
                throw new IllegalArgumentException("Water-chemistry species must be basis components other than water: " + species);
            }
        }
        Objects.requireNonNull(domain, "domain");
        if (!domain.components().equals(components)) throw new IllegalArgumentException("Domain basis differs from the contract basis");
        Objects.requireNonNull(fluidCoverage, "fluidCoverage");
        if (!fluidCoverage.grade().answer()) throw new IllegalArgumentException("The fluid coverage must be a grade an answer can have");
    }

    /**
     * The fluid network package: its PR78 components followed by {@code Water} as separate free water, qualified for
     * fluid-only competition, the network's {@link FluidDomain}, no water-chemistry species (the bundled packages hold
     * hydrocarbons and nitrogen only), graded estimated-with-declared-error.
     */
    public static PhaseContract forNetworkPackage(MaterialCatalog catalog, String packageId, String spineFingerprint) {
        var propertyPackage = catalog.requirePackage(packageId);
        List<String> basis = new ArrayList<>(propertyPackage.components());
        basis.add("Water");
        var identity = ThermoIdentity.of(catalog, packageId, CubicPhaseEvaluator.FAMILY, spineFingerprint,
                WaterParticipation.SEPARATE_FREE_WATER);
        var domain = PhaseDomain.of(FluidDomain.of(catalog, packageId), basis);
        var coverage = new EquilibriumResult.Coverage(EquilibriumResult.CoverageGrade.ESTIMATED_DECLARED_ERROR,
                "PR78 with constant volume translation, every phase at the state pressure (P1: liquid density within "
                        + "2.6 % mean per isobar to 10 MPa for N2, CH4, C2H6, CO2 outside the critical band); the "
                        + "declared critical band is graded research-only by the engine (P3); the package's advisory evidence applies");
        return new PhaseContract(packageId, identity, basis, WaterParticipation.SEPARATE_FREE_WATER, "Water",
                Set.of(PhaseCompetition.FLUID_ONLY), Set.of(), domain, coverage);
    }

    public int index(String component) { return components.indexOf(component); }
    /** The water slot of the basis, -1 without water. */
    public int waterIndex() { return waterComponent == null ? -1 : components.indexOf(waterComponent); }
    public boolean qualifies(PhaseCompetition competition) { return qualifiedCompetitions.contains(competition); }
}
