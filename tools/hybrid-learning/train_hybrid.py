"""Paired native-anchor/residual transformers; no test profiles are constructed."""
import argparse
import copy
import hashlib
import json
import math
from pathlib import Path
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'neural'))
import numpy as np
import torch
from torch import nn
import train_transformer as pilot
import train_transformer_accuracy as accuracy
import train_generalized as base
import train_gen3_factorized as factor
from prepare_transformer_data import read_rows, digest, strict, stats

SEEDS = (20260910, 20260911, 20260912)


def load_anchors(paths):
    result = {}
    for path in paths:
        for row in read_rows(path):
            if row['id'] in result and result[row['id']] != row['anchors']:
                raise ValueError('Conflicting native anchor')
            result[row['id']] = row['anchors']
    return result


def normalization(rows, anchors):
    norm = pilot.normalization(rows)
    native, residual, weights = [], [], []
    for row in rows:
        anchor = np.asarray(anchors[row['id']][row['seed']['branch']]['values'])
        target, _ = factor.targets(row)
        delta = target.copy(); delta[:, :43] -= anchor[:, :43]
        native.append(anchor); residual.append(delta)
        weights.extend([1/len(anchor)]*len(anchor))
    for prefix, values in [('b', native), ('d', residual)]:
        mean, scale = base.moments(np.vstack(values), weights)
        scale[0] = max(scale[0], 25)
        scale[1:3] = np.maximum(scale[1:3], .1)
        scale[3:] = np.maximum(scale[3:], 1)
        norm[prefix+'m'] = mean.tolist(); norm[prefix+'scale'] = scale.tolist()
    return norm


def tensors(rows, norm, anchors, seen, device):
    batch = accuracy.tensors(rows, norm, device)
    n, length = batch['valid'].shape
    values = np.zeros((n, 3, length, 85), np.float32)
    available = np.zeros((n, 3, length, 1), np.float32)
    allowed = np.tile(np.asarray(seen, bool), (n, 1))
    for i, row in enumerate(rows):
        for j, branch in enumerate(base.BRANCHES):
            a = anchors[row['id']][branch]; count = row['input']['stageCount']+2
            values[i,j,:count] = a['values']; available[i,j,:count,0] = a['available']
        if any(s.get('ratio', 0) > 0 for s in row['input']['specifications']):
            allowed[i,2] = False
    batch.update(anchors=torch.tensor(values, device=device), available=torch.tensor(available, device=device),
                 branchAllowed=torch.tensor(allowed, device=device))
    return batch


def forward(model, batch, norm):
    branch = model.branch(batch['g'])
    chosen = branch.masked_fill(~batch['branchAllowed'], -torch.inf).argmax(-1)
    index = torch.arange(len(chosen), device=chosen.device)
    anchor = batch['anchors'][index, chosen]
    available = batch['available'][index, chosen]
    bm, bs, dm, ds = (anchor.new_tensor(norm[k]) for k in ('bm', 'bscale', 'dm', 'dscale'))
    onehot = torch.nn.functional.one_hot(chosen, 3).to(anchor.dtype)[:,None,:].expand(-1, anchor.shape[1], -1)
    joined = torch.cat((batch['x'], (anchor-bm)/bs, onehot, available), dim=-1)
    pred, _ = model(joined, batch['g'], batch['valid'])
    raw = pred*ds+dm
    raw = torch.cat((raw[..., :43] + anchor[..., :43], raw[..., 43:]), dim=-1)
    return raw, branch


