"""Read-only evidence definitions; no fits, choices or solver calls."""
from common import *
from collections import Counter
import math
import re


def optional(values):
    values=[float(value) for value in values if isinstance(value,(int,float,np.number)) and math.isfinite(value)]
    return stats(values) if values else None


def outcome(row,mode):
    return 'strict' if strict({**row,**row[mode]}) else 'advisory' if row[mode]['success'] else 'failed'


def mode_evidence(row,mode):
    record=row[mode];diagnostics=record.get('diagnostics') or {};events=diagnostics.get('events')
    text='\n'.join(events or []);tags=re.findall(r'initializer=([A-Z_]+)',text)
    if mode=='current':owner='classical';fallback=None
    else:
        fallback=True if 'CURRENT_BACKUP' in tags else False if 'LNN' in tags else None
        owner='classical_backup' if fallback is True else 'neural' if any(t in ('LNN','LNN_FAILED') for t in tags) else 'unknown'
    stops=[]
    for name,pattern in [('iteration_limit',r'iteration budget exhausted'),('neural_time_limit',r'neural budget exhausted'),
        ('no_admissible_step',r'no admissible|no acceptable|line search failed|line-search failed|no descent'),
        ('property_domain_rejection',r'outside the property domain|V3ThermoException|property domain')]:
        if re.search(pattern,text+' '+str(record.get('failure','')),re.I):stops.append(name)
    if record['status']=='DEADLINE_EXCEEDED':stops.append('whole_request_deadline')
    result=outcome(row,mode)
    if not stops and result!='strict':stops=['unknown']
    checks=(diagnostics.get('acceptanceAudit') or {}).get('checks')
    families={check['family']:dict(value=check['value'],limit=check['limit'],passed=check['passed']) for check in checks or []}
    dominant=re.findall(r'dominant residual: EquationId\[family=([^,]+), node=(\d+), component=(-?\d+)',text)
    details=dict(family=dominant[-1][0],node=int(dominant[-1][1]),component=int(dominant[-1][2])) if dominant else None
    return dict(outcome=result,status=record['status'],failureClass=record.get('failureClass'),milliseconds=record['ms'],
        cpuMillis=record.get('cpuMillis'),allocatedBytes=record.get('allocatedBytes'),newtonIterations=diagnostics.get('newtonIterations'),
        eventsAvailable=events is not None,initializerTags=tags,terminalTrajectoryOwner=owner,fallbackObserved=fallback,
        observedStopPhrases=stops,dominantTerminalResidual=details,auditAvailable=checks is not None,audits=families)


def summarize_evidence(records):
    families=sorted({name for record in records for name in record['audits']})
    return dict(cases=len(records),outcomes=dict(Counter(r['outcome'] for r in records)),statuses=dict(Counter(r['status'] for r in records)),
        stopPhraseCounts=dict(Counter(s for r in records for s in r['observedStopPhrases'])),
        terminalOwners=dict(Counter(r['terminalTrajectoryOwner'] for r in records)),
        fallbackTrue=sum(r['fallbackObserved'] is True for r in records),fallbackFalse=sum(r['fallbackObserved'] is False for r in records),
        fallbackUnknownOrNotApplicable=sum(r['fallbackObserved'] is None for r in records),
        milliseconds=optional([r['milliseconds'] for r in records]),cpuMillis=optional([r['cpuMillis'] for r in records]),
        allocatedBytes=optional([r['allocatedBytes'] for r in records]),newtonIterations=optional([r['newtonIterations'] for r in records]),
        auditUnknown=sum(not r['auditAvailable'] for r in records),
        audits={name:dict(available=sum(name in r['audits'] for r in records),
            failed=sum(not r['audits'][name]['passed'] for r in records if name in r['audits']),
            values=optional([r['audits'][name]['value'] for r in records if name in r['audits']]),
            valueOverLimit=optional([r['audits'][name]['value']/r['audits'][name]['limit'] for r in records if name in r['audits'] and r['audits'][name]['limit']>0])) for name in families})


