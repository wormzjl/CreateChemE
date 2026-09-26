"""Derive an additive native adapter while preserving frozen measurement helpers."""
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
SOURCE=ROOT/'tools/hybrid-learning/java'
DEST=ROOT/'tools/trace-followup/java'


def replace_once(text,old,new):
    assert text.count(old)==1,old
    return text.replace(old,new)


def main():
    DEST.mkdir(parents=True,exist_ok=True)
    text=(SOURCE/'V3HybridResidualInitializer.java').read_text(encoding='utf-8')
    text=text.replace('V3HybridResidualInitializer','V3AnchorAugmentedInitializer')
    text=text.replace('v3-hybrid-residual-1','v3-anchor-augmented-1').replace('"hybrid-residual"','"anchor-augmented"')
    text=text.replace('Offline native-anchor/residual transformer candidate.','Offline absolute-output Transformer with optional full or compact native anchor inputs.')
    text=replace_once(text,'    @Override public String modelId()',
        '    private static int inputs(Document document) { return "compact".equals(document.anchorLayout) ? 103 : 185; }\n\n    @Override public String modelId()')
    text=replace_once(text,'|| m.modelId == null','|| !("compact".equals(m.anchorLayout) || "full".equals(m.anchorLayout))\n                || m.modelId == null')
    text=replace_once(text,'"bm", "bscale", "dm", "dscale"','"bm", "bscale"')
    text=replace_once(text,'result.checkLinear("embed", 185, 64)','result.checkLinear("embed", inputs(m), 64)')
    text=replace_once(text,'long expected = m.modelType.equals("anchor-augmented") ? 89496 : 83288;',
        'long expected = 83800 + (inputs(m) - 96) * 64L;')
    text=replace_once(text,'double[] joined = new double[185];','double[] joined = new double[inputs(model)];')
    text=replace_once(text,'            System.arraycopy(normalize(anchor.values()[i], "b"), 0, joined, 96, 85);\n            joined[181 + best] = 1;\n            joined[184] = anchor.available() ? 1 : 0;',
        '            double[] encoded = normalize(anchor.values()[i], "b");\n            int at = 96;\n            for (int coordinate = 0; coordinate < 85; coordinate++)\n                if ("full".equals(model.anchorLayout) || coordinate < 3)\n                    joined[at++] = encoded[coordinate];\n            joined[at + best] = 1;\n            joined[at + 3] = anchor.available() ? 1 : 0;')
    text=replace_once(text,'(i < 43 ? anchor.values()[n][i] : 0) + values[n][i] * model.normalization.get("dscale")[i] + model.normalization.get("dm")[i]',
        'values[n][i] * model.normalization.get("yscale")[i] + model.normalization.get("ym")[i]')
    text=replace_once(text,'String featureRevision, modelType, modelId, packageId, propertyRevision;',
        'String featureRevision, modelType, modelId, packageId, propertyRevision, anchorLayout;')
    (DEST/'V3AnchorAugmentedInitializer.java').write_text(text,encoding='utf-8',newline='\n')
    text=(SOURCE/'V3HybridModels.java').read_text(encoding='utf-8').replace('V3HybridModels','V3TraceModels')
    text=replace_once(text,'V3NeuralInitializer model;','V3NeuralInitializer model;\n        if (doc.get("kind").getAsString().equals("anchor-augmented"))\n            try(var stream=Files.newInputStream(file)){model=V3AnchorAugmentedInitializer.read(stream);}\n        else')
    (DEST/'V3TraceModels.java').write_text(text,encoding='utf-8',newline='\n')
    text=(SOURCE/'V3HybridParityCheck.java').read_text(encoding='utf-8')
    text=text.replace('V3HybridParityCheck','V3TraceParityCheck').replace('V3HybridResidualInitializer','V3AnchorAugmentedInitializer')
    text=replace_once(text,'        V3AnchorAugmentedInitializer model;\n        try(var stream=Files.newInputStream(Path.of(args[0]))){model=V3AnchorAugmentedInitializer.read(stream);}',
        '        V3NeuralInitializer model;\n        String feature=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject().get("featureRevision").getAsString();\n        if(feature.equals("v3-anchor-augmented-1"))\n            try(var stream=Files.newInputStream(Path.of(args[0]))){model=V3AnchorAugmentedInitializer.read(stream);}\n        else model=V3CandidateModels.read(Path.of(args[0]));')
    text=replace_once(text,'        var wrapped=new V3MechanisticTransformerInitializer(model);','        V3NeuralInitializer wrapped=model;')
    text=replace_once(text,'            var raw=model.raw(input,V3SolveControl.UNBOUNDED);','            var raw=raw(model,input);')
    text=replace_once(text,'        var tasks=new ArrayList<Callable<Boolean>>();',
        '        var predictionCalls=new AtomicInteger();boolean predictionCancelled=false;\n        try{model.predict(first,()->{if(predictionCalls.incrementAndGet()==100)throw new CancellationException("test inference cancellation");});}\n        catch(CancellationException expected){predictionCancelled=true;}\n        require(predictionCancelled,"model inference cancellation propagates");\n        var tasks=new ArrayList<Callable<Boolean>>();')
    text=replace_once(text,'result.put("baselineCancellationPropagated",cancelled);',
        'result.put("baselineCancellationPropagated",cancelled);result.put("predictionCancellationPropagated",predictionCancelled);')
    text=replace_once(text,'    private static void require(boolean condition,String message)',
        '    private record Values(double[][] values,double[] branchLogits) {}\n    private static Values raw(V3NeuralInitializer model,V3ColumnInput input) {\n        if(model instanceof V3AnchorAugmentedInitializer anchor){var r=anchor.raw(input,V3SolveControl.UNBOUNDED);return new Values(r.values(),r.branchLogits());}\n        if(model instanceof V3ColumnTransformerInitializer plain){var r=plain.raw(input,V3SolveControl.UNBOUNDED);return new Values(r.values(),r.branchLogits());}\n        throw new IllegalArgumentException("Unexpected parity model type");\n    }\n    private static void require(boolean condition,String message)')
    (DEST/'V3TraceParityCheck.java').write_text(text,encoding='utf-8',newline='\n')
    text=(SOURCE/'V3HybridEvaluationProbe.java').read_text(encoding='utf-8')
    text=text.replace('V3HybridEvaluationProbe','V3TraceEvaluationProbe').replace('V3HybridModels.read','V3TraceModels.read')
    text=text.replace('hybrid-column-evaluation-v1','trace-followup-column-evaluation-v1')
    capture='        if (raw != null) prediction.put("seedPresentedByPipeline", raw);\n'
    text=replace_once(text,'        row.put("rawPrediction", prediction);',capture+'        row.put("rawPrediction", prediction);')
    original=(SOURCE/'V3HybridEvaluationProbe.java').read_text(encoding='utf-8')
    assert text.replace(capture,'')[text.replace(capture,'').index('    private static Map<String, Object> evaluate'):]==original[original.index('    private static Map<String, Object> evaluate'):]
    (DEST/'V3TraceEvaluationProbe.java').write_text(text,encoding='utf-8',newline='\n')
    print('Generated four additive adapters; measurement equations unchanged, with an additional capture of the already computed pipeline seed.')


if __name__=='__main__':main()
