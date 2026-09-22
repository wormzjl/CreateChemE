package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.*;
import com.wormzjl.createcheme.science.material.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Immutable, bounded, bundled-only model registry. Selection never performs neural inference. */
public final class V3NeuralRegistry implements V3NeuralInitializer {
    private static final int MAX_ENTRIES=16;
    record Entry(int priority,V3AnchorTransformerInitializer model,String pipelineSha256,
            boolean allowExtraZeroComponents,boolean allowMissingZeroComponents) {
        Entry { Objects.requireNonNull(model);Objects.requireNonNull(pipelineSha256); }
    }
    @FunctionalInterface interface Resources { InputStream open(String path) throws IOException; }
    private final List<Entry> entries;
    V3NeuralRegistry(List<Entry> entries) {
        if(entries.size()>MAX_ENTRIES)throw new IllegalArgumentException("Too many neural registry entries");
        if(entries.stream().map(e->e.model().modelId()).distinct().count()!=entries.size())
            throw new IllegalArgumentException("Duplicate neural model ID");
        this.entries=entries.stream().sorted(Comparator.comparingInt(Entry::priority).reversed()
                .thenComparing(e->e.model().modelId())).toList();
    }
    static V3NeuralRegistry read(InputStream stream,Resources resources) throws IOException {
        if(stream==null)throw new IllegalArgumentException("Missing neural registry");
        byte[] bytes=stream.readNBytes(65537);
        if(bytes.length>65536)throw new IllegalArgumentException("Neural registry exceeds size limit");
        try {
            var root=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
            if(!root.keySet().equals(Set.of("schemaVersion","entries"))||!root.get("schemaVersion").toString().equals("1"))
                throw new IllegalArgumentException("Invalid neural registry schema");
            var rows=root.getAsJsonArray("entries");
            if(rows.size()>MAX_ENTRIES)throw new IllegalArgumentException("Too many neural registry entries");
            var entries=new ArrayList<Entry>();
            for(var value:rows) {
                var row=value.getAsJsonObject();
                if(!row.keySet().equals(Set.of("priority","modelId","payload","sidecar","pipelineSha256")))
                    throw new IllegalArgumentException("Invalid neural registry entry fields");
                int priority=row.get("priority").getAsBigDecimal().intValueExact();
                try(var payload=resources.open(path(row,"payload"));var sidecar=resources.open(path(row,"sidecar"))) {
                    var loaded=V3TransformerArtifact.read(payload,sidecar);
                    if(!loaded.initializer().modelId().equals(row.get("modelId").getAsString())
                            ||!loaded.pipelineSha256().equals(row.get("pipelineSha256").getAsString()))
                        throw new IllegalArgumentException("Neural registry identity mismatch");
                    entries.add(new Entry(priority,loaded.initializer(),loaded.pipelineSha256(),
                            loaded.allowExtraZeroComponents(),loaded.allowMissingZeroComponents()));
                }
            }
            return new V3NeuralRegistry(entries);
        } catch(JsonParseException|IllegalStateException|ArithmeticException|NullPointerException invalid) {
            throw new IllegalArgumentException("Invalid neural registry",invalid);
        }
    }
    private static String path(JsonObject row,String field) {
        String path=row.get(field).getAsString();
        if(path.length()>256||!path.matches("/data/[a-z0-9_.-]+/neural/[a-z0-9_./-]+\\.json")||path.contains(".."))
            throw new IllegalArgumentException("Invalid bundled neural resource: "+field);
        return path;
    }
    @Override public String modelId(){return "bundled-registry";}
    @Override public V3NeuralInitializer bind(MaterialCatalog catalog,V3ColumnInput input) {
        Objects.requireNonNull(catalog);Objects.requireNonNull(input);
        var source=catalog.packages().get(input.packageId());
        if(source==null||!source.components().equals(input.componentBasis().componentIds()))return UNAVAILABLE;
        for(var entry:entries) {
            var model=entry.model();var axis=new MaterialAxis(model.componentIds());
            var original=new MaterialAxis(source.components());
            if(!entry.allowExtraZeroComponents()&&!axis.ids().containsAll(original.ids()))continue;
            if(!entry.allowMissingZeroComponents()&&!original.ids().containsAll(axis.ids()))continue;
            try {
                double[] feed=axis.project(original,input.feedComponentMolarFlowsMolPerSecond());
                var view=catalog.inferenceView(input.packageId(),axis.ids(),model.referencePackage(),entry.allowMissingZeroComponents());
                if(!view.physicsFingerprint(input.packageId(),axis.ids()).equals(model.physicsFingerprint()))continue;
                var projected=source.components().equals(axis.ids())?input:project(input,axis.ids(),feed);
                if(!MaterialRuntime.with(view,input.packageId(),()->model.supported(projected)))continue;
                return new Bound(entry,catalog,view,input,projected);
            } catch(IllegalArgumentException incompatible) {
                // Unsupported identity/physics is ordinary registry exclusion, never a zero default.
            }
        }
        return UNAVAILABLE;
    }
    @Override public Optional<V3NeuralSeed> predict(V3ColumnInput input,V3SolveControl control) {
        control.checkpoint();return bind(MaterialRuntime.current(),input).predict(input,control);
    }
    private static V3ColumnInput project(V3ColumnInput input,List<String> ids,double[] feed) {
        return new V3ColumnInput(input.schemaVersion(),input.packageId(),input.assayId(),new V3ComponentBasis(ids),feed,
                input.feedTemperatureKelvin(),input.stageCount(),input.feedStageNumber(),input.topPressurePascal(),
                input.stagePressureDropPascal(),input.specifications(),input.sideDraws(),input.steamFeeds(),input.pumparounds(),
                input.columnDiameterMetres());
    }
    private record Bound(Entry entry,MaterialCatalog originalCatalog,MaterialCatalog view,
            V3ColumnInput original,V3ColumnInput projected) implements V3NeuralInitializer {
        @Override public String modelId(){return entry.model().modelId();}
        @Override public Optional<V3NeuralSeed> predict(V3ColumnInput input,V3SolveControl control) {
            control.checkpoint();
            if(!input.equals(original))return Optional.empty();
            return MaterialRuntime.with(view,projected.packageId(),()->entry.model().predict(projected,control)).map(seed->{
                if(projected==original)return seed;
                var target=new MaterialAxis(original.componentBasis().componentIds());
                var source=new MaterialAxis(projected.componentBasis().componentIds());
                double[][] liquid=seed.liquid(),vapor=seed.vapor();
                for(int n=0;n<liquid.length;n++) {
                    control.checkpoint();liquid[n]=target.project(source,liquid[n]);vapor[n]=target.project(source,vapor[n]);
                }
                return new V3NeuralSeed(original,originalCatalog.requirePackage(original.packageId()).scientificRevision(),
                        seed.branch(),liquid,vapor,seed.temperatures(),seed.freeWater(),seed.wetTrays());
            });
        }
    }
}
