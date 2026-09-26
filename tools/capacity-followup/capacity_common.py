"""Additive depth experiment; existing study sources and artifacts are read-only."""
import copy
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/trace-followup'))
import common as prior
from common import np, torch, pilot, hybrid, accuracy, read, read_rows, digest, freeze, info, strict, stats, canonical_input_hash

OUT = ROOT / 'build/neural-capacity-followup/v1'
SOURCE = ROOT / 'build/neural-trace-followup/v1'
BASE_NAME = 'F-20260911-s4160'
BASE_WEIGHTS_SHA = '7f909d025e12cbc7e8457b459adfbc63e48f52fb9e98ce45b91e64e84ddd1962'
TRAIN_SHA = '12573c376342389cf40731d68f92e2aacbb26306c84b959a522365b4a18bcc4b'
PREDECESSOR_RESULTS_SHA = 'aa46495c70e5f712a2493d9ae2ce4884a481045ca0ca939620c18af078568133'
ANCHORS = [ROOT / 'build/neural-hybrid-learning/v1' / name
           for name in ('N-anchors.jsonl', 'new-anchors.jsonl')]


def base_binding():
    item = read(SOURCE / 'models.json')[BASE_NAME]
    archived = read(ROOT / 'tools/trace-followup/cache-manifest.json')
    assert archived['archive']['sha256'] == PREDECESSOR_RESULTS_SHA
    entries = {entry['entry']: entry for entry in archived['entries']}
    assert item['weights']['sha256'] == BASE_WEIGHTS_SHA
    for entry in (item, item['weights'], item['checkpoint'], item['precisionCheck']):
        assert digest(ROOT / entry['path']) == entry['sha256'], entry['path']
        assert entries[entry['path']]['sha256'] == entry['sha256']
    document = read(ROOT / item['weights']['path'])
    assert document['anchorLayout'] == 'full' and document['outputConvention'] == 'absolute'
    assert read(ROOT / item['path'])['materialCompletion'] is False
    return item, document


def initial_model(layers):
    """Append pre-norm residual blocks with zero output projections: exact identity."""
    assert layers in (2, 4)
    _, document = base_binding()
    model = pilot.ColumnModel('transformer', inputs=185)
    state = {key: torch.tensor(value['values'], dtype=torch.float32).reshape(value['shape'])
             for key, value in document['weights'].items()}
    model.load_state_dict(state)
    # Both additions use the same fixed trained basis. No random new initialization differs by order seed.
    for _ in range(layers - 2):
        block = copy.deepcopy(model.blocks[1])
        with torch.no_grad():
            block.self_attn.out_proj.weight.zero_()
            block.self_attn.out_proj.bias.zero_()
            block.linear2.weight.zero_()
            block.linear2.bias.zero_()
        model.blocks.append(block)
    assert sum(p.numel() for p in model.parameters()) == {2: 89496, 4: 156440}[layers]
    return model


def forward(model, batch, norm):
    return prior.absolute_forward(model, batch, norm, 'full')


def state_digest(model):
    return prior.state_digest(model)


def normalization():
    _, document = base_binding()
    return copy.deepcopy(document['normalization'])


def dataset(rows, device='cpu'):
    _, document = base_binding()
    return hybrid.tensors(rows, normalization(), hybrid.load_anchors(ANCHORS), document['branchesSeen'], device)


def batches(count, updates, seed):
    generator = torch.Generator(device='cpu').manual_seed(seed)
    pending = iter(())
    for _ in range(updates):
        indices = next(pending, None)
        if indices is None:
            pending = iter(torch.randperm(count, generator=generator).split(32))
            indices = next(pending)
        yield indices
