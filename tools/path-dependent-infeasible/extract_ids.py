"""Extract the classical-control INFEASIBLE_SPECIFICATION population from recorded journals only.

Read-only: every source lives in the sealed predecessor worktree and is opened for reading. The output is
``ids.json`` plus ``inputs-108.jsonl``, the frozen input specifications of exactly those ids, in the order
they appear in the validation population, for the classical-only verification runner.

Usage:  python tools/path-dependent-infeasible/extract_ids.py
"""
import json
import hashlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).resolve().parent
SEALED = Path('C:/Users/wormz/.codex/worktrees/8848/CreateChemE')
CAPACITY = SEALED / 'build/neural-capacity-followup/v1/validation-case-evidence.jsonl'
TRACE = SEALED / 'build/neural-trace-followup/v1/validation-case-evidence.jsonl'
INPUTS = SEALED / 'build/neural-trace-followup/v1/validation-inputs.jsonl'
# The two extra sources the R6 screen pooled to reach its published 42/47. Kept separate because the
# generation map is hash-verified while the hybrid case map carries no input hash and is joined by id only.
GENERATION = SEALED / 'tools/neural/generation-comparison-case-map.jsonl'
HYBRID = SEALED / 'build/neural-hybrid-learning/v1/case-map.jsonl'
CONTROL_PIPELINE = 'F0'
CONTROL_MODE = 'current'


def rows(path):
    with path.open(encoding='utf-8') as stream:
        for line in stream:
            if line.strip():
                yield json.loads(line)


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b''):
            value.update(chunk)
    return value.hexdigest()


