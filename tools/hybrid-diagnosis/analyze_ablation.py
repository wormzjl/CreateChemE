"""Revalidate the bounded experiment and diagnose its fixed selected checkpoints."""
from common import *
import numpy as np
import train_hybrid as h
import train_transformer as pilot
import train_transformer_accuracy as accuracy
import train_gen3_factorized as factor
import benchmark as original
from ablation import verify
from analyze_cases_v2 import summarize,outcome


def build():
    torch.set_num_threads(4);plan=verify();models=json.loads((OUT/'ablation-models.json').read_text())
    old=checkpoint(AREA/'fits/N-20260911/model.pt')
    controls={a:torch.load(OUT/'ablation-fits'/a/'model.pt',map_location='cpu',weights_only=True) for a in ('empirical','canonical')}
    a=controls['empirical'];b=controls['canonical']
    assert a['normalization']==old['normalization']
    exact=all(torch.equal(a['state_dict'][k],old['state_dict'][k]) for k in old['state_dict']);assert exact
    assert a['report']['minibatchOrderSha256']==b['report']['minibatchOrderSha256']
    assert a['report']['perCasePresentationCounts']==b['report']['perCasePresentationCounts']
    assert set(a['report']['perCasePresentationCounts'].values())=={160}
    baseline=json.loads((AREA/'training-plan.json').read_text());sources=[];native={};indexed={}
    native_plan=dict(validation=plan['panel'],models=models,warmup=info(AREA/'warmup.json'))
    for block in (1,2):
        for name in ('incumbent','empirical','canonical'):
            rows,meta=original.validate_run(OUT/'ablation-native'/f'block-{block}'/name,native_plan,'validation',name)
            indexed[block,name]={r['id']:r for r in rows}
            native.setdefault(name,{})[str(block)]={mode:summarize(rows,mode) for mode in ('current','neural','neuralFirst')}
            sources.extend(info(OUT/'ablation-native'/f'block-{block}'/name/f) for f in ('evaluation.jsonl','run.json'))
    paired={}
    for block in (1,2):
        paired[str(block)]={}
        for mode in ('neural','neuralFirst'):
            x=indexed[block,'empirical'];y=indexed[block,'canonical'];ids=sorted(x)
            sx={i for i in ids if outcome(x[i],mode)=='strict'};sy={i for i in ids if outcome(y[i],mode)=='strict'}
            paired[str(block)][mode]=dict(gains=sorted(sy-sx),losses=sorted(sx-sy),commonStrict=len(sx&sy),
                allCasePairedMilliseconds=stats([y[i][mode]['ms']-x[i][mode]['ms'] for i in ids]))
    rows=[r for r in read_rows(ROOT/baseline['datasets']['N']['path']) if r['split']=='validation' and strict(r)]
    anchors=h.load_anchors([ROOT/p for p in baseline['anchors']]);profiles={};per_case=[]
    for arm,cp in controls.items():
        path=OUT/'ablation-fits'/arm/'model.pt';doc=json.loads((OUT/'ablation-models'/arm/'model.json').read_text());assert digest(path)==doc['checkpointSha256']
        model=pilot.ColumnModel('transformer',inputs=185);model.load_state_dict(cp['state_dict']);model.eval();norm=cp['normalization']
        batch=h.tensors(rows,norm,anchors,cp['report']['branchesSeen'],'cpu')
        with torch.no_grad():
            raw,branch=h.forward(model,batch,norm);metrics={k:v.numpy() for k,v in accuracy.metrics(raw,branch,batch).items()}
            _,flows,_,_=accuracy.decode(raw,branch,batch)
        arm_rows=[]
        for i,row in enumerate(rows):
            count=row['input']['stageCount']+2;pred=flows[i,:count].numpy();actual=batch['flowTarget'][i,:count].numpy()
            floor=np.maximum(batch['feed'][i].numpy(),1e-12)[None,None,:]*factor.TRACE_FLOOR
            wanted=actual>=floor;missing=wanted&(pred==0);head=raw[i,:count,45:].numpy().reshape(count,2,20)>=np.log(.02/.98)
            record=dict(id=row['id'],arm=arm,missedAboveFloor=int(missing.sum()),missedByPresenceHead=int((missing&~head).sum()),
                missedDespitePresenceHead=int((missing&head).sum()),predictedAbsentInReference=int(((pred>0)&~wanted).sum()),
                **{k:float(v[i]) for k,v in metrics.items()})
            arm_rows.append(record);per_case.append(record)
        profiles[arm]=dict(referenceColumns=len(rows),metrics={k:stats(v) for k,v in metrics.items()},
            support={k:dict(total=sum(r[k] for r in arm_rows),perColumn=stats([r[k] for r in arm_rows])) for k in
                ('missedAboveFloor','missedByPresenceHead','missedDespitePresenceHead','predictedAbsentInReference')})
    result=dict(revision='support-ablation-analysis-v1',source=info(Path(__file__)),plan=info(OUT/'ablation-plan.json'),
        sources=sources,originalControlWeightsExactlyReproduced=exact,initialPhysicalPredictionsEqualWithinRegisteredTolerance=True,
        minibatchOrderSha256=a['report']['minibatchOrderSha256'],allNCasePresentations=160,
        fitReports={arm:info(OUT/'ablation-fits'/arm/'report.json') for arm in controls},
        selectedUpdates={arm:cp['report']['bestEpoch']*26 for arm,cp in controls.items()},
        native=native,canonicalAgainstEmpirical=paired,profiles=profiles,
        measuredPanelRequests=65*3*3*2,fixedTrainWarmupRequests=6*3*2,independentDiagnosticInputs=65,
        populationSuccessEstimate=False,promotionAllowed=False,newTestInputs=0)
    return result,per_case


if __name__=='__main__':
    result,rows=build();freeze(OUT/'ablation-analysis.json',result);freeze(OUT/'ablation-profile-evidence.jsonl',rows,True)
    print(json.dumps({'controlWeightsExactlyReproduced':result['originalControlWeightsExactlyReproduced'],
        'pairedResults':result['canonicalAgainstEmpirical'],'measuredPanelRequests':result['measuredPanelRequests']}))
