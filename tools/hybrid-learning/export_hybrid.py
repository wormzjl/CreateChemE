"""Export all six validation-checkpoint-selected fits and TRAIN-only parity fixtures."""
import copy
import json
from pathlib import Path
import numpy as np
import torch
import train_hybrid as h
from native_checkpoint_selection_v1 import freeze, info

ROOT=Path(__file__).resolve().parents[2]
AREA=ROOT/'build/neural-hybrid-learning/v1'


def fixture_forward(model,row,norm,anchors,seen):
    """Double inputs avoid hiding float32 normalization drift in Java parity."""
    inp=row['input'];g=h.base.global_features(inp);x=h.base.node_features(inp,'TWO_PHASE')[:,:-3]
    gg=torch.tensor((g-norm['gm'])/norm['gscale'],dtype=torch.float64)[None]
    with torch.no_grad(): logits=model.branch(gg)[0]
    allowed=np.array(seen)
    if any(s.get('ratio',0)>0 for s in inp['specifications']):allowed[2]=False
    branch_index=int(logits.masked_fill(~torch.tensor(allowed),-torch.inf).argmax())
    branch=h.base.BRANCHES[branch_index];a=anchors[row['id']][branch]
    anchor=np.asarray(a['values']);onehot=np.zeros((len(x),3));onehot[:,branch_index]=1
    joined=np.hstack(((x-norm['xm'])/norm['xscale'],(anchor-norm['bm'])/norm['bscale'],
                      onehot,np.full((len(x),1),a['available'])))
    with torch.no_grad(): pred,_=model(torch.tensor(joined,dtype=torch.float64)[None],gg,torch.ones((1,len(x)),dtype=torch.bool))
    raw=pred[0].numpy()*norm['dscale']+norm['dm'];raw[:,:43]+=anchor[:,:43]
    decoded=h.factor.decode(inp,raw,branch,.02)
    return dict(id=row['id'],split='train',input=inp,branch=branch,branchLogits=logits.tolist(),
                raw=raw.tolist(),anchor=a,decoded={k:v.tolist() if isinstance(v,np.ndarray) else v for k,v in decoded.items()})


def main():
    torch.set_num_threads(2)
    plan=json.loads((AREA/'training-plan.json').read_text())
    anchors=h.load_anchors([ROOT/p for p in plan['anchors']])
    n=[r for r in h.read_rows(ROOT/plan['datasets']['N']['path']) if r['split']=='train']
    fixture=sorted(n,key=lambda r:(r['input']['stageCount'],r['id']))[::max(1,len(n)//12)][:12]
    for row in (max(n,key=lambda r:r['input']['stageCount']),next(r for r in n if r['seed']['branch']=='LIQUID_ONLY')):
        if row not in fixture:fixture.append(row)
    incumbent_path=ROOT/'build/neural-hybrid/feasibility-v1/model.json'
    incumbent=json.loads(incumbent_path.read_text())
    all_models={}
    for seed in plan['training']['seeds']:
        initial=[]
        for dataset in ('N','Nplus1'):
            fit=AREA/'fits'/f'{dataset}-{seed}';cp=h.torch.load(fit/'model.pt',map_location='cpu',weights_only=True)
            report=cp['report'];initial.append(report['initialWeightsSha256'])
            assert report['optimizerSteps']==4160 and report['planSha256']==h.digest(AREA/'training-plan.json')
            model=h.pilot.ColumnModel('transformer',inputs=185);model.load_state_dict(cp['state_dict']);model.eval()
            norm=cp['normalization'];out=AREA/'models'/f'{dataset}-{seed}'
            doc={k:copy.deepcopy(incumbent[k]) for k in ('packageId','propertyRevision','components','formulationRevisions',
                'presenceThreshold','traceFloorFraction','globalMin','globalMax','designConstraints')}
            doc.update(featureRevision='v3-hybrid-residual-1',modelType='hybrid-residual',modelId=f'hybrid-{dataset}-{seed}-v1',
                branchesSeen=report['branchesSeen'],normalization=norm,weights={k:{'shape':list(v.shape),'values':v.numpy().ravel().tolist()} for k,v in model.state_dict().items()},
                checkpointSha256=h.digest(fit/'model.pt'),dataSha256=report['dataSha256'],trainingPlanSha256=report['planSha256'],
                baselineRevision='native-material-closed-anchor-v1',absoluteOutputStart=43)
            freeze(out/'model.json',doc)
            model=model.double()
            fixtures=[fixture_forward(model,r,norm,anchors,report['branchesSeen']) for r in fixture]
            freeze(out/'fixture.json',fixtures)
            pipeline=dict(revision='hybrid-pipeline-v1',id=f'{dataset}-{seed}',kind='hybrid-residual',
                weights=info(out/'model.json'),materialCompletion=True,seed=seed,dataset=dataset)
            freeze(out/'pipeline.json',pipeline)
            all_models[pipeline['id']]={**info(out/'pipeline.json'),'weights':pipeline['weights'],'offlineSelectionScore':report['bestSelectionScore']}
        assert initial[0]==initial[1], 'Paired initial weights differ'
    for name,completion in [('incumbent',False),('incumbent-wrapper',True)]:
        pipeline=dict(revision='hybrid-pipeline-v1',id=name,kind='transformer',weights=info(incumbent_path),materialCompletion=completion)
        freeze(AREA/'models'/name/'pipeline.json',pipeline)
        all_models[name]={**info(AREA/'models'/name/'pipeline.json'),'weights':pipeline['weights']}
    freeze(AREA/'models.json',all_models)
    print(json.dumps({'exportedPipelines':len(all_models),'fixturesPerHybrid':len(fixture)}))


if __name__=='__main__':main()
