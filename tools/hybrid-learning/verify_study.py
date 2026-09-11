"""Independent artifact and selection checks without refitting or solver calls."""
import argparse
import json
from pathlib import Path
import benchmark as study
from benchmark import ROOT, AREA
from prepare_transformer_data import digest, strict
from native_checkpoint_selection_v1 import freeze, info


def check_training():
    import torch
    plan=json.loads((AREA/'training-plan.json').read_text())
    norm=json.loads((AREA/'normalization.json').read_text())
    assert digest(AREA/'normalization.json')==plan['normalization']['sha256']
    for item in plan['sources']:
        assert digest(ROOT/item['path'])==item['sha256'],item['path']
    initial={};records=[]
    for dataset in ('N','Nplus1'):
        for seed in (20260910,20260911,20260912):
            name=f'{dataset}-{seed}';fit=AREA/'fits'/name;directory=AREA/'models'/name
            cp=torch.load(fit/'model.pt',map_location='cpu',weights_only=True)
            r=json.loads((fit/'report.json').read_text());doc=json.loads((directory/'model.json').read_text())
            assert r==cp['report'] and cp['normalization']==norm and doc['normalization']==norm
            assert r['optimizerSteps']==4160 and r['epochsRun']==160 and len(r['history'])==160
            chosen=min(r['history'],key=lambda item:(item['selectionScore'],item['steps']))
            assert chosen['steps']==r['bestEpoch']*26 and chosen['selectionScore']==r['bestSelectionScore']
            assert r['planSha256']==digest(AREA/'training-plan.json')
            assert doc['checkpointSha256']==digest(fit/'model.pt') and r['dataSha256']==plan['datasets'][dataset]['sha256']
            assert sum(v.numel() for v in cp['state_dict'].values())==89496
            for key,value in cp['state_dict'].items():
                assert doc['weights'][key]['shape']==list(value.shape)
                assert doc['weights'][key]['values']==value.numpy().ravel().tolist()
            precision=json.loads((directory/'precision-check.json').read_text())
            for key in ('model','fixtures','javaParity'):
                item=precision[key];assert digest(ROOT/item['path'])==item['sha256']
            native=json.loads((directory/'java-parity.json').read_text())
            assert native['passed'] and native['exactMasks'] and native['exactAnchorParity']
            assert native['baselineFailureRetained'] and native['baselineCancellationPropagated']
            assert native['scheduling']['terminated'] and native['scheduling']['distinctWorkerThreads']==10
            assert precision['maskDifferences']==0
            initial.setdefault(seed,[]).append(r['initialWeightsSha256']);records.append(name)
    assert all(len(set(values))==1 for values in initial.values())
    return dict(passed=True,models=records,pairedInitialHashes=initial,normalization=info(AREA/'normalization.json'),
        exactUpdates=4160,exactCheckpointWeights=True,parityBindingsVerified=True)


def check_selection():
    plan=study.verify_plan();selection=json.loads((AREA/'selection.json').read_text())
    assert selection['validationComplete'] and selection['testUsed'] is False
    assert selection['planSha256']==digest(study.PLAN) and selection['testSha256']==plan['test']['sha256']
    for item in selection['validationFiles']:
        assert digest(ROOT/item['path'])==item['sha256']
    records={};classical=set()
    for name in study.ORDER:
        blocks=[]
        for block in (1,2):
            rows,_=study.validate_run(AREA/'validation'/f'block-{block}'/name,plan,'validation',name)
            classical.update(r['id'] for r in rows if strict({**r,**r['current']}))
            blocks.append(dict(strictIds=sorted(r['id'] for r in rows if strict({**r,**r['neuralFirst']})),meanMillis=sum(r['neuralFirst']['ms'] for r in rows)/len(rows)))
        records[name]=dict(blocks=blocks,minimumStrict=min(len(b['strictIds']) for b in blocks),pooledMeanMillis=sum(b['meanMillis'] for b in blocks)/2)
    assert records==selection['records'] and sorted(classical)==selection['classicalUnionIds']
    for dataset in ('N','Nplus1'):
        names=[f'{dataset}-{seed}' for seed in (20260910,20260911,20260912)]
        expected=min(names,key=lambda n:(-records[n]['minimumStrict'],records[n]['pooledMeanMillis'],int(n.rsplit('-',1)[1])))
        assert selection['representatives'][dataset]==expected
        assert selection['selectedPipelines'][expected]==plan['models'][expected]
    for name,r in records.items():
        reference=records['incumbent']
        improve=all(len(a['strictIds'])>len(b['strictIds']) for a,b in zip(r['blocks'],reference['blocks']))
        preserve=all(classical<=set(b['strictIds']) for b in r['blocks'])
        faster=r['pooledMeanMillis']<=reference['pooledMeanMillis']
        assert selection['recommendations'][name]==dict(improvesBothBlocks=improve,preservesClassicalUnionBothBlocks=preserve,noPooledLatencyRegression=faster,eligibleReplacement=improve and preserve and faster)
    return dict(passed=True,selection=info(AREA/'selection.json'),representatives=selection['representatives'],allValidationJournalsRecomputed=True)


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('mode',choices=['training','selection']);a=p.parse_args()
    result=check_training() if a.mode=='training' else check_selection()
    freeze(AREA/f'{a.mode}-verification.json',result);print(json.dumps(result))
