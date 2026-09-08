package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Finds the free-water flows of a wet tray set by continuation, with the flows held as parameters.
 *
 * <h2>Why the simultaneous wet system stalls</h2>
 *
 * <p>The tray water balance telescopes: what condenses on tray {@code n} falls to tray {@code n + 1} and comes
 * straight back up, so {@code W_n = (steam fed at or below n) + F_(n-1)} and <strong>{@code F_n} does not
 * appear in tray {@code n}'s own saturation ratio at all</strong>. Its only effect there is thermal — it
 * carries a latent heat {@code h_v(T_(n+1)) - h_l(T_n)} out of the tray below and into the wet tray, which
 * heats the tray that is pinned to its dew point and cools the tray that feeds it. The gain of that loop is
 * close to one on a crude column, so {@code d SAT(n) / d F_n} is small and the simultaneous Jacobian is nearly
 * singular in the direction that circulates water around the pair. Measured on the literature CDU at its
 * published top-cooler duty, the Newton direction came out 1.5e6 long and every tray energy row stalled short
 * by the same 57.6 kW: the free-water unknown, being nearly free, absorbed the energy common mode instead of
 * the temperatures resolving it (see {@code documentation/V3_FREE_WATER_TRAYS_REVIEW.md} section 5).</p>
 *
 * <h2>The split</h2>
 *
 * <p>Freeze {@code F} instead. A <em>parametric</em> wet set ({@link V3WetTraySet#parametric}) carries the
 * free water through the water balance, the equilibrium dilution and the energy rows while contributing
 * neither an unknown nor a saturation row, so the system solved is the well-conditioned dry-shaped one with
 * an extra known water flow. Newton then has to place the energy where it belongs. What is left is one scalar
 * per wet tray, {@code SAT(n)(F)} evaluated on the converged parametric solve, and that is a one-dimensional
 * root find: monotone decreasing in {@code F_n}, because more circulating water means a hotter wet tray and a
 * higher water saturation pressure under it.</p>
 *
 * <p>The trays are swept top down with the others held (Gauss-Seidel), because {@code F_(n-1)} enters
 * {@code SAT(n)} directly through {@code W_n} while {@code F_(n+1)} reaches it only through the temperatures.
 * A sweep that finds the tray below the block supersaturated admits it and continues, which is how a wet zone
 * several trays deep is discovered without waiting for another attempt: a parametric tray costs no ledger
 * change, so the set may grow inside one continuation.</p>
 *
 * <p>The state this produces satisfies every row of the simultaneous wet system — the parametric rows because
 * Newton converged them, the saturation rows because the root find closed them — so the certifying solve that
 * follows starts inside its own tolerance and finishes on a verified final Newton correction.</p>
 */
final class V3FreeWaterContinuation {
    /** Parametric solves one tray may spend inside one continuation. */
    static final int MAXIMUM_SOLVES_PER_TRAY = 12;
    /** Gauss-Seidel sweeps over the wet set. */
    static final int MAXIMUM_SWEEPS = 4;
    /** Trays the continuation may admit beyond the set it was handed. */
    static final int MAXIMUM_ADMITTED_TRAYS = 6;
    /** Newton budget of one warm-started parametric solve. */
    private static final int PARAMETRIC_NEWTON_ITERATIONS = 24;
    /** The saturation rows are closed to this fraction of the convergence closure, not merely to it. */
    private static final double SATURATION_TARGET_FRACTION = 0.25;
    /** Upper search limit of a tray's free water, as a multiple of the total authored steam. */
    private static final double FREE_WATER_SEARCH_CAP_FACTOR = 8.0;
    /** Growth factor of the bracketing ramp while the tray is still supersaturated. */
    private static final double BRACKET_GROWTH = 2.0;
    /** A trial is kept this far inside the open bracket so a secant can never land on an endpoint. */
    private static final double BRACKET_MARGIN = 0.05;

    private V3FreeWaterContinuation() {}

    /**
     * The outcome of one continuation.
     *
     * @param wetTrays the solved (unknown-carrying) set the certifying solve should use
     * @param state the parametric solution, carrying its free-water flows
     * @param converged whether every saturation row was closed inside the target
     * @param event one bounded diagnostics line
     */
    record Result(V3WetTraySet wetTrays, V3DryMeshState state, boolean converged, String event) {
        Result {
            Objects.requireNonNull(wetTrays, "wetTrays");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(event, "event");
        }
    }

    static Result run(
            V3ColumnProblem untruncated,
            V3TruncationSupport support,
            V3WetTraySet wetTrays,
            V3DryMeshState seed,
            V3PengRobinsonThermo thermo,
            double feedMolarEnthalpyJoulesPerMol,
            double closureTolerance,
            V3SolveControl control) {
        Objects.requireNonNull(untruncated, "untruncated");
        Objects.requireNonNull(support, "support");
        Objects.requireNonNull(wetTrays, "wetTrays");
        Objects.requireNonNull(seed, "seed");
        Objects.requireNonNull(thermo, "thermo");
        Objects.requireNonNull(control, "control");
        if (!wetTrays.hasWetTrays()) {
            return new Result(wetTrays, seed, false, "free-water continuation declined: no wet tray");
        }
        Continuation continuation = new Continuation(untruncated, support, thermo,
                feedMolarEnthalpyJoulesPerMol, closureTolerance, control, seed);
        try {
            return continuation.run(wetTrays);
        } catch (IllegalArgumentException | com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoException unavailable) {
            String detail = unavailable.getMessage() == null ? unavailable.getClass().getSimpleName() : unavailable.getMessage();
            return new Result(wetTrays, seed, false, "free-water continuation declined: " + detail);
        }
    }

    /** One continuation's mutable working set: the free-water vector and the state that belongs to it. */
    private static final class Continuation {
        private final V3ColumnProblem untruncated;
        private final V3TruncationSupport support;
        private final V3PengRobinsonThermo thermo;
        private final double feedMolarEnthalpyJoulesPerMol;
        private final double closureTolerance;
        private final V3SolveControl control;
        private final double[] freeWater;
        private final boolean[] wet;
        private final double searchCap;
        private V3DryMeshState state;
        private V3ColumnProblem problem;
        private int solves;
        private int admitted;
        private String note = "no sensitivity measured";

        Continuation(V3ColumnProblem untruncated, V3TruncationSupport support, V3PengRobinsonThermo thermo,
                     double feedMolarEnthalpyJoulesPerMol, double closureTolerance, V3SolveControl control,
                     V3DryMeshState seed) {
            this.untruncated = untruncated;
            this.support = support;
            this.thermo = thermo;
            this.feedMolarEnthalpyJoulesPerMol = feedMolarEnthalpyJoulesPerMol;
            this.closureTolerance = closureTolerance;
            this.control = control;
            this.freeWater = new double[untruncated.topology().nodeCount()];
            this.wet = new boolean[untruncated.topology().nodeCount()];
            this.searchCap = FREE_WATER_SEARCH_CAP_FACTOR * untruncated.freeWaterFlowScaleMolPerSecond();
            this.state = seed;
        }

        Result run(V3WetTraySet initial) {
            List<Integer> trays = new ArrayList<>(initial.wetTrays());
            for (int tray : trays) wet[tray] = true;
            // The zero-water parametric problem is the dry one, and the state the refresh came from already
            // solves it, so this first solve is a warm confirmation rather than a fresh Newton.
            if (!solveParametric()) {
                return declined(initial, "the zero-water parametric solve did not converge");
            }
            for (int sweep = 0; sweep < MAXIMUM_SWEEPS; sweep++) {
                for (int index = 0; index < trays.size(); index++) {
                    int tray = trays.get(index);
                    Closure closure = closeTray(tray);
                    if (closure != Closure.CLOSED) return declined(initial, closure.detail(tray, note));
                }
                Integer admittedTray = admitNextTray(trays);
                if (admittedTray == null) {
                    return new Result(V3WetTraySet.of(untruncated.topology(), wet), state, true, event(trays, true));
                }
                trays.add(admittedTray);
            }
            return declined(initial, "the wet set did not settle in " + MAXIMUM_SWEEPS + " sweeps");
        }

        private Result declined(V3WetTraySet initial, String detail) {
            String line = "free-water continuation declined after " + solves + " parametric solves: " + detail;
            return new Result(initial, state, false, line.length() <= 256 ? line : line.substring(0, 256));
        }

        /** Why a tray's saturation row could not be closed. */
        private enum Closure {
            CLOSED,
            /**
             * The saturation ratio did not fall as the free water was ramped to the search cap. This is the
             * signature of the <em>topmost</em> tray of a wet block: the telescoped water balance gives it
             * {@code W_n = (steam fed at or below n)} exactly, with no {@code F} in it, so its saturation can
             * only move through {@code T_n} and its hydrocarbon vapour — and the free water it sheds is an
             * exchange cycle with the tray below, which absorbs it without moving either.
             */
            UNCONTROLLABLE,
            /** No parametric solve converged near the bracket. */
            UNSOLVABLE,
            /** The bracket was found but the root find ran out of its evaluation budget. */
            BUDGET;

            String detail(int tray, String note) {
                return switch (this) {
                    case CLOSED -> "tray " + tray + " closed";
                    case UNCONTROLLABLE -> "tray " + tray + " cannot be saturated by any free-water flow, "
                            + note;
                    case UNSOLVABLE -> "the parametric solve failed while bracketing tray " + tray;
                    case BUDGET -> "tray " + tray + " used its " + MAXIMUM_SOLVES_PER_TRAY
                            + " parametric solves without closing, " + note;
                };
            }
        }

        /**
         * Drives one tray's saturation row to zero by a safeguarded root find on its free water.
         *
         * <p>{@code SAT(n)} is positive on a supersaturated tray and falls as its free water rises, so the
         * bracket opens at the current flow and is grown geometrically until the sign turns; from then on the
         * step is a secant kept strictly inside the bracket, and a secant that leaves it or a parametric solve
         * that fails falls back to bisection. Each evaluation is one converged parametric solve, warm-started
         * from the previous one.</p>
         *
         * <p>A ramp that reaches the search cap with the tray still supersaturated is reported as
         * {@link Closure#UNCONTROLLABLE} together with the sensitivity it measured, rather than being pushed
         * further: the tray's saturation does not depend on its own free water and no root exists.</p>
         */
        private Closure closeTray(int tray) {
            double target = SATURATION_TARGET_FRACTION * closureTolerance;
            double flow = freeWater[tray];
            double residual = saturationResidual(tray);
            double openingFlow = flow;
            double openingResidual = residual;
            double lowFlow = Double.NaN;
            double lowResidual = Double.NaN;
            double highFlow = Double.NaN;
            double highResidual = Double.NaN;
            double previousFlow = Double.NaN;
            double previousResidual = Double.NaN;
            for (int evaluation = 0; evaluation < MAXIMUM_SOLVES_PER_TRAY; evaluation++) {
                if (!Double.isFinite(residual)) return Closure.UNSOLVABLE;
                if (Math.abs(residual) <= target) return Closure.CLOSED;
                if (residual > 0.0) {
                    if (Double.isNaN(lowFlow) || flow > lowFlow) {
                        lowFlow = flow;
                        lowResidual = residual;
                    }
                    if (flow >= searchCap) {
                        recordSensitivity(tray, openingFlow, openingResidual, flow, residual);
                        return Closure.UNCONTROLLABLE;
                    }
                } else if (Double.isNaN(highFlow) || flow < highFlow) {
                    highFlow = flow;
                    highResidual = residual;
                }
                // Two supersaturated points already say where the root would be. When the secant through
                // them puts it past the search cap the tray is uncontrollable, and walking the ramp to the
                // cap only spends parametric solves to learn the same thing.
                if (residual > 0.0 && Double.isNaN(highFlow) && Double.isFinite(previousResidual)
                        && flow > previousFlow) {
                    boolean falling = previousResidual > residual;
                    double extrapolated = falling
                            ? flow + residual * (flow - previousFlow) / (previousResidual - residual)
                            : Double.POSITIVE_INFINITY;
                    if (!(extrapolated <= searchCap)) {
                        recordSensitivity(tray, openingFlow, openingResidual, flow, residual);
                        return Closure.UNCONTROLLABLE;
                    }
                }
                double next = nextTrial(tray, flow, residual, previousFlow, previousResidual,
                        lowFlow, lowResidual, highFlow, highResidual);
                if (!(next > 0.0) || !Double.isFinite(next) || next == flow) return Closure.UNSOLVABLE;
                previousFlow = flow;
                previousResidual = residual;
                double restore = freeWater[tray];
                V3DryMeshState restoreState = state;
                freeWater[tray] = next;
                if (!solveParametric()) {
                    // A parametric solve that does not converge is not a sign, it is a step that went too far:
                    // treat the trial as an upper search limit and bisect back toward the last good flow.
                    freeWater[tray] = restore;
                    state = restoreState;
                    double bisected = 0.5 * ((Double.isNaN(lowFlow) ? 0.0 : lowFlow) + next);
                    if (!(bisected > 0.0) || bisected == restore) return Closure.UNSOLVABLE;
                    freeWater[tray] = bisected;
                    if (!solveParametric()) {
                        freeWater[tray] = restore;
                        state = restoreState;
                        return Closure.UNSOLVABLE;
                    }
                    flow = bisected;
                } else {
                    flow = next;
                }
                residual = saturationResidual(tray);
            }
            if (Math.abs(residual) <= target) return Closure.CLOSED;
            recordSensitivity(tray, openingFlow, openingResidual, flow, residual);
            return Closure.BUDGET;
        }

        /** Keeps the measured {@code d ln(ratio) / d F} of a tray that would not close, for the event line. */
        private void recordSensitivity(int tray, double fromFlow, double fromResidual, double toFlow, double toResidual) {
            double span = toFlow - fromFlow;
            String slope = span == 0.0 || !Double.isFinite(toResidual) || !Double.isFinite(fromResidual)
                    ? "no usable span"
                    : String.format(Locale.ROOT, "d ln(ratio)/dF = %.3g per kmol/h",
                            (toResidual - fromResidual) / (span * 3.6));
            note = String.format(Locale.ROOT, "ln(ratio) %.6g -> %.6g over F %.0f -> %.0f kmol/h (%s)",
                    fromResidual, toResidual, fromFlow * 3.6, toFlow * 3.6, slope);
        }

        private double nextTrial(
                int tray, double flow, double residual, double previousFlow, double previousResidual,
                double lowFlow, double lowResidual, double highFlow, double highResidual) {
            if (!Double.isNaN(lowFlow) && !Double.isNaN(highFlow) && highFlow > lowFlow) {
                double span = highFlow - lowFlow;
                double lowest = lowFlow + BRACKET_MARGIN * span;
                double highest = highFlow - BRACKET_MARGIN * span;
                if (Double.isFinite(lowResidual) && Double.isFinite(highResidual) && lowResidual != highResidual) {
                    double secant = lowFlow + span * lowResidual / (lowResidual - highResidual);
                    if (secant > lowest && secant < highest) return secant;
                }
                if (Double.isFinite(previousResidual) && previousResidual != residual && previousFlow != flow) {
                    double secant = flow - residual * (flow - previousFlow) / (residual - previousResidual);
                    if (secant > lowest && secant < highest) return secant;
                }
                return 0.5 * (lowFlow + highFlow);
            }
            if (!Double.isNaN(highFlow)) {
                // Below the root already and no lower bracket: halve toward zero.
                return 0.5 * highFlow;
            }
            // Still supersaturated everywhere tried. Open the bracket from the water the tray holds above
            // saturation — the right magnitude even though the mechanism is thermal — and then double.
            double opening = flow > 0.0 ? BRACKET_GROWTH * flow : excessWater(tray);
            return Math.min(searchCap, opening);
        }

        /** The water this tray's vapour carries above its saturation line, floored into the positive domain. */
        private double excessWater(int tray) {
            double water = waterVaporFlow(tray);
            V3WetTraySet.Saturation saturation = V3WetTraySet.saturation(problem, state, tray, water);
            double floor = Math.max(Double.MIN_NORMAL,
                    V3WetTraySet.MINIMUM_FREE_WATER_FRACTION * untruncated.freeWaterFlowScaleMolPerSecond());
            if (saturation == null) return floor;
            double excess = water - saturation.saturatedWaterMolPerSecond();
            return Double.isFinite(excess) && excess > floor ? excess : floor;
        }

        /**
         * Admits the first supersaturated dry tray directly below the wet block, or null when there is none.
         *
         * <p>A parametric tray changes no unknown and no row, so admitting one costs nothing structurally and
         * the wet zone can be discovered to its full depth inside one continuation instead of one tray per
         * refresh. The tray below a wet one is the only candidate: it is the tray the free water falls onto.</p>
         */
        private Integer admitNextTray(List<Integer> trays) {
            if (admitted >= MAXIMUM_ADMITTED_TRAYS) return null;
            for (int tray = 1; tray < untruncated.topology().trayCount(); tray++) {
                int candidate = tray + 1;
                if (!wet[tray] || wet[candidate]) continue;
                V3WetTraySet.Saturation saturation = V3WetTraySet.saturation(
                        problem, state, candidate, waterVaporFlow(candidate));
                if (saturation == null || !(saturation.ratio() > 1.0 + V3WetTraySet.WET_ENTRY_HYSTERESIS)) continue;
                wet[candidate] = true;
                admitted++;
                if (!solveParametric()) {
                    wet[candidate] = false;
                    admitted--;
                    return null;
                }
                return candidate;
            }
            return null;
        }

        /** {@code ln(P_n W_n / ((V_hc,n + W_n) P_sat(T_n)))} on the current parametric solution. */
        private double saturationResidual(int tray) {
            double water = waterVaporFlow(tray);
            V3WetTraySet.Saturation saturation = V3WetTraySet.saturation(problem, state, tray, water);
            return saturation == null ? Double.NaN : Math.log(saturation.ratio());
        }

        private double waterVaporFlow(int node) {
            return untruncated.waterVaporFlowMolPerSecond(node) + (node >= 2 ? freeWater[node - 1] : 0.0);
        }

        /** Solves the dry-shaped system with the current free water frozen, warm-started from the last state. */
        private boolean solveParametric() {
            control.checkpoint();
            V3WetTraySet parametric = V3WetTraySet.parametric(untruncated.topology(), wet, freeWater);
            V3ColumnProblem parametricProblem;
            try {
                parametricProblem = V3ColumnProblemResolver.withTruncation(untruncated, support, parametric);
            } catch (IllegalArgumentException invalidLedger) {
                return false;
            }
            V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(
                    parametricProblem, thermo, feedMolarEnthalpyJoulesPerMol);
            V3DryMeshCoordinateMap coordinates = new V3DryMeshCoordinateMap(parametricProblem);
            V3DryMeshState seed = parametric.seed(parametricProblem, state);
            solves++;
            V3SimultaneousColumnSolver.Attempt attempt;
            try {
                attempt = V3SimultaneousColumnSolver.solve(parametricProblem, evaluator, coordinates, seed,
                        thermo::newWorkspace, V3ConvergenceEvidence.unavailable(closureTolerance),
                        PARAMETRIC_NEWTON_ITERATIONS, closureTolerance,
                        V3FiniteDifferenceJacobian.DifferenceScale.FINE, control);
            } catch (IllegalArgumentException | com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoException unavailable) {
                return false;
            }
            if (!(attempt instanceof V3SimultaneousColumnSolver.Attempt.Converged converged)) return false;
            state = converged.state();
            problem = parametricProblem;
            return true;
        }

        private String event(List<Integer> trays, boolean closed) {
            StringBuilder flows = new StringBuilder();
            double worst = 0.0;
            for (int tray : trays) {
                if (flows.length() > 0) flows.append('/');
                flows.append(String.format(Locale.ROOT, "%.1f", freeWater[tray] * 3.6));
                double residual = Math.abs(saturationResidual(tray));
                if (Double.isFinite(residual)) worst = Math.max(worst, residual);
            }
            String line = String.format(Locale.ROOT,
                    "free-water continuation %s: trays %s, free water %s kmol/h, %d parametric solves, "
                            + "worst saturation %.3g",
                    closed ? "closed" : "did not close", trays, flows, solves, worst);
            return line.length() <= 256 ? line : line.substring(0, 256);
        }
    }
}
