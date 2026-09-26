"""Pure validation and selection rules for the ten-worker checkpoint study."""
import math

from prepare_generalized_evaluation import canonical_input_hash
from prepare_transformer_data import strict, stats
from unified_column_evaluation import summarize

SEEDS = (20260910, 20260911, 20260912)
REFERENCE = 20260911
MODES = ('current', 'neural', 'neuralFirst')
POLICY = {'revision': 'concurrent-column-evaluation-v1', 'mode': 'concurrent-benchmark',
          'workers': 10, 'queueCapacity': 10, 'maximumInFlight': 10,
          'deadlineMillis': 30000, 'neuralBudgetMillis': 2000, 'neuralMaximumIterations': 16}


def finite_nonnegative(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) and value >= 0


def validate_run(rows, meta, source, model_sha, source_sha):
    expected = {str(r['id']): canonical_input_hash(r['input']) for r in source}
    actual = {str(r['id']): canonical_input_hash(r['input']) for r in rows}
    if len(expected) != len(source) or len(rows) != len(source) or len(actual) != len(rows) or actual != expected:
        raise ValueError('Incomplete, duplicate or mismatched evaluation population')
    if any(meta.get(k) != value for k, value in POLICY.items()):
        raise ValueError('Mismatched concurrent evaluation policy')
    if (meta.get('complete') is not True or meta.get('completed') != len(source)
            or meta.get('caseCount') != len(source) or meta.get('modelSha256') != model_sha
            or meta.get('sourceSha256') != source_sha or meta.get('strategies') != list(MODES)):
        raise ValueError('Incomplete or unfrozen evaluation')
    scheduling = meta.get('scheduling') or {}
    if (scheduling.get('terminated') is not True or scheduling.get('submitted') != len(source)
            or scheduling.get('completed') != len(source)
            or not 1 <= scheduling.get('maximumInFlight', 0) <= 10
            or not 1 <= scheduling.get('maximumActive', 0) <= 10
            or not 1 <= scheduling.get('distinctWorkerThreads', 0) <= 10):
        raise ValueError('Invalid worker lifecycle evidence')
    if len(source) >= 10 and scheduling['distinctWorkerThreads'] != 10:
        raise ValueError('The registered ten workers were not used')
    for row in rows:
        if row.get('status') != 'BENCHMARKED' or not finite_nonnegative(row.get('queueWaitMillis')):
            raise ValueError('Missing case execution evidence')
        for mode in MODES:
            result = row.get(mode)
            if (not isinstance(result, dict) or not isinstance(result.get('success'), bool)
                    or not isinstance(result.get('status'), str) or not finite_nonnegative(result.get('ms'))):
                raise ValueError('Missing strategy or invalid elapsed time')
            for metric in ('cpuMillis', 'allocatedBytes'):
                if result.get(metric) is not None and not finite_nonnegative(result[metric]):
                    raise ValueError('Invalid optional measurement')
            strict({**row, **result})


def analyze(rows):
    variants = {m: [{**r, **r[m]} for r in rows] for m in MODES}
    ids = {m: sorted(r['id'] for r in variants[m] if strict(r)) for m in MODES}
    common = [r for r in rows if r['id'] in set(ids['current']) & set(ids['neuralFirst'])]
    paired = {'firstGains': sorted(set(ids['neuralFirst']) - set(ids['current'])),
              'firstLosses': sorted(set(ids['current']) - set(ids['neuralFirst'])),
              'firstMinusCurrentMillis': stats([r['neuralFirst']['ms'] - r['current']['ms'] for r in rows]),
              'commonQualifiedCases': len(common)}
    if common:
        paired['firstMinusCurrentMillisCommonQualified'] = stats([r['neuralFirst']['ms'] - r['current']['ms'] for r in common])
    return {'strategies': {m: summarize(v) for m, v in variants.items()}, 'qualifiedIds': ids,
            'pairedAgainstCurrent': paired, 'queueWaitMillis': stats([r['queueWaitMillis'] for r in rows])}


