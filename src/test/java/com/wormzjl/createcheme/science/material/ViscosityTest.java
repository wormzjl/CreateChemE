package com.wormzjl.createcheme.science.material;

import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ViscosityTest {
    private static final String PACKAGE = "createcheme:tjl20_methane";
    private static final String WATER = "data/createcheme/materials/water/water.json";

    @Test void liquidWaterMatchesIndependentIapwsTableEight() {
        var v = MaterialCatalog.bundled().viscosity(PACKAGE, "Water", ViscosityCorrelation.Phase.LIQUID).orElseThrow();
        // IAPWS SR6-08(2011), table 8: values are published in microPa.s (including metastable liquid).
        assertEquals(3058.36075e-6, v.dynamicViscosityPascalSeconds(260, 100000), 5e-12);
        assertEquals(889.996774e-6, v.dynamicViscosityPascalSeconds(298.15, 100000), 5e-13);
        assertEquals(276.207245e-6, v.dynamicViscosityPascalSeconds(375, 100000), 5e-13);
        assertFalse(v.estimated());
        assertTrue(v.source().contains("IAPWS"));
        assertEquals(889.996774e-6 / 997.047013,
                v.kinematicViscositySquareMetresPerSecond(298.15, 100000, 997.047013), 1e-15);
    }

    @Test void validityLimitsAndMissingDataAreExplicit() {
        var catalog = MaterialCatalog.bundled();
        assertTrue(catalog.viscosity(PACKAGE, "Water", ViscosityCorrelation.Phase.VAPOR).isEmpty());
        assertTrue(catalog.viscosity("createcheme:cdu17_tjl_acs2018", "cdu17_pc01", ViscosityCorrelation.Phase.LIQUID).isEmpty());
        assertEquals(catalog.viscosity(PACKAGE,"tjl19_pc07",ViscosityCorrelation.Phase.LIQUID),
                catalog.viscosity(PACKAGE,"TJL_PC07",ViscosityCorrelation.Phase.LIQUID));
        assertThrows(IllegalArgumentException.class, () -> catalog.viscosity(PACKAGE, "NotAComponent", ViscosityCorrelation.Phase.LIQUID));
        var v = catalog.viscosity(PACKAGE, "Water", ViscosityCorrelation.Phase.LIQUID).orElseThrow();
        for (double t : new double[] {253.14, 383.16, Double.NaN, Double.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class, () -> v.dynamicViscosityPascalSeconds(t, 100000));
        assertThrows(IllegalArgumentException.class, () -> v.dynamicViscosityPascalSeconds(300, 250000));
        assertThrows(IllegalArgumentException.class, () -> v.kinematicViscositySquareMetresPerSecond(300, 100000, 0));
        assertThrows(IllegalArgumentException.class, () -> v.kinematicViscositySquareMetresPerSecond(300, 100000, Double.NaN));
        assertTrue(v.dynamicViscosityPascalSeconds(253.15, 100000) > v.dynamicViscosityPascalSeconds(383.15, 100000));
        assertThrows(UnsupportedOperationException.class, () -> v.coefficients().set(0, 1.0));
    }

    @Test void liquidAndGasFitsLoadForTheSameComponentWithoutChangingEquilibriumData() {
        var resources = new HashMap<>(MaterialCatalog.bundled().resources());
        String path = "data/createcheme/materials/properties/tjl20_methane.json";
        var property = JsonParser.parseString(resources.get(path)).getAsJsonObject();
        var fits = new JsonObject();
        fits.add("liquid", fit("andrade", 1e-6, 1500));
        fits.add("vapor", fit("sutherland", 1.1e-5, 110));
        property.add("viscosity", fits); resources.put(path, property.toString());
        var catalog = MaterialCatalog.parse(resources);
        var liquid = catalog.viscosity(PACKAGE, "Methane", ViscosityCorrelation.Phase.LIQUID).orElseThrow();
        var vapor = catalog.viscosity(PACKAGE, "Methane", ViscosityCorrelation.Phase.VAPOR).orElseThrow();
        assertEquals(1e-6 * Math.exp(5), liquid.dynamicViscosityPascalSeconds(300, 100000), 1e-18);
        assertEquals(1.1e-5, vapor.dynamicViscosityPascalSeconds(300, 100000), 1e-20);
        assertTrue(vapor.dynamicViscosityPascalSeconds(600, 100000) > vapor.dynamicViscosityPascalSeconds(300, 100000));
        assertTrue(liquid.estimated());
        assertEquals(MaterialCatalog.bundled().requirePackage(PACKAGE).fingerprint(), catalog.requirePackage(PACKAGE).fingerprint());
        assertNotEquals(MaterialCatalog.bundled().viscosityFingerprint(PACKAGE), catalog.viscosityFingerprint(PACKAGE));
    }

    @Test void malformedFitsFailCatalogLoadingWithAFieldPath() {
        for (var edit : List.<java.util.function.Consumer<JsonObject>>of(
                v -> v.addProperty("type", "unregistered"),
                v -> v.remove("source"),
                v -> v.addProperty("temperature_max_kelvin", 200),
                v -> v.addProperty("pressure_max_pascal", 1),
                v -> v.getAsJsonArray("coefficients").set(0, new JsonPrimitive(-1)),
                v -> v.add("exponents", new JsonArray()),
                v -> v.addProperty("reference_temperature_kelvin", 0))) {
            var resources = waterChange(edit);
            var error = assertThrows(IllegalArgumentException.class, () -> MaterialCatalog.parse(resources));
            assertTrue(error.getMessage().contains("water/water.json"));
            assertTrue(error.getMessage().contains("viscosity.liquid"));
        }
        var resources = waterChange(v -> {
            var invalid = fit("andrade", 1, 1e9);
            for (String key : List.copyOf(v.keySet())) v.remove(key);
            invalid.entrySet().forEach(e -> v.add(e.getKey(), e.getValue()));
        });
        assertThrows(IllegalArgumentException.class, () -> MaterialCatalog.parse(resources));
    }

    @Test void transportFingerprintAndSnapshotTrackViscosityWithoutInvalidatingPrSeeds() {
        var original = MaterialCatalog.bundled();
        var replacement = MaterialCatalog.parse(waterChange(v -> v.getAsJsonArray("coefficients").set(0, new JsonPrimitive(.0003))));
        assertNotEquals(original.viscosityFingerprint(PACKAGE), replacement.viscosityFingerprint(PACKAGE));
        assertEquals(original.requirePackage(PACKAGE).scientificRevision(), replacement.requirePackage(PACKAGE).scientificRevision());
        var citationOnly = MaterialCatalog.parse(waterChange(v -> v.addProperty("source", "Corrected source citation")));
        assertEquals(original.viscosityFingerprint(PACKAGE), citationOnly.viscosityFingerprint(PACKAGE));
        try {
            MaterialRuntime.with(original, PACKAGE, () -> {
                MaterialRuntime.publish(replacement);
                assertSame(original.viscosity(PACKAGE, "Water", ViscosityCorrelation.Phase.LIQUID).orElseThrow(),
                        MaterialRuntime.viscosity(PACKAGE, "Water", ViscosityCorrelation.Phase.LIQUID).orElseThrow());
                return null;
            });
            assertSame(replacement.viscosity(PACKAGE, "Water", ViscosityCorrelation.Phase.LIQUID).orElseThrow(),
                    MaterialRuntime.viscosity(PACKAGE, "Water", ViscosityCorrelation.Phase.LIQUID).orElseThrow());
            var invalid = waterChange(v -> v.addProperty("temperature_min_kelvin", -1));
            assertThrows(IllegalArgumentException.class, () -> MaterialRuntime.publish(MaterialCatalog.parse(invalid)));
            assertSame(replacement, MaterialRuntime.active());
        } finally { MaterialRuntime.reset(); }
    }

    private static Map<String, String> waterChange(java.util.function.Consumer<JsonObject> edit) {
        var resources = new HashMap<>(MaterialCatalog.bundled().resources());
        var water = JsonParser.parseString(resources.get(WATER)).getAsJsonObject();
        edit.accept(water.getAsJsonObject("viscosity").getAsJsonObject("liquid"));
        resources.put(WATER, water.toString()); return resources;
    }

    @Test void allCurrentCrudeComponentsMatchIndependentDwsimApiCheckPoints() throws Exception {
        JsonObject report;
        try (var in = getClass().getResourceAsStream("/materials/dwsim-viscosity-api.json")) {
            assertNotNull(in);
            report = JsonParser.parseReader(new java.io.InputStreamReader(in,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        }
        var catalog=MaterialCatalog.bundled();
        Map<String,JsonObject> exported=new HashMap<>();
        for (var row:report.getAsJsonArray("records")) {
            var record=row.getAsJsonObject();exported.put(record.get("property_id").getAsString(),record);
        }
        int verified=0;
        for (var p:catalog.packages().values()) for (var property:p.properties()) {
            var row=exported.get(property.id());
            // Nitrogen's curves are NIST isobar tables, not DWSIM API samples, so the exported
            // comparison report carries no record for that property at all.
            if(row==null||!row.has("checks"))continue;
            for(var phase:ViscosityCorrelation.Phase.values()) {
                var curve=catalog.viscosity(p.id(),property.component(),phase).orElseThrow();
                assertEquals(ViscosityCorrelation.Model.LOG_TABLE,curve.model());
                if(property.component().startsWith("tjl19_pc"))assertTrue(curve.estimated());
                var checks=row.getAsJsonObject("checks").getAsJsonArray(phase.name().toLowerCase(Locale.ROOT));
                for(var value:checks) {
                    var point=value.getAsJsonObject();double t=point.get("temperature_kelvin").getAsDouble();
                    double expected=point.get("viscosity_pascal_seconds").getAsDouble();
                    assertEquals(expected,curve.dynamicViscosityPascalSeconds(t,100000),expected*0.000500001,
                            ()->property.id()+" "+phase+" at "+t+" K diverges from DWSIM");
                    verified++;
                }
            }
        }
        assertTrue(verified>1000);
        for(String id:catalog.requirePackage(PACKAGE).components())for(var phase:ViscosityCorrelation.Phase.values())
            assertTrue(catalog.viscosity(PACKAGE,id,phase).isPresent(),id+" "+phase);
    }

    @Test void tableNodesAreExactAndMalformedTablesAreRejected() {
        var table=new ViscosityCorrelation(ViscosityCorrelation.Model.LOG_TABLE,300,400,100000,100000,300,
                List.of(.001,.00025),List.of(),"test","manufactured interpolation fixture",true,List.of(300.,400.));
        assertEquals(.001,table.dynamicViscosityPascalSeconds(300,100000));
        assertEquals(.00025,table.dynamicViscosityPascalSeconds(400,100000));
        assertEquals(.0005,table.dynamicViscosityPascalSeconds(350,100000),1e-18);
        assertThrows(IllegalArgumentException.class,()->new ViscosityCorrelation(ViscosityCorrelation.Model.LOG_TABLE,300,400,100000,100000,300,
                List.of(.001,.00025),List.of(),"test","fixture",true,List.of(300.,300.)));
        assertThrows(IllegalArgumentException.class,()->new ViscosityCorrelation(ViscosityCorrelation.Model.LOG_TABLE,300,400,100000,100000,300,
                List.of(.001,-.00025),List.of(),"test","fixture",true,List.of(300.,400.)));
    }

    private static JsonObject fit(String type, double a, double b) {
        // Manufactured fixtures exercise the equations; these are not published methane estimates.
        var fit = JsonParser.parseString("""
                {"temperature_min_kelvin":250,"temperature_max_kelvin":700,
                 "pressure_min_pascal":100000,"pressure_max_pascal":300000,"reference_temperature_kelvin":300,
                 "revision":"test-r1","source":"Manufactured equation fixture","estimated":true}
                """).getAsJsonObject();
        fit.addProperty("type", type); var c = new JsonArray(); c.add(a); c.add(b); fit.add("coefficients", c);
        return fit;
    }
}
