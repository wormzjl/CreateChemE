package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.runtime.ProcessSolveServices;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static com.wormzjl.createcheme.runtime.fluid.IslandScheduler.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

/** The deadline heap and the scheduling properties of the coordinator built on it. */
class IslandSchedulerTest {
    private static final FluidThermodynamics MODEL=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
    private static final PassiveNetwork.Reservoir TANK=new PassiveNetwork.Reservoir(1,0,MODEL.initialNitrogenCharge(1,298.15,101325,()->{}));

    @AfterEach void disableCounters(){FluidRuntimeDiagnostics.ENABLED=false;FluidRuntimeDiagnostics.reset();}
    private static void count(){FluidRuntimeDiagnostics.reset();FluidRuntimeDiagnostics.ENABLED=true;}
    private static long counted(String name){return FluidRuntimeDiagnostics.sample().get(name);}

    /** Hands out slices and, on request, completes them with a real interval solve. */
    private static final class Dispatch implements IslandCoordinator.Dispatcher {
        long sequence;int capacity=64;IslandCoordinator coordinator;
        final Map<Long,IslandCoordinator.Attempt> attempts=new LinkedHashMap<>();
        final Map<Long,ProcessSolveServices.FluidIslandCommand> commands=new HashMap<>();
        final List<IslandCoordinator.Attempt> submitted=new ArrayList<>();
        public int availableWorkers(){return capacity-attempts.size();}
        public long nextRequestId(){return ++sequence;}
        public boolean submit(IslandCoordinator.Attempt attempt,ProcessSolveServices.FluidIslandCommand command){attempts.put(attempt.slice().requestId(),attempt);commands.put(attempt.slice().requestId(),command);submitted.add(attempt);return true;}
        public void cancel(long request){}
        void finishAll() {
            for(long request:List.copyOf(attempts.keySet())) {
                var attempt=attempts.remove(request);var command=commands.remove(request);
                var result=new PassiveIntervalSolver(MODEL).solve(command.snapshot(),command.durationSeconds(),command.settings(),()->{});
                coordinator.completed(attempt,Optional.of(new ProcessSolveServices.FluidIslandSolveResult(Optional.of(result),"FULL",10,FallbackAllowance.NONE,Optional.of(ApproximationAnchor.fromFull(MODEL,result)))));
            }
            coordinator.pump();
        }
    }
    private static IslandCoordinator coordinator(Dispatch dispatch) {
        var coordinator=new IslandCoordinator(dispatch,changed->{},()->0,new IslandCoordinator.Settings(2_000_000_000L,1_500_000_000L,64,false));
        dispatch.coordinator=coordinator;return coordinator;
    }
    private static IslandCoordinator.Snapshot island(long id,long online,long committed,Map<UUID,Long> fences) {
        var node=new PassiveNetwork.Reservoir(id,0,TANK.state());
        return new IslandCoordinator.Snapshot(id,0,new PassiveNetwork(List.of(node),List.of()),new IslandClock.Snapshot(online,committed,0,100),FallbackAllowance.NONE,Optional.empty(),Optional.empty(),"READY",fences);
    }

    @Test void deadlinesComeOutByTickThenInSchedulingOrderAndNextDueIsAPeek() {
        var scheduler=new IslandScheduler();assertEquals(Long.MAX_VALUE,scheduler.nextDue());assertNull(scheduler.poll(Long.MAX_VALUE-1));
        scheduler.schedule(50,SLICE_DUE,1,1);scheduler.schedule(10,RETRY,2,1);scheduler.schedule(50,ROUND_TIMEOUT,3,0);scheduler.schedule(10,MODULE_HORIZON,4,7);
        for(int i=0;i<1000;i++)assertEquals(10,scheduler.nextDue());
        assertEquals(4,scheduler.size(),"nextDue must not pop");assertNull(scheduler.poll(9));
        assertEquals(2,scheduler.poll(10).id());assertEquals(4,scheduler.poll(10).id());assertNull(scheduler.poll(10));
        assertEquals(50,scheduler.nextDue());var tied=scheduler.poll(60);assertEquals(1,tied.id());assertEquals(SLICE_DUE,tied.kind());assertEquals(3,scheduler.poll(60).id());
        assertEquals(Long.MAX_VALUE,scheduler.nextDue());
        // The answer is the heap's head whatever its size: nextDue is O(1) and leaves the heap as it was.
        for(int i=0;i<100_000;i++)scheduler.schedule(1_000_000-i,SLICE_DUE,i,0);
        assertEquals(900_001,scheduler.nextDue());assertEquals(100_000,scheduler.size());
        scheduler.compact(entry->entry.id()%2==0);assertEquals(50_000,scheduler.size());assertEquals(900_002,scheduler.nextDue(),"id 99,998 is now the head");
        assertThrows(IllegalArgumentException.class,()->scheduler.schedule(Long.MAX_VALUE,SLICE_DUE,1,0));
        scheduler.clear();assertEquals(0,scheduler.size());
    }

