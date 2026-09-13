"""Package the committed evidence and record what stays outside git.

The raw journals are about 25 MiB per run and are not committed. What is committed is the per-case
outcome, status, cost and stop evidence for every pipeline and block, the reference profile evidence, the
per-case decoded-seed digests, the correction-trajectory summary of the bounded diagnostic, and the hashes
of the full journals so a later audit can bind them to the preserved build directory.
"""
from budget_common import *
import argparse
import gzip
import hashlib


def rows(path):
    with gzip.open(path, 'rt', encoding='utf-8') as stream:
        return [json.loads(line) for line in stream if line.strip()]


def write(path, values):
    assert not path.exists(), path
    path.parent.mkdir(parents=True, exist_ok=True)
    body = ''.join(json.dumps(value, sort_keys=True, allow_nan=False) + '\n' for value in values)
    # mtime zero so the committed bytes depend only on the evidence, not on when it was packed.
    with path.open('xb') as raw, gzip.GzipFile(filename='', mode='wb', fileobj=raw, compresslevel=9, mtime=0) as stream:
        stream.write(body.encode('utf-8'))
    return info(path)


def trajectory(case):
    """One traced case, without its per-iteration state: the trajectory, not the profile."""
    return dict(id=case['production']['id'], condition=case['production']['condition'],
                crawling=case['crawling'], stalled=case['stalled'], hopeless=case['hopeless'],
                reinserting=case['reinserting'], referenceProfile=case['referenceProfile'],
                referenceOmissions=case['referenceOmissions'],
                configurations={name: {key: value[key] for key in (
                    'status', 'converged', 'milliseconds', 'cpuMillis', 'seedZeroPhases',
                    'initialProjectedResidual', 'publishedNewtonIterations', 'attemptCount', 'wetPrepass',
                    'supportRefreshes', 'wetTrayRefreshes', 'retentionDeltas', 'totalIterations',
                    'terminalStopCode', 'terminalInterrupted', 'terminalIterations', 'terminalResiduals',
                    'terminalIterationElapsedMillis', 'terminalMilliseconds',
                    'contractionRatio', 'shortTrajectory', 'millisPerIteration')}
                    for name, value in (('production', case['production']), ('diagnostic', case['diagnostic']))})


def seal():
    analysis = read(OUT / 'validation-analysis.json')
    cases = rows(OUT / 'validation-case-evidence.jsonl.gz')
    profiles = rows(OUT / 'validation-profile-evidence.jsonl.gz')
    entries = []
    for block in BLOCKS:
        for name in ORDER:
            subset = sorted((r for r in cases if r['block'] == block and r['pipeline'] == name),
                            key=lambda r: r['id'])
            assert len(subset) == 405, (block, name)
            entries.append(write(EVIDENCE / f'case-block{block}-{name}.jsonl.gz', subset))
    entries.append(write(EVIDENCE / 'profile-evidence.jsonl.gz',
                         sorted(profiles, key=lambda r: (r['block'], r['pipeline'], r['id']))))
    for name in ORDER:
        entries.append(write(EVIDENCE / f'decode-seed-digests-{name}.jsonl.gz', [
            dict(id=row['id'], supported=row['supported'],
                 seedSha256=None if row['seed'] is None else hashlib.sha256(
                     json.dumps(row['seed'], sort_keys=True, separators=(',', ':')).encode()).hexdigest())
            for row in read_rows(OUT / 'preflight/decode' / f'{name}.jsonl')]))
    diagnostic = read(DIAGNOSTIC_OUT / 'trace-analysis.json')
    entries.append(write(EVIDENCE / 'trace-trajectories.jsonl.gz',
                         [trajectory(diagnostic['perCase'][case]) for case in sorted(diagnostic['perCase'])]))
    manifest = dict(
        revision='neural-budget-evidence-v1', studyPlan=info(OUT / 'study-plan.json'),
        analysis=info(OUT / 'validation-analysis.json'), summary=info(ROOT / 'tools/neural-budget/summary.json'),
        diagnostic=info(DIAGNOSTIC_OUT / 'trace-analysis.json'),
        committedEvidence=entries,
        committedBytes=sum((ROOT / entry['path']).stat().st_size for entry in entries),
        uncommittedJournals=[dict(run=value['run'], evaluation=value['evaluation'],
                                  evaluationBytes=(ROOT / value['evaluation']['path']).stat().st_size)
                             for value in analysis['runMetadata'].values()],
        uncommittedDecodeDumps=[info(OUT / 'preflight/decode' / f'{name}.jsonl') for name in ORDER],
        uncommittedTraceJournals=[info(DIAGNOSTIC_OUT / 'trace' / config / 'trace.jsonl') for config in TRACE_CONFIGS],
        restoration='Committed files hold per-case outcome, status, cost and stop evidence, the reference profile '
                    'evidence, the per-case decoded-seed digests and the per-case correction trajectories of the '
                    'bounded diagnostic. The complete journals, decoded seeds and traced profiles stay under '
                    'build/neural-budget/v1; preserve that directory to re-derive anything else.')
    freeze(EVIDENCE.parent / 'cache-manifest.json', manifest)
    print(json.dumps(dict(files=len(entries), committedMiB=round(manifest['committedBytes'] / 1048576, 2))), flush=True)


if __name__ == '__main__':
    argparse.ArgumentParser().parse_args()
    seal()
