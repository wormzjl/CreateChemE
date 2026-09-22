package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import net.minecraft.nbt.CompoundTag;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FluidSaveCompatibilityTest {
    @Test void oldCoreOnlySaveStillLoadsAndResavesWithoutLosingInventoryOrInventingTopology() {
        var model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),FluidPresetCatalog.NETWORK_PACKAGE,1e-9);
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(5,0,model.initialNitrogenCharge(1,298.15,65000,()->{}))),List.of());
        var island=new IslandCoordinator.Snapshot(1,0,graph,new IslandClock.Snapshot(350,200,0,100),FallbackAllowance.NONE,Optional.empty(),Optional.empty(),"HELD");
        var checkpoint=new FluidCheckpointCodec.Checkpoint(List.of(new FluidCheckpointCodec.IslandEntry("minecraft:overworld",FluidPresetCatalog.NETWORK_PACKAGE,1e-9,island)),new BufferedTransfers.Snapshot(0,Map.of(),Map.of()));
        var legacy=new FluidSavedData(checkpoint,key->model).save(new CompoundTag(),null);assertFalse(legacy.contains("Topology"));assertEquals(FluidCheckpointCodec.VERSION,legacy.getInt("FluidFormat"));
        var loaded=FluidSavedData.load(legacy,key->model);assertTrue(loaded.world().isEmpty());
        assertEquals(graph.reservoirs().getFirst().inventory(),loaded.checkpoint().islands().getFirst().snapshot().graph().reservoirs().getFirst().inventory());
        assertEquals(island.clock(),loaded.checkpoint().islands().getFirst().snapshot().clock());assertEquals(legacy,loaded.save(new CompoundTag(),null));
    }
    @Test void topologyIsAnOptionalChecksummedExtensionOfTheSameCoreFormat() {
        var empty=new FluidCheckpointCodec.Checkpoint(List.of(),new BufferedTransfers.Snapshot(0,Map.of(),Map.of()));
        var data=new FluidSavedData(empty,WorldTopologyLedger.Snapshot.empty(),key->{throw new AssertionError("No model needed");});
        var tag=data.save(new CompoundTag(),null);assertEquals(FluidCheckpointCodec.VERSION,tag.getInt("FluidFormat"));
        assertTrue(FluidSavedData.load(tag,key->{throw new AssertionError();}).world().isPresent());
        var previous=tag.copy();previous.putInt("TopologyFormat",1);var retained=previous.copy();
        assertThrows(IllegalArgumentException.class,()->FluidSavedData.load(previous,key->{throw new AssertionError();}));assertEquals(retained,previous);
        tag.remove("TopologySHA256");assertThrows(IllegalArgumentException.class,()->FluidSavedData.load(tag,key->{throw new AssertionError();}));
    }
}
