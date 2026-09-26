"""Copy the five benchmark populations out of their archives into one uniform shape.

    python tools/benchmark-population/prepare_populations.py

Writes ``build/benchmark-population/inputs/<population>.jsonl`` and ``sources.json``, which binds each
population to the archived file it came from by SHA-256.  Sources:

===============  ====  ===========================================================================
population        n    source
===============  ====  ===========================================================================
validation        405  tools/transformer-promotion/inputs/validation-inputs.jsonl (in this repo)
g4fresh           252  sealed .neural-cache/unified-evaluation-v1/dependencies.zip ->
                       build/neural-transformer/data-v2/fresh-holdout.jsonl
g6fresh           252  sealed build/neural-transformer/selection-v1/test.jsonl
historical-test   395  sealed build/neural-gen3/data/cases.jsonl, split == test
train           1,993  sealed build/neural-gen3/data/cases.jsonl, split == train
===============  ====  ===========================================================================

``historical-test`` and ``train`` are the two non-validation folds of the generalized design matrix; the
matrix's own validation fold is verified here to be the same 405 inputs as the promotion population.
"""
from __future__ import annotations

import json

import population_common as common


GEN3_MATRIX = common.SEALED / 'build/neural-gen3/data/cases.jsonl'
G6FRESH = common.SEALED / 'build/neural-transformer/selection-v1/test.jsonl'
UNIFIED_DEPENDENCIES = common.SEALED / '.neural-cache/unified-evaluation-v1/dependencies.zip'
G4FRESH_MEMBER = 'build/neural-transformer/data-v2/fresh-holdout.jsonl'
VALIDATION = common.ROOT / 'tools/transformer-promotion/inputs/validation-inputs.jsonl'

EXPECTED = {'validation': 405, 'g4fresh': 252, 'g6fresh': 252, 'historical-test': 395, 'train': 1993}


def canonical_input_sha256(value):
    return common.sha256_bytes(json.dumps(value, sort_keys=True, separators=(',', ':')).encode('utf-8'))


def main():
    sources = {}
    prepared = {}

    validation = [common.normalise(row, 'validation') for row in common.rows(VALIDATION)]
    sources['validation'] = {'path': common.where(VALIDATION), 'sha256': common.sha256_file(VALIDATION),
                             'selection': 'all rows'}
    prepared['validation'] = validation

    g4, g4_sha = common.zip_rows(UNIFIED_DEPENDENCIES, G4FRESH_MEMBER)
    sources['g4fresh'] = {'path': common.where(f'{UNIFIED_DEPENDENCIES}!{G4FRESH_MEMBER}'), 'sha256': g4_sha,
                          'archiveSha256': common.sha256_file(UNIFIED_DEPENDENCIES), 'selection': 'all rows'}
    prepared['g4fresh'] = [common.normalise(row, 'test') for row in g4]

    g6 = list(common.rows(G6FRESH))
    sources['g6fresh'] = {'path': common.where(G6FRESH), 'sha256': common.sha256_file(G6FRESH),
                          'selection': 'all rows'}
    prepared['g6fresh'] = [common.normalise(row, 'test') for row in g6]

    matrix = list(common.rows(GEN3_MATRIX))
    matrix_sha = common.sha256_file(GEN3_MATRIX)
    for population, split in (('historical-test', 'test'), ('train', 'train')):
        sources[population] = {'path': common.where(GEN3_MATRIX), 'sha256': matrix_sha,
                               'selection': f"split == {split!r}"}
        prepared[population] = [common.normalise(row, split) for row in matrix if row['split'] == split]

    # The design matrix's own validation fold must be the promotion population, or the two non-validation
    # folds are not the same experiment as the numbers this study re-bases.
    matrix_validation = {canonical_input_sha256(r['input']) for r in matrix if r['split'] == 'validation'}
    promotion_validation = {canonical_input_sha256(r['input']) for r in validation}
    if matrix_validation != promotion_validation:
        raise SystemExit('The design matrix validation fold is not the promotion validation population')

    seen = {}
    for population, records in prepared.items():
        if len(records) != EXPECTED[population]:
            raise SystemExit(f'{population}: expected {EXPECTED[population]} rows, read {len(records)}')
        ids = [r['id'] for r in records]
        if len(set(ids)) != len(ids):
            raise SystemExit(f'{population}: duplicate case ids')
        for record in records:
            key = canonical_input_sha256(record['input'])
            seen.setdefault(key, []).append(f"{population}:{record['id']}")

    shared = {k: v for k, v in seen.items() if len({e.split(':')[0] for e in v}) > 1}
    document = {'revision': 'benchmark-population-sources-v1', 'sources': sources,
                'counts': {p: len(r) for p, r in prepared.items()},
                'crossPopulationSharedInputs': {k: v for k, v in sorted(shared.items())},
                'files': {}}
    for population, records in prepared.items():
        path = common.OUT / 'inputs' / f'{population}.jsonl'
        document['files'][population] = {'path': common.where(path), 'rows': len(records),
                                         'sha256': common.write_jsonl(path, records)}
    common.write_json(common.OUT / 'inputs/sources.json', document)
    print(json.dumps({'counts': document['counts'],
                      'sharedInputsAcrossPopulations': len(shared)}, indent=1))


if __name__ == '__main__':
    main()
