package com.wormzjl.createcheme.science.fluid.thermo;

import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.Locale;

/**
 * WP7c probe (scratch, never tracked): hot dry and wet states of the network basis through the network's own
 * {@code flashTP} (the engine adapter): which slot holds the hydrocarbons, and for an all-steam state the water partial
 * pressure against the ideal-mixing estimate {@code y_w P} of the gas's own mole fraction.
 */
public final class Wp7cWetProbe {
    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        var model = new FluidThermodynamics(MaterialCatalog.bundled(), "createcheme:tjl20_methane_nitrogen");
        var hydrocarbon = model.hydrocarbon;
        int n = hydrocarbon.componentCount();
        System.out.println("components " + hydrocarbon.components());
        int nitrogen = hydrocarbon.components().indexOf("Nitrogen");
        double[] pure = new double[n];
        pure[nitrogen] = 1;
        double[] lean = new double[n];
        lean[nitrogen] = 0.9;
        lean[(nitrogen + 1) % n] = 0.1;
        double[] mixture = new double[n];
        for (int i = 0; i < n; i++) mixture[i] = i == nitrogen ? 0.1 : (i % 5 + 1) * 0.01;
        double[] light = new double[n];
        for (String id : new String[] {"Nitrogen", "Methane", "Ethane", "Propane", "Isobutane", "N-butane", "Isopentane", "N-pentane"}) {
            light[hydrocarbon.components().indexOf(id)] = 0.125;
        }
        String[] names = {"pure", "lean", "light", "mixture"};
        double[][] compositions = {pure, lean, light, mixture};
        for (double water : new double[] {0.0, 0.1}) {
            for (double t : new double[] {600.0, 650.0, 700.0, 750.0, 800.0, 900.0, 1200.0}) {
                for (double p : new double[] {1.0e5, 5.0e5, 1.0e6, 2.0e6}) {
                    for (int c = 0; c < compositions.length; c++) {
                        double[] overall = new double[n + 1];
                        System.arraycopy(compositions[c], 0, overall, 0, n);
                        overall[n] = water;
                        String at = String.format("%s %.0f K %.1f MPa %-7s", water > 0 ? "wet" : "dry", t, p / 1e6, names[c]);
                        try {
                            var s = model.flashTP(t, p, overall, () -> { });
                            double liquid = 0, vapour = 0;
                            for (double v : s.liquidView()) liquid += v;
                            for (double v : s.vaporView()) vapour += v;
                            String line = String.format("%s hc liquid %.6g vapour %.6g", at, liquid, vapour);
                            if (water > 0) {
                                line += String.format(" liquidWater %.6g steam %.6g pc %.9g pw %.9g", s.waterLiquid(), s.waterVapor(),
                                        s.hydrocarbonPartialPressure(), s.waterPartialPressure());
                                if (s.waterLiquid() == 0 && vapour > 0) {
                                    double yw = s.waterVapor() / (s.waterVapor() + vapour);
                                    line += String.format(" pw/(y_w P) %.6f", s.waterPartialPressure() / (yw * p));
                                }
                            }
                            if (liquid > 0) line += "  <-- hydrocarbon in the liquid slot";
                            System.out.println(line);
                        } catch (RuntimeException refused) {
                            System.out.println(at + " refused " + refused.getClass().getSimpleName() + ": " + refused.getMessage());
                        }
                    }
                }
            }
        }
    }
}
