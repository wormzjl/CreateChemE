package com.wormzjl.createcheme.science.thermo.phase;

import com.wormzjl.createcheme.science.thermo.PengRobinsonKernel;

public final class PipCheck {
    public static void main(String[] args) {
        var k = PhaseTestSupport.classicalKernel("Methane", "Nitrogen");
        var ws = k.newWorkspace();
        var e = k.newEvaluation();
        double[][] states = {{77, 0.5e6, 0, 1}, {77, 0.1e6, 0, 1}, {300, 0.1e6, 0, 1}, {300, 1e-3, 0, 1}, {130, 6e6, 0, 1}, {150, 10e6, 0, 1},
                {250, 10e6, 1, 0}, {130, 6e6, .5, .5}, {150, 10e6, .5, .5}, {250, 10e6, .5, .5}, {126.192, 3.3958e6, 0, 1}, {190.564, 4.5992e6, 1, 0},
                {400, 3e6, .5, .5}, {110, 0.5e6, .9, .1}, {120, 3e6, .3, .7}};
        for (double[] s : states) {
            double[] z = {s[2], s[3]};
            for (var root : PengRobinsonKernel.Root.values()) {
                k.evaluate(s[0], s[1], z, root, ws, e);
                System.out.printf("%7.3f K %9.4g Pa x=[%.2f %.2f] %s roots %d Z %.5f PIP %.6f%n", s[0], s[1], z[0], z[1], root,
                        e.physicalRootCount(), e.compressibility(), PhaseIdentification.parameter(e, s[0], s[1]));
            }
        }
    }
}
