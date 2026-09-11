"""Recompute repeated-block results without changing any fitted or selected artifact."""
from collections import Counter, defaultdict
import json
import math
from pathlib import Path
import sys
import benchmark as study
from benchmark import ROOT, AREA
from prepare_transformer_data import read_rows, digest, strict, stats, coverage
from native_checkpoint_selection_v1 import freeze, info
from unified_column_evaluation import summarize
from generation_comparison import stage_band


def optional_stats(values):
    finite=[float(v) for v in values if isinstance(v,(int,float)) and not isinstance(v,bool) and math.isfinite(v)]
    return stats(finite) if finite else None


def diagnostic_summary(rows):
    raw=[r.get('rawPrediction',{}) for r in rows]
    preparation=[r['materialCompletion'] for r in raw if isinstance(r.get('materialCompletion'),dict)]
    anchor=[r['separateAnchorDiagnostic'] for r in raw if isinstance(r.get('separateAnchorDiagnostic'),dict)]
    return dict(cases=len(rows),rawSupported=sum(r.get('supported') is True for r in raw),
        predictionMillis=optional_stats([r.get('ms') for r in raw]),
        rawNativeProjectedMaximumScaledResidual=optional_stats([r.get('nativeResidual',{}).get('maximumScaledMeshResidual') for r in raw]),
        materialCompletionStatus=dict(Counter(r['status'] for r in preparation)),
        materialCompletionMillis=optional_stats([r.get('preparationMillis') for r in preparation]),
        materialCompletionPropertyCalls=optional_stats([r.get('propertyCalls') for r in preparation]),
        baselineAvailable=sum(r.get('available') is True for r in anchor),baselineAssessed=len(anchor),
        baselineReasons=dict(Counter(r.get('reason','unknown') for r in anchor)),
        baselineDiagnosticMillis=optional_stats([r.get('milliseconds') for r in anchor]),
        diagnosticsOutsideMeasuredRequests=True,
        failureCodes={mode:dict(Counter(r[mode]['status'] for r in rows if not r[mode]['success'])) for mode in study.old_policy.MODES},
        fallback={mode:dict(observed=sum(any('initializer=CURRENT_BACKUP' in e for e in r[mode].get('diagnostics',{}).get('events',[])) for r in rows),
            diagnosticsUnavailable=sum(not isinstance(r[mode].get('diagnostics'),dict) for r in rows)) for mode in ('neural','neuralFirst')})


def pair(reference,candidate,mode):
    a={r['id']:r for r in reference};b={r['id']:r for r in candidate};assert a.keys()==b.keys()
    sa={i for i,r in a.items() if strict({**r,**r[mode]})};sb={i for i,r in b.items() if strict({**r,**r[mode]})}
    ids=sorted(a);common=sorted(sa&sb)
    return dict(gainedIds=sorted(sb-sa),lostIds=sorted(sa-sb),commonStrict=len(common),
        allCaseCandidateMinusReferenceMillis=stats([b[i][mode]['ms']-a[i][mode]['ms'] for i in ids]),
        commonStrictCandidateMinusReferenceMillis=stats([b[i][mode]['ms']-a[i][mode]['ms'] for i in common]) if common else None)


def summarize_population(plan,split,names):
    raw={};result={};groups=defaultdict(set);case_map=[]
    for block in (1,2):
        raw[block]={}
        for name in names:
            directory=AREA/split/f'block-{block}'/name
            rows,meta=study.validate_run(directory,plan,split,name);raw[block][name]=rows
            analyzed=study.old_policy.analyze(rows)
            result.setdefault(name,{'blocks':[]})['blocks'].append(dict(block=block,**analyzed,
                diagnostics=diagnostic_summary(rows),run=meta,journal=info(directory/'evaluation.jsonl')))
    for row in raw[1]['incumbent']:
        inp=row['input'];identifier=row['id']
        for category in ('stageBand/'+stage_band(inp['stageCount']),'stageCount/'+str(inp['stageCount']),
            'steam/'+str(bool(inp.get('steamFeeds'))),'sideDrawCount/'+str(len(inp.get('sideDraws',[]))),
            'pumparoundCount/'+str(len(inp.get('pumparounds',[])))):
            groups[category].add(identifier)
    classical_sets=[]
    for name in names:
        pooled=raw[1][name]+raw[2][name]
        result[name]['pooledRequestMeasurements']={mode:summarize([{**r,**r[mode]} for r in pooled]) for mode in study.old_policy.MODES}
        result[name]['independentInputs']=len(raw[1][name])
        result[name]['pairedAgainstIncumbent']={str(block):{mode:pair(raw[block]['incumbent'],raw[block][name],mode) for mode in study.old_policy.MODES} for block in (1,2)}
        result[name]['repeatDisagreement']={mode:pair(raw[1][name],raw[2][name],mode) for mode in study.old_policy.MODES}
        result[name]['strata']={category:{str(block):{mode:summarize([{**r,**r[mode]} for r in raw[block][name] if r['id'] in ids]) for mode in study.old_policy.MODES} for block in (1,2)} for category,ids in sorted(groups.items())}
        for block in (1,2):classical_sets.append(set(result[name]['blocks'][block-1]['qualifiedIds']['current']))
    for block in (1,2):
        indexed={name:{r['id']:r for r in rows} for name,rows in raw[block].items()}
        for identifier,row in indexed['incumbent'].items():
            case_map.append(dict(split=split,block=block,id=identifier,stageCount=row['input']['stageCount'],
                steam=bool(row['input']['steamFeeds']),sideDraws=len(row['input']['sideDraws']),pumparounds=len(row['input']['pumparounds']),
                pipelines={name:{mode:dict(strict=strict({**indexed[name][identifier],**indexed[name][identifier][mode]}),
                    **{k:indexed[name][identifier][mode][k] for k in ('status','success','ms')}) for mode in study.old_policy.MODES} for name in names}))
    return dict(independentInputs=len(raw[1]['incumbent']),pipelines=result,
        classicalUnionIds=sorted(set.union(*classical_sets)),classicalControlDisagreementIds=sorted(set.union(*classical_sets)-set.intersection(*classical_sets))),case_map


