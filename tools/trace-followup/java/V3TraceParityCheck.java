package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Native reconstruction parity and deterministic shared-model ownership checks. */
public final class V3TraceParityCheck {
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private V3TraceParityCheck() {}
    public static void main(String[] args) throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("model fixture output");
        V3NeuralInitializer model;
        String feature=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject().get("featureRevision").getAsString();
        if(feature.equals("v3-anchor-augmented-1"))
            try(var stream=Files.newInputStream(Path.of(args[0]))){model=V3AnchorAugmentedInitializer.read(stream);}
        else model=V3CandidateModels.read(Path.of(args[0]));
        var fixtures=JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonArray();
        double maxRaw=0,maxTemperature=0,maxFlow=0,maxBranch=0;
        var inputs=new ArrayList<V3ColumnInput>();var serial=new ArrayList<String>();
        V3NeuralInitializer wrapped=model;
        for(var element:fixtures){
            var row=element.getAsJsonObject();var input=V3NeuralMvpProbe.input(row.getAsJsonObject("input"));inputs.add(input);
            var branch=V3CondenserPhaseBranch.valueOf(row.get("branch").getAsString());
            var anchor=V3HybridBaseline.build(input,branch,V3SolveControl.UNBOUNDED);
            var expectedAnchor=row.getAsJsonObject("anchor");
            require(anchor.available()==expectedAnchor.get("available").getAsBoolean(),"anchor availability");
            require(maxDifference(anchor.values(),JSON.fromJson(expectedAnchor.get("values"),double[][].class))==0,"anchor parity");
            var raw=raw(model,input);
            maxRaw=Math.max(maxRaw,maxDifference(raw.values(),JSON.fromJson(row.get("raw"),double[][].class)));
            maxBranch=Math.max(maxBranch,maxDifference(raw.branchLogits(),JSON.fromJson(row.get("branchLogits"),double[].class)));
            var decoded=model.predict(input,V3SolveControl.UNBOUNDED).orElseThrow();
            var target=row.getAsJsonObject("decoded");require(decoded.branch()==branch,"decoded branch");
            require(Arrays.equals(decoded.wetTrays(),JSON.fromJson(target.get("wetTrays"),boolean[].class)),"wet mask");
            maxTemperature=Math.max(maxTemperature,maxDifference(decoded.temperatures(),JSON.fromJson(target.get("temperatures"),double[].class)));
            double feed=Arrays.stream(input.feedComponentMolarFlowsMolPerSecond()).sum();
            maxFlow=Math.max(maxFlow,maxDifference(decoded.freeWater(),JSON.fromJson(target.get("freeWater"),double[].class))/feed);
            for(var phase:List.of("liquid","vapor")){
                double[][] actual=phase.equals("liquid")?decoded.liquid():decoded.vapor();
                double[][] expected=JSON.fromJson(target.get(phase),double[][].class);
                maxFlow=Math.max(maxFlow,maxDifference(actual,expected)/feed);
                for(int n=0;n<actual.length;n++)for(int c=0;c<actual[n].length;c++)
                    require((actual[n][c]==0)==(expected[n][c]==0),"zero support mask");
            }
            serial.add(JSON.toJson(wrapped.predict(input,V3SolveControl.UNBOUNDED).orElse(null)));
        }
        require(maxRaw<5e-5 && maxTemperature<5e-5 && maxFlow<2e-6 && maxBranch<5e-5,"numeric tolerance");
        var first=inputs.getFirst();
        var reflux=inputs.stream().filter(i->i.specifications().stream().anyMatch(s->s instanceof V3ColumnSpecification.OrganicRefluxRatio r&&r.ratio()>0)).findFirst().orElseThrow();
        var unavailable=V3HybridBaseline.build(reflux,V3CondenserPhaseBranch.VAPOR_ONLY,V3SolveControl.UNBOUNDED);
        require(!unavailable.available(),"illegal branch baseline retained as unavailable");
        require(Arrays.stream(unavailable.values()).flatMapToDouble(Arrays::stream).allMatch(v->v==0),"neutral failed baseline");
        var calls=new AtomicInteger();boolean cancelled=false;
        try{V3HybridBaseline.build(first,V3CondenserPhaseBranch.TWO_PHASE,()->{if(calls.incrementAndGet()==3)throw new CancellationException("test property boundary");});}
        catch(CancellationException expected){cancelled=true;}
        require(cancelled,"baseline cancellation propagates");
        var predictionCalls=new AtomicInteger();boolean predictionCancelled=false;
        try{model.predict(first,()->{if(predictionCalls.incrementAndGet()==100)throw new CancellationException("test inference cancellation");});}
        catch(CancellationException expected){predictionCancelled=true;}
        require(predictionCancelled,"model inference cancellation propagates");
        var tasks=new ArrayList<Callable<Boolean>>();var start=new CountDownLatch(10);
        for(int i=0;i<40;i++){int index=i%inputs.size();boolean firstWave=i<10;
            tasks.add(()->{if(firstWave){start.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException("barrier timeout");}
                return serial.get(index).equals(JSON.toJson(wrapped.predict(inputs.get(index),V3SolveControl.UNBOUNDED).orElse(null)));});}
        var scheduling=V3BoundedEvaluation.run(tasks,10,(same,wait)->require(same,"shared pipeline repeatability"));
        require(scheduling.terminated()&&scheduling.distinctWorkerThreads()==10,"owned workers terminate");
        require(serial.getFirst().equals(JSON.toJson(wrapped.predict(first,V3SolveControl.UNBOUNDED).orElse(null))),"later prediction unchanged");
        var result=new LinkedHashMap<String,Object>();result.put("passed",true);result.put("fixtures",fixtures.size());
        result.put("maximumRawDifference",maxRaw);result.put("maximumTemperatureDifferenceK",maxTemperature);
        result.put("maximumFlowDifferenceOverFeed",maxFlow);result.put("maximumBranchLogitDifference",maxBranch);
        result.put("exactAnchorParity",true);result.put("exactMasks",true);result.put("baselineFailureRetained",true);
        result.put("baselineCancellationPropagated",cancelled);result.put("predictionCancellationPropagated",predictionCancelled);result.put("parallelComparisons",40);result.put("scheduling",scheduling);
        Files.writeString(Path.of(args[2]),JSON.toJson(result),StandardOpenOption.CREATE_NEW);System.out.println(JSON.toJson(result));
    }
    private record Values(double[][] values,double[] branchLogits) {}
    private static Values raw(V3NeuralInitializer model,V3ColumnInput input) {
        if(model instanceof V3AnchorAugmentedInitializer anchor){var r=anchor.raw(input,V3SolveControl.UNBOUNDED);return new Values(r.values(),r.branchLogits());}
        if(model instanceof V3ColumnTransformerInitializer plain){var r=plain.raw(input,V3SolveControl.UNBOUNDED);return new Values(r.values(),r.branchLogits());}
        throw new IllegalArgumentException("Unexpected parity model type");
    }
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
    private static double maxDifference(double[] a,double[] b){require(a.length==b.length,"vector shape");double max=0;for(int i=0;i<a.length;i++){require(Double.isFinite(a[i])&&Double.isFinite(b[i]),"finite parity");max=Math.max(max,Math.abs(a[i]-b[i]));}return max;}
    private static double maxDifference(double[][] a,double[][] b){require(a.length==b.length,"matrix shape");double max=0;for(int i=0;i<a.length;i++)max=Math.max(max,maxDifference(a[i],b[i]));return max;}
}
