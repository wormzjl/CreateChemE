package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.createcheme.science.fluid.network.PassiveNetwork.PhasePort;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Decision D13 (documentation/2026-09-26-phase-ports-and-compressor/PHASE_PORTS_REVIEW.md, sections "Vent gate defect" and
 * "D13"): a point the reconstruction's equation gate refuses is re-solved once on a fresh Jacobian before the step is
 * refused ({@code PassiveStepSolver.polishRefusedPoint}).
 *
 * <p>The reproduction is the D9 manometer with B vented: tanks A and B, 0.3 m3 of water under nitrogen each, joined bottom
 * to bottom (LIQUID to LIQUID, 2 m, 50 mm); A's headspace fed by a nitrogen generator at 104325 Pa on a BULK end, B filled
 * through its LIQUID port and vented through its VAPOR port to a 1 atm void (2 m, 50 mm). Before D13 the 5 s run failed its
 * second slice on the interval substep limit, every substep refused by the gate at about 1.008e-8: the Newton's chord
 * iterates stop inside the tolerance on A's nitrogen balance, whose scale is the tank's total amount (99.8 % water), and the
 * reconstruction's fixed-(T, P) restatement magnifies that about 500 times in the volume and saturation rows. The BULK-vent
 * control refused one substep per 5 s slice for the same reason.
 *
 * <p>Every slice is a job as the island runtime runs it, commits whole, closes the component ledger to 1e-12 and the energy
 * ledger to 1e-10, and no substep is refused by the equation gate (the rejection reasons and the
 * {@link SolverDiagnostics#equationGateRejections} counter both say so). Each run prints a {@code VENT_GATE_RUN} line.
 */
class VentGatePolishTest {
    private static final Runnable NOOP=()->{};
    private static final String GATE="Conservative reconstruction fails equation gate";
    private static final double COMPONENT_LEDGER=1e-12,ENERGY_LEDGER=1e-10;
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
    private final double[] mw=model.molecularWeights();
    private final int water=model.components().indexOf("Water"),nitrogen=model.components().indexOf("Nitrogen");

    private static PipeResistance.Geometry line(double length,double diameter){return new PipeResistance.Geometry(length,diameter,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);}
    private static PassiveNetwork.Pipe pipe(long id,int a,int b,PhasePort pa,PhasePort pb) {
        return new PassiveNetwork.Pipe(id,a,b,List.of(line(2,.05)),new FlowControl.Passive(),0,null,pa,pb);
    }
    /** A 1 m3 vessel at 298.15 K and {@code pressure} holding {@code waterVolume} m3 of water under nitrogen. */
    private FluidThermodynamics.State waterUnderNitrogen(double waterVolume,double pressure) {
        double t=298.15;double[] n=new double[mw.length];
        n[water]=waterVolume*997/mw[water];n[nitrogen]=pressure*(1-waterVolume)/(FluidThermodynamics.R*t);
        var unit=model.flashTP(t,pressure,n,NOOP);for(int c=0;c<n.length;c++)n[c]/=unit.volume();
        return model.flashTP(t,pressure,n,NOOP);
    }
    /** The manometer with B vented through {@code vent} to a 1 atm void. */
    private PassiveNetwork ventedManometer(PhasePort vent) {
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,waterUnderNitrogen(.3,104325)),new PassiveNetwork.Reservoir(2,0,waterUnderNitrogen(.3,101325)),
                new PassiveNetwork.Reservoir(3,0,model.initialNitrogenCharge(1,298.15,104325,NOOP),PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(4,0,model.initialNitrogenCharge(1,298.15,101325,NOOP),PassiveNetwork.NodeKind.VOID));
        return new PassiveNetwork(nodes,List.of(pipe(96,0,1,PhasePort.LIQUID,PhasePort.LIQUID),pipe(97,2,0,PhasePort.BULK,PhasePort.BULK),pipe(98,1,3,vent,PhasePort.BULK)));
    }

    @Test void aTankFilledThroughItsBottomAndVentedThroughItsTopIntegratesAtFiveSecondSlices(){run("vapor-vent-5.0",PhasePort.VAPOR,5,8,true);}
    @Test void aTankFilledThroughItsBottomAndVentedThroughItsTopIntegratesAtTenthSecondSlices(){run("vapor-vent-0.1",PhasePort.VAPOR,.1,100,false);}
    @Test void theBulkVentControlIsNeverRefusedByTheGate(){run("bulk-vent-5.0",PhasePort.BULK,5,8,true);}

    /** {@code count} slices of {@code interval}; {@code polished}: the run must have needed (and accepted) a polish, which
     * is what it refused before D13. */
    private void run(String label,PhasePort vent,double interval,int count,boolean polished) {
        var graph=ventedManometer(vent);
        double[] initial=owned(graph),external=new double[mw.length];double initialEnergy=ownedEnergy(graph),externalEnergy=0;
        var reasons=new TreeMap<String,Integer>();int accepted=0,rejected=0;long gateRejections,polishes,polishesAccepted;
        var solver=new PassiveIntervalSolver(model);PassiveIntervalSolver.Result committed=null;
        boolean enabled=SolverDiagnostics.ENABLED;SolverDiagnostics.reset();SolverDiagnostics.ENABLED=true;
        try {
            for(int slice=0;slice<count;slice++) {
                solver.replayStart(graph,committed==null?null:committed.graph(),committed==null?null:committed.endpointModes());
                var result=solver.solve(graph,interval,PassiveIntervalSolver.Settings.defaults(),NOOP);committed=result;graph=result.graph();
                assertEquals(PassiveStepSolver.Acceptance.FULL,result.acceptance(),label+" slice "+slice);
                assertEquals(interval,result.advancedSeconds(),0,label+" slice "+slice+": the whole slice commits");
                assertFalse(result.rejectionReasons().containsKey(GATE),label+" slice "+slice+": refused by the equation gate "+result.rejectionReasons());
                for(var transfer:result.boundaries()){var n=transfer.moles();for(int c=0;c<n.length;c++)external[c]+=n[c];externalEnergy+=transfer.totalEnergyJoule();}
                var now=owned(graph);
                for(int c=0;c<now.length;c++){double error=Math.abs(now[c]-initial[c]-external[c])/Math.max(1,initial[c]);
                    assertTrue(error<COMPONENT_LEDGER,label+" slice "+slice+": component "+c+" ledger "+error);}
                double error=Math.abs(ownedEnergy(graph)-initialEnergy-externalEnergy)/Math.max(1,Math.abs(initialEnergy));
                assertTrue(error<ENERGY_LEDGER,label+" slice "+slice+": energy ledger "+error);
                accepted+=result.acceptedSubsteps();rejected+=result.rejectedSubsteps();result.rejectionReasons().forEach((k,v)->reasons.merge(k,v,Integer::sum));
            }
            gateRejections=SolverDiagnostics.equationGateRejections.sum();polishes=SolverDiagnostics.equationGatePolishes.sum();polishesAccepted=SolverDiagnostics.equationGatePolishesAccepted.sum();
        } finally {SolverDiagnostics.ENABLED=enabled;SolverDiagnostics.reset();}
        var a=graph.reservoirs().get(0).state();var b=graph.reservoirs().get(1).state();
        System.out.println("VENT_GATE_RUN "+label+" slices="+count+" PA="+a.pressure()+" PB="+b.pressure()+" mA="+model.liquidMass(a)+" mB="+model.liquidMass(b)
                +" accepted="+accepted+" rejected="+rejected+" reasons="+reasons+" gateRejections="+gateRejections+" polishes="+polishes+" polishesAccepted="+polishesAccepted);
        assertEquals(0,gateRejections,label+": steps refused by the equation gate");
        assertTrue(model.liquidMass(b)>model.liquidMass(graph.reservoirs().get(0).state()),label+": water has moved from A into B");
        if(polished)assertTrue(polishesAccepted>0,label+": the run passes because refused points were polished ("+polishes+" polishes)");
    }
    private double[] owned(PassiveNetwork graph) {
        var totals=new double[mw.length];
        for(var node:graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR){var n=node.inventory().moles();for(int c=0;c<n.length;c++)totals[c]+=n[c];}
        return totals;
    }
    private double ownedEnergy(PassiveNetwork graph) {
        double energy=0;
        for(var node:graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR)energy+=node.inventory().internalEnergy();
        return energy;
    }
}
