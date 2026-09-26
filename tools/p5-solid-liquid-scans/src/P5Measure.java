package com.wormzjl.createcheme.science.thermo.qualification;

import com.wormzjl.createcheme.science.thermo.phase.FluidTpEquilibrium;
import com.wormzjl.createcheme.science.thermo.phase.SolidCarbonDioxideReferences;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * P5 measurement probe (not a gate): the G5 families and the G4 frost points on the pilot service with each anchor fusion
 * enthalpy (9019 J/mol, the record; 8875 J/mol, Jaeger-Span and Maltby 2025), for the D16 revisit. Args: fusion values
 * (default 9019 8875); -Dprint=true prints every point.
 */
public final class P5Measure {
    public static void main(String[] args) {
        double[] values = args.length == 0 ? new double[] {9019, 8875} : java.util.Arrays.stream(args).mapToDouble(Double::parseDouble).toArray();
        boolean print = Boolean.getBoolean("print");
        for (double fusion : values) {
            FluidTpEquilibrium s = fusion == 9019 ? G5Support.SERVICE : G5Support.withFusionEnthalpy(fusion);
            System.out.println("==================== anchor fusion enthalpy " + fusion + " J/mol ====================");
            var ws = s.newWorkspace();
            // F1: pure melting against Span-Wagner 3.10.
            double worst = 0;
            StringBuilder melt = new StringBuilder();
            for (double p : new double[] {0.6e6, 1e6, 2e6, 3e6, 4e6, 5e6, 6e6, 7e6, 8e6, 9e6, 1e7}) {
                var onset = s.freezingTemperature(null, p, G5Support.BASIS, G5Support.z(0, 0, 0, 1), ws);
                double t = invertMelting(p);
                worst = Math.max(worst, Math.abs(onset.temperature() - t));
                melt.append(String.format(Locale.ROOT, " %.1f MPa %+.4f;", p / 1e6, onset.temperature() - t));
            }
            System.out.printf(Locale.ROOT, "F1 melting vs SW 3.10: worst %.4f K;%s%n", worst, melt);
            // F2 liquidus.
            List<G5Support.Point> liquidus = new ArrayList<>(G5Support.shen());
            liquidus.addAll(G5Support.davisCrystalPoints());
            G5Support.summary("F2 liquidus", G5Support.score(s, liquidus, print), 2.0);
            // F3 three-phase line.
            threePhase(s, "Davis 1962", G5Support.davisLine(), print);
            threePhase(s, "Souza 2020", G5Support.souzaLine(), print);
            compositions(s, print);
            // F4 ternaries.
            List<G5Support.Point> ternary = new ArrayList<>(G5Support.campestrini());
            ternary.addAll(G5Support.rivaStringari());
            G5Support.summary("F4 ternaries", G5Support.score(s, ternary, print), 2.0);
            // F5 report only.
            List<G5Support.Point> report = new ArrayList<>(G5Support.gao());
            report.addAll(G5Support.sampson());
            G5Support.summary("F5 report", G5Support.score(s, report, print), Double.NaN);
            // G4 frost points.
            frost(s, "G4F1 binary CH4", g4f1Binary(), print);
            frost(s, "G4F2 N2 (Sonntag + Smith)", g4f2(), print);
        }
    }

    static double invertMelting(double p) {
        double lo = SolidCarbonDioxideReferences.TRIPLE_TEMPERATURE, hi = 230;
        for (int k = 0; k < 200; k++) {
            double mid = 0.5 * (lo + hi);
            if (SolidCarbonDioxideReferences.spanWagnerMeltingPressure(mid) < p) lo = mid; else hi = mid;
        }
        return 0.5 * (lo + hi);
    }

    static void threePhase(FluidTpEquilibrium s, String name, double[][] line, boolean print) {
        var ws = s.newWorkspace();
        List<Double> dev = new ArrayList<>();
        List<Double> teq = new ArrayList<>();
        int missing = 0;
        for (double[] point : line) {
            double[] three = G5Support.threePhase(s, point[0], 1, ws);
            if (three == null) {
                missing++;
                if (print) System.out.printf(Locale.ROOT, "  %s %.2f K %.4f MPa: no three-phase point%n", name, point[0], point[1] / 1e6);
                continue;
            }
            double d = 100 * (three[0] / point[1] - 1);
            dev.add(d);
            // Temperature equivalent from the model's own line slope.
            double[] next = G5Support.threePhase(s, point[0] + 0.05, 1, ws);
            double slope = next == null ? Double.NaN : (Math.log(next[0]) - Math.log(three[0])) / 0.05;
            double te = Math.abs(slope) > 0.01 ? -Math.log(three[0] / point[1]) / slope : Double.NaN;
            if (Double.isFinite(te)) teq.add(te);
            if (print) System.out.printf(Locale.ROOT, "  %s %.2f K: data %.4f MPa, model %.4f MPa (%+.2f %%), T-equivalent %+.3f K, x %.5f y %.5f%n", name,
                    point[0], point[1] / 1e6, three[0] / 1e6, d, te, three[1], three[2]);
        }
        System.out.printf(Locale.ROOT, "F3 three-phase line %s: %d points, %d without; |dP/P| MAD %.2f %%, bias %+.2f %%, max %.2f %%; T-equivalent MAD %.3f K (%d points), max %.3f K%n",
                name, dev.size(), missing, dev.stream().mapToDouble(Math::abs).average().orElse(Double.NaN),
                dev.stream().mapToDouble(x -> x).average().orElse(Double.NaN), dev.stream().mapToDouble(Math::abs).max().orElse(Double.NaN),
                teq.stream().mapToDouble(Math::abs).average().orElse(Double.NaN), teq.size(), teq.stream().mapToDouble(Math::abs).max().orElse(Double.NaN));
    }

