package com.wormzjl.createcheme.science.fluid.thermo;

import com.wormzjl.createcheme.science.material.MaterialCatalog;

/**
 * WP7b probe (scratch, never tracked): the free-water flashTP states of LegacyNetworkPathPinTest.digest's probe grid,
 * one line each (T, P, composition, water/steam split, pc, p_w, volume), printed with full digits so two class paths
 * can be diffed: which states the fixed-point start moves and by how much.
 */
public final class Wp7bDigestStates {
    public static void main(String[] args) {
        var model = new FluidThermodynamics(MaterialCatalog.bundled(), "createcheme:tjl20_methane_nitrogen");
        var hydrocarbon = model.hydrocarbon;
        int n = hydrocarbon.componentCount();
        int nitrogen = hydrocarbon.components().indexOf("Nitrogen");
        double[] pure = new double[n];
        pure[nitrogen] = 1;
        double[] mixture = new double[n];
        for (int i = 0; i < n; i++) mixture[i] = i == nitrogen ? 0.1 : (i % 5 + 1) * 0.01;
        double[] lean = new double[n];
        lean[nitrogen] = 0.9;
        lean[(nitrogen + 1) % n] = 0.1;
        String[] names = {"pure", "lean", "mixture"};
        double[][] compositions = {pure, lean, mixture};
        for (double t : new double[] {77.0, 110.0, 150.0, 293.15, 350.0, 450.0, 700.0}) {
            for (double p : new double[] {1.0e5, 5.0e5, 2.0e6}) {
                for (int c = 0; c < 3; c++) {
                    double[] overall = new double[n + 1];
                    System.arraycopy(compositions[c], 0, overall, 0, n);
                    overall[n] = 0.1;
                    try {
                        var s = model.flashTP(t, p, overall, () -> { });
                        StringBuilder all = new StringBuilder();
                        for (double value : new double[] {s.volume(), s.enthalpy(), s.internalEnergy(), s.mass()}) all.append(Double.doubleToRawLongBits(value)).append(',');
                        for (double value : s.liquidView()) all.append(Double.doubleToRawLongBits(value)).append(',');
                        for (double value : s.vaporView()) all.append(Double.doubleToRawLongBits(value)).append(',');
                        for (double value : new double[] {s.waterLiquid(), s.waterVapor(), s.hydrocarbonPartialPressure(), s.waterPartialPressure(),
                                s.liquidVolume(), s.waterVolume(), s.vaporVolume(), s.waterCompressibility(), model.velocityLimit(s)}) all.append(Double.doubleToRawLongBits(value)).append(',');
                        System.out.printf("%s K %s Pa %-7s liquidWater %s steam %s pc %s pw %s V %s H %s all-bits %08x%n", t, p, names[c],
                                s.waterLiquid(), s.waterVapor(), s.hydrocarbonPartialPressure(), s.waterPartialPressure(),
                                s.volume(), s.enthalpy(), all.toString().hashCode());
                    } catch (RuntimeException refused) {
                        System.out.printf("%s K %s Pa %-7s refused %s%n", t, p, names[c], refused.getMessage());
                    }
                }
            }
        }
    }
}