def condition(row):
    i=row['input'];n=i['stageCount']
    return dict(stageCount=n,stageBand=next(label for high,label in [(8,'2-8'),(16,'9-16'),(32,'17-32'),(48,'33-48'),(64,'49-64')] if n<=high),
        steam=bool(i['steamFeeds']),sideDrawCount=len(i['sideDraws']),heatLoopCount=len(i['pumparounds']))


def profile_evidence(row,reference):
    predicted=row['rawPrediction'].get('seedPresentedByPipeline')
    result=dict(id=row['id'],available=predicted is not None,condition=condition(row),
        originalReferenceAmbiguityFlag=bool(reference['labelProvenance'].get('materialProfileDisagreement',False)))
    if predicted is None:return result
    assert canonical_input_hash(predicted['input'])==canonical_input_hash(reference['input'])==canonical_input_hash(row['input'])
    actual=np.stack([np.asarray(predicted[phase],float) for phase in ('liquid','vapor')],axis=1)
    wanted=np.stack([np.asarray(reference['seed'][phase],float) for phase in ('liquid','vapor')],axis=1)
    feed=np.asarray(row['input']['feedComponentMolarFlowsMolPerSecond']);total=feed.sum()
    assert actual.shape==wanted.shape and np.isfinite(actual).all() and (actual>=0).all()
    floor=1e-10*np.maximum(feed,1e-12*total)
    above=(wanted>=floor)&(feed[None,None,:]>0)
    omissions=above&(actual==0);positive_below=(wanted>0)&(wanted<floor)
    forbidden=np.zeros(actual.shape[:2],dtype=bool)
    if predicted['branch']=='LIQUID_ONLY':forbidden[0,1]=True
    elif predicted['branch']=='VAPOR_ONLY':forbidden[0,0]=True
    zero_phase=actual.sum(-1)==0
    branch_omissions=int((omissions&forbidden[...,None]).sum())
    zero_phase_omissions=int((omissions&zero_phase[...,None]&~forbidden[...,None]).sum())
    positive_phase_omissions=int((omissions&~zero_phase[...,None]).sum())
    assert branch_omissions+zero_phase_omissions+positive_phase_omissions==int(omissions.sum())
    extra=(wanted<floor)&(actual>=floor)&(feed[None,None,:]>0)
    reference_fraction=wanted/np.maximum(wanted.sum(-1,keepdims=True),1e-300)
    trace=above&(reference_fraction<=1e-4)
    log_error=np.abs(np.log10(np.maximum(actual,floor))-np.log10(np.maximum(wanted,floor)))
    a_tot=actual.sum(-1)/total;b_tot=wanted.sum(-1)/total
    temps=np.asarray(predicted['temperatures']);ref_temps=np.asarray(reference['seed']['temperatures'])
    result.update(branch=predicted['branch'],referenceBranch=reference['seed']['branch'],branchMatchesReference=predicted['branch']==reference['seed']['branch'],
        seedSha256=hashlib.sha256(json.dumps(predicted,sort_keys=True,separators=(',',':')).encode()).hexdigest(),
        referenceAboveFloorEntries=int(above.sum()),aboveFloorOmissions=int(omissions.sum()),
        predictedBranchOmissions=branch_omissions,zeroAllowedPhaseOmissions=zero_phase_omissions,positivePhaseComponentOmissions=positive_phase_omissions,
        omittedOnlyWithinSameBranch=int(omissions.sum()) if predicted['branch']==reference['seed']['branch'] else None,
        referencePositiveBelowFloorEntries=int(positive_below.sum()),additionalAboveFloorEntries=int(extra.sum()),
        traceEntries=int(trace.sum()),traceLog10Mae=float(log_error[trace].mean()) if trace.any() else None,
        aboveFloorLog10Mae=float(log_error[above].mean()) if above.any() else None,
        temperatureMaeKelvin=float(np.abs(temps-ref_temps).mean()),temperatureMaximumErrorKelvin=float(np.abs(temps-ref_temps).max()),
        liquidTotalMaeOverFeed=float(np.abs(a_tot[:,0]-b_tot[:,0]).mean()),vaporTotalMaeOverFeed=float(np.abs(a_tot[:,1]-b_tot[:,1]).mean()),
        liquidComponentL1OverFeed=float(np.abs(actual[:,0]-wanted[:,0]).sum(-1).mean()/total),
        vaporComponentL1OverFeed=float(np.abs(actual[:,1]-wanted[:,1]).sum(-1).mean()/total),
        liquidMeanSignedTrafficErrorOverFeed=float((a_tot[:,0]-b_tot[:,0]).mean()),
        vaporMeanSignedTrafficErrorOverFeed=float((a_tot[:,1]-b_tot[:,1]).mean()),
        maximumLiquidTrafficOverFeed=float(a_tot[:,0].max()),maximumVaporTrafficOverFeed=float(a_tot[:,1].max()),
        freeWaterMaximumErrorOverFeed=float(np.abs(np.asarray(predicted['freeWater'])-reference['seed']['freeWater']).max()/total),
        wetMaskDifferences=int(np.count_nonzero(np.asarray(predicted['wetTrays'])!=reference['seed']['wetTrays'])))
    return result


