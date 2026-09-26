"""CUDA whole-column pilot: small stage transformer versus parameter-matched MLP.

This is an offline profile-regression experiment, not a native-solver candidate.
Only eligible training and original validation profiles are loaded into tensors.
"""
import argparse
import copy
import hashlib
import json
import time
from pathlib import Path

import numpy as np
import torch
from torch import nn
from torch.nn import functional as F

import train_generalized as base
import train_gen3_factorized as factor
from prepare_transformer_data import strict, stats


class ColumnModel(nn.Module):
    def __init__(self, kind, inputs=96, outputs=85):
        super().__init__()
        self.kind = kind
        self.embed = nn.Linear(inputs, 64)
        if kind == 'transformer':
            self.blocks = nn.ModuleList([nn.TransformerEncoderLayer(64, 4, 128,
                dropout=0., activation='gelu', batch_first=True, norm_first=True) for _ in range(2)])
        elif kind == 'mlp':
            self.blocks = nn.ModuleList([nn.Sequential(nn.LayerNorm(64), nn.Linear(64, 256),
                nn.GELU(), nn.Linear(256, 64)) for _ in range(2)])
        else:
            raise ValueError('Unknown architecture')
        self.output = nn.Sequential(nn.LayerNorm(64), nn.Linear(64, outputs))
        self.branch = nn.Sequential(nn.Linear(74, 64), nn.GELU(), nn.Linear(64, 3))

    def forward(self, x, g, valid):
        h = self.embed(x)
        for block in self.blocks:
            h = block(h, src_key_padding_mask=~valid) if self.kind=='transformer' else h+block(h)
            h = h.masked_fill(~valid[...,None], 0.)
        return self.output(h), self.branch(g)


def normalization(rows):
    xs, ys, weights = [], [], []
    for row in rows:
        x = base.node_features(row['input'], 'TWO_PHASE')[:,:-3]
        y, _ = factor.targets(row)
        xs.append(x); ys.append(y); weights.extend([1/len(x)]*len(x))
    xm, xscale = base.moments(np.vstack(xs), weights)
    ym, yscale = base.moments(np.vstack(ys), weights)
    c = len(rows[0]['input']['feedComponentMolarFlowsMolPerSecond'])
    yscale[0] = max(yscale[0],25); yscale[1:3] = np.maximum(yscale[1:3],.1)
    yscale[3:3+2*c] = np.maximum(yscale[3:3+2*c],1)
    yscale[3+2*c] = max(yscale[3+2*c],1); yscale[4+2*c]=1
    ym[5+2*c:]=0; yscale[5+2*c:]=1
    gm, gscale = base.moments(np.vstack([base.global_features(r['input']) for r in rows]))
    return {k:v.tolist() for k,v in dict(xm=xm,xscale=xscale,ym=ym,yscale=yscale,gm=gm,gscale=gscale).items()}


def tensors(rows, norm, device):
    c = len(rows[0]['input']['feedComponentMolarFlowsMolPerSecond'])
    n = len(rows); length = max(r['input']['stageCount']+2 for r in rows)
    x = np.zeros((n,length,len(norm['xm'])),np.float32)
    raw = np.zeros((n,length,4*c+5),np.float32)
    fractions = np.zeros((n,length,2,c),np.float32)
    valid = np.zeros((n,length),bool)
    for i,r in enumerate(rows):
        xx = base.node_features(r['input'],'TWO_PHASE')[:,:-3]
        y,f = factor.targets(r); count=len(xx)
        x[i,:count] = (xx-norm['xm'])/norm['xscale']
        raw[i,:count]=y; fractions[i,:count]=f; valid[i,:count]=True
    g = (np.vstack([base.global_features(r['input']) for r in rows])-norm['gm'])/norm['gscale']
    feed = np.asarray([r['input']['feedComponentMolarFlowsMolPerSecond'] for r in rows])
    values = dict(x=x,g=g,raw=raw,fractions=fractions,valid=valid,feed=feed/feed.sum(axis=1)[:,None],
        labels=np.asarray([base.BRANCHES.index(r['seed']['branch']) for r in rows],dtype=np.int64))
    return {k:torch.as_tensor(v,device=device,dtype=torch.bool if k=='valid' else torch.long if k=='labels' else torch.float32) for k,v in values.items()}


