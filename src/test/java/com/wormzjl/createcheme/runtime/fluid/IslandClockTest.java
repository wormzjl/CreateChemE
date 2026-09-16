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
}