def choose(records):
    """Records contain certified sets and unrounded all-case elapsed means."""
    if set(records) != set(SEEDS):
        raise ValueError('Selection requires all three registered checkpoints')
    reference = records[REFERENCE]
    classical = set().union(*(set(r['currentIds']) for r in records.values()))
    reference_ids = set(reference['firstIds'])
    for value in records.values():
        if not finite_nonnegative(value['firstMeanMillis']):
            raise ValueError('Nonfinite selection latency')
    decisions = {}
    for seed, value in records.items():
        first = set(value['firstIds'])
        gains, losses = first - reference_ids, reference_ids - first
        more = len(first) > len(reference_ids)
        preserves = classical <= first
        latency = value['firstMeanMillis'] <= reference['firstMeanMillis']
        decisions[str(seed)] = {'isReference': seed == REFERENCE, 'qualified': len(first),
            'firstMeanMillis': value['firstMeanMillis'], 'moreQualifications': more,
            'preservesClassicalUnion': preserves, 'noMeanLatencyRegression': latency,
            'missingClassicalIds': sorted(classical - first), 'gainedReferenceIds': sorted(gains),
            'lostReferenceIds': sorted(losses), 'eligible': seed != REFERENCE and more and preserves and latency}
    eligible = [s for s in SEEDS if decisions[str(s)]['eligible']]
    ranking = sorted(eligible, key=lambda s: (-decisions[str(s)]['qualified'], records[s]['firstMeanMillis'], s))
    return {'selectedSeed': ranking[0] if ranking else REFERENCE, 'referenceSeed': REFERENCE,
            'eligibleRanking': ranking, 'retainedReference': not ranking, 'decisions': decisions,
            'classicalUnionIds': sorted(classical),
            'classicalControlDisagreements': sorted(classical - set.intersection(*(set(r['currentIds']) for r in records.values())))}


def test_gate(selection, plan_sha, test_sha, models):
    if not selection or selection.get('validationComplete') is not True or selection.get('testUsed') is not False:
        raise ValueError('Complete and freeze validation selection before testing')
    if selection.get('planSha256') != plan_sha or selection.get('testSha256') != test_sha:
        raise ValueError('Selection belongs to a different plan or test')
    seed = selection.get('decision', {}).get('selectedSeed')
    if seed not in SEEDS or selection.get('selectedModelSha256') != models[str(seed)]['sha256']:
        raise ValueError('Selected artifact differs from registered candidate')
    return seed


def test_seeds(selected, models):
    result, seen = [], set()
    for seed in (REFERENCE, selected):
        checksum = models[str(seed)]['sha256']
        if checksum not in seen:
            result.append(seed); seen.add(checksum)
    return result


def validate_numerical_check(report, fixtures):
    numerical = report.get('numerical') or {}
    actual = numerical.get('cases') or []
    expected = {r['id']:canonical_input_hash(r['input']) for r in fixtures}
    observed = {r['id']:canonical_input_hash(r['input']) for r in actual}
    if (report.get('allPassed') is not True or numerical.get('passed') is not True
            or numerical.get('distinctWorkerThreads') != 10 or numerical.get('maximumActiveSolverCalls') != 10
            or numerical.get('executorTerminated') is not True or len(fixtures) != 10
            or len(actual) != 10 or len(observed) != 10 or observed != expected
            or any(r.get('passed') is not True or r.get('differingProfileValues') != 0 for r in actual)):
        raise ValueError('Native numerical certification is not bound to the ten TRAIN fixtures')


def paired_models(reference_rows, candidate_rows):
    reference = {r['id']: r for r in reference_rows}
    candidate = {r['id']: r for r in candidate_rows}
    if set(reference) != set(candidate):
        raise ValueError('Paired models require the same inputs')
    result = {}
    for mode in MODES:
        a = {i for i, r in reference.items() if strict({**r, **r[mode]})}
        b = {i for i, r in candidate.items() if strict({**r, **r[mode]})}
        result[mode] = {'gainedIds': sorted(b-a), 'lostIds': sorted(a-b),
            'candidateMinusReferenceMillis': stats([candidate[i][mode]['ms'] - reference[i][mode]['ms'] for i in sorted(reference)])}
    return result
