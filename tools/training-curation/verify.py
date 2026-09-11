"""Verify published partitions directly, replay determinism, and seal evidence."""
import argparse
import copy
import json
import hashlib
import zipfile
from collections import Counter
import numpy as np
import curate as c

CACHE=c.ROOT/'.neural-cache/training-curation-v1'


def read(path):return json.loads(path.read_text(encoding='utf-8'))
def rows(path):return [json.loads(line) for line in path.read_bytes().splitlines()]
def serial(value):return json.loads(json.dumps(value,allow_nan=False))


def ambiguity_evidence(records):
    """Evidence-only exact TRAIN lookup; never feeds scales or subset choice."""
    report=c.ROOT/'build/neural-transformer/data-v2/profile-disagreements.jsonl'
    details=[]
    for source in records:
        if not source['row']['labelProvenance'].get('materialProfileDisagreement',False):continue
        matches=[line for line in report.read_bytes().splitlines(keepends=True) if json.loads(line)['id']==source['id']]
        assert len(matches)==1;entry=json.loads(matches[0]);assert entry['split']=='train'
        observations=[]
        for reference in entry['differences']:
            journal=c.ROOT/'build/neural-gen3'/reference['journal']
            assert c.digest(journal)==reference['journalSha256']
            matching=[line for line in journal.read_bytes().splitlines(keepends=True) if json.loads(line)['id']==source['id']]
            assert len(matching)==1;other=json.loads(matching[0]);original=source['row']
            assert other['split']=='train'
            same_input=c.canonical_input_hash(other['input'])==source['key']==c.canonical_input_hash(other['seed']['input'])
            same_property=other['seed']['propertyRevision']==original['seed']['propertyRevision']
            same_formulation=other['formulationRevision']==original['formulationRevision']
            total=sum(original['input']['feedComponentMolarFlowsMolPerSecond'])
            temperature=float(np.abs(np.asarray(other['seed']['temperatures'])-original['seed']['temperatures']).max())
            flow=max(float(np.abs(np.asarray(other['seed'][phase])-original['seed'][phase]).max()/total) for phase in ('liquid','vapor'))
            assert np.isclose(temperature,reference['temperatureMaxK'],rtol=0,atol=1e-10)
            assert np.isclose(flow,reference['flowMaxOverFeed'],rtol=0,atol=1e-10)
            observations.append(dict(reference=reference,journal=c.info(journal),sourceLineSha256=hashlib.sha256(matching[0]).hexdigest(),
                sameCanonicalInput=same_input,samePropertyRevision=same_property,sameFormulationRevision=same_formulation,
                originalFormulation=original['formulationRevision'],otherFormulation=other['formulationRevision'],
                propertyRevision=other['seed']['propertyRevision'],temperatureMaximumDifferenceKelvin=temperature,componentFlowMaximumDifferenceOverFeed=flow,
                evidenceScope='Historical consistency check only; no claim that distinct physical roots were proven.',
                certifiedObservation={key:other[key] for key in ('id','split','input','seed','formulationRevision','diagnostics','success','equilibriumQualified','waterQualification')}))
        details.append(dict(id=source['id'],nativeCertification='STRICT_PASS',curationDisposition='QUARANTINE',
            reason='UNRESOLVED_HISTORICAL_PROFILE_DISAGREEMENT',originalProvenance=source['row']['labelProvenance'],
            disagreementReport=c.info(report),disagreementLineSha256=hashlib.sha256(matches[0]).hexdigest(),disagreement=entry,observations=observations))
    return dict(selectionDependency=False,records=details)


def dependencies():
    out=[]
    for name in ('tools/hybrid-diagnosis/cache-manifest.json','tools/hybrid-learning/results-cache-manifest.json','tools/neural/transformer-accuracy-cache-manifest.json'):
        path=c.ROOT/name;m=read(path)
        item=m['archive'] if isinstance(m.get('archive'),dict) else dict(path=m['archivePath'],sha256=m['archiveSha256'])
        assert c.digest(c.ROOT/item['path'])==item['sha256'];out.append(dict(manifest=c.info(path),archive=item))
    return out


