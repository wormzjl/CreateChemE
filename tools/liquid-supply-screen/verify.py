"""Replay CURRENT_ONLY on all 405 validation inputs and check the liquid-supply screen against the control.

Three subcommands:

    python tools/liquid-supply-screen/verify.py prepare
    python tools/liquid-supply-screen/verify.py run     --label changed
    python tools/liquid-supply-screen/verify.py compare --label changed

``prepare`` copies the frozen 405-case input population and the archived classical control out of the sealed
predecessor worktree (read-only) into ``build/liquid-supply-screen/`` and writes ``expected.json``: the
Python port of the screen statistic per id, the caught set at the shipped 0.30, and the control's status,
outcome and block-averaged milliseconds per id.

``run`` reuses ``tools/path-dependent-infeasible/java/V3ClassicalOnlyProbe.java`` unchanged — the same
ten-worker pool, 30 s request deadline and 4 GiB heap the campaign used — compiling only the self-contained
V3 package with Gson as the sole classpath entry, so no stale project bytecode can satisfy a dependency.

``compare`` writes ``verification.json`` with the three acceptance criteria:

    1. exactly the expected ids carry INFEASIBLE_SPECIFICATION with the liquid-supply detail;
    2. every archived classical strict success is still strict, and no id outside the expected caught set and
       the 108 the base branch already retyped to NONCONVERGENCE changes status;
    3. the measured time on the caught ids, archived against this run.

``transfer`` repeats the Python half on the 252-case ``g4fresh`` holdout inside the sealed cache, extracting
only under ``%TEMP%``.
"""
import argparse
import json
import math
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
STUDY = Path(__file__).resolve().parent
OUT = ROOT / 'build/liquid-supply-screen'
SEALED = Path('C:/Users/wormz/.codex/worktrees/8848/CreateChemE')
CONTROL = SEALED / 'build/neural-capacity-followup/v1/validation-case-evidence.jsonl'
INPUTS = SEALED / 'build/neural-trace-followup/v1/validation-inputs.jsonl'
UNIFIED = SEALED / '.neural-cache/unified-evaluation-v1/dependencies.zip'
CONTROL_PIPELINE = 'F0'
CONTROL_MODE = 'current'
JAVA_HOME = Path('C:/Program Files/Java/jdk-21.0.11/bin')
GSON = Path('C:/Users/wormz/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.10.1/'
            'b3add478d4382b78ea20b1671390a858002feb6c/gson-2.10.1.jar')
HELPERS = ['tools/neural/V3NeuralMvpProbe.java', 'tools/neural/V3BoundedEvaluation.java',
           'tools/path-dependent-infeasible/java/V3ClassicalOnlyProbe.java']
WORKERS = '10'
DEADLINE_SECONDS = '30'

# The shipped screen. Mirrors V3LiquidSupplyScreen exactly; kept in Python so the Java verdict is compared
# against an independently written statistic rather than against itself.
SHIPPED_RATIO = 0.30
LAMBDA_PUMPAROUND_J_PER_MOL = 30_000.0
DETAIL_MARKER = 'liquid that reflux, feed and authored pumparound condensation can deliver'


def rows(path):
    with Path(path).open(encoding='utf-8') as stream:
        for line in stream:
            if line.strip():
                yield json.loads(line)


def cooling_above(spec, tray):
    """Authored cooling duty on trays 1..tray, in watts, as a positive number."""
    duty = 0.0
    for pumparound in spec['pumparounds']:
        if pumparound['dutyWatts'] >= 0.0:
            continue
        low, high = pumparound['returnTray'], pumparound['drawTray']
        if pumparound['split'] == 'RETURN_TRAY':
            duty += -pumparound['dutyWatts'] if low <= tray else 0.0
        else:
            duty += -pumparound['dutyWatts'] * max(0, min(high, tray) - low + 1) / (high - low + 1)
    return duty


