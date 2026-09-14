"""Read-only reconstruction of the LNN-gap campaign. Runs no solver and decides nothing of its own.

Every threshold this module applies is written in protocol.md and was registered before the campaign ran;
what is computed here is the arithmetic of those rules over the journals, with the ids attached to every
count so a reader can check any of them by hand.
"""
from gap_common import *
from gap_benchmark import validate_run
from gap_register import verify_plan
import argparse
import gzip
import re

# The solver's stall stop publishes this evidence; it is how a trajectory that the early abort cut short is
# told apart from one that ran out of iterations or wall clock.
STALL_STOP = re.compile(r'maximum scaled residual .* at iteration \d+ and .* now')
GROUPS = ('group-b', 'group-c', 'crawling')


def compact(record):
    """Committed per-case evidence: outcome, status, cost and route, without seeds or streams."""
    return dict(block=record['block'], arm=record['arm'], id=record['id'], condition=record['condition'],
                caseServiceMillis=record['caseServiceMillis'], queueWaitMillis=record['queueWaitMillis'],
                modes={mode: dict(outcome=value['outcome'], status=value['status'],
                                  milliseconds=value['milliseconds'], cpuMillis=value.get('cpuMillis'),
                                  newtonIterations=value.get('newtonIterations'),
                                  solvePath=value.get('solvePath'), initializer=value['initializer'],
                                  handoffMillis=value['handoffMillis'], stalled=value['stalled'])
                       for mode, value in record['modes'].items()})


def evidence(row, mode):
    value = dict(row['modes'][mode])
    # A request the deadline cancelled never reached the branch that publishes solver diagnostics, so the
    # probe omits these keys entirely. Absent is not zero and is normalised to null, not filled in.
    for key in ('newtonIterations', 'solvePath', 'cpuMillis'):
        value.setdefault(key, None)
    value['initializer'] = initializer(value)
    value['handoffMillis'] = handoff_millis(value)
    value['stalled'] = any(STALL_STOP.search(str(event)) for event in value.get('events') or [])
    return value


def collect():
    registration = verify_plan()
    arms = list(registration['arms'])
    cases, summaries, metadata, records = {}, {}, {}, []
    for block in BLOCKS:
        for name in arms:
            rows, meta = validate_run(run_directory(name, block), name)
            observed = {}
            for row in sorted(rows, key=lambda r: r['id']):
                record = dict(block=block, arm=name, id=row['id'], condition=row['condition'],
                              caseServiceMillis=row['caseServiceMillis'], queueWaitMillis=row['queueWaitMillis'],
                              modes={mode: evidence(row, mode) for mode in STRATEGIES})
                observed[row['id']] = record
                records.append(compact(record))
            cases[block, name] = observed
            summaries[f'{block}/{name}'] = dict(
                cases=len(observed),
                strict={mode: len(strict_ids(observed, mode)) for mode in STRATEGIES},
                advisory={mode: sum(r['modes'][mode]['outcome'] == 'advisory' for r in observed.values())
                          for mode in STRATEGIES},
                milliseconds={mode: stats([r['modes'][mode]['milliseconds'] for r in observed.values()])
                              for mode in STRATEGIES},
                statuses={mode: counted(r['modes'][mode]['status'] for r in observed.values())
                          for mode in STRATEGIES},
                initializers={mode: counted(r['modes'][mode]['initializer'] for r in observed.values())
                              for mode in STRATEGIES})
            metadata[f'{block}/{name}'] = dict(
                run=info(run_directory(name, block) / 'run.json'),
                evaluation=info(run_directory(name, block) / 'evaluation.jsonl'),
                **{key: meta[key] for key in ('elapsedSeconds', 'modelLoadMillis', 'modelSource', 'scheduling',
                                              'availableProcessors', 'java', 'correction', 'candidateRule',
                                              'recovery', 'rampHandoffBudgetMillis')})
            print(f'Reconstructed block {block} {name}', flush=True)
    return registration, arms, cases, summaries, metadata, records


def counted(values):
    result = {}
    for value in values:
        key = 'none' if value is None else str(value)
        result[key] = result.get(key, 0) + 1
    return dict(sorted(result.items()))


def pooled_mean(cases, name, mode):
    return mean([cases[block, name][i]['modes'][mode]['milliseconds']
                 for block in BLOCKS for i in sorted(cases[block, name])])


def block_mean(cases, name, mode, block):
    return mean([cases[block, name][i]['modes'][mode]['milliseconds'] for i in sorted(cases[block, name])])


