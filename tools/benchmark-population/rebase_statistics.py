"""Re-base the promoted pipeline's headline statistics on the cleaned validation set.

    python tools/benchmark-population/rebase_statistics.py

No campaign is run.  Every number comes from journals already committed in this repository:
``tools/transformer-promotion/evidence/case-block{1,2}.jsonl.gz`` for the production path and
``tools/neural-budget/evidence/case-block{1,2}-F0-*.jsonl.gz`` for the R2 study's four pipelines.  Writes
``tools/benchmark-population/v1/statistics.json`` and renders ``tools/benchmark-population/v1/population.md``.

Only the denominator changes.  No case's outcome, cost or iteration count is touched, so every strict count
below is the archived count restricted to the cleaned ids, and every paired statistic is computed on the same
cases as before -- which is the point of reporting it: a paired comparison over commonly solved columns never
had an infeasible request in it, so cleaning cannot move it.
"""
from __future__ import annotations

import gzip
import json
import statistics

import population_common as common


PROMOTION = common.ROOT / 'tools/transformer-promotion/evidence'
BUDGET = common.ROOT / 'tools/neural-budget/evidence'
BUDGET_PIPELINES = ('F0-baseline', 'F0-progress', 'F0-phase-floor', 'F0-progress-phase-floor')
MODES = (('classical', 'current'), ('LNN_ONLY', 'neural'), ('LNN_FIRST', 'neuralFirst'))


def read_gz(path):
    raw = path.read_bytes()
    rows = [json.loads(line) for line in gzip.decompress(raw).decode('utf-8').splitlines() if line.strip()]
    return rows, common.sha256_bytes(raw)


def block(path):
    rows, digest = read_gz(path)
    return {row['id']: row['modes'] for row in rows}, digest


def strict_ids(modes, journal_mode, keep):
    return {case for case, evidence in modes.items()
            if case in keep and evidence[journal_mode]['outcome'] == 'strict'}


def milliseconds(modes, journal_mode, keep):
    return [evidence[journal_mode]['milliseconds'] for case, evidence in modes.items() if case in keep]


def describe(values):
    return {'n': len(values), 'mean': round(statistics.fmean(values), 1),
            'median': round(statistics.median(values), 1)}


