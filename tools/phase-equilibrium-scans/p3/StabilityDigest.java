package com.wormzjl.createcheme.science.thermo.phase;

import com.wormzjl.createcheme.science.thermo.TangentPlaneStability;

/** Digest of every one-pressure stability result field over the P1 scan grids: the bitwise identity check of WP6a. */
public final class StabilityDigest {
    static long h = 1125899906842597L;
    static void mix(long v) { h = 31 * h + v; h ^= (h >>> 29); }
    public static void main(String[] args) {
        var stability = new TangentPlaneStability(PhaseTestSupport.classicalKernel("Methane", "Nitrogen"));
        var w = stability.newWorkspace();
        int n = 0;
        for (double[] g : new double[][] {{95, 190, 5, 0.1e6, 5.0e6, 0.1e6, 0.02, 0.98, 0.04}, {130, 185, 5, 2.5e6, 6.0e6, 0.02e6, 0.01, 0.99, 0.02}}) {
            for (int it = 0; g[0] + it * g[2] <= g[1] + 1e-9; it++) for (int ip = 0; g[3] + ip * g[5] <= g[4] + 1e-3; ip++)
                for (int ix = 0; g[6] + ix * g[8] <= g[7] + 1e-9; ix++) {
                    double t = g[0] + it * g[2], p = g[3] + ip * g[5], x = g[6] + ix * g[8];
                    add(stability.test(t, p, new double[] {1 - x, x}, w)); n++;
                }
        }
        var service = PhaseTestSupport.networkService();
        var kernel = service.evaluator().kernel();
        var big = new TangentPlaneStability(kernel);
        var bw = big.newWorkspace();
        double[] crude = PhaseTestSupport.crudeWithNitrogen(service.contract());
        double[] feed = java.util.Arrays.copyOf(crude, kernel.componentCount());
        for (int it = 0; it <= 30; it++) for (int ip = 1; ip <= 20; ip++) { add(big.test(300 + 20 * it, 0.1e6 * ip, feed, bw)); n++; }
        // Pure components on both sides of coexistence.
        for (double p = 50e3; p <= 200e3; p += 5e3) { add(stability.test(77.355, p, new double[] {0, 1}, w)); n++; }
        System.out.println("states " + n + " digest " + Long.toHexString(h));
    }
    static void add(TangentPlaneStability.Result r) {
        mix(r.verdict().ordinal()); mix(Double.doubleToRawLongBits(r.minimumTangentPlaneDistance()));
        for (double v : r.trialComposition()) mix(Double.doubleToRawLongBits(v));
        mix(r.trialRoot().ordinal()); mix(r.feedRoot().ordinal()); mix(r.trials()); mix(r.iterations());
        mix(r.kernelEvaluations()); mix(r.derivativeEvaluations());
    }
}
