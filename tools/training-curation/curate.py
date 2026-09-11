"""Conservative, TRAIN-only direct-representation curation of the certified pool."""
import os
for name in ('OPENBLAS_NUM_THREADS','MKL_NUM_THREADS','OMP_NUM_THREADS'):os.environ.setdefault(name,'4')
from collections import Counter,defaultdict
from datetime import datetime,timezone
import hashlib
import json
from pathlib import Path
import sys
import numpy as np

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'build/training-curation/v1'
SOURCE=ROOT/'build/neural-salvage/nplus1-v1/Nplus1-certified.jsonl'
SOURCE_SHA='e0dc4194ce3dc02812399deded1686c37fdb351afc1b63a98374223e06478972'
sys.path.insert(0,str(ROOT/'tools/neural'))
from prepare_transformer_data import strict,digest,stats
from prepare_generalized_evaluation import canonical_input_hash
from native_checkpoint_selection_v1 import freeze,info


def allowed_source(path=SOURCE):
    path=Path(path).resolve()
    if path!=SOURCE.resolve():raise PermissionError('Selection permits only the registered TRAIN-only source')
    raw=path.read_bytes()
    assert len(raw)==35046934 and hashlib.sha256(raw).hexdigest()==SOURCE_SHA
    records=[];seen=set();ids=set()
    for line in raw.splitlines(keepends=True):
        r=json.loads(line);assert r['split']=='train' and r['labelProvenance']['eligibleForFitting'] is True
        assert strict(r);key=canonical_input_hash(r['input'])
        assert key==r['labelProvenance']['canonicalInputSha256'] and key not in seen and r['id'] not in ids
        assert key==canonical_input_hash(r['seed']['input'])
        n=r['input']['stageCount']+2;c=len(r['input']['feedComponentMolarFlowsMolPerSecond'])
        for name,shape in [('liquid',(n,c)),('vapor',(n,c)),('temperatures',(n,)),('freeWater',(n,)),('wetTrays',(n,))]:
            a=np.asarray(r['seed'][name]);assert a.shape==shape and np.isfinite(a).all()
            if name in ('liquid','vapor','freeWater'):assert (a>=0).all()
        records.append(dict(row=r,raw=line,key=key,id=r['id']));seen.add(key);ids.add(r['id'])
    assert len(records)==906
    return records


def stage_band(n):
    return next(label for lo,hi,label in [(2,8,'2-8'),(9,16,'9-16'),(17,32,'17-32'),(33,48,'33-48'),(49,64,'49-64')] if lo<=n<=hi)


