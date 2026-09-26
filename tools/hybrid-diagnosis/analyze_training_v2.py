"""Incumbent comparison, frozen-source checks, anchor correlations and exposure bounds."""
from common import *
import numpy as np
import train_hybrid as h
import train_transformer as pilot
import train_transformer_accuracy as accuracy
import train_gen3_factorized as factor


def anchor_details(rows,anchors,norm,branches):
    targets=[];bases=[];weights=[];tails={k:[] for k in ('temperature','logLiquidTotal','logVaporTotal','liquidComposition','vaporComposition')}
    groups={'temperature':[0],'logLiquidTotal':[1],'logVaporTotal':[2],'liquidComposition':list(range(3,23)),'vaporComposition':list(range(23,43))}
    failures=0;branch_differences=0
    for row,branch in zip(rows,branches):
        target,_=factor.targets(row);a=anchors[row['id']][branch];b=np.asarray(a['values'])
        failures+=not a['available'];branch_differences+=branch!=row['seed']['branch']
        targets.append(target);bases.append(b);weights.extend([1/len(b)]*len(b))
        for name,ix in groups.items():
            absolute=np.abs((target[:,ix]-np.asarray(norm['ym'])[ix])/np.asarray(norm['yscale'])[ix])
            residual=np.abs((target[:,ix]-b[:,ix]-np.asarray(norm['dm'])[ix])/np.asarray(norm['dscale'])[ix])
            tails[name].append(dict(absoluteColumnMaximum=float(absolute.max()),residualColumnMaximum=float(residual.max())))
    y=np.vstack(targets);b=np.vstack(bases);w=np.asarray(weights);w/=w.sum();ym=(w[:,None]*y).sum(0);bm=(w[:,None]*b).sum(0)
    vy=(w[:,None]*(y-ym)**2).sum(0);vb=(w[:,None]*(b-bm)**2).sum(0);cov=(w[:,None]*(y-ym)*(b-bm)).sum(0)
    vd=(w[:,None]*((y-b)-(ym-bm))**2).sum(0);result={}
    for name,ix in groups.items():
        result[name]=dict(anchorVarianceMean=float(vb[ix].mean()),targetVarianceMean=float(vy[ix].mean()),
            residualToAbsoluteVariance=float(vd[ix].sum()/max(vy[ix].sum(),1e-30)),
            correlations=[float(cov[j]/np.sqrt(vy[j]*vb[j])) if vy[j]>1e-20 and vb[j]>1e-20 else None for j in ix],
            maximumAbsoluteAnchorCoordinate=float(np.abs(b[:,ix]).max()),
            standardizedColumnMaxima={key:stats([r[key] for r in tails[name]]) for key in tails[name][0]})
    return dict(columns=len(rows),unavailableAnchors=failures,predictedVersusLabelBranchDifferences=branch_differences,groups=result)


def exposures(steps,count):
    batches=(count+31)//32;complete,remainder=divmod(steps,batches)
    return dict(updates=steps,completePasses=complete,partialPassBatches=remainder,
        perCasePresentationLowerBound=complete,perCasePresentationUpperBound=complete+bool(remainder),
        exactAllDatasetPresentations=complete*count+min(remainder*32,count),
        interpretation='Each full shuffled pass visits every case once; no expected value is presented as a recorded per-case count.')


def main():
    torch.set_num_threads(4);old=json.loads((OUT/'training-analysis.json').read_text());sources=[]
    for item in old['sources']:sources.append(verify_frozen(ROOT/item['path']))
    plan=json.loads((AREA/'training-plan.json').read_text())
    for p in plan['anchors']:sources.append(verify_frozen(ROOT/p))
    anchors=h.load_anchors([ROOT/p for p in plan['anchors']]);norm=json.loads((AREA/'normalization.json').read_text())
    n=[r for r in read_rows(ROOT/plan['datasets']['N']['path']) if r['split']=='train' and r['labelProvenance']['eligibleForFitting']]
    ids={r['id'] for r in n};allrows=read_rows(ROOT/plan['datasets']['Nplus1']['path'])
    added=[r for r in allrows if r['split']=='train' and r['labelProvenance']['eligibleForFitting'] and r['id'] not in ids]
    val=[r for r in allrows if r['split']=='validation' and strict(r)]
    groups={'originalN':n,'added101':added,'validation168':val};rows=n+added+val
    path=ROOT/'build/neural-transformer/accuracy-v1/fits/baseline-20260911/model.pt';cp=checkpoint(path);sources.append(info(path))
    incumbent=pilot.ColumnModel('transformer');incumbent.load_state_dict(cp['state_dict']);incumbent.eval()
    batch=accuracy.tensors(rows,cp['normalization'],'cpu')
    with torch.no_grad():
        p,b=incumbent(batch['x'],batch['g'],batch['valid']);raw=p*p.new_tensor(cp['normalization']['yscale'])+p.new_tensor(cp['normalization']['ym'])
        metrics={k:v.numpy() for k,v in accuracy.metrics(raw,b,batch).items()}
    incumbent_summary={};offset=0;incumbent_cases=[]
    for group,part in groups.items():
        incumbent_summary[group]=dict(columns=len(part),metrics={k:stats(v[offset:offset+len(part)]) for k,v in metrics.items()})
        incumbent_cases.extend(dict(pipeline='incumbent',group=group,id=row['id'],**{k:float(v[offset+j]) for k,v in metrics.items()}) for j,row in enumerate(part));offset+=len(part)
    detailed={};exposure={}
    for name,r in old['fits'].items():
        cp=checkpoint(AREA/'fits'/name/'model.pt');model=pilot.ColumnModel('transformer',inputs=185);model.load_state_dict(cp['state_dict']);model.eval()
        batch=h.tensors(rows,norm,anchors,cp['report']['branchesSeen'],'cpu')
        with torch.no_grad():branch=model.branch(batch['g']).masked_fill(~batch['branchAllowed'],-torch.inf).argmax(-1).tolist()
        detailed[name]={};offset=0
        for group,part in groups.items():
            chosen=[h.base.BRANCHES[b] for b in branch[offset:offset+len(part)]]
            detailed[name][group]=anchor_details(part,anchors,norm,chosen);offset+=len(part)
        count=cp['report']['trainColumns'];exposure[name]=dict(selected=exposures(r['selectedUpdate'],count),final=exposures(4160,count))
    result=dict(revision='hybrid-training-diagnosis-v2',source=info(Path(__file__)),sources=sources,
        precedingAnalysis=info(OUT/'training-analysis.json'),incumbent=incumbent_summary,
        hybridMetrics=old['fits'],predictedBranchConditioning=detailed,exposureBounds=exposure,
        originalRows=old['groups'],newFits=0,testTensors=0,
        interpretation='Incumbent and hybrid metrics use their own unchanged normalization and deployed decoder; common labelled subsets only. Conditioning is descriptive, not causal proof.')
    freeze(OUT/'training-analysis-v2.json',result);freeze(OUT/'incumbent-training-case-evidence.jsonl',incumbent_cases,True)
    print(json.dumps({'originalTrain':len(n),'addedTrain':len(added),'originalValidationReferences':len(val),'sealedSourcesChecked':len(sources),'testTensors':0}))


if __name__=='__main__':main()
