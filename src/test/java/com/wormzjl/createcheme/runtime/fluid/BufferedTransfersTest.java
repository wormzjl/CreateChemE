package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.state.EnergyReference;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BufferedTransfersTest {
    private final UUID receiver=UUID.randomUUID(),producer=UUID.randomUUID();
    private MaterialParcel parcel(){return new MaterialParcel(new double[]{2000,2000},new double[]{.018,.032},123456,EnergyReference.sensible(List.of("Water","Oxygen")));}
    private BufferedTransfers ledger(double capacity) {
        var buffer=new BufferedTransfers.Buffer(receiver,capacity,0,Map.of());
        return new BufferedTransfers(new BufferedTransfers.Snapshot(0,Map.of(receiver,buffer),Map.of()));
    }
    private PendingTransfers.Pending pending(UUID id,long due){return new PendingTransfers.Pending(id,producer,receiver,due,0,parcel());}
    @Test void partialDeliveryAtomicallyConvertsReservedCapacityToOccupancyAndConservesMaterial() {
        var ledger=ledger(100);var id=UUID.randomUUID();ledger.commit(ledger.reserve(List.of(pending(id,100))));
        assertEquals(0,ledger.snapshot().buffers().get(receiver).freeKg());assertEquals(100,ledger.snapshot().buffers().get(receiver).reservedKg());
        var first=ledger.deliver(100,List.of(new BufferedTransfers.Feasible(id,0,20)));
        assertEquals(100,ledger.snapshot().pending().get(id).remaining().massKg());
        ledger.commit(first);var after=ledger.snapshot();assertEquals(20,after.buffers().get(receiver).occupiedKg());assertEquals(80,after.buffers().get(receiver).reservedKg());assertEquals(1,after.pending().get(id).revision());
        var second=ledger.deliver(100,List.of(new BufferedTransfers.Feasible(id,1,80)));ledger.commit(second);
        assertTrue(ledger.snapshot().pending().isEmpty());assertEquals(100,ledger.snapshot().buffers().get(receiver).occupiedKg());assertEquals(0,ledger.snapshot().buffers().get(receiver).reservedKg());
        var a=first.delivered().getFirst().material();var b=second.delivered().getFirst().material();
        assertEquals(parcel().internalEnergy(),a.internalEnergy()+b.internalEnergy(),1e-9);
        for(int c=0;c<2;c++)assertEquals(parcel().moles()[c],a.moles()[c]+b.moles()[c],1e-12);
    }
    @Test void failedOrStalePhysicalProposalCannotConsumeAnyReservationOrPendingPortion() {
        var ledger=ledger(200);var a=UUID.randomUUID();var b=UUID.randomUUID();ledger.commit(ledger.reserve(List.of(pending(a,100),pending(b,100))));
        var before=ledger.snapshot();var discarded=ledger.deliver(100,List.of(new BufferedTransfers.Feasible(a,0,20),new BufferedTransfers.Feasible(b,0,20)));
        assertSame(before,ledger.snapshot()); // The physical solve may fail after staging.
        var accepted=ledger.deliver(100,List.of(new BufferedTransfers.Feasible(a,0,0),new BufferedTransfers.Feasible(b,0,20)));ledger.commit(accepted);
        assertEquals(0,ledger.snapshot().pending().get(a).revision());assertEquals(1,ledger.snapshot().pending().get(b).revision());
        var committed=ledger.snapshot();assertThrows(IllegalStateException.class,()->ledger.commit(discarded));assertSame(committed,ledger.snapshot());
        assertThrows(IllegalStateException.class,()->ledger.deliver(99,List.of(new BufferedTransfers.Feasible(a,0,20))));assertSame(committed,ledger.snapshot());
    }
    @Test void reservationsCannotHideUnlimitedCapacityAndRestartPreservesThem() {
        var ledger=ledger(100);var a=UUID.randomUUID();ledger.commit(ledger.reserve(List.of(pending(a,100))));
        var before=ledger.snapshot();assertThrows(IllegalArgumentException.class,()->ledger.reserve(List.of(pending(UUID.randomUUID(),100))));assertSame(before,ledger.snapshot());
        assertThrows(IllegalArgumentException.class,()->ledger.occupancy(Map.of(receiver,1.0)));assertSame(before,ledger.snapshot());
        var restored=new BufferedTransfers(before);assertEquals(100,restored.snapshot().pending().get(a).remaining().massKg());assertEquals(0,restored.snapshot().buffers().get(receiver).freeKg());
        restored.commit(restored.deliver(100,List.of(new BufferedTransfers.Feasible(a,0,100))));
        restored.commit(restored.occupancy(Map.of(receiver,75.0)));assertEquals(25,restored.snapshot().buffers().get(receiver).freeKg());
    }
    @Test void backpressureStopsImmediatelyAndResumesOnlyAtBothThresholds() {
        var policy=BufferBackpressure.defaults();
        assertTrue(policy.mayRun(false,List.of(20.0),List.of(20.0,20.0)));
        assertFalse(policy.mayRun(false,List.of(19.99),List.of(100.0)));
        assertFalse(policy.mayRun(false,List.of(100.0),List.of(20.0,19.99)));
        assertTrue(policy.mayRun(true,List.of(.01),List.of(.01)));
        assertFalse(policy.mayRun(true,List.of(100.0),List.of(0.0)));
    }
}
