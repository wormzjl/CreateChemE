package com.wormzjl.createcheme.science.thermo.phase;

import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.createcheme.science.thermo.TangentPlaneStability;

/** One wet network state in detail: the network's flash, the engine, and the per-branch stability test at its pc. */
public final class WetDebug {
    public static void main(String[] args) {
        double t = Double.parseDouble(args[0]), p = Double.parseDouble(args[1]), ratio = Double.parseDouble(args[2]);
        var catalog = MaterialCatalog.bundled();
        var fluid = new FluidThermodynamics(catalog, PhaseTestSupport.NETWORK, 1.0e-9);
        var service = PhaseTestSupport.networkService(PhaseTestSupport.networkFreeWater(fluid));
        var contract = service.contract();
        double[] feed = PhaseTestSupport.crudeWithNitrogen(contract);
        double hc = PhaseTestSupport.sum(feed);
        feed[contract.waterIndex()] = ratio * hc;
        var flash = fluid.flashTP(t, p, feed.clone(), () -> { });
        System.out.printf("network: nl %.6g nv %.6g wl %.6g wv %.6g pc %.8g pw %.6g%n", PhaseTestSupport.sum(flash.liquidView()),
                PhaseTestSupport.sum(flash.vaporView()), flash.waterLiquid(), flash.waterVapor(), flash.hydrocarbonPartialPressure(),
                flash.waterPartialPressure());

        var r = service.tp(EquilibriumRequest.tp(t, p, contract.components(), feed, PhaseCompetition.FLUID_ONLY));

        System.out.println("engine: " + r + " / " + r.diagnostics());
        var dry = service.tp(EquilibriumRequest.tp(t, p, contract.components(), java.util.Arrays.copyOf(PhaseTestSupport.crudeWithNitrogen(contract), feed.length), PhaseCompetition.FLUID_ONLY));
        System.out.println("dry engine at P: " + dry + " tie " + dry.diagnostics().tieLine());
        double pc = flash.hydrocarbonPartialPressure();
        var kernel = service.evaluator().kernel();
        var test = new TangentPlaneStability(kernel);
        double[] eos = java.util.Arrays.copyOf(feed, kernel.componentCount());
        var s = test.test(t, p, pc, service.evaluator().translations(), eos, test.newWorkspace());
        System.out.println("branched test at network pc: " + s.verdict() + " tm " + s.minimumTangentPlaneDistance() + " feedRoot "
                + s.feedRoot() + " trialRoot " + s.trialRoot() + " trials " + s.trials());
        var e = kernel.newEvaluation();
        var ws = kernel.newWorkspace();
        for (var root : PengRobinsonKernel.Root.values()) {
            double pr = root == PengRobinsonKernel.Root.VAPOR ? pc : p;
            kernel.evaluate(t, pr, eos, root, ws, e);
            System.out.printf("  feed %s at %.6g: roots %d Z %.5f PIP %.5f%n", root, pr, e.physicalRootCount(), e.compressibility(),
                    PhaseIdentification.parameter(e, t, pr));
        }
        double[] w = s.trialComposition();
        for (var root : PengRobinsonKernel.Root.values()) {
            double pr = root == PengRobinsonKernel.Root.VAPOR ? pc : p;
            kernel.evaluate(t, pr, w, root, ws, e);
            System.out.printf("  trial %s at %.6g: roots %d Z %.5f PIP %.5f%n", root, pr, e.physicalRootCount(), e.compressibility(),
                    PhaseIdentification.parameter(e, t, pr));
        }
    }
}
