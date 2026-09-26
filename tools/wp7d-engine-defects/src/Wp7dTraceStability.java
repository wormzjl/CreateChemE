package com.wormzjl.createcheme.science.thermo;

import com.wormzjl.createcheme.science.thermo.phase.PhaseIdentification;
import java.util.Arrays;
import java.util.Objects;

/**
 * Michelsen's (1982a) tangent-plane test of whether one phase of a feed is stable, on the PR78 kernel's own
 * fugacity coefficients and its analytic composition derivatives.
 *
 * <p>The feed {@code z} is evaluated on its physical root with the lower Gibbs energy, which fixes the tangent
 * plane {@code d_i = ln z_i + ln phi_i(z)}. A trial phase of mole numbers {@code W} is measured by the modified
 * tangent-plane distance {@code tm(W) = 1 + sum_i W_i (ln W_i + ln phi_i(W) - d_i - 1)}; its minimum over the
 * amount scale is {@code 1 - exp(-D(w))} with {@code D} the reduced tangent-plane distance of the normalised
 * composition, so {@code tm(W) < 0} at any {@code W} proves the feed unstable, and the feed is stable exactly when
 * no {@code W} makes it negative. Every trial phase is evaluated on its own lower-Gibbs root, which is the
 * minimum of {@code tm} over both roots at that composition.</p>
 *
 * <p>Trials run in a fixed order: Wilson vapour-like {@code W = z K}, Wilson liquid-like {@code W = z / K}, and,
 * when at most {@link Settings#pureComponentTrialLimit()} components are present, each pure present component in
 * basis order. A trial iterates {@code ln W_i = d_i - ln phi_i(W)} by successive substitution with a fixed-cycle
 * dominant-eigenvalue extrapolation, then switches to Newton on {@code alpha_i = 2 sqrt(W_i)} with the Hessian
 * {@code delta_ij (1 + g_i / 2) + sqrt(w_i w_j) d ln phi_i/d n_j} from the kernel's analytic derivatives, a
 * decrease test on {@code tm} and step halving. A trial ends converged, at the trivial solution {@code W = z},
 * or out of iterations.</p>
 *
 * <p>Components absent from the feed stay absent from every trial. A feed with one present component is decided
 * by comparing its two roots' fugacities directly, which is always a stable single phase on the lower root.
 * Deterministic: no randomness, a fixed trial order, and one caller-owned {@link Workspace} whose only state
 * carried between calls is the kernel's prepared temperature, which is recomputed bit-identically.</p>
 *
 * <p>Per-branch pressures ({@link #test(double, double, double, double[], Workspace)}, P3): the fluid network's
 * separate free-water rule evaluates a hydrocarbon liquid at the state pressure {@code P} and a hydrocarbon vapour at
 * its partial pressure {@code pc = P - p_w}, because ideal steam shares the gas volume. The test then compares
 * fugacities, not fugacity coefficients: every liquid-branch evaluation (the kernel's {@code LIQUID} root) is made at
 * the liquid pressure, every vapour-branch evaluation ({@code VAPOR} root) at the vapour pressure, and the vapour
 * branch carries {@code ln(pc/P)} (plus {@code (pc - P) c_i/(R T)} for a translated model) on its {@code ln phi_i}, so
 * the comparison is of {@code ln f_i = ln x_i + ln phi_i + ln P_branch} up to the common {@code ln P}. Both branches
 * are evaluated for every composition. A single physical root at the
 * vapour pressure counts as the vapour branch only when it is vapour-like by the phase identification parameter
 * (Venkatarathnam and Oellrich 2011, {@code PhaseIdentification}): a dense fluid at the lower pressure is the same
 * liquid at the wrong pressure, and admitting it would make every liquid look unstable against itself. With equal
 * pressures the call is the one-pressure test, bit for bit.</p>
 */
public final class Wp7dTraceStability { public static boolean TRACE = true; static void trace(String f, Object... a) { if (TRACE) System.out.println(String.format(java.util.Locale.ROOT, f, a)); }
    /** How far one extrapolation may move any {@code ln W_i}. */
    private static final double MAXIMUM_EXTRAPOLATION = 20.0;
    /** Keeps {@code exp(ln W)} a finite, positive normal double. */
    private static final double LOG_AMOUNT_BOUND = 700.0;
    /** A Newton step may shrink any {@code alpha_i} by at most this factor, i.e. {@code W_i} by its square. */
    private static final double MINIMUM_ALPHA_RATIO = 0.1;
    private static final int LINE_SEARCH_HALVINGS = 4;
    /** Roundoff allowance on the decrease test of a Newton step, relative to {@code 1 + |tm|}. */
    private static final double DECREASE_ALLOWANCE = 1.0e-12;

    public enum Verdict { STABLE, UNSTABLE, UNRESOLVED }