def paired(cases, candidate, mode):
    """Per-block gains and losses against the baseline, as identities rather than counts."""
    blocks, stable_reference, stable_candidate = {}, None, None
    for block in BLOCKS:
        a = strict_ids(cases[block, REFERENCE], mode)
        b = strict_ids(cases[block, candidate], mode)
        blocks[str(block)] = dict(referenceStrict=len(a), candidateStrict=len(b), gained=len(b - a),
                                  lost=len(a - b), net=len(b) - len(a),
                                  gainedIds=sorted(b - a), lostIds=sorted(a - b))
        stable_reference = a if stable_reference is None else stable_reference & a
        stable_candidate = b if stable_candidate is None else stable_candidate & b
    both = sorted(stable_reference & stable_candidate)
    costs = {label: stats([cases[block, name][i]['modes'][mode]['milliseconds'] for block in BLOCKS for i in both])
             for label, name in (('reference', REFERENCE), ('candidate', candidate))}
    delta = [cases[block, candidate][i]['modes'][mode]['milliseconds']
             - cases[block, REFERENCE][i]['modes'][mode]['milliseconds'] for block in BLOCKS for i in both]
    return dict(blocks=blocks,
                gainsReproduceAcrossBlocks=blocks['1']['gainedIds'] == blocks['2']['gainedIds'],
                lossesReproduceAcrossBlocks=blocks['1']['lostIds'] == blocks['2']['lostIds'],
                stableGainedIds=sorted(stable_candidate - stable_reference),
                stableLostIds=sorted(stable_reference - stable_candidate),
                netGainBothBlocks=min(blocks[str(b)]['net'] for b in BLOCKS),
                commonSuccess=dict(cases=len(both), costs=costs, pairedMillis=stats(delta)),
                commonFailure=failure_costs(cases, candidate, mode),
                allCaseMeanMillis=dict(reference=pooled_mean(cases, REFERENCE, mode),
                                       candidate=pooled_mean(cases, candidate, mode)))


def failure_costs(cases, candidate, mode):
    """Latency on cases both arms fail in both blocks: where a reshaped budget acts first."""
    failing = None
    for block in BLOCKS:
        for name in (REFERENCE, candidate):
            group = {i for i, r in cases[block, name].items() if r['modes'][mode]['outcome'] != 'strict'}
            failing = group if failing is None else failing & group
    both = sorted(failing)
    if not both:
        return None
    values = {label: stats([cases[block, name][i]['modes'][mode]['milliseconds'] for block in BLOCKS for i in both])
              for label, name in (('reference', REFERENCE), ('candidate', candidate))}
    iterations = {label: stats([cases[block, name][i]['modes'][mode]['newtonIterations'] or 0
                                for block in BLOCKS for i in both])
                  for label, name in (('reference', REFERENCE), ('candidate', candidate))}
    delta = [cases[block, candidate][i]['modes'][mode]['milliseconds']
             - cases[block, REFERENCE][i]['modes'][mode]['milliseconds'] for block in BLOCKS for i in both]
    return dict(cases=len(both), milliseconds=values, pairedMillis=stats(delta), newtonIterations=iterations)


def group_table(cases, arms, kept):
    """What each arm did to the three registered target groups, per mode and per block."""
    table = {}
    for group in GROUPS:
        members = [i for i in id_list(group) if i in kept]
        entry = dict(registered=len(id_list(group)), present=len(members), ids=members, arms={})
        for name in arms:
            entry['arms'][name] = dict(
                onlyStrict=[len(strict_ids(cases[block, name], 'neural') & set(members)) for block in BLOCKS],
                firstStrict=[len(strict_ids(cases[block, name], 'neuralFirst') & set(members)) for block in BLOCKS],
                onlyRecoveredIds={str(block): sorted(
                    i for i in members
                    if cases[block, name][i]['modes']['neural']['outcome'] == 'strict'
                    and cases[block, REFERENCE][i]['modes']['neural']['outcome'] != 'strict') for block in BLOCKS},
                onlyLostIds={str(block): sorted(
                    i for i in members
                    if cases[block, name][i]['modes']['neural']['outcome'] != 'strict'
                    and cases[block, REFERENCE][i]['modes']['neural']['outcome'] == 'strict') for block in BLOCKS},
                onlyMillis=[stats([cases[block, name][i]['modes']['neural']['milliseconds'] for i in members])
                            for block in BLOCKS],
                onlyIterations=[stats([cases[block, name][i]['modes']['neural']['newtonIterations'] or 0
                                       for i in members]) for block in BLOCKS])
        table[group] = entry
    return table


