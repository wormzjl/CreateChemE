"""All-TRAIN identity proof before any capacity fit, followed by native export checks."""
from capacity_common import *
from capacity_export import export, parity
from capacity_native import CORE_CLASSES, CLASSES, execute
import argparse


def identity_and_export():
    torch.set_num_threads(4)
    models = {2: initial_model(2).eval(), 4: initial_model(4).eval()}
    norm = normalization(); rows = read_rows(SOURCE / 'N905.jsonl')
    raw_max = 0.; branch_max = 0.; checked = 0
    for start in range(0, len(rows), 32):
        batch = dataset(rows[start:start + 32])
        with torch.no_grad():
            a, ab = forward(models[2], batch, norm)
            b, bb = forward(models[4], batch, norm)
        assert torch.equal(a, b) and torch.equal(ab, bb)
        _, af, ac, ai = accuracy.decode(a, ab, batch)
        _, bf, bc, bi = accuracy.decode(b, bb, batch)
        assert torch.equal(af == 0, bf == 0) and torch.equal(ac, bc) and torch.equal(ai, bi)
        raw_max = max(raw_max, float((a - b).abs().max()))
        branch_max = max(branch_max, float((ab - bb).abs().max()))
        checked += len(batch['g'])
    assert checked == 905
    binding, _ = base_binding()
    proof = dict(revision='capacity-initial-function-proof-v1', passed=True, sourcePipeline=binding,
                 allTrainCases=checked, trainSource=info(SOURCE / 'N905.jsonl'),
                 maximumRawDifference=raw_max, maximumBranchLogitDifference=branch_max,
                 exactMasksBranchesAndInvalidFlags=True,
                 expansion='Append two copies of trained block 1, zeroing attention output and FF output weights/biases.',
                 stateHashes={str(layers): state_digest(model) for layers, model in models.items()},
                 parameters={str(layers): sum(p.numel() for p in model.parameters()) for layers, model in models.items()},
                 optimizerUpdates=0, validationUsed=False)
    freeze(OUT / 'initialization-proof.json', proof)
    exports = {}
    for layers, model in models.items():
        name = f'initial-{layers}'
        exports[name] = export(model, OUT / name, name)
    freeze(OUT / 'initial-models.json', exports)
    print('Exact initial raw, branch and decoded-mask equality on all 905 TRAIN inputs; two initial exports complete.', flush=True)


def native_parity():
    torch.set_num_threads(4)
    for layers in (2, 4):
        parity(OUT / f'initial-{layers}', initial_model(layers), suffix='-rebuilt')


def mode_identity():
    torch.set_num_threads(4)
    rows=read_rows(SOURCE/'N804.jsonl');tensors=dataset(rows);norm=normalization();proof={}
    for training in (False,True):
        small=initial_model(2).train(training);large=initial_model(4).train(training)
        for start in range(0,len(rows),32):
            batch=pilot.subset(tensors,torch.arange(start,min(start+32,len(rows))))
            with torch.no_grad():
                a,ab=forward(small,batch,norm);b,bb=forward(large,batch,norm)
            assert torch.equal(a,b) and torch.equal(ab,bb)
            _,af,ac,ai=accuracy.decode(a,ab,batch);_,bf,bc,bi=accuracy.decode(b,bb,batch)
            assert torch.equal(af==0,bf==0) and torch.equal(ac,bc) and torch.equal(ai,bi)
        proof['train' if training else 'eval']=dict(cases=len(rows),exactRaw=True,exactBranch=True,exactMasks=True)
    freeze(OUT/'initialization-mode-proof.json',dict(passed=True,modes=proof,mixedLengthPadding=True,optimizerUpdates=0))
    print('All804 exact identity in both training and evaluation modes with mixed-length padding.',flush=True)


def runtime_identity():
    binding,_=base_binding()
    execute('V3CapacityRuntimeCheck',[ROOT/binding['weights']['path'],OUT/'initial-2/model.json',
        OUT/'initial-4/model.json',SOURCE/'N804.jsonl',CORE_CLASSES,CLASSES,OUT/'native-runtime-proof.json'],
        OUT/'preflight/native-runtime-identity.log')


if __name__ == '__main__':
    p = argparse.ArgumentParser(); p.add_argument('mode', choices=['identity', 'native','modes','runtime'])
    args = p.parse_args()
    {'identity':identity_and_export,'native':native_parity,'modes':mode_identity,'runtime':runtime_identity}[args.mode]()
