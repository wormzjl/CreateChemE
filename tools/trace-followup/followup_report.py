"""Render the four registered questions from verified numerical summaries."""
from common import *


def label(name):
    if name in ('I','H805'):return name
    arm,seed,step=name.split('-');return f'{arm}@{int(step[1:]):,}'


def count(summary,block,name,mode):
    return summary['summaries'][f'{block}/{name}']['all']['modes'][mode]['outcomes'].get('strict',0)


def pair(value):return f"{value['gains']}/{value['losses']}"


def metric_mean(value):return 'unavailable' if value is None else f"{value['mean']:.6g}"


def build():
    plan=read(OUT/'training-plan.json');selection=read(OUT/'selection.json')
    screen=read(OUT/'screen-analysis.json');validation=read(OUT/'validation-analysis.json')
    eligible=[name for name,value in validation['qualification'].items() if name not in ('I','H805') and value['eligibleForFurtherQualification']]
    intro=(f"**{len(eligible)} candidate pipeline(s) meet the registered validation rule for further qualification:** "+', '.join(label(name) for name in eligible)+'.'
        if eligible else '**No new candidate meets all three registered validation gates for further qualification. Retain the incumbent.**')
    lines=['# Four controlled follow-ups: results','',intro,'',
        'All four requested experiments are complete. Ten continuation fits produced thirty predefined checkpoints. Each checkpoint passed native export parity before screening. The final pipelines were frozen before two full-validation blocks; no production model, decoder threshold, thermodynamic property, support rule or strict audit changed.','',
        'C is the curated continuation control; T adds the trace-margin loss; F adds all native anchor inputs; K keeps only temperature and two phase-traffic anchors; D adds the 101 curated profiles. I is the retained Transformer. H805 is the historical hybrid with its original wrapper and is contextual, not a matched causal control.','',
        '## Full native validation','',
        'Counts cover all 405 validation inputs. Each cell gives block 1 / block 2. ONLY is neural correction alone; FIRST includes classical fallback. Latency is pooled all-case FIRST service time under the fixed ten-worker load.','',
        '| Pipeline | Strict ONLY | Strict FIRST | FIRST mean ms | Further-qualification gates |',
        '|---|---:|---:|---:|---|']
    for name in validation['pipelines']:
        q=validation['qualification'][name]
        gates='pass' if q['eligibleForFurtherQualification'] else ', '.join(reason for key,reason in (
            ('improvesStrictFirstBothBlocks','strict gain'),('preservesClassicalUnionBothBlocks','classical preservation'),('noPooledMeanLatencyRegression','latency')) if not q[key])
        if name=='I':gates='reference'
        lines.append(f"| {label(name)} | {count(validation,1,name,'neural')} / {count(validation,2,name,'neural')} | {count(validation,1,name,'neuralFirst')} / {count(validation,2,name,'neuralFirst')} | {q['pooledFirstMeanMillis']:.1f} | {gates} |")
    lines+=['',
        'Passing requires a strict FIRST gain over I in both blocks, preservation of the contemporaneous classical-success union in both, and no pooled mean-latency regression. A passing result supports further qualification; it is not automatic deployment or new blind-test evidence.','',
        '## Fixed causal and exposure comparisons','',
        'Gains/losses are paired strict cases relative to the specified reference. These fixed endpoints remain in the results even when checkpoint screening selects another update.','',
        '| Question | Candidate versus reference | ONLY gains/losses, blocks 1 / 2 | FIRST gains/losses, blocks 1 / 2 |',
        '|---|---|---:|---:|']
    comparisons=[('Trace margin','trace_fixed'),('Useful compact anchors','compact_vs_plain_fixed'),('Anchor simplification','compact_vs_full_fixed'),
        ('Added data: equal updates','data_equal_updates'),('Added data: matched old exposure','data_matched_old_exposure'),
        ('Added data: equal extended updates','data_equal_extended_updates'),('Extra optimization only','extra_optimization'),
        ('Native versus profile checkpoint choice','trace_native_vs_profile_selection')]
    for title,key in comparisons:
        c=validation['contrasts'][key]
        only=' / '.join(pair(c['blocks'][str(block)]['all']['neural']) for block in (1,2))
        first=' / '.join(pair(c['blocks'][str(block)]['all']['neuralFirst']) for block in (1,2))
        lines.append(f"| {title} | {label(c['candidate'])} versus {label(c['reference'])} | {only} | {first} |")
    trace=validation['contrasts']['trace_fixed'];profiles=[trace['blocks'][str(block)]['all']['profiles'] for block in (1,2)]
    lines+=['','## 1. Trace-flow treatment','',
        f"The fixed coefficient is **{plan['traceCoefficient']:.8f}**, calibrated once from three predefined TRAIN batches without an optimizer step or validation data. The term penalizes log-flow shortfall below the lesser of the reference flow and ten times the unchanged floor. Presence BCE remains unchanged. The surrogate is computed over all active feed components before presence and trace pruning; it is not an exact differentiable copy of the hard decoder.",'',
        'At update 4,160, actual native pipeline seeds give the following comparison on original certified references available for both C and T:','',
        '| Block | Common reference cases | Above-floor omissions C → T | Mean temperature MAE change, K | Mean liquid component-L1 change / feed | Mean vapor component-L1 change / feed |',
        '|---|---:|---:|---:|---:|---:|']
    for block,p in enumerate(profiles,1):
        delta=p['candidateMinusReference']
        lines.append(f"| {block} | {p['bothAvailable']} | {p['referenceOmissions']} → {p['candidateOmissions']} | {metric_mean(delta['temperatureMaeKelvin'])} | {metric_mean(delta['liquidComponentL1OverFeed'])} | {metric_mean(delta['vaporComponentL1OverFeed'])} |")
    lines+=['','Signed mean traffic-error changes T−C (liquid, vapor)/feed, blocks 1 / 2: '+
        ' / '.join('('+metric_mean(p['candidateMinusReference']['liquidMeanSignedTrafficErrorOverFeed'])+', '+
            metric_mean(p['candidateMinusReference']['vaporMeanSignedTrafficErrorOverFeed'])+')' for p in profiles)+'.',
        'Mean additional above-floor entry-count changes per column, blocks 1 / 2: '+
        ' / '.join(metric_mean(p['candidateMinusReference']['additionalAboveFloorEntries']) for p in profiles)+'.','',
        '| Block | Terminal ONLY energy-audit failures / available, C | T | Terminal ONLY equilibrium-audit failures / available, C | T |',
        '|---|---:|---:|---:|---:|']
    for block in (1,2):
        cells=[]
        for family in ('ENERGY_BALANCE','EQUILIBRIUM'):
            for name in (trace['reference'],trace['candidate']):
                audit=validation['summaries'][f'{block}/{name}']['all']['modes']['neural']['audits'].get(family)
                cells.append(f"{audit['failed']} / {audit['available']}" if audit else 'unavailable')
        lines.append(f"| {block} | "+' | '.join(cells)+' |')
    lines+=['',
        'The case evidence separates omissions at a branch-forbidden phase, an allowed phase whose decoded total is zero, and a positive phase with missing components. A zero decoded phase does not by itself distinguish a nonpositive raw total from total loss during pruning. The evidence also reports signed traffic errors, extra above-floor entries, branch agreement, unavailable predictions, and energy/equilibrium audit results by terminal trajectory owner. A smaller omission count alone does not establish a better seed or mandatory support at every admissible root. State-dependent projected residuals and audit ratios are diagnostics, not one common training objective. FIRST terminal energy evidence may belong to its classical fallback, while the separate ONLY request is a different trajectory.','']
    return finish(lines,plan,selection,screen,validation,eligible)


