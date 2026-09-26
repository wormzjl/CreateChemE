"""Add a pipeline-aware offline probe while preserving native measurement helpers."""
from pathlib import Path
root=Path(__file__).resolve().parents[2]
source=(root/'tools/neural/V3ConcurrentColumnEvaluationProbe.java').read_text()
source=source.replace('V3ConcurrentColumnEvaluationProbe','V3HybridEvaluationProbe')
source=source.replace('concurrent-column-evaluation-v1','hybrid-column-evaluation-v1')
source=source.replace('args.length != 7','args.length != 8')
source=source.replace('model workers deadline-seconds neural-budget-ms iterations','pipeline workers deadline-seconds neural-budget-ms iterations warmup-json')
source=source.replace('V3CandidateModels.read(modelPath)','V3HybridModels.read(modelPath)')
source=source.replace('V3CandidateModels.parameterStorageBytes(model)','Files.size(modelPath)')
source=source.replace('"modelParameterStorageBytes"','"pipelineManifestBytes"')
source=source.replace('Path.of("tools/neural/methane-qualification.json")','Path.of(args[7])')
source=source.replace('metadata.put("modelId", model.modelId());','metadata.put("modelId", model.modelId());\n        metadata.put("pipelineManifest", JsonParser.parseString(Files.readString(modelPath)));\n        metadata.put("warmupSha256", sha256(Path.of(args[7])));')
source=source.replace('try { raw = model.predict(input, deadline(start, deadlineMillis)).orElse(null); }','''try {
            raw = (model instanceof V3MechanisticTransformerInitializer wrapped
                    ? wrapped.predict(input, deadline(start, deadlineMillis), evidence -> prediction.put("materialCompletion", evidence))
                    : model.predict(input, deadline(start, deadlineMillis))).orElse(null);
        }''')
# Diagnostic construction is outside run() and outside all reported request times.
source=source.replace('if (raw != null) prediction.put("nativeResidual", residual(raw));','''if (raw != null) {
            prediction.put("nativeResidual", residual(raw));
            long baselineStart = System.nanoTime();
            var anchor = V3HybridBaseline.build(input, raw.branch(), deadline(baselineStart, deadlineMillis));
            prediction.put("separateAnchorDiagnostic", Map.of("available", anchor.available(), "reason", anchor.reason(),
                    "milliseconds", (System.nanoTime() - baselineStart) / 1e6, "insideRequestTiming", false));
        }''')
start='    private static Map<String, Object> run('
end='    private static Map<String, Object> residual('
old=(root/'tools/neural/V3ConcurrentColumnEvaluationProbe.java').read_text()
assert old[old.index(start):old.index(end)] in source
(root/'tools/hybrid-learning/java/V3HybridEvaluationProbe.java').write_text(source,encoding='utf-8',newline='\n')
