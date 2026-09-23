package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.runtime.ProcessSolveServices;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IslandCoordinatorTest {
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
    private final AtomicLong time=new AtomicLong();
    private final FakeDispatch dispatch=new FakeDispatch();
    private final List<List<IslandCoordinator.Snapshot>> publications=new ArrayList<>();
    private final IslandCoordinator coordinator=new IslandCoordinator(dispatch,publications::add,time::get,new IslandCoordinator.Settings(1000,750,64,false));
    private final class FakeDispatch implements IslandCoordinator.Dispatcher {
        private long sequence;
        private int capacity=2;
        private final Map<Long,IslandCoordinator.Attempt> attempts=new LinkedHashMap<>();
        private final Map<Long,ProcessSolveServices.FluidIslandCommand> commands=new HashMap<>();
        private final Set<Long> cancelled=new HashSet<>();
        public int availableWorkers(){return capacity-attempts.size();}
        public long nextRequestId(){return ++sequence;}
        public boolean submit(IslandCoordinator.Attempt attempt,ProcessSolveServices.FluidIslandCommand command) {
            attempts.put(attempt.slice().requestId(),attempt);commands.put(attempt.slice().requestId(),command);return true;
        }
        public void cancel(long request){cancelled.add(request);}
        private IslandCoordinator.Attempt finish(long request) {
            var attempt=attempts.remove(request);var command=commands.remove(request);
            var result=new PassiveIntervalSolver(model).solve(command.snapshot(),command.durationSeconds(),command.settings(),()->{});
            coordinator.completed(attempt,Optional.of(new ProcessSolveServices.FluidIslandSolveResult(Optional.of(result),"FULL",10,FallbackAllowance.NONE,Optional.of(ApproximationAnchor.fromFull(model,result)))));
            return attempt;
        }
    }
    private void register(long id,long online,long committed) {
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(id,0,model.initialNitrogenCharge(1,298.15,101325,()->{}))),List.of());
        coordinator.register(new IslandCoordinator.Snapshot(id,0,graph,new IslandClock.Snapshot(online,committed,0,100),FallbackAllowance.NONE,Optional.empty(),Optional.empty(),"READY"),model);
    }
    @Test void nonblockingBarrierCommitsACompleteRoundAndThenCatchesUpFairly() {
        register(1,900,0);register(2,900,0);coordinator.pump();
        dispatch.finish(1);coordinator.pump();assertEquals(0,coordinator.snapshot(1).clock().committedTick());assertTrue(publications.isEmpty());
        dispatch.finish(2);coordinator.pump();assertEquals(100,coordinator.snapshot(1).clock().committedTick());assertEquals(2,publications.getFirst().size());
        register(3,900,0);
        dispatch.finish(3);dispatch.finish(4);coordinator.pump();
        assertTrue(dispatch.attempts.values().stream().anyMatch(a->a.islandId()==3));
        while(coordinator.snapshots().stream().anyMatch(s->s.clock().committedTick()<900)) {
            var ids=List.copyOf(dispatch.attempts.keySet());assertFalse(ids.isEmpty());
            for(long request:ids)dispatch.finish(request);coordinator.pump();
        }
        assertEquals(0,coordinator.pendingCount());assertTrue(dispatch.attempts.isEmpty());
    }
    @Test void deadlineKeepsDebtAndDoesNotReuseACancelledButStillRunningOwner() {
        register(1,100,0);register(2,100,0);coordinator.pump();dispatch.finish(1);
        time.set(1000);coordinator.pump();
        assertEquals(100,coordinator.snapshot(1).clock().committedTick());assertEquals(0,coordinator.snapshot(2).clock().committedTick());
        assertTrue(dispatch.cancelled.contains(2L));assertEquals(1,coordinator.pendingCount());
        var late=dispatch.finish(2);coordinator.pump();assertEquals(0,coordinator.snapshot(2).clock().committedTick());assertEquals(0,coordinator.pendingCount());
        coordinator.completed(late,Optional.empty());assertEquals(0,coordinator.snapshot(2).clock().committedTick());
    }
    @Test void anIndependentGroupUsesFreeWorkersWithoutBreakingTheEarlierBarrierOrDeadline() {
        register(1,100,0);register(2,100,0);register(3,100,0);coordinator.pump();
        time.set(400);dispatch.finish(1);coordinator.pump();
        assertEquals(0,coordinator.snapshot(1).clock().committedTick());
        assertEquals(3,dispatch.attempts.get(3L).islandId());
        time.set(1000);coordinator.pump();
        assertEquals(100,coordinator.snapshot(1).clock().committedTick());
        assertEquals(0,coordinator.snapshot(2).clock().committedTick());assertTrue(dispatch.cancelled.contains(2L));
        assertFalse(dispatch.cancelled.contains(3L),"Later group must retain its own deadline");
        time.set(1100);dispatch.finish(3);coordinator.pump();assertEquals(100,coordinator.snapshot(3).clock().committedTick());
        dispatch.finish(2);coordinator.pump();assertEquals(0,coordinator.snapshot(2).clock().committedTick());assertEquals(0,coordinator.pendingCount());
    }
    @Test void laterIndependentGroupMayPublishWhileEarlierGroupStillComputes() {
        register(1,100,0);register(2,100,0);register(3,100,0);coordinator.pump();
        dispatch.finish(1);coordinator.pump();dispatch.finish(3);coordinator.pump();
        assertEquals(100,coordinator.snapshot(3).clock().committedTick());
        assertEquals(0,coordinator.snapshot(1).clock().committedTick());assertEquals(0,coordinator.snapshot(2).clock().committedTick());
        coordinator.stop();assertEquals(0,coordinator.snapshot(1).clock().committedTick());
        dispatch.finish(2);coordinator.pump();assertEquals(0,coordinator.pendingCount());
    }
    @Test void aDeadlineRetriesAShorterWholeIntervalThenGrowsWithoutDiscardingDebt() {
        register(1,100,0);coordinator.pump();var first=dispatch.attempts.remove(1L);dispatch.commands.remove(1L);
        coordinator.completed(first,Optional.empty());coordinator.pump();
        assertEquals(0,coordinator.snapshot(1).clock().committedTick());
        for(int tick=0;tick<100;tick++)coordinator.tick();
        var retry=dispatch.attempts.get(2L);assertEquals(0,retry.slice().startTick());assertEquals(50,retry.slice().endTick());
        dispatch.finish(2);coordinator.pump();assertEquals(50,coordinator.snapshot(1).clock().committedTick());
        var grown=dispatch.attempts.get(3L);assertEquals(50,grown.slice().startTick());assertEquals(150,grown.slice().endTick());
        assertEquals(100,coordinator.snapshot(1).clock().cadenceTicks());assertEquals(FallbackAllowance.NONE,coordinator.snapshot(1).allowance());
    }
    @Test void fencesAlignUnequalClocksWithoutApplyingAnEventRetroactively() {
        register(1,200,100);register(2,200,0);var event=UUID.randomUUID();coordinator.fence(event,150,List.of(1L,2L));
        coordinator.pump();dispatch.finish(1);dispatch.finish(2);coordinator.pump();
        assertEquals(150,coordinator.snapshot(1).clock().committedTick());assertEquals(100,coordinator.snapshot(2).clock().committedTick());assertFalse(coordinator.aligned(event,List.of(1L,2L)));
        assertThrows(IllegalStateException.class,()->coordinator.releaseFence(event,List.of(1L,2L)));
        dispatch.finish(3);coordinator.pump();assertTrue(coordinator.aligned(event,List.of(1L,2L)));assertTrue(dispatch.attempts.isEmpty());
        coordinator.releaseFence(event,List.of(1L,2L));assertEquals(150,coordinator.snapshot(1).clock().committedTick());
    }
    @Test void staleStampAndStopCannotPublishAnUncommittedCandidate() {
        register(1,100,0);coordinator.pump();var actual=dispatch.attempts.get(1L);
        coordinator.completed(new IslandCoordinator.Attempt(1,1,actual.slice()),Optional.empty());assertEquals(1,coordinator.pendingCount());
        dispatch.finish(1);coordinator.stop();assertEquals(0,coordinator.snapshot(1).clock().committedTick());assertEquals(0,coordinator.pendingCount());
        coordinator.pump();assertTrue(dispatch.attempts.isEmpty());
    }
    @Test void splitAndMergeAtAnAlignedFencePreserveEveryInventoryAndOnlineDebt() {
        register(1,200,150);register(2,200,150);var merge=UUID.randomUUID();coordinator.fence(merge,150,List.of(1L,2L));
        var first=coordinator.snapshot(1).graph().reservoirs().getFirst();var second=coordinator.snapshot(2).graph().reservoirs().getFirst();
        var combined=new PassiveNetwork(List.of(first,second),List.of(new PassiveNetwork.Pipe(9,0,1,new PipeResistance.Geometry(1,.05,.000045,0))));
        coordinator.repartition(merge,Set.of(1L,2L),List.of(new IslandCoordinator.Replacement(3,combined)));
        assertEquals(1,coordinator.snapshots().size());assertEquals(150,coordinator.snapshot(3).clock().committedTick());assertEquals(200,coordinator.snapshot(3).clock().onlineTick());
        var split=UUID.randomUUID();coordinator.fence(split,150,List.of(3L));
        assertThrows(IllegalStateException.class,()->coordinator.repartition(split,Set.of(3L),List.of(new IslandCoordinator.Replacement(4,new PassiveNetwork(List.of(first),List.of())))));
        assertEquals(1,coordinator.snapshots().size());assertEquals(combined,coordinator.snapshot(3).graph());
        coordinator.repartition(split,Set.of(3L),List.of(new IslandCoordinator.Replacement(4,new PassiveNetwork(List.of(first),List.of())),new IslandCoordinator.Replacement(5,new PassiveNetwork(List.of(second),List.of()))));
        assertEquals(first.inventory(),coordinator.snapshot(4).graph().reservoirs().getFirst().inventory());
        assertEquals(second.inventory(),coordinator.snapshot(5).graph().reservoirs().getFirst().inventory());
        assertEquals(2,coordinator.snapshot(4).revision());
    }

    // ---- scheduling duties driven by their own deadlines, with no pump from outside ----

    @Test void aRoundTimeoutClosesTheRoundFromItsDeadlineAlone() {
        register(1,100,0);register(2,100,0);coordinator.pump();assertEquals(2,dispatch.attempts.size());
        time.set(500);coordinator.tick();
        assertTrue(dispatch.cancelled.isEmpty(),"half the budget: the first check re-arms instead of closing");
        coordinator.tick();assertTrue(dispatch.cancelled.isEmpty());
        time.set(1000);coordinator.tick();
        assertEquals(Set.of(1L,2L),dispatch.cancelled,"the expired round closed on its re-armed deadline");
        for(long id:List.of(1L,2L)){assertEquals(0,coordinator.snapshot(id).clock().committedTick());assertTrue(coordinator.snapshot(id).status().startsWith("HELD"));}
        assertEquals(2,coordinator.pendingCount(),"cancelled jobs keep their owners until the terminal drains");
    }
    /** A lagging server: every tick takes 100 ms of wall time, so the 2 s budget runs out after 20 ticks while
     * the nominal-tick deadline would not look until tick 40. Nothing else pumps; the round must still close
     * within one tick of its wall deadline. */
    @Test void anExpiredRoundClosesWithinOneTickOfItsWallBudgetOnASlowServer() {
        var attempts=new LinkedHashMap<Long,IslandCoordinator.Attempt>();var cancelled=new ArrayList<Long>();
        var slow=new IslandCoordinator.Dispatcher() {
            long sequence;
            public int availableWorkers(){return 2-attempts.size();}
            public long nextRequestId(){return ++sequence;}
            public boolean submit(IslandCoordinator.Attempt attempt,ProcessSolveServices.FluidIslandCommand command){attempts.put(attempt.slice().requestId(),attempt);return true;}
            public void cancel(long request){cancelled.add(request);}
        };
        var lagging=new IslandCoordinator(slow,changed->{},time::get,new IslandCoordinator.Settings(2_000_000_000L,1_500_000_000L,64,false));
        for(long id=1;id<=2;id++){var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(id,0,model.initialNitrogenCharge(1,298.15,101325,()->{}))),List.of());
            lagging.register(new IslandCoordinator.Snapshot(id,0,graph,new IslandClock.Snapshot(100,0,0,100),FallbackAllowance.NONE,Optional.empty(),Optional.empty(),"READY"),model);}
        lagging.pump();assertEquals(2,attempts.size());
        int closedAt=-1;
        for(int tick=1;tick<=40&&closedAt<0;tick++) {
            time.addAndGet(100_000_000L);lagging.tick();
            if(!cancelled.isEmpty())closedAt=tick;
        }
        assertTrue(closedAt>=20&&closedAt<=21,"the expired round closed at tick "+closedAt+", its wall budget ran out at tick 20");
        assertEquals(Set.of(1L,2L),new HashSet<>(cancelled));
        for(long id=1;id<=2;id++)assertTrue(lagging.snapshot(id).status().startsWith("HELD"));
    }
    @Test void aHeldOwnerRetriesExactlyAtItsRetryDeadlineAndIsNotVisitedBefore() {
        register(1,100,0);coordinator.pump();var first=dispatch.attempts.remove(1L);dispatch.commands.remove(1L);
        coordinator.completed(first,Optional.of(new ProcessSolveServices.FluidIslandSolveResult(Optional.empty(),"HELD: test refusal",10,FallbackAllowance.NONE,Optional.empty())));
        coordinator.pump();assertEquals(200,coordinator.snapshot(1).clock().retryAtTick());
        FluidRuntimeDiagnostics.reset();FluidRuntimeDiagnostics.ENABLED=true;
        try {
            for(int tick=1;tick<100;tick++)coordinator.tick();
            assertTrue(dispatch.attempts.isEmpty());
            assertEquals(0,FluidRuntimeDiagnostics.sample().get("islandVisits"),"nothing looks at a held owner before its retry");
            assertEquals(0,FluidRuntimeDiagnostics.sample().get("readinessPumps"));
            coordinator.tick();
            assertEquals(1,FluidRuntimeDiagnostics.sample().get("deadlinesFired"));assertEquals(1,FluidRuntimeDiagnostics.sample().get("readinessPumps"));
            var retry=dispatch.attempts.values().iterator().next();assertEquals(0,retry.slice().startTick());assertEquals(100,retry.slice().endTick());
        } finally {FluidRuntimeDiagnostics.ENABLED=false;FluidRuntimeDiagnostics.reset();}
    }
    @Test void thePerTickDispatchBudgetResumesOnTheNextTickWithoutPolling() {
        var budgeted=new IslandCoordinator(dispatch,publications::add,time::get,new IslandCoordinator.Settings(1000,750,2,false));
        dispatch.capacity=10;var order=new ArrayList<Long>();
        for(long id=1;id<=5;id++){var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(id,0,model.initialNitrogenCharge(1,298.15,101325,()->{}))),List.of());
            budgeted.register(new IslandCoordinator.Snapshot(id,0,graph,new IslandClock.Snapshot(100,0,0,100),FallbackAllowance.NONE,Optional.empty(),Optional.empty(),"READY"),model);}
        Runnable completeAll=()->{
            for(long request:List.copyOf(dispatch.attempts.keySet())) {
                var attempt=dispatch.attempts.remove(request);var command=dispatch.commands.remove(request);order.add(attempt.islandId());
                var result=new PassiveIntervalSolver(model).solve(command.snapshot(),command.durationSeconds(),command.settings(),()->{});
                budgeted.completed(attempt,Optional.of(new ProcessSolveServices.FluidIslandSolveResult(Optional.of(result),"FULL",10,FallbackAllowance.NONE,Optional.of(ApproximationAnchor.fromFull(model,result)))));
            }
            budgeted.pump();
        };
        budgeted.pump();assertEquals(2,dispatch.attempts.size());
        completeAll.run();assertTrue(dispatch.attempts.isEmpty(),"the tick's dispatch budget is spent, so the completion pump dispatches nothing");
        budgeted.pumpIfUseful();assertTrue(dispatch.attempts.isEmpty());
        budgeted.tick();assertEquals(2,dispatch.attempts.size(),"the next tick resets the budget and the ready owners go out");
        completeAll.run();budgeted.tick();assertEquals(1,dispatch.attempts.size());completeAll.run();
        assertEquals(List.of(1L,2L,3L,4L,5L),order,"registration order is kept across the budget boundary");
    }
    @Test void theAllocatorShrinkIsObservedExactlyAtItsDeadlineWithNoOtherActivity() {
        long[] epoch={0};var allocator=new com.wormzjl.createcheme.runtime.WorkerAllocation.Demand(8);int[] limit={1};var observed=new ArrayList<long[]>();
        var attempts=new LinkedHashMap<Long,IslandCoordinator.Attempt>();var commands=new HashMap<Long,ProcessSolveServices.FluidIslandCommand>();
        var elastic=new IslandCoordinator.Dispatcher() {
            long sequence;
            @Override public void demand(int eligible){limit[0]=allocator.observe(eligible+attempts.size(),epoch[0]);observed.add(new long[]{epoch[0],limit[0]});}
            @Override public long demandShrinkDelay(){long due=allocator.shrinkTick();return due<0?-1:due-epoch[0];}
            public int availableWorkers(){return limit[0]-attempts.size();}
            public long nextRequestId(){return ++sequence;}
            public boolean submit(IslandCoordinator.Attempt attempt,ProcessSolveServices.FluidIslandCommand command){attempts.put(attempt.slice().requestId(),attempt);commands.put(attempt.slice().requestId(),command);return true;}
            public void cancel(long request){}
        };
        // A shared epoch: the owner advances it before each tick, as the world does.
        var shared=new IslandCoordinator(elastic,changed->{},time::get,new IslandCoordinator.Settings(1000,750,64,false),IslandCoordinator.CommitHook.NO_MATERIAL,()->epoch[0]);
        for(long id=1;id<=3;id++){var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(id,0,model.initialNitrogenCharge(1,298.15,101325,()->{}))),List.of());
            shared.register(new IslandCoordinator.Snapshot(id,0,graph,new IslandClock.Snapshot(100,0,0,100),FallbackAllowance.NONE,Optional.empty(),Optional.empty(),"READY"),model);}
        shared.pump();assertEquals(3,limit[0]);assertEquals(3,attempts.size());
        for(long request:List.copyOf(attempts.keySet())) {
            var attempt=attempts.remove(request);var command=commands.remove(request);var result=new PassiveIntervalSolver(model).solve(command.snapshot(),command.durationSeconds(),command.settings(),()->{});
            shared.completed(attempt,Optional.of(new ProcessSolveServices.FluidIslandSolveResult(Optional.of(result),"FULL",10,FallbackAllowance.NONE,Optional.of(ApproximationAnchor.fromFull(model,result)))));
        }
        // Park all three owners on an event so nothing but the allocator deadline can wake the coordinator.
        shared.fence(UUID.randomUUID(),100,List.of(1L,2L,3L));shared.pump();
        assertEquals(3,limit[0]);assertEquals(200,allocator.shrinkTick(),"lower demand observed at epoch 0");
        int before=observed.size();
        for(epoch[0]=1;epoch[0]<200;epoch[0]++)shared.tick();
        assertEquals(before,observed.size(),"no demand observation before the shrink deadline");assertEquals(3,limit[0]);
        shared.tick();assertEquals(200,epoch[0]);
        assertEquals(before+1,observed.size());assertEquals(1,limit[0],"the shrink applied exactly at its deadline");
        assertEquals(-1,allocator.shrinkTick());assertEquals(Long.MAX_VALUE,shared.nextDue());
    }
}
