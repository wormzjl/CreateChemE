package com.wormzjl.createcheme.science.thermo.phase;

import com.wormzjl.createcheme.science.fluid.thermo.ThermoDomainViolation;
import com.wormzjl.createcheme.science.thermo.TangentPlaneStability;
import java.util.List;
import java.util.Objects;

/**
 * The answer of an {@link EquilibriumService}: a status, the phases with their amounts over the shared basis, a
 * classification, a coverage grade with its evidence, and diagnostics.
 *
 * <p>Status rules (the constructor enforces them):</p>
 * <ul>
 *   <li>{@link Status#CONVERGED}: a classification other than {@link Classification#NONE}; at least one phase, except
 *   {@link Classification#PURE_COEXISTENCE_UNDERDETERMINED}, which has no phases and exactly two
 *   {@link #coexisting()} states (the split of a pure component at its own coexistence pressure is not determined by
 *   T and P, and none is invented); a coverage grade an answer can have.</li>
 *   <li>{@link Status#UNSUPPORTED}: a typed {@link UnsupportedReason}, no phases, grade {@link CoverageGrade#UNAVAILABLE}.</li>
 *   <li>{@link Status#OUT_OF_DOMAIN}: the {@link ThermoDomainViolation}, no phases, grade {@link CoverageGrade#OUT_OF_DOMAIN}.</li>
 *   <li>{@link Status#NOT_CONVERGED}: no phases, grade {@link CoverageGrade#UNAVAILABLE}, diagnostics of the attempt.</li>
 * </ul>
 * <p>A solid phase must name a crystal of {@link #competition()}; a {@link PhaseCompetition#FLUID_ONLY} result
 * therefore holds no solid and {@link #solidsAssessed()} is false: it is a constrained fluid answer, not a complete
 * equilibrium.</p>
 */
public final class EquilibriumResult {
    public enum Status { CONVERGED, UNSUPPORTED, OUT_OF_DOMAIN, NOT_CONVERGED }

    public enum UnsupportedReason {
        /** The request needs a phase competition the package is not qualified for. */
        PHASE_COMPETITION_NOT_QUALIFIED,
        /** The request carries a species the package does not have. */
        SPECIES_NOT_IN_PACKAGE,
        /** The request needs water chemistry (dissolution, aqueous non-ideality, hydrates) no participation models. */
        WATER_CHEMISTRY_NOT_MODELLED,
        /** The contract admits the request but this service does not implement it yet (named milestone in the detail). */
        NOT_IMPLEMENTED
    }

    public enum Classification {
        /** One phase on the vapour root of a three-root cubic. */
        SINGLE_VAPOR,
        /** One phase on the liquid root of a three-root cubic. */
        SINGLE_LIQUID,
        /**
         * One phase with one physical root that is not a pure supercritical fluid; from P3 its phase label says whether
         * it is vapour-like or liquid-like (phase identification parameter), or {@link PhaseKind#FLUID} for a
         * near-critical split merged into one fluid.
         */
        SINGLE_FLUID,
        /** One pure component above its critical temperature and pressure. */
        SUPERCRITICAL_FLUID,
        /** Two fluid phases; the denser is labelled liquid. */
        VAPOR_LIQUID,
        /** A pure component at its own coexistence pressure: two phases, split undetermined by T and P. */
        PURE_COEXISTENCE_UNDERDETERMINED,
        /** At least one crystal among the phases. */
        SOLID_PRESENT,
        /**
         * Water took part as separate free water (P3): the phases are the gas (hydrocarbon vapour and steam), the
         * hydrocarbon liquid and the free-water liquid, each where present; {@link #freeWater()} carries the rule's
         * pressures and the hydrocarbon phases' own classification.
         */
        FREE_WATER,
        /** No answer. */
        NONE
    }

    /**
     * The separate free-water rule's numbers of a {@link Classification#FREE_WATER} result.
     *
     * @param hydrocarbon the classification of the hydrocarbon phases alone ({@link Classification#NONE} without
     *        hydrocarbons)
     * @param saturationPressure water's saturation pressure at the temperature, Pa ({@code +infinity} above its
     *        critical temperature)
     * @param hydrocarbonPressure the pressure the hydrocarbon vapour is evaluated at, {@code P - p_w}, Pa (the state
     *        pressure when no gas carries steam)
     * @param waterPartialPressure the steam's partial pressure {@code n_w R T / V_gas}, Pa; zero without steam
     * @param liquidWater moles of free liquid water
     * @param waterVapor moles of steam in the gas
     */
    public record FreeWater(Classification hydrocarbon, double saturationPressure, double hydrocarbonPressure,
                            double waterPartialPressure, double liquidWater, double waterVapor) {
        public FreeWater {
            Objects.requireNonNull(hydrocarbon, "hydrocarbon");
            if (hydrocarbon == Classification.FREE_WATER || hydrocarbon == Classification.SOLID_PRESENT
                    || hydrocarbon == Classification.PURE_COEXISTENCE_UNDERDETERMINED) {
                throw new IllegalArgumentException("Not a hydrocarbon classification: " + hydrocarbon);
            }
            if (!(liquidWater >= 0.0) || !(waterVapor >= 0.0) || !(liquidWater + waterVapor > 0.0)
                    || !Double.isFinite(liquidWater + waterVapor) || !(hydrocarbonPressure > 0.0)
                    || !(waterPartialPressure >= 0.0) || !(saturationPressure > 0.0)) {
                throw new IllegalArgumentException("Invalid free-water split");
            }
        }
    }

