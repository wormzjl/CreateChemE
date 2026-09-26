"""Harness checks. No solver, no campaign artifacts, no fits. Run with plain python."""
from floor_common import *
from floor_preflight import lift_counts
import generate_floor_native as generator
import tempfile


def test_adapters_are_reproducible_from_the_sealed_predecessor():
    with tempfile.TemporaryDirectory() as directory:
        original, generator.DEST = generator.DEST, Path(directory)
        try:
            generator.main()
            for name in ('V3FloorInitializer.java', 'V3FloorEvaluationProbe.java'):
                # Compared after newline normalization: this repository checks Java out with CRLF.
                produced = (Path(directory) / name).read_bytes().replace(b'\r\n', b'\n')
                committed = (original / name).read_bytes().replace(b'\r\n', b'\n')
                assert produced == committed, name
        finally:
            generator.DEST = original


def test_pipelines_declare_exactly_the_registered_decoder_variants():
    for name, decoder in PIPELINES.items():
        manifest = read(pipeline_path(name))
        assert manifest['revision'] == 'decoder-floor-pipeline-v1'
        assert manifest['kind'] == 'anchor-augmented' and manifest['materialCompletion'] is False
        assert manifest['decoder'] == decoder
        assert manifest['weights']['sha256'] == BASE_WEIGHTS_SHA
    lifts = [d for d in PIPELINES.values() if d['rule'] == 'presence-floor-lift']
    assert len(PIPELINES) == 4 and len(lifts) == 3
    assert all(d['liftFactor'] >= 1 and 0 < d['liftPresenceProbability'] < 1 for d in lifts)


def test_lift_accounting_counts_new_entries_and_rejects_moved_mass():
    feed = [0.0] + [1.0] * 3
    inputs = {'a': dict(input=dict(feedComponentMolarFlowsMolPerSecond=feed))}
    base = {'a': dict(liquid=[[0, 2.0, 0, 1.0]], vapor=[[0, 0, 0, 0]])}
    lifted = {'a': dict(liquid=[[0, 2.0, 1e-9, 1.0]], vapor=[[0, 0, 0, 0]])}
    counts = lift_counts(base, lifted, inputs)
    assert counts == dict(changedCases=1, liftedEntries=1, changedCaseIds=['a'])
    assert lift_counts(base, base, inputs) == dict(changedCases=0, liftedEntries=0, changedCaseIds=[])
    moved = {'a': dict(liquid=[[0, 1.9, 1e-9, 1.0]], vapor=[[0, 0, 0, 0]])}
    try:
        lift_counts(base, moved, inputs)
    except AssertionError:
        return
    raise AssertionError('A renormalized phase must be rejected by the lift accounting')


if __name__ == '__main__':
    passed = 0
    for name, function in sorted(globals().items()):
        if name.startswith('test_'):
            function(); passed += 1
            print('ok', name, flush=True)
    print(json.dumps({'passed': passed}))
