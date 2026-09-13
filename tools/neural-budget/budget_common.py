"""Neural-budget follow-up primitives.

This implements recommendation R2 of the Transformer initializer review: diagnose why the learned
seed's Newton correction stops at its budget walls, then reshape the budget from walls into progress
rules. The predecessor workspaces are read only; everything measured here lives under this study's own
root and is bound by the SHA-256 the predecessor registrations recorded.
"""
import json
import os
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

# The study runs in two registered phases with their own native cores, because the bounded diagnostic has
# to measure the unchanged production correction and the campaign has to measure the changed one. `v1`
# holds the diagnostic; `v2` holds the campaign that the diagnostic's declared decision rule selects.
REVISION = os.environ.get('NEURAL_BUDGET_REVISION', 'v1')
assert REVISION in ('v1', 'v2'), REVISION
OUT = ROOT / f'build/neural-budget/{REVISION}'
DIAGNOSTIC_OUT = ROOT / 'build/neural-budget/v1'
INPUTS = OUT / 'inputs'
EVIDENCE = ROOT / 'tools/neural-budget/evidence'
IDS = ROOT / 'tools/neural-budget/ids'

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

# The two tracing configurations of the bounded diagnostic. The production configuration is the exact
# budget every archived campaign ran; the diagnostic one triples both walls and changes nothing else,
# so a case that converges only under it was crawling rather than diverging.
TRACE_CONFIGS = {
    'production': dict(maximumIterations=16, budgetMillis=2000),
    'diagnostic': dict(maximumIterations=48, budgetMillis=6000),
}

# The correction rule of a pipeline, as the pipeline manifest states it. "fixed" is the production wall
# pair. The progress rule keeps both walls and adds the declared contraction test and early stop; its
# parameters are ladder step 1, registered by budget_ladder.py under the rule protocol.md declared.
BASELINE_CORRECTION = dict(rule='fixed', maximumIterations=MAXIMUM_ITERATIONS, budgetMillis=NEURAL_BUDGET_MILLIS)
PROGRESS_CORRECTION = dict(rule='progress', maximumIterations=MAXIMUM_ITERATIONS,
                           budgetMillis=NEURAL_BUDGET_MILLIS, extensionBlock=8, extensionMaximumIterations=48,
                           contractionWindow=8, contractionFactor=0.5,
                           stallWindow=8, stallFactor=0.9, stallResidualFloor=1e-6)
PHASE_FLOOR_DECODER = dict(rule='zero-phase-floor', zeroPhaseFloorFactor=10.0)
PRUNE_DECODER = dict(rule='prune')

# One pipeline per selected intervention on the identical frozen F0 weight bytes. The diagnostic phase
# registers the baseline alone; the campaign phase adds the interventions the declared rules selected.
PIPELINES = {'F0-baseline': dict(decoder=PRUNE_DECODER, correction=BASELINE_CORRECTION)}
if REVISION != 'v1':
    PIPELINES.update({
        'F0-progress': dict(decoder=PRUNE_DECODER, correction=PROGRESS_CORRECTION),
        'F0-phase-floor': dict(decoder=PHASE_FLOOR_DECODER, correction=BASELINE_CORRECTION),
        'F0-progress-phase-floor': dict(decoder=PHASE_FLOOR_DECODER, correction=PROGRESS_CORRECTION),
    })
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


def trace_directory(config):
    return OUT / 'trace' / config


def id_list(name):
    """A committed target group, as a list of case identifiers."""
    return read(IDS / f'{name}.json')['ids']
