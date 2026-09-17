package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class V3RegroupedTransformerTest {
    private static final Gson JSON = new Gson();
    private static V3ColumnInput input(String packageId) {
        var p = MaterialCatalog.bundled().requirePackage(packageId);
        double[] feed = new double[p.components().size()];
        feed[feed.length-2] = 50; feed[feed.length-8] = 50;
        return new V3ColumnInput(1, packageId, "test:reader", new V3ComponentBasis(p.components()),
                feed, 550, 2, 1, 250000, 750, List.of(
                new V3ColumnSpecification.CondenserOutletTemperature(300),
                new V3ColumnSpecification.OrganicRefluxRatio(2), new V3ColumnSpecification.ReboilerDuty(0)));
    }
    static JsonObject document(V3ColumnInput input) {
        int c=input.componentBasis().componentCount(), nodes=76+c, global=54+c, out=4*c+5;
        var d=new JsonObject();
        d.addProperty("featureRevision","v3-anchor-augmented-regrouped-2");
        d.addProperty("modelType","anchor-augmented"); d.addProperty("anchorLayout","full");
        d.addProperty("baselineRevision",V3NativeAnchor.REVISION); d.addProperty("modelId","test:synthetic");
        d.addProperty("packageId",input.packageId());
        var p=MaterialCatalog.bundled().requirePackage(input.packageId());
        d.addProperty("propertyRevision",p.scientificRevision()); d.addProperty("propertyFingerprint",p.fingerprint());
        d.add("packageFingerprints",JSON.toJsonTree(Map.of(input.packageId(),p.fingerprint())));
        d.add("components",JSON.toJsonTree(input.componentBasis().componentIds()));
        double[] z=input.feedComponentMolarFlowsMolPerSecond(); double total=Arrays.stream(z).sum();
        for(int i=0;i<z.length;i++)z[i]/=total;
        d.add("compositionEdges",JSON.toJsonTree(new double[][][]{{z,z}}));
        d.add("formulationRevisions",JSON.toJsonTree(List.of(V3ColumnCalculator.formulationRevision(input,0,V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE))));
        d.add("branchesSeen",JSON.toJsonTree(new boolean[]{true,true,false}));
        d.addProperty("presenceThreshold",.02); d.addProperty("traceFloorFraction",V3TruncationSupport.TRACE_FLOOR_FRACTION);
        d.add("globalMin",JSON.toJsonTree(fill(global,-1e6))); d.add("globalMax",JSON.toJsonTree(fill(global,1e6)));
        d.add("designConstraints",JSON.toJsonTree(Map.of("minimumNodePressurePascal",50000,"maximumNodePressurePascal",300000,"steamAtSumpOnly",true,"pumparoundSplits",List.of("UNIFORM"))));
        var norms=new JsonObject();
        for(var prefix:List.of("x","g","y","b")) {
            int width=prefix.equals("x")?nodes:prefix.equals("g")?global:out;
            norms.add(prefix+"m",JSON.toJsonTree(fill(width,0))); norms.add(prefix+"scale",JSON.toJsonTree(fill(width,1)));
        }
        d.add("normalization",norms);
        var weights=new JsonObject(); linear(weights,"embed",nodes+out+4,64);
        norm(weights,"output.0"); linear(weights,"output.1",64,out);
        linear(weights,"branch.0",global,64); linear(weights,"branch.2",64,3);
        for(int i=0;i<2;i++) {
            String a="blocks."+i+".";
            norm(weights,a+"norm1"); norm(weights,a+"norm2");
            tensor(weights,a+"self_attn.in_proj_weight",0,192,64); tensor(weights,a+"self_attn.in_proj_bias",0,192);
            linear(weights,a+"self_attn.out_proj",64,64); linear(weights,a+"linear1",64,128); linear(weights,a+"linear2",128,64);
        }
        var bias=weights.getAsJsonObject("output.1.bias").getAsJsonArray("values");
        bias.set(0,new JsonPrimitive(370)); bias.set(1,new JsonPrimitive(1)); bias.set(2,new JsonPrimitive(1));
        d.add("weights",weights); return d;
    }
    private static double[] fill(int n,double value) { double[] a=new double[n]; Arrays.fill(a,value); return a; }
    private static void tensor(JsonObject w,String name,double v,int... shape) {
        int count=1; for(int n:shape)count*=n;
        w.add(name,JSON.toJsonTree(Map.of("shape",shape,"values",fill(count,v))));
    }
    private static void linear(JsonObject w,String name,int in,int out) {tensor(w,name+".weight",0,out,in);tensor(w,name+".bias",0,out);}
    private static void norm(JsonObject w,String name) {tensor(w,name+".weight",1,64);tensor(w,name+".bias",0,64);}
    private static InputStream stream(JsonObject d) {return new ByteArrayInputStream(d.toString().getBytes(StandardCharsets.UTF_8));}
    @Test void widthsFollowBothActiveHydrocarbonAxes() throws Exception {
        for(String p:List.of("createcheme:tjl19_dwsim","createcheme:tjl20_methane")) {
            var input=input(p); var model=V3AnchorTransformerInitializer.read(stream(document(input)));
            assertTrue(model.supported(input));
            assertEquals(input.componentBasis().componentCount()==19?88852:88208,model.parameterCount());
            assertEquals(4*input.componentBasis().componentCount()+5,model.raw(input,V3SolveControl.UNBOUNDED).values()[0].length);
        }
    }
    @Test void rejectsRetiredEncodingAndWrongTensors() {
        var d=document(input("createcheme:tjl20_methane")); d.addProperty("featureRevision","v3-anchor-augmented-1");
        assertThrows(IllegalArgumentException.class,()->V3AnchorTransformerInitializer.read(stream(d)));
        d.addProperty("featureRevision","v3-anchor-augmented-regrouped-2");
        d.getAsJsonObject("weights").getAsJsonObject("embed.weight").add("shape",JSON.toJsonTree(new int[]{64,185}));
        assertThrows(IllegalArgumentException.class,()->V3AnchorTransformerInitializer.read(stream(d)));
    }
    @Test void cancellationAndSharedWeightsAreDeterministic() throws Exception {
        var input=input("createcheme:tjl20_methane"); var model=V3AnchorTransformerInitializer.read(stream(document(input)));
        assertThrows(CancellationException.class,()->model.raw(input,()->{throw new CancellationException();}));
        var expected=model.raw(input,V3SolveControl.UNBOUNDED);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var a=workers.submit(()->model.raw(input,V3SolveControl.UNBOUNDED));
            var b=workers.submit(()->model.raw(input,V3SolveControl.UNBOUNDED));
            for(var result:List.of(a.get(),b.get())) {
                assertArrayEquals(expected.branchLogits(),result.branchLogits());
                for(int n=0;n<expected.values().length;n++)assertArrayEquals(expected.values()[n],result.values()[n]);
            }
        }
    }
    @Test void editedPhysicsIsExcludedWhileCapturedCatalogRemainsEligible() throws Exception {
        var input=input("createcheme:tjl20_methane"); var original=MaterialCatalog.bundled();
        var model=V3AnchorTransformerInitializer.read(stream(document(input)));
        var records=new HashMap<>(original.resources());
        String key=records.keySet().stream().filter(k->k.endsWith("properties/crude_pc08.json")).findFirst().orElseThrow();
        var edited=JsonParser.parseString(records.get(key)).getAsJsonObject();
        var pr=edited.getAsJsonObject("models").getAsJsonObject("pr78");
        pr.addProperty("critical_temperature_kelvin",pr.get("critical_temperature_kelvin").getAsDouble()+1);
        records.put(key,edited.toString()); var changed=MaterialCatalog.parse(records);
        assertFalse(com.wormzjl.createcheme.science.material.MaterialRuntime.with(changed,input.packageId(),()->model.supported(input)));
        assertTrue(com.wormzjl.createcheme.science.material.MaterialRuntime.with(original,input.packageId(),()->model.supported(input)));
    }
    @Test void sidecarPinsPayloadAndRejectsOverlappingFields() throws Exception {
        var d=document(input("createcheme:tjl20_methane")); var payload=new JsonObject();
        for(String key:List.of("featureRevision","modelType","anchorLayout","baselineRevision","components","normalization","weights"))payload.add(key,d.remove(key));
        payload.addProperty("schemaVersion",1); d.addProperty("schemaVersion",1);
        d.addProperty("payloadSha256",V3TransformerArtifact.sha256(payload.toString().getBytes(StandardCharsets.UTF_8)));
        d.addProperty("decoder","ZERO_PHASE_FLOOR_10"); d.addProperty("candidateRule","SINGLE"); d.addProperty("correctionRule","PROGRESS");
        assertEquals(88852,V3TransformerArtifact.read(stream(payload),stream(d)).initializer().parameterCount());
        payload.addProperty("baselineRevision","changed");
        assertThrows(IllegalArgumentException.class,()->V3TransformerArtifact.read(stream(payload),stream(d)));
        payload.addProperty("baselineRevision",V3NativeAnchor.REVISION); d.add("weights",payload.get("weights"));
        assertThrows(IllegalArgumentException.class,()->V3TransformerArtifact.read(stream(payload),stream(d)));
    }
}
