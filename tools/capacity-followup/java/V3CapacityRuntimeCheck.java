package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.*;
import com.wormzjl.createcheme.science.column.v3.thermo.V3TraceTruncationPolicy;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Verifies actually loaded class origins, initial native identity and depth parser rejection. */
public final class V3CapacityRuntimeCheck {
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private V3CapacityRuntimeCheck() {}

    public static void main(String[] args) throws Exception {
        if(args.length!=7) throw new IllegalArgumentException("F0 L2 L4 TRAIN source-core source-adapters proof");
        Path core=Path.of(args[4]).toAbsolutePath().normalize();
        Path adapters=Path.of(args[5]).toAbsolutePath().normalize();
        var classes=new ArrayList<Map<String,Object>>();
        for(Class<?> type:List.of(V3ColumnCalculator.class,V3SimultaneousColumnSolver.class,
                V3AcceptanceAuditor.class,V3TraceTruncationPolicy.class,V3BoundedEvaluation.class,
                V3HybridBaseline.class,V3ColumnTransformerInitializer.class,V3CapacityInitializer.class)) {
            Path origin=Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            Path expected=type==V3CapacityInitializer.class?adapters:core;
            require(origin.equals(expected),"Unexpected loaded class origin: "+type.getName());
            String resource=type.getName().replace('.','/')+".class";
            byte[] loaded;
            try(var stream=type.getClassLoader().getResourceAsStream(resource)){loaded=Objects.requireNonNull(stream).readAllBytes();}
            require(Arrays.equals(loaded,Files.readAllBytes(expected.resolve(resource))),"Class resource differs from rebuilt bytes");
            classes.add(Map.of("class",type.getName(),"origin",origin.toString(),"sha256",sha(loaded)));
        }
        V3AnchorAugmentedInitializer base;
        V3CapacityInitializer small,large;
        try(var stream=Files.newInputStream(Path.of(args[0]))){base=V3AnchorAugmentedInitializer.read(stream);}
        try(var stream=Files.newInputStream(Path.of(args[1]))){small=V3CapacityInitializer.read(stream);}
        try(var stream=Files.newInputStream(Path.of(args[2]))){large=V3CapacityInitializer.read(stream);}
        int count=0;double rawMaximum=0,branchMaximum=0;
        for(String line:Files.readAllLines(Path.of(args[3]))) {
            if(line.isBlank())continue;
            JsonObject row=JsonParser.parseString(line).getAsJsonObject();
            require(row.get("split").getAsString().equals("train"),"TRAIN-only preflight");
            V3ColumnInput input=V3NeuralMvpProbe.input(row.getAsJsonObject("input"));
            var a=base.raw(input,V3SolveControl.UNBOUNDED);
            var b=small.raw(input,V3SolveControl.UNBOUNDED);
            var c=large.raw(input,V3SolveControl.UNBOUNDED);
            for(var candidate:List.of(b,c)) {
                rawMaximum=Math.max(rawMaximum,maximumDifference(a.values(),candidate.values()));
                branchMaximum=Math.max(branchMaximum,maximumDifference(a.branchLogits(),candidate.branchLogits()));
            }
            String prediction=JSON.toJson(base.predict(input,V3SolveControl.UNBOUNDED).orElse(null));
            require(prediction.equals(JSON.toJson(small.predict(input,V3SolveControl.UNBOUNDED).orElse(null))),"L2 native decoded identity");
            require(prediction.equals(JSON.toJson(large.predict(input,V3SolveControl.UNBOUNDED).orElse(null))),"L4 native decoded identity");
            count++;
            if(count%100==0)System.out.println("Native initial identity "+count+"/804");
        }
        require(count==804&&rawMaximum==0&&branchMaximum==0,"Exact native all-TRAIN identity");
        JsonObject document=JsonParser.parseString(Files.readString(Path.of(args[2]))).getAsJsonObject();
        int rejected=0;
        for(int layers:new int[]{0,1,2,3,5}) {
            var changed=document.deepCopy();changed.addProperty("layerCount",layers);reject(changed);rejected++;
        }
        var missing=document.deepCopy();missing.getAsJsonObject("weights").remove("blocks.3.linear2.weight");reject(missing);rejected++;
        var extra=document.deepCopy();extra.getAsJsonObject("weights").add("blocks.4.linear2.weight",document.getAsJsonObject("weights").get("blocks.3.linear2.weight").deepCopy());reject(extra);rejected++;
        var shape=document.deepCopy();shape.getAsJsonObject("weights").getAsJsonObject("blocks.3.linear2.weight").getAsJsonArray("shape").set(0,new JsonPrimitive(63));reject(shape);rejected++;
        var result=new LinkedHashMap<String,Object>();result.put("passed",true);result.put("allTrainCases",count);
        result.put("maximumRawDifference",rawMaximum);result.put("maximumBranchLogitDifference",branchMaximum);
        result.put("exactCompleteDecodedPredictions",true);result.put("rejectedMalformedManifests",rejected);
        result.put("loadedClasses",classes);result.put("newCorrectedColumnRequests",0);
        Files.writeString(Path.of(args[6]),JSON.toJson(result),StandardOpenOption.CREATE_NEW);
        System.out.println(JSON.toJson(result));
    }

    private static void reject(JsonObject document) throws Exception {
        try(var stream=new ByteArrayInputStream(JSON.toJson(document).getBytes(StandardCharsets.UTF_8))) {
            try { V3CapacityInitializer.read(stream); }
            catch(IllegalArgumentException expected) { return; }
        }
        throw new IllegalStateException("Malformed capacity manifest was admitted");
    }
    private static double maximumDifference(double[] a,double[] b) {
        require(a.length==b.length,"Shape");double maximum=0;
        for(int i=0;i<a.length;i++){require(Double.isFinite(a[i])&&Double.isFinite(b[i]),"Finite output");maximum=Math.max(maximum,Math.abs(a[i]-b[i]));}
        return maximum;
    }
    private static double maximumDifference(double[][] a,double[][] b) {
        require(a.length==b.length,"Shape");double maximum=0;
        for(int i=0;i<a.length;i++)maximum=Math.max(maximum,maximumDifference(a[i],b[i]));
        return maximum;
    }
    private static String sha(byte[] bytes) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException(message);}
}
