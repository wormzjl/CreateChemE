"""Read-only reconstruction of the decoder-floor campaign. Runs no solver and selects nothing.

Every evidence definition is imported from the predecessor's analysis module so the counts here mean
exactly what the archived campaign's counts mean.
"""
from floor_common import *
from floor_benchmark import validate_run
from floor_register import ARCHIVED_F0, verify_plan
from floor_preflight import decode_path
import sys as _sys
_sys.path.insert(0, str(ROOT / 'tools/trace-followup'))
from analysis_common import (mode_evidence, condition, profile_evidence, summarize_evidence,  # noqa: E402
                             summarize_profiles, paired_profiles, optional)
import argparse
import gzip
from collections import Counter

REFERENCE = 'F0-baseline'


def compact(record):
    """Committed per-case evidence: outcome, status, cost and stop evidence, without seeds or streams."""
    return dict(block=record['block'], pipeline=record['pipeline'], id=record['id'], condition=record['condition'],
                pipelineSeedAvailable=record['pipelineSeedAvailable'],
                initialProjectedMaximumScaledMeshResidual=record['initialProjectedMaximumScaledMeshResidual'],
                caseServiceMillis=record['caseServiceMillis'], queueWaitMillis=record['queueWaitMillis'],
                modes={mode: {key: value[key] for key in ('outcome', 'status', 'milliseconds', 'cpuMillis',
                                                          'newtonIterations', 'observedStopPhrases',
                                                          'terminalTrajectoryOwner', 'fallbackObserved')}
                       for mode, value in record['modes'].items()})


def collect():
    plan = verify_plan()
    references = {row['id']: row for row in read_rows(INPUTS / 'validation-references.jsonl')}
    cases, profiles, summaries, metadata = {}, {}, {}, {}
    case_records, profile_records, classical = [], [], {}
    for block in BLOCKS:
        for name in ORDER:
            directory = run_directory(name, block)
            rows, meta = validate_run(directory, name)
            rows.sort(key=lambda row: row['id'])
            observed, seen = {}, {}
            for row in rows:
                modes = {mode: mode_evidence(row, mode) for mode in STRATEGIES}
                record = dict(block=block, pipeline=name, id=row['id'],
                              canonicalInputSha256=canonical_input_hash(row['input']), condition=condition(row),
                              modes=modes, pipelineSeedAvailable=row['rawPrediction']['supported'],
                              rawDiagnosticMillis=row['rawPrediction'].get('ms'),
                              initialProjectedMaximumScaledMeshResidual=(
                                  row['rawPrediction'].get('nativeResidual') or {}).get('maximumScaledMeshResidual'),
                              caseServiceMillis=row['caseServiceMillis'], queueWaitMillis=row['queueWaitMillis'])
                observed[row['id']] = record
                case_records.append(compact(record))
                if row['id'] in references:
                    profile = profile_evidence(row, references[row['id']])
                    profile.update(block=block, pipeline=name)
                    seen[row['id']] = profile
                    profile_records.append(profile)
            cases[block, name] = observed
            profiles[block, name] = seen
            ids = sorted(observed)
            classical[block, name] = {i for i in ids if observed[i]['modes']['current']['outcome'] == 'strict'}
            summaries[f'{block}/{name}'] = dict(
                cases=len(ids),
                modes={mode: summarize_evidence([observed[i]['modes'][mode] for i in ids]) for mode in STRATEGIES},
                profiles=summarize_profiles([seen[i] for i in sorted(seen)]),
                **{key: optional([observed[i][key] for i in ids]) for key in (
                    'initialProjectedMaximumScaledMeshResidual', 'rawDiagnosticMillis',
                    'caseServiceMillis', 'queueWaitMillis')})
            metadata[f'{block}/{name}'] = dict(
                run=info(directory / 'run.json'), evaluation=info(directory / 'evaluation.jsonl'),
                **{key: meta[key] for key in ('elapsedSeconds', 'modelLoadMillis', 'heapUsedAtEndBytes',
                                              'poolPeakUsage', 'scheduling', 'availableProcessors', 'java')})
            print(f'Reconstructed block {block} {name}', flush=True)
    return plan, cases, profiles, summaries, metadata, case_records, profile_records, classical, references


