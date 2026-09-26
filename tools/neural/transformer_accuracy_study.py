"""Prepare, fit, select and measure the frozen transformer accuracy study."""
import argparse
from datetime import datetime, timezone
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import zipfile

import numpy as np
import generalized_design as design
import prepare_gen3_holdouts as holdouts
from prepare_generalized_evaluation import canonical_input_hash
from prepare_transformer_data import read_rows, digest, strict, stats
import unified_column_evaluation as unified
from train_transformer_accuracy import ARMS, SEEDS

ROOT = Path(__file__).resolve().parents[2]
AREA = ROOT/'build/neural-transformer/accuracy-v1'
PLAN = ROOT/'tools/neural/transformer-accuracy-plan.json'


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def freeze(path, value, rows=False):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('x', encoding='utf-8', newline='\n') as f:
        f.write(''.join(json.dumps(r, sort_keys=True, allow_nan=False)+'\n' for r in value) if rows
                else json.dumps(value, sort_keys=True, indent=2, allow_nan=False)+'\n')


def info(path):
    return {'path': path.relative_to(ROOT).as_posix(), 'sha256': digest(path)}


def prepare():
    if PLAN.exists():
        verify_plan(); return
    if AREA.exists():
        raise FileExistsError('An unregistered accuracy output directory exists')
    data = ROOT/'build/neural-transformer/data-v2/cases.jsonl'
    rows = read_rows(data)
    prior_pool = read_rows(ROOT/'build/neural-gen3/fresh-design/candidate-pool.jsonl')
    baseline = read(design.DEFAULT_BASELINE)['input']
    old_pool, _, _, _ = design.generate(baseline, 202609104)
    prior = {canonical_input_hash(r['input']) for r in rows+prior_pool+old_pool}
    candidates, admitted, excluded, generated = design.generate(baseline, 202609114)
    keys = [canonical_input_hash(r['input']) for r in candidates]
    if len(keys)!=len(set(keys)) or prior.intersection(keys):
        raise ValueError('Fresh pool overlaps historical inputs or contains duplicates')
    holdouts.SELECTION_SEED = 'transformer-accuracy-independent-20260911-v1'
    selected = copy.deepcopy(holdouts.select_fresh(admitted))
    for row in selected:
        row['id'] = 'g5fresh-'+str(row['id']).removeprefix('gd-')
        row['split'] = 'test'
        row['design'].update(trainingAllowed=False, origin='accuracy_v1_prospective_test')
    if len(selected)!=252:
        raise ValueError('The unified test must contain all 252 inputs')
    freeze(AREA/'candidate-pool.jsonl', candidates, True)
    freeze(AREA/'test.jsonl', selected, True)
    freeze(AREA/'sampling.json', {'generator': generated, 'seed': 202609114,
        'selectionSeed': holdouts.SELECTION_SEED, 'historicalInputHashesChecked': len(prior),
        'candidateCases': len(candidates), 'admittedCases': len(admitted),
        'preflightExcluded': len(excluded), 'testCases': 252, 'overlapCount': 0,
        'selectionUsesOutcomes': False})
    sources = [ROOT/'tools/neural'/name for name in (
        'train_transformer_accuracy.py', 'train_transformer.py', 'train_generalized.py',
        'train_gen3_factorized.py', 'V3ColumnTransformerInitializer.java',
        'V3CandidateModels.java', 'V3GeneralTrainingProbe.java', 'transformer-accuracy-protocol.md')]
    plan = {'revision': 'transformer-accuracy-v1', 'createdUtc': datetime.now(timezone.utc).isoformat(),
        'trainingData': info(data), 'trainingColumns': 805,
        'validation': {**info(ROOT/'build/neural-transformer/native-v1/validation.jsonl'), 'cases':405, 'qualifiedReferences':168},
        'test': {**info(AREA/'test.jsonl'), 'cases':252},
        'incumbent': info(ROOT/'build/neural-transformer/native-v1/transformer/model.json'),
        'fixtureSource': info(ROOT/'build/neural-transformer/native-v1/transformer/fixture.json'),
        'sources': {p.relative_to(ROOT).as_posix():digest(p) for p in sources},
        'arms': {k:list(v) for k,v in ARMS.items()}, 'seeds':list(SEEDS), 'epochs':160, 'patience':40,
        'selection': 'Same decoded validation score for all epochs and arms; choose lowest mean across three seeds, then arm name. Choose lowest-score seed within the winning arm, then seed. Freeze before test. No test selection.',
        'testPolicy': unified.POLICY, 'strategies':list(unified.MODES),
        'testSelectionAllowed': False, 'historicalTestRetainedUnchanged': True}
    freeze(PLAN, plan); verify_plan()
    print('Frozen: 805 TRAIN, 405 validation (168 references), fresh unified test 252; six arms x three CUDA seeds.', flush=True)