def verify_outputs():
    records=c.allowed_source();source={r['id']:r for r in records};result=read(c.OUT/'selection.json');decisions=rows(c.OUT/'case-decisions.jsonl')
    decision={d['id']:d for d in decisions};assert len(decision)==len(records)==906
    status={};partition_info=[]
    for category in ('selected','reserve','quarantine'):
        path=c.OUT/f'{category}.jsonl';lines=path.read_bytes().splitlines(keepends=True)
        assert len(lines)==result['counts'][category]
        for line in lines:
            r=json.loads(line);key=r['id'];assert key not in status and line==source[key]['raw'];status[key]=category
            assert decision[key]['status']==category and c.strict(r)
            assert c.digest_json(r['input'])==decision[key]['inputSha256']
            assert c.digest_json(r['seed'])==decision[key]['labelSha256']
            assert c.digest_json(r['labelProvenance'])==decision[key]['provenanceSha256']
            assert hashlib.sha256(line).hexdigest()==decision[key]['sourceRecordSha256']
        assert b''.join(lines)==b''.join(r['raw'] for r in records if decision[r['id']]['status']==category)
        partition_info.append(dict(**c.info(path),records=len(lines),bytes=path.stat().st_size))
    assert set(status)==set(source)
    all_desc=[c.describe(r) for r in sorted(records,key=lambda r:(r['key'],r['id']))]
    eligible=[d for d in all_desc if not d['row']['labelProvenance'].get('materialProfileDisagreement',False)]
    assert len(eligible)==905;scales=c.scales_for(all_desc);assert serial(scales)==result['scales']
    descriptions={d['id']:d for d in all_desc};multiplicity=Counter()
    for key,category in status.items():
        flagged=bool(source[key]['row']['labelProvenance'].get('materialProfileDisagreement',False))
        assert flagged==(category=='quarantine')
        representative=decision[key]['representativeId']
        if category=='quarantine':assert representative is None;continue
        assert status[representative]=='selected'
        gates=c.compare(descriptions[key],descriptions[representative],scales);assert gates['admissible']
        assert serial(gates)==decision[key]['representationGates'];multiplicity[representative]+=1
    assert dict(multiplicity)==result['representativeMultiplicities'] and sum(multiplicity.values())==905
    reasons,quotas,witnesses=c.requirements(eligible)
    assert all(status[eligible[j]['id']]=='selected' for j in reasons)
    assert all(sum(status[eligible[j]['id']]=='selected' for j in q['indices'])>=q['minimum'] for q in quotas.values())
    assert serial(witnesses)==result['witnesses']
    rare=[d['id'] for d in all_desc if d['categories']['branch']=='LIQUID_ONLY']
    assert len(rare)==11 and all(status[key]=='selected' for key in rare)
    assert len({d['categories']['stageCount'] for d in all_desc})==len({d['categories']['stageCount'] for d in all_desc if status[d['id']]=='selected'})
    print('Direct partition, certificates, representatives, quotas and witnesses verified.',flush=True)
    rebuilt,redecided,regraph=c.build()
    assert serial(rebuilt)==result and serial(redecided)==decisions and serial(regraph)==read(c.OUT/'redundancy.json')
    assert c.render(rebuilt)==(c.OUT/'report.md').read_text(encoding='utf-8')
    print('Registered selection, decisions, graph, coverage and report replay matched.',flush=True)
    reverse,reversed_decisions,_=c.build(list(reversed(records)))
    assert serial(reverse)==result and serial(reversed_decisions)==decisions
    perturbed=copy.deepcopy(records)
    for index,r in enumerate(perturbed):
        row=r['row']
        for key in ('ms','cold_ms','cpuMillis','allocatedBytes','heapUsedAfterBytes','heapUsedBeforeBytes'):row[key]=(-1 if index%2 else 1)*1e50
        row['modelError']=1e90;row['solverPath']='deliberately altered irrelevant path';row['iterations']=-123
        for key in ('iterations','initializationPath','initializationMode'):row['diagnostics'][key]='ignored metadata'
    _,changed,_=c.build(perturbed)
    assert [(d['id'],d['status'],d['representativeId']) for d in changed]==[(d['id'],d['status'],d['representativeId']) for d in decisions]
    evidence=ambiguity_evidence(records);assert evidence==read(c.OUT/'quarantine-evidence.json')
    print('Order/metadata invariance and exact TRAIN quarantine evidence verified.',flush=True)
    return dict(passed=True,strictCertificates=906,curationCandidates=905,quarantine=1,rawPartitionPreserved=True,
        directRepresentationVerified=True,rareProfilesRetained=11,allQuotasAndWitnessesVerified=True,
        selectorReplayMatches=True,orderInvariant=True,irrelevantMetadataInvariant=True,reportReplayMatches=True,
        partitionFiles=partition_info,dependencies=dependencies(),newFits=0,newNativeRequests=0)


