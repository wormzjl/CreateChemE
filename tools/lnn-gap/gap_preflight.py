"""Solver-free decode dumps and the seed half of the parity gate.

No arm in this study may move the seed the promoted path decodes. Six of the seven change only solver
rules, and the seventh adds a second candidate behind the first. So before any campaign time is spent,
every arm decodes the whole population and its first candidate is compared to the promotion run's committed
digests. A mismatch here is a broken study, not an interesting result.
"""
from gap_common import *
from gap_native import execute, next_log
from gap_register import verify_plan
import argparse


def decode():
    registration = verify_plan()
    source = population_path()
    for rule in sorted({decode_rule(name) for name in registration['arms']}):
        target = OUT / 'decode' / (rule.replace(':', '-') + '.jsonl')
        if target.exists():
            print(f'Reusing decode dump {target}', flush=True)
            continue
        execute('V3GapDecodeCheck', [target, source, rule], next_log('decode-%d.log'))


def dump_path(name):
    return OUT / 'decode' / (decode_rule(name).replace(':', '-') + '.jsonl')


def check():
    registration = verify_plan()
    expected = promotion_seed_digests()
    kept = set(registration['population']['ids'])
    result = dict(revision='lnn-gap-preflight-v1', studyPlan=info(OUT / 'study-plan.json'),
                  population=registration['population']['sha256'], arms={})
    first = {}
    for name in registration['arms']:
        rows = {row['id']: row for row in read_rows(dump_path(name))}
        assert set(rows) == kept, f'The decode dump for {name} covers a different population'
        first[name] = {i: seed_digest(row['seed']) for i, row in rows.items()}
        comparable = sorted(i for i in kept if i in expected)
        mismatched = sorted(i for i in comparable if expected[i] != first[name][i])
        offered = [rows[i]['candidateCount'] for i in sorted(rows)]
        alternates = [i for i, row in rows.items()
                      if row.get('alternates') and seed_digest(row['alternates'][0]) != first[name][i]]
        result['arms'][name] = dict(
            decodeRule=decode_rule(name), cases=len(rows),
            supported=sum(row['supported'] for row in rows.values()),
            comparableToPromotion=len(comparable), mismatchedCases=len(mismatched), mismatchedIds=mismatched,
            reproducesPromotionSeeds=not mismatched,
            distinctAlternateSeeds=len(alternates), distinctAlternateIds=sorted(alternates),
            candidatesOffered=dict(total=sum(offered), maximum=max(offered), distribution=dict(
                (str(count), offered.count(count)) for count in sorted(set(offered)))))
    result['allArmsReproducePromotionSeeds'] = all(
        entry['reproducesPromotionSeeds'] for entry in result['arms'].values())
    result['firstCandidateIsInvariantAcrossArms'] = len({tuple(sorted(value.items())) for value in first.values()}) == 1
    freeze(OUT / 'preflight-parity.json', result)
    print(json.dumps(dict(allArmsReproducePromotionSeeds=result['allArmsReproducePromotionSeeds'],
                          firstCandidateIsInvariantAcrossArms=result['firstCandidateIsInvariantAcrossArms'],
                          candidates={name: entry['candidatesOffered']['distribution']
                                      for name, entry in result['arms'].items()},
                          distinctAlternateSeeds={name: entry['distinctAlternateSeeds']
                                                  for name, entry in result['arms'].items()}), indent=1), flush=True)
    assert result['allArmsReproducePromotionSeeds'], 'A study arm moved a decoded seed; stop here.'


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['decode', 'check'])
    mode = parser.parse_args().mode
    decode() if mode == 'decode' else check()
