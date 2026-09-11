"""Ten registered incumbent-continuation fits with fixed checkpoint/exposure accounting."""
import os
os.environ.setdefault('CUBLAS_WORKSPACE_CONFIG',':4096:8')
from common import *
from trace_objective import trace_margin
from calibrate import gradient_vector
from initialization import ANCHORS
import argparse
import copy
import time


def batches(count,steps,seed):
    generator=torch.Generator(device='cpu').manual_seed(seed)
    pending=iter(())
    for _ in range(steps):
        indices=next(pending,None)
        if indices is None:pending=iter(torch.randperm(count,generator=generator).split(32));indices=next(pending)
        yield indices


def run(arm,seed):
    from register_followup import verify_plan
    plan=verify_plan();config=plan['arms'][arm];recipe=plan['training']
    assert seed in recipe['seeds'];destination=OUT/'fits'/f'{arm}-{seed}'
    if destination.exists():raise FileExistsError('Fit evidence is immutable; do not restart an existing trajectory.')
    destination.mkdir(parents=True);freeze(destination/'started.json',dict(plan=info(OUT/'training-plan.json'),arm=arm,seed=seed))
    rows=read_rows(ROOT/plan['cohorts']['files'][config['cohort']]['path']);references=read_rows(OUT/'validation-references.jsonl')
    norm=read(OUT/'normalization.json');anchors=hybrid.load_anchors(ANCHORS);seen=incumbent_document()['branchesSeen']
    torch.set_num_threads(4);torch.manual_seed(seed);np.random.seed(seed)
    assert torch.cuda.is_available();torch.cuda.manual_seed_all(seed)
    torch.backends.cuda.matmul.allow_tf32=False;torch.backends.cudnn.allow_tf32=False
    torch.use_deterministic_algorithms(True);torch.cuda.reset_peak_memory_stats()
    model=incumbent_model(config['inputs']).cuda();initial_hash=state_digest(model)
    training=hybrid.tensors(rows,norm,anchors,seen,'cuda');validation=hybrid.tensors(references,norm,anchors,seen,'cuda')
    optimizer=torch.optim.AdamW(model.parameters(),lr=recipe['learningRate'],weight_decay=recipe['weightDecay'])
    ym=torch.tensor(norm['ym'],device='cuda',dtype=torch.float32);ys=torch.tensor(norm['yscale'],device='cuda',dtype=torch.float32)
    coefficient=plan['traceCoefficient'] if config['trace'] else 0.
    counts=np.zeros(len(rows),dtype=np.int64);order_hash=hashlib.sha256();history=[];checkpoint_records=[];gradients=[]
    elapsed=time.perf_counter();totals=dict(base=0.,trace=0.,combined=0.,gradient=0.,clipped=0,updates=0,presentations=0)
    for step,indices in enumerate(batches(len(rows),recipe['updates'],seed),1):
        model.train();ix=indices.numpy();counts[ix]+=1;order_hash.update(ix.tobytes())
        batch=pilot.subset(training,indices.to('cuda'));optimizer.zero_grad(set_to_none=True)
        raw,branch=absolute_forward(model,batch,norm,config['layout'])
        base_loss=pilot.loss((raw-ym)/ys,branch,batch,ym,ys);auxiliary,detail=trace_margin(raw,batch)
        combined=base_loss+coefficient*auxiliary if coefficient else base_loss
        assert torch.isfinite(combined) and torch.isfinite(auxiliary)
        if step in [1,*recipe['checkpoints']]:
            bg=gradient_vector(base_loss,model,True);tg=gradient_vector(auxiliary,model,True)
            a=float(bg.norm());b=float(tg.norm());assert np.isfinite(a) and np.isfinite(b)
            gradients.append(dict(step=step,measuredBeforeUpdate=True,baseGradientL2=a,traceGradientL2=b,
                weightedTraceGradientL2=coefficient*b,weightedTraceToBaseRatio=coefficient*b/max(a,1e-30),
                baseTraceGradientCosine=float(torch.dot(bg,tg)/(bg.norm()*tg.norm())) if a*b>0 else None,
                baseLoss=float(base_loss.detach()),traceLoss=float(auxiliary.detach()),
                traceDetails={k:float(v.detach()) for k,v in detail.items()}))
        combined.backward();gn=torch.nn.utils.clip_grad_norm_(model.parameters(),recipe['gradientClip'])
        assert torch.isfinite(gn);optimizer.step()
        size=len(indices);totals['base']+=float(base_loss.detach())*size;totals['trace']+=float(auxiliary.detach())*size
        totals['combined']+=float(combined.detach())*size;totals['gradient']+=float(gn);totals['clipped']+=int(float(gn)>recipe['gradientClip'])
        totals['updates']+=1;totals['presentations']+=size
        if step%260==0 or step in recipe['checkpoints']:
            history.append(dict(step=step,meanBaseLoss=totals['base']/totals['presentations'],
                meanTraceLoss=totals['trace']/totals['presentations'],meanCombinedLoss=totals['combined']/totals['presentations'],
                meanUnclippedGradientNorm=totals['gradient']/totals['updates'],clippedUpdates=totals['clipped'],
                intervalUpdates=totals['updates'],intervalPresentations=totals['presentations']))
            totals=dict(base=0.,trace=0.,combined=0.,gradient=0.,clipped=0,updates=0,presentations=0)
        if step in recipe['checkpoints']:
            model.eval()
            with torch.no_grad():
                vr,vb=absolute_forward(model,validation,norm,config['layout']);measures=accuracy.metrics(vr,vb,validation)
                score=float(measures['selectionScore'].mean())
            assert np.isfinite(score)
            exposure={r['id']:int(n) for r,n in zip(rows,counts)}
            checkpoint_record=dict(step=step,profileSelectionScore=score,minibatchOrderSha256=order_hash.hexdigest(),
                perCasePresentationCounts=exposure,totalSamplePresentations=int(counts.sum()),
                minimumPresentations=int(counts.min()),maximumPresentations=int(counts.max()),
                validation={k:stats(v.cpu().numpy()) for k,v in measures.items()})
            report=dict(revision='trace-followup-checkpoint-v1',arm=arm,seed=seed,layout=config['layout'],traceCoefficient=coefficient,
                initialWeightsSha256=initial_hash,planSha256=digest(OUT/'training-plan.json'),dataSha256=digest(OUT/f"{config['cohort']}.jsonl"),
                branchesSeen=seen,checkpoint=checkpoint_record,optimizerSteps=step,trainColumns=len(rows),validationColumns=168)
            state={k:v.detach().cpu().clone() for k,v in model.state_dict().items()}
            checkpoint_path=destination/f'checkpoint-{step}.pt'
            torch.save(dict(state_dict=state,normalization=norm,report=report,optimizer_state_dict=optimizer.state_dict()),checkpoint_path)
            freeze(destination/f'checkpoint-{step}.json',report)
            checkpoint_records.append(dict(**checkpoint_record,file=info(checkpoint_path)))
            print(json.dumps({'arm':arm,'seed':seed,'checkpoint':step,'profileScore':score,
                'presentations':[int(counts.min()),int(counts.max())],'seconds':round(time.perf_counter()-elapsed,2)}),flush=True)
    torch.cuda.synchronize()
    final=dict(revision='trace-followup-fit-v1',complete=True,arm=arm,seed=seed,cohort=config['cohort'],layout=config['layout'],
        initialWeightsSha256=initial_hash,traceCoefficient=coefficient,parameters=sum(p.numel() for p in model.parameters()),
        planSha256=digest(OUT/'training-plan.json'),dataSha256=digest(OUT/f"{config['cohort']}.jsonl"),
        optimizerSteps=recipe['updates'],samplePresentations=int(counts.sum()),minibatchOrderSha256=order_hash.hexdigest(),
        perCasePresentationCounts={r['id']:int(n) for r,n in zip(rows,counts)},history=history,gradientDiagnostics=gradients,
        checkpoints=checkpoint_records,trainingSeconds=time.perf_counter()-elapsed,
        precision='float32 CUDA; TF32 disabled; deterministic algorithms',gpu=torch.cuda.get_device_name(),
        torchVersion=str(torch.__version__),cudaVersion=torch.version.cuda,peakAllocatedGpuBytes=torch.cuda.max_memory_allocated())
    freeze(destination/'report.json',final)
    print(json.dumps({'fitComplete':f'{arm}-{seed}','updates':recipe['updates'],'seconds':round(final['trainingSeconds'],2)}),flush=True)


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('arm');p.add_argument('seed',type=int);args=p.parse_args();run(args.arm,args.seed)
