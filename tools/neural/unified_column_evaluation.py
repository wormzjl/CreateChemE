"""One validation set and one serial test set for convergence and runtime.

This entry point preserves previous campaigns and consumes their frozen model
selection. The legacy harness's 'benchmark' mode runs all three strategies on
every test input; no separate timing subset is selected.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile

from prepare_transformer_data import read_rows, digest, strict, stats
from prepare_generalized_evaluation import canonical_input_hash

ROOT = Path(__file__).resolve().parents[2]
PLAN = ROOT / 'tools/neural/unified-evaluation.json'
OUTPUT = ROOT / 'build/neural-transformer/unified-v1'
MODES = ('current', 'neural', 'neuralFirst')
LABELS = ('transformer', 'mlp', 'gen3-factorized', 'nearest-k1')
POLICY = {'mode': 'benchmark', 'workers': 1, 'neuralBudgetMillis': 2000,
          'deadlineMillis': 30000, 'neuralMaximumIterations': 16}


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def freeze(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('x', encoding='utf-8', newline='\n') as f:
        json.dump(value, f, indent=2, sort_keys=True, allow_nan=False)
        f.write('\n')


def prepare():
    if PLAN.exists():
        verify_plan()
        print('Existing unified plan verified.')
        return
    native = ROOT / 'build/neural-transformer/native-v1'
    selection = read(native / 'selection.json')
    candidates = read(native / 'candidates.json')
    sets = {}
    for name, relative, count in (
            ('validation', 'build/neural-transformer/native-v1/validation.jsonl', 405),
            ('test', 'build/neural-transformer/data-v2/fresh-holdout.jsonl', 252)):
        path = ROOT / relative
        rows = read_rows(path)
        if len(rows) != count or len({canonical_input_hash(r['input']) for r in rows}) != count:
            raise ValueError('Unexpected or duplicate evaluation inputs')
        sets[name] = {'path': relative, 'sha256': digest(path), 'cases': count}
    validation_keys = {canonical_input_hash(r['input']) for r in read_rows(ROOT / sets['validation']['path'])}
    if validation_keys.intersection(canonical_input_hash(r['input']) for r in read_rows(ROOT / sets['test']['path'])):
        raise ValueError('Validation/test overlap')
    if sets['test']['sha256'] != selection['holdoutSha256']:
        raise ValueError('Test input changed since candidate selection')
    sources = {str(p.relative_to(ROOT)).replace('\\', '/'): digest(p) for p in (
        native / 'selection.json', native / 'candidates.json', native / 'parity.json',
        ROOT / 'tools/neural/V3ColumnTransformerInitializer.java',
        ROOT / 'tools/neural/V3GeneralTrainingProbe.java', ROOT / 'tools/neural/V3CandidateModels.java')}
    plan = {'revision': 'unified-column-evaluation-v1', 'createdUtc': datetime.now(timezone.utc).isoformat(),
        'sets': sets, 'testPolicy': POLICY, 'strategies': list(MODES), 'sources': sources,
        'selectedModel': selection['selectedNewArchitecture'], 'candidates': candidates['candidates'],
        'outputPath': str(OUTPUT.relative_to(ROOT)).replace('\\', '/'),
        'policy': 'Keep the original 405 validation inputs for selection. Measure convergence and runtime together on all 252 test inputs, serially, using one policy for every strategy/model. No 64-case subset.',
        'priorValidationSelectionNeuralBudgetMillis': 10000,
        'testSelectionAllowed': False,
        'priorTestExposure': 'These 252 inputs were already released for the native-v1 campaign. This unified run is a fixed-model follow-up, not another independent blind test.',
        'historicalCampaigns': ['build/neural-transformer/native-v1/fresh-*', 'build/neural-transformer/native-v1/benchmark-*']}
    freeze(PLAN, plan)
    verify_plan()
    print('Prepared: validation 405; unified serial test 252; neural 2000 ms; overall 30000 ms.')


def verify_plan():
    plan = read(PLAN)
    if plan['testPolicy'] != POLICY or plan['strategies'] != list(MODES):
        raise ValueError('Unified policy changed')
    for info in plan['sets'].values():
        if digest(ROOT / info['path']) != info['sha256']:
            raise ValueError('Frozen input changed')
    for path, sha in plan['sources'].items():
        if digest(ROOT / path) != sha:
            raise ValueError('Frozen implementation or selection changed: ' + path)
    for info in plan['candidates'].values():
        if digest(ROOT / info['modelPath']) != info['modelSha256']:
            raise ValueError('Frozen model changed')
    return plan


def validate_run(rows, meta, source, model_sha, source_sha):
    expected = {str(r['id']): canonical_input_hash(r['input']) for r in source}
    actual = {str(r['id']): canonical_input_hash(r['input']) for r in rows}
    if len(expected) != len(source) or len(rows) != len(source) or len(actual) != len(rows) or actual != expected:
        raise ValueError('Incomplete, duplicate or mismatched test population')
    if any(meta.get(k) != v for k, v in POLICY.items()):
        raise ValueError('Mixed evaluation budgets or concurrent timing run')
    if meta.get('completed') != len(source) or meta.get('modelSha256') != model_sha or meta.get('sourceSha256') != source_sha:
        raise ValueError('Incomplete or unfrozen run')
    if any(any(not isinstance(r.get(mode), dict) for mode in MODES) for r in rows):
        raise ValueError('Every case must contain all three strategies')


def summarize(rows):
    result = {'cases': len(rows), 'qualified': sum(strict(r) for r in rows),
        'advisoryOnly': sum(r.get('success') is True and not strict(r) for r in rows),
        'failed': sum(r.get('success') is not True for r in rows)}
    for name in ('ms', 'cpuMillis', 'allocatedBytes'):
        values = [r[name] for r in rows if r.get(name) is not None]
        if values:
            result[name] = stats(values)
    iterations = [r['diagnostics']['newtonIterations'] for r in rows
                  if r.get('diagnostics', {}).get('newtonIterations') is not None]
    if iterations:
        result['newtonIterations'] = stats(iterations)
    return result


def report():
    plan = verify_plan()
    source = read_rows(ROOT / plan['sets']['test']['path'])
    result = {'revision': plan['revision'], 'planSha256': digest(PLAN), 'cases': len(source),
              'policy': POLICY, 'models': {}, 'priorTestExposure': plan['priorTestExposure']}
    for label in LABELS:
        folder = OUTPUT / label
        rows = read_rows(folder / 'evaluation.jsonl')
        meta = read(folder / 'run.json')
        validate_run(rows, meta, source, plan['candidates'][label]['modelSha256'], plan['sets']['test']['sha256'])
        variants = {mode: [{**r, **r[mode]} for r in rows] for mode in MODES}
        common = [r for r in rows if strict({**r, **r['current']}) and strict({**r, **r['neuralFirst']})]
        comparison = {'firstGains': sum(strict(a) and not strict(b) for a, b in zip(variants['neuralFirst'], variants['current'])),
            'firstLosses': sum(strict(b) and not strict(a) for a, b in zip(variants['neuralFirst'], variants['current'])),
            'firstMinusCurrentMillis': stats([r['neuralFirst']['ms'] - r['current']['ms'] for r in rows]),
            'commonQualifiedCases': len(common)}
        if common:
            comparison['firstMinusCurrentMillisCommonQualified'] = stats([r['neuralFirst']['ms'] - r['current']['ms'] for r in common])
        result['models'][label] = {'strategies': {m: summarize(v) for m, v in variants.items()},
            'paired': comparison, 'journalSha256': digest(folder / 'evaluation.jsonl'), 'run': meta}
    freeze(OUTPUT / 'summary.json', result)
    lines = ['# Unified column test: convergence and runtime', '',
        'One set of 252 cases supplies every convergence and runtime metric below. All strategies run serially with 2-second neural and 30-second whole-request budgets. The 405-case validation set remains separate for model selection.', '',
        '| Model | Strategy | Qualified / 252 | Advisory only | Failed | Elapsed ms, mean ± sample SD | CPU ms, mean ± sample SD |',
        '|---|---|---:|---:|---:|---:|---:|']
    fmt = lambda s: f"{s['mean']:.4g} ± {s['sampleSd']:.3g}" if s else 'unavailable'
    for label, record in result['models'].items():
        for mode, s in record['strategies'].items():
            lines.append(f"| {label} | {mode} | {s['qualified']} | {s['advisoryOnly']} | {s['failed']} | {fmt(s.get('ms'))} | {fmt(s.get('cpuMillis'))} |")
    lines += ['', 'All-case timing includes failures. Paired gains/losses, Newton iteration statistics and common-qualified timing differences are in summary.json. Each model has its own paired classical measurements; these repeated controls are not independent test cases.', '',
        'The prior 10-second convergence campaign and 64-case timing subset are historical diagnostics and are not pooled into this report. The models remain frozen. These test inputs were already released for the prior campaign, so this is a fixed-model follow-up rather than a new blind test.', '']
    with (OUTPUT / 'report.md').open('x', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(lines))
    print('Combined report: ' + str(OUTPUT / 'report.md'))


def run():
    plan = verify_plan()
    source = read_rows(ROOT / plan['sets']['test']['path'])
    for label in LABELS:
        folder = OUTPUT / label
        if folder.exists():
            # Resume only after a complete, valid model run. Never overwrite a
            # partial journal, and never reuse the old 64-case measurements.
            validate_run(read_rows(folder / 'evaluation.jsonl'), read(folder / 'run.json'), source,
                         plan['candidates'][label]['modelSha256'], plan['sets']['test']['sha256'])
            continue
        command = [str(ROOT / 'gradlew.bat'), 'generalNeuralExperiment', '-PgeneralMode=benchmark',
            '-PgeneralSource=' + plan['sets']['test']['path'], '-PgeneralOutput=' + str(folder.relative_to(ROOT)),
            '-PgeneralModel=' + plan['candidates'][label]['modelPath'], '-PgeneralWorkers=1',
            '-PgeneralNeuralBudgetMillis=2000', '-PgeneralDeadlineSeconds=30', '-PgeneralIterations=16', '--offline']
        subprocess.run(command, cwd=ROOT, check=True)
    report()
    archive()


def archive():
    verify_plan()
    if not (OUTPUT / 'summary.json').is_file():
        raise ValueError('Complete the combined report before archiving')
    destination = ROOT / '.neural-cache/unified-evaluation-v1'
    destination.mkdir(parents=True, exist_ok=True)
    entries = []
    sources = [(p, p.relative_to(OUTPUT).as_posix()) for p in sorted(OUTPUT.rglob('*')) if p.is_file()]
    sources += [(PLAN, 'unified-evaluation.json'), (Path(__file__), 'unified_column_evaluation.py')]
    path = destination / 'study.zip'
    with zipfile.ZipFile(path, 'x', compression=zipfile.ZIP_DEFLATED) as z:
        for p, name in sources:
            z.write(p, name)
            entries.append({'entry': name, 'sha256': digest(p)})
    with zipfile.ZipFile(path) as z:
        for entry in entries:
            if hashlib.sha256(z.read(entry['entry'])).hexdigest() != entry['sha256']:
                raise ValueError('Archive verification failed')
    freeze(destination / 'manifest.json', {'archiveSha256': digest(path), 'entries': entries})


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('prepare', 'verify', 'run', 'report', 'archive'))
    args = parser.parse_args()
    {'prepare': prepare, 'verify': verify_plan, 'run': run, 'report': report, 'archive': archive}[args.command]()
