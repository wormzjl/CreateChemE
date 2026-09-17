package com.wormzjl.createcheme.science.column.v3.thermo;

import com.wormzjl.createcheme.science.material.*;
import com.wormzjl.createcheme.science.fluid.transport.MixtureViscosity;
import com.wormzjl.createcheme.science.column.v3.*;
import com.wormzjl.createcheme.world.level.block.entity.ColumnInputPreset;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegroupedCrudeTest {
    @Test void replacementHasTwelveConsistentCutsAndNoRetiredProductionBasis() {
        var catalog=MaterialCatalog.bundled();assertEquals(8,catalog.packages().size());
        assertFalse(catalog.packages().containsKey("createcheme:cdu17_tjl_acs2018"));
        var p=catalog.requirePackage("createcheme:tjl20_methane");assertEquals(19,p.components().size());
        for(int i=1;i<=12;i++)assertEquals("crude_pc%02d".formatted(i),p.components().get(i+6));
        double[] edges={651.0249814474507,723.15,823.15,923.15,1023.15};
        for(int i=0;i<5;i++) {
            var name=catalog.name("crude_pc%02d".formatted(i+8));assertTrue(name.estimated());
            assertEquals(edges[i],name.lowerKelvin());
            if(i<4)assertEquals(edges[i+1],name.upperKelvin());else assertNull(name.upperKelvin());
        }
        assertTrue(catalog.name("crude_pc12").english().contains("above 750.0"));
        assertTrue(p.aliases().isEmpty());
    }
    @Test void reconstructedFeedConservesIndependentOriginalMassVolumeAndCaloricMoments() {
        var current=MaterialCatalog.bundled().requirePackage("createcheme:tjl19_dwsim");
        var amounts=current.assays().get("createcheme:tia_juana_light").amounts();
        double mass=0,volume=0;
        for(int i=0;i<amounts.size();i++) {
            var p=current.properties().get(i);mass+=amounts.get(i)*p.molecularWeight();volume+=amounts.get(i)*p.molecularWeight()/p.density();
        }
        assertEquals(159.65286000000003,mass,1e-9);
        assertEquals(.18401666666666672,volume,1e-12);
        var original=V3Tjl19PropertyPackage.INSTANCE;var oldFractions=original.crudeFeed("createcheme:tia_juana_light").moleFractions();
        var loaded=V3PropertyPackageRegistry.require(current.id());
        for(double t:new double[]{293.15,298.15,500,638.15,900}) {
            double oldCp=0,newCp=0,oldH=0,newH=0;
            for(int i=0;i<oldFractions.length;i++) {
                oldCp+=737.6996333000835*oldFractions[i]*original.component(i).idealGasHeatCapacity(t);
                oldH+=737.6996333000835*oldFractions[i]*original.component(i).idealGasEnthalpy(t);
            }
            for(int i=0;i<amounts.size();i++) {
                newCp+=amounts.get(i)*loaded.component(i).idealGasHeatCapacity(t);
                newH+=amounts.get(i)*loaded.component(i).idealGasEnthalpy(t);
            }
            assertEquals(oldCp,newCp,Math.max(1e-8,Math.abs(oldCp)*1e-11));
            assertEquals(oldH,newH,Math.max(1e-8,Math.abs(oldH)*1e-11));
        }
    }
    @Test void productionTransportMeetsTheUnusedDaliaChecksAndSharesTheUnresolvedTail() {
        var catalog=MaterialCatalog.bundled();String id="createcheme:dalia_tjl20";
        var p=catalog.requirePackage(id);var thermo=V3PengRobinsonThermo.fromRegisteredPackage(id);
        var whole=thermo.crudeFeed("createcheme:dalia").moleFractions();var residue=whole.clone();
        for(int i=0;i<residue.length;i++) {
            var name=catalog.name(p.components().get(i));
            double retained=0;
            if(name.kind().equals("petroleum_fraction")) {
                double lo=name.lowerKelvin()==null?Double.NEGATIVE_INFINITY:name.lowerKelvin(),hi=name.upperKelvin()==null?Double.POSITIVE_INFINITY:name.upperKelvin();
                retained=lo>=643.15?1:hi<=643.15?0:(hi-643.15)/(hi-lo);
            }
            residue[i]*=retained;
        }
        var viscosity=new MixtureViscosity(catalog,id);
        double wholeObserved=.0423044914004914,residueObserved=.4893037324285021;
        double wholeError=Math.abs(viscosity.liquid(313.15,whole).pascalSeconds()/wholeObserved-1);
        double residueError=Math.abs(viscosity.liquid(333.15,residue).pascalSeconds()/residueObserved-1);
        assertTrue((wholeError+residueError)/2<=.1);assertTrue(Math.max(wholeError,residueError)<=.2);
        for(double t:new double[]{293.15,298.15,373.15,773.15,900}) {
            double last=catalog.viscosity(id,"crude_pc12",ViscosityCorrelation.Phase.LIQUID).orElseThrow().dynamicViscosityPascalSeconds(t,100000);
            assertEquals(last,catalog.viscosity(id,"crude_pc11",ViscosityCorrelation.Phase.LIQUID).orElseThrow().dynamicViscosityPascalSeconds(t,100000),last*1e-12);
            assertEquals(last,catalog.viscosity(id,"crude_pc10",ViscosityCorrelation.Phase.LIQUID).orElseThrow().dynamicViscosityPascalSeconds(t,100000),last*1e-12);
        }
    }
    @Test void gameplayDrawsRetainTheCapturedPhysicalVolumeTargets() throws Exception {
        var catalog=MaterialCatalog.bundled();
        try(var stream=getClass().getResourceAsStream("/materials/column-draw-volume-targets.json")) {
            assertNotNull(stream);
            var targets=com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(stream,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            for(var element:targets.getAsJsonArray("targets")) {
                var row=element.getAsJsonObject();var preset=ColumnInputPreset.fromId(row.get("preset").getAsString());
                var input=preset.input(catalog);var success=assertInstanceOf(V3ColumnOutcome.Success.class,V3ColumnCalculator.calculate(input));
                var properties=catalog.requirePackage(input.packageId());
                for(var target:row.getAsJsonObject("targets_m3_per_second").entrySet()) {
                    var product=success.result().streams().stream().filter(v->v.streamId().equals(target.getKey())).findFirst().orElseThrow();
                    double volume=0;
                    for(var fraction:product.moleFractions()) {
                        int index=properties.components().indexOf(fraction.componentId());
                        if(index>=0)volume+=product.molarFlowMolPerSecond()*fraction.moleFraction()*properties.properties().get(index).molecularWeight()/properties.properties().get(index).density();
                    }
                    double expected=target.getValue().getAsDouble();
                    assertEquals(expected,volume,expected*1e-5,preset.id()+" "+target.getKey());
                }
            }
        }
    }

    @Test void previouslyDifficultCrudesPublishAuditedClassicalSolutionsWithoutLearnedSeeds() {
        for(var preset:List.of(ColumnInputPreset.BONGA,ColumnInputPreset.COLD_LAKE)) {
            var input=preset.input(MaterialCatalog.bundled());long start=System.nanoTime();
            var result=V3ColumnCalculator.calculate(input,()->{
                if(System.nanoTime()-start>45_000_000_000L)throw new java.util.concurrent.CancellationException("Regrouped preset deadline");
            },0,0,V3InitializationOptions.CURRENT,V3NeuralInitializer.UNAVAILABLE);
            var success=assertInstanceOf(V3ColumnOutcome.Success.class,result,result::toString);
            assertTrue(success.result().convergenceEvidence().satisfiesGates());
            assertTrue(success.result().acceptanceAudit().accepted());
            // Dew-point advisories are retained explicitly; numerical convergence is not a new water-phase model.
        }
    }
}
