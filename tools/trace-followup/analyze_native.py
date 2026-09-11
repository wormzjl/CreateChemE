"""Reconstruct all registered native outcomes and causal contrasts from journals."""
from analysis_common import *
from benchmark_followup import fit_and_export_bindings,validate_run,verify_selection
import argparse


def collect(stage):
    plan,models=fit_and_export_bindings();references={r['id']:r for r in read_rows(OUT/'validation-references.jsonl')}
    panel_ids={r['id'] for r in read_rows(ROOT/plan['screeningPanel']['path'])}
    if stage=='screen':source=plan['screeningPanel'];names=plan['screenOrder'];blocks=[1]
    else:
        validation=verify_selection();source=validation['source'];names=validation['orderByBlock'][0];blocks=[1,2]
    cases={};profiles={};summaries={};evidence=[];case_records=[];profile_records=[];classical=set();metadata={}
    for block in blocks:
        for name in names:
            directory=OUT/stage/f'block-{block}'/name
            rows,meta=validate_run(directory,source,models[name],plan['warmup']);rows.sort(key=lambda r:r['id'])
            observed={};pp={}
            for row in rows:
                modes={mode:mode_evidence(row,mode) for mode in plan['strategies']}
                if modes['current']['outcome']=='strict':classical.add(row['id'])
                native=row['rawPrediction'].get('nativeResidual') or {}
                record=dict(stage=stage,block=block,pipeline=name,id=row['id'],canonicalInputSha256=canonical_input_hash(row['input']),
                    condition=condition(row),screeningMember=row['id'] in panel_ids,modes=modes,
                    pipelineSeedAvailable=row['rawPrediction']['supported'],rawDiagnosticMillis=row['rawPrediction'].get('ms'),
                    initialProjectedMaximumScaledMeshResidual=native.get('maximumScaledMeshResidual'),
                    caseServiceMillis=row['caseServiceMillis'],queueWaitMillis=row['queueWaitMillis'])
                observed[row['id']]=record;case_records.append(record)
                if row['id'] in references:
                    profile=profile_evidence(row,references[row['id']]);profile.update(stage=stage,block=block,pipeline=name)
                    pp[row['id']]=profile;profile_records.append(profile)
            cases[block,name]=observed;profiles[block,name]=pp
            populations={'all':set(observed),'screening':set(observed)&panel_ids,'remaining':set(observed)-panel_ids}
            populations={key:ids for key,ids in populations.items() if ids}
            summaries[f'{block}/{name}']={population:dict(cases=len(ids),
                modes={mode:summarize_evidence([observed[i]['modes'][mode] for i in sorted(ids)]) for mode in plan['strategies']},
                pipelineSeedUnavailable=sum(not observed[i]['pipelineSeedAvailable'] for i in ids),
                profiles=summarize_profiles([pp[i] for i in sorted(ids) if i in pp]),
                initialProjectedMaximumScaledMeshResidual=optional([observed[i]['initialProjectedMaximumScaledMeshResidual'] for i in sorted(ids)]),
                rawDiagnosticMillis=optional([observed[i]['rawDiagnosticMillis'] for i in sorted(ids)]),
                caseServiceMillis=optional([observed[i]['caseServiceMillis'] for i in sorted(ids)]),
                queueWaitMillis=optional([observed[i]['queueWaitMillis'] for i in sorted(ids)])) for population,ids in populations.items()}
            regime={}
            for key in ('stageBand','steam','sideDrawCount','heatLoopCount'):
                regime[key]={}
                for value in sorted({str(r['condition'][key]) for r in observed.values()}):
                    selected=[r for r in observed.values() if str(r['condition'][key])==value]
                    regime[key][value]=dict(cases=len(selected),strict={mode:sum(r['modes'][mode]['outcome']=='strict' for r in selected) for mode in plan['strategies']})
            metadata[f'{block}/{name}']=dict(run=info(directory/'run.json'),elapsedSeconds=meta['elapsedSeconds'],
                modelLoadMillis=meta['modelLoadMillis'],heapUsedAtEndBytes=meta['heapUsedAtEndBytes'],
                poolPeakUsage=meta['poolPeakUsage'],scheduling=meta['scheduling'],regimes=regime)
            evidence.extend(info(directory/file) for file in ('evaluation.jsonl','run.json'))
            print(f'Reconstructed {stage} block {block} {name}',flush=True)
    return plan,names,blocks,cases,profiles,summaries,metadata,evidence,case_records,profile_records,classical


