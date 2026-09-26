"""Finite prospective registration; verify all model, data and runtime bindings."""
from capacity_common import *
from capacity_native import DEPENDENCIES, CLASSES, JAVA, classpath
from datetime import datetime, timezone
import argparse
import hashlib

# Draft recipe: frozen only after the planning review and every preflight check.
ARMS = {'L2': dict(layers=2, parameters=89496), 'L4': dict(layers=4, parameters=156440)}
SEEDS = [20260913, 20260914]
CHECKPOINTS = [1040, 2080, 3120]


def runtime_entries():
    entries=[]
    for path in [*DEPENDENCIES, CLASSES, JAVA/'java.exe', JAVA/'javac.exe']:
        if path.is_dir():
            selected=sorted(p for p in path.rglob('*') if p.is_file())
        else:
            selected=[path]
        entries.extend(dict(path=str(p),sha256=digest(p)) for p in selected)
    assert entries
    return entries


def predecessor_bindings():
    from register_followup import predecessor_bindings as old_bindings, verify_plan as verify_old_plan
    verify_old_plan()
    entries=old_bindings()
    for name in ('tools/trace-followup/cache-manifest.json','tools/trace-followup/registration-manifest.json'):
        manifest=read(ROOT/name);archive=manifest['archive']
        assert digest(ROOT/archive['path'])==archive['sha256']
        entries.append(dict(manifest=info(ROOT/name),archive=archive))
    return entries


def archive_input_proof():
    import zipfile
    manifest=read(ROOT/'tools/trace-followup/cache-manifest.json')
    archive=ROOT/manifest['archive']['path']
    assert digest(archive)==PREDECESSOR_RESULTS_SHA==manifest['archive']['sha256']
    binding,_=base_binding()
    paths={SOURCE/name for name in ('models.json','N804.jsonl','N905.jsonl','normalization.json',
        'validation-inputs.jsonl','validation-references.jsonl','training-plan.json','cohorts.json')}
    paths.update(ROOT/binding[key]['path'] for key in ('weights','checkpoint','precisionCheck'))
    paths.add(ROOT/binding['path'])
    parity=read(ROOT/binding['precisionCheck']['path'])
    paths.update(ROOT/parity[key]['path'] for key in ('model','fixtures','javaParity'))
    entries={e['entry']:e for e in manifest['entries']}
    with zipfile.ZipFile(archive) as z:
        for path in paths:
            key=path.relative_to(ROOT).as_posix()
            assert digest(path)==entries[key]['sha256']
            assert z.read(key)==path.read_bytes(),key
    assert digest(SOURCE/'N804.jsonl')==TRAIN_SHA
    return dict(passed=True,archive=manifest['archive'],exactArchivedInputs=[info(p) for p in sorted(paths)])


