package com.wormzjl.createcheme.science.column.v3;

import java.util.Objects;

/** Immutable admission snapshot. Neural budgets never replace the caller's overall deadline. */
public record V3InitializationOptions(Mode mode, WetStart wetStart, int maximumIterations, int budgetMilliseconds,
        Correction correction, Recovery recovery) {
    public enum Mode { LNN_FIRST, LNN_ONLY, CURRENT_ONLY }
    public enum WetStart { AUTO, DRY_START, PREDICTED_WET }

    /**
     * What a failed learned correction may do with the state it reached, before its route ends.
     *
     * <p>{@link #NONE} is the frozen production rule and the default: a learned pass that fails publishes its
     * failure, {@code LNN_ONLY} stops there and {@code LNN_FIRST} restarts classically from the original
     * input with no state carried over.</p>
     *
     * <p>{@link #RAMP_HANDOFF} gives that terminal state one more use. An input carrying side draws, stage
     * heat or steam is solved classically by continuing a feature-free surrogate up to the authored
     * parameters in bounded rungs, and on the columns the learned seed cannot close by itself that ramp is
     * what closes them. The handoff re-solves the same feature-free surrogate from the failed learned state
     * instead of from a cold stage continuation, and, if that surrogate is accepted, hands it to the
     * unchanged ramp. It is skipped entirely when the learned attempt never produced a state — a rejected
     * seed, an out-of-coverage request or a request-only typed failure — and when the input carries none of
     * the three features, because then there is no ramp to hand anything to.</p>
     *
     * <p>The handoff spends the caller's own request deadline, never the learned allowance, under its own
     * declared sub-wall; it runs before the classical restart in {@code LNN_FIRST} and as the last step in
     * {@code LNN_ONLY}. A handoff that fails changes nothing about what is published except the diagnostic
     * event that records what it cost.</p>
     */
    public enum Recovery { NONE, RAMP_HANDOFF }

    /**
     * How one learned correction attempt is allowed to spend Newton iterations.
     *
     * <p>{@link #WALLS} is the frozen production rule and the default: a hard iteration cap inside a hard
     * wall-clock allowance, with no progress test of any kind. It reproduces the historical path bit for
     * bit, because every field it would consult is zero.</p>
     *
     * <p>A progress rule keeps the same base cap and, when an attempt reaches it, extends it in blocks
     * while the maximum scaled residual is still falling by {@code contractionFactor} over
     * {@code contractionWindow} iterations, never past {@code extensionMaximumIterations} and never past
     * the unchanged wall. {@code stallWindow}, {@code stallFactor} and {@code stallResidualFloor} are the
     * mirror image: an attempt whose residual has not fallen by that factor over that window stops there
     * instead of spending the rest of its cap. Extending and stopping are one rule, not two — the time the
     * stop returns is what pays for the time the extension spends.</p>
     *
     * @param extensionBlock iterations added per granted block, or zero for the frozen hard cap
     * @param extensionMaximumIterations the cap an extended attempt may never exceed
     * @param contractionWindow iterations the extension's contraction test looks back over
     * @param contractionFactor the fraction of its earlier value the residual must have reached
     * @param stallWindow iterations the early stop looks back over, or zero to never stop early
     * @param stallFactor the fraction an attempt must beat to continue
     * @param stallResidualFloor residual below which the early stop never fires
     */
    public record Correction(int extensionBlock, int extensionMaximumIterations, int contractionWindow,
            double contractionFactor, int stallWindow, double stallFactor, double stallResidualFloor) {
        /** The frozen production rule: walls only. */
        public static final Correction WALLS = new Correction(0, 0, 0, 0.0, 0, 0.0, 0.0);

        /**
         * The qualified progress rule, and the learned default since the Transformer promotion.
         *
         * <p>These are exactly the parameters the neural-budget study registered as its progress
         * correction and measured on the frozen F0 weights: extension blocks of eight iterations up to a
         * cap of forty-eight while the maximum scaled residual has fallen below half its value eight
         * iterations ago, and an early stop when it has not fallen below nine tenths of that value over
         * the same window while still above 1e-6. The base cap of sixteen and the two-second allowance
         * are unchanged, and {@link #WALLS} remains available for a caller that wants the frozen path.</p>
         */
        public static final Correction PROGRESS = new Correction(8, 48, 8, 0.5, 8, 0.9, 1e-6);

        public Correction {
            if (extensionBlock < 0 || extensionMaximumIterations < 0 || contractionWindow < 0 || stallWindow < 0
                    || extensionMaximumIterations > V3ColumnCalculator.MAXIMUM_NEWTON_ITERATIONS
                    || !Double.isFinite(contractionFactor) || contractionFactor < 0.0 || contractionFactor > 1.0
                    || !Double.isFinite(stallFactor) || stallFactor < 0.0 || stallFactor > 1.0
                    || !Double.isFinite(stallResidualFloor) || stallResidualFloor < 0.0) {
                throw new IllegalArgumentException("Invalid neural correction progress rule");
            }
            if (extensionBlock > 0 && (contractionWindow < 1 || extensionMaximumIterations < 1)) {
                throw new IllegalArgumentException("A neural correction extension needs a window and a cap");
            }
        }

        public boolean extendsAttempts() { return extensionBlock > 0; }

        public boolean stopsEarly() { return stallWindow > 0; }
    }

    /** The learned default: the bundled Transformer's qualified walls and progress rule. */
    public static final V3InitializationOptions DEFAULT =
            new V3InitializationOptions(Mode.LNN_FIRST, WetStart.AUTO, 16, 2_000, Correction.PROGRESS);
    /** The classical route. It runs no learned correction, so no progress rule applies to it. */
    public static final V3InitializationOptions CURRENT =
            new V3InitializationOptions(Mode.CURRENT_ONLY, WetStart.DRY_START, 16, 2_000);

    /** The production walls; every caller that does not ask for a progress rule gets the frozen path. */
    public V3InitializationOptions(Mode mode, WetStart wetStart, int maximumIterations, int budgetMilliseconds) {
        this(mode, wetStart, maximumIterations, budgetMilliseconds, Correction.WALLS);
    }

    /** The frozen recovery rule; a caller that does not ask for one keeps the historical failure path. */
    public V3InitializationOptions(Mode mode, WetStart wetStart, int maximumIterations, int budgetMilliseconds,
            Correction correction) {
        this(mode, wetStart, maximumIterations, budgetMilliseconds, correction, Recovery.NONE);
    }

    public V3InitializationOptions {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(wetStart, "wetStart");
        Objects.requireNonNull(correction, "correction");
        Objects.requireNonNull(recovery, "recovery");
        if (maximumIterations < 1 || maximumIterations > V3ColumnCalculator.MAXIMUM_NEWTON_ITERATIONS
                || budgetMilliseconds < 1 || budgetMilliseconds > 60_000) {
            throw new IllegalArgumentException("Invalid neural initialization budget");
        }
        if (correction.extendsAttempts() && correction.extensionMaximumIterations() < maximumIterations) {
            throw new IllegalArgumentException("A neural correction extension cannot lower the base cap");
        }
    }
}
