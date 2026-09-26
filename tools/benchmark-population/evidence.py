"""Archived outcome evidence, per population, for the "ever solved" half of the exclusion rule.

Every journal this study reads is declared here with the arms it contributes and the hash of the bytes read,
so a reader can tell which evidence was available for which population.  Three journal shapes occur:

``modes``      one row per (case, pipeline, block) with ``modes.{current,neural,neuralFirst}.outcome`` in
               {strict, advisory, failed} -- the capacity/trace followups, the neural-budget evidence and the
               promotion evidence;
``case map``   one row per (case, block) with a dict of pipelines, each with three modes carrying ``success``
               and a strict flag -- the generation-comparison and hybrid-learning maps;
``flat``       one row per case, either a single outcome or one nested dict per mode -- the native campaign
               journals (unified evaluation, checkpoint selection, the gen3 design matrix).

The operative rule needs only "did any arm reach an accepted solve", which every shape states unambiguously
as ``success``/``status == ACCEPTED``/``outcome != failed``.  A strict flag is recorded wherever the journal
publishes one, because the partition the gap analysis published is a strict-only partition and this study has
to be comparable with it.
"""
from __future__ import annotations

import gzip
import json
from pathlib import Path

import population_common as common


SEALED = common.SEALED
ROOT = common.ROOT

# ---------------------------------------------------------------------------------------------------------
# Readers


def _payload(path):
    raw = Path(path).read_bytes()
    text = gzip.decompress(raw) if str(path).endswith('.gz') else raw
    return raw, [json.loads(line) for line in text.decode('utf-8').splitlines() if line.strip()]


def _zip_payload(archive, member):
    records, digest = common.zip_rows(archive, member)
    return digest, records


def _observation(solved, strict):
    return {'solved': bool(solved), 'strict': None if strict is None else bool(strict)}


def read_modes(label, path, archive=None):
    """Capacity/trace/budget/promotion evidence: modes.<mode>.outcome in {strict, advisory, failed}."""
    if archive is None:
        raw, rows = _payload(path)
        digest = common.sha256_bytes(raw)
    else:
        digest, rows = _zip_payload(archive, path)
    arms = {}
    for row in rows:
        pipeline = row.get('pipeline', label)
        block = row.get('block', 1)
        for mode, evidence in row['modes'].items():
            arm = f'{label}:{pipeline}:{mode}:b{block}'
            outcome = evidence.get('outcome')
            solved = outcome in ('strict', 'advisory') or evidence.get('status') == 'ACCEPTED'
            arms.setdefault(arm, {})[row['id']] = _observation(solved, outcome == 'strict')
    return digest, arms


def read_case_map(label, path, container, strict_key):
    """Generation-comparison / hybrid-learning maps: one dict of pipelines, each with three modes."""
    raw, rows = _payload(path)
    arms = {}
    for row in rows:
        block = row.get('block', row.get('population', 1))
        for pipeline, modes in row[container].items():
            for mode, evidence in modes.items():
                arm = f'{label}:{pipeline}:{mode}:{block}'
                arms.setdefault(arm, {})[row['id']] = _observation(
                    evidence.get('success') or evidence.get('status') == 'ACCEPTED', evidence.get(strict_key))
    return common.sha256_bytes(raw), arms


def read_flat(label, path, modes=None, archive=None, strict_key='equilibriumQualified'):
    """Native campaign journals: one row per case, optionally with one nested dict per mode."""
    if archive is None:
        raw, rows = _payload(path)
        digest = common.sha256_bytes(raw)
    else:
        digest, rows = _zip_payload(archive, path)
    arms = {}
    for row in rows:
        for mode in (modes or [None]):
            evidence = row if mode is None else row.get(mode)
            if not isinstance(evidence, dict):
                continue
            arm = label if mode is None else f'{label}:{mode}'
            solved = evidence.get('success') is True or evidence.get('status') == 'ACCEPTED'
            strict = evidence.get(strict_key) if strict_key in evidence else None
            arms.setdefault(arm, {})[row['id']] = _observation(solved, solved and strict if strict is not None else None)
    return digest, arms


def read_observations(label, path, split):
    """gen3 case map: ``observations`` is a dict of arm name -> outcome, on the gd design-matrix cases."""
    raw, rows = _payload(path)
    arms = {}
    for row in rows:
        if row.get('split') != split or not row['id'].startswith('gd-'):
            continue
        for arm_name, evidence in (row.get('observations') or {}).items():
            arm = f'{label}:{arm_name}'
            solved = evidence.get('success') is True or evidence.get('status') == 'ACCEPTED'
            arms.setdefault(arm, {})[row['id']] = _observation(solved, evidence.get('equilibriumQualified'))
    return common.sha256_bytes(raw), arms


