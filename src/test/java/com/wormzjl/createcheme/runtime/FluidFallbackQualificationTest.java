package com.wormzjl.createcheme.runtime;

import com.google.gson.GsonBuilder;
import com.wormzjl.createcheme.runtime.fluid.*;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FluidFallbackQualificationTest {
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
    private final BoundedCpuSolveService.CancellationToken token=new BoundedCpuSolveService.CancellationToken(){
        public long deadlineNanos(){return Long.MAX_VALUE;}public boolean isDeadlineExceeded(){return false;}public boolean isCancellationRequested(){return false;}public void throwIfCancellationRequested(){}
    };
    private PassiveNetwork.Reservoir node(long id,double pressure,double[] composition,PassiveNetwork.NodeKind kind) {
        var n=composition.clone();var unit=model.flashTP(350,pressure,n,()->{});for(int c=0;c<n.length;c++)n[c]/=unit.volume();
        var state=model.flashTP(350,pressure,n,()->{});return new PassiveNetwork.Reservoir(id,0,state,kind,new PassiveNetwork.Inventory(1,n,state.internalEnergy()));
    }
    private PassiveIntervalSolver.Result warm(String name) {
        double[] n=new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1];
        if(name.equals("nitrogen"))n[com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN]=1;
        else{n=Arrays.copyOf(V3PengRobinsonThermo.fromRegisteredPackage("createcheme:tjl20_methane_nitrogen").crudeFeed("createcheme:tia_juana_light_methane").moleFractions(),com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1);n[com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN]=.1;n[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK]=.2;}
        boolean pump=name.equals("wet crude pump");
        var graph=new PassiveNetwork(List.of(node(1,pump?149000:150000,n,PassiveNetwork.NodeKind.GENERATOR),node(2,pump?150000:149000,n,PassiveNetwork.NodeKind.RESERVOIR)),List.of(
                new PassiveNetwork.Pipe(1,0,1,new PipeResistance.Geometry(100,name.equals("nitrogen")?.01:.03,.000045,0),pump?new FlowControl.Pump(.0001,500000,1):new FlowControl.Passive())));
        return new PassiveIntervalSolver(model).solve(graph,.05,PassiveIntervalSolver.Settings.defaults(),()->{});
    }
    private ProcessSolveServices.FluidIslandSolveResult approximate(PassiveNetwork graph,ApproximationAnchor anchor,FallbackAllowance allowance,int cadence,double duration) {
        var command=new ProcessSolveServices.FluidIslandCommand(model,graph,duration,PassiveIntervalSolver.Settings.defaults(),1_000_000_000,
                FluidFallbackPolicy.active(anchor,allowance,cadence,1000));var clock=new AtomicLong();
        return (ProcessSolveServices.FluidIslandSolveResult)command.solve(token,()->clock.getAndAdd(1000));
    }
    @Test void threeApproximateIntervalsMeetThePublishedTrajectoryAndThroughputBounds() throws Exception {
        var rows=new ArrayList<Map<String,Object>>();var failures=new ArrayList<String>();
        for(String name:List.of("nitrogen","wet crude","wet crude pump")) {
            var row=new LinkedHashMap<String,Object>();rows.add(row);row.put("case",name);
            try {
                var warm=warm(name);var anchor=ApproximationAnchor.fromFull(model,warm);var fullGraph=warm.graph();var approximateGraph=fullGraph;var allowance=FallbackAllowance.NONE;
                double[] fullThroughput=new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1],approximateThroughput=new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1];double maxP=0,maxT=0,maxPhase=0,maxComponent=0,actualMillis=0;
                for(int interval=0;interval<3;interval++) {
                    var reference=new PassiveIntervalSolver(model).solve(fullGraph,5,PassiveIntervalSolver.Settings.defaults(),()->{});fullGraph=reference.graph();
                    var before=approximateGraph.reservoirs().get(1).inventory();long started=System.nanoTime();
                    var outcome=approximate(approximateGraph,anchor,allowance,interval==0?100:200,5);actualMillis+=(System.nanoTime()-started)/1e6;
                    if(outcome.candidate().isEmpty())throw new IllegalStateException(outcome.detail());
                    var result=outcome.candidate().orElseThrow();assertEquals(PassiveStepSolver.Acceptance.APPROXIMATE,result.acceptance());approximateGraph=result.graph();allowance=outcome.proposedAllowance();
                    assertSame(anchor,outcome.proposedAnchor().orElseThrow());assertEquals(interval+1,allowance.acceptedIntervals());assertEquals(100,allowance.capturedCadenceTicks());assertEquals(100L*(interval+1),allowance.advancedTicks());
                    double[] incoming=new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1];double energy=0;
                    for(var transfer:reference.boundaries()){var amounts=transfer.moles();for(int c=0;c<com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1;c++)fullThroughput[c]+=amounts[c];}
                    for(var transfer:result.boundaries()){var amounts=transfer.moles();for(int c=0;c<com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1;c++){approximateThroughput[c]+=amounts[c];incoming[c]+=amounts[c];}energy+=transfer.totalEnergyJoule();}
                    var next=approximateGraph.reservoirs().get(1).inventory();var oldN=before.moles();var newN=next.moles();
                    for(int c=0;c<com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1;c++) {
                        assertEquals(oldN[c]+incoming[c],newN[c],1e-10+1e-8*Math.max(oldN[c],newN[c]));
                        double error=Math.abs(approximateThroughput[c]-fullThroughput[c]);assertTrue(error<=1e-10+.02*Math.abs(fullThroughput[c]),"component "+c);
                        maxComponent=Math.max(maxComponent,Math.max(0,error-1e-10)/Math.max(1e-10,Math.abs(fullThroughput[c])));
                    }
                    assertEquals(before.internalEnergy()+energy+result.pumpWorkJoule(),next.internalEnergy(),1e-4+1e-6*(Math.abs(before.internalEnergy())+Math.abs(energy)+Math.abs(result.pumpWorkJoule())));
                    assertEquals(before.volume(),next.volume());
                    var a=approximateGraph.reservoirs().get(1).state();var b=fullGraph.reservoirs().get(1).state();
                    maxP=Math.max(maxP,Math.abs(a.pressure()/b.pressure()-1));maxT=Math.max(maxT,Math.abs(a.temperature()-b.temperature()));
                    var av=new double[]{a.liquidVolume(),a.waterVolume(),a.vaporVolume()};var bv=new double[]{b.liquidVolume(),b.waterVolume(),b.vaporVolume()};
                    for(int phase=0;phase<3;phase++)maxPhase=Math.max(maxPhase,Math.abs(av[phase]/a.volume()-bv[phase]/b.volume()));
                    assertTrue(Math.abs(a.pressure()-b.pressure())<=1+.02*b.pressure());assertTrue(maxT<=2);assertTrue(maxPhase<=.02);
                }
                var exhausted=new ProcessSolveServices.FluidIslandCommand(model,approximateGraph,5,PassiveIntervalSolver.Settings.defaults(),1_000_000,
                        FluidFallbackPolicy.active(anchor,allowance,200,1));var clock=new AtomicLong();
                var held=(ProcessSolveServices.FluidIslandSolveResult)exhausted.solve(token,()->clock.getAndAdd(1_000_000));assertTrue(held.candidate().isEmpty());assertEquals(allowance,held.proposedAllowance());
                var recovered=(ProcessSolveServices.FluidIslandSolveResult)exhausted.solve(token,()->0);assertEquals("FULL",recovered.detail());assertEquals(FallbackAllowance.NONE,recovered.proposedAllowance());
                row.put("status","PASS");row.put("maximumPressureRelativeError",maxP);row.put("maximumTemperatureErrorKelvin",maxT);row.put("maximumPhaseVolumeFractionError",maxPhase);row.put("maximumCumulativeComponentThroughputError",maxComponent);row.put("actualApproximateWallMillisecondsForThreeIntervals",actualMillis);
            }catch(RuntimeException|AssertionError failure){row.put("status","FAIL");row.put("failure",failure.toString());failures.add(name+": "+failure);}
        }
        Files.createDirectories(Path.of("build/reports/fluid"));Files.writeString(Path.of("build/reports/fluid/M4-fallback-qualification.json"),new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("trigger","injected monotonic clock; real timings are separate measurements","intervals",3,"secondsPerInterval",5,"rows",rows)));
        assertTrue(failures.isEmpty(),String.join("\n",failures));
    }
    @Test void topologyPropertyPhaseAndTrustChangesRefuseTheOldAnchor() {
        var full=warm("nitrogen");var anchor=ApproximationAnchor.fromFull(model,full);var graph=full.graph();
        assertThrows(ApproximationRejected.class,()->anchor.guard(model,new PassiveNetwork(graph.reservoirs(),List.of())));
        assertThrows(ApproximationRejected.class,()->anchor.guard(FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1.1e-9),graph));
        var changed=new ArrayList<>(graph.reservoirs());var high=model.initialNitrogenCharge(1,350,160000,()->{});
        changed.set(1,new PassiveNetwork.Reservoir(2,0,high,PassiveNetwork.NodeKind.RESERVOIR,new PassiveNetwork.Inventory(1,com.wormzjl.createcheme.science.fluid.solver.PhaseLayout.totalAmounts(high),high.internalEnergy())));
        assertTrue(assertThrows(ApproximationRejected.class,()->anchor.guard(model,new PassiveNetwork(changed,graph.pipes()))).getMessage().contains("trust region"));
        double[] wet=new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1];wet[com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN]=1;wet[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK]=10;var unit=model.flashTP(350,149000,wet,()->{});for(int c=0;c<wet.length;c++)wet[c]/=unit.volume();var phase=model.flashTP(350,149000,wet,()->{});
        changed.set(1,new PassiveNetwork.Reservoir(2,0,phase,PassiveNetwork.NodeKind.RESERVOIR,new PassiveNetwork.Inventory(1,wet,phase.internalEnergy())));
        assertTrue(assertThrows(ApproximationRejected.class,()->anchor.guard(model,new PassiveNetwork(changed,graph.pipes()))).getMessage().contains("Phase"));
    }
    @Test void durationCapAndAnUntrustedCandidateCannotConsumeOrRenewAllowance() {
        var full=warm("nitrogen");var anchor=ApproximationAnchor.fromFull(model,full);
        var first=approximate(full.graph(),anchor,FallbackAllowance.NONE,100,5);assertTrue(first.candidate().isPresent(),first.detail());
        var second=approximate(first.candidate().orElseThrow().graph(),anchor,first.proposedAllowance(),400,10);assertTrue(second.candidate().isPresent(),second.detail());
        assertEquals(new FallbackAllowance(2,300,100),second.proposedAllowance());
        var exhausted=new ProcessSolveServices.FluidIslandCommand(model,second.candidate().orElseThrow().graph(),.05,PassiveIntervalSolver.Settings.defaults(),100,
                FluidFallbackPolicy.active(anchor,new FallbackAllowance(2,300,100),400,1));var clock=new AtomicLong();
        var held=(ProcessSolveServices.FluidIslandSolveResult)exhausted.solve(token,()->clock.getAndAdd(100));assertTrue(held.candidate().isEmpty());assertEquals(second.proposedAllowance(),held.proposedAllowance());
        var graph=full.graph();var changed=new PassiveNetwork(graph.reservoirs(),List.of(new PassiveNetwork.Pipe(99,0,1,new PipeResistance.Geometry(10,.05,.000045,0))));
        var rejected=approximate(changed,anchor,FallbackAllowance.NONE,100,5);assertTrue(rejected.candidate().isEmpty());assertEquals(FallbackAllowance.NONE,rejected.proposedAllowance());assertTrue(rejected.detail().contains("topology"));
        assertEquals(graph.reservoirs().get(1).inventory(),changed.reservoirs().get(1).inventory());
    }
    @Test void hardTimeoutInsideFallbackAndBoundaryRegimeChangesCannotPublish() {
        var full=warm("nitrogen");var anchor=ApproximationAnchor.fromFull(model,full);
        var deadline=new ProcessSolveServices.FluidIslandCommand(model,full.graph(),5,PassiveIntervalSolver.Settings.defaults(),5,
                FluidFallbackPolicy.active(anchor,FallbackAllowance.NONE,100,1));var clock=new AtomicLong();
        var held=(ProcessSolveServices.FluidIslandSolveResult)deadline.solve(token,clock::getAndIncrement);
        assertTrue(held.candidate().isEmpty());assertTrue(held.detail().contains("wall deadline"));assertEquals(FallbackAllowance.NONE,held.proposedAllowance());
        var changed=new ArrayList<>(full.graph().reservoirs());var state=model.initialNitrogenCharge(1,350,150400,()->{});
        changed.set(1,new PassiveNetwork.Reservoir(2,0,state,PassiveNetwork.NodeKind.RESERVOIR,new PassiveNetwork.Inventory(1,com.wormzjl.createcheme.science.fluid.solver.PhaseLayout.totalAmounts(state),state.internalEnergy())));
        var graph=new PassiveNetwork(changed,full.graph().pipes());var refused=approximate(graph,anchor,FallbackAllowance.NONE,100,5);
        assertTrue(refused.candidate().isEmpty());assertTrue(refused.detail().contains("regime"),refused.detail());assertEquals(FallbackAllowance.NONE,refused.proposedAllowance());
    }
    @Test void trustBoundaryPerturbationsAreCheckedOnBothSidesOfThePublishedProfile() {
        var full=warm("nitrogen");var anchor=ApproximationAnchor.fromFull(model,full);var old=full.graph().reservoirs().get(1).state();
        for(boolean temperature:new boolean[]{false,true})for(double fraction:new double[]{.9999,1.0001}) {
            double t=old.temperature()-(temperature?fraction:0),p=old.pressure()-(temperature?0:fraction*(.01*old.pressure()+1));
            var state=model.initialNitrogenCharge(1,t,p,()->{});var nodes=new ArrayList<>(full.graph().reservoirs());
            nodes.set(1,new PassiveNetwork.Reservoir(2,0,state,PassiveNetwork.NodeKind.RESERVOIR,new PassiveNetwork.Inventory(1,com.wormzjl.createcheme.science.fluid.solver.PhaseLayout.totalAmounts(state),state.internalEnergy())));
            var graph=new PassiveNetwork(nodes,full.graph().pipes());
            if(fraction>1){assertThrows(ApproximationRejected.class,()->anchor.guard(model,graph));continue;}
            assertDoesNotThrow(()->anchor.guard(model,graph));
            var outcome=approximate(graph,anchor,FallbackAllowance.NONE,100,5);assertTrue(outcome.candidate().isPresent(),outcome.detail());
            var reference=new PassiveIntervalSolver(model).solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});var result=outcome.candidate().orElseThrow();
            assertEquals(reference.averageMassFlows()[0],result.averageMassFlows()[0],.02*Math.abs(reference.averageMassFlows()[0]));
            assertEquals(reference.graph().reservoirs().get(1).state().temperature(),result.graph().reservoirs().get(1).state().temperature(),2);
        }
    }
    @Test void bulkCompositionDriftCannotHideInsideAnUnchangedGasPhase() {
        var full=warm("nitrogen");var anchor=ApproximationAnchor.fromFull(model,full);
        var old=full.graph().reservoirs().get(1).state();
        for(double methane:new double[]{.009999,.010001}) {
            double[] n=new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1];n[0]=methane;n[com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN]=1-methane;
            var unit=model.flashTP(old.temperature(),old.pressure(),n,()->{});
            for(int i=0;i<n.length;i++)n[i]/=unit.volume();
            var state=model.flashTP(old.temperature(),old.pressure(),n,()->{});
            var nodes=new ArrayList<>(full.graph().reservoirs());
            nodes.set(1,new PassiveNetwork.Reservoir(2,0,state,PassiveNetwork.NodeKind.RESERVOIR,new PassiveNetwork.Inventory(1,n,state.internalEnergy())));
            var changed=new PassiveNetwork(nodes,full.graph().pipes());
            if(methane>.01)assertThrows(ApproximationRejected.class,()->anchor.guard(model,changed));
            else assertDoesNotThrow(()->anchor.guard(model,changed));
        }
    }
}
