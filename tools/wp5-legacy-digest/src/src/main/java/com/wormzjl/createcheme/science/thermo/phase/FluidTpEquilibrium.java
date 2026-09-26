package com.wormzjl.createcheme.science.thermo.phase;

import com.wormzjl.createcheme.science.fluid.thermo.ThermoDomainViolation;
import com.wormzjl.createcheme.science.thermo.IdealGasFunction;
import com.wormzjl.createcheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.createcheme.science.thermo.PengRobinsonKernel.Root;
import com.wormzjl.createcheme.science.thermo.PhaseRoot;
import com.wormzjl.createcheme.science.thermo.TangentPlaneStability;
import com.wormzjl.createcheme.science.thermo.phase.EquilibriumRequest.Specification;
import com.wormzjl.createcheme.science.thermo.phase.EquilibriumResult.Classification;
import com.wormzjl.createcheme.science.thermo.phase.EquilibriumResult.Diagnostics;
import com.wormzjl.createcheme.science.thermo.phase.EquilibriumResult.UnsupportedReason;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * TP equilibrium of fluid phases on a {@link CubicPhaseEvaluator} (P2, completed in P3 work package WP6a): the
 * hydrocarbon phases by stability test and flash, and water as the fluid network's separate free water.
 *
 * <p>Hydrocarbon phases:</p>
 * <ol>
 *   <li>Contract checks ({@link EquilibriumService}); a request asking for crystals the package is qualified for is
 *   {@link UnsupportedReason#NOT_IMPLEMENTED} (P4).</li>
 *   <li>Tangent-plane stability of the feed ({@link TangentPlaneStability} on the evaluator's kernel; the constant
 *   translation cancels from it at one pressure).</li>
 *   <li>Stable: one phase on the feed's lower-Gibbs root, classified from the root situation. A pure component with
 *   three roots whose two fugacities agree to within {@link Settings#coexistenceTolerance()} in relative pressure
 *   (first order: {@code |ln phi_L - ln phi_V| / (Z_V - Z_L)}) is
 *   {@link Classification#PURE_COEXISTENCE_UNDERDETERMINED}: both states, no split. A single phase with one physical
 *   root is labelled liquid or vapour by its phase identification parameter ({@link PhaseIdentification}), except a
 *   pure component above its critical temperature and pressure, {@link Classification#SUPERCRITICAL_FLUID} labelled
 *   {@link PhaseKind#FLUID}.</li>
 *   <li>Unstable: two-phase Rachford-Rice with successive substitution on {@code ln K_i = ln phi_i^L - ln phi_i^V},
 *   started from the stability test's stationary composition, each phase on its own lower-Gibbs root, with a
 *   dominant-eigenvalue extrapolation every {@link Settings#accelerationCycle()} plain steps; converged when a plain
 *   step moves no {@code ln K_i} by {@link Settings#lnKTolerance()}.</li>
 *   <li>Second-order finish (P3): when substitution has run {@link Settings#slowSubstitutionSteps()} steps with the
 *   dominant eigenvalue estimate above {@link Settings#slowEigenvalue()}, after {@link Settings#substitutionLimit()}
 *   steps, or when it collapses onto the feed or loses the split, Newton minimises the Gibbs energy over the vapour mole
 *   numbers {@code v_i} (liquid {@code l_i = z_i - v_i}, both carried so a trace keeps its precision): gradient
 *   {@code g_i = ln f_i^V - ln f_i^L}, Hessian
 *   {@code H_ij = sum over both phases (delta_ij/n_i - 1/N + Phi_ij/N)} with the kernel's per-mole
 *   {@code Phi_ij = d ln phi_i/d n_j} (symmetrised), scaled by {@code s_i = sqrt(v_i l_i / z_i)} and shifted by
 *   {@code mu I} until Cholesky succeeds, the step cut so no amount shrinks below a tenth of itself, and an Armijo line
 *   search on {@code Delta G/RT = sum_i v_i (ln f_i^V - d_i) + l_i (ln f_i^L - d_i)} measured from the feed's
 *   {@code d_i = ln f_i(z)}, which it keeps negative: the start is a split below the feed's Gibbs energy (the
 *   substitution iterate, or else the stability test's stationary composition in a small amount), so the finish can
 *   never reach the trivial solution. Converged at {@code max |g_i| < }{@link Settings#fugacityTolerance()}.</li>
 *   <li>The phase amounts are split per component: the smaller share is computed directly (capped at {@code n_i}) and
 *   the larger is {@code n_i} minus it, so they conserve each component to rounding, stay nonnegative, and a trace
 *   share keeps its relative precision.</li>
 *   <li>Near-critical merging: a converged split with {@code sum z_i (ln K_i)^2 <} {@link Settings#mergeTieLine()}
 *   whose molar volumes differ by less than {@link Settings#mergeVolumeGap()} (relative to their mean) is one fluid:
 *   {@link Classification#SINGLE_FLUID} labelled {@link PhaseKind#FLUID}, inside the critical band, its detail saying
 *   it was merged. Otherwise both products are re-tested for stability; an unstable product (a third phase, or a wrong
 *   split) is {@link EquilibriumResult.Status#NOT_CONVERGED}.</li>
 * </ol>
 *
 * <p>Declared critical band (plan P3 section 6.2): a pure fluid (one component at least 0.95 of the amount) at reduced
 * temperature and pressure in [0.95, 1.1] x [0.8, 1.5], [0.90, 0.95] x [0.58, 0.74] or [1.05, 1.2] x [2, 3]; a split
 * with {@code sum z_i (ln K_i)^2 <} {@link Settings#bandTieLine()}; a single mixture phase whose stability test met a
 * stationary point other than the feed within {@link Settings#bandStationaryDistance()} of it
 * ({@link TangentPlaneStability.Result#nearestStationaryDistance()}); a failed split whose last iterate or stability
 * seed was that short. Inside it a converged answer is graded {@link EquilibriumResult.CoverageGrade#RESEARCH_ONLY}, and
 * a failure is the typed not-converged result whose detail starts with {@link EquilibriumResult#CRITICAL_BAND} and whose
 * diagnostics say {@code criticalBand}. No split is fabricated, and no single phase is reported as stable when the test
 * said unstable (a merged split says so in its detail).</p>
 *
 * <p>Free water ({@link WaterParticipation#SEPARATE_FREE_WATER} with a {@link FreeWaterModel}): the fluid network's rule
 * as {@code FluidThermodynamics.flashTP} encodes it (read at commit {@code 5100233}; restated here, constants below):
 * the hydrocarbon liquid at the state pressure {@code P}, the hydrocarbon vapour at its partial pressure
 * {@code pc = P - p_w}, ideal steam sharing the gas volume, liquid water from the water model at {@code P}. Without
 * hydrocarbons the water is all liquid when {@code P >= p_sat} and all steam otherwise. With both, when
 * {@code P > p_sat + 1e-6 Pa} the hydrocarbons are split at {@code (P, P - p_sat)} and the steam that saturates the gas,
 * {@code p_sat V_gas/(R T)}, is taken from the water if there is that much (the rest is free water); otherwise all water
 * is steam and {@code pc} solves {@code pc + n_w R T / V_gas(pc) = P} by the network's bisection on
 * {@code [1e-6 Pa, P]} (70 halvings, stop at {@code 1e-8 P}, accepted within {@code 1e-6 P}). A steam partial pressure
 * above the model's qualified limit is {@code OUT_OF_DOMAIN} (the network refuses it too). The hydrocarbon split with
 * branch pressures uses {@link TangentPlaneStability}'s per-branch test and the vapour branch's shift
 * {@code ln(pc/P) + (pc - P) c_i/(R T)} in the flash (the translation does not cancel between two pressures), with the
 * vapour on the vapour root at {@code pc} and the liquid on the liquid root at {@code P}.</p>
 *
 * <p>Deterministic (fixed arithmetic order, no randomness; a reused {@link Workspace} carries nothing between calls but
 * prepared temperatures, recomputed bit-identically) and allocation-light: the iterations allocate nothing; a call
 * allocates the stability results and its own result.</p>
 */
public final class FluidTpEquilibrium implements EquilibriumService {
    /** Largest {@code ln K} change one extrapolation may add. */
    private static final double MAXIMUM_EXTRAPOLATION = 20.0;
    /** Keeps {@code exp(ln K)} a finite, positive normal double. */
    private static final double LOG_K_BOUND = 700.0;
    private static final int MAXIMUM_RACHFORD_RICE_STEPS = 200;
    private static final String CONVERGED = "converged";
    private static final double R = PengRobinsonKernel.GAS_CONSTANT;
    /** Fraction of the predicted decrease an accepted Newton step must achieve. */
    private static final double ARMIJO = 1.0e-4;
    private static final int NEWTON_HALVINGS = 40;
    /** One Newton step leaves every mole number above {@code 1 - FRACTION_TO_BOUNDARY} of itself. */
    private static final double FRACTION_TO_BOUNDARY = 0.9;
    /** Roundoff allowance on the Gibbs-energy decrease, relative to the magnitude of its summands. */
    private static final double GIBBS_ROUNDOFF = 1.0e-15;
    /** Halvings of the amount of the stationary phase when a Newton start is built from the stability test. */
    private static final int START_HALVINGS = 60;

    // The fluid network's free-water rule (FluidThermodynamics.flashTP and HydrocarbonModel at commit 5100233).
    /** Liquid water with hydrocarbons needs {@code P > p_sat + SATURATION_MARGIN}. */
    public static final double SATURATION_MARGIN = 1.0e-6;
    /** Lower end of the hydrocarbon partial-pressure bisection (HydrocarbonModel.VAPOR_PARTIAL_PRESSURE_FLOOR). */
    public static final double PARTIAL_PRESSURE_FLOOR = 1.0e-6;
    public static final int PARTIAL_PRESSURE_BISECTIONS = 70;
    /** The bisection stops at {@code |pc + p_w - P| < PARTIAL_PRESSURE_TOLERANCE P}. */
    public static final double PARTIAL_PRESSURE_TOLERANCE = 1.0e-8;
    /** And its result is accepted within {@code PARTIAL_PRESSURE_ACCEPTANCE P}. */
    public static final double PARTIAL_PRESSURE_ACCEPTANCE = 1.0e-6;

    // Outcomes of the hydrocarbon core.
    private static final int SINGLE = 0;
    private static final int SPLIT = 1;
    private static final int MERGED = 2;
    private static final int FAILED = 3;

    /**
     * @param lnKTolerance a plain substitution step moving no {@code ln K_i} by more than this is converged
     * @param maximumIterations substitution iterations before {@link EquilibriumResult.Status#NOT_CONVERGED} (not reached
     *        while {@code substitutionLimit} is smaller: Newton takes over first)
     * @param accelerationCycle plain steps between two dominant-eigenvalue extrapolations
     * @param coexistenceTolerance relative pressure distance to a pure component's equal-fugacity pressure within
     *        which the state is pure coexistence
     * @param trivialDistance {@code sum ln K_i^2} below which substitution has collapsed onto the feed (Newton takes over)
     * @param stability settings of the stability tests
     * @param slowSubstitutionSteps substitution steps after which a slow iteration hands over to Newton
     * @param slowEigenvalue the dominant eigenvalue estimate above which substitution is slow
     * @param substitutionLimit substitution steps after which Newton takes over in any case
     * @param maximumNewtonIterations accepted Newton steps before {@link EquilibriumResult.Status#NOT_CONVERGED}
     * @param fugacityTolerance Newton is converged at {@code max |ln f_i^V - ln f_i^L|} below this
     * @param bandTieLine a split with {@code sum z_i (ln K_i)^2} below this is in the critical band
     * @param bandStationaryDistance a single mixture phase whose stability test met a stationary point within this
     *        {@code sum (ln W_i - ln z_i)^2} of the feed is in the critical band
     * @param mergeTieLine a split with {@code sum z_i (ln K_i)^2} below this ...
     * @param mergeVolumeGap ... and molar volumes within this relative gap is merged into one fluid
     */
    public record Settings(double lnKTolerance, int maximumIterations, int accelerationCycle,
                           double coexistenceTolerance, double trivialDistance,
                           TangentPlaneStability.Settings stability,
                           int slowSubstitutionSteps, double slowEigenvalue, int substitutionLimit,
                           int maximumNewtonIterations, double fugacityTolerance,
                           double bandTieLine, double bandStationaryDistance,
                           double mergeTieLine, double mergeVolumeGap) {
        public static final Settings DEFAULT = new Settings(1.0e-10, 1000, 5, 1.0e-9, 1.0e-8,
                TangentPlaneStability.Settings.DEFAULT);

        public Settings {
            Objects.requireNonNull(stability, "stability");
            if (!(lnKTolerance > 0.0) || !Double.isFinite(lnKTolerance) || maximumIterations < 1 || accelerationCycle < 2
                    || !(coexistenceTolerance >= 0.0) || !Double.isFinite(coexistenceTolerance)
                    || !(trivialDistance > 0.0) || !Double.isFinite(trivialDistance)
                    || slowSubstitutionSteps < 1 || !(slowEigenvalue > 0.0) || substitutionLimit < 1
                    || maximumNewtonIterations < 1 || !(fugacityTolerance > 0.0) || !Double.isFinite(fugacityTolerance)
                    || !(bandTieLine >= 0.0) || !(bandStationaryDistance >= 0.0) || !(mergeTieLine >= 0.0)
                    || !(mergeVolumeGap >= 0.0)) {
                throw new IllegalArgumentException("Invalid equilibrium settings");
            }
        }

        /** The P2 settings with the P3 defaults of the Newton finish, the critical band and merging. */
        public Settings(double lnKTolerance, int maximumIterations, int accelerationCycle, double coexistenceTolerance,
                        double trivialDistance, TangentPlaneStability.Settings stability) {
            this(lnKTolerance, maximumIterations, accelerationCycle, coexistenceTolerance, trivialDistance, stability,
                    10, 0.9, 50, 50, 1.0e-10, 0.1, 0.1, 1.0e-8, 1.0e-2);
        }
    }

    private final PhaseContract contract;
    private final CubicPhaseEvaluator evaluator;
    private final PengRobinsonKernel kernel;
    private final TangentPlaneStability stability;
    private final Settings settings;
    private final FreeWaterModel freeWater;
    private final int count;
    private final int basis;
    private final int[] basisOfComponent;
    private final int waterIndex;
    private final int[] waterChemistry;
    private final double[] translations;
    private final EquilibriumResult.Coverage convergedCoverage;

    public FluidTpEquilibrium(PhaseContract contract, CubicPhaseEvaluator evaluator) {
        this(contract, evaluator, Settings.DEFAULT);
    }

    public FluidTpEquilibrium(PhaseContract contract, CubicPhaseEvaluator evaluator, Settings settings) {
        this(contract, evaluator, settings, null);
    }

    /**
     * @param evaluator its basis must be the contract's basis without water, in the same order, and its family the
     *        contract identity's
     * @param freeWater the water model of a {@link WaterParticipation#SEPARATE_FREE_WATER} contract, whose revision the
     *        contract identity must name; {@code null} leaves requests with water {@code NOT_IMPLEMENTED}
     */
    public FluidTpEquilibrium(PhaseContract contract, CubicPhaseEvaluator evaluator, Settings settings,
                              FreeWaterModel freeWater) {
        this.contract = Objects.requireNonNull(contract, "contract");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.freeWater = freeWater;
        if (!contract.identity().evaluatorFamily().equals(evaluator.family())) {
            throw new IllegalArgumentException("Contract identity names family " + contract.identity().evaluatorFamily()
                    + ", evaluator is " + evaluator.family());
        }
        List<String> withoutWater = new ArrayList<>(contract.components());
        if (contract.waterComponent() != null) withoutWater.remove(contract.waterComponent());
        if (!withoutWater.equals(evaluator.components())) {
            throw new IllegalArgumentException("The evaluator basis must be the contract basis without water, in order");
        }
        if (freeWater != null) {
            if (contract.water() != WaterParticipation.SEPARATE_FREE_WATER) {
                throw new IllegalArgumentException("A free-water model needs a separate-free-water contract");
            }
            String expected = ThermoIdentity.SEPARATE_FREE_WATER_FORMULATION + ":" + freeWater.revision();
            if (!contract.identity().waterModelRevision().equals(expected)) {
                throw new IllegalArgumentException("The contract identity names water model "
                        + contract.identity().waterModelRevision() + ", the free-water model is " + expected);
            }
        }
        count = evaluator.componentCount();
        basis = contract.components().size();
        basisOfComponent = new int[count];
        for (int i = 0; i < count; i++) basisOfComponent[i] = contract.index(evaluator.components().get(i));
        waterIndex = contract.waterIndex();
        waterChemistry = contract.waterChemistrySpecies().stream().mapToInt(contract::index).sorted().toArray();
        kernel = evaluator.kernel();
        translations = evaluator.translations();
        stability = new TangentPlaneStability(kernel, settings.stability());
        convergedCoverage = new EquilibriumResult.Coverage(contract.fluidCoverage().grade(),
                contract.fluidCoverage().evidence() + "; fluid-only: solid phases not assessed");
    }

    @Override public PhaseContract contract() { return contract; }
    public CubicPhaseEvaluator evaluator() { return evaluator; }
    public Settings settings() { return settings; }
    /** The water model, {@code null} when requests with water are not implemented by this service. */
    public FreeWaterModel freeWater() { return freeWater; }

    @Override public Workspace newWorkspace() { return new Workspace(this); }

    /** Scratch for one thread. */
    public static final class Workspace implements EquilibriumService.Workspace {
        private final FluidTpEquilibrium owner;
        private final double[] basisAmounts;
        private final double[] feed;
        private final double[] z;
        private final int[] present;
        private int presentCount;
        private final double[] logK, k, x, y, newLogK;
        private double[] delta, previousDelta;
        private final double[] liquidLogPhi, vaporLogPhi;
        private final double[] liquidAmounts, vaporAmounts;
        private final PengRobinsonKernel.Workspace mixture;
        private final PengRobinsonKernel.Evaluation first, second;
        private final TangentPlaneStability.Workspace stability;
        private final PhaseEvaluator.Workspace phases;
        private final PhaseState vaporState, liquidState;
        private int kernelEvaluations, derivativeEvaluations, rachfordRiceSteps, iterations, extrapolations,
                newtonIterations;
        private double lastChange;
        // Branch pressures of the hydrocarbon core: equal on the dry path.
        private double liquidPressure, vaporPressure;
        private boolean branched;
        private final double[] vaporShifts;
        // The Newton finish.
        private final double[] vaporMoles, liquidMoles, trialVapor, trialLiquid, step, gradient, scale, reference, trial;
        private final double[] hessian, factor, direction;
        private final PengRobinsonKernel.Derivatives vaporDerivatives, liquidDerivatives;
        private double lambda, beta, tieLine, seedDistance, gibbsScale;
        private boolean trialIsVapour;
        // Outcome of the hydrocarbon core.
        private TangentPlaneStability.Result feedResult;
        private Root singleRoot;
        private String failure;
        private double residual, productDistance;
        // Free water.
        private final PhaseState gasState, waterState;
        private final double[] gasComposition, gasLogPhi, gasIdealGibbs, waterProperties;
        private final double[] one = {1.0}, oneLogPhi = new double[1], oneIdealGibbs = new double[1];

        private Workspace(FluidTpEquilibrium owner) {
            this.owner = owner;
            int n = owner.count;
            basisAmounts = new double[owner.basis];
            feed = new double[n];
            z = new double[n];
            present = new int[n];
            logK = new double[n];
            k = new double[n];
            x = new double[n];
            y = new double[n];
            newLogK = new double[n];
            delta = new double[n];
            previousDelta = new double[n];
            liquidLogPhi = new double[n];
            vaporLogPhi = new double[n];
            liquidAmounts = new double[n];
            vaporAmounts = new double[n];
            mixture = owner.kernel.newWorkspace();
            first = owner.kernel.newEvaluation();
            second = owner.kernel.newEvaluation();
            stability = owner.stability.newWorkspace();
            phases = owner.evaluator.newWorkspace();
            vaporState = owner.evaluator.newState();
            liquidState = owner.evaluator.newState();
            vaporShifts = new double[n];
            vaporMoles = new double[n];
            liquidMoles = new double[n];
            trialVapor = new double[n];
            trialLiquid = new double[n];
            step = new double[n];
            gradient = new double[n];
            scale = new double[n];
            reference = new double[n];
            trial = new double[n];
            hessian = new double[n * n];
            factor = new double[n * n];
            direction = new double[n];
            vaporDerivatives = owner.kernel.newDerivatives();
            liquidDerivatives = owner.kernel.newDerivatives();
            gasState = new PhaseState(owner.basis);
            waterState = new PhaseState(1);
            gasComposition = new double[owner.basis];
            gasLogPhi = new double[owner.basis];
            gasIdealGibbs = new double[owner.basis];
            waterProperties = new double[2];
        }

        /** The pressure a root's branch is evaluated at. */
        private double pressureOf(Root root) { return root == Root.VAPOR ? vaporPressure : liquidPressure; }
    }

    @Override
    public EquilibriumResult tp(EquilibriumRequest request, EquilibriumService.Workspace workspace) {
        Workspace w = own(workspace);
        requireSpecification(request, Specification.TP);
        PhaseCompetition competition = request.competition();
        EquilibriumResult refused = contractChecks(request, w);
        if (refused != null) return refused;
        double t = request.temperature();
        double p = request.pressure();
        ThermoDomainViolation violation = contract.domain().violation(t, p, w.basisAmounts);
        if (violation != null) return EquilibriumResult.outOfDomain(Specification.TP, contract.identity(), competition, violation);
        if (!competition.fluidOnly()) {
            return unsupported(Specification.TP, competition, UnsupportedReason.NOT_IMPLEMENTED,
                    "solid phases compete from P4 on; this service computes fluid phases only");
        }
        boolean wet = waterIndex >= 0 && w.basisAmounts[waterIndex] > 0.0;
        if (wet && freeWater == null) {
            return unsupported(Specification.TP, competition, UnsupportedReason.NOT_IMPLEMENTED,
                    "the separate free-water phase needs a FreeWaterModel (P3); this service was built without one");
        }
        double hydrocarbons = 0.0;
        for (int i = 0; i < count; i++) {
            w.feed[i] = w.basisAmounts[basisOfComponent[i]];
            hydrocarbons += w.feed[i];
        }
        if (hydrocarbons > 0.0) {
            violation = evaluator.idealGasViolation(t, w.feed);
            if (violation != null) return EquilibriumResult.outOfDomain(Specification.TP, contract.identity(), competition, violation);
        }
        w.kernelEvaluations = 0;
        w.derivativeEvaluations = 0;
        w.rachfordRiceSteps = 0;
        w.iterations = 0;
        w.extrapolations = 0;
        w.newtonIterations = 0;
        w.lastChange = Double.NaN;
        w.tieLine = Double.NaN;
        w.feedResult = null;
        w.residual = Double.NaN;
        w.productDistance = Double.NaN;
        try {
            return wet ? solveWet(t, p, hydrocarbons, w.basisAmounts[waterIndex], competition, w)
                    : solveDry(t, p, competition, w);
        } catch (ThermoDomainViolation outside) {
            return EquilibriumResult.outOfDomain(Specification.TP, contract.identity(), competition, outside);
        } catch (IllegalArgumentException | IllegalStateException | ArithmeticException refusedByModel) {
            // The equation of state refused a state (no physical root, a mechanically unstable root): no answer.
            return EquilibriumResult.notConverged(Specification.TP, contract.identity(), competition,
                    "evaluation refused: " + refusedByModel.getMessage(), diagnostics(w, w.feedResult, false));
        }
    }

    /** PH is not implemented before P3; the contract checks still run first. */
    @Override
    public EquilibriumResult ph(EquilibriumRequest request, EquilibriumService.Workspace workspace) {
        Workspace w = own(workspace);
        requireSpecification(request, Specification.PH);
        EquilibriumResult refused = contractChecks(request, w);
        if (refused != null) return refused;
        return unsupported(Specification.PH, request.competition(), UnsupportedReason.NOT_IMPLEMENTED,
                "PH equilibrium is not implemented before P3");
    }

    /** UV is not implemented before P3; the contract checks still run first. */
    @Override
    public EquilibriumResult uv(EquilibriumRequest request, EquilibriumService.Workspace workspace) {
        Workspace w = own(workspace);
        requireSpecification(request, Specification.UV);
        EquilibriumResult refused = contractChecks(request, w);
        if (refused != null) return refused;
        return unsupported(Specification.UV, request.competition(), UnsupportedReason.NOT_IMPLEMENTED,
                "UV equilibrium is not implemented before P3");
    }

    // Contract checks.

    /** Maps the request onto the basis and applies the species, water-chemistry and competition rules. */
    private EquilibriumResult contractChecks(EquilibriumRequest request, Workspace w) {
        Specification specification = request.specification();
        PhaseCompetition competition = request.competition();
        List<String> species = request.species();
        double[] amounts = request.amountsView();
        if (species.equals(contract.components())) {
            System.arraycopy(amounts, 0, w.basisAmounts, 0, basis);
        } else {
            Arrays.fill(w.basisAmounts, 0.0);
            for (int s = 0; s < species.size(); s++) {
                int i = contract.index(species.get(s));
                if (i >= 0) {
                    w.basisAmounts[i] = amounts[s];
                } else if (amounts[s] > 0.0) {
                    return unsupported(specification, competition, UnsupportedReason.SPECIES_NOT_IN_PACKAGE,
                            "species " + species.get(s) + " is not in package " + contract.packageId());
                }
            }
        }
        if (competition.hydrates()) {
            return unsupported(specification, competition, UnsupportedReason.WATER_CHEMISTRY_NOT_MODELLED,
                    "gas hydrates need water chemistry; water participation " + contract.water() + " models none");
        }
        if (waterIndex >= 0 && w.basisAmounts[waterIndex] > 0.0) {
            for (int i : waterChemistry) {
                if (w.basisAmounts[i] > 0.0) {
                    return unsupported(specification, competition, UnsupportedReason.WATER_CHEMISTRY_NOT_MODELLED,
                            contract.components().get(i) + " together with water needs aqueous chemistry; the "
                                    + contract.water() + " approximation cannot describe its dissolution");
                }
            }
        }
        if (!contract.qualifies(competition)) {
            return unsupported(specification, competition, UnsupportedReason.PHASE_COMPETITION_NOT_QUALIFIED,
                    "package " + contract.packageId() + " is not qualified for the " + competition + " competition");
        }
        return null;
    }

    // The dry calculation: hydrocarbon phases at one pressure.

    private EquilibriumResult solveDry(double t, double p, PhaseCompetition competition, Workspace w) {
        setBranches(t, p, p, w);
        int outcome = hydrocarbon(t, w);
        return switch (outcome) {
            case SINGLE -> singlePhase(t, p, competition, w);
            case MERGED -> merged(t, p, competition, w);
            case SPLIT -> twoPhaseResult(t, p, competition, w);
            default -> failed(t, p, competition, w);
        };
    }

    private void setBranches(double t, double liquidPressure, double vaporPressure, Workspace w) {
        w.liquidPressure = liquidPressure;
        w.vaporPressure = vaporPressure;
        w.branched = liquidPressure != vaporPressure;
        if (w.branched) {
            double logRatio = Math.log(vaporPressure / liquidPressure);
            double scale = (vaporPressure - liquidPressure) / (R * t);
            for (int i = 0; i < count; i++) w.vaporShifts[i] = logRatio + scale * translations[i];
        }
    }

    private EquilibriumResult singlePhase(double t, double p, PhaseCompetition competition, Workspace w) {
        TangentPlaneStability.Result feed = w.feedResult;
        boolean band = inBand(t, p, w) || feed.nearestStationaryDistance() < settings.bandStationaryDistance() && w.presentCount > 1;
        String why = band ? bandReason(t, p, w, feed.nearestStationaryDistance()) : null;
        if (w.presentCount == 1) {
            int only = w.present[0];
            kernel.evaluate(t, p, w.feed, Root.VAPOR, w.mixture, w.first);
            w.kernelEvaluations++;
            if (w.first.physicalRootCount() > 1) {
                kernel.evaluate(t, p, w.feed, Root.LIQUID, w.mixture, w.second);
                w.kernelEvaluations++;
                // d(ln phi_L - ln phi_V)/d ln P = Z_L - Z_V: the gap over the Z gap is the relative distance in
                // pressure to the equal-fugacity pressure, to first order.
                double gap = w.second.logFugacityCoefficient(only) - w.first.logFugacityCoefficient(only);
                double compressibilityGap = w.first.compressibility() - w.second.compressibility();
                if (Math.abs(gap) <= settings.coexistenceTolerance() * compressibilityGap) {
                    PhaseState vapour = evaluator.evaluate(t, p, w.feed, PhaseRoot.VAPOR, w.phases, w.vaporState).copy();
                    PhaseState liquid = evaluator.evaluate(t, p, w.feed, PhaseRoot.LIQUID, w.phases, w.liquidState).copy();
                    w.kernelEvaluations += 2;
                    return converged(competition, Classification.PURE_COEXISTENCE_UNDERDETERMINED, List.of(),
                            List.of(vapour, liquid), diagnostics(w, feed, band), why, null);
                }
            }
        }
        PhaseRoot root = w.singleRoot == Root.VAPOR ? PhaseRoot.VAPOR : PhaseRoot.LIQUID;
        PhaseState state = evaluator.evaluate(t, p, w.feed, root, w.phases, w.vaporState);
        w.kernelEvaluations++;
        Classification classification = singleClassification(state, t, p, w);
        PhaseKind label = singleLabel(state, classification, t, p, w);
        var phase = new PhaseAmounts(label, null, t, p, w.basisAmounts, state.copy());
        return converged(competition, classification, List.of(phase), List.of(), diagnostics(w, feed, band), why, null);
    }

    /** The classification of one hydrocarbon phase from its root situation (P2 rule). */
    private Classification singleClassification(PhaseState state, double t, double p, Workspace w) {
        return switch (state.kind()) {
            case VAPOR -> Classification.SINGLE_VAPOR;
            case LIQUID -> Classification.SINGLE_LIQUID;
            default -> w.presentCount == 1 && supercritical(w.present[0], t, p)
                    ? Classification.SUPERCRITICAL_FLUID : Classification.SINGLE_FLUID;
        };
    }

    /**
     * The label of a single phase: the root for three roots; {@link PhaseKind#FLUID} for a pure supercritical fluid;
     * otherwise the phase identification parameter of its one root (liquid-like above 1).
     */
    private PhaseKind singleLabel(PhaseState state, Classification classification, double t, double p, Workspace w) {
        return switch (classification) {
            case SINGLE_VAPOR -> PhaseKind.VAPOR;
            case SINGLE_LIQUID -> PhaseKind.LIQUID;
            case SUPERCRITICAL_FLUID -> PhaseKind.FLUID;
            default -> {
                Root root = state.root() == PhaseRoot.VAPOR ? Root.VAPOR : Root.LIQUID;
                kernel.evaluate(t, p, w.feed, root, w.mixture, w.first);
                w.kernelEvaluations++;
                double parameter = PhaseIdentification.parameter(w.first, t, p);
                yield Double.isNaN(parameter) ? PhaseKind.FLUID
                        : PhaseIdentification.liquidLike(parameter) ? PhaseKind.LIQUID : PhaseKind.VAPOR;
            }
        };
    }

    private boolean supercritical(int component, double t, double p) {
        var constants = evaluator.component(component);
        return t > constants.criticalTemperatureKelvin() && p > constants.criticalPressurePascal();
    }

    /** A near-critical split below the model's resolution: one fluid, the feed on its stability root, in the band. */
    private EquilibriumResult merged(double t, double p, PhaseCompetition competition, Workspace w) {
        PhaseRoot root = w.feedResult.feedRoot() == Root.VAPOR ? PhaseRoot.VAPOR : PhaseRoot.LIQUID;
        PhaseState state = evaluator.evaluate(t, p, w.feed, root, w.phases, w.vaporState);
        w.kernelEvaluations++;
        var phase = new PhaseAmounts(PhaseKind.FLUID, null, t, p, w.basisAmounts, state.copy());
        return converged(competition, Classification.SINGLE_FLUID, List.of(phase), List.of(), diagnostics(w, w.feedResult, true),
                mergedDetail(w), null);
    }

    private String mergedDetail(Workspace w) {
        return "merged near-critical split (sum z ln K^2 = " + w.tieLine + " below " + settings.mergeTieLine()
                + "): the stability test found the feed unstable, but its two phases coincide within the model's resolution";
    }

    private EquilibriumResult twoPhaseResult(double t, double p, PhaseCompetition competition, Workspace w) {
        PhaseState liquid = w.liquidState;
        PhaseState vapour = w.vaporState;
        // The equilibrium's labels: the denser phase is the liquid.
        boolean swapped = vapour.molarVolume() < liquid.molarVolume();
        PhaseAmounts lighter = new PhaseAmounts(PhaseKind.VAPOR, null, t, p,
                expand(swapped ? w.liquidAmounts : w.vaporAmounts), (swapped ? liquid : vapour).copy());
        PhaseAmounts denser = new PhaseAmounts(PhaseKind.LIQUID, null, t, p,
                expand(swapped ? w.vaporAmounts : w.liquidAmounts), (swapped ? vapour : liquid).copy());
        boolean band = inBand(t, p, w) || w.tieLine < settings.bandTieLine();
        return converged(competition, Classification.VAPOR_LIQUID, List.of(lighter, denser), List.of(),
                diagnostics(w, w.feedResult, band), band ? bandReason(t, p, w, Double.NaN) : null, null);
    }

    private EquilibriumResult failed(double t, double p, PhaseCompetition competition, Workspace w) {
        double seed = w.feedResult == null ? Double.NaN : w.seedDistance;
        boolean band = inBand(t, p, w) || w.tieLine < settings.bandTieLine() || seed < settings.bandStationaryDistance();
        String detail = band ? EquilibriumResult.CRITICAL_BAND + " (" + bandReason(t, p, w, seed) + "): " + w.failure : w.failure;
        return EquilibriumResult.notConverged(Specification.TP, contract.identity(), competition,
                detail, diagnostics(w, w.feedResult, band));
    }

    // The hydrocarbon core, at the workspace's branch pressures.

    /** Stability test and, for an unstable feed, the split. Returns SINGLE, SPLIT, MERGED or FAILED. */
    private int hydrocarbon(double t, Workspace w) {
        double total = 0.0;
        for (int i = 0; i < count; i++) total += w.feed[i];
        w.presentCount = 0;
        for (int i = 0; i < count; i++) {
            w.z[i] = w.feed[i] / total;
            if (w.feed[i] > 0.0) w.present[w.presentCount++] = i;
        }
        w.tieLine = Double.NaN;
        w.seedDistance = Double.NaN;
        w.residual = Double.NaN;
        w.productDistance = Double.NaN;
        TangentPlaneStability.Result feed = w.branched
                ? stability.test(t, w.liquidPressure, w.vaporPressure, translations, w.feed, w.stability)
                : stability.test(t, w.liquidPressure, w.feed, w.stability);
        w.feedResult = feed;
        w.kernelEvaluations += feed.kernelEvaluations();
        w.derivativeEvaluations += feed.derivativeEvaluations();
        switch (feed.verdict()) {
            case UNRESOLVED -> {
                w.failure = "the feed's stability test is unresolved";
                return FAILED;
            }
            case STABLE -> {
                w.singleRoot = feed.feedRoot();
                return SINGLE;
            }
            default -> {
                return twoPhase(t, feed, w);
            }
        }
    }

    /**
     * The split of an unstable feed: a first attempt seeded from the stability test's stationary phase, and, when that
     * fails, one more from Wilson's K. With branch pressures a stationary phase on the feed's own branch (a second
     * vapour, or a second liquid) cannot seed the network's split of a liquid at {@code P} and a vapour at {@code pc},
     * so Wilson's K (Raoult with the vapour at {@code pc}, the network's own seed) is the first attempt there.
     */
    private int twoPhase(double t, TangentPlaneStability.Result feed, Workspace w) {
        boolean sameBranch = w.branched && feed.trialRoot() == feed.feedRoot();
        int outcome = attempt(t, feed, sameBranch, w);
        if (outcome == FAILED && !sameBranch) {
            String first = w.failure;
            double seed = w.seedDistance;
            outcome = attempt(t, feed, true, w);
            if (outcome == FAILED) w.failure = first + "; again from Wilson's K: " + w.failure;
            w.seedDistance = seed;
        }
        return outcome;
    }

    private int attempt(double t, TangentPlaneStability.Result feed, boolean wilson, Workspace w) {
        // The stability test's stationary point seeds one side of the split: the lighter one the vapour.
        double[] trial = feed.trialComposition();
        System.arraycopy(trial, 0, w.trial, 0, count);
        kernel.evaluate(t, w.pressureOf(feed.trialRoot()), trial, feed.trialRoot(), w.mixture, w.first);
        kernel.evaluate(t, w.pressureOf(feed.feedRoot()), w.feed, feed.feedRoot(), w.mixture, w.second);
        w.kernelEvaluations += 2;
        w.trialIsVapour = w.branched && feed.trialRoot() != feed.feedRoot() ? feed.trialRoot() == Root.VAPOR
                : w.first.compressibility() > w.second.compressibility();
        Arrays.fill(w.logK, 0.0);
        double seedDistance = 0.0;
        for (int a = 0; a < w.presentCount; a++) {
            int i = w.present[a];
            double ratio = Math.log(trial[i]) - Math.log(w.z[i]);
            w.logK[i] = w.trialIsVapour ? ratio : -ratio;
            seedDistance += w.logK[i] * w.logK[i];
        }
        w.seedDistance = seedDistance;
        if (wilson || !(seedDistance >= settings.trivialDistance())) {
            // Instability proven by the feed's own curvature with no distinct stationary point: Wilson's K instead.
            kernel.wilsonK(t, w.vaporPressure, w.k);
            for (int a = 0; a < w.presentCount; a++) w.logK[w.present[a]] = Math.log(w.k[w.present[a]]);
        }

        // Successive substitution; Newton takes over when it is slow, collapses or loses the split.
        boolean newton = false;
        boolean fromSubstitution = true;
        boolean hasPrevious = false;
        int plainSteps = 0;
        w.lambda = 0.0;
        int steps = 0;
        while (true) {
            if (Double.isNaN(split(w))) {
                newton = true;
                fromSubstitution = false;
                break;
            }
            phaseLogPhi(t, w.x, false, w.liquidLogPhi, w);
            phaseLogPhi(t, w.y, true, w.vaporLogPhi, w);
            double change = 0.0;
            for (int a = 0; a < w.presentCount; a++) {
                int i = w.present[a];
                w.newLogK[i] = w.liquidLogPhi[i] - w.vaporLogPhi[i];
                w.delta[i] = w.newLogK[i] - w.logK[i];
                change = Math.max(change, Math.abs(w.delta[i]));
            }
            w.iterations++;
            steps++;
            w.lastChange = change;
            if (change < settings.lnKTolerance()) {
                for (int a = 0; a < w.presentCount; a++) w.logK[w.present[a]] = w.newLogK[w.present[a]];
                break;
            }
            if (!Double.isFinite(change)) {
                newton = true;
                fromSubstitution = false;
                break;
            }
            if (steps >= settings.maximumIterations()) {
                w.failure = "no convergence in " + steps + " substitution iterations (max |d ln K| " + change + ")";
                return FAILED;
            }
            boolean extrapolated = false;
            plainSteps++;
            if (hasPrevious && plainSteps >= settings.accelerationCycle()) {
                double numerator = 0.0;
                double denominator = 0.0;
                for (int a = 0; a < w.presentCount; a++) {
                    int i = w.present[a];
                    numerator += w.delta[i] * w.delta[i];
                    denominator += w.previousDelta[i] * w.delta[i];
                }
                double lambda = numerator / denominator;
                w.lambda = lambda;
                if (lambda > 0.0 && lambda < 1.0) {
                    double factor = lambda / (1.0 - lambda);
                    double largest = change * factor;
                    double scale = largest > MAXIMUM_EXTRAPOLATION ? MAXIMUM_EXTRAPOLATION / largest : 1.0;
                    for (int a = 0; a < w.presentCount; a++) {
                        int i = w.present[a];
                        w.logK[i] = w.newLogK[i] + w.delta[i] * factor * scale;
                    }
                    extrapolated = true;
                    w.extrapolations++;
                }
                plainSteps = 0;
            }
            if (extrapolated) {
                hasPrevious = false;
            } else {
                for (int a = 0; a < w.presentCount; a++) w.logK[w.present[a]] = w.newLogK[w.present[a]];
                double[] swap = w.previousDelta;
                w.previousDelta = w.delta;
                w.delta = swap;
                hasPrevious = true;
            }
            double distance = 0.0;
            for (int a = 0; a < w.presentCount; a++) distance += w.logK[w.present[a]] * w.logK[w.present[a]];
            if (distance < settings.trivialDistance()) {
                // Collapsing onto the feed: start the finish from the stability test's own phase instead.
                newton = true;
                fromSubstitution = false;
                break;
            }
            if (steps >= settings.substitutionLimit()
                    || steps >= settings.slowSubstitutionSteps() && w.lambda > settings.slowEigenvalue()) {
                newton = true;
                break;
            }
        }

        if (newton) {
            if (!newtonFinish(t, feed, fromSubstitution, w)) return FAILED;
        } else {
            double beta = split(w);
            if (!(beta > 0.0 && beta < 1.0)) {
                // A converged ratio set with no admissible split: the finish decides from the stability test's phase.
                if (!newtonFinish(t, feed, false, w)) return FAILED;
            } else {
                w.beta = beta;
                double tie = 0.0;
                Arrays.fill(w.vaporAmounts, 0.0);
                Arrays.fill(w.liquidAmounts, 0.0);
                for (int a = 0; a < w.presentCount; a++) {
                    int i = w.present[a];
                    tie += w.z[i] * w.logK[i] * w.logK[i];
                    // The smaller share by its own formula, the larger by difference: conserved to rounding, and a
                    // component almost entirely in one phase keeps its trace in the other to full relative precision.
                    double denominator = 1.0 + beta * (w.k[i] - 1.0);
                    double vapourShare = beta * w.k[i] / denominator;
                    double liquidShare = (1.0 - beta) / denominator;
                    if (vapourShare <= liquidShare) {
                        w.vaporAmounts[i] = Math.min(w.feed[i], w.feed[i] * vapourShare);
                        w.liquidAmounts[i] = w.feed[i] - w.vaporAmounts[i];
                    } else {
                        w.liquidAmounts[i] = Math.min(w.feed[i], w.feed[i] * liquidShare);
                        w.vaporAmounts[i] = w.feed[i] - w.liquidAmounts[i];
                    }
                }
                w.tieLine = tie;
            }
        }
        return finishSplit(t, w);
    }

    /** States of both products, merging, the product stability re-check and the fugacity residual. */
    private int finishSplit(double t, Workspace w) {
        PhaseState liquid;
        PhaseState vapour;
        if (w.branched) {
            liquid = evaluator.evaluate(t, w.liquidPressure, w.liquidAmounts, PhaseRoot.LIQUID, w.phases, w.liquidState);
            vapour = evaluator.evaluate(t, w.vaporPressure, w.vaporAmounts, PhaseRoot.VAPOR, w.phases, w.vaporState);
        } else {
            liquid = evaluator.evaluate(t, w.liquidPressure, w.liquidAmounts,
                    toPhaseRoot(lowerGibbsRoot(t, w.liquidAmounts, Root.LIQUID, w.liquidLogPhi, w)), w.phases, w.liquidState);
            vapour = evaluator.evaluate(t, w.vaporPressure, w.vaporAmounts,
                    toPhaseRoot(lowerGibbsRoot(t, w.vaporAmounts, Root.VAPOR, w.vaporLogPhi, w)), w.phases, w.vaporState);
        }
        w.kernelEvaluations += 2;
        double gap = Math.abs(vapour.molarVolume() - liquid.molarVolume()) / (0.5 * (vapour.molarVolume() + liquid.molarVolume()));
        if (w.tieLine < settings.mergeTieLine() && gap < settings.mergeVolumeGap()) return MERGED;
        if (w.branched && vapour.physicalRootCount() == 1) {
            kernel.evaluate(t, w.vaporPressure, w.vaporAmounts, Root.VAPOR, w.mixture, w.first);
            w.kernelEvaluations++;
            if (!PhaseIdentification.vapourLike(PhaseIdentification.parameter(w.first, t, w.vaporPressure))) {
                w.failure = "the vapour-branch phase is liquid-like at the hydrocarbon partial pressure";
                return FAILED;
            }
        }
        // Stability of both products: a third phase or another split is not an answer.
        TangentPlaneStability.Result liquidCheck = w.branched
                ? stability.test(t, w.liquidPressure, w.vaporPressure, translations, w.liquidAmounts, w.stability)
                : stability.test(t, w.liquidPressure, w.liquidAmounts, w.stability);
        w.kernelEvaluations += liquidCheck.kernelEvaluations();
        w.derivativeEvaluations += liquidCheck.derivativeEvaluations();
        TangentPlaneStability.Result vapourCheck = w.branched
                ? stability.test(t, w.liquidPressure, w.vaporPressure, translations, w.vaporAmounts, w.stability)
                : stability.test(t, w.liquidPressure, w.vaporAmounts, w.stability);
        w.kernelEvaluations += vapourCheck.kernelEvaluations();
        w.derivativeEvaluations += vapourCheck.derivativeEvaluations();
        w.productDistance = Math.min(liquidCheck.minimumTangentPlaneDistance(), vapourCheck.minimumTangentPlaneDistance());
        if (liquidCheck.verdict() != TangentPlaneStability.Verdict.STABLE
                || vapourCheck.verdict() != TangentPlaneStability.Verdict.STABLE) {
            w.failure = "a product phase is not stable (liquid " + liquidCheck.verdict() + ", vapour " + vapourCheck.verdict()
                    + "): a third phase or another split; three-phase equilibrium is not implemented";
            return FAILED;
        }
        double logPressureRatio = w.branched ? Math.log(w.liquidPressure / w.vaporPressure) : 0.0;
        double residual = 0.0;
        for (int a = 0; a < w.presentCount; a++) {
            int i = w.present[a];
            double xi = liquid.moleFraction(i);
            double yi = vapour.moleFraction(i);
            if (!(xi > 0.0) || !(yi > 0.0)) continue;
            double difference = Math.log(xi) + liquid.logFugacityCoefficient(i) - Math.log(yi) - vapour.logFugacityCoefficient(i);
            if (w.branched) difference += logPressureRatio;
            residual = Math.max(residual, Math.abs(difference));
        }
        w.residual = residual;
        return SPLIT;
    }

    // The second-order finish.

    /**
     * Newton on the vapour mole numbers per mole of feed, minimising the Gibbs energy. Leaves the amounts, the tie line
     * and the ratios in the workspace; false (with {@code failure}) when it cannot start or does not converge.
     */
    private boolean newtonFinish(double t, TangentPlaneStability.Result feed, boolean fromSubstitution, Workspace w) {
        // d_i = ln f_i of the feed on its stability root, over the liquid pressure.
        kernel.evaluate(t, w.pressureOf(feed.feedRoot()), w.z, feed.feedRoot(), w.mixture, w.first);
        w.kernelEvaluations++;
        double[] feedLogPhi = w.first.logFugacityCoefficientsView();
        for (int a = 0; a < w.presentCount; a++) {
            int i = w.present[a];
            w.reference[i] = Math.log(w.z[i]) + feedLogPhi[i] + shift(w, feed.feedRoot() == Root.VAPOR, i);
        }
        double gibbs = Double.NaN;
        if (fromSubstitution) {
            double beta = split(w);
            if (beta > 0.0 && beta < 1.0) {
                for (int a = 0; a < w.presentCount; a++) {
                    int i = w.present[a];
                    double denominator = 1.0 + beta * (w.k[i] - 1.0);
                    w.vaporMoles[i] = w.z[i] * (beta * w.k[i] / denominator);
                    w.liquidMoles[i] = w.z[i] * ((1.0 - beta) / denominator);
                }
                gibbs = gibbs(t, w.vaporMoles, w.liquidMoles, w);
                if (!(gibbs < -GIBBS_ROUNDOFF * w.gibbsScale)) gibbs = Double.NaN;
            }
        }
        if (Double.isNaN(gibbs)) gibbs = startFromStationaryPhase(t, feed, w);
        if (Double.isNaN(gibbs)) {
            w.failure = "no two-phase start below the feed's Gibbs energy for the Newton finish (stationary tm "
                    + feed.minimumTangentPlaneDistance() + ")";
            return false;
        }
        int m = w.presentCount;
        for (int iteration = 0; ; iteration++) {
            // Both phases with their composition derivatives at the current point.
            double vapourTotal = 0.0;
            double liquidTotal = 0.0;
            for (int a = 0; a < m; a++) {
                int i = w.present[a];
                vapourTotal += w.vaporMoles[i];
                liquidTotal += w.liquidMoles[i];
            }
            PengRobinsonKernel.Evaluation vapour;
            PengRobinsonKernel.Evaluation liquid;
            try {
                vapour = differentiatePhase(t, w.vaporMoles, true, w.vaporDerivatives, w);
                liquid = differentiatePhase(t, w.liquidMoles, false, w.liquidDerivatives, w);
            } catch (IllegalStateException coalescing) {
                w.failure = "the Newton finish met a phase at root coalescence: " + coalescing.getMessage();
                return false;
            }
            double largest = 0.0;
            double tie = 0.0;
            for (int a = 0; a < m; a++) {
                int i = w.present[a];
                double logY = Math.log(w.vaporMoles[i] / vapourTotal);
                double logX = Math.log(w.liquidMoles[i] / liquidTotal);
                w.gradient[i] = logY + vapour.logFugacityCoefficient(i) + shift(w, true, i)
                        - logX - liquid.logFugacityCoefficient(i);
                largest = Math.max(largest, Math.abs(w.gradient[i]));
                tie += w.z[i] * (logY - logX) * (logY - logX);
            }
            w.lastChange = largest;
            w.tieLine = tie;
            if (largest < settings.fugacityTolerance()) break;
            if (!Double.isFinite(largest) || iteration >= settings.maximumNewtonIterations()) {
                w.failure = "no convergence in " + w.newtonIterations + " Newton steps (max |ln f_V - ln f_L| " + largest + ")";
                return false;
            }
            // Scaled Hessian: s_i H_ij s_j with s_i = sqrt(v_i l_i / z_i), so the ideal part is the identity.
            for (int a = 0; a < m; a++) {
                int i = w.present[a];
                w.scale[i] = Math.sqrt(w.vaporMoles[i] * w.liquidMoles[i] / (w.vaporMoles[i] + w.liquidMoles[i]));
            }
            for (int a = 0; a < m; a++) {
                int i = w.present[a];
                double[] vapourRow = w.vaporDerivatives.dLogPhiDnRowView(i);
                double[] liquidRow = w.liquidDerivatives.dLogPhiDnRowView(i);
                for (int b = 0; b <= a; b++) {
                    int j = w.present[b];
                    double coupling = (0.5 * (vapourRow[j] + w.vaporDerivatives.dLogPhiDnRowView(j)[i]) - 1.0) / vapourTotal
                            + (0.5 * (liquidRow[j] + w.liquidDerivatives.dLogPhiDnRowView(j)[i]) - 1.0) / liquidTotal;
                    double entry = coupling * w.scale[i] * w.scale[j];
                    if (a == b) entry += 1.0;
                    w.hessian[a * m + b] = entry;
                }
            }
            if (!shiftedSolve(w, m)) {
                w.failure = "the Newton finish found no positive definite shift of the Gibbs Hessian";
                return false;
            }
            // Step in mole numbers, cut so that no amount shrinks below a tenth of itself.
            double slope = 0.0;
            double length = 1.0;
            for (int a = 0; a < m; a++) {
                int i = w.present[a];
                double dv = w.scale[i] * w.step[a];
                w.direction[i] = dv;
                slope += w.gradient[i] * dv;
                if (dv < 0.0) length = Math.min(length, FRACTION_TO_BOUNDARY * w.vaporMoles[i] / -dv);
                else if (dv > 0.0) length = Math.min(length, FRACTION_TO_BOUNDARY * w.liquidMoles[i] / dv);
            }
            boolean accepted = false;
            double allowance = GIBBS_ROUNDOFF * w.gibbsScale;
            for (int halving = 0; halving <= NEWTON_HALVINGS; halving++, length *= 0.5) {
                for (int a = 0; a < m; a++) {
                    int i = w.present[a];
                    w.trialVapor[i] = w.vaporMoles[i] + length * w.direction[i];
                    w.trialLiquid[i] = w.liquidMoles[i] - length * w.direction[i];
                }
                double next;
                try {
                    next = gibbs(t, w.trialVapor, w.trialLiquid, w);
                } catch (IllegalArgumentException refused) {
                    continue;
                }
                if (next <= gibbs + ARMIJO * length * slope + allowance) {
                    gibbs = next;
                    accepted = true;
                    break;
                }
            }
            if (!accepted) {
                w.failure = "the Newton finish found no Gibbs-energy decrease (max |ln f_V - ln f_L| " + largest + ")";
                return false;
            }
            for (int a = 0; a < m; a++) {
                int i = w.present[a];
                w.vaporMoles[i] = w.trialVapor[i];
                w.liquidMoles[i] = w.trialLiquid[i];
            }
            w.newtonIterations++;
        }
        // The amounts: each component's smaller share by its own mole number, the larger by difference.
        double vapourTotal = 0.0;
        Arrays.fill(w.vaporAmounts, 0.0);
        Arrays.fill(w.liquidAmounts, 0.0);
        for (int a = 0; a < m; a++) {
            int i = w.present[a];
            double v = w.vaporMoles[i];
            double l = w.liquidMoles[i];
            vapourTotal += v;
            if (v <= l) {
                w.vaporAmounts[i] = Math.min(w.feed[i], w.feed[i] * (v / (v + l)));
                w.liquidAmounts[i] = w.feed[i] - w.vaporAmounts[i];
            } else {
                w.liquidAmounts[i] = Math.min(w.feed[i], w.feed[i] * (l / (v + l)));
                w.vaporAmounts[i] = w.feed[i] - w.liquidAmounts[i];
            }
        }
        w.beta = vapourTotal;
        return true;
    }

    /**
     * A split below the feed's Gibbs energy from the stability test's stationary composition {@code w}: that phase in the
     * amount {@code eps w}, the rest {@code z - eps w}, with {@code eps} halved from half the largest admissible value
     * until {@code Delta G < 0} (to first order {@code Delta G = eps D(w) < 0}). NaN when the test found no negative
     * stationary point or no amount works.
     */
    private double startFromStationaryPhase(double t, TangentPlaneStability.Result feed, Workspace w) {
        if (!(feed.minimumTangentPlaneDistance() < 0.0)) return Double.NaN;
        // With branch pressures a stationary phase on the feed's own branch cannot be one side of the network's split.
        if (w.branched && feed.trialRoot() == feed.feedRoot()) return Double.NaN;
        double largest = Double.POSITIVE_INFINITY;
        for (int a = 0; a < w.presentCount; a++) {
            int i = w.present[a];
            if (w.trial[i] > 0.0) largest = Math.min(largest, w.z[i] / w.trial[i]);
        }
        if (!(largest > 0.0) || !Double.isFinite(largest)) return Double.NaN;
        double amount = 0.5 * Math.min(largest, 1.0);
        for (int halving = 0; halving < START_HALVINGS; halving++, amount *= 0.5) {
            for (int a = 0; a < w.presentCount; a++) {
                int i = w.present[a];
                double part = amount * w.trial[i];
                double rest = w.z[i] - part;
                if (w.trialIsVapour) {
                    w.vaporMoles[i] = part;
                    w.liquidMoles[i] = rest;
                } else {
                    w.liquidMoles[i] = part;
                    w.vaporMoles[i] = rest;
                }
            }
            double gibbs;
            try {
                gibbs = gibbs(t, w.vaporMoles, w.liquidMoles, w);
            } catch (IllegalArgumentException refused) {
                continue;
            }
            if (gibbs < -GIBBS_ROUNDOFF * w.gibbsScale) return gibbs;
        }
        return Double.NaN;
    }

    /**
     * {@code Delta G/RT} per mole of feed of the split {@code (v, l)} against the feed:
     * {@code sum_i v_i (ln y_i + ln phi_i^V + s_i - d_i) + l_i (ln x_i + ln phi_i^L - d_i)}; leaves the magnitude of its
     * summands in {@code gibbsScale} for the roundoff allowance.
     */
    private double gibbs(double t, double[] v, double[] l, Workspace w) {
        double vapourTotal = 0.0;
        double liquidTotal = 0.0;
        for (int a = 0; a < w.presentCount; a++) {
            int i = w.present[a];
            vapourTotal += v[i];
            liquidTotal += l[i];
        }
        Arrays.fill(w.x, 0.0);
        Arrays.fill(w.y, 0.0);
        for (int a = 0; a < w.presentCount; a++) {
            int i = w.present[a];
            w.y[i] = v[i] / vapourTotal;
            w.x[i] = l[i] / liquidTotal;
        }
        phaseLogPhi(t, w.y, true, w.vaporLogPhi, w);
        phaseLogPhi(t, w.x, false, w.liquidLogPhi, w);
        double sum = 0.0;
        double magnitude = 0.0;
        for (int a = 0; a < w.presentCount; a++) {
            int i = w.present[a];
            double vapourTerm = Math.log(w.y[i]) + w.vaporLogPhi[i];
            double liquidTerm = Math.log(w.x[i]) + w.liquidLogPhi[i];
            sum += v[i] * (vapourTerm - w.reference[i]) + l[i] * (liquidTerm - w.reference[i]);
            magnitude += v[i] * (Math.abs(vapourTerm) + Math.abs(w.reference[i]))
                    + l[i] * (Math.abs(liquidTerm) + Math.abs(w.reference[i]));
        }
        w.gibbsScale = magnitude;
        if (!Double.isFinite(sum)) throw new IllegalArgumentException("Non-finite Gibbs energy of a trial split");
        return sum;
    }

    /**
     * Solves {@code (H + mu I) s = -s g} in place into {@code step} for the scaled Hessian in {@code hessian}, with
     * {@code mu} zero or the first of {@code 1e-8, 1e-7, ..., 1e4} that makes it positive definite.
     */
    private static boolean shiftedSolve(Workspace w, int m) {
        double[] h = w.hessian;
        for (int attempt = 0; attempt < 14; attempt++) {
            double mu = attempt == 0 ? 0.0 : Math.pow(10.0, attempt - 9);
            if (factorShifted(h, m, mu, w.factor)) {
                for (int a = 0; a < m; a++) {
                    int i = w.present[a];
                    w.step[a] = -w.scale[i] * w.gradient[i];
                }
                solveCholesky(w.factor, m, w.step);
                return true;
            }
        }
        return false;
    }

    // Phase evaluations at the branch pressures.

    /** {@code ln phi_i} of a composition on its branch (the vapour's shift added with branch pressures) into {@code out}. */
    private Root phaseLogPhi(double t, double[] composition, boolean vapourSide, double[] out, Workspace w) {
        if (!w.branched) {
            return lowerGibbsRoot(t, composition, vapourSide ? Root.VAPOR : Root.LIQUID, out, w);
        }
        Root root = vapourSide ? Root.VAPOR : Root.LIQUID;
        kernel.evaluate(t, w.pressureOf(root), composition, root, w.mixture, w.first);
        w.kernelEvaluations++;
        double[] logPhi = w.first.logFugacityCoefficientsView();
        for (int i = 0; i < count; i++) out[i] = vapourSide ? logPhi[i] + w.vaporShifts[i] : logPhi[i];
        return root;
    }

    /** Derivatives of one side's phase on its root (the lower-Gibbs root at one pressure, the fixed branch otherwise). */
    private PengRobinsonKernel.Evaluation differentiatePhase(double t, double[] amounts, boolean vapourSide,
                                                             PengRobinsonKernel.Derivatives out, Workspace w) {
        Root preferred = vapourSide ? Root.VAPOR : Root.LIQUID;
        kernel.evaluateDerivatives(t, w.pressureOf(preferred), amounts, preferred, w.mixture, out);
        w.kernelEvaluations++;
        w.derivativeEvaluations++;
        if (!w.branched && out.evaluation().physicalRootCount() > 1) {
            Root other = vapourSide ? Root.LIQUID : Root.VAPOR;
            kernel.evaluate(t, w.liquidPressure, amounts, other, w.mixture, w.second);
            w.kernelEvaluations++;
            if (residualGibbs(w.second, amounts) < residualGibbs(out.evaluation(), amounts)) {
                kernel.evaluateDerivatives(t, w.liquidPressure, amounts, other, w.mixture, out);
                w.kernelEvaluations++;
                w.derivativeEvaluations++;
            }
        }
        return out.evaluation();
    }

    private double shift(Workspace w, boolean vapourSide, int i) {
        return w.branched && vapourSide ? w.vaporShifts[i] : 0.0;
    }

    /**
     * Rachford-Rice for the current {@code ln K}: {@code sum z_i (K_i - 1)/(1 + beta (K_i - 1)) = 0} on the window
     * between its poles (a negative flash is allowed while iterating), safeguarded Newton with bisection. Fills
     * {@code k}, and the normalised {@code x} and {@code y}. Returns {@code NaN} when the K-values straddle no split.
     */
    private double split(Workspace w) {
        double smallest = Double.POSITIVE_INFINITY;
        double largest = Double.NEGATIVE_INFINITY;
        for (int a = 0; a < w.presentCount; a++) {
            int i = w.present[a];
            w.k[i] = Math.exp(Math.clamp(w.logK[i], -LOG_K_BOUND, LOG_K_BOUND));
            smallest = Math.min(smallest, w.k[i]);
            largest = Math.max(largest, w.k[i]);
        }
        if (!(smallest < 1.0 && largest > 1.0)) return Double.NaN;
        double lower = 1.0 / (1.0 - largest);
        double upper = 1.0 / (1.0 - smallest);
        double beta = 0.5;
        for (int step = 0; step < MAXIMUM_RACHFORD_RICE_STEPS; step++) {
            double f = 0.0;
            double slope = 0.0;
            for (int a = 0; a < w.presentCount; a++) {
                int i = w.present[a];
                double d = w.k[i] - 1.0;
                double denominator = 1.0 + beta * d;
                f += w.z[i] * d / denominator;
                slope -= w.z[i] * d * d / (denominator * denominator);
            }
            w.rachfordRiceSteps++;
            if (f == 0.0) break;
            // f decreases in beta: a positive value puts the root above beta.
            if (f > 0.0) lower = beta; else upper = beta;
            double next = beta - f / slope;
            if (!(next > lower && next < upper)) next = 0.5 * (lower + upper);
            if (next == beta || Math.abs(next - beta) <= 1.0e-15 * Math.max(1.0, Math.abs(beta))) {
                beta = next;
                break;
            }
            beta = next;
        }
        double sumX = 0.0;
        double sumY = 0.0;
        Arrays.fill(w.x, 0.0);
        Arrays.fill(w.y, 0.0);
        for (int a = 0; a < w.presentCount; a++) {
            int i = w.present[a];
            double xi = w.z[i] / (1.0 + beta * (w.k[i] - 1.0));
            w.x[i] = xi;
            w.y[i] = w.k[i] * xi;
            sumX += w.x[i];
            sumY += w.y[i];
        }
        for (int a = 0; a < w.presentCount; a++) {
            int i = w.present[a];
            w.x[i] /= sumX;
            w.y[i] /= sumY;
        }
        return beta;
    }

    /** Evaluates {@code composition} on its lower-Gibbs root (the preferred one at a tie), copying its {@code ln phi}. */
    private Root lowerGibbsRoot(double t, double[] composition, Root preferred, double[] logPhi, Workspace w) {
        double p = w.liquidPressure;
        kernel.evaluate(t, p, composition, preferred, w.mixture, w.first);
        w.kernelEvaluations++;
        PengRobinsonKernel.Evaluation chosen = w.first;
        Root root = preferred;
        if (w.first.physicalRootCount() > 1) {
            Root other = preferred == Root.LIQUID ? Root.VAPOR : Root.LIQUID;
            kernel.evaluate(t, p, composition, other, w.mixture, w.second);
            w.kernelEvaluations++;
            if (residualGibbs(w.second, composition) < residualGibbs(w.first, composition)) {
                chosen = w.second;
                root = other;
            }
        }
        System.arraycopy(chosen.logFugacityCoefficientsView(), 0, logPhi, 0, count);
        return root;
    }

    /** {@code sum n_i ln phi_i}: at one composition the root with the smaller value has the lower Gibbs energy. */
    private double residualGibbs(PengRobinsonKernel.Evaluation evaluation, double[] composition) {
        double[] logPhi = evaluation.logFugacityCoefficientsView();
        double sum = 0.0;
        for (int i = 0; i < count; i++) if (composition[i] > 0.0) sum += composition[i] * logPhi[i];
        return sum;
    }

    private static PhaseRoot toPhaseRoot(Root root) { return root == Root.VAPOR ? PhaseRoot.VAPOR : PhaseRoot.LIQUID; }

    // The critical band.

    /** The pure-fluid band: one component at least 0.95 of the amount, at the plan's reduced temperature and pressure. */
    private boolean inBand(double t, double p, Workspace w) {
        int dominant = -1;
        for (int a = 0; a < w.presentCount; a++) {
            int i = w.present[a];
            if (w.z[i] >= 0.95) dominant = i;
        }
        if (dominant < 0) return false;
        var constants = evaluator.component(dominant);
        double tr = t / constants.criticalTemperatureKelvin();
        double pr = p / constants.criticalPressurePascal();
        return tr >= 0.95 && tr <= 1.1 && pr >= 0.8 && pr <= 1.5
                || tr >= 0.90 && tr <= 0.95 && pr >= 0.58 && pr <= 0.74
                || tr >= 1.05 && tr <= 1.2 && pr >= 2.0 && pr <= 3.0;
    }

    private String bandReason(double t, double p, Workspace w, double stationary) {
        if (inBand(t, p, w)) return "critical band: pure fluid near its critical point";
        if (w.tieLine < settings.bandTieLine()) return "critical band: short tie line (sum z ln K^2 = " + w.tieLine + ")";
        return "critical band: incipient phase next to the feed (sum (ln w - ln z)^2 = " + stationary + ")";
    }

    // Free water.

    private EquilibriumResult solveWet(double t, double p, double hydrocarbons, double water, PhaseCompetition competition,
                                       Workspace w) {
        double saturation = freeWater.saturationPressure(t);
        if (!(hydrocarbons > 0.0)) {
            // Pure water: all liquid at or above its saturation pressure, all steam below.
            if (p >= saturation) {
                PhaseAmounts liquidWater = freeWaterPhase(t, p, saturation, water, w);
                var split = new EquilibriumResult.FreeWater(Classification.NONE, saturation, p, 0.0, water, 0.0);
                return converged(competition, Classification.FREE_WATER, List.of(liquidWater), List.of(),
                        diagnostics(w, null, false), null, split);
            }
            ThermoDomainViolation steam = steamViolation(t, p);
            if (steam != null) return EquilibriumResult.outOfDomain(Specification.TP, contract.identity(), competition, steam);
            PhaseAmounts gas = gasPhase(t, p, p, 0.0, water, null, w);
            var split = new EquilibriumResult.FreeWater(Classification.NONE, saturation, p, p, 0.0, water);
            return converged(competition, Classification.FREE_WATER, List.of(gas), List.of(), diagnostics(w, null, false), null, split);
        }
        if (p > saturation + SATURATION_MARGIN) {
            double pc = p - saturation;
            setBranches(t, p, pc, w);
            int outcome = hydrocarbon(t, w);
            if (outcome == FAILED) return failed(t, p, competition, w);
            double gasVolume = hydrocarbonVapour(t, outcome, w);
            double required = saturation * gasVolume / (R * t);
            if (required <= water) {
                double partial = gasVolume > 0.0 ? required * R * t / gasVolume : 0.0;
                if (required > 0.0) {
                    ThermoDomainViolation steam = steamViolation(t, partial);
                    if (steam != null) return EquilibriumResult.outOfDomain(Specification.TP, contract.identity(), competition, steam);
                }
                return wetResult(t, p, pc, outcome, required, water - required, saturation, partial, competition, w);
            }
        }
        // All water is steam; the hydrocarbon partial pressure closes the gas volume (the network's bisection).
        double low = PARTIAL_PRESSURE_FLOOR;
        double high = p;
        double pc = high;
        int outcome = FAILED;
        double gasVolume = 0.0;
        for (int iteration = 0; iteration < PARTIAL_PRESSURE_BISECTIONS; iteration++) {
            pc = (low + high) * 0.5;
            setBranches(t, p, pc, w);
            outcome = hydrocarbon(t, w);
            if (outcome == FAILED) return failed(t, p, competition, w);
            gasVolume = hydrocarbonVapour(t, outcome, w);
            double residual = gasVolume > 0.0 ? pc + water * R * t / gasVolume - p : Double.POSITIVE_INFINITY;
            if (Math.abs(residual) < PARTIAL_PRESSURE_TOLERANCE * p) break;
            if (residual > 0.0) high = pc; else low = pc;
        }
        double partial = gasVolume > 0.0 ? water * R * t / gasVolume : p;
        if (Math.abs(pc + partial - p) > PARTIAL_PRESSURE_ACCEPTANCE * p) {
            w.failure = "the hydrocarbon partial pressure closing the gas volume was not found (pc " + pc + " Pa, p_w "
                    + partial + " Pa at " + p + " Pa)";
            return failed(t, p, competition, w);
        }
        ThermoDomainViolation steam = steamViolation(t, partial);
        if (steam != null) return EquilibriumResult.outOfDomain(Specification.TP, contract.identity(), competition, steam);
        return wetResult(t, p, pc, outcome, water, 0.0, saturation, partial, competition, w);
    }

    private ThermoDomainViolation steamViolation(double t, double partialPressure) {
        double limit = freeWater.vaporPartialPressureLimit(t);
        if (partialPressure <= limit) return null;
        return new ThermoDomainViolation(contract.packageId(), contract.waterComponent(),
                ThermoDomainViolation.Property.PRESSURE, partialPressure, 0.0, limit);
    }

    /**
     * The hydrocarbon vapour of a wet hydrocarbon outcome at the branch pressures: its amounts in {@code vaporAmounts}
     * (liquid in {@code liquidAmounts}), its state in {@code vaporState}; returns its volume {@code n_V v_V(pc)}.
     */
    private double hydrocarbonVapour(double t, int outcome, Workspace w) {
        if (outcome == SINGLE || outcome == MERGED) {
            Root root = outcome == SINGLE ? w.singleRoot : w.feedResult.feedRoot();
            Arrays.fill(w.vaporAmounts, 0.0);
            Arrays.fill(w.liquidAmounts, 0.0);
            if (root == Root.VAPOR) {
                System.arraycopy(w.feed, 0, w.vaporAmounts, 0, count);
            } else {
                System.arraycopy(w.feed, 0, w.liquidAmounts, 0, count);
                return 0.0;
            }
            evaluator.evaluate(t, w.vaporPressure, w.vaporAmounts, PhaseRoot.VAPOR, w.phases, w.vaporState);
            w.kernelEvaluations++;
        }
        double vapour = 0.0;
        for (int i = 0; i < count; i++) vapour += w.vaporAmounts[i];
        return vapour > 0.0 ? vapour * w.vaporState.molarVolume() : 0.0;
    }

    private EquilibriumResult wetResult(double t, double p, double pc, int outcome, double steam, double liquidWater,
                                        double saturation, double partial, PhaseCompetition competition, Workspace w) {
        double vapour = 0.0;
        double liquid = 0.0;
        for (int i = 0; i < count; i++) {
            vapour += w.vaporAmounts[i];
            liquid += w.liquidAmounts[i];
        }
        List<PhaseAmounts> phases = new ArrayList<>(3);
        if (vapour > 0.0 || steam > 0.0) {
            phases.add(gasPhase(t, p, pc, vapour, steam, vapour > 0.0 ? w.vaporState : null, w));
        }
        if (liquid > 0.0) {
            PhaseState state = outcome == SPLIT ? w.liquidState
                    : evaluator.evaluate(t, p, w.liquidAmounts, PhaseRoot.LIQUID, w.phases, w.liquidState);
            if (outcome != SPLIT) w.kernelEvaluations++;
            phases.add(new PhaseAmounts(PhaseKind.LIQUID, null, t, p, expand(w.liquidAmounts), state.copy()));
        }
        if (liquidWater > 0.0) phases.add(freeWaterPhase(t, p, saturation, liquidWater, w));
        Classification hydrocarbon = switch (outcome) {
            case SPLIT -> Classification.VAPOR_LIQUID;
            case MERGED -> Classification.SINGLE_FLUID;
            default -> vapour > 0.0 ? singleClassification(w.vaporState, t, pc, w) : singleClassification(w.liquidState, t, p, w);
        };
        boolean band = inBand(t, p, w) || outcome != SINGLE && w.tieLine < settings.bandTieLine()
                || outcome == SINGLE && w.presentCount > 1
                && w.feedResult.nearestStationaryDistance() < settings.bandStationaryDistance();
        String why = band ? bandReason(t, p, w, w.feedResult.nearestStationaryDistance())
                + (outcome == MERGED ? "; " + mergedDetail(w) : "") : null;
        var split = new EquilibriumResult.FreeWater(hydrocarbon, saturation, pc, partial, liquidWater, steam);
        return converged(competition, Classification.FREE_WATER, phases, List.of(), diagnostics(w, w.feedResult, band), why, split);
    }

    /**
     * The gas: hydrocarbon vapour ({@code vapour} moles, {@code hydrocarbonState} at {@code pc}) and ideal steam sharing
     * its volume, as one state over the contract basis at {@code P}: volume {@code n_V v_V(pc)} (or {@code n_w R T/P}
     * for steam alone), enthalpy, entropy and Gibbs energy the sums of both parts (steam at its partial pressure),
     * {@code ln phi_i} from each component's chemical potential against the ideal gas at {@code P}.
     */
    private PhaseAmounts gasPhase(double t, double p, double pc, double vapour, double steam, PhaseState hydrocarbonState,
                                  Workspace w) {
        double rt = R * t;
        double volume = vapour > 0.0 ? vapour * hydrocarbonState.molarVolume() : steam * rt / p;
        double partial = steam > 0.0 ? steam * rt / volume : 0.0;
        double total = vapour + steam;
        IdealGasFunction steamGas = freeWater.vapor();
        double enthalpy = 0.0;
        double entropy = 0.0;
        double gibbs = 0.0;
        if (vapour > 0.0) {
            enthalpy += vapour * hydrocarbonState.molarEnthalpy();
            entropy += vapour * hydrocarbonState.molarEntropy();
            gibbs += vapour * hydrocarbonState.molarGibbsEnergy();
        }
        double steamEnthalpy = 0.0;
        double steamGibbs = 0.0;
        if (steam > 0.0) {
            steamEnthalpy = steamGas.enthalpy(t);
            double steamEntropy = steamGas.entropy(t, partial);
            steamGibbs = steamEnthalpy - t * steamEntropy;
            enthalpy += steam * steamEnthalpy;
            entropy += steam * steamEntropy;
            gibbs += steam * steamGibbs;
        }
        Arrays.fill(w.gasComposition, 0.0);
        Arrays.fill(w.gasLogPhi, 0.0);
        Arrays.fill(w.gasIdealGibbs, Double.NaN);
        double idealEnthalpy = 0.0;
        double idealEntropy = 0.0;
        for (int i = 0; i < count; i++) {
            double amount = vapour > 0.0 ? w.vaporAmounts[i] : 0.0;
            if (!(amount > 0.0)) continue;
            int slot = basisOfComponent[i];
            double y = amount / total;
            IdealGasFunction gas = evaluator.idealGas(i);
            double h = gas.enthalpy(t);
            double s = gas.entropy(t, p);
            w.gasComposition[slot] = y;
            w.gasIdealGibbs[slot] = h - t * s;
            idealEnthalpy += y * h;
            idealEntropy += y * (s - R * Math.log(y));
            w.gasLogPhi[slot] = (hydrocarbonState.chemicalPotential(i) - w.gasIdealGibbs[slot]) / rt - Math.log(y);
        }
        if (steam > 0.0) {
            double y = steam / total;
            double s = steamGas.entropy(t, p);
            w.gasComposition[waterIndex] = y;
            w.gasIdealGibbs[waterIndex] = steamEnthalpy - t * s;
            idealEnthalpy += y * steamEnthalpy;
            idealEntropy += y * (s - R * Math.log(y));
            w.gasLogPhi[waterIndex] = (steamGibbs - w.gasIdealGibbs[waterIndex]) / rt - Math.log(y);
        }
        int roots = vapour > 0.0 ? hydrocarbonState.physicalRootCount() : 1;
        w.gasState.set(PhaseKind.VAPOR, null, PhaseRoot.VAPOR, roots, t, p, volume / total, idealEnthalpy,
                enthalpy / total - idealEnthalpy, idealEntropy, entropy / total - idealEntropy, gibbs / total,
                w.gasComposition, w.gasLogPhi, w.gasIdealGibbs);
        double[] amounts = new double[basis];
        if (vapour > 0.0) for (int i = 0; i < count; i++) amounts[basisOfComponent[i]] = w.vaporAmounts[i];
        amounts[waterIndex] = steam;
        return new PhaseAmounts(PhaseKind.VAPOR, null, t, p, amounts, w.gasState.copy());
    }

    /**
     * Free liquid water at (T, P) from the water model, with the rule's fugacity {@code p_sat} (so
     * {@code ln phi = ln(p_sat/P)} and {@code mu = g_ig(T, p_sat)}): the steam it coexists with has the same chemical
     * potential exactly when {@code p_w = p_sat}, as the rule sets it.
     */
    private PhaseAmounts freeWaterPhase(double t, double p, double saturation, double amount, Workspace w) {
        freeWater.liquid(t, p, w.waterProperties);
        IdealGasFunction steamGas = freeWater.vapor();
        double idealEnthalpy = steamGas.enthalpy(t);
        double idealEntropy = steamGas.entropy(t, p);
        double idealGibbs = idealEnthalpy - t * idealEntropy;
        double logPhi = Math.log(saturation / p);
        double gibbs = idealGibbs + R * t * logPhi;
        double enthalpy = w.waterProperties[1];
        double entropy = (enthalpy - gibbs) / t;
        w.oneLogPhi[0] = logPhi;
        w.oneIdealGibbs[0] = idealGibbs;
        w.waterState.set(PhaseKind.FREE_WATER, null, PhaseRoot.LIQUID, 1, t, p, w.waterProperties[0], idealEnthalpy,
                enthalpy - idealEnthalpy, idealEntropy, entropy - idealEntropy, gibbs, w.one, w.oneLogPhi, w.oneIdealGibbs);
        double[] amounts = new double[basis];
        amounts[waterIndex] = amount;
        return new PhaseAmounts(PhaseKind.FREE_WATER, null, t, p, amounts, w.waterState.copy());
    }

    // Results.

    /** EOS-basis amounts over the contract basis (water, if any, zero). */
    private double[] expand(double[] amounts) {
        double[] expanded = new double[basis];
        for (int i = 0; i < count; i++) expanded[basisOfComponent[i]] = amounts[i];
        return expanded;
    }

    private EquilibriumResult converged(PhaseCompetition competition, Classification classification,
                                        List<PhaseAmounts> phases, List<PhaseState> coexisting, Diagnostics diagnostics,
                                        String band, EquilibriumResult.FreeWater split) {
        EquilibriumResult.Coverage coverage = band == null ? convergedCoverage
                : new EquilibriumResult.Coverage(EquilibriumResult.CoverageGrade.RESEARCH_ONLY,
                        contract.fluidCoverage().evidence() + "; " + band + ": research-only"
                                + "; fluid-only: solid phases not assessed");
        String detail = band == null ? CONVERGED : CONVERGED + " (" + band + ")";
        return new EquilibriumResult(Specification.TP, EquilibriumResult.Status.CONVERGED, null, detail, null,
                contract.identity(), competition, classification, phases, coexisting, coverage, diagnostics, split);
    }

    private EquilibriumResult unsupported(Specification specification, PhaseCompetition competition,
                                          UnsupportedReason reason, String detail) {
        return EquilibriumResult.unsupported(specification, contract.identity(), competition, reason, detail);
    }

    private static Diagnostics diagnostics(Workspace w, TangentPlaneStability.Result feed, boolean band) {
        return new Diagnostics(feed == null ? null : feed.verdict(),
                feed == null ? Double.NaN : feed.minimumTangentPlaneDistance(), w.iterations, w.extrapolations,
                w.rachfordRiceSteps, w.lastChange, w.residual, w.productDistance, w.kernelEvaluations,
                w.derivativeEvaluations, w.newtonIterations, w.tieLine, band);
    }

    private Workspace own(EquilibriumService.Workspace workspace) {
        if (!(Objects.requireNonNull(workspace, "workspace") instanceof Workspace w) || w.owner != this) {
            throw new IllegalArgumentException("Workspace belongs to another service");
        }
        return w;
    }

    private static void requireSpecification(EquilibriumRequest request, Specification expected) {
        if (Objects.requireNonNull(request, "request").specification() != expected) {
            throw new IllegalArgumentException("Expected a " + expected + " request, got " + request.specification());
        }
    }

    // Dense linear algebra on the leading m x m block.

    /** Lower Cholesky factor of {@code a + mu I} into {@code factor}; false unless positive definite. */
    private static boolean factorShifted(double[] a, int m, double mu, double[] factor) {
        for (int j = 0; j < m; j++) {
            for (int i = j; i < m; i++) factor[i * m + j] = a[i * m + j] + (i == j ? mu : 0.0);
        }
        for (int j = 0; j < m; j++) {
            double pivot = factor[j * m + j];
            for (int k = 0; k < j; k++) pivot -= factor[j * m + k] * factor[j * m + k];
            if (!(pivot > 0.0) || !Double.isFinite(pivot)) return false;
            double root = Math.sqrt(pivot);
            factor[j * m + j] = root;
            for (int i = j + 1; i < m; i++) {
                double value = factor[i * m + j];
                for (int k = 0; k < j; k++) value -= factor[i * m + k] * factor[j * m + k];
                factor[i * m + j] = value / root;
            }
        }
        return true;
    }

    private static void solveCholesky(double[] factor, int m, double[] rhs) {
        for (int i = 0; i < m; i++) {
            double value = rhs[i];
            for (int k = 0; k < i; k++) value -= factor[i * m + k] * rhs[k];
            rhs[i] = value / factor[i * m + i];
        }
        for (int i = m - 1; i >= 0; i--) {
            double value = rhs[i];
            for (int k = i + 1; k < m; k++) value -= factor[k * m + i] * rhs[k];
            rhs[i] = value / factor[i * m + i];
        }
    }
}
