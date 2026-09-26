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
    """Verify the promoted production initializer still carries this study's numerical body.

    The derived `V3BudgetInitializer` no longer exists: the transformer-promotion work moved exactly this
    class into `src/main` as `V3AnchorTransformerInitializer`, bundled the frozen F0 weights beside it and
    made it the shipped default. Nothing is generated any more, because a campaign that regenerated an
    offline twin could no longer claim to be measuring what the game runs. What is still checked is that
    the production class is the sealed predecessor's arithmetic under the renames this study applied, so a
    silent upstream change cannot be absorbed here either.
    """
    production = (ROOT / 'src/main/java/com/wormzjl/createcheme/science/column/v3'
                  / 'V3AnchorTransformerInitializer.java')
    origin = (TRACE / 'V3AnchorAugmentedInitializer.java').read_text(encoding='utf-8').replace('\r\n', '\n')
    shipped = production.read_text(encoding='utf-8').replace('\r\n', '\n')
    # The promotion renamed the offline anchor helper; nothing else in the numerical body moved.
    origin = origin.replace('V3HybridBaseline', 'V3NativeAnchor')
    marker = '    Raw raw(V3ColumnInput input, V3SolveControl control) {'
    assert marker in origin and marker in shipped, 'The numerical body marker moved'
    assert shipped[shipped.index(marker):] == origin[origin.index(marker):], \
        'The promoted initializer diverged from the sealed predecessor body'
    for required in ('static final String REVISION = "v3-anchor-augmented-1"',
                     'V3FactorizedNeuralFeatures.DecodeOptions decode',
                     'raw.values, model.presenceThreshold, decode'):
        assert required in shipped, required
    print(f'Verified the promoted initializer {production.relative_to(ROOT).as_posix()} '
          f'against the sealed predecessor body.')


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
    print('Verified the promoted initializer and generated the campaign probe; measurement helpers are byte identical.')


if __name__ == '__main__':
    main()
