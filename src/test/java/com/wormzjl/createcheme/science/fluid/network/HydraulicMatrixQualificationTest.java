package com.wormzjl.createcheme.science.fluid.network;

import com.google.gson.GsonBuilder;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** P02/P07/P09 and seeded graph-order coverage. Fixed steps isolate ordering from adaptation. */
class HydraulicMatrixQualificationTest {
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
    private FluidThermodynamics.State state(boolean water,double pressure) {
        double[] n=new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1];n[water?com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK:com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN]=1;var unit=model.flashTP(350,pressure,n,()->{});n[water?com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK:com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN]/=unit.volume();
        return model.flashTP(350,pressure,n,()->{});
    }
    @Test void equalStateGasAndLiquidNetworksRemainAtRest() {
        for(boolean water:new boolean[]{false,true}) {
            var a=state(water,150000);var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,a),new PassiveNetwork.Reservoir(2,0,a)),List.of(new PassiveNetwork.Pipe(3,0,1,new PipeResistance.Geometry(10,.02,.000045,0))));
            var result=new PassiveIntervalSolver(model).solve(graph,20,PassiveIntervalSolver.Settings.defaults(),()->{});
            assertEquals(0,result.averageMassFlows()[0],1e-10);for(var node:result.graph().reservoirs()) {
                assertArrayEquals(graph.reservoirs().getFirst().inventory().moles(),node.inventory().moles(),1e-10);
                assertEquals(a.internalEnergy(),node.inventory().internalEnergy(),1e-4);assertEquals(150000,node.state().pressure(),1);
            }
        }
    }
    @Test void unequalParallelPipesMatchIndependentLaminarFlowForGasAndLiquid() {
        for(boolean water:new boolean[]{false,true}) {
            var source=state(water,150100);var sink=state(water,150000);double rho=source.mass()/source.volume();
            double mu=water?model.viscosity.waterLiquid(350):model.viscosity.vapor(350,source.vapor(),0);
            var sections=List.of(new PipeResistance.Geometry(100,.01,0,0),new PipeResistance.Geometry(200,.012,0,0));
            var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,source,PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,0,sink,PassiveNetwork.NodeKind.VOID)),List.of(new PassiveNetwork.Pipe(3,0,1,sections.get(0)),new PassiveNetwork.Pipe(4,0,1,sections.get(1))));
            var result=new PassiveStepSolver(model).solve(graph,5,()->{});
            for(int i=0;i<2;i++) {
                var p=sections.get(i);double reference=100*Math.PI*rho*Math.pow(p.diameter(),4)/(128*mu*p.length());
                assertTrue(4*reference/(Math.PI*mu*p.diameter())<2000,"Analytic fixture left laminar regime");
                assertEquals(reference,result.massFlows()[i],1e-10+1e-4*reference);
            }
            assertEquals(Math.pow(.012/.01,4)/2,result.massFlows()[1]/result.massFlows()[0],1e-4);
        }
    }
    @Test void hydrostaticRestAndReversedElevationUseTheSameGravityEnergyLedger() {
        var lower=state(true,200000);double rho=lower.mass()/lower.volume();double dz=2;
        var upper=state(true,200000-rho*PassiveStepSolver.GRAVITY*dz);
        var pipe=new PassiveNetwork.Pipe(3,0,1,new PipeResistance.Geometry(100,.01,0,0));
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,lower),new PassiveNetwork.Reservoir(2,dz,upper)),List.of(pipe));
        var rest=new PassiveStepSolver(model).solve(graph,.1,()->{});assertEquals(0,rest.massFlows()[0],1e-10);
        var reversed=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,dz,lower),new PassiveNetwork.Reservoir(2,0,upper)),List.of(pipe));
        var result=new PassiveStepSolver(model).solve(reversed,.1,()->{});assertTrue(result.massFlows()[0]>0);
        double before=0,after=0;for(int i=0;i<2;i++) {
            var node=reversed.reservoirs().get(i);before+=node.inventory().internalEnergy()+node.state().mass()*PassiveStepSolver.GRAVITY*node.elevation();
            after+=result.inventories().get(i).internalEnergy()+result.states().get(i).mass()*PassiveStepSolver.GRAVITY*node.elevation();
        }
        assertEquals(before,after,1e-4+1e-6*Math.abs(before));
    }
    @Test void recordedRandomGraphsPreserveBalancePositivityAndOrderIndependentTrajectories() throws Exception {
        var rows=new ArrayList<Map<String,Object>>();
        for(boolean water:new boolean[]{false,true})for(long seed:new long[]{2026091601L,2026091602L,2026091603L}) {
            var random=new Random(seed);var nodes=new ArrayList<PassiveNetwork.Reservoir>();var pipes=new ArrayList<PassiveNetwork.Pipe>();
            for(int i=0;i<6;i++)nodes.add(new PassiveNetwork.Reservoir(i+1,0,state(water,150000+500*random.nextDouble())));
            for(int i=1;i<6;i++)pipes.add(new PassiveNetwork.Pipe(100+i,i,random.nextInt(i),new PipeResistance.Geometry(50+150*random.nextDouble(),.008+.004*random.nextDouble(),.000045,0)));
            pipes.add(new PassiveNetwork.Pipe(200,0,5,new PipeResistance.Geometry(100,.01,.000045,1)));
            var original=new PassiveNetwork(nodes,pipes);var shuffled=new ArrayList<>(nodes);Collections.shuffle(shuffled,random);
            var index=new HashMap<Long,Integer>();for(int i=0;i<6;i++)index.put(shuffled.get(i).id(),i);
            var permutedPipes=new ArrayList<PassiveNetwork.Pipe>();for(var p:pipes)permutedPipes.add(new PassiveNetwork.Pipe(p.id(),index.get(nodes.get(p.first()).id()),index.get(nodes.get(p.second()).id()),p.sections(),p.control()));Collections.shuffle(permutedPipes,random);
            var a=new PassiveStepSolver(model).solve(original,.05,()->{});var b=new PassiveStepSolver(model).solve(new PassiveNetwork(shuffled,permutedPipes),.05,()->{});
            double before=0,after=0,beforeU=0,afterU=0,maximumPressureDifference=0;int component=water?com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK:com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN;
            for(int i=0;i<6;i++) {
                int j=index.get(nodes.get(i).id());var ia=a.inventories().get(i);var ib=b.inventories().get(j);
                assertArrayEquals(ia.moles(),ib.moles(),1e-8*Math.max(1,ia.moles()[component]));assertEquals(ia.internalEnergy(),ib.internalEnergy(),1e-4+1e-6*Math.abs(ia.internalEnergy()));
                maximumPressureDifference=Math.max(maximumPressureDifference,Math.abs(a.states().get(i).pressure()-b.states().get(j).pressure()));
                for(double n:ia.moles())assertTrue(n>=0);before+=nodes.get(i).inventory().moles()[component];after+=ia.moles()[component];beforeU+=nodes.get(i).inventory().internalEnergy();afterU+=ia.internalEnergy();
            }
            assertEquals(before,after,1e-10+1e-8*before);assertEquals(beforeU,afterU,1e-4+1e-6*Math.abs(beforeU));assertTrue(maximumPressureDifference<=1+1e-4*150000);
            rows.add(Map.of("seed",seed,"fluid",water?"water":"nitrogen","reservoirs",6,"pipes",6,"fixedStepSeconds",.05,"maximumOrderPressureDifferencePa",maximumPressureDifference,"componentBalanceMoles",after-before,"energyBalanceJoules",afterU-beforeU,"status","PASS"));
        }
        var path=Path.of("build/reports/fluid/M9-random-graphs.json");Files.createDirectories(path.getParent());Files.writeString(path,new GsonBuilder().setPrettyPrinting().create().toJson(rows));
    }
}
