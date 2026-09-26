"""Apply the exclusion rule to every prepared population and freeze the filtered populations.

    python tools/benchmark-population/classify.py

Reads ``build/benchmark-population/inputs/<population>.jsonl``, the probe verdicts under
``build/benchmark-population/v1/admission/``, and the archived journals declared in ``evidence.py``.  Writes
``tools/benchmark-population/v1/<population>/{exclusions,open-set}.json`` and
``<population>-inputs.jsonl``, plus ``v1/manifest.json`` binding every source hash to every filtered hash.

The rule, applied in this order and recorded per id:

REQUEST_ONLY_TYPED
    The promoted branch's request-only admission types the input INFEASIBLE_SPECIFICATION at the production
    ratio 0.30.  Measured by ``V3RequestAdmissionProbe``, which calls
    ``V3ColumnCalculator.requestOnlyAdmission`` -- the method the production ``calculate`` path calls -- and
    runs no solve.

STATE_GATE_NEVER_SOLVED
    The archived classical control typed the input INFEASIBLE_SPECIFICATION while the retired state-dependent
    heat gates were still publishing that code, AND no archived arm, in any mode or block, reached strict or
    advisory on it.

Advisory-only and never-converged cases are NOT excluded.  They are the open set, written per population to
``open-set.json``.
"""
from __future__ import annotations

import json

import evidence
import population_common as common


REQUEST_ONLY = 'REQUEST_ONLY_TYPED'
STATE_GATE = 'STATE_GATE_NEVER_SOLVED'


def admission(population, label='v1'):
    path = common.OUT / label / 'admission' / population / 'admission.jsonl'
    return {row['id']: row for row in common.rows(path)}, common.sha256_file(path)


