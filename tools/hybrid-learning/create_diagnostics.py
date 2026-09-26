"""Derive post-campaign profile diagnostics from preserved native helper definitions."""
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
old=(ROOT/'tools/neural/V3MechanisticSeedProbe.java').read_text()
material=old[old.index('    private record MaterialRows('):old.index('    private static Map<String, Object> check(')]
material=material.replace('description.put("maximumMaterialDefectOverInputComponentScale", materialDefect(seed));',
'''if (seed.input().steamFeeds().isEmpty() && seed.input().sideDraws().isEmpty())
            description.put("maximumMaterialDefectOverInputComponentScale", materialDefect(seed));
        else description.put("fullGridMaterialDiagnostic", "dry/no-side-draw formula inapplicable");''')
material=material.replace('var problem = V3ColumnProblemResolver.withTruncation(full, support);',
'''var wet = seed.wetSetFor(full, V3InitializationOptions.WetStart.PREDICTED_WET);
            var problem = V3ColumnProblemResolver.withTruncation(full, support, wet);''')
old_native=(ROOT/'tools/neural/V3ConcurrentColumnEvaluationProbe.java').read_text()
profile=old_native[old_native.index('    private static Map<String, Object> profileDifference('):old_native.index('    private static V3SolveControl deadline(')]
head='''package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.*;
import com.wormzjl.createcheme.science.column.v3.thermo.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Serial input-only profile diagnostics after timed campaigns; no correction or selection. */
public final class V3HybridProfileDiagnostics {
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private V3HybridProfileDiagnostics() {}
    public static void main(String[] args) throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("pipeline source-jsonl output-jsonl");
        var pipeline=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
        var weight=pipeline.getAsJsonObject("weights");var path=Path.of(weight.get("path").getAsString());
        String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        if(!sha.equals(weight.get("sha256").getAsString()))throw new IllegalArgumentException("Diagnostic weights changed");
        V3NeuralInitializer model;
        if(pipeline.get("kind").getAsString().equals("hybrid-residual"))
            try(var stream=Files.newInputStream(path)){model=V3HybridResidualInitializer.read(stream);}
        else model=V3CandidateModels.read(path);
        boolean completion=pipeline.get("materialCompletion").getAsBoolean();int count=0;
        try(var lines=Files.lines(Path.of(args[1]));var writer=Files.newBufferedWriter(Path.of(args[2]),StandardOpenOption.CREATE_NEW)) {
            for(var iterator=lines.iterator();iterator.hasNext();) {
                var source=JsonParser.parseString(iterator.next()).getAsJsonObject();
                var input=V3NeuralMvpProbe.input(source.getAsJsonObject("input"));
                var row=new LinkedHashMap<String,Object>();row.put("id",source.get("id"));row.put("input",source.get("input"));
                row.put("pipeline",pipeline.get("id"));row.put("correctedRequest",false);row.put("usedForSelection",false);
                try {
                    var raw=model.predict(input,deadline(30_000)).orElse(null);row.put("rawSupported",raw!=null);
                    if(raw!=null) {
                        row.put("raw",describe(raw));V3NeuralSeed prepared=raw;
                        if(completion)prepared=V3MechanisticTransformerInitializer.prepare(raw,deadline(30_000),e->row.put("preparation",e));
                        else row.put("preparation",Map.of("status","DISABLED","prepared",false));
                        row.put("final",describe(prepared));row.put("profileChange",profileChange(raw,prepared));
                        if(source.has("referenceCertified")&&source.get("referenceCertified").getAsBoolean()) {
                            var reference=seed(input,source.getAsJsonObject("seed"));
                            row.put("rawVsOriginalCertifiedReference",profileDifference(raw,reference));
                            row.put("finalVsOriginalCertifiedReference",profileDifference(prepared,reference));
                        }
                    }
                } catch(V3ThermoException|IllegalArgumentException|CancellationException unavailable) {
                    row.put("diagnosticUnavailable",unavailable.getClass().getSimpleName()+": "+unavailable.getMessage());
                }
                writer.write(JSON.toJson(row));writer.newLine();writer.flush();
                if(++count%100==0)System.out.println("diagnostic inputs="+count);
            }
        }
        System.out.println("complete diagnostic inputs="+count);
    }
'''
tail='''    private static V3SolveControl deadline(long milliseconds) {
        long start=System.nanoTime();return ()->{
            if(Thread.currentThread().isInterrupted()||System.nanoTime()-start>=milliseconds*1_000_000L)
                throw new CancellationException("post-campaign diagnostic budget");
        };
    }
}
'''
out=ROOT/'tools/hybrid-learning/diagnostic-java/V3HybridProfileDiagnostics.java';out.parent.mkdir(exist_ok=True)
out.write_text(head+material+profile+tail,encoding='utf-8',newline='\n')
