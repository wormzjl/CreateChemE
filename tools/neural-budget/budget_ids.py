"""Derive the diagnostic's target groups from committed per-case evidence. Runs no solver.

The three groups the review names are read out of the decoder-floor campaign's committed baseline
evidence, which reproduced the archived F0 journals exactly on all 405 inputs in both blocks. A case
enters a group when it qualifies in either block; the intersection is recorded beside it, because a
case that is iteration-capped in one block and time-capped in the other is the same case sitting on the
same wall a few milliseconds apart, and both blocks are needed to see that.
"""
from budget_common import *
import argparse
import gzip

SOURCE = ROOT / 'tools/decoder-floor-followup/evidence'
PROFILES = SOURCE / 'profile-evidence.jsonl.gz'
BASELINE = 'F0-baseline'

GROUPS = {
    'iteration-capped': 'Neural-only failures whose observed stop evidence includes the correction-iteration cap.',
    'time-capped': 'Neural-only failures whose observed stop evidence includes the two-second neural budget wall.',
    'classical-only-loss': 'Cases the classical solver takes strictly while the neural-only request does not.',
}


def rows(path):
    with gzip.open(path, 'rt', encoding='utf-8') as stream:
        return [json.loads(line) for line in stream if line.strip()]


def baseline_cases():
    cases = {}
    for block in BLOCKS:
        path = SOURCE / f'case-block{block}-{BASELINE}.jsonl.gz'
        observed = {row['id']: row for row in rows(path)}
        assert len(observed) == 405, path
        cases[block] = observed
    return cases


def membership(cases, group):
    result = {}
    for block, observed in cases.items():
        selected = set()
        for case, record in observed.items():
            neural = record['modes']['neural']
            if group == 'classical-only-loss':
                if record['modes']['current']['outcome'] == 'strict' and neural['outcome'] != 'strict':
                    selected.add(case)
                continue
            if neural['outcome'] == 'strict':
                continue
            phrase = 'iteration_limit' if group == 'iteration-capped' else 'neural_time_limit'
            if phrase in neural['observedStopPhrases']:
                selected.add(case)
        result[block] = selected
    return result


def omissions(cases):
    """Reference seed-omission evidence per case, joined from the same campaign's profile evidence."""
    result = {}
    for row in rows(PROFILES):
        if row['pipeline'] != BASELINE or row['block'] != BLOCKS[0] or not row['available']:
            continue
        result[row['id']] = dict(
            referenceAboveFloorEntries=row['referenceAboveFloorEntries'],
            aboveFloorOmissions=row['aboveFloorOmissions'],
            zeroAllowedPhaseOmissions=row['zeroAllowedPhaseOmissions'],
            positivePhaseComponentOmissions=row['positivePhaseComponentOmissions'],
            predictedBranchOmissions=row['predictedBranchOmissions'],
            branchMatchesReference=row['branchMatchesReference'])
    assert len(result) == 168, len(result)
    return result


def store(path, payload):
    """Frozen once. A rerun must reproduce the committed bytes rather than quietly replace them."""
    if path.exists():
        assert read(path) == payload, path
    else:
        freeze(path, payload)
    return info(path)


def build():
    cases = baseline_cases()
    reference = omissions(cases)
    written, groups = [], {}
    for group, description in GROUPS.items():
        present = membership(cases, group)
        union = sorted(set.union(*present.values()))
        intersection = sorted(set.intersection(*present.values()))
        payload = dict(
            revision='neural-budget-ids-v1', group=group, description=description,
            source=[info(SOURCE / f'case-block{block}-{BASELINE}.jsonl.gz') for block in BLOCKS],
            perBlockCounts={str(block): len(present[block]) for block in BLOCKS},
            stableAcrossBlocks=len(intersection), ids=union,
            stableIds=intersection,
            referenceProfileCases=sorted(case for case in union if case in reference))
        written.append(store(IDS / f'{group}.json', payload))
        groups[group] = payload

    targets = sorted(set().union(*(set(value['ids']) for value in groups.values())))
    payload = dict(
        revision='neural-budget-ids-v1', group='trace-targets',
        description='Every case the bounded diagnostic traces: the union of the three groups above.',
        groups={group: len(value['ids']) for group, value in groups.items()},
        perBlockCounts={group: value['perBlockCounts'] for group, value in groups.items()},
        ids=targets,
        referenceProfileCases=sorted(case for case in targets if case in reference),
        seedOmissionEvidence={case: reference[case] for case in targets if case in reference},
        source=[info(SOURCE / f'case-block{block}-{BASELINE}.jsonl.gz') for block in BLOCKS] + [info(PROFILES)])
    written.append(store(IDS / 'trace-targets.json', payload))
    print(json.dumps(dict(groups={group: len(value['ids']) for group, value in groups.items()},
                          stable={group: value['stableAcrossBlocks'] for group, value in groups.items()},
                          traceTargets=len(targets), withReferenceProfile=len(payload['referenceProfileCases']),
                          files=[entry['path'] for entry in written])), flush=True)


def emit_inputs():
    """Write the traced subset of the frozen validation inputs, in the frozen file's own order."""
    targets = set(id_list('trace-targets'))
    source = INPUTS / 'validation-inputs.jsonl'
    assert digest(source) == VALIDATION_INPUTS_SHA
    selected = [row for row in read_rows(source) if row['id'] in targets]
    assert len(selected) == len(targets), (len(selected), len(targets))
    path = INPUTS / 'trace-inputs.jsonl'
    if path.exists():
        assert read_rows(path) == selected, path
    else:
        with path.open('x', encoding='utf-8', newline='\n') as stream:
            for row in selected:
                stream.write(json.dumps(row, sort_keys=True, allow_nan=False) + '\n')
    print(json.dumps(dict(traceInputs=info(path), cases=len(selected))), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['build', 'inputs'])
    mode = parser.parse_args().mode
    build() if mode == 'build' else emit_inputs()
