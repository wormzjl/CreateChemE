package com.wormzjl.createcheme.science.thermo.phase;

import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Robustness scans of FluidTpEquilibrium beyond the unit tests (P2, batch 2026-09-24-coolprop-low-temperature).
 * Lives in the test package to reuse PhaseTestSupport; compiled and run by run.sh outside Gradle.
 */
public final class FlashScan {
    public static void main(String[] args) {
        binary("binary field", 95, 190, 5, 0.1e6, 5.0e6, 0.1e6, 0.02, 0.98, 0.04);
        binary("near-critical", 130, 185, 5, 2.5e6, 6.0e6, 0.02e6, 0.01, 0.99, 0.02);
        network();
    }

    static void binary(String name, double t0, double t1, double dt, double p0, double p1, double dp, double x0, double x1, double dx) {
        var evaluator = PhaseTestSupport.methaneNitrogen();
        var service = new FluidTpEquilibrium(PhaseTestSupport.openContract(evaluator), evaluator);
        var w = service.newWorkspace();
        List<String> basis = List.of("Methane", "Nitrogen");
        Stats s = new Stats();
        long start = System.nanoTime();
        for (int it = 0; t0 + it * dt <= t1 + 1e-9; it++) {
            double t = t0 + it * dt;
            for (int ip = 0; p0 + ip * dp <= p1 + 1e-3; ip++) {
                double p = p0 + ip * dp;
                for (int ix = 0; x0 + ix * dx <= x1 + 1e-9; ix++) {
                    double x = x0 + ix * dx;
                    double[] z = {1 - x, x};
                    var r = service.tp(EquilibriumRequest.tp(t, p, basis, z, PhaseCompetition.FLUID_ONLY), w);
                    s.add(r, z, t + " K " + p + " Pa x_N2 " + x);
                    if (!r.converged()) s.box(t, p, x);
                }
            }
        }
        s.print(name, System.nanoTime() - start);
    }

    static void network() {
        var service = PhaseTestSupport.networkService();
        var contract = service.contract();
        var fluid = new FluidThermodynamics(MaterialCatalog.bundled(), PhaseTestSupport.NETWORK, 1.0e-9);
        double[] crude = PhaseTestSupport.crudeWithNitrogen(contract);
        double[] gas = new double[contract.components().size()];
        for (String id : List.of("Methane", "Ethane", "Propane", "Isobutane", "N-butane", "Isopentane", "N-pentane")) {
            int i = contract.index(id);
            if (i < 0) throw new IllegalStateException("no " + id + " in " + contract.components());
            gas[i] = 0.10;
        }
        gas[contract.index("Nitrogen")] = 0.30;
        for (int i = 0; i < gas.length; i++) if (contract.components().get(i).startsWith("crude_pc")) gas[i] = 1e-4;
        var w = service.newWorkspace();
        Stats s = new Stats();
        int agree = 0, total = 0;
        long start = System.nanoTime();
        for (double[] feed : new double[][] {crude, gas}) {
            for (int it = 0; it <= 30; it++) {
                double t = 300 + 20 * it;
                for (int ip = 1; ip <= 20; ip++) {
                    double p = 0.1e6 * ip;
                    var r = service.tp(EquilibriumRequest.tp(t, p, contract.components(), feed, PhaseCompetition.FLUID_ONLY), w);
                    s.add(r, feed, t + " K " + p + " Pa");
                    var flash = fluid.flashTP(t, p, feed.clone(), () -> { });
                    int phases = (PhaseTestSupport.sum(flash.liquidView()) > 0 ? 1 : 0) + (PhaseTestSupport.sum(flash.vaporView()) > 0 ? 1 : 0);
                    int ours = r.converged() ? r.phases().size() : -1;
                    if (ours == phases) agree++;
                    else if (s.disagreements++ < 10) System.out.println("  network flash phases " + phases + " vs " + ours + " at " + t + " K " + p + " Pa");
                    total++;
                }
            }
        }
        s.print("network (" + agree + "/" + total + " phase counts agree with FluidThermodynamics.flashTP)", System.nanoTime() - start);
    }

    static final class Stats {
        final Map<String, Integer> outcomes = new TreeMap<>();
        int states, split, iterationSum, iterationMax, kernelMax, disagreements;
        long kernelSum;
        double defectMax, fugacityMax;
        /** Bounding box of the not-converged binary states: T, P, x_N2 minimum and maximum. */
        final double[] box = {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        void box(double t, double p, double x) {
            box[0] = Math.min(box[0], t); box[1] = Math.max(box[1], t);
            box[2] = Math.min(box[2], p); box[3] = Math.max(box[3], p);
            box[4] = Math.min(box[4], x); box[5] = Math.max(box[5], x);
        }
        String fugacityAt = "";
        final Map<String, Integer> reasons = new TreeMap<>();
        void add(EquilibriumResult r, double[] z, String at) {
            states++;
            String key = r.status() + (r.converged() ? "/" + r.classification() : "");
            outcomes.merge(key, 1, Integer::sum);
            if (!r.converged()) reasons.merge(r.detail().replaceAll("[-0-9.E]+", "#"), 1, Integer::sum);
            if (!r.converged() && outcomes.get(key) <= 3) System.out.println("  " + key + " at " + at + ": " + r.detail());
            kernelSum += r.diagnostics().kernelEvaluations();
            kernelMax = Math.max(kernelMax, r.diagnostics().kernelEvaluations());
            if (r.converged() && !r.phases().isEmpty()) defectMax = Math.max(defectMax, r.conservationDefect(z));
            if (r.converged() && r.classification() == EquilibriumResult.Classification.VAPOR_LIQUID) {
                split++;
                iterationSum += r.diagnostics().flashIterations();
                iterationMax = Math.max(iterationMax, r.diagnostics().flashIterations());
                if (r.diagnostics().fugacityResidual() > fugacityMax) { fugacityMax = r.diagnostics().fugacityResidual(); fugacityAt = at; }
            }
        }
        void print(String name, long nanos) {
            System.out.printf("%s: %d states in %.1f s; %s; two-phase %d, flash iterations mean %.1f max %d; "
                            + "kernel calls mean %.1f max %d; worst conservation defect %.3g; worst fugacity residual %.3g%n",
                    name, states, nanos / 1e9, outcomes, split, split == 0 ? 0.0 : (double) iterationSum / split, iterationMax,
                    (double) kernelSum / states, kernelMax, defectMax, fugacityMax);
            System.out.println("    worst fugacity at " + fugacityAt + "; not converged by reason " + reasons);
            if (box[0] <= box[1]) System.out.printf("    not converged within T %.0f..%.0f K, P %.2f..%.2f MPa, x_N2 %.2f..%.2f%n",
                    box[0], box[1], box[2] / 1e6, box[3] / 1e6, box[4], box[5]);
        }
    }
}
