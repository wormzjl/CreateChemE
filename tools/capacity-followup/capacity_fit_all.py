"""Execute only registered fits and export their predefined checkpoints."""
from capacity_common import *
from capacity_register import verify_plan
from capacity_export import export, parity
import argparse
import os
import subprocess


def verify_fit(arm,seed):
    plan=verify_plan();directory=OUT/'fits'/f'{arm}-{seed}';report=read(directory/'report.json')
    assert report['complete'] and report['arm']==arm and report['seed']==seed
    assert report['optimizerSteps']==plan['training']['updates'] and report['freshOptimizer']
    assert report['planSha256']==digest(OUT/'training-plan.json')
    assert report['dataSha256']==digest(SOURCE/'N804.jsonl')
    assert report['layers']==plan['arms'][arm]['layers'] and report['parameters']==plan['arms'][arm]['parameters']
    assert [c['step'] for c in report['checkpoints']]==plan['training']['checkpoints']
    for checkpoint in report['checkpoints']:
        assert digest(ROOT/checkpoint['file']['path'])==checkpoint['file']['sha256']
        assert set(checkpoint['perCasePresentationCounts'].values())=={checkpoint['step']//26}
    return report


def verify_pairing():
    plan=verify_plan()
    reports={f'{arm}-{seed}':verify_fit(arm,seed) for seed in plan['training']['seeds'] for arm in plan['arms']}
    for seed in plan['training']['seeds']:
        small,large=reports[f'L2-{seed}'],reports[f'L4-{seed}']
        assert small['initialMetrics']==large['initialMetrics']
        for a,b in zip(small['checkpoints'],large['checkpoints']):
            assert a['minibatchOrderSha256']==b['minibatchOrderSha256']
            assert a['perCasePresentationCounts']==b['perCasePresentationCounts']
    for arm in plan['arms']:
        assert len({reports[f'{arm}-{seed}']['initialWeightsSha256'] for seed in plan['training']['seeds']})==1
    return reports


def fit():
    plan=verify_plan()
    for seed in plan['training']['seeds']:
        for arm in plan['armOrder']:
            name=f'{arm}-{seed}';directory=OUT/'fits'/name
            if directory.exists():
                verify_fit(arm,seed);print('Verified completed fit '+name,flush=True);continue
            log=OUT/'logs'/f'fit-{name}.log';log.parent.mkdir(parents=True,exist_ok=True)
            with log.open('x',encoding='utf-8') as stream:
                process=subprocess.Popen([sys.executable,str(ROOT/'tools/capacity-followup/capacity_train.py'),arm,str(seed)],
                    cwd=ROOT,env=dict(os.environ,CUBLAS_WORKSPACE_CONFIG=':4096:8'),stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace')
                for line in process.stdout:stream.write(line);stream.flush();print(line,end='',flush=True)
                if process.wait():raise RuntimeError('Capacity fit failed; preserve the partial trajectory and diagnose.')
            verify_fit(arm,seed)
    reports=verify_pairing()
    freeze(OUT/'fit-verification.json',dict(passed=True,fits=4,checkpoints=12,exactPairedStreams=True,
        identicalInitialPredictions=True,reports=[info(OUT/'fits'/name/'report.json') for name in reports]))
    print('Four capacity fits and twelve predefined checkpoints verified.',flush=True)


def export_all():
    plan=verify_plan();verify_pairing();torch.set_num_threads(4);models=dict(plan['controls'])
    for seed in plan['training']['seeds']:
        for arm in plan['armOrder']:
            for step in plan['training']['checkpoints']:
                name=f'{arm}-{seed}-s{step}';directory=OUT/'models'/name
                cp_path=OUT/'fits'/f'{arm}-{seed}'/f'checkpoint-{step}.pt'
                cp=torch.load(cp_path,map_location='cpu',weights_only=True)
                assert cp['report']['planSha256']==digest(OUT/'training-plan.json')
                model=initial_model(plan['arms'][arm]['layers']);model.load_state_dict(cp['state_dict']);model.eval()
                if directory.exists():raise FileExistsError('Existing export must be diagnosed, never overwritten: '+name)
                item=export(model,directory,name,cp_path,OUT/'training-plan.json')
                parity(directory,model)
                models[name]=dict(**item,checkpoint=info(cp_path),precisionCheck=info(directory/'precision-check.json'),
                    arm=arm,seed=seed,step=step,layers=len(model.blocks),
                    profileSelectionScore=cp['report']['checkpoint']['profileSelectionScore'])
    freeze(OUT/'models.json',models)
    print('All twelve capacity exports passed native parity.',flush=True)


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('mode',choices=['fit','export']);args=p.parse_args()
    fit() if args.mode=='fit' else export_all()
