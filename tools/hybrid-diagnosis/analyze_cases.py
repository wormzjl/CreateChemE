"""Retrospective case diagnosis of the sealed hybrid study; launches no solver."""
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT/'tools/hybrid-learning'), str(ROOT/'tools/neural')]
from prepare_transformer_data import read_rows, strict, stats
from native_checkpoint_selection_v1 import freeze, info

SOURCE = ROOT/'build/neural-hybrid-learning/v1'
OUT = ROOT/'build/neural-hybrid-diagnosis/v1'
MODES = ('neural', 'neuralFirst')
PROFILE_FIELDS = ('temperatureRmseKelvin', 'phaseTotalFlowRelativeToFeedRmse',
                  'componentFlowRmseMolPerSecond', 'log1pComponentFlowRmse')
FAMILIES = ('COMPONENT_MATERIAL_BALANCE', 'ENERGY_BALANCE', 'VAPOR_LIQUID_EQUILIBRIUM')


def optional(values):
    values = [v for v in values if isinstance(v, (int, float))]
    return stats(values) if values else None


def qualified(row, mode):
    return strict({**row, **row[mode]})


def condition(row):
    i = row['input']; stage = i['stageCount']
    return dict(stageCount=stage, stageBand=next(name for lo, hi, name in
        [(2,8,'2-8'),(9,16,'9-16'),(17,32,'17-32'),(33,48,'33-48'),(49,64,'49-64')] if lo <= stage <= hi),
        steam=bool(i.get('steamFeeds')), draws=len(i.get('sideDraws',[])), heatLoops=len(i.get('pumparounds',[])))


def diagnostics(row, mode):
    m = row[mode]; d = m.get('diagnostics') or {}; events = d.get('events', [])
    joined = '\n'.join(events)
    dominant = re.findall(r'dominant residual: EquationId\[family=([^,]+), node=(\d+), component=(-?\d+)', joined)
    decay = re.findall(r'scaled residual: initial=([\d.eE+-]+), final=([\d.eE+-]+)', joined)
    ratios = [float(b)/float(a) for a,b in decay if float(a)>0]
    return dict(status=m['status'], failureClass=m.get('failureClass'), strict=qualified(row,mode),
        milliseconds=m['ms'], iterations=d.get('newtonIterations'), finalResidual=d.get('maximumScaledResidual'),
        fallback='initializer=CURRENT_BACKUP' in joined,
        budgetMention=bool(re.search(r'budget|deadline|cancel|time.?out',joined+' '+str(m.get('failure','')),re.I)),
        iterationExhausted='iteration budget exhausted' in joined,
        dominantFamily=dominant[-1][0] if dominant else None,
        dominantNode=int(dominant[-1][1]) if dominant else None,
        dominantComponent=int(dominant[-1][2]) if dominant else None,
        residualReductionRatio=ratios[-1] if ratios else None,
        failedAuditFamilies=[a['family'] for a in d.get('acceptanceAudit',{}).get('checks',[]) if not a['passed']])


def summarize_solver(rows, mode):
    values=[diagnostics(r,mode) for r in rows]
    return dict(cases=len(rows), strict=sum(v['strict'] for v in values),
        statuses=dict(Counter(v['status'] for v in values)),
        failureClasses=dict(Counter(v['failureClass'] for v in values if v['failureClass'])),
        dominantFamilies=dict(Counter(v['dominantFamily'] for v in values if v['dominantFamily'])),
        dominantComponents=dict(Counter(v['dominantComponent'] for v in values if v['dominantComponent'] is not None)),
        failedAuditFamilies=dict(Counter(k for v in values for k in v['failedAuditFamilies'])),
        fallback=sum(v['fallback'] for v in values),
        iterationExhausted=sum(v['iterationExhausted'] for v in values),
        budgetMention=sum(v['budgetMention'] for v in values),
        iterations=optional([v['iterations'] for v in values]),
        finalResidual=optional([v['finalResidual'] for v in values]),
        residualReductionRatio=optional([v['residualReductionRatio'] for v in values]),
        milliseconds=optional([v['milliseconds'] for v in values]))


def profile_comparison(ids, reference, candidate):
    common=[i for i in ids if 'rawVsOriginalCertifiedReference' in reference[i]
            and 'rawVsOriginalCertifiedReference' in candidate[i]]
    result={'totalCases':len(ids),'certifiedReferenceCases':len(common)}
    for phase in ('rawVsOriginalCertifiedReference','finalVsOriginalCertifiedReference'):
        result[phase]={f:dict(incumbent=optional([reference[i][phase][f] for i in common]),
            candidate=optional([candidate[i][phase][f] for i in common]),
            pairedCandidateMinusIncumbent=optional([candidate[i][phase][f]-reference[i][phase][f] for i in common]),
            candidateWorse=sum(candidate[i][phase][f]>reference[i][phase][f] for i in common)) for f in PROFILE_FIELDS}
        result[phase]['candidateBranchMismatch']=sum(not candidate[i][phase]['branchMatches'] for i in common)
    for phase in ('raw','final'):
        result[phase]={}
        for family in FAMILIES:
            kept=[i for i in ids if family in reference[i].get(phase,{}).get('nativeFamilies',{})
                  and family in candidate[i].get(phase,{}).get('nativeFamilies',{})]
            a=[reference[i][phase]['nativeFamilies'][family]['maximumAbsoluteScaled'] for i in kept]
            b=[candidate[i][phase]['nativeFamilies'][family]['maximumAbsoluteScaled'] for i in kept]
            result[phase][family]=dict(availablePairs=len(kept),unavailablePairs=len(ids)-len(kept),
                incumbent=optional(a),candidate=optional(b),candidateWorse=sum(y>x for x,y in zip(a,b)),
                pairedCandidateMinusIncumbent=optional([y-x for x,y in zip(a,b)]))
    return result


