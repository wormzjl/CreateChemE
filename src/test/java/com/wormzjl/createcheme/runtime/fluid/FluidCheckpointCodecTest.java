package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.state.EnergyReference;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import net.minecraft.nbt.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Checkpoint format 6, one island: round trip, field refusals, a changed basis, energy datum or velocity limit. */
class FluidCheckpointCodecTest {
    private static final String PACKAGE="createcheme:tjl20_methane_nitrogen";
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),PACKAGE,1e-9);
    private FluidCheckpointCodec.Checkpoint fixture() {
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,model.initialNitrogenCharge(1,298.15,101325,()->{}))),List.of());
        var full=new PassiveIntervalSolver(model).solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});
        var state=new IslandCoordinator.Snapshot(1,4,full.graph(),new IslandClock.Snapshot(350,200,400,100),new FallbackAllowance(2,100,100),Optional.of(ApproximationAnchor.fromFull(model,full)),Optional.of(full),"APPROXIMATE",Map.of(UUID.randomUUID(),300L));
        var buffer=UUID.randomUUID();var id=UUID.randomUUID();var material=new MaterialParcel(new double[]{2000,2000},new double[]{.018,.032},123456,EnergyReference.sensible(List.of("Water","Oxygen")));
        var ledger=new BufferedTransfers(new BufferedTransfers.Snapshot(0,Map.of(buffer,new BufferedTransfers.Buffer(buffer,100,0,Map.of())),Map.of()));
        ledger.commit(ledger.reserve(List.of(new PendingTransfers.Pending(id,UUID.randomUUID(),buffer,200,0,material))));
        ledger.commit(ledger.deliver(200,List.of(new BufferedTransfers.Feasible(id,0,20))));
        return new FluidCheckpointCodec.Checkpoint(List.of(new FluidCheckpointCodec.IslandEntry("minecraft:overworld",PACKAGE,1e-9,state)),ledger.snapshot());
    }
    private static CompoundTag islands(CompoundTag core){return core.getCompound("Islands");}
    static void assertSameImage(FluidCheckpointCodec.Image a,FluidCheckpointCodec.Image b,String what) {
        assertEquals(a.core(),b.core(),what+": core record");assertEquals(a.packs().keySet(),b.packs().keySet(),what+": packs");
        for(var pack:a.packs().keySet())assertArrayEquals(a.packs().get(pack),b.packs().get(pack),what+": pack "+pack);
    }
    private static FluidCheckpointCodec.Image copy(FluidCheckpointCodec.Image image){return new FluidCheckpointCodec.Image(image.core().copy(),image.packs());}

    @Test void roundTripRetainsStockDebtFencesFallbackAndTheUndeliveredEightyKg() {
        var before=fixture();var image=FluidCheckpointCodec.encode(before,key->model);
        assertEquals(FluidCheckpointCodec.VERSION,image.core().getInt("FluidFormat"));assertEquals(350,FluidCheckpointCodec.epoch(image.core()),"epoch of a core-only checkpoint: its most advanced island");
        var loaded=FluidCheckpointCodec.decode(image,key->model);var a=before.islands().getFirst().snapshot();var b=loaded.islands().getFirst().snapshot();
        assertEquals(a.graph().reservoirs().getFirst().inventory(),b.graph().reservoirs().getFirst().inventory());assertEquals(a.clock(),b.clock());assertEquals(a.fences(),b.fences());assertEquals(a.allowance(),b.allowance());
        assertEquals(a.anchor().orElseThrow().propertyRevision(),b.anchor().orElseThrow().propertyRevision());assertEquals(a.revision(),b.revision());assertEquals(a.status(),b.status());
        assertSame(b.graph(),b.anchor().orElseThrow().graph(),"the anchor is stored as the island's own graph");
        var remainder=loaded.transfers().pending().values().iterator().next();assertEquals(80,remainder.remaining().massKg());assertEquals(1,remainder.revision());
        assertEquals(20,loaded.transfers().buffers().get(remainder.receiver()).occupiedKg());assertEquals(80,loaded.transfers().buffers().get(remainder.receiver()).reservedKg());
        assertSameImage(image,FluidCheckpointCodec.encode(loaded,key->model),"a reloaded checkpoint re-encodes to the same bytes");
    }
    @Test void missingClocksMistypedStampsCorruptionUnknownVersionsAndChangedPropertiesAreRejected() {
        var image=FluidCheckpointCodec.encode(fixture(),key->model);
        var missing=copy(image);islands(missing.core()).remove("Committed");
        assertTrue(assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(missing,key->model)).getMessage().contains("field Committed"));
        var mistyped=copy(image);islands(mistyped.core()).putDouble("Revision",1.5);
        assertTrue(assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(mistyped,key->model)).getMessage().contains("field Revision"));
        var corrupt=copy(image);islands(corrupt.core()).getLongArray("Committed")[0]=250;
        assertTrue(assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(corrupt,key->model)).getMessage().contains("checksum"),"an unsealed change is caught by the envelope digest");
        var packs=new HashMap<>(image.packs());var bytes=packs.get(1L).clone();bytes[bytes.length/2]^=1;packs.put(1L,bytes);
        assertTrue(assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(new FluidCheckpointCodec.Image(image.core(),packs),key->model)).getMessage().contains("checksum"),"a flipped unit byte");
        var version=copy(image);version.core().putInt("FluidFormat",999);
        assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(version,key->model));
        var changed=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),PACKAGE,2e-9);
        assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(image,key->changed));
    }
    @Test void aChangedThermodynamicBasisRequiresANewWorldAndNeverReinterpretsStock() {
        var image=FluidCheckpointCodec.encode(fixture(),key->model);
        var retired=copy(image);
        ((ListTag)retired.core().get("Packages")).getCompound(0).putString("PropertyRevision","fluid-trbdf2-r1:fluid-nitrogen-r1:7781afaaad17de4926015ebf321c0b106766dd5c131668f54962a8825ed25bd5:fluid-shared-k-v1:nist-liquid-calibration-20260915:k=0x1.12e0be826d695p-30:cb6773ed32b52aefb268f9c5610e288c8cecdbc6c682522b54d0be703c92b533:log-liquid-wilke-v1:dwsim-conditional-solute-v1");
        FluidCheckpointCodec.reseal(retired.core());
        var refusal=assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(retired,key->model));
        assertTrue(refusal.getMessage().contains("fresh development world"));
        for(String field:List.of("ideal_gas_cp","viscosity")) {
            var resources=new HashMap<>(MaterialCatalog.bundled().resources());
            String path="data/createcheme/materials/properties/crude_pc04.json";
            var property=com.google.gson.JsonParser.parseString(resources.get(path)).getAsJsonObject();
            var correlation=property.getAsJsonObject(field);
            if(field.equals("viscosity"))correlation=correlation.getAsJsonObject("liquid");
            var coefficients=correlation.getAsJsonArray("coefficients");
            coefficients.set(0,new com.google.gson.JsonPrimitive(coefficients.get(0).getAsDouble()*1.001));
            resources.put(path,property.toString());
            var overridden=FluidThermodynamics.forNetwork(MaterialCatalog.parse(resources),PACKAGE,1e-9);
            assertTrue(assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(image,key->overridden),field).getMessage().contains("fresh development world"),field);
        }
    }
    @Test void anEnergyDatumChangeIsRefused() {
        var image=FluidCheckpointCodec.encode(fixture(),key->model);
        var revision=copy(image);var entry=((ListTag)revision.core().get("Packages")).getCompound(0);entry.putString("EnergyRevision",entry.getString("EnergyRevision")+"-offset");FluidCheckpointCodec.reseal(revision.core());
        assertTrue(assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(revision,key->model)).getMessage().contains("fresh development world"));
        var components=copy(image);((ListTag)((ListTag)components.core().get("Packages")).getCompound(0).get("EnergyComponents")).remove(0);FluidCheckpointCodec.reseal(components.core());
        assertTrue(assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(components,key->model)).getMessage().contains("fresh development world"));
    }
    /** The topology unit: every field of the world ledger comes back exactly and re-encodes to the same bytes. */
    @Test void aTopologyRoundTripsEveryFieldExactlyAndRefusesAMalformedUnit() {
        var empty=com.wormzjl.createcheme.runtime.fluid.WorldTopologyLedger.Snapshot.empty(MaterialCatalog.bundled());int count=empty.basis().components().size();
        var nitrogen=new double[count];nitrogen[com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN]=1;
        var mixed=new double[count];for(int c=0;c<count;c++)mixed[c]=c+1;
        var slurry=new SlurryFeed(.1,List.of(new SlurryFeed.Grade("createcheme:demo_particle",com.wormzjl.createcheme.science.fluid.state.ParticleSize.micrometres("100"),1)));
        var block=new PipeResistance.Geometry(1,.05,.000045,0);var wide=new PipeResistance.Geometry(2,.1,.0001,.5);
        java.util.function.Function<Object[],WorldTopologyLedger.Registration> device=a->new WorldTopologyLedger.Registration(new PhysicalFluidTopology.Device((long)a[0],
                new PhysicalFluidTopology.Position((String)a[1],(int)a[2],64,-3),(com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind)a[3],PhysicalFluidTopology.Direction.EAST,(PipeResistance.Geometry)a[4],(FlowControl)a[5]),
                (FluidDeviceSpec)a[6],(long)a[7]);
        var kinds=com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind.class.getEnumConstants();
        var reservoir=device.apply(new Object[]{1L,"minecraft:overworld",0,kinds[0],block,new FlowControl.Passive(),new FluidDeviceSpec(2,300,150000,nitrogen),0L});
        var pump=device.apply(new Object[]{2L,"minecraft:overworld",1,com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind.PUMP,block,new FlowControl.Pump(.01,500000,.75),new FluidDeviceSpec(1,298.15,101325,nitrogen),0L});
        var valve=device.apply(new Object[]{3L,"minecraft:overworld",2,com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind.VALVE,wide,new FlowControl.PressureValve(120000),new FluidDeviceSpec(1,298.15,101325,nitrogen),0L});
        var pipe=device.apply(new Object[]{4L,"minecraft:the_nether",5,com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind.PIPE,wide,new FlowControl.Passive(),new FluidDeviceSpec(1,298.15,101325,mixed),0L});
        var generator=device.apply(new Object[]{5L,"minecraft:overworld",7,com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind.GENERATOR,block,new FlowControl.Passive(),new FluidDeviceSpec(1,320,180000,mixed,slurry),0L});
        var retuned=device.apply(new Object[]{3L,"minecraft:overworld",2,com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind.VALVE,wide,new FlowControl.PressureValve(130000),new FluidDeviceSpec(1,298.15,101325,nitrogen),1L});
        var player=UUID.randomUUID();
        var events=List.of(new WorldTopologyLedger.Event(UUID.randomUUID(),10,List.of(new WorldTopologyLedger.Edit(3,retuned),new WorldTopologyLedger.Edit(4,null)),Set.of(2L,3L,4L)),
                new WorldTopologyLedger.Event(UUID.randomUUID(),12,List.of(new WorldTopologyLedger.Edit(2,null)),Set.of(2L),new WorldTopologyLedger.Recovery(2,player)));
        var particle=new com.wormzjl.createcheme.science.material.SolidMaterial("createcheme:demo_particle","r1",2500,800);
        var solids=new com.wormzjl.createcheme.science.fluid.state.SolidInventory(List.of(new com.wormzjl.createcheme.science.fluid.state.SolidInventory.Population(particle,com.wormzjl.createcheme.science.fluid.state.ParticleSize.micrometres("100"),.25)));
        var recoveries=Map.of(UUID.randomUUID(),new RecoveredSolid(new PhysicalFluidTopology.Position("minecraft:overworld",3,65,-3),player,solids,12.5),
                UUID.randomUUID(),new RecoveredSolid(new PhysicalFluidTopology.Position("minecraft:the_nether",9,70,1),null,solids,-4));
        var moles=new double[count];moles[0]=3.5;moles[count-1]=-0.0+1e-9;
        var constructed=new WorldTopologyLedger.MaterialTotal(moles,4.25e6,Map.of("createcheme:demo_particle",.75));
        var world=new WorldTopologyLedger.Snapshot(12,9,Map.of(1L,reservoir,2L,pump,3L,valve,4L,pipe,5L,generator),events,constructed,WorldTopologyLedger.MaterialTotal.empty(count),empty.basis(),recoveries);
        var bytes=FluidCheckpointCodec.encodeTopology(world);var decoded=FluidCheckpointCodec.decodeTopology(bytes,77);
        assertEquals(77,decoded.onlineTick());assertEquals(9,decoded.nextIdentity());
        assertEquals(world.active(),decoded.active(),"devices, positions, kinds, geometries, controls, specs, slurries, revisions");
        assertEquals(world.events(),decoded.events(),"events: edits, removals, touched sets, recovery");
        assertArrayEquals(world.constructed().moles(),decoded.constructed().moles());assertEquals(world.constructed().totalEnergy(),decoded.constructed().totalEnergy());
        assertEquals(world.constructed().solidMasses(),decoded.constructed().solidMasses());assertArrayEquals(world.destroyed().moles(),decoded.destroyed().moles());
        assertEquals(world.basis(),decoded.basis());assertEquals(world.recoveries(),decoded.recoveries());
        assertArrayEquals(bytes,FluidCheckpointCodec.encodeTopology(decoded),"a decoded topology re-encodes to the same bytes");
        assertTrue(bytes.length<2_000,"binary topology of five devices: "+bytes.length+" bytes");
        assertTrue(assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decodeTopology(Arrays.copyOf(bytes,bytes.length+1),0)).getMessage().contains("trailing bytes"));
        assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decodeTopology(Arrays.copyOf(bytes,bytes.length/2),0));
    }
    @Test void changingVelocityLimitPreservesSavedStockClocksAndAllowanceButInvalidatesTheOldAnchor() {
        var before=fixture();var image=FluidCheckpointCodec.encode(before,key->model);
        var loaded=FluidCheckpointCodec.decode(image,key->FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),PACKAGE,1e-9,10));
        var a=before.islands().getFirst().snapshot();var b=loaded.islands().getFirst().snapshot();
        assertEquals(a.graph().reservoirs().getFirst().inventory(),b.graph().reservoirs().getFirst().inventory());assertEquals(a.clock(),b.clock());assertEquals(a.allowance(),b.allowance());assertEquals(a.fences(),b.fences());
        assertThrows(ApproximationRejected.class,()->b.anchor().orElseThrow().guard(FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),PACKAGE,1e-9,10),b.graph()));
        // An anchor recorded under the thermodynamic revision alone does not carry the hydraulic acceptance law.
        var thermodynamicOnly=FluidCheckpointCodec.withIsland(image,1,key->model,entry->{
            var s=entry.snapshot();var anchor=s.anchor().orElseThrow();
            return new FluidCheckpointCodec.IslandEntry(entry.dimension(),entry.packageId(),entry.compressibility(),new IslandCoordinator.Snapshot(s.id(),s.revision(),s.graph(),s.clock(),s.allowance(),
                    Optional.of(new ApproximationAnchor(ApproximationAnchor.thermodynamicRevision(model),anchor.graph(),anchor.modes())),s.lastResult(),s.status(),s.fences()));
        });
        var restored=FluidCheckpointCodec.decode(thermodynamicOnly,key->model).islands().getFirst().snapshot();
        assertEquals(a.clock(),restored.clock());assertEquals(a.allowance(),restored.allowance());assertEquals(a.graph().reservoirs().getFirst().inventory(),restored.graph().reservoirs().getFirst().inventory());
        assertThrows(ApproximationRejected.class,()->restored.anchor().orElseThrow().guard(model,restored.graph()));
    }

    // ---------------- format 6: pipe ports and the compressor (phase-ports WP5, plan 4.4) ----------------

    private int component(String name){int i=model.components().indexOf(name);assertTrue(i>=0,name);return i;}
    private FluidThermodynamics.State waterUnderNitrogen(double waterVolume,double pressure) {
        double t=298.15;double[] mw=model.molecularWeights();double[] n=new double[mw.length];int water=component("Water");
        n[water]=waterVolume*997/mw[water];n[component("Nitrogen")]=pressure*(1-waterVolume)/(FluidThermodynamics.R*t);
        var unit=model.flashTP(t,pressure,n,()->{});for(int c=0;c<n.length;c++)n[c]/=unit.volume();
        return model.flashTP(t,pressure,n,()->{});
    }
    /**
     * An island whose pipes have every port kind and a compressor, solved for one interval in which the compressor, fed
     * the bulk of a half-water tank, is refused ({@code INLET_WRONG_PHASE}): graph, ports, controls, the last interval's
     * endpoint modes and the anchor come back exactly, and the reloaded checkpoint re-encodes to the same bytes.
     */
    @Test void portsACompressorAndARefusedMoverRoundTripExactly() {
        var block=new PipeResistance.Geometry(1,.05,.000045,0);
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,waterUnderNitrogen(.5,150000)),new PassiveNetwork.Reservoir(2,0,model.initialNitrogenCharge(1,298.15,150000,()->{}),PassiveNetwork.NodeKind.JUNCTION),
                new PassiveNetwork.Reservoir(3,0,model.initialNitrogenCharge(1,298.15,101325,()->{}),PassiveNetwork.NodeKind.VOID));
        var pipes=List.of(new PassiveNetwork.Pipe(10,0,2,List.of(block),new FlowControl.Passive(),0,null,PassiveNetwork.PhasePort.LIQUID,PassiveNetwork.PhasePort.BULK),
                new PassiveNetwork.Pipe(11,0,1,List.of(block),new FlowControl.Passive(),0,null,PassiveNetwork.PhasePort.BULK,PassiveNetwork.PhasePort.BULK),
                new PassiveNetwork.Pipe(12,1,2,List.of(block),new FlowControl.Compressor(.01,2.5,.9),0,null,PassiveNetwork.PhasePort.BULK,PassiveNetwork.PhasePort.BULK),
                new PassiveNetwork.Pipe(13,0,2,List.of(block),new FlowControl.Passive(),0,null,PassiveNetwork.PhasePort.VAPOR,PassiveNetwork.PhasePort.BULK));
        var full=new PassiveIntervalSolver(model).solve(new PassiveNetwork(nodes,pipes),5,PassiveIntervalSolver.Settings.defaults(),()->{});
        assertEquals(FlowControl.Mode.INLET_WRONG_PHASE,full.endpointModes().get(2),"the compressor on the tank's bulk is refused");
        var state=new IslandCoordinator.Snapshot(1,4,full.graph(),new IslandClock.Snapshot(350,200,400,100),new FallbackAllowance(2,100,100),Optional.of(ApproximationAnchor.fromFull(model,full)),Optional.of(full),"FULL");
        var before=new FluidCheckpointCodec.Checkpoint(List.of(new FluidCheckpointCodec.IslandEntry("minecraft:overworld",PACKAGE,1e-9,state)),new BufferedTransfers.Snapshot(0,Map.of(),Map.of()));
        var image=FluidCheckpointCodec.encode(before,key->model);assertEquals(6,image.core().getInt("FluidFormat"));
        var after=FluidCheckpointCodec.decode(image,key->model).islands().getFirst().snapshot();
        assertEquals(full.graph().pipes(),after.graph().pipes(),"ends, sections, controls, blocked masks and the ports of every pipe");
        assertEquals(PassiveNetwork.PhasePort.LIQUID,after.graph().pipes().get(0).firstPort());assertEquals(PassiveNetwork.PhasePort.VAPOR,after.graph().pipes().get(3).firstPort());
        assertEquals(new FlowControl.Compressor(.01,2.5,.9),after.graph().pipes().get(2).control());
        assertEquals(full.endpointModes(),after.lastResult().orElseThrow().endpointModes(),"the refused mover's committed mode, the hysteresis input of the next slice (A38)");
        assertArrayEquals(full.averageMassFlows(),after.lastResult().orElseThrow().averageMassFlows());
        assertEquals(full.graph().pipes(),after.anchor().orElseThrow().graph().pipes());
        assertSameImage(image,FluidCheckpointCodec.encode(FluidCheckpointCodec.decode(image,key->model),key->model),"a reloaded format-6 checkpoint re-encodes to the same bytes");
    }
    /** The world ledger's compressor registration (control tag 3) comes back exactly; an unknown control tag is refused. */
    @Test void aCompressorRegistrationRoundTripsInTheTopologyUnit() {
        var empty=WorldTopologyLedger.Snapshot.empty(MaterialCatalog.bundled());int count=empty.basis().components().size();
        var nitrogen=new double[count];nitrogen[com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN]=1;
        var device=new PhysicalFluidTopology.Device(4,new PhysicalFluidTopology.Position("minecraft:overworld",3,64,-3),com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind.COMPRESSOR,
                PhysicalFluidTopology.Direction.UP,new PipeResistance.Geometry(1,.05,.000045,0),new FlowControl.Compressor(.05,3,1));
        var world=new WorldTopologyLedger.Snapshot(12,9,Map.of(4L,new WorldTopologyLedger.Registration(device,new FluidDeviceSpec(1,298.15,101325,nitrogen),2)),List.of(),
                WorldTopologyLedger.MaterialTotal.empty(count),WorldTopologyLedger.MaterialTotal.empty(count),empty.basis(),Map.of());
        var bytes=FluidCheckpointCodec.encodeTopology(world);var decoded=FluidCheckpointCodec.decodeTopology(bytes,12);
        assertEquals(world.active(),decoded.active());assertArrayEquals(bytes,FluidCheckpointCodec.encodeTopology(decoded));
        // The control tag follows the facing byte and the geometry reference; find the one byte 3 whose change to 4 is refused.
        boolean refused=false;
        for(int i=0;i<bytes.length&&!refused;i++)if(bytes[i]==3){var corrupt=bytes.clone();corrupt[i]=4;
            try{FluidCheckpointCodec.decodeTopology(corrupt,12);}catch(IllegalArgumentException e){refused=e.getMessage().contains("unknown control");}}
        assertTrue(refused,"an unknown control tag is refused");
    }
    /**
     * The JSON graph shape (archived gameplay captures) with the format-6 fields: every pipe names its two ports, and a
     * "compressor" control reads target, limit (the ratio) and efficiency. A pipe without its ports is refused (no absent-field
     * default), and so is a phase port on a node that is not a tank.
     */
    @Test void theJsonGraphShapeReadsPortsAndACompressorAndRefusesAPipeWithoutPorts() throws Exception {
        com.google.gson.JsonObject graph;
        try(var input=Objects.requireNonNull(getClass().getResourceAsStream("/fluid/mcp-held-drain-checkpoint.json"))) {
            graph=com.google.gson.JsonParser.parseString(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("islands").get(0).getAsJsonObject().getAsJsonObject("graph");
        }
        for(var node:graph.getAsJsonArray("nodes")){var empty=new com.google.gson.JsonObject();empty.add("populations",new com.google.gson.JsonArray());node.getAsJsonObject().getAsJsonObject("inventory").add("solids",empty);}
        var pipes=graph.getAsJsonArray("pipes");
        for(var pipe:pipes){var p=pipe.getAsJsonObject();p.addProperty("blockedDirections",0);p.add("filter",com.google.gson.JsonNull.INSTANCE);p.addProperty("firstPort","BULK");p.addProperty("secondPort","BULK");}
        // Pipe 0 runs from the tank (node 0) to the void, pipe 1 from the tank to the generator.
        pipes.get(0).getAsJsonObject().addProperty("firstPort","LIQUID");
        var compressor=new com.google.gson.JsonObject();compressor.addProperty("kind","compressor");compressor.addProperty("target",.02);compressor.addProperty("limit",2.5);compressor.addProperty("efficiency",.9);
        pipes.get(1).getAsJsonObject().add("control",compressor);pipes.get(1).getAsJsonObject().addProperty("firstPort","VAPOR");
        var decoded=FluidCheckpointCodec.decodeGraph(graph,model);
        assertEquals(PassiveNetwork.PhasePort.LIQUID,decoded.pipes().get(0).firstPort());assertEquals(PassiveNetwork.PhasePort.BULK,decoded.pipes().get(0).secondPort());
        assertEquals(PassiveNetwork.PhasePort.VAPOR,decoded.pipes().get(1).firstPort());assertEquals(new FlowControl.Compressor(.02,2.5,.9),decoded.pipes().get(1).control());
        var generatorPort=graph.deepCopy();generatorPort.getAsJsonArray("pipes").get(1).getAsJsonObject().addProperty("secondPort","LIQUID");
        assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decodeGraph(generatorPort,model),"a phase port on the generator's end");
        var withoutPorts=graph.deepCopy();withoutPorts.getAsJsonArray("pipes").get(0).getAsJsonObject().remove("secondPort");
        assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decodeGraph(withoutPorts,model),"a pipe without its ports");
    }
}
