"""Frozen-model TRAIN/validation diagnosis; no fitting, test tensors or solver calls."""
from collections import Counter
import json
from pathlib import Path
import sys
import numpy as np
import torch

ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'tools/hybrid-learning'),str(ROOT/'tools/neural')]
import train_hybrid as hybrid
import train_transformer as pilot
import train_transformer_accuracy as accuracy
import train_gen3_factorized as factor
from prepare_transformer_data import read_rows, digest, strict, stats
from native_checkpoint_selection_v1 import freeze, info

AREA=ROOT/'build/neural-hybrid-learning/v1'
OUT=ROOT/'build/neural-hybrid-diagnosis/v1'


def profile_summary(rows):
    return dict(columns=len(rows),stages=stats([r['input']['stageCount'] for r in rows]),
        stageCounts=dict(Counter(r['input']['stageCount'] for r in rows)),
        steam=sum(bool(r['input'].get('steamFeeds')) for r in rows),
        draws=dict(Counter(len(r['input'].get('sideDraws',[])) for r in rows)),
        heatLoops=dict(Counter(len(r['input'].get('pumparounds',[])) for r in rows)),
        branches=dict(Counter(r['seed']['branch'] for r in rows)),
        wetColumns=sum(any(r['seed']['wetTrays']) for r in rows))


def conditioning(rows,anchors,norm):
    y=[]; b=[]; weights=[]; unavailable=0
    for r in rows:
        target,_=factor.targets(r);anchor=anchors[r['id']][r['seed']['branch']]
        y.append(target);b.append(np.asarray(anchor['values']));weights.extend([1/len(target)]*len(target))
        unavailable+=not anchor['available']
    y=np.vstack(y);b=np.vstack(b);w=np.asarray(weights);w/=w.sum()
    variance=lambda v: np.sum(w[:,None]*(v-np.sum(w[:,None]*v,axis=0))**2,axis=0)
    vy=variance(y);vd=variance(y-b);result={}
    for name,indices in [('temperature',[0]),('logLiquidTotal',[1]),('logVaporTotal',[2]),
                          ('liquidComposition',list(range(3,23))),('vaporComposition',list(range(23,43)))]:
        iy=np.asarray(indices);ys=np.asarray(norm['yscale'])[iy]
        result[name]=dict(absoluteVariance=float(vy[iy].mean()),residualVariance=float(vd[iy].mean()),
            residualToAbsoluteVariance=float(vd[iy].sum()/max(vy[iy].sum(),1e-30)),
            anchorTargetNormalizedRmse=float(np.sqrt(np.sum(w[:,None]*((y[:,iy]-b[:,iy])/ys)**2)/len(iy))))
    return dict(columns=len(rows),labelBranchAnchorUnavailable=unavailable,groups=result,
        interpretation='Column-balanced encoded targets; label branch used only to describe anchor conditioning, not as inference input.')


