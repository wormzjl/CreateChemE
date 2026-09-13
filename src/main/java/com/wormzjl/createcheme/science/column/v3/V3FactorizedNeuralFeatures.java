package com.wormzjl.createcheme.science.column.v3;

import java.util.Arrays;

/** Gen3 targets separate phase traffic from normalized compositions and optional learned seed support. */
public final class V3FactorizedNeuralFeatures {
    public static final String REVISION = "v3-factorized-stage-1";
    public static final double TRACE_FLOOR_FRACTION = 1e-10;
    private static final double MAXIMUM_LOG_TOTAL = Math.log1p(1000);

    private V3FactorizedNeuralFeatures() {}

    public static int globalWidth(int components) { return V3GeneralNeuralFeatures.globalWidth(components); }
    public static int nodeWidth(int components) { return V3GeneralNeuralFeatures.nodeWidth(components); }
    public static int outputWidth(int components) { return 4 * components + 5; }
    public static double[] global(V3ColumnInput input) { return V3GeneralNeuralFeatures.global(input); }
    public static double[][] nodes(V3ColumnInput input, V3CondenserPhaseBranch branch) { return V3GeneralNeuralFeatures.nodes(input, branch); }

    /** T, log1p(L/F), log1p(V/F), centered log(x/zFeed), centered log(y/zFeed), water, wet, and signed support labels. */
    public static double[][] targets(V3NeuralSeed seed) {
        double[] feed = seed.input().feedComponentMolarFlowsMolPerSecond();
        double total = Arrays.stream(feed).sum(); int c = feed.length;
        double[][] liquid = seed.liquid(), vapor = seed.vapor();
        double[] temperatures = seed.temperatures(), water = seed.freeWater(); boolean[] wet = seed.wetTrays();
        double[][] result = new double[temperatures.length][outputWidth(c)];
        for (int n = 0; n < result.length; n++) {
            result[n][0] = temperatures[n];
            encodePhase(result[n], 1, 3, 5 + 2*c, liquid[n], feed, total);
            encodePhase(result[n], 2, 3 + c, 5 + 3*c, vapor[n], feed, total);
            result[n][3 + 2*c] = Math.log1p(water[n] / (total * 1e-8));
            result[n][4 + 2*c] = wet[n] ? 1 : 0;
        }
        return result;
    }

    private static void encodePhase(double[] target, int totalIndex, int compositionOffset, int presenceOffset,
            double[] flows, double[] feed, double totalFeed) {
        double phaseTotal = Arrays.stream(flows).sum();
        target[totalIndex] = Math.log1p(phaseTotal / totalFeed);
        double center = 0; int active = 0;
        for (int c = 0; c < feed.length; c++) {
            double floor = Math.max(feed[c], totalFeed * 1e-12) * TRACE_FLOOR_FRACTION;
            target[presenceOffset + c] = feed[c] > 0 && flows[c] >= floor ? 8 : -8;
            if (feed[c] == 0 || phaseTotal == 0) continue;
            double logRatio = Math.log(Math.max(flows[c], floor) / phaseTotal / (feed[c] / totalFeed));
            target[compositionOffset + c] = logRatio; center += logRatio; active++;
        }
        if (active > 0) for (int c = 0; c < feed.length; c++)
            if (feed[c] > 0) target[compositionOffset + c] -= center / active;
    }

