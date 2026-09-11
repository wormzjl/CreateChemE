"""Prospective finite registration and fail-closed runtime source bindings."""
from common import *
import argparse
from datetime import datetime,timezone

ARMS={'C':dict(cohort='N804',layout='plain',inputs=96,trace=False),
      'T':dict(cohort='N804',layout='plain',inputs=96,trace=True),
      'F':dict(cohort='N804',layout='full',inputs=185,trace=False),
      'K':dict(cohort='N804',layout='compact',inputs=103,trace=False),
      'D':dict(cohort='N905',layout='plain',inputs=96,trace=False)}
SEEDS=[20260911,20260912]
CHECKPOINTS=[3120,4160,4640]


def predecessor_bindings():
    result=[]
    for name in ('tools/training-curation/cache-manifest.json','tools/hybrid-diagnosis/cache-manifest.json',
                 'tools/hybrid-learning/results-cache-manifest.json','tools/neural/transformer-accuracy-cache-manifest.json'):
        manifest=read(ROOT/name)
        archive=manifest['archive'] if isinstance(manifest.get('archive'),dict) else dict(path=manifest['archivePath'],sha256=manifest['archiveSha256'])
        assert digest(ROOT/archive['path'])==archive['sha256']
        result.append(dict(manifest=info(ROOT/name),archive=archive))
    return result


