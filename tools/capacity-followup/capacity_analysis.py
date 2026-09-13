"""Read-only reconstruction of the depth study; never runs a solver or selects a fit."""
from capacity_common import *
from analysis_common import (mode_evidence, condition, profile_evidence, summarize_evidence,
    summarize_profiles, optional)
from analyze_native import contrast
from capacity_benchmark import bindings, validate_run, verify_selection
import argparse


def collect(stage):
    plan, models = bindings()
    references = {r['id']: r for r in read_rows(SOURCE/'validation-references.jsonl')}
    panel = {r['id'] for r in read_rows(ROOT/plan['screeningPanel']['path'])}
    if stage == 'screen':
        source, names, blocks = plan['screeningPanel'], plan['screenOrder'], [1]
    else:
        validation = verify_selection()
        source, names, blocks = validation['source'], validation['orderByBlock'][0], [1, 2]
    cases, profiles, summaries, metadata = {}, {}, {}, {}
    evidence, case_records, profile_records, classical = [], [], [], set()
    for block in blocks:
        for name in names:
            directory = OUT/stage/f'block-{block}'/name
            rows, meta = validate_run(directory, source, models[name], plan['warmup'])
            rows.sort(key=lambda row: row['id'])
            observed, pp = {}, {}
            for row in rows:
                modes = {mode: mode_evidence(row, mode) for mode in plan['strategies']}
                if modes['current']['outcome'] == 'strict': classical.add(row['id'])
                native = row['rawPrediction'].get('nativeResidual') or {}
                record = dict(stage=stage, block=block, pipeline=name, id=row['id'],
                    canonicalInputSha256=canonical_input_hash(row['input']), condition=condition(row),
                    screeningMember=row['id'] in panel, modes=modes,
                    pipelineSeedAvailable=row['rawPrediction']['supported'],
                    rawDiagnosticMillis=row['rawPrediction'].get('ms'),
                    initialProjectedMaximumScaledMeshResidual=native.get('maximumScaledMeshResidual'),
                    caseServiceMillis=row['caseServiceMillis'], queueWaitMillis=row['queueWaitMillis'])
                observed[row['id']] = record
                case_records.append(record)
                if row['id'] in references:
                    profile = profile_evidence(row, references[row['id']])
                    profile.update(stage=stage, block=block, pipeline=name)
                    pp[row['id']] = profile
                    profile_records.append(profile)
            cases[block, name], profiles[block, name] = observed, pp
            populations = {'all': set(observed), 'screening': set(observed)&panel, 'remaining': set(observed)-panel}
            summaries[f'{block}/{name}'] = {population: dict(cases=len(ids),
                modes={mode: summarize_evidence([observed[i]['modes'][mode] for i in sorted(ids)]) for mode in plan['strategies']},
                pipelineSeedUnavailable=sum(not observed[i]['pipelineSeedAvailable'] for i in ids),
                profiles=summarize_profiles([pp[i] for i in sorted(ids) if i in pp]),
                **{key: optional([observed[i][key] for i in sorted(ids)]) for key in (
                    'initialProjectedMaximumScaledMeshResidual', 'rawDiagnosticMillis', 'caseServiceMillis', 'queueWaitMillis')})
                for population, ids in populations.items() if ids}
            regimes = {}
            for key in ('stageBand', 'steam', 'sideDrawCount', 'heatLoopCount'):
                regimes[key] = {}
                for value in sorted({str(r['condition'][key]) for r in observed.values()}):
                    subset = [r for r in observed.values() if str(r['condition'][key]) == value]
                    regimes[key][value] = dict(cases=len(subset), strict={mode: sum(
                        r['modes'][mode]['outcome'] == 'strict' for r in subset) for mode in plan['strategies']})
            metadata[f'{block}/{name}'] = dict(run=info(directory/'run.json'), regimes=regimes,
                **{key: meta[key] for key in ('elapsedSeconds', 'modelLoadMillis', 'heapUsedAtEndBytes', 'poolPeakUsage', 'scheduling')})
            evidence.extend(info(directory/file) for file in ('evaluation.jsonl', 'run.json'))
            print(f'Reconstructed {stage} block {block} {name}', flush=True)
    return plan, names, blocks, cases, profiles, summaries, metadata, evidence, case_records, profile_records, classical, panel


def gate(reference, candidate, blocks, cases, classical):
    def ids(name, block):
        return {i for i, r in cases[block, name].items() if r['modes']['neuralFirst']['outcome'] == 'strict'}
    def mean(name):
        return float(np.mean([cases[block, name][i]['modes']['neuralFirst']['milliseconds']
            for block in blocks for i in sorted(cases[block, name])]))
    a, b = [ids(reference, block) for block in blocks], [ids(candidate, block) for block in blocks]
    candidate_mean, reference_mean = mean(candidate), mean(reference)
    improves = all(len(y) > len(x) for x, y in zip(a, b))
    preserves = all(classical <= group for group in b)
    timing = candidate_mean <= reference_mean
    return dict(reference=reference, candidate=candidate, referenceStrictFirstCounts=[len(x) for x in a],
        strictFirstCounts=[len(x) for x in b], improvesStrictFirstBothBlocks=improves,
        preservesClassicalUnionBothBlocks=preserves, noPooledMeanLatencyRegression=timing,
        passed=improves and preserves and timing, pooledFirstMeanMillis=candidate_mean,
        referencePooledFirstMeanMillis=reference_mean,
        classicalMissedIds={str(block): sorted(classical-b[j]) for j, block in enumerate(blocks)})


