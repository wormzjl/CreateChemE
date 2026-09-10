"""Freeze validation-selected pilot weights and TRAIN-only CPU parity fixtures."""
import json
from pathlib import Path
import hashlib
import numpy as np
import torch
import train_generalized as base
from train_transformer import ColumnModel, tensors
from prepare_transformer_data import read_rows, digest

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'build/neural-transformer/native-v1'


def write(path,value,rows=False):
    with path.open('x',encoding='utf-8',newline='\n') as f:
        f.write(''.join(json.dumps(r,sort_keys=True,allow_nan=False)+'\n' for r in value) if rows else json.dumps(value,sort_keys=True,allow_nan=False)+'\n')


def main():
    if OUT.exists(): raise FileExistsError('Export already frozen')
    rows=read_rows(ROOT/'build/neural-transformer/data-v2/cases.jsonl')
    train=[r for r in rows if r['labelProvenance']['eligibleForFitting']]
    validation=[r for r in rows if r['split']=='validation']
    assert len(validation)==405 and len(train)==805
    # Authored historical input domain; no new holdout outcomes or inputs read.
    domain=rows+read_rows(ROOT/'build/neural-gen3/fresh-design/candidate-pool.jsonl')
    global_inputs=np.vstack([base.global_features(r['input']) for r in domain])
    constraints=base.derive_design_constraints(domain)
    fixture=sorted(train,key=lambda r:r['input']['stageCount'])[::max(1,len(train)//12)][:12]
    for r in (max(train,key=lambda r:r['input']['stageCount']),next(r for r in train if r['seed']['branch']=='LIQUID_ONLY')):
        if r not in fixture: fixture.append(r)
    OUT.mkdir(parents=True)
    write(OUT/'validation.jsonl',validation,True)
    candidates={}
    for kind in ('transformer','mlp'):
        reports=[json.loads((ROOT/f'build/neural-transformer/pilot-{kind}-{seed}/report.json').read_text()) for seed in (20260910,20260911,20260912)]
        chosen=min(reports,key=lambda r:(r['bestValidationLoss'],r['seed']))
        checkpoint_path=ROOT/f"build/neural-transformer/pilot-{kind}-{chosen['seed']}/model.pt"
        cp=torch.load(checkpoint_path,map_location='cpu',weights_only=True)
        model=ColumnModel(kind); model.load_state_dict(cp['state_dict']); model.eval()
        norm=cp['normalization']
        doc={'featureRevision':'v3-column-transformer-1','modelType':kind,'modelId':f"tjl20-{kind}-{chosen['seed']}-native-v1",
            'packageId':train[0]['input']['packageId'],'propertyRevision':train[0]['seed']['propertyRevision'],
            'components':train[0]['input']['componentBasis']['componentIds'],
            'formulationRevisions':sorted({r['formulationRevision'] for r in train}),
            'branchesSeen':chosen['branchesSeen'],'presenceThreshold':.02,'traceFloorFraction':1e-10,
            'globalMin':global_inputs.min(0).tolist(),'globalMax':global_inputs.max(0).tolist(),
            'designConstraints':constraints,'normalization':norm,
            'weights':{k:{'shape':list(v.shape),'values':v.detach().numpy().ravel().tolist()} for k,v in model.state_dict().items()},
            'checkpointSha256':digest(checkpoint_path),'dataSha256':chosen['dataSha256'],
            'selection':'Lowest offline original-validation objective within architecture; no native or fresh-test outcomes used.'}
        directory=OUT/kind; directory.mkdir()
        write(directory/'model.json',doc)
        batch=tensors(fixture,norm,'cpu')
        with torch.no_grad():
            fp,bp=model(batch['x'],batch['g'],batch['valid'])
            model=model.double()
            dp,db=model(batch['x'].double(),batch['g'].double(),batch['valid'])
        # Recompute features in float64, avoiding a hidden float32-normalization
        # rounding reference when validating Java's double arithmetic.
        fixtures=[]
        for row in fixture:
            g=base.global_features(row['input']); x=base.node_features(row['input'],'TWO_PHASE')[:,:-3]
            xx=torch.tensor((x-norm['xm'])/norm['xscale'],dtype=torch.float64)[None]
            gg=torch.tensor((g-norm['gm'])/norm['gscale'],dtype=torch.float64)[None]
            with torch.no_grad(): p,b=model(xx,gg,torch.ones((1,len(x)),dtype=torch.bool))
            fixtures.append({'id':row['id'],'split':'train','input':row['input'],'global':g.tolist(),'nodes':x.tolist(),
                'raw':(p[0].numpy()*norm['yscale']+norm['ym']).tolist(),'branchLogits':b[0].tolist()})
        write(directory/'fixture.json',fixtures)
        candidates[kind]={'modelPath':(directory/'model.json').relative_to(ROOT).as_posix(),'modelSha256':digest(directory/'model.json'),
            'seed':chosen['seed'],'offlineValidationLoss':chosen['bestValidationLoss'],
            'cpuFloat32VsFloat64MaximumStandardizedDifference':float((fp.double()-dp).abs().max()),
            'cpuFloat32VsFloat64MaximumBranchDifference':float((bp.double()-db).abs().max())}
    for name,path in [('gen3-factorized','build/neural-gen3/factorized-v1/factorized-model.json'),('nearest-k1','build/neural-gen3/nearest-k1/model.json')]:
        candidates[name]={'modelPath':path,'modelSha256':digest(ROOT/path)}
    write(OUT/'candidates.json',{'revision':'transformer-native-protocol-v1','candidates':candidates,'validationCount':405,
        'validationSha256':digest(OUT/'validation.jsonl'),'workers':10,'candidateMillis':10000,'iterations':16,'deadlineSeconds':30,
        'selectionRule':'Select transformer versus matched MLP by strict-qualified native validation count, then artifact bytes, then model id. Frozen Gen3 controls remain controls.',
        'freshTestCount':252,'freshBenchmarkCount':64,'freshTestUsed':False})
    print(json.dumps(candidates,indent=2))


if __name__=='__main__': main()
