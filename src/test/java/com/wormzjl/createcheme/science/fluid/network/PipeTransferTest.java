package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PipeTransferTest {
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane",1e-9);
    @Test void integratedThreePhaseHistoryMatchesTheConservativeTankInventoryChange() {
        var n=Arrays.copyOf(V3PengRobinsonThermo.fromRegisteredPackage("createcheme:tjl20_methane").crudeFeed("createcheme:tia_juana_light_methane").moleFractions(),22);n[20]=.1;n[21]=.2;
        var nodes=new ArrayList<PassiveNetwork.Reservoir>();
        for(int i=0;i<2;i++) {
            var amounts=n.clone();double pressure=150000-1000*i;
            var unit=model.flashTP(350,pressure,amounts,()->{});for(int c=0;c<n.length;c++)amounts[c]/=unit.volume();
            var state=model.flashTP(350,pressure,amounts,()->{});nodes.add(new PassiveNetwork.Reservoir(i+1,0,state));
        }
        var graph=new PassiveNetwork(nodes,List.of(new PassiveNetwork.Pipe(8,0,1,new PipeResistance.Geometry(100,.03,.000045,0))));
        var result=new PassiveIntervalSolver(model).solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});
        assertEquals(1,result.pipeTransfers().size());var transfer=result.pipeTransfers().getFirst();
        var forward=transfer.forward().componentMoles();var reverse=transfer.reverse().componentMoles();
        var before=nodes.get(1).inventory().moles();var after=result.graph().reservoirs().get(1).inventory().moles();
        for(int c=0;c<n.length;c++)assertEquals(after[c]-before[c],forward[c]-reverse[c],1e-10+1e-8*Math.max(before[c],after[c]));
        assertEquals(5*result.averageMassFlows()[0],transfer.forward().massKg()-transfer.reverse().massKg(),1e-12);
        for(double volume:transfer.forward().phaseVolumes())assertTrue(volume>0);
        var copy=transfer.forward().phaseMoles();copy[0][0]=-100;assertTrue(transfer.forward().phaseMoles()[0][0]>=0);
    }
    @Test void reversalKeepsBothDonorCompositionsAndBoundsTheHistorySize() {
        var a=model.initialNitrogenCharge(1,350,150000,()->{});double[] water=new double[22];water[21]=1;
        var b=model.flashTP(350,150000,water,()->{});
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,a),new PassiveNetwork.Reservoir(2,0,b)),List.of(new PassiveNetwork.Pipe(9,0,1,new PipeResistance.Geometry(1,.05,0,0))));
        var accumulator=new PipeTransfer.Accumulator();
        for(int i=0;i<100;i++)accumulator.add(PipeTransfer.sample(graph,List.of(a,b),new double[]{i%2==0?.1:-.2},1),1);
        assertEquals(1,accumulator.snapshot().size());var transfer=accumulator.snapshot().getFirst();
        assertEquals(5,transfer.forward().massKg(),1e-12);assertEquals(10,transfer.reverse().massKg(),1e-12);
        assertTrue(transfer.forward().componentMoles()[20]>0);assertEquals(0,transfer.forward().componentMoles()[21]);
        assertTrue(transfer.reverse().componentMoles()[21]>0);assertEquals(0,transfer.reverse().componentMoles()[20]);
    }
}