    /**
     * Opt-in decoder presence floor, used only by offline research pipelines.
     *
     * <p>The production decode prunes a kept component whose decoded flow lands below its own support
     * floor to exactly zero, and the native support refresh then has to reinsert the point. This option
     * instead seeds such a component at {@code liftFactor} floors, so the point is already present when
     * the solver starts. {@link V3TruncationSupport#FLOOR_REINSERTION_FACTOR} is the hysteresis the native
     * support uses to reinsert a removed phase, which is why ten floors is the natural default: a lifted
     * trace lands exactly at the flow the support rules already treat as "carrying material again", and
     * below that it would still be inside the remove/reinsert band.</p>
     *
     * <p>{@code liftPresenceProbability} gates the rule on the presence head's own confidence, as a
     * probability rather than a logit. The decode presence threshold itself (0.02 in production) still
     * decides which components are kept at all, so a gate at 0.02 reproduces "every kept component" and a
     * gate at 0.5 lifts only confident presences. The fallback component that a positive phase total
     * rescues when every support score is uncertain is never lifted: it was not kept by the head.</p>
     *
     * <p>The lift is <b>not</b> renormalised into the phase total. The lifted mass is added after the
     * existing renormalisation, so every component that stays above its floor keeps its default decoded
     * value bit for bit and only the lifted traces differ. The phase total then exceeds the predicted
     * total by at most one lift per component, which is of order 1e-9 of the feed; the material rows are
     * Newton unknowns and absorb it, whereas renormalising would perturb every major component of the
     * phase to pay for a trace.</p>
     *
     * @param liftFactor multiple of the component's support floor to seed, or zero to prune as usual
     * @param liftPresenceProbability minimum presence probability of a kept component for the lift to apply
     */
    record DecodeOptions(double liftFactor, double liftPresenceProbability) {
        /** The unchanged production rule: below-floor flows of kept components are pruned to zero. */
        static final DecodeOptions NONE = new DecodeOptions(0, .5);

        DecodeOptions {
            if (!Double.isFinite(liftFactor) || liftFactor < 0 || liftFactor > 1e6
                    || (liftFactor > 0 && liftFactor < 1))
                throw new IllegalArgumentException("Invalid decoder presence floor lift factor");
            if (!Double.isFinite(liftPresenceProbability) || liftPresenceProbability <= 0 || liftPresenceProbability >= 1)
                throw new IllegalArgumentException("Invalid decoder presence floor gate probability");
        }

        static DecodeOptions lift(double factor, double probability) { return new DecodeOptions(factor, probability); }

        boolean liftsPresentTraces() { return liftFactor > 0; }

        /** Presence logit a kept component must reach to be lifted; unreachable while the rule is off. */
        double liftLogit() {
            return liftsPresentTraces() ? Math.log(liftPresenceProbability / (1 - liftPresenceProbability))
                    : Double.POSITIVE_INFINITY;
        }
    }

    static V3NeuralSeed decode(V3ColumnInput input, String revision, V3CondenserPhaseBranch branch,
            double[][] outputs, double presenceThreshold) {
        return decode(input, revision, branch, outputs, presenceThreshold, DecodeOptions.NONE);
    }

    static V3NeuralSeed decode(V3ColumnInput input, String revision, V3CondenserPhaseBranch branch,
            double[][] outputs, double presenceThreshold, DecodeOptions options) {
        if (options == null) throw new IllegalArgumentException("Missing learned seed decode options");
        if (!Double.isFinite(presenceThreshold) || presenceThreshold < 0 || presenceThreshold > .5)
            throw new IllegalArgumentException("Invalid learned seed presence threshold");
        double[] feed = input.feedComponentMolarFlowsMolPerSecond(); double total = Arrays.stream(feed).sum();
        int count = input.stageCount()+2, c = feed.length;
        if (outputs.length != count) throw new IllegalArgumentException("Factorized neural output node mismatch");
        double[][] liquid = new double[count][c], vapor = new double[count][c];
        double[] temperatures = new double[count], water = new double[count]; boolean[] wet = new boolean[count];
        for (int n = 0; n < count; n++) {
            double[] row = outputs[n];
            if (row.length != outputWidth(c)) throw new IllegalArgumentException("Factorized neural output width mismatch");
            for (double value : row) if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite factorized prediction");
            temperatures[n] = row[0];
            if (row[0] < 100 || row[0] > 1500) throw new IllegalArgumentException("Unbounded factorized temperature");
            double liquidTotal = phaseTotal(row[1], total), vaporTotal = phaseTotal(row[2], total);
            if (n == 0 && branch == V3CondenserPhaseBranch.LIQUID_ONLY) vaporTotal = 0;
            if (n == 0 && branch == V3CondenserPhaseBranch.VAPOR_ONLY) liquidTotal = 0;
            liquid[n] = decodePhase(row, 3, 5 + 2*c, liquidTotal, feed, total, presenceThreshold, options);
            vapor[n] = decodePhase(row, 3 + c, 5 + 3*c, vaporTotal, feed, total, presenceThreshold, options);
            double waterLog = row[3 + 2*c];
            if (waterLog > 30) throw new IllegalArgumentException("Unbounded factorized water flow");
            wet[n] = row[4 + 2*c] >= .5 && n > 0 && n < count-1 && !input.steamFeeds().isEmpty();
            water[n] = wet[n] ? Math.expm1(Math.max(0, waterLog)) * total * 1e-8 : 0;
        }
        for (var spec : input.specifications()) if (spec instanceof V3ColumnSpecification.CondenserOutletTemperature t) temperatures[0] = t.kelvin();
        return new V3NeuralSeed(input, revision, branch, liquid, vapor, temperatures, water, wet);
    }

