"""Reproducible, bounded TRAIN-only probe of one native material-completion pass."""
from collections import Counter
from datetime import datetime, timezone
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import statistics
import subprocess
import zipfile

import native_generation_comparison as preceding
from prepare_generalized_evaluation import canonical_input_hash
from prepare_transformer_data import strict

ROOT = Path(__file__).resolve().parents[2]
AREA = ROOT / 'build/neural-hybrid/feasibility-v1'
PLAN = ROOT / 'tools/neural/hybrid-initializer-feasibility-plan.json'
CACHE = ROOT / '.neural-cache/hybrid-initializer-feasibility-v1'
FILES = ('V3MechanisticTransformerInitializer.java', 'V3MechanisticSeedProbe.java',
         'hybrid-initializer-feasibility.gradle', 'hybrid-initializer-feasibility-protocol.md',
         'hybrid_initializer_feasibility.py', 'test_hybrid_initializer_feasibility.py')
ARMS = ('raw', 'materialCompletion')
POLICY = {'split': 'train', 'cases': 20, 'workers': 10, 'treatments': list(ARMS),
          'mode': 'LNN_FIRST', 'requestDeadlineMillis': 30_000, 'neuralBudgetMillis': 2_000,
          'maximumIterations': 16, 'maximumMeasuredRequests': 40,
          'fittingAllowed': False, 'holdoutTuningAllowed': False, 'runtimePromotionAllowed': False}


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def rows(path):
    return [json.loads(line) for line in path.read_text(encoding='utf-8-sig').splitlines() if line.strip()]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def rel(path):
    return path.relative_to(ROOT).as_posix()


def encode(value):
    return json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + '\n'


