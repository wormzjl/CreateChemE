package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3WaterProperties;
import java.util.Arrays;

/** Stage-conditioned encoding: one shared predictor serves every supported stage count. */
public final class V3GeneralNeuralFeatures {
    public static final String REVISION = "v3-stage-conditioned-1";
    public static final int LOCAL_WIDTH = 22;
    public static final int BRANCH_COUNT = 3;
    private static final double FLOW_FLOOR = 1e-8;

    private V3GeneralNeuralFeatures() {}

    public static int globalWidth(int components) { return 54 + components; }
    public static int nodeWidth(int components) { return globalWidth(components) + LOCAL_WIDTH + BRANCH_COUNT; }
    public static int outputWidth(int components) { return 2 * components + 3; }

    /** All component fractions have the same representation; no component receives a special role. */
    public static double[] global(V3ColumnInput input) {
        if (input.pumparounds().size() > 4 || input.sideDraws().size() > 3 || input.steamFeeds().size() > 2)
            throw new IllegalArgumentException("General neural topology exceeds the encoding contract");
        double[] feed = input.feedComponentMolarFlowsMolPerSecond();
        double total = Arrays.stream(feed).sum(), span = input.stageCount() + 1.0;
        double[] x = new double[globalWidth(feed.length)];
        x[0] = Math.log(total); x[1] = input.feedTemperatureKelvin();
        x[2] = input.topPressurePascal() / 1e5; x[3] = input.stagePressureDropPascal() / 1e3;
        x[4] = input.stageCount() / 64.0; x[5] = input.feedStageNumber() / span;
        for (V3ColumnSpecification spec : input.specifications()) {
            if (spec instanceof V3ColumnSpecification.CondenserOutletTemperature t) x[6] = t.kelvin();
            if (spec instanceof V3ColumnSpecification.OrganicRefluxRatio r) x[7] = r.ratio();
            if (spec instanceof V3ColumnSpecification.ReboilerDuty q) x[8] = q.watts() / total / 1e3;
        }
        for (V3SteamFeedSpec steam : input.steamFeeds()) {
            x[9] += steam.molarFlowMolPerSecond() / total;
            x[10] += steam.molarFlowMolPerSecond() * V3WaterProperties.vaporMolarEnthalpy(steam.temperatureKelvin()) / total / 1e3;
        }
        for (V3PumparoundSpec pa : input.pumparounds()) x[11] += pa.dutyWatts() / total / 1e3;
        for (V3SideDrawSpec draw : input.sideDraws()) x[12] += draw.molarFlowMolPerSecond() / total;
        x[13] = input.steamFeeds().isEmpty() ? 0 : 1;
        x[14] = input.pumparounds().size() / 4.0; x[15] = input.sideDraws().size() / 3.0;
        x[16] = input.steamFeeds().size() / 2.0;
        for (int c = 0; c < feed.length; c++) x[17 + c] = feed[c] / total;
        int k = 17 + feed.length;
        for (int p = 0; p < input.pumparounds().size(); p++) {
            var pa = input.pumparounds().get(p); int j = k + 5 * p;
            x[j] = 1; x[j + 1] = pa.returnTray() / span; x[j + 2] = pa.drawTray() / span;
            x[j + 3] = pa.dutyWatts() / total / 1e3;
            x[j + 4] = pa.split() == V3PumparoundSpec.Split.UNIFORM ? 1 : 0;
        }
        k += 20;
        for (int p = 0; p < input.sideDraws().size(); p++) {
            var draw = input.sideDraws().get(p); int j = k + 3 * p;
            x[j] = 1; x[j + 1] = draw.trayNumber() / span; x[j + 2] = draw.molarFlowMolPerSecond() / total;
        }
        k += 9;
        for (int p = 0; p < input.steamFeeds().size(); p++) {
            var steam = input.steamFeeds().get(p); int j = k + 4 * p;
            x[j] = 1; x[j + 1] = steam.stageNumber() / span;
            x[j + 2] = steam.molarFlowMolPerSecond() / total; x[j + 3] = steam.temperatureKelvin();
        }
        return x;
    }

