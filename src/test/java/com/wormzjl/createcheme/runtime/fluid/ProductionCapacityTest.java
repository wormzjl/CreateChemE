package com.wormzjl.createcheme.runtime.fluid;

import com.google.gson.JsonParser;
import com.wormzjl.createcheme.science.fluid.state.EnergyReference;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProductionCapacityTest {
    private final UUID producer=UUID.randomUUID(),receiver=UUID.randomUUID(),id=UUID.randomUUID();
    private BufferedTransfers ledger(){return new BufferedTransfers(new BufferedTransfers.Snapshot(0,Map.of(receiver,new BufferedTransfers.Buffer(receiver,100,0,Map.of())),Map.of()));}
    private MaterialParcel material(double kg){return new MaterialParcel(new double[]{kg},new double[]{1},100*kg,EnergyReference.sensible(List.of("test")));}
    @Test void reservationOwnsNoMaterialAndConvertsOnlyActualProductionIntoPendingStock() {
        var ledger=ledger();ledger.commit(ledger.reserveCapacity(List.of(new BufferedTransfers.CapacityReservation(id,producer,receiver,300,100))));
        assertEquals(0,ledger.snapshot().freeKg(receiver));assertTrue(ledger.snapshot().pending().isEmpty());assertEquals(0,ledger.snapshot().buffers().get(receiver).occupiedKg());
        assertThrows(IllegalArgumentException.class,()->ledger.occupancy(Map.of(receiver,1.0)));
        var before=ledger.snapshot();var product=new PendingTransfers.Pending(id,producer,receiver,300,0,material(40));
        var prepared=ledger.produce(Set.of(id),List.of(product));assertSame(before,ledger.snapshot());
        ledger.commit(prepared);assertTrue(ledger.snapshot().planned().isEmpty());assertEquals(60,ledger.snapshot().freeKg(receiver));assertEquals(40,ledger.snapshot().pending().get(id).remaining().massKg());
        ledger.commit(ledger.deliver(300,List.of(new BufferedTransfers.Feasible(id,0,20))));
        assertEquals(60,ledger.snapshot().freeKg(receiver));assertEquals(20,ledger.snapshot().buffers().get(receiver).occupiedKg());assertEquals(20,ledger.snapshot().pending().get(id).remaining().massKg());
    }
    @Test void overproductionWrongProducerOrFutureDateCannotPublishAndZeroProductionReleasesCapacity() {
        var ledger=ledger();ledger.commit(ledger.reserveCapacity(List.of(new BufferedTransfers.CapacityReservation(id,producer,receiver,300,100))));var before=ledger.snapshot();
        for(var invalid:List.of(new PendingTransfers.Pending(id,producer,receiver,300,0,material(101)),new PendingTransfers.Pending(id,UUID.randomUUID(),receiver,300,0,material(10)),new PendingTransfers.Pending(id,producer,receiver,301,0,material(10)))) {
            assertThrows(IllegalStateException.class,()->ledger.produce(Set.of(id),List.of(invalid)));assertSame(before,ledger.snapshot());
        }
        assertThrows(IllegalArgumentException.class,()->ledger.reserve(List.of(new PendingTransfers.Pending(UUID.randomUUID(),producer,receiver,300,0,material(1)))));
        ledger.commit(ledger.produce(Set.of(id),List.of()));assertEquals(100,ledger.snapshot().freeKg(receiver));assertTrue(ledger.snapshot().pending().isEmpty());
    }
    @Test void optionalCapacityExtensionRoundTripsAndLegacyCheckpointKeepsItsMaterial() {
        var ledger=ledger();ledger.commit(ledger.reserveCapacity(List.of(new BufferedTransfers.CapacityReservation(id,producer,receiver,300,100))));
        var json=FluidCheckpointCodec.encode(new FluidCheckpointCodec.Checkpoint(List.of(),ledger.snapshot()),key->{throw new AssertionError();});
        var loaded=FluidCheckpointCodec.decode(json,key->{throw new AssertionError();});assertEquals(ledger.snapshot(),loaded.transfers());
        var bad=JsonParser.parseString(json).getAsJsonObject();bad.getAsJsonObject("transfers").getAsJsonObject("productionCapacity").addProperty("version",99);
        assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(bad.toString(),key->{throw new AssertionError();}));
        ledger.commit(ledger.produce(Set.of(id),List.of(new PendingTransfers.Pending(id,producer,receiver,300,0,material(40)))));
        var legacy=JsonParser.parseString(FluidCheckpointCodec.encode(new FluidCheckpointCodec.Checkpoint(List.of(),ledger.snapshot()),key->{throw new AssertionError();})).getAsJsonObject();legacy.getAsJsonObject("transfers").remove("productionCapacity");
        var restored=FluidCheckpointCodec.decode(legacy.toString(),key->{throw new AssertionError();});assertTrue(restored.transfers().planned().isEmpty());
        assertEquals(40,restored.transfers().pending().get(id).remaining().massKg());assertEquals(4000,restored.transfers().pending().get(id).remaining().energyJoule());
    }
}