    /**
     * @param instabilityTolerance a trial below {@code -instabilityTolerance} proves instability
     * @param convergenceTolerance a trial is converged when {@code max |ln W_i + ln phi_i - d_i|} is below this,
     *        or when an accepted Newton step moved no {@code ln W_i} by more than this
     * @param newtonSwitch successive substitution hands over to Newton below this gradient
     * @param maximumSuccessiveSubstitutions and in any case after this many substitution steps
     * @param accelerationCycle substitution steps between two dominant-eigenvalue extrapolations
     * @param maximumIterations accepted points per trial before it is declared unresolved
     * @param trivialDistance a trial with {@code sum_i (ln W_i - ln z_i)^2} below this is at the trivial solution,
     *        once the feed itself is known to be locally stable
     * @param pureComponentTrialLimit pure-component trials run only up to this many present components
     * @param exhaustive run every trial; otherwise stop at the first that proves instability
     */
    public record Settings(double instabilityTolerance, double convergenceTolerance, double newtonSwitch,
                           int maximumSuccessiveSubstitutions, int accelerationCycle, int maximumIterations,
                           double trivialDistance, int pureComponentTrialLimit, boolean exhaustive) {
        public static final Settings DEFAULT = new Settings(1.0e-8, 1.0e-10, 1.0e-2, 30, 5, 100, 1.0e-4, 8, false);

        public Settings {
            if (!(instabilityTolerance >= 0.0) || !Double.isFinite(instabilityTolerance)
                    || !(convergenceTolerance > 0.0) || !Double.isFinite(convergenceTolerance)
                    || !(newtonSwitch > 0.0) || !Double.isFinite(newtonSwitch)
                    || !(trivialDistance > 0.0) || !Double.isFinite(trivialDistance)
                    || maximumSuccessiveSubstitutions < 1 || accelerationCycle < 2 || maximumIterations < 1
                    || pureComponentTrialLimit < 0) {
                throw new IllegalArgumentException("Invalid stability-test settings");
            }
        }

        public Settings withInstabilityTolerance(double tolerance) {
            return new Settings(tolerance, convergenceTolerance, newtonSwitch, maximumSuccessiveSubstitutions,
                    accelerationCycle, maximumIterations, trivialDistance, pureComponentTrialLimit, exhaustive);
        }

        public Settings withExhaustive(boolean all) {
            return new Settings(instabilityTolerance, convergenceTolerance, newtonSwitch, maximumSuccessiveSubstitutions,
                    accelerationCycle, maximumIterations, trivialDistance, pureComponentTrialLimit, all);
        }
    }

    /**
     * The verdict and the trial endpoint with the most negative {@code tm}. A trial that ended at the trivial
     * solution reports {@code tm = 0} with the feed composition and root.
     *
     * <p>A root is the kernel's {@link PengRobinsonKernel.Root}: {@code LIQUID} and {@code VAPOR} name the smallest
     * and the largest physical root of the cubic. Where it has one physical root both name that root and the
     * result reports {@code VAPOR}, whether the fluid is dense or not; the label is not a phase classification.</p>
     *
     * @param trialComposition normalised over the kernel's basis; zero for every component the feed lacks
     * @param trials trial phases run
     * @param iterations accepted trial points over all trials
     * @param kernelEvaluations every kernel call, value and derivative, including the feed's
     * @param derivativeEvaluations the {@code evaluateDerivatives} calls among them
     * @param nearestStationaryDistance the smallest {@code sum_i (ln W_i - ln z_i)^2} of a trial that converged to a
     *        stationary point other than the feed without proving instability (an incipient phase that does not
     *        lower the Gibbs energy); positive infinity when no trial did. Near a critical point such a point lies
     *        next to the feed; the equilibrium engine reads it to grade the critical band (P3).
     */
    public record Result(Verdict verdict, double minimumTangentPlaneDistance, double[] trialComposition,
                         PengRobinsonKernel.Root trialRoot, PengRobinsonKernel.Root feedRoot,
                         int trials, int iterations, int kernelEvaluations, int derivativeEvaluations,
                         double nearestStationaryDistance) {
        public Result {
            Objects.requireNonNull(verdict, "verdict");
            Objects.requireNonNull(trialRoot, "trialRoot");
            Objects.requireNonNull(feedRoot, "feedRoot");
            trialComposition = trialComposition.clone();
        }

        /** The P1 form: no stationary point reported. */
        public Result(Verdict verdict, double minimumTangentPlaneDistance, double[] trialComposition,
                      PengRobinsonKernel.Root trialRoot, PengRobinsonKernel.Root feedRoot,
                      int trials, int iterations, int kernelEvaluations, int derivativeEvaluations) {
            this(verdict, minimumTangentPlaneDistance, trialComposition, trialRoot, feedRoot, trials, iterations,
                    kernelEvaluations, derivativeEvaluations, Double.POSITIVE_INFINITY);
        }

        @Override public double[] trialComposition() { return trialComposition.clone(); }
    }

    private enum Outcome { TRIVIAL, CONVERGED, PROVEN, UNRESOLVED }

    private static final int NOT_COMPUTED = 0;
    private static final int LOCALLY_STABLE = 1;
    private static final int LOCALLY_UNSTABLE = 2;
    private static final int UNDETERMINED = 3;

    private final PengRobinsonKernel kernel;
    private final Settings settings;

    public Wp7dTraceStability(PengRobinsonKernel kernel) { this(kernel, Settings.DEFAULT); }