def describe(record):
    r=record['row'];i=r['input'];s=r['seed'];n=i['stageCount'];feed=np.asarray(i['feedComponentMolarFlowsMolPerSecond'],float);total=feed.sum()
    assert total>0 and np.all(feed>=0);active=feed>0
    heats=sorted(i['pumparounds'],key=lambda p:(p['returnTray'],p['drawTray'],p['split'],p['dutyWatts']))
    draws=sorted(i['sideDraws'],key=lambda p:(p['trayNumber'],p['molarFlowMolPerSecond']))
    steams=sorted(i['steamFeeds'],key=lambda p:(p['stageNumber'],p['molarFlowMolPerSecond'],p['temperatureKelvin']))
    spec={k:v for item in i['specifications'] for k,v in item.items()};bottom=i['topPressurePascal']+(n-1)*i['stagePressureDropPascal']
    values={};families={}
    def add(key,family,value):values[key]=float(value);families[key]=family
    for key,value in [('feed_log_total',np.log(total)),('feed_temperature',i['feedTemperatureKelvin']),('condenser_temperature',spec['kelvin']),
                      ('top_log_pressure',np.log(i['topPressurePascal'])),('bottom_log_pressure',np.log(bottom)),
                      ('pressure_drop_asinh',np.arcsinh(i['stagePressureDropPascal']/1000)),('reflux_log1p',np.log1p(spec['ratio'])),
                      ('reboiler_specific_asinh',np.arcsinh(spec['watts']/total/1000)),('feed_location',i['feedStageNumber']/(n+1))]:add(key,key,value)
    for c in np.flatnonzero(active):add(f'mixture_{c}',f'mixture_{c}',np.log(feed[c]/total))
    source=np.zeros((4,n+2))
    for j,p in enumerate(heats):
        for field in ('returnTray','drawTray'):add(f'heat_{j}_{field}','heat_location',p[field]/(n+1))
        add(f'heat_{j}_duty','heat_specific_asinh',np.arcsinh(p['dutyWatts']/total/1000))
        if p['split']=='RETURN_TRAY':source[0,p['returnTray']]+=p['dutyWatts']/total/1000
        else:source[0,p['returnTray']:p['drawTray']+1]+=p['dutyWatts']/total/1000/(p['drawTray']-p['returnTray']+1)
    for j,p in enumerate(draws):
        add(f'draw_{j}_location','draw_location',p['trayNumber']/(n+1));add(f'draw_{j}_fraction','draw_log_fraction',np.log1p(p['molarFlowMolPerSecond']/total))
        source[1,p['trayNumber']]+=p['molarFlowMolPerSecond']/total
    for j,p in enumerate(steams):
        add(f'steam_{j}_location','steam_location',p['stageNumber']/(n+1));add(f'steam_{j}_fraction','steam_log_fraction',np.log1p(p['molarFlowMolPerSecond']/total))
        add(f'steam_{j}_temperature','steam_temperature',p['temperatureKelvin'])
        source[2,p['stageNumber']]+=p['molarFlowMolPerSecond']/total
        source[3,p['stageNumber']]+=p['molarFlowMolPerSecond']/total*p['temperatureKelvin']
    for kind in range(4):
        transformed=np.arcsinh(source[kind]) if kind==0 else source[kind]
        for node,value in enumerate(transformed):add(f'source_{kind}_{node}',f'source_{kind}',value)
        for node,value in enumerate(np.cumsum(source[kind])):add(f'cumulative_{kind}_{node}',f'cumulative_{kind}',np.arcsinh(value) if kind==0 else value)
    flow=np.stack((np.asarray(s['liquid']),np.asarray(s['vapor'])),axis=1)
    floor=1e-10*np.maximum(feed,1e-12*total);bands=np.zeros_like(flow,dtype=np.int8)
    bands[flow>0]=1;bands[flow>=floor]=2;bands[flow>=10*floor]=3
    structural=np.broadcast_to(active,(n+2,2,len(feed))).copy()
    if s['branch']=='LIQUID_ONLY':structural[0,1]=False
    if s['branch']=='VAPOR_ONLY':structural[0,0]=False
    assert np.all(flow[~structural]==0)
    phase_totals=flow.sum(-1);composition=flow/np.maximum(phase_totals[...,None],1e-300)
    categories=dict(stageCount=n,stageBand=stage_band(n),branch=s['branch'],steam=bool(steams),sideDrawCount=len(draws),heatLoopCount=len(heats),
        steamCount=len(steams),heatRules=','.join(sorted({p['split'] for p in heats})) or 'NONE',wet=bool(any(s['wetTrays'])),
        propertyRevision=s['propertyRevision'],formulation=r['formulationRevision'])
    signature=(i['packageId'],json.dumps(i['componentBasis'],sort_keys=True),s['propertyRevision'],r['formulationRevision'],n,s['branch'],
        tuple(active.tolist()),len(steams),len(draws),len(heats),tuple(p['split'] for p in heats),
        np.sign(i['stagePressureDropPascal']),np.sign(spec['ratio']),np.sign(spec['watts']),tuple(np.sign(p['dutyWatts']) for p in heats))
    temp=np.asarray(s['temperatures']);quantities={k:v for k,v in values.items() if not k.startswith(('source_','cumulative_'))}
    quantities.update(minimum_profile_temperature=float(temp.min()),maximum_profile_temperature=float(temp.max()),
        temperature_span=float(np.ptp(temp)),maximum_temperature_jump=float(np.abs(np.diff(temp)).max()),
        maximum_liquid_over_feed=float(phase_totals[:,0].max()/total),maximum_vapor_over_feed=float(phase_totals[:,1].max()/total),
        maximum_traffic_jump=float(np.abs(np.diff(phase_totals/total,axis=0)).max()))
    for phase in range(2):
        if phase_totals[0,phase]>0:quantities[f'positive_overhead_{phase}_over_feed']=float(phase_totals[0,phase]/total)
    water=r.get('waterEvidence') or {}
    if isinstance(water.get('maximumDrySaturationRatio'),(int,float)):quantities['dry_water_saturation_ratio']=water['maximumDrySaturationRatio']
    neighborhoods={'condenser':{0},'reboiler':{n+1},'feed':{k for k in range(i['feedStageNumber']-1,i['feedStageNumber']+2) if 1<=k<=n},
        'draw':{k for p in draws for k in range(p['trayNumber']-1,p['trayNumber']+2) if 1<=k<=n},
        'heat':{k for k in range(1,n+1) if source[0,k]!=0}}
    neighborhoods['remaining']={k for k in range(1,n+1)}-set.union(*neighborhoods.values())
    return dict(**record,categories=categories,signature=signature,values=values,families=families,flow=flow,floor=floor,
        bands=bands,structural=structural,temperature=temp,phase_totals=phase_totals/total,composition=composition,
        water=np.asarray(s['freeWater'])/total,wet=np.asarray(s['wetTrays']),quantities=quantities,neighborhoods=neighborhoods)


