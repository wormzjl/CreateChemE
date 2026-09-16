package com.wormzjl.createcheme.science.fluid.transport;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.material.ViscosityCorrelation;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MixtureViscosityTest {
    @Test void dissolvedReferencesMatchIndependentApiCheckpointsWithoutExtrapolatingPureLiquidTables() throws Exception {
        String id=com.wormzjl.createcheme.science.fluid.thermo.FluidMaterialCatalog.NETWORK_PACKAGE;var catalog=MaterialCatalog.bundled();
        var model=new MixtureViscosity(catalog,id);var p=catalog.requirePackage(id);
        try(var input=getClass().getResourceAsStream("/data/createcheme/fluid/dissolved_viscosity.json")) {
            var json=JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
            for(var value:json.getAsJsonArray("curves")) {
                var curve=value.getAsJsonObject();int solute=p.components().indexOf(curve.get("component").getAsString());
                var pure=catalog.viscosity(id,p.components().get(solute),ViscosityCorrelation.Phase.LIQUID).orElseThrow();
                var carrier=catalog.viscosity(id,p.components().get(19),ViscosityCorrelation.Phase.LIQUID).orElseThrow();
                for(var check:curve.getAsJsonArray("checks")) {
                    var row=check.getAsJsonObject();double t=row.get("temperature_kelvin").getAsDouble();
                    if(t<=pure.maximumTemperatureKelvin()||t<carrier.minimumTemperatureKelvin()||t>carrier.maximumTemperatureKelvin())continue;
                    double[] n=new double[p.components().size()];n[solute]=.05;n[19]=.95;
                    double expected=Math.exp(.05*Math.log(row.get("viscosity_pascal_seconds").getAsDouble())
                            +.95*Math.log(carrier.dynamicViscosityPascalSeconds(t,carrier.minimumPressurePascal())));
                    var actual=model.liquid(t,n);
                    assertTrue(actual.conditionalSoluteApproximation());
                    assertEquals(expected,actual.pascalSeconds(),expected*.0005);
                }
            }
        }
    }
    @Test void pureUnsupportedSoluteDoesNotBecomeAnInventedLiquid() {
        var model=new MixtureViscosity(MaterialCatalog.bundled(),"createcheme:tjl20_methane");
        double[] n=new double[20];n[0]=1;
        assertThrows(IllegalArgumentException.class,()->model.liquid(300,n));
        assertTrue(model.vapor(300,n,0)>0);
        assertTrue(model.vapor(300,n,.1)>0);
    }
    @Test void crudeCompositionsHaveDifferentMaterialDependentViscosities() {
        var catalog=MaterialCatalog.bundled();var model=new MixtureViscosity(catalog,"createcheme:tjl20_methane");
        double[] light=new double[20],heavy=new double[20];light[6]=.99;light[1]=.01;heavy[19]=.99;heavy[1]=.01;
        assertTrue(model.liquid(300,heavy).pascalSeconds()>model.liquid(300,light).pascalSeconds());
        assertThrows(IllegalArgumentException.class,()->model.liquid(1000,heavy));
    }
}
