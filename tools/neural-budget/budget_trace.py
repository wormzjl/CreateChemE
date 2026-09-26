"""Run the bounded correction-trajectory diagnostic and classify its cases by the declared rules.

Every threshold below is stated in protocol.md and was registered before the diagnostic ran. This module
applies them; it does not choose them, and it runs no campaign. The classification is read out of the
per-iteration residual history of the attempt each configuration ended on.
"""
from budget_common import *
from budget_native import execute
from budget_register import verify_plan
import argparse

# Declared in protocol.md before the diagnostic ran.
CONTRACTION_WINDOW = 8
STALL_RATIO = 0.5
HOPELESS_RATIO = 0.9
CRAWLING_CASES_FOR_EXTENSION = 10
STALLED_FRACTION_FOR_ABORT = 0.25
REINSERTING_FRACTION_FOR_FLOOR = 0.25


def invoke(config):
    plan = verify_plan()
    settings = plan['traceConfigurations'][config]
    directory = trace_directory(config)
    if directory.exists():
        print(f'Reusing completed diagnostic {directory}', flush=True)
        return
    execute('V3BudgetTraceProbe',
            [directory, INPUTS / 'trace-inputs.jsonl', pipeline_path('F0-baseline'),
             WORKERS, DEADLINE_SECONDS, settings['budgetMillis'], settings['maximumIterations'],
             INPUTS / 'warmup.json'],
            OUT / 'logs' / f'trace-{config}.log')
    meta = read(directory / 'run.json')
    assert meta['complete'] and meta['scheduling']['distinctWorkerThreads'] == WORKERS
    assert meta['neuralBudgetMillis'] == settings['budgetMillis']
    assert meta['neuralMaximumIterations'] == settings['maximumIterations']
    print(json.dumps(dict(config=config, cases=meta['completed'], seconds=meta['elapsedSeconds'])), flush=True)


def load(config):
    directory = trace_directory(config)
    rows = {row['id']: row for row in read_rows(directory / 'trace.jsonl')}
    meta = read(directory / 'run.json')
    assert meta['complete'] and len(rows) == meta['completed']
    return rows, meta


def converged(row):
    """The study's own strict definition, applied to a traced request exactly as to a campaign request."""
    if not row.get('success'):
        return False
    return strict(row)


def terminal(row):
    attempts = row.get('attempts') or []
    return attempts[-1] if attempts else None


def ratio(residuals, window=CONTRACTION_WINDOW):
    """Contraction over the declared window: the last residual divided by the one `window` steps earlier."""
    if len(residuals) <= window:
        return None
    earlier = residuals[-1 - window]
    if not earlier:
        return None
    return residuals[-1] / earlier


def evidence(row):
    attempt = terminal(row)
    residuals = (attempt or {}).get('maximumScaledResiduals') or []
    attempts = row.get('attempts') or []
    return dict(
        id=row['id'], condition=row['condition'], status=row.get('status'), success=bool(row.get('success')),
        converged=converged(row), milliseconds=row['milliseconds'], cpuMillis=row.get('cpuMillis'),
        seedAvailable=row['seedAvailable'],
        seedZeroPhases=(row.get('seedSupport') or {}).get('zeroPhases'),
        initialProjectedResidual=row.get('initialProjectedResidual'),
        publishedNewtonIterations=row.get('publishedNewtonIterations'),
        attemptCount=len(attempts),
        wetPrepass=any(a['label'] == 'wet-prepass' for a in attempts),
        supportRefreshes=max((a['supportRefreshes'] for a in attempts), default=-1),
        wetTrayRefreshes=max((a['wetTrayRefreshes'] for a in attempts), default=-1),
        retentionDeltas=[dict(attempt=a['attempt'], label=a['label'], retainedPoints=a['retainedPoints'],
                              totalPoints=a['totalPoints'], wetTrayCount=a['wetTrayCount'],
                              iterationBudget=a['iterationBudget'], stopCode=a['stopCode'],
                              iterations=a['iterations'], milliseconds=a['milliseconds'],
                              retentionChange=(a['retainedPoints'] - attempts[i - 1]['retainedPoints']
                                               if i and a['retainedPoints'] >= 0
                                               and attempts[i - 1]['retainedPoints'] >= 0 else None))
                         for i, a in enumerate(attempts)],
        totalIterations=sum(max(a['iterations'], 0) for a in attempts),
        terminalStopCode=(attempt or {}).get('stopCode'),
        terminalInterrupted=(attempt or {}).get('interrupted'),
        terminalIterations=(attempt or {}).get('iterations'),
        terminalResiduals=residuals,
        terminalIterationElapsedMillis=(attempt or {}).get('iterationElapsedMillis') or [],
        terminalMilliseconds=(attempt or {}).get('milliseconds'),
        terminalInitialResidual=residuals[0] if residuals else None,
        terminalFinalResidual=residuals[-1] if residuals else None,
        contractionRatio=ratio(residuals),
        shortTrajectory=len(residuals) <= CONTRACTION_WINDOW,
        millisPerIteration=row['milliseconds'] / max(1, sum(max(a['iterations'], 0) for a in attempts)))