def weighted_quantile(values,weights,quantile):
    order=np.argsort(values,kind='stable');v=np.asarray(values)[order];w=np.asarray(weights)[order]
    c=np.cumsum(w);return float(np.interp(quantile*c[-1],c,v))


def scales_for(descriptions):
    data=defaultdict(list);weights=defaultdict(list)
    for d in sorted(descriptions,key=lambda d:(d['key'],d['id'])):
        by_family=defaultdict(list)
        for key,value in d['values'].items():by_family[d['families'][key]].append(value)
        for name,values in by_family.items():data[name].extend(values);weights[name].extend([1/len(values)]*len(values))
    scales={}
    for name,values in sorted(data.items()):
        lo=weighted_quantile(values,weights[name],.05);hi=weighted_quantile(values,weights[name],.95)
        span=max(values)-min(values);constant=span<=1e-12*max(1,max(abs(v) for v in values))
        scales[name]=dict(p05=lo,p95=hi,minimum=min(values),maximum=max(values),constant=constant,
            effectiveScale=max(hi-lo,.1*span) if not constant else 1.,definition='Column-balanced central 90% span with 10%-of-range floor; constants require numerical equality.')
    return scales


def compare(a,b,scales,multiplier=1.):
    if a['signature']!=b['signature']:return dict(admissible=False,reason='incompatible_structure_or_contract')
    assert a['values'].keys()==b['values'].keys()
    worst=0.;worst_key=None
    for key,x in a['values'].items():
        y=b['values'][key];scale=scales[a['families'][key]];slack=1e-12*max(1,abs(x),abs(y))
        tolerance=slack if scale['constant'] else .05*multiplier*scale['effectiveScale']+slack
        ratio=abs(x-y)/tolerance
        if ratio>worst:worst=ratio;worst_key=key
    temp=float(np.abs(a['temperature']-b['temperature']).max())
    traffic=np.abs(a['phase_totals']-b['phase_totals'])/(multiplier*(.02+.05*np.maximum(a['phase_totals'],b['phase_totals']))+1e-12)
    traffic_ratio=float(traffic.max());both=(a['phase_totals']>0)&(b['phase_totals']>0)
    composition=float((.5*np.abs(a['composition']-b['composition']).sum(-1))[both].max()) if both.any() else 0.
    above=(a['flow']>=a['floor'])&(b['flow']>=b['floor']);log_difference=0.
    if above.any():
        la=np.log10(np.maximum(a['flow'],1e-300))-np.log10(a['floor']);lb=np.log10(np.maximum(b['flow'],1e-300))-np.log10(b['floor'])
        log_difference=float(np.abs(la-lb)[above].max())
    band_crossings=int(np.count_nonzero(a['bands']!=b['bands']));structural=np.array_equal(a['structural'],b['structural'])
    water_equal=np.array_equal(a['wet'],b['wet']) and np.all(np.abs(a['water']-b['water'])<=1e-12)
    failed=[]
    if worst>1+1e-12:failed.append('continuous_inputs')
    if temp>2*multiplier+1e-9:failed.append('temperature')
    if traffic_ratio>1+1e-12:failed.append('phase_traffic')
    if composition>.02*multiplier+1e-12:failed.append('composition')
    if log_difference>.25*multiplier+1e-12:failed.append('retained_component_flow')
    if band_crossings:failed.append('trace_band_crossing')
    if not structural:failed.append('structural_phase_mismatch')
    if not water_equal:failed.append('water_or_wet_mismatch')
    return dict(admissible=not failed,failedGates=failed,maximumInputToleranceRatio=worst,limitingInput=worst_key,
        maximumTemperatureDifferenceKelvin=temp,maximumTrafficToleranceRatio=traffic_ratio,maximumCompositionTotalVariation=composition,
        maximumRetainedFlowDifferenceDecades=log_difference,traceBandCrossings=band_crossings,structuralMasksEqual=structural,waterMasksAndFlowsCompatible=bool(water_equal),
        representativeDistance=max(worst,temp/(2*multiplier),traffic_ratio,composition/(.02*multiplier),log_difference/(.25*multiplier)))


