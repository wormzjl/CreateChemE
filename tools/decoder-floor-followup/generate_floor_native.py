"""Derive the decoder-variant adapter from the sealed trace-followup initializer.

The predecessor sources are never edited. Every substitution below is asserted to be unique, so a
silent upstream change cannot be absorbed, and the numerical body of the initializer is required to
stay byte identical to its origin apart from the threaded decode options.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'tools/trace-followup/java'
DEST = ROOT / 'tools/decoder-floor-followup/java'


def once(text, old, new):
    assert text.count(old) == 1, old
    return text.replace(old, new)


def main():
    DEST.mkdir(parents=True, exist_ok=True)
    text = (SOURCE / 'V3AnchorAugmentedInitializer.java').read_text(encoding='utf-8')
    # The feature revision and every manifest check stay as they are: the model bytes are frozen and
    # the decoder variant is chosen by the pipeline manifest alone.
    text = text.replace('V3AnchorAugmentedInitializer', 'V3FloorInitializer')
    text = once(text, 'Offline absolute-output Transformer with optional full or compact native anchor inputs.',
                'Offline absolute-output Transformer with a pipeline-selected decoder presence-floor rule.')
    text = once(text, '    private final Document model;\n'
                      '    private V3FloorInitializer(Document model) { this.model = model; }',
                '    private final Document model;\n'
                '    /** Decoder variant selected by the pipeline manifest, never by the model bytes. */\n'
                '    private final V3FactorizedNeuralFeatures.DecodeOptions decode;\n'
                '    private V3FloorInitializer(Document model, V3FactorizedNeuralFeatures.DecodeOptions decode) {\n'
                '        this.model = model; this.decode = decode;\n'
                '    }')
    text = once(text, '    static V3FloorInitializer read(InputStream stream) throws IOException {\n'
                      '        byte[] bytes',
                '    static V3FloorInitializer read(InputStream stream) throws IOException {\n'
                '        return read(stream, V3FactorizedNeuralFeatures.DecodeOptions.NONE);\n'
                '    }\n\n'
                '    static V3FloorInitializer read(InputStream stream, V3FactorizedNeuralFeatures.DecodeOptions decode) throws IOException {\n'
                '        if (decode == null) throw new IllegalArgumentException("Missing pipeline decode options");\n'
                '        byte[] bytes')
    text = once(text, 'var result = new V3FloorInitializer(m);', 'var result = new V3FloorInitializer(m, decode);')
    text = once(text, 'raw.values, model.presenceThreshold));', 'raw.values, model.presenceThreshold, decode));')
    (DEST / 'V3FloorInitializer.java').write_text(text, encoding='utf-8', newline='\n')

    original = (SOURCE / 'V3TraceEvaluationProbe.java').read_text(encoding='utf-8')
    text = original.replace('V3TraceEvaluationProbe', 'V3FloorEvaluationProbe')
    text = once(text, 'V3TraceModels.read', 'V3FloorModels.read')
    text = once(text, 'trace-followup-column-evaluation-v1', 'decoder-floor-column-evaluation-v1')
    marker = '    private static Map<String, Object> evaluate'
    # Everything from the first measurement helper onwards must remain byte identical to the origin.
    assert text[text.index(marker):] == original[original.index(marker):]
    (DEST / 'V3FloorEvaluationProbe.java').write_text(text, encoding='utf-8', newline='\n')
    print('Generated the decoder-variant initializer and probe; measurement helpers are byte identical.')


if __name__ == '__main__':
    main()
