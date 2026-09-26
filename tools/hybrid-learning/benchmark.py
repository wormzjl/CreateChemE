"""Frozen repeated native validation, representative selection, and locked fresh test."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import sys

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'neural'))
from prepare_transformer_data import read_rows, digest, strict, stats
from prepare_generalized_evaluation import canonical_input_hash
from native_checkpoint_selection_v1 import freeze, info
import checkpoint_selection as old_policy

ROOT=Path(__file__).resolve().parents[2];AREA=ROOT/'build/neural-hybrid-learning/v1'
PLAN=AREA/'benchmark-plan.json'
ORDER=['incumbent','N-20260910','Nplus1-20260910','incumbent-wrapper','Nplus1-20260911','N-20260911','N-20260912','Nplus1-20260912']


def prepare():
    models=json.loads((AREA/'models.json').read_text());assert set(models)==set(ORDER)
    training=json.loads((AREA/'training-plan.json').read_text())
    source=read_rows(ROOT/training['datasets']['N']['path'])
    validation=[{k:r[k] for k in ('id','split','input','design') if k in r} for r in source if r['split']=='validation']
    assert len(validation)==405
    warm=min((r for r in source if r['split']=='train'),key=lambda r:(r['input']['stageCount'],r['id']))
    freeze(AREA/'validation-inputs.jsonl',validation,True);freeze(AREA/'warmup.json',{'id':warm['id'],'split':'train','input':warm['input']})
    evidence=[]
    for name in ORDER:
        if name.startswith('N'):
            directory=AREA/'models'/name
            native=json.loads((directory/'java-parity.json').read_text())
            assert native['passed'] and native['scheduling']['terminated'] and native['scheduling']['distinctWorkerThreads']==10
            for filename in ('java-parity.json','precision-check.json','fixture.json'):
                evidence.append(info(directory/filename))
    sources=list((ROOT/'src/main/java/com/wormzjl/createcheme/science/column/v3').rglob('*.java'))
    sources+=list((ROOT/'tools/neural').glob('*.java'))+list((ROOT/'tools/hybrid-learning/java').glob('*.java'))
    sources += [ROOT/'tools/hybrid-learning'/p for p in ('benchmark.py','hybrid.gradle','protocol.md')]
    plan=dict(revision='hybrid-repeated-native-v1',createdUtc=datetime.now(timezone.utc).isoformat(),
        validation=info(AREA/'validation-inputs.jsonl'),test=info(AREA/'test.jsonl'),warmup=info(AREA/'warmup.json'),
        training=info(AREA/'training-plan.json'),models=models,checks=evidence,sources=[info(p) for p in sources],
        orderByBlock=[ORDER,list(reversed(ORDER))],blocks=2,workers=10,heap='4g',java='21.0.11',
        requestDeadlineMillis=30000,neuralBudgetMillis=2000,maximumIterations=16,
        strategies=['current','neural','neuralFirst'],requestsPerValidationBlock=405*8*3,
        requestsPerTestBlock=252*4*3,
        representativeRule='Maximize minimum two-block strict LNN_FIRST count, then lower pooled all-case mean ms, then seed.',
        replacementRule='Strict improvement in both blocks, preserve union of all contemporaneous classical strict IDs in both blocks, no pooled latency regression.',
        noTestDrivenChanges=True)
    freeze(PLAN,plan);verify_plan()
    print(json.dumps({'benchmarkPlanSha256':digest(PLAN),'validationRequests':405*8*3*2,'testRequests':252*4*3*2}))


def verify_plan():
    plan=json.loads(PLAN.read_text())
    assert plan['orderByBlock']==[ORDER,list(reversed(ORDER))] and plan['blocks']==2
    for item in [plan[k] for k in ('validation','test','warmup','training')]+plan['sources']+plan['checks']:
        assert digest(ROOT/item['path'])==item['sha256'],item['path']
    for item in plan['models'].values():
        assert digest(ROOT/item['path'])==item['sha256']
        assert digest(ROOT/item['weights']['path'])==item['weights']['sha256']
    return plan


def validate_run(directory,plan,split,name):
    source=read_rows(ROOT/plan[split]['path']);rows=read_rows(directory/'evaluation.jsonl')
    meta=json.loads((directory/'run.json').read_text())
    assert meta['revision']=='hybrid-column-evaluation-v1'
    # Same audited completeness/policy contract; only the additive pipeline revision differs.
    old_policy.validate_run(rows,{**meta,'revision':old_policy.POLICY['revision']},source,
        plan['models'][name]['sha256'],plan[split]['sha256'])
    assert meta['maximumHeapBytes']==4*1024**3 and meta['warmupSha256']==plan['warmup']['sha256']
    return rows,meta


def run(split):
    plan=verify_plan();names=set(ORDER)
    if split=='test':
        selection=json.loads((AREA/'selection.json').read_text())
        assert selection['validationComplete'] and not selection['testUsed'] and selection['planSha256']==digest(PLAN)
        assert selection['testSha256']==plan['test']['sha256']
        names={'incumbent','incumbent-wrapper',*selection['representatives'].values()}
        assert len(names)==4
    env=dict(os.environ,JAVA_HOME='C:/Program Files/Java/jdk-21.0.11')
    logdir=AREA/'benchmark-logs';logdir.mkdir(exist_ok=True)
    for block in range(2):
        for name in plan['orderByBlock'][block]:
            if name not in names:continue
            verify_plan()
            directory=AREA/split/f'block-{block+1}'/name
            if directory.exists():
                validate_run(directory,plan,split,name)
                print('VERIFIED existing complete '+str(directory.relative_to(ROOT)),flush=True);continue
            args=[str(directory.relative_to(ROOT)),plan[split]['path'],plan['models'][name]['path'],
                  '10','30','2000','16',plan['warmup']['path']]
            command=['gradlew.bat','-I','tools/hybrid-learning/hybrid.gradle','hybridLearning',
                '-PhybridMain=com.wormzjl.createcheme.science.column.v3.V3HybridEvaluationProbe',
                '-PhybridArgs='+','.join(args),'--console=plain']
            print(f'START {split} block {block+1} {name}',flush=True)
            with (logdir/f'{split}-block-{block+1}-{name}.log').open('x') as log:
                process=subprocess.Popen(command,env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
                for line in process.stdout:
                    log.write(line);log.flush();print(line,end='',flush=True)
                if process.wait():raise RuntimeError('Native campaign failed; no automatic retry or replacement')
            rows,meta=validate_run(directory,plan,split,name)
            print(json.dumps({'completed':split,'block':block+1,'pipeline':name,'seconds':meta['elapsedSeconds'],
                'strict':{mode:sum(strict({**r,**r[mode]}) for r in rows) for mode in plan['strategies']}}),flush=True)


def select():
    plan=verify_plan();records={};classical=set();validation_files=[]
    for name in ORDER:
        blocks=[]
        for block in range(2):
            directory=AREA/'validation'/f'block-{block+1}'/name
            rows,meta=validate_run(directory,plan,'validation',name)
            classical.update(r['id'] for r in rows if strict({**r,**r['current']}))
            blocks.append(dict(strictIds=sorted(r['id'] for r in rows if strict({**r,**r['neuralFirst']})),
                meanMillis=sum(r['neuralFirst']['ms'] for r in rows)/len(rows)))
            validation_files.extend(info(directory/f) for f in ('evaluation.jsonl','run.json'))
        records[name]=dict(blocks=blocks,minimumStrict=min(len(b['strictIds']) for b in blocks),
            pooledMeanMillis=sum(b['meanMillis'] for b in blocks)/2)
    representatives={}
    for dataset in ('N','Nplus1'):
        names=[f'{dataset}-{seed}' for seed in (20260910,20260911,20260912)]
        representatives[dataset]=min(names,key=lambda name:(-records[name]['minimumStrict'],records[name]['pooledMeanMillis'],int(name.rsplit('-',1)[1])))
    reference=records['incumbent'];recommendations={}
    for name in ORDER:
        row=records[name]
        improvement=all(len(a['strictIds'])>len(b['strictIds']) for a,b in zip(row['blocks'],reference['blocks']))
        preserves=all(classical<=set(b['strictIds']) for b in row['blocks'])
        latency=row['pooledMeanMillis']<=reference['pooledMeanMillis']
        recommendations[name]=dict(improvesBothBlocks=improvement,preservesClassicalUnionBothBlocks=preserves,
            noPooledLatencyRegression=latency,eligibleReplacement=improvement and preserves and latency)
    freeze(AREA/'selection.json',dict(validationComplete=True,testUsed=False,planSha256=digest(PLAN),
        testSha256=plan['test']['sha256'],representatives=representatives,records=records,
        classicalUnionIds=sorted(classical),recommendations=recommendations,validationFiles=validation_files,
        selectedPipelines={name:plan['models'][name] for name in representatives.values()}))
    print(json.dumps({'representatives':representatives,'eligibleReplacements':[n for n,r in recommendations.items() if r['eligibleReplacement']]}))


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('mode',choices=['prepare','validation','select','test']);a=p.parse_args()
    if a.mode=='prepare':prepare()
    elif a.mode=='select':select()
    else:run(a.mode)
