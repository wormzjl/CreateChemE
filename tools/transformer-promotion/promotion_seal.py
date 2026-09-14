"""Commit the per-case evidence and the decoded-seed digests of the production-path campaign.

The full journals carry the per-mode event text and stay under `build/`; what is committed is what a
later reader needs to re-derive every count and every identity in results.md without this machine.
"""
from promotion_common import *
import gzip


def write(path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    with gzip.open(path, 'wt', encoding='utf-8', newline='\n', compresslevel=9) as stream:
        for row in rows:
            stream.write(json.dumps(row, sort_keys=True, separators=(',', ':'), allow_nan=False) + '\n')
    return info(path)


def compact(row):
    return dict(id=row['id'], condition=row['condition'], caseServiceMillis=row['caseServiceMillis'],
                queueWaitMillis=row['queueWaitMillis'],
                modes={mode: {key: value.get(key) for key in
                              ('outcome', 'status', 'milliseconds', 'newtonIterations',
                               'waterQualification', 'equilibriumQualified')}
                       for mode, value in row['modes'].items()})


def seal():
    entries = []
    for block in BLOCKS:
        rows = sorted(read_rows(run_directory(block) / 'evaluation.jsonl'), key=lambda r: r['id'])
        assert len(rows) == population().cases, block
        entries.append(write(EVIDENCE / f'case-block{block}.jsonl.gz', [compact(row) for row in rows]))
        entries.append(info(run_directory(block) / 'run.json')
                       if (run_directory(block) / 'run.json').is_relative_to(ROOT / 'tools') else
                       dict(path=str(run_directory(block) / 'run.json'),
                            sha256=digest(run_directory(block) / 'run.json')))
    entries.append(write(EVIDENCE / 'decode-seed-digests.jsonl.gz',
                         [dict(id=row['id'], supported=row['supported'], seedSha256=seed_digest(row['seed']))
                          for row in read_rows(decode_path())]))
    manifest = dict(revision='transformer-promotion-evidence-v1', frozen=verify_frozen(),
                    comparison=info(EVIDENCE / 'comparison.json'),
                    nativeCore=info(OUT / 'native-core-build.json') if (OUT / 'native-core-build.json')
                    .is_relative_to(ROOT / 'tools') else dict(path=str(OUT / 'native-core-build.json'),
                                                              sha256=digest(OUT / 'native-core-build.json')),
                    committedEvidence=[e for e in entries if e['path'].startswith('tools/')],
                    uncommittedJournals=[dict(path=str(run_directory(b) / 'evaluation.jsonl'),
                                              sha256=digest(run_directory(b) / 'evaluation.jsonl'),
                                              bytes=(run_directory(b) / 'evaluation.jsonl').stat().st_size)
                                         for b in BLOCKS]
                    + [dict(path=str(decode_path()), sha256=digest(decode_path()),
                            bytes=decode_path().stat().st_size)],
                    restoration='Committed files hold per-case outcome, status, cost, iteration count and water '
                                'grade for all three routes in both blocks, plus the per-case decoded-seed '
                                'digests. The full journals with per-mode event text stay under build/.')
    target = EVIDENCE / 'cache-manifest.json'
    if target.exists():
        target.unlink()
    freeze(target, manifest)
    print(json.dumps(dict(committed=[e['path'] for e in manifest['committedEvidence']]), indent=1))


if __name__ == '__main__':
    seal()