    /** The plan's coverage vocabulary (section 3, decision D7). */
    public enum CoverageGrade {
        QUALIFIED, ESTIMATED_DECLARED_ERROR, RESEARCH_ONLY, UNAVAILABLE, OUT_OF_DOMAIN;

        /** Whether a converged answer may carry this grade. */
        public boolean answer() { return this == QUALIFIED || this == ESTIMATED_DECLARED_ERROR || this == RESEARCH_ONLY; }
    }

    public record Coverage(CoverageGrade grade, String evidence) {
        public Coverage {
            Objects.requireNonNull(grade, "grade");
            Objects.requireNonNull(evidence, "evidence");
        }
    }

    /**
     * What the calculation did.
     *
     * @param feedVerdict the stability verdict of the feed, {@code null} if no test ran
     * @param feedTangentPlaneDistance its most negative {@code tm}
     * @param flashIterations successive-substitution iterations of the two-phase flash
     * @param extrapolations accepted dominant-eigenvalue extrapolations among them
     * @param rachfordRiceIterations Newton/bisection steps over every Rachford-Rice solve
     * @param lnKChange {@code max |ln K_new - ln K_old|} of the last substitution iteration, or the last
     *        {@code max |ln f_i^V - ln f_i^L|} of the Newton finish when it ran
     * @param fugacityResidual {@code max |ln f_i^L - ln f_i^V|} of the returned phases, present components
     * @param productTangentPlaneDistance the most negative {@code tm} of the stability re-check of the product phases
     * @param kernelEvaluations every equation-of-state call: stability tests, flash, phase states
     * @param derivativeEvaluations the derivative calls among them
     * @param newtonIterations accepted steps of the second-order finish (P3), zero when substitution converged alone
     * @param tieLine {@code sum_i z_i (ln K_i)^2} of the last split (converged or not), {@code NaN} without one
     * @param criticalBand whether the state lies in the declared critical band (P3): a converged answer there is graded
     *        research-only, and a failure there is the typed {@code CRITICAL_BAND} not-converged result
     */
    public record Diagnostics(TangentPlaneStability.Verdict feedVerdict, double feedTangentPlaneDistance,
                              int flashIterations, int extrapolations, int rachfordRiceIterations, double lnKChange,
                              double fugacityResidual, double productTangentPlaneDistance, int kernelEvaluations,
                              int derivativeEvaluations, int newtonIterations, double tieLine, boolean criticalBand) {
        public static final Diagnostics NONE = new Diagnostics(null, Double.NaN, 0, 0, 0, Double.NaN, Double.NaN,
                Double.NaN, 0, 0, 0, Double.NaN, false);
    }

    /** The prefix of the detail of a not-converged result inside the declared critical band. */
    public static final String CRITICAL_BAND = "CRITICAL_BAND";

    private final EquilibriumRequest.Specification specification;
    private final Status status;
    private final UnsupportedReason reason;
    private final String detail;
    private final ThermoDomainViolation violation;
    private final ThermoIdentity identity;
    private final PhaseCompetition competition;
    private final Classification classification;
    private final List<PhaseAmounts> phases;
    private final List<PhaseState> coexisting;
    private final Coverage coverage;
    private final Diagnostics diagnostics;
    private final FreeWater freeWater;

    public EquilibriumResult(EquilibriumRequest.Specification specification, Status status, UnsupportedReason reason,
                             String detail, ThermoDomainViolation violation, ThermoIdentity identity,
                             PhaseCompetition competition, Classification classification, List<PhaseAmounts> phases,
                             List<PhaseState> coexisting, Coverage coverage, Diagnostics diagnostics) {
        this(specification, status, reason, detail, violation, identity, competition, classification, phases, coexisting,
                coverage, diagnostics, null);
    }

