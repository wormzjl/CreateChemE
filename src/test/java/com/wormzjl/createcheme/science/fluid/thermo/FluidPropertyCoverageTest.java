package com.wormzjl.createcheme.science.fluid.thermo;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FluidPropertyCoverageTest {
    @Test void gameplayBasisWithNitrogenHasFinitePropertiesAtGridAndInteriorSamples() throws Exception {
        var catalog = MaterialCatalog.bundled();
        var rows = new ArrayList<Map<String,Object>>();
        var failures = new ArrayList<String>();
        for (String packageId : List.of("createcheme:tjl20_methane", "createcheme:wti_light_export_tjl20", "createcheme:cold_lake_blend_tjl20")) {
            var model = FluidThermodynamics.forNetwork(catalog, packageId, 1e-9);
            var propertyPackage = catalog.requirePackage(packageId);
            var n = Arrays.copyOf(V3PengRobinsonThermo.fromRegisteredPackage(packageId)
                    .crudeFeed(propertyPackage.assays().keySet().iterator().next()).moleFractions(), 22);
            n[20] = .1; n[21] = .2;
            double[] temperatures = {298.15, 300, 325, 350, 375};
            double[] pressures = {50000, 101325, 500000, 1e6, 2e6};
            for (boolean interior : new boolean[]{false, true}) {
                int count = interior ? 4 : 5;
                for (int ti = 0; ti < count; ti++) for (int pi = 0; pi < count; pi++) {
                    double t = interior ? (temperatures[ti] + temperatures[ti + 1]) / 2 : temperatures[ti];
                    double p = interior ? Math.sqrt(pressures[pi] * pressures[pi + 1]) : pressures[pi];
                    var row = new LinkedHashMap<String,Object>(); rows.add(row);
                    row.put("package", packageId); row.put("temperatureKelvin", t);
                    row.put("pressurePa", p); row.put("interiorSample", interior);
                    try {
                        var state = model.flashTP(t, p, n, () -> {});
                        double[] reconstructed = Arrays.copyOf(state.liquid(), 22);
                        for (int c = 0; c < 21; c++) reconstructed[c] += state.vapor()[c];
                        reconstructed[21] = state.waterLiquid() + state.waterVapor();
                        assertArrayEquals(n, reconstructed, 1e-8);
                        assertTrue(Double.isFinite(state.internalEnergy()));
                        assertTrue(state.mass() > 0 && Double.isFinite(state.mass()));
                        assertTrue(state.volume() > 0 && Double.isFinite(state.volume()));
                        assertEquals(state.volume(), state.liquidVolume() + state.waterVolume() + state.vaporVolume(), 1e-12);
                        row.put("densityKgPerM3", state.mass() / state.volume());
                        row.put("liquidVolumeFraction", state.liquidVolume() / state.volume());
                        row.put("waterVolumeFraction", state.waterVolume() / state.volume());
                        row.put("vaporVolumeFraction", state.vaporVolume() / state.volume());
                        if (state.liquidVolume() > 0) {
                            var viscosity = model.viscosity.liquid(t, state.liquid());
                            row.put("liquidViscosityPaS", viscosity.pascalSeconds());
                            row.put("conditionalSoluteViscosity", viscosity.conditionalSoluteApproximation());
                        }
                        if (state.waterVolume() > 0) row.put("waterViscosityPaS", model.viscosity.waterLiquid(t));
                        if (state.vaporVolume() > 0) row.put("vaporViscosityPaS", model.viscosity.vapor(t, state.vapor(), state.waterVapor()));
                        row.put("status", "PASS");
                    } catch (IllegalArgumentException failure) {
                        row.put("status", "FAIL"); row.put("reason", failure.getMessage());
                        failures.add(packageId + " T=" + t + " P=" + p + ": " + failure.getMessage());
                    }
                }
            }
        }
        Files.createDirectories(Path.of("build/reports/fluid"));
        Files.writeString(Path.of("build/reports/fluid/M1-network-property-grid.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(Map.of(
                        "scope", "Sampled operability and component closure, not independent property accuracy or continuous-domain qualification",
                        "basisComponents", 22, "nitrogenMolesPerMoleCrude", .1, "waterMolesPerMoleCrude", .2, "rows", rows)));
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }
    @Test void requiredCrudeMixturesHaveFinitePropertiesAcrossTheInitialWetOperatingGrid() throws Exception {
        var catalog=MaterialCatalog.bundled();var rows=new ArrayList<Map<String,Object>>();var failures=new ArrayList<String>();
        for(String packageId:List.of("createcheme:tjl20_methane","createcheme:wti_light_export_tjl20","createcheme:cold_lake_blend_tjl20")) {
            var model=new FluidThermodynamics(catalog,packageId,1e-9);var p=catalog.requirePackage(packageId);
            var n=Arrays.copyOf(V3PengRobinsonThermo.fromRegisteredPackage(packageId).crudeFeed(p.assays().keySet().iterator().next()).moleFractions(),21);
            n[20]=.2;
            for(double t:new double[]{298.15,300,325,350,375})for(double pressure:new double[]{50000,101325,500000,1e6,2e6}) {
                var row=new LinkedHashMap<String,Object>();rows.add(row);row.put("package",packageId);row.put("temperature",t);row.put("pressure",pressure);
                try {
                    var state=model.flashTP(t,pressure,n,()->{});
                    if(state.liquidVolume()>0)row.put("liquidViscosity",model.viscosity.liquid(t,state.liquid()).pascalSeconds());
                    if(state.waterVolume()>0)row.put("waterViscosity",model.viscosity.waterLiquid(t));
                    if(state.vaporVolume()>0)row.put("vaporViscosity",model.viscosity.vapor(t,state.vapor(),state.waterVapor()));
                    row.put("volume",state.volume());row.put("waterPartialPressure",state.waterPartialPressure());row.put("status","PASS");
                }catch(IllegalArgumentException error){row.put("status","FAIL");row.put("reason",error.getMessage());failures.add(packageId+" T="+t+" P="+pressure+": "+error.getMessage());}
            }
        }
        Files.createDirectories(Path.of("build/reports/fluid"));
        Files.writeString(Path.of("build/reports/fluid/M1-wet-grid.json"),new GsonBuilder().setPrettyPrinting().create().toJson(rows));
        assertTrue(failures.isEmpty(),String.join("\n",failures));
    }
    @Test void steamScreeningRetainsTheRejectedPointAndChecksIndependentViscosityReferences() throws Exception {
        try(var in=getClass().getResourceAsStream("/fluid/reference/water-vapor-checks.json")) {
            var rows=JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(in),StandardCharsets.UTF_8)).getAsJsonArray();
            int accepted=0,rejected=0;
            for(var value:rows) {
                var row=value.getAsJsonObject();double t=row.get("temperatureKelvin").getAsDouble(),p=row.get("partialPressurePascal").getAsDouble();
                double ideal=FluidThermodynamics.R*t/p,reference=row.get("referenceVolume").getAsDouble();
                if(p<=FluidThermodynamics.waterVaporPressureLimit(t)){assertTrue(Math.abs(ideal/reference-1)<=.02,row.toString());accepted++;}else rejected++;
                double mu=WaterRegion1.diluteVaporViscosity(t);assertEquals(row.get("referenceViscosity").getAsDouble(),mu,.05*mu);
            }
            assertTrue(accepted>=10);assertTrue(rejected>=1);
        }
    }
    @Test void steamCompressionMatchesIndependentPressureDifferencesWithinTheSampledEnvelope() throws Exception {
        try(var in=getClass().getResourceAsStream("/fluid/reference/water-vapor-compression.json")) {
            var rows=JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(in),StandardCharsets.UTF_8)).getAsJsonArray();
            int accepted=0;
            for(var value:rows) {
                var row=value.getAsJsonObject();double t=row.get("temperatureKelvin").getAsDouble(),p=row.get("partialPressurePascal").getAsDouble();
                if(p<=FluidThermodynamics.waterVaporPressureLimit(t)) {
                    assertTrue(Math.abs(1/(p*row.get("referenceCompressibility").getAsDouble())-1)<=.02,row.toString());accepted++;
                }
            }
            assertEquals(12,accepted);
        }
    }
}