def verify_plan():
    plan = read(PLAN)
    if plan['arms']!={k:list(v) for k,v in ARMS.items()} or plan['seeds']!=list(SEEDS):
        raise ValueError('Registered training experiment changed')
    if plan['testPolicy']!=unified.POLICY or plan['strategies']!=list(unified.MODES):
        raise ValueError('Unified evaluation method changed')
    for key in ('trainingData', 'validation', 'test', 'incumbent', 'fixtureSource'):
        record = plan[key]
        if digest(ROOT/record['path'])!=record['sha256']:
            raise ValueError('Frozen artifact changed: '+key)
    for path, checksum in plan['sources'].items():
        if digest(ROOT/path)!=checksum:
            raise ValueError('Frozen implementation changed: '+path)
    return plan


def fit():
    verify_plan()
    for arm in ARMS:
        for seed in SEEDS:
            output = AREA/'fits'/f'{arm}-{seed}'
            if output.exists():
                report = read(output/'report.json')
                if report['arm']!=arm or report['seed']!=seed or not (output/'model.pt').is_file() or not (output/'validation.jsonl').is_file():
                    raise ValueError('Incomplete or mismatched fit')
                continue
            subprocess.run([sys.executable, 'tools/neural/train_transformer_accuracy.py', '--arm', arm,
                            '--seed', str(seed), '--output', str(output.relative_to(ROOT))], cwd=ROOT, check=True)


def select():
    plan = verify_plan(); groups = {}
    for arm in ARMS:
        reports = [read(AREA/'fits'/f'{arm}-{seed}'/'report.json') for seed in SEEDS]
        if any(r['dataSha256']!=plan['trainingData']['sha256'] or
               r['trainerSha256']!=plan['sources']['tools/neural/train_transformer_accuracy.py'] for r in reports):
            raise ValueError('Mixed fitting sources')
        groups[arm] = {'metrics': {key:stats([r['validation'][key]['mean'] for r in reports]) for key in reports[0]['validation']},
                       'trainingSeconds': stats([r['trainingSeconds'] for r in reports]), 'reports':reports}
    arm = min(groups, key=lambda k:(groups[k]['metrics']['selectionScore']['mean'], k))
    chosen = min(groups[arm]['reports'], key=lambda r:(r['bestSelectionScore'],r['seed']))
    checkpoint = AREA/'fits'/f"{arm}-{chosen['seed']}"/'model.pt'
    selection = {'revision': plan['revision'], 'planSha256':digest(PLAN), 'arm':arm, 'seed':chosen['seed'],
                 'checkpoint':info(checkpoint), 'testSha256':plan['test']['sha256'],
                 'testUsed':False, 'groups':groups}
    freeze(AREA/'selection.json', selection)
    print(json.dumps({'selectedArm':arm, 'seed':chosen['seed'],
        'score':chosen['bestSelectionScore'], 'baselineMean':groups['baseline']['metrics']['selectionScore']['mean'],
        'selectedMean':groups[arm]['metrics']['selectionScore']['mean']}), flush=True)


