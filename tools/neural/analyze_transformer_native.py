"""Freeze validation selection and analyze paired native outcomes without test tuning."""
import argparse
from collections import Counter
from datetime import datetime, timezone
from itertools import combinations
import json
from pathlib import Path
import numpy as np
from prepare_transformer_data import read_rows, strict, digest, stats, profile_difference
from prepare_generalized_evaluation import canonical_input_hash

ROOT=Path(__file__).resolve().parents[2]
AREA=ROOT/'build/neural-transformer/native-v1'
LABELS=('transformer','mlp','gen3-factorized','nearest-k1')


def read(path): return json.loads(path.read_text(encoding='utf-8-sig'))


def write(path, data):
    with path.open('x',encoding='utf-8') as f: json.dump(data,f,indent=2,sort_keys=True,allow_nan=False); f.write('\n')


def load_run(stage,label,source,benchmark=False):
    folder=AREA/f'{stage}-{label}'
    rows=read_rows(folder/('cases.jsonl' if label=='current' else 'evaluation.jsonl'))
    meta=read(folder/'run.json'); expected={str(r['id']):canonical_input_hash(r['input']) for r in source}
    if len(rows)!=len(expected) or meta['completed']!=len(expected) or len({str(r['id']) for r in rows})!=len(rows): raise ValueError('Incomplete or duplicate run')
    if {str(r['id']):canonical_input_hash(r['input']) for r in rows}!=expected: raise ValueError('Unpaired inputs')
    if meta['workers']!=(1 if benchmark else 10) or meta['deadlineMillis']!=30000 or meta['neuralBudgetMillis']!=(2000 if benchmark else 10000) or meta['neuralMaximumIterations']!=16:
        raise ValueError('Unequal solve policy')
    if label!='current' and meta['modelSha256']!=read(AREA/'candidates.json')['candidates'][label]['modelSha256']: raise ValueError('Model changed')
    return rows,meta


def outcomes(rows):
    qualified=[r for r in rows if strict(r)]
    result={'cases':len(rows),'strictQualified':len(qualified),'nativeAccepted':sum(r.get('success') is True for r in rows),
        'advisoryOnly':sum(r.get('success') is True and not strict(r) for r in rows),
        'statusCounts':dict(Counter(r.get('status','missing') for r in rows)),
        'qualifiedIds':sorted(str(r['id']) for r in qualified)}
    for field in ('ms','cpuMillis','allocatedBytes'):
        values=[r[field] for r in rows if r.get(field) is not None]
        if values: result[field]=stats(values)
    if any('rawPrediction' in r for r in rows):
        result['rawSupported']=sum(r.get('rawPrediction',{}).get('supported') is True for r in rows)
        values=[r['rawPrediction']['ms'] for r in rows if r.get('rawPrediction',{}).get('ms') is not None]
        if values: result['rawInferenceMillis']=stats(values)
    return result


def freeze_selection():
    if (AREA/'selection.json').exists(): raise FileExistsError('Selection already frozen')
    if list(AREA.glob('fresh-*/evaluation.jsonl')): raise ValueError('Test evaluation already began')
    source=read_rows(AREA/'validation.jsonl'); candidates={}
    for label in LABELS:
        rows,meta=load_run('validation',label,source)
        candidates[label]={**outcomes(rows),'modelBytes':(ROOT/read(AREA/'candidates.json')['candidates'][label]['modelPath']).stat().st_size,
            'journalSha256':digest(AREA/f'validation-{label}/evaluation.jsonl'),'run':meta}
    rank=lambda label:(-candidates[label]['strictQualified'],candidates[label]['modelBytes'],label)
    selected=min(('transformer','mlp'),key=rank)
    result={'revision':'transformer-native-selection-v1','frozenUtc':datetime.now(timezone.utc).isoformat(),
        'selectedNewArchitecture':selected,'bestIncludingFrozenControls':min(LABELS,key=rank),'testTuningAllowed':False,
        'candidateManifestSha256':digest(AREA/'candidates.json'),'paritySha256':digest(AREA/'parity.json'),
        'holdoutSha256':digest(ROOT/'build/neural-transformer/data-v2/fresh-holdout.jsonl'),
        'benchmarkSha256':digest(ROOT/'build/neural-transformer/data-v2/fresh-benchmark.jsonl'),
        'inferenceSourceSha256':digest(ROOT/'tools/neural/V3ColumnTransformerInitializer.java'),
        'candidates':candidates}
    write(AREA/'selection.json',result)
    print(json.dumps({'selected':selected,'strictQualified':{k:v['strictQualified'] for k,v in candidates.items()}},indent=2))


