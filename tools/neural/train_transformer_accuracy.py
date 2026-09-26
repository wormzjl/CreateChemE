"""Frozen transformer accuracy ablations; unchanged native inference architecture."""
import argparse
import copy
import json
import math
from pathlib import Path
import time

import numpy as np
import torch
from torch import nn
from torch.nn import functional as F

import train_transformer as pilot
import train_generalized as base
import train_gen3_factorized as factor
from prepare_transformer_data import read_rows, digest, strict, stats

ARMS = {
    'baseline': (0., 1e-4, False, 0.),
    'regularized-05': (.05, 1e-3, True, 0.),
    'regularized-10': (.10, 1e-3, True, 0.),
    'decoded': (0., 1e-4, False, 2.),
    'regularized-05-decoded': (.05, 1e-3, True, 2.),
    'regularized-10-decoded': (.10, 1e-3, True, 2.),
}
SEEDS = (20260910, 20260911, 20260912)


def model_for(dropout):
    model = pilot.ColumnModel('transformer')
    for block in model.blocks:
        block.self_attn.dropout = dropout
        for module in block.modules():
            if isinstance(module, nn.Dropout):
                module.p = dropout
    return model


def tensors(rows, norm, device):
    batch = pilot.tensors(rows, norm, device)
    shape = (*batch['valid'].shape, 2, batch['feed'].shape[-1])
    flow = np.zeros(shape, np.float32)
    temperatures = np.zeros(shape[:2], np.float32)
    fixed = np.full(len(rows), np.nan, np.float32)
    branch_allowed = np.ones((len(rows), 3), bool)
    branch_allowed[:, base.BRANCHES.index('VAPOR_ONLY')] = False
    for i, row in enumerate(rows):
        count = row['input']['stageCount'] + 2
        feed = sum(row['input']['feedComponentMolarFlowsMolPerSecond'])
        flow[i, :count, 0] = np.asarray(row['seed']['liquid']) / feed
        flow[i, :count, 1] = np.asarray(row['seed']['vapor']) / feed
        temperatures[i, :count] = row['seed']['temperatures']
        for spec in row['input']['specifications']:
            if 'kelvin' in spec:
                fixed[i] = spec['kelvin']
            if spec.get('ratio', 0) > 0:
                branch_allowed[i, 2] = False
    batch.update(flowTarget=torch.tensor(flow, device=device),
                 temperatureTarget=torch.tensor(temperatures, device=device),
                 fixedTemperature=torch.tensor(fixed, device=device),
                 branchAllowed=torch.tensor(branch_allowed, device=device))
    return batch


def decode(raw, branch_logits, batch):
    """Hard deployment support/trace rules, differentiable within each active set.

    Clamps protect training arithmetic. Invalid raw outputs are separately flagged
    and penalized in selection; they are never advertised as valid predictions.
    Presence learning continues through the original BCE term, not through masks.
    """
    c = batch['feed'].shape[-1]
    active = batch['feed'][:, None, :] > 0
    branches = branch_logits.masked_fill(~batch['branchAllowed'], -torch.inf).argmax(-1)
    floor = batch['feed'][:, None, :].clamp_min(1e-12) * factor.TRACE_FLOOR
    phases = []
    for phase in range(2):
        offset, support = 3+phase*c, 5+2*c+phase*c
        total = torch.expm1(raw[..., 1+phase].clamp(0, float(factor.MAX_LOG_TOTAL)))
        top_allowed = branches != base.BRANCHES.index('VAPOR_ONLY' if phase == 0 else 'LIQUID_ONLY')
        boundary = torch.ones_like(total)
        boundary[:, 0] = top_allowed.to(total.dtype)
        total = total * boundary
        logits = raw[..., offset:offset+c] + batch['feed'][:, None, :].clamp_min(1e-30).log()
        logits = logits.masked_fill(~active, -torch.inf)
        present = (raw[..., support:support+c] >= math.log(.02/.98)) & active
        fallback = F.one_hot(logits.argmax(-1), c).bool()
        present = torch.where(present.any(-1, keepdim=True), present, fallback)
        probability = logits.masked_fill(~present, -torch.inf).softmax(-1)
        flow = probability * total[..., None]
        flow = torch.where(flow >= floor, flow, torch.zeros_like(flow))
        retained = flow.sum(-1, keepdim=True)
        flow = flow * (total[..., None] / retained.clamp_min(1e-30))
        phases.append(flow)
    temperature = raw[..., 0].clone()
    temperature[:, 0] = torch.where(batch['fixedTemperature'].isfinite(),
                                  batch['fixedTemperature'], temperature[:, 0])
    invalid_node = (~raw.isfinite().all(-1) | (raw[..., 0] < 100) | (raw[..., 0] > 1500)
                    | (raw[..., 1:3] > float(factor.MAX_LOG_TOTAL)).any(-1)
                    | (raw[..., 3:3+2*c].abs() > 120).any(-1)
                    | (raw[..., 5+2*c:].abs() > 120).any(-1)
                    | (raw[..., 3+2*c] > 30))
    invalid = (invalid_node & batch['valid']).any(-1)
    return temperature, torch.stack(phases, dim=2), branches, invalid