    /** Global boundaries, local/cumulative source terms, and a condenser branch condition for each node. */
    public static double[][] nodes(V3ColumnInput input, V3CondenserPhaseBranch branch) {
        double[] global = global(input);
        double total = Arrays.stream(input.feedComponentMolarFlowsMolPerSecond()).sum();
        int count = input.stageCount() + 2, k = global.length;
        double span = count - 1.0;
        double[][] sources = new double[4][count];
        for (var pa : input.pumparounds()) for (int n = 1; n < count - 1; n++) sources[0][n] += pa.trayDutyWatts(n) / total / 1e3;
        for (var draw : input.sideDraws()) sources[1][draw.trayNumber()] += draw.molarFlowMolPerSecond() / total;
        for (var steam : input.steamFeeds()) {
            sources[2][steam.stageNumber()] += steam.molarFlowMolPerSecond() / total;
            sources[3][steam.stageNumber()] += steam.molarFlowMolPerSecond()
                    * V3WaterProperties.vaporMolarEnthalpy(steam.temperatureKelvin()) / total / 1e3;
        }
        double[][] cumulative = new double[4][count];
        for (int s = 0; s < 4; s++) for (int n = 0; n < count; n++)
            cumulative[s][n] = sources[s][n] + (n == 0 ? 0 : cumulative[s][n - 1]);
        int[] paPositions = input.pumparounds().stream().mapToInt(V3PumparoundSpec::returnTray).toArray();
        int[] steamPositions = input.steamFeeds().stream().mapToInt(V3SteamFeedSpec::stageNumber).toArray();
        int[] drawPositions = input.sideDraws().stream().mapToInt(V3SideDrawSpec::trayNumber).toArray();
        double[][] x = new double[count][nodeWidth(input.componentBasis().componentCount())];
        for (int n = 0; n < count; n++) {
            System.arraycopy(global, 0, x[n], 0, k);
            x[n][k] = n / span; x[n][k + 1] = n / 64.0;
            x[n][k + 2] = (n - input.feedStageNumber()) / span;
            x[n][k + 3] = n == 0 ? 1 : 0; x[n][k + 4] = n == count - 1 ? 1 : 0;
            x[n][k + 5] = n == input.feedStageNumber() ? 1 : 0;
            x[n][k + 6] = (input.topPressurePascal() + Math.max(0, Math.min(n, input.stageCount()) - 1)
                    * input.stagePressureDropPascal()) / 1e5;
            for (int s = 0; s < 4; s++) {
                x[n][k + 7 + 3 * s] = sources[s][n];
                x[n][k + 8 + 3 * s] = cumulative[s][n];
                x[n][k + 9 + 3 * s] = cumulative[s][count - 1] - cumulative[s][n];
            }
            x[n][k + 19] = nearestPosition(paPositions, n, span);
            x[n][k + 20] = nearestPosition(steamPositions, n, span);
            x[n][k + 21] = nearestPosition(drawPositions, n, span);
            x[n][k + LOCAL_WIDTH + branchIndex(branch)] = 1;
        }
        return x;
    }

    public static int branchIndex(V3CondenserPhaseBranch branch) {
        return switch (branch) { case LIQUID_ONLY -> 0; case TWO_PHASE -> 1; case VAPOR_ONLY -> 2; };
    }

    /** Per-component flow scaling avoids privileging large feed fractions over dilute components. */
    public static double[][] targets(V3NeuralSeed seed) {
        double[] feed = seed.input().feedComponentMolarFlowsMolPerSecond();
        double total = Arrays.stream(feed).sum();
        double[][] l = seed.liquid(), v = seed.vapor();
        double[] t = seed.temperatures(), w = seed.freeWater(); boolean[] wet = seed.wetTrays();
        double[][] y = new double[t.length][outputWidth(feed.length)];
        for (int n = 0; n < t.length; n++) {
            int k = 0; y[n][k++] = t[n];
            for (int c = 0; c < feed.length; c++) y[n][k++] = Math.log1p(l[n][c] / componentScale(feed[c], total));
            for (int c = 0; c < feed.length; c++) y[n][k++] = Math.log1p(v[n][c] / componentScale(feed[c], total));
            y[n][k++] = Math.log1p(w[n] / (total * FLOW_FLOOR)); y[n][k] = wet[n] ? 1 : 0;
        }
        return y;
    }

    static V3NeuralSeed decode(V3ColumnInput input, String revision, V3CondenserPhaseBranch branch, double[][] y) {
        double[] feed = input.feedComponentMolarFlowsMolPerSecond(); double total = Arrays.stream(feed).sum();
        int count = input.stageCount() + 2;
        if (y.length != count) throw new IllegalArgumentException("General neural output node mismatch");
        double[][] l = new double[count][feed.length], v = new double[count][feed.length];
        double[] t = new double[count], w = new double[count]; boolean[] wet = new boolean[count];
        for (int n = 0; n < count; n++) {
            if (y[n].length != outputWidth(feed.length)) throw new IllegalArgumentException("General neural output width mismatch");
            int k = 0; t[n] = y[n][k++];
            if (!Double.isFinite(t[n]) || t[n] < 100 || t[n] > 1500) throw new IllegalArgumentException("Unbounded neural temperature");
            for (int c = 0; c < feed.length; c++) {
                double prediction = flow(y[n][k++], componentScale(feed[c], total));
                l[n][c] = feed[c] == 0 ? 0 : prediction;
            }
            for (int c = 0; c < feed.length; c++) {
                double prediction = flow(y[n][k++], componentScale(feed[c], total));
                v[n][c] = feed[c] == 0 ? 0 : prediction;
            }
            double water = flow(y[n][k++], total * FLOW_FLOOR);
            if (!Double.isFinite(y[n][k])) throw new IllegalArgumentException("Nonfinite neural wet score");
            wet[n] = y[n][k] >= 0.5 && n > 0 && n < count - 1 && !input.steamFeeds().isEmpty();
            w[n] = wet[n] ? water : 0;
        }
        for (var spec : input.specifications()) if (spec instanceof V3ColumnSpecification.CondenserOutletTemperature tc) t[0] = tc.kelvin();
        if (branch == V3CondenserPhaseBranch.LIQUID_ONLY) Arrays.fill(v[0], 0);
        if (branch == V3CondenserPhaseBranch.VAPOR_ONLY) Arrays.fill(l[0], 0);
        return new V3NeuralSeed(input, revision, branch, l, v, t, w, wet);
    }

    private static double componentScale(double componentFeed, double total) { return Math.max(componentFeed, total * 1e-12) * 1e-5; }
    private static double flow(double value, double scale) {
        if (!Double.isFinite(value) || value > 30) throw new IllegalArgumentException("Unbounded neural flow");
        return scale * Math.expm1(Math.max(0, value));
    }
    private static double nearestPosition(int[] positions, int node, double span) {
        if (positions.length == 0) return 0;
        int closest = positions[0];
        for (int position : positions) if (Math.abs(node - position) < Math.abs(node - closest)) closest = position;
        return (node - closest) / span;
    }
}
