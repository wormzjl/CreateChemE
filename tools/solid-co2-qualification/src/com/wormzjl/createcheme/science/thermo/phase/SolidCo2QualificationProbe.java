package com.wormzjl.createcheme.science.thermo.phase;

import static com.wormzjl.createcheme.science.thermo.phase.SolidCarbonDioxideReferences.*;

import com.wormzjl.createcheme.science.material.CrystalReference;
import com.wormzjl.createcheme.science.material.JaegerSpanGibbsFunction;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.material.PilotCryogenicTestCatalog;
import com.wormzjl.createcheme.science.thermo.PhaseRoot;
import com.wormzjl.createcheme.science.thermo.reference.HelmholtzFluid;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.DoubleUnaryOperator;

/**
 * One-off measurement probe of P4 stage 1 (batch 2026-09-24-coolprop-low-temperature, tools/solid-co2-qualification):
 * prints every table of P4_SOLID_CO2_MODEL.md. Not a gate; the gates are the tracked tests.
 */
public final class SolidCo2QualificationProbe {
    static final String CRYSTAL = "createcheme:crystal_carbon_dioxide_i";
    static final String PILOT = "createcheme:pilot_cryogenic";
    static final String CRYSTAL_PATH = "data/createcheme/materials/crystals/carbon_dioxide_i.json";

    public static void main(String[] args) {
        MaterialCatalog catalog = MaterialCatalog.bundled();
        CrystalReference crystal = catalog.crystals().get(CRYSTAL);
        JaegerSpanGibbsFunction js = crystal.gibbsFunction();
        tableFive(js);
        oracle(js, 8875);
        oracle(js, 9019);
        for (double fusion : new double[] {8875, 9019}) pilot(fusion);
        trusler(js);
    }

    static void tableFive(JaegerSpanGibbsFunction js) {
        System.out.println("== Table 5 check values with the published g0, g1 ==");
        double[][] rows = {
            {216.592, 517950, -1.447007522e3, 2.848595255e-5, -1.803247012e1, 5.913420271e1, 8.127788321e-4, 2.813585169e-10},
            {100, 1e8, -2.961795962e3, 2.614596591e-5, -5.623154438e1, 3.911045710e1, 3.843376525e-4, 1.149061787e-10},
        };
        String[] names = {"g", "v", "s", "cp", "alpha", "kappa"};
        for (double[] r : rows) {
            var s = js.evaluate(r[0], r[1], js.publishedG0(), js.publishedG1());
            double[] got = {s.gibbs(), s.volume(), s.entropy(), s.heatCapacity(), s.expansion(), s.compressibility()};
            for (int i = 0; i < 6; i++)
                System.out.printf("T=%.3f p=%.0f %-6s table %.9e computed %.12e rel %.3e%n", r[0], r[1], names[i], r[2 + i], got[i], got[i] / r[2 + i] - 1);
        }
        var ref = js.evaluate(150, 101325, js.publishedG0(), js.publishedG1());
        System.out.printf("v(T0, p0) %.10e vs v0 %.8e rel %.3e%n", ref.volume(), js.referenceVolume(), ref.volume() / js.referenceVolume() - 1);
    }

    static double[] constants(JaegerSpanGibbsFunction js, double mu, double h, double fusion) {
        double s = (h - mu) / TRIPLE_TEMPERATURE - fusion / TRIPLE_TEMPERATURE;
        return js.integrationConstants(TRIPLE_TEMPERATURE, TRIPLE_PRESSURE, mu, s);
    }

    static DoubleUnaryOperator[] solidAt(JaegerSpanGibbsFunction js, double[] c, double t) {
        return new DoubleUnaryOperator[] {p -> js.evaluate(t, p, c[0], c[1]).gibbs(), p -> js.evaluate(t, p, c[0], c[1]).volume()};
    }