def export():
    import torch
    import train_transformer as pilot
    import train_generalized as base
    plan = verify_plan(); selected = read(AREA/'selection.json')
    cp_path = ROOT/selected['checkpoint']['path']
    if digest(cp_path)!=selected['checkpoint']['sha256'] or selected['planSha256']!=digest(PLAN):
        raise ValueError('Selected checkpoint changed')
    cp = torch.load(cp_path, map_location='cpu', weights_only=True)
    model = pilot.ColumnModel('transformer'); model.load_state_dict(cp['state_dict']); model.eval()
    doc = read(ROOT/plan['incumbent']['path'])
    doc.update(modelId=f"tjl20-accuracy-v1-{selected['arm']}-{selected['seed']}", normalization=cp['normalization'],
        weights={k:{'shape':list(v.shape),'values':v.detach().numpy().ravel().tolist()} for k,v in model.state_dict().items()},
        checkpointSha256=digest(cp_path), selection='Frozen accuracy-v1 validation selection; no fresh test outcomes used.')
    directory = AREA/'native'; freeze(directory/'model.json',doc)
    model.double(); norm=cp['normalization']; fixtures=[]
    for original in read(ROOT/plan['fixtureSource']['path']):
        row = copy.deepcopy(original); inp=row['input']
        g = base.global_features(inp); x=base.node_features(inp,'TWO_PHASE')[:,:-3]
        xx=torch.tensor((x-norm['xm'])/norm['xscale'],dtype=torch.float64)[None]
        gg=torch.tensor((g-norm['gm'])/norm['gscale'],dtype=torch.float64)[None]
        with torch.no_grad(): pred, branch = model(xx,gg,torch.ones((1,len(x)),dtype=torch.bool))
        row.update({'global':g.tolist(),'nodes':x.tolist(),'raw':(pred[0].numpy()*norm['yscale']+norm['ym']).tolist(),
                    'branchLogits':branch[0].tolist()})
        fixtures.append(row)
    freeze(directory/'fixture.json',fixtures)
    freeze(AREA/'native-selection.json',{'planSha256':digest(PLAN),'selectionSha256':digest(AREA/'selection.json'),
        'candidate':info(directory/'model.json'), 'incumbent':plan['incumbent'], 'test':plan['test']})


def parity():
    import train_generalized as base
    from train_gen3_factorized import decode
    verify_plan(); directory=AREA/'native'
    subprocess.run([str(ROOT/'gradlew.bat'),'transformerNeuralModelParity',
                    '-PtransformerModelDirectory='+str(directory.relative_to(ROOT)),'--offline'],cwd=ROOT,check=True)
    doc=read(directory/'model.json'); expected=read(directory/'fixture.json'); actual=read(directory/'java-parity.json')
    if len(expected)!=len(actual['predictions']): raise ValueError('Missing parity fixtures')
    maximum_t=maximum_q=0.
    for row,result in zip(expected,actual['predictions']):
        if row['id']!=result['id']: raise ValueError('Mismatched fixture')
        logits=np.array(row['branchLogits']); logits[~np.array(doc['branchesSeen'])]=-np.inf
        if any(s.get('ratio',0)>0 for s in row['input']['specifications']): logits[2]=-np.inf
        wanted=decode(row['input'],np.array(row['raw']),base.BRANCHES[int(logits.argmax())],.02)
        observed=result['prediction']; feed=sum(row['input']['feedComponentMolarFlowsMolPerSecond'])
        if wanted['branch']!=observed['branch'] or not np.array_equal(wanted['wetTrays'],observed['wetTrays']):
            raise ValueError('Phase/wet parity mismatch')
        maximum_t=max(maximum_t,float(np.max(np.abs(wanted['temperatures']-observed['temperatures']))))
        maximum_q=max(maximum_q,max(float(np.max(np.abs(wanted[k]-observed[k])))/feed for k in ('liquid','vapor','freeWater')))
        for k in ('liquid','vapor'):
            if not np.array_equal(wanted[k]==0,np.array(observed[k])==0): raise ValueError('Trace support mismatch')
    if maximum_t>=5e-5 or maximum_q>=2e-6 or not actual['cancellationPassed'] or not actual['malformedShapeRejected'] or actual['parallelIdenticalPredictions']!=32:
        raise ValueError('Native parity gate failed')
    freeze(directory/'parity.json',{'fixtures':len(expected),'temperatureMaxK':maximum_t,'flowMaxOverFeed':maximum_q,
        'branchWetZeroMasksMatch':True,'parallelIdenticalPredictions':32,'cancellationPassed':True,'malformedShapeRejected':True})
    print('Java/PyTorch and concurrency parity passed.',flush=True)


