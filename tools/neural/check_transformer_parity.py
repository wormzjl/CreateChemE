"""Check full decoded native seeds against independent exported PyTorch-double outputs."""
import json
from pathlib import Path
import numpy as np
import train_generalized as base
from train_gen3_factorized import decode


def main():
    root=Path('build/neural-transformer/native-v1'); reports={}
    for kind in ('transformer','mlp'):
        directory=root/kind
        doc=json.loads((directory/'model.json').read_text())
        expected=json.loads((directory/'fixture.json').read_text())
        actual=json.loads((directory/'java-parity.json').read_text())
        assert len(expected)==len(actual['predictions'])
        maxima={'temperatureMaxK':0.,'flowMaxOverFeed':0.}
        for row,result in zip(expected,actual['predictions']):
            assert row['id']==result['id']
            logits=np.asarray(row['branchLogits']); logits[~np.asarray(doc['branchesSeen'])]=-np.inf
            if any(s.get('ratio',0)>0 for s in row['input']['specifications']): logits[2]=-np.inf
            seed=decode(row['input'],np.asarray(row['raw']),base.BRANCHES[int(logits.argmax())],.02)
            observed=result['prediction']; feed=sum(row['input']['feedComponentMolarFlowsMolPerSecond'])
            assert seed['branch']==observed['branch'] and np.array_equal(seed['wetTrays'],observed['wetTrays'])
            temperature=float(np.max(np.abs(seed['temperatures']-observed['temperatures'])))
            flow=max(float(np.max(np.abs(seed[k]-observed[k])))/feed for k in ('liquid','vapor','freeWater'))
            assert temperature<5e-5 and flow<2e-6, (kind,row['id'],temperature,flow)
            for k in ('liquid','vapor'): assert np.array_equal(seed[k]==0,np.asarray(observed[k])==0)
            maxima['temperatureMaxK']=max(maxima['temperatureMaxK'],temperature)
            maxima['flowMaxOverFeed']=max(maxima['flowMaxOverFeed'],flow)
        reports[kind]={'fixtures':len(expected),'branchWetAndZeroMasksMatch':True,**maxima,
            'parallelIdenticalPredictions':actual['parallelIdenticalPredictions'],'cancellationPassed':actual['cancellationPassed'],
            'malformedShapeRejected':actual['malformedShapeRejected']}
    with (root/'parity.json').open('x') as f: json.dump(reports,f,indent=2)
    print(json.dumps(reports,indent=2))


if __name__=='__main__': main()