def verify_archive():
    manifest=read(CACHE/'manifest.json');path=c.ROOT/manifest['archive']['path']
    assert c.digest(path)==manifest['archive']['sha256']
    with zipfile.ZipFile(path) as z:
        assert z.testzip() is None and set(z.namelist())=={e['entry'] for e in manifest['entries']}
        for e in manifest['entries']:
            data=z.read(e['entry']);assert len(data)==e['bytes'] and hashlib.sha256(data).hexdigest()==e['sha256']
    for dep in manifest['dependencies']:assert c.digest(c.ROOT/dep['archive']['path'])==dep['archive']['sha256']
    print(json.dumps(dict(archiveVerified=True,entries=len(manifest['entries']),predecessorArchivesUnchanged=len(manifest['dependencies']))),flush=True)


def seal():
    proof=verify_outputs();c.freeze(c.OUT/'verification.json',proof)
    assert (c.ROOT/'tools/training-curation/results.md').read_bytes()==(c.OUT/'report.md').read_bytes()
    CACHE.mkdir(parents=True,exist_ok=True)
    paths={p for p in c.OUT.rglob('*') if p.is_file()}
    paths.update(p for p in (c.ROOT/'tools/training-curation').rglob('*') if p.is_file() and '__pycache__' not in p.parts and p.suffix!='.pyc')
    paths.add(c.SOURCE)
    plan=read(c.OUT/'plan.json');paths.update(c.ROOT/item['path'] for item in plan['sourceDependencies'])
    paths.update(c.ROOT/item['manifest']['path'] for item in proof['dependencies'])
    archive=CACHE/'study.zip';entries=[]
    with zipfile.ZipFile(archive,'x',compression=zipfile.ZIP_DEFLATED) as z:
        for path in sorted(paths):
            data=path.read_bytes();name=path.relative_to(c.ROOT).as_posix();z.writestr(name,data)
            entries.append(dict(entry=name,bytes=len(data),sha256=hashlib.sha256(data).hexdigest()))
    manifest=dict(revision='training-curation-cache-v1',archive=c.info(archive),entries=entries,dependencies=proof['dependencies'],
        counts=read(c.OUT/'selection.json')['counts'],source=c.info(c.SOURCE),
        restore='Extract relative paths at repository root with base commit 2c41d3e plus the curation commit. Historical quarantine evidence reread requires the preceding study data. No model checkpoints are required by selection.')
    c.freeze(CACHE/'manifest.json',manifest);verify_archive();c.freeze(c.ROOT/'tools/training-curation/cache-manifest.json',manifest)


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('mode',choices=['evidence','seal','verify']);args=p.parse_args()
    if args.mode=='evidence':c.freeze(c.OUT/'quarantine-evidence.json',ambiguity_evidence(c.allowed_source()))
    elif args.mode=='seal':seal()
    else:verify_archive()
