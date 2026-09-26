"""Independently revalidate artifacts and replay analyses before sealing results."""
from common import *
from register_followup import verify_plan
from fit_all import verify_pairing
from benchmark_followup import fit_and_export_bindings,verify_selection
import argparse
import zipfile

CACHE=ROOT/'.neural-cache/trace-followup-v1'


def archive_matches(manifest):
    archive=ROOT/manifest['archive']['path'];assert digest(archive)==manifest['archive']['sha256']
    with zipfile.ZipFile(archive) as z:
        assert z.testzip() is None and set(z.namelist())=={e['entry'] for e in manifest['entries']}
        for entry in manifest['entries']:
            data=z.read(entry['entry']);assert len(data)==entry['bytes'] and hashlib.sha256(data).hexdigest()==entry['sha256']


def verify_exports_and_exposure():
    from train import batches
    plan,models=fit_and_export_bindings();reports=verify_pairing();torch.set_num_threads(4)
    recomputed={}
    for cohort in ('N804','N905'):
        rows=read_rows(OUT/f'{cohort}.jsonl')
        for seed in plan['training']['seeds']:
            counts=np.zeros(len(rows),dtype=np.int64);order=hashlib.sha256();snapshots={}
            for step,indices in enumerate(batches(len(rows),4640,seed),1):
                ix=indices.numpy();counts[ix]+=1;order.update(ix.tobytes())
                if step in plan['training']['checkpoints']:
                    snapshots[step]=dict(counts={r['id']:int(n) for r,n in zip(rows,counts)},hash=order.hexdigest())
            recomputed[cohort,seed]=snapshots
    verified=[]
    for name,item in models.items():
        if name in plan['controls']:continue
        cp=torch.load(ROOT/item['checkpoint']['path'],map_location='cpu',weights_only=True)
        doc=read(ROOT/item['weights']['path']);report=cp['report'];arm=item['arm'];seed=item['seed'];step=item['step']
        assert report['dataSha256']==digest(OUT/f"{plan['arms'][arm]['cohort']}.jsonl")==doc['dataSha256']
        assert cp['normalization']==read(OUT/'normalization.json')==doc['normalization']
        assert set(cp['state_dict'])==set(doc['weights'])
        for key,value in cp['state_dict'].items():
            tensor=doc['weights'][key];assert list(value.shape)==tensor['shape']
            converted=torch.tensor(tensor['values'],dtype=value.dtype).reshape(tensor['shape'])
            assert torch.isfinite(value).all() and torch.equal(value,converted),name+'/'+key
        counts=recomputed[plan['arms'][arm]['cohort'],seed][step]
        assert report['checkpoint']['perCasePresentationCounts']==counts['counts']
        assert report['checkpoint']['minibatchOrderSha256']==counts['hash']
        for group in cp['optimizer_state_dict']['param_groups']:
            assert group['lr']==8e-5 and group['weight_decay']==1e-4
        for state in cp['optimizer_state_dict']['state'].values():assert int(state['step'])==step
        verified.append(name)
    assert len(verified)==30
    print('Verified exact checkpoint/export tensors, optimizer endpoints and per-ID shuffle accounting.',flush=True)
    return verified,reports


