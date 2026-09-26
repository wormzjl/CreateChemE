"""Fixed TRAIN-only gradients and equivalent-initialization checks; no optimizer steps."""
from common import *
import copy
import numpy as np
import train_hybrid as hybrid
import train_transformer as pilot
import train_transformer_accuracy as accuracy


def flat_gradient(model):
    return torch.cat([(p.grad if p.grad is not None else torch.zeros_like(p)).reshape(-1) for p in model.parameters()])


def main():
    torch.set_num_threads(4);plan=json.loads((AREA/'training-plan.json').read_text())
    for item in plan['datasets'].values():verify_frozen(ROOT/item['path'])
    for path in plan['anchors']:verify_frozen(ROOT/path)
    anchors=hybrid.load_anchors([ROOT/p for p in plan['anchors']])
    n=[r for r in read_rows(ROOT/plan['datasets']['N']['path']) if r['split']=='train' and r['labelProvenance']['eligibleForFitting']]
    ids={r['id'] for r in n}
    delta=[r for r in read_rows(ROOT/plan['datasets']['Nplus1']['path']) if r['split']=='train' and r['labelProvenance']['eligibleForFitting'] and r['id'] not in ids]
    ordered=lambda rows:sorted(rows,key=lambda r:hashlib.sha256(('hybrid-gradient-v1/'+r['id']).encode()).hexdigest())[:96]
    groups={'originalN':ordered(n),'added101':ordered(delta)};records=[];sources=[]
    models={'incumbent':ROOT/'build/neural-transformer/accuracy-v1/fits/baseline-20260911/model.pt'}
    models.update({f'{d}-{s}':AREA/'fits'/f'{d}-{s}'/'model.pt' for d in ('N','Nplus1') for s in hybrid.SEEDS})
    for name,path in models.items():
        cp=checkpoint(path);norm=cp['normalization'];sources.append(info(path));is_hybrid=name!='incumbent'
        model=pilot.ColumnModel('transformer',inputs=185 if is_hybrid else 96);model.load_state_dict(cp['state_dict']);model.eval()
        gradients={}
        for group,rows in groups.items():
            batch=hybrid.tensors(rows,norm,anchors,cp['report']['branchesSeen'],'cpu') if is_hybrid else accuracy.tensors(rows,norm,'cpu')
            gradients[group]=[]
            for b in range(3):
                part=pilot.subset(batch,torch.arange(b*32,(b+1)*32));model.zero_grad(set_to_none=True)
                if is_hybrid:raw,branch=hybrid.forward(model,part,norm)
                else:
                    pred,branch=model(part['x'],part['g'],part['valid']);raw=pred*pred.new_tensor(norm['yscale'])+pred.new_tensor(norm['ym'])
                raw.retain_grad();ym=raw.new_tensor(norm['ym']);ys=raw.new_tensor(norm['yscale'])
                loss=pilot.loss((raw-ym)/ys,branch,part,ym,ys);loss.backward()
                gradient=flat_gradient(model).detach().clone();gradients[group].append(gradient)
                heads={label:dict(parameterWeightGradientL2=float(model.output[1].weight.grad[lo:hi].norm()),
                                  physicalOutputGradientL2=float(raw.grad[...,lo:hi].norm()))
                       for label,lo,hi in [('temperature',0,1),('phaseTotals',1,3),('compositions',3,43),('waterWet',43,45),('support',45,85)]}
                records.append(dict(pipeline=name,group=group,batch=b,ids=[r['id'] for r in rows[b*32:(b+1)*32]],
                    loss=float(loss),unclippedGradientL2=float(gradient.norm()),wouldClipAtOne=bool(gradient.norm()>1),heads=heads))
        for b,(a,d) in enumerate(zip(gradients['originalN'],gradients['added101'])):
            records.append(dict(pipeline=name,pairedBatch=b,oldNewGradientCosine=float(torch.nn.functional.cosine_similarity(a,d,dim=0))))
    # This test transforms only the initial support head and its output coordinates.
    torch.manual_seed(20260911);first=pilot.ColumnModel('transformer',inputs=185);first.eval()
    second=copy.deepcopy(first);norm=json.loads((AREA/'normalization.json').read_text());other=canonical_support(second,norm)
    batch=hybrid.tensors(groups['originalN'],norm,anchors,[True,True,False],'cpu')
    with torch.no_grad():
        a,ba=hybrid.forward(first,batch,norm);b,bb=hybrid.forward(second,batch,other)
        _,qa,ma,ia=accuracy.decode(a,ba,batch);_,qb,mb,ib=accuracy.decode(b,bb,batch)
    diff=float((a-b).abs().max());mask=int(((qa==0)!=(qb==0)).sum())
    assert diff<2e-5 and mask==0 and torch.equal(ma,mb) and torch.equal(ia,ib)
    result=dict(revision='hybrid-train-gradient-diagnosis-v1',source=info(Path(__file__)),sources=sources,
        minibatchDefinition='first 96 IDs per TRAIN subset sorted by SHA256(hybrid-gradient-v1/ID); three batches of 32',
        optimizerSteps=0,testTensors=0,records=records,initialEquivalence=dict(maximumRawDifference=diff,maskDifferences=mask,
            comparedTrainColumns=96,branchMasksEqual=True,invalidFlagsEqual=True),
        caveats=['Gradient alignment is measured at fitted checkpoints, not averaged over training trajectories.',
                 'Per-head parameter-gradient norms depend on parameterization; physical-output gradients are also recorded.',
                 'The canonical head retains equal initial physical predictions; AdamW regularization and clipping act in the changed parameter coordinates.'])
    freeze(OUT/'gradient-analysis.json',result)
    print(json.dumps({'frozenModels':len(models),'gradientBatches':42,'optimizerSteps':0,'initialRawDifference':diff,'initialMaskDifferences':mask}))


if __name__=='__main__':main()