    @Test void aRevisionBumpLeavesOnlyStaleEntriesThatFireNothingUntilTheResumeReschedules() {
        var dispatch=new Dispatch();var coordinator=coordinator(dispatch);
        coordinator.register(island(1,0,0,Map.of()),MODEL);coordinator.register(island(2,0,0,Map.of()),MODEL);
        assertEquals(100,coordinator.nextDue());
        coordinator.suspendForPropertyChange("HELD: property data changed");count();
        for(int tick=0;tick<150;tick++)coordinator.tick();
        assertTrue(dispatch.submitted.isEmpty());assertEquals(0,counted("deadlinesFired"),"a revision bump must invalidate the queued deadlines");
        assertEquals(0,counted("islandVisits"));assertEquals(0,counted("readinessPumps"));assertEquals(Long.MAX_VALUE,coordinator.nextDue(),"stale entries are dropped when popped");
        coordinator.resumeQualifiedProperties();assertEquals(2,coordinator.readyCount());
        coordinator.tick();assertEquals(2,dispatch.submitted.size());
    }

    @Test void repartitionAndRemovalInvalidateTheReplacedIslandsDeadlines() {
        var dispatch=new Dispatch();var coordinator=coordinator(dispatch);
        coordinator.register(island(1,0,0,Map.of()),MODEL);coordinator.register(island(2,0,0,Map.of()),MODEL);coordinator.register(island(3,0,0,Map.of()),MODEL);
        assertEquals(3,coordinator.scheduledDeadlines(),"one live deadline per island");
        var merge=UUID.randomUUID();coordinator.fence(merge,0,List.of(1L,2L));assertTrue(coordinator.aligned(merge,List.of(1L,2L)));
        var first=coordinator.snapshot(1).graph().reservoirs().getFirst();var second=coordinator.snapshot(2).graph().reservoirs().getFirst();
        coordinator.repartition(merge,Set.of(1L,2L),List.of(new IslandCoordinator.Replacement(4,new PassiveNetwork(List.of(first,second),List.of()))));
        var removal=UUID.randomUUID();coordinator.fence(removal,0,List.of(3L));
        coordinator.topology(removal,Set.of(3L),List.of(),MODEL,0,0,Map.of(),Set.of(3L),()->{});
        assertEquals(Set.of(4L),coordinator.snapshots().stream().map(IslandCoordinator.Snapshot::id).collect(java.util.stream.Collectors.toSet()));
        count();for(int tick=0;tick<100;tick++)coordinator.tick();
        assertEquals(1,counted("deadlinesFired"),"only the replacement's deadline is current");
        assertEquals(List.of(4L),dispatch.submitted.stream().map(IslandCoordinator.Attempt::islandId).toList());
        assertTrue(coordinator.fencedIslands(merge).isEmpty()&&coordinator.fencedIslands(removal).isEmpty(),"released events leave no index entries");
    }

    @Test void stopClearsEveryDeadlineAndLaterTicksDoNothing() {
        var dispatch=new Dispatch();var coordinator=coordinator(dispatch);
        for(long id=1;id<=5;id++)coordinator.register(island(id,0,0,Map.of()),MODEL);
        coordinator.stop();assertEquals(Long.MAX_VALUE,coordinator.nextDue());assertEquals(0,coordinator.scheduledDeadlines());
        count();for(int tick=0;tick<500;tick++)coordinator.tick();
        assertTrue(dispatch.submitted.isEmpty());assertEquals(0,counted("islandVisits"));assertEquals(0,counted("readinessPumps"));
    }

    /**
     * Idle islands (waiting on an event fence at their committed tick, the one state without due work before
     * certificates exist) cost nothing per tick at any count, and a transient island's work is the same with
     * or without a thousand idle neighbours.
     */
    @Test void idleIslandsCostNothingPerTickAtOneHundredOrAThousand() {
        long active=-1;
        for(int count:new int[]{1,100,1000}) {
            var dispatch=new Dispatch();var coordinator=coordinator(dispatch);var event=UUID.randomUUID();
            for(long id=1;id<=count;id++)coordinator.register(island(id,0,0,Map.of(event,0L)),MODEL);
            assertEquals(Long.MAX_VALUE,coordinator.nextDue());assertEquals(0,coordinator.scheduledDeadlines());assertEquals(0,coordinator.readyCount());
            count();for(int tick=0;tick<10_000;tick++)coordinator.tick();
            for(var name:List.of("islandVisits","readinessPumps","solvesDispatched","deadlinesFired","islandSnapshots","moduleScans","topologySnapshots"))
                assertEquals(0,counted(name),count+" idle islands: "+name+" over 10,000 ticks");
            assertTrue(dispatch.submitted.isEmpty());assertEquals(Long.MAX_VALUE,coordinator.nextDue());
            for(var snapshot:coordinator.snapshots())assertEquals(new IslandClock.Snapshot(10_000,0,0,100),snapshot.clock(),"online time still accrues");
            // One transient island beside the idle ones: its visits do not depend on how many idle ones exist.
            coordinator.register(island(count+1L,0,0,Map.of()),MODEL);count();
            for(int tick=0;tick<2_000;tick++){coordinator.tick();dispatch.finishAll();}
            assertEquals(20,counted("solvesDispatched"));assertEquals(2_000,coordinator.snapshot(count+1L).clock().committedTick());
            long visits=counted("islandVisits");if(active<0)active=visits;
            assertEquals(active,visits,"the transient island's scheduling work must not scale with "+count+" idle islands");
        }
    }