    static DoubleUnaryOperator[] oracleFluid(HelmholtzFluid co2, double t, HelmholtzFluid.Root root) {
        return new DoubleUnaryOperator[] {p -> co2.stateAtPressure(t, p, root).gibbsEnergy(), p -> 1 / co2.density(t, p, root)};
    }

    static double oracleCoexistence(JaegerSpanGibbsFunction js, double[] c, HelmholtzFluid co2, double t, HelmholtzFluid.Root root, double start) {
        DoubleUnaryOperator[] solid = solidAt(js, c, t);
        DoubleUnaryOperator[] fluid = oracleFluid(co2, t, root);
        return coexistencePressure(start, solid[0], solid[1], fluid[0], fluid[1]);
    }

    static void oracle(JaegerSpanGibbsFunction js, double fusion) {
        System.out.println("== Oracle (Span-Wagner via CoolProp CarbonDioxide.json) anchoring, dH_fus = " + fusion + " ==");
        HelmholtzFluid co2 = HelmholtzFluid.load("CarbonDioxide");
        var liquid = co2.stateAtPressure(TRIPLE_TEMPERATURE, TRIPLE_PRESSURE, HelmholtzFluid.Root.LIQUID);
        double[] c = constants(js, liquid.gibbsEnergy(), liquid.enthalpy(), fusion);
        System.out.printf("liquid at triple point: mu %.6f h %.6f s %.8f v %.6e%n", liquid.gibbsEnergy(), liquid.enthalpy(), liquid.entropy(), 1 / liquid.density());
        System.out.printf("g0 %.8f (published %.7f, diff %.3e)  g1 %.8f (published %.7f, diff %.3e)%n", c[0], js.publishedG0(), c[0] - js.publishedG0(),
                c[1], js.publishedG1(), c[1] - js.publishedG1());
        var vapourTriple = co2.stateAtPressure(TRIPLE_TEMPERATURE, TRIPLE_PRESSURE, HelmholtzFluid.Root.VAPOUR);
        System.out.printf("oracle vapour-liquid mu difference at (Tt, pt): %.6f J/mol%n", vapourTriple.gibbsEnergy() - liquid.gibbsEnergy());
        System.out.println("T  p_calc  p_SW  dp=p_SW-p_calc(Pa)  unc  |dp|/unc  rel%  vs_Trusler48%  vs_FS%");
        double worstRatio = 0;
        List<Double> temperatures = new ArrayList<>();
        for (double t = 80; t < 216; t += 2) temperatures.add(t);
        for (double t : new double[] {150, 170, 185, 190, 192, 193, 194, 194.6855, 196, 198, 200, 204, 208, 210, 211, 212, 213, 214, 215, 216, 216.3, 216.5, 216.592})
            if (!temperatures.contains(t)) temperatures.add(t);
        temperatures.sort(null);
        for (double t : temperatures) {
            double p = oracleCoexistence(js, c, co2, t, HelmholtzFluid.Root.VAPOUR, spanWagnerSublimationPressure(t));
            double sw = spanWagnerSublimationPressure(t);
            double unc = spanWagnerSublimationUncertainty(t);
            worstRatio = Math.max(worstRatio, Math.abs(sw - p) / unc);
            System.out.printf("%8.4f %14.6e %14.6e %10.3f %5.0f %6.3f %9.4f %9.4f %9.4f%n", t, p, sw, sw - p, unc, Math.abs(sw - p) / unc, 100 * (p / sw - 1),
                    t >= 150 ? 100 * (p / truslerSublimationPressure(t) - 1) : Double.NaN, 100 * (p / fraySchmittSublimationPressure(t) - 1));
        }
        System.out.printf("worst |dp|/uncertainty %.3f%n", worstRatio);
        System.out.println("melting: T  p_calc  p_SW  rel%(SW-calc)/SW  unc%  vs_Trusler47%");
        double worstMelt = 0;
        for (double t : new double[] {216.6, 216.7, 217, 217.5, 218, 219, 220, 222, 225, 230, 235, 240, 245, 250, 255, 260, 265, 270}) {
            double p = oracleCoexistence(js, c, co2, t, HelmholtzFluid.Root.LIQUID, spanWagnerMeltingPressure(t));
            double sw = spanWagnerMeltingPressure(t);
            double rel = (sw - p) / sw;
            worstMelt = Math.max(worstMelt, Math.abs(rel) / spanWagnerMeltingUncertainty(t));
            System.out.printf("%8.3f %14.6e %14.6e %9.4f %5.2f %9.4f%n", t, p, sw, 100 * rel, 100 * spanWagnerMeltingUncertainty(t), 100 * (p / truslerMeltingPressure(t) - 1));
        }
        System.out.printf("worst |dp/p|/uncertainty %.3f%n", worstMelt);
        for (double t : new double[] {140, 170, 194.67, 195}) {
            double p = oracleCoexistence(js, c, co2, t, HelmholtzFluid.Root.VAPOUR, spanWagnerSublimationPressure(t));
            var vapour = co2.stateAtPressure(t, p, HelmholtzFluid.Root.VAPOUR);
            var s = js.evaluate(t, p, c[0], c[1]);
            System.out.printf("dh_sub(%.2f K, %.3f Pa) = %.3f J/mol (target %.0f, %.3f %%)%n", t, p, vapour.enthalpy() - s.enthalpy(),
                    GIAUQUE_EGAN_SUBLIMATION_ENTHALPY, 100 * ((vapour.enthalpy() - s.enthalpy()) / GIAUQUE_EGAN_SUBLIMATION_ENTHALPY - 1));
        }
    }

