"""Strict test entry point: independently recompute and lock validation selection."""
import json
from pathlib import Path
import benchmark as study
from benchmark import ROOT, AREA
from prepare_transformer_data import digest
from native_checkpoint_selection_v1 import freeze, info
from verify_study import check_selection


def main():
    registration=json.loads((AREA/'test-gate-registration.json').read_text())
    for item in registration['sources']:
        assert digest(ROOT/item['path'])==item['sha256'],item['path']
    assert digest(study.PLAN)==registration['benchmarkPlan']['sha256']
    check=check_selection()
    selection=json.loads((AREA/'selection.json').read_text())
    expected=dict(revision='hybrid-native-test-lock-v1',gateRegistration=info(AREA/'test-gate-registration.json'),
        selection=info(AREA/'selection.json'),plan=info(study.PLAN),
        test=study.verify_plan()['test'],selectedPipelines=selection['selectedPipelines'],
        representatives=selection['representatives'],validationRecomputed=True)
    lock=AREA/'test-execution-lock.json'
    if lock.exists():assert json.loads(lock.read_text())==expected
    else:
        assert not (AREA/'test').exists(), 'Test cannot predate its verified lock'
        freeze(lock,expected)
        freeze(AREA/'selection-verification.json',check)
    study.run('test')
    assert json.loads(lock.read_text())==expected
    assert digest(AREA/'selection.json')==expected['selection']['sha256']
    print('Fresh test completed under independently verified selection lock')


if __name__=='__main__':main()
