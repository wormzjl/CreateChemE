"""Absolute-output candidate exports and fixed original-TRAIN parity fixtures."""
from common import *
import copy
from initialization import ANCHORS


def fixture_rows():
    rows=read_rows(OUT/'N804.jsonl')
    chosen=sorted(rows,key=lambda r:(r['input']['stageCount'],r['id']))[::max(1,len(rows)//12)][:12]
    for row in (max(rows,key=lambda r:r['input']['stageCount']),next(r for r in rows if r['seed']['branch']=='LIQUID_ONLY')):
        if row not in chosen:chosen.append(row)
    assert len(chosen)<=14;return chosen


def fixture_forward(model,row,norm,anchors,layout):
    inp=row['input'];g=hybrid.base.global_features(inp);x=hybrid.base.node_features(inp,'TWO_PHASE')[:,:-3]
    gg=torch.tensor((g-norm['gm'])/norm['gscale'],dtype=torch.float64)[None]
    with torch.no_grad():logits=model.branch(gg)[0]
    allowed=np.asarray(incumbent_document()['branchesSeen'],dtype=bool)
    if any(s.get('ratio',0)>0 for s in inp['specifications']):allowed[2]=False
    branch_index=int(logits.masked_fill(~torch.tensor(allowed),-torch.inf).argmax())
    branch=hybrid.base.BRANCHES[branch_index];anchor=anchors[row['id']][branch]
    normalized=(x-norm['xm'])/norm['xscale']
    if layout!='plain':
        native=(np.asarray(anchor['values'])-norm['bm'])/norm['bscale']
        if layout=='compact':native=native[:,COMPACT_ANCHOR_INDICES]
        onehot=np.zeros((len(x),3));onehot[:,branch_index]=1
        normalized=np.hstack((normalized,native,onehot,np.full((len(x),1),anchor['available'])))
    with torch.no_grad():p,_=model(torch.tensor(normalized,dtype=torch.float64)[None],gg,torch.ones((1,len(x)),dtype=torch.bool))
    raw=p[0].numpy()*norm['yscale']+norm['ym'];decoded=hybrid.factor.decode(inp,raw,branch,.02)
    return dict(id=row['id'],split='train',input=inp,globalFeatures=g.tolist(),nodes=x.tolist(),branch=branch,branchLogits=logits.tolist(),
        raw=raw.tolist(),anchor=anchor,decoded={k:v.tolist() if isinstance(v,np.ndarray) else v for k,v in decoded.items()})


def export(model,norm,layout,destination,model_id,checkpoint=None,plan=None):
    doc=incumbent_document();doc['modelId']=model_id
    doc['normalization']=copy.deepcopy(norm)
    if layout!='plain':doc.update(featureRevision='v3-anchor-augmented-1',modelType='anchor-augmented',anchorLayout=layout,
        baselineRevision='native-material-closed-anchor-v1',outputConvention='absolute')
    doc['weights']={k:dict(shape=list(v.shape),values=v.detach().cpu().numpy().ravel().tolist()) for k,v in model.state_dict().items()}
    # Do not inherit the incumbent's checkpoint identity for a newly fitted state.
    doc['checkpointSha256']=digest(checkpoint) if checkpoint else None
    doc['initializationSourceSha256']=INCUMBENT_SHA;doc['trainingPlanSha256']=digest(plan) if plan else None
    doc['dataSha256']=torch.load(checkpoint,map_location='cpu',weights_only=True)['report']['dataSha256'] if checkpoint else None
    doc['fixtureDataSha256']=digest(OUT/'N804.jsonl')
    doc['selection']='Prospectively registered follow-up checkpoint; no retired-test selection.'
    freeze(destination/'model.json',doc)
    anchors=hybrid.load_anchors(ANCHORS);fixtures=[fixture_forward(copy.deepcopy(model).double().eval(),r,norm,anchors,layout) for r in fixture_rows()]
    freeze(destination/'fixture.json',fixtures)
    pipeline=dict(revision='hybrid-pipeline-v1',id=model_id,kind='transformer' if layout=='plain' else 'anchor-augmented',
        weights=info(destination/'model.json'),materialCompletion=False)
    freeze(destination/'pipeline.json',pipeline)
    return {**info(destination/'pipeline.json'),'weights':pipeline['weights']}


def initial():
    torch.set_num_threads(4);norm=read(OUT/'normalization.json');models={}
    for layout,inputs in [('plain',96),('full',185),('compact',103)]:
        models[layout]=export(incumbent_model(inputs).eval(),norm,layout,OUT/'initial-models'/layout,'trace-initial-'+layout)
    freeze(OUT/'initial-models.json',models)
    print(json.dumps({'initialExports':list(models)}))


if __name__=='__main__':initial()