def register():
    from prepare_data import validate_reference_source
    assert validate_reference_source()['passed']
    rows=read_rows(SOURCE/'N804.jsonl')
    assert len(rows)==804 and all(r['split']=='train' and strict(r) and not r['labelProvenance'].get('materialProfileDisagreement',False) for r in rows)
    proof=read(OUT/'initialization-proof.json')
    assert proof['passed'] and proof['allTrainCases']==905 and proof['maximumRawDifference']==0
    assert proof['maximumBranchLogitDifference']==0 and proof['exactMasksBranchesAndInvalidFlags']
    modes=read(OUT/'initialization-mode-proof.json');runtime=read(OUT/'native-runtime-proof.json')
    assert modes['passed'] and all(value['cases']==804 and value['exactRaw'] and value['exactMasks'] for value in modes['modes'].values())
    assert runtime['passed'] and runtime['allTrainCases']==804 and runtime['maximumRawDifference']==0
    assert runtime['exactCompleteDecodedPredictions'] and runtime['rejectedMalformedManifests']==8
    core=read(OUT/'native-core-build.json');assert core['passed'] and core['noPreexistingProjectClasspath']
    for entry in core['sources']+core['compiledClasses']:assert digest(ROOT/entry['path'])==entry['sha256']
    archived=archive_input_proof();freeze(OUT/'archive-input-proof.json',archived)
    binding,document=base_binding()
    checks=[info(OUT/name) for name in ('initialization-proof.json','initial-models.json','initialization-mode-proof.json',
                                      'native-runtime-proof.json','native-core-build.json','archive-input-proof.json')]
    for layers in (2,4):
        directory=OUT/f'initial-{layers}'
        check=read(directory/'precision-check-rebuilt.json')
        assert check['passed'] and check['exactMasks']
        checks.extend(info(directory/name) for name in ('model.json','fixture.json','pipeline.json','java-parity-rebuilt.json','precision-check-rebuilt.json'))
    old=read(SOURCE/'training-plan.json')
    controls={'I':old['controls']['I'],'F0':{**info(ROOT/binding['path']),'weights':binding['weights']}}
    for value in controls.values():
        assert digest(ROOT/value['path'])==value['sha256'] and digest(ROOT/value['weights']['path'])==value['weights']['sha256']
    files=[SOURCE/name for name in ('N804.jsonl','N905.jsonl','normalization.json','validation-inputs.jsonl','validation-references.jsonl','models.json','training-plan.json')]
    files+=ANCHORS+[ROOT/binding['weights']['path'],ROOT/binding['checkpoint']['path'],ROOT/binding['path']]
    source_dir=ROOT/'tools/capacity-followup'
    sources=[p for p in source_dir.rglob('*') if p.is_file() and p.suffix in ('.py','.java','.md') and p.name not in ('results.md',)]
    imports=[ROOT/'tools/trace-followup'/name for name in ('common.py','export_models.py','initialization.py','analysis_common.py','register_followup.py','prepare_data.py')]
    imports += [ROOT/item['path'] for item in old['dependencies']]
    plan=dict(revision='capacity-study-v1',createdUtc=datetime.now(timezone.utc).isoformat(),baseCommit='6528c38',
        arms=ARMS,armOrder=list(ARMS),sourceModel=binding,sourceInitialization=info(OUT/'initialization-proof.json'),
        training=dict(seeds=SEEDS,updates=3120,checkpoints=CHECKPOINTS,learningRate=8e-5,weightDecay=1e-4,
                      batchSize=32,dropout=0,gradientClip=1.,scheduler=None,freshOptimizer=True,
                      precision='float32 CUDA; deterministic algorithms; TF32 disabled',
                      perCasePresentations={str(step):step//26 for step in CHECKPOINTS}),
        fixedComparisonStep=2080,newFits=4,newCheckpoints=12,controls=controls,
        screeningPanel=old['screeningPanel'],validationInputs=old['validationInputs'],warmup=old['warmup'],
        screenOrder=['F0']+[f'{arm}-{seed}-s{step}' for seed in SEEDS for step in CHECKPOINTS for arm in ARMS]+['I'],
        workers=10,requestDeadlineMillis=30000,neuralBudgetMillis=2000,maximumIterations=16,heapBytes=4*1024**3,
        strategies=['current','neural','neuralFirst'],screeningCases=65,validationCases=405,validationBlocks=2,
        maximumScreenRequests=14*65*3,maximumValidationPipelines=10,maximumValidationRequests=10*405*3*2,
        checkpointRule='Preserve classical panel-success union, maximize strict FIRST, lower all-case mean FIRST ms, earlier update.',
        fullValidationRule='Both arms and both seeds at fixed2080; all four native-selected checkpoints; I and F0. Deduplicate complete pipelines, then two reversed blocks.',
        inference='Full native anchors and absolute outputs, width64/head4/FF128. Only block count changes. No wrapper.',
        depthClaimRule='Fixed2080 L4 must improve strict FIRST over matched L2 for both order seeds and both blocks; preserve classical union; no pooled mean FIRST latency regression against matched L2 for either seed. Report I/F0 losses even when passing.',
        qualificationRule='Further validation only, no automatic promotion. Also compare full endpoint candidates with unchanged F0 and I.',
        trainDiagnostics='Identical per-column profile metrics on N804 and original168 validation references at initial and saved endpoints.',
        historicalPretrainingIncludesQuarantinedCase=True,testEvaluationAllowed=False,newDataAcquisitionAllowed=False,
        acceptanceCriteriaChanged=False,productionDefaultChanged=False,
        sources=[info(p) for p in sorted(sources)],imports=[info(p) for p in sorted(set(imports))],
        frozenInputs=[info(p) for p in files],initialChecks=checks,orderedRuntimeClasspath=classpath(),
        runtime=runtime_entries(),predecessors=predecessor_bindings())
    freeze(OUT/'training-plan.json',plan);verify_plan()
    print(json.dumps({'registeredFits':4,'checkpoints':12,'screenRequests':plan['maximumScreenRequests'],
                      'maximumValidationRequests':plan['maximumValidationRequests']}),flush=True)


def verify_plan():
    plan=read(OUT/'training-plan.json')
    assert plan['arms']==ARMS and plan['training']['seeds']==SEEDS and plan['training']['checkpoints']==CHECKPOINTS
    assert plan['fixedComparisonStep']==2080 and plan['newFits']==4 and not plan['testEvaluationAllowed']
    for entry in plan['sources']+plan['imports']+plan['frozenInputs']+plan['initialChecks']:
        assert digest(ROOT/entry['path'])==entry['sha256'],entry['path']
    for entry in plan['runtime']:
        assert digest(Path(entry['path']))==entry['sha256'],entry['path']
    for entry in (plan['screeningPanel'],plan['validationInputs'],plan['warmup']):
        assert digest(ROOT/entry['path'])==entry['sha256'],entry['path']
    return plan


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('mode',choices=['register','verify']);args=p.parse_args()
    register() if args.mode=='register' else print(json.dumps({'verified':verify_plan()['revision']}))
