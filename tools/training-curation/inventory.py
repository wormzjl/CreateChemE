"""TRAIN-only certification, coverage and descriptor inventory; no subset selection."""
import os
for variable in ('OPENBLAS_NUM_THREADS','MKL_NUM_THREADS','OMP_NUM_THREADS'):
    os.environ.setdefault(variable,'4')
from collections import Counter
import hashlib
import json
from pathlib import Path
import sys
import numpy as np

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'build/training-curation/v1'
sys.path.insert(0,str(ROOT/'tools/neural'))
from prepare_transformer_data import strict,stats,digest
from prepare_generalized_evaluation import canonical_input_hash
from native_checkpoint_selection_v1 import freeze,info
import train_generalized as base
import train_gen3_factorized as factor


def load_pool():
    manifest=json.loads((ROOT/'tools/neural/salvage_dataset_manifest.json').read_text(encoding='utf-8'))
    source=manifest['trainingFiles']['Nplus1'];path=ROOT/source['path']
    assert digest(path)==source['sha256']
    raw=path.read_bytes().splitlines(keepends=True);pool=[];other=[]
    for line in raw:
        r=json.loads(line)
        if r['split']=='train':
            assert r['labelProvenance']['eligibleForFitting'] is True
            pool.append((r,line))
        else:other.append((r,line))
    assert len(pool)==906 and len(other)==835
    return pool,other,source


def band(n):
    return next(label for low,high,label in [(2,8,'2-8'),(9,16,'9-16'),(17,32,'17-32'),(33,48,'33-48'),(49,64,'49-64')] if low<=n<=high)


def audit(row):
    reasons=[]
    try:
        if not strict(row):reasons.append('not_strictly_certified')
    except (ValueError,KeyError,TypeError) as e:reasons.append('strict_evidence:'+str(e))
    i=row['input'];s=row['seed'];n=i['stageCount']+2;c=len(i['feedComponentMolarFlowsMolPerSecond'])
    if canonical_input_hash(i)!=row['labelProvenance']['canonicalInputSha256']:reasons.append('canonical_provenance_mismatch')
    if canonical_input_hash(i)!=canonical_input_hash(s['input']):reasons.append('seed_input_mismatch')
    for field,shape in [('liquid',(n,c)),('vapor',(n,c)),('temperatures',(n,)),('freeWater',(n,)),('wetTrays',(n,))]:
        a=np.asarray(s[field]);
        if a.shape!=shape:reasons.append('shape:'+field)
        if not np.isfinite(a).all():reasons.append('nonfinite:'+field)
        if field in ('liquid','vapor','freeWater') and (a<0).any():reasons.append('negative:'+field)
    if row['labelProvenance'].get('materialProfileDisagreement'):reasons.append('recorded_root_disagreement')
    if s['branch']=='LIQUID_ONLY' and np.any(np.asarray(s['vapor'])[0]!=0):reasons.append('liquid_only_vapor_outlet')
    if s['branch']=='VAPOR_ONLY' and np.any(np.asarray(s['liquid'])[0]!=0):reasons.append('vapor_only_liquid_outlet')
    return reasons


def descriptors(row):
    i=row['input'];s=row['seed'];n=i['stageCount'];feed=np.asarray(i['feedComponentMolarFlowsMolPerSecond']);total=feed.sum()
    global_x=base.global_features(i);target,_=factor.targets(row)
    positions=np.r_[0,np.linspace(1,n,9),n+1]
    sample=lambda values:np.vstack([np.interp(positions,np.arange(n+2),values[:,j]) for j in range(values.shape[1])]).T
    logmix=np.log(np.maximum(feed/total,1e-30));logmix-=logmix.mean()
    liquid=np.asarray(s['liquid']);vapor=np.asarray(s['vapor']);temperature=np.asarray(s['temperatures'])
    threshold=np.maximum(feed,total*1e-12)*factor.TRACE_FLOOR
    ratios=np.concatenate([liquid/threshold,vapor/threshold]).ravel()
    present=np.stack((liquid>=threshold,vapor>=threshold),axis=1)
    above=ratios[ratios>=1]
    reflux=next(v['ratio'] for v in i['specifications'] if 'ratio' in v)
    recovery=np.r_[(liquid[0]/(1+reflux)+vapor[0])/feed,liquid[-1]/feed]
    views=dict(input_operating=global_x[:13],input_mixture=logmix,input_equipment=np.r_[global_x[13:17],global_x[17+len(feed):]],
        profile_temperature=sample(temperature[:,None]).ravel(),profile_totals=sample(target[:,1:3]).ravel(),
        profile_composition=sample(target[:,3:43]).ravel(),profile_support=sample(present.reshape(n+2,-1).astype(float)).ravel(),
        profile_recovery=recovery)
    quantities=dict(stageCount=n,feedTotalMolPerSecond=float(total),feedTemperatureKelvin=i['feedTemperatureKelvin'],
        topPressurePascal=i['topPressurePascal'],columnPressureDropPascal=(n-1)*i['stagePressureDropPascal'],
        feedStageFraction=i['feedStageNumber']/(n+1),condenserTemperatureKelvin=float(global_x[6]),refluxRatio=reflux,
        reboilerDutyPerFeedJoulesPerMol=float(global_x[8]*1000),steamToFeedRatio=float(global_x[9]),
        heatDutyPerFeedJoulesPerMol=float(global_x[11]*1000),sideDrawToFeedRatio=float(global_x[12]),
        minimumTemperatureKelvin=float(temperature.min()),maximumTemperatureKelvin=float(temperature.max()),
        temperatureSpanKelvin=float(np.ptp(temperature)),maximumAdjacentTemperatureJumpKelvin=float(np.abs(np.diff(temperature)).max()),
        maximumLiquidOverFeed=float(liquid.sum(1).max()/total),maximumVaporOverFeed=float(vapor.sum(1).max()/total),
        nearFloorAboveFraction=float(((ratios>=1)&(ratios<10)).mean()),nearFloorBelowFraction=float(((ratios>=.1)&(ratios<1)).mean()),
        minimumAboveFloorLog10Ratio=float(np.log10(above).min()) if len(above) else None,
        supportFraction=float(present.mean()),supportTransitions=int(np.count_nonzero(present[1:]!=present[:-1])))
    category=dict(stageBand=band(n),stageCount=n,branch=s['branch'],steam=bool(i['steamFeeds']),
        sideDrawCount=len(i['sideDraws']),heatLoopCount=len(i['pumparounds']),wet=any(s['wetTrays']))
    return views,quantities,category


