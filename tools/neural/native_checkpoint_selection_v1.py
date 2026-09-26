"""Frozen three-checkpoint selection using full ten-worker native validation."""
import argparse
import copy
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import zipfile

import numpy as np

import checkpoint_selection as policy
import generalized_design as design
import historical_neural_inputs as history_inputs
import prepare_gen3_holdouts as holdouts
from prepare_generalized_evaluation import canonical_input_hash
from prepare_transformer_data import read_rows, digest, strict, stats

ROOT = Path(__file__).resolve().parents[2]
AREA = ROOT/'build/neural-transformer/selection-v1'
PLAN = ROOT/'tools/neural/checkpoint-selection-plan.json'
CHECKS = ROOT/'build/neural-transformer/selection-checks-v1'
ACCURACY = ROOT/'build/neural-transformer/accuracy-v1'
REVISION = 'native-checkpoint-selection-v1'
GENERATOR_SEED = 202609115
SELECTION_SEED = 'native-checkpoint-selection-20260911-v1'


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def freeze(path, value, rows=False):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('x', encoding='utf-8', newline='\n') as stream:
        if rows:
            for row in value: stream.write(json.dumps(row, sort_keys=True, allow_nan=False)+'\n')
        else:
            stream.write(json.dumps(value, sort_keys=True, indent=2, allow_nan=False)+'\n')


def info(path):
    return {'path': path.relative_to(ROOT).as_posix(), 'sha256': digest(path)}