def profiles():
    """Diagnostic comparison of two already-frozen models; never selects weights."""
    import torch
    import train_transformer as pilot
    import train_transformer_accuracy as accuracy
    plan=verify_plan(); selection=read(AREA/'native-selection.json')
    torch.set_num_threads(4)
    rows=[r for r in read_rows(ROOT/plan['validation']['path']) if strict(r)]
    results={}; summaries={}
    for label in ('incumbent','candidate'):
        entry=selection[label]
        if digest(ROOT/entry['path'])!=entry['sha256']: raise ValueError('Frozen model changed')
        doc=read(ROOT/entry['path']); model=pilot.ColumnModel('transformer').eval()
        model.load_state_dict({k:torch.tensor(v['values'],dtype=torch.float32).reshape(v['shape']) for k,v in doc['weights'].items()})
        batch=accuracy.tensors(rows,doc['normalization'],'cpu')
        ym,ys=(torch.tensor(doc['normalization'][k]) for k in ('ym','yscale'))
        with torch.no_grad():
            pred,branch=model(batch['x'],batch['g'],batch['valid'])
            values={k:v.numpy() for k,v in accuracy.metrics(pred*ys+ym,branch,batch).items()}
        results[label]=values
        summaries[label]={'modelSha256':entry['sha256'],'metrics':{k:stats(v) for k,v in values.items()}}
        freeze(AREA/(label+'-validation-profiles.jsonl'),
               [{'id':r['id'],**{k:float(v[i]) for k,v in values.items()}} for i,r in enumerate(rows)],True)
    paired={k:stats(results['candidate'][k]-results['incumbent'][k]) for k in results['candidate']}
    freeze(AREA/'profile-comparison.json',{'cases':len(rows),'scope':'168 qualified validation references; CPU float32; both models frozen before this diagnostic',
        'models':summaries,'candidateMinusIncumbent':paired})
    print(json.dumps({'profileComparison':{label:{k:v['mean'] for k,v in summary['metrics'].items()} for label,summary in summaries.items()}}),flush=True)


def native():
    plan=verify_plan(); selection=read(AREA/'native-selection.json'); read(AREA/'native/parity.json')
    if selection['selectionSha256']!=digest(AREA/'selection.json') or selection['planSha256']!=digest(PLAN):
        raise ValueError('Native selection changed')
    source=read_rows(ROOT/plan['test']['path'])
    for label in ('incumbent','candidate'):
        entry=selection[label]
        if digest(ROOT/entry['path'])!=entry['sha256']: raise ValueError('Frozen model changed')
        folder=AREA/'test'/label
        if folder.exists():
            unified.validate_run(read_rows(folder/'evaluation.jsonl'),read(folder/'run.json'),source,entry['sha256'],plan['test']['sha256'])
            continue
        subprocess.run([str(ROOT/'gradlew.bat'),'generalNeuralExperiment','-PgeneralMode=benchmark',
            '-PgeneralSource='+plan['test']['path'],'-PgeneralOutput='+str(folder.relative_to(ROOT)),
            '-PgeneralModel='+entry['path'],'-PgeneralWorkers=1','-PgeneralNeuralBudgetMillis=2000',
            '-PgeneralDeadlineSeconds=30','-PgeneralIterations=16','--offline'],cwd=ROOT,check=True)