def distance_matrix(x):
    square=np.einsum('ij,ij->i',x,x)
    d=np.maximum(square[:,None]+square[None,:]-2*x@x.T,0)
    np.fill_diagonal(d,0)
    return np.sqrt(d)


def build():
    pool,other,source=load_pool();seen=set();catalog=[];features={};invalid=[]
    for row,line in pool:
        key=canonical_input_hash(row['input']);assert key not in seen;seen.add(key)
        problems=audit(row)
        if problems:invalid.append(dict(id=row['id'],reasons=problems));continue
        groups,quantities,categories=descriptors(row)
        for name,value in groups.items():features.setdefault(name,[]).append(value)
        ratios=[]
        for check in row['diagnostics']['acceptanceAudit']['checks']:
            if check['limit']>0:ratios.append(check['value']/check['limit'])
        catalog.append(dict(id=row['id'],canonicalInputSha256=key,sourceRecordSha256=hashlib.sha256(line).hexdigest(),
            categories=categories,quantities=quantities,origin=row['labelProvenance'].get('origin','unspecified'),
            propertyRevision=row['seed']['propertyRevision'],maximumAuditLimitRatio=max(ratios) if ratios else None,
            strictCertified=True,qualityProblems=[]))
    norm={};arrays={};standardized={}
    for name,values in features.items():
        x=np.asarray(values);lo,hi=np.quantile(x,[.05,.95],axis=0);span=np.ptp(x,axis=0);center=np.median(x,axis=0)
        varying=span>1e-10*np.maximum(1,np.max(np.abs(x),axis=0))
        # Fractions have a natural unit interval. Other rare-but-real tails must
        # not be amplified by an almost-zero central percentile span.
        scale=(np.ones(x.shape[1]) if name in ('profile_support','profile_recovery') else
               np.where(varying,np.maximum(hi-lo,.1*span),1))
        z=(x-center)/scale;z[:,~varying]=0
        standardized[name]=z/np.sqrt(max(1,int(varying.sum())))
        arrays[name]=x;norm[name]=dict(median=center.tolist(),scale=scale.tolist(),varying=varying.tolist(),width=x.shape[1],varyingWidth=int(varying.sum()))
    input_names=[k for k in standardized if k.startswith('input_')];profile_names=[k for k in standardized if k.startswith('profile_')]
    x=np.hstack([standardized[k]/np.sqrt(len(input_names)) for k in input_names])
    y=np.hstack([standardized[k]/np.sqrt(len(profile_names)) for k in profile_names])
    di=distance_matrix(x);dp=distance_matrix(y)
    keys=[tuple(r['categories'][k] for k in ('stageBand','branch','steam','sideDrawCount','heatLoopCount')) for r in catalog]
    same=np.asarray([[a==b for b in keys] for a in keys]);np.fill_diagonal(same,False)
    joint=np.maximum(di,dp);nearest=np.where(same,joint,np.inf).min(1);finite=np.isfinite(nearest)
    inventory=dict(revision='certified-training-inventory-v2',source=source,code=info(Path(__file__)),
        inspectedTrain=len(pool),eligible=len(catalog),quarantined=invalid,nontrainingRecordsNotUsed=len(other),
        canonicalDuplicates=0,categoryCounts={k:dict(Counter(str(r['categories'][k]) for r in catalog)) for k in catalog[0]['categories']},
        quantityStatistics={k:stats([r['quantities'][k] for r in catalog if r['quantities'][k] is not None]) for k in catalog[0]['quantities']},
        observedCoarseRegimes=len(set(keys)),coarseSingletons=sum(v==1 for v in Counter(keys).values()),
        nearestSameCoarseRegimeJointDistance=stats(nearest[finite]),withoutSameRegimeNeighbor=int((~finite).sum()),
        scaling=norm,descriptorPolicy='TRAIN-only 5th–95th percentile scaling with a 10%-of-range lower bound; support/recovery fractions use unit scale; numerical constants ignored; equal input blocks and equal profile blocks.',
        restrictions='No trained-model errors, validation/test statistics, solver timing or convergence-iteration filtering. Profile support is certified-label structure, not prediction error.')
    arrays.update(input_standardized=x,profile_standardized=y,input_distances=di,profile_distances=dp,same_regime=same)
    return inventory,catalog,arrays


if __name__=='__main__':
    OUT.mkdir(parents=True,exist_ok=True);inventory,catalog,arrays=build()
    freeze(OUT/'inventory-v2.json',inventory);freeze(OUT/'catalog-v2.jsonl',catalog,True)
    with (OUT/'descriptors-v2.npz').open('xb') as stream:np.savez_compressed(stream,**arrays)
    print(json.dumps({k:inventory[k] for k in ('inspectedTrain','eligible','quarantined','observedCoarseRegimes','coarseSingletons','nearestSameCoarseRegimeJointDistance')}))
