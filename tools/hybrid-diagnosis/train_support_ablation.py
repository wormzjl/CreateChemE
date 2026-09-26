"""Two registered support-parameterization fits with equal physical initialization."""
from common import *
import argparse
import copy
import math
import time
import numpy as np
import train_hybrid as h
import train_transformer as pilot
import train_transformer_accuracy as accuracy


def run(arm):
    registration=json.loads((OUT/'ablation-plan.json').read_text())
    for item in registration['sources']+registration['frozenInputs']:
        assert digest(ROOT/item['path'])==item['sha256'],item['path']
    assert arm in ('empirical','canonical')
    destination=OUT/'ablation-fits'/arm
    if destination.exists():raise FileExistsError('Registered fits are immutable')
    prior=json.loads((AREA/'training-plan.json').read_text())
    rows=read_rows(ROOT/prior['datasets']['N']['path'])
    train=[r for r in rows if r['split']=='train' and r['labelProvenance']['eligibleForFitting']]
    validation=[r for r in rows if r['split']=='validation' and strict(r)]
    assert len(train)==805 and len(validation)==168
    anchors=h.load_anchors([ROOT/p for p in prior['anchors']]);norm=json.loads((AREA/'normalization.json').read_text())
    seen=[any(r['seed']['branch']==b for r in train) for b in h.base.BRANCHES]
    torch.set_num_threads(4);torch.manual_seed(20260911);np.random.seed(20260911)
    assert torch.cuda.is_available();torch.cuda.manual_seed_all(20260911)
    torch.backends.cuda.matmul.allow_tf32=False;torch.backends.cudnn.allow_tf32=False
    model=pilot.ColumnModel('transformer',inputs=185)
    base_hash=hashlib.sha256(b''.join(v.numpy().tobytes() for v in model.state_dict().values())).hexdigest()
    old_report=json.loads((AREA/'fits/N-20260911/report.json').read_text())
    assert base_hash==old_report['initialWeightsSha256']
    original=copy.deepcopy(model).eval();candidate=copy.deepcopy(model).eval()
    alternative=canonical_support(candidate,norm)
    fixture=sorted(train,key=lambda r:(r['input']['stageCount'],r['id']))[::max(1,len(train)//14)]
    check=h.tensors(fixture,norm,anchors,seen,'cpu')
    with torch.no_grad():
        a,ba=h.forward(original,check,norm);b,bb=h.forward(candidate,check,alternative)
        _,qa,ma,ia=accuracy.decode(a,ba,check);_,qb,mb,ib=accuracy.decode(b,bb,check)
    difference=float((a-b).abs().max());masks=int(((qa==0)!=(qb==0)).sum())
    assert difference<2e-5 and masks==0 and torch.equal(ma,mb) and torch.equal(ia,ib)
    if arm=='canonical':norm=canonical_support(model,norm)
    model=model.cuda();training=h.tensors(train,norm,anchors,seen,'cuda');validating=h.tensors(validation,norm,anchors,seen,'cuda')
    initial_hash=hashlib.sha256(b''.join(v.detach().cpu().numpy().tobytes() for v in model.state_dict().values())).hexdigest()
    optimizer=torch.optim.AdamW(model.parameters(),lr=8e-4,weight_decay=1e-4)
    ym=torch.tensor(norm['ym'],device='cuda',dtype=torch.float32);ys=torch.tensor(norm['yscale'],device='cuda',dtype=torch.float32)
    best=math.inf;best_checkpoint=0;best_state=None;history=[];steps=0;presentations=0;passes=0
    batches=iter(());order_hash=hashlib.sha256();counts=np.zeros(len(train),dtype=np.int64);started=time.perf_counter()
    for checkpoint_index in range(1,161):
        model.train();total=0.;interval_count=0;clipped=0;norm_sum=0.
        for _ in range(26):
            ix=next(batches,None)
            if ix is None:batches=iter(torch.randperm(len(train),device='cuda').split(32));passes+=1;ix=next(batches)
            indices=ix.cpu().numpy();order_hash.update(indices.tobytes());counts[indices]+=1
            batch=pilot.subset(training,ix);optimizer.zero_grad(set_to_none=True)
            raw,branch=h.forward(model,batch,norm);loss=pilot.loss((raw-ym)/ys,branch,batch,ym,ys)
            assert torch.isfinite(loss);loss.backward();gradient_norm=torch.nn.utils.clip_grad_norm_(model.parameters(),1.)
            norm_value=float(gradient_norm.detach());norm_sum+=norm_value;clipped+=norm_value>1
            optimizer.step();steps+=1;presentations+=len(ix);interval_count+=len(ix);total+=float(loss.detach())*len(ix)
        model.eval()
        with torch.no_grad():
            raw,branch=h.forward(model,validating,norm);measures=accuracy.metrics(raw,branch,validating)
            score=float(measures['selectionScore'].mean())
        assert math.isfinite(score)
        history.append(dict(checkpoint=checkpoint_index,steps=steps,trainLoss=total/interval_count,
            selectionScore=score,clippedUpdates=clipped,meanUnclippedGradientNorm=norm_sum/26))
        if score<best:best=score;best_checkpoint=checkpoint_index;best_state=copy.deepcopy(model.state_dict())
        if checkpoint_index%20==0:print(json.dumps({'arm':arm,**history[-1]}),flush=True)
    last_state=copy.deepcopy(model.cpu().state_dict());model.load_state_dict(best_state);model.eval()
    valid_cpu=h.tensors(validation,norm,anchors,seen,'cpu')
    with torch.no_grad():raw,branch=h.forward(model,valid_cpu,norm);metrics={k:v.numpy() for k,v in accuracy.metrics(raw,branch,valid_cpu).items()}
    report=dict(revision='support-parameterization-ablation-v1',arm=arm,seed=20260911,trainColumns=805,validationColumns=168,
        optimizerSteps=steps,samplePresentations=presentations,shuffledPassesStarted=passes,
        baseInitialWeightsSha256=base_hash,initialWeightsSha256=initial_hash,minibatchOrderSha256=order_hash.hexdigest(),
        perCasePresentationCounts={r['id']:int(c) for r,c in zip(train,counts)},
        initialEquivalence=dict(maximumRawDifference=difference,maskDifferences=masks,trainFixtures=len(fixture)),
        bestEpoch=best_checkpoint,bestSelectionScore=best,finalSelectionScore=history[-1]['selectionScore'],
        branchesSeen=seen,parameters=sum(p.numel() for p in model.parameters()),history=history,
        validation={k:stats(v) for k,v in metrics.items()},trainingSeconds=time.perf_counter()-started,
        planSha256=digest(OUT/'ablation-plan.json'),trainerSha256=digest(Path(__file__)),
        dataSha256=prior['datasets']['N']['sha256'],precision='float32; TF32 disabled',gpu=torch.cuda.get_device_name(),
        explanation='Only support output parameter coordinates change; equal initial physical predictions. AdamW and global clipping act in the new parameter coordinates.')
    destination.mkdir(parents=True)
    torch.save(dict(state_dict=model.state_dict(),normalization=norm,report=report),destination/'model.pt')
    torch.save(dict(state_dict=last_state,normalization=norm,report=report),destination/'last-model.pt')
    freeze(destination/'report.json',report)
    freeze(destination/'validation.jsonl',[dict(id=r['id'],**{k:float(v[i]) for k,v in metrics.items()}) for i,r in enumerate(validation)],True)
    print(json.dumps({'completed':arm,'selectedUpdate':best_checkpoint*26,'score':best,'seconds':report['trainingSeconds']}),flush=True)


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('arm',choices=['empirical','canonical']);run(p.parse_args().arm)
