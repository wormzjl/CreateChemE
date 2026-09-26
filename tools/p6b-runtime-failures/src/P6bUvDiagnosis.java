package com.wormzjl.createcheme.science.fluid.thermo;

import com.wormzjl.createcheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.createcheme.science.thermo.phase.EquilibriumResult;
import java.util.Arrays;

/** P6b probe helper (tools/p6b-runtime-failures/, never committed): the engine's crystal UV of one vessel, cold and warm. */
public final class P6bUvDiagnosis {
    public static String analyse(FluidThermodynamics model, PassiveNetwork.Reservoir node) {
        var inventory = node.inventory();
        double[] totals = inventory.crystals().speciesTotals(inventory.moles());
        var formation = model.hydrocarbon.formationReference().orElseThrow();
        double energy = inventory.internalEnergy();
        for (int i = 0; i < totals.length - 1; i++) energy += totals[i] * formation.offsetJoulesPerMole(i);
        var engine = model.phaseEngine();
        var s = node.state();
        StringBuilder out = new StringBuilder(String.format("totals %s U %.6e V %.4f at %.4f K %.1f Pa", Arrays.toString(totals), energy, inventory.volume(), s.temperature(), s.pressure()));
        for (int warm = 0; warm < 2; warm++) {
            long start = System.nanoTime();
            EquilibriumResult r = warm == 0 ? engine.uv(energy, inventory.volume(), totals, true) : engine.uv(energy, inventory.volume(), totals, true, s.temperature(), s.pressure());
            double ms = (System.nanoTime() - start) / 1e6;
            String detail = r.detail();
            if (detail.length() > 200) detail = detail.substring(0, 200);
            out.append(String.format("%n     %s: %s %s kernel %d outer %d %.2f ms T %.4f P %.1f | %s", warm == 0 ? "cold" : "warm", r.status(), r.classification(),
                    r.diagnostics().kernelEvaluations(), r.diagnostics().outerIterations(), ms, r.converged() ? r.phases().get(0).temperature() : Double.NaN,
                    r.converged() ? r.phases().get(0).pressure() : Double.NaN, detail));
        }
        return out.toString();
    }
}
