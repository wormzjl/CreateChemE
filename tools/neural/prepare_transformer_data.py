"""Audit Gen3 native successes, preserve old folds, and freeze a replacement holdout.

Gen3 fresh-test retirement is explicitly authorized for this revision. Never use
raw network predictions as labels. One deterministic native profile per input.
"""
from collections import Counter, defaultdict
import copy
import hashlib
import json
from pathlib import Path

import numpy as np
import generalized_design as design
import prepare_gen3_holdouts as holdouts
from prepare_generalized_evaluation import canonical_input_hash
from prepare_gen3_data import qualified
import train_generalized as base

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'build/neural-gen3'
DEST = ROOT / 'build/neural-transformer/data-v2'


def read_rows(path):
    return [json.loads(s) for s in path.read_text(encoding='utf-8-sig').splitlines() if s]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def strict(row):
    if not qualified(row):
        return False
    if canonical_input_hash(row['input']) != canonical_input_hash(row['seed']['input']):
        raise ValueError('Native state/input mismatch')
    audit = row['diagnostics']['acceptanceAudit']['checks']
    cert = row['diagnostics']['convergenceEvidence']
    if not (audit and all(c['passed'] is True for c in audit)
            and cert['hasFinalNewtonStep'] is True and cert['closureTolerance'] == 1e-8
            and cert['finalLinearBackwardError'] <= 1e-12
            and cert['maximumLogFlowChange'] <= 1e-8
            and cert['maximumTemperatureStepRatio'] <= 1):
        raise ValueError('Qualified result lacks strict native evidence')
    return True


def stats(values):
    a = np.asarray(values, dtype=float)
    return {'count': len(a), 'mean': float(a.mean()), 'sampleSd': float(a.std(ddof=1)) if len(a)>1 else 0.,
            **dict(zip(('min', 'p05', 'median', 'p95', 'max'), map(float, np.quantile(a, [0,.05,.5,.95,1]))))}


def coverage(rows):
    return {name: dict(Counter(str(fn(r)) for r in rows)) for name, fn in {
        'stageCount': lambda r:r['input']['stageCount'],
        'steamOn': lambda r:bool(r['input']['steamFeeds']),
        'paCount': lambda r:len(r['input']['pumparounds']),
        'sideDrawCount': lambda r:len(r['input']['sideDraws']),
        'branch': lambda r:r.get('seed',{}).get('branch','unlabelled'),
        'wetColumn': lambda r:any(r['seed']['wetTrays']) if 'seed' in r else 'unlabelled'}.items()}


def profile_difference(a, b):
    sa, sb = a['seed'], b['seed']
    f = sum(a['input']['feedComponentMolarFlowsMolPerSecond'])
    return {'temperatureMaxK': float(np.max(np.abs(np.asarray(sa['temperatures'])-sb['temperatures']))),
            'flowMaxOverFeed': max(float(np.max(np.abs(np.asarray(sa[k])-sb[k])))/f for k in ('liquid','vapor','freeWater')),
            'wetDifferent': sa['wetTrays'] != sb['wetTrays'], 'branchDifferent': sa['branch'] != sb['branch']}


def freeze(path, value, rows=False):
    text = ''.join(json.dumps(r, sort_keys=True, separators=(',', ':'), allow_nan=False)+'\n' for r in value) if rows else json.dumps(value, indent=2, sort_keys=True, allow_nan=False)+'\n'
    with path.open('x', encoding='utf-8', newline='\n') as f:
        f.write(text)


