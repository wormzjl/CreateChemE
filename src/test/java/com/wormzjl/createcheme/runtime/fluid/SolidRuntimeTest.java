package com.wormzjl.createcheme.runtime.fluid;

import com.google.gson.*;
import com.wormzjl.createcheme.fluid.support.FluidTestSupport;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.state.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.createcheme.science.fluid.transport.SolidTransportSettings;
import com.wormzjl.createcheme.science.material.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SolidRuntimeTest {
    private final MaterialCatalog catalog=MaterialCatalog.bundled();
    private final FluidThermodynamics model=FluidTestSupport.networkModel();
    private SolidInventory stock(double mass){
        return new SolidInventory(List.of(new SolidInventory.Population(catalog.solids().require("createcheme:demo_particle"),ParticleSize.micrometres("100"),mass)));
    }
    private FluidCheckpointCodec.Checkpoint checkpoint(PassiveNetwork graph){
        var snapshot=new IslandCoordinator.Snapshot(1,0,graph,new IslandClock.Snapshot(0,0,0,100),FallbackAllowance.NONE,Optional.empty(),Optional.empty(),"READY");
        return new FluidCheckpointCodec.Checkpoint(List.of(new FluidCheckpointCodec.IslandEntry("minecraft:overworld",FluidPresetCatalog.NETWORK_PACKAGE,1e-9,snapshot)),new BufferedTransfers.Snapshot(0,Map.of(),Map.of()));
    }
    @Test void generatorUsesSlurryVolumeFractionWithoutChangingTheFluidAxis(){
        var water=FluidDeviceSpec.water(catalog);
        var spec=new FluidDeviceSpec(1,298.15,101325,water.composition(),SlurryFeed.demo());
        var device=new PhysicalFluidTopology.Device(1,new PhysicalFluidTopology.Position("minecraft:overworld",0,0,0),TopologyCompiler.Kind.GENERATOR,
                PhysicalFluidTopology.Direction.EAST,new PipeResistance.Geometry(1,.05,0,0),new FlowControl.Passive());
        var source=spec.initialize(device,model,()->{}).state();
        assertEquals(1,source.volume(),1e-10);
        assertEquals(.1,source.solidMoments().volume()/(source.liquidVolume()+source.waterVolume()+source.solidMoments().volume()),1e-12);
        assertEquals(250,source.solids().massKg(),1e-8);
        assertEquals(model.componentCount(),spec.composition().length);
    }
    @Test void filterAndParticlesRoundTripAndCapacityChangesDoNotLoseStock(){
        var source=model.initialNitrogenCharge(1,298.15,101325,()->{}).withSolids(stock(3));
        var cake=new InlineFilter(.01,1e6,stock(4),1234);
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,source),new PassiveNetwork.Reservoir(2,0,model.initialNitrogenCharge(1,298.15,101325,()->{}))),
                List.of(new PassiveNetwork.Pipe(3,0,1,new PipeResistance.Geometry(1,.05,0,0)).withFilter(cake)));
        String json=FluidCheckpointCodec.encode(checkpoint(graph),key->model);
        var restored=FluidCheckpointCodec.decode(json,key->model).islands().getFirst().snapshot().graph();
        assertEquals(cake,restored.pipes().getFirst().filter());
        assertEquals(source.solids(),restored.reservoirs().getFirst().inventory().solids());
        var changed=FluidThermodynamics.forNetwork(catalog,FluidPresetCatalog.NETWORK_PACKAGE,1e-9,100,1e-6,new SolidTransportSettings(100,1e-8,12,.0001,2e6));
        var resized=FluidCheckpointCodec.decode(json,key->changed).islands().getFirst().snapshot().graph().pipes().getFirst().filter();
        assertTrue(resized.clogged());assertEquals(cake.captured(),resized.captured());assertEquals(cake.energyJoule(),resized.energyJoule());
        assertEquals(.0001,resized.capacity());assertEquals(2e6,resized.cleanResistance());
    }
    @Test void actualVersionOnePayloadMigratesAdditively() throws Exception{
        var initial=model.initialNitrogenCharge(1,298.15,101325,()->{});
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,initial)),List.of());
        var tag=new FluidSavedData(checkpoint(graph),WorldTopologyLedger.Snapshot.empty(catalog),key->model).save(new CompoundTag(),null);
        var core=JsonParser.parseString(new String(tag.getByteArray("Checkpoint"),StandardCharsets.UTF_8));stripExtensions(core);core.getAsJsonObject().addProperty("version",1);
        byte[] bytes=core.toString().getBytes(StandardCharsets.UTF_8);tag.putInt("FluidFormat",1);tag.putByteArray("Checkpoint",bytes);tag.putByteArray("SHA256",MessageDigest.getInstance("SHA-256").digest(bytes));
        var world=JsonParser.parseString(new String(tag.getByteArray("Topology"),StandardCharsets.UTF_8));stripExtensions(world);
        bytes=world.toString().getBytes(StandardCharsets.UTF_8);tag.putInt("TopologyFormat",2);tag.putByteArray("Topology",bytes);tag.putByteArray("TopologySHA256",MessageDigest.getInstance("SHA-256").digest(bytes));
        var loaded=FluidSavedData.load(tag,key->model);
        assertEquals(graph.reservoirs().getFirst().inventory(),loaded.checkpoint().islands().getFirst().snapshot().graph().reservoirs().getFirst().inventory());
        assertTrue(loaded.world().orElseThrow().recoveries().isEmpty());
        var next=loaded.save(new CompoundTag(),null);assertEquals(2,next.getInt("FluidFormat"));assertEquals(3,next.getInt("TopologyFormat"));
    }
    @Test void solidOnlyParcelsConserveEnergyAndRefuseUndefinedSplits(){
        var parcel=new MaterialParcel(new double[model.componentCount()],model.molecularWeights(),2345,EnergyReference.sensible(model.components()),stock(10));
        var split=parcel.takeMass(3);
        assertEquals(3,split.delivered().massKg());assertEquals(7,split.remainder().massKg());
        assertEquals(parcel.energyJoule(),split.delivered().energyJoule()+split.remainder().energyJoule(),1e-10);
        assertThrows(IllegalArgumentException.class,()->parcel.split(new double[model.componentCount()]));
    }
    @Test void drySolidInventoryCanBeStoredSavedAndRemainStationary(){
        var solids=stock(10);var state=model.solidState(310,101325,solids);
        var inventory=new PassiveNetwork.Inventory(1,new double[model.componentCount()],state.internalEnergy(),solids);
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,state,PassiveNetwork.NodeKind.RESERVOIR,inventory)),List.of());
        var saved=FluidCheckpointCodec.encode(checkpoint(graph),key->model);
        var loaded=FluidCheckpointCodec.decode(saved,key->model).islands().getFirst().snapshot().graph();
        var result=new PassiveIntervalSolver(model).solve(loaded,5,PassiveIntervalSolver.Settings.defaults(),()->{});
        assertEquals(inventory,result.graph().reservoirs().getFirst().inventory());
        assertEquals(310,result.graph().reservoirs().getFirst().state().temperature(),1e-10);
        assertEquals(0,result.graph().reservoirs().getFirst().state().vaporVolume());
    }
    @Test void disconnectedFilterRetainsItsCakeInAZeroFlowIsland(){
        var device=new PhysicalFluidTopology.Device(1,new PhysicalFluidTopology.Position("minecraft:overworld",0,0,0),TopologyCompiler.Kind.FILTER,
                PhysicalFluidTopology.Direction.EAST,new PipeResistance.Geometry(1,.05,0,0),new FlowControl.Passive());
        var cake=new InlineFilter(.01,1e6,stock(4),1234);
        var compiled=PhysicalFluidTopology.compile(List.of(device),Map.of(),Map.of(1L,cake),model.initialNitrogenCharge(1,298.15,101325,()->{}));
        assertEquals(1,compiled.islands().size());
        var result=new PassiveIntervalSolver(model).solve(compiled.islands().getFirst().graph(),1,PassiveIntervalSolver.Settings.defaults(),()->{});
        assertEquals(0,result.averageMassFlows()[0]);assertEquals(cake,result.graph().pipes().getFirst().filter());
        assertTrue(result.graph().reservoirs().stream().noneMatch(n->n.kind()==PassiveNetwork.NodeKind.RESERVOIR));
    }
    @Test void physicalPumpFilterAndNitrogenReceiverStartTogether(){
        var devices=new ArrayList<PhysicalFluidTopology.Device>();
        var kinds=new TopologyCompiler.Kind[]{TopologyCompiler.Kind.GENERATOR,TopologyCompiler.Kind.PUMP,TopologyCompiler.Kind.PIPE,TopologyCompiler.Kind.FILTER,TopologyCompiler.Kind.PIPE,TopologyCompiler.Kind.RESERVOIR};
        for(int i=0;i<kinds.length;i++)devices.add(new PhysicalFluidTopology.Device(i+1,new PhysicalFluidTopology.Position("minecraft:overworld",i,-60,0),kinds[i],PhysicalFluidTopology.Direction.EAST,new PipeResistance.Geometry(1,.05,.000045,0),i==1?new FlowControl.Pump(.01,500000,1):new FlowControl.Passive()));
        var spec=new FluidDeviceSpec(1,298.15,101325,FluidDeviceSpec.water(catalog).composition(),SlurryFeed.demo());
        var source=spec.initialize(devices.getFirst(),model,()->{});
        var sink=FluidDeviceSpec.nitrogen(catalog).initialize(devices.getLast(),model,()->{});
        var compiled=PhysicalFluidTopology.compile(devices,Map.of(1L,source,6L,sink),Map.of(),model.initialNitrogenCharge(1,298.15,101325,()->{}));
        var graph=compiled.islands().getFirst().graph();
        var solver=new PassiveIntervalSolver(model);
        PassiveIntervalSolver.Result result=null;
        for(int interval=0;interval<20;interval++){result=solver.solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});graph=result.graph();}
        assertTrue(result.graph().pipes().stream().filter(p->p.filter()!=null).findFirst().orElseThrow().filter().clogged());
        assertEquals(0,result.averageMassFlows()[0],1e-9);
        assertTrue(result.graph().reservoirs().stream().filter(n->n.id()==6).findFirst().orElseThrow().inventory().solids().empty());
    }
    @Test void unrelatedSolidDefinitionsDoNotChangeTheFluidBasis(){
        var resources=new HashMap<>(catalog.resources());
        resources.put("data/createcheme/materials/solids/another.json","{\"schema_version\":1,\"id\":\"createcheme:another\",\"revision\":\"v1\",\"density_kg_per_m3\":1500,\"heat_capacity_j_per_kg_kelvin\":900}");
        var extended=MaterialCatalog.parse(resources);
        assertEquals(FluidBasis.capture(catalog),FluidBasis.capture(extended));
        extended.solids().validate(stock(1));
        var changed=new HashMap<>(catalog.resources());var key="data/createcheme/materials/solids/demo_particle.json";
        var record=JsonParser.parseString(changed.get(key)).getAsJsonObject();record.addProperty("density_kg_per_m3",2600);changed.put(key,record.toString());
        assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(changed).solids().validate(stock(1)));
    }
    private static void stripExtensions(JsonElement element){
        if(element.isJsonArray()){for(var child:element.getAsJsonArray())stripExtensions(child);}
        else if(element.isJsonObject()){var object=element.getAsJsonObject();for(String key:List.of("solids","solidDirection","solidMasses","blockedDirections","filter","recoveries","recovery"))object.remove(key);for(var e:object.entrySet())stripExtensions(e.getValue());}
    }
}