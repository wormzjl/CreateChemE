package com.wormzjl.createcheme.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessSolveServicesRequestTest {
    @Test
    void processWideRequestIdsArePositiveAndStrictlyMonotonic() {
        long first = ProcessSolveServices.nextRequestId();
        long second = ProcessSolveServices.nextRequestId();

        assertTrue(first > 0L);
        assertTrue(second > first);
    }
}
