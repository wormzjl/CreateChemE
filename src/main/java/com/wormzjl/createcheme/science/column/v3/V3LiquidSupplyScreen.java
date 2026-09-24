package com.wormzjl.createcheme.science.column.v3;

import java.util.Locale;
import java.util.Objects;

/**
 * Request-only liquid-supply screen: the authored side draws against the liquid that can reach their trays.
 *
 * <p>Liquid entering tray 1 is exactly {@code R*D}, and a draw can only remove liquid that has already reached
 * its own tray. With a nonnegative bottoms rate the material cap on the distillate is
 * {@code D <= F + S - sum(d)}, cooling placed above a tray condenses vapour into extra liquid there, and below
 * the feed tray the whole feed is credited as liquid. Every term is read from the authored
 * {@link V3ColumnInput}: no flash, no property package and no solve state enter the statistic, so a verdict
 * from this screen is a property of the request in the same sense as
 * {@code V3ColumnCalculator}'s {@code totalDraw >= totalFeed} gate, which it generalises.</p>
 *
 * <p>Two tiers share the one statistic {@code rho}:</p>
 * <ul>
 *   <li>{@code rho >= 1} is physics. The cumulative withdrawal exceeds a supply bound that is already
 *       generous in every term, so no liquid balance closes and no initializer, seed or retry can help.</li>
 *   <li>{@code rho >= calibratedRatio} is calibration, not proof. Measured over 474 independently generated
 *       solvable requests (405 neural-validation inputs and the 252-case {@code g4fresh} holdout) the worst
 *       solved case reached 0.2176 and 0.2271 respectively, while never-solved cases run up to 2.5. The
 *       default 0.30 sits about 1.38x above the worst solved case and typed 16 of 405 and 11 of 252
 *       never-solved requests with zero false positives. It is the solver's demonstrated envelope rather than
 *       a physical bound, so the published detail carries the measured ratio and the threshold, and a caller
 *       may set the ratio to 0 to disable the tier entirely — the project's goal is to solve exactly these
 *       draw-wall specifications, and a research probe must be able to reach the raw solver.</li>
 * </ul>
 */
final class V3LiquidSupplyScreen {
    /**
     * Calibrated operating point of the second tier; {@code 0} disables it and leaves only {@code rho >= 1}.
     *
     * <p>Never lower this below 0.2176 without re-measuring: the first false positive appears at 0.20 on both
     * populations.</p>
     */
    static final double DEFAULT_CALIBRATED_RATIO = 0.30;

    /** Physically necessary tier; the liquid balance cannot close at or above it. */
    static final double NECESSARY_RATIO = 1.0;

    /**
     * Latent-heat credit for authored cooling above a tray, in joules per mole condensed.
     *
     * <p>Real tray heats of vaporisation in this package run 30–80 kJ/mol, so 30 kJ/mol converts a given
     * duty into the largest plausible amount of extra liquid and keeps the screen conservative. Smaller is
     * safer: it credits more liquid, raises the supply and fires less often. Measured at the default ratio,
     * all with zero false positives on the 405-case population: 20 kJ/mol caught 14, 30 caught 16, 60 caught
     * 19, 100 caught 27, and the worst protected value stayed at 0.2176 for 20–60 kJ/mol.</p>
     */
    static final double PUMPAROUND_LATENT_HEAT_JOULES_PER_MOL = 30_000.0;

    private V3LiquidSupplyScreen() {}

    /**
     * Worst cumulative-draw to liquid-supply ratio over the trays, with the tray that attains it.
     *
     * <p>{@code limitingTray} is one-based with tray 1 at the top and is zero exactly when the screen found
     * nothing to measure.</p>
     */
    record Verdict(double ratio, int limitingTray) {
        static final Verdict NONE = new Verdict(0.0, 0);

        /** Whether this request must be typed infeasible at the given calibrated ratio. */
        boolean fires(double calibratedRatio) {
            return ratio >= NECESSARY_RATIO || calibratedRatio > 0.0 && ratio >= calibratedRatio;
        }

        /** The threshold that actually fired, so the published detail names the tier it came from. */
        double firedThreshold(double calibratedRatio) {
            return calibratedRatio > 0.0 && ratio >= calibratedRatio && ratio < NECESSARY_RATIO
                    ? calibratedRatio : NECESSARY_RATIO;
        }
    }

