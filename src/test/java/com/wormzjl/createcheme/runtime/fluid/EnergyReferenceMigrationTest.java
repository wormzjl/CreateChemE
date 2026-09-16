package com.wormzjl.createcheme.runtime.fluid;

import com.google.gson.*;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.state.EnergyReference;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EnergyReferenceMigrationTest {
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane",1e-9);
    private List<String> basis(){var names=new ArrayList<>(model.hydrocarbon.components());names.add("Water");return names;}
    private double[] offsets(){var values=new double[22];values[20]=1000;values[21]=-500;return values;}
    private EnergyReference oldReference(){return new EnergyReference("test:shifted-datum",basis(),offsets(),false);}
    private JsonObject oldReferenceJson(){var value=new JsonObject();value.addProperty("revision","test:shifted-datum");value.add("components",new Gson().toJsonTree(basis()));value.add("offsets",new Gson().toJsonTree(offsets()));value.addProperty("formationQualified",false);return value;}
    private void shift(JsonObject object,String amount,String energy) {double value=object.get(energy).getAsDouble();var n=object.getAsJsonArray(amount);var offsets=offsets();for(int c=0;c<n.size();c++)value=Math.fma(n.get(c).getAsDouble(),offsets[c],value);object.addProperty(energy,value);}
    private void shiftGraph(JsonObject graph){for(var entry:graph.getAsJsonArray("nodes"))shift(entry.getAsJsonObject().getAsJsonObject("inventory"),"moles","internalEnergy");}
    @Test void explicitMigrationPreservesPhysicalStatesSignedBoundaryEnergyPendingRemaindersAndClocks() {
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,model.initialNitrogenCharge(1,298.15,200100,()->{}),PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,0,model.initialNitrogenCharge(1,298.15,200000,()->{})),new PassiveNetwork.Reservoir(3,0,model.initialNitrogenCharge(1,298.15,199900,()->{}),PassiveNetwork.NodeKind.VOID)),List.of(new PassiveNetwork.Pipe(4,0,1,new PipeResistance.Geometry(10,.02,.000045,0)),new PassiveNetwork.Pipe(5,1,2,new PipeResistance.Geometry(10,.02,.000045,0))));
        var full=new PassiveIntervalSolver(model).solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});
        var island=new IslandCoordinator.Snapshot(1,7,full.graph(),new IslandClock.Snapshot(350,200,400,100),new FallbackAllowance(2,100,100),Optional.of(ApproximationAnchor.fromFull(model,full)),Optional.of(full),"HELD",Map.of(UUID.randomUUID(),300L));
        double[] n=new double[22],weights=new double[22];for(int c=0;c<22;c++)weights[c]=c==21?model.waterMolecularWeight:model.hydrocarbon.molecularWeight(c);n[20]=.02/weights[20];n[21]=.08/weights[21];
        var material=new MaterialParcel(n,weights,model.flashTP(350,200000,n,()->{}).enthalpy(),EnergyReference.sensible(basis()));var receiver=UUID.randomUUID();var transfer=UUID.randomUUID();
        var buffer=new BufferedTransfers.Buffer(receiver,100,2,Map.of(transfer,material.massKg()));var pending=new PendingTransfers.Pending(transfer,UUID.randomUUID(),receiver,100,1,material);
        var checkpoint=new FluidCheckpointCodec.Checkpoint(List.of(new FluidCheckpointCodec.IslandEntry("minecraft:overworld","createcheme:tjl20_methane",1e-9,island)),new BufferedTransfers.Snapshot(3,Map.of(receiver,buffer),Map.of(transfer,pending)));
        String original=FluidCheckpointCodec.encode(checkpoint,key->model);var shifted=JsonParser.parseString(original).getAsJsonObject();
        var saved=shifted.getAsJsonArray("islands").get(0).getAsJsonObject();saved.add("reference",oldReferenceJson());shiftGraph(saved.getAsJsonObject("graph"));shiftGraph(saved.getAsJsonObject("anchor").getAsJsonObject("graph"));
        for(var boundary:saved.getAsJsonObject("history").getAsJsonArray("boundaries"))shift(boundary.getAsJsonObject(),"moles","totalEnergyJoule");
        var remainder=shifted.getAsJsonObject("transfers").getAsJsonArray("pending").get(0).getAsJsonObject().getAsJsonObject("remaining");shift(remainder,"moles","energy");remainder.add("reference",oldReferenceJson());
        String before=shifted.toString();assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(before,key->model));
        var restored=FluidCheckpointCodec.migrateToSensibleReference(before,oldReference(),key->model);assertEquals(before,shifted.toString());
        var actual=restored.islands().getFirst().snapshot();assertEquals(island.clock(),actual.clock());assertEquals(island.allowance(),actual.allowance());assertEquals(island.fences(),actual.fences());
        for(int i=0;i<3;i++){var a=island.graph().reservoirs().get(i);var b=actual.graph().reservoirs().get(i);assertArrayEquals(a.inventory().moles(),b.inventory().moles());assertEquals(a.inventory().internalEnergy(),b.inventory().internalEnergy(),1e-8);assertEquals(a.state().temperature(),b.state().temperature());assertEquals(a.state().pressure(),b.state().pressure());}
        var oldBoundaries=full.boundaries();var newBoundaries=actual.lastResult().orElseThrow().boundaries();assertEquals(oldBoundaries.size(),newBoundaries.size());
        assertTrue(oldBoundaries.stream().anyMatch(b->b.moles()[20]<0));for(int i=0;i<oldBoundaries.size();i++)assertEquals(oldBoundaries.get(i).totalEnergyJoule(),newBoundaries.get(i).totalEnergyJoule(),1e-8);
        var retained=restored.transfers().pending().get(transfer);assertEquals(1,retained.revision());assertArrayEquals(material.moles(),retained.remaining().moles());assertEquals(material.energyJoule(),retained.remaining().energyJoule(),1e-8);
        assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.migrateToSensibleReference(before,EnergyReference.sensible(basis()),key->model));
        assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.migrateToSensibleReference(before,oldReference(),key->FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),key.packageId(),2e-9)));
    }
    @Test void nativeSaveMigrationRebasesHistoricalConstructionAndDestructionWithoutWritingOrMutatingInput() throws Exception {
        var checkpoint=new FluidCheckpointCodec.Checkpoint(List.of(),new BufferedTransfers.Snapshot(0,Map.of(),Map.of()));
        var tag=new CompoundTag();byte[] core=FluidCheckpointCodec.encode(checkpoint,key->model).getBytes(StandardCharsets.UTF_8);tag.putInt("FluidFormat",1);tag.putByteArray("Checkpoint",core);tag.putByteArray("SHA256",MessageDigest.getInstance("SHA-256").digest(core));
        var n=new double[22];n[20]=10;var oldTotal=new WorldTopologyLedger.MaterialTotal(n,10050);
        var world=new WorldTopologyLedger.Snapshot(500,1,Map.of(),List.of(),oldTotal,oldTotal);byte[] topology=FluidCheckpointCodec.encodeWorld(world).getBytes(StandardCharsets.UTF_8);
        tag.putInt("TopologyFormat",1);tag.putByteArray("Topology",topology);tag.putByteArray("TopologySHA256",MessageDigest.getInstance("SHA-256").digest(topology));var before=tag.copy();
        var migrated=FluidSavedData.migrateToSensibleReference(tag,oldReference(),key->model);assertEquals(before,tag);var restored=migrated.world().orElseThrow();assertEquals(50,restored.constructed().totalEnergy());assertEquals(50,restored.destroyed().totalEnergy());assertEquals(500,restored.onlineTick());assertArrayEquals(n,restored.constructed().moles());
        var corrupt=tag.copy();corrupt.putByteArray("SHA256",new byte[32]);assertThrows(IllegalArgumentException.class,()->FluidSavedData.migrateToSensibleReference(corrupt,oldReference(),key->model));
    }
}
