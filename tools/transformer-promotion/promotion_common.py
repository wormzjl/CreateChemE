"""Transformer-promotion primitives.

The neural-budget study measured the frozen F0 weights through a study-owned pipeline. This study
measures the same population through the production entry point, after those weights, that decoder rule
and that correction rule became what the mod ships. Nothing here trains, selects or tunes: the only
question is whether the shipped path reproduces the qualified numbers, case for case.

Every predecessor artifact is read only and bound by the SHA-256 its registration recorded.
"""
import gzip
import hashlib
import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

OUT = ROOT / 'build/transformer-promotion/v1'
STUDY = ROOT / 'tools/transformer-promotion'
INPUTS = STUDY / 'inputs'
EVIDENCE = STUDY / 'evidence'
# The predecessor's committed per-case evidence and decoded-seed digests; read only.
BUDGET_EVIDENCE = ROOT / 'tools/neural-budget/evidence'
QUALIFIED_PIPELINE = 'F0-progress-phase-floor'

BASE_NAME = 'F-20260911-s4160'
BASE_WEIGHTS_SHA = '7f909d025e12cbc7e8457b459adfbc63e48f52fb9e98ce45b91e64e84ddd1962'
VALIDATION_INPUTS_SHA = '95d773b06126b1637492e07cc5e7ab62b819655f5ac221c98c961bff436a9a0a'
WARMUP_SHA = 'f213c11059f9b81b57fbc9a32bc1bb86fbca26f583fe24f0027e8bd03ec50769'
BUNDLED_ARTIFACT = ROOT / 'src/main/resources/data/createcheme/neural/v3-column-transformer-f0.json'

STRATEGIES = ['current', 'neural', 'neuralFirst']
WORKERS = 10
DEADLINE_SECONDS = 30
NEURAL_BUDGET_MILLIS = 2000
MAXIMUM_ITERATIONS = 16
HEAP_BYTES = 4 * 1024 ** 3
BLOCKS = [1, 2]
CASES = 405

# What the qualification campaign published for the pipeline this promotion ships, per block.
EXPECTED_STRICT = {'current': (110, 110), 'neural': (163, 164), 'neuralFirst': (180, 180)}

# The correction rule the study registered as PROGRESS_CORRECTION, restated so the comparer can assert
# that the shipped one is this one and not merely "some progress rule".
PROGRESS_CORRECTION = dict(extensionBlock=8, extensionMaximumIterations=48, contractionWindow=8,
                           contractionFactor=0.5, stallWindow=8, stallFactor=0.9, stallResidualFloor=1e-6)
PHASE_FLOOR_FACTOR = 10.0


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def read_rows(path):
    path = Path(path)
    opener = gzip.open if path.suffix == '.gz' else open
    with opener(path, 'rt', encoding='utf-8') as stream:
        return [json.loads(line) for line in stream if line.strip()]


def info(path):
    return {'path': Path(path).relative_to(ROOT).as_posix(), 'sha256': digest(path)}


def freeze(path, value):
    """Write once. A measurement that has to overwrite its own record is not a measurement."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('x', encoding='utf-8') as stream:
        json.dump(value, stream, indent=1, sort_keys=True, allow_nan=False)


def run_directory(block):
    return OUT / 'validation' / f'block-{block}'


def decode_path():
    return OUT / 'decode' / 'bundled.jsonl'


def qualified_cases(block):
    """The predecessor's committed per-case evidence for the pipeline this promotion ships."""
    return {row['id']: row for row in
            read_rows(BUDGET_EVIDENCE / f'case-block{block}-{QUALIFIED_PIPELINE}.jsonl.gz')}


def qualified_seed_digests():
    return {row['id']: (row['seedSha256'] if row['supported'] else None) for row in
            read_rows(BUDGET_EVIDENCE / f'decode-seed-digests-{QUALIFIED_PIPELINE}.jsonl.gz')}


def seed_digest(seed):
    """The predecessor's sealing routine, unchanged: canonical JSON of the Gson seed, then SHA-256."""
    if seed is None:
        return None
    return hashlib.sha256(json.dumps(seed, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def strict_ids(cases, mode):
    return {i for i, record in cases.items() if record['modes'][mode]['outcome'] == 'strict'}


def verify_frozen():
    """Bind this study to the bytes it claims to be measuring, before it measures anything."""
    assert digest(BUNDLED_ARTIFACT) == BASE_WEIGHTS_SHA, 'The bundled artifact is not the registered F0 export'
    assert digest(INPUTS / 'validation-inputs.jsonl') == VALIDATION_INPUTS_SHA, 'The frozen population changed'
    assert digest(INPUTS / 'warmup.json') == WARMUP_SHA, 'The warmup input changed'
    rows = read_rows(INPUTS / 'validation-inputs.jsonl')
    assert len(rows) == CASES and len({row['id'] for row in rows}) == CASES
    return dict(bundledArtifact=info(BUNDLED_ARTIFACT), inputs=info(INPUTS / 'validation-inputs.jsonl'),
                warmup=info(INPUTS / 'warmup.json'), cases=len(rows))