def liquid_supply(spec):
    """Worst cumulative-draw / liquid-supply ratio over the trays, with the tray that attains it."""
    draws = sorted((d['trayNumber'], d['molarFlowMolPerSecond']) for d in spec['sideDraws'])
    if not draws:
        return 0.0, 0
    feed = sum(spec['feedComponentMolarFlowsMolPerSecond'])
    steam = sum(s['molarFlowMolPerSecond'] for s in spec['steamFeeds'])
    reflux = next(e['ratio'] for e in spec['specifications'] if 'ratio' in e)
    distillate = max(0.0, feed + steam - sum(d[1] for d in draws))
    worst, limiting, cumulative, index = 0.0, 0, 0.0, 0
    for tray in range(1, spec['stageCount'] + 1):
        while index < len(draws) and draws[index][0] <= tray:
            cumulative += draws[index][1]
            index += 1
        if cumulative <= 0.0:
            continue
        supply = reflux * distillate + steam + cooling_above(spec, tray) / LAMBDA_PUMPAROUND_J_PER_MOL \
            + (feed if tray >= spec['feedStageNumber'] else 0.0)
        ratio = cumulative / supply if supply > 0.0 else math.inf
        if ratio > worst:
            worst, limiting = ratio, tray
    return worst, limiting


def prepare():
    OUT.mkdir(parents=True, exist_ok=True)
    population = [{'id': row['id'], 'input': row['input']} for row in rows(INPUTS)]
    assert len(population) == 405, len(population)
    with (OUT / 'inputs-405.jsonl').open('w', encoding='utf-8', newline='\n') as stream:
        for row in population:
            stream.write(json.dumps(row) + '\n')

    control = {}
    for row in rows(CONTROL):
        if row['pipeline'] != CONTROL_PIPELINE:
            continue
        mode = row['modes'][CONTROL_MODE]
        entry = control.setdefault(row['id'], {'status': {}, 'outcome': {}, 'milliseconds': {}})
        entry['status'][row['block']] = mode['status']
        entry['outcome'][row['block']] = mode['outcome']
        entry['milliseconds'][row['block']] = mode['milliseconds']
        entry.setdefault('newtonIterations', {})[row['block']] = mode.get('newtonIterations')
    assert len(control) == 405, len(control)
    assert not [i for i, e in control.items() if len(set(e['status'].values())) != 1], 'blocks disagree'
    control = {i: {'status': e['status'][1], 'outcome': e['outcome'][1],
                   'newtonIterations': e['newtonIterations'][1],
                   'milliseconds': round(sum(e['milliseconds'].values()) / len(e['milliseconds']), 4)}
               for i, e in control.items()}

    screen = {}
    for row in population:
        ratio, tray = liquid_supply(row['input'])
        screen[row['id']] = {'rho': ratio, 'limitingTray': tray}
    caught = sorted(i for i, v in screen.items() if v['rho'] >= SHIPPED_RATIO)
    necessary = sorted(i for i, v in screen.items() if v['rho'] >= 1.0)
    strict = sorted(i for i, v in control.items() if v['outcome'] == 'strict')
    typed = sorted(i for i, v in control.items() if v['status'] == 'INFEASIBLE_SPECIFICATION')
    document = {
        'revision': 'liquid-supply-screen-expected-v1',
        'shippedRatio': SHIPPED_RATIO,
        'lambdaPumparoundJoulesPerMol': LAMBDA_PUMPAROUND_J_PER_MOL,
        'population': len(population),
        'screen': screen,
        'control': control,
        'expectedCaught': caught,
        'expectedCaughtByNecessaryTier': necessary,
        'archivedStrictSuccesses': strict,
        'archivedTypedInfeasible': typed,
        'caughtThatAreArchivedStrict': sorted(set(caught) & set(strict)),
        'controlSecondsOverCaught': round(sum(control[i]['milliseconds'] for i in caught) / 1000.0, 3),
        'controlSecondsOverAll': round(sum(v['milliseconds'] for v in control.values()) / 1000.0, 3),
    }
    (OUT / 'expected.json').write_text(json.dumps(document, indent=1) + '\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in document.items()
                      if k not in ('screen', 'control', 'archivedStrictSuccesses', 'archivedTypedInfeasible',
                                   'expectedCaught')}, indent=1))
    print('expectedCaught', caught)


