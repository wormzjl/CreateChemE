package com.wormzjl.createcheme.science.material;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AmbientViscosityTest {
    @Test void addedAmbientSamplesMatchIndependentDwsimCheckpoints() throws Exception {
        var catalog=MaterialCatalog.bundled();int checked=0;
        try(var input=getClass().getResourceAsStream("/materials/dwsim-ambient-viscosity-api.json")) {
            var report=JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
            for(var record:report.getAsJsonArray("records")) {
                var row=record.getAsJsonObject();if(!row.has("checks"))continue;
                String id=row.get("property_id").getAsString();
                var p=catalog.packages().values().stream().filter(pkg->pkg.properties().stream().anyMatch(v->v.id().equals(id))).findFirst().orElseThrow();
                String component=p.properties().stream().filter(v->v.id().equals(id)).findFirst().orElseThrow().component();
                for(var phase:row.getAsJsonObject("checks").entrySet()) {
                    var correlation=catalog.viscosity(p.id(),component,ViscosityCorrelation.Phase.valueOf(phase.getKey().toUpperCase(java.util.Locale.ROOT))).orElseThrow();
                    for(var value:phase.getValue().getAsJsonArray()) {
                        var point=value.getAsJsonObject();double t=point.get("temperature_kelvin").getAsDouble();
                        double expected=point.get("viscosity_pascal_seconds").getAsDouble();
                        assertEquals(expected,correlation.dynamicViscosityPascalSeconds(t,100000),expected*.000500001,id+" at "+t);
                        checked++;
                    }
                }
            }
        }
        assertTrue(checked>100);
    }
}
