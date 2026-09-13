"""Replay CURRENT_ONLY on the 108 typed-infeasible ids and compare against the recorded classical control.

Two subcommands:

    python tools/path-dependent-infeasible/verify.py run --label changed
    python tools/path-dependent-infeasible/verify.py compare --label changed [--baseline baseline]

``run`` compiles only the self-contained V3 package (Gson is the sole classpath entry, so no stale project
bytecode can satisfy a dependency) plus the two tools helpers and this study's probe, then executes the probe
under the campaign's ten workers, 30 s request deadline and 4 GiB heap. ``compare`` writes ``verification.json``
with the three acceptance criteria. Nothing here is a benchmark; timings are recorded but not asserted.

Run ``run --label baseline`` from a checkout of the parent commit to get a measured before-state; otherwise the
comparison falls back to the recorded journal statuses in ``ids.json``.
"""
import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
STUDY = Path(__file__).resolve().parent
OUT = ROOT / 'build/path-dependent-infeasible'
JAVA_HOME = Path('C:/Program Files/Java/jdk-21.0.11/bin')
GSON = Path('C:/Users/wormz/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.10.1/'
            'b3add478d4382b78ea20b1671390a858002feb6c/gson-2.10.1.jar')
HELPERS = ['tools/neural/V3NeuralMvpProbe.java', 'tools/neural/V3BoundedEvaluation.java']
WORKERS = '10'
DEADLINE_SECONDS = '30'


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def rows(path):
    return [json.loads(line) for line in Path(path).read_text(encoding='utf-8').splitlines() if line.strip()]


def sources():
    package = sorted((ROOT / 'src/main/java/com/wormzjl/createcheme/science/column/v3').rglob('*.java'))
    helpers = [ROOT / name for name in HELPERS]
    probes = sorted((STUDY / 'java').glob('*.java'))
    for path in package + helpers + probes:
        assert path.exists(), path
    return package + helpers + probes


def compile_classes(label):
    classes = OUT / label / 'classes'
    if classes.exists():
        print(f'Reusing compiled classes at {classes}')
        return classes
    classes.mkdir(parents=True)
    command = [JAVA_HOME / 'javac.exe', '-J-Duser.language=en', '-J-Dfile.encoding=UTF-8',
               '-encoding', 'UTF-8', '-cp', GSON, '-d', classes, *sources()]
    subprocess.run([str(x) for x in command], cwd=ROOT, check=True)
    print(f'Compiled {len(sources())} sources into {classes}')
    return classes


def run(label):
    directory = OUT / label / 'run'
    assert not directory.exists(), f'{directory} already exists; never overwrite a recorded run'
    classes = compile_classes(label)
    classpath = os.pathsep.join(str(p) for p in (classes, ROOT / 'src/main/resources', GSON))
    command = [JAVA_HOME / 'java.exe', '-Xmx4g', '-cp', classpath,
               'com.wormzjl.createcheme.science.column.v3.V3ClassicalOnlyProbe',
               directory, STUDY / 'inputs-108.jsonl', WORKERS, DEADLINE_SECONDS]
    subprocess.run([str(x) for x in command], cwd=ROOT, check=True)
    meta = read(directory / 'run.json')
    assert meta['complete'] and meta['completed'] == 108, meta
    assert meta['scheduling']['distinctWorkerThreads'] == 10 and meta['scheduling']['terminated'], meta
    print(json.dumps({'label': label, 'cases': meta['completed'], 'seconds': meta['elapsedSeconds']}))


def statuses(label):
    return {row['id']: row for row in rows(OUT / label / 'run/classical.jsonl')}