def crawling_recall(cases, arms, kept):
    """The pre-declared E2 criterion: how many crawling trajectories a rule lets continue.

    A trajectory continued when its neural-only request was strict, or when its terminal stop was anything
    other than the early stall abort. It is the recall of the abort against the group the abort is most
    likely to be cutting short, and it is reported for every arm whether or not a rule consumes it.
    """
    members = [i for i in id_list('crawling') if i in kept]
    result = dict(cases=len(members), ids=members, arms={})
    for name in arms:
        per_block = []
        for block in BLOCKS:
            allowed = [i for i in members
                       if cases[block, name][i]['modes']['neural']['outcome'] == 'strict'
                       or not cases[block, name][i]['modes']['neural']['stalled']]
            per_block.append(dict(continued=len(allowed), fraction=len(allowed) / len(members) if members else None,
                                  abortedIds=sorted(set(members) - set(allowed))))
        result['arms'][name] = dict(byBlock=per_block,
                                    minimumFraction=min(b['fraction'] for b in per_block) if members else None)
    return result


def handoff_table(cases, arms):
    """What the ramp handoff cost and bought, separately from the learned allowance."""
    table = {}
    for name in arms:
        if spec(name)['recovery'] == 'NONE':
            continue
        entry = {}
        for mode in ('neural', 'neuralFirst'):
            armed, spent, accepted = [], [], []
            for block in BLOCKS:
                for i, record in cases[block, name].items():
                    value = record['modes'][mode]
                    if value['handoffMillis'] is None:
                        continue
                    armed.append(i)
                    spent.append(value['handoffMillis'])
                    if value['initializer'] == 'LNN_RAMP_HANDOFF':
                        accepted.append(i)
            entry[mode] = dict(
                armedRequests=len(armed), armedCases=len(set(armed)),
                acceptedRequests=len(accepted), acceptedIds=sorted(set(accepted)),
                millisecondsWhenArmed=stats(spent), totalSecondsSpent=sum(spent) / 1000 if spent else 0.0,
                millisecondsWhenAccepted=stats(
                    [record['modes'][mode]['handoffMillis'] for block in BLOCKS
                     for record in cases[block, name].values()
                     if record['modes'][mode]['initializer'] == 'LNN_RAMP_HANDOFF']),
                millisecondsWhenRefused=stats(
                    [record['modes'][mode]['handoffMillis'] for block in BLOCKS
                     for record in cases[block, name].values()
                     if record['modes'][mode]['handoffMillis'] is not None
                     and record['modes'][mode]['initializer'] != 'LNN_RAMP_HANDOFF']),
                strictAmongAccepted=sum(
                    record['modes'][mode]['outcome'] == 'strict' for block in BLOCKS
                    for record in cases[block, name].values()
                    if record['modes'][mode]['initializer'] == 'LNN_RAMP_HANDOFF'))
        table[name] = entry
    return table


def classical_union(cases, arms):
    """Cases classical solves strictly in every run of this campaign; no arm may lose one under FIRST."""
    groups = [strict_ids(cases[block, name], 'current') for block in BLOCKS for name in arms]
    return set.intersection(*groups), set.union(*groups)


def noise_band(cases):
    """The baseline's own between-block spread in the pooled FIRST mean.

    Two runs of an identical pipeline on identical seeds do not produce identical means, so the latency gate
    is applied against this band rather than against zero. Declared in protocol.md before the campaign and
    measured here from the baseline arm alone, never from a candidate.
    """
    return abs(block_mean(cases, REFERENCE, 'neuralFirst', BLOCKS[0])
               - block_mean(cases, REFERENCE, 'neuralFirst', BLOCKS[1]))


def gate(cases, candidate, union, band):
    first = [strict_ids(cases[block, candidate], 'neuralFirst') for block in BLOCKS]
    reference = [strict_ids(cases[block, REFERENCE], 'neuralFirst') for block in BLOCKS]
    improves = all(len(b) > len(a) for a, b in zip(reference, first))
    preserves = all(union <= group for group in first)
    candidate_mean = pooled_mean(cases, candidate, 'neuralFirst')
    reference_mean = pooled_mean(cases, REFERENCE, 'neuralFirst')
    return dict(reference=REFERENCE, candidate=candidate,
                referenceStrictFirstCounts=[len(x) for x in reference],
                strictFirstCounts=[len(x) for x in first],
                improvesStrictFirstBothBlocks=improves, preservesClassicalUnionBothBlocks=preserves,
                pooledFirstMeanMillis=candidate_mean, referencePooledFirstMeanMillis=reference_mean,
                latencyDeltaMillis=candidate_mean - reference_mean, noiseBandMillis=band,
                noPooledMeanLatencyRegressionStrict=candidate_mean <= reference_mean,
                noPooledMeanLatencyRegressionWithinBand=candidate_mean <= reference_mean + band,
                passed=improves and preserves and candidate_mean <= reference_mean + band,
                classicalMissedIds={str(block): sorted(union - first[index])
                                    for index, block in enumerate(BLOCKS)})