def report():
    plan=verify_plan(); selection=read(AREA/'selection.json'); native_selection=read(AREA/'native-selection.json')
    source=read_rows(ROOT/plan['test']['path']); records={}; qualified={}
    for label in ('incumbent','candidate'):
        folder=AREA/'test'/label; rows=read_rows(folder/'evaluation.jsonl'); meta=read(folder/'run.json')
        unified.validate_run(rows,meta,source,native_selection[label]['sha256'],plan['test']['sha256'])
        strategies={m:unified.summarize([{**r,**r[m]} for r in rows]) for m in unified.MODES}
        qualified[label]={m:{r['id'] for r in rows if strict({**r,**r[m]})} for m in unified.MODES}
        common=[r for r in rows if strict({**r,**r['current']}) and strict({**r,**r['neuralFirst']})]
        within={'firstGains':len(qualified[label]['neuralFirst']-qualified[label]['current']),
                'firstLosses':len(qualified[label]['current']-qualified[label]['neuralFirst']),
                'firstMinusCurrentMillis':stats([r['neuralFirst']['ms']-r['current']['ms'] for r in rows]),
                'commonQualifiedCases':len(common)}
        if common:
            within['firstMinusCurrentMillisCommonQualified']=stats([r['neuralFirst']['ms']-r['current']['ms'] for r in common])
        records[label]={'strategies':strategies,'pairedAgainstCurrent':within,'run':meta,'journalSha256':digest(folder/'evaluation.jsonl')}
    pair={m:{'candidateGains':len(qualified['candidate'][m]-qualified['incumbent'][m]),
             'candidateLosses':len(qualified['incumbent'][m]-qualified['candidate'][m])} for m in unified.MODES}
    summary={'revision':plan['revision'],'planSha256':digest(PLAN),'selectedArm':selection['arm'],'selectedSeed':selection['seed'],
        'validation':{arm:{k:v for k,v in group.items() if k!='reports'} for arm,group in selection['groups'].items()},
        'test':records,'pairedCandidateVsIncumbent':pair,'parity':read(AREA/'native/parity.json'),
        'frozenModelProfileComparison':read(AREA/'profile-comparison.json')}
    freeze(AREA/'summary.json',summary)
    fmt=lambda value:f"{value['mean']:.4g} ± {value['sampleSd']:.3g}"
    lines=['# Transformer accuracy ablation results','',
        'Six predeclared arms used the same 805 training columns, 168 qualified references from the 405-case validation set, and three CUDA seeds. Architecture and deployment decoder were fixed. Validation SD below is across three seed-level column means.', '',
        '| Arm | Selection score | Temperature MAE K | Liquid total MAE / feed | Vapor total MAE / feed | Trace log10 MAE |',
        '|---|---:|---:|---:|---:|---:|']
    for arm,group in selection['groups'].items():
        values=group['metrics']; lines.append('| '+arm+' | '+' | '.join(fmt(values[k]) for k in
            ('selectionScore','temperatureMaeK','liquidTotalMaeOverFeed','vaporTotalMaeOverFeed','traceLog10Mae'))+' |')
    lines += ['',f"Validation selected **{selection['arm']}**, seed **{selection['seed']}**, before fresh-test execution.",'']
    if selection['arm']=='baseline':
        lines += ['None of the added regularization/decoded-flow-loss configurations improved the mean selection score across seeds. The selected candidate is a baseline checkpoint selected with the new decoded criterion; any candidate/incumbent difference is not evidence that the added loss or regularization helped.', '']
    lines += ['The following comparison uses the two frozen native models on the same 168 qualified validation references. SD here is across columns, not seeds. It is diagnostic and does not change selection.', '',
              '| Frozen model | Temperature MAE K | Liquid total MAE / feed | Vapor total MAE / feed | Trace log10 MAE |',
              '|---|---:|---:|---:|---:|']
    for label,record in summary['frozenModelProfileComparison']['models'].items():
        lines.append('| '+label+' | '+' | '.join(fmt(record['metrics'][k]) for k in
            ('temperatureMaeK','liquidTotalMaeOverFeed','vaporTotalMaeOverFeed','traceLog10Mae'))+' |')
    lines += ['',
        'The new 252-case test was frozen before fitting. Every input ran CURRENT_ONLY, LNN_ONLY and LNN_FIRST serially under the unified 2-second neural / 30-second whole-request budgets. No timing subset is used. The incumbent is the previously frozen native transformer; the baseline training arm uses the same new checkpoint-selection criterion as all ablations.', '',
        '| Model | Strategy | Qualified / 252 | Advisory only | Failed | Elapsed ms, mean ± sample SD | CPU ms, mean ± sample SD |',
        '|---|---|---:|---:|---:|---:|---:|']
    for label,record in records.items():
        for mode,value in record['strategies'].items():
            lines.append(f"| {label} | {mode} | {value['qualified']} | {value['advisoryOnly']} | {value['failed']} | {fmt(value['ms'])} | {fmt(value['cpuMillis'])} |")
    lines += ['', 'Test SD describes variation across cases; all-case timing includes failures. Paired candidate gains/losses are in the summary. The repeated classical controls are not independent cases. Native diagnostic Newton counters are not sums of every attempted correction pass. Allocation volume is not retained RAM.', '',
        'Hard support decisions have no training gradient; the original presence BCE and trace losses remain active. The additional flow term uses the deployment decoder within admissible raw output bounds. Invalid predictions receive an explicit selection penalty. No physical residual loss, synthetic labels, solver-policy changes or production-default promotion is part of this study.', '']
    with (AREA/'report.md').open('x',encoding='utf-8',newline='\n') as f: f.write('\n'.join(lines))
    print(json.dumps({'selectedArm':selection['arm'],'paired':pair,'qualified':{k:{m:len(v) for m,v in q.items()} for k,q in qualified.items()}}),flush=True)


