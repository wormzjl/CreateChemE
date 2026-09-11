"""Preserve immutable study artifacts outside Gradle clean; verify every entry."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import zipfile
from benchmark import ROOT, AREA, verify_plan
from prepare_transformer_data import digest
from native_checkpoint_selection_v1 import freeze, info

CACHE=ROOT/'.neural-cache/hybrid-learning-v1'


def collect(mode):
    plan=verify_plan();paths=set()
    if mode=='training':
        paths.update(p for p in AREA.iterdir() if p.is_file())
        for folder in ('fits','models','training-logs'):
            paths.update(p for p in (AREA/folder).rglob('*') if p.is_file())
    else:
        assert (AREA/'summary.json').exists() and (AREA/'report.md').exists()
        paths.update(p for p in AREA.rglob('*') if p.is_file())
    training=json.loads((AREA/'training-plan.json').read_text())
    for item in list(training['datasets'].values())+plan['sources']+plan['checks']:
        paths.add(ROOT/item['path'])
    for item in plan['models'].values():
        paths.add(ROOT/item['path']);paths.add(ROOT/item['weights']['path'])
    for p in (ROOT/'tools/hybrid-learning').rglob('*'):
        if p.is_file() and '__pycache__' not in p.parts and not p.name.endswith('.pyc'):
            paths.add(p)
    for name in ('worktree-artifact-organization.json','salvage_dataset_manifest.json','salvage_verification.json','salvage_results.md'):
        paths.add(ROOT/'tools/neural'/name)
    paths.add(ROOT/'build/neural-salvage/nplus1-v1/certified-dataset-manifest.json')
    paths.add(ROOT/'build/neural-salvage/nplus1-v1/execution-checks.json')
    return sorted(paths)


def make(mode):
    if mode=='results':
        from verify_results import verify_outputs
        verified=verify_outputs()
        proof=AREA/'results-verification.json'
        if proof.exists():assert json.loads(proof.read_text())==verified
        else:freeze(proof,verified)
    CACHE.mkdir(parents=True,exist_ok=True)
    archive=CACHE/f'{mode}.zip';entries=[]
    # Stored training snapshot is brief I/O and avoids compression CPU during timed runs.
    compression=zipfile.ZIP_STORED if mode=='training' else zipfile.ZIP_DEFLATED
    paths=collect(mode)
    with zipfile.ZipFile(archive,'x',compression=compression) as output:
        for path in paths:
            value=path.read_bytes();sha=hashlib.sha256(value).hexdigest();name=path.relative_to(ROOT).as_posix()
            output.writestr(name,value)
            entries.append(dict(entry=name,sha256=sha,bytes=len(value)))
    record=dict(revision='hybrid-learning-archive-v1',mode=mode,createdUtc=datetime.now(timezone.utc).isoformat(),
        archive=info(archive),entries=entries,
        acquisitionArchive=json.loads((ROOT/'tools/neural/salvage_dataset_manifest.json').read_text())['archive'])
    manifest=CACHE/f'{mode}-manifest.json';freeze(manifest,record)
    verify(manifest)
    freeze(ROOT/'tools/hybrid-learning'/f'{mode}-cache-manifest.json',record)
    print(json.dumps({'archive':str(archive.relative_to(ROOT)),'entries':len(entries),'sha256':record['archive']['sha256']}))


def verify(manifest):
    record=json.loads(manifest.read_text());path=ROOT/record['archive']['path']
    assert digest(path)==record['archive']['sha256']
    with zipfile.ZipFile(path) as archive:
        assert archive.testzip() is None
        assert set(archive.namelist())=={e['entry'] for e in record['entries']}
        for item in record['entries']:
            data=archive.read(item['entry'])
            assert len(data)==item['bytes'] and hashlib.sha256(data).hexdigest()==item['sha256'],item['entry']
    return record


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('mode',choices=['training','results','verify']);a=p.parse_args()
    if a.mode=='verify':
        for manifest in sorted(CACHE.glob('*-manifest.json')):verify(manifest)
        print('All hybrid archive entries verified')
    else:make(a.mode)