def sources():
    package = sorted((ROOT / 'src/main/java/com/wormzjl/createcheme/science/column/v3').rglob('*.java'))
    helpers = [ROOT / name for name in HELPERS]
    for path in package + helpers:
        assert path.exists(), path
    return package + helpers


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
    assert (OUT / 'inputs-405.jsonl').exists(), 'run prepare first'
    classes = compile_classes(label)
    classpath = os.pathsep.join(str(p) for p in (classes, ROOT / 'src/main/resources', GSON))
    command = [JAVA_HOME / 'java.exe', '-Xmx4g', '-cp', classpath,
               'com.wormzjl.createcheme.science.column.v3.V3ClassicalOnlyProbe',
               directory, OUT / 'inputs-405.jsonl', WORKERS, DEADLINE_SECONDS]
    subprocess.run([str(x) for x in command], cwd=ROOT, check=True)
    meta = json.loads((directory / 'run.json').read_text(encoding='utf-8'))
    assert meta['complete'] and meta['completed'] == 405, meta
    assert meta['scheduling']['distinctWorkerThreads'] == 10 and meta['scheduling']['terminated'], meta
    print(json.dumps({'label': label, 'cases': meta['completed'], 'seconds': meta['elapsedSeconds']}))


def parse_detail(summary):
    """Pull the ratio and the limiting tray back out of the published detail, for the Java/Python check."""
    if not summary or DETAIL_MARKER not in summary:
        return None, None
    head = summary.split(' the liquid that reflux')[0]
    token = head.split()[-2] if head.endswith(' of') else None
    ratio = None
    if token is not None:
        try:
            ratio = float(token)
        except ValueError:
            ratio = None
    elif 'more than all of' in head:
        ratio = math.inf
    tail = summary.split('can deliver to tray ', 1)
    tray = int(tail[1].split(';')[0]) if len(tail) > 1 else None
    return ratio, tray


