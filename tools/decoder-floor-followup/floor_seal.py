"""Package the committed evidence and record what stays outside git.

The raw journals are about 25 MiB per run and are not committed. What is committed is the per-case
outcome, status, cost and stop evidence for every pipeline and block, the reference profile evidence,
and the hashes of the full journals so a later audit can bind them to the preserved build directory.
"""
from floor_common import *
import argparse
import gzip
import hashlib

EVIDENCE = ROOT / 'tools/decoder-floor-followup/evidence'


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
    manifest = dict(
        revision='decoder-floor-evidence-v1', studyPlan=info(OUT / 'study-plan.json'),
        analysis=info(OUT / 'validation-analysis.json'), summary=info(ROOT / 'tools/decoder-floor-followup/summary.json'),
        committedEvidence=entries,
        committedBytes=sum((ROOT / entry['path']).stat().st_size for entry in entries),
        uncommittedJournals=[dict(run=value['run'], evaluation=value['evaluation'],
                                  evaluationBytes=(ROOT / value['evaluation']['path']).stat().st_size)
                             for value in analysis['runMetadata'].values()],
        uncommittedDecodeDumps=[info(OUT / 'preflight/decode' / f'{name}.jsonl') for name in ORDER],
        restoration='Committed files hold per-case outcome, status, cost and stop evidence plus the reference '
                    'profile evidence and per-case decoded-seed digests. The complete journals and decoded seeds '
                    'stay under build/neural-decoder-floor/v1; preserve that directory to re-derive anything else.')
    freeze(EVIDENCE.parent / 'cache-manifest.json', manifest)
    print(json.dumps(dict(files=len(entries), committedMiB=round(manifest['committedBytes'] / 1048576, 2))), flush=True)


if __name__ == '__main__':
    argparse.ArgumentParser().parse_args()
    seal()
