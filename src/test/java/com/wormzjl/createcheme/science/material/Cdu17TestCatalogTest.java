package com.wormzjl.createcheme.science.material;

import com.google.gson.JsonParser;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Cdu17TestCatalogTest {
    @Test void referenceLoadsWithoutAnyProductionResources() {
        var reference = Cdu17TestCatalog.catalog();
        assertEquals(1, reference.packages().size());
        var p = reference.requirePackage(Cdu17TestCatalog.PACKAGE_ID);
        assertEquals("cdu17-tjl-kl1976-r2", p.revision());
        assertEquals(16, p.components().size());
        assertEquals(0.016043, p.properties().getFirst().molecularWeight());
        assertEquals(0.0, Cdu17TestCatalog.with(() -> {
            var thermo = V3PengRobinsonThermo.fromRegisteredPackage(Cdu17TestCatalog.PACKAGE_ID);
            return thermo.crudeFeed("createcheme:tia_juana_light").moleFractions()[0];
        }));
    }

    @Test void scopeRestoresTheEnclosingCatalogOnFailure() {
        var before = MaterialRuntime.current();
        var marker = new IllegalStateException("scope probe");
        assertSame(marker, assertThrows(IllegalStateException.class, () -> Cdu17TestCatalog.with(() -> {
            assertSame(Cdu17TestCatalog.catalog(), MaterialRuntime.current());
            throw marker;
        })));
        assertSame(before, MaterialRuntime.current());
    }

    @Test void editingProductionPropertiesCannotAlterTheReference() {
        var before = Cdu17TestCatalog.catalog().requirePackage(Cdu17TestCatalog.PACKAGE_ID);
        var resources = new HashMap<>(MaterialCatalog.bundled().resources());
        String path = "data/createcheme/materials/properties/tjl20_methane.json";
        var edited = JsonParser.parseString(resources.get(path)).getAsJsonObject();
        edited.addProperty("molecular_weight_kg_per_mol", .017);
        resources.put(path, edited.toString());
        MaterialRuntime.with(MaterialCatalog.parse(resources), "createcheme:tjl20_methane", () -> {
            assertEquals(before, Cdu17TestCatalog.catalog().requirePackage(Cdu17TestCatalog.PACKAGE_ID));
            return null;
        });
    }
}
