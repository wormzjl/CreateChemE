package com.wormzjl.createcheme.science.material;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.wormzjl.createcheme.runtime.fluid.*;
import com.wormzjl.createcheme.science.fluid.thermo.*;
import com.wormzjl.createcheme.world.level.block.entity.ColumnInputPreset;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class MaterialPresetsTest {
    private static final String ROOT="data/createcheme/materials/";
    @Test void allSixInputsRemainExactlyEqualToThePreFrameworkQualificationInputs() throws Exception {
        try(var stream=getClass().getResourceAsStream("/materials/column-preset-inputs.json")) {
            var expected=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonArray();
            assertEquals(6,expected.size());
            for(var row:expected) {
                var fixture=row.getAsJsonObject();var actual=ColumnInputPreset.fromId(fixture.get("id").getAsString()).input(MaterialCatalog.bundled());
                assertEquals(fixture.get("input"),new Gson().toJsonTree(actual));
            }
        }
    }
    @Test void exampleWholeRecordOverridePreservesAllExistingTransportData() throws Exception {
        var original=MaterialCatalog.bundled();var resources=new HashMap<>(original.resources());String path=ROOT+"properties/tjl20_methane.json";
        resources.put(path,java.nio.file.Files.readString(java.nio.file.Path.of("examples/material-override/"+path)));
        var replaced=MaterialCatalog.parse(resources);String id="createcheme:tjl20_methane";
        assertEquals(357,replaced.requirePackage(id).properties().getFirst().density());
        assertEquals(original.viscosityFingerprint(id),replaced.viscosityFingerprint(id));
        assertNotEquals(original.physicsFingerprint(id,original.requirePackage(id).components()),replaced.physicsFingerprint(id,replaced.requirePackage(id).components()));
    }
    @Test void customPresetIsServerDefinedAndInvalidOverridesFailBeforePublication() {
        var original=MaterialCatalog.bundled();var resources=new HashMap<>(original.resources());String path=ROOT+"presets/column_custom.json";
        var preset=JsonParser.parseString(resources.get(ROOT+"presets/column_dalia.json")).getAsJsonObject();
        preset.addProperty("id","custom_crude");preset.addProperty("label","Server crude");preset.addProperty("translation_key","server.crude");
        preset.getAsJsonObject("operating").addProperty("feedTemperatureKelvin",650);resources.put(path,preset.toString());
        var changed=MaterialCatalog.parse(resources);var choice=ColumnInputPreset.fromId(changed,"custom_crude");
        assertEquals(650,choice.input(changed).feedTemperatureKelvin());assertTrue(changed.presets().visibleColumns().contains(choice.descriptor(changed)));
        assertThrows(IllegalArgumentException.class,()->ColumnInputPreset.fromId(original,"custom_crude"));
        assertEquals(original.physicsFingerprint("createcheme:dalia_tjl20",original.requirePackage("createcheme:dalia_tjl20").components()),changed.physicsFingerprint("createcheme:dalia_tjl20",changed.requirePackage("createcheme:dalia_tjl20").components()));
        preset.getAsJsonObject("operating").addProperty("stageCount",2);resources.put(path,preset.toString());
        var failure=assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(resources));assertTrue(failure.getMessage().contains(path));
        preset.addProperty("assay","missing:assay");resources.put(path,preset.toString());
        assertTrue(assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(resources)).getMessage().contains("package/assay"));
    }
    @Test void addingAQualifiedFixtureComponentAndMovingNitrogenNeedsNoFixedWidthEdits() {
        var original=MaterialCatalog.bundled();var resources=new HashMap<>(original.resources());
        var name=JsonParser.parseString(resources.get(ROOT+"components/nitrogen.json")).getAsJsonObject();
        name.addProperty("id","Testgas");name.addProperty("fallback","Testgas");name.addProperty("translation_key","test.gas");resources.put(ROOT+"components/testgas.json",name.toString());
        var property=JsonParser.parseString(resources.get(ROOT+"properties/nitrogen.json")).getAsJsonObject();
        property.addProperty("id","test:testgas");property.addProperty("component","Testgas");property.addProperty("source","Synthetic structural fixture using nitrogen reference data");resources.put(ROOT+"properties/testgas.json",property.toString());
        var basis=JsonParser.parseString(resources.get(ROOT+"bases/network.json")).getAsJsonObject();
        basis.add("components",new Gson().toJsonTree(List.of("Testgas","Nitrogen")));basis.add("properties",new Gson().toJsonTree(List.of("test:testgas","createcheme:fluid_nitrogen")));resources.put(ROOT+"bases/network.json",basis.toString());
        var changed=MaterialCatalog.parse(resources);String pkg=FluidMaterialCatalog.networkPackage(changed);var axis=changed.requirePackage(pkg).components();
        assertEquals(original.requirePackage(pkg).components().size()+1,axis.size());
        var nitrogen=FluidDeviceSpec.nitrogen(changed);assertEquals(1,nitrogen.composition()[axis.indexOf("Nitrogen")]);assertEquals(0,nitrogen.composition()[axis.indexOf("Testgas")]);
        var presets=FluidPresetCatalog.resolve(changed);var previous=FluidPresetCatalog.resolve(original);
        assertEquals(previous.size(),presets.size());
        var target=new ArrayList<>(axis);target.add("Water");var source=new ArrayList<>(original.requirePackage(pkg).components());source.add("Water");
        for(int i=0;i<presets.size();i++)assertArrayEquals(new MaterialAxis(target).project(new MaterialAxis(source),previous.get(i).moleFractions()),presets.get(i).moleFractions());
        var model=FluidThermodynamics.forNetwork(changed,pkg,1e-9);assertEquals(target,model.components());
        var state=model.initialNitrogenCharge(1,298.15,101325,()->{});assertTrue(Double.isFinite(state.mass())&&state.mass()>0);
        assertSame(original,MaterialRuntime.current());
    }
}
