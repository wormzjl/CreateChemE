"""Add depth metadata to an isolated adapter; preserve prior measurement helpers."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'tools/trace-followup/java'
DEST = ROOT / 'tools/capacity-followup/java'


def once(text, old, new):
    assert text.count(old) == 1, old
    return text.replace(old, new)


def main():
    DEST.mkdir(parents=True, exist_ok=True)
    text = (SOURCE / 'V3AnchorAugmentedInitializer.java').read_text(encoding='utf-8')
    text = text.replace('V3AnchorAugmentedInitializer', 'V3CapacityInitializer')
    text = text.replace('v3-anchor-augmented-1', 'v3-capacity-1')
    text = text.replace('"anchor-augmented"', '"capacity-transformer"')
    text = once(text, '!("compact".equals(m.anchorLayout) || "full".equals(m.anchorLayout))',
                '(!"full".equals(m.anchorLayout) || (m.layerCount != 2 && m.layerCount != 4))')
    text = once(text, 'for (int i = 0; i < 2; i++) {', 'for (int i = 0; i < m.layerCount; i++) {')
    text = once(text, 'long expected = 83800 + (inputs(m) - 96) * 64L;',
                'long expected = 89496 + (m.layerCount - 2) * 33472L;')
    text = once(text, 'for (int block = 0; block < 2; block++) {',
                'for (int block = 0; block < model.layerCount; block++) {')
    text = once(text, 'String featureRevision, modelType, modelId, packageId, propertyRevision, anchorLayout;',
                'String featureRevision, modelType, modelId, packageId, propertyRevision, anchorLayout;\n        int layerCount;')
    text = text.replace('Offline absolute-output Transformer with optional full or compact native anchor inputs.',
                        'Offline full-anchor absolute-output Transformer with two or four width-64 blocks.')
    (DEST / 'V3CapacityInitializer.java').write_text(text, encoding='utf-8', newline='\n')

    text = (SOURCE / 'V3TraceModels.java').read_text(encoding='utf-8').replace('V3TraceModels', 'V3CapacityModels')
    text = once(text, '        V3NeuralInitializer model;',
                '        if (!doc.get("kind").getAsString().equals("capacity-transformer")) return V3TraceModels.read(path);\n'
                '        if (doc.get("materialCompletion").getAsBoolean()) throw new IllegalArgumentException("Capacity wrapper forbidden");\n'
                '        V3NeuralInitializer model;')
    start = text.index('        if (doc.get("kind").getAsString().equals("anchor-augmented"))')
    end = text.index('        return doc.get("materialCompletion")', start)
    text = text[:start] + '        try(var stream=Files.newInputStream(file)){model=V3CapacityInitializer.read(stream);}\n' + text[end:]
    (DEST / 'V3CapacityModels.java').write_text(text, encoding='utf-8', newline='\n')

    text = (SOURCE / 'V3TraceParityCheck.java').read_text(encoding='utf-8').replace('V3TraceParityCheck', 'V3CapacityParityCheck')
    text = once(text, '        if(feature.equals("v3-anchor-augmented-1"))',
                '        if(feature.equals("v3-capacity-1"))\n'
                '            try(var stream=Files.newInputStream(Path.of(args[0]))){model=V3CapacityInitializer.read(stream);}\n'
                '        else if(feature.equals("v3-anchor-augmented-1"))')
    text = once(text, '        if(model instanceof V3AnchorAugmentedInitializer anchor)',
                '        if(model instanceof V3CapacityInitializer depth){var r=depth.raw(input,V3SolveControl.UNBOUNDED);return new Values(r.values(),r.branchLogits());}\n'
                '        if(model instanceof V3AnchorAugmentedInitializer anchor)')
    (DEST / 'V3CapacityParityCheck.java').write_text(text, encoding='utf-8', newline='\n')

    original = (SOURCE / 'V3TraceEvaluationProbe.java').read_text(encoding='utf-8')
    text = original.replace('V3TraceEvaluationProbe', 'V3CapacityEvaluationProbe')
    text = text.replace('V3TraceModels.read', 'V3CapacityModels.read')
    text = text.replace('trace-followup-column-evaluation-v1', 'capacity-column-evaluation-v1')
    marker = '    private static Map<String, Object> evaluate'
    assert text[text.index(marker):] == original[original.index(marker):]
    (DEST / 'V3CapacityEvaluationProbe.java').write_text(text, encoding='utf-8', newline='\n')
    print('Generated depth adapter and registry; all native measurement helpers remain byte-identical.')


if __name__ == '__main__':
    main()
