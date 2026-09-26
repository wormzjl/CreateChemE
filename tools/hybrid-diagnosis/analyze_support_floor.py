"""Separate meaningful support omissions from harmless below-decoder-floor traces."""
from analyze_support import ROOT,AREA,OUT,hybrid,pilot,accuracy,read_rows,strict,freeze,info,json,np,torch
import train_gen3_factorized as factor


def main():
    torch.set_num_threads(4);plan=json.loads((AREA/'training-plan.json').read_text())
    rows=[r for r in read_rows(ROOT/plan['datasets']['N']['path']) if r['split']=='validation' and strict(r)]
    anchors=hybrid.load_anchors([ROOT/p for p in plan['anchors']]);records=[];sources=[]
    mapping={'incumbent':ROOT/'build/neural-transformer/accuracy-v1/fits/baseline-20260911/model.pt'}
    mapping.update({f'{d}-{s}':AREA/'fits'/f'{d}-{s}'/'model.pt' for d in ('N','Nplus1') for s in hybrid.SEEDS})
    for name,path in mapping.items():
        cp=torch.load(path,map_location='cpu',weights_only=False);norm=cp['normalization'];sources.append(info(path))
        model=pilot.ColumnModel('transformer',inputs=96 if name=='incumbent' else 185)
        model.load_state_dict(cp['state_dict']);model.eval()
        batch=accuracy.tensors(rows,norm,'cpu') if name=='incumbent' else hybrid.tensors(rows,norm,anchors,cp['report']['branchesSeen'],'cpu')
        with torch.no_grad():
            if name=='incumbent':
                pred,branch=model(batch['x'],batch['g'],batch['valid']);raw=pred*pred.new_tensor(norm['yscale'])+pred.new_tensor(norm['ym'])
            else:raw,branch=hybrid.forward(model,batch,norm)
            _,flows,_,_=accuracy.decode(raw,branch,batch)
        for i,row in enumerate(rows):
            count=row['input']['stageCount']+2;pred=flows[i,:count].numpy();actual=batch['flowTarget'][i,:count].numpy()
            threshold=np.maximum(batch['feed'][i].numpy(),1e-12)[None,None,:]*factor.TRACE_FLOOR
            wanted=actual>=threshold;present=pred>0;missing=wanted&~present
            logits=raw[i,:count,45:].numpy().reshape(count,2,20)
            head_kept=logits>=np.log(.02/.98)
            v=dict(pipeline=name,id=row['id'],aboveFloorReferencePoints=int(wanted.sum()),
                missedAboveFloor=int(missing.sum()),missedByPresenceHead=int((missing&~head_kept).sum()),
                missedDespitePresenceHead=int((missing&head_kept).sum()),
                predictedAbsentInReference=int((present&~wanted).sum()),
                belowFloorPositiveReferencePoints=int(((actual>0)&~wanted).sum()),
                missedComponentCounts=missing.sum((0,1)).tolist())
            records.append(v)
    output=dict(revision='hybrid-support-floor-diagnosis-v1',source=info(__import__('pathlib').Path(__file__)),
        dependencies=[info(__import__('pathlib').Path(__file__).with_name('analyze_support.py'))],sources=sources,
        traceFloorFraction=factor.TRACE_FLOOR,originalValidationReferences=len(rows),testTensors=0,
        newFits=0,newSolverRequests=0,records=records,
        caveat='Above-floor omissions are measured on decoded raw seeds before native support refresh, not proved causes of solver failure.')
    freeze(OUT/'support-floor-analysis.json',output)
    print(json.dumps({'models':len(mapping),'referenceProfiles':len(rows),'newSolverRequests':0}))


if __name__=='__main__':main()