def column_mean(node, valid):
    return (node * valid).sum(1) / valid.sum(1)


def decoded_flow_loss(raw, branches, batch):
    _, flows, _, _ = decode(raw, branches, batch)
    errors = F.smooth_l1_loss(flows, batch['flowTarget'], reduction='none', beta=.05)
    return column_mean(errors.sum(-1).mean(-1), batch['valid']).mean()


def metrics(raw, branches, batch):
    temperature, flows, predicted_branch, invalid = decode(raw, branches, batch)
    valid = batch['valid']
    error = (flows - batch['flowTarget']).abs()
    result = {'temperatureMaeK': column_mean((temperature-batch['temperatureTarget']).abs(), valid),
              'branchError': (predicted_branch != batch['labels']).to(raw.dtype),
              'invalidPrediction': invalid.to(raw.dtype)}
    floor = batch['feed'][:, None, None, :].clamp_min(1e-12)*factor.TRACE_FLOOR
    target = batch['flowTarget']
    target_fraction = target / target.sum(-1, keepdim=True).clamp_min(1e-30)
    trace = ((target >= floor) & (target_fraction <= 1e-4)
             & (batch['feed'][:, None, None, :] > 0) & valid[..., None, None])
    log_error = (flows.clamp_min(floor).log10() - target.clamp_min(floor).log10()).abs()
    result['traceLog10Mae'] = (log_error*trace).sum((1, 2, 3))/trace.sum((1, 2, 3)).clamp_min(1)
    result['traceEntries'] = trace.sum((1, 2, 3)).to(raw.dtype)
    for i, name in enumerate(('liquid', 'vapor')):
        result[name+'TotalMaeOverFeed'] = column_mean((flows[:, :, i].sum(-1)-target[:, :, i].sum(-1)).abs(), valid)
        result[name+'ComponentL1OverFeed'] = column_mean(error[:, :, i].sum(-1), valid)
    result['selectionScore'] = (result['temperatureMaeK']/25
        + .5*(result['liquidTotalMaeOverFeed']+result['vaporTotalMaeOverFeed'])
        + .5*(result['liquidComponentL1OverFeed']+result['vaporComponentL1OverFeed'])
        + .02*result['traceLog10Mae']+.25*result['branchError']+10*result['invalidPrediction'])
    return result


