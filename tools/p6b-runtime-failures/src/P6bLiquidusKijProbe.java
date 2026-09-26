package com.wormzjl.createcheme.science.fluid.thermo;

import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.thermo.PairInteractions;
import com.wormzjl.createcheme.science.thermo.PhaseRoot;
import com.wormzjl.createcheme.science.thermo.phase.EquilibriumResult;
import com.wormzjl.createcheme.science.thermo.phase.PhaseAmounts;
import com.wormzjl.createcheme.science.thermo.phase.PhaseKind;
import java.util.Locale;

/**
 * P6b probe (tools/p6b-runtime-failures/, never committed): which side of the solid-liquid equilibrium the methane-rich
 * liquidus error (D19: about 2 K cold, solubility about 25 % high) sits on. At Shen 2012's binary point 150.40 K,
 * 1.055 MPa (x_CO2 0.008225): (1) the engine's CO2 solubility (the liquid of a TP answer with excess CO2 under the crystal
 * competition); (2) the crystal side: the model's pure CO2 sublimation pressure at 150.40 K against Span-Wagner 1996's
 * sublimation equation; (3) the liquid side: the CH4/CO2 k_ij the engine uses there (E-PPR78, matched by a constant-k_ij
 * twin of the same translated PR78), and the solubility with k_ij perturbed at the same crystal fugacity.
 */
public final class P6bLiquidusKijProbe {
    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        String pilot = "createcheme:pilot_cryogenic";
        var model = new FluidThermodynamics(MaterialCatalog.bundled(), pilot);
        var engine = model.phaseEngine();
        double t = 150.40, shen = 0.008225;
        System.out.printf("(1) engine solubility at %.2f K (Shen 2012 binary: x_CO2 %.6f at 1.055 MPa)%n", t, shen);
        double xModel = Double.NaN, pModel = Double.NaN;
        for (double p : new double[] {1.055e6, 2e6, 5e6}) {
            EquilibriumResult r = engine.tp(t, p, new double[] {0, 0.95, 0, 0.05, 0}, true);
            double x = Double.NaN;
            for (PhaseAmounts a : r.phases()) if (a.kind() == PhaseKind.LIQUID) x = a.amount(3) / a.total();
            System.out.printf("    P %.3f MPa: %s liquid x_CO2 %.6f, ratio to Shen %.3f%n", p / 1e6, r.classification(), x, x / shen);
            if (Double.isNaN(xModel) && !Double.isNaN(x)) {xModel = x; pModel = p;}
        }
        // (2) The crystal side: pure CO2 at t, the pressure where the TP answer turns from vapour to crystal.
        double low = 10, high = 1e5;
        for (int k = 0; k < 80; k++) {
            double mid = Math.sqrt(low * high);
            EquilibriumResult r = engine.tp(t, mid, new double[] {0, 0, 0, 1, 0}, true);
            boolean solid = r.phases().stream().anyMatch(a -> a.kind() == PhaseKind.SOLID) || r.classification() == EquilibriumResult.Classification.PURE_COEXISTENCE_UNDERDETERMINED;
            if (solid) high = mid; else low = mid;
        }
        double theta = 1 - t / 216.592;
        double spanWagner = 0.51795e6 * Math.exp(216.592 / t * (-14.740846 * theta + 2.4327015 * Math.pow(theta, 1.9) - 5.3061778 * Math.pow(theta, 2.9)));
        System.out.printf("(2) pure CO2 sublimation pressure at %.2f K: model %.2f Pa, Span-Wagner 1996 %.2f Pa, ratio %.4f%n", t, Math.sqrt(low * high), spanWagner, Math.sqrt(low * high) / spanWagner);
        // (3) The liquid side.
        var spine = model.hydrocarbon.spine().orElseThrow();
        var components = spine.components();
        double[] translations = spine.translations();
        var full = TranslatedPengRobinson.residualOnly(components, spine.interactions(), translations);
        double p = pModel;
        double[] binary = {0, 1 - xModel, 0, xModel};
        double target = full.evaluate(t, p, binary, PhaseRoot.LIQUID).logFugacityCoefficientsView()[3];
        double k0 = 0.1, k1 = 0.12;
        double f0 = lnPhi(components, translations, k0, t, p, binary) - target, f1 = lnPhi(components, translations, k1, t, p, binary) - target;
        for (int i = 0; i < 40 && Math.abs(f1) > 1e-13; i++) {
            double k2 = k1 - f1 * (k1 - k0) / (f1 - f0);
            k0 = k1; f0 = f1; k1 = k2; f1 = lnPhi(components, translations, k1, t, p, binary) - target;
        }
        double kij = k1;
        System.out.printf("(3) CH4/CO2 k_ij of the engine at %.2f K (E-PPR78): %.5f (constant twin matches ln phi_CO2 to %.1e)%n", t, kij, f1);
        double lnFugacityTarget = Math.log(xModel) + target;
        for (double delta : new double[] {-0.02, -0.01, 0, 0.01, 0.02, 0.03, 0.04, 0.05}) {
            double x = xModel;
            for (int i = 0; i < 100; i++) {
                double next = Math.exp(lnFugacityTarget - lnPhi(components, translations, kij + delta, t, p, new double[] {0, 1 - x, 0, x}));
                if (Math.abs(next - x) < 1e-14) {x = next; break;}
                x = next;
            }
            System.out.printf("    k_ij %+.2f (%.5f): x_CO2 %.6f, ratio to Shen %.3f%n", delta, kij + delta, x, x / shen);
        }
    }

    static double lnPhi(java.util.List<com.wormzjl.createcheme.science.thermo.ThermoComponent> components, double[] translations, double kij, double t, double p, double[] amounts) {
        double[][] m = new double[4][4];
        m[1][3] = m[3][1] = kij;
        var twin = TranslatedPengRobinson.residualOnly(components, PairInteractions.constant(m), translations);
        return twin.evaluate(t, p, amounts, PhaseRoot.LIQUID).logFugacityCoefficientsView()[3];
    }
}