def finish(lines,plan,selection,screen,validation,eligible):
    lines+=['## 2. Native checkpoint selection','',
        'The same three updates—3,120, 4,160 and 4,640—were screened for each fit. The panel contains 65 historically outcome-stratified validation cases, including its rare LIQUID_ONLY reference. Selection prioritizes classical-success preservation, then strict FIRST count, mean FIRST time and earlier update. No better seed was selected: 20260911 was fixed as the full-validation replicate in advance.','',
        '| Arm | Native-selected update, seed 20260911 | Native-selected update, seed 20260912 | Selected strict FIRST, primary / second seed | Primary preserves panel classical union |',
        '|---|---:|---:|---:|---|']
    for arm in plan['armOrder']:
        a=selection['nativeSelections'][arm]['20260911'];b=selection['nativeSelections'][arm]['20260912']
        first_a=count(screen,1,a['pipeline'],'neuralFirst');first_b=count(screen,1,b['pipeline'],'neuralFirst')
        lines.append(f"| {arm} | {a['step']:,} | {b['step']:,} | {first_a} / {first_b} | {'yes' if a['preservesClassicalUnion'] else 'no'} |")
    native=selection['nativeSelections']['T']['20260911']['pipeline'];profile=selection['primaryTraceProfileSelected']
    lines += ['',f"For primary T, profile scoring selects **{label(profile)}**, while native screening selects **{label(native)}**. "+
        ('They select the same artifact, so this study observes no checkpoint-choice difference for T.' if native==profile else 'Both artifacts are included in the full comparison above.'),'',
        'Detailed screening results retain both training-order seeds at all three updates. Full-validation summaries separately report the 65 screening cases and the remaining 340. The remainder provides an internal validation check; it is not a newly independent holdout. Single-pass screening time is a tie-break heuristic, not proof of a speedup.','',
        '## 3. Compact incumbent-preserving anchors','',
        'F has 185 inputs and 89,496 parameters. K has 103 inputs and 84,248 parameters, compared with I/C at 96 inputs and 83,800 parameters. K retains native temperature, log liquid traffic and log vapor traffic plus the predicted-branch and availability indicators. All 85 output heads, including water, wetness and component presence, remain intact.','',
        'Zero-initialized added embedding columns preserve the incumbent’s raw outputs, branches and decoded masks exactly on all 905 TRAIN inputs before fitting. Outputs remain absolute; no mechanistic anchor is added to them. Arbitrary finite and unavailable anchor tests also preserve the initial function, and nonzero gradients can train the new projection. Native parity and ten-worker cancellation/ownership checks pass for every exported checkpoint.','',
        'K−F at 4,160 measures simplification; K−C at the same update measures whether adding these anchor features helps. F and K pay for MATERIAL_CLOSED baseline construction inside the neural budget, including failed attempts. Algebraic initialization equivalence therefore does not imply equal request cost.','',
        '## 4. Added-data and compute accounting','',
        'The new training domains are 804 unchanged original records and those same records plus 101 additions. The disputed historical target is excluded from every new minibatch. Each new fit nevertheless inherits the incumbent’s historical pretraining; this is continuation training, not unlearning. Historical input/output normalization is shared across arms.','',
        '| Endpoint | Old examples: presentations each | Added examples: presentations each |',
        '|---|---:|---:|',
        '| C at 4,160 | 160 | — |',
        '| D at 4,160 | 143–144 | 143–144 |',
        '| D at 4,640 | 160 | 160 |',
        '| C at 4,640 | 178–179 | — |','',
        'Per-case counters verify these bounds at both training-order seeds. C/T/F/K share exact minibatch-order hashes for each seed. The fixed endpoint table distinguishes equal updates, matched original-example exposure, equal extended updates and extra optimization alone. The selected-checkpoint results do not replace those comparisons.','',
        '## Verification, scope and files','',
        f"Completed accounting: **{screen['measuredRequests']+validation['measuredRequests']:,} measured native requests**, plus **{screen['warmupRequests']+validation['warmupRequests']:,} fixed TRAIN warmup requests**. Full validation covers {len(validation['pipelines'])} frozen pipelines × 405 inputs × three strategies × two blocks. All ten fits, thirty checkpoints, export identities, exact masks, source bindings and presentation counts were verified.",'',
        'The main evidence files are `screen-analysis.json`, `validation-analysis.json`, their case/profile sidecars, `selection.json`, `validation-plan.json` and the ten fit reports. They preserve strict/advisory/failure outcomes, fallback and unknown evidence, paired case identities, support/profile metrics, gradient diagnostics and complete request costs.','',
        'All original validation records match the preceding archive. No former fresh-test outcomes, new acquisition, coefficient sweep, extra architecture or production-default change was used. Missing wet-qualified and VAPOR_ONLY training populations remain gaps. The preliminary 145-input draft and both preflight setup/syntax failures are retained as superseded evidence; the registered compact arm has 103 inputs.','',
        'See the [protocol](protocol.md) and [archive manifest](cache-manifest.json) for source hashes, registration, verification and restoration.','']
    summary=dict(revision='trace-followup-report-summary-v1',complete=True,registeredFits=10,checkpoints=30,
        eligibleForFurtherQualification=eligible,productionDefaultChanged=False,newTestEvaluation=False,
        primaryNativeSelections=selection['nativeSelections'],primaryTraceProfileSelected=selection['primaryTraceProfileSelected'],
        measuredNativeRequests=screen['measuredRequests']+validation['measuredRequests'],warmupRequests=screen['warmupRequests']+validation['warmupRequests'],
        fullValidationPipelines=validation['pipelines'],traceCoefficient=plan['traceCoefficient'],
        inputs=[info(OUT/name) for name in ('training-plan.json','selection.json','screen-analysis.json','validation-analysis.json')],
        renderer=info(Path(__file__)))
    return summary,'\n'.join(lines)


if __name__=='__main__':
    summary,text=build();freeze(OUT/'report-summary.json',summary)
    with (OUT/'report.md').open('x',encoding='utf-8',newline='\n') as stream:stream.write(text)
    print(json.dumps({'reportRendered':True,'eligible':summary['eligibleForFurtherQualification']}))
