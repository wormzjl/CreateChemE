"""Verify complete acquisition and freeze matched training inputs/statistics."""
import argparse
import json
from pathlib import Path
from collections import Counter
import train_hybrid as training
from prepare_transformer_data import read_rows, digest, strict
from prepare_generalized_evaluation import canonical_input_hash
from native_checkpoint_selection_v1 import freeze, info
import salvage_nplus1 as salvage

ROOT = Path(__file__).resolve().parents[2]
AREA = ROOT/'build/neural-hybrid-learning/v1'
ACQ = ROOT/'build/neural-salvage/nplus1-v1'


def verify_data():
    salvage.verify(json.loads((ACQ/'plan.json').read_text()))
    run = json.loads((ACQ/'attempt-001/run.json').read_text())
    check = json.loads((ACQ/'execution-checks.json').read_text())
    assert check['passed'] and check['journalSha256']==digest(ACQ/'attempt-001/evaluation.jsonl')
    assert run['complete'] and run['completed']==2793
    assert (run['workers'],run['deadlineMillis'],run['neuralBudgetMillis'],run['maximumIterations'])==(10,30000,2000,16)
    assert run['scheduling']==dict(submitted=2793,completed=2793,maximumInFlight=10,maximumActive=10,distinctWorkerThreads=10,terminated=True)
    manifest = json.loads((ACQ/'certified-dataset-manifest.json').read_text())
    data = {}
    for name, record in manifest['trainingFiles'].items():
        path = ROOT/record['path']; assert digest(path)==record['sha256']
        data[name] = read_rows(path)
    original = read_rows(ROOT/'build/neural-transformer/data-v2/cases.jsonl')
    n = [r for r in original if r['split']=='train' and r['labelProvenance']['eligibleForFitting']]
    old_nontrain = [r for r in original if r['split']!='train']
    assert len(n)==805
    for name, rows in data.items():
        assert rows[:805]==n
        assert [r for r in rows if r['split']!='train']==old_nontrain
        assert len({canonical_input_hash(r['input']) for r in rows})==len(rows)
        assert all(strict(r) and r['labelProvenance']['eligibleForFitting'] for r in rows if r['split']=='train')
    novel = [r for r in data['Nplus1'][805:] if r['split']=='train']
    assert len(novel)==manifest['newTrain']
    for row in novel:
        for key in ('nativeRun','nativeJournal'):
            rec=row['labelProvenance'][key]; assert digest(ROOT/rec['path'])==rec['sha256']
    return manifest, data, n, novel


def main(mode):
    manifest, data, n, novel = verify_data()
    if mode=='inputs':
        freeze(AREA/'new-anchor-inputs.jsonl', [{k:r[k] for k in ('id','split','input')} for r in novel], True)
        print(json.dumps({'newNativeAnchorInputs':len(novel)})); return
    paths = [AREA/'N-anchors.jsonl', AREA/'new-anchors.jsonl']
    anchors = training.load_anchors(paths)
    norm = training.normalization(n, anchors)
    freeze(AREA/'normalization.json', norm)
    datasets = {name:{**record,'trainColumns':sum(r['split']=='train' for r in data[name])}
                for name,record in manifest['trainingFiles'].items()}
    sources = [ROOT/'tools/hybrid-learning/train_hybrid.py', ROOT/'tools/hybrid-learning/prepare_training.py',
               ROOT/'tools/hybrid-learning/protocol.md', ROOT/'tools/hybrid-learning/java/V3HybridBaseline.java']
    sources += [ROOT/'tools/neural'/name for name in ('train_transformer.py','train_transformer_accuracy.py',
               'train_generalized.py','train_gen3_factorized.py','prepare_transformer_data.py')]
    plan = dict(revision='hybrid-residual-training-v1', datasets=datasets,
        anchors={p.relative_to(ROOT).as_posix():digest(p) for p in paths}, normalization=info(AREA/'normalization.json'),
        certifiedDataset=info(ACQ/'certified-dataset-manifest.json'), executionCheck=info(ACQ/'execution-checks.json'),
        prospectiveTest=info(AREA/'test-registration.json'), sources=[info(p) for p in sources],
        training=dict(seeds=list(training.SEEDS), updates=4160, evaluationEveryUpdates=26, learningRate=.0008,
            weightDecay=.0001,batchSize=32,dropout=0,gradientClip=1,normalizationSource='N',
            branchConditioning='classifier-selected in training, validation and native inference',
            architecture=[185,64,2,4,128,85],parameters=89496),
        counts=dict(N=len(n),new=len(novel),Nplus1=len(n)+len(novel),certifiedValidation=168,fullValidation=405,freshTest=252),
        nativeAnchorAvailability={name:dict(Counter(anchors[r['id']][r['seed']['branch']]['available'] for r in rows if r['split']=='train')) for name,rows in data.items()})
    freeze(AREA/'training-plan.json', plan)
    print(json.dumps({'registered':plan['counts'],'trainingPlanSha256':digest(AREA/'training-plan.json')}))


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('mode',choices=['inputs','freeze']);main(p.parse_args().mode)
