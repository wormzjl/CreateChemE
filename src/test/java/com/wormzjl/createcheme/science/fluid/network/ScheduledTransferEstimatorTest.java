package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ScheduledTransferEstimatorTest {
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
    @Test void changingGasCompositionAndWithdrawnEnergyMatchTighterStepDoubling() {
        var gas=model.initialNitrogenCharge(1,320,300000,()->{});double[] methane=new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1];methane[0]=1;
        var injected=model.flashTP(320,300000,methane,()->{});
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,gas)),List.of(),List.of(new ScheduledTransfer.Injection(-1,0,methane,injected.enthalpy()),new ScheduledTransfer.Withdrawal(-2,0,.03)));
        compare(graph);
    }
    @Test void wetCrudePipeAndSimultaneousModuleBoundariesMatchTighterStepDoubling() {
        var composition=Arrays.copyOf(V3PengRobinsonThermo.fromRegisteredPackage("createcheme:tjl20_methane_nitrogen").crudeFeed("createcheme:tia_juana_light_methane").moleFractions(),com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1);composition[com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN]=.1;composition[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK]=.2;
        var nodes=new ArrayList<PassiveNetwork.Reservoir>();
        for(int i=0;i<2;i++){var n=composition.clone();var unit=model.flashTP(350,150100-i*100,n,()->{});for(int c=0;c<com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1;c++)n[c]/=unit.volume();nodes.add(new PassiveNetwork.Reservoir(i+1,0,model.flashTP(350,150100-i*100,n,()->{})));}
        double[] water=new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1];water[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK]=.01/model.waterMolecularWeight;var feed=model.flashTP(350,150100,water,()->{});
        var graph=new PassiveNetwork(nodes,List.of(new PassiveNetwork.Pipe(9,0,1,new PipeResistance.Geometry(20,.05,.000045,0))),List.of(new ScheduledTransfer.Injection(-1,0,water,feed.enthalpy()),new ScheduledTransfer.Withdrawal(-2,1,.012)));
        compare(graph);
    }
    private void compare(PassiveNetwork graph) {
        var actual=new PassiveIntervalSolver(model).solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});
        var reference=new PassiveIntervalSolver(model).solve(graph,5,new PassiveIntervalSolver.Settings(.005,.05,4096),()->{});
        for(int i=0;i<graph.reservoirs().size();i++) {
            var a=actual.graph().reservoirs().get(i).state();var r=reference.graph().reservoirs().get(i).state();
            assertEquals(r.pressure(),a.pressure(),.001*r.pressure());assertEquals(r.temperature(),a.temperature(),.05);assertEquals(r.mass(),a.mass(),.001*r.mass());
        }
        var a=totals(actual.boundaries());var r=totals(reference.boundaries());assertEquals(r.keySet(),a.keySet());
        // A boundary's components within 1 % of its total amount (TR-BDF2: 0.1 % of each component). Backward Euler withdraws
        // at the step's end composition, and the state cap does not see a composition integral: on the single-tank fixture
        // one 5 s step withdraws 0.160 mol of the injected methane against 0.120 refined, 0.74 % of the 5.41 mol withdrawn
        // (WP1 of the mixed-gas junction batch). Energy stays at 0.1 %.
        for(long id:r.keySet()){double total=0;for(int c=0;c<22;c++)total+=Math.abs(r.get(id)[c]);
            for(int c=0;c<23;c++)assertEquals(r.get(id)[c],a.get(id)[c],c==22?1e-4+.001*Math.abs(r.get(id)[c]):1e-10+.01*total,"Boundary "+id+" component/energy "+c);}
        for(int i=0;i<actual.averageMassFlows().length;i++)assertEquals(reference.averageMassFlows()[i],actual.averageMassFlows()[i],1e-9+.002*Math.abs(reference.averageMassFlows()[i]));
        assertTrue(actual.acceptedSubsteps()<reference.acceptedSubsteps(),"The controller did not reduce accepted work in this smooth fixture");
    }
    private static Map<Long,double[]> totals(List<ConservativeTransport.BoundaryTransfer> transfers) {
        var result=new HashMap<Long,double[]>();for(var transfer:transfers){var sum=result.computeIfAbsent(transfer.nodeId(),id->new double[23]);var n=transfer.moles();for(int c=0;c<com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1;c++)sum[c]+=n[c];sum[22]+=transfer.totalEnergyJoule();}return result;
    }
}