def run(args):
    if args.output.exists(): raise FileExistsError('Training outputs are immutable')
    plan = json.loads(args.plan.read_text())
    for record in plan['sources']:
        assert digest(Path(record['path'])) == record['sha256'], record['path']
    assert args.seed in SEEDS and plan['training']['seeds'] == list(SEEDS)
    expected = plan['datasets'][args.dataset]
    assert digest(args.data) == expected['sha256']
    for path in args.anchors:
        assert digest(path) == plan['anchors'][path.as_posix()]
    rows = read_rows(args.data)
    train = [r for r in rows if r['split']=='train' and r['labelProvenance']['eligibleForFitting']]
    validation = [r for r in rows if r['split']=='validation' and strict(r)]
    assert len(train)==expected['trainColumns'] and len(validation)==168 and all(strict(r) for r in train)
    assert not {r['labelProvenance']['canonicalInputSha256'] for r in train} & {r['labelProvenance']['canonicalInputSha256'] for r in validation}
    anchors = load_anchors(args.anchors)
    seen = [any(r['seed']['branch']==b for r in train) for b in base.BRANCHES]
    # N-derived statistics are frozen once and shared between the two datasets.
    norm_path = Path(plan['normalization']['path'])
    assert digest(norm_path) == plan['normalization']['sha256']
    norm = json.loads(norm_path.read_text())
    torch.set_num_threads(4); torch.manual_seed(args.seed); np.random.seed(args.seed)
    if not torch.cuda.is_available(): raise RuntimeError('Registered CUDA training requires GPU')
    torch.cuda.manual_seed_all(args.seed)
    torch.backends.cuda.matmul.allow_tf32 = False; torch.backends.cudnn.allow_tf32 = False
    torch.cuda.reset_peak_memory_stats()
    training = tensors(train, norm, anchors, seen, 'cuda')
    validating = tensors(validation, norm, anchors, seen, 'cuda')
    model = pilot.ColumnModel('transformer', inputs=185).cuda()
    initial_hash = hashlib.sha256(b''.join(v.detach().cpu().numpy().tobytes() for v in model.state_dict().values())).hexdigest()
    optimizer = torch.optim.AdamW(model.parameters(), lr=8e-4, weight_decay=1e-4)
    ym, ys = (torch.tensor(norm[k], device='cuda', dtype=torch.float32) for k in ('ym', 'yscale'))
    best, best_epoch, best_state, history = float('inf'), 0, None, []
    started = time.perf_counter(); steps = 0; presentations = 0; passes = 0; batches = iter(())
    for epoch in range(1, 161):
        model.train(); total = 0.; interval_presentations = 0
        for _ in range(26):
            ix = next(batches, None)
            if ix is None:
                batches = iter(torch.randperm(len(train), device='cuda').split(32)); passes += 1
                ix = next(batches)
            batch = pilot.subset(training, ix); optimizer.zero_grad(set_to_none=True)
            raw, branch = forward(model, batch, norm)
            value = pilot.loss((raw-ym)/ys, branch, batch, ym, ys)
            if not torch.isfinite(value): raise FloatingPointError('Nonfinite hybrid loss')
            value.backward(); nn.utils.clip_grad_norm_(model.parameters(), 1.); optimizer.step(); steps += 1
            total += value.detach().item()*len(ix)
            presentations += len(ix); interval_presentations += len(ix)
        model.eval()
        with torch.no_grad():
            raw, branch = forward(model, validating, norm)
            measures = accuracy.metrics(raw, branch, validating)
            score = measures['selectionScore'].mean().item()
        if not math.isfinite(score): raise FloatingPointError('Nonfinite selection score')
        history.append({'checkpoint': epoch, 'trainLoss': total/interval_presentations, 'selectionScore': score, 'steps': steps})
        if score < best: best, best_epoch, best_state = score, epoch, copy.deepcopy(model.state_dict())
        if epoch % 20 == 0: print(json.dumps({'dataset': args.dataset, 'seed': args.seed, **history[-1]}), flush=True)
    model.load_state_dict(best_state); model.eval()
    with torch.no_grad():
        raw, branch = forward(model, validating, norm)
        results = {k:v.cpu().numpy() for k,v in accuracy.metrics(raw, branch, validating).items()}
    torch.cuda.synchronize()
    report = dict(revision='hybrid-residual-training-v1', dataset=args.dataset, seed=args.seed,
        trainColumns=len(train), validationColumns=len(validation), bestEpoch=best_epoch, epochsRun=epoch,
        optimizerSteps=steps, samplePresentations=presentations, shuffledPassesStarted=passes,
        initialWeightsSha256=initial_hash, bestSelectionScore=best, trainingSeconds=time.perf_counter()-started,
        parameters=sum(p.numel() for p in model.parameters()), branchesSeen=seen,
        dataSha256=digest(args.data), planSha256=digest(args.plan), trainerSha256=digest(Path(__file__)),
        gpu=torch.cuda.get_device_name(), torchVersion=str(torch.__version__), cudaVersion=torch.version.cuda,
        precision='float32; TF32 disabled', peakAllocatedGpuBytes=torch.cuda.max_memory_allocated(),
        validation={k:stats(v) for k,v in results.items()}, history=history)
    args.output.mkdir(parents=True)
    torch.save(dict(state_dict=model.cpu().state_dict(), normalization=norm, report=report), args.output/'model.pt')
    (args.output/'report.json').write_text(json.dumps(report, indent=2, allow_nan=False)+'\n')
    (args.output/'validation.jsonl').write_text(''.join(json.dumps({'id':r['id'], **{k:float(v[i]) for k,v in results.items()}}, allow_nan=False)+'\n' for i,r in enumerate(validation)))
    print(json.dumps({'completed':args.dataset, 'seed':args.seed, 'score':best, 'seconds':report['trainingSeconds']}), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dataset', choices=['N','Nplus1'], required=True)
    parser.add_argument('--data', type=Path, required=True)
    parser.add_argument('--anchors', type=Path, nargs='+', required=True)
    parser.add_argument('--plan', type=Path, required=True)
    parser.add_argument('--seed', type=int, choices=SEEDS, required=True)
    parser.add_argument('--output', type=Path, required=True)
    run(parser.parse_args())