def run(args):
    if args.output.exists():
        raise FileExistsError('Accuracy runs are immutable; choose a new directory')
    from transformer_accuracy_study import verify_plan
    plan = verify_plan()
    if args.seed not in SEEDS or args.arm not in ARMS:
        raise ValueError('Unregistered experiment')
    torch.set_num_threads(4)
    torch.manual_seed(args.seed); np.random.seed(args.seed)
    if not torch.cuda.is_available():
        raise RuntimeError('CUDA is required for the registered accuracy study')
    torch.cuda.manual_seed_all(args.seed)
    torch.backends.cuda.matmul.allow_tf32 = False
    torch.backends.cudnn.allow_tf32 = False
    torch.cuda.reset_peak_memory_stats()
    rows = read_rows(Path(plan['trainingData']['path']))
    train = [r for r in rows if r['split']=='train' and r['labelProvenance']['eligibleForFitting']]
    validation = [r for r in rows if r['split']=='validation' and strict(r)]
    assert len(train)==805 and len(validation)==168 and all(strict(r) for r in train)
    norm = pilot.normalization(train)
    training, validating = tensors(train, norm, 'cuda'), tensors(validation, norm, 'cuda')
    ym, ys = (torch.tensor(norm[k], device='cuda') for k in ('ym', 'yscale'))
    dropout, decay, cosine, flow_weight = ARMS[args.arm]
    model = model_for(dropout).cuda()
    optimizer = torch.optim.AdamW(model.parameters(), lr=8e-4, weight_decay=decay)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=160, eta_min=8e-5) if cosine else None
    best, best_epoch, best_state, history = float('inf'), 0, None, []
    started = time.perf_counter()
    for epoch in range(1, 161):
        model.train(); order = torch.randperm(len(train), device='cuda'); total = 0.
        for ix in order.split(32):
            batch = pilot.subset(training, ix); optimizer.zero_grad(set_to_none=True)
            pred, branch = model(batch['x'], batch['g'], batch['valid'])
            value = pilot.loss(pred, branch, batch, ym, ys)
            if flow_weight:
                value = value + flow_weight*decoded_flow_loss(pred*ys+ym, branch, batch)
            if not torch.isfinite(value):
                raise FloatingPointError('Nonfinite training loss')
            value.backward(); nn.utils.clip_grad_norm_(model.parameters(), 1.); optimizer.step()
            total += value.detach().item()*len(ix)
        model.eval()
        with torch.no_grad():
            pred, branch = model(validating['x'], validating['g'], validating['valid'])
            measures = metrics(pred*ys+ym, branch, validating)
            score = measures['selectionScore'].mean().item()
            original = pilot.loss(pred, branch, validating, ym, ys).item()
        if not math.isfinite(score):
            raise FloatingPointError('Nonfinite validation metric')
        history.append({'epoch': epoch, 'trainLoss': total/len(train), 'selectionScore': score,
                        'originalValidationLoss': original, 'learningRate': optimizer.param_groups[0]['lr']})
        if score < best:
            best, best_epoch, best_state = score, epoch, copy.deepcopy(model.state_dict())
        if scheduler:
            scheduler.step()
        if epoch % 20 == 0:
            print(json.dumps({'arm': args.arm, 'seed': args.seed, **history[-1]}), flush=True)
        if epoch-best_epoch >= 40:
            break
    model.load_state_dict(best_state); model.eval()
    with torch.no_grad():
        pred, branch = model(validating['x'], validating['g'], validating['valid'])
        results = {k:v.cpu().numpy() for k,v in metrics(pred*ys+ym, branch, validating).items()}
    torch.cuda.synchronize()
    report = {'revision': 'transformer-accuracy-v1', 'arm': args.arm, 'kind': 'transformer', 'seed': args.seed,
              'config': {'dropout': dropout, 'weightDecay': decay, 'cosineSchedule': cosine, 'decodedFlowWeight': flow_weight},
              'trainColumns': len(train), 'validationColumns': len(validation), 'bestEpoch': best_epoch,
              'epochsRun': epoch, 'bestSelectionScore': best, 'trainingSeconds': time.perf_counter()-started,
              'parameters': sum(p.numel() for p in model.parameters()), 'device': 'cuda',
              'gpu': torch.cuda.get_device_name(), 'torchVersion': str(torch.__version__),
              'cudaVersion': torch.version.cuda, 'precision': 'float32; TF32 disabled',
              'peakAllocatedGpuBytes': torch.cuda.max_memory_allocated(), 'branchesSeen': [True, True, False],
              'dataSha256': plan['trainingData']['sha256'], 'trainerSha256': digest(Path(__file__)),
              'validation': {k:stats(v) for k,v in results.items()}, 'history': history}
    args.output.mkdir(parents=True)
    torch.save({'state_dict': model.cpu().state_dict(), 'normalization': norm, 'kind': 'transformer', 'report': report}, args.output/'model.pt')
    (args.output/'report.json').write_text(json.dumps(report, indent=2, allow_nan=False)+'\n', encoding='utf-8')
    predictions = [{'id': r['id'], **{k:float(v[i]) for k,v in results.items()}} for i,r in enumerate(validation)]
    (args.output/'validation.jsonl').write_text(''.join(json.dumps(r, allow_nan=False)+'\n' for r in predictions), encoding='utf-8')
    print(json.dumps({'completed': args.arm, 'seed': args.seed, 'score': best, 'seconds': report['trainingSeconds']}), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--arm', choices=ARMS, required=True)
    parser.add_argument('--seed', type=int, required=True)
    parser.add_argument('--output', type=Path, required=True)
    run(parser.parse_args())
