package com.wormzjl.createcheme.science.material;

import com.google.gson.*;
import com.wormzjl.createcheme.science.column.v3.*;
import com.wormzjl.createcheme.science.column.v3.thermo.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class MaterialCatalogTest {
    private static final String ID="createcheme:tjl20_methane";
    private static final String ROOT="data/createcheme/materials/";
    @AfterEach void reset(){MaterialRuntime.reset();}
    static Map<String,String> changed(String path,java.util.function.Consumer<JsonObject> edit) {
        var resources=new HashMap<>(MaterialCatalog.bundled().resources());
        var json=JsonParser.parseString(resources.get(ROOT+path)).getAsJsonObject(); edit.accept(json);
        resources.put(ROOT+path,json.toString()); return resources;
    }
    @Test void immutableCatalogRetainsSeparateDatasetsForSharedChemicalIdentity() {
        var c=MaterialCatalog.bundled(); var pilot=Cdu17TestCatalog.catalog().requirePackage("createcheme:cdu17_tjl_acs2018"); var literature=c.requirePackage(ID);
        assertEquals("Ethane",pilot.properties().get(1).component()); assertEquals("Ethane",literature.properties().get(1).component());
        assertNotEquals(pilot.properties().get(1).molecularWeight(),literature.properties().get(1).molecularWeight());
        assertThrows(UnsupportedOperationException.class,()->literature.properties().clear());
        assertThrows(UnsupportedOperationException.class,()->literature.interactions().get(0).set(0,9.0));
        assertEquals("Ethane",c.name("Ethane").english());
        assertTrue(c.name("crude_pc01").english().contains("below"));
        assertTrue(c.name("crude_pc12").english().contains("above"));
        assertTrue(c.name("crude_pc07").english().endsWith("(estimated)"));
    }
    @Test void numericalEditsInvalidateFingerprintEvenWithoutRevisionBumpButNamesDoNot() {
        var before=MaterialCatalog.bundled().requirePackage(ID);
        var names=MaterialCatalog.parse(changed("components/crude_pc07.json",o->o.addProperty("fallback","Petroleum")));
        assertEquals(before.fingerprint(),names.requirePackage(ID).fingerprint());
        var science=MaterialCatalog.parse(changed("properties/crude_pc07.json",o->o.addProperty("molecular_weight_kg_per_mol",.31)));
        assertEquals(before.revision(),science.requirePackage(ID).revision());
        assertNotEquals(before.fingerprint(),science.requirePackage(ID).fingerprint());
        assertFalse(MaterialRuntime.with(science,ID,()->MaterialRuntime.isBundledScience(ID)));
        assertFalse(MaterialRuntime.with(science,ID,()->MaterialRuntime.isCurrent(ID,before.scientificRevision())));
        assertTrue(MaterialRuntime.with(names,ID,()->MaterialRuntime.isCurrent(ID,before.scientificRevision())));
        var input=com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity.methaneCduInput();
        assertTrue(MaterialRuntime.with(science,ID,()->V3NeuralModels.bundled().predict(input,V3SolveControl.UNBOUNDED).isEmpty()));
    }
    @Test void invalidReloadCannotPublishPartialCatalog() {
        MaterialRuntime.publish(MaterialCatalog.bundled());
        var bad=changed("properties/crude_pc07.json",o->o.addProperty("molecular_weight_kg_per_mol",-1));
        var error=assertThrows(IllegalArgumentException.class,()->MaterialRuntime.publish(MaterialCatalog.parse(bad)));
        assertTrue(error.getMessage().contains("properties/crude_pc07.json"));
        assertTrue(error.getMessage().contains("molecular_weight_kg_per_mol"));
        assertSame(MaterialCatalog.bundled(),MaterialRuntime.active());
    }
    @Test void rejectsDuplicateIdsMissingReferencesAndAmbiguousAliases() {
        var duplicate=new HashMap<>(MaterialCatalog.bundled().resources());
        duplicate.put(ROOT+"components/duplicate.json",duplicate.get(ROOT+"components/ethane.json"));
        assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(duplicate));
        assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(changed("properties/crude_pc07.json",o->o.addProperty("component","Missing"))));
        assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(changed("packages/tjl20.json",o->o.getAsJsonObject("aliases").addProperty("Ethane","Methane"))));
        assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(changed("packages/tjl20.json",o->o.addProperty("missing_interactions","error"))));
    }
    @Test void massAssaysConvertUsingSelectedMolecularWeightsAndVolumeReferencesAreChecked() {
        var original=MaterialCatalog.bundled();var p=original.requirePackage(ID);var assay=p.assays().get("createcheme:tia_juana_light_methane");
        var resources=changed("assays/tjl20.json",o->{
            o.addProperty("basis","mass");var mass=new JsonArray();
            for(int i=0;i<p.components().size();i++)mass.add(assay.amounts().get(i)*p.properties().get(i).molecularWeight());
            o.remove("amounts_by_component");o.add("components",new Gson().toJsonTree(p.components()));o.add("amounts",mass);
        });
        var changed=MaterialCatalog.parse(resources);
        double[] expected=V3PengRobinsonThermo.fromRegisteredPackage(ID).crudeFeed(assay.id()).moleFractions();
        double[] actual=MaterialRuntime.with(changed,ID,()->V3PengRobinsonThermo.fromRegisteredPackage(ID).crudeFeed(assay.id()).moleFractions());
        assertArrayEquals(expected,actual,1e-16);
        assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(changed("assays/tjl19.json",o->{o.addProperty("basis","standard_liquid_volume");o.addProperty("standard_temperature_kelvin",300);o.addProperty("standard_pressure_pascal",101325);})));
    }
    @Test void nrtlPairsAreDirectedCompleteAndNotAnImplementedSolver() {
        var r=new HashMap<>(MaterialCatalog.bundled().resources());
        r.put(ROOT+"interactions/nrtl.json","""
                {"schema_version":1,"id":"example:nrtl","model":"nrtl","source":"manufactured validation fixture",
                 "pairs":[{"first":"Ethane","second":"Propane","a12":1,"b12_kelvin":100,"a21":2,"b21_kelvin":200,
                 "alpha":0.3,"temperature_min_kelvin":298.15,"temperature_max_kelvin":900}]}
                """);
        var p=JsonParser.parseString(r.get(ROOT+"packages/tjl19.json")).getAsJsonObject();
        p.remove("basis");p.addProperty("id","example:nrtl");p.addProperty("model","nrtl");p.addProperty("interactions","example:nrtl");p.addProperty("missing_interactions","error");
        p.add("components",JsonParser.parseString("[\"Ethane\",\"Propane\"]"));
        p.add("properties",JsonParser.parseString("[\"createcheme:tjl19_ethane\",\"createcheme:tjl19_propane\"]"));p.add("aliases",new JsonObject());
        r.put(ROOT+"packages/nrtl.json",p.toString());
        var c=MaterialCatalog.parse(r);var pair=c.requirePackage("example:nrtl").nrtlPairs().getFirst();
        assertEquals(100,pair.b12());assertEquals(200,pair.b21());
        var error=assertThrows(IllegalArgumentException.class,()->MaterialRuntime.with(c,"example:nrtl",()->V3PengRobinsonThermo.fromRegisteredPackage("example:nrtl")));
        assertTrue(error.getMessage().contains("solver unavailable: nrtl"));
        var interactions=JsonParser.parseString(r.get(ROOT+"interactions/nrtl.json")).getAsJsonObject();
        interactions.getAsJsonArray("pairs").get(0).getAsJsonObject().remove("b21_kelvin");r.put(ROOT+"interactions/nrtl.json",interactions.toString());
        assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(r));
    }
    @Test void runningContextKeepsBothPrAndWaterAcrossPublicationAndRestoresAfterFailure() throws Exception {
        var original=MaterialCatalog.bundled();
        var replacement=MaterialCatalog.parse(changed("water/water.json",o->o.addProperty("molar_mass",.019)));
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var future=executor.submit(()->MaterialRuntime.with(original,ID,()-> {
                entered.countDown();
                try { if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("test release timeout"); }
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
                assertEquals(.01801528,V3WaterProperties.molarMass());
                assertEquals(original.requirePackage(ID).scientificRevision(),V3PengRobinsonThermo.fromRegisteredPackage(ID).datasetRevision());
                return MaterialRuntime.current();
            }));
            assertTrue(entered.await(10,TimeUnit.SECONDS));MaterialRuntime.publish(replacement);release.countDown();
            assertSame(original,future.get(10,TimeUnit.SECONDS));
            assertSame(replacement,executor.submit(MaterialRuntime::current).get(10,TimeUnit.SECONDS));
        } finally {release.countDown();}
        assertThrows(IllegalStateException.class,()->MaterialRuntime.with(original,ID,()->{throw new IllegalStateException();}));
        assertSame(replacement,MaterialRuntime.current());assertEquals(.019,V3WaterProperties.molarMass());
    }
    @Test void retiredInputIsRejectedWithoutMutatingItsAmounts() {
        var c=MaterialCatalog.bundled();var p=c.requirePackage(ID);var ids=new ArrayList<>(p.components());
        int index=ids.indexOf("crude_pc07");ids.set(index,"TJL_PC07");Collections.reverse(ids);
        double[] values=new double[ids.size()];for(int i=0;i<values.length;i++)values[i]=i+1;
        var input=new V3ColumnInput(1,ID,"createcheme:tia_juana_light_methane",new V3ComponentBasis(ids),values,638.15,4,2,250000,0,
                List.of(new V3ColumnSpecification.CondenserOutletTemperature(332.15),new V3ColumnSpecification.OrganicRefluxRatio(4.17),new V3ColumnSpecification.ReboilerDuty(0)));
        assertThrows(IllegalArgumentException.class,()->V3MaterialInputs.requireCurrent(input,c));
        assertArrayEquals(values,input.feedComponentMolarFlowsMolPerSecond());
    }
}
