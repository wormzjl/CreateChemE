package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.thermo.phase.EquilibriumRequest;
import com.wormzjl.createcheme.science.thermo.phase.FluidTpEquilibrium;
import com.wormzjl.createcheme.science.thermo.phase.PhaseCompetition;
import java.util.Arrays;
import java.util.List;

/** Exploratory: the engine's UV answer for P4's (d) fixture inventories (the vapour-slot charge and the TP adapter's liquid). */
public final class P5DProbe {
    public static void main(String[] a) {
        var catalog = MaterialCatalog.bundled();
        var model = new FluidThermodynamics(catalog, "createcheme:pilot_cryogenic");
        var engine = FluidTpEquilibrium.forPackage(catalog, "createcheme:pilot_cryogenic");
        double[] feed = {0, 0, .3, .7, 0};
        var gas = model.state(200, 2e6, new double[4], new double[] {0, 0, .3, .7}, 0, 0, 2e6);
        var liquid = model.flashTP(200, 2e6, feed, () -> { });
        var liquidLow = model.flashTP(200, 5e5, feed, () -> { });
        for (var s : List.of(gas, liquid, liquidLow)) {
            var inv = new PassiveNetwork.Reservoir(1, 0, s).inventory();
            double[] totals = inv.speciesMoles();
            double energy = inv.internalEnergy();
            var formation = model.hydrocarbon.formationReference().orElseThrow();
            for (int i = 0; i < totals.length - 1; i++) energy += totals[i] * formation.offsetJoulesPerMole(i);
            var uv = engine.uv(EquilibriumRequest.uv(energy, inv.volume(), engine.contract().components(), totals, PhaseCompetition.FLUID_AND_CRYSTALS),
                    engine.newWorkspace());
            System.out.println("V " + inv.volume() + " m3/mol-total " + Arrays.stream(totals).sum() + ": " + uv);
        }
    }
}
