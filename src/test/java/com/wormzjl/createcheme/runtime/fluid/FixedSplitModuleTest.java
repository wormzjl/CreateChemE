package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.state.EnergyReference;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FixedSplitModuleTest {
    private final UUID moduleId=UUID.randomUUID(),feedA=UUID.randomUUID(),feedB=UUID.randomUUID(),outA=UUID.randomUUID(),outB=UUID.randomUUID();
    private MaterialParcel material(double kg){return new MaterialParcel(new double[]{kg*.4,kg*.3},new double[]{1,2},100*kg,EnergyReference.sensible(List.of("A","B")));}
    private BufferedTransfers ledger(double capacity){return new BufferedTransfers(new BufferedTransfers.Snapshot(0,Map.of(outA,new BufferedTransfers.Buffer(outA,capacity,0,Map.of()),outB,new BufferedTransfers.Buffer(outB,capacity,0,Map.of())),Map.of()));}
    private FixedSplitModule module(int ticks,BufferedTransfers ledger) {
        var definition=new FixedSplitModule.Definition(moduleId,List.of(new FixedSplitModule.Feed(feedA,2),new FixedSplitModule.Feed(feedB,1)),outA,outB,ticks,new double[]{1,0});
        return new FixedSplitModule(new FixedSplitModule.Snapshot(definition,0,0,false,null),ledger,BufferBackpressure.defaults());
    }
    @Test void fiveFifteenAndThirtySecondCyclesOwnPartialFeedsThenPublishConservedProductsOnce() {
        for(int cadence:new int[]{100,300,600}) {
            var ledger=ledger(1000);var module=module(cadence,ledger);var observations=Map.of(feedA,new FixedSplitModule.Observation(0,material(100)),feedB,new FixedSplitModule.Observation(0,material(100)));
            module.commit(module.begin(observations));assertTrue(ledger.snapshot().pending().isEmpty());assertEquals(2,ledger.snapshot().planned().size());
            double owned=0;
            for(int tick=0;tick<cadence;tick+=100) {
                for(var request:module.withdrawals(tick,tick+100,Set.of(feedA,feedB))) {
                    var staged=module.receive(request.id(),tick,tick+100,material(request.maximumKg()));
                    assertEquals(owned,module.snapshot().cycle().inputs().values().stream().mapToDouble(i->i.owned().massKg()).sum(),1e-10);
                    module.commit(staged);owned+=request.maximumKg();
                    assertThrows(IllegalStateException.class,()->module.commit(staged));
                }
            }
            assertEquals(3*cadence/20.0,owned,1e-10);assertThrows(IllegalStateException.class,()->module.finish(cadence-1));
            var finished=module.finish(cadence);assertTrue(ledger.snapshot().pending().isEmpty());module.commit(finished);
            assertNull(module.snapshot().cycle());assertEquals(cadence,module.snapshot().committedTick());assertTrue(ledger.snapshot().planned().isEmpty());
            assertEquals(owned,ledger.snapshot().pending().values().stream().mapToDouble(p->p.remaining().massKg()).sum(),1e-10);
            assertEquals(owned*100,ledger.snapshot().pending().values().stream().mapToDouble(p->p.remaining().energyJoule()).sum(),1e-8);
            var first=ledger.snapshot().pending().values().stream().filter(p->p.receiver().equals(outA)).findFirst().orElseThrow();
            assertEquals(owned*.4,first.remaining().moles()[0],1e-10);assertEquals(0,first.remaining().moles()[1]);
        }
    }
    @Test void independentFeedLagCannotProcessBorrowedOrFutureMaterialAndRestoreKeepsHoldup() {
        var ledger=ledger(1000);var module=module(300,ledger);
        assertThrows(IllegalStateException.class,()->module.begin(Map.of(feedA,new FixedSplitModule.Observation(1,material(100)),feedB,new FixedSplitModule.Observation(0,material(100)))));
        module.commit(module.begin(Map.of(feedA,new FixedSplitModule.Observation(0,material(100)),feedB,new FixedSplitModule.Observation(0,material(100)))));
        var request=module.withdrawals(0,100,Set.of(feedA)).getFirst();module.commit(module.receive(request.id(),0,100,material(10)));
        assertThrows(IllegalStateException.class,()->module.receive(request.id(),0,100,material(10)));
        assertThrows(IllegalStateException.class,()->module.withdrawals(100,200,Set.of(feedB)));
        assertThrows(IllegalStateException.class,()->module.finish(300));assertTrue(ledger.snapshot().pending().isEmpty());
        var restored=new FixedSplitModule(module.snapshot(),new BufferedTransfers(ledger.snapshot()),BufferBackpressure.defaults());
        assertEquals(10,restored.snapshot().cycle().inputs().get(feedA).owned().massKg());assertEquals(0,restored.snapshot().cycle().inputs().get(feedB).owned().massKg());
        assertThrows(IllegalArgumentException.class,()->new FixedSplitModule(module.snapshot(),ledger(1000),BufferBackpressure.defaults()));
    }
    @Test void emptyFeedAndInsufficientOutputCapacityAnnounceKnownZeroWithoutCircularWait() {
        for(boolean empty:new boolean[]{true,false}) {
            var ledger=ledger(empty?1000:20);var module=module(300,ledger);double feedKg=empty?0:100;
            module.commit(module.begin(Map.of(feedA,new FixedSplitModule.Observation(0,material(feedKg)),feedB,new FixedSplitModule.Observation(0,material(feedKg)))));
            assertTrue(module.snapshot().cycle().knownZero());assertTrue(module.snapshot().cycle().inputsComplete());assertTrue(ledger.snapshot().planned().isEmpty());
            module.commit(module.finish(300));assertEquals(300,module.snapshot().committedTick());assertFalse(module.snapshot().running());assertTrue(ledger.snapshot().pending().isEmpty());
        }
    }
    @Test void aRestoreCannotOmitReservationsAndSilentlyDiscardOwnedFeedOnFinish() {
        var ledger=ledger(1000);var module=module(100,ledger);module.commit(module.begin(Map.of(feedA,new FixedSplitModule.Observation(0,material(100)),feedB,new FixedSplitModule.Observation(0,material(100)))));
        var s=module.snapshot();var c=s.cycle();
        assertThrows(IllegalArgumentException.class,()->new FixedSplitModule.Snapshot(s.definition(),s.revision(),s.committedTick(),s.running(),new FixedSplitModule.Cycle(c.startTick(),c.endTick(),c.inputs(),List.of(),"corrupt")));
    }
    @Test void codecRestoresPartialInputOwnershipWithItsUnresolvedDeliveryHorizon() {
        var ledger=ledger(1000);var module=module(300,ledger);module.commit(module.begin(Map.of(feedA,new FixedSplitModule.Observation(0,material(100)),feedB,new FixedSplitModule.Observation(0,material(100)))));
        var request=module.withdrawals(0,100,Set.of(feedA)).getFirst();module.commit(module.receive(request.id(),0,100,material(7)));
        var checkpoint=new FluidCheckpointCodec.Checkpoint(List.of(),ledger.snapshot(),List.of(module.snapshot()));
        var json=FluidCheckpointCodec.encode(checkpoint,key->{throw new AssertionError();});var decoded=FluidCheckpointCodec.decode(json,key->{throw new AssertionError();});
        var saved=decoded.modules().getFirst();assertEquals(300,saved.cycle().endTick());assertEquals(100,saved.cycle().inputs().get(feedA).throughTick());assertEquals(0,saved.cycle().inputs().get(feedB).throughTick());
        assertArrayEquals(material(7).moles(),saved.cycle().inputs().get(feedA).owned().moles());assertEquals(700,saved.cycle().inputs().get(feedA).owned().energyJoule());
        assertEquals(2,decoded.transfers().planned().size());assertTrue(decoded.transfers().pending().isEmpty());
        var typed=com.google.gson.JsonParser.parseString(json).getAsJsonObject();var typedState=typed.getAsJsonObject("modules").getAsJsonArray("states").get(0).getAsJsonObject();
        typedState.addProperty("scientificRevision","unknown-revision");
        assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(typed.toString(),key->{throw new AssertionError();}));
        typedState.remove("scientificRevision");
        assertThrows(RuntimeException.class,()->FluidCheckpointCodec.decode(typed.toString(),key->{throw new AssertionError();}));
        typedState.remove("type");
        assertEquals(1,FluidCheckpointCodec.decode(typed.toString(),key->{throw new AssertionError();}).modules().size());
        var legacy=com.google.gson.JsonParser.parseString(FluidCheckpointCodec.encode(new FluidCheckpointCodec.Checkpoint(List.of(),ledger(1000).snapshot()),key->{throw new AssertionError();})).getAsJsonObject();legacy.remove("modules");legacy.getAsJsonObject("transfers").remove("productionCapacity");
        assertTrue(FluidCheckpointCodec.decode(legacy.toString(),key->{throw new AssertionError();}).modules().isEmpty());
    }
}
