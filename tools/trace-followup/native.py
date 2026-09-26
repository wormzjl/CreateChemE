"""Unchanged-budget native evaluation and independent ten-worker export parity."""
from common import *
import argparse
import os
import subprocess


def execute(main,args,log):
    command=['gradlew.bat','-I','tools/trace-followup/followup.gradle','traceFollowup',
        '-PfollowupMain=com.wormzjl.createcheme.science.column.v3.'+main,
        '-PfollowupArgs='+','.join(Path(p).relative_to(ROOT).as_posix() if isinstance(p,Path) else str(p) for p in args),'--console=plain']
    log.parent.mkdir(parents=True,exist_ok=True)
    with log.open('x',encoding='utf-8') as stream:
        process=subprocess.Popen(command,env=dict(os.environ,JAVA_HOME='C:/Program Files/Java/jdk-21.0.11'),
            cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace')
        for line in process.stdout:stream.write(line);stream.flush();print(line,end='',flush=True)
        if process.wait():raise RuntimeError('Native invocation failed; preserve this attempt and diagnose before a new run.')


def parity(directory,layout,norm,state):
    execute('V3TraceParityCheck',[directory/'model.json',directory/'fixture.json',directory/'java-parity.json'],directory/'java-parity.log')
    native=read(directory/'java-parity.json');assert native['passed'] and native['exactMasks']
    assert native['scheduling']['terminated'] and native['scheduling']['distinctWorkerThreads']==10
    assert native['predictionCancellationPropagated']
    from export_models import fixture_rows
    from initialization import ANCHORS
    rows=fixture_rows();anchors=hybrid.load_anchors(ANCHORS);seen=incumbent_document()['branchesSeen']
    model=pilot.ColumnModel('transformer',inputs={'plain':96,'full':185,'compact':103}[layout]);model.load_state_dict(state);model.eval()
    batch=hybrid.tensors(rows,norm,anchors,seen,'cpu')
    with torch.no_grad():raw,branch=absolute_forward(model,batch,norm,layout)
    fixtures=read(directory/'fixture.json');masks=0;max_raw=0.;max_temperature=0.;max_flow=0.
    for index,(row,fixture) in enumerate(zip(rows,fixtures)):
        count=row['input']['stageCount']+2;r=raw[index,:count].numpy();max_raw=max(max_raw,float(np.abs(r-fixture['raw']).max()))
        chosen=hybrid.base.BRANCHES[int(branch[index,:2].argmax())];assert chosen==fixture['branch']
        decoded=hybrid.factor.decode(row['input'],r,chosen,.02);feed=sum(row['input']['feedComponentMolarFlowsMolPerSecond'])
        for phase in ('liquid','vapor'):
            expected=np.asarray(fixture['decoded'][phase]);masks+=int(np.count_nonzero((decoded[phase]==0)!=(expected==0)))
            max_flow=max(max_flow,float(np.abs(decoded[phase]-expected).max()/feed))
        max_temperature=max(max_temperature,float(np.abs(decoded['temperatures']-fixture['decoded']['temperatures']).max()))
        masks+=int(np.count_nonzero(decoded['wetTrays']!=fixture['decoded']['wetTrays']))
    assert masks==0
    proof=dict(passed=True,layout=layout,maskDifferences=masks,maximumFloat32RawDifference=max_raw,
        maximumFloat32TemperatureDifferenceKelvin=max_temperature,maximumFloat32FlowDifferenceOverFeed=max_flow,
        model=info(directory/'model.json'),fixtures=info(directory/'fixture.json'),javaParity=info(directory/'java-parity.json'))
    freeze(directory/'precision-check.json',proof)
    print(json.dumps({'parityPassed':directory.relative_to(ROOT).as_posix(),'layout':layout,'maskDifferences':masks}),flush=True)
    return proof


def initial_parity():
    torch.set_num_threads(4);norm=read(OUT/'normalization.json')
    for layout in ('plain','full','compact'):
        directory=OUT/'initial-models'/layout
        parity(directory,layout,norm,incumbent_model({'plain':96,'full':185,'compact':103}[layout]).state_dict())


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('mode',choices=['initial-parity']);args=p.parse_args();initial_parity()
