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

    static V3NeuralSeed decode(V3ColumnInput input, String revision, V3CondenserPhaseBranch branch,
            double[][] outputs, double presenceThreshold) {
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
            liquid[n] = decodePhase(row, 3, 5 + 2*c, liquidTotal, feed, total, presenceThreshold);
            vapor[n] = decodePhase(row, 3 + c, 5 + 3*c, vaporTotal, feed, total, presenceThreshold);
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
            double[] feed, double totalFeed, double presenceThreshold) {
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
        double retained = 0;
        for (int c = 0; c < feed.length; c++) {
            flows[c] *= total / sum;
            double floor = Math.max(feed[c], totalFeed * 1e-12) * TRACE_FLOOR_FRACTION;
            if (flows[c] < floor) flows[c] = 0;
            retained += flows[c];
        }
        // This only forms a seed. The unchanged native support refresh and final physical audits decide acceptance.
        if (retained > 0) for (int c = 0; c < flows.length; c++) flows[c] *= total / retained;
        return flows;
    }
}
