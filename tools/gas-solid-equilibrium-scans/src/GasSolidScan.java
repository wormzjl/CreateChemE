package com.wormzjl.createcheme.science.thermo.phase;

import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.List;
import java.util.Locale;

/**
 * Exploratory scan of the P4 stage 2a gas-solid engine on the pilot package (batch 2026-09-24-coolprop-low-temperature):
 * TP answers of CO2 in N2 and CH4, onset functions, PH round trips. Prints; asserts nothing. Run with
 * {@code bash tools/gas-solid-equilibrium-scans/run.sh main com.wormzjl.createcheme.science.thermo.phase.GasSolidScan}.
 */
public final class GasSolidScan {
    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        var service = FluidTpEquilibrium.forPackage(MaterialCatalog.bundled(), "createcheme:pilot_cryogenic");
        List<String> basis = service.contract().components();
        System.out.println("basis " + basis + " competitions " + service.contract().qualifiedCompetitions());
        var ws = service.newWorkspace();
        double[][] states = {{0.001, 130, 1e5}, {0.01, 150, 1e6}, {0.1, 170, 1e6}, {0.5, 190, 5e5}, {0.5, 130, 5e6},
                {0.2, 200, 3e6}, {0.001, 210, 1e5}, {0.3, 180, 1e5}, {0.1, 160, 1e6}, {0.05, 140, 5e6}, {0.5, 210, 5e6}, {0.01, 130, 3e6}};
        for (double[] s : states) {
            double[] z = {1 - s[0], 0, 0, s[0], 0};
            var r = service.tp(EquilibriumRequest.tp(s[1], s[2], basis, z, PhaseCompetition.FLUID_AND_CRYSTALS), ws);
            System.out.printf("y=%.3f T=%.1f P=%.3g: %s %s%n", s[0], s[1], s[2], r.status(), r.classification());
            if (r.converged() && r.deposition() != null) {
                var d = r.deposition();
                System.out.printf("   solid %.12g complete %s drive %.4g residual %.3g it %d eq %d conservation %.3g%n",
                        d.solidAmount(), d.complete(), d.feedDrivingForce(), d.residual(), d.iterations(), d.fluidEquilibria(),
                        r.conservationDefect(z));
            } else {
                System.out.println("   " + r.detail());
            }
        }
        for (double p : new double[] {1e5, 3e5, 5e5, 1e6, 5e6}) {
            for (double y : new double[] {1 - 1e-6, 0.5, 0.01, 0.001}) {
                double[] z = {1 - y, 0, 0, y, 0};
                var onset = service.depositionTemperature(null, p, basis, z, ws);
                System.out.printf("onset P=%.3g y=%.6f: %s T=%.6f approx=%.6f residual=%.2g eq=%d %s%n", p, y, onset.status(),
                        onset.temperature(), onset.approximation(), onset.residual(), onset.fluidEquilibria(),
                        onset.converged() ? "" : onset.detail());
            }
        }
        // PH round trip at one deposit state.
        double[] z = {0.9, 0, 0, 0.1, 0};
        var tp = service.tp(EquilibriumRequest.tp(160, 1e6, basis, z, PhaseCompetition.FLUID_AND_CRYSTALS), ws);
        System.out.println("TP " + tp);
        var ph = service.ph(EquilibriumRequest.ph(1e6, tp.enthalpy(), basis, z, PhaseCompetition.FLUID_AND_CRYSTALS), ws);
        System.out.println("PH " + ph + " T=" + (ph.converged() ? ph.phases().get(0).temperature() : Double.NaN) + " outer "
                + ph.diagnostics().outerIterations());
        var uv = service.uv(EquilibriumRequest.uv(tp.internalEnergy(), tp.volume(), basis, z, PhaseCompetition.FLUID_AND_CRYSTALS), ws);
        System.out.println("UV " + uv + " T=" + (uv.converged() ? uv.phases().get(0).temperature() : Double.NaN) + " P="
                + (uv.converged() ? uv.phases().get(0).pressure() : Double.NaN) + " outer " + uv.diagnostics().outerIterations());
        // Pure CO2 PH across the sublimation step at 0.1 MPa.
        double[] pure = {0, 0, 0, 1, 0};
        var cold = service.tp(EquilibriumRequest.tp(190, 1e5, basis, pure, PhaseCompetition.FLUID_AND_CRYSTALS), ws);
        var warm = service.tp(EquilibriumRequest.tp(200, 1e5, basis, pure, PhaseCompetition.FLUID_AND_CRYSTALS), ws);
        System.out.println("pure cold " + cold + "\npure warm " + warm);
        for (double beta : new double[] {0.0, 0.3, 0.7, 1.0}) {
            double h = cold.converged() && warm.converged() ? cold.enthalpy() + beta * (warm.enthalpy() - cold.enthalpy()) : Double.NaN;
            var r = service.ph(EquilibriumRequest.ph(1e5, h, basis, pure, PhaseCompetition.FLUID_AND_CRYSTALS), ws);
            System.out.println("pure PH beta " + beta + ": " + r + " T=" + (r.converged() ? r.phases().get(0).temperature() : Double.NaN));
        }
    }
}