def build(stage):
    plan, names, blocks, cases, profiles, summaries, metadata, evidence, case_records, profile_records, classical, panel = collect(stage)
    contrasts = {}
    for seed in plan['training']['seeds']:
        steps = plan['training']['checkpoints'] if stage == 'screen' else [plan['fixedComparisonStep']]
        for step in steps:
            contrasts[f'depth/{seed}/{step}'] = contrast(f'L2-{seed}-s{step}', f'L4-{seed}-s{step}', blocks, cases, profiles, panel)
        if stage == 'validation':
            selection = read(OUT/'selection.json')['nativeSelections']
            contrasts[f'selected_depth/{seed}'] = contrast(selection['L2'][str(seed)]['pipeline'],
                selection['L4'][str(seed)]['pipeline'], blocks, cases, profiles, panel)
    versus_controls = {control: {name: contrast(control, name, blocks, cases, profiles, panel)
        for name in names if name != control} for control in ('F0', 'I')}
    qualification, depth, stable_costs = {}, {}, {}
    if stage == 'validation':
        qualification = {control: {name: gate(control, name, blocks, cases, classical)
            for name in names if name != control} for control in ('F0', 'I')}
        depth = {str(seed): gate(f'L2-{seed}-s{plan["fixedComparisonStep"]}',
            f'L4-{seed}-s{plan["fixedComparisonStep"]}', blocks, cases, classical) for seed in plan['training']['seeds']}
        for label, comparison in {**contrasts, **{f'vs_{control}/{name}': c
                for control, comparisons in versus_controls.items() for name, c in comparisons.items()}}.items():
            stable_costs[label] = {}
            for mode in ('neural', 'neuralFirst'):
                ids = comparison['consistency'][mode]['stableGroups']['both']
                a, b = comparison['reference'], comparison['candidate']
                stable_costs[label][mode] = dict(independentCases=len(ids), ids=ids,
                    reference=summarize_evidence([cases[block, a][i]['modes'][mode] for block in blocks for i in ids]),
                    candidate=summarize_evidence([cases[block, b][i]['modes'][mode] for block in blocks for i in ids]),
                    pairedMillis=optional([cases[block, b][i]['modes'][mode]['milliseconds']-
                        cases[block, a][i]['modes'][mode]['milliseconds'] for block in blocks for i in ids]))
    profile_stability = {}
    if len(blocks) == 2:
        for name in names:
            a, b = profiles[1, name], profiles[2, name]
            assert a.keys() == b.keys()
            profile_stability[name] = dict(referenceCases=len(a), changedAvailabilityIds=sorted(
                i for i in a if a[i]['available'] != b[i]['available']), changedSeedIds=sorted(
                i for i in a if a[i].get('seedSha256') != b[i].get('seedSha256')))
    result = dict(revision='capacity-native-analysis-v1', stage=stage, trainingPlan=info(OUT/'training-plan.json'),
        models=info(OUT/'models.json'), source=plan['screeningPanel'] if stage == 'screen' else plan['validationInputs'],
        pipelines=names, blocks=blocks, independentCases=65 if stage == 'screen' else 405,
        screeningCases=65, remainingCases=0 if stage == 'screen' else 340,
        measuredRequests=len(names)*len(blocks)*(65 if stage == 'screen' else 405)*3,
        warmupRequests=len(names)*len(blocks)*6, classicalStrictUnionIds=sorted(classical),
        summaries=summaries, runMetadata=metadata, contrasts=contrasts, versusControls=versus_controls,
        qualification=qualification, fixedDepthGates=depth,
        strongReplicatedDepthBenefit=all(value['passed'] for value in depth.values()) if depth else None,
        stableCommonSuccessCosts=stable_costs, profileStability=profile_stability, inputEvidence=evidence,
        interpretation='Historical validation, not a fresh holdout. Repeated blocks reuse the same 405 cases. Selected endpoints can differ in training exposure.',
        evidenceRules=['FIRST terminal owner is explicit; separately timed ONLY is not its internal neural trajectory.',
            'Stop phrases describe observed events; absent evidence remains unknown.',
            'Projected residuals and audit ratios are state/support dependent diagnostics, not a common optimization objective.',
            'Profiles are actual native pipeline seeds; reference discrepancies do not prove a unique admissible root.',
            'Further qualification requires improvement against F0; passing against I alone is insufficient.',
            'Stable common-success cost and all-case cost must both be reported; faster failures do not establish faster solving.'],
        analysisSources=[info(Path(__file__)), info(ROOT/'tools/trace-followup/analysis_common.py'),
            info(ROOT/'tools/trace-followup/analyze_native.py')], newSolverRequestsByAnalysis=0)
    return result, case_records, profile_records


def main(stage):
    result, cases, profiles = build(stage)
    freeze(OUT/f'{stage}-analysis.json', result)
    freeze(OUT/f'{stage}-case-evidence.jsonl', cases, True)
    freeze(OUT/f'{stage}-profile-evidence.jsonl', profiles, True)
    print(json.dumps(dict(analyzed=stage, pipelines=len(result['pipelines']), caseRows=len(cases),
        profileRows=len(profiles), measuredRequests=result['measuredRequests'])), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('stage', choices=['screen', 'validation'])
    main(parser.parse_args().stage)
