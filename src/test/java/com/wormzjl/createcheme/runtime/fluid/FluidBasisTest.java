package com.wormzjl.createcheme.runtime.fluid;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.wormzjl.createcheme.science.material.*;
import java.util.*;
import org.junit.jupiter.api.*;

class FluidBasisTest {
    @AfterEach void reset(){MaterialRuntime.reset();}
    private static MaterialCatalog reordered() {
        var resources=new HashMap<>(MaterialCatalog.bundled().resources());String path="data/createcheme/materials/bases/network.json";
        var basis=JsonParser.parseString(resources.get(path)).getAsJsonObject();basis.remove("extends");
        var original=MaterialCatalog.bundled().requirePackage("createcheme:tjl20_methane_nitrogen");
        var ids=new ArrayList<>(original.components());var properties=new ArrayList<>(original.properties().stream().map(MaterialCatalog.Property::id).toList());
        Collections.reverse(ids);Collections.reverse(properties);basis.add("components",new Gson().toJsonTree(ids));basis.add("properties",new Gson().toJsonTree(properties));resources.put(path,basis.toString());
        return MaterialCatalog.parse(resources);
    }
    @Test void topologyWithoutAnyIslandStillRejectsAChangedIdentityAxis() {
        var original=MaterialCatalog.bundled();var saved=WorldTopologyLedger.Snapshot.empty(original);
        var decoded=FluidCheckpointCodec.decodeTopology(FluidCheckpointCodec.encodeTopology(saved),saved.onlineTick());assertEquals(saved.basis(),decoded.basis());
        decoded.basis().requireCurrent(original);
        assertThrows(IllegalArgumentException.class,()->decoded.basis().requireCurrent(reordered()));
        // The basis lies near the end of the topology: one cut short of it is refused, never read without it.
        var bytes=FluidCheckpointCodec.encodeTopology(saved);
        assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decodeTopology(Arrays.copyOf(bytes,bytes.length-2),0));
    }
    @Test void capturedAccountingAndSerializationNeverReadTheReloadedGlobalAxis() {
        var basis=new FluidBasis("test:nitrogen",List.of("Nitrogen","Water"),"0".repeat(64)+":"+"0".repeat(64));
        var ledger=new WorldTopologyLedger(new WorldTopologyLedger.Snapshot(0,1,Map.of(),List.of(),WorldTopologyLedger.MaterialTotal.empty(2),WorldTopologyLedger.MaterialTotal.empty(2),basis));
        MaterialRuntime.publish(reordered());ledger.tick();
        var saved=ledger.snapshot();assertEquals(basis,saved.basis());assertEquals(2,saved.constructed().moles().length);
        var next=saved.constructed().plus(List.of(),new double[saved.basis().components().size()]);
        assertArrayEquals(saved.constructed().moles(),next.moles());
        var decoded=FluidCheckpointCodec.decodeTopology(FluidCheckpointCodec.encodeTopology(saved),saved.onlineTick());assertEquals(saved.basis(),decoded.basis());
    }
}