def paired(a,b):
    ka={str(r['id']) for r in a if strict(r)}; kb={str(r['id']) for r in b if strict(r)}
    if {str(r['id']) for r in a}!={str(r['id']) for r in b}: raise ValueError('Different paired populations')
    return {'bothQualified':len(ka&kb),'firstOnlyQualified':len(ka-kb),'secondOnlyQualified':len(kb-ka),
        'neitherQualified':len(a)-len(ka|kb),'firstOnlyIds':sorted(ka-kb),'secondOnlyIds':sorted(kb-ka)}


def final_report():
    selection=read(AREA/'selection.json')
    if digest(AREA/'candidates.json')!=selection['candidateManifestSha256']: raise ValueError('Candidate manifest changed')
    if digest(ROOT/'tools/neural/V3ColumnTransformerInitializer.java')!=selection['inferenceSourceSha256']: raise ValueError('Frozen inference implementation changed')
    for name,field in [('fresh-holdout.jsonl','holdoutSha256'),('fresh-benchmark.jsonl','benchmarkSha256')]:
        if digest(ROOT/'build/neural-transformer/data-v2'/name)!=selection[field]: raise ValueError('Holdout changed')
    fresh_source=read_rows(ROOT/'build/neural-transformer/data-v2/fresh-holdout.jsonl')
    benchmark_source=read_rows(ROOT/'build/neural-transformer/data-v2/fresh-benchmark.jsonl')
    fresh_rows={}; fresh={}; benchmarks={}; source_hashes={}
    for label in (*LABELS,'current'):
        rows,meta=load_run('fresh',label,fresh_source)
        fresh_rows[label]=rows; fresh[label]={**outcomes(rows),'run':meta}
    for label in LABELS:
        rows,meta=load_run('benchmark',label,benchmark_source,True)
        variants={mode:[{**r,**r[mode]} for r in rows] for mode in ('current','neural','neuralFirst')}
        record={'run':meta,'modes':{k:outcomes(v) for k,v in variants.items()},
            'firstVsCurrent':paired(variants['neuralFirst'],variants['current']),
            'neuralVsCurrent':paired(variants['neural'],variants['current']),
            'pairedFirstMinusCurrentMillis':stats([r['neuralFirst']['ms']-r['current']['ms'] for r in rows]),
            'rawInferenceMillis':stats([r['rawPrediction']['ms'] for r in rows])}
        common=[r for r in rows if strict({**r,**r['neuralFirst']}) and strict({**r,**r['current']})]
        record['commonQualifiedCount']=len(common)
        if common: record['pairedFirstMinusCurrentMillisCommonQualified']=stats([r['neuralFirst']['ms']-r['current']['ms'] for r in common])
        record['firstLossDetails']=[{'id':r['id'],'firstStatus':r['neuralFirst']['status'],
            'firstWaterQualification':r['neuralFirst'].get('waterQualification'),
            'firstFailure':r['neuralFirst'].get('failure'),'firstMillis':r['neuralFirst']['ms'],
            'currentMillis':r['current']['ms']} for r in rows
            if strict({**r,**r['current']}) and not strict({**r,**r['neuralFirst']})]
        benchmarks[label]=record
    for p in sorted(AREA.rglob('*')):
        if p.is_file() and p.name in ('evaluation.jsonl','cases.jsonl','run.json','model.json','java-parity.json'): source_hashes[p.relative_to(AREA).as_posix()]=digest(p)
    comparisons={f'{a}_vs_{b}':paired(fresh_rows[a],fresh_rows[b]) for a,b in [('transformer','mlp'),('transformer','gen3-factorized'),('transformer','nearest-k1'),('transformer','current')]}
    by_id={label:{str(r['id']):r for r in rows} for label,rows in fresh_rows.items()}
    disagreements=[]
    for identifier in by_id['transformer']:
        qualified=[(label,by_id[label][identifier]) for label in by_id if strict(by_id[label][identifier])]
        if len(qualified)<2: continue
        for (reference,first),(label,row) in combinations(qualified,2):
            difference=profile_difference(first,row)
            if difference['temperatureMaxK']>.1 or difference['flowMaxOverFeed']>1e-4 or difference['wetDifferent'] or difference['branchDifferent']:
                disagreements.append({'id':identifier,'reference':reference,'other':label,**difference})
    result={'revision':'transformer-native-report-v1','selection':selection,'fresh':fresh,'benchmark':benchmarks,
        'freshPaired':comparisons,'qualifiedProfileDisagreements':disagreements,'sourceSha256':source_hashes,'defaultChanged':False}
    result['freshStageCoverage']={f'{lo}-{hi}':{label:{'cases':sum(lo<=r['input']['stageCount']<=hi for r in rows),
        'qualified':sum(strict(r) for r in rows if lo<=r['input']['stageCount']<=hi)} for label,rows in fresh_rows.items()}
        for lo,hi in ((2,16),(17,32),(33,48),(49,64))}
    write(AREA/'summary.json',result); write(ROOT/'tools/neural/transformer-native-summary.json',result)
    def fmt(s): return f"{s['mean']:.4g} ± {s['sampleSd']:.3g}"
    lines=['# Transformer native convergence and runtime', '',
        f"Native validation selected **{selection['selectedNewArchitecture']}** using the original 405 cases. This report evaluates that frozen decision on the replacement 252-case holdout. All models perform actual Java inference inside the rigorous solve; runtime defaults remain unchanged.", '',
        '## Strict native outcomes', '',
        '| Candidate | Validation qualified / 405 | Fresh qualified / 252 | Fresh advisory only | Fresh raw supported |', '|---|---:|---:|---:|---:|']
    for label in LABELS:
        s=selection['candidates'][label]; f=fresh[label]
        lines.append(f"| {label} | {s['strictQualified']} | {f['strictQualified']} | {f['advisoryOnly']} | {f['rawSupported']} |")
    lines += [f"| Classical CURRENT_ONLY | — | {fresh['current']['strictQualified']} | {fresh['current']['advisoryOnly']} | — |", '',
        'Qualified means success plus equilibrium qualification, a passed native acceptance audit, and the strict final Newton certificate. Advisory results are not qualified labels. Neural-only convergence runs use 16 correction iterations, a 10-second neural budget and 30-second request deadline with 10 workers. Per-case concurrent times are excluded from latency conclusions. Solver status labels, including initialization-time infeasibility, are recorded outcomes rather than independent proofs of physical infeasibility.', '',
        '| Fresh paired comparison | Both qualified | Transformer only | Control only | Neither |', '|---|---:|---:|---:|---:|']
    for name,v in comparisons.items(): lines.append(f"| {name.removeprefix('transformer_vs_')} | {v['bothQualified']} | {v['firstOnlyQualified']} | {v['secondOnlyQualified']} | {v['neitherQualified']} |")
    lines += ['', '## Serial end-to-end benchmark', '',
        'These are the same 64 input-only cases for every model. Each strategy gets a 30-second whole-request deadline; neural strategies receive a 2-second neural budget and 16 correction iterations. LNN_FIRST includes inference, correction and classical fallback where needed. Each model run measures its own paired classical control. All times include failures; common-success paired differences are supplied separately in JSON. SD below is sample SD across cases, not uncertainty across repeated benchmark runs.', '',
        '| Model | CURRENT qualified | LNN_ONLY qualified | LNN_FIRST qualified | CURRENT ms | LNN_ONLY ms | LNN_FIRST ms | Paired FIRST − CURRENT ms |', '|---|---:|---:|---:|---:|---:|---:|---:|']
    for label,b in benchmarks.items():
        m=b['modes']; lines.append(f"| {label} | {m['current']['strictQualified']} | {m['neural']['strictQualified']} | {m['neuralFirst']['strictQualified']} | {fmt(m['current']['ms'])} | {fmt(m['neural']['ms'])} | {fmt(m['neuralFirst']['ms'])} | {fmt(b['pairedFirstMinusCurrentMillis'])} |")
    lines += ['', 'Raw inference, measured separately from the end-to-end calls:', '', '| Model | Inference ms, mean ± SD | FIRST gains / losses against paired CURRENT |', '|---|---:|---:|']
    for label,b in benchmarks.items(): lines.append(f"| {label} | {fmt(b['rawInferenceMillis'])} | {b['firstVsCurrent']['firstOnlyQualified']} / {b['firstVsCurrent']['secondOnlyQualified']} |")
    losses={label:[{'id':r['id'],'status':r['firstStatus'],'grade':r['firstWaterQualification']} for r in b['firstLossDetails']] for label,b in benchmarks.items() if b['firstLossDetails']}
    if losses:
        lines += ['', 'Some LNN_FIRST calls lose qualification relative to their paired classical call. The JSON records each case, status, water grade and timings. The existing runtime returns accepted advisory results and treats certain terminal correction failures as terminal rather than always trying classical fallback; whole-request deadlines can also limit backup. These policies are unchanged in this comparison.']
    lines += ['', 'One serial campaign per model does not establish stable microbenchmark timings. Case difficulty, failures, JIT/GC and deadline competition affect dispersion. Allocated bytes in the JSON are allocation volume, not retained memory. Compare qualified coverage alongside time, especially when one strategy fails quickly.', '',
        '## Evidence and limits', '',
        'The 805 fitted columns and all weights are unchanged from the GPU pilot. The transformer and matched MLP each use seed 20260912, selected by offline validation before native comparisons. Selection hashes were frozen before fresh evaluation. New holdout labels remain outside fitting.', '',
        f"Comparing qualified profiles across fresh methods found {len(disagreements)} material pairwise disagreements across {len({d['id'] for d in disagreements})} inputs (temperature >0.1 K, component/free-water flow >1e-4 of feed, or differing branch/wet masks). Details are retained in JSON. Accepted profiles are not averaged or silently promoted to training labels.", '',
        'Both Java models passed 70,582 feature/raw-value comparisons on 14 TRAIN fixtures, full decoded temperature/flow parity, exact branch/wet/zero masks, cancellation propagation, malformed-dimension rejection, and 32 identical predictions across eight concurrent workers. Maximum temperature difference from the independent double-precision PyTorch fixture was below 0.00002 K. The small erf approximation used for GELU is measured by these parity checks; this is fixture evidence, not a domain-wide error bound.', '',
        'The current methane-containing mixture naturally has few liquid-only labels. This campaign cannot establish wet-equilibrium generalization: fitted labels contain no free-water profiles. Native audits still govern every returned result. The old validation fold has been reused across generations; the fresh results are the prospective evidence for this frozen comparison.', '',
        'Implementation is restricted to the offline neural tooling. No game runtime initializer, default setting, correction budget or acceptance criterion was changed. See [the protocol](transformer-native-protocol.md), [machine-readable results](transformer-native-summary.json), and [the GPU/data investigation](transformer-investigation.md).', '']
    (ROOT/'tools/neural/transformer-native-results.md').write_text('\n'.join(lines),encoding='utf-8')
    print(json.dumps({'freshQualified':{k:v['strictQualified'] for k,v in fresh.items()},'benchmarkFirstQualified':{k:v['modes']['neuralFirst']['strictQualified'] for k,v in benchmarks.items()}},indent=2))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__); parser.add_argument('phase',choices=('freeze','report')); args=parser.parse_args()
    freeze_selection() if args.phase=='freeze' else final_report()