def classify(production, diagnostic, omissions):
    """The declared per-case classification. Groups are not exclusive and every case reports all of them."""
    result = {}
    for case, base in production.items():
        wide = diagnostic[case]
        reference = omissions.get(case)
        crawling = not base['converged'] and wide['converged']
        stalled = not wide['converged'] and wide['contractionRatio'] is not None \
            and wide['contractionRatio'] > STALL_RATIO
        hopeless = not wide['converged'] and wide['contractionRatio'] is not None \
            and wide['contractionRatio'] > HOPELESS_RATIO
        reinserting = base['supportRefreshes'] >= 1 and (
            (reference or {}).get('zeroAllowedPhaseOmissions', 0) + (reference or {}).get('positivePhaseComponentOmissions', 0) >= 1
            if reference else (base['seedZeroPhases'] or 0) >= 1)
        result[case] = dict(
            crawling=crawling, stalled=stalled, hopeless=hopeless, reinserting=reinserting,
            referenceProfile=reference is not None,
            unclassified=not (crawling or stalled or reinserting) and not wide['converged'],
            production=base, diagnostic=wide, referenceOmissions=reference)
    return result


def decide(classified, groups):
    """Apply the declared decision rules to the capped population."""
    capped = sorted(set(groups['iteration-capped']) | set(groups['time-capped']))
    present = [case for case in capped if case in classified]
    crawling = [case for case in present if classified[case]['crawling']]
    stalled = [case for case in present if classified[case]['stalled']]
    reinserting = [case for case in present if classified[case]['reinserting']]
    zero_phases = sorted(classified[case]['production']['seedZeroPhases'] or 0 for case in present)
    median_zero = zero_phases[len(zero_phases) // 2] if zero_phases else 0
    extend = len(crawling) >= CRAWLING_CASES_FOR_EXTENSION
    abort = len(present) > 0 and len(stalled) / len(present) >= STALLED_FRACTION_FOR_ABORT
    floor = len(present) > 0 and len(reinserting) / len(present) >= REINSERTING_FRACTION_FOR_FLOOR and median_zero >= 1
    return dict(
        cappedCases=len(present), crawling=len(crawling), crawlingIds=crawling,
        stalled=len(stalled), stalledFraction=len(stalled) / len(present) if present else None,
        hopeless=sum(classified[case]['hopeless'] for case in present),
        reinserting=len(reinserting), reinsertingFraction=len(reinserting) / len(present) if present else None,
        medianSeedZeroPhases=median_zero,
        rules=dict(contractionWindow=CONTRACTION_WINDOW, stallRatio=STALL_RATIO, hopelessRatio=HOPELESS_RATIO,
                   crawlingCasesForExtension=CRAWLING_CASES_FOR_EXTENSION,
                   stalledFractionForAbort=STALLED_FRACTION_FOR_ABORT,
                   reinsertingFractionForFloor=REINSERTING_FRACTION_FOR_FLOOR),
        selectsProgressExtension=extend, selectsProgressAbort=abort, selectsPhaseFloor=floor,
        selected=sorted({name for name, fired in (('A-extend', extend), ('A-abort', abort), ('B-phase-floor', floor))
                         if fired}) or ['A-abort-only-fallback'])


def cost(production):
    """Measured per-iteration correction cost, the input to the declared time-budget arithmetic."""
    values = [row['millisPerIteration'] for row in production.values() if row['totalIterations'] > 0]
    by_stage = {}
    for row in production.values():
        if row['totalIterations'] <= 0:
            continue
        by_stage.setdefault(str(row['condition']['stageCount']), []).append(row['millisPerIteration'])
    return dict(millisPerIteration=stats(values) if values else None,
                millisPerIterationByStageCount={key: stats(value) for key, value in sorted(by_stage.items())},
                caseMilliseconds=stats([row['milliseconds'] for row in production.values()]))


def analyse():
    plan = verify_plan()
    targets = read(IDS / 'trace-targets.json')
    groups = {group: id_list(group) for group in ('iteration-capped', 'time-capped', 'classical-only-loss')}
    frames, metadata = {}, {}
    for config in TRACE_CONFIGS:
        rows, meta = load(config)
        assert set(rows) == set(targets['ids']), config
        frames[config] = {case: evidence(row) for case, row in rows.items()}
        metadata[config] = {key: meta[key] for key in ('elapsedSeconds', 'scheduling', 'neuralBudgetMillis',
                                                       'neuralMaximumIterations', 'java', 'availableProcessors')}
    classified = classify(frames['production'], frames['diagnostic'], targets['seedOmissionEvidence'])
    decision = decide(classified, groups)
    result = dict(
        revision='neural-budget-trace-analysis-v1', studyPlan=info(OUT / 'study-plan.json'),
        traceTargets=info(IDS / 'trace-targets.json'), configurations=TRACE_CONFIGS, runMetadata=metadata,
        cases=len(classified), groups={group: len(value) for group, value in groups.items()},
        convergedByConfiguration={config: sum(row['converged'] for row in frame.values())
                                  for config, frame in frames.items()},
        decision=decision, cost=cost(frames['production']),
        perCase=classified,
        interpretation='Outcome-selected diagnostic groups, not a population benchmark. The diagnostic budget '
                       'changes only the two walls; every tolerance, support rule and audit is the production one.',
        newCampaignRequests=0)
    freeze(OUT / 'trace-analysis.json', result)
    print(json.dumps(dict(cases=result['cases'], converged=result['convergedByConfiguration'],
                          decision={key: decision[key] for key in ('cappedCases', 'crawling', 'stalled',
                                                                   'reinserting', 'selected')})), flush=True)
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['production', 'diagnostic', 'analyse'])
    mode = parser.parse_args().mode
    analyse() if mode == 'analyse' else invoke(mode)
