"""Register, fit and export the one bounded support-parameterization experiment."""
from common import *
import argparse
import copy
from datetime import datetime,timezone
import os
import subprocess
import numpy as np
import train_hybrid as h
from export_hybrid import fixture_forward
import benchmark as original_benchmark


def register():
    original_benchmark.verify_plan()
    if (OUT/'ablation-plan.json').exists():raise FileExistsError('Registration already exists')
    old=json.loads((AREA/'training-plan.json').read_text())
    rows=read_rows(AREA/'validation-inputs.jsonl');indexed={r['id']:r for r in rows}
    a={r['id']:r for r in read_rows(AREA/'validation/block-1/incumbent/evaluation.jsonl')}
    b={r['id']:r for r in read_rows(AREA/'validation/block-1/N-20260911/evaluation.jsonl')}
    groups={k:[] for k in ('incumbent_only','candidate_only','both','neither')}
    for i in indexed:
        x=strict({**a[i],**a[i]['neural']});y=strict({**b[i],**b[i]['neural']})
        group='both' if x and y else 'incumbent_only' if x else 'candidate_only' if y else 'neither';groups[group].append(i)
    selected=[];selection={}
    for group,ids in groups.items():
        chosen=sorted(ids,key=lambda i:hashlib.sha256(('support-ablation-panel-v1/'+i).encode()).hexdigest())[:16]
        assert len(chosen)==16;selected+=chosen;selection[group]=chosen
    rare=[r['id'] for r in read_rows(ROOT/old['datasets']['N']['path']) if r['split']=='validation' and strict(r) and r['seed']['branch']=='LIQUID_ONLY']
    selected=sorted(set(selected+rare));assert 64<=len(selected)<=65
    freeze(OUT/'ablation-panel.jsonl',[indexed[i] for i in selected],True)
    files=[ROOT/'tools/hybrid-diagnosis'/n for n in ('common.py','train_support_ablation.py','ablation.py','run_ablation_native.py')]
    frozen=[AREA/'training-plan.json',AREA/'normalization.json',AREA/'warmup.json',AREA/'benchmark-plan.json',
            AREA/'fits/N-20260911/report.json',ROOT/old['datasets']['N']['path']]+[ROOT/p for p in old['anchors']]
    for p in frozen:verify_frozen(p)
    plan=dict(revision='support-parameterization-diagnostic-v1',createdUtc=datetime.now(timezone.utc).isoformat(),baseCommit='f459085',
        arms=['empirical','canonical'],maxFits=2,seed=20260911,optimizerSteps=4160,checkpointEvery=26,
        checkpointRule='Minimum original 168-reference profile selection score; earliest tie. Final endpoint also retained.',
        change='Support coordinates 45:85 only: canonical dm=0,dscale=1; transform initial W=sW,b=sb+m to preserve physical logits.',
        fixed='Architecture, continuous outputs, loss, N data, minibatch order, optimizer settings, clipping, decoder, material wrapper and native budgets.',
        panel=info(OUT/'ablation-panel.jsonl'),panelCases=len(selected),panelSelection=selection,additionalRareReferenceIds=rare,
        panelInterpretation='Outcome-stratified retrospective validation diagnostic; not a population success estimate or new holdout.',
        nativeOrderByBlock=[['incumbent','empirical','canonical'],['canonical','empirical','incumbent']],
        nativeBlocks=2,workers=10,requestDeadlineMillis=30000,neuralBudgetMillis=2000,maximumIterations=16,
        strategies=['current','neural','neuralFirst'],maximumCorrectedRequests=len(selected)*3*3*2,
        preparationOnOffExperiment='Skipped: selected hybrids have no incumbent-only losses among PREPARED validation cases.',
        sourceAnalysis=[info(OUT/n) for n in ('case-analysis.json','training-analysis.json','support-floor-analysis.json','gradient-analysis.json')],
        sources=[info(p) for p in files],frozenInputs=[info(p) for p in frozen],
        testInputsAllowed=False,promotionAllowed=False,extraFitsOrThresholdSearchAllowed=False)
    freeze(OUT/'ablation-plan.json',plan)
    print(json.dumps({'registeredFits':2,'panelCases':len(selected),'maximumCorrectedRequests':plan['maximumCorrectedRequests']}))


