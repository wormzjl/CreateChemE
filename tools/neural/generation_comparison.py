"""Matched, fixed-model analysis; these functions never select or fit a model."""
from collections import Counter
import math

import checkpoint_selection as policy
from prepare_generalized_evaluation import canonical_input_hash
from prepare_transformer_data import strict, stats

LABELS = ('gen2', 'gen3', 'transformer')
PROFILE_FIELDS = ('temperatureRmseKelvin', 'componentFlowRmseMolPerSecond',
                  'phaseTotalFlowRelativeToFeedRmse', 'log1pComponentFlowRmse')


def stage_band(n):
    for low, high in ((2,8),(9,16),(17,32),(33,48),(49,64)):
        if low <= n <= high: return f'{low}-{high}'
    raise ValueError('Stage count outside registered domain')


def same_population(rows_by_model):
    if set(rows_by_model) != set(LABELS): raise ValueError('Missing or unexpected generation')
    reference = None
    for rows in rows_by_model.values():
        current = {r['id']:canonical_input_hash(r['input']) for r in rows}
        if len(current) != len(rows): raise ValueError('Duplicate case ID')
        if reference is not None and current != reference: raise ValueError('Generations have different inputs')
        reference = current


def check_prediction_evidence(report, model_sha, fixture_sha):
    s = report.get('scheduling') or {}
    if (report.get('passed') is not True or report.get('modelSha256') != model_sha
        or report.get('fixturesSha256') != fixture_sha or report.get('trainingFixtures') != 10
        or not 1 <= report.get('supportedFixtures',0) <= 10 or report.get('parallelPredictions') != 40
        or report.get('supportedParallelPredictions') != 40 or report.get('synchronizedSupportedPredictions') != 10
        or report.get('barrierInsidePrediction') is not True
        or any(report.get(k) is not True for k in ('serialParallelBitIdentical','cancellationPassed','subsequentPredictionsUnchanged'))
        or s.get('submitted') != 40 or s.get('completed') != 40 or s.get('maximumActive') != 10
        or s.get('maximumInFlight') != 10 or s.get('distinctWorkerThreads') != 10 or s.get('terminated') is not True):
        raise ValueError('Incomplete or mismatched shared-model qualification')


def diagnostics(rows):
    residuals = [r.get('rawPrediction',{}).get('nativeResidual',{}).get('maximumScaledMeshResidual') for r in rows]
    finite = [v for v in residuals if isinstance(v,(int,float)) and not isinstance(v,bool) and math.isfinite(v)]
    unavailable_reasons = Counter(r.get('rawPrediction',{}).get('failure') or 'unavailable/unspecified'
        for r in rows if r.get('rawPrediction',{}).get('supported') is not True)
    result = {'cases':len(rows), 'rawSupported':sum(r.get('rawPrediction',{}).get('supported') is True for r in rows),
        'predictionUnavailableReasons':dict(sorted(unavailable_reasons.items())),
        'rawNativeResidual':stats(finite) if finite else None,
        'failureCodes':{m:dict(sorted(Counter(r[m]['status'] for r in rows if not r[m]['success']).items())) for m in policy.MODES}}
    result['rawUnavailable'] = len(rows)-result['rawSupported']
    return result


def compare(rows_by_model, certified_reference_ids=()):
    same_population(rows_by_model)
    analyzed = {label:policy.analyze(rows) for label,rows in rows_by_model.items()}
    indexed = {label:{r['id']:r for r in rows} for label,rows in rows_by_model.items()}
    groups = {}
    for row in rows_by_model['transformer']:
        inp = row['input']
        categories = ('stages/'+stage_band(inp['stageCount']), 'stageCount/'+str(inp['stageCount']),
            'steam/'+('on' if inp.get('steamFeeds') else 'off'),
            'pumparounds/'+str(len(inp.get('pumparounds',[]))), 'sideDraws/'+str(len(inp.get('sideDraws',[]))))
        for key in categories: groups.setdefault(key,[]).append(row['id'])
    strata = {name:{'cases':len(ids),'models':{label:{
        **{mode:sum(strict({**indexed[label][i],**indexed[label][i][mode]}) for i in ids) for mode in policy.MODES},
        'rawSupported':sum(indexed[label][i].get('rawPrediction',{}).get('supported') is True for i in ids)}
        for label in LABELS}} for name,ids in sorted(groups.items())}
    qualified = {m:{label:set(analyzed[label]['qualifiedIds'][m]) for label in LABELS} for m in policy.MODES}
    complementarity = {}
    for mode in ('neural','neuralFirst'):
        sets = qualified[mode]; union = set.union(*sets.values())
        complementarity[mode] = {'separateRunUnionQualified':len(union),
            'extraBeyondTransformerIds':sorted(union-sets['transformer']),
            'olderModelExclusiveIds':{label:sorted(sets[label]-sets['transformer']) for label in ('gen2','gen3')},
            'measuredCascade':False,'extraBudgetForCascadeMeasured':False}
    certified=set(certified_reference_ids)
    if not certified <= set(indexed['transformer']): raise ValueError('Unknown certified reference')
    available={label:{r['id'] for r in rows if isinstance(r.get('rawVsTeacher'),dict)} & certified
               for label,rows in rows_by_model.items()}
    common_profile_ids = sorted(set.intersection(*available.values()))
    profiles = {'certifiedReferences':len(certified),'availableComparisons':{k:len(v) for k,v in available.items()},
                'commonCases':len(common_profile_ids),'ids':common_profile_ids,'models':{}}
    for label in LABELS:
        profiles['models'][label] = {field:stats([indexed[label][i]['rawVsTeacher'][field] for i in common_profile_ids])
            for field in PROFILE_FIELDS} if common_profile_ids else {}
    classical_sets = qualified['current']
    return {'models':{label:{**analyzed[label],'diagnostics':diagnostics(rows)} for label,rows in rows_by_model.items()},
        'pairedAgainstTransformer':{label:policy.paired_models(rows_by_model['transformer'],rows_by_model[label])
                                   for label in ('gen2','gen3')},
        'classicalUnionIds':sorted(set.union(*classical_sets.values())),
        'classicalControlDisagreements':sorted(set.union(*classical_sets.values())-set.intersection(*classical_sets.values())),
        'strata':strata, 'commonTeacherProfileErrors':profiles, 'complementarity':complementarity}