def requirements(descriptions):
    reasons=defaultdict(list);quotas={};witnesses={};groups=defaultdict(list)
    for j,d in enumerate(descriptions):
        c=d['categories'];groups[('rare',c['branch'],c['propertyRevision'],c['formulation'],c['wet'])].append(j)
        groups[('stage',c['stageCount'])].append(j)
        groups[('coarse',c['stageBand'],c['branch'],c['steam'],c['sideDrawCount']>0,c['heatLoopCount']>0)].append(j)
        for key in ('sideDrawCount','heatLoopCount','steamCount','heatRules','wet'):groups[(key,c[key])].append(j)
    for group,indices in sorted(groups.items(),key=lambda item:str(item[0])):
        name=json.dumps(group);minimum=min(3,len(indices)) if group[0] in ('stage','coarse') else 1
        if (group[0]=='rare' and len(indices)<=12) or (group[0]=='stage' and len(indices)<=3):
            for j in indices:reasons[j].append('scarce:'+name)
        quotas[name]=dict(indices=indices,minimum=minimum)
    quantities=sorted({key for d in descriptions for key in d['quantities']})
    for key in quantities:
        eligible=[j for j,d in enumerate(descriptions) if key in d['quantities']]
        for direction in ('minimum','maximum'):
            sign=1 if direction=='minimum' else -1
            j=min(eligible,key=lambda j:(sign*descriptions[j]['quantities'][key],descriptions[j]['key'],descriptions[j]['id']))
            name=f'extreme:{direction}:{key}';reasons[j].append(name);witnesses[name]=dict(id=descriptions[j]['id'],value=descriptions[j]['quantities'][key])
    boundaries={}
    for j,d in enumerate(descriptions):
        for hood,nodes in d['neighborhoods'].items():
            for node in sorted(nodes):
                for phase in range(2):
                    for component in range(d['flow'].shape[-1]):
                        q=d['flow'][node,phase,component]
                        if not d['structural'][node,phase,component] or q<=0:continue
                        logratio=float(np.log10(q)-np.log10(d['floor'][component]))
                        for threshold in (0.,1.):
                            side='below' if logratio<threshold else 'at_or_above'
                            name=f'trace:{component}:{phase}:{hood}:{int(threshold)}:{side}'
                            key=(abs(logratio-threshold),d['key'],d['id'],node)
                            if name not in boundaries or key<boundaries[name][0]:boundaries[name]=(key,j,node,logratio)
    for name,(key,j,node,logratio) in sorted(boundaries.items()):
        reasons[j].append(name);witnesses[name]=dict(id=descriptions[j]['id'],node=node,log10FlowOverFloor=logratio,distanceDecades=key[0])
    return dict(reasons),quotas,witnesses