def main():
    OUT.mkdir(parents=True,exist_ok=True)
    proof=json.loads((SOURCE/'results-verification.json').read_text())
    assert proof['passed'] and proof['storedOutputsEqual']
    for item in proof['reports']:
        assert hashlib.sha256((ROOT/item['path']).read_bytes()).hexdigest()==item['sha256']
    summary=json.loads((SOURCE/'summary.json').read_text())
    manifest=json.loads((ROOT/'tools/hybrid-learning/results-cache-manifest.json').read_text())
    bindings={e['entry']:e['sha256'] for e in manifest['entries']}
    used={}; output={}; case_records=[]
    def load(path):
        name=path.relative_to(ROOT).as_posix(); value=path.read_bytes()
        assert hashlib.sha256(value).hexdigest()==bindings[name],name
        used[name]=info(path)
        return read_rows(path)
    for split in ('validation','test'):
        names=list(summary[split]['pipelines']); raw={}; profiles={}
        for name in names:
            profiles[name]={r['id']:r for r in load(SOURCE/'profile-diagnostics'/f'{split}-{name}.jsonl')}
            for block in (1,2):
                raw[block,name]={r['id']:r for r in load(SOURCE/split/f'block-{block}'/name/'evaluation.jsonl')}
                assert raw[block,name].keys()==profiles[name].keys()
                assert all(r['input']==profiles[name][i]['input'] for i,r in raw[block,name].items())
        ref=raw[1,'incumbent']; ids=sorted(ref); output[split]={}
        for name in names:
            model={}; output[split][name]=model
            for mode in MODES:
                groups=defaultdict(list)
                for i in ids:
                    a=qualified(ref[i],mode);b=qualified(raw[1,name][i],mode)
                    group='both' if a and b else 'incumbent_only' if a else 'candidate_only' if b else 'neither'
                    groups[group].append(i)
                    case_records.append(dict(split=split,pipeline=name,mode=mode,id=i,group=group,
                        condition=condition(ref[i]),preparation=profiles[name][i].get('preparation',{}).get('status'),
                        incumbent=diagnostics(ref[i],mode),candidate=diagnostics(raw[1,name][i],mode),
                        repeatChanged=qualified(raw[1,name][i],mode)!=qualified(raw[2,name][i],mode)))
                model[mode]={'groups':{}}
                for group, group_ids in sorted(groups.items()):
                    rows=[raw[1,name][i] for i in group_ids]
                    model[mode]['groups'][group]=dict(ids=group_ids,cases=len(group_ids),
                        conditions={k:dict(Counter(str(condition(ref[i])[k]) for i in group_ids))
                                    for k in ('stageBand','steam','draws','heatLoops')},
                        preparation=dict(Counter(profiles[name][i].get('preparation',{}).get('status','UNAVAILABLE') for i in group_ids)),
                        incumbentSolver=summarize_solver([ref[i] for i in group_ids],mode),
                        candidateSolver=summarize_solver(rows,mode),
                        profileComparison=profile_comparison(group_ids,profiles['incumbent'],profiles[name]))
                model[mode]['repeatChangedIds']=[i for i in ids if qualified(raw[1,name][i],mode)!=qualified(raw[2,name][i],mode)]
                model[mode]['allCases']=summarize_solver(list(raw[1,name].values()),mode)
            model['preparationEffects']={}
            for status in sorted({p.get('preparation',{}).get('status','UNAVAILABLE') for p in profiles[name].values()}):
                kept=[i for i in ids if profiles[name][i].get('preparation',{}).get('status','UNAVAILABLE')==status]
                model['preparationEffects'][status]=dict(cases=len(kept),
                    supportChanges=optional([profiles[name][i].get('profileChange',{}).get('exactZeroPatternChanges') for i in kept]),
                    profileComparison=profile_comparison(kept,profiles['incumbent'],profiles[name]))
    result=dict(revision='hybrid-retrospective-case-diagnosis-v1',sourceCommit='f459085',
        newSolverRequests=0,usedForSelection=False,testInterpretation='Previously evaluated test is retrospective diagnostic evidence; not a new holdout.',
        source=info(Path(__file__)),sealedManifest=info(ROOT/'tools/hybrid-learning/results-cache-manifest.json'),
        inputs=list(used.values()),populations=output)
    freeze(OUT/'case-analysis.json',result);freeze(OUT/'case-evidence.jsonl',case_records,True)
    print(json.dumps({'sealedInputFiles':len(used),'caseEvidenceRows':len(case_records),'newSolverRequests':0}))


if __name__=='__main__':main()
