"""Freeze prospective inputs using only input identity and design geometry."""
import copy
import json
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'neural'))
import generalized_design as design
import historical_neural_inputs as historical
import prepare_gen3_holdouts as holdouts
from prepare_generalized_evaluation import canonical_input_hash
from prepare_transformer_data import read_rows, digest
from native_checkpoint_selection_v1 import freeze, info

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT/'build/neural-hybrid-learning/v1'


def main():
    known, populations, manifests = historical.collect(ROOT)
    paths = [ROOT/'build/neural-transformer/selection-v2/historical-input-hashes.json',
             ROOT/'build/neural-transformer/selection-v2/candidate-pool.jsonl']
    known.update(json.loads(paths[0].read_text()))
    known.update(canonical_input_hash(r['input']) for r in read_rows(paths[1]))
    baseline = json.loads(design.DEFAULT_BASELINE.read_text(encoding='utf-8-sig'))['input']
    pool, admitted, excluded, report = design.generate(baseline, 202609116)
    historical.new_pool(pool, known)
    holdouts.SELECTION_SEED = 'hybrid-residual-20260911-v1'
    selected = copy.deepcopy(holdouts.select_fresh(admitted))
    for row in selected:
        row['id'] = 'hybrid-fresh-'+str(row['id']).removeprefix('gd-')
        row['split'] = 'test'
        row['design'].update(trainingAllowed=False, origin='hybrid-residual-v1')
    historical.fresh_test(selected, known)
    freeze(OUT/'historical-input-hashes.json', sorted(known))
    freeze(OUT/'candidate-pool.jsonl', pool, True)
    freeze(OUT/'test.jsonl', selected, True)
    freeze(OUT/'test-registration.json', dict(generatorSeed=202609116, selectionSeed=holdouts.SELECTION_SEED,
        candidateRows=len(pool), excludedRows=len(excluded), selectedRows=len(selected),
        historicalUnique=len(known), historicalDependencies=[info(p) for p in paths],
        verifiedArchivePopulations=populations, verifiedManifests=manifests,
        pool=info(OUT/'candidate-pool.jsonl'), test=info(OUT/'test.jsonl'), generated=report))
    print(json.dumps({'freshInputs':len(selected),'excludedHistoricalInputs':len(known)}))


if __name__ == '__main__': main()
