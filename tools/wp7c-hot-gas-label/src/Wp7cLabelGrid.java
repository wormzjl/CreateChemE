package com.wormzjl.createcheme.science.thermo.phase;

import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.createcheme.science.thermo.PhaseRoot;
import java.util.Locale;

/**
 * WP7c probe (scratch, never tracked): the one-root labels of pure hot gases on the network and pilot contracts
 * (classification, label, PIP and Z of the untranslated root), and the liquid margin of the Z < 1 guard: the largest Z
 * of any one-root PIP > 1 state on each package's pure components inside their fluid domains.
 */
public final class Wp7cLabelGrid {
    static final String PILOT = "createcheme:pilot_cryogenic";
    static final double R = PengRobinsonKernel.GAS_CONSTANT;
    static final double[] MPA = {0.1, 0.5, 1, 2, 5, 10};

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        var catalog = MaterialCatalog.bundled();
        var network = PhaseTestSupport.networkService();
        var pilot = new FluidTpEquilibrium(PhaseContract.forNetworkPackage(catalog, PILOT), CubicPhaseEvaluator.forPackage(catalog, PILOT));
        if (args.length > 0 && args[0].equals("margins")) { margins(catalog, PhaseTestSupport.NETWORK, network); margins(catalog, PILOT, pilot); return; }
        grid("network", network, "Nitrogen", 300, 900);
        grid("network", network, "Methane", 300, 900);
        grid("pilot", pilot, "Nitrogen", 300, 1200);
        grid("pilot", pilot, "Methane", 200, 1200);
        grid("pilot", pilot, "Ethane", 350, 1200);
        grid("pilot", pilot, "CarbonDioxide", 350, 1200);
        survey(catalog, PhaseTestSupport.NETWORK, network.evaluator());
        survey(catalog, PILOT, pilot.evaluator());
        margins(catalog, PhaseTestSupport.NETWORK, network);
        margins(catalog, PILOT, pilot);
    }

    /**
     * Every one-root state with PIP > 1 and Z >= 1 over pure components and a few mixtures on a dense (T, P) grid of
     * the package's envelope (each state inside the range of every component it carries): the sign of PIP - Z against
     * the density v/b, and the engine's label of the dense ones.
     */
    static void margins(MaterialCatalog catalog, String packageId, FluidTpEquilibrium service) {
        var evaluator = service.evaluator();
        var kernel = evaluator.kernel();
        var ws = kernel.newWorkspace();
        var ev = kernel.newEvaluation();
        int n = kernel.componentCount();
        java.util.List<double[]> feeds = new java.util.ArrayList<>();
        java.util.List<String> names = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            double[] x = new double[n];
            x[i] = 1;
            feeds.add(x);
            names.add(evaluator.components().get(i));
        }
        double[] even = new double[n];
        java.util.Arrays.fill(even, 1.0 / n);
        feeds.add(even);
        names.add("equimolar");
        if (n > 4) {
            double[] heavy = new double[n];
            heavy[evaluator.components().indexOf("crude_pc11")] = 0.5;
            heavy[evaluator.components().indexOf("crude_pc12")] = 0.5;
            feeds.add(heavy);
            names.add("pc11+pc12");
            double[] residue = new double[n];
            for (String id : new String[] {"crude_pc08", "crude_pc09", "crude_pc10", "crude_pc11", "crude_pc12"}) {
                residue[evaluator.components().indexOf(id)] = 0.2;
            }
            feeds.add(residue);
            names.add("pc08-12");
            double[] mixture = new double[n];
            int nitrogen = evaluator.components().indexOf("Nitrogen");
            for (int i = 0; i < n; i++) mixture[i] = i == nitrogen ? 0.1 : (i % 5 + 1) * 0.01;
            feeds.add(mixture);
            names.add("digest-mixture");
        } else {
            feeds.add(new double[] {0.25, 0.25, 0.25, 0.25});
            names.add("pilot-equimolar");
            feeds.add(new double[] {0.5, 0.5, 0, 0});
            names.add("N2/CH4");
            feeds.add(new double[] {0, 0.5, 0, 0.5});
            names.add("CH4/CO2");
            feeds.add(new double[] {0.8, 0, 0, 0.2});
            names.add("N2/CO2");
        }
        var envelope = catalog.fluidValidity(packageId).orElseThrow();
        for (int f = 0; f < feeds.size(); f++) {
            double[] x = feeds.get(f);
            double tMin = envelope.minimumTemperature(), tMax = envelope.maximumTemperature(), pMax = envelope.maximumPressure();
            for (int i = 0; i < n; i++) {
                if (x[i] == 0) continue;
                var range = catalog.fluidValidity(packageId, evaluator.components().get(i)).orElseThrow();
                tMin = Math.max(tMin, range.minimumTemperature());
                tMax = Math.min(tMax, range.maximumTemperature());
                pMax = Math.min(pMax, range.maximumPressure());
            }
            double gasMin = Double.POSITIVE_INFINITY;
            double gasMax = Double.NEGATIVE_INFINITY, denseMin = Double.POSITIVE_INFINITY, denseZmax = 0, denseAtT = 0, denseAtP = 0;
            String gasAt = "", denseAt = "";
            int gas = 0, dense = 0, gasLiquid = 0, denseVapour = 0;
            for (int k = 0; k <= 160; k++) {
                double t = tMin + (tMax - tMin) * k / 160;
                for (int j = 0; j <= 40; j++) {
                    double p = Math.exp(Math.log(1e2) + (Math.log(pMax) - Math.log(1e2)) * j / 40);
                    for (PengRobinsonKernel.Root root : PengRobinsonKernel.Root.values()) {
                        kernel.evaluate(t, p, x, root, ws, ev);
                        if (ev.physicalRootCount() > 1) continue;
                        double z = ev.compressibility();
                        double pip = PhaseIdentification.parameter(ev, t, p);
                        if (!(pip > 1) || z < 1) continue;
                        double vb = z * R * t / p / ev.bMix();
                        if (vb > 3.9514) {
                            gas++;
                            if (pip > z) gasLiquid++;
                            gasMin = Math.min(gasMin, pip - z);
                            if (pip - z > gasMax) { gasMax = pip - z; gasAt = String.format("%.1f K %.0f Pa v/b %.1f PIP %.6f Z %.6f", t, p, vb, pip, z); }
                        } else {
                            dense++;
                            if (!(pip > z)) denseVapour++;
                            if (pip - z < denseMin) { denseMin = pip - z; denseAt = String.format("%.1f K %.0f Pa v/b %.3f PIP %.3f Z %.6f", t, p, vb, pip, z); }
                            if (z > denseZmax) { denseZmax = z; denseAtT = t; denseAtP = p; }
                        }
                        break;
                    }
                }
            }
            String label = "";
            if (dense > 0) {
                var contract = service.contract();
                double[] amounts = new double[contract.components().size()];
                for (int i = 0; i < n; i++) amounts[contract.index(evaluator.components().get(i))] = x[i];
                var r = service.tp(EquilibriumRequest.tp(denseAtT, denseAtP, contract.components(), amounts, PhaseCompetition.FLUID_ONLY));
                label = " engine at the densest Z>=1 state (" + denseAtT + " K, " + denseAtP + " Pa, Z " + denseZmax + "): " + r.status() + " "
                        + r.classification() + " " + (r.phases().isEmpty() ? "-" : r.phases().get(0).kind());
            }
            System.out.printf("MARGIN %s %s [%.2f-%.0f K, <= %.0f Pa]: one-root PIP>1 & Z>=1: gas-like (v/b > 3.95) %d, min PIP-Z %.3e, max PIP-Z %s (%s), PIP>Z among them %d;"
                            + " dense (v/b <= 3.95) %d, min PIP-Z %s (%s), PIP<=Z among them %d;%s%n",
                    packageId, names.get(f), tMin, tMax, pMax, gas, gasMin, gas > 0 ? String.format("%.3e", gasMax) : "-", gasAt, gasLiquid,
                    dense, dense > 0 ? String.format("%.3f", denseMin) : "-", denseAt, denseVapour, label);
        }
    }

    static void grid(String name, FluidTpEquilibrium service, String species, double from, double to) {
        var contract = service.contract();
        var kernel = service.evaluator().kernel();
        var ws = kernel.newWorkspace();
        var ev = kernel.newEvaluation();
        double worstPip = -1, worstZ = 0, worstT = 0, worstP = 0, minZ = 9, maxZ = 0;
        int states = 0, liquid = 0, refused = 0, notSingle = 0;
        for (double t = from; t <= to + 1e-9; t += 50) {
            for (double mpa : MPA) {
                double p = mpa * 1e6;
                double[] z = new double[contract.components().size()];
                z[contract.index(species)] = 1.0;
                var r = service.tp(EquilibriumRequest.tp(t, p, contract.components(), z, PhaseCompetition.FLUID_ONLY));
                if (!r.converged()) {
                    refused++;
                    System.out.printf("%s %s %.0f K %.1f MPa: %s %s%n", name, species, t, mpa, r.status(), r.detail());
                    continue;
                }
                if (r.phases().size() != 1) {
                    notSingle++;
                    System.out.printf("%s %s %.0f K %.1f MPa: %s %d phases%n", name, species, t, mpa, r.classification(), r.phases().size());
                    continue;
                }
                var phase = r.phases().get(0);
                var state = phase.state();
                kernel.evaluate(t, p, state.compositionView(), state.root() == PhaseRoot.VAPOR ? PengRobinsonKernel.Root.VAPOR
                        : PengRobinsonKernel.Root.LIQUID, ws, ev);
                double pip = PhaseIdentification.parameter(ev, t, p);
                double zz = ev.compressibility();
                states++;
                boolean liquidLabel = phase.kind() == PhaseKind.LIQUID;
                if (liquidLabel) liquid++;
                if (pip > worstPip) { worstPip = pip; worstZ = zz; worstT = t; worstP = mpa; }
                minZ = Math.min(minZ, zz);
                maxZ = Math.max(maxZ, zz);
                System.out.printf("%s %s %.0f K %.1f MPa: %s %s roots %d PIP %.9f Z %.9f v/b %.2f%s%n", name, species, t, mpa,
                        r.classification(), phase.kind(), ev.physicalRootCount(), pip, zz, zz * R * t / p / ev.bMix(),
                        liquidLabel ? "  <-- LIQUID" : "");
            }
        }
        System.out.printf("SUMMARY %s %s %.0f-%.0f K: %d single, %d LIQUID-labelled, %d not single, %d refused; worst PIP %.9f (Z %.9f) at %.0f K %.1f MPa; Z %.6f..%.6f%n",
                name, species, from, to, states, liquid, notSingle, refused, worstPip, worstZ, worstT, worstP, minZ, maxZ);
    }

    /** One-root states with PIP > 1 over each pure component's own fluid domain: the largest Z, and PIP > 1 with Z >= 1. */
    static void survey(MaterialCatalog catalog, String packageId, CubicPhaseEvaluator evaluator) {
        var kernel = evaluator.kernel();
        var ws = kernel.newWorkspace();
        var ev = kernel.newEvaluation();
        int n = kernel.componentCount();
        double overallMaxZ = 0;
        String overallAt = "";
        for (int i = 0; i < n; i++) {
            String id = evaluator.components().get(i);
            var range = catalog.fluidValidity(packageId, id).orElseThrow();
            double[] x = new double[n];
            x[i] = 1;
            double maxZ = 0, maxZvb = 0, maxZt = 0, maxZp = 0, gasMinT = Double.NaN, gasMinVb = Double.POSITIVE_INFINITY;
            double threeMaxZ = 0;
            int gas = 0, dense = 0;
            double tMin = range.minimumTemperature(), tMax = range.maximumTemperature();
            int steps = 200;
            double pLow = Math.max(range.minimumPressure(), 1e3);
            for (int k = 0; k <= steps; k++) {
                double t = tMin + (tMax - tMin) * k / steps;
                for (int j = 0; j <= 40; j++) {
                    double p = Math.exp(Math.log(pLow) + (Math.log(range.maximumPressure()) - Math.log(pLow)) * j / 40);
                    kernel.evaluate(t, p, x, PengRobinsonKernel.Root.LIQUID, ws, ev);
                    double z = ev.compressibility();
                    if (ev.physicalRootCount() > 1) { threeMaxZ = Math.max(threeMaxZ, z); continue; }
                    double pip = PhaseIdentification.parameter(ev, t, p);
                    if (!(pip > 1)) continue;
                    double vb = z * R * t / p / ev.bMix();
                    if (z < 1) {
                        dense++;
                        if (z > maxZ) { maxZ = z; maxZvb = vb; maxZt = t; maxZp = p; }
                    } else {
                        gas++;
                        if (Double.isNaN(gasMinT) || t < gasMinT) gasMinT = t;
                        gasMinVb = Math.min(gasMinVb, vb);
                    }
                }
            }
            System.out.printf("SURVEY %s %s [%.2f-%.0f K, %.0f-%.0f Pa]: one-root PIP>1 & Z<1: %d, max Z %.4f (v/b %.2f) at %.2f K %.0f Pa;"
                            + " PIP>1 & Z>=1: %d, lowest T %.1f K, min v/b %.1f; three-root liquid max Z %.4f%n",
                    packageId, id, tMin, tMax, range.minimumPressure(), range.maximumPressure(), dense, maxZ, maxZvb, maxZt, maxZp,
                    gas, gasMinT, gasMinVb, threeMaxZ);
            if (maxZ > overallMaxZ) { overallMaxZ = maxZ; overallAt = id + " " + maxZt + " K " + maxZp + " Pa"; }
        }
        System.out.printf("SURVEY %s: largest Z of a one-root PIP>1 state with Z<1: %.4f (%s)%n", packageId, overallMaxZ, overallAt);
    }
}
