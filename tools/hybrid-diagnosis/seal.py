"""Replay analysis without writes, validate all evidence, then seal a separate archive."""
from common import *
import argparse
import contextlib
import importlib
import io
import zipfile
from datetime import datetime,timezone
from ablation import verify as verify_ablation

CACHE=ROOT/'.neural-cache/hybrid-diagnosis-v1'
MODULES=('analyze_cases','analyze_training','analyze_support','analyze_support_floor',
         'analyze_gradients','analyze_cases_v2','analyze_training_v2')


def verified_outputs():
    verify_ablation();replayed=[];output_files={}
    for name in MODULES:
        module=importlib.import_module(name);original_freeze=module.freeze;captured=[]
        def capture(path,value,jsonl=False):
            path=Path(path);assert path.is_relative_to(OUT)
            expected=read_rows(path) if jsonl else json.loads(path.read_text(encoding='utf-8'))
            # JSON serializes numeric mapping keys as strings. Compare the same
            # serialized value contract used by the original immutable writers.
            serialized=json.loads(json.dumps(value,allow_nan=False))
            assert serialized==expected,'Analysis replay mismatch: '+str(path)
            output_files[str(path)]=info(path);captured.append(str(path.relative_to(ROOT)))
        module.freeze=capture
        try:
            with contextlib.redirect_stdout(io.StringIO()):module.main()
        finally:module.freeze=original_freeze
        assert captured
        replayed.append(dict(module=name,source=info(Path(module.__file__)),outputs=captured))
        print('Recomputed and matched '+name,flush=True)
    from analyze_ablation import build as build_ablation
    result,rows=build_ablation()
    assert result==json.loads((OUT/'ablation-analysis.json').read_text(encoding='utf-8'))
    assert rows==read_rows(OUT/'ablation-profile-evidence.jsonl')
    output_files['ablation']=info(OUT/'ablation-analysis.json');output_files['ablationProfiles']=info(OUT/'ablation-profile-evidence.jsonl')
    from diagnosis_report import build as build_report
    summary,text=build_report()
    assert summary==json.loads((OUT/'diagnosis-summary.json').read_text(encoding='utf-8'))
    assert text==(OUT/'report.md').read_text(encoding='utf-8')
    assert (ROOT/'tools/hybrid-diagnosis/results.md').read_bytes()==(OUT/'report.md').read_bytes()
    assert set(p.name for p in (OUT/'ablation-fits').iterdir() if p.is_dir())=={'empirical','canonical'}
    assert len(result['sources'])==12 and result['measuredPanelRequests']==1170 and result['fixedTrainWarmupRequests']==36
    model_info=json.loads((OUT/'ablation-models.json').read_text(encoding='utf-8'))
    lock=json.loads((OUT/'ablation-execution-lock.json').read_text(encoding='utf-8'))
    for item in [lock['plan'],lock['models'],lock['panel']]+lock['precision']:
        assert digest(ROOT/item['path'])==item['sha256']
    for record in model_info.values():
        for item in (record,record['weights']):assert digest(ROOT/item['path'])==item['sha256']
    dependencies=[]
    for path in (ROOT/'tools/hybrid-learning/results-cache-manifest.json',ROOT/'tools/neural/transformer-accuracy-cache-manifest.json'):
        m=json.loads(path.read_text(encoding='utf-8'));archive=m.get('archive')
        if isinstance(archive,dict):item=archive
        else:item={'path':m['archivePath'],'sha256':m['archiveSha256']}
        assert digest(ROOT/item['path'])==item['sha256']
        dependencies.append(dict(manifest=info(path),archive=item))
    return dict(revision='hybrid-diagnosis-verification-v1',passed=True,analysisReplay=replayed,
        ablationRecomputed=True,nativeRunsRevalidated=6,allPanelInputsRetained=True,
        initialEquivalenceVerified=True,controlWeightsExactlyReproduced=result['originalControlWeightsExactlyReproduced'],
        measuredNativeRequests=1170,fixedTrainWarmupRequests=36,newFits=2,newTestInputs=0,
        summaryRecomputed=True,reportRerendered=True,publishedCopyMatches=True,
        outputs=list(output_files.values())+[info(OUT/'diagnosis-summary.json'),info(OUT/'report.md')],
        dependencies=dependencies,ablationPlan=info(OUT/'ablation-plan.json'),executionLock=info(OUT/'ablation-execution-lock.json'),
        sources=[info(p) for p in sorted((ROOT/'tools/hybrid-diagnosis').glob('*.py'))]+[info(ROOT/'tools/hybrid-diagnosis/protocol.md')])


def verify_archive():
    record=json.loads((CACHE/'manifest.json').read_text(encoding='utf-8'));archive=ROOT/record['archive']['path']
    assert digest(archive)==record['archive']['sha256']
    with zipfile.ZipFile(archive) as z:
        assert z.testzip() is None and set(z.namelist())=={e['entry'] for e in record['entries']}
        for e in record['entries']:
            data=z.read(e['entry']);assert len(data)==e['bytes'] and hashlib.sha256(data).hexdigest()==e['sha256']
    for dep in record['dependencies']:assert digest(ROOT/dep['archive']['path'])==dep['archive']['sha256']
    print(json.dumps({'verifiedArchiveEntries':len(record['entries']),'dependencyArchivesUnchanged':len(record['dependencies'])}),flush=True)


def seal():
    proof=verified_outputs();freeze(OUT/'verification.json',proof)
    # This manifest records completed retrospective analyses; only the ablation registration was prospective.
    freeze(OUT/'diagnosis-manifest.json',dict(revision='hybrid-diagnosis-evidence-v1',createdUtc=datetime.now(timezone.utc).isoformat(),
        baseCommit='f459085',retrospective=True,prospectiveAblation=info(OUT/'ablation-plan.json'),verification=info(OUT/'verification.json'),
        protocol=info(ROOT/'tools/hybrid-diagnosis/protocol.md'),dependencies=proof['dependencies']))
    CACHE.mkdir(parents=True,exist_ok=True);paths={p for p in OUT.rglob('*') if p.is_file()}
    paths.update(p for p in (ROOT/'tools/hybrid-diagnosis').rglob('*') if p.is_file() and '__pycache__' not in p.parts and not p.name.endswith('.pyc'))
    paths.update(ROOT/dep['manifest']['path'] for dep in proof['dependencies'])
    archive=CACHE/'study.zip';entries=[]
    with zipfile.ZipFile(archive,'x',compression=zipfile.ZIP_DEFLATED) as z:
        for path in sorted(paths):
            data=path.read_bytes();name=path.relative_to(ROOT).as_posix();z.writestr(name,data)
            entries.append(dict(entry=name,bytes=len(data),sha256=hashlib.sha256(data).hexdigest()))
    record=dict(revision='hybrid-diagnosis-cache-v1',archive=info(archive),entries=entries,dependencies=proof['dependencies'],
        restore='Restore this archive and the bound preceding study archives into the repository root; source checkout f459085 plus diagnosis commit is required.')
    freeze(CACHE/'manifest.json',record);verify_archive();freeze(ROOT/'tools/hybrid-diagnosis/cache-manifest.json',record)
    print(json.dumps({'sealed':str(archive.relative_to(ROOT)),'entries':len(entries),'sha256':record['archive']['sha256']}),flush=True)


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('mode',choices=['seal','verify']);a=p.parse_args()
    seal() if a.mode=='seal' else verify_archive()
