"""Bind curated TRAIN cohorts and unchanged original validation references."""
from common import *
from collections import Counter
import zipfile


def validate_reference_source():
    manifest_path=ROOT/'tools/neural/transformer-accuracy-cache-manifest.json';manifest=read(manifest_path)
    archive=ROOT/manifest['archivePath'];assert digest(archive)==manifest['archiveSha256']
    source=ROOT/'build/neural-transformer/native-v1/validation.jsonl';name=source.relative_to(ROOT).as_posix()
    record=next(item for item in manifest['entries'] if item['entry']==name)
    assert digest(source)==record['sha256']
    with zipfile.ZipFile(archive) as z:assert z.read(name)==source.read_bytes()
    rows=read_rows(source);assert len(rows)==405 and all(r['split']=='validation' for r in rows)
    references=[r for r in rows if strict(r)];assert len(references)==168
    return dict(passed=True,manifest=info(manifest_path),archive=info(archive),source=info(source),
        archiveEntry=name,exactOriginalReferenceRecords=168,allValidationRecordsByteIdenticalToArchive=True)


def build():
    validate_reference_source()
    curation=read(ROOT/'tools/training-curation/cache-manifest.json')
    wanted=next(item for item in curation['entries'] if item['entry']==CURATED.relative_to(ROOT).as_posix())
    assert digest(CURATED)==wanted['sha256']
    acquisition=ROOT/'build/neural-salvage/nplus1-v1/certified-dataset-manifest.json'
    original=read(acquisition)['trainOnlyFiles']['N'];old_source=ROOT/original['path']
    assert digest(old_source)==original['sha256']=='3d123dd631996b54e791c193cc2ef730dd354954b8ea3de420a4615664d40f01'
    old=read_rows(old_source);selected=read_rows(CURATED);assert len(old)==805 and len(selected)==905
    old_by_key={canonical_input_hash(r['input']):r for r in old}
    selected_by_key={canonical_input_hash(r['input']):r for r in selected}
    assert len(selected_by_key)==905 and len(old_by_key)==805
    for r in selected:
        assert r['split']=='train' and strict(r) and r['labelProvenance']['eligibleForFitting']
        assert not r['labelProvenance'].get('materialProfileDisagreement',False)
        key=canonical_input_hash(r['input']);assert key==r['labelProvenance']['canonicalInputSha256']
        if key in old_by_key:assert r==old_by_key[key]
    cohorts=dict(N804=[r for r in selected if canonical_input_hash(r['input']) in old_by_key],
                 added101=[r for r in selected if canonical_input_hash(r['input']) not in old_by_key],N905=selected)
    assert [len(cohorts[name]) for name in ('N804','added101','N905')]==[804,101,905]
    omitted=[r for key,r in old_by_key.items() if key not in selected_by_key]
    assert len(omitted)==1 and omitted[0]['labelProvenance']['materialProfileDisagreement']
    validation_source=ROOT/'build/neural-transformer/native-v1/validation.jsonl'
    validation=read_rows(validation_source);assert len(validation)==405 and all(r['split']=='validation' for r in validation)
    validation_inputs=ROOT/'build/neural-hybrid-learning/v1/validation-inputs.jsonl'
    existing=read_rows(validation_inputs)
    assert {(r['id'],canonical_input_hash(r['input'])) for r in validation}=={(r['id'],canonical_input_hash(r['input'])) for r in existing}
    assert not set(selected_by_key)&{canonical_input_hash(r['input']) for r in validation}
    references=[r for r in validation if strict(r)];assert len(references)==168
    return cohorts,references,existing,dict(revision='trace-followup-cohorts-v1',curationManifest=info(ROOT/'tools/training-curation/cache-manifest.json'),
        curated=info(CURATED),originalTrain=info(old_source),acquisitionManifest=info(acquisition),
        validationSource=info(validation_source),validationInputs=info(validation_inputs),
        counts={name:len(rows) for name,rows in cohorts.items()},validationCases=405,certifiedValidationReferences=168,
        excludedHistoricalTrainIds=[r['id'] for r in omitted],
        branchCounts={name:dict(Counter(r['seed']['branch'] for r in rows)) for name,rows in cohorts.items()},
        historicalIncumbentTrainingIncludesQuarantinedCase=True,
        interpretation='New fits never present the quarantined target. A warm start inherits the unchanged historical incumbent, including its earlier training history; it is not an unlearning experiment.')


def main():
    cohorts,references,validation,metadata=build();OUT.mkdir(parents=True,exist_ok=True)
    source_lines={json.loads(line)['id']:line for line in CURATED.read_bytes().splitlines(keepends=True)}
    for name,rows in cohorts.items():
        with (OUT/f'{name}.jsonl').open('xb') as stream:
            for r in rows:stream.write(source_lines[r['id']])
    freeze(OUT/'validation-references.jsonl',references,True)
    freeze(OUT/'validation-inputs.jsonl',validation,True)
    metadata['files']={name:info(OUT/f'{name}.jsonl') for name in cohorts}
    metadata['referenceFile']=info(OUT/'validation-references.jsonl');metadata['inputFile']=info(OUT/'validation-inputs.jsonl')
    freeze(OUT/'cohorts.json',metadata)
    print(json.dumps({'prepared':metadata['counts'],'validationReferences':168,'fullValidationInputs':405,'excluded':metadata['excludedHistoricalTrainIds']}))


if __name__=='__main__':main()
