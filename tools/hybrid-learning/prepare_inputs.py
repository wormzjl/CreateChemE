"""Prepare anchor inputs using frozen TRAIN membership and original validation."""
import json
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'neural'))
from prepare_transformer_data import read_rows, strict

out = Path('build/neural-hybrid-learning/v1')
out.mkdir(parents=True, exist_ok=True)
rows = read_rows(Path('build/neural-transformer/data-v2/cases.jsonl'))
selected = [r for r in rows if r['labelProvenance']['eligibleForFitting'] or r['split']=='validation' and strict(r)]
assert len(selected)==973
with (out/'N-anchor-inputs.jsonl').open('x') as stream:
    for r in selected:
        stream.write(json.dumps({k:r[k] for k in ('id','split','input')}, sort_keys=True)+'\n')
print(json.dumps({'anchorInputs':len(selected)}))