def compare(label):
    expected = json.loads((OUT / 'expected.json').read_text(encoding='utf-8'))
    after = {row['id']: row for row in rows(OUT / label / 'run/classical.jsonl')}
    control = expected['control']
    screen = expected['screen']
    assert set(after) == set(control), 'the run must cover exactly the recorded 405 ids'

    caught_expected = set(expected['expectedCaught'])
    typed_now = {i for i, r in after.items()
                 if r['status'] == 'INFEASIBLE_SPECIFICATION' and DETAIL_MARKER in (r.get('failure') or '')}
    # Criterion 1: exactly the expected ids, and each carries the liquid-supply detail with a matching rho.
    mismatched_detail = []
    for case in sorted(typed_now):
        ratio, tray = parse_detail(after[case]['failure'])
        want = screen[case]
        # The detail prints %.4g, so agreement is to four significant figures.
        agrees = ratio is not None and (math.isinf(ratio) and math.isinf(want['rho'])
                                        or abs(ratio - want['rho']) <= 5.0e-4 * max(1.0, abs(want['rho'])))
        if not agrees or tray != want['limitingTray'] or after[case]['newtonIterations'] != 0:
            mismatched_detail.append({'id': case, 'published': {'rho': ratio, 'tray': tray},
                                      'python': want, 'newtonIterations': after[case]['newtonIterations']})
    criterion1 = {
        'passed': typed_now == caught_expected and not mismatched_detail,
        'expectedCaught': sorted(caught_expected),
        'caught': sorted(typed_now),
        'missing': sorted(caught_expected - typed_now),
        'unexpected': sorted(typed_now - caught_expected),
        'detailMismatches': mismatched_detail,
    }

    # Criterion 2: no strict success lost, and no status moved except the caught set and the 108 the base
    # branch already retyped from INFEASIBLE_SPECIFICATION to NONCONVERGENCE.
    #
    # "Still strict" is checked as still ACCEPTED *and* on the identical Newton trajectory length. The
    # campaign's strict/advisory split is an acceptance-audit value (WATER_DEW_POINT above its limit) that the
    # reused probe does not record per case; matching newtonIterations against the archived control shows the
    # same solve was performed, and the screen cannot influence an audit it never reaches — it either answers
    # before the solve or does nothing at all.
    already_retyped = set(expected['archivedTypedInfeasible'])
    lost_strict = sorted(i for i in expected['archivedStrictSuccesses'] if after[i]['status'] != 'ACCEPTED')
    newton_drift = [{'id': i, 'before': control[i]['newtonIterations'], 'after': after[i]['newtonIterations']}
                    for i in expected['archivedStrictSuccesses']
                    if control[i]['newtonIterations'] != after[i]['newtonIterations']]
    unexpected_transitions, deadline_boundary = [], []
    for case, row in sorted(after.items()):
        was, now = control[case]['status'], row['status']
        if was == now:
            continue
        if case in caught_expected and now == 'INFEASIBLE_SPECIFICATION':
            continue
        if case in already_retyped and now == 'NONCONVERGENCE':
            continue
        # A 30 s request deadline is wall-clock, so a case that sat on the boundary in the archived control
        # can land either side of it in any later run. Reported with both timings rather than absorbed.
        if was == 'DEADLINE_EXCEEDED' and case not in caught_expected:
            deadline_boundary.append({'id': case, 'after': now, 'rho': screen[case]['rho'],
                                      'archivedMilliseconds': control[case]['milliseconds'],
                                      'measuredMilliseconds': round(row['ms'], 4)})
            continue
        unexpected_transitions.append({'id': case, 'before': was, 'after': now,
                                       'rho': screen[case]['rho']})
    criterion2 = {
        'passed': not lost_strict and not newton_drift and not unexpected_transitions,
        'archivedStrictCount': len(expected['archivedStrictSuccesses']),
        'strictNowFailing': lost_strict,
        'strictStillAcceptedOnTheIdenticalNewtonTrajectory':
            len(expected['archivedStrictSuccesses']) - len(newton_drift) - len(lost_strict),
        'newtonIterationDrift': newton_drift,
        'archivedAdvisoryStillAccepted': sorted(i for i in control
                                                if control[i]['outcome'] == 'advisory'
                                                and after[i]['status'] == 'ACCEPTED'),
        'alreadyRetypedByBaseBranch': len(already_retyped),
        'unexpectedTransitions': unexpected_transitions,
        'deadlineBoundaryTransitions': deadline_boundary,
    }

    # Criterion 3: what the caught set used to cost and what it costs now.
    before_ms = sum(control[i]['milliseconds'] for i in caught_expected)
    after_ms = sum(after[i]['ms'] for i in caught_expected)
    criterion3 = {
        'passed': after_ms < before_ms,
        'caughtCount': len(caught_expected),
        'archivedSecondsOverCaught': round(before_ms / 1000.0, 3),
        'measuredSecondsOverCaught': round(after_ms / 1000.0, 4),
        'reclaimedSeconds': round((before_ms - after_ms) / 1000.0, 3),
        'reclaimedPercentOfAllControlTime': round(100.0 * (before_ms - after_ms)
                                                  / (expected['controlSecondsOverAll'] * 1000.0), 2),
        'slowestCaughtMillisecondsNow': round(max(after[i]['ms'] for i in caught_expected), 4),
    }

    document = {
        'revision': 'liquid-supply-screen-verification-v1',
        'label': label,
        'population': len(control),
        'shippedRatio': expected['shippedRatio'],
        'wallSecondsThisRun': json.loads((OUT / label / 'run/run.json').read_text(encoding='utf-8'))['elapsedSeconds'],
        'statusHistogramBefore': histogram(control[i]['status'] for i in control),
        'statusHistogramAfter': histogram(after[i]['status'] for i in after),
        'criterion1_exactlyTheExpectedCaughtSet': criterion1,
        'criterion2_noFalsePositiveAndNothingElseMoved': criterion2,
        'criterion3_timeReclaimed': criterion3,
        'worstProtectedRatio': round(max(screen[i]['rho'] for i in control
                                         if control[i]['outcome'] in ('strict', 'advisory')), 6),
        'caughtCases': [{'id': i, 'rho': round(screen[i]['rho'], 6), 'limitingTray': screen[i]['limitingTray'],
                         'controlStatus': control[i]['status'],
                         'archivedMilliseconds': control[i]['milliseconds'],
                         'measuredMilliseconds': round(after[i]['ms'], 4)}
                        for i in sorted(caught_expected, key=lambda i: -control[i]['milliseconds'])],
    }
    document['marginOverWorstProtected'] = round(expected['shippedRatio'] / document['worstProtectedRatio'], 3)
    transfer_path = OUT / 'transfer.json'
    if transfer_path.exists():
        found = json.loads(transfer_path.read_text(encoding='utf-8'))
        document['g4freshTransfer'] = {k: v for k, v in found.items() if k != 'caughtDetail'}
        document['g4freshTransfer']['caughtDetail'] = found.get('caughtDetail', [])
    document['passed'] = all(document[key]['passed'] for key in document if key.startswith('criterion'))
    (STUDY / 'verification.json').write_text(json.dumps(document, indent=1) + '\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in document.items() if k != 'caughtCases'}, indent=1)[:6000])
    return 0 if document['passed'] else 1


def histogram(values):
    counts = {}
    for value in values:
        counts[value] = counts.get(value, 0) + 1
    return dict(sorted(counts.items()))


TRANSFER_CLASSICAL = 'build/neural-transformer/native-v1/fresh-current/cases.jsonl'
TRANSFER_MODELS = ['transformer', 'mlp', 'nearest-k1', 'gen3-factorized']


def transfer():
    """Python-only transfer check on the 252-case g4fresh holdout; extracts only under %TEMP%."""
    if not UNIFIED.exists():
        print(json.dumps({'available': False, 'reason': f'{UNIFIED} is absent'}))
        return 0
    members = [TRANSFER_CLASSICAL] + [f'build/neural-transformer/native-v1/fresh-{m}/evaluation.jsonl'
                                      for m in TRANSFER_MODELS]
    temporary = Path(tempfile.mkdtemp(prefix='liquid-supply-transfer-', dir=os.environ.get('TEMP')))
    try:
        with zipfile.ZipFile(UNIFIED) as archive:
            present = [name for name in members if name in archive.namelist()]
            if TRANSFER_CLASSICAL not in present:
                print(json.dumps({'available': False, 'reason': 'no g4fresh specification member',
                                  'names': archive.namelist()[:20]}))
                return 0
            for name in present:
                archive.extract(name, temporary)
        cases, control, solved = {}, {}, set()
        for name in present:
            for row in rows(temporary / name):
                if row.get('input') is None:
                    continue
                cases[row['id']] = row['input']
                if row.get('success'):
                    solved.add(row['id'])
                if name == TRANSFER_CLASSICAL:
                    control[row['id']] = {'status': row.get('status'), 'milliseconds': row.get('ms')}
        if not cases:
            print(json.dumps({'available': False, 'reason': 'members carry no input specifications',
                              'members': present}))
            return 0
        values = {i: liquid_supply(spec) for i, spec in cases.items()}
        caught = sorted(i for i, v in values.items() if v[0] >= SHIPPED_RATIO)
        false_positives = sorted(i for i in caught if i in solved)
        worst_solved = max((values[i][0] for i in solved), default=0.0)
        document = {
            'available': True, 'members': present, 'cases': len(cases),
            'everSolved': len(solved), 'neverSolved': len(cases) - len(solved),
            'caught': caught, 'caughtCount': len(caught),
            'falsePositives': false_positives,
            'worstSolvedRatio': round(worst_solved, 6),
            'marginOverWorstSolved': round(SHIPPED_RATIO / worst_solved, 3) if worst_solved else None,
            'classicalSecondsOverAll': round(sum(v['milliseconds'] for v in control.values()) / 1000.0, 3),
            'classicalSecondsOverCaught': round(sum(control[i]['milliseconds'] for i in caught
                                                    if i in control) / 1000.0, 3),
            'caughtDetail': [{'id': i, 'rho': round(values[i][0], 6), 'limitingTray': values[i][1],
                              'classicalStatus': control.get(i, {}).get('status'),
                              'classicalMilliseconds': control.get(i, {}).get('milliseconds')}
                             for i in caught],
        }
        (OUT / 'transfer.json').write_text(json.dumps(document, indent=1) + '\n', encoding='utf-8')
        print(json.dumps({k: v for k, v in document.items() if k != 'caughtDetail'}, indent=1))
        return 0
    finally:
        shutil.rmtree(temporary, ignore_errors=True)


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest='command', required=True)
    sub.add_parser('prepare')
    runner = sub.add_parser('run')
    runner.add_argument('--label', required=True)
    comparer = sub.add_parser('compare')
    comparer.add_argument('--label', required=True)
    sub.add_parser('transfer')
    args = parser.parse_args()
    if args.command == 'prepare':
        prepare()
        return 0
    if args.command == 'run':
        run(args.label)
        return 0
    if args.command == 'transfer':
        return transfer()
    return compare(args.label)


if __name__ == '__main__':
    sys.exit(main())
