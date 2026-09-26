"""Additive follow-up experiment primitives; prior study files remain immutable."""
import hashlib
import json
from pathlib import Path
import sys

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'build/neural-trace-followup/v1'
sys.path[:0]=[str(ROOT/'tools/hybrid-learning'),str(ROOT/'tools/neural')]
import numpy as np
import torch
import train_transformer as pilot
import train_transformer_accuracy as accuracy
import train_hybrid as hybrid
from prepare_transformer_data import read_rows,digest,strict,stats
from prepare_generalized_evaluation import canonical_input_hash
from native_checkpoint_selection_v1 import freeze,info

INCUMBENT=ROOT/'build/neural-hybrid/feasibility-v1/model.json'
INCUMBENT_SHA='7aa7eaa5ecbe4cea51a31bbe4724569e40900b128e98b1d69a6068e5e7d43e5e'
CURATED=ROOT/'build/training-curation/v1/selected.jsonl'
COMPACT_ANCHOR_INDICES=[0,1,2]


def read(path):return json.loads(Path(path).read_text(encoding='utf-8'))


def incumbent_document():
    assert digest(INCUMBENT)==INCUMBENT_SHA
    return read(INCUMBENT)


def incumbent_model(inputs=96):
    """Copy the incumbent exactly; additional embedding columns start at zero."""
    document=incumbent_document();model=pilot.ColumnModel('transformer',inputs=inputs)
    state={k:torch.tensor(v['values'],dtype=torch.float32).reshape(v['shape']) for k,v in document['weights'].items()}
    if inputs!=96:
        extended=torch.zeros((64,inputs),dtype=state['embed.weight'].dtype)
        extended[:,:96]=state['embed.weight'];state['embed.weight']=extended
    model.load_state_dict(state);return model


def state_digest(model):
    return hashlib.sha256(b''.join(v.detach().cpu().contiguous().numpy().tobytes() for v in model.state_dict().values())).hexdigest()


def absolute_forward(model,batch,norm,layout='plain'):
    """Absolute incumbent output coordinates; anchors augment inputs only."""
    if layout=='plain':pred,branch=model(batch['x'],batch['g'],batch['valid'])
    else:
        assert layout in ('full','compact')
        branch=model.branch(batch['g'])
        chosen=branch.masked_fill(~batch['branchAllowed'],-torch.inf).argmax(-1)
        index=torch.arange(len(chosen),device=chosen.device)
        anchor=batch['anchors'][index,chosen];available=batch['available'][index,chosen]
        normalized=(anchor-anchor.new_tensor(norm['bm']))/anchor.new_tensor(norm['bscale'])
        if layout=='compact':normalized=normalized[...,COMPACT_ANCHOR_INDICES]
        onehot=torch.nn.functional.one_hot(chosen,3).to(anchor.dtype)[:,None,:].expand(-1,anchor.shape[1],-1)
        joined=torch.cat((batch['x'],normalized,onehot,available),-1)
        pred,_=model(joined,batch['g'],batch['valid'])
    return pred*pred.new_tensor(norm['yscale'])+pred.new_tensor(norm['ym']),branch
