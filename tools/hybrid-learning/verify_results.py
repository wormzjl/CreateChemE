"""Refuse sealing when reports, journals, selection or pipeline bindings disagree."""
import json
from pathlib import Path
import report as analysis
from benchmark import ROOT, AREA, PLAN, verify_plan
from verify_study import check_selection
from prepare_transformer_data import read_rows, digest
from native_checkpoint_selection_v1 import info


def require_equal_outputs(summary,cases,text):
    """Pure equality boundary also used with copied synthetic fixtures in tests."""
    if json.loads((AREA/'summary.json').read_text())!=summary:
        raise ValueError('Stored summary differs from fresh reconstruction; preserve the mismatch')
    if read_rows(AREA/'case-map.jsonl')!=cases:
        raise ValueError('Stored case map differs from fresh reconstruction; preserve the mismatch')
    if (AREA/'report.md').read_text(encoding='utf-8')!=text:
        raise ValueError('Stored report differs from fresh rendering; preserve the mismatch')


def verify_outputs():
    verify_plan();selection_check=check_selection()
    lock=json.loads((AREA/'test-execution-lock.json').read_text())
    assert lock['validationRecomputed'] and lock['selection']['sha256']==digest(AREA/'selection.json')
    assert lock['plan']['sha256']==digest(PLAN)
    selection=json.loads((AREA/'selection.json').read_text())
    assert lock['selectedPipelines']==selection['selectedPipelines'] and lock['representatives']==selection['representatives']
    gate=json.loads((AREA/'test-gate-registration.json').read_text())
    assert lock['gateRegistration']['sha256']==digest(AREA/'test-gate-registration.json')
    for item in gate['sources']:assert digest(ROOT/item['path'])==item['sha256']
    summary,cases,text=analysis.build_outputs()
    require_equal_outputs(summary,cases,text)
    return dict(passed=True,selection=selection_check['selection'],testExecutionLock=info(AREA/'test-execution-lock.json'),
        benchmarkPlan=info(PLAN),reports=[info(AREA/name) for name in ('summary.json','case-map.jsonl','report.md')],
        source=info(Path(__file__)),validationIndependentInputs=405,testIndependentInputs=252,blocks=2,
        summaryRecomputed=True,caseMapRecomputed=True,reportRerendered=True,storedOutputsEqual=True)


if __name__=='__main__':print(json.dumps(verify_outputs()))