    /**
     * @param freeWater the free-water rule's numbers, exactly for a {@link Classification#FREE_WATER} result
     */
    public EquilibriumResult(EquilibriumRequest.Specification specification, Status status, UnsupportedReason reason,
                             String detail, ThermoDomainViolation violation, ThermoIdentity identity,
                             PhaseCompetition competition, Classification classification, List<PhaseAmounts> phases,
                             List<PhaseState> coexisting, Coverage coverage, Diagnostics diagnostics,
                             FreeWater freeWater) {
        this.freeWater = freeWater;
        if ((classification == Classification.FREE_WATER) != (freeWater != null)) {
            throw new IllegalArgumentException("Exactly a free-water result carries the free-water split");
        }
        this.specification = Objects.requireNonNull(specification, "specification");
        this.status = Objects.requireNonNull(status, "status");
        this.reason = reason;
        this.detail = Objects.requireNonNull(detail, "detail");
        this.violation = violation;
        this.identity = Objects.requireNonNull(identity, "identity");
        this.competition = Objects.requireNonNull(competition, "competition");
        this.classification = Objects.requireNonNull(classification, "classification");
        this.phases = List.copyOf(phases);
        this.coexisting = List.copyOf(coexisting);
        this.coverage = Objects.requireNonNull(coverage, "coverage");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        if ((status == Status.UNSUPPORTED) != (reason != null)) throw new IllegalArgumentException("Exactly an unsupported result has a reason");
        if ((status == Status.OUT_OF_DOMAIN) != (violation != null)) throw new IllegalArgumentException("Exactly an out-of-domain result has a violation");
        for (PhaseState state : this.coexisting) {
            if (!state.frozen()) throw new IllegalArgumentException("A result holds frozen phase states");
        }
        if (status == Status.CONVERGED) {
            if (classification == Classification.NONE || !coverage.grade().answer()) {
                throw new IllegalArgumentException("A converged result is classified and graded as an answer");
            }
            boolean underdetermined = classification == Classification.PURE_COEXISTENCE_UNDERDETERMINED;
            if (underdetermined ? !this.phases.isEmpty() || this.coexisting.size() != 2
                    : this.phases.isEmpty() || !this.coexisting.isEmpty()) {
                throw new IllegalArgumentException("Pure coexistence has two coexisting states and no split; any other answer has phases");
            }
            int basis = -1;
            boolean solid = false;
            for (PhaseAmounts phase : this.phases) {
                if (basis >= 0 && phase.amountsView().length != basis) throw new IllegalArgumentException("Phases on different bases");
                basis = phase.amountsView().length;
                if (phase.kind() == PhaseKind.SOLID) {
                    solid = true;
                    if (!competition.crystals().contains(phase.crystal())) {
                        throw new IllegalArgumentException("Crystal " + phase.crystal() + " is outside the competition " + competition);
                    }
                }
            }
            if (solid != (classification == Classification.SOLID_PRESENT)) {
                throw new IllegalArgumentException("SOLID_PRESENT exactly when a crystal is among the phases");
            }
        } else {
            if (!this.phases.isEmpty() || !this.coexisting.isEmpty() || classification != Classification.NONE) {
                throw new IllegalArgumentException("Only a converged result has phases and a classification");
            }
            CoverageGrade expected = status == Status.OUT_OF_DOMAIN ? CoverageGrade.OUT_OF_DOMAIN : CoverageGrade.UNAVAILABLE;
            if (coverage.grade() != expected) throw new IllegalArgumentException(status + " is graded " + expected);
        }
    }

    public static EquilibriumResult unsupported(EquilibriumRequest.Specification specification, ThermoIdentity identity,
                                                PhaseCompetition competition, UnsupportedReason reason, String detail) {
        return new EquilibriumResult(specification, Status.UNSUPPORTED, Objects.requireNonNull(reason), detail, null,
                identity, competition, Classification.NONE, List.of(), List.of(),
                new Coverage(CoverageGrade.UNAVAILABLE, reason + ": " + detail), Diagnostics.NONE);
    }

    public static EquilibriumResult outOfDomain(EquilibriumRequest.Specification specification, ThermoIdentity identity,
                                                PhaseCompetition competition, ThermoDomainViolation violation) {
        return new EquilibriumResult(specification, Status.OUT_OF_DOMAIN, null, violation.getMessage(), violation,
                identity, competition, Classification.NONE, List.of(), List.of(),
                new Coverage(CoverageGrade.OUT_OF_DOMAIN, violation.getMessage()), Diagnostics.NONE);
    }

    public static EquilibriumResult notConverged(EquilibriumRequest.Specification specification, ThermoIdentity identity,
                                                 PhaseCompetition competition, String detail, Diagnostics diagnostics) {
        return new EquilibriumResult(specification, Status.NOT_CONVERGED, null, detail, null, identity, competition,
                Classification.NONE, List.of(), List.of(), new Coverage(CoverageGrade.UNAVAILABLE, detail), diagnostics);
    }

