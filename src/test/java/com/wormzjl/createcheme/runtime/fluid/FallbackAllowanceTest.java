package com.wormzjl.createcheme.runtime.fluid;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class FallbackAllowanceTest {
    @Test void cadenceIncreaseAndRestartCannotExtendTheCapturedDuration() {
        var episode=FallbackAllowance.NONE.accept(100,100).accept(100,400);
        var restored=new FallbackAllowance(episode.acceptedIntervals(),episode.advancedTicks(),episode.capturedCadenceTicks());
        assertFalse(restored.permits(101,400));assertTrue(restored.permits(100,400));
        var exhausted=restored.accept(100,400);assertFalse(exhausted.permits(1,20));assertEquals(FallbackAllowance.NONE,exhausted.fullRecovery());
    }
    @Test void ThreeSmallCatchupSlicesStillExhaustTheIntervalCount() {
        var episode=FallbackAllowance.NONE.accept(10,100).accept(10,100).accept(10,100);
        assertEquals(30,episode.advancedTicks());assertFalse(episode.permits(10,100));
        assertThrows(IllegalStateException.class,()->episode.accept(10,100));
    }
}
