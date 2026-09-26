package com.wormzjl.createcheme.science.thermo.phase;

import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.thermo.ThermoDomainViolation;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * WP7d survey of the liquid-liquid label: (A) the pilot engine (network contract) over its six binaries and two mixtures,
 * 90-400 K, 0.1-10 MPa; (B) the pilot network's flashTP over SpineNetworkPathTest's grid; (C) the bundled network's
 * flashTP over the WP6a network field (crude + N2 and the light gas, 300-900 K, 0.1-2 MPa, dry and wet). Counts
 * two-phase answers by classification and network refusals by type.
 */
public final class Wp7dLabelSurvey {
    public static void main(String[] args) {
        var catalog = MaterialCatalog.bundled();
        String pilotId = "createcheme:pilot_cryogenic";
        var evaluator = CubicPhaseEvaluator.forPackage(catalog, pilotId);
        var engine = new FluidTpEquilibrium(PhaseContract.forNetworkPackage(catalog, pilotId), evaluator);
        var ws = engine.newWorkspace();
        var species = engine.contract().components();
        String[] names = {"N2", "CH4", "C2H6", "CO2"};
        double[] pressures = {0.1e6, 0.2e6, 0.5e6, 1e6, 2e6, 3e6, 4e6, 5e6, 6e6, 7e6, 8e6, 9e6, 10e6};
        Map<String, Integer> tally = new TreeMap<>();
        Map<String, String> lleRange = new TreeMap<>();
        for (int a = 0; a < 4; a++) {
            for (int b = a + 1; b < 5; b++) {
                String pair = b == 4 ? "N2/CH4/C2H6/CO2 equimolar" : names[a] + "/" + names[b];
                if (b == 4 && a > 0) continue;
                for (double t = 90; t <= 400; t += 5) {
                    for (double p : pressures) {
                        for (double x = 0.05; x < 0.96; x += 0.1) {
                            double[] z = new double[5];
                            if (b == 4) { z[0] = z[1] = z[2] = z[3] = 0.25; } else { z[a] = x; z[b] = 1 - x; }
                            var r = engine.tp(EquilibriumRequest.tp(t, p, species, z, PhaseCompetition.FLUID_ONLY), ws);
                            String key = pair + " " + r.status() + (r.converged() ? "/" + r.classification() : "");
                            tally.merge(key, 1, Integer::sum);
                            if (r.converged() && r.classification().name().equals("LIQUID_LIQUID")) {
                                String at = String.format(Locale.ROOT, "%.0f K %.1f MPa x %.2f tie %.3f", t, p / 1e6, x, r.diagnostics().tieLine());
                                lleRange.merge(pair, at, (old, add) -> old.contains(" .. ") ? old.substring(0, old.indexOf(" .. ")) + " .. " + add : old + " .. " + add);
                                tally.merge(pair + " LLE min tie x1000 " + (int) Math.floor(1000 * r.diagnostics().tieLine() / 100) * 100, 1, Integer::sum);
                            }
                            if (b == 4) break;
                        }
                    }
                }
            }
        }
        System.out.println("(A) pilot engine, network contract:");
        tally.forEach((k, v) -> System.out.println("  " + k + ": " + v));
        lleRange.forEach((k, v) -> System.out.println("  LLE first .. last " + k + ": " + v));

        // (B) SpineNetworkPathTest's grid through the pilot network.
        var pilot = new FluidThermodynamics(catalog, pilotId);
        double[][] feeds = {{1, 0, 0, 0, 0}, {0, 1, 0, 0, 0}, {0, 0, 1, 0, 0}, {0, 0, 0, 1, 0}, {0.5, 0.5, 0, 0, 0}, {0.1, 0.7, 0.2, 0, 0},
                {0, 0.8, 0, 0.2, 0}, {0.2, 0, 0, 0.8, 0}, {0, 0, 0.6, 0.4, 0}, {0.05, 0.8, 0.1, 0.05, 0}, {0.1, 0.6, 0.1, 0.1, 0.1}};
        Map<String, Integer> network = new TreeMap<>();
        for (double t : new double[] {80, 100, 120, 150, 180, 230, 260, 300, 400, 700, 1100}) {
            for (double p : new double[] {1.0e5, 3.0e5, 1.0e6, 2.0e6, 5.0e6, 1.0e7}) {
                for (double[] feed : feeds) network.merge(flash(pilot, t, p, feed), 1, Integer::sum);
            }
        }
        System.out.println("(B) pilot network flashTP over SpineNetworkPathTest's grid: " + network);

        // (C) The bundled network over the WP6a network field.
        var bundled = new FluidThermodynamics(catalog, "createcheme:tjl20_methane_nitrogen");
        var contract = PhaseContract.forNetworkPackage(catalog, "createcheme:tjl20_methane_nitrogen", ThermoIdentity.NO_SPINE);
        double[] crude = PhaseTestSupport.crudeWithNitrogen(contract);
        double[] gas = new double[contract.components().size()];
        for (String id : List.of("Methane", "Ethane", "Propane", "Isobutane", "N-butane", "Isopentane", "N-pentane")) gas[contract.index(id)] = 0.10;
        gas[contract.index("Nitrogen")] = 0.30;
        for (int i = 0; i < gas.length; i++) if (contract.components().get(i).startsWith("crude_pc")) gas[i] = 1e-4;
        int wi = contract.waterIndex();
        Map<String, Integer> field = new TreeMap<>();
        for (double[] base : new double[][] {crude, gas}) {
            double hc = 0;
            for (double v : base) hc += v;
            for (double ratio : new double[] {0, 0.01, 1.0}) {
                for (int it = 0; it <= 30; it++) {
                    for (int ip = 1; ip <= 20; ip++) {
                        double[] feed = base.clone();
                        feed[wi] = ratio * hc;
                        field.merge(flash(bundled, 300 + 20 * it, 0.1e6 * ip, feed), 1, Integer::sum);
                    }
                }
            }
        }
        System.out.println("(C) bundled network flashTP over the WP6a field (dry, water 0.01 and 1): " + field);
    }

    static String flash(FluidThermodynamics model, double t, double p, double[] feed) {
        try {
            var s = model.flashTP(t, p, feed.clone(), () -> { });
            double l = 0, v = 0;
            for (double x : s.liquidView()) l += x;
            for (double x : s.vaporView()) v += x;
            return "answered " + (l > 0 && v > 0 ? "two-phase" : "one-phase");
        } catch (ThermoDomainViolation outside) {
            return "domain";
        } catch (IllegalArgumentException refused) {
            String m = refused.getMessage();
            return "refused " + refused.getClass().getSimpleName() + ": " + (m.length() > 60 ? m.substring(0, 60) : m);
        }
    }
}