def graph(descriptions,scales,multiplier):
    count=len(descriptions);adj=np.eye(count,dtype=bool);dist=np.zeros((count,count));edges=[];near_rejected=[];failures=Counter();compatible=0
    nearest=np.full(count,np.inf)
    for i in range(count):
        for j in range(i):
            a,b=descriptions[i],descriptions[j]
            if a['signature']!=b['signature']:continue
            compatible+=1;r=compare(a,b,scales,multiplier)
            nearest[i]=min(nearest[i],r['maximumInputToleranceRatio']);nearest[j]=min(nearest[j],r['maximumInputToleranceRatio'])
            if r['admissible']:
                adj[i,j]=adj[j,i]=True;dist[i,j]=dist[j,i]=r['representativeDistance'];edges.append(dict(a=a['id'],b=b['id'],gates=r))
            else:
                failures.update(r['failedGates'])
                if r['maximumInputToleranceRatio']<=1+1e-12:near_rejected.append(dict(a=a['id'],b=b['id'],gates=r))
    return adj,dist,dict(multiplier=multiplier,compatiblePairs=compatible,admissiblePairs=len(edges),edges=edges,
        inputNeighborsRejectedByProfile=near_rejected,failedGateCounts=dict(failures),isolatedCases=int((adj.sum(1)==1).sum()),
        nearestCompatibleInputToleranceRatio=stats(nearest[np.isfinite(nearest)]) if np.isfinite(nearest).any() else None)


def choose_cover(adj,dist,protected,quotas,keys):
    selected=set(protected);history=[]
    def deficits(s):return {name:q['minimum']-len(s.intersection(q['indices'])) for name,q in quotas.items() if len(s.intersection(q['indices']))<q['minimum']}
    while deficits(selected):
        missing=deficits(selected);available=[j for j in range(len(adj)) if j not in selected]
        gains={j:sum(j in quotas[k]['indices'] for k in missing) for j in available};j=min(available,key=lambda j:(-gains[j],keys[j]))
        assert gains[j]>0;selected.add(j);history.append(dict(index=j,reason='coverage_quota'))
    covered=adj[list(selected)].any(0) if selected else np.zeros(len(adj),dtype=bool)
    if np.all(adj.sum(1)==1):
        for j in sorted(set(range(len(adj)))-selected,key=lambda j:keys[j]):selected.add(j);history.append(dict(index=j,reason='no_admissible_representative'))
        return selected,history
    while not covered.all():
        available=[j for j in range(len(adj)) if j not in selected]
        def rank(j):
            newly=adj[j]&~covered;return (-int(newly.sum()),float(dist[j,newly].max()) if newly.any() else np.inf,keys[j])
        j=min(available,key=rank);selected.add(j);covered|=adj[j];history.append(dict(index=j,reason='direct_coverage'))
    for j in sorted(selected-protected,key=lambda j:keys[j],reverse=True):
        trial=selected-{j}
        if not deficits(trial) and trial and adj[list(trial)].any(0).all():selected=trial;history.append(dict(index=j,reason='reverse_deleted'))
    return selected,history


def register():
    records=allowed_source();OUT.mkdir(parents=True,exist_ok=True)
    plan=dict(revision='training-curation-plan-v1',createdUtc=datetime.now(timezone.utc).isoformat(),baseCommit='2c41d3e',
        userSizeChoice='coverage and redundancy; no fixed count',source=info(SOURCE),sourceRows=906,
        allowedSelectionData=[SOURCE.relative_to(ROOT).as_posix()],quarantineRule='Existing TRAIN provenance materialProfileDisagreement=true; certification remains valid and original record unchanged.',
        resolution=dict(inputFraction=.05,temperatureKelvin=2.,trafficAbsolute=.02,trafficRelative=.05,compositionTV=.02,retainedLogFlowDecades=.25,
            traceFloorFraction=1e-10,traceBands=['zero','positive_below_one_floor','one_to_ten_floors','at_least_ten_floors']),
        sensitivityMultipliers=[.5,1.,2.],inputScale='Column-balanced full-906 p05-p95 span, at least 10% full range; numerical constants require equality.',
        directSameGrid=True,requireSameContractStageBranchActiveFeedEquipmentCountsAndHeatRules=True,
        protectedRules=['All branch/property/formulation/wet categories with at most 12 records','All stage counts with at most 3 records','Input/profile extrema witnesses','Nearest trace witnesses around 1 and 10 floors by component, phase and physical neighborhood'],
        quotas='At least min(3,count) per exact stage and coarse stage-band/branch/steam/draw-present/heat-present cell; equipment counts and heat placement preserved.',
        algorithm='Protected set, quota gain, direct uncovered-neighbor gain, deterministic reverse deletion. Distance then canonical hash/ID ties; no transitive clustering.',
        selectionIsTrainingPerformanceClaim=False,trainWeightsAutomaticallyApplied=False,newFits=0,newNativeRequests=0,
        sources=[info(ROOT/'tools/training-curation'/f) for f in ('curate.py','verify.py','test_curation.py','protocol.md')],
        sourceDependencies=[info(ROOT/'tools/neural'/f) for f in ('prepare_transformer_data.py','prepare_generalized_evaluation.py','prepare_gen3_data.py','native_checkpoint_selection_v1.py')])
    freeze(OUT/'plan.json',plan);print(json.dumps({'registeredSourceRows':len(records),'fixedTargetSize':False}))


