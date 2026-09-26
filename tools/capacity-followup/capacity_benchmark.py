"""Registered native screen and locked both-seed, both-block validation."""
from capacity_common import *
from capacity_register import verify_plan
from capacity_native import execute
import checkpoint_selection as policy
import argparse


def bindings():
    plan=verify_plan();models=read(OUT/'models.json')
    assert set(models)==set(plan['screenOrder'])
    for name,item in models.items():
        for entry in (item,item['weights']):assert digest(ROOT/entry['path'])==entry['sha256']
        if name in plan['controls']:
            assert item==plan['controls'][name];continue
        for entry in (item['checkpoint'],item['precisionCheck']):assert digest(ROOT/entry['path'])==entry['sha256']
        doc=read(ROOT/item['weights']['path']);check=read(ROOT/item['precisionCheck']['path'])
        assert doc['checkpointSha256']==item['checkpoint']['sha256']
        assert doc['trainingPlanSha256']==digest(OUT/'training-plan.json')
        assert doc['layerCount']==plan['arms'][item['arm']]['layers']==item['layers']
        assert check['passed'] and check['exactMasks']
        for entry in (check['model'],check['fixturesFile'],check['javaParity']):assert digest(ROOT/entry['path'])==entry['sha256']
    return plan,models


def validate_run(directory,source,model,warmup):
    rows=read_rows(directory/'evaluation.jsonl');meta=read(directory/'run.json')
    assert meta['revision']=='capacity-column-evaluation-v1'
    policy.validate_run(rows,{**meta,'revision':policy.POLICY['revision']},read_rows(ROOT/source['path']),model['sha256'],source['sha256'])
    assert meta['maximumHeapBytes']==4*1024**3 and meta['warmupSha256']==warmup['sha256']
    assert meta['scheduling']['distinctWorkerThreads']==10 and meta['scheduling']['terminated']
    for row in rows:
        prediction=row['rawPrediction'];seed=prediction.get('seedPresentedByPipeline')
        assert bool(seed)==bool(prediction['supported'])
        if seed:assert canonical_input_hash(seed['input'])==canonical_input_hash(row['input'])
    return rows,meta


def invoke(name,item,source,stage,block):
    plan=verify_plan();directory=OUT/stage/f'block-{block}'/name
    assert source==plan['screeningPanel' if stage=='screen' else 'validationInputs']
    if directory.exists():
        validate_run(directory,source,item,plan['warmup']);print('Verified completed run '+str(directory),flush=True);return
    print(f'START {stage} block {block} {name}',flush=True)
    execute('V3CapacityEvaluationProbe',[directory,ROOT/source['path'],ROOT/item['path'],'10','30','2000','16',ROOT/plan['warmup']['path']],
            OUT/'logs'/f'{stage}-block-{block}-{name}.log')
    rows,meta=validate_run(directory,source,item,plan['warmup'])
    print(json.dumps({'completeStage':stage,'block':block,'pipeline':name,'cases':len(rows),'seconds':meta['elapsedSeconds'],
                      'strict':{mode:sum(strict({**r,**r[mode]}) for r in rows) for mode in plan['strategies']}}),flush=True)


def screen():
    plan,models=bindings();lock=OUT/'screen-execution-lock.json'
    expected=dict(plan=info(OUT/'training-plan.json'),models=info(OUT/'models.json'),panel=plan['screeningPanel'])
    if lock.exists():assert read(lock)==expected
    else:freeze(lock,expected)
    for name in plan['screenOrder']:invoke(name,models[name],plan['screeningPanel'],'screen',1)


def rank(record,classical,step):
    return (not classical<=set(record['strictFirstIds']),-len(record['strictFirstIds']),record['firstMeanMillis'],step)