def main():
    OUT.mkdir(parents=True,exist_ok=True);torch.set_num_threads(4)
    plan=json.loads((AREA/'training-plan.json').read_text());sources=[]
    for item in plan['datasets'].values():
        assert digest(ROOT/item['path'])==item['sha256'];sources.append(item)
    assert digest(ROOT/plan['normalization']['path'])==plan['normalization']['sha256']
    for path,sha in plan['anchors'].items(): assert digest(ROOT/path)==sha
    anchors=hybrid.load_anchors([ROOT/p for p in plan['anchors']])
    norm=json.loads((ROOT/plan['normalization']['path']).read_text())
    old=[r for r in read_rows(ROOT/plan['datasets']['N']['path']) if r['split']=='train' and r['labelProvenance']['eligibleForFitting']]
    allrows=read_rows(ROOT/plan['datasets']['Nplus1']['path'])
    train=[r for r in allrows if r['split']=='train' and r['labelProvenance']['eligibleForFitting']]
    oldids={r['id'] for r in old};added=[r for r in train if r['id'] not in oldids]
    val=[r for r in allrows if r['split']=='validation' and strict(r)]
    assert len(old)==805 and len(added)==101 and len(train)==906 and len(val)==168
    groups={'originalN':old,'added101':added,'validation168':val};fits={};per_case=[]
    tensor_rows=old+added+val
    for dataset in ('N','Nplus1'):
        for seed in hybrid.SEEDS:
            name=f'{dataset}-{seed}';path=AREA/'fits'/name/'model.pt'
            checkpoint=torch.load(path,map_location='cpu',weights_only=False)
            assert checkpoint['normalization']==norm
            report=checkpoint['report'];sources.append(info(path))
            model=pilot.ColumnModel('transformer',inputs=185)
            model.load_state_dict(checkpoint['state_dict']);model.eval()
            batch=hybrid.tensors(tensor_rows,norm,anchors,report['branchesSeen'],'cpu')
            collected={};support=[]
            with torch.no_grad():
                for start in range(0,len(tensor_rows),32):
                    part=pilot.subset(batch,torch.arange(start,min(start+32,len(tensor_rows))))
                    raw,branch=hybrid.forward(model,part,norm)
                    metrics=accuracy.metrics(raw,branch,part)
                    for key,value in metrics.items(): collected.setdefault(key,[]).extend(value.tolist())
                    pred=raw[...,45:]>=np.log(.02/.98);wanted=part['raw'][...,45:]>0
                    active=part['feed'][:,None,:]>0
                    active=torch.cat((active,active),-1)&part['valid'][...,None]
                    fn=(~pred&wanted&active).sum((1,2));fp=(pred&~wanted&active).sum((1,2))
                    positives=(wanted&active).sum((1,2));negatives=(~wanted&active).sum((1,2))
                    support.extend([dict(falseNegative=int(a),falsePositive=int(b),positive=int(c),negative=int(d))
                        for a,b,c,d in zip(fn,fp,positives,negatives)])
            measurements={};offset=0
            for group,rows in groups.items():
                end=offset+len(rows);values=support[offset:end]
                measurements[group]=dict(columns=len(rows),metrics={k:stats(v[offset:end]) for k,v in collected.items()},
                    rawSupportCounts={k:sum(v[k] for v in values) for k in values[0]},
                    rawSupportColumnsWithFalseNegative=sum(v['falseNegative']>0 for v in values),
                    rawSupportColumnsWithFalsePositive=sum(v['falsePositive']>0 for v in values))
                for j,row in enumerate(rows,start=offset):
                    per_case.append(dict(pipeline=name,group=group,id=row['id'],**{k:v[j] for k,v in collected.items()},rawSupport=support[j]))
                offset=end
            fits[name]=dict(dataset=dataset,seed=seed,measurements=measurements,
                selectedUpdate=report['bestEpoch']*26,updates=report['optimizerSteps'],presentations=report['samplePresentations'],
                meanFullDatasetExposure=report['samplePresentations']/report['trainColumns'],
                meanOriginalNExposureExpectation=report['samplePresentations']/report['trainColumns'],
                firstSelectionScore=report['history'][0]['selectionScore'],lastSelectionScore=report['history'][-1]['selectionScore'],
                selectedSelectionScore=report['bestSelectionScore'],firstTrainLoss=report['history'][0]['trainLoss'],
                lastTrainLoss=report['history'][-1]['trainLoss'],source=info(path))
    result=dict(revision='hybrid-retrospective-training-diagnosis-v1',source=info(Path(__file__)),
        sources=sources,trainingPlan=info(AREA/'training-plan.json'),newFits=0,newSolverRequests=0,testTensors=0,
        groups={k:profile_summary(v) for k,v in groups.items()},
        conditioning={k:conditioning(v,anchors,norm) for k,v in groups.items()},
        normalization={k:norm[k] for k in ('dm','dscale','ym','yscale')},fits=fits,
        caveats=['Raw support classification precedes decoder trace pruning and support refresh; it is not native support correctness.',
                 'Exposure expectation describes uniform sampling; exact old/new counts were not recorded during fitting.',
                 'Statistics and errors are descriptive; no candidate or checkpoint is selected by this analysis.'])
    freeze(OUT/'training-analysis.json',result);freeze(OUT/'training-case-evidence.jsonl',per_case,True)
    print(json.dumps({'frozenModels':len(fits),'profileEvaluations':len(per_case),'newFits':0,'testTensors':0}))


if __name__=='__main__':main()
