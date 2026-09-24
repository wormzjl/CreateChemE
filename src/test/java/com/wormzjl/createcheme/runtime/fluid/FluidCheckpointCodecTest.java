package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.state.EnergyReference;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import net.minecraft.nbt.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Checkpoint format 4, one island: round trip, field refusals, a changed basis, energy datum or velocity limit. */
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
}
