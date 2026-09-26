"""Both-block case attribution with explicit unknown evidence and within-model preparation effects."""
from common import *
from collections import Counter,defaultdict
import re
from analyze_cases import optional,condition,profile_comparison


def outcome(row,mode):
    q=strict({**row,**row[mode]})
    return 'strict' if q else 'advisory' if row[mode]['success'] else 'failed'


def evidence(row,mode):
    m=row[mode];d=m.get('diagnostics');events=d.get('events') if isinstance(d,dict) else None
    text='\n'.join(events or []);tags=re.findall(r'initializer=([A-Z_]+)',text)
    fallback=True if 'CURRENT_BACKUP' in tags else False if 'LNN' in tags else None
    owner='classical_backup' if fallback is True else 'neural' if any(t in ('LNN','LNN_FAILED') for t in tags) else 'unknown'
    observed=[]
    for code,pattern in [('iteration_limit',r'iteration budget exhausted'),('neural_time_limit',r'neural budget exhausted'),
                         ('no_admissible_step',r'no admissible|no acceptable|line search failed|line-search failed|no descent'),
                         ('property_domain_rejection',r'outside the property domain|V3ThermoException|property domain')]:
        if re.search(pattern,text+' '+str(m.get('failure','')),re.I):observed.append(code)
    if m['status']=='DEADLINE_EXCEEDED':observed.append('whole_request_deadline')
    dominant=re.findall(r'dominant residual: EquationId\[family=([^,]+), node=(\d+), component=(-?\d+)',text)
    decay=re.findall(r'scaled residual: initial=([\d.eE+-]+), final=([\d.eE+-]+)',text)
    details=None
    if dominant:
        family,node,component=dominant[-1];node=int(node);inp=row['input'];special={'condenser':[0],'reboiler':[inp['stageCount']+1],
            'feed':[inp['feedStageNumber']], 'draw':[v['trayNumber'] for v in inp.get('sideDraws',[])],
            'heatDuty':[n for v in inp.get('pumparounds',[]) for n in range(v['returnTray'],v['drawTray']+1)]}
        details=dict(family=family,node=node,component=int(component),
            neighborhoods=[k for k,nodes in special.items() if any(abs(node-n)<=1 for n in nodes)])
    audits=d.get('acceptanceAudit',{}).get('checks') if isinstance(d,dict) else None
    return dict(outcome=outcome(row,mode),status=m['status'],failureClass=m.get('failureClass'),
        milliseconds=m['ms'],cpuMillis=m.get('cpuMillis'),newtonIterations=d.get('newtonIterations') if isinstance(d,dict) else None,
        initializerTags=tags,terminalTrajectoryOwner=owner,fallbackObserved=fallback,
        diagnosticEventsAvailable=events is not None,observedStopEvidence=observed or ['unknown'],
        dominantResidual=details,scaledResidualProgress=[dict(initial=float(a),final=float(b),ratio=float(b)/float(a) if float(a)>0 else None) for a,b in decay],
        auditEvidenceAvailable=audits is not None,failedAuditFamilies=[a['family'] for a in audits if not a['passed']] if audits is not None else None)


def summarize(rows,mode):
    es=[evidence(r,mode) for r in rows]
    return dict(cases=len(rows),outcomes=dict(Counter(e['outcome'] for e in es)),statuses=dict(Counter(e['status'] for e in es)),
        stopEvidence=dict(Counter(k for e in es for k in e['observedStopEvidence'])),
        terminalTrajectoryOwners=dict(Counter(e['terminalTrajectoryOwner'] for e in es)),
        fallbackTrue=sum(e['fallbackObserved'] is True for e in es),fallbackFalse=sum(e['fallbackObserved'] is False for e in es),
        fallbackUnknown=sum(e['fallbackObserved'] is None for e in es),
        milliseconds=optional([e['milliseconds'] for e in es]),newtonIterations=optional([e['newtonIterations'] for e in es]),
        dominantFamilies=dict(Counter(e['dominantResidual']['family'] for e in es if e['dominantResidual'])),
        failedAuditFamilies=dict(Counter(k for e in es for k in (e['failedAuditFamilies'] or []))),
        auditsUnknown=sum(e['failedAuditFamilies'] is None for e in es))


def within_preparation(rows):
    result={}
    for status in sorted({r.get('preparation',{}).get('status','UNAVAILABLE') for r in rows}):
        kept=[r for r in rows if r.get('preparation',{}).get('status','UNAVAILABLE')==status]
        families={}
        for family in ('COMPONENT_MATERIAL_BALANCE','ENERGY_BALANCE','VAPOR_LIQUID_EQUILIBRIUM'):
            pairs=[r for r in kept if family in r.get('raw',{}).get('nativeFamilies',{}) and family in r.get('final',{}).get('nativeFamilies',{})]
            families[family]={}
            for metric in ('maximumAbsolutePhysical','maximumAbsoluteScaled','rows'):
                a=[r['raw']['nativeFamilies'][family][metric] for r in pairs];b=[r['final']['nativeFamilies'][family][metric] for r in pairs]
                families[family][metric]=dict(availablePairs=len(pairs),unavailablePairs=len(kept)-len(pairs),raw=optional(a),final=optional(b),
                    pairedFinalMinusRaw=optional([y-x for x,y in zip(a,b)]),increased=sum(y>x for x,y in zip(a,b)))
        fixed=[r for r in kept if 'maximumMaterialDefectOverInputComponentScale' in r.get('raw',{}) and 'maximumMaterialDefectOverInputComponentScale' in r.get('final',{})]
        refs=[r for r in kept if 'rawVsOriginalCertifiedReference' in r and 'finalVsOriginalCertifiedReference' in r]
        result[status]=dict(cases=len(kept),nativeFamilies=families,
            rawFullGridMaterialDefect=optional([r['raw']['maximumMaterialDefectOverInputComponentScale'] for r in fixed]),
            finalFullGridMaterialDefect=optional([r['final']['maximumMaterialDefectOverInputComponentScale'] for r in fixed]),
            certifiedReferenceCases=len(refs),referenceErrorFinalMinusRaw={k:optional([r['finalVsOriginalCertifiedReference'][k]-r['rawVsOriginalCertifiedReference'][k] for r in refs])
                for k in ('temperatureRmseKelvin','phaseTotalFlowRelativeToFeedRmse','componentFlowRmseMolPerSecond')},
            supportChanges=optional([r.get('profileChange',{}).get('exactZeroPatternChanges') for r in kept]),
            rawSupportAvailable=sum('nativeSupport' in r.get('raw',{}) for r in kept),finalSupportAvailable=sum('nativeSupport' in r.get('final',{}) for r in kept))
    return result


