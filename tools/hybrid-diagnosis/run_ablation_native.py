"""Parity gate and exact frozen probe on the registered diagnostic panel."""
from common import *
import argparse
import os
import subprocess
import numpy as np
import train_hybrid as h
import benchmark as original
from ablation import verify

ENV=dict(os.environ,JAVA_HOME='C:/Program Files/Java/jdk-21.0.11')


def execute(command,log):
    with log.open('x') as stream:
        process=subprocess.Popen(command,env=ENV,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,cwd=ROOT)
        for line in process.stdout:stream.write(line);stream.flush();print(line,end='',flush=True)
        if process.wait():raise RuntimeError('Native check failed; evidence preserved, no automatic retry')


def parity():
    verify();torch.set_num_threads(2);models=json.loads((OUT/'ablation-models.json').read_text())
    old=json.loads((AREA/'training-plan.json').read_text());anchors=h.load_anchors([ROOT/p for p in old['anchors']])
    train={r['id']:r for r in read_rows(ROOT/old['datasets']['N']['path']) if r['split']=='train'}
    for arm in ('empirical','canonical'):
        directory=OUT/'ablation-models'/arm
        for item in (models[arm],models[arm]['weights']):assert digest(ROOT/item['path'])==item['sha256']
        args=[directory/'model.json',directory/'fixture.json',directory/'java-parity.json']
        execute(['gradlew.bat','-I','tools/hybrid-learning/hybrid.gradle','hybridLearning',
                 '-PhybridMain=com.wormzjl.createcheme.science.column.v3.V3HybridParityCheck',
                 '-PhybridArgs='+','.join(p.relative_to(ROOT).as_posix() for p in args),'--console=plain'],directory/'java-parity.log')
        record=json.loads((directory/'java-parity.json').read_text());assert record['passed'] and record['exactMasks']
        cp=torch.load(OUT/'ablation-fits'/arm/'model.pt',map_location='cpu',weights_only=True)
        model=h.pilot.ColumnModel('transformer',inputs=185);model.load_state_dict(cp['state_dict']);model.eval()
        fixtures=json.loads((directory/'fixture.json').read_text());rows=[train[f['id']] for f in fixtures]
        batch=h.tensors(rows,cp['normalization'],anchors,cp['report']['branchesSeen'],'cpu')
        with torch.no_grad():raw,branch=h.forward(model,batch,cp['normalization'])
        masks=0;maximum=0.
        for i,(row,f) in enumerate(zip(rows,fixtures)):
            count=row['input']['stageCount']+2;r=raw[i,:count].numpy();maximum=max(maximum,float(np.max(np.abs(r-np.asarray(f['raw'])))))
            chosen=h.base.BRANCHES[int(branch[i,:2].argmax())];assert chosen==f['branch']
            decoded=h.factor.decode(row['input'],r,chosen,.02)
            for phase in ('liquid','vapor'):masks+=int(np.count_nonzero((decoded[phase]==0)!=(np.asarray(f['decoded'][phase])==0)))
            masks+=int(np.count_nonzero(decoded['wetTrays']!=f['decoded']['wetTrays']))
        assert masks==0
        freeze(directory/'precision-check.json',dict(passed=True,maskDifferences=masks,maximumFloat32RawDifference=maximum,
            model=info(directory/'model.json'),fixtures=info(directory/'fixture.json'),javaParity=info(directory/'java-parity.json')))
    print('Both exports passed native parity and exact float32 decoded masks')


def native():
    plan=verify();models=json.loads((OUT/'ablation-models.json').read_text())
    for arm in ('empirical','canonical'):
        p=OUT/'ablation-models'/arm/'precision-check.json';record=json.loads(p.read_text());assert record['passed']
        for item in (record['model'],record['fixtures'],record['javaParity']):assert digest(ROOT/item['path'])==item['sha256']
    test_plan=dict(validation=plan['panel'],models=models,warmup=info(AREA/'warmup.json'))
    logs=OUT/'ablation-logs';logs.mkdir(exist_ok=True)
    lock=OUT/'ablation-execution-lock.json'
    if lock.exists():raise FileExistsError('No unregistered resume; preserve execution evidence')
    freeze(lock,dict(plan=info(OUT/'ablation-plan.json'),models=info(OUT/'ablation-models.json'),panel=plan['panel'],
        precision=[info(OUT/'ablation-models'/a/'precision-check.json') for a in ('empirical','canonical')]))
    results={}
    for block,names in enumerate(plan['nativeOrderByBlock'],1):
        for name in names:
            verify()
            for item in (models[name],models[name]['weights']):assert digest(ROOT/item['path'])==item['sha256']
            directory=OUT/'ablation-native'/f'block-{block}'/name
            args=[directory.relative_to(ROOT).as_posix(),plan['panel']['path'],models[name]['path'],'10','30','2000','16',test_plan['warmup']['path']]
            print(f'START diagnostic panel block {block} {name}',flush=True)
            execute(['gradlew.bat','-I','tools/hybrid-learning/hybrid.gradle','hybridLearning',
                '-PhybridMain=com.wormzjl.createcheme.science.column.v3.V3HybridEvaluationProbe',
                '-PhybridArgs='+','.join(args),'--console=plain'],logs/f'native-{block}-{name}.log')
            rows,meta=original.validate_run(directory,test_plan,'validation',name)
            result=dict(block=block,pipeline=name,strict={mode:sum(strict({**r,**r[mode]}) for r in rows) for mode in plan['strategies']},
                seconds=meta['elapsedSeconds'],journal=info(directory/'evaluation.jsonl'),run=info(directory/'run.json'))
            results[f'{block}-{name}']=result;print(json.dumps(result),flush=True)
    freeze(OUT/'ablation-native-summary.json',dict(plan=info(OUT/'ablation-plan.json'),complete=True,
        independentInputs=plan['panelCases'],populationEstimate=False,promotionAllowed=False,results=results))


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('mode',choices=['parity','native']);a=p.parse_args();globals()[a.mode]()
