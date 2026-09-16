package com.wormzjl.createcheme.fluid.benchmark;

import com.google.gson.GsonBuilder;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Standalone property microbenchmark. It makes no server-tick or network-latency claim. */
class FluidPropertyBenchmarkTest {
    private static volatile double consumed;
    @Test void measureDirectStateAndStandaloneTpInitializationSeparately() throws Exception {
        String id="createcheme:tjl20_methane";var model=new FluidThermodynamics(MaterialCatalog.bundled(),id,1e-9);
        var n=Arrays.copyOf(V3PengRobinsonThermo.fromRegisteredPackage(id).crudeFeed("createcheme:tia_juana_light_methane").moleFractions(),21);n[20]=.2;
        var seed=model.flashTP(350,101325,n,()->{});
        var output=new LinkedHashMap<String,Object>();output.put("java",System.getProperty("java.version"));
        output.put("propertyRevision",model.hydrocarbon.revision());output.put("viscosityRevision",model.viscosity.revision());
        output.put("fixture","TJL20 + 0.2 mol water per mol hydrocarbon; 350 K; 101325 Pa");
        output.put("directPhaseState",measure(()->model.state(seed.temperature(),seed.pressure(),seed.liquid(),seed.vapor(),seed.waterLiquid(),seed.waterVapor(),seed.hydrocarbonPartialPressure()).internalEnergy(),2000));
        // What a residual evaluation pays per node once the temperature-only properties are prepared:
        // the mixing terms, the reference-pressure Region 1 water state, saturation and vapor enthalpy.
        var prepared=model.prepare(seed.temperature());
        output.put("directPhaseStateWithPreparedTemperature",measure(()->model.state(seed.temperature(),seed.pressure(),seed.liquid(),seed.vapor(),seed.waterLiquid(),seed.waterVapor(),seed.hydrocarbonPartialPressure(),prepared).internalEnergy(),2000));
        output.put("prepareTemperature",measure(()->model.prepare(seed.temperature()).temperature(),2000));
        output.put("waterLiquid",measure(()->model.waterLiquid(seed.temperature(),seed.pressure()).molarVolume(),2000));
        output.put("waterLiquidWithPreparedTemperature",measure(()->model.waterLiquid(seed.temperature(),seed.pressure(),prepared).molarVolume(),2000));
        output.put("saturationPressure",measure(()->model.saturationPressure(seed.temperature()),2000));
        // The transport term the decode builds on top of state(), with and without its prepared terms.
        var scratch=new com.wormzjl.createcheme.science.fluid.transport.MixtureViscosity.Workspace();
        var transport=prepared.viscosities();
        output.put("viscosityLiquid",measure(()->model.viscosity.liquid(seed.temperature(),seed.liquidView()).pascalSeconds(),2000));
        output.put("viscosityLiquidPrepared",measure(()->model.viscosity.liquid(seed.temperature(),seed.liquidView(),transport).pascalSeconds(),2000));
        output.put("viscosityVapor",measure(()->model.viscosity.vapor(seed.temperature(),seed.vaporView(),seed.waterVapor()),2000));
        output.put("viscosityVaporPrepared",measure(()->model.viscosity.vapor(seed.temperature(),seed.vaporView(),seed.waterVapor(),scratch,transport),2000));
        output.put("standaloneTpInitializer",measure(()->model.flashTP(350,101325,n,()->{}).internalEnergy(),200));
        output.put("limitations","Microbenchmark only; measures direct state and TP initialization, not the UV inventory recovery or coupled network solve. No nested initializer permitted in network residuals.");
        Files.createDirectories(Path.of("build/reports/fluid"));Files.writeString(Path.of("build/reports/fluid/M1-property-cost.json"),new GsonBuilder().setPrettyPrinting().create().toJson(output));
        assertTrue(Double.isFinite(consumed));
    }
    private static Map<String,Object> measure(java.util.function.DoubleSupplier action,int count) {
        for(int i=0;i<count;i++)consumed=action.getAsDouble();
        long[] times=new long[count];
        for(int i=0;i<count;i++){long start=System.nanoTime();consumed=action.getAsDouble();times[i]=System.nanoTime()-start;}
        Arrays.sort(times);return Map.of("warmupCalls",count,"samples",count,"medianNanoseconds",times[count/2],"p95Nanoseconds",times[(int)(count*.95)],"maxNanoseconds",times[count-1]);
    }
}