def strict_ids(cases, block, name, mode):
    return {i for i, record in cases[block, name].items() if record['modes'][mode]['outcome'] == 'strict'}


def pooled_mean(cases, name, mode):
    return float(np.mean([cases[block, name][i]['modes'][mode]['milliseconds']
                          for block in BLOCKS for i in sorted(cases[block, name])]))


def contrast(cases, profiles, candidate):
    result = dict(reference=REFERENCE, candidate=candidate, byMode={})
    for mode in ('neural', 'neuralFirst'):
        blocks = {}
        stable_reference, stable_candidate = None, None
        for block in BLOCKS:
            a = strict_ids(cases, block, REFERENCE, mode)
            b = strict_ids(cases, block, candidate, mode)
            blocks[str(block)] = dict(referenceStrict=len(a), candidateStrict=len(b),
                                      gainedIds=sorted(b - a), lostIds=sorted(a - b),
                                      gained=len(b - a), lost=len(a - b))
            stable_reference = a if stable_reference is None else stable_reference & a
            stable_candidate = b if stable_candidate is None else stable_candidate & b
        both = sorted(stable_reference & stable_candidate)
        costs = {}
        for label, name in (('reference', REFERENCE), ('candidate', candidate)):
            values = [cases[block, name][i]['modes'][mode]['milliseconds'] for block in BLOCKS for i in both]
            costs[label] = dict(meanMillis=float(np.mean(values)), medianMillis=float(np.median(values)))
        paired = [cases[block, candidate][i]['modes'][mode]['milliseconds']
                  - cases[block, REFERENCE][i]['modes'][mode]['milliseconds'] for block in BLOCKS for i in both]
        result['byMode'][mode] = dict(
            blocks=blocks, reproducedAcrossBlocks=(blocks['1']['gainedIds'] == blocks['2']['gainedIds']
                                                   and blocks['1']['lostIds'] == blocks['2']['lostIds']),
            stableBothSuccessCases=len(both), stableCommonSuccessIds=both, stableCommonSuccessCosts=costs,
            stablePairedMillis=dict(meanMillis=float(np.mean(paired)), medianMillis=float(np.median(paired)))
            if both else None,
            allCaseMeanMillis=dict(reference=pooled_mean(cases, REFERENCE, mode),
                                   candidate=pooled_mean(cases, candidate, mode)))
    a, b = profiles[1, REFERENCE], profiles[1, candidate]
    result['profiles'] = paired_profiles(a, b, sorted(a))
    return result


def gate(cases, candidate, classical_union):
    first = [strict_ids(cases, block, candidate, 'neuralFirst') for block in BLOCKS]
    reference = [strict_ids(cases, block, REFERENCE, 'neuralFirst') for block in BLOCKS]
    improves = all(len(b) > len(a) for a, b in zip(reference, first))
    preserves = all(classical_union <= group for group in first)
    candidate_mean = pooled_mean(cases, candidate, 'neuralFirst')
    reference_mean = pooled_mean(cases, REFERENCE, 'neuralFirst')
    timing = candidate_mean <= reference_mean
    return dict(reference=REFERENCE, candidate=candidate,
                referenceStrictFirstCounts=[len(x) for x in reference], strictFirstCounts=[len(x) for x in first],
                improvesStrictFirstBothBlocks=improves, preservesClassicalUnionBothBlocks=preserves,
                noPooledMeanLatencyRegression=timing, passed=improves and preserves and timing,
                pooledFirstMeanMillis=candidate_mean, referencePooledFirstMeanMillis=reference_mean,
                classicalMissedIds={str(block): sorted(classical_union - first[j])
                                    for j, block in enumerate(BLOCKS)})


