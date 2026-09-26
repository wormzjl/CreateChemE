"""Run one registered, matched continuation fit; no adaptive checkpoint creation."""
from capacity_common import *
import argparse
import hashlib
import time


def metrics(model, tensors, norm, batch_size=32):
    model.eval(); values={}
    with torch.no_grad():
        for start in range(0, len(tensors['g']), batch_size):
            ix=torch.arange(start, min(start+batch_size, len(tensors['g'])), device=tensors['g'].device)
            batch=pilot.subset(tensors, ix); raw, branch=forward(model, batch, norm)
            measured=accuracy.metrics(raw, branch, batch)
            for key,value in measured.items(): values.setdefault(key,[]).append(value.detach().cpu())
    return {key:stats(torch.cat(parts).numpy()) for key,parts in values.items()}


def added_block_diagnostics(model,tensors,norm):
    batch=pilot.subset(tensors,torch.arange(min(32,len(tensors['g'])),device=tensors['g'].device))
    results={};handles=[]
    for index in range(2,len(model.blocks)):
        block=model.blocks[index]
        result=dict(attentionOutputWeightNorm=float(block.self_attn.out_proj.weight.detach().norm()),
                    attentionOutputBiasNorm=float(block.self_attn.out_proj.bias.detach().norm()),
                    feedForwardOutputWeightNorm=float(block.linear2.weight.detach().norm()),
                    feedForwardOutputBiasNorm=float(block.linear2.bias.detach().norm()))
        results[str(index)]=result
        def capture(module,args,output,target=result):
            difference=(output-args[0])[batch['valid']]
            target['trainProbeHiddenDifferenceL2']=float(difference.norm())
            target['trainProbeHiddenDifferenceMaximum']=float(difference.abs().max())
        handles.append(block.register_forward_hook(capture))
    try:
        with torch.no_grad():forward(model.eval(),batch,norm)
    finally:
        for handle in handles:handle.remove()
    return dict(trainProbeIndices=list(range(len(batch['g']))),blocks=results)


def run(arm, seed):
    from capacity_register import verify_plan
    plan=verify_plan(); recipe=plan['training']; config=plan['arms'][arm]
    assert seed in recipe['seeds']
    destination=OUT/'fits'/f'{arm}-{seed}'
    destination.mkdir(parents=True, exist_ok=False)
    freeze(destination/'started.json',dict(plan=info(OUT/'training-plan.json'),arm=arm,seed=seed))
    torch.set_num_threads(4);torch.manual_seed(seed);np.random.seed(seed)
    assert torch.cuda.is_available();torch.cuda.manual_seed_all(seed)
    torch.backends.cuda.matmul.allow_tf32=False;torch.backends.cudnn.allow_tf32=False
    torch.use_deterministic_algorithms(True);torch.cuda.reset_peak_memory_stats()
    rows=read_rows(SOURCE/'N804.jsonl');references=read_rows(SOURCE/'validation-references.jsonl')
    norm=normalization();train=dataset(rows,'cuda');validation=dataset(references,'cuda')
    model=initial_model(config['layers']).cuda();initial_hash=state_digest(model)
    # Reset AdamW identically in both arms; no inherited moments or scheduler state.
    optimizer=torch.optim.AdamW(model.parameters(),lr=recipe['learningRate'],weight_decay=recipe['weightDecay'])
    ym=torch.tensor(norm['ym'],device='cuda');ys=torch.tensor(norm['yscale'],device='cuda')
    counts=np.zeros(len(rows),dtype=np.int64);order=hashlib.sha256();history=[];checkpoints=[]
    started=time.perf_counter();loss_sum=0.;presentations=0;clipped=0;updates=0
    initial_metrics=dict(train=metrics(model,train,norm),validation=metrics(model,validation,norm))
    for step,indices in enumerate(batches(len(rows),recipe['updates'],seed),1):
        model.train();ix=indices.numpy();counts[ix]+=1;order.update(ix.tobytes())
        batch=pilot.subset(train,indices.to('cuda'));optimizer.zero_grad(set_to_none=True)
        raw,branch=forward(model,batch,norm)
        loss=pilot.loss((raw-ym)/ys,branch,batch,ym,ys)
        assert torch.isfinite(loss)
        loss.backward();gradient=torch.nn.utils.clip_grad_norm_(model.parameters(),recipe['gradientClip'])
        assert torch.isfinite(gradient);optimizer.step()
        loss_sum+=float(loss.detach())*len(indices);presentations+=len(indices)
        clipped+=int(float(gradient)>recipe['gradientClip']);updates+=1
        if step%260==0 or step in recipe['checkpoints']:
            history.append(dict(step=step,meanBaseLoss=loss_sum/presentations,intervalPresentations=presentations,
                                intervalUpdates=updates,clippedUpdates=clipped))
            loss_sum=0.;presentations=0;clipped=0;updates=0
        if step in recipe['checkpoints']:
            train_metrics=metrics(model,train,norm);validation_metrics=metrics(model,validation,norm)
            record=dict(step=step,train=train_metrics,validation=validation_metrics,
                        addedBlocks=added_block_diagnostics(model,train,norm),
                        profileSelectionScore=validation_metrics['selectionScore']['mean'],
                        minibatchOrderSha256=order.hexdigest(),
                        perCasePresentationCounts={row['id']:int(n) for row,n in zip(rows,counts)},
                        minimumPresentations=int(counts.min()),maximumPresentations=int(counts.max()),
                        totalSamplePresentations=int(counts.sum()))
            report=dict(revision='capacity-checkpoint-v1',arm=arm,seed=seed,layers=config['layers'],
                        planSha256=digest(OUT/'training-plan.json'),dataSha256=digest(SOURCE/'N804.jsonl'),
                        initialWeightsSha256=initial_hash,optimizerSteps=step,checkpoint=record)
            path=destination/f'checkpoint-{step}.pt'
            torch.save(dict(state_dict={k:v.detach().cpu().clone() for k,v in model.state_dict().items()},
                            normalization=norm,report=report,optimizer_state_dict=optimizer.state_dict()),path)
            freeze(destination/f'checkpoint-{step}.json',report)
            checkpoints.append(dict(**record,file=info(path)))
            print(json.dumps({'arm':arm,'seed':seed,'step':step,'trainProfileScore':train_metrics['selectionScore']['mean'],
                              'validationProfileScore':record['profileSelectionScore'],'seconds':round(time.perf_counter()-started,2)}),flush=True)
    torch.cuda.synchronize()
    final=dict(revision='capacity-fit-v1',complete=True,arm=arm,seed=seed,layers=config['layers'],
               planSha256=digest(OUT/'training-plan.json'),dataSha256=digest(SOURCE/'N804.jsonl'),
               initialWeightsSha256=initial_hash,parameters=sum(p.numel() for p in model.parameters()),
               optimizerSteps=recipe['updates'],freshOptimizer=True,history=history,initialMetrics=initial_metrics,
               checkpoints=checkpoints,trainingSeconds=time.perf_counter()-started,
               peakAllocatedGpuBytes=torch.cuda.max_memory_allocated(),gpu=torch.cuda.get_device_name(),
               torchVersion=str(torch.__version__),cudaVersion=torch.version.cuda)
    freeze(destination/'report.json',final)
    print(json.dumps({'fitComplete':f'{arm}-{seed}','seconds':round(final['trainingSeconds'],2)}),flush=True)


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('arm');p.add_argument('seed',type=int);args=p.parse_args()
    run(args.arm,args.seed)