def compare(label, baseline):
    ids = read(STUDY / 'ids.json')
    typed = ids['typedInfeasibleByClassicalControl']
    rescued = set(ids['pooledWithGenerationAndHybridMaps']['solvedStrictly'])
    after = statuses(label)
    assert set(after) == set(typed), 'the run must cover exactly the 108 recorded ids'
    before = statuses(baseline) if baseline else None

    # Criterion 1: no id in the population may still be typed INFEASIBLE_SPECIFICATION, because none of them
    # can reach a request-only gate (no draw exceeds its feed; every case authors cooling inside the static
    # bound). The 42 strictly-solved-elsewhere ids are called out separately because they are the proof that
    # the old verdict was wrong rather than merely unproven.
    still_infeasible = sorted(i for i in typed if after[i]['status'] == 'INFEASIBLE_SPECIFICATION')
    rescued_still_infeasible = sorted(i for i in still_infeasible if i in rescued)

    # Criterion 2: the substitute code and the hint must both be present wherever the old verdict was.
    retyped = sorted(i for i in typed if after[i]['status'] == 'NONCONVERGENCE')
    hinted = sorted(i for i in retyped if 'continuation path' in (after[i].get('failure') or ''))
    missing_hint = sorted(set(retyped) - set(hinted))
    gates = {}
    for case in typed:
        detail = after[case].get('failure') or ''
        gate = ('condenser-bound' if 'Q_cond0' in detail
                else 'condensation-cap' if 'arriving vapor can release' in detail
                else 'other')
        gates.setdefault(gate, []).append(case)

    # Criterion 3: nothing else moved. Against a measured baseline this is exact; against the journal it is a
    # status comparison only, since the journal kept no detail strings.
    unexpected = []
    if before is not None:
        for case in typed:
            was, now = before[case]['status'], after[case]['status']
            if was == now:
                continue
            if was == 'INFEASIBLE_SPECIFICATION' and now == 'NONCONVERGENCE':
                continue
            unexpected.append({'id': case, 'before': was, 'after': now})
        newly_accepted = sorted(i for i in typed
                                if before[i]['status'] != 'ACCEPTED' and after[i]['status'] == 'ACCEPTED')
    else:
        for case in typed:
            now = after[case]['status']
            if now != 'NONCONVERGENCE':
                unexpected.append({'id': case, 'before': 'INFEASIBLE_SPECIFICATION (journal)', 'after': now})
        newly_accepted = sorted(i for i in typed if after[i]['status'] == 'ACCEPTED')

    document = {
        'revision': 'path-dependent-infeasible-verification-v1',
        'label': label,
        'baseline': baseline or 'recorded journal statuses in ids.json',
        'population': len(typed),
        'statusHistogram': {status: sum(1 for i in typed if after[i]['status'] == status)
                            for status in sorted({after[i]['status'] for i in typed})},
        'criterion1_noneStillTypedInfeasible': {
            'passed': not still_infeasible,
            'stillInfeasible': still_infeasible,
            'strictlySolvedElsewhereStillInfeasible': rescued_still_infeasible,
            'strictlySolvedElsewhereCount': len(rescued),
        },
        'criterion2_hintPresentOnEveryRetypedCase': {
            'passed': bool(retyped) and not missing_hint,
            'retypedCount': len(retyped),
            'missingHint': missing_hint,
            'gateBreakdown': {gate: len(cases) for gate, cases in sorted(gates.items())},
        },
        'criterion3_nothingElseChanged': {
            'passed': not unexpected,
            'unexpectedTransitions': unexpected,
            'newlyAccepted': newly_accepted,
        },
        'wallSecondsThisRun': read(OUT / label / 'run/run.json')['elapsedSeconds'],
    }
    document['passed'] = all(document[key]['passed'] for key in document if key.startswith('criterion'))
    (STUDY / 'verification.json').write_text(json.dumps(document, indent=1) + '\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in document.items() if k != 'statusHistogram'}, indent=1)[:4000])
    print('statusHistogram', document['statusHistogram'])
    return 0 if document['passed'] else 1


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest='command', required=True)
    runner = sub.add_parser('run')
    runner.add_argument('--label', required=True)
    comparer = sub.add_parser('compare')
    comparer.add_argument('--label', required=True)
    comparer.add_argument('--baseline', default=None)
    args = parser.parse_args()
    if args.command == 'run':
        run(args.label)
        return 0
    return compare(args.label, args.baseline)


if __name__ == '__main__':
    sys.exit(main())
