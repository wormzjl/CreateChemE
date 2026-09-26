package com.wormzjl.createcheme.science.thermo.qualification;

import com.wormzjl.createcheme.science.thermo.phase.EquilibriumRequest;
import com.wormzjl.createcheme.science.thermo.phase.PhaseCompetition;
import java.util.Locale;

/** Exploratory: TP answers of a feed around a temperature at a pressure list (args: n2 ch4 c2h6 co2 T dT P...). */
public final class P5StepDebug {
    public static void main(String[] a) {
        double[] z = G5Support.z(Double.parseDouble(a[0]), Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3]));
        double t0 = Double.parseDouble(a[4]), dt = Double.parseDouble(a[5]);
        var s = G5Support.SERVICE;
        var ws = s.newWorkspace();
        for (int k = 6; k < a.length; k++) {
            double p = Double.parseDouble(a[k]);
            for (int j = -3; j <= 3; j++) {
                double t = t0 + j * dt;
                var r = s.tp(EquilibriumRequest.tp(t, p, G5Support.BASIS, z, PhaseCompetition.FLUID_AND_CRYSTALS), ws);
                System.out.printf(Locale.ROOT, "%.10f K %.6g Pa: %s crystal %.12g U %.9f V %.12g%n", t, p, r.classification(), G5F6ConsistencyTest.crystal(r),
                        r.converged() ? r.internalEnergy() : Double.NaN, r.converged() ? r.volume() : Double.NaN);
            }
        }
    }
}
