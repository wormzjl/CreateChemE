package com.wormzjl.createcheme.science.thermo.phase;

import com.wormzjl.createcheme.science.thermo.ThermoComponent;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** WP7d probe: a synthetic binary (methane-like and ethane-like constants, k_ij 0.3) searched for a liquid-liquid split. */
public final class Wp7dSynthetic {
    public static void main(String[] args) {
        double kij = args.length > 0 ? Double.parseDouble(args[0]) : 0.3;
        var components = List.of(new ThermoComponent("LightLiquid", 190.564, 4.5992e6, 0.01142, 0.0160428),
                new ThermoComponent("HeavyLiquid", 305.32, 4.8722e6, 0.0995, 0.0300690));
        var gas = List.of(PhaseTestSupport.StubIdealGas.constant(35.7, 50, 1000), PhaseTestSupport.StubIdealGas.constant(52.5, 50, 1000));
        var evaluator = new CubicPhaseEvaluator("test:synthetic-lle", components, new double[][] {{0, kij}, {kij, 0}}, new double[2], gas);
        var engine = new FluidTpEquilibrium(PhaseTestSupport.openContract(evaluator), evaluator);
        var ws = engine.newWorkspace();
        for (double t = 100; t <= 180; t += 10) for (double p : new double[] {1e6, 3e6, 5e6}) for (double x : new double[] {0.3, 0.5, 0.7}) {
            var r = engine.tp(EquilibriumRequest.tp(t, p, evaluator.components(), new double[] {x, 1 - x}, PhaseCompetition.FLUID_ONLY), ws);
            if (r.converged() && r.phases().size() == 2) {
                var a = r.phases().get(0); var b = r.phases().get(1);
                System.out.printf(Locale.ROOT, "%.0f K %.0f MPa x %.1f: %s tie %.3f band %s | %s x1 %.4f v %.4e | %s x1 %.4f v %.4e%n", t, p / 1e6, x,
                        r.classification(), r.diagnostics().tieLine(), r.diagnostics().criticalBand(), a.kind(), a.amount(0) / a.total(),
                        a.state().molarVolume(), b.kind(), b.amount(0) / b.total(), b.state().molarVolume());
            }
        }
    }
}
