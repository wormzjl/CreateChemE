"""Characterize frozen inputs/labels, summarize paired GPU pilots, and preserve artifacts."""
from collections import Counter
import hashlib
import json
from pathlib import Path
import zipfile

import numpy as np
from prepare_transformer_data import read_rows, stats, digest, strict

ROOT=Path(__file__).resolve().parents[2]
STUDY=ROOT/'build/neural-transformer'
DATA=STUDY/'data-v2'
TOOLS=ROOT/'tools/neural'


def write(path,obj):
    path.write_text(json.dumps(obj,indent=2,sort_keys=True,allow_nan=False)+'\n',encoding='utf-8')


def fmt(s):
    return f"{s['mean']:.4g} ± {s['sampleSd']:.3g}"


def main():
    info=json.loads((DATA/'data.json').read_text())
    rows=read_rows(DATA/'cases.jsonl')
    train=[r for r in rows if r['labelProvenance']['eligibleForFitting']]
    old=[r for r in rows if r['labelProvenance']['origin']=='old_matrix' and r['split']=='train']
    fresh=[r for r in rows if r['labelProvenance']['origin']=='retired_gen3_fresh']
    coverage=[]
    for lo,hi in ((2,16),(17,32),(33,48),(49,64)):
        old_group=[r for r in old if lo<=r['input']['stageCount']<=hi]
        fresh_group=[r for r in fresh if lo<=r['input']['stageCount']<=hi]
        coverage.append({'stageRange':f'{lo}–{hi}', 'oldTrainInputs':len(old_group),
            'oldTrainQualified':sum(strict(r) for r in old_group),'retiredFreshInputs':len(fresh_group),
            'retiredFreshQualified':sum(strict(r) for r in fresh_group),
            'eligibleTrainColumns':sum(lo<=r['input']['stageCount']<=hi for r in train)})
    runs={}; per_seed={}; paired=[]
    for kind in ('transformer','mlp'):
        reports=[json.loads((STUDY/f'pilot-{kind}-{seed}/report.json').read_text()) for seed in (20260910,20260911,20260912)]
        if any(r['dataSha256']!=info['fileSha256']['cases.jsonl'] or r['device']!='cuda' or r['nativeSolverEvaluated'] or r['freshHoldoutEvaluated'] for r in reports):
            raise ValueError('Pilot violates frozen protocol')
        runs[kind]=reports
        per_seed[kind]={k:stats([r[k] for r in reports]) for k in ('bestValidationLoss','trainingSeconds','bestEpoch')}
        per_seed[kind]['parameters']=reports[0]['parameters']
        per_seed[kind]['validationPerSeedColumnMeans']={k:stats([r['validation'][k]['mean'] for r in reports]) for k in reports[0]['validation']}
    for seed in (20260910,20260911,20260912):
        a=read_rows(STUDY/f'pilot-transformer-{seed}/validation.jsonl')
        b=read_rows(STUDY/f'pilot-mlp-{seed}/validation.jsonl')
        if [r['id'] for r in a]!=[r['id'] for r in b]: raise ValueError('Unpaired validation')
        paired.append({'seed':seed,'transformerMinusMlp':{k:stats([float(x[k])-float(y[k]) for x,y in zip(a,b)]) for k in a[0] if k!='id'}})
    characterization={'stageCoverageAndQualification':coverage,
        'missingTrainingStageCounts':[n for n in range(2,65) if not any(r['input']['stageCount']==n for r in train)],
        'trainingStageCountsWithAtMostThreeColumns':{n:count for n,count in sorted(Counter(r['input']['stageCount'] for r in train).items()) if count<=3},
        'data':info,'pilots':per_seed,'pairedPerSeed':paired}
    report_dir=STUDY/'analysis'; report_dir.mkdir(exist_ok=True)
    write(report_dir/'summary.json',characterization)
    write(TOOLS/'transformer-pilot-summary.json',characterization)
    lines=['# Transformer data and GPU pilot', '',
        '**805 qualified training columns** are available, versus 579 before: +133 original TRAIN rescues and +93 fresh Gen3 labels (39.0% more columns). The 94th fresh success is quarantined because accepted methods disagree on its condenser branch.', '',
        'Native audits and final Newton certificates were checked before any label was admitted. All original validation/test inputs retain their folds; validation now has 168 qualified references, the old test has 167, and 14 solved historical cases remain challenge-only. Repeated successful solves produce one target per input, with journal hashes and provenance.', '',
        'The replacement **252-case holdout** and its 64-case benchmark subset are frozen and unevaluated. There are four cases at every stage count 2–64, two steam-on and two steam-off, with zero overlap against 5,637 earlier input hashes. The old geometry test is now a regression fold: fresh Gen3 additions introduce some of its stage counts into training.', '',
        '## What the data says', '',
        'The 805 columns contain 20,992 correlated nodes, not 20,992 independent training examples. All normalization, losses and reported errors give each column equal weight. Training covers 61 of 63 stage counts; **N=38 and N=52 have no fitted labels**, and several other stage counts have only one to three.', '',
        'The branch labels are 794 TWO_PHASE and 11 LIQUID_ONLY. As expected for the current methane-containing mixture, liquid-only cases are rare; the pilot keeps that natural distribution. There are 437 steam-on columns, but all fitted profiles have dry equilibrium and no free-water nodes. Steam presence must not be interpreted as a wet-equilibrium label.', '',
        '| Stages | Qualified / original TRAIN inputs | Qualified / retired fresh inputs | Eligible fitted columns |',
        '|---|---:|---:|---:|']
    for r in coverage: lines.append(f"| {r['stageRange']} | {r['oldTrainQualified']} / {r['oldTrainInputs']} | {r['retiredFreshQualified']} / {r['retiredFreshInputs']} | {r['eligibleTrainColumns']} |")
    lines += ['', 'Qualified-label coverage declines with column size. These counts describe observed solver success; unlabelled failures are not proofs of infeasibility. Fresh successes help equipment coverage: four-pumparound training columns rise from 57 to 100.', '',
        '| Per-column training statistic | Mean ± sample SD | Median | 95th percentile |', '|---|---:|---:|---:|']
    for k,label in [('temperatureMeanK','Mean profile temperature (K)'),('maximumAdjacentTemperatureJumpK','Maximum adjacent temperature jump (K)'),('liquidTotalOverFeedMean','Mean liquid traffic / feed'),('vaporTotalOverFeedMean','Mean vapor traffic / feed'),('liquidZeroEntryFraction','Zero liquid component fraction'),('vaporZeroEntryFraction','Zero vapor component fraction')]:
        s=info['perColumnStatistics'][k]; lines.append(f"| {label} | {fmt(s)} | {s['median']:.4g} | {s['p95']:.4g} |")
    lines += ['', 'Large temperature jumps and sparse phase/component flows argue against forcing smooth profiles or using only unweighted raw-flow error. The pilot reuses factorized phase totals, feed-prior composition, trace-support heads and the conservative decoder. Three inputs show materially differing accepted profiles across methods: the new branch-ambiguous case is quarantined; the two existing labels remain unchanged. One existing training case differs by up to 68.86 K and 9.77 times feed in component flow from a later replay, so a future root-selection policy deserves investigation.', '',
        '## CUDA comparison', '',
        'Both architectures use the same 805 columns, 168 validation references, factorized targets, branch classifier, optimizer, stopping rule and three seeds. The transformer uses two width-64 blocks and four heads; the residual MLP is matched within 0.7% in parameter count. Teacher branch labels are absent from input features. At only 4–66 tokens, full attention is inexpensive.', '',
        'The following SD is **across the three seed-level means**, not across columns. Individual reports also contain per-column sample SD and paired differences.', '',
        '| Metric | Transformer | MLP |', '|---|---:|---:|']
    for key,label in [('bestValidationLoss','Best validation objective'),('trainingSeconds','GPU training time per run (s)')]:
        lines.append(f"| {label} | {fmt(per_seed['transformer'][key])} | {fmt(per_seed['mlp'][key])} |")
    for key,label in [('temperatureMaeK','Temperature MAE (K)'),('liquidTotalMaeOverFeed','Liquid total MAE / feed'),('vaporTotalMaeOverFeed','Vapor total MAE / feed'),('branchCorrect','Branch accuracy')]:
        lines.append(f"| {label} | {fmt(per_seed['transformer']['validationPerSeedColumnMeans'][key])} | {fmt(per_seed['mlp']['validationPerSeedColumnMeans'][key])} |")
    lines += ['', f"Parameter counts: transformer {per_seed['transformer']['parameters']:,}; MLP {per_seed['mlp']['parameters']:,}. All six runs used PyTorch 2.10.0+cu128 on the RTX 4070 Ti, float32 with TF32 disabled. Timing covers optimization and validation/early stopping, excludes environment startup/data preparation, and is not an inference or native-solver benchmark.", '',
        '## Interpretation and next gates', '',
        'This is evidence about held-out profile regression on the qualified validation subset. It does not establish native convergence improvements, performance on the unlabelled failures, or support for wet equilibrium. The validation fold has been reused across generations, so it is model-selection evidence. The fresh replacement test remains available for a later frozen native campaign.', '',
        'Before any promotion: export the chosen model with CPU/Java parity checks; compare strict native convergence and equal-policy end-to-end runtime against the incumbent nearest-profile initializer and the matched MLP; test branch/root ambiguity and trace-support behavior; then evaluate the untouched replacement holdout after candidate selection is frozen. Targeted additional native data at N=38/52 and sparsely covered large columns is more justified than artificially balancing liquid-only cases.', '',
        'The research rationale and pre-fit choices are in [transformer-protocol.md](transformer-protocol.md), including [Transolver](https://arxiv.org/abs/2402.02366), [LinearNO](https://arxiv.org/abs/2511.06294), and [warm-start fixed-point optimization](https://jmlr.org/papers/v25/23-1174.html). Large-mesh attention and optimization convergence results do not automatically transfer to this MESH active-set solver.', '',
        '## Reproduce and retain', '',
        'Use the project-local Python environment and `requirements-transformer.txt`. Restore the verified Gen3 campaign, run `prepare_transformer_data.py`, run `test_transformer.py`, then run `train_transformer.py` for the two architectures and seeds listed in the protocol. `finalize_transformer_pilot.py` regenerates this report and the immutable cache.', '',
        'The cache manifest in `transformer-cache-manifest.json` records SHA-256 for the full dataset, provenance, replacement holdouts, all six checkpoints, validation predictions, training histories, environment lock and implementation snapshots. The archive lives under `.neural-cache/transformer-investigation-v1/`, outside Gradle clean. Earlier generation caches are preserved.', '']
    (TOOLS/'transformer-investigation.md').write_text('\n'.join(lines),encoding='utf-8')
    cache=ROOT/'.neural-cache/transformer-investigation-v1'; cache.mkdir(parents=True,exist_ok=True)
    archive=cache/'study.zip'
    if archive.exists(): raise FileExistsError('Never overwrite an investigation archive')
    sources=[(p,p.relative_to(STUDY).as_posix()) for p in sorted(DATA.glob('*')) if p.is_file()]
    for folder in sorted(STUDY.glob('pilot-*')):
        sources += [(p,p.relative_to(STUDY).as_posix()) for p in sorted(folder.glob('*')) if p.is_file()]
    sources += [(report_dir/'summary.json','analysis/summary.json'), (STUDY/'environment.txt','environment.txt')]
    sources += [(TOOLS/name,'implementation/'+name) for name in ('prepare_transformer_data.py','train_transformer.py','test_transformer.py','finalize_transformer_pilot.py','transformer-protocol.md','transformer-investigation.md','requirements-transformer.txt')]
    entries=[]
    with zipfile.ZipFile(archive,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for path,name in sources:
            z.write(path,name); entries.append({'entry':name,'sha256':digest(path),'bytes':path.stat().st_size})
    with zipfile.ZipFile(archive) as z:
        if z.testzip() is not None: raise ValueError('Corrupt archive')
        for e in entries:
            value=z.read(e['entry'])
            if hashlib.sha256(value).hexdigest()!=e['sha256'] or len(value)!=e['bytes']: raise ValueError('Archive roundtrip mismatch')
    manifest={'revision':'transformer-investigation-cache-v1','archivePath':archive.relative_to(ROOT).as_posix(),
        'archiveSha256':digest(archive),'archiveBytes':archive.stat().st_size,'entries':entries,
        'verifiedAllEntries':True,'restoreRoot':'build/neural-transformer','sourceGen3ManifestSha256':digest(TOOLS/'gen3-cache-manifest.json')}
    write(cache/'manifest.json',manifest); write(TOOLS/'transformer-cache-manifest.json',manifest)
    print(json.dumps({'pilotSummary':per_seed,'cacheBytes':manifest['archiveBytes'],'cacheEntries':len(entries)},indent=2))


if __name__=='__main__': main()
