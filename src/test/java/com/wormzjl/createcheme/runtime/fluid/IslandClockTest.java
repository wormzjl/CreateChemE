package com.wormzjl.createcheme.runtime.fluid;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class IslandClockTest {
    @Test void idleCatchupCanConsumeNineIntervalsWithoutWaitingForNineMoreCadences() {
        var clock=IslandClock.fresh();clock.accrueOnlineTicks(900);
        for(int request=1;request<=9;request++){var slice=clock.nextSlice(request,Long.MAX_VALUE).orElseThrow();clock.admitted(slice);clock.completed(slice,true);}
        assertEquals(0,clock.debtTicks());assertEquals(900,clock.committedTick());assertTrue(clock.nextSlice(10,Long.MAX_VALUE).isEmpty());
    }
    @Test void heldResultsKeepDebtAndNeverReplayAnAcceptedApproximateInterval() {
        var clock=IslandClock.fresh();clock.accrueOnlineTicks(200);var first=clock.nextSlice(1,Long.MAX_VALUE).orElseThrow();clock.admitted(first);clock.completed(first,true);
        var second=clock.nextSlice(2,Long.MAX_VALUE).orElseThrow();clock.admitted(second);clock.completed(second,false);
        assertEquals(100,clock.committedTick());assertEquals(100,clock.debtTicks());assertTrue(clock.nextSlice(3,Long.MAX_VALUE).isEmpty());
        clock.inputsChanged();assertEquals(100,clock.nextSlice(3,Long.MAX_VALUE).orElseThrow().startTick());
        assertThrows(IllegalStateException.class,()->clock.completed(first,true));
    }
    @Test void causalFencesAndRestartPreserveOnlineDebtWithoutAddingOfflineTime() {
        var clock=IslandClock.fresh();clock.accrueOnlineTicks(500);var slice=clock.nextSlice(1,60).orElseThrow();assertEquals(3,slice.seconds());
        clock.admitted(slice);clock.completed(slice,true);assertTrue(clock.nextSlice(2,60).isEmpty());
        var restored=new IslandClock(clock.snapshot());assertEquals(440,restored.debtTicks());assertEquals(60,restored.nextSlice(2,Long.MAX_VALUE).orElseThrow().startTick());
    }
    @Test void cadenceChangesAreBoundedAndDoNotEraseAccruedTime() {
        var clock=IslandClock.fresh();clock.accrueOnlineTicks(1000);
        for(int i=0;i<100;i++)clock.performanceSample(true,false);assertEquals(400,clock.snapshot().cadenceTicks());
        for(int i=0;i<300;i++)clock.performanceSample(false,true);assertEquals(20,clock.snapshot().cadenceTicks());assertEquals(1000,clock.debtTicks());
    }
    @Test void measuredOverloadAndRecoveryFollowThePlannedHysteresis() {
        var clock=IslandClock.fresh();
        for(int i=0;i<2;i++)clock.performanceSample(true,false);assertEquals(100,clock.snapshot().cadenceTicks());
        clock.performanceSample(true,false);assertEquals(125,clock.snapshot().cadenceTicks());
        for(int i=0;i<9;i++)clock.performanceSample(false,true);assertEquals(125,clock.snapshot().cadenceTicks());
        clock.performanceSample(false,true);assertEquals(112,clock.snapshot().cadenceTicks());
    }

    // ---- online time derived from a shared epoch ----

    @Test void oneEpochIncrementAdvancesEveryClockOnItWithoutTouchingThem() {
        long[] epoch={1000};
        var fresh=new IslandClock(new IslandClock.Snapshot(0,0,0,100),()->epoch[0]);
        var behind=new IslandClock(new IslandClock.Snapshot(250,200,0,100),()->epoch[0]);
        assertEquals(0,fresh.snapshot().onlineTick());assertEquals(250,behind.snapshot().onlineTick());
        epoch[0]+=150;
        assertEquals(150,fresh.snapshot().onlineTick());assertEquals(400,behind.snapshot().onlineTick());assertEquals(200,behind.debtTicks());
        assertEquals(1100,fresh.epochTickAt(100),"online 100 on a clock restored at epoch 1000 with online 0");
        assertEquals(950,behind.epochTickAt(200));
        assertThrows(IllegalArgumentException.class,()->fresh.accrueOnlineTicks(-1));
        fresh.accrueOnlineTicks(5);assertEquals(155,fresh.snapshot().onlineTick(),"a clock driven directly still accrues relative to its epoch");
    }
    @Test void restartOnAnotherEpochKeepsTheSavedDebtExactlyAndAddsNoOfflineTime() {
        long[] epoch={0};var clock=new IslandClock(new IslandClock.Snapshot(0,0,0,100),()->epoch[0]);epoch[0]=540;
        var slice=clock.nextSlice(1,Long.MAX_VALUE).orElseThrow();clock.admitted(slice);clock.completed(slice,true);
        var saved=clock.snapshot();assertEquals(new IslandClock.Snapshot(540,100,0,100),saved);assertEquals(440,clock.debtTicks());
        // The server is down for any length of time; the restored world epoch resumes from its saved value.
        long[] restored={540};var sameWorld=new IslandClock(saved,()->restored[0]);
        assertEquals(saved,sameWorld.snapshot());assertEquals(440,sameWorld.debtTicks());assertEquals(100,sameWorld.nextSlice(2,Long.MAX_VALUE).orElseThrow().startTick());
        // A clock restored onto an unrelated epoch value reads the same saved clock and moves only with it.
        long[] other={9000};var elsewhere=new IslandClock(saved,()->other[0]);assertEquals(saved,elsewhere.snapshot());
        other[0]+=20;assertEquals(560,elsewhere.snapshot().onlineTick());assertEquals(460,elsewhere.debtTicks());
    }
    @Test void aFenceAtTheCommittedTickHasNoTimeDeadlineAndALaterFenceShortensTheSlice() {
        long[] epoch={0};var clock=new IslandClock(new IslandClock.Snapshot(300,100,0,100),()->epoch[0]);
        assertTrue(clock.nextSlice(1,100).isEmpty());assertEquals(Long.MAX_VALUE,clock.readyAtTick(100,Integer.MAX_VALUE));
        epoch[0]+=10_000;assertTrue(clock.nextSlice(1,100).isEmpty(),"time alone never passes a fence at the committed tick");
        assertEquals(200,clock.readyAtTick(Long.MAX_VALUE,Integer.MAX_VALUE));assertEquals(150,clock.readyAtTick(150,Integer.MAX_VALUE));
        assertEquals(150,clock.nextSlice(2,150).orElseThrow().endTick());
        assertThrows(IllegalArgumentException.class,()->clock.readyAtTick(99,Integer.MAX_VALUE));
    }
    @Test void theRetrySpanIsTheExactReadyTickOfAHeldClock() {
        long[] epoch={0};var clock=new IslandClock(new IslandClock.Snapshot(100,0,0,100),()->epoch[0]);
        var slice=clock.nextSlice(1,Long.MAX_VALUE).orElseThrow();clock.admitted(slice);
        assertEquals(Long.MAX_VALUE,clock.readyAtTick(Long.MAX_VALUE,Integer.MAX_VALUE),"an outstanding slice waits for its completion");
        epoch[0]=7;clock.completed(slice,false);assertEquals(207,clock.retryAtTick());assertEquals(107,clock.debtTicks());
        assertEquals(207,clock.readyAtTick(Long.MAX_VALUE,Integer.MAX_VALUE));
        assertEquals(207,clock.readyAtTick(Long.MAX_VALUE,50),"a shorter retry span shortens the slice, not the backoff");
        assertEquals(107,clock.epochTickAt(207));
        epoch[0]=106;assertTrue(clock.nextSlice(2,Long.MAX_VALUE,50).isEmpty());
        epoch[0]=107;var retry=clock.nextSlice(2,Long.MAX_VALUE,50).orElseThrow();assertEquals(0,retry.startTick());assertEquals(50,retry.endTick());
        clock.inputsChanged();assertEquals(50,clock.readyAtTick(Long.MAX_VALUE,50),"an input change lifts the backoff");
    }
    /** readyAtTick is exact: a slice is present at an online tick if and only if the tick has reached it. */
    @Test void theReadyTickMatchesSliceAvailabilityForArbitraryClockStates() {
        var random=new java.util.Random(20260923);
        for(int trial=0;trial<20_000;trial++) {
            long committed=random.nextInt(500);long online=committed+random.nextInt(600);int cadence=20+random.nextInt(381);
            long retry=random.nextBoolean()?0:online-50+random.nextInt(800);if(retry<0)retry=0;
            long[] epoch={random.nextInt(1_000_000)};var clock=new IslandClock(new IslandClock.Snapshot(online,committed,retry,cadence),()->epoch[0]);
            long fence=random.nextInt(4)==0?Long.MAX_VALUE:committed+random.nextInt(700);int span=random.nextInt(3)==0?1+random.nextInt(200):Integer.MAX_VALUE;
            if(random.nextInt(8)==0){var admitted=clock.nextSlice(1,Long.MAX_VALUE);if(admitted.isPresent())clock.admitted(admitted.orElseThrow());}
            long ready=clock.readyAtTick(fence,span);
            for(int step=0;step<5;step++) {
                long now=clock.snapshot().onlineTick();
                assertEquals(ready!=Long.MAX_VALUE&&now>=ready,clock.nextSlice(1,fence,span).isPresent(),"trial "+trial+" online "+now+" ready "+ready);
                epoch[0]+=random.nextInt(300);
            }
        }
    }
    /** Plan section 3.2: the identity or replay commit refuses exactly what a solved commit may not do. */
    @Test void restCommitsWithoutASolveButNeverPastTheOnlineClockAFenceOrAroundAnOutstandingSlice() {
        long[] epoch={1_000};var clock=new IslandClock(new IslandClock.Snapshot(700,200,650,100),()->epoch[0]);
        assertThrows(IllegalArgumentException.class,()->clock.rest(199,Long.MAX_VALUE),"below the committed tick");
        assertThrows(IllegalArgumentException.class,()->clock.rest(701,Long.MAX_VALUE),"past the online clock");
        assertThrows(IllegalArgumentException.class,()->clock.rest(500,499),"past a fence");
        assertEquals(200,clock.committedTick());assertEquals(650,clock.retryAtTick());
        clock.rest(200,200);assertEquals(200,clock.committedTick(),"a zero advance at a fence is allowed");
        clock.rest(450,450);assertEquals(450,clock.committedTick());assertEquals(0,clock.retryAtTick(),"a commit clears the retry");
        epoch[0]+=100;clock.rest(800,Long.MAX_VALUE);assertEquals(800,clock.committedTick());assertEquals(0,clock.debtTicks());
        epoch[0]+=100;var slice=clock.nextSlice(1,Long.MAX_VALUE).orElseThrow();clock.admitted(slice);
        assertThrows(IllegalStateException.class,()->clock.rest(850,Long.MAX_VALUE),"nothing commits around an outstanding slice");
        clock.completed(slice,true);assertEquals(900,clock.committedTick());
    }
}