def contrast(reference,candidate,blocks,cases,profiles,panel_ids):
    results={};stable={mode:{} for mode in ('neural','neuralFirst')}
    for block in blocks:
        a=cases[block,reference];b=cases[block,candidate];assert a.keys()==b.keys()
        assert all(a[i]['canonicalInputSha256']==b[i]['canonicalInputSha256'] for i in a)
        populations={'all':set(a),'screening':set(a)&panel_ids,'remaining':set(a)-panel_ids}
        results[str(block)]={}
        for population,ids in populations.items():
            if not ids:continue
            result={}
            for mode in ('neural','neuralFirst'):
                groups={key:[] for key in ('both','reference_only','candidate_only','neither')}
                for i in sorted(ids):
                    x=a[i]['modes'][mode]['outcome']=='strict';y=b[i]['modes'][mode]['outcome']=='strict'
                    group='both' if x and y else 'reference_only' if x else 'candidate_only' if y else 'neither'
                    groups[group].append(i)
                    if population=='all':stable[mode].setdefault(i,[]).append(group)
                result[mode]=dict(gains=len(groups['candidate_only']),losses=len(groups['reference_only']),
                    groups={group:dict(ids=kept,cases=len(kept),reference=summarize_evidence([a[i]['modes'][mode] for i in kept]),
                        candidate=summarize_evidence([b[i]['modes'][mode] for i in kept]),
                        candidateMinusReferenceMillis=optional([b[i]['modes'][mode]['milliseconds']-a[i]['modes'][mode]['milliseconds'] for i in kept]),
                        profiles=paired_profiles(profiles[block,reference],profiles[block,candidate],kept)) for group,kept in groups.items()},
                    pairedMillis=optional([b[i]['modes'][mode]['milliseconds']-a[i]['modes'][mode]['milliseconds'] for i in sorted(ids)]))
            result['profiles']=paired_profiles(profiles[block,reference],profiles[block,candidate],sorted(ids))
            results[str(block)][population]=result
    consistency={mode:dict(repeatedBlocksAvailable=len(blocks)>1,
        classificationChangedIds=sorted(i for i,values in observed.items() if len(set(values))>1) if len(blocks)>1 else None,
        referenceOutcomeChangedIds=sorted(i for i in observed if len({cases[block,reference][i]['modes'][mode]['outcome'] for block in blocks})>1) if len(blocks)>1 else None,
        candidateOutcomeChangedIds=sorted(i for i in observed if len({cases[block,candidate][i]['modes'][mode]['outcome'] for block in blocks})>1) if len(blocks)>1 else None,
        stableGroups={group:sorted(i for i,values in observed.items() if len(values)==len(blocks) and set(values)=={group}) for group in ('both','reference_only','candidate_only','neither')} if len(blocks)>1 else None) for mode,observed in stable.items()}
    return dict(reference=reference,candidate=candidate,blocks=results,consistency=consistency)


