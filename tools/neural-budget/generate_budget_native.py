"""Derive this study's initializer and campaign probe from the sealed predecessors.

Neither predecessor source is edited. Every substitution is asserted to be unique, so a silent upstream
change cannot be absorbed, and the numerical body of the initializer and every measurement helper of the
evaluation probe are required to stay byte identical to their origin. The only deliberate difference in
the probe is that the correction budget of a request comes from the pipeline manifest instead of being
fixed in the binary, which is exactly the intervention this study measures.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TRACE = ROOT / 'tools/trace-followup/java'
FLOOR = ROOT / 'tools/decoder-floor-followup/java'
DEST = ROOT / 'tools/neural-budget/java'


def once(text, old, new):
    assert text.count(old) == 1, old
    return text.replace(old, new)


def initializer():
    text = (TRACE / 'V3AnchorAugmentedInitializer.java').read_text(encoding='utf-8')
    # The feature revision and every manifest check stay as they are: the model bytes are frozen and the
    # decoder variant is chosen by the pipeline manifest alone.
    text = text.replace('V3AnchorAugmentedInitializer', 'V3BudgetInitializer')
    text = once(text, 'Offline absolute-output Transformer with optional full or compact native anchor inputs.',
                'Offline absolute-output Transformer with a pipeline-selected decoder rule.')
    text = once(text, '    private final Document model;\n'
                      '    private V3BudgetInitializer(Document model) { this.model = model; }',
                '    private final Document model;\n'
                '    /** Decoder variant selected by the pipeline manifest, never by the model bytes. */\n'
                '    private final V3FactorizedNeuralFeatures.DecodeOptions decode;\n'
                '    private V3BudgetInitializer(Document model, V3FactorizedNeuralFeatures.DecodeOptions decode) {\n'
                '        this.model = model; this.decode = decode;\n'
                '    }')
    text = once(text, '    static V3BudgetInitializer read(InputStream stream) throws IOException {\n'
                      '        byte[] bytes',
                '    static V3BudgetInitializer read(InputStream stream) throws IOException {\n'
                '        return read(stream, V3FactorizedNeuralFeatures.DecodeOptions.NONE);\n'
                '    }\n\n'
                '    static V3BudgetInitializer read(InputStream stream, V3FactorizedNeuralFeatures.DecodeOptions decode) throws IOException {\n'
                '        if (decode == null) throw new IllegalArgumentException("Missing pipeline decode options");\n'
                '        byte[] bytes')
    text = once(text, 'var result = new V3BudgetInitializer(m);', 'var result = new V3BudgetInitializer(m, decode);')
    text = once(text, 'raw.values, model.presenceThreshold));', 'raw.values, model.presenceThreshold, decode));')
    (DEST / 'V3BudgetInitializer.java').write_text(text, encoding='utf-8', newline='\n')


def probe():
    origin = (TRACE / 'V3TraceEvaluationProbe.java').read_text(encoding='utf-8')
    text = (FLOOR / 'V3FloorEvaluationProbe.java').read_text(encoding='utf-8')
    text = text.replace('V3FloorEvaluationProbe', 'V3BudgetEvaluationProbe')
    text = once(text, 'V3FloorModels.read', 'V3BudgetModels.read')
    text = once(text, 'decoder-floor-column-evaluation-v1', 'neural-budget-column-evaluation-v1')
    # One pipeline per process. The correction policy is read in main, before any worker thread exists,
    # and is then immutable; the ten workers only read it. The command line keeps stating the production
    # wall pair, and a pipeline that does not reshape the budget must reproduce it exactly.
    text = once(text, '    private V3BudgetEvaluationProbe() {}',
                '    private static volatile V3BudgetModels.Correction correction;\n\n'
                '    private V3BudgetEvaluationProbe() {}')
    text = once(text, '        V3NeuralInitializer model = V3BudgetModels.read(modelPath);',
                '        V3NeuralInitializer model = V3BudgetModels.read(modelPath);\n'
                '        correction = V3BudgetModels.correction(modelPath, maximumIterations, Math.toIntExact(neuralBudgetMillis));')
    text = once(text, 'metadata.put("neuralMaximumIterations", maximumIterations); metadata.put("caseCount", requests.size());',
                'metadata.put("neuralMaximumIterations", maximumIterations); metadata.put("caseCount", requests.size());\n'
                '        metadata.put("correction", correction.manifest());\n'
                '        metadata.put("correctionIterations", correction.options(V3InitializationOptions.Mode.LNN_ONLY).maximumIterations());\n'
                '        metadata.put("correctionBudgetMillis", correction.options(V3InitializationOptions.Mode.LNN_ONLY).budgetMilliseconds());')
    marker = '    private static Map<String, Object> evaluate'
    # Everything from the first measurement helper onwards is byte identical to the origin at this point:
    # the substitutions above are confined to main. The single deliberate change inside that region is
    # applied next, and it is the whole of this study's difference from the predecessor campaign's probe.
    assert text[text.index(marker):] == origin[origin.index(marker):], 'Measurement helpers diverged'
    text = once(text, 'new V3InitializationOptions(mode, V3InitializationOptions.WetStart.AUTO, maximumIterations, '
                      'Math.toIntExact(neuralBudgetMillis)), model, profile -> accepted[0] = profile);',
                'correction.options(mode), model, profile -> accepted[0] = profile);')
    changed = [pair for pair in zip(text[text.index(marker):].splitlines(),
                                    origin[origin.index(marker):].splitlines()) if pair[0] != pair[1]]
    assert len(changed) == 1 and 'correction.options(mode)' in changed[0][0], changed
    (DEST / 'V3BudgetEvaluationProbe.java').write_text(text, encoding='utf-8', newline='\n')


def main():
    DEST.mkdir(parents=True, exist_ok=True)
    initializer()
    probe()
    print('Generated the pipeline initializer and the campaign probe; measurement helpers are byte identical.')


if __name__ == '__main__':
    main()
