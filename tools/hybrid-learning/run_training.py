"""Run the six registered fits sequentially, retaining output per fit."""
import json
from pathlib import Path
import subprocess
import sys
import time

area=Path('build/neural-hybrid-learning/v1')
plan=json.loads((area/'training-plan.json').read_text())
logdir=area/'training-logs';logdir.mkdir(exist_ok=True)
for seed in plan['training']['seeds']:
    for dataset in ('N','Nplus1'):
        output=area/'fits'/f'{dataset}-{seed}'
        command=[sys.executable,'tools/hybrid-learning/train_hybrid.py','--dataset',dataset,
            '--data',plan['datasets'][dataset]['path'],'--plan',str(area/'training-plan.json'),
            '--anchors',*plan['anchors'],'--seed',str(seed),'--output',str(output)]
        print('START '+dataset+' '+str(seed),flush=True)
        with (logdir/f'{dataset}-{seed}.log').open('x') as log:
            process=subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
            for line in process.stdout:
                log.write(line);log.flush();print(line,end='',flush=True)
            code=process.wait()
            if code:raise RuntimeError(f'Fit exited {code}; preserve failure and do not automatically retry')
print('All six registered fits completed',flush=True)
