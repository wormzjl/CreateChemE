"""Guarded native screen, deterministic checkpoint selection and full validation."""
from common import *
from register_followup import verify_plan
from native import execute
import argparse
import checkpoint_selection as frozen_policy


def validate_run(directory,source,model,warmup):
    rows=read_rows(directory/'evaluation.jsonl');meta=read(directory/'run.json')
    assert meta['revision']=='trace-followup-column-evaluation-v1'
    inputs=read_rows(ROOT/source['path'])
    frozen_policy.validate_run(rows,{**meta,'revision':frozen_policy.POLICY['revision']},inputs,model['sha256'],source['sha256'])
    assert meta['maximumHeapBytes']==4*1024**3 and meta['warmupSha256']==warmup['sha256']
    assert meta['scheduling']['distinctWorkerThreads']==10 and meta['scheduling']['terminated']
    for row in rows:
        prediction=row['rawPrediction'];seed=prediction.get('seedPresentedByPipeline')
        assert bool(seed)==bool(prediction['supported'])
        if seed:assert canonical_input_hash(seed['input'])==canonical_input_hash(row['input'])
    return rows,meta


def fit_and_export_bindings():
    plan=verify_plan();models=read(OUT/'models.json')
    assert set(models)==set(plan['screenOrder'])
    for name,item in models.items():
        for record in (item,item['weights']):assert digest(ROOT/record['path'])==record['sha256']
        if name in plan['controls']:assert item==plan['controls'][name];continue
        checkpoint=ROOT/item['checkpoint']['path'];assert digest(checkpoint)==item['checkpoint']['sha256']
        doc=read(ROOT/item['weights']['path']);assert doc['checkpointSha256']==digest(checkpoint)
        assert doc['trainingPlanSha256']==digest(OUT/'training-plan.json')
        check=read(ROOT/item['precisionCheck']['path']);assert check['passed'] and check['maskDifferences']==0
        assert digest(ROOT/item['precisionCheck']['path'])==item['precisionCheck']['sha256']
        for record in (check['model'],check['fixtures'],check['javaParity']):assert digest(ROOT/record['path'])==record['sha256']
    return plan,models


def invoke(name,model,source,stage,block):
    plan=verify_plan();directory=OUT/stage/f'block-{block}'/name
    assert stage in ('screen','validation') and source==plan['screeningPanel' if stage=='screen' else 'validationInputs']
    if directory.exists():
        validate_run(directory,source,model,plan['warmup']);print('VERIFIED complete '+str(directory.relative_to(ROOT)),flush=True);return
    args=[directory,ROOT/source['path'],ROOT/model['path'],'10','30','2000','16',ROOT/plan['warmup']['path']]
    print(f'START {stage} block {block} {name}',flush=True)
    execute('V3TraceEvaluationProbe',args,OUT/'logs'/f'{stage}-block-{block}-{name}.log')
    rows,meta=validate_run(directory,source,model,plan['warmup'])
    print(json.dumps({'completeStage':stage,'block':block,'pipeline':name,'cases':len(rows),
        'seconds':meta['elapsedSeconds'],'strict':{mode:sum(strict({**r,**r[mode]}) for r in rows) for mode in plan['strategies']}}),flush=True)


def screen():
    plan,models=fit_and_export_bindings()
    lock=OUT/'screen-execution-lock.json'
    expected=dict(plan=info(OUT/'training-plan.json'),models=info(OUT/'models.json'),panel=plan['screeningPanel'])
    if lock.exists():assert read(lock)==expected
    else:freeze(lock,expected)
    for name in plan['screenOrder']:invoke(name,models[name],plan['screeningPanel'],'screen',1)


def screen_rank(record,classical_union,step):
    return (not classical_union<=set(record['strictFirstIds']),-len(record['strictFirstIds']),record['firstMeanMillis'],step)


