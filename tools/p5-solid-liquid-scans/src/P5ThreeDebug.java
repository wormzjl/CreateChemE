package com.wormzjl.createcheme.science.thermo.qualification;

import java.util.Arrays;

/** Exploratory: G5Support.threePhase at the given temperatures with a fresh and a shared workspace. */
public final class P5ThreeDebug {
    public static void main(String[] a) {
        var ws = G5Support.SERVICE.newWorkspace();
        for (String s : a) {
            double t = Double.parseDouble(s);
            System.out.println(t + " shared: " + Arrays.toString(G5Support.threePhase(G5Support.SERVICE, t, 1, ws))
                    + " fresh: " + Arrays.toString(G5Support.threePhase(G5Support.SERVICE, t, 1, G5Support.SERVICE.newWorkspace())));
            double[] feed = G5Support.z(0, 0.05, 0, 0.95);
            for (double p : new double[] {1e3, 0.9999e7}) {
                var r = G5Support.tp(G5Support.SERVICE, t, p, feed, ws);
                System.out.println("   " + p + ": " + r);
            }
        }
    }
}
