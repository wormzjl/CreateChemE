package com.wormzjl.createcheme.science.thermo.qualification;

/** Exploratory: the three-phase bisection at T printing any TP answer that does not converge. */
public final class P5BisectDebug {
    public static void main(String[] a) {
        double t = Double.parseDouble(a[0]);
        var ws = G5Support.SERVICE.newWorkspace();
        double[] feed = G5Support.z(0, 0.05, 0, 0.95);
        double lo = Math.log(1e3), hi = Math.log(0.9999e7);
        for (int k = 0; k < 48; k++) {
            double mid = 0.5 * (lo + hi);
            var r = G5Support.tp(G5Support.SERVICE, t, Math.exp(mid), feed, ws);
            if (!r.converged()) { System.out.println("P " + Math.exp(mid) + ": " + r.detail()); return; }
            if (G5Support.hasLiquid(r)) hi = mid; else lo = mid;
        }
        System.out.println("P3 " + Math.exp(0.5 * (lo + hi)));
    }
}
