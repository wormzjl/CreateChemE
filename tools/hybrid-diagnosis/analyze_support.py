"""Inspect decoded support and relative composition errors on original validation references."""
import json
from pathlib import Path
import sys
import numpy as np
import torch

ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'tools/hybrid-learning'),str(ROOT/'tools/neural')]
import train_hybrid as hybrid
import train_transformer as pilot
import train_transformer_accuracy as accuracy
from prepare_transformer_data import read_rows,strict,stats,digest
from native_checkpoint_selection_v1 import freeze,info

AREA=ROOT/'build/neural-hybrid-learning/v1';OUT=ROOT/'build/neural-hybrid-diagnosis/v1'


def main():
    torch.set_num_threads(4)
    plan=json.loads((AREA/'training-plan.json').read_text())
    rows=[r for r in read_rows(ROOT/plan['datasets']['N']['path']) if r['split']=='validation' and strict(r)]
    assert len(rows)==168
    anchors=hybrid.load_anchors([ROOT/p for p in plan['anchors']]);result={};records=[];sources=[]
    mapping={'incumbent':ROOT/'build/neural-transformer/accuracy-v1/fits/baseline-20260911/model.pt'}
    mapping.update({f'{d}-{s}':AREA/'fits'/f'{d}-{s}'/'model.pt' for d in ('N','Nplus1') for s in hybrid.SEEDS})
    for name,path in mapping.items():
        cp=torch.load(path,map_location='cpu',weights_only=False);norm=cp['normalization'];sources.append(info(path))
        is_hybrid=name!='incumbent';model=pilot.ColumnModel('transformer',inputs=185 if is_hybrid else 96)
        model.load_state_dict(cp['state_dict']);model.eval()
        batch=hybrid.tensors(rows,norm,anchors,cp['report']['branchesSeen'],'cpu') if is_hybrid else accuracy.tensors(rows,norm,'cpu')
        with torch.no_grad():
            if is_hybrid:raw,branch=hybrid.forward(model,batch,norm)
            else:
                pred,branch=model(batch['x'],batch['g'],batch['valid']);raw=pred*pred.new_tensor(norm['yscale'])+pred.new_tensor(norm['ym'])
            _,flows,branches,invalid=accuracy.decode(raw,branch,batch)
        flows=flows.numpy();actual=batch['flowTarget'].numpy();values=[]
        for index,row in enumerate(rows):
            count=row['input']['stageCount']+2;p=flows[index,:count];a=actual[index,:count]
            # Match the fixed deployed trace threshold when comparing support.
            present=p>0;wanted=a>0;floor=batch['feed'][index].numpy()[None,None,:]*1e-30
            common=present&wanted
            log_error=np.abs(np.log(np.maximum(p,floor))-np.log(np.maximum(a,floor)))
            k_common=common[:,0]&common[:,1]
            xp=p[:,0]/np.maximum(p[:,0].sum(-1,keepdims=True),1e-30);yp=p[:,1]/np.maximum(p[:,1].sum(-1,keepdims=True),1e-30)
            xa=a[:,0]/np.maximum(a[:,0].sum(-1,keepdims=True),1e-30);ya=a[:,1]/np.maximum(a[:,1].sum(-1,keepdims=True),1e-30)
            kerr=np.abs(np.log(np.maximum(yp,1e-30)/np.maximum(xp,1e-30))-np.log(np.maximum(ya,1e-30)/np.maximum(xa,1e-30)))
            v=dict(id=row['id'],pipeline=name,falseNegative=int((~present&wanted).sum()),falsePositive=int((present&~wanted).sum()),
                positive=int(wanted.sum()),negative=int((~wanted).sum()),commonPositive=int(common.sum()),
                commonLogFlowMean=float(log_error[common].mean()) if common.any() else None,
                commonLogFlowMax=float(log_error[common].max()) if common.any() else None,
                commonLogPhaseCompositionRatioMean=float(kerr[k_common].mean()) if k_common.any() else None,
                commonLogPhaseCompositionRatioMax=float(kerr[k_common].max()) if k_common.any() else None,
                branchError=int(branches[index]!=batch['labels'][index]),invalid=bool(invalid[index]))
            records.append(v);values.append(v)
        fields=('falseNegative','falsePositive','commonLogFlowMean','commonLogFlowMax',
                'commonLogPhaseCompositionRatioMean','commonLogPhaseCompositionRatioMax')
        groups={'all':[r['id'] for r in rows]}
        if is_hybrid:
            analysis=json.loads((OUT/'case-analysis.json').read_text())['populations']['validation'][name]['neural']['groups']
            groups.update({g:d['ids'] for g,d in analysis.items()})
        result[name]={}
        for group,ids in groups.items():
            kept=[v for v in values if v['id'] in ids]
            result[name][group]=dict(referenceColumns=len(kept),populationColumns=len(ids),
                metrics={k:stats([v[k] for v in kept if v[k] is not None]) if any(v[k] is not None for v in kept) else None for k in fields},
                totalFalseNegative=sum(v['falseNegative'] for v in kept),totalFalsePositive=sum(v['falsePositive'] for v in kept),
                branchErrors=sum(v['branchError'] for v in kept))
    output=dict(revision='hybrid-decoded-support-diagnosis-v1',source=info(Path(__file__)),sources=sources,
        originalValidationReferences=168,newFits=0,newSolverRequests=0,testTensors=0,models=result,
        caveats=['Exact zero support is descriptive; native support projection may change it.',
                 'Log phase composition ratios are a profile-error proxy, not a recomputed thermodynamic fugacity residual.',
                 'Log errors use only common-positive coordinates; missing support is counted separately.'])
    freeze(OUT/'support-analysis.json',output);freeze(OUT/'support-case-evidence.jsonl',records,True)
    print(json.dumps({'models':len(mapping),'originalReferenceProfiles':len(rows),'newSolverRequests':0}))


if __name__=='__main__':main()
