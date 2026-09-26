"""Archive the completed native campaign, including failures, outside Gradle clean."""
import hashlib
import json
from pathlib import Path
import zipfile
from prepare_transformer_data import digest

ROOT=Path(__file__).resolve().parents[2]
AREA=ROOT/'build/neural-transformer/native-v1'
TOOLS=ROOT/'tools/neural'


def main():
    if not (AREA/'summary.json').exists(): raise ValueError('Finalize the complete native report first')
    cache=ROOT/'.neural-cache/transformer-native-v1'; cache.mkdir(parents=True,exist_ok=True)
    archive=cache/'study.zip'
    if archive.exists(): raise FileExistsError('Never overwrite an archived campaign')
    sources=[(p,p.relative_to(AREA).as_posix()) for p in sorted(AREA.rglob('*')) if p.is_file()]
    sources += [(ROOT/'build/neural-transformer/data-v2'/name,'inputs/'+name) for name in ('fresh-holdout.jsonl','fresh-benchmark.jsonl')]
    names=('export_transformer.py','V3ColumnTransformerInitializer.java','V3TransformerParityCheck.java',
           'V3CandidateModels.java','V3GeneralTrainingProbe.java','check_transformer_parity.py',
           'run_transformer_native.ps1','analyze_transformer_native.py','cache_transformer_native.py',
           'test_transformer_native_analysis.py','transformer-native-protocol.md','transformer-native-results.md')
    sources += [(TOOLS/name,'implementation/'+name) for name in names]
    entries=[]
    with zipfile.ZipFile(archive,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for path,name in sources:
            z.write(path,name); entries.append({'entry':name,'sha256':digest(path),'bytes':path.stat().st_size})
    with zipfile.ZipFile(archive) as z:
        if z.testzip() is not None: raise ValueError('Archive CRC failure')
        for entry in entries:
            data=z.read(entry['entry'])
            if hashlib.sha256(data).hexdigest()!=entry['sha256'] or len(data)!=entry['bytes']: raise ValueError('Archive content mismatch')
    manifest={'revision':'transformer-native-cache-v1','archivePath':archive.relative_to(ROOT).as_posix(),
        'archiveSha256':digest(archive),'archiveBytes':archive.stat().st_size,'entries':entries,'verifiedAllEntries':True,
        'sourceGpuPilotManifestSha256':digest(TOOLS/'transformer-cache-manifest.json'),
        'sourceGen3ManifestSha256':digest(TOOLS/'gen3-cache-manifest.json'),
        'restoreRoot':'build/neural-transformer/native-v1',
        'solverSourcesSha256':{p.relative_to(ROOT).as_posix():digest(p) for p in sorted((ROOT/'src/main/java/com/wormzjl/createcheme/science/column/v3').rglob('*.java'))}}
    for path in (cache/'manifest.json',TOOLS/'transformer-native-cache-manifest.json'):
        with path.open('x',encoding='utf-8') as f: json.dump(manifest,f,indent=2,sort_keys=True); f.write('\n')
    print(json.dumps({'archiveBytes':manifest['archiveBytes'],'entries':len(entries),'allVerified':True}))


if __name__=='__main__': main()