def classify(population, label='v1'):
    records, source_sha = common.load_prepared(population)
    ids = [row['id'] for row in records]
    verdicts, admission_sha = admission(population, label)
    if set(verdicts) != set(ids):
        raise SystemExit(f'{population}: the admission probe did not cover the prepared population')

    arms, arm_sources = evidence.arms_for(population)
    arms = {arm: {i: o for i, o in observations.items() if i in set(ids)}
            for arm, observations in arms.items()}
    arms = {arm: observations for arm, observations in arms.items() if observations}
    control_source, control = evidence.classical_control(population)
    missing_control = sorted(set(ids) - set(control))
    if missing_control:
        raise SystemExit(f'{population}: no classical control for {len(missing_control)} ids')

    solved, strict_solved, solving_arms = {}, {}, {}
    for case in ids:
        solving = sorted(arm for arm, observations in arms.items()
                         if observations.get(case, {}).get('solved'))
        strict_arms = sorted(arm for arm, observations in arms.items()
                             if observations.get(case, {}).get('strict'))
        solved[case] = bool(solving)
        strict_solved[case] = bool(strict_arms)
        solving_arms[case] = solving

    exclusions, open_set = [], []
    for case in ids:
        verdict = verdicts[case]
        typed_by_control = control.get(case) == 'INFEASIBLE_SPECIFICATION'
        if verdict['gate'] is not None:
            exclusions.append({
                'id': case, 'reason': REQUEST_ONLY, 'gate': verdict['gate'], 'detail': verdict['detail'],
                'evidenceSource': common.where(common.OUT / label / 'admission' / population
                                               / 'admission.jsonl'),
                'liquidSupplyRatio': verdict['liquidSupplyRatio'],
                'liquidSupplyLimitingTray': verdict['liquidSupplyLimitingTray'],
                'necessaryTier': (verdict['liquidSupplyRatio'] is None
                                  or verdict['liquidSupplyRatio'] >= 1.0),
                'alsoClassicalTypedInfeasible': typed_by_control,
                'alsoNeverSolvedByAnyArm': not solved[case],
            })
            continue
        if typed_by_control and not solved[case]:
            exclusions.append({
                'id': case, 'reason': STATE_GATE, 'gate': 'retiredStateDependentHeatBound',
                'detail': f'classical control status {control[case]}; no archived arm reached strict or advisory',
                'evidenceSource': control_source['path'],
                'archivedArmsChecked': len(arms),
                'solvedByArms': [],
                'strictAnywhere': False,
            })
            continue
        if strict_solved[case]:
            continue
        # Retained, and still unsolved: an advisory-only case, or one nothing has ever converged on.  This is
        # the work the cleaned denominator is honest about, not something to filter away.
        open_set.append({
            'id': case, 'classicalControlStatus': control.get(case),
            'everSolvedAdvisory': solved[case],
            'solvingArms': solving_arms[case][:8],
            'solvingArmCount': len(solving_arms[case]),
            'classicalTypedInfeasible': typed_by_control,
            'liquidSupplyRatio': verdict['liquidSupplyRatio'],
        })

    excluded_ids = {row['id'] for row in exclusions}
    filtered = [row for row in records if row['id'] not in excluded_ids]

    # Secondary reading, for comparability with the published partition: the same state-gate rule with
    # "ever solved" read as strict only, which is how V4_LNN_ONLY_GAP_ANALYSIS.md drew group D1.  D1 counts a
    # case the request-only gate also claims, so the comparable total ignores the precedence order.
    strict_only_all = sorted(case for case in ids
                             if control.get(case) == 'INFEASIBLE_SPECIFICATION' and not strict_solved[case])
    strict_only_state_gate = sorted(case for case in strict_only_all if verdicts[case]['gate'] is None)
    advisory_rescued = sorted(set(strict_only_state_gate) - excluded_ids)
    advisory_evidence = {case: sorted(arm for arm in solving_arms[case]
                                      if not arms[arm][case]['strict'])[:8] for case in advisory_rescued}

    counts = {
        'population': len(ids),
        'excluded': len(exclusions),
        'excludedByReason': {
            REQUEST_ONLY: sum(1 for row in exclusions if row['reason'] == REQUEST_ONLY),
            STATE_GATE: sum(1 for row in exclusions if row['reason'] == STATE_GATE),
        },
        'cleaned': len(filtered),
        'openSet': len(open_set),
        'openSetNeverConverged': sum(1 for row in open_set if not row['everSolvedAdvisory']),
        'openSetAdvisoryOnly': sum(1 for row in open_set if row['everSolvedAdvisory']),
        'strictCeilingOnCleaned': len(filtered) - len(open_set),
        'classicalControlTypedInfeasible': sum(1 for case in ids
                                               if control.get(case) == 'INFEASIBLE_SPECIFICATION'),
        'archivedArms': len(arms),
        'everSolvedAnyArm': sum(1 for case in ids if solved[case]),
        'everStrictAnyArm': sum(1 for case in ids if strict_solved[case]),
        # Published-partition equivalents.  strictOnlyTotal is the gap analysis's group D1 definition
        # ("typed by classical, never strictly solved"), which does not subtract the request-only gate.
        'stateGateUnderStrictOnlyReading': len(strict_only_state_gate),
        'stateGateStrictOnlyTotalIncludingRequestOnly': len(strict_only_all),
        'advisoryRescuedFromStateGate': advisory_rescued,
        'advisoryRescueEvidence': advisory_evidence,
    }

    directory = common.V1 / population
    exclusions_sha = common.write_json(directory / 'exclusions.json', {
        'revision': 'benchmark-population-exclusions-v1', 'population': population,
        'rule': {REQUEST_ONLY: 'V3ColumnCalculator.requestOnlyAdmission types INFEASIBLE_SPECIFICATION '
                               f'at ratio {common.PRODUCTION_SCREEN_RATIO}',
                 STATE_GATE: 'archived classical control typed INFEASIBLE_SPECIFICATION (pre-fix state gates) '
                             'and no archived arm reached strict or advisory'},
        'counts': counts, 'classicalControl': control_source, 'archivedArms': sorted(arms),
        'armSources': arm_sources, 'exclusions': exclusions})
    open_sha = common.write_json(directory / 'open-set.json', {
        'revision': 'benchmark-population-open-set-v1', 'population': population,
        'note': 'Cases kept in the cleaned population that no archived arm has ever solved strictly. '
                'Advisory-only and never-converged cases are deliberately retained; this is the work left.',
        'cleanedPopulation': len(filtered),
        'count': len(open_set),
        'neverConverged': sum(1 for row in open_set if not row['everSolvedAdvisory']),
        'advisoryOnly': sum(1 for row in open_set if row['everSolvedAdvisory']),
        'cases': open_set})
    filtered_sha = common.write_jsonl(directory / f'{population}-inputs.jsonl', filtered)

    return {'counts': counts, 'sourceSha256': source_sha, 'admissionSha256': admission_sha,
            'classicalControl': control_source, 'armSources': arm_sources,
            'files': {'exclusions.json': exclusions_sha, 'open-set.json': open_sha,
                      f'{population}-inputs.jsonl': {'sha256': filtered_sha, 'rows': len(filtered)}},
            'excludedIds': sorted(excluded_ids)}


