package com.wormzjl.createcheme.science.material;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AmbientViscosityTest {
    @Test void addedAmbientSamplesMatchIndependentDwsimCheckpoints() throws Exception {
        var catalog=MaterialCatalog.bundled();int checked=0;var properties=new java.util.HashSet<String>();
        try(var input=getClass().getResourceAsStream("/materials/dwsim-ambient-viscosity-api.json")) {
            var report=JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
            for(var record:report.getAsJsonArray("records")) {
                var row=record.getAsJsonObject();if(!row.has("checks"))continue;
                String id=row.get("property_id").getAsString();
                if(id.contains("tjl19_pc")||id.contains("cdu17"))continue; // These liquid curves were intentionally replaced by the global Dalia family.
                var p=catalog.packages().values().stream().filter(pkg->pkg.properties().stream().anyMatch(v->v.id().equals(id))).findFirst().orElseThrow();
                properties.add(id);
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
        assertTrue(checked>0);
        assertEquals(java.util.Set.of("createcheme:tjl19_ethane","createcheme:tjl19_isobutane","createcheme:tjl19_isopentane","createcheme:tjl19_n_butane","createcheme:tjl19_n_pentane","createcheme:tjl19_propane","createcheme:tjl20_methane"),properties);
    }
}