def verify_all():
    plan=verify_plan();registration=read(CACHE/'registration-manifest.json');archive_matches(registration)
    assert registration==read(ROOT/'tools/trace-followup/registration-manifest.json')
    assert registration['plan']==info(OUT/'training-plan.json')
    predecessors=[]
    for item in plan['predecessors']:
        assert digest(ROOT/item['manifest']['path'])==item['manifest']['sha256']
        assert digest(ROOT/item['archive']['path'])==item['archive']['sha256'];predecessors.append(item)
    from prepare_data import build as rebuild_cohorts,validate_reference_source
    cohorts,references,validation,_=rebuild_cohorts()
    for name,rows in cohorts.items():assert rows==read_rows(OUT/f'{name}.jsonl')
    assert references==read_rows(OUT/'validation-references.jsonl') and validation==read_rows(OUT/'validation-inputs.jsonl')
    assert validate_reference_source()==read(OUT/'validation-reference-binding.json')
    verified,reports=verify_exports_and_exposure();vp=verify_selection()
    assert vp['measuredRequests']<=plan['maximumValidationRequests']
    expected_lock=dict(validationPlan=info(OUT/'validation-plan.json'),selection=info(OUT/'selection.json'),trainingPlan=info(OUT/'training-plan.json'))
    assert read(OUT/'validation-execution-lock.json')==expected_lock
    from analyze_native import build as rebuild_analysis
    analyses=[]
    for stage in ('screen','validation'):
        result,cases,profiles=rebuild_analysis(stage)
        assert result==read(OUT/f'{stage}-analysis.json')
        assert cases==read_rows(OUT/f'{stage}-case-evidence.jsonl')
        assert profiles==read_rows(OUT/f'{stage}-profile-evidence.jsonl')
        analyses.append(dict(stage=stage,summary=info(OUT/f'{stage}-analysis.json'),caseEvidence=info(OUT/f'{stage}-case-evidence.jsonl'),
            profileEvidence=info(OUT/f'{stage}-profile-evidence.jsonl'),measuredRequests=result['measuredRequests'],warmupRequests=result['warmupRequests']))
        print('Exact analysis and case/profile replay matched '+stage,flush=True)
    from followup_report import build as rebuild_report
    summary,text=rebuild_report()
    assert summary==read(OUT/'report-summary.json') and text==(OUT/'report.md').read_text(encoding='utf-8')
    assert (ROOT/'tools/trace-followup/results.md').read_bytes()==(OUT/'report.md').read_bytes()
    actual_fits={p.name for p in (OUT/'fits').iterdir() if p.is_dir()}
    assert actual_fits=={f'{arm}-{seed}' for arm in plan['arms'] for seed in plan['training']['seeds']}
    return dict(revision='trace-followup-final-verification-v1',passed=True,registeredFits=10,verifiedCheckpoints=verified,
        cohortsRevalidated=True,referenceArchiveBindingRevalidated=True,exactTensorExportsVerified=True,
        perIdExposureAndOrderRecomputed=True,selectionRecomputed=True,fullValidationGuardVerified=True,
        analysesRecomputed=analyses,reportRecomputed=True,publishedReportMatches=True,
        registrationManifest=info(CACHE/'registration-manifest.json'),registrationArchive=registration['archive'],predecessors=predecessors,
        measuredNativeRequests=summary['measuredNativeRequests'],warmupRequests=summary['warmupRequests'],newTestRequests=0,
        newSolverRequestsByVerification=0,productionDefaultChanged=False,
        analysisSources=[info(ROOT/'tools/trace-followup'/name) for name in ('analysis_common.py','analyze_native.py','followup_report.py','seal_results.py','test_analysis.py')])


def seal():
    proof=verify_all();freeze(OUT/'verification.json',proof)
    paths={p for p in OUT.rglob('*') if p.is_file()}
    paths.update(p for p in (ROOT/'tools/trace-followup').rglob('*') if p.is_file() and '__pycache__' not in p.parts and p.suffix!='.pyc')
    for name in ('training-output.log','export-output.log','screen-output.log','validation-output.log','analysis-checks.log'):
        path=OUT.parent/name
        if path.exists():paths.add(path)
    paths.add(CACHE/'registration-manifest.json')
    archive=CACHE/'results.zip';entries=[]
    with zipfile.ZipFile(archive,'x',compression=zipfile.ZIP_DEFLATED) as z:
        for path in sorted(paths):
            data=path.read_bytes();name=path.relative_to(ROOT).as_posix();z.writestr(name,data)
            entries.append(dict(entry=name,bytes=len(data),sha256=hashlib.sha256(data).hexdigest()))
    manifest=dict(revision='trace-followup-results-cache-v1',archive=info(archive),entries=entries,
        registrationArchive=proof['registrationArchive'],registrationManifest=proof['registrationManifest'],
        predecessors=proof['predecessors'],verification=info(OUT/'verification.json'),summary=info(OUT/'report-summary.json'),
        restore='Restore registration.zip and results.zip at the repository root with base bad3346 plus the follow-up commits. Existing native/Gradle dependencies and the recorded Python environment are required. Historical archive bindings remain available separately.')
    freeze(CACHE/'results-manifest.json',manifest);archive_matches(manifest)
    freeze(ROOT/'tools/trace-followup/cache-manifest.json',manifest)
    print(json.dumps({'sealedResults':True,'entries':len(entries),'sha256':manifest['archive']['sha256'],
        'measuredNativeRequests':proof['measuredNativeRequests'],'warmupRequests':proof['warmupRequests']}),flush=True)


def verify_archive():
    manifest=read(CACHE/'results-manifest.json');archive_matches(manifest)
    assert manifest==read(ROOT/'tools/trace-followup/cache-manifest.json')
    assert digest(ROOT/manifest['registrationArchive']['path'])==manifest['registrationArchive']['sha256']
    for item in manifest['predecessors']:assert digest(ROOT/item['archive']['path'])==item['archive']['sha256']
    print(json.dumps({'verifiedResultEntries':len(manifest['entries']),'registrationUnchanged':True,'predecessorsUnchanged':len(manifest['predecessors'])}))


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('mode',choices=['seal','verify']);args=p.parse_args()
    seal() if args.mode=='seal' else verify_archive()
