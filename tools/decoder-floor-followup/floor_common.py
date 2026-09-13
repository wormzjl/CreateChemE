"""Decoder-floor follow-up primitives.

The predecessor workspace is read only. Everything this study measures is copied into its own study
root first, and every copy is bound by the SHA-256 the predecessor registration recorded.
"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / 'tools/trace-followup'), str(ROOT / 'tools/neural')]
from prepare_transformer_data import read_rows, digest, strict, stats  # noqa: E402
from prepare_generalized_evaluation import canonical_input_hash  # noqa: E402
from native_checkpoint_selection_v1 import freeze  # noqa: E402
import numpy as np  # noqa: E402

# Codex's sealed workspace. Read only: never write, never import its build outputs into place.
PRIOR = Path('C:/Users/wormz/.codex/worktrees/8848/CreateChemE')
PRIOR_TRACE = PRIOR / 'build/neural-trace-followup/v1'
PRIOR_CAPACITY = PRIOR / 'build/neural-capacity-followup/v1'

OUT = ROOT / 'build/neural-decoder-floor/v1'
INPUTS = OUT / 'inputs'

BASE_NAME = 'F-20260911-s4160'
BASE_WEIGHTS_SHA = '7f909d025e12cbc7e8457b459adfbc63e48f52fb9e98ce45b91e64e84ddd1962'
VALIDATION_INPUTS_SHA = '95d773b06126b1637492e07cc5e7ab62b819655f5ac221c98c961bff436a9a0a'
WARMUP_SHA = 'f213c11059f9b81b57fbc9a32bc1bb86fbca26f583fe24f0027e8bd03ec50769'
PREDECESSOR_ARCHIVE_SHA = 'aa46495c70e5f712a2493d9ae2ce4884a481045ca0ca939620c18af078568133'

STRATEGIES = ['current', 'neural', 'neuralFirst']
WORKERS = 10
DEADLINE_SECONDS = 30
NEURAL_BUDGET_MILLIS = 2000
MAXIMUM_ITERATIONS = 16
HEAP_BYTES = 4 * 1024 ** 3
BLOCKS = [1, 2]

# One pipeline per decoder variant on the identical frozen F0 weight bytes.
PIPELINES = {
    'F0-baseline': dict(rule='prune'),
    'F0-lift-p002-k10': dict(rule='presence-floor-lift', liftFactor=10.0, liftPresenceProbability=0.02),
    'F0-lift-p050-k10': dict(rule='presence-floor-lift', liftFactor=10.0, liftPresenceProbability=0.5),
    'F0-lift-p050-k1': dict(rule='presence-floor-lift', liftFactor=1.0, liftPresenceProbability=0.5),
}
ORDER = list(PIPELINES)


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def info(path):
    return {'path': Path(path).relative_to(ROOT).as_posix(), 'sha256': digest(Path(path))}


def external(path):
    return {'path': str(path), 'sha256': digest(Path(path))}


def exact_copy(source, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open('xb') as stream:
        stream.write(Path(source).read_bytes())
    assert digest(Path(source)) == digest(target), 'Artifact copy mismatch'


def pipeline_path(name):
    return OUT / 'pipelines' / f'{name}.json'


def run_directory(name, block):
    return OUT / 'validation' / f'block-{block}' / name
