package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3WaterProperties;
import java.util.Arrays;

/** Versioned pre-solve features and physical-state encoding for the dense baseline model. */
public final class V3NeuralFeatures {
    public static final String REVISION = "v3-physical-dense-1";
    private V3NeuralFeatures() {}

    /** Fixed property basis and stage count are checked by the model manifest, not inferred from indices. */
    public static double[] encode(V3ColumnInput input) {
        int nodes = input.stageCount() + 2;
        double[] feed = input.feedComponentMolarFlowsMolPerSecond();
        double total = Arrays.stream(feed).sum();
        double[] x = new double[8 + feed.length + 4 * nodes];
        x[0] = Math.log(total); x[1] = input.feedTemperatureKelvin(); x[2] = input.topPressurePascal();
        x[3] = input.stagePressureDropPascal(); x[4] = input.feedStageNumber();
        for (V3ColumnSpecification spec : input.specifications()) {
            if (spec instanceof V3ColumnSpecification.CondenserOutletTemperature t) x[5] = t.kelvin();
            if (spec instanceof V3ColumnSpecification.OrganicRefluxRatio r) x[6] = r.ratio();
            if (spec instanceof V3ColumnSpecification.ReboilerDuty q) x[7] = q.watts() / total;
        }
        for (int c = 0; c < feed.length; c++) x[8 + c] = feed[c] / total;
        int offset = 8 + feed.length;
        for (V3PumparoundSpec pa : input.pumparounds()) for (int n = 1; n <= input.stageCount(); n++)
            x[offset + 4*n] += pa.trayDutyWatts(n) / total;
        for (V3SideDrawSpec draw : input.sideDraws()) x[offset + 4*draw.trayNumber() + 1] += draw.molarFlowMolPerSecond() / total;
        for (V3SteamFeedSpec steam : input.steamFeeds()) {
            x[offset + 4*steam.stageNumber() + 2] += steam.molarFlowMolPerSecond() / total;
            x[offset + 4*steam.stageNumber() + 3] += steam.molarFlowMolPerSecond()
                    * V3WaterProperties.vaporMolarEnthalpy(steam.temperatureKelvin()) / total;
        }
        return x;
    }

    public static int outputCount(V3ColumnInput input) {
        return (input.stageCount() + 2) * (2 * input.componentBasis().componentCount() + 3);
    }

    /** T, transformed L[c], transformed V[c], transformed free water, wet indicator, for each node. */
    public static double[] targets(V3NeuralSeed seed) {
        double scale = flowScale(seed.input());
        double[][] l = seed.liquid(), v = seed.vapor();
        double[] t = seed.temperatures(), w = seed.freeWater();
        boolean[] wet = seed.wetTrays();
        double[] y = new double[outputCount(seed.input())]; int k = 0;
        for (int n = 0; n < t.length; n++) {
            y[k++] = t[n];
            for (double q : l[n]) y[k++] = Math.log1p(q / scale);
            for (double q : v[n]) y[k++] = Math.log1p(q / scale);
            y[k++] = Math.log1p(w[n] / scale); y[k++] = wet[n] ? 1 : 0;
        }
        return y;
    }

    static V3NeuralSeed decode(V3ColumnInput input, String propertyRevision, V3CondenserPhaseBranch branch, double[] y) {
        if (y.length != outputCount(input)) throw new IllegalArgumentException("Model output shape mismatch");
        double[] feed = input.feedComponentMolarFlowsMolPerSecond();
        int nodes = input.stageCount() + 2, count = feed.length, k = 0;
        double scale = flowScale(input);
        double[][] l = new double[nodes][count], v = new double[nodes][count];
        double[] t = new double[nodes], w = new double[nodes]; boolean[] wet = new boolean[nodes];
        for (int n = 0; n < nodes; n++) {
            t[n] = y[k++];
            for (int c = 0; c < count; c++) { double z = y[k++]; l[n][c] = feed[c] == 0 ? 0 : flow(z, scale); }
            for (int c = 0; c < count; c++) { double z = y[k++]; v[n][c] = feed[c] == 0 ? 0 : flow(z, scale); }
            w[n] = flow(y[k++], scale);
            wet[n] = y[k++] >= 0.5 && n > 0 && n < nodes - 1 && !input.steamFeeds().isEmpty();
            if (!wet[n]) w[n] = 0;
        }
        for (V3ColumnSpecification spec : input.specifications())
            if (spec instanceof V3ColumnSpecification.CondenserOutletTemperature temperature) t[0] = temperature.kelvin();
        if (branch == V3CondenserPhaseBranch.LIQUID_ONLY) Arrays.fill(v[0], 0);
        if (branch == V3CondenserPhaseBranch.VAPOR_ONLY) Arrays.fill(l[0], 0);
        return new V3NeuralSeed(input, propertyRevision, branch, l, v, t, w, wet);
    }

    private static double flowScale(V3ColumnInput input) {
        return Arrays.stream(input.feedComponentMolarFlowsMolPerSecond()).sum() * 1e-8;
    }

    private static double flow(double z, double scale) {
        if (!Double.isFinite(z) || z > 30) throw new IllegalArgumentException("Unbounded neural flow prediction");
        return scale * Math.expm1(Math.max(0, z));
    }
}
