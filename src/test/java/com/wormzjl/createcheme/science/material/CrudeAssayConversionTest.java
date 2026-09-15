package com.wormzjl.createcheme.science.material;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.wormzjl.createcheme.science.column.v3.thermo.V3FeedPhase;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CrudeAssayConversionTest {
    @Test
    void allFivePublishedConversionsLoadWithTheSameOrderAndMassBasis() throws IOException {
        var report = JsonParser.parseString(Files.readString(
                Path.of("examples/crude-assays/converted-compositions.json"))).getAsJsonObject();
        var catalog = MaterialCatalog.bundled();
        var original = catalog.requirePackage("createcheme:tjl20_methane");
        assertEquals(5, report.getAsJsonArray("crudes").size());
        for (var element : report.getAsJsonArray("crudes")) {
            var row = element.getAsJsonObject();
            String packageId = row.get("package_id").getAsString();
            String assayId = row.get("assay_id").getAsString();
            var resolved = catalog.requirePackage(packageId);
            assertEquals(original.components(), resolved.components());
            assertEquals(original.properties(), resolved.properties());
            assertEquals("mass", resolved.assays().get(assayId).basis());
            MaterialRuntime.with(catalog, packageId, () -> {
                var thermo = V3PengRobinsonThermo.fromRegisteredPackage(packageId);
                double[] feed = thermo.crudeFeed(assayId).moleFractions();
                assertArrayEquals(vector(row.getAsJsonArray("mole_fractions")), feed, 1e-14);
                double totalMass = 0;
                for (int i = 0; i < feed.length; i++) totalMass += feed[i] * resolved.properties().get(i).molecularWeight();
                for (int i = 0; i < feed.length; i++) {
                    assertEquals(row.getAsJsonArray("mass_fractions").get(i).getAsDouble(),
                            feed[i] * resolved.properties().get(i).molecularWeight() / totalMass, 1e-14);
                }
                assertTrue(thermo.advisoryEvidence().stream().anyMatch(s -> s.startsWith("ESTIMATED_HEAVY_TAIL:")));
                var flash = thermo.flashTP(638.15, 267_250, feed, thermo.newWorkspace());
                assertEquals(V3FeedPhase.TWO_PHASE, flash.phase(), packageId + ": " + flash.detail());
                assertTrue(Double.isFinite(flash.molarEnthalpyJoulesPerMol()));
                for (int i = 0; i < feed.length; i++) {
                    assertEquals(feed[i], (1 - flash.vaporFraction()) * flash.liquidComposition()[i]
                            + flash.vaporFraction() * flash.vaporComposition()[i], 1e-9, packageId);
                }
                return null;
            });
        }
    }

    private static double[] vector(JsonArray array) {
        double[] values = new double[array.size()];
        for (int i = 0; i < values.length; i++) values[i] = array.get(i).getAsDouble();
        return values;
    }
}
