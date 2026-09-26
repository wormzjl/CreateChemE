package com.wormzjl.createcheme.science.thermo.phase;

import com.wormzjl.createcheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.createcheme.science.thermo.PhaseRoot;
import java.util.List;

/**
 * WP7b probe (scratch, never tracked): the pure-nitrogen coexistence UV of
 * FluidTpEquilibriumPhUvTest.pureNitrogenCoexistenceTakesItsVapourFractionFromTheBalance, printing for each beta the UV
 * answer's temperature and pressure errors against the kernel's equal-fugacity temperature, the TP calls and the
 * detail. Args: optional betas.
 */
public final class Wp7bUvProbe {
    public static void main(String[] args) {
        var service = PhaseTestSupport.networkService();
        var contract = service.contract();
        var workspace = service.newWorkspace();
        double p = 0.5e6;
        var kernel = service.evaluator().kernel();
        int k = contract.index("Nitrogen");
        double[] eos = new double[kernel.componentCount()];
        eos[k] = 1.0;
        var mixture = kernel.newWorkspace();
        var liq = kernel.newEvaluation();
        var vap = kernel.newEvaluation();
        double below = 85.0, above = 100.0;
        for (int i = 0; i < 200 && Math.nextUp(below) < above; i++) {
            double m = 0.5 * (below + above);
            kernel.evaluate(m, p, eos, PengRobinsonKernel.Root.LIQUID, mixture, liq);
            kernel.evaluate(m, p, eos, PengRobinsonKernel.Root.VAPOR, mixture, vap);
            if (liq.logFugacityCoefficient(k) - vap.logFugacityCoefficient(k) < 0.0) below = m; else above = m;
        }
        double saturation = 0.5 * (below + above);
        var ws = service.evaluator().newWorkspace();
        PhaseState liquid = service.evaluator().evaluate(saturation, p, eos, PhaseRoot.LIQUID, ws, service.evaluator().newState()).copy();
        PhaseState vapour = service.evaluator().evaluate(saturation, p, eos, PhaseRoot.VAPOR, ws, service.evaluator().newState()).copy();
        double n = 2.0;
        double[] pure = new double[contract.components().size()];
        pure[k] = n;
        List<String> species = contract.components();
        double[] betas = args.length == 0 ? new double[] {0.0005, 0.3, 0.7, 0.9995} : java.util.Arrays.stream(args).mapToDouble(Double::parseDouble).toArray();
        System.out.println("saturation " + saturation + " K; window " + java.util.Arrays.toString(contract.domain().getClass().getSimpleName().split(",")));
        for (double beta : betas) {
            double u = n * ((1.0 - beta) * liquid.molarInternalEnergy() + beta * vapour.molarInternalEnergy());
            double v = n * ((1.0 - beta) * liquid.molarVolume() + beta * vapour.molarVolume());
            var uv = service.uv(EquilibriumRequest.uv(u, v, species, pure, PhaseCompetition.FLUID_ONLY), workspace);
            double t = uv.phases().isEmpty() ? Double.NaN : uv.phases().get(0).temperature();
            double pp = uv.phases().isEmpty() ? Double.NaN : uv.phases().get(0).pressure();
            System.out.printf("beta %s: %s %s UV TP calls %d, T error %.3e, P error %.3e, spec residual %.3e%n  %s%n", beta, uv.status(),
                    uv.classification(), uv.diagnostics().outerIterations(), t / saturation - 1.0, pp / p - 1.0,
                    uv.diagnostics().specificationResidual(), uv.detail());
            var ph = service.ph(EquilibriumRequest.ph(p, n * ((1.0 - beta) * liquid.molarEnthalpy() + beta * vapour.molarEnthalpy()),
                    species, pure, PhaseCompetition.FLUID_ONLY), workspace);
            System.out.printf("  PH TP calls %d, T error %.3e%n", ph.diagnostics().outerIterations(), ph.phases().get(0).temperature() / saturation - 1.0);
        }
    }
}
