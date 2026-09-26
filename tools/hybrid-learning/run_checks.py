"""Complete export parity checks before any corrected holdout campaign."""
import json
import os
from pathlib import Path
import subprocess
import sys
import numpy as np
import torch
import train_hybrid as h
from export_hybrid import fixture_forward
from native_checkpoint_selection_v1 import freeze, info

ROOT=Path(__file__).resolve().parents[2];AREA=ROOT/'build/neural-hybrid-learning/v1'
env=dict(os.environ,JAVA_HOME='C:/Program Files/Java/jdk-21.0.11')
torch.set_num_threads(2)
plan=json.loads((AREA/'training-plan.json').read_text());anchors=h.load_anchors([ROOT/p for p in plan['anchors']])
train={r['id']:r for r in h.read_rows(ROOT/plan['datasets']['N']['path']) if r['split']=='train'}
for seed in plan['training']['seeds']:
    for dataset in ('N','Nplus1'):
        name=f'{dataset}-{seed}';directory=AREA/'models'/name
        parity=directory/'java-parity.json'
        if not parity.exists():
            command=['gradlew.bat','-I','tools/hybrid-learning/hybrid.gradle','hybridLearning',
                '-PhybridMain=com.wormzjl.createcheme.science.column.v3.V3HybridParityCheck',
                '-PhybridArgs='+','.join(str(p.relative_to(ROOT)).replace('\\','/') for p in (directory/'model.json',directory/'fixture.json',parity)),
                '--console=plain']
            result=subprocess.run(command,env=env,capture_output=True,text=True)
            (directory/'java-parity.log').write_text(result.stdout+result.stderr)
            if result.returncode:raise RuntimeError(name+' native parity failed; inspect saved output')
        record=json.loads(parity.read_text());assert record['passed'] and record['exactMasks']
        cp=torch.load(AREA/'fits'/name/'model.pt',map_location='cpu',weights_only=True)
        model=h.pilot.ColumnModel('transformer',inputs=185);model.load_state_dict(cp['state_dict']);model.eval()
        fixtures=json.loads((directory/'fixture.json').read_text());rows=[train[r['id']] for r in fixtures]
        batch=h.tensors(rows,cp['normalization'],anchors,cp['report']['branchesSeen'],'cpu')
        with torch.no_grad():raw,branch=h.forward(model,batch,cp['normalization'])
        raw=raw.numpy();branch=branch.numpy();worst=0.;branch_error=0.;mask_changes=0
        for i,(r,fixture) in enumerate(zip(rows,fixtures)):
            count=r['input']['stageCount']+2
            worst=max(worst,float(np.max(np.abs(raw[i,:count]-fixture['raw']))))
            branch_error=max(branch_error,float(np.max(np.abs(branch[i]-fixture['branchLogits']))))
            chosen=h.base.BRANCHES[int(branch[i,:2].argmax())]
            mask_changes+=int(chosen!=fixture['branch'])
            decoded=h.factor.decode(r['input'],raw[i,:count],chosen,.02)
            for phase in ('liquid','vapor'):
                mask_changes+=int(np.count_nonzero((decoded[phase]==0)!=(np.asarray(fixture['decoded'][phase])==0)))
            mask_changes+=int(np.count_nonzero(decoded['wetTrays']!=fixture['decoded']['wetTrays']))
        freeze(directory/'precision-check.json',dict(passed=True,model=info(directory/'model.json'),fixtures=info(directory/'fixture.json'),
            javaParity=info(parity),cpuFloat32VsFloat64MaximumRawDifference=worst,
            cpuFloat32VsFloat64MaximumBranchDifference=branch_error,maskDifferences=mask_changes,
            interpretation='Native tolerance gate uses double Python inputs; float32 differences are separately disclosed.'))
        print(json.dumps({'checked':name,'nativeRawMaximumDifference':record['maximumRawDifference'],'float32MaskDifferences':mask_changes}),flush=True)
print('All six exports checked')
