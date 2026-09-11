"""One prospectively fixed TRAIN-only gradient calibration; no optimizer step."""
from common import *
from trace_objective import trace_margin
import argparse
from datetime import datetime,timezone


def gradient_vector(loss,model,retain_graph=False):
    parameters=list(model.parameters())
    gradients=torch.autograd.grad(loss,parameters,retain_graph=retain_graph,allow_unused=True)
    return torch.cat([(torch.zeros_like(p) if g is None else g).reshape(-1) for p,g in zip(parameters,gradients)])


def register():
    rows=read_rows(OUT/'N804.jsonl')
    ordered=sorted(rows,key=lambda r:hashlib.sha256(('trace-followup-calibration-v1/'+canonical_input_hash(r['input'])).encode()).hexdigest())
    batches=[[r['id'] for r in ordered[j*32:(j+1)*32]] for j in range(3)]
    freeze(OUT/'calibration-plan.json',dict(revision='trace-gradient-calibration-v1',createdUtc=datetime.now(timezone.utc).isoformat(),
        source=info(OUT/'N804.jsonl'),normalization=info(OUT/'normalization.json'),incumbent=info(INCUMBENT),
        batches=batches,batchSize=32,coefficientRule='min(0.1, 0.1 * median(baseGradientL2) / max(median(traceGradientL2), 1e-12))',
        parameterSpace='All trainable plain incumbent parameters; unused gradients count as zero',
        precision='float32 CUDA; TF32 disabled',newOptimizerSteps=0,validationAllowed=False,
        sources=[info(ROOT/'tools/trace-followup'/name) for name in ('common.py','trace_objective.py','calibrate.py')]))
    print('Registered three fixed disjoint TRAIN calibration batches.')


def run():
    plan=read(OUT/'calibration-plan.json')
    for item in plan['sources']+[plan[k] for k in ('source','normalization','incumbent')]:assert digest(ROOT/item['path'])==item['sha256']
    torch.set_num_threads(4);assert torch.cuda.is_available();torch.backends.cuda.matmul.allow_tf32=False;torch.backends.cudnn.allow_tf32=False
    model=incumbent_model().cuda().train();before=state_digest(model);norm=read(OUT/'normalization.json')
    indexed={r['id']:r for r in read_rows(OUT/'N804.jsonl')};results=[]
    for ids in plan['batches']:
        rows=[indexed[i] for i in ids];batch=accuracy.tensors(rows,norm,'cuda')
        raw,branch=absolute_forward(model,batch,norm)
        ym=raw.new_tensor(norm['ym']);ys=raw.new_tensor(norm['yscale'])
        base_loss=pilot.loss((raw-ym)/ys,branch,batch,ym,ys);trace_loss,detail=trace_margin(raw,batch)
        base_grad=gradient_vector(base_loss,model,True);trace_grad=gradient_vector(trace_loss,model)
        assert torch.isfinite(base_grad).all() and torch.isfinite(trace_grad).all()
        a=float(base_grad.norm());b=float(trace_grad.norm())
        results.append(dict(ids=ids,baseLoss=float(base_loss.detach()),traceLoss=float(trace_loss.detach()),
            baseGradientL2=a,traceGradientL2=b,gradientCosine=float(torch.dot(base_grad,trace_grad)/(base_grad.norm()*trace_grad.norm())) if a*b>0 else None,
            traceDetails={k:float(v.detach()) for k,v in detail.items()}))
    assert state_digest(model)==before
    gbase=float(np.median([r['baseGradientL2'] for r in results]));gtrace=float(np.median([r['traceGradientL2'] for r in results]))
    coefficient=min(.1,.1*gbase/max(gtrace,1e-12));assert np.isfinite(coefficient) and coefficient>=0
    record=dict(revision='trace-gradient-calibration-result-v1',plan=info(OUT/'calibration-plan.json'),batches=results,
        medianBaseGradientL2=gbase,medianTraceGradientL2=gtrace,coefficient=coefficient,
        weightsUnchanged=True,initialWeightsSha256=before,newOptimizerSteps=0,
        limitation='The coefficient limits initial median gradient contribution only. It is fixed during training; later imbalance is measured, not adaptively corrected.')
    freeze(OUT/'calibration.json',record)
    print(json.dumps({'calibratedCoefficient':coefficient,'medianBaseGradientL2':gbase,'medianTraceGradientL2':gtrace,'weightsUnchanged':True}))


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('mode',choices=['register','run']);args=p.parse_args();globals()[args.mode]()
