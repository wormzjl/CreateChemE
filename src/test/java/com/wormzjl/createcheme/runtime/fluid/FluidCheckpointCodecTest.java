package com.wormzjl.createcheme.runtime.fluid;

import com.google.gson.JsonParser;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.state.EnergyReference;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

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
    @Test void roundTripRetainsStockDebtFencesFallbackAndTheUndeliveredEightyKg() {
        var before=fixture();String text=FluidCheckpointCodec.encode(before,key->model);
        var loaded=FluidCheckpointCodec.decode(text,key->model);var a=before.islands().getFirst().snapshot();var b=loaded.islands().getFirst().snapshot();
        assertEquals(a.graph().reservoirs().getFirst().inventory(),b.graph().reservoirs().getFirst().inventory());assertEquals(a.clock(),b.clock());assertEquals(a.fences(),b.fences());assertEquals(a.allowance(),b.allowance());
        assertEquals(a.anchor().orElseThrow().propertyRevision(),b.anchor().orElseThrow().propertyRevision());
        var remainder=loaded.transfers().pending().values().iterator().next();assertEquals(80,remainder.remaining().massKg());assertEquals(1,remainder.revision());
        assertEquals(20,loaded.transfers().buffers().get(remainder.receiver()).occupiedKg());assertEquals(80,loaded.transfers().buffers().get(remainder.receiver()).reservedKg());
        assertEquals(text,FluidCheckpointCodec.encode(loaded,key->model));
    }
    @Test void missingClocksFractionalStampsUnknownVersionsAndChangedPropertiesAreRejected() {
        String text=FluidCheckpointCodec.encode(fixture(),key->model);
        var missing=JsonParser.parseString(text).getAsJsonObject();missing.getAsJsonArray("islands").get(0).getAsJsonObject().getAsJsonObject("clock").remove("committedTick");
        assertThrows(RuntimeException.class,()->FluidCheckpointCodec.decode(missing.toString(),key->model));
        var fractional=JsonParser.parseString(text).getAsJsonObject();fractional.getAsJsonArray("islands").get(0).getAsJsonObject().addProperty("revision",1.5);
        assertThrows(RuntimeException.class,()->FluidCheckpointCodec.decode(fractional.toString(),key->model));
        var version=JsonParser.parseString(text).getAsJsonObject();version.addProperty("version",999);
        assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(version.toString(),key->model));
        var changed=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),PACKAGE,2e-9);
        assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(text,key->changed));
    }
    @Test void retiredAmbientBasisRequiresExplicitNewWorldAndNeverAutoMigratesStock() {
        var before=fixture();
        var tree=JsonParser.parseString(FluidCheckpointCodec.encode(before,key->model)).getAsJsonObject();
        var island=tree.getAsJsonArray("islands").get(0).getAsJsonObject();
        island.addProperty("propertyRevision","fluid-trbdf2-r1:fluid-nitrogen-r1:7781afaaad17de4926015ebf321c0b106766dd5c131668f54962a8825ed25bd5:fluid-shared-k-v1:nist-liquid-calibration-20260915:k=0x1.12e0be826d695p-30:cb6773ed32b52aefb268f9c5610e288c8cecdbc6c682522b54d0be703c92b533:log-liquid-wilke-v1:dwsim-conditional-solute-v1");
        island.getAsJsonObject("anchor").addProperty("revision",island.get("propertyRevision").getAsString());
        String legacy=tree.toString();
        var refusal=assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(legacy,key->model));
        assertTrue(refusal.getMessage().contains("fresh development world"));
        var changed=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),PACKAGE,2e-9);
        assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(legacy,key->changed));
        for(String field:List.of("ideal_gas_cp","viscosity")) {
            var resources=new HashMap<>(MaterialCatalog.bundled().resources());
            String path="data/createcheme/materials/properties/crude_pc04.json";
            var property=JsonParser.parseString(resources.get(path)).getAsJsonObject();
            var correlation=property.getAsJsonObject(field);
            if(field.equals("viscosity"))correlation=correlation.getAsJsonObject("liquid");
            var coefficients=correlation.getAsJsonArray("coefficients");
            coefficients.set(0,new com.google.gson.JsonPrimitive(coefficients.get(0).getAsDouble()*1.001));
            resources.put(path,property.toString());
            var overridden=FluidThermodynamics.forNetwork(MaterialCatalog.parse(resources),PACKAGE,1e-9);
            assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(legacy,key->overridden),field);
        }
    }
    @Test void anEnergyDatumChangeRequiresExplicitMigration() {
        var tree=JsonParser.parseString(FluidCheckpointCodec.encode(fixture(),key->model)).getAsJsonObject();
        tree.getAsJsonArray("islands").get(0).getAsJsonObject().getAsJsonObject("reference").getAsJsonArray("offsets").set(20,new com.google.gson.JsonPrimitive(1000));
        assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(tree.toString(),key->model));
    }
    @Test void changingVelocityLimitPreservesSavedStockClocksAndAllowanceButInvalidatesTheOldAnchor() {
        var before=fixture();var loaded=FluidCheckpointCodec.decode(FluidCheckpointCodec.encode(before,key->model),key->FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),PACKAGE,1e-9,10));
        var a=before.islands().getFirst().snapshot();var b=loaded.islands().getFirst().snapshot();
        assertEquals(a.graph().reservoirs().getFirst().inventory(),b.graph().reservoirs().getFirst().inventory());assertEquals(a.clock(),b.clock());assertEquals(a.allowance(),b.allowance());assertEquals(a.fences(),b.fences());
        assertThrows(ApproximationRejected.class,()->b.anchor().orElseThrow().guard(FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),PACKAGE,1e-9,10),b.graph()));
        var legacy=JsonParser.parseString(FluidCheckpointCodec.encode(before,key->model)).getAsJsonObject();
        legacy.getAsJsonArray("islands").get(0).getAsJsonObject().getAsJsonObject("anchor").addProperty("revision",ApproximationAnchor.thermodynamicRevision(model));
        var restored=FluidCheckpointCodec.decode(legacy.toString(),key->model).islands().getFirst().snapshot();
        assertEquals(a.clock(),restored.clock());assertEquals(a.allowance(),restored.allowance());assertEquals(a.graph().reservoirs().getFirst().inventory(),restored.graph().reservoirs().getFirst().inventory());
        assertThrows(ApproximationRejected.class,()->restored.anchor().orElseThrow().guard(model,restored.graph()));
    }
}
