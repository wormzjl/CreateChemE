package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldTopologyLedgerTest {
    private WorldTopologyLedger.Registration at(long id,int x) {
        var old=record(id,Kind.PIPE);var d=old.device();return new WorldTopologyLedger.Registration(new PhysicalFluidTopology.Device(id,new PhysicalFluidTopology.Position("minecraft:overworld",x,12,0),d.kind(),d.facing(),d.geometry(),d.control()),old.spec(),0);
    }
    @Test void independentLaterEventCanCommitWithoutAdvancingTheBlockedEventsTimestamp() {
        var ledger=new WorldTopologyLedger(WorldTopologyLedger.Snapshot.empty());ledger.tick();
        var first=ledger.queue(List.of(new WorldTopologyLedger.Edit(1,at(1,0))),Set.of(1L),2);ledger.commit(first);ledger.tick();
        var second=ledger.queue(List.of(new WorldTopologyLedger.Edit(2,at(2,20))),Set.of(2L),3);ledger.commit(second);
        assertEquals(List.of(first.event(),second.event()),ledger.readyEvents());
        var applied=ledger.applyReady(second.event().id(),List.of(),List.of(),new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1],3);ledger.commit(applied);
        assertEquals(Set.of(2L),ledger.active().keySet());assertEquals(List.of(first.event()),ledger.snapshot().events());assertEquals(1,ledger.snapshot().events().getFirst().tick());
        var restored=new WorldTopologyLedger(FluidCheckpointCodec.decodeWorld(FluidCheckpointCodec.encodeWorld(ledger.snapshot())));
        assertEquals(Set.of(1L,2L),restored.latest().keySet());restored.commit(restored.applyFirst(List.of(),List.of(),new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1],3));assertEquals(Set.of(1L,2L),restored.active().keySet());
        assertThrows(IllegalStateException.class,()->ledger.commit(applied));
    }
    @Test void sharedIslandDependenciesAndRemovalReplacementPositionsCannotBeReordered() {
        var ledger=new WorldTopologyLedger(new WorldTopologyLedger.Snapshot(0,3,Map.of(1L,at(1,0),2L,at(2,20)),List.of(),WorldTopologyLedger.MaterialTotal.empty(),WorldTopologyLedger.MaterialTotal.empty()));
        var remove=ledger.queue(List.of(new WorldTopologyLedger.Edit(1,null)),Set.of(1L),3);ledger.commit(remove);
        var replacement=ledger.queue(List.of(new WorldTopologyLedger.Edit(3,at(3,0))),Set.of(3L),4);ledger.commit(replacement);
        assertEquals(List.of(remove.event()),ledger.readyEvents());assertThrows(IllegalStateException.class,()->ledger.applyReady(replacement.event().id(),List.of(),List.of(),new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1],4));
        ledger.commit(ledger.applyFirst(List.of(),List.of(),new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1],4));assertEquals(List.of(replacement.event()),ledger.readyEvents());ledger.commit(ledger.applyFirst(List.of(),List.of(),new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1],4));
        var a=ledger.queue(List.of(new WorldTopologyLedger.Edit(2,null)),Set.of(2L,3L),4);ledger.commit(a);
        var b=ledger.queue(List.of(new WorldTopologyLedger.Edit(3,null)),Set.of(3L),4);ledger.commit(b);
        assertEquals(List.of(a.event()),ledger.readyEvents());assertThrows(IllegalStateException.class,()->ledger.applyReady(b.event().id(),List.of(),List.of(),new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1],4));
    }
    private WorldTopologyLedger.Registration record(long id,Kind kind) {
        var device=new PhysicalFluidTopology.Device(id,new PhysicalFluidTopology.Position("minecraft:overworld",0,12,0),kind,PhysicalFluidTopology.Direction.EAST,new PipeResistance.Geometry(1,.05,.000045,0),kind==Kind.PUMP?new FlowControl.Pump(.01,500000,1):new FlowControl.Passive());
        return new WorldTopologyLedger.Registration(device,FluidDeviceSpec.nitrogen(),0);
    }
    @Test void pendingPhysicalEventsRoundTripWithControlSettingsAndTheirOriginalTimestamps() {
        var ledger=new WorldTopologyLedger(WorldTopologyLedger.Snapshot.empty());for(int i=0;i<40;i++)ledger.tick();
        var registration=record(1,Kind.PUMP);var edit=ledger.queue(List.of(new WorldTopologyLedger.Edit(1,registration)),Set.of(1L),2);ledger.commit(edit);
        for(int i=0;i<10;i++)ledger.tick();var restored=new WorldTopologyLedger(FluidCheckpointCodec.decodeWorld(FluidCheckpointCodec.encodeWorld(ledger.snapshot())));
        assertEquals(50,restored.onlineTick());assertEquals(40,restored.snapshot().events().getFirst().tick());assertEquals(registration,restored.latest().get(1L));assertTrue(restored.snapshot().active().isEmpty());
    }
    @Test void constructionAndDestructionCountOnceAndNeverReuseTheOldIdentity() {
        var ledger=new WorldTopologyLedger(WorldTopologyLedger.Snapshot.empty());var registration=record(1,Kind.RESERVOIR);
        ledger.commit(ledger.queue(List.of(new WorldTopologyLedger.Edit(1,registration)),Set.of(1L),2));
        var model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),FluidPresetCatalog.NETWORK_PACKAGE,1e-9);var charge=registration.spec().initialize(registration.device(),model,()->{});
        double[] w=new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1];for(int c=0;c<com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1;c++)w[c]=c==com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK?model.waterMolecularWeight:model.hydrocarbon.molecularWeight(c);
        var created=ledger.applyFirst(List.of(charge),List.of(),w,3);var stale=ledger.applyFirst(List.of(charge),List.of(),w,3);ledger.commit(created);
        assertThrows(IllegalStateException.class,()->ledger.commit(stale));assertArrayEquals(charge.inventory().moles(),ledger.snapshot().constructed().moles());
        ledger.commit(ledger.queue(List.of(new WorldTopologyLedger.Edit(1,null)),Set.of(1L),3));ledger.commit(ledger.applyFirst(List.of(),List.of(charge),w,3));
        assertTrue(ledger.snapshot().active().isEmpty());assertArrayEquals(ledger.snapshot().constructed().moles(),ledger.snapshot().destroyed().moles());assertEquals(ledger.snapshot().constructed().totalEnergy(),ledger.snapshot().destroyed().totalEnergy());
        assertThrows(IllegalStateException.class,()->ledger.queue(List.of(new WorldTopologyLedger.Edit(1,registration)),Set.of(1L),3));
    }
    @Test void finiteReservoirInitializationCannotBeReappliedAsASettingsEdit() {
        var registration=record(1,Kind.RESERVOIR);var snapshot=new WorldTopologyLedger.Snapshot(0,2,Map.of(1L,registration),List.of(),WorldTopologyLedger.MaterialTotal.empty(),WorldTopologyLedger.MaterialTotal.empty());
        var ledger=new WorldTopologyLedger(snapshot);var changed=new WorldTopologyLedger.Registration(registration.device(),new FluidDeviceSpec(1,298.15,200000,registration.spec().composition()),1);
        assertThrows(IllegalStateException.class,()->ledger.queue(List.of(new WorldTopologyLedger.Edit(1,changed)),Set.of(1L),2));assertSame(snapshot,ledger.snapshot());
    }
}