def parity(cases, kept):
    """The baseline arm must be the promotion run, restricted to the ids the cleaned population kept."""
    result = {}
    for block in BLOCKS:
        archived = promotion_cases(block)
        comparable = {i for i in kept if i in archived}
        entry = {}
        for mode in STRATEGIES:
            theirs = {i for i in strict_ids(archived, mode) if i in comparable}
            mine = {i for i in strict_ids(cases[block, REFERENCE], mode) if i in comparable}
            entry[mode] = dict(promotion=len(theirs), baseline=len(mine), identical=theirs == mine,
                               baselineOnlyIds=sorted(mine - theirs), promotionOnlyIds=sorted(theirs - mine))
        entry['comparableCases'] = len(comparable)
        result[str(block)] = entry
    result['allIdentitySetsReproduced'] = all(
        result[str(block)][mode]['identical'] for block in BLOCKS for mode in STRATEGIES)
    return result


def select(contrasts, gates, groups, recall):
    """The registered selection rules, applied. Nothing here reads a number it was not told to read."""
    decisions = {}

    def net_only(name):
        return contrasts[name]['neural']['netGainBothBlocks']

    def cost(name):
        return gates[name]['pooledFirstMeanMillis']

    def eligible(name):
        return net_only(name) > 0 and gates[name]['noPooledMeanLatencyRegressionWithinBand']

    for experiment, arms in (('E1', ['E1a', 'E1b']), ('E2', ['E2a', 'E2b'])):
        present = [name for name in arms if name in contrasts]
        qualifying = [name for name in present if eligible(name)]
        chosen = None
        if qualifying:
            best = max(net_only(name) for name in qualifying)
            tied = [name for name in qualifying if net_only(name) == best]
            if len(tied) > 1 and experiment == 'E2':
                recalls = {name: recall['arms'][name]['minimumFraction'] for name in tied}
                top = max(recalls.values())
                tied = [name for name in tied if recalls[name] == top]
            chosen = min(tied, key=cost)
        decisions[experiment] = dict(arms=present, qualifying=qualifying, selected=chosen,
                                     netOnlyGainBothBlocks={name: net_only(name) for name in present},
                                     pooledFirstMeanMillis={name: cost(name) for name in present},
                                     crawlingRecall={name: recall['arms'][name]['minimumFraction']
                                                     for name in present})
    for experiment, name in (('E3', 'E3'), ('E4', 'E4')):
        if name not in contrasts:
            decisions[experiment] = dict(arms=[], qualifying=[], selected=None)
            continue
        decisions[experiment] = dict(
            arms=[name], qualifying=[name] if eligible(name) else [], selected=name if eligible(name) else None,
            netOnlyGainBothBlocks={name: net_only(name)}, pooledFirstMeanMillis={name: cost(name)},
            groupBRecovery=groups['group-b']['arms'][name]['onlyRecoveredIds'],
            groupCRecovery=groups['group-c']['arms'][name]['onlyRecoveredIds'])
    decisions['combination'] = combination([decisions[e]['selected'] for e in ('E1', 'E2', 'E3', 'E4')])
    if COMBINATION in contrasts:
        # Round two. The combination is recommended over the best single arm only when it beats every one
        # of them on LNN_ONLY in both blocks, which is a comparison against round one's own journals.
        previous = read(COMBINATION_SOURCE) if COMBINATION_SOURCE.exists() else None
        singles = {} if previous is None else {
            name: value['neural']['netGainBothBlocks'] for name, value in previous['contrasts'].items()}
        best = max(singles.values()) if singles else None
        decisions[COMBINATION] = dict(
            arms=[COMBINATION], qualifying=[COMBINATION] if eligible(COMBINATION) else [],
            selected=COMBINATION if eligible(COMBINATION) and (best is None or net_only(COMBINATION) > best)
            else None,
            netOnlyGainBothBlocks={COMBINATION: net_only(COMBINATION)},
            roundOneNetOnlyGains=singles, roundOneBest=best,
            groupBRecovery=groups['group-b']['arms'][COMBINATION]['onlyRecoveredIds'],
            groupCRecovery=groups['group-c']['arms'][COMBINATION]['onlyRecoveredIds'])
    return decisions


