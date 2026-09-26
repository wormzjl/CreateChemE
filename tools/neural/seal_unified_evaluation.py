"""Preserve unified results and their frozen dependencies after serial evaluation.

The active runner creates study.zip. This adds the inputs/model artifacts and
selection provenance required to restore the study after Gradle clean, without
altering the evaluation method or pooling historical measurements.
"""
import hashlib
import json
from pathlib import Path
import shutil
import zipfile

from prepare_transformer_data import digest
from unified_column_evaluation import ROOT, OUTPUT, PLAN, LABELS, read, verify_plan, validate_run
from prepare_transformer_data import read_rows


def freeze(path,value):
    with path.open('x',encoding='utf-8',newline='\n') as f:
        json.dump(value,f,indent=2,sort_keys=True,allow_nan=False); f.write('\n')


def main():
    plan=verify_plan()
    source=read_rows(ROOT/plan['sets']['test']['path'])
    for label in LABELS:
        directory=OUTPUT/label
        validate_run(read_rows(directory/'evaluation.jsonl'),read(directory/'run.json'),source,
            plan['candidates'][label]['modelSha256'],plan['sets']['test']['sha256'])
    summary=read(OUTPUT/'summary.json')
    if summary['planSha256']!=digest(PLAN): raise ValueError('Summary belongs to another plan')
    cache=ROOT/'.neural-cache/unified-evaluation-v1'
    study=cache/'study.zip'; original=read(cache/'manifest.json')
    if digest(study)!=original['archiveSha256']: raise ValueError('Unified results archive changed')
    with zipfile.ZipFile(study) as z:
        for entry in original['entries']:
            if hashlib.sha256(z.read(entry['entry'])).hexdigest()!=entry['sha256']: raise ValueError('Corrupt unified results entry')
    files={ROOT/info['path'] for info in plan['sets'].values()}
    files.update(ROOT/path for path in plan['sources'])
    files.update(ROOT/info['modelPath'] for info in plan['candidates'].values())
    files.update((ROOT/'build/neural-transformer/native-v1'/kind/'java-parity.json') for kind in ('transformer','mlp'))
    files.update((ROOT/'build/neural-transformer/native-v1'/kind/'fixture.json') for kind in ('transformer','mlp'))
    # The complete historical validation journals explain the frozen selection.
    # Historical test/subset results are not part of the unified measurement set.
    files.update(p for label in LABELS for p in (ROOT/'build/neural-transformer/native-v1'/('validation-'+label)).glob('*.json*'))
    # Preserve superseded raw diagnostics separately by their original paths,
    # including the interrupted legacy timing run. Never analyze them as part
    # of the unified test or advertise an interrupted run as complete.
    historical=[p for pattern in ('fresh-*','benchmark-*')
        for folder in (ROOT/'build/neural-transformer/native-v1').glob(pattern)
        for p in folder.glob('*') if p.is_file()]
    files.update(historical)
    names=('unified_column_evaluation.py','unified-evaluation.md','unified-evaluation.json',
        'test_unified_column_evaluation.py','V3ColumnTransformerInitializer.java','V3TransformerParityCheck.java',
        'V3CandidateModels.java','V3GeneralTrainingProbe.java','export_transformer.py',
        'check_transformer_parity.py','analyze_transformer_native.py','seal_unified_evaluation.py',
        'unified-evaluation-findings.md')
    files.update(ROOT/'tools/neural'/name for name in names)
    files.update((ROOT/'build.gradle',ROOT/'gradle.properties'))
    archive=cache/'dependencies.zip'; entries=[]
    with zipfile.ZipFile(archive,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for path in sorted(files):
            name=path.relative_to(ROOT).as_posix()
            z.write(path,name); entries.append({'entry':name,'sha256':digest(path),'bytes':path.stat().st_size})
    with zipfile.ZipFile(archive) as z:
        if z.testzip() is not None: raise ValueError('Dependency archive CRC failure')
        for entry in entries:
            data=z.read(entry['entry'])
            if len(data)!=entry['bytes'] or hashlib.sha256(data).hexdigest()!=entry['sha256']: raise ValueError('Dependency archive mismatch')
    manifest={'revision':'unified-evaluation-complete-cache-v1','planSha256':digest(PLAN),
        'studyArchive':{'path':study.relative_to(ROOT).as_posix(),'sha256':digest(study),'bytes':study.stat().st_size,'entries':original['entries']},
        'dependencyArchive':{'path':archive.relative_to(ROOT).as_posix(),'sha256':digest(archive),'bytes':archive.stat().st_size,'entries':entries},
        'allEntriesVerified':True,'activeTestCases':252,'validationSelectionCases':405,
        'historicalTestResultsPooled':False,
        'historicalRawFilesPreserved':[p.relative_to(ROOT).as_posix() for p in sorted(historical)],
        'historicalIncompleteRun':'native-v1/benchmark-nearest-k1 was interrupted when the user switched to unified evaluation; preserved bytes are not a completed benchmark',
        'gpuPilotCacheManifestSha256':digest(ROOT/'tools/neural/transformer-cache-manifest.json'),
        'gen3CacheManifestSha256':digest(ROOT/'tools/neural/gen3-cache-manifest.json')}
    freeze(cache/'complete-manifest.json',manifest)
    freeze(ROOT/'tools/neural/unified-evaluation-cache-manifest.json',manifest)
    for source_name,target_name in [('report.md','unified-evaluation-results.md'),('summary.json','unified-evaluation-summary.json')]:
        target=ROOT/'tools/neural'/target_name
        if target.exists(): raise FileExistsError('Do not overwrite published unified results')
        shutil.copyfile(OUTPUT/source_name,target)
        if digest(target)!=digest(OUTPUT/source_name): raise ValueError('Result copy mismatch')
    print(json.dumps({'studyBytes':study.stat().st_size,'dependencyBytes':archive.stat().st_size,
        'dependencyEntries':len(entries),'verified':True}))


if __name__=='__main__': main()
