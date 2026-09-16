package com.wormzjl.createcheme.runtime.fluid;

import com.google.gson.JsonParser;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;

class FluidPropertyReloadGuardTest {
    private final MaterialCatalog original=MaterialCatalog.bundled();
    private final String packageId=FluidPresetCatalog.NETWORK_PACKAGE;
    private FluidPropertyReloadGuard guard(){return new FluidPropertyReloadGuard(original,packageId,1e-9,FluidThermodynamics.forNetwork(original,packageId,1e-9));}
    private MaterialCatalog changed(String path,java.util.function.Consumer<com.google.gson.JsonObject> edit) {
        var resources=new HashMap<>(original.resources());var full="data/createcheme/materials/"+path;
        var json=JsonParser.parseString(resources.get(full)).getAsJsonObject();edit.accept(json);resources.put(full,json.toString());return MaterialCatalog.parse(resources);
    }
    @Test void metadataOnlyReloadKeepsQualifiedScienceAndNumericalChangeWithoutRevisionBumpHolds() {
        var guard=guard();assertTrue(guard.inspect(original).isEmpty());
        var names=changed("components/tjl19_pc07.json",j->j.addProperty("fallback","Renamed petroleum"));
        assertTrue(guard.inspect(names).isEmpty());
        var science=changed("properties/tjl19_tjl19_pc07.json",j->j.addProperty("molecular_weight_kg_per_mol",.31));
        assertEquals(original.requirePackage(packageId).revision(),science.requirePackage(packageId).revision());
        assertTrue(guard.inspect(science).isPresent());assertTrue(guard.inspect(science).isPresent());
        assertTrue(guard.inspect(MaterialCatalog.parse(original.resources())).isEmpty());
    }
    @Test void viscosityOnlyChangeInvalidatesThePinnedFluidModel() {
        var resources=new HashMap<>(original.resources());
        var path=resources.keySet().stream().filter(p->p.contains("/water/")).findFirst().orElseThrow();
        var json=JsonParser.parseString(resources.get(path)).getAsJsonObject();
        var coefficients=json.getAsJsonObject("viscosity").getAsJsonObject("liquid").getAsJsonArray("coefficients");
        coefficients.set(0,new com.google.gson.JsonPrimitive(coefficients.get(0).getAsDouble()*1.01));
        resources.put(path,json.toString());var changed=MaterialCatalog.parse(resources);
        assertNotEquals(original.viscosityFingerprint(packageId),changed.viscosityFingerprint(packageId));
        assertTrue(guard().inspect(changed).isPresent());
    }
}