def combination(selected):
    """The registered E5 rule: one arm carrying every field its experiment selected."""
    chosen = [name for name in selected if name]
    if not chosen:
        return dict(selected=[], arm=None,
                    note='No experiment qualified; the recommendation is the promoted defaults unchanged.')
    rule = dict(PROMOTED_CORRECTION)
    if 'E1a' in chosen:
        rule['contractionFactor'] = ARMS['E1a']['correction']['contractionFactor']
    if 'E1b' in chosen:
        for key in ('maximumIterations', 'extensionBlock', 'extensionMaximumIterations',
                    'contractionWindow', 'contractionFactor'):
            rule[key] = ARMS['E1b']['correction'][key]
    for name in ('E2a', 'E2b'):
        if name in chosen:
            for key in ('stallWindow', 'stallFactor', 'stallResidualFloor'):
                rule[key] = ARMS[name]['correction'][key]
    return dict(selected=chosen, arm=dict(
        decoder=PHASE_FLOOR_DECODER, correction=rule,
        candidates='DECODE_VARIANTS' if 'E4' in chosen else 'SINGLE',
        recovery='RAMP_HANDOFF' if 'E3' in chosen else 'NONE'),
        note='E1 supplies the extension fields, E2 the stall fields, E3 the recovery rule and E4 the '
             'candidate rule. E5 is recommended over the best single arm only if it is strictly better on '
             'LNN_ONLY in both blocks.')


def build():
    registration, arms, cases, summaries, metadata, records = collect()
    kept = set(registration['population']['ids'])
    union, loose = classical_union(cases, arms)
    band = noise_band(cases)
    candidates = [name for name in arms if name != REFERENCE]
    contrasts = {name: {mode: paired(cases, name, mode) for mode in ('neural', 'neuralFirst')}
                 for name in candidates}
    gates = {name: gate(cases, name, union, band) for name in candidates}
    groups = group_table(cases, arms, kept)
    recall = crawling_recall(cases, arms, kept)
    result = dict(
        revision='lnn-gap-analysis-v1', studyPlan=info(OUT / 'study-plan.json'),
        preflight=info(OUT / 'preflight-parity.json'), arms=arms, blocks=BLOCKS, reference=REFERENCE,
        cases=registration['population']['cases'], population=registration['population']['sha256'],
        measuredRequests=len(arms) * len(BLOCKS) * len(STRATEGIES) * registration['population']['cases'],
        summaries=summaries, runMetadata=metadata,
        classicalStrictStableAcrossEveryRun=union == loose,
        classicalStrictUnionIds=sorted(union),
        latencyNoiseBandMillis=band,
        parity=parity(cases, kept),
        contrasts=contrasts, qualification=gates,
        groups=groups, crawlingRecall=recall, handoff=handoff_table(cases, arms),
        decisions=select(contrasts, gates, groups, recall),
        evidenceRules=[
            'Strict outcomes require the unchanged native audit and the final Newton certificate.',
            'FIRST includes classical fallback; ONLY is a separately timed neural-only request.',
            'All-case means include failed and advisory requests; common-success and common-failure costs '
            'are reported beside them, because a rule that fails earlier lowers an all-case mean without '
            'solving anything.',
            'The ramp handoff spends the request deadline under its own sub-wall, never the learned '
            'allowance; its cost is reported separately and is included in the mode timings it belongs to.',
            'Every arm loads the identical bundled weight bytes; the preflight proves the first decoded '
            'candidate is the promoted one for all of them.'],
        interpretation='Historical validation on a cleaned population, not a fresh holdout. The two blocks '
                       'repeat the same inputs in reversed arm order, so their spread describes case and '
                       'load variability rather than a confidence interval over independent campaigns.')
    return result, records


def main():
    result, records = build()
    freeze(OUT / 'validation-analysis.json', result)
    path = OUT / 'validation-case-evidence.jsonl.gz'
    assert not path.exists(), path
    with gzip.open(path, 'wt', encoding='utf-8', newline='\n') as stream:
        for row in records:
            stream.write(json.dumps(row, sort_keys=True, allow_nan=False) + '\n')
    print(json.dumps(dict(
        parity=result['parity']['allIdentitySetsReproduced'],
        strict={key: value['strict'] for key, value in result['summaries'].items()},
        passed=[name for name, value in result['qualification'].items() if value['passed']],
        decisions={key: value.get('selected') for key, value in result['decisions'].items()}), indent=1),
        flush=True)


if __name__ == '__main__':
    argparse.ArgumentParser().parse_args()
    main()