def read_generalized_map(label, path, split):
    """Generalized design-matrix map: ``current`` (classical) and ``generalNeural`` per case."""
    raw, rows = _payload(path)
    arms = {}
    for row in rows:
        if row.get('split') != split:
            continue
        for arm_name in ('current', 'generalNeural'):
            evidence = row.get(arm_name) or {}
            if not evidence.get('evaluated'):
                continue
            arms.setdefault(f'{label}:{arm_name}', {})[row['id']] = _observation(
                evidence.get('success') is True or evidence.get('status') == 'ACCEPTED',
                evidence.get('equilibriumQualified'))
    return common.sha256_bytes(raw), arms


def read_salvage_audit(label, archive, split):
    """Salvage certified campaign audit: the acquisition outcome per design-matrix case."""
    digest, rows = _zip_payload(archive, 'audit.jsonl')
    arms = {}
    for row in rows:
        if row['split'] != split:
            continue
        arms.setdefault(label, {})[row['id']] = _observation(row['status'] == 'ACCEPTED', row.get('strict'))
    return digest, arms


# ---------------------------------------------------------------------------------------------------------
# Declared sources


CAPACITY = SEALED / 'build/neural-capacity-followup/v1/validation-case-evidence.jsonl'
TRACE = SEALED / 'build/neural-trace-followup/v1/validation-case-evidence.jsonl'
GENERATION_MAP = SEALED / 'tools/neural/generation-comparison-case-map.jsonl'
HYBRID_MAP = SEALED / 'build/neural-hybrid-learning/v1/case-map.jsonl'
BUDGET = ROOT / 'tools/neural-budget/evidence'
PROMOTION = ROOT / 'tools/transformer-promotion/evidence'
UNIFIED_DEPENDENCIES = SEALED / '.neural-cache/unified-evaluation-v1/dependencies.zip'
UNIFIED_STUDY = SEALED / '.neural-cache/unified-evaluation-v1/study.zip'
CHECKPOINT_STUDY = SEALED / '.neural-cache/checkpoint-selection-v2/study.zip'
GENERATION_STUDY = SEALED / '.neural-cache/generation-comparison-v1/study.zip'
GEN3_MATRIX = SEALED / 'build/neural-gen3/data/cases.jsonl'
GEN3_CASE_MAP = ROOT / 'tools/neural/gen3-case-map.jsonl.gz'
GENERALIZED_CASE_MAP = ROOT / 'tools/neural/generalized-case-map.jsonl.gz'
SALVAGE = SEALED / '.neural-cache/salvage-nplus1-v1/certified-campaign.zip'

BUDGET_PIPELINES = ('F0-baseline', 'F0-progress', 'F0-phase-floor', 'F0-progress-phase-floor')
UNIFIED_MODELS = ('transformer', 'mlp', 'gen3-factorized', 'nearest-k1')