def archive_parity(cases, classical_union):
    """The baseline must reproduce the predecessor campaign's F0 strict identity sets exactly."""
    result = {}
    for block in BLOCKS:
        archived = {'current': set(), 'neural': set(), 'neuralFirst': set()}
        with Path(ARCHIVED_F0[block]).open(encoding='utf-8') as stream:
            for line in stream:
                if not line.strip():
                    continue
                row = json.loads(line)
                for mode in STRATEGIES:
                    if strict({**row, **row[mode]}):
                        archived[mode].add(row['id'])
        mine = {mode: strict_ids(cases, block, REFERENCE, mode) for mode in STRATEGIES}
        result[str(block)] = dict(journal=external(ARCHIVED_F0[block]), **{
            mode: dict(archived=len(archived[mode]), study=len(mine[mode]), identical=archived[mode] == mine[mode],
                       studyOnlyIds=sorted(mine[mode] - archived[mode]),
                       archiveOnlyIds=sorted(archived[mode] - mine[mode])) for mode in STRATEGIES})
    result['classicalUnionCases'] = len(classical_union)
    result['allIdentitySetsReproduced'] = all(
        result[str(block)][mode]['identical'] for block in BLOCKS for mode in STRATEGIES)
    return result


def regimes(cases, key):
    table = {}
    for name in ORDER:
        table[name] = {}
        values = sorted({str(record['condition'][key]) for record in cases[1, name].values()},
                        key=lambda v: (len(v), v))
        for value in values:
            entry = dict(cases=0, strict={mode: [] for mode in STRATEGIES})
            for block in BLOCKS:
                subset = [r for r in cases[block, name].values() if str(r['condition'][key]) == value]
                if block == BLOCKS[0]:
                    entry['cases'] = len(subset)
                for mode in STRATEGIES:
                    entry['strict'][mode].append(sum(r['modes'][mode]['outcome'] == 'strict' for r in subset))
            table[name][value] = entry
    return table


def transitions(cases, classical_union):
    """What happened to the baseline's documented failure groups.

    Two groups are tracked. A classical-only loss is a case the classical solver takes strictly while
    the neural-only request does not; those are the cases a better seed is supposed to rescue. An
    iteration-capped ONLY failure is a non-strict neural-only request whose observed stop evidence
    includes the correction-iteration budget.
    """
    result = {}
    classical_only = {block: sorted(strict_ids(cases, block, REFERENCE, 'current')
                                    - strict_ids(cases, block, REFERENCE, 'neural')) for block in BLOCKS}
    classical_only_first = {block: sorted(strict_ids(cases, block, REFERENCE, 'current')
                                          - strict_ids(cases, block, REFERENCE, 'neuralFirst')) for block in BLOCKS}
    capped = {block: sorted(i for i, record in cases[block, REFERENCE].items()
                            if record['modes']['neural']['outcome'] != 'strict'
                            and 'iteration_limit' in record['modes']['neural']['observedStopPhrases'])
              for block in BLOCKS}
    for name in ORDER:
        if name == REFERENCE:
            continue
        recovered = {block: sorted(i for i in classical_only[block]
                                   if i in strict_ids(cases, block, name, 'neural')) for block in BLOCKS}
        recovered_first = {block: sorted(i for i in classical_only_first[block]
                                         if i in strict_ids(cases, block, name, 'neuralFirst')) for block in BLOCKS}
        escaped = {block: sorted(i for i in capped[block] if 'iteration_limit'
                                 not in cases[block, name][i]['modes']['neural']['observedStopPhrases'])
                   for block in BLOCKS}
        solved = {block: sorted(i for i in capped[block] if i in strict_ids(cases, block, name, 'neural'))
                  for block in BLOCKS}
        result[name] = dict(
            classicalOnlyLossesRecoveredByOnly={str(b): len(v) for b, v in recovered.items()},
            classicalOnlyLossesRecoveredByOnlyIds={str(b): v for b, v in recovered.items()},
            classicalOnlyLossesRecoveredByFirst={str(b): len(v) for b, v in recovered_first.items()},
            iterationCappedOnlyEscapedCap={str(b): len(v) for b, v in escaped.items()},
            iterationCappedOnlyEscapedIds={str(b): v for b, v in escaped.items()},
            iterationCappedOnlyNowStrict={str(b): len(v) for b, v in solved.items()},
            iterationCappedOnlyNowStrictIds={str(b): v for b, v in solved.items()})
    result['baseline'] = dict(
        classicalOnlyOnlyLossCount={str(b): len(v) for b, v in classical_only.items()},
        classicalOnlyOnlyLossIds={str(b): v for b, v in classical_only.items()},
        classicalOnlyFirstLossCount={str(b): len(v) for b, v in classical_only_first.items()},
        iterationCappedOnlyFailureCount={str(b): len(v) for b, v in capped.items()},
        iterationCappedOnlyFailureIds={str(b): v for b, v in capped.items()})
    return result


