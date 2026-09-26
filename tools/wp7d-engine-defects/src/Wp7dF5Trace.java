package com.wormzjl.createcheme.science.thermo;

import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.thermo.phase.CubicPhaseEvaluator;

/** WP7d probe: the iteration trace of the feed's stability test at the F5 state (a traced copy of the P1 test). */
public final class Wp7dF5Trace {
    public static void main(String[] args) {
        double t = args.length > 0 ? Double.parseDouble(args[0]) : 150.0;
        double p = args.length > 1 ? Double.parseDouble(args[1]) : 4.69e6;
        double x = args.length > 2 ? Double.parseDouble(args[2]) : 0.67;
        var evaluator = CubicPhaseEvaluator.forPackage(MaterialCatalog.bundled(), "createcheme:pilot_cryogenic");
        double[] feed = new double[evaluator.componentCount()];
        feed[0] = x;
        feed[1] = 1 - x;
        var d = Wp7dTraceStability.Settings.DEFAULT;
        var s = new Wp7dTraceStability.Settings(d.instabilityTolerance(), d.convergenceTolerance(), d.newtonSwitch(),
                d.maximumSuccessiveSubstitutions(), d.accelerationCycle(), args.length > 3 ? Integer.parseInt(args[3]) : 100,
                d.trivialDistance(), d.pureComponentTrialLimit(), true);
        var test = new Wp7dTraceStability(evaluator.kernel(), s);
        var r = test.test(t, p, feed, test.newWorkspace());
        System.out.println("verdict " + r.verdict() + " tm " + r.minimumTangentPlaneDistance() + " iterations " + r.iterations());
    }
}