    public EquilibriumRequest.Specification specification() { return specification; }
    public Status status() { return status; }
    public boolean converged() { return status == Status.CONVERGED; }
    /** The typed reason of an {@link Status#UNSUPPORTED} result, {@code null} otherwise. */
    public UnsupportedReason reason() { return reason; }
    public String detail() { return detail; }
    /** The domain violation of an {@link Status#OUT_OF_DOMAIN} result, {@code null} otherwise. */
    public ThermoDomainViolation violation() { return violation; }
    public ThermoIdentity identity() { return identity; }
    /** The competition the answer covers; a fluid-only answer says nothing about solids. */
    public PhaseCompetition competition() { return competition; }
    /** Whether solid phases took part: false for every {@link PhaseCompetition#FLUID_ONLY} answer. */
    public boolean solidsAssessed() { return status == Status.CONVERGED && !competition.crystals().isEmpty(); }
    public Classification classification() { return classification; }
    public List<PhaseAmounts> phases() { return phases; }
    /** The two coexisting states of {@link Classification#PURE_COEXISTENCE_UNDERDETERMINED}, empty otherwise. */
    public List<PhaseState> coexisting() { return coexisting; }
    public Coverage coverage() { return coverage; }
    public Diagnostics diagnostics() { return diagnostics; }
    /** The free-water rule's numbers of a {@link Classification#FREE_WATER} result, {@code null} otherwise. */
    public FreeWater freeWater() { return freeWater; }

    /**
     * {@code max_i |sum_p n_(p,i) - overall_i| / overall_i} over the basis, with the total overall amount as the scale
     * of a component the overall lacks (so any phase amount of it counts).
     *
     * @throws IllegalStateException without a phase split (not converged, or pure coexistence)
     */
    public double conservationDefect(double[] overall) {
        requireSplit();
        if (overall.length != phases.get(0).amountsView().length) throw new IllegalArgumentException("Overall basis mismatch");
        double total = 0.0;
        for (double amount : overall) total += amount;
        double worst = 0.0;
        for (int i = 0; i < overall.length; i++) {
            double sum = 0.0;
            for (PhaseAmounts phase : phases) sum += phase.amount(i);
            double scale = overall[i] > 0.0 ? overall[i] : total;
            worst = Math.max(worst, Math.abs(sum - overall[i]) / scale);
        }
        return worst;
    }

    /** Moles over every phase. */
    public double totalAmount() {
        requireSplit();
        double total = 0.0;
        for (PhaseAmounts phase : phases) total += phase.total();
        return total;
    }
    /** {@code sum_p n_p h_p}, J. */
    public double enthalpy() { return sum(Property.ENTHALPY); }
    /** {@code sum_p n_p u_p}, J. */
    public double internalEnergy() { return sum(Property.INTERNAL_ENERGY); }
    /** {@code sum_p n_p s_p}, J/K. */
    public double entropy() { return sum(Property.ENTROPY); }
    /** {@code sum_p n_p g_p}, J. */
    public double gibbsEnergy() { return sum(Property.GIBBS); }
    /** {@code sum_p n_p v_p}, m3. */
    public double volume() { return sum(Property.VOLUME); }
    public double molarEnthalpy() { return enthalpy() / totalAmount(); }
    public double molarInternalEnergy() { return internalEnergy() / totalAmount(); }
    public double molarEntropy() { return entropy() / totalAmount(); }
    public double molarVolume() { return volume() / totalAmount(); }

    private enum Property { ENTHALPY, INTERNAL_ENERGY, ENTROPY, GIBBS, VOLUME }

    private double sum(Property property) {
        requireSplit();
        double sum = 0.0;
        for (PhaseAmounts phase : phases) {
            PhaseState state = phase.state();
            double molar = switch (property) {
                case ENTHALPY -> state.molarEnthalpy();
                case INTERNAL_ENERGY -> state.molarInternalEnergy();
                case ENTROPY -> state.molarEntropy();
                case GIBBS -> state.molarGibbsEnergy();
                case VOLUME -> state.molarVolume();
            };
            sum += phase.total() * molar;
        }
        return sum;
    }

    private void requireSplit() {
        if (status != Status.CONVERGED) throw new IllegalStateException("No phases: " + status + " (" + detail + ")");
        if (phases.isEmpty()) throw new IllegalStateException("Pure coexistence: the split is undetermined by T and P");
    }

    @Override
    public String toString() {
        return "EquilibriumResult[" + specification + " " + status + (reason == null ? "" : " " + reason) + ", "
                + classification + ", " + phases.size() + " phases, " + coverage.grade() + ", " + detail + ']';
    }
}