    static void compositions(FluidTpEquilibrium s, boolean print) {
        var ws = s.newWorkspace();
        for (boolean vapour : new boolean[] {true, false}) {
            List<Double> dev = new ArrayList<>();
            for (double[] r : G5Support.davisCompositions(vapour)) {
                double[] three = G5Support.threePhase(s, r[0], 1, ws);
                if (three == null) continue;
                double model = vapour ? three[2] : three[1];
                double d = 100 * Math.log(model / r[1]);
                dev.add(d);
                if (print) System.out.printf(Locale.ROOT, "  Davis %s %.2f K: data %.5f model %.5f (%+.1f %% in ln)%n", vapour ? "y" : "x", r[0], r[1], model, d);
            }
            System.out.printf(Locale.ROOT, "F3 Davis %s on the line: %d points, |ln ratio| MAD %.1f %%, bias %+.1f %%, max %.1f %%%n", vapour ? "vapour y" : "liquid x",
                    dev.size(), dev.stream().mapToDouble(Math::abs).average().orElse(Double.NaN), dev.stream().mapToDouble(x -> x).average().orElse(Double.NaN),
                    dev.stream().mapToDouble(Math::abs).max().orElse(Double.NaN));
        }
    }

    static List<G5Support.Point> g4f1Binary() {
        List<G5Support.Point> points = new ArrayList<>();
        List<G4F1CarbonDioxideFrostPointHoldoutTest.Point> all = new ArrayList<>();
        for (var p : G4F1CarbonDioxideFrostPointHoldoutTest.leTrebble()) if (p.set() == 1) all.add(p);
        all.addAll(G4F1CarbonDioxideFrostPointHoldoutTest.zhang());
        for (var p : G4F1CarbonDioxideFrostPointHoldoutTest.xiong()) if (p.set() == 3) all.add(p);
        for (var p : all) points.add(new G5Support.Point(p.dataset(), "binary", p.temperature(), p.pressure(), G4F1CarbonDioxideFrostPointHoldoutTest.feed(p.z()), true, ""));
        return points;
    }

    static List<G5Support.Point> g4f2() {
        List<G5Support.Point> points = new ArrayList<>();
        List<G4F2CarbonDioxideNitrogenFrostPointHoldoutTest.Point> all = new ArrayList<>(G4F2CarbonDioxideNitrogenFrostPointHoldoutTest.sonntag());
        all.addAll(G4F2CarbonDioxideNitrogenFrostPointHoldoutTest.smith());
        for (var p : all) if (p.pressure() <= 1e7) points.add(new G5Support.Point(p.dataset(), "N2", p.temperature(), p.pressure(), p.feed(), true, ""));
        return points;
    }

    /** Frost points: vapour-carrier points scored, points whose onset fluid holds a liquid reported apart (as the G4 tests). */
    static void frost(FluidTpEquilibrium s, String name, List<G5Support.Point> points, boolean print) {
        var ws = s.newWorkspace();
        G5Support.Score vapour = new G5Support.Score(), liquid = new G5Support.Score();
        for (var point : points) {
            var onset = s.depositionTemperature(null, point.p(), G5Support.BASIS, point.z(), ws);
            if (!onset.converged()) {
                vapour.failures.add(point.dataset() + " " + point.t() + ": " + onset.detail());
                continue;
            }
            boolean hasLiquid = false;
            for (var ph : onset.fluid().phases()) hasLiquid |= ph.kind() == com.wormzjl.createcheme.science.thermo.phase.PhaseKind.LIQUID;
            (hasLiquid ? liquid : vapour).deviations.add(onset.temperature() - point.t());
        }
        System.out.printf(Locale.ROOT, "%s: vapour carrier %d points MAD %.3f K bias %+.3f max %.3f; liquid-labelled fluid at the onset %d points MAD %.3f max %.3f; failures %d%n",
                name, vapour.deviations.size(), vapour.mad(), vapour.bias(), vapour.max(), liquid.deviations.size(), liquid.mad(), liquid.max(), vapour.failures.size());
    }
}
