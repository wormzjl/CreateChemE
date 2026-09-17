package com.wormzjl.createcheme.science.fluid.network;

import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.*;
import org.junit.jupiter.api.Test;

class PassiveStepSolverTest {
    private final FluidThermodynamics model=new FluidThermodynamics(MaterialCatalog.bundled(),"createcheme:tjl20_methane",1e-9);
    private FluidThermodynamics.State fill(double pressure,int component) {
        double[] n=new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.CRUDE+1];n[component]=1;var unit=model.flashTP(350,pressure,n,()->{});n[component]/=unit.volume();
        return model.flashTP(350,pressure,n,()->{});
    }
    private PassiveNetwork graph(int component,boolean reverse) {
        var a=new PassiveNetwork.Reservoir(1,0,fill(200000,component));var b=new PassiveNetwork.Reservoir(2,0,fill(101325,component));
        return new PassiveNetwork(reverse?List.of(b,a):List.of(a,b),List.of(new PassiveNetwork.Pipe(3,0,1,new PipeResistance.Geometry(10,.05,.000045,0))));
    }
    @Test void gasAndLiquidFullReservoirsEqualizeWithConservedInventoriesAndEnergy() {
        for(int component:new int[]{0,model.componentCount()-1}) {
            var graph=graph(component,false);var result=new PassiveStepSolver(model).solve(graph,.1,()->{});
            assertTrue(result.massFlows()[0]>0);assertTrue(result.states().get(0).pressure()<200000);
            assertTrue(result.states().get(1).pressure()>101325);assertTrue(result.states().get(0).pressure()>=result.states().get(1).pressure());
            double beforeN=0,afterN=0,beforeU=0,afterU=0;
            for(int i=0;i<2;i++){var before=graph.reservoirs().get(i).state();var after=result.states().get(i);beforeN+=PhaseLayout.totalAmounts(before)[component];afterN+=PhaseLayout.totalAmounts(after)[component];beforeU+=before.internalEnergy();afterU+=after.internalEnergy();assertEquals(before.volume(),after.volume(),1e-8);}
            assertEquals(beforeN,afterN,1e-8*beforeN);assertEquals(beforeU,afterU,1e-6*Math.abs(beforeU));
        }
    }
    @Test void endpointOrderOnlyChangesTheFlowSign() {
        var solver=new PassiveStepSolver(model);var forward=solver.solve(graph(0,false),.1,()->{});var backward=solver.solve(graph(0,true),.1,()->{});
        assertEquals(forward.massFlows()[0],-backward.massFlows()[0],1e-8);
        assertEquals(forward.states().get(0).pressure(),backward.states().get(1).pressure(),.001);
    }
    @Test void branchJunctionHasNoInventoryAndBalancesEveryConnectedFlow() {
        var geometry=new PipeResistance.Geometry(10,.05,.000045,0);
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,fill(300000,0)),
                new PassiveNetwork.Reservoir(2,0,fill(100000,0)),new PassiveNetwork.Reservoir(3,0,fill(100000,0)),
                new PassiveNetwork.Reservoir(4,0,fill(180000,0),true)),List.of(
                new PassiveNetwork.Pipe(5,0,3,geometry),new PassiveNetwork.Pipe(6,3,1,geometry),new PassiveNetwork.Pipe(7,3,2,geometry)));
        var result=new PassiveStepSolver(model).solve(graph,.1,()->{});var flows=result.massFlows();
        assertTrue(flows[0]>0);assertEquals(flows[0],flows[1]+flows[2],1e-9);assertEquals(flows[1],flows[2],1e-9);
        assertEquals(result.states().get(1).pressure(),result.states().get(2).pressure(),1e-4);
    }
    @Test void bulkThreePhaseCrudeTransfersAllComponentsAndIncludesElevationEnergy() {
        String id="createcheme:tjl20_methane";double[] composition=Arrays.copyOf(V3PengRobinsonThermo.fromRegisteredPackage(id).crudeFeed("createcheme:tia_juana_light_methane").moleFractions(),com.wormzjl.createcheme.science.material.MaterialTestBasis.CRUDE+1);composition[com.wormzjl.createcheme.science.material.MaterialTestBasis.CRUDE]=.2;
        var reservoirs=new ArrayList<PassiveNetwork.Reservoir>();
        for(double pressure:new double[]{200000,150000}) {
            double[] n=composition.clone();var unit=model.flashTP(350,pressure,n,()->{});for(int i=0;i<n.length;i++)n[i]/=unit.volume();
            reservoirs.add(new PassiveNetwork.Reservoir(reservoirs.size(),reservoirs.size()*2,model.flashTP(350,pressure,n,()->{})));
        }
        var graph=new PassiveNetwork(reservoirs,List.of(new PassiveNetwork.Pipe(4,0,1,new PipeResistance.Geometry(10,.05,.000045,0))));
        var result=new PassiveStepSolver(model).solve(graph,.01,()->{});assertTrue(result.massFlows()[0]>0);
        var oldReceiver=PhaseLayout.totalAmounts(reservoirs.get(1).state());var newReceiver=PhaseLayout.totalAmounts(result.states().get(1));
        for(int i=0;i<oldReceiver.length;i++)assertTrue(newReceiver[i]>oldReceiver[i],"component "+i);
        for(var state:result.states()){assertTrue(state.liquidVolume()>0);assertTrue(state.waterVolume()>0);assertTrue(state.vaporVolume()>0);}
    }
    @Test void aPreviouslyAbsentGasComponentCanEnterAReceivingReservoir() {
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,fill(200000,0)),new PassiveNetwork.Reservoir(2,0,fill(101325,1))),
                List.of(new PassiveNetwork.Pipe(3,0,1,new PipeResistance.Geometry(10,.05,.000045,0))));
        var result=new PassiveStepSolver(model).solve(graph,.01,()->{});
        assertTrue(result.states().get(1).vapor()[0]>0);assertTrue(result.massFlows()[0]>0);
        assertEquals(0,result.states().get(0).vapor()[1],1e-10);
    }
}