def build(records=None):
    plan=json.loads((OUT/'plan.json').read_text(encoding='utf-8'))
    for item in plan['sources']+plan['sourceDependencies']+[plan['source']]:assert digest(ROOT/item['path'])==item['sha256']
    records=allowed_source() if records is None else records
    descriptions=[describe(r) for r in sorted(records,key=lambda r:(r['key'],r['id']))]
    scales=scales_for(descriptions);eligible=[d for d in descriptions if not d['row']['labelProvenance'].get('materialProfileDisagreement',False)]
    quarantine=[d for d in descriptions if d['row']['labelProvenance'].get('materialProfileDisagreement',False)]
    reasons,quotas,witnesses=requirements(eligible);protected=set(reasons);keys=[(d['key'],d['id']) for d in eligible]
    _,source_quotas,source_witnesses=requirements(descriptions)
    sensitivities=[];primary=None
    for multiplier in plan['sensitivityMultipliers']:
        adj,dist,detail=graph(eligible,scales,multiplier);selected,history=choose_cover(adj,dist,protected,quotas,keys)
        sensitivities.append(dict(multiplier=multiplier,selected=len(selected),reserve=len(eligible)-len(selected),
            protected=len(protected),admissiblePairs=detail['admissiblePairs'],isolated=detail['isolatedCases']))
        if multiplier==1:primary=(adj,dist,detail,selected,history)
    adj,dist,redundancy,selected,history=primary;decisions=[];multiplicity=Counter();selected_ids={eligible[j]['id'] for j in selected}
    for j,d in enumerate(eligible):
        representative=j if j in selected else min((k for k in selected if adj[j,k]),key=lambda k:(dist[j,k],keys[k]))
        multiplicity[eligible[representative]['id']]+=1
        decisions.append(dict(id=d['id'],canonicalInputSha256=d['key'],sourceRecordSha256=hashlib.sha256(d['raw']).hexdigest(),
            inputSha256=digest_json(d['row']['input']),labelSha256=digest_json(d['row']['seed']),provenanceSha256=digest_json(d['row']['labelProvenance']),
            status='selected' if j in selected else 'reserve',reasons=reasons.get(j,[])+(['no_admissible_other_representative'] if adj[j].sum()==1 else []),
            representativeId=eligible[representative]['id'],representationGates=compare(d,eligible[representative],scales),categories=d['categories']))
    for d in quarantine:decisions.append(dict(id=d['id'],canonicalInputSha256=d['key'],sourceRecordSha256=hashlib.sha256(d['raw']).hexdigest(),
        inputSha256=digest_json(d['row']['input']),labelSha256=digest_json(d['row']['seed']),provenanceSha256=digest_json(d['row']['labelProvenance']),
        status='quarantine',strictCertified=True,reasons=['certified_target_has_recorded_root_disagreement'],representativeId=None,representationGates=None,categories=d['categories']))
    decisions.sort(key=lambda r:(r['canonicalInputSha256'],r['id']))
    by_status={status:[d for d in decisions if d['status']==status] for status in ('selected','reserve','quarantine')}
    coverage={status:dict(count=len(ds),categories={k:dict(Counter(str(d['categories'][k]) for d in ds)) for k in decisions[0]['categories']}) for status,ds in by_status.items()}
    coverage['source']=dict(count=906,categories={k:dict(Counter(str(d['categories'][k]) for d in decisions)) for k in decisions[0]['categories']})
    coverage['eligible']=dict(count=len(eligible),categories={k:dict(Counter(str(d['categories'][k]) for d in decisions if d['status']!='quarantine')) for k in decisions[0]['categories']})
    ranges={}
    for key in sorted({k for d in eligible for k in d['quantities']}):
        original=[d['quantities'][key] for d in eligible if key in d['quantities']];retained=[d['quantities'][key] for j,d in enumerate(eligible) if j in selected and key in d['quantities']]
        ranges[key]=dict(eligibleMinimum=min(original),eligibleMaximum=max(original),selectedMinimum=min(retained),selectedMaximum=max(retained))
    result=dict(revision='training-curation-result-v1',source=info(SOURCE),plan=info(OUT/'plan.json'),counts={k:len(v) for k,v in by_status.items()},
        admittedStrict=906,eligibleUnambiguous=len(eligible),protected=len(protected),coverage=coverage,continuousRanges=ranges,
        knownGaps=dict(stageCounts=[n for n in range(2,65) if n not in {d['categories']['stageCount'] for d in descriptions}],wetQualified=0,vaporOnly=0),
        quotas={k:dict(minimum=q['minimum'],selected=sum(j in selected for j in q['indices']),sourceCases=len(q['indices'])) for k,q in quotas.items()},
        witnesses=witnesses,sourceWitnessesLostToQuarantine={k:v for k,v in source_witnesses.items() if v['id'] not in {d['id'] for d in eligible}},
        sourceQuotaExceptions={k:dict(minimum=q['minimum'],selected=sum(descriptions[j]['id'] in selected_ids for j in q['indices'])) for k,q in source_quotas.items() if sum(descriptions[j]['id'] in selected_ids for j in q['indices'])<q['minimum']},
        sourceGroupCounts=dict(Counter(d['row']['labelProvenance']['origin'] for d in descriptions)),
        selectedSourceGroupCounts=dict(Counter(eligible[j]['row']['labelProvenance']['origin'] for j in selected)),
        scales=scales,sensitivities=sensitivities,representativeMultiplicities=dict(multiplicity),
        representedUnambiguousColumns=sum(multiplicity.values()),quarantineRepresented=False,trainingWeightsApplied=False,
        code=info(Path(__file__)))
    return result,decisions,redundancy