def training_summary():
    plan=json.loads((AREA/'training-plan.json').read_text());fits={}
    incumbent=json.loads((ROOT/'build/neural-transformer/accuracy-v1/fits/baseline-20260911/report.json').read_text())
    for seed in (20260910,20260911,20260912):
        for dataset in ('N','Nplus1'):
            name=f'{dataset}-{seed}';r=json.loads((AREA/'fits'/name/'report.json').read_text())
            fits[name]={k:r[k] for k in ('dataset','seed','trainColumns','validationColumns','optimizerSteps','samplePresentations','shuffledPassesStarted',
                'initialWeightsSha256','trainingSeconds','bestSelectionScore','parameters','gpu','precision','validation')}
            fits[name]['selectedCheckpointUpdate']=r['bestEpoch']*26
            fits[name]['bestCheckpointIndex']=r['bestEpoch']
            fits[name]['parity']=json.loads((AREA/'models'/name/'precision-check.json').read_text())
    return dict(plan=info(AREA/'training-plan.json'),fits=fits,incumbentCertifiedValidation=incumbent['validation'],
        interpretation='Same 168 original certified reference columns; profile score selects a checkpoint within each fit. Native full-405 selection chooses the representative seed.')


def report_text(summary):
    selection=summary['selection'];lines=['# Trained hybrid comparison','',
        'N contains 805 unchanged certified training columns. The verified original-condition sweep appended 101 new strict TRAIN labels, giving N+1 906 columns. All historical nontraining records and the original 168 certified validation references were preserved. Newly acquired validation and test successes were withheld.','',
        'Six paired fits use the same 89,496-parameter native-baseline/residual Transformer, N-derived normalization and 4,160 optimizer updates. The branch classifier selects the native MATERIAL_CLOSED baseline in training and inference. Continuous coordinates are residual outputs; water, wet and presence coordinates are absolute. The existing factorized decoder and exact one-pass material wrapper feed the unchanged corrector/audit.','',
        '| Dataset | Seed | Selected update | Profile selection score | Training seconds |',
        '|---|---:|---:|---:|---:|']
    for r in summary['training']['fits'].values():lines.append(f"| {r['dataset']} | {r['seed']} | {r['selectedCheckpointUpdate']} | {r['bestSelectionScore']:.6f} | {r['trainingSeconds']:.2f} |")
    lines += ['', 'These scores describe predictions on the same 168 certified validation references. They are not success rates or measured native convergence. N versus N+1 holds initial weights, normalization and update count fixed; comparison to the incumbent additionally changes representation and training history.','',
        '## Complete native validation','',
        'Eight fixed pipelines were evaluated on all 405 original validation inputs, twice, with reversed pipeline order. Each pipeline receives contemporaneous CURRENT_ONLY, LNN_ONLY and LNN_FIRST requests. All runs use ten owned workers, Java 21, a 4 GiB heap, a 30-second request deadline, a 2-second neural budget and 16 correction iterations. Diagnostics and identical TRAIN warmup are outside reported request timing; actual baseline construction, prediction, decoding and completion are inside the neural budget.','',
        '| Pipeline | FIRST strict, blocks 1 / 2 | ONLY strict, blocks 1 / 2 | FIRST mean ms | FIRST sample SD ms |',
        '|---|---:|---:|---:|---:|']
    for name,r in summary['validation']['pipelines'].items():
        blocks=r['blocks'];q=r['pooledRequestMeasurements']['neuralFirst']['ms']
        counts=lambda mode:' / '.join(str(b['strategies'][mode]['qualified']) for b in blocks)
        lines.append(f"| {name} | {counts('neuralFirst')} | {counts('neural')} | {q['mean']:.2f} | {q['sampleSd']:.2f} |")
    lines += ['',f"The frozen representatives are **{selection['representatives']['N']}** and **{selection['representatives']['Nplus1']}**. Selection maximizes the minimum strict FIRST count across the two blocks, then minimizes pooled all-case latency, then uses the seed as tie-breaker.",'',
        'A replacement recommendation additionally requires qualification improvement in both validation blocks, preservation of the union of contemporaneous classical strict successes in both blocks, and no pooled mean-latency regression. The complete decision and case-level gains/losses are retained in selection.json.','',
        '## Prospective 252-input test','',
        'The input-only test was frozen before fitting: four columns for every stage count 2–64, with two steam-on and two steam-off columns per stage count, excluding 14,086 historical inputs and complete prior candidate pools. Selected artifacts and the validation decision were frozen before test execution.','',
        '| Pipeline | Mode | Strict, blocks 1 / 2 | Advisory, blocks 1 / 2 | Failed, blocks 1 / 2 | Mean ms | Sample SD ms | P95 ms |',
        '|---|---|---:|---:|---:|---:|---:|---:|']
    for name,r in summary['test']['pipelines'].items():
        for mode in study.old_policy.MODES:
            counts=lambda field:' / '.join(str(b['strategies'][mode][field]) for b in r['blocks'])
            q=r['pooledRequestMeasurements'][mode]['ms']
            lines.append(f"| {name} | {mode} | {counts('qualified')} | {counts('advisoryOnly')} | {counts('failed')} | {q['mean']:.2f} | {q['sampleSd']:.2f} | {q['p95']:.2f} |")
    lines += ['', 'Both blocks contain the same 252 independent operating inputs; 504 request measurements are not 504 independent test columns. All-case latency includes failed requests and classical fallback. Sample SD describes request/case variability, not a confidence interval or proof of a causal speedup.','',
        '| Pipeline | FIRST gains / losses vs incumbent, block 1 | FIRST gains / losses vs incumbent, block 2 | Mean paired delta ms, block 1 / 2 |',
        '|---|---:|---:|---:|']
    for name,r in summary['test']['pipelines'].items():
        pair=[r['pairedAgainstIncumbent'][str(b)]['neuralFirst'] for b in (1,2)]
        counts=[f"{len(p['gainedIds'])} / {len(p['lostIds'])}" for p in pair]
        delta=' / '.join(f"{p['allCaseCandidateMinusReferenceMillis']['mean']:.2f}" for p in pair)
        lines.append(f'| {name} | {counts[0]} | {counts[1]} | {delta} |')
    lines += ['', '## Interpretation and evidence limits','',
        'The summary JSON retains every block, strategy, case pairing, stage/steam/draw/pumparound stratum, raw-prediction availability, preparation status/cost, native residual diagnostic, fallback observation and classical-control disagreement. Preparation and anchor diagnostic timings are separately labelled and must not be substituted for full request latency. Cases with declined preparation or unavailable predictions remain in every denominator.','',
        'The MATERIAL_CLOSED anchor is a numerical guess. Capped seed withdrawals do not certify side-draw closure, and dry hydrocarbon initialization does not close steam/water or energy equations. Material completion is limited to dry inputs without side draws; lower material residual alone is not MESH qualification. All claimed strict successes pass the same native correction and audit.','',
        'N and N+1 contain no wet-qualified profiles. The 101 added profiles are all TWO_PHASE condensers; 53 have steam feeds and 48 do not. A steam-fed, dry-equilibrium result does not establish wet-tray generalization. Rare-branch and wet limits remain explicit.','',
        'No test result changed training, checkpoint/seed selection, decoder thresholds, budgets or runtime defaults. Previous sealed studies remain unchanged.','']
    return '\n'.join(lines)


def main():
    plan=study.verify_plan();selection=json.loads((AREA/'selection.json').read_text())
    assert selection['validationComplete'] and selection['planSha256']==digest(study.PLAN)
    validation,vcases=summarize_population(plan,'validation',study.ORDER)
    names=[name for name in study.ORDER if name in {'incumbent','incumbent-wrapper',*selection['representatives'].values()}]
    test,tcases=summarize_population(plan,'test',names)
    result=dict(revision='hybrid-residual-comparison-v1',plan=info(study.PLAN),selection=selection,
        training=training_summary(),validation=validation,test=test,
        source=info(Path(__file__)),dataAcquisition=json.loads((ROOT/'tools/neural/salvage_verification.json').read_text()))
    freeze(AREA/'summary.json',result);freeze(AREA/'case-map.jsonl',vcases+tcases,True)
    text=report_text(result)
    with (AREA/'report.md').open('x',encoding='utf-8',newline='\n') as stream:stream.write(text)
    print(json.dumps({'report':str((AREA/'report.md').relative_to(ROOT)),'validationInputs':405,'testInputs':252,'blocks':2}))


if __name__=='__main__':main()