    /**
     * Plan section 5 item 1: 1, 100 and 1,000 certified islands with no viewers and no events cost nothing over
     * 10,000 ticks - no island visit, readiness pump, solve, deadline, snapshot or materialisation, and no heap
     * entry - while online time accrues; a read materialises only the island read; and a transient island
     * beside them does the same scheduling work at every count.
     */
    @Test void certifiedIslandsCostNothingPerTickAtOneHundredOrAThousand() {
        long active=-1;
        for(int count:new int[]{1,100,1000}) {
            var dispatch=new Dispatch();
            var coordinator=new IslandCoordinator(dispatch,changed->{},()->0,new IslandCoordinator.Settings(2_000_000_000L,1_500_000_000L,64,false,100,CertificatePolicy.defaults()));
            dispatch.coordinator=coordinator;
            for(long id=1;id<=count;id++)coordinator.register(island(id,0,0,Map.of()),MODEL);
            int tick=0;
            for(;tick<1_000&&coordinator.observe().stream().anyMatch(s->s.certificate().isEmpty());tick++){coordinator.tick();if(!dispatch.attempts.isEmpty())dispatch.finishAll();}
            for(var snapshot:coordinator.observe())assertEquals(IslandCertificate.Kind.REST,snapshot.certificate().orElseThrow().kind(),"island "+snapshot.id());
            assertEquals(0,coordinator.readyCount());assertEquals(0,coordinator.pendingCount());assertEquals(2L*count,dispatch.submitted.size(),"two exact-zero intervals each");
            count();
            for(int idle=0;idle<10_000;idle++){coordinator.tick();tick++;if(!dispatch.attempts.isEmpty())dispatch.finishAll();}
            for(var name:List.of("islandVisits","readinessPumps","solvesDispatched","deadlinesFired","islandSnapshots","moduleScans","topologySnapshots","materialisations","certificateWakes"))
                assertEquals(0,counted(name),count+" certified islands: "+name+" over 10,000 ticks");
            assertEquals(Long.MAX_VALUE,coordinator.nextDue());assertEquals(2L*count,dispatch.submitted.size());
            final long now=tick;
            var read=coordinator.snapshot(1);assertEquals(now,read.clock().onlineTick());assertEquals(now,read.clock().committedTick(),"a read materialises the island to now");
            assertEquals(1,counted("materialisations"),"and only the island read");
            assertTrue(coordinator.observe().stream().filter(s->s.id()!=1).allMatch(s->s.clock().committedTick()<now-9_000),"the others were not advanced");
            // One transient island beside them: a generator filling a thousand-cubic-metre tank never certifies here.
            var generator=new PassiveNetwork.Reservoir(10_000_001,0,MODEL.initialNitrogenCharge(1,298.15,150000,()->{}),PassiveNetwork.NodeKind.GENERATOR);
            var tank=new PassiveNetwork.Reservoir(10_000_002,0,MODEL.initialNitrogenCharge(1000,298.15,101325,()->{}));
            var filling=new PassiveNetwork(List.of(generator,tank),List.of(new PassiveNetwork.Pipe(10_000_003,0,1,new PipeResistance.Geometry(4,.05,.000045,0))));
            long id=count+1L;coordinator.register(new IslandCoordinator.Snapshot(id,0,filling,new IslandClock.Snapshot(0,0,0,100),FallbackAllowance.NONE,Optional.empty(),Optional.empty(),"READY"),MODEL);
            count();
            for(int step=0;step<2_000;step++){coordinator.tick();if(!dispatch.attempts.isEmpty())dispatch.finishAll();}
            assertTrue(coordinator.observe(id).certificate().isEmpty(),"the filling tank must not certify");
            assertEquals(20,counted("solvesDispatched"));assertEquals(2_000,coordinator.observe(id).clock().committedTick());
            assertEquals(0,counted("materialisations"));
            long visits=counted("islandVisits");if(active<0)active=visits;
            assertEquals(active,visits,"the transient island's scheduling work must not scale with "+count+" certified islands");
        }
    }
}
