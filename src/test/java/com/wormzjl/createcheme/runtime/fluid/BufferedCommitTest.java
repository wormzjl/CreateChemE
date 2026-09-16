package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.runtime.*;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.state.EnergyReference;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BufferedCommitTest {
    @Test void hydraulicAndPendingMaterialPublishTogetherAndAbortedStagingConsumesNeither() {
        var model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane",1e-9);
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,model.initialNitrogenCharge(1,298.15,101325,()->{}))),List.of());
        double[] n=new double[22],w=new double[22];n[21]=100/model.waterMolecularWeight;for(int c=0;c<22;c++)w[c]=c==21?model.waterMolecularWeight:model.hydrocarbon.molecularWeight(c);
        var basis=new ArrayList<>(model.hydrocarbon.components());basis.add("Water");
        var material=new MaterialParcel(n,w,model.flashTP(298.15,101325,n,()->{}).enthalpy(),EnergyReference.sensible(basis));
        var receiver=UUID.randomUUID();var transfer=UUID.randomUUID();
        double initialKg=graph.reservoirs().getFirst().state().mass();
        var ledger=new BufferedTransfers(new BufferedTransfers.Snapshot(0,Map.of(receiver,new BufferedTransfers.Buffer(receiver,200,initialKg,Map.of())),Map.of()));
        ledger.commit(ledger.reserve(List.of(new PendingTransfers.Pending(transfer,UUID.randomUUID(),receiver,0,0,material))));
        var attempted=new ArrayList<IslandCoordinator.Attempt>();var commands=new ArrayList<ProcessSolveServices.FluidIslandCommand>();
        int[] active={0};boolean[] abort={false};long[] sequence={0};
        var dispatch=new IslandCoordinator.Dispatcher() {
            public int availableWorkers(){return 1-active[0];}public long nextRequestId(){return ++sequence[0];}
            public boolean submit(IslandCoordinator.Attempt a,ProcessSolveServices.FluidIslandCommand c){attempted.add(a);commands.add(c);active[0]++;return true;}
            public void cancel(long request){}
        };
        var coordinator=new IslandCoordinator(dispatch,changed->{
            double water=changed.getFirst().graph().reservoirs().getFirst().inventory().moles()[21]*model.waterMolecularWeight;
            assertEquals(water,ledger.snapshot().buffers().get(receiver).occupiedKg()-initialKg,1e-8);
        },()->0,new IslandCoordinator.Settings(2_000_000_000L,1_500_000_000L,64,false),(attempt,result)->{
            var delivered=result.materialTransfers().orElseThrow().delivered().get(transfer);
            var record=ledger.snapshot().pending().get(transfer);
            var prepared=ledger.deliver(attempt.slice().startTick(),List.of(new BufferedTransfers.Feasible(transfer,record.revision(),delivered.massKg())));
            return abort[0]?Optional.empty():Optional.of(()->ledger.commit(prepared));
        });
        coordinator.register(new IslandCoordinator.Snapshot(1,0,graph,new IslandClock.Snapshot(200,0,0,100),FallbackAllowance.NONE,Optional.empty(),Optional.empty(),"READY"),model);coordinator.pump();
        var token=new BoundedCpuSolveService.CancellationToken() {
            public long deadlineNanos(){return Long.MAX_VALUE;}public boolean isDeadlineExceeded(){return false;}public boolean isCancellationRequested(){return false;}public void throwIfCancellationRequested(){}
        };
        for(int i=0;i<2;i++) {
            abort[0]=i==1;var attempt=attempted.get(i);var command=commands.get(i);var pending=ledger.snapshot().pending().get(transfer);
            var buffered=new ProcessSolveServices.BufferedIslandCommand(model,command.snapshot(),attempt.slice().startTick(),100,List.of(new ModuleTransferPlanner.Input(transfer,1,0,pending.remaining(),20)),List.of(),2_000_000_000L,FallbackAllowance.NONE,Optional.empty());
            var result=(ProcessSolveServices.FluidIslandSolveResult)buffered.solve(token);assertTrue(result.candidate().isPresent(),result.detail());
            active[0]--;coordinator.completed(attempt,Optional.of(result));coordinator.pump();
        }
        assertEquals(100,coordinator.snapshot(1).clock().committedTick());assertEquals(80,ledger.snapshot().pending().get(transfer).remaining().massKg(),1e-9);
        assertEquals(20,coordinator.snapshot(1).graph().reservoirs().getFirst().inventory().moles()[21]*model.waterMolecularWeight,1e-8);
    }
}
