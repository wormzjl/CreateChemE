"""TRAIN-only normalization and all-TRAIN incumbent-preservation proof."""
from common import *

ANCHORS=[ROOT/'build/neural-hybrid-learning/v1'/name for name in ('N-anchors.jsonl','new-anchors.jsonl')]


def shared_normalization(rows,anchors):
    norm=incumbent_document()['normalization']
    arrays=[np.asarray(anchors[r['id']][r['seed']['branch']]['values']) for r in rows]
    weights=[1/len(a) for a in arrays for _ in a]
    mean,scale=hybrid.base.moments(np.vstack(arrays),weights)
    scale[0]=max(scale[0],25);scale[1:3]=np.maximum(scale[1:3],.1);scale[3:]=np.maximum(scale[3:],1)
    norm['bm']=mean.tolist();norm['bscale']=scale.tolist();return norm


def main():
    cohorts=read(OUT/'cohorts.json')
    for record in cohorts['files'].values():assert digest(ROOT/record['path'])==record['sha256']
    previous=read(ROOT/'build/neural-hybrid-learning/v1/training-plan.json')
    for path in ANCHORS:assert digest(path)==previous['anchors'][path.relative_to(ROOT).as_posix()]
    original=read_rows(OUT/'N804.jsonl');all_rows=read_rows(OUT/'N905.jsonl')
    anchors=hybrid.load_anchors(ANCHORS);norm=shared_normalization(original,anchors)
    seen=incumbent_document()['branchesSeen']
    assert all(seen[hybrid.base.BRANCHES.index(r['seed']['branch'])] for r in all_rows)
    torch.set_num_threads(4)
    batch=hybrid.tensors(all_rows,norm,anchors,seen,'cpu')
    models={layout:incumbent_model(inputs).eval() for layout,inputs in [('plain',96),('full',185),('compact',103)]}
    with torch.no_grad():raw,branch=absolute_forward(models['plain'],batch,norm)
    _,flow,chosen,invalid=accuracy.decode(raw,branch,batch);results={}
    for layout in ('full','compact'):
        with torch.no_grad():candidate,logits=absolute_forward(models[layout],batch,norm,layout)
        _,cf,cb,ci=accuracy.decode(candidate,logits,batch)
        difference=float((candidate-raw)[batch['valid']].abs().max())
        masks=int(((cf==0)!=(flow==0)).sum());branch_difference=float((logits-branch).abs().max())
        assert difference<2e-4 and masks==0 and torch.equal(chosen,cb) and torch.equal(invalid,ci)
        assert torch.equal(models[layout].embed.weight[:,:96],models['plain'].embed.weight)
        assert torch.count_nonzero(models[layout].embed.weight[:,96:])==0
        results[layout]=dict(trainCases=len(all_rows),maximumFloat32RawDifference=difference,
            maximumBranchLogitDifference=branch_difference,decodedMaskDifferences=masks,exactBranchesAndInvalidFlags=True,
            parameters=sum(p.numel() for p in models[layout].parameters()),initialStateSha256=state_digest(models[layout]))
    proof=dict(revision='incumbent-preserving-anchor-inputs-v1',passed=True,incumbent=info(INCUMBENT),anchors=[info(p) for p in ANCHORS],
        cohorts=info(OUT/'cohorts.json'),inputAndOutputNormalizationSource='Frozen incumbent; shared across all arms',
        anchorNormalizationSource='Only N804 native anchors, column balanced',
        compactRemovedAnchorCoordinates=list(range(3,85)),compactRetainedAnchorCoordinates=COMPACT_ANCHOR_INDICES,
        outputConvention='Absolute incumbent output coordinates. No anchor is added to the output.',
        initialEquivalence=results,historicalWarmStartIncludesQuarantinedTraining=True,
        nativeAnchorCostExcludedFromAlgebraicEquivalence=True)
    freeze(OUT/'normalization.json',norm);freeze(OUT/'initialization-proof.json',proof)
    print(json.dumps({'initializationPassed':True,'allTrainCases':905,'arms':results}))


if __name__=='__main__':main()