def build_selection():
    plan,models=fit_and_export_bindings();records={};classical=set();evidence=[]
    for name in plan['screenOrder']:
        directory=OUT/'screen/block-1'/name;rows,meta=validate_run(directory,plan['screeningPanel'],models[name],plan['warmup'])
        classical.update(r['id'] for r in rows if strict({**r,**r['current']}))
        records[name]=dict(strictFirstIds=sorted(r['id'] for r in rows if strict({**r,**r['neuralFirst']})),
            strictOnlyIds=sorted(r['id'] for r in rows if strict({**r,**r['neural']})),
            firstMeanMillis=sum(r['neuralFirst']['ms'] for r in rows)/len(rows),
            elapsedSeconds=meta['elapsedSeconds'])
        evidence.extend(info(directory/file) for file in ('evaluation.jsonl','run.json'))
    selections={}
    for arm in plan['arms']:
        selections[arm]={}
        for seed in plan['training']['seeds']:
            names=[f'{arm}-{seed}-s{step}' for step in plan['training']['checkpoints']]
            chosen=min(names,key=lambda name:screen_rank(records[name],classical,int(name.rsplit('-s',1)[1])))
            selections[arm][str(seed)]=dict(pipeline=chosen,step=int(chosen.rsplit('-s',1)[1]),
                preservesClassicalUnion=classical<=set(records[chosen]['strictFirstIds']))
    seed=plan['primarySeed'];profiles={}
    for step in plan['training']['checkpoints']:
        cp=read(OUT/'fits'/f'T-{seed}'/f'checkpoint-{step}.json')
        assert cp['planSha256']==digest(OUT/'training-plan.json')
        profiles[f'T-{seed}-s{step}']=cp['checkpoint']['profileSelectionScore']
    profile_selected=min(profiles,key=lambda name:(profiles[name],int(name.rsplit('-s',1)[1])))
    mandatory={f'{arm}-{seed}-s{step}' for arm in ('C','D') for step in (4160,4640)}
    mandatory.update(f'{arm}-{seed}-s4160' for arm in ('T','F','K'))
    candidates=mandatory|{selections[arm][str(seed)]['pipeline'] for arm in plan['arms']}|{profile_selected,'I','H805'}
    # Full identity includes the complete bound pipeline manifest and weights.
    ordered=[];identities=set();aliases={}
    for name in plan['screenOrder']:
        if name not in candidates:continue
        identity=(models[name]['sha256'],models[name]['weights']['sha256'])
        if identity not in identities:ordered.append(name);identities.add(identity)
        else:aliases[name]=next(prior for prior in ordered if (models[prior]['sha256'],models[prior]['weights']['sha256'])==identity)
    assert len(ordered)<=15 and mandatory<=(set(ordered)|set(aliases))
    return dict(revision='trace-followup-checkpoint-selection-v1',trainingPlan=info(OUT/'training-plan.json'),models=info(OUT/'models.json'),
        panel=plan['screeningPanel'],screeningComplete=True,testUsed=False,primarySeed=seed,records=records,
        classicalPanelUnionIds=sorted(classical),nativeSelections=selections,primaryTraceProfileScores=profiles,
        primaryTraceProfileSelected=profile_selected,mandatoryEndpointPipelines=sorted(mandatory),fullValidationPipelines=ordered,
        aliases=aliases,screeningEvidence=evidence,measuredScreenRequests=len(plan['screenOrder'])*65*3,
        screenWarmupRequests=len(plan['screenOrder'])*6)


def select():
    selection=build_selection();freeze(OUT/'selection.json',selection);plan,models=fit_and_export_bindings()
    names=selection['fullValidationPipelines']
    validation=dict(revision='trace-followup-full-validation-v1',selection=info(OUT/'selection.json'),trainingPlan=info(OUT/'training-plan.json'),
        source=plan['validationInputs'],models={name:models[name] for name in names},orderByBlock=[names,list(reversed(names))],
        blocks=2,cases=405,strategies=plan['strategies'],measuredRequests=2*len(names)*405*3,warmupRequests=2*len(names)*6,
        primarySeed=plan['primarySeed'],testUsed=False,automaticPromotion=False)
    freeze(OUT/'validation-plan.json',validation)
    print(json.dumps({'selectedNative':selection['nativeSelections'],'primaryTraceProfileSelected':selection['primaryTraceProfileSelected'],
        'fullValidationPipelines':names,'measuredRequests':validation['measuredRequests']}),flush=True)


def verify_selection():
    expected=build_selection();stored=read(OUT/'selection.json');assert expected==stored
    validation=read(OUT/'validation-plan.json');plan,models=fit_and_export_bindings()
    assert validation['selection']==info(OUT/'selection.json') and validation['trainingPlan']==info(OUT/'training-plan.json')
    assert validation['source']==plan['validationInputs']
    assert validation['orderByBlock']==[stored['fullValidationPipelines'],list(reversed(stored['fullValidationPipelines']))]
    assert validation['models']=={name:models[name] for name in stored['fullValidationPipelines']}
    assert validation['measuredRequests']==2*len(validation['models'])*405*3
    return validation


def validation():
    locked=verify_selection();lock=OUT/'validation-execution-lock.json'
    expected=dict(validationPlan=info(OUT/'validation-plan.json'),selection=info(OUT/'selection.json'),trainingPlan=info(OUT/'training-plan.json'))
    if lock.exists():assert read(lock)==expected
    else:freeze(lock,expected)
    for block,names in enumerate(locked['orderByBlock'],1):
        for name in names:invoke(name,locked['models'][name],locked['source'],'validation',block)


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('mode',choices=['screen','select','validation']);args=p.parse_args();globals()[args.mode]()
