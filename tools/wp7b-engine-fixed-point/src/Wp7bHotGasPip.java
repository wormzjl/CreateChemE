package com.wormzjl.createcheme.science.thermo.phase;

import com.wormzjl.createcheme.science.thermo.PengRobinsonKernel;

/**
 * WP7b side observation (scratch, never tracked): the phase identification parameter of dilute pure nitrogen and
 * methane on the network kernel over temperature. PIP > 1 is "liquid-like"; a dilute gas whose B - T dB/dT is
 * positive (above roughly its Boyle temperature) has PIP slightly above 1.
 */
public final class Wp7bHotGasPip {
    public static void main(String[] args) {
        var evaluator = PhaseTestSupport.networkEvaluator();
        var kernel = evaluator.kernel();
        var mixture = kernel.newWorkspace();
        var evaluation = kernel.newEvaluation();
        for (String id : new String[] {"Nitrogen", "Methane"}) {
            int k = evaluator.components().indexOf(id);
            double[] x = new double[kernel.componentCount()];
            x[k] = 1.0;
            for (double t : new double[] {300, 350, 400, 450, 500, 550, 600, 650, 700, 800, 900}) {
                StringBuilder line = new StringBuilder(id + " " + t + " K:");
                for (double p : new double[] {1.0e3, 5.0e4, 1.0e5, 5.0e5, 2.0e6}) {
                    kernel.evaluate(t, p, x, PengRobinsonKernel.Root.VAPOR, mixture, evaluation);
                    double pip = PhaseIdentification.parameter(evaluation, t, p);
                    line.append(String.format(" %.0f Pa %.9f%s", p, pip, PhaseIdentification.liquidLike(pip) ? " (liquid-like)" : ""));
                }
                System.out.println(line);
            }
        }
        // The engine's TP answer for dry pure nitrogen: classification and label of its one phase.
        var service = PhaseTestSupport.networkService();
        double[] pure = new double[service.contract().components().size()];
        pure[service.contract().index("Nitrogen")] = 1.0;
        for (double t : new double[] {600.0, 650.0, 700.0}) {
            for (double p : new double[] {1.0e5, 5.0e5}) {
                var r = service.tp(EquilibriumRequest.tp(t, p, service.contract().components(), pure, PhaseCompetition.FLUID_ONLY));
                System.out.println("engine N2 " + t + " K " + p + " Pa: " + r.status() + " " + r.classification() + " "
                        + (r.phases().isEmpty() ? "-" : r.phases().get(0).kind()));
            }
        }
    }
}