def stop_phrases(cases):
    return {f'{block}/{name}': {mode: dict(Counter(
        phrase for record in cases[block, name].values() for phrase in record['modes'][mode]['observedStopPhrases']))
        for mode in STRATEGIES} for block in BLOCKS for name in ORDER}


def build():
    plan, cases, profiles, summaries, metadata, case_records, profile_records, classical, references = collect()
    union = set.union(*classical.values())
    intersection = set.intersection(*classical.values())
    classical_union = intersection
    preflight = read(OUT / 'preflight-parity.json')
    result = dict(
        revision='decoder-floor-analysis-v1', studyPlan=info(OUT / 'study-plan.json'),
        preflight=info(OUT / 'preflight-parity.json'), pipelines=ORDER, blocks=BLOCKS, cases=plan['cases'],
        referenceCases=plan['referenceCases'],
        measuredRequests=len(ORDER) * len(BLOCKS) * plan['cases'] * len(STRATEGIES),
        warmupRequests=len(ORDER) * len(BLOCKS) * 6,
        classicalStrictCountsByRun={f'{block}/{name}': len(value) for (block, name), value in classical.items()},
        classicalStrictUnionIds=sorted(classical_union),
        classicalStableAcrossEveryRun=union == intersection,
        summaries=summaries, runMetadata=metadata,
        contrasts={name: contrast(cases, profiles, name) for name in ORDER if name != REFERENCE},
        qualification={name: gate(cases, name, classical_union) for name in ORDER if name != REFERENCE},
        archiveParity=archive_parity(cases, classical_union),
        decodeLiftEffect=preflight['liftEffect'], referenceOmissions=preflight['referenceOmissions'],
        regimesByHeatLoopCount=regimes(cases, 'heatLoopCount'), regimesByStageCount=regimes(cases, 'stageCount'),
        regimesByStageBand=regimes(cases, 'stageBand'), stopPhraseCounts=stop_phrases(cases),
        transitions=transitions(cases, classical_union),
        interpretation='Historical validation, not a fresh holdout. The two blocks repeat the same 405 inputs. '
                       'All pipelines share the frozen F0 weight bytes and differ only in the decode rule.',
        evidenceRules=['Strict outcomes require the unchanged native audit and the final Newton certificate.',
                       'FIRST includes classical fallback; ONLY is a separately timed neural-only request.',
                       'All-case costs include failed and advisory requests; stable common-success costs are '
                       'reported beside them because a lower all-case mean can come from earlier failures.',
                       'Above-floor omissions are decode diagnostics against the certified references, not proof '
                       'that a reference phase is necessary at every admissible root.'],
        newSolverRequestsByAnalysis=0)
    return result, case_records, profile_records


def main():
    result, case_records, profile_records = build()
    freeze(OUT / 'validation-analysis.json', result)
    for name, rows in (('case', case_records), ('profile', profile_records)):
        path = OUT / f'validation-{name}-evidence.jsonl.gz'
        assert not path.exists(), path
        with gzip.open(path, 'wt', encoding='utf-8', newline='\n') as stream:
            for row in rows:
                stream.write(json.dumps(row, sort_keys=True, allow_nan=False) + '\n')
    print(json.dumps(dict(pipelines=len(ORDER), caseRows=len(case_records), profileRows=len(profile_records),
                          parity=result['archiveParity']['allIdentitySetsReproduced'],
                          passed=[name for name, value in result['qualification'].items() if value['passed']])),
          flush=True)


if __name__ == '__main__':
    argparse.ArgumentParser().parse_args()
    main()
