"""Run exactly the registered fits, verify pairing, then export fixed checkpoints."""
from common import *
from register_followup import verify_plan
import argparse
import os
import subprocess
from export_models import export
from native import parity


def verify_fit(arm,seed):
    plan=verify_plan();directory=OUT/'fits'/f'{arm}-{seed}';report=read(directory/'report.json')
    assert report['complete'] and report['arm']==arm and report['seed']==seed
    assert report['optimizerSteps']==4640 and report['planSha256']==digest(OUT/'training-plan.json')
    assert report['dataSha256']==digest(OUT/f"{plan['arms'][arm]['cohort']}.jsonl")
    assert [r['step'] for r in report['checkpoints']]==plan['training']['checkpoints']
    for checkpoint in report['checkpoints']:
        assert digest(ROOT/checkpoint['file']['path'])==checkpoint['file']['sha256']
        metadata=read(directory/f"checkpoint-{checkpoint['step']}.json")
        assert metadata['planSha256']==report['planSha256'] and metadata['initialWeightsSha256']==report['initialWeightsSha256']
    return report


def verify_pairing():
    plan=verify_plan();reports={f'{arm}-{seed}':verify_fit(arm,seed) for seed in plan['training']['seeds'] for arm in plan['arms']}
    for seed in plan['training']['seeds']:
        baseline=reports[f'C-{seed}']
        for arm in ('T','F','K'):
            other=reports[f'{arm}-{seed}'];assert other['minibatchOrderSha256']==baseline['minibatchOrderSha256']
            for a,b in zip(baseline['checkpoints'],other['checkpoints']):
                assert a['minibatchOrderSha256']==b['minibatchOrderSha256'] and a['perCasePresentationCounts']==b['perCasePresentationCounts']
        for arm in ('T','D'):assert reports[f'{arm}-{seed}']['initialWeightsSha256']==baseline['initialWeightsSha256']
        c={r['step']:r for r in baseline['checkpoints']};d={r['step']:r for r in reports[f'D-{seed}']['checkpoints']}
        assert set(c[4160]['perCasePresentationCounts'].values())=={160}
        assert set(d[4640]['perCasePresentationCounts'].values())=={160}
        assert set(c[4640]['perCasePresentationCounts'].values())=={178,179}
        assert set(d[4160]['perCasePresentationCounts'].values())=={143,144}
        for row in read_rows(OUT/'N804.jsonl'):assert c[4160]['perCasePresentationCounts'][row['id']]==d[4640]['perCasePresentationCounts'][row['id']]==160
    for arm in plan['arms']:
        assert reports[f'{arm}-20260911']['initialWeightsSha256']==reports[f'{arm}-20260912']['initialWeightsSha256']
    return reports


def train_all():
    plan=verify_plan();logs=OUT/'logs';logs.mkdir(exist_ok=True)
    for seed in plan['training']['seeds']:
        for arm in plan['armOrder']:
            directory=OUT/'fits'/f'{arm}-{seed}'
            if directory.exists():verify_fit(arm,seed);print(f'VERIFIED completed fit {arm}-{seed}',flush=True);continue
            command=[sys.executable,str(ROOT/'tools/trace-followup/train.py'),arm,str(seed)]
            with (logs/f'fit-{arm}-{seed}.log').open('x',encoding='utf-8') as stream:
                process=subprocess.Popen(command,env=dict(os.environ,CUBLAS_WORKSPACE_CONFIG=':4096:8'),cwd=ROOT,
                    stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace')
                for line in process.stdout:stream.write(line);stream.flush();print(line,end='',flush=True)
                if process.wait():raise RuntimeError('Registered fit failed; preserve evidence and diagnose before continuing.')
            verify_fit(arm,seed)
    reports=verify_pairing();freeze(OUT/'fit-verification.json',dict(passed=True,completeFits=10,checkpoints=30,
        pairedStreamsVerified=True,exactExposureVerified=True,commonIncumbentInitializationVerified=True,
        plan=info(OUT/'training-plan.json'),reports=[info(OUT/'fits'/name/'report.json') for name in reports]))
    print('All ten fits and thirty checkpoints complete; paired streams and exposure verified.',flush=True)


def export_all():
    plan=verify_plan();verify_pairing();models=dict(plan['controls']);torch.set_num_threads(4)
    for seed in plan['training']['seeds']:
        for arm in plan['armOrder']:
            config=plan['arms'][arm]
            for step in plan['training']['checkpoints']:
                name=f'{arm}-{seed}-s{step}';path=OUT/'fits'/f'{arm}-{seed}'/f'checkpoint-{step}.pt'
                checkpoint=torch.load(path,map_location='cpu',weights_only=True)
                assert checkpoint['report']['planSha256']==digest(OUT/'training-plan.json') and checkpoint['report']['optimizerSteps']==step
                model=pilot.ColumnModel('transformer',inputs=config['inputs']);model.load_state_dict(checkpoint['state_dict']);model.eval()
                directory=OUT/'models'/name
                if directory.exists():raise FileExistsError('Exports are immutable; diagnose an existing partial export rather than overwrite it.')
                item=export(model,checkpoint['normalization'],config['layout'],directory,'trace-followup/'+name,path,OUT/'training-plan.json')
                parity(directory,config['layout'],checkpoint['normalization'],checkpoint['state_dict'])
                models[name]=dict(**item,checkpoint=info(path),precisionCheck=info(directory/'precision-check.json'),
                    arm=arm,seed=seed,step=step,layout=config['layout'],profileSelectionScore=checkpoint['report']['checkpoint']['profileSelectionScore'])
    freeze(OUT/'models.json',models)
    print(json.dumps({'exportedNewCheckpoints':30,'frozenControls':2,'allNativeParityPassed':True}),flush=True)


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('mode',choices=['train','export']);args=p.parse_args()
    train_all() if args.mode=='train' else export_all()
