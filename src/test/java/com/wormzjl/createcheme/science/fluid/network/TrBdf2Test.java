package com.wormzjl.createcheme.science.fluid.network;

import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;

class TrBdf2Test {
    @Test void embeddedControlAgreesWithStepDoublingAndFixedStepsShowSecondOrder() {
        var graph=graph(gas(1,180000,0),gas(2,101325,0),new FlowControl.Passive());
        var embedded=new PassiveIntervalSolver(model).solve(graph,.2,PassiveIntervalSolver.Settings.defaults(),()->{});
        var doubled=new PassiveIntervalSolver(model,PassiveIntervalSolver.ErrorControl.STEP_DOUBLING).solve(graph,.2,PassiveIntervalSolver.Settings.defaults(),()->{});
        assertEquals(doubled.averageMassFlows()[0],embedded.averageMassFlows()[0],.005*Math.abs(doubled.averageMassFlows()[0]));
        double reference=integratedMass(graph,256),coarse=Math.abs(integratedMass(graph,4)-reference),fine=Math.abs(integratedMass(graph,8)-reference);
        assertTrue(coarse/fine>3,"Observed error ratio="+coarse/fine);
    }
    private double integratedMass(PassiveNetwork initial,int steps) {
        var graph=initial;var solver=new TrBdf2StepSolver(model);double mass=0;
        for(int i=0;i<steps;i++){var result=solver.solve(graph,.2/steps,()->{});graph=PassiveIntervalSolver.replace(graph,result);mass+=result.massFlows()[0]*.2/steps;}
        return mass;
    }
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
    private PassiveNetwork.Reservoir gas(long id,double pressure,double elevation) {
        return new PassiveNetwork.Reservoir(id,elevation,model.initialNitrogenCharge(1,298.15,pressure,()->{}));
    }
    private PassiveNetwork graph(PassiveNetwork.Reservoir a,PassiveNetwork.Reservoir b,FlowControl control) {
        return new PassiveNetwork(List.of(a,b),List.of(new PassiveNetwork.Pipe(10,0,1,new PipeResistance.Geometry(10,.05,.000045,0),control)));
    }
    @Test void waterAppearanceConservesNitrogenAndTheIntegratedBoundaryLedger() {
        var n=new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1];n[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK]=1;
        var source=new PassiveNetwork.Reservoir(1,0,model.flashTP(298.15,200000,n,()->{}),PassiveNetwork.NodeKind.GENERATOR);
        var graph=graph(source,gas(2,101325,2),new FlowControl.Passive());
        var result=new PassiveIntervalSolver(model).solve(graph,.05,PassiveIntervalSolver.Settings.defaults(),()->{});
        var old=graph.reservoirs().get(1).inventory();var next=result.graph().reservoirs().get(1).inventory();
        double[] incoming=new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1];double energy=0;
        for(var entry:result.boundaries()){assertEquals(1,entry.nodeId());var amounts=entry.moles();for(int c=0;c<com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1;c++)incoming[c]+=amounts[c];energy+=entry.totalEnergyJoule();}
        var before=old.moles();var after=next.moles();double movedMass=0;
        for(int c=0;c<com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1;c++){assertEquals(before[c]+incoming[c],after[c],1e-8);movedMass+=incoming[c]*(c==com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK?model.waterMolecularWeight:model.hydrocarbon.molecularWeight(c));}
        assertTrue(after[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK]>0);assertEquals(before[com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN],after[com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN],1e-9);
        assertEquals(energy,next.internalEnergy()-old.internalEnergy()+movedMass*2*PassiveStepSolver.GRAVITY,1e-3);
    }
    @Test void pumpWorkIncludesAllStagesAndElevationEnergy() {
        // A pump's setting is its rise for water (F4, P1): the setting that is 500 kPa on this nitrogen, as before.
        var suction=gas(1,101325,0);
        var graph=graph(suction,gas(2,200000,10),new FlowControl.Pump(.001,500000*model.pumpReferenceDensity()/(suction.state().mass()/suction.state().volume()),.8));
        var result=new TrBdf2StepSolver(model).solve(graph,.2,()->{});
        assertTrue(result.pumpWorkJoule()>0);double change=0;
        for(int i=0;i<2;i++){var old=graph.reservoirs().get(i).inventory();var next=result.inventories().get(i);double dm=(next.moles()[com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN]-old.moles()[com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN])*model.hydrocarbon.molecularWeight(com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN);change+=next.internalEnergy()-old.internalEnergy()+dm*graph.reservoirs().get(i).elevation()*PassiveStepSolver.GRAVITY;}
        assertEquals(result.pumpWorkJoule(),change,1e-5);assertTrue(result.boundaries().isEmpty());
    }
    @Test void sourceAndVoidKeepSeparateNonzeroLedgersDespiteZeroNetTransfer() {
        var source=new PassiveNetwork.Reservoir(1,0,gas(1,200000,0).state(),PassiveNetwork.NodeKind.GENERATOR);
        var sink=new PassiveNetwork.Reservoir(2,0,gas(2,101325,0).state(),PassiveNetwork.NodeKind.VOID);
        var result=new TrBdf2StepSolver(model).solve(graph(source,sink,new FlowControl.Passive()),2,()->{});
        double sourceMoles=0,sinkMoles=0;for(var entry:result.boundaries()){if(entry.nodeId()==1)sourceMoles+=entry.moles()[com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN];else sinkMoles+=entry.moles()[com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN];}
        assertTrue(sourceMoles>0);assertEquals(sourceMoles,-sinkMoles,1e-9);
        assertEquals(result.massFlows()[0]*2,sourceMoles*model.hydrocarbon.molecularWeight(com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN),1e-9);
    }
    @Test void valveTransitionMatchesRefinedBackwardEulerWithoutFlowAfterClosure() {
        var graph=graph(gas(1,180000,0),gas(2,101325,0),new FlowControl.PressureValve(175000));
        var adaptive=new PassiveIntervalSolver(model).solve(graph,1,PassiveIntervalSolver.Settings.defaults(),()->{});
        var reference=graph;double moved=0;var be=new PassiveStepSolver(model);
        for(int i=0;i<400;i++){
            PassiveStepSolver.Result step;
            try{step=be.solve(reference,.0025,()->{});}catch(RuntimeException failure){throw new AssertionError("Reference step "+i+" Pa="+reference.reservoirs().getFirst().state().pressure(),failure);}
            reference=PassiveIntervalSolver.replace(reference,step);moved+=.0025*step.massFlows()[0];
        }
        assertEquals(moved,adaptive.averageMassFlows()[0],.005*Math.abs(moved)+1e-9);
        assertEquals(reference.reservoirs().getFirst().state().pressure(),adaptive.graph().reservoirs().getFirst().state().pressure(),1);
        var closed=new PassiveIntervalSolver(model).solve(adaptive.graph(),1,PassiveIntervalSolver.Settings.defaults(),()->{});
        assertEquals(0,closed.averageMassFlows()[0],1e-8);
    }
}