def digest_json(value):return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(',',':'),allow_nan=False).encode()).hexdigest()


def render(r):
    c=r['counts'];lines=['# Representative training-data selection','',
        f"Selected **{c['selected']} profiles** from the 906 certified TRAIN records. **{c['reserve']}** are represented reserve cases and **{c['quarantine']}** is held separately because its certified target has recorded root disagreement. No fixed subset size was imposed.",'',
        'Good training data here means a trustworthy same-input native certificate, useful operating and physical-profile coverage, and an unambiguous target policy. A difficult or unusual solution is not poor data. Solver speed, iteration count, model error and holdout outcomes did not influence selection.','',
        '## Quality and ambiguity','',
        'All 906 records pass the existing strict certification and shape/provenance checks. The case `gd-s08-w0-p1-d0-r00` remains certified, but its existing provenance records an unresolved historical profile disagreement. It is preserved unchanged in `quarantine.jsonl`; no alternative target is chosen and no label is averaged. The historical flag establishes a label-consistency issue, not proof of multiple physical roots. See `quarantine-evidence.json` for the exact TRAIN evidence and metadata bindings.','',
        '## Redundancy and chosen size','',
        'A reserve case needs a retained direct representative with the same contract, exact stage count, condenser branch, active-feed set, equipment counts and heat-placement rules. Inputs, every native temperature, phase traffic, composition, retained component flows and full-grid trace bands must all pass their registered proximity gates. There is no cross-stage interpolation or transitive clustering.','',
        '| Resolution multiplier | Selected | Reserve | Protected witnesses | Admissible nonself pairs | Isolated profiles |',
        '|---|---:|---:|---:|---:|---:|']
    for s in r['sensitivities']:lines.append(f"| {s['multiplier']} | {s['selected']} | {s['reserve']} | {s['protected']} | {s['admissiblePairs']} | {s['isolated']} |")
    lines+=['',
        'These are data-representation resolutions, not solver convergence tolerances. The primary setting allows 5% of each effective TRAIN input span, at most 2 K temperature difference, bounded phase-traffic differences, 0.02 composition total variation and 0.25 decades in above-floor component flows. Trace bands and structural masks cannot cross. Half/double sensitivity changes only continuous resolutions.','',
        'If the selected set stays large, that is evidence against aggressive compression at these resolutions. These 906 examples were already sparse and varied; forcing a round number would discard distinct examples rather than remove demonstrated redundancy. This selection does not prove that retraining on it will improve convergence.','',
        '## Coverage','',
        '| Category | Original certified pool | Selected |',
        '|---|---|---|']
    for key in ('branch','steam','sideDrawCount','heatLoopCount','heatRules','wet'):
        lines.append('| '+key+' | '+json.dumps(r['coverage']['source']['categories'][key],sort_keys=True)+' | '+json.dumps(r['coverage']['selected']['categories'][key],sort_keys=True)+' |')
    lines += ['',f"All eleven LIQUID_ONLY profiles and every observed stage count are retained. Known stage-count gaps remain {r['knownGaps']['stageCounts']}; the pool has no wet-qualified or VAPOR_ONLY profiles. Selection cannot manufacture those regimes.",'',
        f"Quarantine removes {len(r['sourceWitnessesLostToQuarantine'])} original deterministic boundary/extreme witnesses and leaves {len(r['sourceQuotaExceptions'])} original coverage-quota exceptions. Exact exceptions and replacement witnesses within the 905-case domain are recorded in `selection.json`. Original witness loss is not silently counted as satisfied.",'',
        'Protected records include scarce categories, operating/profile extrema and nearest observed witnesses on both sides of one-floor and ten-floor boundaries by component, phase and physical neighborhood. Heat loops are represented as authored stage heat only. Every quota and boundary witness is checked after selection.','',
        'Representative multiplicities describe coverage of the unambiguous pool and sum to its size. They are not applied as training weights. All labels, certificates and provenance retain their original raw JSONL record bytes. The original 906-record dataset and all older studies remain unchanged.','',
        '## Files','',
        '- `selected.jsonl`: recommended unambiguous training records at the registered coverage resolution.','- `reserve.jsonl`: valid records directly represented by selected cases; an empty file means no demonstrated redundancy.','- `quarantine.jsonl`: original certified records held aside for target ambiguity.','- `case-decisions.jsonl`: every source ID, hashes, reasons, representative and gate values.','- `selection.json` and `redundancy.json`: coverage, witnesses, resolutions and pairwise evidence.','',
        'The files are preserved in the separate [archive](cache-manifest.json). See [protocol](protocol.md) for the complete rules. No neural training, native solve, thermodynamic campaign or production-default change was performed.','']
    return '\n'.join(lines)


def run():
    result,decisions,redundancy=build();records=allowed_source();status={d['id']:d['status'] for d in decisions}
    for category in ('selected','reserve','quarantine'):
        with (OUT/f'{category}.jsonl').open('xb') as stream:
            for r in records:
                if status[r['id']]==category:stream.write(r['raw'])
    freeze(OUT/'selection.json',result);freeze(OUT/'case-decisions.jsonl',decisions,True);freeze(OUT/'redundancy.json',redundancy)
    with (OUT/'report.md').open('x',encoding='utf-8',newline='\n') as stream:stream.write(render(result))
    print(json.dumps({'counts':result['counts'],'protected':result['protected'],'sensitivity':result['sensitivities']}))


if __name__=='__main__':
    import argparse
    p=argparse.ArgumentParser();p.add_argument('mode',choices=['register','run']);a=p.parse_args();register() if a.mode=='register' else run()
