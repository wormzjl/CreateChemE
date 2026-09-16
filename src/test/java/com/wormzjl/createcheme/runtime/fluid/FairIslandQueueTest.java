package com.wormzjl.createcheme.runtime.fluid;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class FairIslandQueueTest {
    @Test void catchupYieldsToAnotherReadyOwnerAndUnavailableOwnersDoNotWasteWorkers() {
        var queue=new FairIslandQueue();queue.register(1);queue.register(2);queue.register(3);queue.register(1);
        assertEquals(3,queue.size());assertEquals(1,queue.nextReady(id->id!=2).orElseThrow());
        assertEquals(3,queue.nextReady(id->id!=2).orElseThrow());assertEquals(2,queue.nextReady(id->true).orElseThrow());
        assertEquals(1,queue.nextReady(id->true).orElseThrow());queue.remove(2);assertEquals(2,queue.size());
        assertTrue(queue.nextReady(id->false).isEmpty());assertEquals(3,queue.nextReady(id->true).orElseThrow());
    }
}