def build_selection():
    plan,models=bindings();records={};classical=set();evidence=[]
    for name in plan['screenOrder']:
        directory=OUT/'screen/block-1'/name
        rows,meta=validate_run(directory,plan['screeningPanel'],models[name],plan['warmup'])
        classical.update(r['id'] for r in rows if strict({**r,**r['current']}))
        records[name]=dict(strictFirstIds=sorted(r['id'] for r in rows if strict({**r,**r['neuralFirst']})),
            strictOnlyIds=sorted(r['id'] for r in rows if strict({**r,**r['neural']})),
            firstMeanMillis=sum(r['neuralFirst']['ms'] for r in rows)/len(rows),elapsedSeconds=meta['elapsedSeconds'])
        evidence.extend(info(directory/file) for file in ('evaluation.jsonl','run.json'))
    selections={}
    for arm in plan['arms']:
        selections[arm]={}
        for seed in plan['training']['seeds']:
            candidates=[f'{arm}-{seed}-s{step}' for step in plan['training']['checkpoints']]
            choice=min(candidates,key=lambda name:rank(records[name],classical,int(name.rsplit('-s',1)[1])))
            selections[arm][str(seed)]=dict(pipeline=choice,step=int(choice.rsplit('-s',1)[1]),
                                          preservesClassicalUnion=classical<=set(records[choice]['strictFirstIds']))
    mandatory={f'{arm}-{seed}-s{plan["fixedComparisonStep"]}' for arm in plan['arms'] for seed in plan['training']['seeds']}
    keep=mandatory|{value['pipeline'] for arm in selections.values() for value in arm.values()}|set(plan['controls'])
    names=[];identities={};aliases={}
    for name in plan['screenOrder']:
        if name not in keep:continue
        identity=(models[name]['sha256'],models[name]['weights']['sha256'])
        if identity in identities:aliases[name]=identities[identity]
        else:names.append(name);identities[identity]=name
    assert len(names)<=10 and mandatory<=set(names)|set(aliases)
    return dict(revision='capacity-selection-v1',trainingPlan=info(OUT/'training-plan.json'),models=info(OUT/'models.json'),
        screeningComplete=True,records=records,classicalPanelUnionIds=sorted(classical),nativeSelections=selections,
        mandatoryEndpointPipelines=sorted(mandatory),fullValidationPipelines=names,aliases=aliases,screeningEvidence=evidence,
        measuredScreenRequests=14*65*3,screenWarmupRequests=14*6,testUsed=False)


def validation_document(selection,plan,models):
    names=selection['fullValidationPipelines']
    return dict(revision='capacity-validation-plan-v1',selection=info(OUT/'selection.json'),trainingPlan=info(OUT/'training-plan.json'),
        source=plan['validationInputs'],models={name:models[name] for name in names},orderByBlock=[names,list(reversed(names))],
        blocks=2,cases=405,strategies=plan['strategies'],measuredRequests=2*len(names)*405*3,warmupRequests=2*len(names)*6,
        trainingSeeds=plan['training']['seeds'],testUsed=False,automaticPromotion=False)


def select():
    selection=build_selection();freeze(OUT/'selection.json',selection);plan,models=bindings()
    validation=validation_document(selection,plan,models);freeze(OUT/'validation-plan.json',validation)
    print(json.dumps({'selected':selection['nativeSelections'],'fullPipelines':selection['fullValidationPipelines'],
                      'measuredValidationRequests':validation['measuredRequests']}),flush=True)


def verify_selection():
    expected=build_selection();assert expected==read(OUT/'selection.json')
    plan,models=bindings();validation=validation_document(expected,plan,models)
    assert validation==read(OUT/'validation-plan.json')
    return validation


def validation():
    plan=verify_selection();lock=OUT/'validation-execution-lock.json'
    expected=dict(selection=info(OUT/'selection.json'),validationPlan=info(OUT/'validation-plan.json'),trainingPlan=info(OUT/'training-plan.json'))
    if lock.exists():assert read(lock)==expected
    else:freeze(lock,expected)
    for block,names in enumerate(plan['orderByBlock'],1):
        for name in names:invoke(name,plan['models'][name],plan['source'],'validation',block)


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('mode',choices=['screen','select','validation']);args=p.parse_args();globals()[args.mode]()