def main():
    summary=json.loads((AREA/'summary.json').read_text());populations={};case_records=[];inputs=[]
    for split in ('validation','test'):
        names=list(summary[split]['pipelines']);raw={};profiles={}
        for name in names:
            path=AREA/'profile-diagnostics'/f'{split}-{name}.jsonl';inputs.append(verify_frozen(path));profiles[name]={r['id']:r for r in read_rows(path)}
            for block in (1,2):
                path=AREA/split/f'block-{block}'/name/'evaluation.jsonl';inputs.append(verify_frozen(path));raw[block,name]={r['id']:r for r in read_rows(path)}
        ids=sorted(raw[1,'incumbent']);populations[split]={}
        for name in names:
            record=dict(withinModelPreparation=within_preparation(list(profiles[name].values())),modes={});populations[split][name]=record
            for mode in ('neural','neuralFirst'):
                classifications={};blocks={}
                for block in (1,2):
                    groups=defaultdict(list);a=raw[block,'incumbent'];b=raw[block,name]
                    assert a.keys()==b.keys()==profiles[name].keys()
                    for i in ids:
                        assert a[i]['input']==b[i]['input']==profiles[name][i]['input']
                        x=outcome(a[i],mode)=='strict';y=outcome(b[i],mode)=='strict'
                        g='both' if x and y else 'incumbent_only' if x else 'candidate_only' if y else 'neither';groups[g].append(i);classifications[block,i]=g
                        case_records.append(dict(split=split,block=block,pipeline=name,mode=mode,id=i,group=g,
                            inputSha256=hashlib.sha256(json.dumps(a[i]['input'],sort_keys=True,separators=(',',':')).encode()).hexdigest(),
                            condition=condition(a[i]),preparation=profiles[name][i].get('preparation',{}).get('status'),
                            incumbent=evidence(a[i],mode),candidate=evidence(b[i],mode),
                            candidateSeparateNeuralOnlyRequest=evidence(b[i],'neural') if mode=='neuralFirst' else None,
                            incumbentCurrentOutcome=outcome(a[i],'current'),candidateCurrentOutcome=outcome(b[i],'current')))
                    blocks[str(block)]={g:dict(ids=kept,cases=len(kept),incumbent=summarize([a[i] for i in kept],mode),
                        candidate=summarize([b[i] for i in kept],mode),
                        pairedMillis=optional([b[i][mode]['ms']-a[i][mode]['ms'] for i in kept]),
                        candidateCurrentStrict=sum(outcome(b[i],'current')=='strict' for i in kept),
                        candidateSeparateNeuralOnly=summarize([b[i] for i in kept],'neural'),
                        profileComparison=profile_comparison(kept,profiles['incumbent'],profiles[name])) for g,kept in groups.items()}
                unstable=[i for i in ids if classifications[1,i]!=classifications[2,i]]
                reference_changed=[i for i in ids if outcome(raw[1,'incumbent'][i],mode)!=outcome(raw[2,'incumbent'][i],mode)]
                candidate_changed=[i for i in ids if outcome(raw[1,name][i],mode)!=outcome(raw[2,name][i],mode)]
                record['modes'][mode]=dict(blocks=blocks,classificationChangedIds=unstable,
                    referenceOutcomeChangedIds=reference_changed,candidateOutcomeChangedIds=candidate_changed,
                    stableGroups={g:[i for i in ids if classifications[1,i]==classifications[2,i]==g] for g in ('both','neither','candidate_only','incumbent_only')})
    result=dict(revision='hybrid-case-attribution-v2',source=info(Path(__file__)),inputs=inputs,populations=populations,
        newSolverRequests=0,testInterpretation='Retrospective former test; no fitting, model selection or new holdout claim.',
        evidenceRules=['Stop flags count observed evidence; absent reasons remain unknown.',
            'FIRST terminal diagnostics are labelled by trajectory owner; separately timed ONLY requests are not its internal trajectory.',
            'State-dependent scaled residuals and changing support are not one common optimization objective.',
            'Pumparounds are heat-duty neighborhoods only; no material return connection is inferred.'],
        supersedesPreliminaryInterpretation=['case-analysis.json budgetMention and absent-event fallback fields','case-analysis.json single-block grouping and preparationEffects'])
    freeze(OUT/'case-analysis-v2.json',result);freeze(OUT/'case-evidence-v2.jsonl',case_records,True)
    print(json.dumps({'verifiedInputFiles':len(inputs),'bothBlockEvidenceRows':len(case_records),'newSolverRequests':0}))


if __name__=='__main__':main()
