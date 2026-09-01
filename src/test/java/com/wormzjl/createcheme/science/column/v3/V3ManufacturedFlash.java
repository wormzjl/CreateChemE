package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3FeedPhase;
import com.wormzjl.createcheme.science.column.v3.thermo.V3FlashResult;

/** Deterministic constant-K flash used only by manufactured solver fixtures. */
final class V3ManufacturedFlash {
    private static final double ENDPOINT_TOLERANCE = 1.0e-12;

    private V3ManufacturedFlash() {}

    static V3FlashResult flash(double[] componentFlows, double[] kValues) {
        if (componentFlows.length != kValues.length) throw new IllegalArgumentException("Manufactured flash basis differs");
        double total = 0.0;
        for (double flow : componentFlows) total += flow;
        double[] overall = new double[componentFlows.length];
        for (int component = 0; component < overall.length; component++) overall[component] = componentFlows[component] / total;
        double liquidEndpoint = residual(overall, kValues, 0.0);
        double vaporEndpoint = residual(overall, kValues, 1.0);
        if (liquidEndpoint <= ENDPOINT_TOLERANCE) {
            return new V3FlashResult(V3FeedPhase.LIQUID, 0, 0.0, overall, new double[0], 0.0,
                    "manufactured all-liquid constant-K flash");
        }
        if (vaporEndpoint >= -ENDPOINT_TOLERANCE) {
            return new V3FlashResult(V3FeedPhase.VAPOR, 0, 1.0, new double[0], overall, 0.0,
                    "manufactured all-vapor constant-K flash");
        }
        double lower = 0.0;
        double upper = 1.0;
        for (int iteration = 0; iteration < 100; iteration++) {
            double midpoint = 0.5 * (lower + upper);
            if (residual(overall, kValues, midpoint) > 0.0) lower = midpoint;
            else upper = midpoint;
        }
        double beta = 0.5 * (lower + upper);
        double[] liquid = new double[overall.length];
        double[] vapor = new double[overall.length];
        double liquidTotal = 0.0;
        double vaporTotal = 0.0;
        for (int component = 0; component < overall.length; component++) {
            liquid[component] = overall[component] / (1.0 + beta * (kValues[component] - 1.0));
            vapor[component] = kValues[component] * liquid[component];
            liquidTotal += liquid[component];
            vaporTotal += vapor[component];
        }
        for (int component = 0; component < overall.length; component++) {
            liquid[component] /= liquidTotal;
            vapor[component] /= vaporTotal;
        }
        return new V3FlashResult(V3FeedPhase.TWO_PHASE, 100, beta, liquid, vapor, 0.0,
                "manufactured two-phase constant-K flash");
    }

    private static double residual(double[] overall, double[] kValues, double beta) {
        double value = 0.0;
        for (int component = 0; component < overall.length; component++) {
            value += overall[component] * (kValues[component] - 1.0)
                    / (1.0 + beta * (kValues[component] - 1.0));
        }
        return value;
    }
}