def register():
    from prepare_data import build
    from initialization import ANCHORS
    cohorts,references,validation,_=build();metadata=read(OUT/'cohorts.json')
    assert [len(cohorts[name]) for name in ('N804','added101','N905')]==[804,101,905]
    assert read(OUT/'validation-reference-binding.json')['passed']
    assert read(OUT/'initialization-proof.json')['passed']
    coefficient=read(OUT/'calibration.json');assert coefficient['weightsUnchanged'] and coefficient['newOptimizerSteps']==0
    cp=read(OUT/'calibration-plan.json')
    for record in cp['sources']+[cp[k] for k in ('source','normalization','incumbent')]:assert digest(ROOT/record['path'])==record['sha256']
    assert coefficient['coefficient']==min(.1,.1*coefficient['medianBaseGradientL2']/max(coefficient['medianTraceGradientL2'],1e-12))
    initial_checks=[]
    for layout in ('plain','full','compact'):
        directory=OUT/'initial-models'/layout;check=read(directory/'precision-check.json')
        assert check['passed'] and check['maskDifferences']==0
        for record in (check['model'],check['fixtures'],check['javaParity']):assert digest(ROOT/record['path'])==record['sha256']
        initial_checks.extend(info(directory/name) for name in ('model.json','fixture.json','java-parity.json','precision-check.json'))
    area=ROOT/'build/neural-hybrid-learning/v1'
    panel=ROOT/'build/neural-hybrid-diagnosis/v1/ablation-panel.jsonl'
    assert digest(panel)=='0b287ecc9ff91f073dbdefdffaf92bc1b0ff452aef6ad5bb1779939911b9c1a8' and len(read_rows(panel))==65
    assert {r['id'] for r in read_rows(panel)}<={r['id'] for r in validation}
    controls={}
    for name,path in [('I',area/'models/incumbent/pipeline.json'),('H805',area/'models/N-20260911/pipeline.json')]:
        pipeline=read(path);assert digest(ROOT/pipeline['weights']['path'])==pipeline['weights']['sha256']
        controls[name]={**info(path),'weights':pipeline['weights']}
    source_root=ROOT/'tools/trace-followup'
    sources=[p for p in source_root.rglob('*') if p.is_file() and p.suffix in ('.py','.java','.gradle','.md') and '__pycache__' not in p.parts]
    dependencies=[ROOT/'tools/neural'/name for name in ('train_transformer.py','train_transformer_accuracy.py','train_generalized.py',
        'train_gen3_factorized.py','prepare_transformer_data.py','prepare_generalized_evaluation.py','prepare_gen3_data.py',
        'checkpoint_selection.py','native_checkpoint_selection_v1.py','V3BoundedEvaluation.java')]
    dependencies+=[ROOT/'tools/hybrid-learning/train_hybrid.py',ROOT/'tools/hybrid-learning/benchmark.py']
    prior_native=read(area/'benchmark-plan.json')
    dependencies += [ROOT/item['path'] for item in prior_native['sources'] if item['path'].endswith('.java')]
    for item in prior_native['sources']:assert digest(ROOT/item['path'])==item['sha256']
    input_files=[OUT/name for name in ('cohorts.json','N804.jsonl','N905.jsonl','added101.jsonl','normalization.json',
        'validation-references.jsonl','validation-inputs.jsonl','validation-reference-binding.json','initialization-proof.json',
        'calibration-plan.json','calibration.json')]+ANCHORS
    screen_order=['I']+[f'{arm}-{seed}-s{step}' for seed in SEEDS for step in CHECKPOINTS for arm in ARMS]+['H805']
    plan=dict(revision='trace-followup-training-and-native-v1',createdUtc=datetime.now(timezone.utc).isoformat(),baseCommit='bad3346',
        arms=ARMS,armOrder=list(ARMS),training=dict(seeds=SEEDS,updates=4640,checkpoints=CHECKPOINTS,learningRate=8e-5,weightDecay=1e-4,
            batchSize=32,dropout=0,gradientClip=1.,scheduler=None,precision='float32 CUDA; TF32 disabled; deterministic algorithms',
            initialization='All ten fits copy the same retained incumbent; seeds control independent complete-shuffle streams.'),
        cohorts=metadata,traceCoefficient=coefficient['coefficient'],calibration=info(OUT/'calibration.json'),
        normalization=info(OUT/'normalization.json'),incumbent=info(INCUMBENT),initialChecks=initial_checks,
        controls=controls,screeningPanel=info(panel),screeningCases=65,screenOrder=screen_order,screenBlocks=1,
        validationInputs=info(OUT/'validation-inputs.jsonl'),validationCases=405,primarySeed=20260911,validationBlocks=2,
        warmup=info(area/'warmup.json'),workers=10,requestDeadlineMillis=30000,neuralBudgetMillis=2000,maximumIterations=16,
        heapBytes=4*1024**3,strategies=['current','neural','neuralFirst'],newFits=10,newCheckpoints=30,
        maximumScreenRequests=6240,maximumValidationPipelines=15,maximumValidationRequests=36450,
        warmupRequestsPerPipelineInvocation=6,
        nativeCheckpointRule='Preserve contemporaneous classical panel-success union, maximize strict FIRST count, lower all-case FIRST mean ms, earlier update.',
        profileCheckpointRule='Primary T: minimum unchanged 168-reference profile score among the same three registered checkpoints; earlier tie.',
        fullValidationRule='Mandatory primary C/D4160/4640 and T/F/K4160; primary native selections for all arms; primary T profile selection; I/H805. Deduplicate complete pipelines. Two reversed-order blocks on all405.',
        qualificationRule='Strict FIRST improvement over I in both blocks, preserve contemporaneous classical-success union both blocks, no pooled all-case mean-latency regression. No automatic promotion.',
        postprocessing='Raw factorized seed for every new model. H805 retains its unchanged historical wrapper.',
        diagnosticCapture='Store the already computed seed presented by each pipeline; native measurement equations and solver calls remain unchanged.',
        testEvaluationAllowed=False,newDataAcquisitionAllowed=False,automaticPromotionAllowed=False,
        sources=[info(p) for p in sorted(sources)],dependencies=[info(p) for p in sorted(set(dependencies))],
        frozenInputs=[info(p) for p in input_files],predecessors=predecessor_bindings())
    freeze(OUT/'training-plan.json',plan);verify_plan()
    print(json.dumps({'registeredFits':10,'checkpoints':30,'traceCoefficient':plan['traceCoefficient'],
        'maxMeasuredNativeRequests':plan['maximumScreenRequests']+plan['maximumValidationRequests']}))


def verify_plan():
    plan=read(OUT/'training-plan.json')
    assert plan['arms']==ARMS and plan['training']['seeds']==SEEDS and plan['training']['checkpoints']==CHECKPOINTS
    assert plan['newFits']==10 and plan['newCheckpoints']==30 and not plan['testEvaluationAllowed']
    for record in plan['sources']+plan['dependencies']+plan['frozenInputs']+plan['initialChecks']+[plan[k] for k in ('incumbent','screeningPanel','validationInputs','warmup')]:
        assert digest(ROOT/record['path'])==record['sha256'],record['path']
    for item in plan['controls'].values():
        for record in (item,item['weights']):assert digest(ROOT/record['path'])==record['sha256']
    return plan


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('mode',choices=['register','verify']);args=p.parse_args()
    register() if args.mode=='register' else print(json.dumps({'verified':verify_plan()['revision']}))