    /**
     * Evaluates the screen statistic from the request alone.
     *
     * <p>Returns {@link Verdict#NONE} for any request with no side draws, and for a malformed request this
     * screen is not the right place to reject — a missing reflux specification or a nonpositive stage count
     * belongs to input validation, which publishes its own typed outcome.</p>
     */
    static Verdict evaluate(V3ColumnInput input) {
        Objects.requireNonNull(input, "input");
        if (input.sideDraws().isEmpty() || input.stageCount() < 1) return Verdict.NONE;
        Double reflux = organicRefluxRatio(input);
        if (reflux == null) return Verdict.NONE;

        double feed = 0.0;
        for (double flow : input.feedComponentMolarFlowsMolPerSecond()) feed += flow;
        double steam = 0.0;
        for (V3SteamFeedSpec spec : input.steamFeeds()) steam += spec.molarFlowMolPerSecond();
        double draws = 0.0;
        for (V3SideDrawSpec spec : input.sideDraws()) draws += spec.molarFlowMolPerSecond();
        if (!Double.isFinite(feed) || !Double.isFinite(steam) || !Double.isFinite(draws)) return Verdict.NONE;

        // Bottoms cannot be negative, so this is the largest distillate the material balance admits, and
        // R * D_max is therefore the largest liquid rate that can descend from the condenser.
        double maximumDistillate = Math.max(0.0, feed + steam - draws);
        double worst = 0.0;
        int limitingTray = 0;
        double cumulativeDraw = 0.0;
        for (int tray = 1; tray <= input.stageCount(); tray++) {
            for (V3SideDrawSpec spec : input.sideDraws()) {
                if (spec.trayNumber() == tray) cumulativeDraw += spec.molarFlowMolPerSecond();
            }
            if (!(cumulativeDraw > 0.0)) continue;
            double supply = reflux * maximumDistillate + steam
                    + coolingAboveWatts(input, tray) / PUMPAROUND_LATENT_HEAT_JOULES_PER_MOL
                    + (tray >= input.feedStageNumber() ? feed : 0.0);
            double ratio = supply > 0.0 ? cumulativeDraw / supply : Double.POSITIVE_INFINITY;
            if (ratio > worst) {
                worst = ratio;
                limitingTray = tray;
            }
        }
        return limitingTray == 0 ? Verdict.NONE : new Verdict(worst, limitingTray);
    }

    /**
     * Authored cooling duty placed on trays {@code 1..tray}, in watts, as a positive number.
     *
     * <p>Only cooling counts. An authored heater above the tray raises vapour rather than liquid, so crediting
     * it here would be the wrong sign, and netting it off would make the screen fire more often than the
     * measured calibration allows.</p>
     */
    private static double coolingAboveWatts(V3ColumnInput input, int tray) {
        double cooling = 0.0;
        for (V3PumparoundSpec pumparound : input.pumparounds()) {
            if (pumparound.dutyWatts() >= 0.0) continue;
            for (int zoneTray = pumparound.returnTray(); zoneTray <= Math.min(pumparound.drawTray(), tray); zoneTray++) {
                cooling += Math.max(0.0, -pumparound.trayDutyWatts(zoneTray));
            }
        }
        return Double.isFinite(cooling) ? cooling : 0.0;
    }

    private static Double organicRefluxRatio(V3ColumnInput input) {
        for (V3ColumnSpecification specification : input.specifications()) {
            if (specification instanceof V3ColumnSpecification.OrganicRefluxRatio reflux) return reflux.ratio();
        }
        return null;
    }

    /**
     * Human-readable screen diagnostic naming the measured ratio, the limiting tray and the threshold.
     *
     * <p>The two tiers are worded apart on purpose: one states a closed balance, the other states a measured
     * envelope, and a reader has to be able to tell which claim was made.</p>
     */
    static String detail(Verdict verdict, double calibratedRatio) {
        double threshold = verdict.firedThreshold(calibratedRatio);
        String withdrawn = Double.isFinite(verdict.ratio())
                ? String.format(Locale.ROOT, "%.4g of", verdict.ratio()) : "more than all of";
        String bound = threshold >= NECESSARY_RATIO
                ? String.format(Locale.ROOT, "no liquid balance closes at or above %.4g", threshold)
                : String.format(Locale.ROOT, "the solver's demonstrated liquid-supply envelope is %.4g", threshold);
        return String.format(Locale.ROOT,
                "V3 authored side draws withdraw %s the liquid that reflux, feed and authored pumparound "
                        + "condensation can deliver to tray %d; %s",
                withdrawn, verdict.limitingTray() + 1, bound);
    }

    /**
     * Validates an authored screen ratio.
     *
     * @throws IllegalArgumentException if the ratio is nonfinite or outside [0, 1]
     */
    static double requireRatio(double calibratedRatio) {
        if (!Double.isFinite(calibratedRatio) || calibratedRatio < 0.0 || calibratedRatio > 1.0) {
            throw new IllegalArgumentException("V3 liquid-supply screen ratio must be finite and in [0, 1]");
        }
        return calibratedRatio;
    }
}
