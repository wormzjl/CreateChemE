"""Summarize separately registered native profile diagnostics after all campaigns."""
from collections import Counter
import json
from pathlib import Path
from benchmark import ROOT, AREA, verify_plan, ORDER
from prepare_transformer_data import read_rows, digest
from native_checkpoint_selection_v1 import freeze, info
from report import optional_stats

FIELDS=('temperatureRmseKelvin','componentFlowRmseMolPerSecond','phaseTotalFlowRelativeToFeedRmse',
        'log1pComponentFlowRmse','freeWaterRmseMolPerSecond','wetMaskMismatches')


def describe(rows):
    result={'cases':len(rows),'rawSupported':sum(r.get('rawSupported') is True for r in rows),
        'preparationStatuses':dict(Counter(r.get('preparation',{}).get('status','NO_RAW_PREDICTION') for r in rows))}
    for phase in ('raw','final'):
        values=[r[phase] for r in rows if isinstance(r.get(phase),dict)]
        families=sorted({key for r in values for key in r.get('nativeFamilies',{})})
        result[phase]=dict(available=len(values),
            fullGridMaterialDefect=optional_stats([r.get('maximumMaterialDefectOverInputComponentScale') for r in values]),
            nativeFamilies={family:{metric:optional_stats([r.get('nativeFamilies',{}).get(family,{}).get(metric) for r in values])
                for metric in ('maximumAbsolutePhysical','maximumAbsoluteScaled','rows')} for family in families},
            projectedNativeResidualAvailable=sum('nativeFamilies' in r for r in values),
            residualUnavailableReasons=dict(Counter(r['nativeResidualUnavailable'] for r in values if 'nativeResidualUnavailable' in r)))
    result['maximumTemperatureChangeKelvin']=optional_stats([r.get('profileChange',{}).get('maximumTemperatureChangeKelvin') for r in rows])
    result['exactZeroPatternChanges']=optional_stats([r.get('profileChange',{}).get('exactZeroPatternChanges') for r in rows])
    return result


def build_outputs():
    plan=verify_plan();manifest=json.loads((AREA/'profile-diagnostics/manifest.json').read_text())
    assert manifest['complete'] and not manifest['usedForSelection'] and manifest['correctedRequests']==0
    assert digest(ROOT/manifest['plan']['path'])==manifest['plan']['sha256']
    source=json.loads((ROOT/manifest['plan']['path']).read_text())
    for item in source['sources']:assert digest(ROOT/item['path'])==item['sha256']
    selection=json.loads((AREA/'selection.json').read_text())
    testnames={'incumbent','incumbent-wrapper',*selection['representatives'].values()}
    expected={f'validation-{n}.jsonl' for n in ORDER}|{f'test-{n}.jsonl' for n in testnames}
    assert {Path(r['path']).name for r in manifest['entries']}==expected
    populations={'validation':{},'test':{}}
    indexed={}
    for item in manifest['entries']:
        path=ROOT/item['path'];assert digest(path)==item['sha256']
        split,name=path.stem.split('-',1);rows=read_rows(path)
        inputs=read_rows(ROOT/plan[split]['path'])
        assert len(rows)==len(inputs) and {r['id'] for r in rows}=={r['id'] for r in inputs}
        assert {r['id']:r['input'] for r in rows}=={r['id']:r['input'] for r in inputs}
        assert all(r['pipeline']==name and r['correctedRequest'] is False and r['usedForSelection'] is False for r in rows)
        indexed[split,name]={r['id']:r for r in rows}
        groups={status:[r for r in rows if r.get('preparation',{}).get('status','NO_RAW_PREDICTION')==status]
                for status in sorted({r.get('preparation',{}).get('status','NO_RAW_PREDICTION') for r in rows})}
        populations[split][name]=dict(allCases=describe(rows),byPreparationStatus={k:describe(v) for k,v in groups.items()},journal=item)
    certified={r['id'] for r in read_rows(AREA/'diagnostic-validation-inputs.jsonl') if r['referenceCertified']}
    common=set(certified)
    for name in ORDER:
        common &= {i for i,r in indexed['validation',name].items() if 'rawVsOriginalCertifiedReference' in r and 'finalVsOriginalCertifiedReference' in r}
    profiles={}
    for name in ORDER:
        profiles[name]={phase:{field:optional_stats([indexed['validation',name][i][phase][field] for i in sorted(common)]) for field in FIELDS}
            for phase in ('rawVsOriginalCertifiedReference','finalVsOriginalCertifiedReference')}
        profiles[name]['branchMatches']={phase:sum(indexed['validation',name][i][phase]['branchMatches'] for i in common)
            for phase in ('rawVsOriginalCertifiedReference','finalVsOriginalCertifiedReference')}
    result=dict(revision='hybrid-native-profile-diagnostics-v1',manifest=info(AREA/'profile-diagnostics/manifest.json'),
        populations=populations,certifiedReferences=len(certified),commonReferenceInputs=len(common),commonReferenceIds=sorted(common),
        sameReferenceProfileErrors=profiles,correctedRequests=0,usedForSelection=False,source=info(Path(__file__)))
    lines=['# Native profile and residual diagnostics','',
        'These deterministic prediction/property evaluations ran after all timed native campaigns. They contain no corrected requests and did not select models. All 405 validation inputs and all 252 test inputs remain included. Diagnostic budgets and costs are separate from the two-second measured neural budget.','',
        f'Profile comparisons use the same {len(common)} available original certified validation references across all eight pipelines, from the unchanged set of {len(certified)}. No fresh-test teacher is chosen.','',
        '| Pipeline | Raw temperature RMSE K | Final temperature RMSE K | Raw phase-total RMSE / feed | Final phase-total RMSE / feed |',
        '|---|---:|---:|---:|---:|']
    for name in ORDER:
        r=profiles[name];a=r['rawVsOriginalCertifiedReference'];b=r['finalVsOriginalCertifiedReference']
        values=[a['temperatureRmseKelvin'],b['temperatureRmseKelvin'],a['phaseTotalFlowRelativeToFeedRmse'],b['phaseTotalFlowRelativeToFeedRmse']]
        lines.append('| '+name+' | '+' | '.join(f"{v['mean']:.6g}" if v else 'unavailable' for v in values)+' |')
    lines+=['','| Population | Pipeline | Preparation status counts |','|---|---|---|']
    for split,models in populations.items():
        for name,r in models.items():lines.append(f"| {split} | {name} | "+', '.join(f'{s}: {n}' for s,n in r['allCases']['preparationStatuses'].items())+' |')
    lines+=['',
        'The JSON retains raw/final maxima separately for each native equation family, including physical and scaled values. Full-grid component-material defects use the original dry/no-side-draw formula only where applicable. Native residuals are evaluated after native trace/support projection and are not interchangeable with those full-grid defects. Support changes and residual-unavailable cases are retained.','',
        'Material completion retains temperature and does not enforce the energy or equilibrium equations. Its applicability and decline groups are descriptive subsets, not filtered benchmark populations or independently selected model candidates. Lower prediction error or residual magnitude is not a strict native success.','']
    return result,'\n'.join(lines)


def main():
    result,text=build_outputs();freeze(AREA/'profile-summary.json',result)
    with (AREA/'profile-report.md').open('x',encoding='utf-8',newline='\n') as stream:stream.write(text)
    print(json.dumps({'commonOriginalReferences':result['commonReferenceInputs'],'diagnosticCampaigns':12,'correctedRequests':0}))


if __name__=='__main__':main()
