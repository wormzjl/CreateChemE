package com.wormzjl.createcheme.science.thermo.phase;

import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** WP7d probe: the pilot engine's NOT_CONVERGED answers on the survey grid of Wp7dLabelSurvey (A), grouped by detail. */
public final class Wp7dFailures {
    public static void main(String[] args) {
        var catalog = MaterialCatalog.bundled();
        String pilotId = "createcheme:pilot_cryogenic";
        var engine = new FluidTpEquilibrium(PhaseContract.forNetworkPackage(catalog, pilotId), CubicPhaseEvaluator.forPackage(catalog, pilotId));
        var ws = engine.newWorkspace();
        var species = engine.contract().components();
        String[] names = {"N2", "CH4", "C2H6", "CO2"};
        double[] pressures = {0.1e6, 0.2e6, 0.5e6, 1e6, 2e6, 3e6, 4e6, 5e6, 6e6, 7e6, 8e6, 9e6, 10e6};
        Map<String, Integer> groups = new TreeMap<>();
        Map<String, String> example = new TreeMap<>();
        for (int a = 0; a < 4; a++) for (int b = a + 1; b < 4; b++) {
            for (double t = 90; t <= 400; t += 5) for (double p : pressures) for (double x = 0.05; x < 0.96; x += 0.1) {
                double[] z = new double[5];
                z[a] = x; z[b] = 1 - x;
                var r = engine.tp(EquilibriumRequest.tp(t, p, species, z, PhaseCompetition.FLUID_ONLY), ws);
                if (r.status() != EquilibriumResult.Status.NOT_CONVERGED) continue;
                String d = r.detail();
                String key = names[a] + "/" + names[b] + " band " + r.diagnostics().criticalBand() + ": " + (d.length() > 90 ? d.substring(0, 90) : d);
                groups.merge(key, 1, Integer::sum);
                example.putIfAbsent(key, String.format(Locale.ROOT, "%.0f K %.1f MPa x %.2f", t, p / 1e6, x));
            }
        }
        groups.forEach((k, v) -> System.out.println(v + "  " + k + "  e.g. " + example.get(k)));
    }
}