def exact_copy(source, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open('xb') as stream: stream.write(source.read_bytes())
    if digest(source) != digest(target): raise ValueError('Artifact copy mismatch')


def original_artifacts():
    from transformer_accuracy_study import verify_plan as verify_accuracy
    previous = verify_accuracy()
    if read(ACCURACY/'selection.json')['arm'] != 'baseline' or read(ACCURACY/'selection.json')['seed'] != policy.REFERENCE:
        raise ValueError('Reference differs from the reviewed accuracy-v1 selection')
    manifest_path = ROOT/'tools/neural/transformer-accuracy-cache-manifest.json'
    manifest = read(manifest_path)
    if digest(ROOT/manifest['archivePath']) != manifest['archiveSha256']:
        raise ValueError('Previous verified archive changed')
    entries = {item['entry']: item for item in manifest['entries']}
    paths = [ACCURACY/'fits'/f'baseline-{seed}'/name for seed in policy.SEEDS for name in ('model.pt','report.json')]
    paths += [ACCURACY/'native'/name for name in ('model.json','fixture.json','java-parity.json','parity.json')]
    for path in paths:
        if digest(path) != entries[path.relative_to(ROOT).as_posix()]['sha256']:
            raise ValueError('Archived checkpoint/reference artifact changed: '+str(path))
    return previous, manifest_path, paths


def prepare():
    if PLAN.exists(): verify_plan(); return
    if AREA.exists(): raise FileExistsError('Unregistered selection output directory exists')
    previous, manifest_path, artifacts = original_artifacts()
    scheduler = read(CHECKS/'scheduling-check-final.json')
    if scheduler['allPassed'] is not True or scheduler.get('shutdownInterruptionWaitedForTermination') is not True:
        raise ValueError('Run scheduler checks first')
    training_path = ROOT/previous['trainingData']['path']
    validation_path = ROOT/previous['validation']['path']
    rows = read_rows(training_path)
    train = [r for r in rows if r['split'] == 'train' and r.get('labelProvenance', {}).get('eligibleForFitting')]
    validation = read_rows(validation_path)
    if len(train) != 805 or len(validation) != 405 or sum(strict(r) for r in validation) != 168:
        raise ValueError('Original population changed')

    # This inventory uses inputs only, including unsuccessful and interrupted
    # historical campaigns; their outcome fields never affect test sampling.
    historical, archived_populations, historical_manifests = history_inputs.collect(ROOT)
    inventory = []
    for folder in sorted((ROOT/'build').glob('neural*')):
        if not folder.is_dir(): continue
        for path in sorted(folder.rglob('*.jsonl')):
            before = digest(path); count = 0
            for row in read_rows(path):
                if isinstance(row, dict) and isinstance(row.get('input'), dict):
                    historical.add(canonical_input_hash(row['input'])); count += 1
            if digest(path) != before: raise ValueError('Historical input inventory changed while reading')
            if count: inventory.append({**info(path), 'inputRows': count})
    critical = {training_path.relative_to(ROOT).as_posix(), validation_path.relative_to(ROOT).as_posix(),
                'build/neural-gen3/fresh-design/candidate-pool.jsonl',
                'build/neural-transformer/accuracy-v1/candidate-pool.jsonl'}
    if not critical <= {entry['path'] for entry in inventory}:
        raise ValueError('Historical input inventory is incomplete')
    baseline = read(design.DEFAULT_BASELINE)['input']
    old_data = json.loads(history_inputs.archived_entry(ROOT,'transformer-cache-manifest.json','data-v2/data.json'))
    old_holdout = [json.loads(line) for line in history_inputs.archived_entry(
        ROOT,'transformer-cache-manifest.json','data-v2/fresh-holdout.jsonl').decode('utf-8').splitlines() if line.strip()]
    old_pool, old_admitted, old_excluded, _ = design.generate(baseline, old_data['holdout']['seed'])
    holdouts.SELECTION_SEED = old_data['holdout']['selectionSeed']
    reproduced = holdouts.select_fresh(old_admitted)
    if (len(old_pool) != old_data['holdout']['candidateRows'] or len(old_excluded) != old_data['holdout']['preflightExcluded']
            or {canonical_input_hash(r['input']) for r in reproduced} != {canonical_input_hash(r['input']) for r in old_holdout}):
        raise ValueError('Regenerated Gen4 pool does not reproduce its archived design evidence')
    historical.update(canonical_input_hash(r['input']) for r in old_pool)
    candidates, admitted, excluded, generated = design.generate(baseline, GENERATOR_SEED)
    history_inputs.new_pool(candidates,historical)
    holdouts.SELECTION_SEED = SELECTION_SEED
    selected = copy.deepcopy(holdouts.select_fresh(admitted))
    for row in selected:
        row['id'] = 'g6fresh-'+str(row['id']).removeprefix('gd-')
        row['split'] = 'test'
        row['design'].update(trainingAllowed=False, origin=REVISION)
    history_inputs.fresh_test(selected,historical)
    freeze(AREA/'historical-input-hashes.json', sorted(historical))
    freeze(AREA/'historical-input-inventory.json', {'files':inventory, 'verifiedArchivePopulations':archived_populations,
        'verifiedManifests':historical_manifests,'uniqueInputs':len(historical),
        'regeneratedGen4Pool':{'generatorSeed':202609104,'cases':len(old_pool),
            'canonicalInputHashesSha256':history_inputs.canonical_digest({canonical_input_hash(r['input']) for r in old_pool}),
            'archivedHoldoutReproduced':True,'archivedCandidateAndExclusionCountsMatched':True},
        'outcomesUsedForSelection':False})
    freeze(AREA/'candidate-pool.jsonl', candidates, True)
    freeze(AREA/'test.jsonl', selected, True)
    freeze(AREA/'sampling.json', {'generator':generated, 'generatorSeed':GENERATOR_SEED,
        'selectionSeed':SELECTION_SEED, 'candidateCases':len(candidates), 'admittedCases':len(admitted),
        'excludedCases':len(excluded), 'testCases':252, 'historicalOverlap':0,
        'historicalHashesSha256':digest(AREA/'historical-input-hashes.json'), 'selectionUsesOutcomes':False})
    # Select original classical TRAIN observations, not neural-rescued labels
    # whose cold classical solve is not known to converge.
    candidates_for_check = sorted((r for r in train if 2 <= r['input']['stageCount'] <= 6
        and r['labelProvenance']['origin'] == 'old_matrix'
        and r['labelProvenance']['selected']['journal'] == 'data/cases.jsonl'),
        key=lambda r:(r['input']['stageCount'],r['id']))
    fixtures, seen = [], set()
    for row in candidates_for_check:
        checksum = canonical_input_hash(row['input'])
        if checksum not in seen:
            fixtures.append(row); seen.add(checksum)
        if len(fixtures) == 10: break
    if len(fixtures) != 10 or any(not strict(r) for r in fixtures): raise ValueError('Missing certified TRAIN fixtures')
    freeze(AREA/'concurrency-fixtures.jsonl', fixtures, True)
    sources = {ROOT/'build.gradle', ROOT/'gradle.properties', Path(__file__)}
    sources.update((ROOT/'src/main/java').rglob('*.java'))
    sources.update(ROOT/'tools/neural'/name for name in (
        'checkpoint_selection.py','historical_neural_inputs.py','test_checkpoint_selection.py',
        'test_checkpoint_selection_provenance.py','checkpoint-selection-protocol.md',
        'V3ConcurrentColumnEvaluationProbe.java','V3BoundedEvaluation.java','V3BoundedEvaluationCheck.java',
        'V3GeneralTrainingProbe.java','V3GeneralParallelCheck.java','V3ColumnTransformerInitializer.java',
        'V3CandidateModels.java','V3TransformerParityCheck.java','V3NeuralMvpProbe.java',
        'generalized_design.py','prepare_gen3_holdouts.py','prepare_generalized_evaluation.py',
        'prepare_transformer_data.py','prepare_gen3_data.py','train_transformer.py','train_generalized.py',
        'train_gen3_factorized.py','train_transformer_accuracy.py','transformer_accuracy_study.py',
        'transformer-accuracy-plan.json','transformer-accuracy-protocol.md',
        'unified_column_evaluation.py','methane-qualification.json'))
    registered = {'training': info(training_path), 'validation':info(validation_path),
        'test':info(AREA/'test.jsonl'), 'historicalHashes':info(AREA/'historical-input-hashes.json'),
        'historicalInventory':info(AREA/'historical-input-inventory.json'),
        'candidatePool':info(AREA/'candidate-pool.jsonl'), 'concurrencyFixtures':info(AREA/'concurrency-fixtures.jsonl'),
        'previousManifest':info(manifest_path), 'schedulerCheck':info(CHECKS/'scheduling-check-final.json')}
    for index, record in enumerate(historical_manifests): registered[f'historicalManifest{index}'] = record
    for name in ('test','incumbent','fixtureSource'):
        registered['previousAccuracy'+name.title()] = previous[name]
    plan = {'revision':REVISION, 'createdUtc':datetime.now(timezone.utc).isoformat(), 'policy':policy.POLICY,
        'seeds':list(policy.SEEDS), 'referenceSeed':policy.REFERENCE, 'validationOrder':list(policy.SEEDS),
        'validationCases':405, 'profileReferences':168, 'testCases':252,
        'registered':registered, 'artifacts':{p.relative_to(ROOT).as_posix():digest(p) for p in artifacts},
        'sources':{p.relative_to(ROOT).as_posix():digest(p) for p in sorted(sources)},
        'selectionRule':'Non-reference must have more strict FIRST qualifications than reference, cover the union of all current classical validation sets, and have <= unrounded all-case FIRST mean latency. Rank by -qualifications, latency, seed; retain reference if none.',
        'testSelectionAllowed':False, 'trainingAllowed':False, 'testDeduplicateIdenticalArtifacts':True,
        'previousArchiveSha256':read(manifest_path)['archiveSha256']}
    freeze(PLAN, plan); verify_plan()
    print('Frozen: three archived checkpoints, 405 validation, fresh test 252, ten workers.', flush=True)


def verify_plan():
    plan = read(PLAN)
    if (plan['policy'] != policy.POLICY or plan['seeds'] != list(policy.SEEDS)
            or plan['referenceSeed'] != policy.REFERENCE or plan['trainingAllowed'] is not False):
        raise ValueError('Registered experiment changed')
    for record in plan['registered'].values():
        if digest(ROOT/record['path']) != record['sha256']: raise ValueError('Frozen input or evidence changed')
    for path, checksum in {**plan['sources'], **plan['artifacts']}.items():
        if digest(ROOT/path) != checksum: raise ValueError('Frozen source or artifact changed: '+path)
    from transformer_accuracy_study import verify_plan as verify_accuracy
    verify_accuracy()
    return plan


def export():
    import torch
    import train_transformer as pilot
    import train_generalized as base
    verify_plan(); models = {}
    torch.set_num_threads(4)
    for seed in policy.SEEDS:
        directory = AREA/'models'/str(seed)
        cp_path = ACCURACY/'fits'/f'baseline-{seed}'/'model.pt'
        report_path = cp_path.with_name('report.json')
        if directory.exists(): raise FileExistsError('Checkpoint export already exists')
        if seed == policy.REFERENCE:
            for name in ('model.json','fixture.json','java-parity.json'):
                exact_copy(ACCURACY/'native'/name, directory/name)
            parity = read(ACCURACY/'native/parity.json')
            parity.update(modelSha256=digest(directory/'model.json'),
                          fixtureSha256=digest(directory/'fixture.json'),
                          javaParitySha256=digest(directory/'java-parity.json'),
                          reusedFrom=info(ACCURACY/'native/parity.json'))
            freeze(directory/'parity.json', parity)
        else:
            checkpoint = torch.load(cp_path, map_location='cpu', weights_only=True)
            model = pilot.ColumnModel('transformer'); model.load_state_dict(checkpoint['state_dict']); model.eval()
            doc = read(ACCURACY/'native/model.json'); norm = checkpoint['normalization']
            doc.update(modelId=f'tjl20-checkpoint-selection-{seed}', normalization=norm,
                checkpointSha256=digest(cp_path), selection='Archived baseline checkpoint; no native validation or test selection yet.',
                weights={k:{'shape':list(v.shape),'values':v.detach().numpy().ravel().tolist()} for k,v in model.state_dict().items()})
            freeze(directory/'model.json', doc); model.double(); fixtures = []
            for original in read(ACCURACY/'native/fixture.json'):
                row = copy.deepcopy(original); inp = row['input']
                g = base.global_features(inp); x = base.node_features(inp,'TWO_PHASE')[:,:-3]
                xx = torch.tensor((x-norm['xm'])/norm['xscale'],dtype=torch.float64)[None]
                gg = torch.tensor((g-norm['gm'])/norm['gscale'],dtype=torch.float64)[None]
                with torch.no_grad(): raw, branch = model(xx,gg,torch.ones((1,len(x)),dtype=torch.bool))
                row.update({'global':g.tolist(),'nodes':x.tolist(),
                    'raw':(raw[0].numpy()*norm['yscale']+norm['ym']).tolist(),'branchLogits':branch[0].tolist()})
                fixtures.append(row)
            freeze(directory/'fixture.json', fixtures)
        models[str(seed)] = {**info(directory/'model.json'), 'checkpoint':info(cp_path),
                            'fitReport':info(report_path), 'fixture':info(directory/'fixture.json')}
    freeze(AREA/'models.json', {'planSha256':digest(PLAN),'models':models})
    print('Three native artifacts frozen; reference reused byte for byte.', flush=True)


def models():
    record = read(AREA/'models.json')
    if record['planSha256'] != digest(PLAN): raise ValueError('Exports belong to another plan')
    if set(record['models']) != {str(s) for s in policy.SEEDS}: raise ValueError('Missing or extra checkpoint export')
    plan = read(PLAN)
    for label, model in record['models'].items():
        checkpoint_path = (ACCURACY/'fits'/f'baseline-{label}'/'model.pt').relative_to(ROOT).as_posix()
        if (model['checkpoint']['path'] != checkpoint_path
                or model['checkpoint']['sha256'] != plan['artifacts'][checkpoint_path]
                or read(ROOT/model['path'])['checkpointSha256'] != model['checkpoint']['sha256']):
            raise ValueError('Export/checkpoint identity mismatch')
        for item in (model, model['checkpoint'], model['fitReport'], model['fixture']):
            if digest(ROOT/item['path']) != item['sha256']: raise ValueError('Native artifact changed')
    if record['models'][str(policy.REFERENCE)]['sha256'] != plan['artifacts'][(ACCURACY/'native/model.json').relative_to(ROOT).as_posix()]:
        raise ValueError('Reference artifact was replaced')
    return record['models']


def gradle(arguments, log_name):
    logs = AREA/'logs'; logs.mkdir(parents=True, exist_ok=True)
    with (logs/log_name).open('x', encoding='utf-8', newline='\n') as log:
        with subprocess.Popen([str(ROOT/'gradlew.bat'), *arguments, '--offline'], cwd=ROOT,
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace') as process:
            for line in process.stdout:
                log.write(line); log.flush(); print(line, end='', flush=True)
            if process.wait(): raise subprocess.CalledProcessError(process.returncode, process.args)


def parity():
    import train_generalized as base
    from train_gen3_factorized import decode
    verify_plan(); registered = models()
    for seed in policy.SEEDS:
        directory = AREA/'models'/str(seed)
        if (directory/'parity.json').exists():
            verify_parity(directory, registered[str(seed)]); continue
        gradle(['transformerNeuralModelParity', '-PtransformerModelDirectory='+str(directory.relative_to(ROOT))], f'parity-{seed}.log')
        doc, fixtures, actual = read(directory/'model.json'), read(directory/'fixture.json'), read(directory/'java-parity.json')
        if len(fixtures) != len(actual['predictions']): raise ValueError('Missing native parity fixtures')
        max_t = max_q = 0.
        for row, result in zip(fixtures, actual['predictions']):
            if row['id'] != result['id']: raise ValueError('Parity ID mismatch')
            logits = np.array(row['branchLogits']); logits[~np.array(doc['branchesSeen'])] = -np.inf
            if any(s.get('ratio',0)>0 for s in row['input']['specifications']): logits[2] = -np.inf
            expected = decode(row['input'],np.array(row['raw']),base.BRANCHES[int(logits.argmax())],.02)
            observed = result['prediction']; feed = sum(row['input']['feedComponentMolarFlowsMolPerSecond'])
            if expected['branch'] != observed['branch'] or not np.array_equal(expected['wetTrays'],observed['wetTrays']):
                raise ValueError('Native branch/wet mismatch')
            max_t = max(max_t,float(np.max(np.abs(expected['temperatures']-observed['temperatures']))))
            max_q = max(max_q,max(float(np.max(np.abs(expected[k]-observed[k])))/feed for k in ('liquid','vapor','freeWater')))
            for k in ('liquid','vapor'):
                if not np.array_equal(expected[k]==0,np.array(observed[k])==0): raise ValueError('Native support mismatch')
        if (max_t >= 5e-5 or max_q >= 2e-6 or actual['parallelIdenticalPredictions'] != 32
                or not actual['cancellationPassed'] or not actual['malformedShapeRejected']):
            raise ValueError('Native inference parity gate failed')
        freeze(directory/'parity.json', {'fixtures':len(fixtures),'temperatureMaxK':max_t,'flowMaxOverFeed':max_q,
            'branchWetZeroMasksMatch':True,'parallelIdenticalPredictions':32,'cancellationPassed':True,
            'malformedShapeRejected':True,'modelSha256':registered[str(seed)]['sha256'],
            'fixtureSha256':digest(directory/'fixture.json'),'javaParitySha256':digest(directory/'java-parity.json')})
    target = AREA/'native-concurrency-check.json'
    if not target.exists():
        gradle(['generalNeuralParallelCheck', '-PgeneralSource='+str((AREA/'concurrency-fixtures.jsonl').relative_to(ROOT)),
                '-PgeneralParallelReport='+str(target.relative_to(ROOT))], 'native-concurrency.log')
    policy.validate_numerical_check(read(target),read_rows(AREA/'concurrency-fixtures.jsonl'))
    evidence = certification_evidence()
    if (AREA/'certification.json').exists():
        if read(AREA/'certification.json') != evidence: raise ValueError('Completed certification changed')
    else: freeze(AREA/'certification.json',evidence)
    print('Export parity and ten-worker native numerical consistency passed.', flush=True)


def verify_parity(directory, model):
    evidence = read(directory/'parity.json')
    if (evidence['modelSha256'] != model['sha256'] or evidence['fixtureSha256'] != digest(directory/'fixture.json')
            or evidence['javaParitySha256'] != digest(directory/'java-parity.json')
            or evidence['temperatureMaxK'] >= 5e-5 or evidence['flowMaxOverFeed'] >= 2e-6
            or evidence['parallelIdenticalPredictions'] != 32 or not evidence['cancellationPassed']
            or not evidence['malformedShapeRejected'] or not evidence['branchWetZeroMasksMatch']):
        raise ValueError('Native parity evidence mismatch')


def certification_evidence():
    registered = models()
    for seed in policy.SEEDS: verify_parity(AREA/'models'/str(seed),registered[str(seed)])
    target = AREA/'native-concurrency-check.json'
    policy.validate_numerical_check(read(target),read_rows(AREA/'concurrency-fixtures.jsonl'))
    return {'planSha256':digest(PLAN), 'exports':info(AREA/'models.json'),
            'nativeNumericalCheck':info(target),
            'parity':{str(s):info(AREA/'models'/str(s)/'parity.json') for s in policy.SEEDS}}


def execution_lock(allow_create=False):
    if read(AREA/'certification.json') != certification_evidence():
        raise ValueError('Certification files changed after qualification')
    lock = {'planSha256':digest(PLAN),'certification':info(AREA/'certification.json'),
            'exports':info(AREA/'models.json')}
    path = AREA/'execution-lock.json'
    if path.exists():
        if read(path) != lock: raise ValueError('Pre-campaign execution lock changed')
    elif allow_create and not (AREA/'validation').exists() and not (AREA/'test').exists():
        freeze(path,lock)
    else: raise ValueError('Missing pre-campaign execution lock')


def checked_run(folder, source_record, model):
    source = read_rows(ROOT/source_record['path'])
    rows, meta = read_rows(folder/'evaluation.jsonl'), read(folder/'run.json')
    policy.validate_run(rows,meta,source,model['sha256'],source_record['sha256'])
    return rows, meta


def campaign(split, seeds):
    plan = verify_plan(); registered = models()
    if split == 'validation':
        if tuple(seeds) != policy.SEEDS: raise ValueError('Use the registered validation campaign order')
    elif split == 'test':
        if list(seeds) != policy.test_seeds(selected_seed(), registered):
            raise ValueError('Only frozen reference/winner artifacts may enter the test')
    else: raise ValueError('Unknown campaign population')
    execution_lock(allow_create=split == 'validation')
    for seed in seeds:
        model = registered[str(seed)]; verify_parity(AREA/'models'/str(seed),model)
        folder = AREA/split/str(seed)
        if not folder.exists():
            gradle(['concurrentColumnEvaluation', '-PcolumnEvaluationOutput='+str(folder.relative_to(ROOT)),
                '-PcolumnEvaluationSource='+plan['registered'][split]['path'],
                '-PcolumnEvaluationModel='+model['path'], '-PcolumnEvaluationWorkers=10',
                '-PcolumnEvaluationDeadlineSeconds=30','-PcolumnEvaluationNeuralBudgetMillis=2000',
                '-PcolumnEvaluationIterations=16'], f'{split}-{seed}.log')
        checked_run(folder,plan['registered'][split],model)


def validation():
    campaign('validation', policy.SEEDS)


def validation_results():
    plan = verify_plan(); registered = models(); records = {}; raw = {}; inputs = {}
    execution_lock()
    for seed in policy.SEEDS:
        folder = AREA/'validation'/str(seed)
        rows, meta = checked_run(folder,plan['registered']['validation'],registered[str(seed)])
        analysis = policy.analyze(rows)
        records[str(seed)] = {**analysis,'run':meta,'journal':info(folder/'evaluation.jsonl')}
        raw[seed] = rows
        inputs[seed] = {'currentIds':analysis['qualifiedIds']['current'],
            'firstIds':analysis['qualifiedIds']['neuralFirst'],
            'firstMeanMillis':analysis['strategies']['neuralFirst']['ms']['mean']}
    environments = {(r['run']['java'],r['run']['maximumHeapBytes'],r['run']['availableProcessors']) for r in records.values()}
    if len(environments) != 1: raise ValueError('Validation campaigns used different execution environments')
    return records, raw, policy.choose(inputs)


def select():
    records, raw, decision = validation_results(); plan = read(PLAN); registered = models()
    freeze(AREA/'selection.json', {'planSha256':digest(PLAN), 'createdUtc':datetime.now(timezone.utc).isoformat(),
        'executionLockSha256':digest(AREA/'execution-lock.json'),
        'validationComplete':True,'testUsed':False,'testSha256':plan['registered']['test']['sha256'],
        'selectedModelSha256':registered[str(decision['selectedSeed'])]['sha256'], 'decision':decision,
        'validation':records, 'pairedAgainstReference':{str(s):policy.paired_models(raw[policy.REFERENCE],raw[s]) for s in policy.SEEDS}})
    print(json.dumps(decision), flush=True)


def selected_seed():
    plan = verify_plan(); registered = models(); selection = read(AREA/'selection.json')
    seed = policy.test_gate(selection,digest(PLAN),plan['registered']['test']['sha256'],registered)
    if selection['executionLockSha256'] != digest(AREA/'execution-lock.json'):
        raise ValueError('Selection execution lock changed')
    records, _, decision = validation_results()
    if selection['decision'] != decision or selection['validation'] != records:
        raise ValueError('Frozen validation results or selection calculation changed')
    return seed


def test():
    seed = selected_seed()
    campaign('test', policy.test_seeds(seed, models()))


def summary_data():
    seed = selected_seed(); plan = read(PLAN); registered = models(); selection = read(AREA/'selection.json')
    tests, raw = {}, {}
    for s in policy.test_seeds(seed,registered):
        folder = AREA/'test'/str(s); rows, meta = checked_run(folder,plan['registered']['test'],registered[str(s)])
        tests[str(s)] = {**policy.analyze(rows),'run':meta,'journal':info(folder/'evaluation.jsonl')}; raw[s] = rows
        reference_run = selection['validation'][str(policy.REFERENCE)]['run']
        if any(meta[k] != reference_run[k] for k in ('java','maximumHeapBytes','availableProcessors')):
            raise ValueError('Test environment differs from the frozen validation campaign')
    same = registered[str(seed)]['sha256'] == registered[str(policy.REFERENCE)]['sha256']
    summary = {'revision':REVISION,'planSha256':digest(PLAN),'selectionSha256':digest(AREA/'selection.json'),
        'policy':policy.POLICY,'selectedSeed':seed,'referenceSeed':policy.REFERENCE,'sameArtifact':same,
        'decision':selection['decision'],'validation':selection['validation'],
        'validationPairedAgainstReference':selection['pairedAgainstReference'],'test':tests,
        'testRoles':{'reference':policy.REFERENCE,'selected':policy.REFERENCE if same else seed},
        'testPaired':policy.paired_models(raw[policy.REFERENCE],raw[policy.REFERENCE if same else seed]),
        'cachedProfileMetrics168':{str(s):read(ROOT/registered[str(s)]['fitReport']['path'])['validation'] for s in policy.SEEDS},
        'testInferenceBeforeSelection':False,'trainingPerformed':False}
    return summary


def report_text(summary):
    seed, same = summary['selectedSeed'], summary['sameArtifact']
    fmt = lambda value: f"{value['mean']:.4g} ± {value['sampleSd']:.3g}" if value else 'unavailable'
    lines = ['# Native checkpoint selection results','',
        f"Selected seed **{seed}**; reference **{policy.REFERENCE}**. No training was performed. All campaigns used ten workers, 2-second neural and 30-second request budgets, with 16 iterations per correction pass.", '',
        '| Seed | FIRST qualified / 405 | FIRST ms, mean ± sample SD | More qualified | Covers classical union | No mean-latency regression | Eligible |',
        '|---|---:|---:|---|---|---|---|']
    for s in policy.SEEDS:
        d = summary['decision']['decisions'][str(s)]
        lines.append(f"| {s} | {d['qualified']} | {fmt(summary['validation'][str(s)]['strategies']['neuralFirst']['ms'])} | {d['moreQualifications']} | {d['preservesClassicalUnion']} | {d['noMeanLatencyRegression']} | {d['eligible']} |")
    if summary['decision']['retainedReference']:
        lines += ['', 'No alternative passed all predeclared gates; the reference was retained. This does not establish that the reference covers the union of classical successes.']
    lines += ['', 'The rule protects classical qualifications; it can permit loss of individual reference-only neural successes. Exact gains/losses and classical-control disagreements are retained in the summary.', '',
        '| Population | Seed | Strategy | Strict qualified | Advisory only | Failed | Elapsed ms, mean ± sample SD | CPU ms, mean ± sample SD |',
        '|---|---|---|---:|---:|---:|---:|---:|']
    for population, records in (('validation / 405',summary['validation']),('test / 252',summary['test'])):
        for label, record in records.items():
            for mode, values in record['strategies'].items():
                lines.append(f"| {population} | {label} | {mode} | {values['qualified']} | {values['advisoryOnly']} | {values['failed']} | {fmt(values.get('ms'))} | {fmt(values.get('cpuMillis'))} |")
    lines += ['', 'All-case times include failures and CPU contention. SD describes variation across cases, not repeated-run timing uncertainty. Queue wait, allocation volume, diagnostic Newton counters and per-case paired differences are in the summary. These concurrent measurements must not be pooled with archived serial timings.', '',
        'The reference and selected roles share one test execution because their artifact hashes match.' if same else 'Reference and selected artifacts each completed the full prospective test after selection was frozen.', '',
        'The 168-reference profile metrics are retained from the archived fits for diagnosis; they did not select the winner. Test outcomes did not alter the winner, and no runtime default was promoted.', '']
    return '\n'.join(lines)


def report():
    summary = summary_data()
    freeze(AREA/'summary.json',summary)
    with (AREA/'report.md').open('x',encoding='utf-8',newline='\n') as stream: stream.write(report_text(summary))
    print(json.dumps({'selectedSeed':summary['selectedSeed'],'sameArtifact':summary['sameArtifact'],
        'testQualified':{s:{m:v['qualified'] for m,v in r['strategies'].items()} for s,r in summary['test'].items()}}),flush=True)


def verify_report():
    # Recompute from complete current journals through the frozen selection and
    # certification chain. Matching ZIP entries alone cannot detect stale reports.
    expected = summary_data()
    if read(AREA/'summary.json') != expected:
        raise ValueError('Reported summary no longer matches its underlying journals or artifacts')
    if (AREA/'report.md').read_text(encoding='utf-8') != report_text(expected):
        raise ValueError('Reported text no longer matches the verified summary')
    return expected


def seal():
    plan = verify_plan(); summary = verify_report()
    if summary['planSha256'] != digest(PLAN) or summary['selectionSha256'] != digest(AREA/'selection.json'):
        raise ValueError('Summary provenance mismatch')
    directory = ROOT/'.neural-cache/checkpoint-selection-v1'; directory.mkdir(parents=True,exist_ok=True)
    paths = {p for p in AREA.rglob('*') if p.is_file()}
    paths.update(p for p in CHECKS.rglob('*') if p.is_file())
    paths.update(ROOT/r['path'] for r in plan['registered'].values())
    paths.update(ROOT/p for p in plan['artifacts']); paths.update(ROOT/p for p in plan['sources'])
    paths.update((ROOT/'src/main/resources/data/createcheme/neural').glob('*.json'))
    paths.update((ROOT/'gradle/wrapper').glob('*'))
    paths.update(ROOT/'tools/neural'/name for name in ('requirements-transformer.txt','README.md',
        'checkpoint-selection-protocol.md','checkpoint-selection-findings.md','transformer-accuracy-cache-manifest.json',
        'unified-evaluation-cache-manifest.json','transformer-cache-manifest.json'))
    paths.update((PLAN,ROOT/'settings.gradle',ROOT/'gradle.properties',ROOT/'gradlew',ROOT/'gradlew.bat'))
    archive = directory/'study.zip'; entries = []
    with zipfile.ZipFile(archive,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as output:
        for path in sorted(paths):
            name = path.relative_to(ROOT).as_posix(); output.write(path,name)
            entries.append({'entry':name,'sha256':digest(path),'bytes':path.stat().st_size})
    with zipfile.ZipFile(archive) as output:
        if output.testzip() is not None: raise ValueError('Archive CRC failure')
        for entry in entries:
            value = output.read(entry['entry'])
            if len(value) != entry['bytes'] or hashlib.sha256(value).hexdigest() != entry['sha256']:
                raise ValueError('Archive entry mismatch')
    manifest = {'revision':REVISION,'archivePath':archive.relative_to(ROOT).as_posix(),
        'archiveSha256':digest(archive),'archiveBytes':archive.stat().st_size,'entries':entries,'allEntriesVerified':True,
        'previousAccuracyArchiveSha256':plan['previousArchiveSha256']}
    freeze(directory/'manifest.json',manifest)
    freeze(ROOT/'tools/neural/checkpoint-selection-cache-manifest.json',manifest)
    exact_copy(AREA/'report.md',ROOT/'tools/neural/checkpoint-selection-results.md')
    exact_copy(AREA/'summary.json',ROOT/'tools/neural/checkpoint-selection-summary.json')
    print(json.dumps({'archiveBytes':manifest['archiveBytes'],'verifiedEntries':len(entries)}),flush=True)


if __name__ == '__main__':
    actions = {'prepare':prepare,'verify':verify_plan,'export':export,'parity':parity,'validation':validation,
               'select':select,'test':test,'report':report,'seal':seal}
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=actions)
    actions[parser.parse_args().command]()