def loss(pred, branch, batch, ym, ys):
    raw = pred*ys+ym
    target = batch['raw']; wanted = (target-ym)/ys
    c = batch['feed'].shape[-1]
    indices = [0,1,2,3+2*c,4+2*c]
    scalar_weights = pred.new_tensor([3,5,5,.5,2])
    node = ((pred[...,indices]-wanted[...,indices]).square()*scalar_weights).sum(-1)
    active = batch['feed'][:,None,:]>0
    for phase in range(2):
        offset=3+phase*c; support=5+2*c+phase*c
        logits = raw[...,offset:offset+c]+batch['feed'][:,None,:].clamp_min(1e-30).log()
        logprob = logits.masked_fill(~active,-1e30).log_softmax(-1)
        actual = batch['fractions'][:,:,phase]
        kl = (actual*(actual.clamp_min(1e-30).log()-logprob)).sum(-1)
        present = target[...,1+phase]>0
        node = node+2*kl*present
        mask = active & present[...,None]
        node = node+.1*((pred[...,offset:offset+c]-wanted[...,offset:offset+c]).square()*mask).sum(-1)/mask.sum(-1).clamp_min(1)
        bce = F.binary_cross_entropy_with_logits(raw[...,support:support+c],(target[...,support:support+c]>0).float(),reduction='none')
        node = node+.2*(bce*active).sum(-1)/active.sum(-1).clamp_min(1)
    valid = batch['valid']
    per_column = (node*valid).sum(1)/valid.sum(1)
    return per_column.mean()+F.cross_entropy(branch,batch['labels'])


def subset(batch, indices):
    return {k:v[indices] for k,v in batch.items()}


def evaluate(model, rows, batch, norm, seen):
    model.eval()
    with torch.no_grad():
        p, logits = model(batch['x'],batch['g'],batch['valid'])
    raw = p.cpu().numpy()*norm['yscale']+norm['ym']
    logits=logits.cpu().numpy(); logits[:,~seen]=-np.inf
    results=[]
    for i,row in enumerate(rows):
        inp, seed = row['input'], row['seed']; count=inp['stageCount']+2
        if any(s.get('ratio',0)>0 for s in inp['specifications']): logits[i,2]=-np.inf
        branch=base.BRANCHES[int(logits[i].argmax())]
        predicted=factor.decode(inp,raw[i,:count],branch,.02)
        result={'id':row['id'],'branchCorrect':branch==seed['branch'],
            'temperatureMaeK':float(np.mean(np.abs(predicted['temperatures']-seed['temperatures'])))}
        feed=np.asarray(inp['feedComponentMolarFlowsMolPerSecond']); total=feed.sum()
        for phase in ('liquid','vapor'):
            actual=np.asarray(seed[phase]); q=predicted[phase]
            result[phase+'TotalMaeOverFeed']=float(np.mean(np.abs(q.sum(1)-actual.sum(1)))/total)
            result[phase+'ComponentMaeOverFeed']=float(np.mean(np.abs(q-actual))/total)
        results.append(result)
    return results


