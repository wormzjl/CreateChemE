package com.wormzjl.createcheme.science.thermo.qualification;

import com.wormzjl.createcheme.science.thermo.phase.EquilibriumRequest;
import com.wormzjl.createcheme.science.thermo.phase.PhaseCompetition;
import java.util.Locale;

/** Exploratory: the isochore of a liquid-solid state (args: n2 ch4 c2h6 co2 P dTbelowFreezing Tlo Thi steps). */
public final class P5IsochoreDebug {
    public static void main(String[] a) {
        double[] z = G5Support.z(Double.parseDouble(a[0]), Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3]));
        double p0 = Double.parseDouble(a[4]);
        var s = G5Support.SERVICE;
        var ws = s.newWorkspace();
        double tf = s.freezingTemperature(null, p0, G5Support.BASIS, z, ws).temperature();
        double t0 = tf - Double.parseDouble(a[5]);
        var r = s.tp(EquilibriumRequest.tp(t0, p0, G5Support.BASIS, z, PhaseCompetition.FLUID_AND_CRYSTALS), ws);
        double v = r.volume(), u = r.internalEnergy();
        System.out.printf(Locale.ROOT, "T_f %.9f, state %.9f K: U %.9f V %.12g %s%n", tf, t0, u, v, r);
        double tlo = Double.parseDouble(a[6]), thi = Double.parseDouble(a[7]);
        int n = Integer.parseInt(a[8]);
        for (int k = 0; k <= n; k++) {
            double t = tlo + (thi - tlo) * k / n;
            double lo = Math.log(1e4), hi = Math.log(0.9999e7);
            var best = r;
            for (int j = 0; j < 80; j++) {
                double mid = 0.5 * (lo + hi);
                var x = s.tp(EquilibriumRequest.tp(t, Math.exp(mid), G5Support.BASIS, z, PhaseCompetition.FLUID_AND_CRYSTALS), ws);
                if (!x.converged()) { System.out.println("  " + t + " K " + Math.exp(mid) + " Pa: " + x.detail()); break; }
                best = x;
                if (x.volume() > v) lo = mid; else hi = mid;
            }
            System.out.printf(Locale.ROOT, "  T %.10f: P %.6f Pa U %.9f (U - U_spec %+.6f) %s crystal %.10g%n", t, Math.exp(0.5 * (lo + hi)), best.internalEnergy(),
                    best.internalEnergy() - u, best.classification(), G5F6ConsistencyTest.crystal(best));
        }
    }
}
