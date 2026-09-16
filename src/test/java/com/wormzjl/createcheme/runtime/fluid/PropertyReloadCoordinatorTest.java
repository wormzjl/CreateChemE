package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.runtime.ProcessSolveServices;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PropertyReloadCoordinatorTest {
    @Test void reloadDiscardsReadyAndLateProposalsPreservesOwnershipAndResumesOnlyWithFullScience() {
        var model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),FluidPresetCatalog.NETWORK_PACKAGE,1e-9);
        var attempts=new LinkedHashMap<Long,IslandCoordinator.Attempt>();var commands=new HashMap<Long,ProcessSolveServices.FluidIslandCommand>();var cancelled=new HashSet<Long>();
        var dispatcher=new IslandCoordinator.Dispatcher() {
            long sequence;
            public int availableWorkers(){return 2-attempts.size();}
            public long nextRequestId(){return ++sequence;}
            public boolean submit(IslandCoordinator.Attempt attempt,ProcessSolveServices.FluidIslandCommand command){attempts.put(attempt.slice().requestId(),attempt);commands.put(attempt.slice().requestId(),command);return true;}
            public void cancel(long id){cancelled.add(id);}
        };
        var coordinator=new IslandCoordinator(dispatcher,ignored->{},()->0,new IslandCoordinator.Settings(1000,750,64,false));
        var originals=new HashMap<Long,IslandCoordinator.Snapshot>();var fence=UUID.randomUUID();
        for(long id=1;id<=2;id++) {
            var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(id,0,model.initialNitrogenCharge(1,350,150000,()->{}))),List.of());
            var full=new PassiveIntervalSolver(model).solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});
            var snapshot=new IslandCoordinator.Snapshot(id,7,graph,new IslandClock.Snapshot(300,100,0,100),new FallbackAllowance(2,100,100),Optional.of(ApproximationAnchor.fromFull(model,full)),Optional.of(full),"READY",Map.of(fence,300L));
            originals.put(id,snapshot);coordinator.register(snapshot,model);
        }
        coordinator.pump();var first=attempts.remove(1L);var second=attempts.get(2L);
        var oldResult=originals.get(1L).lastResult().orElseThrow();
        coordinator.completed(first,Optional.of(new ProcessSolveServices.FluidIslandSolveResult(Optional.of(oldResult),"FULL",10,FallbackAllowance.NONE,Optional.of(ApproximationAnchor.fromFull(model,oldResult)))));
        coordinator.suspendForPropertyChange(FluidPropertyReloadGuard.HOLD);
        assertTrue(cancelled.contains(2L));assertEquals(1,coordinator.pendingCount());
        for(int i=0;i<10;i++)coordinator.tick();
        for(long id=1;id<=2;id++) {
            var saved=coordinator.snapshot(id);var original=originals.get(id);
            assertEquals(original.graph(),saved.graph());assertEquals(100,saved.clock().committedTick());assertEquals(310,saved.clock().onlineTick());
            assertEquals(original.allowance(),saved.allowance());assertEquals(original.fences(),saved.fences());assertEquals(8,saved.revision());
            assertNotEquals(ApproximationAnchor.revision(model),saved.anchor().orElseThrow().propertyRevision());
            assertTrue(saved.status().startsWith("HELD: property data"));
        }
        coordinator.suspendForPropertyChange(FluidPropertyReloadGuard.HOLD);assertEquals(8,coordinator.snapshot(1).revision());
        attempts.remove(2L);coordinator.completed(second,Optional.of(new ProcessSolveServices.FluidIslandSolveResult(Optional.of(oldResult),"FULL",10,FallbackAllowance.NONE,Optional.of(ApproximationAnchor.fromFull(model,oldResult)))));
        assertEquals(0,coordinator.pendingCount());coordinator.pump();assertTrue(attempts.isEmpty());
        var saved=coordinator.snapshot(1);var checkpoint=new FluidCheckpointCodec.Checkpoint(List.of(new FluidCheckpointCodec.IslandEntry("minecraft:overworld",FluidPresetCatalog.NETWORK_PACKAGE,1e-9,saved)),new BufferedTransfers.Snapshot(0,Map.of(),Map.of()));
        var restored=FluidCheckpointCodec.decode(FluidCheckpointCodec.encode(checkpoint,key->model),key->model).islands().getFirst().snapshot();
        assertEquals(saved.allowance(),restored.allowance());assertEquals(saved.clock(),restored.clock());assertEquals(saved.anchor().orElseThrow().propertyRevision(),restored.anchor().orElseThrow().propertyRevision());
        coordinator.resumeQualifiedProperties();coordinator.pump();assertEquals(2,attempts.size());
        for(var command:commands.values())if(command.fallback().enabled())assertEquals(originals.get(1L).allowance(),command.fallback().allowance());
        for(var attempt:List.copyOf(attempts.values())) {
            var command=commands.get(attempt.slice().requestId());
            assertEquals(5.0,command.durationSeconds(),"Administrative suspension must not shorten the retry span");
            assertNotEquals(ApproximationAnchor.revision(model),command.fallback().anchor().orElseThrow().propertyRevision());
            var full=new PassiveIntervalSolver(model).solve(command.snapshot(),command.durationSeconds(),command.settings(),()->{});
            attempts.remove(attempt.slice().requestId());coordinator.completed(attempt,Optional.of(new ProcessSolveServices.FluidIslandSolveResult(Optional.of(full),"FULL",10,FallbackAllowance.NONE,Optional.of(ApproximationAnchor.fromFull(model,full)))));
        }
        coordinator.pump();assertEquals(200,coordinator.snapshot(1).clock().committedTick());assertEquals(FallbackAllowance.NONE,coordinator.snapshot(1).allowance());
        coordinator.stop();
    }
}