def main():
    if DEST.exists():
        raise FileExistsError('Use a new revision; do not overwrite frozen data')
    manifest = json.loads((ROOT/'tools/neural/gen3-cache-manifest.json').read_text())
    source_hashes = {}
    for item in manifest['files']:
        path = SOURCE/item['archiveEntry']
        if digest(path) != item['sha256']:
            raise ValueError('Changed Gen3 source: '+str(path))
        source_hashes[item['archiveEntry']] = item['sha256']
    original = read_rows(SOURCE/'data/cases.jsonl')
    fresh = read_rows(SOURCE/'fresh-design/matrix.jsonl')
    historical = [r for r in read_rows(SOURCE/'fresh-design/remaining-gen2-failures.jsonl')
                  if r['design']['gen3Origin']=='remaining_gen2_prior_failure']
    known = {canonical_input_hash(r['input']): r for r in original+fresh+historical}
    if len(known) != len(original)+len(fresh)+len(historical):
        raise ValueError('Duplicate source inputs')
    observations = defaultdict(list)
    advisory = Counter()
    # Original profiles have first priority, then CURRENT, then fixed lexical
    # journal/method order. Outcome magnitude and runtime never select a root.
    paths = [SOURCE/'data/cases.jsonl', SOURCE/'fresh-current/cases.jsonl']
    paths += sorted(p for p in SOURCE.rglob('evaluation.jsonl') if 'evaluation-inputs' not in p.parts)
    for path in paths:
        rel = path.relative_to(SOURCE).as_posix()
        for r in read_rows(path):
            variants = [(k, {**r, **r[k]}) for k in ('current','neural','neuralFirst') if isinstance(r.get(k),dict)] or [('',r)]
            for sub, row in variants:
                key = canonical_input_hash(row['input'])
                if key not in known:
                    raise ValueError('Unexpected observation input')
                if strict(row):
                    observations[key].append((row, {'journal':rel, 'journalSha256':source_hashes[rel], 'subresult':sub, 'sourceCaseId':str(row['id'])}))
                elif row.get('success') is True:
                    advisory[rel+(':'+sub if sub else '')] += 1
    records, provenance, conflicts = [], [], []
    additions = Counter()
    for key, source in sorted(known.items()):
        row = copy.deepcopy(source)
        oldsplit = source['split']
        origin = 'old_matrix' if source in original else ('retired_gen3_fresh' if source in fresh else 'historical_challenge')
        split = ('challenge' if origin=='historical_challenge' else
                 'train' if origin=='retired_gen3_fresh' else oldsplit)
        choices = observations[key]
        disagreement = []
        if choices:
            selected, selected_prov = choices[0]
            for candidate, p in choices[1:]:
                d = profile_difference(selected, candidate)
                if d['temperatureMaxK'] > .1 or d['flowMaxOverFeed'] > 1e-4 or d['wetDifferent'] or d['branchDifferent']:
                    disagreement.append({**p, **d})
            row = copy.deepcopy(selected)
            if not qualified(source):
                additions[origin+':'+split] += 1
        else:
            selected_prov = None
        row['split'] = split
        row['id'] = str(source['id'])
        # Preserve pre-existing labels; quarantine ambiguous newly acquired train
        # profiles pending physical root policy instead of silently choosing one.
        eligible = bool(choices) and split=='train' and not (disagreement and not qualified(source))
        row['labelProvenance'] = {'revision':'transformer-data-v2', 'canonicalInputSha256':key,
            'origin':origin, 'previousSplit':oldsplit, 'eligibleForFitting':eligible,
            'selected':selected_prov, 'qualifiedObservations':len(choices),
            'materialProfileDisagreement':bool(disagreement)}
        if disagreement:
            conflicts.append({'id':row['id'],'split':split,'originalQualified':qualified(source),'differences':disagreement})
        records.append(row)
        provenance.append({**row['labelProvenance'], 'id':row['id'], 'split':split, 'observations':[p for _,p in choices]})
    # New pool and subset are chosen entirely from inputs before any fitting.
    baseline = json.loads(design.DEFAULT_BASELINE.read_text(encoding='utf-8-sig'))['input']
    candidates, admitted, excluded, report = design.generate(baseline, 202609104)
    prior_hashes = set(known) | {canonical_input_hash(r['input']) for r in read_rows(SOURCE/'fresh-design/candidate-pool.jsonl')}
    new_hashes = [canonical_input_hash(r['input']) for r in candidates]
    assert not prior_hashes.intersection(new_hashes) and len(new_hashes)==len(set(new_hashes))
    holdouts.SELECTION_SEED = 'transformer-independent-inputs-20260910-v1'
    selected = holdouts.select_fresh(admitted)
    for r in selected:
        r['id'] = 'g4fresh-'+str(r['id']).removeprefix('gd-')
        r['split'] = 'test'
        r['design'].update(trainingAllowed=False, origin='prospective_gen4_holdout')
    benchmark = holdouts.balanced_subset(selected, 64, 'gen4-fresh-benchmark')
    train = [r for r in records if r['labelProvenance']['eligibleForFitting']]
    val = [r for r in records if r['split']=='validation' and strict(r)]
    before = [r for r in original if r['split']=='train' and strict(r)]
    metrics = defaultdict(list)
    for r in train:
        seed, inp = r['seed'], r['input']
        f = sum(inp['feedComponentMolarFlowsMolPerSecond'])
        metrics['temperatureMeanK'].append(np.mean(seed['temperatures']))
        metrics['maximumAdjacentTemperatureJumpK'].append(np.max(np.abs(np.diff(seed['temperatures']))))
        metrics['wetNodeFraction'].append(np.mean(seed['wetTrays']))
        for phase in ('liquid','vapor'):
            q = np.asarray(seed[phase])
            metrics[phase+'TotalOverFeedMean'].append(q.sum(axis=1).mean()/f)
            metrics[phase+'ZeroEntryFraction'].append(np.mean(q==0))
    g = np.vstack([base.global_features(r['input']) for r in train])
    gm, gs = base.moments(g)
    gv = np.vstack([base.global_features(r['input']) for r in val])
    dist = np.sqrt(np.mean(((gv[:,None,:]-g[None,:,:])/gs)**2, axis=2)).min(axis=1)
    info = {'revision':'transformer-data-v2', 'policy':'Retire Gen3 fresh252; promote strict native successes only. Preserve old matrix folds. All35 historical cases are challenge-only regardless of their historical split field. TRAIN-only normalization; one profile per input; quarantine ambiguous new training roots.',
        'originalTrainQualified':len(before), 'trainQualifiedEligible':len(train), 'validationQualified':len(val),
        'rows':len(records), 'oldFoldCounts':dict(Counter(r['split'] for r in original)),
        'newQualifiedLabels':dict(additions), 'qualifiedBySplit':dict(Counter(r['split'] for r in records if strict(r))),
        'qualifiedObservations':sum(map(len,observations.values())), 'uniqueQualifiedInputs':sum(bool(v) for v in observations.values()),
        'advisoryObservationsExcluded':dict(advisory), 'materialDisagreementInputs':len(conflicts),
        'quarantinedNewTrainLabels':[r['id'] for r in records if r['split']=='train' and strict(r) and not r['labelProvenance']['eligibleForFitting']],
        'beforeTrainCoverage':coverage(before), 'trainCoverage':coverage(train), 'validationCoverage':coverage(val),
        'retiredFreshCoverage':coverage(fresh), 'retiredFreshQualifiedCoverage':coverage([r for r in records if r['labelProvenance']['origin']=='retired_gen3_fresh' and strict(r)]),
        'trainingColumns':len(train), 'trainingNodes':sum(r['input']['stageCount']+2 for r in train),
        'perColumnStatistics':{k:stats(v) for k,v in metrics.items()},
        'globalFeatureCount':g.shape[1], 'constantGlobalFeatureIndices':np.flatnonzero(np.ptp(g,axis=0)<1e-12).tolist(),
        'validationNearestTrainStandardizedFeatureRms':stats(dist),
        'holdout':{'seed':202609104,'selectionSeed':holdouts.SELECTION_SEED,'rows':len(selected),'benchmarkRows':len(benchmark),
            'candidateRows':len(candidates),'preflightExcluded':len(excluded),'oldInputHashesChecked':len(prior_hashes),'overlapCount':0,
            'selectionUsesOutcomes':False,'solverEvaluated':False,'coverage':coverage(selected)},
        'sources':source_hashes}
    DEST.mkdir(parents=True)
    for name, values in [('cases.jsonl',records),('provenance.jsonl',provenance),('profile-disagreements.jsonl',conflicts),
                         ('fresh-holdout.jsonl',selected),('fresh-benchmark.jsonl',benchmark)]:
        freeze(DEST/name,values,rows=True)
    info['fileSha256'] = {p.name:digest(p) for p in DEST.iterdir()}
    freeze(DEST/'data.json',info)
    print(json.dumps({k:info[k] for k in ('trainQualifiedEligible','validationQualified','newQualifiedLabels','qualifiedBySplit','materialDisagreementInputs','quarantinedNewTrainLabels','trainingNodes')}, indent=2))


if __name__ == '__main__':
    main()
