package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.runtime.WorkerAllocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkerAllocationTest {
    @Test void automaticSizingLeavesHeadroomAndCapsTheSharedPool() {
        assertEquals(1, WorkerAllocation.resolve(0, 1));
        assertEquals(1, WorkerAllocation.resolve(0, 2));
        assertEquals(1, WorkerAllocation.resolve(0, 3));
        assertEquals(2, WorkerAllocation.resolve(0, 4));
        assertEquals(12, WorkerAllocation.resolve(0, 16));
        assertEquals(12, WorkerAllocation.resolve(0, 128));
        assertEquals(32, WorkerAllocation.resolve(0, 128,32));
        assertEquals(6, WorkerAllocation.resolve(0, 8,12));
        assertEquals(2, WorkerAllocation.resolve(2, 16));
        assertEquals(1, WorkerAllocation.resolve(1, 16));
        assertThrows(IllegalArgumentException.class, () -> WorkerAllocation.resolve(33, 16));
        assertThrows(IllegalArgumentException.class, () -> WorkerAllocation.resolve(0, 0));
    }
    @Test void growsImmediatelyAndPreservesCapacityAcrossFiveSecondBursts() {
        var allocator=new WorkerAllocation.Demand(14);
        assertEquals(1,allocator.observe(0,0));assertEquals(14,allocator.observe(100,100));
        assertEquals(14,allocator.observe(0,110));assertEquals(14,allocator.observe(0,199));
        assertEquals(14,allocator.observe(100,200));assertEquals(14,allocator.observe(0,210));
        assertEquals(14,allocator.observe(0,409));assertEquals(1,allocator.observe(0,410));
        assertEquals(6,allocator.observe(6,411));assertEquals(14,allocator.observe(100,411));
    }
    @Test void shrinkingNeverRequestsZeroWorkersAndRejectsInvalidDemandClocks() {
        var allocator=new WorkerAllocation.Demand(1);assertEquals(1,allocator.observe(100,0));
        assertThrows(IllegalArgumentException.class,()->allocator.observe(-1,1));
        allocator.observe(0,10);assertThrows(IllegalArgumentException.class,()->allocator.observe(0,9));
    }
}