def arms_for(population):
    """Return (arms, sources) for a population: arm label -> {id: observation}, and the bytes each came from."""
    arms, sources = {}, []

    def add(entry, path, note):
        digest, contributed = entry
        arms.update(contributed)
        sources.append({'path': common.where(path), 'sha256': digest, 'arms': len(contributed), 'note': note})

    if population == 'validation':
        add(read_modes('capacity', CAPACITY), CAPACITY, 'capacity followup, 7 pipelines x 3 modes x 2 blocks')
        add(read_modes('trace', TRACE), TRACE, 'trace followup, 12 pipelines x 3 modes x 2 blocks')
        add(read_case_map('generation', GENERATION_MAP, 'models', 'strictQualified'), GENERATION_MAP,
            'generation comparison, 3 models x 3 modes (hash-verified)')
        add(read_case_map('hybrid', HYBRID_MAP, 'pipelines', 'strict'), HYBRID_MAP,
            'hybrid learning, 8 pipelines x 3 modes x 2 blocks (id join only)')
        for pipeline in BUDGET_PIPELINES:
            for block in (1, 2):
                path = BUDGET / f'case-block{block}-{pipeline}.jsonl.gz'
                add(read_modes('budget', path), path, f'R2 neural budget {pipeline} block {block}')
        for block in (1, 2):
            path = PROMOTION / f'case-block{block}.jsonl.gz'
            add(read_modes('promotion', path), path, f'production-path promotion block {block}')
    elif population == 'g4fresh':
        member = 'build/neural-transformer/native-v1/fresh-current/cases.jsonl'
        add(read_flat('unified:fresh-current', member, archive=UNIFIED_DEPENDENCIES),
            f'{UNIFIED_DEPENDENCIES}!{member}', 'unified evaluation classical control')
        for model in UNIFIED_MODELS:
            member = f'build/neural-transformer/native-v1/fresh-{model}/evaluation.jsonl'
            add(read_flat(f'unified:fresh-{model}', member, archive=UNIFIED_DEPENDENCIES),
                f'{UNIFIED_DEPENDENCIES}!{member}', f'unified evaluation {model}, ONLY mode')
            member = f'{model}/evaluation.jsonl'
            add(read_flat(f'unified:study-{model}', member, modes=('current', 'neural', 'neuralFirst'),
                          archive=UNIFIED_STUDY),
                f'{UNIFIED_STUDY}!{member}', f'unified evaluation {model}, all three modes')
    elif population == 'g6fresh':
        add(read_case_map('generation', GENERATION_MAP, 'models', 'strictQualified'), GENERATION_MAP,
            'generation comparison test fold, 3 models x 3 modes')
        member = 'build/neural-transformer/selection-v2/test/20260911/evaluation.jsonl'
        add(read_flat('checkpoint:20260911', member, modes=('current', 'neural', 'neuralFirst'),
                      archive=CHECKPOINT_STUDY), f'{CHECKPOINT_STUDY}!{member}',
            'checkpoint selection v2 test fold, all three modes')
        for model in ('gen2', 'gen3', 'transformer'):
            member = f'build/neural-generations/comparison-v1/test/{model}/evaluation.jsonl'
            add(read_flat(f'generation:{model}', member, modes=('current', 'neural', 'neuralFirst'),
                          archive=GENERATION_STUDY), f'{GENERATION_STUDY}!{member}',
                f'generation comparison {model} test journal')
    elif population in ('historical-test', 'train'):
        split = 'test' if population == 'historical-test' else 'train'
        raw, rows = _payload(GEN3_MATRIX)
        matrix = {row['id']: _observation(row.get('success') is True, row.get('equilibriumQualified'))
                  for row in rows if row['split'] == split}
        arms['matrix:original-current-initializer'] = matrix
        sources.append({'path': common.where(GEN3_MATRIX), 'sha256': common.sha256_bytes(raw), 'arms': 1,
                        'note': 'generalized design matrix acquisition, classical control'})
        add(read_generalized_map('generalized', GENERALIZED_CASE_MAP, split), GENERALIZED_CASE_MAP,
            'generalized campaign map: classical and general neural')
        add(read_observations('gen3', GEN3_CASE_MAP, split), GEN3_CASE_MAP,
            'gen3 recovery campaign observations')
        add(read_salvage_audit('salvage:certified-acquisition', SALVAGE, split), f'{SALVAGE}!audit.jsonl',
            'salvage N+1 certified campaign acquisition audit')
    else:
        raise ValueError(population)
    return arms, sources


def classical_control(population):
    """The archived classical (CURRENT-only) control the state-gate half of the rule is read from."""
    if population == 'validation':
        raw, rows = _payload(CAPACITY)
        status, blocks = {}, {}
        for row in rows:
            if row.get('pipeline') != 'F0':
                continue
            blocks.setdefault(row['id'], {})[row['block']] = row['modes']['current']['status']
        disagreements = sorted(i for i, v in blocks.items() if len(set(v.values())) != 1)
        status = {i: v[1] for i, v in blocks.items()}
        return {'path': common.where(CAPACITY), 'sha256': common.sha256_bytes(raw),
                'note': 'capacity followup, pipeline F0, mode current, pre-fix solver',
                'blockDisagreements': disagreements}, status
    if population == 'g4fresh':
        member = 'build/neural-transformer/native-v1/fresh-current/cases.jsonl'
        digest, rows = _zip_payload(UNIFIED_DEPENDENCIES, member)
        return {'path': common.where(f'{UNIFIED_DEPENDENCIES}!{member}'), 'sha256': digest,
                'note': 'unified evaluation classical control', 'blockDisagreements': []}, \
               {row['id']: row['status'] for row in rows}
    if population == 'g6fresh':
        member = 'build/neural-generations/comparison-v1/test/transformer/evaluation.jsonl'
        digest, rows = _zip_payload(GENERATION_STUDY, member)
        return {'path': common.where(f'{GENERATION_STUDY}!{member}'), 'sha256': digest,
                'note': "generation comparison transformer journal, 'current' mode",
                'blockDisagreements': []}, {row['id']: row['current']['status'] for row in rows}
    split = 'test' if population == 'historical-test' else 'train'
    raw, rows = _payload(GEN3_MATRIX)
    return {'path': common.where(GEN3_MATRIX), 'sha256': common.sha256_bytes(raw),
            'note': f'generalized design matrix acquisition ({split} fold), classical control',
            'blockDisagreements': []}, \
           {row['id']: row['status'] for row in rows if row['split'] == split}


def certified_label_ids():
    """The certified TRAIN label sets, as {name: (set of ids, sha256 of the bytes read)}."""
    labels = {}
    for name, member in (('N', 'N.jsonl'), ('Nplus1', 'Nplus1-certified.jsonl')):
        digest, rows = _zip_payload(SALVAGE, member)
        labels[name] = {'ids': {row['id'] for row in rows}, 'sha256': digest,
                        'path': common.where(f'{SALVAGE}!{member}'), 'rows': len(rows)}
    return labels