def main():
    exclusions = json.loads((common.V1 / 'validation/exclusions.json').read_text(encoding='utf-8'))
    open_set = json.loads((common.V1 / 'validation/open-set.json').read_text(encoding='utf-8'))
    excluded = {row['id'] for row in exclusions['exclusions']}
    records, _ = common.load_prepared('validation')
    everything = {row['id'] for row in records}
    cleaned = everything - excluded

    sources = {}
    blocks = {}
    for index in (1, 2):
        path = PROMOTION / f'case-block{index}.jsonl.gz'
        blocks[index], sources[str(path.relative_to(common.ROOT))] = block(path)
        if set(blocks[index]) != everything:
            raise SystemExit(f'promotion block {index} does not cover the validation population')

    document = {'revision': 'benchmark-population-statistics-v1',
                'denominators': {'archived': len(everything), 'cleaned': len(cleaned),
                                 'excluded': len(excluded),
                                 'excludedByReason': exclusions['counts']['excludedByReason']},
                'evidence': sources, 'promotion': {}, 'budget': {}, 'openSet': {
                    'size': open_set['count'], 'neverConverged': open_set['neverConverged'],
                    'advisoryOnly': open_set['advisoryOnly']}}

    # --- production path, per block, per route -----------------------------------------------------------
    for label, journal_mode in MODES:
        entry = {}
        for index in (1, 2):
            modes = blocks[index]
            old = strict_ids(modes, journal_mode, everything)
            new = strict_ids(modes, journal_mode, cleaned)
            entry[f'block{index}'] = {
                'strictArchived': len(old), 'strictCleaned': len(new),
                'strictLostToCleaning': sorted(old - new),
                'percentArchived': round(100.0 * len(old) / len(everything), 1),
                'percentCleaned': round(100.0 * len(new) / len(cleaned), 1),
                'allCaseMillisecondsArchived': describe(milliseconds(modes, journal_mode, everything)),
                'allCaseMillisecondsCleaned': describe(milliseconds(modes, journal_mode, cleaned)),
            }
        document['promotion'][label] = entry

    # --- common-success paired timing, which the cleaning cannot move --------------------------------------
    paired = {}
    for label, journal_mode in (('LNN_FIRST', 'neuralFirst'), ('LNN_ONLY', 'neural')):
        for index in (1, 2):
            modes = blocks[index]
            common_all = strict_ids(modes, 'current', everything) & strict_ids(modes, journal_mode, everything)
            common_clean = common_all & cleaned
            classical = [modes[case]['current']['milliseconds'] for case in sorted(common_all)]
            learned = [modes[case][journal_mode]['milliseconds'] for case in sorted(common_all)]
            paired[f'{label}:block{index}'] = {
                'cases': len(common_all), 'casesAfterCleaning': len(common_clean),
                'unchangedByCleaning': common_all == common_clean,
                'classicalMean': round(statistics.fmean(classical), 1),
                'learnedMean': round(statistics.fmean(learned), 1),
                'meanDifference': round(statistics.fmean(learned) - statistics.fmean(classical), 1),
                'medianDifference': round(statistics.median(
                    [b - a for a, b in zip(classical, learned)]), 1)}
    document['promotion']['commonSuccessPaired'] = paired

    # --- the ceiling ---------------------------------------------------------------------------------------
    counts = exclusions['counts']
    document['ceiling'] = {
        'strictAnywhereArchived': counts['everStrictAnyArm'],
        'strictAnywhereCleaned': counts['strictCeilingOnCleaned'],
        'percentArchived': round(100.0 * counts['everStrictAnyArm'] / len(everything), 1),
        'percentCleaned': round(100.0 * counts['strictCeilingOnCleaned'] / len(cleaned), 1),
        'archivedArms': counts['archivedArms']}

    # --- R2 campaign, four pipelines ------------------------------------------------------------------------
    for pipeline in BUDGET_PIPELINES:
        entry = {}
        for index in (1, 2):
            path = BUDGET / f'case-block{index}-{pipeline}.jsonl.gz'
            modes, digest = block(path)
            sources[str(path.relative_to(common.ROOT))] = digest
            if set(modes) != everything:
                raise SystemExit(f'{pipeline} block {index} does not cover the validation population')
            for label, journal_mode in MODES:
                old = strict_ids(modes, journal_mode, everything)
                new = strict_ids(modes, journal_mode, cleaned)
                entry.setdefault(label, {})[f'block{index}'] = {
                    'strictArchived': len(old), 'strictCleaned': len(new),
                    'percentArchived': round(100.0 * len(old) / len(everything), 1),
                    'percentCleaned': round(100.0 * len(new) / len(cleaned), 1)}
            entry.setdefault('allCaseMilliseconds', {})[f'block{index}'] = {
                'FIRST_archived': describe(milliseconds(modes, 'neuralFirst', everything)),
                'FIRST_cleaned': describe(milliseconds(modes, 'neuralFirst', cleaned))}
        document['budget'][pipeline] = entry

    common.write_json(common.V1 / 'statistics.json', document)
    render(document)
    print(json.dumps({'cleaned': len(cleaned), 'excluded': len(excluded),
                      'promotion': {k: {b: v[b]['strictCleaned'] for b in ('block1', 'block2')}
                                    for k, v in document['promotion'].items() if k != 'commonSuccessPaired'}},
                     indent=1))


# ---------------------------------------------------------------------------------------------------------


