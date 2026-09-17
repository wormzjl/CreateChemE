package com.wormzjl.createcheme.science.fluid.network;

import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.createcheme.science.fluid.solver.SparseNewton;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;

class ConservativeTransportTest {
    @Test void simultaneousBranchTransfersRemainPositiveAndCannotInventAnAbsentComponent() {
        var model=new FluidThermodynamics(MaterialCatalog.bundled(),"createcheme:tjl20_methane",1e-9);
        var nodes=new ArrayList<PassiveNetwork.Reservoir>();
        for(int component:new int[]{0,1,1}) {
            double[] n=new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK];n[component]=1/model.hydrocarbon.molecularWeight(component);
            nodes.add(new PassiveNetwork.Reservoir(nodes.size()+1,0,model.flashTP(350,101325,n,()->{})));
        }
        var geometry=new PipeResistance.Geometry(10,.05,.000045,0);
        var graph=new PassiveNetwork(nodes,List.of(new PassiveNetwork.Pipe(4,0,1,geometry),new PassiveNetwork.Pipe(5,0,2,geometry)));
        var states=nodes.stream().map(PassiveNetwork.Reservoir::state).toList();
        var projection=ConservativeTransport.reconstruct(graph,states,new double[]{.1,.2},new double[]{0,0},1,model,()->{});
        assertEquals(.7,projection.inventories().get(0).moles()[0]*model.hydrocarbon.molecularWeight(0),1e-12);
        assertEquals(.1,projection.inventories().get(1).moles()[0]*model.hydrocarbon.molecularWeight(0),1e-12);
        assertEquals(.2,projection.inventories().get(2).moles()[0]*model.hydrocarbon.molecularWeight(0),1e-12);
        for(var inventory:projection.inventories()){assertEquals(0,inventory.moles()[2]);for(double n:inventory.moles())assertTrue(n>=0);}
        assertThrows(SparseNewton.Nonconvergence.class,()->ConservativeTransport.reconstruct(graph,states,new double[]{.6,.6},new double[]{0,0},1,model,()->{}));
    }
    @Test void repeatedStepsKeepTheDeclaredVolumeBitForBitWhileCheckingTheDerivedVolume() {
        var model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
        var a=model.initialNitrogenCharge(1,320,110000,()->{});var b=model.initialNitrogenCharge(1,320,101325,()->{});
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,a,PassiveNetwork.NodeKind.RESERVOIR,new PassiveNetwork.Inventory(1,PhaseLayout.totalAmounts(a),a.internalEnergy())),
                new PassiveNetwork.Reservoir(2,0,b,PassiveNetwork.NodeKind.RESERVOIR,new PassiveNetwork.Inventory(1,PhaseLayout.totalAmounts(b),b.internalEnergy()))),
                List.of(new PassiveNetwork.Pipe(3,0,1,new PipeResistance.Geometry(100,.05,.000045,0))));
        var stepper=new PassiveStepSolver(model);
        for(int step=0;step<20;step++) {
            var result=stepper.solve(graph,.01,()->{});graph=PassiveIntervalSolver.replace(graph,result);
            for(var node:graph.reservoirs()){assertEquals(1,node.inventory().volume());assertEquals(1,node.state().volume(),1e-8);assertArrayEquals(node.inventory().moles(),PhaseLayout.totalAmounts(node.state()),1e-12);}
        }
    }
}
