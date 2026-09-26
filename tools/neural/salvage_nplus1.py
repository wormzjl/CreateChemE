"""Registered one-pass frozen-Transformer salvage; exact N plus novel certified TRAIN labels."""
import argparse, copy, json, hashlib, zipfile
from collections import Counter
from pathlib import Path
from prepare_transformer_data import read_rows, digest, strict, coverage, profile_difference
from prepare_generalized_evaluation import canonical_input_hash
ROOT=Path(__file__).resolve().parents[2]
AREA=ROOT/'build/neural-salvage/nplus1-v1'
CACHE=ROOT/'.neural-cache/salvage-nplus1-v1'
N_SOURCE=ROOT/'build/neural-transformer/data-v2/cases.jsonl'
MODEL=ROOT/'build/neural-hybrid/feasibility-v1/model.json'
N_SHA='ba6d746e1b51368395cc08aacaa1190cc232aa0ee6ba16bc289ffca29feb3930'
MODEL_SHA='7aa7eaa5ecbe4cea51a31bbe4724569e40900b128e98b1d69a6068e5e7d43e5e'
def freeze(path,value,rows=False):
    path.parent.mkdir(parents=True,exist_ok=True)
    with path.open('x',encoding='utf-8',newline='\n') as f:
        if rows:
            for r in value:f.write(json.dumps(r,sort_keys=True,allow_nan=False)+'\n')
        else:json.dump(value,f,sort_keys=True,indent=2,allow_nan=False);f.write('\n')
def info(p):return {'path':p.relative_to(ROOT).as_posix(),'sha256':digest(p),'bytes':p.stat().st_size}
def verify(plan):
    for r in plan['sources']+plan['code']+plan['frozen']:
        p=ROOT/r['path']
        if digest(p)!=r['sha256']:raise ValueError('Changed frozen file '+str(p))
def original():
    doc=json.loads((ROOT/'tools/neural/gen2-cache-manifest.json').read_text())
    sources=[]
    for entry in doc['files']:
        p=ROOT/doc['cacheRoot']/entry['cachedPath']
        if digest(p)!=entry['sha256'] or p.stat().st_size!=entry['bytes']:raise ValueError('Gen2 cache changed')
        sources.append(info(p))
    return doc,sources