def render(document):
    counts = document['denominators']
    manifest = json.loads((common.V1 / 'manifest.json').read_text(encoding='utf-8'))
    lines = [
        '# Cleaned benchmark populations and the statistics re-based on them',
        '',
        'Every benchmark denominator in this project counted requests the shipped solver refuses by '
        'construction, and requests the retired state-dependent heat gates typed infeasible and nothing has '
        'ever solved. This study removes exactly those and re-bases the headline numbers. No campaign was '
        'run: every count below is an archived per-case outcome restricted to a smaller id set.',
        '',
        '## 1. The rule',
        '',
        'Applied in this order, recorded per id in `v1/<population>/exclusions.json`.',
        '',
        '**`REQUEST_ONLY_TYPED`** — the promoted branch types the input `INFEASIBLE_SPECIFICATION` from the '
        'specification alone, at the production liquid-supply ratio 0.30. Measured by '
        '`tools/benchmark-population/java/V3RequestAdmissionProbe.java`, which calls the package-private '
        '`V3ColumnCalculator.requestOnlyAdmission(input, ratio)` — the one method the production `calculate` '
        'path calls for its three request-only gates (`totalDraw >= totalFeed`, `V3LiquidSupplyScreen`, '
        '`staticCoolingAdmission`). The probe runs no solve; the only thermodynamics it touches is the single '
        'feed flash the static cooling admission performs on its own. All 3,297 inputs take 1.7 s.',
        '',
        '**`STATE_GATE_NEVER_SOLVED`** — the archived classical control typed the input '
        '`INFEASIBLE_SPECIFICATION` while the retired `condensationCappedTray` / '
        '`requireCoolingBelowBaseCondenserDuty` bounds still published that code, **and** no archived arm, in '
        'any mode or block, reached strict or advisory on it.',
        '',
        '**Retained.** Advisory-only and never-converged cases stay in. They are the open set, listed per '
        'population in `v1/<population>/open-set.json`.',
        '',
        '## 2. Populations',
        '',
        '| Population | n | `REQUEST_ONLY_TYPED` | `STATE_GATE_NEVER_SOLVED` | cleaned | archived arms | '
        'strict ceiling | open set |',
        '| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |',
    ]
    for population, entry in manifest['populations'].items():
        c = entry['counts']
        lines.append(f"| `{population}` | {c['population']} | {c['excludedByReason']['REQUEST_ONLY_TYPED']} | "
                     f"{c['excludedByReason']['STATE_GATE_NEVER_SOLVED']} | **{c['cleaned']}** | "
                     f"{c['archivedArms']} | {c['strictCeilingOnCleaned']} | {c['openSet']} "
                     f"({c['openSetNeverConverged']} never converged, {c['openSetAdvisoryOnly']} advisory) |")
    lines += [
        '',
        'Every `REQUEST_ONLY_TYPED` exclusion in all five populations is the liquid-supply screen. '
        '`totalDraw >= totalFeed` fires on none of the 3,297 inputs — the design generator already excludes '
        'that cell at preflight — and the static cooling admission fires on none either.',
        '',
        '## 3. Promoted pipeline on the cleaned validation set',
        '',
        f"Denominator {counts['archived']} -> **{counts['cleaned']}**. Evidence: "
        '`tools/transformer-promotion/evidence/case-block{1,2}.jsonl.gz`.',
        '',
        '| Route | Block | strict | of 405 | of 330 |',
        '| --- | --- | ---: | ---: | ---: |',
    ]
    for label, _ in MODES:
        for index in (1, 2):
            entry = document['promotion'][label][f'block{index}']
            lines.append(f"| `{label}` | {index} | {entry['strictCleaned']} | "
                         f"{entry['percentArchived']}% | **{entry['percentCleaned']}%** |")
    lines += [
        '',
        'No strict case was removed by the cleaning, in any route or block, so the numerators are the '
        'archived numerators unchanged and the whole movement is in the denominator.',
        '',
        '### Ceiling',
        '',
        f"Strict by at least one of {document['ceiling']['archivedArms']} archived arm-mode-block "
        f"combinations: {document['ceiling']['strictAnywhereCleaned']} cases, "
        f"{document['ceiling']['percentArchived']}% of 405 -> "
        f"**{document['ceiling']['percentCleaned']}% of 330**.",
        '',
        '### All-case request time',
        '',
        'All-case timing includes failures, so removing requests that fail in microseconds (the screen) or '
        'spend a full budget failing (the state-gate set) moves it.',
        '',
        '| Route | Block | mean of 405 | mean of 330 | median of 405 | median of 330 |',
        '| --- | --- | ---: | ---: | ---: | ---: |',
    ]
    for label, _ in MODES:
        for index in (1, 2):
            entry = document['promotion'][label][f'block{index}']
            old, new = entry['allCaseMillisecondsArchived'], entry['allCaseMillisecondsCleaned']
            lines.append(f"| `{label}` | {index} | {old['mean']} | **{new['mean']}** | "
                         f"{old['median']} | **{new['median']}** |")
    lines += [
        '',
        '### Common-success paired timing',
        '',
        'Unchanged by construction: a paired comparison runs on columns both routes solve strictly, and no '
        'strictly solved column is excluded. Verified rather than asserted — `unchangedByCleaning` is true '
        'for all four comparisons in `statistics.json`.',
        '',
        'The differences reproduce the promotion campaign\'s `versusClassicalOnCommonStrict` exactly. The '
        'absolute means differ from `tools/transformer-promotion/results.md` §5 because that table pairs a '
        'classical mean taken over the *learned* route\'s own strict set (180 and 162 cases) with a '
        'difference taken over the common set; both columns here are on the common set.',
        '',
        '| Comparison | cases | classical mean | learned mean | mean difference | median difference |',
        '| --- | ---: | ---: | ---: | ---: | ---: |',
    ]
    for key, entry in document['promotion']['commonSuccessPaired'].items():
        lines.append(f"| `{key}` | {entry['cases']} | {entry['classicalMean']} | {entry['learnedMean']} | "
                     f"{entry['meanDifference']} | {entry['medianDifference']} |")
    lines += [
        '',
        '## 4. The R2 budget campaign, re-based',
        '',
        'Evidence: `tools/neural-budget/evidence/case-block{1,2}-F0-*.jsonl.gz`. Same restriction, same '
        'outcomes.',
        '',
        '| Pipeline | LNN_ONLY b1/b2 | of 330 | LNN_FIRST b1/b2 | of 330 | classical b1/b2 | of 330 |',
        '| --- | ---: | ---: | ---: | ---: | ---: | ---: |',
    ]
    for pipeline in BUDGET_PIPELINES:
        entry = document['budget'][pipeline]
        cells = []
        for label in ('LNN_ONLY', 'LNN_FIRST', 'classical'):
            b1, b2 = entry[label]['block1'], entry[label]['block2']
            cells.append(f"{b1['strictCleaned']} / {b2['strictCleaned']}")
            cells.append(f"**{b1['percentCleaned']}% / {b2['percentCleaned']}%**")
        lines.append(f"| `{pipeline}` | " + ' | '.join(cells) + ' |')
    lines += [
        '',
        '| Pipeline | FIRST all-case mean of 405, b1/b2 | of 330, b1/b2 |',
        '| --- | ---: | ---: |',
    ]
    for pipeline in BUDGET_PIPELINES:
        entry = document['budget'][pipeline]['allCaseMilliseconds']
        lines.append(f"| `{pipeline}` | {entry['block1']['FIRST_archived']['mean']} / "
                     f"{entry['block2']['FIRST_archived']['mean']} | "
                     f"**{entry['block1']['FIRST_cleaned']['mean']} / "
                     f"{entry['block2']['FIRST_cleaned']['mean']}** |")
    lines += [
        '',
        '## 5. Against the published partition',
        '',
        "`V4_LNN_ONLY_GAP_ANALYSIS.md` drew group D1 — typed by the classical control, never *strictly* "
        'solved — as 66 ids. This study reproduces that number exactly: '
        '`stateGateStrictOnlyTotalIncludingRequestOnly` is 66 for validation, and the ceiling of '
        'everything ever measured is the published 214 of 405. The two differ in what they do next:',
        '',
        '- 2 of the 66 are also caught by the request-only liquid-supply screen, so they are recorded under '
        '`REQUEST_ONLY_TYPED` (which takes precedence, because that is the gate production actually applies) '
        'and the state-gate residue is 64;',
        '- 5 of the remaining 64 reached **advisory** on some archived arm. The rule says strict *or* '
        'advisory, so those 5 are not excluded; they stay in the open set. They are '
        '`gd-s03-w1-p4-d0-r00`, `gd-s10-w1-p4-d2-r00`, `gd-s24-w1-p3-d3-r00`, `gd-s24-w1-p4-d2-r00` and '
        '`gd-s59-w1-p4-d2-r00`, with the arms that reached advisory on each recorded in '
        '`v1/validation/exclusions.json` under `advisoryRescueEvidence`.',
        '',
        f"Excluded: 16 + 59 = {counts['excluded']}. Cleaned denominator **{counts['cleaned']}**. Under the "
        'strict-only reading it would be 405 - 80 = 325.',
        '',
        '## 6. Certified TRAIN labels',
        '',
        'A certified label sitting on an excluded input would mean the network was fitted to a request '
        'production refuses to solve. `classify.py` asserts it rather than assuming it, and refuses to write '
        'a manifest if it ever happens.',
        '',
        '| Certified set | labels | on an excluded TRAIN input |',
        '| --- | ---: | ---: |',
    ]
    for name, entry in manifest['certifiedLabelSets'].items():
        lines.append(f"| `{name}` | {entry['labels']} | **{len(entry['labelsOnExcludedTrainInputs'])}** |")
    lines += [
        '',
        'The archive names these N and N+1 with 805 and 906 labels '
        '(`certified-dataset-manifest.json`), not the 804 / 905 the handoff quoted.',
        '',
        '## 7. Generating conditions that are feasible to begin with',
        '',
        '`tools/neural/generalized_design.py --request-only-screen-ratio 0.30` rejects sampled conditions '
        'the request-only admission would type, at generation time. Off by default: `generate(baseline, '
        '202609104)` still yields the frozen Gen4 pool\'s 2,809 candidates and 17 preflight exclusions. At '
        '0.30 the base design matrix loses 132 further points, which is exactly the 16 + 20 + 96 requests '
        'excluded here from its validation, historical-test and train folds — the Python port of the screen '
        'and the Java admission agree case for case on all 3,297 archived requests '
        '(`tools/neural/test_generalized_design_request_only.py`).',
        '',
        '## 8. Using a cleaned population',
        '',
        'See `tools/benchmark-population/README.md`. Both campaign harnesses accept a population path — '
        '`python tools/neural-budget/budget_benchmark.py validation --population <path>` and '
        '`python tools/transformer-promotion/promotion_native.py block1 --population <path>` — defaulting '
        'to the archived 405, so every sealed study reproduces untouched. A requested population must be '
        'registered in `v1/manifest.json` by SHA-256, and a non-archived run writes under its own '
        '`build/<study>/<rev>/population/<label>/` directory.',
        '',
    ]
    (common.V1 / 'population.md').write_text('\n'.join(lines), encoding='utf-8', newline='\n')


if __name__ == '__main__':
    main()