PROFILE_FIELDS=('aboveFloorOmissions','predictedBranchOmissions','zeroAllowedPhaseOmissions','positivePhaseComponentOmissions','additionalAboveFloorEntries','traceLog10Mae','aboveFloorLog10Mae',
    'temperatureMaeKelvin','temperatureMaximumErrorKelvin','liquidTotalMaeOverFeed','vaporTotalMaeOverFeed',
    'liquidComponentL1OverFeed','vaporComponentL1OverFeed','liquidMeanSignedTrafficErrorOverFeed','vaporMeanSignedTrafficErrorOverFeed',
    'maximumLiquidTrafficOverFeed','maximumVaporTrafficOverFeed','freeWaterMaximumErrorOverFeed','wetMaskDifferences')


def summarize_profiles(records):
    available=[r for r in records if r['available']];same=[r for r in available if r['branchMatchesReference']]
    return dict(referenceCases=len(records),available=len(available),unavailable=len(records)-len(available),
        branchMatches=len(same),referenceAmbiguityFlags=sum(r['originalReferenceAmbiguityFlag'] for r in records),
        referenceAboveFloorEntries=sum(r['referenceAboveFloorEntries'] for r in available),
        aboveFloorOmissions=sum(r['aboveFloorOmissions'] for r in available),
        predictedBranchOmissions=sum(r['predictedBranchOmissions'] for r in available),zeroAllowedPhaseOmissions=sum(r['zeroAllowedPhaseOmissions'] for r in available),
        positivePhaseComponentOmissions=sum(r['positivePhaseComponentOmissions'] for r in available),
        sameBranchAboveFloorOmissions=sum(r['aboveFloorOmissions'] for r in same),
        additionalAboveFloorEntries=sum(r['additionalAboveFloorEntries'] for r in available),
        perColumn={key:optional([r[key] for r in available]) for key in PROFILE_FIELDS})


def paired_profiles(a,b,ids):
    ids=[i for i in ids if i in a and i in b];both=[i for i in ids if a[i]['available'] and b[i]['available']]
    return dict(referenceCases=len(ids),bothAvailable=len(both),referenceUnavailable=sum(not a[i]['available'] for i in ids),
        candidateUnavailable=sum(not b[i]['available'] for i in ids),
        referenceOmissions=sum(a[i]['aboveFloorOmissions'] for i in both),candidateOmissions=sum(b[i]['aboveFloorOmissions'] for i in both),
        candidateMinusReference={key:optional([b[i][key]-a[i][key] for i in both if a[i][key] is not None and b[i][key] is not None]) for key in PROFILE_FIELDS})