    private static double phaseTotal(double value, double feed) {
        if (value > MAXIMUM_LOG_TOTAL) throw new IllegalArgumentException("Unbounded factorized phase total");
        return Math.expm1(Math.max(0, value)) * feed;
    }

    private static double[] decodePhase(double[] row, int compositionOffset, int presenceOffset, double total,
            double[] feed, double totalFeed, double presenceThreshold, DecodeOptions options) {
        double[] flows = new double[feed.length], logits = new double[feed.length];
        boolean[] present = new boolean[feed.length]; int fallback = -1;
        double maximum = -Double.MAX_VALUE, fallbackMaximum = -Double.MAX_VALUE;
        double thresholdLogit = presenceThreshold == 0 ? -Double.MAX_VALUE : Math.log(presenceThreshold / (1-presenceThreshold));
        for (int c = 0; c < feed.length; c++) {
            if (Math.abs(row[compositionOffset+c]) > 120 || Math.abs(row[presenceOffset+c]) > 120)
                throw new IllegalArgumentException("Unbounded factorized composition or presence logit");
            if (feed[c] == 0) continue;
            logits[c] = Math.log(feed[c] / totalFeed) + row[compositionOffset+c];
            if (fallback < 0 || logits[c] > fallbackMaximum) { fallback = c; fallbackMaximum = logits[c]; }
            present[c] = row[presenceOffset+c] >= thresholdLogit;
            if (present[c]) maximum = Math.max(maximum, logits[c]);
        }
        if (total == 0) return flows;
        // A positive phase-total head retains at least one component if all support scores are uncertain.
        if (maximum == -Double.MAX_VALUE) { present[fallback] = true; maximum = fallbackMaximum; }
        double sum = 0;
        for (int c = 0; c < feed.length; c++) if (present[c]) { flows[c] = Math.exp(logits[c]-maximum); sum += flows[c]; }
        double retained = 0, liftLogit = options.liftLogit();
        double[] lifted = null;
        for (int c = 0; c < feed.length; c++) {
            flows[c] *= total / sum;
            double floor = Math.max(feed[c], totalFeed * 1e-12) * TRACE_FLOOR_FRACTION;
            if (flows[c] < floor) {
                // Opt-in only: the gate logit is positive infinity while the rule is off, so the default
                // path below is exactly the historical prune and every retained flow is bit identical.
                if (present[c] && row[presenceOffset+c] >= liftLogit) {
                    if (lifted == null) lifted = new double[feed.length];
                    lifted[c] = floor * options.liftFactor();
                }
                flows[c] = 0;
            }
            retained += flows[c];
        }
        // This only forms a seed. The unchanged native support refresh and final physical audits decide acceptance.
        if (retained > 0) for (int c = 0; c < flows.length; c++) flows[c] *= total / retained;
        // Lifted traces are written after the renormalisation, so they add their own mass instead of taking
        // it from the components the phase total was predicted for. See DecodeOptions for why.
        if (lifted != null) for (int c = 0; c < flows.length; c++) if (lifted[c] > 0) flows[c] = lifted[c];
        return flows;
    }
}