def register():
    if AREA.exists() or CACHE.exists():raise FileExistsError('Preserve existing revision')
    doc,sources=original(); base=ROOT/doc['cacheRoot']/'study/design'
    candidates=read_rows(base/'candidate-matrix.jsonl'); matrix=read_rows(base/'matrix.jsonl'); excluded=read_rows(base/'exclusions.jsonl')
    assert (len(candidates),len(matrix),len(excluded))==(2809,2793,16)
    keys=[canonical_input_hash(r['input']) for r in matrix]
    assert len(set(keys))==len(matrix) and len({r['id'] for r in matrix})==len(matrix)
    assert digest(N_SOURCE)==N_SHA and digest(MODEL)==MODEL_SHA
    all_n=read_rows(N_SOURCE)
    n=[r for r in all_n if r['split']=='train' and r['labelProvenance']['eligibleForFitting'] and strict(r)]
    assert len(n)==805 and len({canonical_input_hash(r['input']) for r in n})==805
    n_by={canonical_input_hash(r['input']):r for r in all_n}
    assert all(n_by[k]['split']==r['split'] for k,r in zip(keys,matrix))
    # Verify the native solve and deadline helpers were copied without semantic edits.
    wrapper=(ROOT/'tools/neural/salvage_src/V3SalvageNplus1Probe.java').read_text()
    frozen=(ROOT/'tools/neural/V3ConcurrentColumnEvaluationProbe.java').read_text()
    for start,end in [('    private static Map<String, Object> run(','    private static Map<String, Object> residual('),('    private static V3SolveControl deadline(',None)]:
        helper=frozen[frozen.index(start):frozen.index(end) if end else len(frozen)]
        assert helper in wrapper
    AREA.mkdir(parents=True)
    freeze(AREA/'matrix.jsonl',matrix,True);freeze(AREA/'N.jsonl',n,True)
    with (AREA/'model.json').open('xb') as f:f.write(MODEL.read_bytes())
    codepaths=list((ROOT/'src/main/java/com/wormzjl/createcheme/science/column/v3').rglob('*.java'))
    codepaths+=list((ROOT/'tools/neural').glob('*.java'))
    codepaths += [ROOT/'tools/neural/salvage_src/V3SalvageNplus1Probe.java',ROOT/'tools/neural/salvage_nplus1.py',ROOT/'tools/neural/salvage_nplus1.gradle',ROOT/'tools/neural/prepare_transformer_data.py',ROOT/'tools/neural/prepare_gen3_data.py',ROOT/'tools/neural/prepare_generalized_evaluation.py',ROOT/'build.gradle']
    plan={'revision':'salvage-nplus1-v1','population':{'candidates':len(candidates),'admitted':len(matrix),'excluded':len(excluded),'folds':dict(Counter(r['split'] for r in matrix))},'N':805,
          'mode':'LNN_FIRST','workers':10,'deadlineMillis':30000,'neuralBudgetMillis':2000,'maximumIterations':16,
          'policy':'One request per original admitted input. Preserve all existing N labels byte-semantically; novel TRAIN strict same-input native labels only. All held-out folds audit-only. Material prior-root discrepancies quarantine novel labels. No outcome-based retries.',
          'rootTolerance':{'temperatureMaxK':0.1,'flowMaxOverFeed':1e-4,'wetAndBranchMustMatch':True},
          'sources':sources+[info(N_SOURCE),info(MODEL),info(ROOT/'tools/neural/gen2-cache-manifest.json')],
          'code':[info(p) for p in sorted(set(codepaths))],'frozen':[info(AREA/n) for n in ('matrix.jsonl','N.jsonl','model.json')]}
    freeze(AREA/'plan.json',plan)
    CACHE.mkdir(parents=True)
    # Pre-outcome registration and immutable assets survive Gradle clean.
    with zipfile.ZipFile(CACHE/'registration.zip','x',zipfile.ZIP_DEFLATED) as z:
        for p in AREA.iterdir():z.write(p,p.name)
        for p in codepaths:z.write(p,'code/'+p.relative_to(ROOT).as_posix())
    freeze(CACHE/'registration.json',{'planSha256':digest(AREA/'plan.json'),'archive':info(CACHE/'registration.zip')})
    print(json.dumps({'plan':info(AREA/'plan.json'),'population':plan['population'],'N':805},indent=2))