    static void pilot(double fusion) {
        System.out.println("== Pilot family (" + PILOT + ", PR78 translated, Soave, spine) anchoring, dH_fus = " + fusion + " ==");
        MaterialCatalog catalog = PilotCryogenicTestCatalog.parseWith(CRYSTAL_PATH, o -> o.getAsJsonObject("anchor").addProperty("fusion_enthalpy_j_per_mol", fusion));
        var fluid = CubicPhaseEvaluator.forPackage(catalog, PILOT);
        var solid = CrystalPhaseEvaluator.forPackage(catalog, PILOT, CRYSTAL);
        var anchor = solid.anchor(fluid);
        int co2 = fluid.components().indexOf("CarbonDioxide");
        double[] pure = new double[fluid.componentCount()];
        pure[co2] = 1;
        var ws = fluid.newWorkspace();
        System.out.printf("anchor: mu_L %.6f h_L %.6f; constants %s%n", anchor.chemicalPotential(), anchor.liquidEnthalpy(),
                Arrays.toString(solid.record().integrationConstants(anchor.chemicalPotential(), anchor.liquidEnthalpy())));
        // PR78's own saturation at the triple point (explains the offset at T_t).
        HelmholtzFluid oracle = HelmholtzFluid.load("CarbonDioxide");
        var liquidTriple = fluid.evaluate(TRIPLE_TEMPERATURE, TRIPLE_PRESSURE, pure, PhaseRoot.LIQUID, ws, fluid.newState()).copy();
        var vapourTriple = fluid.evaluate(TRIPLE_TEMPERATURE, TRIPLE_PRESSURE, pure, PhaseRoot.VAPOR, ws, fluid.newState()).copy();
        var oracleLiquid = oracle.stateAtPressure(TRIPLE_TEMPERATURE, TRIPLE_PRESSURE, HelmholtzFluid.Root.LIQUID);
        var oracleVapour = oracle.stateAtPressure(TRIPLE_TEMPERATURE, TRIPLE_PRESSURE, HelmholtzFluid.Root.VAPOUR);
        System.out.printf("PR at (Tt, pt): liquid %s vapour %s; mu_V - mu_L %.4f J/mol (oracle %.4f); h_V - h_L %.3f (oracle %.3f, %.3f %%); v_L %.6e (oracle %.6e, %.3f %%)%n",
                liquidTriple.kind(), vapourTriple.kind(), vapourTriple.chemicalPotential(co2) - liquidTriple.chemicalPotential(co2),
                oracleVapour.gibbsEnergy() - oracleLiquid.gibbsEnergy(), vapourTriple.molarEnthalpy() - liquidTriple.molarEnthalpy(),
                oracleVapour.enthalpy() - oracleLiquid.enthalpy(),
                100 * ((vapourTriple.molarEnthalpy() - liquidTriple.molarEnthalpy()) / (oracleVapour.enthalpy() - oracleLiquid.enthalpy()) - 1),
                liquidTriple.molarVolume(), 1 / oracleLiquid.density(), 100 * (liquidTriple.molarVolume() * oracleLiquid.density() - 1));
        System.out.println("sublimation: T  p_pilot  p_SW  rel%_vs_SW  rel%_vs_FS  rel%_vs_Trusler48  dT_equiv(K)");
        double worstSw = 0, worstFs = 0;
        List<Double> temperatures = new ArrayList<>();
        for (double t = 130; t < 216; t += 5) temperatures.add(t);
        for (double t : new double[] {194.6855, 194.7, 212, 214, 216, 216.592}) if (!temperatures.contains(t)) temperatures.add(t);
        temperatures.sort(null);
        for (double t : temperatures) {
            double p = coexistence(fluid, solid, anchor, t, pure, co2, PhaseRoot.VAPOR, spanWagnerSublimationPressure(t));
            double sw = spanWagnerSublimationPressure(t);
            double fs = fraySchmittSublimationPressure(t);
            double relSw = p / sw - 1, relFs = p / fs - 1;
            worstSw = Math.max(worstSw, Math.abs(relSw));
            worstFs = Math.max(worstFs, Math.abs(relFs));
            double dlnp = (Math.log(spanWagnerSublimationPressure(t + 1e-3)) - Math.log(spanWagnerSublimationPressure(t - 1e-3))) / 2e-3;
            System.out.printf("%9.4f %14.6e %14.6e %9.4f %9.4f %9.4f %8.4f%n", t, p, sw, 100 * relSw, 100 * relFs,
                    t >= 150 ? 100 * (p / truslerSublimationPressure(t) - 1) : Double.NaN, -Math.log(p / sw) / dlnp);
        }
        System.out.printf("worst |rel| vs SW %.4f %%, vs FS %.4f %%%n", 100 * worstSw, 100 * worstFs);
        System.out.println("melting: T  p_pilot  p_SW  rel%(pilot/SW-1)  (p-pt)_pilot/(p-pt)_SW-1 %  T_SW(p_pilot)-T (K)");
        double previous = 216.592;
        for (double t = 216.6; ; t += (t < 216.8 ? 0.1 : 0.25)) {
            double p = coexistence(fluid, solid, anchor, t, pure, co2, PhaseRoot.LIQUID, spanWagnerMeltingPressure(t));
            if (p > 1e7) {
                double lo = previous, hi = t;
                for (int i = 0; i < 60; i++) {
                    double mid = 0.5 * (lo + hi);
                    if (coexistence(fluid, solid, anchor, mid, pure, co2, PhaseRoot.LIQUID, spanWagnerMeltingPressure(mid)) > 1e7) hi = mid; else lo = mid;
                }
                double tsw = invertMelting(1e7);
                System.out.printf("T_melt(10 MPa): pilot %.4f K, SW %.4f K, diff %.4f K%n", lo, tsw, lo - tsw);
                break;
            }
            previous = t;
            double sw = spanWagnerMeltingPressure(t);
            System.out.printf("%8.3f %14.6e %14.6e %9.4f %9.4f %8.4f%n", t, p, sw, 100 * (p / sw - 1), 100 * ((p - TRIPLE_PRESSURE) / (sw - TRIPLE_PRESSURE) - 1),
                    invertMelting(p) - t);
        }
        for (double t : new double[] {140, 170, 194.67, 195}) {
            double p = coexistence(fluid, solid, anchor, t, pure, co2, PhaseRoot.VAPOR, spanWagnerSublimationPressure(t));
            var vapour = fluid.evaluate(t, p, pure, PhaseRoot.VAPOR, ws, fluid.newState());
            var s = solid.evaluate(t, p, anchor, new PhaseState(1));
            double dh = vapour.molarEnthalpy() - s.molarEnthalpy();
            System.out.printf("dh_sub(%.2f K, %.3f Pa) = %.3f J/mol (target %.0f, %.3f %%) [vapour kind %s]%n", t, p, dh, GIAUQUE_EGAN_SUBLIMATION_ENTHALPY,
                    100 * (dh / GIAUQUE_EGAN_SUBLIMATION_ENTHALPY - 1), vapour.kind());
        }
    }

