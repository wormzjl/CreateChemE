package com.wormzjl.createcheme.runtime.fluid;

import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.createcheme.fluid.support.ConservationAssertions;
import com.wormzjl.createcheme.fluid.support.ConservationAssertions.DeliveryKey;
import com.wormzjl.createcheme.fluid.support.FluidTestSupport.ManualNanoClock;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuntimeHarnessTest {
    @Test void detectsDuplicatePortionsWhileAllowingSuccessivePartialDeliveries() {
        ConservationAssertions.uniqueDeliveries(List.of(new DeliveryKey(1, 0), new DeliveryKey(1, 1)));
        assertThrows(AssertionError.class, () -> ConservationAssertions.uniqueDeliveries(
                List.of(new DeliveryKey(1, 0), new DeliveryKey(1, 0))));
    }
    @Test void deadlinesCanBeDrivenWithoutSleepingOrMovingTimeBackward() {
        var clock = new ManualNanoClock();
        long started = clock.getAsLong();
        assertEquals(started + 75, clock.advance(75));
        assertEquals(started + 75, clock.getAsLong());
        assertThrows(IllegalArgumentException.class, () -> clock.advance(-1));
        assertThrows(ArithmeticException.class, () -> clock.advance(Long.MAX_VALUE));
        assertEquals(started + 75, clock.getAsLong());
    }
}
