"""The declared net-time arithmetic that chooses intervention A's parameters. Runs no solver.

protocol.md section 5 states the rule: before the campaign is registered, the extension's predicted cost
and the abort's predicted saving are computed from the production-configuration trace, and the candidate
is registered at the first setting in the declared ladder whose predicted saving is at least its
predicted cost. This module is that arithmetic and nothing else; the ladder, the window, the factors and
the floor were all fixed before the diagnostic ran.

Cost and saving both use the case's own measured milliseconds per iteration, as declared. The abort is
simulated on the production trace, because that is the time the wall actually spends today. The extension
is simulated on the diagnostic trace, because only that run recorded what the residual does past
iteration sixteen; an attempt that the abort would stop before the cap never reaches the extension and
contributes no cost.
"""
from budget_common import *
from budget_trace import CONTRACTION_WINDOW, load
import argparse

TOLERANCE = 1.0e-8
# Declared in protocol.md section 5, in order.
LADDER = [dict(step=1, extensionCap=48, contractionFactor=0.5),
          dict(step=2, extensionCap=32, contractionFactor=0.5),
          dict(step=3, extensionCap=32, contractionFactor=0.25),
          dict(step=4, extensionCap=24, contractionFactor=0.25),
          dict(step=5, extensionCap=16, contractionFactor=None)]
# The stall stop of intervention A, as declared.
STALL_WINDOW, STALL_FACTOR, STALL_FLOOR = 8, 0.9, 1.0e-6
BASE_ITERATIONS = 16
BLOCK = 8
# The production wall, plus the two modest variants the coordinator allowed, admitted only if the same
# saving-covers-cost rule holds for them.
WALLS = [2000, 2500, 3000]


def abort_point(residuals):
    """First iteration the declared stall rule would stop at, or None if it never fires."""
    for index in range(STALL_WINDOW, len(residuals)):
        earlier = residuals[index - STALL_WINDOW]
        if residuals[index] > STALL_FLOOR and earlier and residuals[index] > STALL_FACTOR * earlier:
            return index
    return None


def granted(residuals, cap, factor):
    """Iterations the extension would add beyond the base cap, from the recorded residual history."""
    if factor is None:
        return 0
    total = 0
    boundary = BASE_ITERATIONS
    while boundary < cap:
        if boundary >= len(residuals):
            break
        earlier = residuals[boundary - CONTRACTION_WINDOW]
        if residuals[boundary] <= TOLERANCE or not earlier:
            break
        if residuals[boundary] > factor * earlier:
            break
        total += min(BLOCK, cap - boundary)
        boundary += BLOCK
    return total


def attempts(rows, case):
    return [a for a in (rows[case].get('attempts') or []) if a['label'] == 'attempt']


def evaluate(production, diagnostic, capped, cap, factor, wall):
    cost, saving, extended, aborted, aborted_iterations = 0.0, 0.0, 0, 0, 0
    for case in capped:
        row = production[case]
        rate = row['milliseconds'] / max(1, sum(max(a['iterations'], 0) for a in attempts(production, case)))
        remaining = max(0.0, wall - row['milliseconds'])
        case_cost = 0.0
        for attempt in attempts(production, case):
            residuals = attempt['maximumScaledResiduals']
            stop = abort_point(residuals)
            last = len(residuals) - 1
            if stop is not None and stop < last:
                saving += (last - stop) * rate
                aborted += 1
                aborted_iterations += last - stop
                continue
            if attempt['stopCode'] != 'MAX_ITERATIONS':
                continue
            wide = next((w for w in attempts(diagnostic, case) if w['attempt'] == attempt['attempt']), None)
            if wide is None:
                continue
            blocks = granted(wide['maximumScaledResiduals'], cap, factor)
            if blocks:
                extended += 1
            case_cost += blocks * rate
        cost += min(case_cost, remaining)
    return dict(extensionCap=cap, contractionFactor=factor, wallMillis=wall,
                predictedCostMillis=cost, predictedSavingMillis=saving, netMillis=saving - cost,
                paysForItself=saving >= cost, extendedAttempts=extended, abortedAttempts=aborted,
                abortedIterations=aborted_iterations,
                perCaseCostMillis=cost / len(capped), perCaseSavingMillis=saving / len(capped))


def crawling_reach(production, diagnostic, crawling, walls):
    """Whether a recovered case could have been recovered inside each wall, from its measured cost."""
    result = {}
    for wall in walls:
        inside = [case for case in crawling if diagnostic[case]['milliseconds'] <= wall]
        result[str(wall)] = dict(cases=len(inside), ids=sorted(inside))
    return dict(reachableWithinWall=result,
                diagnosticMillis=stats([diagnostic[case]['milliseconds'] for case in crawling]),
                productionMillis=stats([production[case]['milliseconds'] for case in crawling]),
                diagnosticIterations=stats([sum(max(a['iterations'], 0) for a in attempts(diagnostic, case))
                                            for case in crawling]))


def main():
    analysis = read(OUT / 'trace-analysis.json')
    production, _ = load('production')
    diagnostic, _ = load('diagnostic')
    capped = sorted(set(id_list('iteration-capped')) | set(id_list('time-capped')))
    crawling = analysis['decision']['crawlingIds']
    grid = [evaluate(production, diagnostic, capped, entry['extensionCap'], entry['contractionFactor'], wall)
            for wall in WALLS for entry in LADDER]
    chosen = next((row for row in grid if row['wallMillis'] == WALLS[0] and row['paysForItself']), None)
    wall_variants = [row for row in grid if row['wallMillis'] != WALLS[0] and row['paysForItself']
                     and chosen is not None and row['extensionCap'] == chosen['extensionCap']
                     and row['contractionFactor'] == chosen['contractionFactor']]
    result = dict(
        revision='neural-budget-ladder-v1', studyPlan=info(OUT / 'study-plan.json'),
        analysis=info(OUT / 'trace-analysis.json'), cappedCases=len(capped),
        rules=dict(ladder=LADDER, stallWindow=STALL_WINDOW, stallFactor=STALL_FACTOR, stallFloor=STALL_FLOOR,
                   contractionWindow=CONTRACTION_WINDOW, baseIterations=BASE_ITERATIONS, block=BLOCK,
                   walls=WALLS, tolerance=TOLERANCE),
        grid=grid, chosen=chosen, admissibleWallVariants=wall_variants,
        crawling=crawling_reach(production, diagnostic, crawling, WALLS),
        note='Predicted from recorded trajectories at each case\'s own measured per-iteration cost. The '
             'campaign measures the real cost; this only keeps a candidate from being registered in a form '
             'the pooled-mean latency gate obviously cannot pass.')
    freeze(OUT / 'ladder.json', result)
    print(json.dumps(dict(chosen=chosen, admissibleWallVariants=[row['wallMillis'] for row in wall_variants],
                          reachable={wall: value['cases'] for wall, value
                                     in result['crawling']['reachableWithinWall'].items()}), indent=1), flush=True)
    for row in grid:
        print(json.dumps({k: (round(v, 1) if isinstance(v, float) else v) for k, v in row.items()}), flush=True)


if __name__ == '__main__':
    argparse.ArgumentParser().parse_args()
    main()