    public Wp7dTraceStability(PengRobinsonKernel kernel, Settings settings) {
        this.kernel = Objects.requireNonNull(kernel, "kernel");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public PengRobinsonKernel kernel() { return kernel; }
    public Settings settings() { return settings; }

    /** Scratch for one calling thread; reusing it makes a call allocate only its {@link Result}. */
    public Workspace newWorkspace() { return new Workspace(kernel); }

    public Result test(double temperatureKelvin, double pressurePascal, double[] feed) {
        return test(temperatureKelvin, pressurePascal, feed, newWorkspace());
    }

    /**
     * @param feed mole numbers or fractions over the kernel's basis: finite, nonnegative, not all zero
     * @throws IllegalArgumentException for such a feed, or a state outside the kernel's domain
     */
    public Result test(double temperatureKelvin, double pressurePascal, double[] feed, Workspace workspace) {
        return run(temperatureKelvin, pressurePascal, pressurePascal, false, feed, workspace);
    }

    /**
     * The test with per-branch pressures: the liquid branch (the kernel's {@code LIQUID} root) at
     * {@code liquidPressurePascal}, the vapour branch ({@code VAPOR} root, admitted with one physical root only when
     * vapour-like) at {@code vaporPressurePascal}, compared by fugacity (see the class comment). With equal pressures
     * this is {@link #test(double, double, double[], Workspace)}, bit for bit. The reported {@code feedRoot} and
     * {@code trialRoot} name the branch, and so the pressure, each composition was taken at.
     *
     * @throws IllegalArgumentException for a pressure that is not positive and finite, and as the one-pressure test
     */
    public Result test(double temperatureKelvin, double liquidPressurePascal, double vaporPressurePascal, double[] feed,
                       Workspace workspace) {
        return test(temperatureKelvin, liquidPressurePascal, vaporPressurePascal, null, feed, workspace);
    }

    /**
     * The per-branch test for a volume-translated model: with the liquid and the vapour at different pressures a
     * constant translation {@code c_i} no longer cancels, and the vapour branch carries
     * {@code ln(p_V/p_L) + (p_V - p_L) c_i/(R T)} on {@code ln phi_i} (the translated fugacity is
     * {@code x_i phi_i^PR exp(P c_i/(R T)) P}). {@code translations} (m3/mol per kernel component) may be {@code null}
     * for none; with equal pressures it plays no part and the call is the one-pressure test, bit for bit.
     */
    public Result test(double temperatureKelvin, double liquidPressurePascal, double vaporPressurePascal,
                       double[] translations, double[] feed, Workspace workspace) {
        if (!(liquidPressurePascal > 0.0) || !(vaporPressurePascal > 0.0) || !Double.isFinite(liquidPressurePascal)
                || !Double.isFinite(vaporPressurePascal)) {
            throw new IllegalArgumentException("Branch pressures must be positive and finite");
        }
        Objects.requireNonNull(workspace, "workspace");
        if (translations != null && translations.length != workspace.count) {
            throw new IllegalArgumentException("Translations have invalid length");
        }
        boolean branched = liquidPressurePascal != vaporPressurePascal;
        if (branched) {
            double logRatio = Math.log(vaporPressurePascal / liquidPressurePascal);
            double scale = (vaporPressurePascal - liquidPressurePascal)
                    / (PengRobinsonKernel.GAS_CONSTANT * temperatureKelvin);
            for (int i = 0; i < workspace.count; i++) {
                workspace.vaporShifts[i] = translations == null ? logRatio : logRatio + scale * translations[i];
            }
        }
        return run(temperatureKelvin, liquidPressurePascal, vaporPressurePascal, branched, feed, workspace);
    }

    private Result run(double temperatureKelvin, double pressurePascal, double vaporPressurePascal, boolean branched,
                       double[] feed, Workspace workspace) {
        Objects.requireNonNull(workspace, "workspace");
        if (workspace.owner != kernel) throw new IllegalArgumentException("Workspace belongs to another kernel");
        Workspace w = workspace;
        w.normaliseFeed(feed);
        w.temperature = temperatureKelvin;
        w.pressure = pressurePascal;
        // Equal to the pressure (the same double) on the one-pressure path, so every call below is the P1 call.
        w.vaporPressure = vaporPressurePascal;
        w.branched = branched;
        w.nearestStationary = Double.POSITIVE_INFINITY;
        w.evaluations = 0;
        w.derivativeEvaluations = 0;
        w.iterations = 0;
        w.localStability = NOT_COMPUTED;
        // Only present entries are written below; the others must be the zeros the kernel is handed.
        Arrays.fill(w.current.amounts, 0.0);
        Arrays.fill(w.candidate.amounts, 0.0);
        kernel.prepareTemperature(temperatureKelvin, w.mixture);

        // The feed on its lower-Gibbs physical root; a tie keeps the vapour root.
        kernel.evaluate(temperatureKelvin, vaporPressurePascal, w.feed, PengRobinsonKernel.Root.VAPOR, w.mixture, w.feedVapor);
        w.evaluations++;
        PengRobinsonKernel.Evaluation feedEvaluation = w.feedVapor;
        w.feedRoot = PengRobinsonKernel.Root.VAPOR;
        boolean twoRoots = w.feedVapor.physicalRootCount() > 1;
        if (branched) {
            // Both branches always exist as evaluations; the vapour one counts only if it is vapour-like.
            kernel.evaluate(temperatureKelvin, pressurePascal, w.feed, PengRobinsonKernel.Root.LIQUID, w.mixture, w.feedLiquid);
            w.evaluations++;
            boolean vapourAdmissible = admissible(w, w.feedVapor, PengRobinsonKernel.Root.VAPOR);
            if (!vapourAdmissible || w.residualGibbs(w.feedLiquid, w.feed, 1.0)
                    < w.residualGibbs(w.feedVapor, w.feed, 1.0) + w.branchShift(w.feed, 1.0)) {
                feedEvaluation = w.feedLiquid;
                w.feedRoot = PengRobinsonKernel.Root.LIQUID;
            }
            twoRoots = vapourAdmissible;
        } else if (twoRoots) {
            kernel.evaluate(temperatureKelvin, pressurePascal, w.feed, PengRobinsonKernel.Root.LIQUID, w.mixture, w.feedLiquid);
            w.evaluations++;
            if (w.residualGibbs(w.feedLiquid, w.feed, 1.0) < w.residualGibbs(w.feedVapor, w.feed, 1.0)) {
                feedEvaluation = w.feedLiquid;
                w.feedRoot = PengRobinsonKernel.Root.LIQUID;
            }
        }
        if (w.presentCount == 1) return pureComponent(w, feedEvaluation, twoRoots);

        double[] feedLogPhi = feedEvaluation.logFugacityCoefficientsView();
        for (int a = 0; a < w.presentCount; a++) {
            int i = w.present[a];
            w.reference[i] = branched ? w.logFeed[i] + (feedLogPhi[i] + w.shift(w.feedRoot, i))
                    : w.logFeed[i] + feedLogPhi[i];
        }
        // Raoult with the vapour at its own pressure: y_i p_V = x_i p_sat,i for both Wilson trials.
        kernel.wilsonK(temperatureKelvin, vaporPressurePascal, w.wilson);

        double bestDistance = Double.POSITIVE_INFINITY;
        PengRobinsonKernel.Root bestRoot = w.feedRoot;
        System.arraycopy(w.feed, 0, w.best, 0, w.count);
        boolean proven = false;
        boolean unresolved = false;
        int trialCount = 2 + (w.presentCount <= settings.pureComponentTrialLimit() ? w.presentCount : 0);
        int trials = 0;
        for (int trial = 0; trial < trialCount; trial++) {
            Outcome outcome = runTrial(w, trial); trace(" trial %d outcome %s", trial, outcome);
            trials++;
            Point end = w.current;
            if (outcome == Outcome.TRIVIAL) {
                if (0.0 < bestDistance) {
                    bestDistance = 0.0;
                    bestRoot = w.feedRoot;
                    System.arraycopy(w.feed, 0, w.best, 0, w.count);
                }
            } else if (end.distance < bestDistance) {
                bestDistance = end.distance;
                bestRoot = end.root;
                for (int i = 0; i < w.count; i++) w.best[i] = end.amounts[i] / end.total;
            }
            if (outcome == Outcome.PROVEN) {
                proven = true;
                if (!settings.exhaustive()) break;
            } else if (outcome == Outcome.UNRESOLVED) {
                unresolved = true;
            } else if (outcome == Outcome.CONVERGED) {
                w.nearestStationary = Math.min(w.nearestStationary, end.trivial);
            }
        }
        Verdict verdict;
        if (proven || w.localStability == LOCALLY_UNSTABLE) verdict = Verdict.UNSTABLE;
        else if (unresolved) verdict = Verdict.UNRESOLVED;
        else verdict = Verdict.STABLE;
        return new Result(verdict, bestDistance, w.best, bestRoot, w.feedRoot, trials, w.iterations,
                w.evaluations, w.derivativeEvaluations, w.nearestStationary);
    }

    /** One present component: the only other phase is the other root at the same composition. */
    private static Result pureComponent(Workspace w, PengRobinsonKernel.Evaluation feedEvaluation, boolean twoRoots) {
        if (!twoRoots) {
            return new Result(Verdict.STABLE, 0.0, w.feed, w.feedRoot, w.feedRoot, 0, 0,
                    w.evaluations, w.derivativeEvaluations);
        }
        int k = w.present[0];
        PengRobinsonKernel.Evaluation other = feedEvaluation == w.feedVapor ? w.feedLiquid : w.feedVapor;
        // The other root's stationary amount is exp(-gap), so its tm is 1 - exp(-gap) with gap >= 0.
        double gap = w.branched
                ? (other.logFugacityCoefficient(k) + w.shift(opposite(w.feedRoot), k))
                        - (feedEvaluation.logFugacityCoefficient(k) + w.shift(w.feedRoot, k))
                : other.logFugacityCoefficient(k) - feedEvaluation.logFugacityCoefficient(k);
        return new Result(Verdict.STABLE, -Math.expm1(-gap), w.feed, opposite(w.feedRoot), w.feedRoot, 1, 0,
                w.evaluations, w.derivativeEvaluations);
    }

    private Outcome runTrial(Workspace w, int trial) {
        Point seed = w.current;
        PengRobinsonKernel.Root preferred;
        if (trial < 2) {
            preferred = trial == 0 ? PengRobinsonKernel.Root.VAPOR : PengRobinsonKernel.Root.LIQUID;
            double sign = trial == 0 ? 1.0 : -1.0;
            for (int a = 0; a < w.presentCount; a++) {
                int i = w.present[a];
                seed.logAmounts[i] = bounded(w.logFeed[i] + sign * Math.log(w.wilson[i]));
            }
            evaluate(w, seed, preferred, false, true);
        } else {
            preferred = opposite(w.feedRoot);
            int pure = w.present[trial - 2];
            Arrays.fill(seed.amounts, 0.0);
            seed.amounts[pure] = 1.0;
            for (int a = 0; a < w.presentCount; a++) {
                int i = w.present[a];
                seed.logAmounts[i] = i == pure ? 0.0 : Double.NEGATIVE_INFINITY;
            }
            evaluate(w, seed, preferred, false, false);
        }
        double threshold = -settings.instabilityTolerance();
        boolean proven = seed.distance < threshold;

        // The first substitution step leaves the seed, whose absent entries have no finite logarithm.
        Point next = w.candidate;
        for (int a = 0; a < w.presentCount; a++) {
            int i = w.present[a];
            next.logAmounts[i] = bounded(w.reference[i] - logPhi(w, seed, i));
        }
        evaluate(w, next, seed.root, false, true);
        w.accept();
        int iterations = 1;
        w.iterations++;
        int substitutions = 1;
        boolean newton = false;
        boolean hasPrevious = false;
        int sinceAcceleration = 0;
        boolean newtonConverged = false;
        while (true) {
            Point p = w.current; trace("  trial %d it %3d %s tm %+.6e err %.3e trivial %.3e x0 %.6f root %s diff %s", trial, iterations, newton ? "N" : "S", p.distance, p.error, p.trivial, p.amounts[w.present[0]] / p.total, p.root, p.differentiated);
            if (p.distance < threshold) proven = true;
            if (p.error < settings.convergenceTolerance() || newtonConverged) {
                if (!proven && p.trivial < settings.trivialDistance()) {
                    // A converged trivial trial proves nothing by itself; the feed's own curvature decides.
                    feedLocallyStable(w);
                    return Outcome.TRIVIAL;
                }
                return proven ? Outcome.PROVEN : Outcome.CONVERGED;
            }
            if (!proven && p.trivial < settings.trivialDistance() && p.distance >= threshold && feedLocallyStable(w)) {
                return Outcome.TRIVIAL;
            }
            if (iterations >= settings.maximumIterations()) return proven ? Outcome.PROVEN : Outcome.UNRESOLVED;
            if (!newton && (p.error < settings.newtonSwitch() || substitutions >= settings.maximumSuccessiveSubstitutions())) {
                newton = true;
            }
            boolean advanced = false;
            if (newton && p.differentiated) {
                double moved = newtonStep(w);
                advanced = moved >= 0.0;
                // Only an undamped step measures the distance to the solution.
                newtonConverged = advanced && w.fullStep && moved < settings.convergenceTolerance();
                if (advanced) {
                    hasPrevious = false;
                    sinceAcceleration = 0;
                }
            }
            if (!advanced) {
                // Substitution, extrapolated every cycle of consecutive substitution steps. Newton mode keeps
                // the cycle: where the trial's own Hessian is indefinite (next to a critical point) Newton is
                // refused at every point and the extrapolation is what still makes progress.
                Point c = w.candidate;
                double[] step = w.step;
                double largest = 0.0;
                for (int a = 0; a < w.presentCount; a++) {
                    step[a] = -p.gradient[w.present[a]];
                    largest = Math.max(largest, Math.abs(step[a]));
                }
                boolean accelerated = false;
                if (hasPrevious && ++sinceAcceleration >= settings.accelerationCycle()) {
                    sinceAcceleration = 0;
                    double numerator = 0.0;
                    double denominator = 0.0;
                    for (int a = 0; a < w.presentCount; a++) {
                        numerator += step[a] * step[a];
                        denominator += w.previousStep[a] * step[a];
                    }
                    if (denominator > 0.0 && numerator < denominator) {
                        double eigenvalue = numerator / denominator;
                        double extra = eigenvalue / (1.0 - eigenvalue);
                        if (largest * extra > MAXIMUM_EXTRAPOLATION) extra = MAXIMUM_EXTRAPOLATION / largest;
                        for (int a = 0; a < w.presentCount; a++) {
                            int i = w.present[a];
                            c.logAmounts[i] = bounded(p.logAmounts[i] + (1.0 + extra) * step[a]);
                        }
                        evaluate(w, c, p.root, newton, true);
                        if (c.distance < p.distance) {
                            w.accept();
                            hasPrevious = false;
                            accelerated = true;
                        }
                    }
                }
                if (!accelerated) {
                    for (int a = 0; a < w.presentCount; a++) {
                        int i = w.present[a];
                        c.logAmounts[i] = bounded(p.logAmounts[i] + step[a]);
                    }
                    evaluate(w, c, p.root, newton, true);
                    w.accept();
                    System.arraycopy(step, 0, w.previousStep, 0, w.presentCount);
                    hasPrevious = true;
                }
                substitutions++;
            }
            iterations++;
            w.iterations++;
        }
    }

    /**
     * One damped Newton step on {@code alpha = 2 sqrt(W)} from the current, differentiated point.
     *
     * @return the largest change of any {@code ln W_i} when a step was accepted, or {@code -1} when the Hessian
     *         is not positive definite or no halving decreased {@code tm}
     */
    private double newtonStep(Workspace w) {
        Point p = w.current;
        int m = w.presentCount;
        double[] h = w.hessian;
        double[] roots = w.roots;
        double scale = Math.sqrt(p.total);
        for (int a = 0; a < m; a++) {
            int i = w.present[a];
            double root = Math.sqrt(p.amounts[i]);
            roots[a] = root / scale;
            w.alpha[a] = 2.0 * root;
            w.step[a] = -root * p.gradient[i];
        }
        for (int a = 0; a < m; a++) {
            int i = w.present[a];
            double[] rowI = p.derivatives.dLogPhiDnRowView(i);
            for (int b = 0; b <= a; b++) {
                int j = w.present[b];
                double coupling = 0.5 * (rowI[j] + p.derivatives.dLogPhiDnRowView(j)[i]) * roots[a] * roots[b];
                h[a * m + b] = a == b ? 1.0 + 0.5 * p.gradient[i] + coupling : coupling;
            }
        }
        if (!cholesky(h, m)) { trace("    newton refused: Hessian not positive definite"); return -1.0; }
        solveCholesky(h, m, w.step);
        double length = 1.0;
        for (int attempt = 0; attempt <= LINE_SEARCH_HALVINGS; attempt++, length *= 0.5) {
            Point c = w.candidate;
            for (int a = 0; a < m; a++) {
                int i = w.present[a];
                double next = Math.max(w.alpha[a] + length * w.step[a], MINIMUM_ALPHA_RATIO * w.alpha[a]);
                c.logAmounts[i] = next > 0.0
                        ? bounded(2.0 * Math.log(0.5 * next))
                        : bounded(w.reference[i] - logPhi(w, p, i));
            }
            evaluate(w, c, p.root, true, true);
            if (c.distance <= p.distance + DECREASE_ALLOWANCE * (1.0 + Math.abs(p.distance))) {
                double moved = 0.0;
                for (int a = 0; a < m; a++) {
                    int i = w.present[a];
                    moved = Math.max(moved, Math.abs(c.logAmounts[i] - p.logAmounts[i]));
                }
                w.fullStep = attempt == 0;
                w.accept();
                return moved;
            }
        }
        return -1.0;
    }

    /**
     * Whether the feed is a local minimum of {@code tm}: the trivial solution's Hessian
     * {@code delta_ij + sqrt(z_i z_j) d ln phi_i/d n_j} is positive definite. Computed once per call, and only
     * when a trial approaches the trivial solution.
     */
    private boolean feedLocallyStable(Workspace w) {
        if (w.localStability == NOT_COMPUTED) {
            try {
                kernel.evaluateDerivatives(w.temperature, w.pressureOf(w.feedRoot), w.feed, w.feedRoot, w.mixture,
                        w.feedDerivatives);
                w.evaluations++;
                w.derivativeEvaluations++;
                int m = w.presentCount;
                for (int a = 0; a < m; a++) w.roots[a] = Math.sqrt(w.feed[w.present[a]]);
                for (int a = 0; a < m; a++) {
                    int i = w.present[a];
                    double[] rowI = w.feedDerivatives.dLogPhiDnRowView(i);
                    for (int b = 0; b <= a; b++) {
                        int j = w.present[b];
                        double coupling = 0.5 * (rowI[j] + w.feedDerivatives.dLogPhiDnRowView(j)[i])
                                * w.roots[a] * w.roots[b];
                        w.hessian[a * m + b] = a == b ? 1.0 + coupling : coupling;
                    }
                }
                w.localStability = cholesky(w.hessian, m) ? LOCALLY_STABLE : LOCALLY_UNSTABLE;
            } catch (IllegalStateException coalescing) {
                w.evaluations++;
                w.derivativeEvaluations++;
                w.localStability = UNDETERMINED;
            }
        }
        return w.localStability == LOCALLY_STABLE;
    }

    /**
     * Evaluates a trial point on its lower-Gibbs root and fills its gradient {@code g_i = ln W_i + ln phi_i - d_i},
     * {@code tm}, the largest {@code |g_i|} and the squared log distance from the feed.
     *
     * @param fromLogarithms take the amounts from {@code logAmounts}; otherwise they are already set
     */
    private void evaluate(Workspace w, Point c, PengRobinsonKernel.Root preferred, boolean differentiate,
                          boolean fromLogarithms) {
        double total = 0.0;
        if (fromLogarithms) {
            for (int a = 0; a < w.presentCount; a++) {
                int i = w.present[a];
                double amount = Math.exp(c.logAmounts[i]);
                c.amounts[i] = amount;
                total += amount;
            }
        } else {
            for (int a = 0; a < w.presentCount; a++) total += c.amounts[w.present[a]];
        }
        c.total = total;
        double t = w.temperature;
        double pressure = w.pressure;
        c.differentiated = false;
        if (differentiate) {
            try {
                w.evaluations++;
                w.derivativeEvaluations++;
                kernel.evaluateDerivatives(t, w.pressureOf(preferred), c.amounts, preferred, w.mixture, c.derivatives);
                c.differentiated = true;
            } catch (IllegalStateException coalescing) {
                // The root is too flat to differentiate; the value path below still evaluates it.
            }
        }
        PengRobinsonKernel.Evaluation chosen;
        if (c.differentiated) {
            chosen = c.derivatives.evaluation();
        } else {
            kernel.evaluate(t, w.pressureOf(preferred), c.amounts, preferred, w.mixture, c.first);
            w.evaluations++;
            chosen = c.first;
        }
        PengRobinsonKernel.Root root = preferred;
        if (w.branched) {
            // Both branches, each at its own pressure; the liquid branch is always admissible.
            PengRobinsonKernel.Root other = opposite(preferred);
            kernel.evaluate(t, w.pressureOf(other), c.amounts, other, w.mixture, c.second);
            w.evaluations++;
            boolean preferredAdmissible = admissible(w, chosen, preferred);
            boolean otherAdmissible = admissible(w, c.second, other);
            double otherShift = other == PengRobinsonKernel.Root.VAPOR ? w.branchShift(c.amounts, total) : 0.0;
            double preferredShift = preferred == PengRobinsonKernel.Root.VAPOR ? w.branchShift(c.amounts, total) : 0.0;
            if (!preferredAdmissible || otherAdmissible && w.residualGibbs(c.second, c.amounts, total) + otherShift
                    < w.residualGibbs(chosen, c.amounts, total) + preferredShift) {
                root = other;
                chosen = c.second;
                if (c.differentiated) {
                    try {
                        w.evaluations++;
                        w.derivativeEvaluations++;
                        kernel.evaluateDerivatives(t, w.pressureOf(other), c.amounts, other, w.mixture, c.derivatives);
                        chosen = c.derivatives.evaluation();
                    } catch (IllegalStateException coalescing) {
                        c.differentiated = false;
                    }
                }
            }
        } else if (chosen.physicalRootCount() > 1) {
            PengRobinsonKernel.Root other = opposite(preferred);
            kernel.evaluate(t, pressure, c.amounts, other, w.mixture, c.second);
            w.evaluations++;
            if (w.residualGibbs(c.second, c.amounts, total) < w.residualGibbs(chosen, c.amounts, total)) {
                root = other;
                chosen = c.second;
                if (c.differentiated) {
                    try {
                        w.evaluations++;
                        w.derivativeEvaluations++;
                        kernel.evaluateDerivatives(t, pressure, c.amounts, other, w.mixture, c.derivatives);
                        chosen = c.derivatives.evaluation();
                    } catch (IllegalStateException coalescing) {
                        c.differentiated = false;
                    }
                }
            }
        }
        c.root = root;
        c.logPhi = chosen.logFugacityCoefficientsView();
        double distance = 1.0 - total;
        double error = 0.0;
        double trivial = 0.0;
        for (int a = 0; a < w.presentCount; a++) {
            int i = w.present[a];
            double g = c.logAmounts[i] + logPhi(w, c, i) - w.reference[i];
            c.gradient[i] = g;
            if (c.amounts[i] > 0.0) distance += c.amounts[i] * g;
            error = Math.max(error, Math.abs(g));
            double shift = c.logAmounts[i] - w.logFeed[i];
            trivial += shift * shift;
        }
        c.distance = distance;
        c.error = error;
        c.trivial = trivial;
    }

    private static double bounded(double logAmount) {
        return Math.clamp(logAmount, -LOG_AMOUNT_BOUND, LOG_AMOUNT_BOUND);
    }

    /**
     * {@code ln phi_i} of a point on its branch: with per-branch pressures the vapour branch carries {@code ln(p_V/p_L)},
     * so the value is {@code ln(f_i/(x_i p_L))}; on the one-pressure path it is the kernel's value itself.
     */
    private static double logPhi(Workspace w, Point c, int i) {
        return w.branched ? c.logPhi[i] + w.shift(c.root, i) : c.logPhi[i];
    }

    /**
     * Whether an evaluation may stand for its branch: the liquid branch always; the vapour branch with three physical
     * roots (its largest), or with one root that is vapour-like by the phase identification parameter
     * ({@link PhaseIdentification#vapourLike(double, double)}: at most one, or at most its {@code Z}, P3 WP7c).
     */
    private static boolean admissible(Workspace w, PengRobinsonKernel.Evaluation evaluation, PengRobinsonKernel.Root root) {
        if (root == PengRobinsonKernel.Root.LIQUID || evaluation.physicalRootCount() > 1) return true;
        return PhaseIdentification.vapourLike(evaluation, w.temperature, w.vaporPressure);
    }

    private static PengRobinsonKernel.Root opposite(PengRobinsonKernel.Root root) {
        return root == PengRobinsonKernel.Root.VAPOR ? PengRobinsonKernel.Root.LIQUID : PengRobinsonKernel.Root.VAPOR;
    }

    /** In-place lower Cholesky factor of the leading {@code m x m} block; false unless positive definite. */
    private static boolean cholesky(double[] a, int m) {
        for (int j = 0; j < m; j++) {
            double pivot = a[j * m + j];
            for (int k = 0; k < j; k++) pivot -= a[j * m + k] * a[j * m + k];
            if (!(pivot > 0.0) || !Double.isFinite(pivot)) return false;
            double root = Math.sqrt(pivot);
            a[j * m + j] = root;
            for (int i = j + 1; i < m; i++) {
                double value = a[i * m + j];
                for (int k = 0; k < j; k++) value -= a[i * m + k] * a[j * m + k];
                a[i * m + j] = value / root;
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

    /** One trial point: its amounts, the root it was evaluated on and the kernel storage that evaluated it. */
    private static final class Point {
        final double[] logAmounts;
        final double[] amounts;
        final double[] gradient;
        final PengRobinsonKernel.Evaluation first;
        final PengRobinsonKernel.Evaluation second;
        final PengRobinsonKernel.Derivatives derivatives;
        double[] logPhi;
        double total;
        double distance;
        double error;
        double trivial;
        PengRobinsonKernel.Root root;
        boolean differentiated;

        Point(PengRobinsonKernel kernel, int count) {
            logAmounts = new double[count];
            amounts = new double[count];
            gradient = new double[count];
            first = kernel.newEvaluation();
            second = kernel.newEvaluation();
            derivatives = kernel.newDerivatives();
        }
    }

    public static final class Workspace {
        private final PengRobinsonKernel owner;
        private final int count;
        private final PengRobinsonKernel.Workspace mixture;
        private final PengRobinsonKernel.Evaluation feedVapor;
        private final PengRobinsonKernel.Evaluation feedLiquid;
        private final PengRobinsonKernel.Derivatives feedDerivatives;
        private final double[] feed;
        private final double[] logFeed;
        private final double[] reference;
        private final double[] wilson;
        private final double[] best;
        private final double[] step;
        private final double[] previousStep;
        private final double[] alpha;
        /** {@code sqrt} of each present component's mole fraction in the point a Hessian is built at. */
        private final double[] roots;
        private final double[] hessian;
        private final int[] present;
        private int presentCount;
        private Point current;
        private Point candidate;
        private double temperature;
        /** The one pressure, or the liquid branch's. */
        private double pressure;
        /** The vapour branch's pressure; the same double as {@link #pressure} on the one-pressure path. */
        private double vaporPressure;
        private boolean branched;
        /**
         * Per component, {@code ln(vaporPressure/pressure) + (vaporPressure - pressure) c_i/(R T)} with per-branch
         * pressures; unused on the one-pressure path.
         */
        private final double[] vaporShifts;
        private double nearestStationary;
        private PengRobinsonKernel.Root feedRoot;
        private int localStability;
        private boolean fullStep;
        private int evaluations;
        private int derivativeEvaluations;
        private int iterations;

        private Workspace(PengRobinsonKernel kernel) {
            owner = kernel;
            count = kernel.componentCount();
            mixture = kernel.newWorkspace();
            feedVapor = kernel.newEvaluation();
            feedLiquid = kernel.newEvaluation();
            feedDerivatives = kernel.newDerivatives();
            feed = new double[count];
            logFeed = new double[count];
            reference = new double[count];
            wilson = new double[count];
            best = new double[count];
            step = new double[count];
            previousStep = new double[count];
            vaporShifts = new double[count];
            alpha = new double[count];
            roots = new double[count];
            hessian = new double[count * count];
            present = new int[count];
            current = new Point(kernel, count);
            candidate = new Point(kernel, count);
        }

        private void accept() {
            Point swap = current;
            current = candidate;
            candidate = swap;
        }

        /** The pressure a root's branch is evaluated at. */
        private double pressureOf(PengRobinsonKernel.Root root) {
            return root == PengRobinsonKernel.Root.VAPOR ? vaporPressure : pressure;
        }

        /** The vapour branch's shift of component {@code i} with per-branch pressures, zero otherwise. */
        private double shift(PengRobinsonKernel.Root root, int i) {
            return branched && root == PengRobinsonKernel.Root.VAPOR ? vaporShifts[i] : 0.0;
        }

        /** {@code sum_i x_i s_i} of the vapour branch at the composition {@code amounts / total}. */
        private double branchShift(double[] amounts, double total) {
            double sum = 0.0;
            for (int a = 0; a < presentCount; a++) {
                int i = present[a];
                sum += amounts[i] / total * vaporShifts[i];
            }
            return sum;
        }

        private void normaliseFeed(double[] amounts) {
            Objects.requireNonNull(amounts, "feed");
            if (amounts.length != count) throw new IllegalArgumentException("Feed has invalid length");
            double total = 0.0;
            for (double amount : amounts) {
                if (!Double.isFinite(amount) || amount < 0.0) throw new IllegalArgumentException("Invalid feed amount");
                total += amount;
            }
            if (!(total > 0.0) || !Double.isFinite(total)) throw new IllegalArgumentException("Feed has no material");
            presentCount = 0;
            for (int i = 0; i < count; i++) {
                feed[i] = amounts[i] / total;
                if (feed[i] > 0.0) {
                    present[presentCount++] = i;
                    logFeed[i] = Math.log(feed[i]);
                } else {
                    logFeed[i] = Double.NEGATIVE_INFINITY;
                }
            }
            if (presentCount == 0) throw new IllegalArgumentException("Feed has no material");
        }

        /** {@code sum_i x_i ln phi_i}: the part of the molar Gibbs energy that differs between two roots. */
        private double residualGibbs(PengRobinsonKernel.Evaluation evaluation, double[] amounts, double total) {
            double[] logPhi = evaluation.logFugacityCoefficientsView();
            double sum = 0.0;
            for (int a = 0; a < presentCount; a++) {
                int i = present[a];
                sum += amounts[i] / total * logPhi[i];
            }
            return sum;
        }
    }
}
