"""Compare the production-path campaign against the qualification evidence, case by case.

Counts are the headline, identities are the claim. A block that reached the same three counts through a
different set of columns would not be a reproduction, so every comparison below is on identifier sets and
every difference is listed rather than summarized.
"""
from promotion_common import *
import argparse


def observed(block):
    directory = run_directory(block)
    meta = read(directory / 'run.json')
    assert meta['complete'] and meta['completed'] == CASES, f'Block {block} did not complete'
    assert meta['workers'] == WORKERS and meta['deadlineMillis'] == DEADLINE_SECONDS * 1000
    assert meta['neuralBudgetMillis'] == NEURAL_BUDGET_MILLIS and meta['neuralMaximumIterations'] == MAXIMUM_ITERATIONS
    assert meta['modelArtifactSha256'] == BASE_WEIGHTS_SHA, 'The campaign did not load the registered weights'
    assert meta['zeroPhaseFloorFactor'] == PHASE_FLOOR_FACTOR, 'The shipped decoder rule is not the qualified one'
    for key, value in PROGRESS_CORRECTION.items():
        assert meta['correction'][key] == value, (key, meta['correction'][key], value)
    rows = {row['id']: row for row in read_rows(directory / 'evaluation.jsonl')}
    assert len(rows) == CASES
    return meta, rows


def compare_block(block):
    meta, rows = observed(block)
    reference = qualified_cases(block)
    assert set(rows) == set(reference), 'The population differs from the qualification campaign'
    result = dict(block=block, elapsedSeconds=meta['elapsedSeconds'], modelId=meta['modelId'],
                  java=meta['java'], availableProcessors=meta['availableProcessors'], modes={})
    for mode in STRATEGIES:
        mine = {i for i, row in rows.items() if row['modes'][mode]['outcome'] == 'strict'}
        theirs = strict_ids(reference, mode)
        expected = EXPECTED_STRICT[mode][block - 1]
        result['modes'][mode] = dict(
            production=len(mine), qualification=len(theirs), publishedExpectation=expected,
            identical=mine == theirs,
            matchesPublishedCount=len(mine) == expected,
            productionOnlyIds=sorted(mine - theirs), qualificationOnlyIds=sorted(theirs - mine))
    result['allIdentitySetsReproduced'] = all(v['identical'] for v in result['modes'].values())
    result['timing'] = timing(rows, reference)
    return result


def timing(rows, reference):
    """Paired cost on the cases both campaigns call strict, per mode, plus classical for scale."""
    table = {}
    for mode in STRATEGIES:
        common = sorted({i for i, row in rows.items() if row['modes'][mode]['outcome'] == 'strict'}
                        & strict_ids(reference, mode))
        if not common:
            table[mode] = None
            continue
        mine = [rows[i]['modes'][mode]['milliseconds'] for i in common]
        theirs = [reference[i]['modes'][mode]['milliseconds'] for i in common]
        classical = [rows[i]['modes']['current']['milliseconds'] for i in common]
        paired = [a - b for a, b in zip(mine, theirs)]
        versus = [rows[i]['modes'][mode]['milliseconds'] - rows[i]['modes']['current']['milliseconds']
                  for i in common if rows[i]['modes']['current']['outcome'] == 'strict']
        table[mode] = dict(
            cases=len(common),
            productionMeanMillis=mean(mine), productionMedianMillis=median(mine),
            qualificationMeanMillis=mean(theirs), qualificationMedianMillis=median(theirs),
            pairedMeanMillis=mean(paired), pairedMedianMillis=median(paired),
            classicalMeanMillis=mean(classical),
            versusClassicalOnCommonStrict=dict(cases=len(versus), meanMillis=mean(versus),
                                               medianMillis=median(versus)) if versus else None)
    return table


def mean(values):
    return sum(values) / len(values) if values else None


def median(values):
    ordered = sorted(values)
    if not ordered:
        return None
    middle = len(ordered) // 2
    return ordered[middle] if len(ordered) % 2 else (ordered[middle - 1] + ordered[middle]) / 2


def compare_seeds():
    """The decoder gate: the shipped model must decode the qualified pipeline's seeds, bit for bit."""
    expected = qualified_seed_digests()
    mine = {row['id']: seed_digest(row['seed']) for row in read_rows(decode_path())}
    assert set(mine) == set(expected), 'The decode dump covers a different population'
    mismatched = sorted(i for i in expected if expected[i] != mine[i])
    return dict(cases=len(expected), supported=sum(v is not None for v in mine.values()),
                qualificationSupported=sum(v is not None for v in expected.values()),
                mismatchedCases=len(mismatched), mismatchedIds=mismatched,
                reproducesQualifiedSeeds=not mismatched)


def compare(blocks):
    report = dict(revision='transformer-promotion-comparison-v1', frozen=verify_frozen(),
                  qualifiedPipeline=QUALIFIED_PIPELINE,
                  qualifiedEvidence=[info(BUDGET_EVIDENCE / f'case-block{b}-{QUALIFIED_PIPELINE}.jsonl.gz')
                                     for b in blocks]
                  + [info(BUDGET_EVIDENCE / f'decode-seed-digests-{QUALIFIED_PIPELINE}.jsonl.gz')],
                  nativeCore=info(OUT / 'native-core-build.json'),
                  decodeParity=compare_seeds(),
                  blocks=[compare_block(block) for block in blocks])
    report['reproduced'] = (report['decodeParity']['reproducesQualifiedSeeds']
                            and all(b['allIdentitySetsReproduced'] for b in report['blocks']))
    report['differingIds'] = sorted({i for b in report['blocks'] for mode in STRATEGIES
                                     for i in b['modes'][mode]['productionOnlyIds']
                                     + b['modes'][mode]['qualificationOnlyIds']})
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    target = EVIDENCE / ('comparison.json' if len(blocks) > 1 else f'comparison-block{blocks[0]}.json')
    if target.exists():
        target.unlink()
    freeze(target, report)
    print(json.dumps(dict(reproduced=report['reproduced'],
                          decodeMismatches=report['decodeParity']['mismatchedCases'],
                          counts={b['block']: {mode: b['modes'][mode]['production'] for mode in STRATEGIES}
                                  for b in report['blocks']},
                          differingIds=report['differingIds']), indent=1), flush=True)
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('blocks', nargs='*', type=int, default=[1])
    compare(parser.parse_args().blocks or [1])
