"""Candidate TRAIN-only log-flow margin; no deployment rule is changed."""
import math
import torch
from torch.nn import functional as F

TRACE_FLOOR=1e-10


def prepruning_log_flows(raw,batch):
    """Unpruned all-active-component flow surrogate, before presence and trace masks.

    The existing presence BCE still trains presence logits. The surrogate cannot
    be called a differentiable copy of the hard decoder: presence normalization
    is intentionally absent. Base phase-total loss handles nonpositive totals.
    All quantities are flow divided by total authored feed, with natural logs.
    """
    c=batch['feed'].shape[-1];active=batch['feed'][:,None,:]>0;phases=[]
    for phase in range(2):
        log_total=torch.expm1(raw[...,1+phase].clamp(0,math.log1p(1000))).clamp_min(1e-30).log()
        logits=raw[...,3+phase*c:3+(phase+1)*c]+batch['feed'][:,None,:].clamp_min(1e-30).log()
        logprob=logits.masked_fill(~active,-1e30).log_softmax(-1)
        phases.append(log_total[...,None]+logprob)
    return torch.stack(phases,dim=2)


def trace_margin(raw,batch):
    """Column-balanced squared shortfall in decades, on reference-present entries.

    The target margin ends at min(reference flow, ten times the unchanged floor).
    Reference structural zeros, inactive feeds and padding contribute zero. The
    caller supplies a prospectively fixed coefficient and gradient safeguards.
    """
    floor=batch['feed'][:,None,None,:].clamp_min(1e-12)*TRACE_FLOOR
    target=batch['flowTarget'];mask=(target>=floor)&(batch['feed'][:,None,None,:]>0)&batch['valid'][...,None,None]
    wanted=torch.minimum(target.clamp_min(1e-30).log(),(10*floor).log())
    shortfall=F.relu((wanted-prepruning_log_flows(raw,batch))/math.log(10))
    penalty=torch.where(mask,shortfall.square(),torch.zeros_like(shortfall))
    per_column=penalty.sum((1,2,3))/mask.sum((1,2,3)).clamp_min(1)
    return per_column.mean(),dict(referenceEntries=mask.sum(),columnsWithEntries=mask.any(-1).any(-1).any(-1).sum(),
        meanShortfallDecades=torch.where(mask,shortfall,torch.zeros_like(shortfall)).sum()/mask.sum().clamp_min(1),
        activeShortfallEntries=((shortfall>0)&mask).sum())
