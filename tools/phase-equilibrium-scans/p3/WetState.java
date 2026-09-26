package com.wormzjl.createcheme.science.thermo.phase;

import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;

/** One wet network state: the network flash, the engine wet and dry at P and at the network's pc. */
public final class WetState {
    public static void main(String[] args) {
        double t = Double.parseDouble(args[0]), p = Double.parseDouble(args[1]), ratio = Double.parseDouble(args[2]);
        var fluid = new FluidThermodynamics(MaterialCatalog.bundled(), PhaseTestSupport.NETWORK, 1.0e-9);
        var service = PhaseTestSupport.networkService(PhaseTestSupport.networkFreeWater(fluid));
        var contract = service.contract();
        double[] dry = PhaseTestSupport.crudeWithNitrogen(contract);
        if (args.length > 3) {
            dry = new double[contract.components().size()];
            for (String id : java.util.List.of("Methane", "Ethane", "Propane", "Isobutane", "N-butane", "Isopentane", "N-pentane")) dry[contract.index(id)] = 0.10;
            dry[contract.index("Nitrogen")] = 0.30;
            for (int i = 0; i < dry.length; i++) if (contract.components().get(i).startsWith("crude_pc")) dry[i] = 1e-4;
        }
        double[] feed = dry.clone();
        feed[contract.waterIndex()] = ratio * PhaseTestSupport.sum(dry);
        var flash = fluid.flashTP(t, p, feed.clone(), () -> { });
        System.out.printf("network wet: nl %.6g nv %.6g wv %.6g pc %.8g%n", PhaseTestSupport.sum(flash.liquidView()), PhaseTestSupport.sum(flash.vaporView()), flash.waterVapor(), flash.hydrocarbonPartialPressure());
        var r = service.tp(EquilibriumRequest.tp(t, p, contract.components(), feed, PhaseCompetition.FLUID_ONLY));
        System.out.println("engine wet: " + r + " " + r.freeWater());
        for (var phase : r.phases()) System.out.printf("  %s total %.6g%n", phase.kind(), phase.total());
        System.out.println("  " + r.diagnostics());
        for (double pp : new double[] {p, flash.hydrocarbonPartialPressure()}) {
            var d = service.tp(EquilibriumRequest.tp(t, pp, contract.components(), dry, PhaseCompetition.FLUID_ONLY));
            var nf = fluid.flashTP(t, pp, dry.clone(), () -> { });
            System.out.printf("dry at %.8g: engine %s %s tm %.3e / network nl %.6g nv %.6g%n", pp, d.classification(),
                    d.phases().stream().map(ph -> String.format("%s %.4g", ph.kind(), ph.total())).toList(), d.diagnostics().feedTangentPlaneDistance(),
                    PhaseTestSupport.sum(nf.liquidView()), PhaseTestSupport.sum(nf.vaporView()));
        }
    }
}
