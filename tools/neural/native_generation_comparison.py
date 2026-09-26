"""Compare archived Gen2/Gen3 with the verified ten-worker transformer runs."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import zipfile

import numpy as np
import checkpoint_selection as policy
import generation_comparison as analysis
from prepare_generalized_evaluation import canonical_input_hash
from prepare_transformer_data import digest, read_rows, strict

ROOT = Path(__file__).resolve().parents[2]
AREA = ROOT/'build/neural-generations/comparison-v1'
CHECKS = ROOT/'build/neural-generations/comparison-checks-v1'
PLAN = ROOT/'tools/neural/generation-comparison-plan.json'
REVISION = 'matched-generation-comparison-v1'
NEW_MODELS = ('gen2','gen3')
ORDER = (('validation','gen2'),('validation','gen3'),('test','gen2'),('test','gen3'))


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def info(path):
    return {'path':path.relative_to(ROOT).as_posix(),'sha256':digest(path)}


def freeze(path, value, rows=False):
    path.parent.mkdir(parents=True,exist_ok=True)
    with path.open('x',encoding='utf-8',newline='\n') as stream:
        if rows:
            for row in value: stream.write(json.dumps(row,sort_keys=True,allow_nan=False)+'\n')
        else: stream.write(json.dumps(value,sort_keys=True,indent=2,allow_nan=False)+'\n')


def copy_bytes(source, target):
    target.parent.mkdir(parents=True,exist_ok=True)
    with target.open('xb') as stream: stream.write(source.read_bytes())
    if digest(source) != digest(target): raise ValueError('Artifact copy changed')


def verify_records(records):
    for r in records:
        if digest(ROOT/r['path']) != r['sha256']: raise ValueError('Frozen file changed: '+r['path'])


def assets():
    if (CHECKS/'assets.json').exists(): return verify_assets()
    if CHECKS.exists(): raise FileExistsError('Unregistered comparison checks directory')
    gen2_manifest = ROOT/'tools/neural/gen2-cache-manifest.json'
    gen3_manifest = ROOT/'tools/neural/gen3-cache-manifest.json'
    g2,g3 = read(gen2_manifest),read(gen3_manifest)
    archive = ROOT/g3['archivePath']
    if digest(archive) != g3['archiveSha256']: raise ValueError('Gen3 archive changed')
    dependencies = [info(gen2_manifest),info(gen3_manifest),info(archive)]
    frozen = []

    def gen2(cached, target):
        entry=next(r for r in g2['files'] if r['cachedPath']==cached)
        source=ROOT/g2['cacheRoot']/cached
        if digest(source)!=entry['sha256'] or source.stat().st_size!=entry['bytes']: raise ValueError('Gen2 cache changed')
        copy_bytes(source,target); dependencies.append(info(source)); frozen.append(info(target))

    def gen3(entry_name, target):
        entry=next(r for r in g3['files'] if r['archiveEntry']==entry_name)
        with zipfile.ZipFile(archive) as source: data=source.read(entry_name)
        if hashlib.sha256(data).hexdigest()!=entry['sha256'] or len(data)!=entry['bytes']: raise ValueError('Gen3 entry changed')
        target.parent.mkdir(parents=True,exist_ok=True)
        with target.open('xb') as stream: stream.write(data)
        frozen.append(info(target))

    gen2('model.json',CHECKS/'gen2/general-model.json')
    for name in ('general-feature-parity.json','java-parity.json','parity-report.json'):
        target = name if name=='general-feature-parity.json' else 'archived-'+name
        gen2('study/model-v1/'+name,CHECKS/'gen2'/target)
    for name in ('factorized-model.json','factorized-feature-parity.json','java-parity.json','parity-report.json'):
        target = 'archived-'+name if name in ('java-parity.json','parity-report.json') else name
        gen3('factorized-v1/'+name,CHECKS/'gen3'/target)
    gen3('selection.json',CHECKS/'archived-gen3-selection.json')
    selection=read(CHECKS/'archived-gen3-selection.json')
    models={'gen2':info(CHECKS/'gen2/general-model.json'),'gen3':info(CHECKS/'gen3/factorized-model.json')}
    if selection['selectedNeural']!='gen3-factorized': raise ValueError('Unexpected selected Gen3 model')
    for label, candidate in (('gen2','gen2'),('gen3','gen3-factorized')):
        if models[label]['sha256']!=selection['candidates'][candidate]['modelSha256']: raise ValueError('Selected model mismatch')
        parity=read(CHECKS/label/'archived-parity-report.json')
        if parity['passed'] is not True or parity['model_sha256']!=models[label]['sha256']: raise ValueError('Archived parity mismatch')
    training_path=ROOT/g2['cacheRoot']/'study/v2/cases.jsonl'
    entry=next(r for r in g2['files'] if r['cachedPath']=='study/v2/cases.jsonl')
    if digest(training_path)!=entry['sha256']: raise ValueError('Original TRAIN source changed')
    dependencies.append(info(training_path))
    train=sorted((r for r in read_rows(training_path) if r['split']=='train' and strict(r)),key=lambda r:(r['input']['stageCount'],r['id']))
    if len(train)<10: raise ValueError('Insufficient original TRAIN fixtures')
    fixtures=[train[i*(len(train)-1)//9] for i in range(10)]
    fixture_path=CHECKS/'training-fixtures.jsonl'
    freeze(fixture_path,[{'id':r['id'],'split':'train','input':r['input']} for r in fixtures],True)
    frozen.append(info(fixture_path))
    expected_ids={r['id'] for r in read(CHECKS/'gen2/general-feature-parity.json')}
    parity_rows=[r for r in train if r['id'] in expected_ids]
    if len(parity_rows)!=len(expected_ids): raise ValueError('Missing original TRAIN parity fixture')
    freeze(CHECKS/'gen2/cases.jsonl',parity_rows,True); frozen.append(info(CHECKS/'gen2/cases.jsonl'))
    result={'revision':REVISION,'models':models,'files':frozen,'dependencies':dependencies,
            'fixtures':info(fixture_path),'originalQualifiedTrainingCases':len(train)}
    freeze(CHECKS/'assets.json',result)
    return verify_assets()


def verify_assets():
    record=read(CHECKS/'assets.json')
    if record['revision']!=REVISION or set(record['models'])!=set(NEW_MODELS): raise ValueError('Unexpected assets')
    verify_records(record['files']+record['dependencies'])
    return record


def command(args, log_path):
    log_path.parent.mkdir(parents=True,exist_ok=True)
    with log_path.open('x',encoding='utf-8',newline='\n') as stream:
        with subprocess.Popen([str(a) for a in args],cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,
                text=True,encoding='utf-8',errors='replace') as process:
            for line in process.stdout:
                stream.write(line); stream.flush(); print(line,end='',flush=True)
            if process.wait(): raise subprocess.CalledProcessError(process.returncode,args)


def verify_parity(label, model):
    directory=CHECKS/label; report=read(directory/'current-parity-report.json')
    if report.get('passed') is not True or report.get('model_sha256')!=model['sha256'] or report.get('test_targets_used') is not False:
        raise ValueError('Current native parity failed')
    actual=read(directory/'current-java-parity.json')
    name='general-feature-parity.json' if label=='gen2' else 'factorized-feature-parity.json'
    fixture={r['id']:r for r in read(directory/name)}
    inputs = {r['id']:r['input'] for r in (read_rows(directory/'cases.jsonl') if label=='gen2' else fixture.values())}
    if (actual['modelSha256']!=model['sha256'] or len(actual['rows'])!=len(fixture)
            or {r['id'] for r in actual['rows']}!=set(fixture)):
        raise ValueError('Native parity coverage or identity mismatch')
    if label=='gen2':
        from check_general_model_parity import prediction
    else:
        from train_gen3_factorized import predict as prediction
    doc=read(ROOT/model['path'])
    for row in actual['rows']:
        if row['split']!='train': raise ValueError('Non-TRAIN parity input')
        if canonical_input_hash(row['input'])!=canonical_input_hash(inputs[row['id']]): raise ValueError('Parity input changed')
        expected=prediction(doc,row['input']); observed=row['prediction']
        if expected is None or observed is None or expected['branch']!=observed['branch']:
            raise ValueError('Native branch/support mismatch')
        np.testing.assert_array_equal(expected['wetTrays'],observed['wetTrays'])
        for key in ('temperatures','liquid','vapor','freeWater'):
            np.testing.assert_allclose(expected[key],observed[key],rtol=1e-8,atol=1e-8)
            if key!='temperatures': np.testing.assert_array_equal(np.asarray(expected[key])==0,np.asarray(observed[key])==0)


def preflight():
    record=assets()
    for label in NEW_MODELS:
        directory=CHECKS/label
        if not (directory/'current-java-parity.json').exists():
            task,prop=('generalNeuralModelParity','general') if label=='gen2' else ('factorizedNeuralModelParity','factorized')
            command([ROOT/'gradlew.bat',task,f'-P{prop}ModelDirectory={directory}',
                f'-P{prop}ParityOutput={directory}/current-java-parity.json','--offline'],directory/'java-parity.log')
        if not (directory/'current-parity-report.json').exists():
            script='check_general_model_parity.py' if label=='gen2' else 'check_gen3_factorized_parity.py'
            command([sys.executable,ROOT/'tools/neural'/script,directory,directory/'current-java-parity.json',
                '--output',directory/'current-parity-report.json'],directory/'python-parity.log')
        verify_parity(label,record['models'][label])
        if not (directory/'prediction-check.json').exists():
            command([ROOT/'gradlew.bat','-I','tools/neural/generation-comparison.gradle','generationPredictionCheck',
                '-PgenerationModel='+record['models'][label]['path'],'-PgenerationFixtures='+record['fixtures']['path'],
                '-PgenerationCheckOutput='+str(directory/'prediction-check.json'),'--offline'],directory/'prediction-check.log')
        analysis.check_prediction_evidence(read(directory/'prediction-check.json'),record['models'][label]['sha256'],record['fixtures']['sha256'])
    print('Gen2 and Gen3 current parity, exact masks and ten-worker prediction checks passed.',flush=True)


def prepare():
    if PLAN.exists(): verify_plan(); return
    if AREA.exists(): raise FileExistsError('Unregistered comparison output exists')
    import native_checkpoint_selection_v2 as previous
    old_summary=previous.verify_report(); old_plan=previous.read(previous.PLAN)
    if old_summary['selectedSeed']!=20260911: raise ValueError('Unexpected transformer reference')
    record=verify_assets()
    for label in NEW_MODELS:
        verify_parity(label,record['models'][label])
        analysis.check_prediction_evidence(read(CHECKS/label/'prediction-check.json'),record['models'][label]['sha256'],record['fixtures']['sha256'])
    old_manifest_path=ROOT/'tools/neural/checkpoint-selection-cache-manifest.json'
    old_manifest=read(old_manifest_path); old_archive=ROOT/old_manifest['archivePath']
    if digest(old_archive)!=old_manifest['archiveSha256']: raise ValueError('Verified reference archive changed')
    entries={r['entry']:r for r in old_manifest['entries']}
    def archived_copy(source,target):
        if digest(source)!=entries[source.relative_to(ROOT).as_posix()]['sha256']: raise ValueError('Reference artifact differs from archive')
        copy_bytes(source,target)
    models=dict(record['models']); ref_model=previous.models()['20260911']
    archived_copy(ROOT/ref_model['path'],AREA/'models/transformer.json')
    models['transformer']=info(AREA/'models/transformer.json')
    for name in ('fixture.json','java-parity.json','parity.json'):
        archived_copy(previous.AREA/'models/20260911'/name,AREA/'reference-evidence'/name)
    for name in ('certification.json','native-concurrency-check.json','execution-lock.json'):
        archived_copy(previous.AREA/name,AREA/'reference-evidence'/name)
    sets={}
    for split,count in (('validation',405),('test',252)):
        source=ROOT/old_plan['registered'][split]['path']
        archived_copy(source,AREA/'inputs'/f'{split}.jsonl')
        sets[split]={**info(AREA/'inputs'/f'{split}.jsonl'),'cases':count}
        for name in ('evaluation.jsonl','run.json'):
            archived_copy(previous.AREA/split/'20260911'/name,AREA/split/'transformer'/name)
    sources=dict(old_plan['sources'])
    for name in ('native_generation_comparison.py','generation_comparison.py','test_generation_comparison.py',
            'generation-comparison-protocol.md','generation-comparison.gradle','V3GenerationPredictionCheck.java',
            'check_general_model_parity.py','check_gen3_factorized_parity.py',
            'V3GeneralModelParityCheck.java','V3FactorizedModelParityCheck.java'):
        path=ROOT/'tools/neural'/name; sources[path.relative_to(ROOT).as_posix()]=digest(path)
    frozen=[info(p) for p in CHECKS.rglob('*') if p.is_file()]
    frozen += [info(AREA/split/'transformer'/name) for split in sets for name in ('evaluation.jsonl','run.json')]
    frozen += [info(p) for p in (AREA/'reference-evidence').iterdir()]
    dependencies=[info(old_manifest_path),info(old_archive)]+record['dependencies']
    plan={'revision':REVISION,'createdUtc':datetime.now(timezone.utc).isoformat(),'policy':policy.POLICY,
        'models':models,'sets':sets,'newCampaignOrder':[list(x) for x in ORDER], 'sources':sources,
        'frozenEvidence':frozen,'dependencies':dependencies,'reusedTransformerJournals':True,
        'trainingAllowed':False,'selectionAllowed':False,'testAlreadyExposed':True,
        'referenceArchiveSha256':old_manifest['archiveSha256']}
    freeze(PLAN,plan); verify_plan()
    print('Comparison frozen: two older models; 405 validation and 252 shared test cases; transformer runs reused.',flush=True)


def verify_plan():
    plan=read(PLAN)
    if (plan['revision']!=REVISION or plan['policy']!=policy.POLICY or set(plan['models'])!=set(analysis.LABELS)
        or plan['newCampaignOrder']!=[list(x) for x in ORDER] or plan['trainingAllowed'] is not False
        or plan['selectionAllowed'] is not False or plan['testAlreadyExposed'] is not True):
        raise ValueError('Comparison registration changed')
    verify_records(list(plan['models'].values())+list(plan['sets'].values())+plan['frozenEvidence']+plan['dependencies'])
    verify_records([{'path':p,'sha256':sha} for p,sha in plan['sources'].items()])
    return plan


def checked_run(plan,split,label):
    source=read_rows(ROOT/plan['sets'][split]['path'])
    if len(source)!=plan['sets'][split]['cases']: raise ValueError('Population count changed')
    directory=AREA/split/label; rows=read_rows(directory/'evaluation.jsonl'); meta=read(directory/'run.json')
    policy.validate_run(rows,meta,source,plan['models'][label]['sha256'],plan['sets'][split]['sha256'])
    reference=read(AREA/split/'transformer/run.json')
    if any(meta[k]!=reference[k] for k in ('java','maximumHeapBytes','availableProcessors')):
        raise ValueError('Execution environment differs from the reused reference')
    return rows,meta


def run():
    plan=verify_plan()
    for split in ('validation','test'): checked_run(plan,split,'transformer')
    for split,label in ORDER:
        folder=AREA/split/label
        if not folder.exists():
            command([ROOT/'gradlew.bat','concurrentColumnEvaluation','-PcolumnEvaluationOutput='+str(folder),
                '-PcolumnEvaluationSource='+plan['sets'][split]['path'],'-PcolumnEvaluationModel='+plan['models'][label]['path'],
                '-PcolumnEvaluationWorkers=10','-PcolumnEvaluationDeadlineSeconds=30',
                '-PcolumnEvaluationNeuralBudgetMillis=2000','-PcolumnEvaluationIterations=16','--offline'],AREA/'logs'/f'{split}-{label}.log')
        checked_run(plan,split,label)


def results():
    plan=verify_plan(); populations={}; journals={}; cases=[]
    for split in ('validation','test'):
        rows={}; journals[split]={}
        for label in analysis.LABELS:
            rows[label],meta=checked_run(plan,split,label)
            journals[split][label]={'journal':info(AREA/split/label/'evaluation.jsonl'),
                'runFile':info(AREA/split/label/'run.json'),'run':meta,'reused':label=='transformer'}
        source=read_rows(ROOT/plan['sets'][split]['path'])
        certified={r['id'] for r in source if strict(r)}
        populations[split]=analysis.compare(rows,certified)
        cases.extend(analysis.case_map(split,rows))
    return {'revision':REVISION,'planSha256':digest(PLAN),'policy':policy.POLICY,'models':plan['models'],
        'populations':populations,'journals':journals,'trainingPerformed':False,'selectionPerformed':False,
        'testAlreadyExposed':True},cases


def report():
    summary,cases=results(); freeze(AREA/'summary.json',summary); freeze(AREA/'case-map.jsonl',cases,True)
    with (AREA/'report.md').open('x',encoding='utf-8',newline='\n') as stream: stream.write(analysis.report_text(summary))
    print(json.dumps({split:{label:{m:r['qualified'] for m,r in data['strategies'].items()}
        for label,data in result['models'].items()} for split,result in summary['populations'].items()}),flush=True)


def verify_report():
    summary,cases=results()
    if (read(AREA/'summary.json')!=summary or read_rows(AREA/'case-map.jsonl')!=cases
            or (AREA/'report.md').read_text(encoding='utf-8')!=analysis.report_text(summary)):
        raise ValueError('Published results differ from verified journals')
    return summary


def seal():
    verify_report(); plan=read(PLAN)
    paths={p for p in AREA.rglob('*') if p.is_file()}|{p for p in CHECKS.rglob('*') if p.is_file()}
    paths.update(ROOT/p for p in plan['sources']); paths.add(PLAN)
    paths.update(ROOT/'tools/neural'/name for name in ('generation-comparison-findings.md','README.md',
        'gen2-cache-manifest.json','gen3-cache-manifest.json','checkpoint-selection-cache-manifest.json'))
    destination=ROOT/'.neural-cache/generation-comparison-v1'; destination.mkdir(parents=True,exist_ok=True)
    archive=destination/'study.zip'; entries=[]
    with zipfile.ZipFile(archive,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for p in sorted(paths):
            name=p.relative_to(ROOT).as_posix(); z.write(p,name)
            entries.append({'entry':name,'sha256':digest(p),'bytes':p.stat().st_size})
    with zipfile.ZipFile(archive) as z:
        if z.testzip() is not None: raise ValueError('Archive CRC failure')
        for r in entries:
            value=z.read(r['entry'])
            if len(value)!=r['bytes'] or hashlib.sha256(value).hexdigest()!=r['sha256']: raise ValueError('Archive entry mismatch')
    manifest={'revision':REVISION,**info(archive),'archivePath':archive.relative_to(ROOT).as_posix(),
        'archiveSha256':digest(archive),'archiveBytes':archive.stat().st_size,'entries':entries,
        'allEntriesVerified':True,'dependencies':plan['dependencies'],'referenceArchiveSha256':plan['referenceArchiveSha256']}
    freeze(destination/'manifest.json',manifest); freeze(ROOT/'tools/neural/generation-comparison-cache-manifest.json',manifest)
    for source,target in (('summary.json','generation-comparison-summary.json'),('report.md','generation-comparison-results.md'),
                          ('case-map.jsonl','generation-comparison-case-map.jsonl')):
        copy_bytes(AREA/source,ROOT/'tools/neural'/target)
    print(json.dumps({'archiveBytes':manifest['archiveBytes'],'verifiedEntries':len(entries)}),flush=True)


if __name__=='__main__':
    actions={'assets':assets,'preflight':preflight,'prepare':prepare,'verify':verify_plan,'run':run,
             'report':report,'verify-report':verify_report,'seal':seal}
    parser=argparse.ArgumentParser(description=__doc__); parser.add_argument('command',choices=actions)
    actions[parser.parse_args().command]()
