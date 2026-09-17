package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.wormzjl.createcheme.science.material.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class V3NeuralRegistryTest {
    private static final String MAIN="createcheme:tjl20_methane",NETWORK="createcheme:tjl20_methane_nitrogen";
    @AfterEach void reset(){MaterialRuntime.reset();}
    private static V3ColumnInput input() {
        var p=MaterialCatalog.bundled().requirePackage(MAIN);double[] feed=new double[p.components().size()];
        feed[feed.length-2]=50;feed[feed.length-8]=50;
        return new V3ColumnInput(1,MAIN,"test:reader",new V3ComponentBasis(p.components()),feed,550,2,1,250000,750,
                List.of(new V3ColumnSpecification.CondenserOutletTemperature(300),new V3ColumnSpecification.OrganicRefluxRatio(2),
                        new V3ColumnSpecification.ReboilerDuty(0)));
    }
    private static V3ColumnInput on(V3ColumnInput input,String packageId,List<String> ids) {
        double[] feed=new MaterialAxis(ids).project(new MaterialAxis(input.componentBasis().componentIds()),input.feedComponentMolarFlowsMolPerSecond());
        return new V3ColumnInput(1,packageId,input.assayId(),new V3ComponentBasis(ids),feed,input.feedTemperatureKelvin(),input.stageCount(),
                input.feedStageNumber(),input.topPressurePascal(),input.stagePressureDropPascal(),input.specifications(),input.sideDraws(),input.steamFeeds(),input.pumparounds());
    }
    private static V3NeuralRegistry.Entry entry(String name,int priority,boolean extra,boolean missing) throws Exception {
        var d=V3RegroupedTransformerTest.document(input());d.addProperty("modelId",name);
        // Explicit equivalent reference supplies only absent, qualified zero components.
        d.addProperty("packageId","createcheme:wti_light_export_tjl20");
        var model=V3AnchorTransformerInitializer.read(stream(d.toString()));
        return new V3NeuralRegistry.Entry(priority,model,"0".repeat(64),extra,missing);
    }
    private static InputStream stream(String s){return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));}
    @Test void deterministicPriorityAndSecondFixtureModelNeedNoProductionConstants() throws Exception {
        var a=entry("test:a",100,false,false);var b=entry("test:b",101,false,false);
        var catalog=MaterialCatalog.bundled();var input=input();
        assertEquals("test:b",new V3NeuralRegistry(List.of(a,b)).bind(catalog,input).modelId());
        assertEquals("test:a",new V3NeuralRegistry(List.of(entry("test:b",100,false,false),a)).bind(catalog,input).modelId());
        assertSame(V3NeuralInitializer.UNAVAILABLE,new V3NeuralRegistry(List.of()).bind(catalog,input));
        assertThrows(IllegalArgumentException.class,()->new V3NeuralRegistry(List.of(a,a)));
    }
    @Test void identityProjectionIsExplicitAndNeverDiscardsNonzeroFeed() throws Exception {
        var catalog=MaterialCatalog.bundled();var input=input();var network=on(input,NETWORK,catalog.requirePackage(NETWORK).components());
        var qualified=new V3NeuralRegistry(List.of(entry("test:projected",1,true,true)));
        var strict=new V3NeuralRegistry(List.of(entry("test:strict",1,false,false)));
        assertSame(V3NeuralInitializer.UNAVAILABLE,strict.bind(catalog,network));
        var base=qualified.bind(catalog,input).predict(input,V3SolveControl.UNBOUNDED).orElseThrow();
        var prediction=qualified.bind(catalog,network).predict(network,V3SolveControl.UNBOUNDED).orElseThrow();
        assertEquals(network,prediction.input());assertArrayEquals(base.temperatures(),prediction.temperatures());
        for(int n=0;n<base.liquid().length;n++) {
            assertArrayEquals(Arrays.copyOf(base.liquid()[n],network.componentBasis().componentCount()),prediction.liquid()[n]);
            assertArrayEquals(Arrays.copyOf(base.vapor()[n],network.componentBasis().componentCount()),prediction.vapor()[n]);
        }
        double[] contaminated=network.feedComponentMolarFlowsMolPerSecond();contaminated[contaminated.length-1]=1e-100;
        var bad=new V3ColumnInput(1,NETWORK,network.assayId(),network.componentBasis(),contaminated,550,2,1,250000,750,network.specifications());
        assertSame(V3NeuralInitializer.UNAVAILABLE,qualified.bind(catalog,bad));
        var axis=new ArrayList<>(input.componentBasis().componentIds());axis.remove("Methane");
        var reduced=catalog.inferenceView(MAIN,axis,MAIN,false);var fewer=on(input,MAIN,axis);
        assertSame(V3NeuralInitializer.UNAVAILABLE,strict.bind(reduced,fewer));
        var padded=qualified.bind(reduced,fewer).predict(fewer,V3SolveControl.UNBOUNDED).orElseThrow();
        assertArrayEquals(base.temperatures(),padded.temperatures());assertEquals(fewer,padded.input());
        var reversed=new ArrayList<>(input.componentBasis().componentIds());Collections.reverse(reversed);
        var reorder=catalog.inferenceView(MAIN,reversed,MAIN,false);var reordered=on(input,MAIN,reversed);
        var reorderedSeed=strict.bind(reorder,reordered).predict(reordered,V3SolveControl.UNBOUNDED).orElseThrow();
        for(int n=0;n<base.liquid().length;n++)assertArrayEquals(new MaterialAxis(reversed).project(new MaterialAxis(input.componentBasis().componentIds()),base.liquid()[n]),reorderedSeed.liquid()[n]);
    }
    private static MaterialCatalog edit(boolean physics) {
        var resources=new HashMap<>(MaterialCatalog.bundled().resources());
        String path=physics?"data/createcheme/materials/properties/tjl20_methane.json":"data/createcheme/materials/assays/tjl20.json";
        var data=JsonParser.parseString(resources.get(path)).getAsJsonObject();
        if(physics)data.addProperty("molecular_weight_kg_per_mol",.017);
        else {var amounts=data.getAsJsonObject("amounts_by_component");amounts.addProperty("Methane",amounts.get("Methane").getAsDouble()*2);}
        resources.put(path,data.toString());return MaterialCatalog.parse(resources);
    }
    @Test void admissionSnapshotSurvivesReloadAndAssayEditsDoNotChangePhysicsEligibility() throws Exception {
        var catalog=MaterialCatalog.bundled();var registry=new V3NeuralRegistry(List.of(entry("test:captured",1,false,false)));var input=input();
        var bound=registry.bind(catalog,input);var expected=bound.predict(input,V3SolveControl.UNBOUNDED).orElseThrow();
        assertEquals(bound.modelId(),registry.bind(edit(false),input).modelId());
        var changed=edit(true);assertSame(V3NeuralInitializer.UNAVAILABLE,registry.bind(changed,input));
        var ready=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var worker=Executors.newSingleThreadExecutor()) {
            var pending=worker.submit(()->{ready.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("release");return bound.predict(input,V3SolveControl.UNBOUNDED).orElseThrow();});
            assertTrue(ready.await(5,TimeUnit.SECONDS));MaterialRuntime.publish(changed);release.countDown();
            var actual=pending.get(10,TimeUnit.SECONDS);assertArrayEquals(expected.temperatures(),actual.temperatures());
            assertEquals(catalog.requirePackage(MAIN).scientificRevision(),actual.propertyRevision());
            assertFalse(MaterialRuntime.isCurrent(MAIN,actual.propertyRevision()));
        } finally {release.countDown();}
        assertThrows(CancellationException.class,()->bound.predict(input,()->{throw new CancellationException();}));
    }
    @Test void boundedRegistryRejectsPathsAndMismatchedPipelineAndAcceptsEmptyFallback() throws Exception {
        assertSame(V3NeuralInitializer.UNAVAILABLE,V3NeuralRegistry.read(stream("{\"schemaVersion\":1,\"entries\":[]}"),p->null).bind(MaterialCatalog.bundled(),input()));
        String manifest;
        try(var s=getClass().getResourceAsStream(V3NeuralModels.REGISTRY)){manifest=new String(s.readAllBytes(),StandardCharsets.UTF_8);}
        var data=JsonParser.parseString(manifest).getAsJsonObject();var row=data.getAsJsonArray("entries").get(0).getAsJsonObject();
        row.addProperty("pipelineSha256","0".repeat(64));
        assertThrows(IllegalArgumentException.class,()->V3NeuralRegistry.read(stream(data.toString()),getClass()::getResourceAsStream));
        row.addProperty("payload","/data/createcheme/neural/../escape.json");
        assertThrows(IllegalArgumentException.class,()->V3NeuralRegistry.read(stream(data.toString()),p->{throw new AssertionError("Unsafe path was opened");}));
    }
}