def seal():
    plan=verify_plan(); summary=read(AREA/'summary.json')
    if summary['planSha256']!=digest(PLAN): raise ValueError('Summary belongs to another plan')
    cache=ROOT/'.neural-cache/transformer-accuracy-v1'; cache.mkdir(parents=True,exist_ok=True)
    files={p:p.relative_to(ROOT).as_posix() for p in AREA.rglob('*') if p.is_file()}
    paths=[ROOT/plan[k]['path'] for k in ('trainingData','validation','test','incumbent','fixtureSource')]
    paths += [ROOT/path for path in plan['sources']]
    paths += [PLAN,Path(__file__),ROOT/'tools/neural/test_transformer_accuracy.py']
    # Capture imported helpers and the unchanged native source/resource dependencies
    # as well as the prospectively registered training sources. These extra source
    # snapshots are archival, not additional preregistration claims.
    paths += list((ROOT/'tools/neural').glob('*.py'))
    paths += list((ROOT/'tools/neural').glob('*.java'))
    paths += list((ROOT/'src/main/java').rglob('*.java'))
    paths += list((ROOT/'src/main/resources/data/createcheme/neural').glob('*.json'))
    paths += [ROOT/'tools/neural'/name for name in (
        'methane-qualification.json','requirements-transformer.txt','transformer-accuracy-findings.md',
        'README.md','unified-evaluation.md','unified-evaluation.json',
        'unified-evaluation-cache-manifest.json','transformer-cache-manifest.json')]
    paths += [ROOT/'build/neural-gen3/fresh-design/candidate-pool.jsonl',
              ROOT/'build.gradle',ROOT/'gradle.properties',ROOT/'settings.gradle',
              ROOT/'gradlew',ROOT/'gradlew.bat']
    paths += list((ROOT/'gradle/wrapper').glob('*'))
    for p in paths: files[p]=p.relative_to(ROOT).as_posix()
    archive=cache/'study.zip'; entries=[]
    with zipfile.ZipFile(archive,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for p,name in sorted(files.items()):
            z.write(p,name); entries.append({'entry':name,'sha256':digest(p),'bytes':p.stat().st_size})
    with zipfile.ZipFile(archive) as z:
        if z.testzip() is not None: raise ValueError('Archive CRC failure')
        for entry in entries:
            data=z.read(entry['entry'])
            if len(data)!=entry['bytes'] or hashlib.sha256(data).hexdigest()!=entry['sha256']:
                raise ValueError('Archive entry verification failed')
    manifest={'revision':plan['revision'],'archivePath':archive.relative_to(ROOT).as_posix(),
        'archiveSha256':digest(archive),'archiveBytes':archive.stat().st_size,'entries':entries,'allEntriesVerified':True,
        'registeredSources':plan['sources'],'additionalSourceSnapshotTiming':'after evaluation',
        'previousUnifiedCacheManifestSha256':digest(ROOT/'tools/neural/unified-evaluation-cache-manifest.json'),
        'previousGpuCacheManifestSha256':digest(ROOT/'tools/neural/transformer-cache-manifest.json')}
    freeze(cache/'manifest.json',manifest); freeze(ROOT/'tools/neural/transformer-accuracy-cache-manifest.json',manifest)
    for source,target in [('summary.json','transformer-accuracy-summary.json'),('report.md','transformer-accuracy-results.md')]:
        with (ROOT/'tools/neural'/target).open('xb') as f: f.write((AREA/source).read_bytes())
    print(json.dumps({'archiveBytes':manifest['archiveBytes'],'verifiedEntries':len(entries)}),flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=('prepare','verify','fit','select','export','parity','profiles','native','report','seal'))
    command=parser.parse_args().command
    {'prepare':prepare,'verify':verify_plan,'fit':fit,'select':select,'export':export,
     'parity':parity,'profiles':profiles,'native':native,'report':report,'seal':seal}[command]()