def create(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('x', encoding='utf-8', newline='\n') as stream:
        stream.write(encode(value))


def sources():
    previous = read(preceding.PLAN)['sources']
    actual = {name: sha(ROOT / name) for name in previous}
    if actual != previous:
        raise ValueError('A preceding frozen implementation changed')
    actual.update({f'tools/neural/{name}': sha(ROOT / 'tools/neural' / name) for name in FILES})
    return actual


def select(source):
    eligible = [row for row in source if row['split'] == 'train'
                and row['labelProvenance']['eligibleForFitting'] and strict(row)]
    if len(eligible) != 805:
        raise ValueError('Unexpected retained TRAIN population')
    pool = [row for row in eligible if not row['input']['steamFeeds'] and not row['input']['sideDraws']]
    pool.sort(key=lambda row: (row['input']['stageCount'], row['id']))
    if len(pool) < 20:
        raise ValueError('Insufficient eligible dry/no-draw TRAIN inputs')
    selected = [pool[i * (len(pool) - 1) // 19] for i in range(20)]
    if len({canonical_input_hash(row['input']) for row in selected}) != 20:
        raise ValueError('Duplicate selected TRAIN input')
    return selected, len(pool)


def assets():
    if (AREA / 'assets.json').exists():
        return verify_assets()
    if AREA.exists() and any(AREA.iterdir()):
        raise ValueError('Partial assets exist; inspect before creating a new revision')
    preceding.verify_report()
    previous = read(preceding.PLAN)
    model = previous['models']['transformer']
    accuracy_path = ROOT / 'tools/neural/transformer-accuracy-plan.json'
    source = read(accuracy_path)['trainingData']
    for item in (model, source):
        if sha(ROOT / item['path']) != item['sha256']:
            raise ValueError('Changed model or original TRAIN source')
    selected, pool_count = select(rows(ROOT / source['path']))
    AREA.mkdir(parents=True)
    shutil.copyfile(ROOT / model['path'], AREA / 'model.json')
    with (AREA / 'fixtures.jsonl').open('x', encoding='utf-8', newline='\n') as stream:
        for row in selected:
            fixture = {'id': row['id'], 'split': 'train', 'input': row['input'],
                       'canonicalInputSha256': canonical_input_hash(row['input'])}
            stream.write(json.dumps(fixture, sort_keys=True, separators=(',', ':'), allow_nan=False) + '\n')
    old_manifest = ROOT / 'tools/neural/generation-comparison-cache-manifest.json'
    old_archive = ROOT / '.neural-cache/generation-comparison-v1/study.zip'
    if sha(old_archive) != read(old_manifest)['archiveSha256']:
        raise ValueError('Preceding comparison archive differs from its manifest')
    required = [accuracy_path, preceding.PLAN, old_manifest, old_archive, ROOT / source['path']]
    result = {
        'createdUtc': datetime.now(timezone.utc).isoformat(),
        'baseCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        'source': source, 'model': model, 'eligibleTrainColumns': 805,
        'dryNoDrawPoolColumns': pool_count, 'selectedColumns': 20,
        'selection': 'stageCount,id sort; floor(i*(n-1)/19), i=0..19; input coverage only',
        'requiredDependencies': {rel(path): sha(path) for path in required},
        'frozenAssets': {rel(AREA / name): sha(AREA / name) for name in ('model.json', 'fixtures.jsonl')},
        'cases': [{'id': row['id'], 'stageCount': row['input']['stageCount'],
                   'pumparounds': len(row['input']['pumparounds']),
                   'canonicalInputSha256': canonical_input_hash(row['input']),
                   'split': row['split'], 'labelProvenance': row['labelProvenance']} for row in selected],
    }
    create(AREA / 'assets.json', result)
    print(encode({'selected': 20, 'pool': pool_count, 'stages': [row['input']['stageCount'] for row in selected]}))
    return result


def verify_hashes(expected):
    for name, digest in expected.items():
        if sha(ROOT / name) != digest:
            raise ValueError('Changed frozen dependency: ' + name)


def verify_assets():
    value = read(AREA / 'assets.json')
    verify_hashes(value['requiredDependencies'])
    verify_hashes(value['frozenAssets'])
    selected, count = select(rows(ROOT / value['source']['path']))
    actual = rows(AREA / 'fixtures.jsonl')
    if count != value['dryNoDrawPoolColumns'] or [row['id'] for row in actual] != [row['id'] for row in selected]:
        raise ValueError('Selected TRAIN population changed')
    for a, b in zip(actual, selected):
        if a['split'] != 'train' or a['input'] != b['input'] or a['canonicalInputSha256'] != canonical_input_hash(b['input']):
            raise ValueError('Fixture differs from original TRAIN input')
    return value


def gradle(mode, output, log):
    command = [str(ROOT / 'gradlew.bat'), '--offline', '--no-daemon', '--console=plain',
               '-I', 'tools/neural/hybrid-initializer-feasibility.gradle', 'mechanisticSeedProbe',
               f'-PhybridMode={mode}', f'-PhybridFixtures={rel(AREA / "fixtures.jsonl")}',
               f'-PhybridModel={rel(AREA / "model.json")}', f'-PhybridOutput={rel(output)}',
               f'-PhybridPlan={rel(PLAN) if mode == "run" else "-"}']
    env = dict(os.environ, JAVA_HOME='C:/Program Files/Java/jdk-21.0.11')
    env['PATH'] = env['JAVA_HOME'] + '/bin;' + env.get('PATH', '')
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open('x', encoding='utf-8', newline='\n') as stream:
        process = subprocess.Popen(command, cwd=ROOT, env=env, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace')
        for line in process.stdout:
            stream.write(line)
            stream.flush()
            print(line, end='', flush=True)
        code = process.wait()
    if code:
        raise RuntimeError(f'Native {mode} exited {code}; retained {rel(log)}')


def check():
    verify_assets()
    number = len(list(AREA.glob('check-*-sources.json'))) + 1
    snapshot = sources()
    create(AREA / f'check-{number}-sources.json', snapshot)
    output = AREA / f'check-{number}.json'
    gradle('check', output, AREA / 'logs' / f'check-{number}.log')
    if sources() != snapshot or not read(output)['passed']:
        raise ValueError('Native preflight or source verification failed')
    create(AREA / f'check-{number}-verified.json', {'check': rel(output), 'sha256': sha(output),
                                                 'sourceSnapshot': rel(AREA / f'check-{number}-sources.json')})


def prepare():
    if PLAN.exists():
        return verify_plan()
    value = verify_assets()
    successful = sorted(AREA.glob('check-*-verified.json'), key=lambda p: int(p.name.split('-')[1]))
    if not successful:
        raise ValueError('Native preflight must pass before registration')
    check_info = read(successful[-1])
    snapshot = sources()
    if read(ROOT / check_info['sourceSnapshot']) != snapshot:
        raise ValueError('Implementation changed after native preflight')
    test_log = AREA / 'logs/python-checks.log'
    if not test_log.exists() or '\nOK' not in test_log.read_text(encoding='utf-8-sig'):
        raise ValueError('Focused analysis checks must pass before registration')
    evidence_paths = [AREA / 'assets.json', ROOT / check_info['check'],
                      ROOT / check_info['sourceSnapshot'], successful[-1], test_log]
    result = {'revision': 'hybrid-initializer-feasibility-v1', 'createdUtc': datetime.now(timezone.utc).isoformat(),
              'sources': snapshot, 'assets': value['frozenAssets'],
              'dependencies': value['requiredDependencies'],
              'preflight': {rel(path): sha(path) for path in evidence_paths},
              'policy': dict(POLICY)}
    create(PLAN, result)
    return result


def verify_plan():
    value = read(PLAN)
    if value['policy'] != POLICY:
        raise ValueError('Frozen feasibility policy changed')
    for field in ('sources', 'assets', 'dependencies', 'preflight'):
        verify_hashes(value[field])
    if value['sources'] != sources():
        raise ValueError('Frozen source population changed')
    verify_assets()
    return value


def run():
    verify_plan()
    output = AREA / 'campaign'
    if output.exists():
        results()
        return
    gradle('run', output, AREA / 'logs/campaign.log')
    results()


def stats(values):
    values = [float(value) for value in values if value is not None]
    if not values:
        return {'count': 0}
    if not all(math.isfinite(value) for value in values):
        raise ValueError('Nonfinite statistic')
    return {'count': len(values), 'mean': statistics.mean(values),
            'sampleSd': statistics.stdev(values) if len(values) > 1 else 0.0,
            'min': min(values), 'median': statistics.median(values), 'max': max(values)}


def analyze(fixtures, journal):
    expected = {row['id']: row for row in fixtures}
    actual = {row['id']: row for row in journal}
    if len(actual) != len(journal) or set(actual) != set(expected):
        raise ValueError('Incomplete or duplicate paired population')
    ordered = [actual[row['id']] for row in fixtures]
    for row in ordered:
        if row['split'] != 'train' or row['input'] != expected[row['id']]['input']:
            raise ValueError('Changed paired input')
        for arm in ARMS:
            if arm not in row:
                raise ValueError('Missing paired treatment')
            observation = row[arm]
            if not isinstance(observation.get('success'), bool) or not math.isfinite(observation['ms']) or observation['ms'] < 0:
                raise ValueError('Missing or invalid complete request')
    output = {'cases': len(ordered), 'scope': 'fixed TRAIN-only feasibility; not held-out generalization', 'treatments': {}}
    successes = {}
    for arm in ARMS:
        qualified = [row['id'] for row in ordered if strict({**row[arm], 'input': row['input']})]
        successes[arm] = set(qualified)
        observations = [row[arm] for row in ordered]
        output['treatments'][arm] = {
            'strict': len(qualified), 'strictIds': sorted(qualified),
            'advisoryOnly': sum(value['success'] for value in observations) - len(qualified),
            'failed': sum(not value['success'] for value in observations),
            'elapsedMillis': stats(value['ms'] for value in observations),
            'cpuMillis': stats(value.get('cpuMillis') for value in observations),
            'classicalFallbackObserved': sum(value.get('classicalFallback') is True for value in observations),
            'fallbackStatusUnavailable': sum('classicalFallback' not in value for value in observations),
            'statuses': dict(Counter(value['status'] for value in observations)),
        }
    gained = sorted(successes[ARMS[1]] - successes[ARMS[0]])
    lost = sorted(successes[ARMS[0]] - successes[ARMS[1]])
    output['paired'] = {'gained': gained, 'lost': lost,
                        'elapsedDeltaMillis': stats(row[ARMS[1]]['ms'] - row[ARMS[0]]['ms'] for row in ordered)}
    preparation = [row[ARMS[1]]['preprocessing'].get('evidence') for row in ordered]
    output['preparation'] = {
        'statuses': dict(Counter(value['status'] if value else 'NO_EVIDENCE' for value in preparation)),
        'elapsedMillis': stats(value['preparationMillis'] if value else None for value in preparation),
        'propertyCalls': stats(value['propertyCalls'] if value else None for value in preparation),
        'predictionAndPreparationMillis': stats(row[ARMS[1]]['preprocessing'].get('predictionAndPreparationMillis') for row in ordered),
        'declineDetails': dict(Counter(value['detail'] for value in preparation if value and value['status'] == 'DECLINED')),
    }
    diagnostics = [row['diagnostics'] for row in ordered]
    diagnostic_results = {'rawPredictionsAvailable': sum(value['rawSupported'] for value in diagnostics)}
    for stage in ('raw', 'prepared'):
        available = [value[stage] for value in diagnostics if stage in value]
        families = sorted({family for value in available for family in value.get('nativeFamilies', {})})
        diagnostic_results[stage] = {
            'maximumMaterialDefectOverInputComponentScale': stats(value['maximumMaterialDefectOverInputComponentScale'] for value in available),
            'nativeResidualAvailable': sum('nativeFamilies' in value for value in available),
            'nativeResidualUnavailable': dict(Counter(value['nativeResidualUnavailable'] for value in available if 'nativeResidualUnavailable' in value)),
            'nativeFamilyMaximumScaled': {family: stats(value.get('nativeFamilies', {}).get(family, {}).get('maximumAbsoluteScaled') for value in available) for family in families},
        }
    diagnostic_results['exactZeroPatternChanges'] = stats(value.get('profileChange', {}).get('exactZeroPatternChanges') for value in diagnostics)
    diagnostic_results['componentFlowRmseMovement'] = stats(value.get('profileChange', {}).get('componentFlowRmseChangeMolPerSecond') for value in diagnostics)
    diagnostic_results['maximumTemperatureChangeKelvin'] = stats(value.get('profileChange', {}).get('maximumTemperatureChangeKelvin') for value in diagnostics)
    output['diagnostics'] = diagnostic_results
    output['caseResults'] = [{
        'id': row['id'], 'stages': row['input']['stageCount'], 'pumparounds': len(row['input']['pumparounds']),
        'rawStrict': row['id'] in successes[ARMS[0]], 'completionStrict': row['id'] in successes[ARMS[1]],
        'rawMillis': row[ARMS[0]]['ms'], 'completionMillis': row[ARMS[1]]['ms'],
        'preparation': row[ARMS[1]]['preprocessing'].get('evidence'),
    } for row in ordered]
    return output


def results():
    plan = verify_plan()
    metadata = read(AREA / 'campaign/run.json')
    schedule = metadata.get('scheduling') or {}
    if (metadata.get('complete') is not True or metadata['completed'] != 20 or metadata['caseCount'] != 20
            or metadata['workers'] != 10 or metadata['treatments'] != list(ARMS)
            or schedule.get('terminated') is not True or schedule.get('completed') != 20
            or schedule.get('maximumInFlight') != 10 or schedule.get('distinctWorkerThreads') != 10
            or metadata['planSha256'] != sha(PLAN)
            or metadata['sourceSha256'] != sha(AREA / 'fixtures.jsonl')
            or metadata['modelSha256'] != sha(AREA / 'model.json')):
        raise ValueError('Incomplete or mismatched native execution')
    for field in ('mode', 'requestDeadlineMillis', 'neuralBudgetMillis', 'maximumIterations'):
        if metadata[field] != plan['policy'][field]:
            raise ValueError('Changed request policy: ' + field)
    value = analyze(rows(AREA / 'fixtures.jsonl'), rows(AREA / 'campaign/evaluation.jsonl'))
    value['evidence'] = {'planSha256': sha(PLAN), 'journalSha256': sha(AREA / 'campaign/evaluation.jsonl'),
                         'runSha256': sha(AREA / 'campaign/run.json'), 'modelSha256': sha(AREA / 'model.json')}
    return value


def report_text(value):
    lines = ['# Frozen Transformer material-completion feasibility', '',
             'Twenty fixed dry/no-side-draw TRAIN inputs; ten workers; one LNN_FIRST request per treatment.',
             'Each request retains the 2-second neural / 30-second request budget and 16 correction iterations.',
             'All cases contribute to timing. These are TRAIN observations, not held-out performance.', '',
             '| Treatment | Strict / 20 | Advisory | Failed | Mean elapsed, ms | Sample SD, ms | Fallback observed |',
             '|---|---:|---:|---:|---:|---:|---:|']
    for arm in ARMS:
        a = value['treatments'][arm]
        lines.append(f"| {arm} | {a['strict']} | {a['advisoryOnly']} | {a['failed']} | {a['elapsedMillis']['mean']:.2f} | {a['elapsedMillis']['sampleSd']:.2f} | {a['classicalFallbackObserved']} |")
    p = value['paired']
    lines += ['', f"Paired strict gains: {len(p['gained'])}; losses: {len(p['lost'])}.",
              f"Mean paired elapsed change: {p['elapsedDeltaMillis']['mean']:.2f} ms (sample SD {p['elapsedDeltaMillis']['sampleSd']:.2f} ms).",
              '', 'Preparation statuses: `' + json.dumps(value['preparation']['statuses'], sort_keys=True) + '`.', '',
              '| Diagnostic | Raw mean | Prepared mean | Raw / prepared count |', '|---|---:|---:|---:|']
    for name in ('maximumMaterialDefectOverInputComponentScale',):
        a, b = value['diagnostics']['raw'][name], value['diagnostics']['prepared'][name]
        am = format(a['mean'], '.6g') if a['count'] else 'unavailable'
        bm = format(b['mean'], '.6g') if b['count'] else 'unavailable'
        lines.append(f"| {name} | {am} | {bm} | {a['count']} / {b['count']} |")
    lines += ['', 'Native family residuals, unavailable reasons, support changes and paired IDs are retained in the summary.',
              'Native scaled maxima use each state\'s support and row scales; they are descriptive, not a common acceptance objective.', '',
              '| Case | Stages | Pumparounds | Raw strict | Completed strict | Raw ms | Completed ms |',
              '|---|---:|---:|---|---|---:|---:|']
    for row in value['caseResults']:
        lines.append(f"| {row['id']} | {row['stages']} | {row['pumparounds']} | {row['rawStrict']} | {row['completionStrict']} | {row['rawMillis']:.2f} | {row['completionMillis']:.2f} |")
    return '\n'.join(lines) + '\n'


def report():
    value = results()
    create(AREA / 'summary.json', value)
    with (AREA / 'report.md').open('x', encoding='utf-8', newline='\n') as stream:
        stream.write(report_text(value))
    return value


def verify_report():
    value = results()
    if value != read(AREA / 'summary.json') or report_text(value) != (AREA / 'report.md').read_text(encoding='utf-8'):
        raise ValueError('Report differs from frozen complete journals')
    return value


def seal():
    verify_report()
    plan = verify_plan()
    if CACHE.exists():
        raise ValueError('Sealed archive already exists')
    paths = {path for path in AREA.rglob('*') if path.is_file()}
    paths.update(ROOT / name for name in plan['sources'])
    paths.update((PLAN, ROOT / 'tools/neural/hybrid-transformer-investigation.md', ROOT / 'tools/neural/README.md'))
    CACHE.mkdir(parents=True)
    archive = CACHE / 'study.zip'
    entries = []
    with zipfile.ZipFile(archive, 'x', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as target:
        for path in sorted(paths):
            name = rel(path)
            target.write(path, name)
            entries.append({'path': name, 'bytes': path.stat().st_size, 'sha256': sha(path)})
    with zipfile.ZipFile(archive) as target:
        if target.testzip() is not None:
            raise ValueError('Archive CRC failure')
        for item in entries:
            data = target.read(item['path'])
            if len(data) != item['bytes'] or hashlib.sha256(data).hexdigest() != item['sha256']:
                raise ValueError('Archive entry mismatch')
    manifest = {'revision': 'hybrid-initializer-feasibility-v1', 'archive': rel(archive),
                'archiveBytes': archive.stat().st_size, 'archiveSha256': sha(archive), 'files': entries,
                'requiredPrecedingDependencies': plan['dependencies']}
    create(CACHE / 'manifest.json', manifest)
    create(ROOT / 'tools/neural/hybrid-initializer-feasibility-cache-manifest.json', manifest)
    shutil.copyfile(AREA / 'summary.json', ROOT / 'tools/neural/hybrid-initializer-feasibility-summary.json')
    shutil.copyfile(AREA / 'report.md', ROOT / 'tools/neural/hybrid-initializer-feasibility-results.md')
    print(encode({'archive': rel(archive), 'entries': len(entries), 'sha256': sha(archive)}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=('assets', 'check', 'prepare', 'verify-plan', 'run', 'report', 'verify-report', 'seal'))
    action = parser.parse_args().action
    globals()[action.replace('-', '_')]()
