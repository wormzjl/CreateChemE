"""Solver-free decode preflight and archive parity gate.

Run before any campaign time is spent. The default variant must reproduce the predecessor campaign's
archived F0 seeds exactly on all 405 inputs; if it does not, the rebuilt core or the added decode
option changed the production path and the campaign is meaningless.
"""
from floor_common import *
from floor_native import execute
from floor_register import ARCHIVED_F0, verify_plan
import argparse


def decode_path(name):
    return OUT / 'preflight/decode' / f'{name}.jsonl'


def decode_all():
    verify_plan()
    for name in ORDER:
        path = decode_path(name)
        if path.exists():
            print(f'Reusing decode dump {path}', flush=True)
            continue
        execute('V3FloorDecodeCheck', [path, INPUTS / 'validation-inputs.jsonl', pipeline_path(name)],
                OUT / 'logs' / f'decode-{name}.log')


def seeds(name):
    return {row['id']: row['seed'] for row in read_rows(decode_path(name))}


def archived_seeds(block):
    result = {}
    with Path(ARCHIVED_F0[block]).open(encoding='utf-8') as stream:
        for line in stream:
            if not line.strip():
                continue
            row = json.loads(line)
            result[row['id']] = row['rawPrediction'].get('seedPresentedByPipeline')
    return result


def floors(input_row):
    feed = np.asarray(input_row['feedComponentMolarFlowsMolPerSecond'], float)
    return np.maximum(feed, feed.sum() * 1e-12) * 1e-10, feed


def lift_counts(base, candidate, inputs):
    """Entries the candidate seeds above zero where the default decoder pruned to zero."""
    changed, lifted, cases = 0, 0, []
    for case, seed in candidate.items():
        reference = base[case]
        if (reference is None) != (seed is None):
            changed += 1
            cases.append(case)
            continue
        if seed is None:
            continue
        floor, feed = floors(inputs[case]['input'])
        difference = 0
        for phase in ('liquid', 'vapor'):
            actual = np.asarray(seed[phase], float)
            previous = np.asarray(reference[phase], float)
            assert actual.shape == previous.shape
            new = (previous == 0) & (actual > 0)
            difference += int(new.sum())
            assert np.allclose(actual[~new], previous[~new], rtol=0, atol=0), case
            if new.any():
                ratio = actual[new] / np.broadcast_to(floor, actual.shape)[new]
                assert np.isfinite(ratio).all()
        if difference:
            changed += 1
            cases.append(case)
        lifted += difference
    return dict(changedCases=changed, liftedEntries=lifted, changedCaseIds=sorted(cases))


def omissions(name, references, inputs):
    """Above-floor reference entries the decoder leaves at zero, on the 168 certified references."""
    decoded = seeds(name)
    total, above, columns, unavailable = 0, 0, 0, 0
    for case, reference in references.items():
        seed = decoded[case]
        if seed is None:
            unavailable += 1
            continue
        floor, feed = floors(inputs[case]['input'])
        columns += 1
        for phase in ('liquid', 'vapor'):
            actual = np.asarray(seed[phase], float)
            wanted = np.asarray(reference['seed'][phase], float)
            mask = (wanted >= floor) & (feed[None, :] > 0)
            above += int(mask.sum())
            total += int((mask & (actual == 0)).sum())
    return dict(referenceColumns=columns, unavailable=unavailable, referenceAboveFloorEntries=above,
                aboveFloorOmissions=total,
                aboveFloorOmissionsPerColumn=total / columns if columns else None)


def check():
    plan = verify_plan()
    inputs = {row['id']: row for row in read_rows(INPUTS / 'validation-inputs.jsonl')}
    references = {row['id']: row for row in read_rows(INPUTS / 'validation-references.jsonl')}
    base = seeds('F0-baseline')
    assert set(base) == set(inputs)
    parity = {}
    for block in BLOCKS:
        archived = archived_seeds(block)
        assert set(archived) == set(base)
        mismatched = sorted(case for case in base if archived[case] != base[case])
        parity[str(block)] = dict(cases=len(base), mismatchedCases=len(mismatched),
                                  mismatchedIds=mismatched[:20],
                                  supported=sum(seed is not None for seed in base.values()),
                                  archivedSupported=sum(seed is not None for seed in archived.values()),
                                  journal=external(ARCHIVED_F0[block]))
    result = dict(revision='decoder-floor-preflight-v1', studyPlan=info(OUT / 'study-plan.json'),
                  decodeDumps={name: info(decode_path(name)) for name in ORDER},
                  archivedSeedParity=parity,
                  defaultDecodeReproducesArchive=all(value['mismatchedCases'] == 0 for value in parity.values()),
                  liftEffect={name: lift_counts(base, seeds(name), inputs) for name in ORDER if name != 'F0-baseline'},
                  referenceOmissions={name: omissions(name, references, inputs) for name in ORDER},
                  solverRequests=0, note='Decode only: no solver, no acceptance audit, no timing claim.')
    assert result['defaultDecodeReproducesArchive'], 'Default decode diverged from the archived F0 seeds'
    freeze(OUT / 'preflight-parity.json', result)
    print(json.dumps(dict(parity=result['defaultDecodeReproducesArchive'],
                          lift={name: value['liftedEntries'] for name, value in result['liftEffect'].items()},
                          omissions={name: value['aboveFloorOmissions']
                                     for name, value in result['referenceOmissions'].items()})), flush=True)
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['decode', 'check'])
    mode = parser.parse_args().mode
    decode_all() if mode == 'decode' else check()