def main():
    sources = json.loads((common.OUT / 'inputs/sources.json').read_text(encoding='utf-8'))
    manifest = {'revision': 'benchmark-population-manifest-v1',
                'liquidSupplyScreenRatio': common.PRODUCTION_SCREEN_RATIO,
                'rule': {
                    REQUEST_ONLY: "The promoted branch's request-only admission "
                                  '(totalDraw >= totalFeed, V3LiquidSupplyScreen at the production ratio, '
                                  'V3HeatFeasibility.availableCoolingWatts static cooling admission) types '
                                  'the input INFEASIBLE_SPECIFICATION. Measured by '
                                  'V3ColumnCalculator.requestOnlyAdmission; no solve is performed.',
                    STATE_GATE: 'The archived classical control typed the input INFEASIBLE_SPECIFICATION '
                                'while the retired condensationCappedTray / requireCoolingBelowBaseCondenserDuty '
                                'gates still published that code, AND no archived arm, in any mode or block, '
                                'reached strict or advisory on it.',
                    'retained': 'Advisory-only and never-converged cases are not excluded; they are the open '
                                'set recorded in open-set.json.'},
                'populations': {}}
    summary = {}
    for population in common.POPULATIONS:
        result = classify(population)
        manifest['populations'][population] = {
            'source': sources['sources'][population],
            'prepared': {'path': common.where(common.OUT / 'inputs' / f'{population}.jsonl'),
                         'sha256': result['sourceSha256'],
                         'rows': sources['counts'][population]},
            'admissionProbe': {'sha256': result['admissionSha256']},
            'classicalControl': result['classicalControl'],
            'armSources': result['armSources'],
            'counts': result['counts'],
            'files': result['files']}
        summary[population] = result['counts']

    # A certified TRAIN label sitting on an excluded input would mean the training set was fitted to a
    # request production now refuses to solve.  It should be impossible; assert it rather than assume it.
    labels = evidence.certified_label_ids()
    train_exclusions = json.loads((common.V1 / 'train/exclusions.json').read_text(encoding='utf-8'))
    train_excluded = {row['id'] for row in train_exclusions['exclusions']}
    certified = {}
    for name, entry in labels.items():
        collisions = sorted(entry['ids'] & train_excluded)
        certified[name] = {'path': entry['path'], 'sha256': entry['sha256'], 'labels': entry['rows'],
                           'labelsOnExcludedTrainInputs': collisions}
    manifest['certifiedLabelSets'] = certified
    collisions = {name: entry['labelsOnExcludedTrainInputs'] for name, entry in certified.items()
                  if entry['labelsOnExcludedTrainInputs']}
    if collisions:
        raise SystemExit(f'A certified TRAIN label sits on an excluded input: {collisions}. Either the '
                         'exclusion rule is wrong or the training set was fitted to a request production '
                         'refuses; do not write a manifest over it.')
    common.write_json(common.V1 / 'manifest.json', manifest)
    print(json.dumps({'summary': summary,
                      'certifiedLabelCollisions': {k: len(v['labelsOnExcludedTrainInputs'])
                                                   for k, v in certified.items()}}, indent=1))


if __name__ == '__main__':
    main()