def analyze():
    plan=json.loads((AREA/'plan.json').read_text());verify(plan)
    run=json.loads((AREA/'attempt-001/run.json').read_text())
    assert run['complete'] and run['completed']==2793 and run['modelSha256']==MODEL_SHA and run['sourceSha256']==digest(AREA/'matrix.jsonl')
    matrix=read_rows(AREA/'matrix.jsonl');outcomes=read_rows(AREA/'attempt-001/evaluation.jsonl');n=read_rows(AREA/'N.jsonl')
    source={r['id']:r for r in matrix};assert len(outcomes)==len(source) and {r['id'] for r in outcomes}==set(source)
    existing={canonical_input_hash(r['input']):r for r in read_rows(N_SOURCE)}
    nkeys={canonical_input_hash(r['input']) for r in n};add=[];audit=[];conflicts=[];qualified=Counter();statuses=Counter();withheld=Counter();duplicate=0
    journal=info(AREA/'attempt-001/evaluation.jsonl')
    for row in sorted(outcomes,key=lambda r:r['id']):
        src=source[row['id']];key=canonical_input_hash(row['input'])
        assert key==canonical_input_hash(src['input']) and row['split']==src['split']
        statuses[row['split']+':'+row['status']]+=1
        ok=strict(row);differences=[]
        if ok:
            qualified[row['split']]+=1
            prior=existing[key]
            if strict(prior):
                d=profile_difference(prior,row)
                if d['temperatureMaxK']>.1 or d['flowMaxOverFeed']>1e-4 or d['wetDifferent'] or d['branchDifferent']:differences.append(d)
            if differences:conflicts.append({'id':row['id'],'canonicalInputSha256':key,'inN':key in nkeys,'differences':differences})
            if key in nkeys:duplicate+=1
            elif row['split']!='train':withheld[row['split']]+=1
            elif differences or prior.get('labelProvenance',{}).get('materialProfileDisagreement',False):withheld['train_root_quarantine']+=1
            else:
                label=copy.deepcopy(row)
                label['labelProvenance']={'revision':'salvage-nplus1-v1','canonicalInputSha256':key,'origin':'original_matrix_transformer_salvage','previousSplit':src['split'],'eligibleForFitting':True,'selected':{'journal':journal['path'],'journalSha256':journal['sha256'],'sourceCaseId':row['id'],'subresult':''},'qualifiedObservations':1,'materialProfileDisagreement':False,'modelSha256':MODEL_SHA,'planSha256':digest(AREA/'plan.json'),'inputSource':plan['frozen'][0],'seedSha256':hashlib.sha256(json.dumps(row['seed'],sort_keys=True,separators=(',',':'),allow_nan=False).encode()).hexdigest()}
                add.append(label)
        audit.append({'id':row['id'],'split':row['split'],'canonicalInputSha256':key,'strict':ok,'status':row['status'],'inN':key in nkeys,'materialRootDiscrepancy':bool(differences)})
    union=n+add
    assert union[:805]==n and len({canonical_input_hash(r['input']) for r in union})==len(union)
    assert all(r['split']=='train' and strict(r) and r['labelProvenance']['eligibleForFitting'] for r in union)
    heldout={canonical_input_hash(r['input']) for r in matrix if r['split']!='train'}
    assert not heldout.intersection(canonical_input_hash(r['input']) for r in union)
    freeze(AREA/'new-train.jsonl',add,True);freeze(AREA/'Nplus1.jsonl',union,True);freeze(AREA/'audit.jsonl',audit,True);freeze(AREA/'root-discrepancies.jsonl',conflicts,True)
    # Ready training case files retain the exact common validation/test records.
    nontrain=[r for r in read_rows(N_SOURCE) if r['split']!='train']
    freeze(AREA/'N-cases.jsonl',n+nontrain,True);freeze(AREA/'Nplus1-cases.jsonl',union+nontrain,True)
    summary={'revision':plan['revision'],'population':plan['population'],'N':len(n),'newTrain':len(add),'Nplus1':len(union),'strictByFold':dict(qualified),'withheldNewStrictByFold':dict(withheld),'strictExistingNObservations':duplicate,'materialRootDiscrepancies':len(conflicts),'statusByFold':dict(statuses),'newCoverage':coverage(add),'NCoverage':coverage(n),'Nplus1Coverage':coverage(union),'checks':{'sourceHashesVerified':len(plan['sources']),'codeHashesVerified':len(plan['code']),'exactNPrefix':True,'uniqueCanonicalInputs':True,'holdoutTrainingOverlap':0,'sameInputNativeLabels':True,'commonNontrainingRows':len(nontrain),'completeOnePass':True},'planSha256':digest(AREA/'plan.json'),'run':run,'files':[info(p) for p in sorted(AREA.rglob('*')) if p.is_file()]}
    freeze(AREA/'dataset-manifest.json',summary)
    with zipfile.ZipFile(CACHE/'campaign.zip','x',zipfile.ZIP_DEFLATED) as z:
        for p in sorted(AREA.rglob('*')):
            if p.is_file():z.write(p,p.relative_to(AREA).as_posix())
    freeze(CACHE/'campaign-manifest.json',{'archive':info(CACHE/'campaign.zip'),'datasetManifestSha256':digest(AREA/'dataset-manifest.json'),'N':len(n),'newTrain':len(add),'Nplus1':len(union)})
    print(json.dumps({k:summary[k] for k in ('N','newTrain','Nplus1','strictByFold','withheldNewStrictByFold','strictExistingNObservations','materialRootDiscrepancies','checks')},indent=2))
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('action',choices=['register','verify','analyze']);a=p.parse_args()
    if a.action=='register':register()
    elif a.action=='analyze':analyze()
    else:verify(json.loads((AREA/'plan.json').read_text()));print('Frozen sources and code verified')