def run(args):
    if args.output.exists(): raise FileExistsError('Pilot outputs are immutable; choose a new directory')
    device=torch.device(args.device)
    if device.type=='cuda' and not torch.cuda.is_available(): raise RuntimeError('Requested CUDA unavailable')
    torch.set_num_threads(4)
    torch.manual_seed(args.seed); np.random.seed(args.seed)
    if device.type=='cuda':
        torch.cuda.manual_seed_all(args.seed)
        torch.backends.cuda.matmul.allow_tf32=False
        torch.backends.cudnn.allow_tf32=False
        torch.cuda.reset_peak_memory_stats()
    # Parse labels only from original validation and eligible training. No test
    # tensors, statistics, early stopping or candidate selection are constructed.
    metadata=json.loads((args.data/'data.json').read_text())
    if hashlib.sha256((args.data/'cases.jsonl').read_bytes()).hexdigest()!=metadata['fileSha256']['cases.jsonl']:
        raise ValueError('Frozen data changed')
    train=[]; validation=[]
    for line in (args.data/'cases.jsonl').read_text(encoding='utf-8').splitlines():
        row=json.loads(line)
        if row['split']=='train' and row['labelProvenance']['eligibleForFitting']:
            assert strict(row); train.append(row)
        elif row['split']=='validation' and strict(row): validation.append(row)
    assert not {r['labelProvenance']['canonicalInputSha256'] for r in train}.intersection(r['labelProvenance']['canonicalInputSha256'] for r in validation)
    norm=normalization(train)
    training=tensors(train,norm,device); validating=tensors(validation,norm,device)
    ym=torch.tensor(norm['ym'],device=device,dtype=torch.float32)
    ys=torch.tensor(norm['yscale'],device=device,dtype=torch.float32)
    model=ColumnModel(args.kind).to(device)
    optimizer=torch.optim.AdamW(model.parameters(),lr=8e-4,weight_decay=1e-4)
    best=float('inf'); best_epoch=0; best_state=None; history=[]
    started=time.perf_counter()
    for epoch in range(1,args.epochs+1):
        model.train(); order=torch.randperm(len(train),device=device)
        total=0.
        for ix in order.split(32):
            batch=subset(training,ix); optimizer.zero_grad(set_to_none=True)
            p,b=model(batch['x'],batch['g'],batch['valid'])
            value=loss(p,b,batch,ym,ys)
            if not torch.isfinite(value): raise FloatingPointError('Nonfinite pilot loss')
            value.backward(); nn.utils.clip_grad_norm_(model.parameters(),1.); optimizer.step()
            total+=value.detach().item()*len(ix)
        model.eval()
        with torch.no_grad():
            p,b=model(validating['x'],validating['g'],validating['valid'])
            score=loss(p,b,validating,ym,ys).item()
        history.append({'epoch':epoch,'trainLoss':total/len(train),'validationLoss':score})
        if score<best:
            best=score; best_epoch=epoch; best_state=copy.deepcopy(model.state_dict())
        if epoch%20==0: print(json.dumps(history[-1]),flush=True)
        if epoch-best_epoch>=40: break
    model.load_state_dict(best_state)
    if device.type=='cuda': torch.cuda.synchronize()
    duration=time.perf_counter()-started
    seen=np.asarray([any(r['seed']['branch']==b for r in train) for b in base.BRANCHES])
    results=evaluate(model,validation,validating,norm,seen)
    report={'revision':'transformer-offline-pilot-v1','kind':args.kind,'seed':args.seed,
        'trainColumns':len(train),'validationColumns':len(validation),'parameters':sum(p.numel() for p in model.parameters()),
        'epochsRun':epoch,'bestEpoch':best_epoch,'bestValidationLoss':best,'trainingSeconds':duration,
        'device':str(device),'gpu':torch.cuda.get_device_name(device) if device.type=='cuda' else None,
        'peakAllocatedGpuBytes':torch.cuda.max_memory_allocated() if device.type=='cuda' else None,
        'torchVersion':str(torch.__version__),'cudaVersion':torch.version.cuda,'precision':'float32; TF32 disabled',
        'dataSha256':metadata['fileSha256']['cases.jsonl'],
        'trainerSha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        'branchesSeen':seen.tolist(),'nativeSolverEvaluated':False,'freshHoldoutEvaluated':False,
        'validation':{k:stats([r[k] for r in results]) for k in results[0] if k!='id'},'history':history}
    args.output.mkdir(parents=True)
    torch.save({'state_dict':model.state_dict(),'normalization':norm,'kind':args.kind,'report':report},args.output/'model.pt')
    (args.output/'report.json').write_text(json.dumps(report,indent=2,allow_nan=False)+'\n')
    (args.output/'validation.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in results))
    print(json.dumps({k:v for k,v in report.items() if k not in ('history','validation')},indent=2),flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data',type=Path,default=Path('build/neural-transformer/data-v2'))
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--kind',choices=('transformer','mlp'),required=True)
    parser.add_argument('--seed',type=int,default=20260910)
    parser.add_argument('--epochs',type=int,default=160)
    parser.add_argument('--device',default='cuda')
    run(parser.parse_args())