    static double invertMelting(double p) {
        double lo = 216.592, hi = 330;
        for (int i = 0; i < 100; i++) { double mid = 0.5 * (lo + hi); if (spanWagnerMeltingPressure(mid) > p) hi = mid; else lo = mid; }
        return 0.5 * (lo + hi);
    }

    static double coexistence(CubicPhaseEvaluator fluid, CrystalPhaseEvaluator solid, SolidPhaseEvaluator.TriplePointAnchor anchor, double t,
                              double[] pure, int i, PhaseRoot root, double start) {
        var ws = fluid.newWorkspace();
        var state = fluid.newState();
        var crystal = new PhaseState(1);
        return coexistencePressure(start,
                p -> solid.evaluate(t, p, anchor, crystal).molarGibbsEnergy(),
                p -> solid.evaluate(t, p, anchor, crystal).molarVolume(),
                p -> fluid.evaluate(t, p, pure, root, ws, state).chemicalPotential(i),
                p -> fluid.evaluate(t, p, pure, root, ws, state).molarVolume());
    }

    static void trusler(JaegerSpanGibbsFunction js) {
        System.out.println("== Trusler 2011 cross-check of the crystal's own properties (independent of g0, g1) ==");
        System.out.println("sublimation curve (at the SW sublimation pressure): T  v_JS  v_Trusler50  rel%  alpha_JS  alpha_Trusler50(dlnV/dT)  rel%  kappa_JS  cp_JS");
        for (double t : new double[] {80, 100, 120, 140, 146.48, 160, 180, 189.78, 194.67, 200, 210, 216.592}) {
            double p = spanWagnerSublimationPressure(t);
            var s = js.evaluate(t, p, 0, 0);
            double vt = truslerSublimationVolume(t), at = truslerSublimationExpansion(t);
            System.out.printf("%8.3f %.6e %.6e %8.4f %.5e %.5e %8.3f %.4e %8.4f%n", t, s.volume(), vt, 100 * (s.volume() / vt - 1), s.expansion(), at,
                    100 * (s.expansion() / at - 1), s.compressibility(), s.heatCapacity());
        }
        System.out.println("secondary cp points (Giauque and Egan 1937 as quoted by the P2 r1 record): 146.48 K 47.11, 189.78 K 54.55 J/(mol K)");
        for (double[] point : new double[][] {{146.48, 47.11}, {189.78, 54.55}}) {
            var s = js.evaluate(point[0], spanWagnerSublimationPressure(point[0]), 0, 0);
            System.out.printf("cp_JS(%.2f) %.4f vs %.2f: %.3f %%%n", point[0], s.heatCapacity(), point[1], 100 * (s.heatCapacity() / point[1] - 1));
        }
        System.out.println("melting curve (at the SW melting pressure): T  p  v_JS  v_Trusler49  rel%");
        for (double t : new double[] {216.592, 220, 230, 240, 250, 260, 270}) {
            double p = spanWagnerMeltingPressure(t);
            var s = js.evaluate(t, p, 0, 0);
            double vt = truslerMeltingVolume(t);
            System.out.printf("%8.3f %.4e %.6e %.6e %8.4f%n", t, p, s.volume(), vt, 100 * (s.volume() / vt - 1));
        }
    }
}
