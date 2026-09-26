package com.wormzjl.createcheme.science.thermo.phase;

import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.thermo.PengRobinsonKernel;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * WP7d probe: two-phase answers whose phases are both liquid-like by the WP7c rule (PIP > max(1, Z)). The F7 states (CO2
 * in liquid methane, 91-100 K, 0.5 and 2 MPa, x_CO2 1e-2) on an open research contract over the pilot evaluator, and a
 * scan of N2/C2H6 on the pilot network contract (type III: liquid-liquid immiscibility below about 130 K).
 */
public final class Wp7dLiquidLiquid {
    static final String PILOT = "createcheme:pilot_cryogenic";

    public static void main(String[] args) {
        var catalog = MaterialCatalog.bundled();
        var evaluator = CubicPhaseEvaluator.forPackage(catalog, PILOT);
        var identity = new ThermoIdentity("wp7d:research", evaluator.family(), ThermoIdentity.NO_SPINE, ThermoIdentity.NO_WATER);
        var contract = new PhaseContract("wp7d:research", identity, evaluator.components(), WaterParticipation.NONE, null,
                Set.of(PhaseCompetition.FLUID_ONLY), Set.of(), PhaseDomain.open("wp7d:research", evaluator.components()),
                new EquilibriumResult.Coverage(EquilibriumResult.CoverageGrade.RESEARCH_ONLY, "WP7d probe, open domain"));
        var research = new FluidTpEquilibrium(contract, evaluator);
        var ws = research.newWorkspace();
        for (double t : new double[] {91, 95, 100, 110}) {
            for (double p : new double[] {0.5e6, 2e6}) {
                double x = 1e-2;
                double[] z = {0, 1 - x, 0, x};
                var r = research.tp(EquilibriumRequest.tp(t, p, contract.components(), z, PhaseCompetition.FLUID_ONLY), ws);
                print("F7 CH4/CO2 x_CO2 0.01", t, p, r, evaluator);
            }
        }
        var pilot = new FluidTpEquilibrium(PhaseContract.forNetworkPackage(catalog, PILOT), evaluator);
        var pws = pilot.newWorkspace();
        int found = 0;
        for (double t = 95; t <= 135 && found < 12; t += 5) {
            for (double p = 1e6; p <= 10e6 && found < 12; p += 1e6) {
                for (double xn : new double[] {0.1, 0.3, 0.5, 0.7}) {
                    double[] z = {xn, 0, 1 - xn, 0, 0};
                    var r = pilot.tp(EquilibriumRequest.tp(t, p, pilot.contract().components(), z, PhaseCompetition.FLUID_ONLY), pws);
                    if (r.converged() && r.phases().size() == 2 && bothLiquidLike(r, evaluator)) {
                        print("N2/C2H6", t, p, r, evaluator);
                        found++;
                    }
                }
            }
        }
        System.out.println("N2/C2H6 liquid-like pairs printed: " + found);
    }

    static boolean bothLiquidLike(EquilibriumResult r, CubicPhaseEvaluator evaluator) {
        for (var ph : r.phases()) if (!(pip(ph, evaluator)[0] > Math.max(1, pip(ph, evaluator)[1]))) return false;
        return true;
    }

    /** {PIP, Z} of a phase on its own root. */
    static double[] pip(PhaseAmounts ph, CubicPhaseEvaluator evaluator) {
        var kernel = evaluator.kernel();
        double[] x = Arrays.copyOf(ph.amountsView(), evaluator.componentCount());
        var e = kernel.newEvaluation();
        var root = ph.state().root() == com.wormzjl.createcheme.science.thermo.PhaseRoot.VAPOR ? PengRobinsonKernel.Root.VAPOR : PengRobinsonKernel.Root.LIQUID;
        kernel.evaluate(ph.temperature(), ph.pressure(), x, root, kernel.newWorkspace(), e);
        return new double[] {PhaseIdentification.parameter(e, ph.temperature(), ph.pressure()), e.compressibility(), e.physicalRootCount()};
    }

    static void print(String name, double t, double p, EquilibriumResult r, CubicPhaseEvaluator evaluator) {
        StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "%s %.0f K %.1f MPa: %s %s", name, t, p / 1e6, r.status(), r.classification()));
        if (r.converged()) {
            for (var ph : r.phases()) {
                double n = ph.total(), mw = 0;
                for (int i = 0; i < evaluator.componentCount(); i++) mw += ph.amount(i) / n * evaluator.component(i).molarMassKilogramPerMol();
                double[] q = pip(ph, evaluator);
                b.append(String.format(Locale.ROOT, "%n   %s root %s roots %.0f: amount %.4f x %s rho %.1f PIP %.3f Z %.4f", ph.kind(), ph.state().root(), q[2], n,
                        Arrays.toString(Arrays.stream(Arrays.copyOf(ph.amountsView(), evaluator.componentCount())).map(v -> Math.round(v / n * 1e4) / 1e4).toArray()),
                        mw / ph.state().molarVolume(), q[0], q[1]));
            }
        } else b.append(" ").append(r.detail());
        System.out.println(b);
    }
}