def verify():
    plan=json.loads((OUT/'ablation-plan.json').read_text())
    for item in plan['sources']+plan['frozenInputs']+plan['sourceAnalysis']+[plan['panel']]:
        assert digest(ROOT/item['path'])==item['sha256'],item['path']
    original_benchmark.verify_plan()
    return plan


def train():
    verify();logs=OUT/'ablation-logs';logs.mkdir(exist_ok=True)
    for arm in ('empirical','canonical'):
        verify();command=[sys.executable,str(ROOT/'tools/hybrid-diagnosis/train_support_ablation.py'),arm]
        with (logs/f'train-{arm}.log').open('x') as log:
            process=subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,cwd=ROOT)
            for line in process.stdout:log.write(line);log.flush();print(line,end='',flush=True)
            if process.wait():raise RuntimeError('Fit failed; preserve logs and do not silently retry')
    a=json.loads((OUT/'ablation-fits/empirical/report.json').read_text());b=json.loads((OUT/'ablation-fits/canonical/report.json').read_text())
    assert a['minibatchOrderSha256']==b['minibatchOrderSha256'] and a['perCasePresentationCounts']==b['perCasePresentationCounts']
    assert set(a['perCasePresentationCounts'].values())=={160}
    print('Two fits complete; initial physical predictions and exact minibatch order matched')


def export():
    verify();torch.set_num_threads(2)
    old=json.loads((AREA/'training-plan.json').read_text());anchors=h.load_anchors([ROOT/p for p in old['anchors']])
    train=[r for r in read_rows(ROOT/old['datasets']['N']['path']) if r['split']=='train' and r['labelProvenance']['eligibleForFitting']]
    fixture_ids=[r['id'] for r in json.loads((AREA/'models/N-20260911/fixture.json').read_text())]
    fixture=[next(r for r in train if r['id']==i) for i in fixture_ids]
    template=json.loads((AREA/'models/N-20260911/model.json').read_text());models={}
    for arm in ('empirical','canonical'):
        path=OUT/'ablation-fits'/arm/'model.pt';cp=torch.load(path,map_location='cpu',weights_only=True);report=cp['report']
        assert report['planSha256']==digest(OUT/'ablation-plan.json') and report['optimizerSteps']==4160
        model=h.pilot.ColumnModel('transformer',inputs=185);model.load_state_dict(cp['state_dict']);model.eval()
        doc=copy.deepcopy(template);doc.update(modelId='support-ablation-'+arm+'-v1',normalization=cp['normalization'],
            checkpointSha256=digest(path),trainingPlanSha256=digest(OUT/'ablation-plan.json'),
            weights={k:{'shape':list(v.shape),'values':v.numpy().ravel().tolist()} for k,v in model.state_dict().items()})
        directory=OUT/'ablation-models'/arm;freeze(directory/'model.json',doc)
        fixtures=[fixture_forward(model.double(),r,cp['normalization'],anchors,report['branchesSeen']) for r in fixture]
        freeze(directory/'fixture.json',fixtures)
        pipeline=dict(revision='hybrid-pipeline-v1',id=arm,kind='hybrid-residual',weights=info(directory/'model.json'),materialCompletion=True)
        freeze(directory/'pipeline.json',pipeline);models[arm]={**info(directory/'pipeline.json'),'weights':pipeline['weights']}
    pipeline=json.loads((AREA/'models/incumbent/pipeline.json').read_text());freeze(OUT/'ablation-models/incumbent/pipeline.json',pipeline)
    models['incumbent']={**info(OUT/'ablation-models/incumbent/pipeline.json'),'weights':pipeline['weights']}
    freeze(OUT/'ablation-models.json',models)
    print(json.dumps({'exportedPipelines':3,'trainParityFixtures':len(fixture)}))


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('mode',choices=['register','train','export']);a=p.parse_args();globals()[a.mode]()
