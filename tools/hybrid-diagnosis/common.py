"""Shared paths and original sealed-source checks for additive diagnosis."""
import hashlib
import json
from pathlib import Path
import sys
import torch

ROOT=Path(__file__).resolve().parents[2]
AREA=ROOT/'build/neural-hybrid-learning/v1'
OUT=ROOT/'build/neural-hybrid-diagnosis/v1'
sys.path[:0]=[str(ROOT/'tools/hybrid-learning'),str(ROOT/'tools/neural')]
from native_checkpoint_selection_v1 import freeze,info
from prepare_transformer_data import read_rows,digest,strict,stats


def bindings():
    result={}
    for path in (ROOT/'tools/hybrid-learning/results-cache-manifest.json',
                 ROOT/'tools/neural/transformer-accuracy-cache-manifest.json'):
        manifest=json.loads(path.read_text())
        for item in manifest['entries']:
            result.setdefault(item['entry'],set()).add(item['sha256'])
    return result


def verify_frozen(path):
    path=Path(path);key=path.relative_to(ROOT).as_posix();sha=digest(path)
    assert sha in bindings().get(key,set()),'Not the sealed source: '+key
    return info(path)


def checkpoint(path):
    verify_frozen(path)
    return torch.load(path,map_location='cpu',weights_only=True)


def canonical_support(model,norm):
    """Same initial physical logits; different trainable parameter coordinates."""
    import copy
    updated=copy.deepcopy(norm)
    scale=model.output[1].weight.new_tensor(norm['dscale'][45:])
    mean=model.output[1].bias.new_tensor(norm['dm'][45:])
    with torch.no_grad():
        model.output[1].weight[45:].mul_(scale[:,None])
        model.output[1].bias[45:].mul_(scale).add_(mean)
    updated['dm'][45:]=[0.]*40;updated['dscale'][45:]=[1.]*40
    return updated