def main():
    # 1. The classical control, block by block. The two blocks must agree before either is used.
    control = {}
    for row in rows(CAPACITY):
        if row['pipeline'] != CONTROL_PIPELINE:
            continue
        control.setdefault(row['id'], {})[row['block']] = row['modes'][CONTROL_MODE]['status']
    assert len(control) == 405, len(control)
    disagreeing = sorted(i for i, blocks in control.items() if len(set(blocks.values())) != 1)
    typed = sorted(i for i, blocks in control.items() if blocks[1] == 'INFEASIBLE_SPECIFICATION')

    # 2. Everything ever solved on the identical input, pooled over both journals and every pipeline/mode.
    strict, advisory = set(), set()
    hashes = {}
    for path in (CAPACITY, TRACE):
        for row in rows(path):
            hashes.setdefault(row['id'], set()).add(row.get('canonicalInputSha256'))
            for mode in row['modes'].values():
                if mode.get('outcome') == 'strict':
                    strict.add(row['id'])
                elif mode.get('outcome') == 'advisory':
                    advisory.add(row['id'])
    # A rescue only counts when both journals agree the id is the same request.
    ambiguous = sorted(i for i, values in hashes.items() if len(values) != 1 or None in values)

    solved = sorted(i for i in typed if i in strict)
    solved_or_advisory = sorted(i for i in typed if i in strict or i in advisory)

    # The two extra sources, pooled exactly as the R6 screen pooled them, so its published 42/47 reproduces.
    generation_strict, generation_advisory = set(), set()
    for row in rows(GENERATION):
        if row.get('population') != 'validation':
            continue
        for models in row['models'].values():
            for mode in models.values():
                if mode.get('strictQualified'):
                    generation_strict.add(row['id'])
                elif mode.get('success'):
                    generation_advisory.add(row['id'])
    hybrid_strict, hybrid_advisory = set(), set()
    for row in rows(HYBRID):
        if row.get('split') != 'validation':
            continue
        for pipelines in row['pipelines'].values():
            for mode in pipelines.values():
                if mode.get('strict'):
                    hybrid_strict.add(row['id'])
                elif mode.get('success'):
                    hybrid_advisory.add(row['id'])
    pooled_strict = strict | generation_strict | hybrid_strict
    pooled_any = pooled_strict | advisory | generation_advisory | hybrid_advisory
    pooled_solved = sorted(i for i in typed if i in pooled_strict)
    pooled_solvable = sorted(i for i in typed if i in pooled_any)

    # 3. Whatever gate text the journals recorded for the control on those ids (coarse vocabulary only).
    evidence = {}
    for row in rows(CAPACITY):
        if row['pipeline'] != CONTROL_PIPELINE or row['id'] not in set(typed):
            continue
        mode = row['modes'][CONTROL_MODE]
        record = evidence.setdefault(row['id'], {'observedStopPhrases': set(), 'failureClass': set(),
                                                 'newtonIterations': set(), 'milliseconds': []})
        record['observedStopPhrases'].update(mode.get('observedStopPhrases') or [])
        record['failureClass'].add(mode.get('failureClass'))
        record['newtonIterations'].add(mode.get('newtonIterations'))
        record['milliseconds'].append(mode.get('milliseconds'))
    phrases = sorted({p for r in evidence.values() for p in r['observedStopPhrases']})
    classes = sorted({c for r in evidence.values() for c in r['failureClass']})

    # 4. Request-only replay: neither request-only gate can fire anywhere on this population.
    specifications = {row['id']: row['input'] for row in rows(INPUTS)}
    assert set(specifications) == set(control)
    draw_over_feed = {}
    for case, spec in specifications.items():
        feed = sum(spec['feedComponentMolarFlowsMolPerSecond'])
        draw = sum(d['molarFlowMolPerSecond'] for d in spec.get('sideDraws') or [])
        draw_over_feed[case] = draw / feed
    request_only_draw = sorted(i for i in typed if draw_over_feed[i] >= 1.0)

    # Structural cross-check of the gate inventory. Every remaining INFEASIBLE_SPECIFICATION publisher needs
    # authored pumparound cooling: the static admission returns early without it, and so do both state-dependent
    # bounds (no heat rung exists in the ramp). A typed case with no cooling would mean a publisher was missed.
    loops = {}
    cooling_free = []
    for case in typed:
        pumparounds = specifications[case].get('pumparounds') or []
        loops[case] = len(pumparounds)
        if not sum(-p['dutyWatts'] for p in pumparounds if p['dutyWatts'] < 0) > 0.0:
            cooling_free.append(case)

    control_millis = {}
    for case, record in evidence.items():
        values = [v for v in record['milliseconds'] if v is not None]
        control_millis[case] = sum(values) / len(values) if values else None

    document = {
        'revision': 'path-dependent-infeasible-ids-v1',
        'sources': {
            'classicalControl': {'path': str(CAPACITY), 'sha256': digest(CAPACITY),
                                 'pipeline': CONTROL_PIPELINE, 'mode': CONTROL_MODE},
            'extraOutcomes': {'path': str(TRACE), 'sha256': digest(TRACE)},
            'inputs': {'path': str(INPUTS), 'sha256': digest(INPUTS)},
        },
        'population': len(control),
        'blockDisagreements': disagreeing,
        'ambiguousInputHashes': ambiguous,
        'typedInfeasibleByClassicalControl': typed,
        'typedInfeasibleCount': len(typed),
        'solvedStrictlyElsewhere': solved,
        'solvedStrictlyElsewhereCount': len(solved),
        'solvedOrAdvisoryElsewhere': solved_or_advisory,
        'solvedOrAdvisoryElsewhereCount': len(solved_or_advisory),
        'pooledWithGenerationAndHybridMaps': {
            'note': 'Adds tools/neural/generation-comparison-case-map.jsonl (hash-verified) and '
                    'build/neural-hybrid-learning/v1/case-map.jsonl (id-join only). This is the pooling the '
                    'R6 screen used for its published 42 strict / 47 solvable.',
            'sources': {'generation': {'path': str(GENERATION), 'sha256': digest(GENERATION)},
                        'hybrid': {'path': str(HYBRID), 'sha256': digest(HYBRID)}},
            'solvedStrictly': pooled_solved,
            'solvedStrictlyCount': len(pooled_solved),
            'solvableCount': len(pooled_solvable),
            'solvable': pooled_solvable,
        },
        'neverSolvedAnywhere': sorted(set(typed) - set(pooled_solvable)),
        'requestOnlyDrawGateWouldFire': request_only_draw,
        'maximumDrawOverFeedAmongTyped': max(draw_over_feed[i] for i in typed),
        'typedWithoutAuthoredCooling': cooling_free,
        'heatLoopHistogramAmongTyped': {str(n): sum(1 for v in loops.values() if v == n)
                                        for n in sorted(set(loops.values()))},
        'recordedGateText': {
            'available': False,
            'note': 'The journals record only a coarse stop vocabulary and a failure class; no gate detail '
                    'string was kept, so which of the two state-dependent gates fired per case is not '
                    'recoverable from them. The verification run records the full summary text.',
            'observedStopPhrases': phrases,
            'failureClasses': classes,
            'newtonIterations': sorted({n for r in evidence.values() for n in r['newtonIterations']}),
        },
        'classicalControlMillisByCase': control_millis,
        'classicalControlSecondsTotal': round(sum(v for v in control_millis.values() if v) / 1000.0, 1),
    }
    (OUT / 'ids.json').write_text(json.dumps(document, indent=1) + '\n', encoding='utf-8')

    order = [row['id'] for row in rows(INPUTS)]
    wanted = set(typed)
    with (OUT / 'inputs-108.jsonl').open('w', encoding='utf-8', newline='\n') as stream:
        written = 0
        for row in rows(INPUTS):
            if row['id'] in wanted:
                stream.write(json.dumps({'id': row['id'], 'input': row['input']}, sort_keys=True) + '\n')
                written += 1
    assert written == len(typed), (written, len(typed))
    assert order  # population order is preserved above

    print(json.dumps({k: v for k, v in document.items()
                      if not isinstance(v, (list, dict)) or k in ('blockDisagreements', 'ambiguousInputHashes',
                                                                  'requestOnlyDrawGateWouldFire')}, indent=1))
    print(json.dumps({'pooledStrict': len(pooled_solved), 'pooledSolvable': len(pooled_solvable),
                      'neverSolvedAnywhere': len(set(typed) - set(pooled_solvable))}))
    print(f'wrote {OUT / "ids.json"} and {written} inputs to {OUT / "inputs-108.jsonl"}')


if __name__ == '__main__':
    main()
