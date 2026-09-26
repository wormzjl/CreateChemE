"""Registered post-campaign profile/residual diagnostics, never candidate selection."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import benchmark as study
from benchmark import ROOT, AREA
from prepare_transformer_data import read_rows, strict, digest
from native_checkpoint_selection_v1 import freeze, info

PLAN=AREA/'diagnostic-plan-v2.json'


def prepare():
    assert not (AREA/'profile-diagnostics').exists()
    plan=study.verify_plan();training=json.loads((AREA/'training-plan.json').read_text())
    source=ROOT/training['datasets']['N']['path'];rows=read_rows(source)
    validation=[]
    for row in rows:
        if row['split']!='validation':continue
        item={k:row[k] for k in ('id','input','split')};item['referenceCertified']=strict(row)
        if item['referenceCertified']:item['seed']=row['seed']
        validation.append(item)
    assert len(validation)==405 and sum(r['referenceCertified'] for r in validation)==168
    inputs=AREA/'diagnostic-validation-inputs.jsonl'
    if inputs.exists():assert read_rows(inputs)==validation
    else:freeze(inputs,validation,True)
    sources=[ROOT/'tools/hybrid-learning'/name for name in ('diagnostics.py','diagnostics.gradle','diagnostic-java/V3HybridProfileDiagnostics.java')]
    freeze(PLAN,dict(revision='hybrid-post-campaign-diagnostics-v2',
        validation=info(AREA/'diagnostic-validation-inputs.jsonl'),test=plan['test'],originalReferences=info(source),
        benchmarkPlan=info(study.PLAN),sources=[info(p) for p in sources],
        validationPipelines=study.ORDER,testPipelines='incumbent, exact wrapper, and both frozen selected representatives',
        runsAfterAllTimedCampaigns=True,usedForSelection=False,correctedRequests=0,workers=1,
        diagnosticPredictionBudgetMillis=30000,
        supersedesUnexecuted=info(AREA/'diagnostic-plan.json'),
        preflightArchive=info(ROOT/'.neural-cache/hybrid-learning-v1/diagnostic-preflight-v1.zip'),
        amendment='Resolve the dedicated diagnostic classpath inside task configuration under the Gradle project lock. No diagnostic predictions or corrected requests occurred in revision 1.',
        scope='All 405 validation inputs and all 252 fresh inputs. Original 168 certified references only; no new teacher selection. Raw/final native residual families and full-grid material diagnostics where the dry/no-side-draw formula applies.'))
    print('Post-campaign diagnostic population and source contract frozen')


def run():
    plan=study.verify_plan();registered=json.loads(PLAN.read_text())
    for item in registered['sources']+[registered[k] for k in ('validation','test','originalReferences','benchmarkPlan')]:
        assert digest(ROOT/item['path'])==item['sha256']
    lock=json.loads((AREA/'test-execution-lock.json').read_text())
    assert digest(AREA/'selection.json')==lock['selection']['sha256']
    names={'incumbent','incumbent-wrapper',*lock['representatives'].values()}
    for split,pipelines in [('validation',study.ORDER),('test',names)]:
        for block in (1,2):
            for name in pipelines:study.validate_run(AREA/split/f'block-{block}'/name,plan,split,name)
    env=dict(os.environ,JAVA_HOME='C:/Program Files/Java/jdk-21.0.11')
    output=AREA/'profile-diagnostics';output.mkdir()
    completed=[]
    for split,pipelines in [('validation',study.ORDER),('test',[n for n in study.ORDER if n in names])]:
        for name in pipelines:
            destination=output/f'{split}-{name}.jsonl'
            command=['gradlew.bat','-I','tools/hybrid-learning/diagnostics.gradle','hybridDiagnostics',
                '-PdiagnosticArgs='+','.join((plan['models'][name]['path'],registered[split]['path'],destination.relative_to(ROOT).as_posix())),
                '--console=plain']
            result=subprocess.run(command,env=env,capture_output=True,text=True)
            (output/f'{split}-{name}.log').write_text(result.stdout+result.stderr)
            if result.returncode:raise RuntimeError('Post-campaign diagnostics failed; preserve output')
            rows=read_rows(destination);source=read_rows(ROOT/registered[split]['path'])
            assert len(rows)==len(source) and {r['id'] for r in rows}=={r['id'] for r in source}
            assert all(r['correctedRequest'] is False and r['usedForSelection'] is False for r in rows)
            completed.append(info(destination));print(f'Post-campaign diagnostics complete: {split} {name}',flush=True)
    freeze(output/'manifest.json',dict(complete=True,plan=info(PLAN),
        entries=completed,correctedRequests=0,usedForSelection=False,independentValidationInputs=405,independentTestInputs=252))


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('mode',choices=['prepare','run']);a=p.parse_args()
    prepare() if a.mode=='prepare' else run()