def case_map(population, rows_by_model):
    same_population(rows_by_model)
    index = {label:{r['id']:r for r in rows} for label,rows in rows_by_model.items()}
    for row in rows_by_model['transformer']:
        yield {'id':row['id'],'population':population,'inputSha256':canonical_input_hash(row['input']),
            'stageCount':row['input']['stageCount'],'steam':bool(row['input'].get('steamFeeds')),
            'models':{label:{mode:{'strictQualified':strict({**index[label][row['id']],**index[label][row['id']][mode]}),
                **{k:index[label][row['id']][mode][k] for k in ('success','status','ms')}} for mode in policy.MODES}
                for label in LABELS}}


def report_text(summary):
    lines = ['# Matched generation comparison','',
        'Fixed Gen2 MLP, selected Gen3 factorized network, and transformer seed 20260911. '
        'All cases use ten workers, a 2-second neural budget, a 30-second request deadline and 16 iterations per correction pass. '
        'Transformer journals are reused from the verified checkpoint study. This is an exposed-cohort comparison, not new blind testing or model selection.','',
        '| Population | Model | Strategy | Strict | Advisory | Failed | Mean elapsed ms | Sample SD ms | Mean CPU ms | Sample SD CPU ms |',
        '|---|---|---|---:|---:|---:|---:|---:|---:|---:|']
    for population, result in summary['populations'].items():
        for label in LABELS:
            for mode in policy.MODES:
                r = result['models'][label]['strategies'][mode]; elapsed=r['ms']; cpu=r.get('cpuMillis')
                lines.append(f"| {population} / {r['cases']} | {label} | {mode} | {r['qualified']} | {r['advisoryOnly']} | {r['failed']} | "
                    f"{elapsed['mean']:.2f} | {elapsed['sampleSd']:.2f} | "+
                    (f"{cpu['mean']:.2f} | {cpu['sampleSd']:.2f} |" if cpu else 'unavailable | unavailable |'))
    lines += ['','All cases, including failures, contribute to timing. Sample SD describes case variability, not repeated-run timing uncertainty. '
        'CPU and allocation counters retain their own sample counts in the summary. Allocations are cumulative volume, not retained memory.','',
        '| Population | Model | FIRST gains / losses vs paired classical | Mean extra elapsed ms | Sample SD ms | FIRST gains / losses vs transformer |',
        '|---|---|---:|---:|---:|---:|']
    for population,result in summary['populations'].items():
        for label in LABELS:
            p=result['models'][label]['pairedAgainstCurrent']; q=p['firstMinusCurrentMillis']
            cross=result['pairedAgainstTransformer'].get(label,{}).get('neuralFirst')
            versus=f"{len(cross['gainedIds'])} / {len(cross['lostIds'])}" if cross else 'reference'
            lines.append(f"| {population} | {label} | {len(p['firstGains'])} / {len(p['firstLosses'])} | {q['mean']:.2f} | {q['sampleSd']:.2f} | {versus} |")
    lines += ['','Cross-campaign elapsed differences include load and execution variation. Classical controls, case-level differences, '
        'stage/steam/equipment strata, raw availability and common-reference profile errors are retained in summary.json and case-map.jsonl. '
        'Different generations also differ in training data and selection; this is not an isolated architecture ablation.','',
        'Unions of separately successful model runs describe potential complementarity only. They are not measured cascade coverage or latency. '
        'The transformer choice and all runtime defaults remain unchanged.','']
    return '\n'.join(lines)