def build(stage):
    plan,names,blocks,cases,profiles,summaries,metadata,evidence,case_records,profile_records,classical=collect(stage)
    panel_ids={r['id'] for r in read_rows(ROOT/plan['screeningPanel']['path'])};contrasts={}
    if stage=='screen':
        for seed in plan['training']['seeds']:
            for step in plan['training']['checkpoints']:
                c=f'C-{seed}-s{step}';k=f'K-{seed}-s{step}'
                for label,ref,candidate in [('trace',c,f'T-{seed}-s{step}'),('compact_vs_plain',c,k),('compact_vs_full',f'F-{seed}-s{step}',k),('data_equal_updates',c,f'D-{seed}-s{step}')]:
                    contrasts[f'{label}/{seed}/{step}']=contrast(ref,candidate,blocks,cases,profiles,panel_ids)
    else:
        seed=plan['primarySeed'];selection=read(OUT/'selection.json')
        for label,ref,candidate in [('trace_fixed',f'C-{seed}-s4160',f'T-{seed}-s4160'),
            ('compact_vs_plain_fixed',f'C-{seed}-s4160',f'K-{seed}-s4160'),('compact_vs_full_fixed',f'F-{seed}-s4160',f'K-{seed}-s4160'),
            ('data_equal_updates',f'C-{seed}-s4160',f'D-{seed}-s4160'),('data_matched_old_exposure',f'C-{seed}-s4160',f'D-{seed}-s4640'),
            ('data_equal_extended_updates',f'C-{seed}-s4640',f'D-{seed}-s4640'),('extra_optimization',f'C-{seed}-s4160',f'C-{seed}-s4640'),
            ('trace_native_vs_profile_selection',selection['primaryTraceProfileSelected'],selection['nativeSelections']['T'][str(seed)]['pipeline'])]:
            contrasts[label]=contrast(ref,candidate,blocks,cases,profiles,panel_ids)
        for arm in plan['armOrder']:
            chosen=selection['nativeSelections'][arm][str(seed)]['pipeline']
            contrasts[f'native_selected_{arm}_vs_I']=contrast('I',chosen,blocks,cases,profiles,panel_ids)
    versus_incumbent={name:contrast('I',name,blocks,cases,profiles,panel_ids) for name in names if name!='I'}
    qualification={}
    if stage=='validation':
        for name in names:
            selected=[{i for i,r in cases[block,name].items() if r['modes']['neuralFirst']['outcome']=='strict'} for block in blocks]
            reference=[{i for i,r in cases[block,'I'].items() if r['modes']['neuralFirst']['outcome']=='strict'} for block in blocks]
            candidate_mean=float(np.mean([r['modes']['neuralFirst']['milliseconds'] for block in blocks for r in cases[block,name].values()]))
            reference_mean=float(np.mean([r['modes']['neuralFirst']['milliseconds'] for block in blocks for r in cases[block,'I'].values()]))
            improves=all(len(a)>len(b) for a,b in zip(selected,reference));preserves=all(classical<=ids for ids in selected)
            timing=candidate_mean<=reference_mean
            qualification[name]=dict(improvesStrictFirstBothBlocks=improves,preservesClassicalUnionBothBlocks=preserves,
                noPooledMeanLatencyRegression=timing,eligibleForFurtherQualification=improves and preserves and timing,
                pooledFirstMeanMillis=candidate_mean,incumbentPooledFirstMeanMillis=reference_mean,
                strictFirstCounts=[len(ids) for ids in selected],classicalMissedIds={str(block):sorted(classical-selected[j]) for j,block in enumerate(blocks)})
    profile_stability={}
    if len(blocks)==2:
        for name in names:
            a=profiles[blocks[0],name];b=profiles[blocks[1],name];assert a.keys()==b.keys()
            profile_stability[name]=dict(referenceCases=len(a),changedAvailabilityIds=sorted(i for i in a if a[i]['available']!=b[i]['available']),
                changedSeedIds=sorted(i for i in a if a[i].get('seedSha256')!=b[i].get('seedSha256')))
    result=dict(revision='trace-followup-native-analysis-v1',stage=stage,trainingPlan=info(OUT/'training-plan.json'),models=info(OUT/'models.json'),
        source=plan['screeningPanel'] if stage=='screen' else plan['validationInputs'],pipelines=names,blocks=blocks,
        independentCases=65 if stage=='screen' else 405,screeningCases=65,remainingCases=0 if stage=='screen' else 340,
        measuredRequests=len(names)*len(blocks)*(65 if stage=='screen' else 405)*3,warmupRequests=len(names)*len(blocks)*6,
        classicalStrictUnionIds=sorted(classical),summaries=summaries,runMetadata=metadata,contrasts=contrasts,
        versusIncumbent=versus_incumbent,qualification=qualification,profileStability=profile_stability,inputEvidence=evidence,
        interpretation='Historical validation. The 65-case outcome-stratified panel is not a population sample; the remaining validation cases are not a fresh holdout.',
        evidenceRules=['FIRST terminal owner is explicit; separately timed ONLY is not its internal neural trajectory.',
            'Stop phrases describe observed request events; absent evidence remains unknown.',
            'Projected scaled MESH maxima and audit ratios are state/support dependent diagnostics, not a common optimization objective.',
            'Seed profiles are the actual native seed presented by the pipeline. H805 includes its old wrapper; new arms have no wrapper.',
            'Reference-profile omissions are evidence of discrepancy, not proof that the reference support is mandatory at every admissible root.'],
        analysisSource=info(Path(__file__)),analysisDefinitions=info(ROOT/'tools/trace-followup/analysis_common.py'),newSolverRequestsByAnalysis=0)
    return result,case_records,profile_records


def main(stage):
    result,cases,profiles=build(stage)
    freeze(OUT/f'{stage}-analysis.json',result);freeze(OUT/f'{stage}-case-evidence.jsonl',cases,True);freeze(OUT/f'{stage}-profile-evidence.jsonl',profiles,True)
    print(json.dumps({'analyzed':stage,'pipelines':len(result['pipelines']),'cases':result['independentCases'],
        'caseEvidenceRows':len(cases),'profileEvidenceRows':len(profiles),'measuredRequests':result['measuredRequests']}),flush=True)


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('stage',choices=['screen','validation']);args=p.parse_args();main(args.stage)
