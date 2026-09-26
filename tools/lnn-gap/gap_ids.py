"""The three target groups this study reports against, written once and committed.

Group B and group C are transcribed from the read-only gap analysis, which derived them from the promotion
run's own journals. Transcription is the risk, so every id is checked here against the promotion evidence
that defines the group: a B case must be LNN_FIRST strict and LNN_ONLY not, in both blocks, and a C case
must be neither classical nor LNN_FIRST strict in either block. The crawling group is not transcribed at
all: it is read from the neural-budget study's committed trace classification.

No solver runs. This is a minute of file reading and it is the reason the groups in the report can be
trusted to mean what the analysis said they mean.
"""
from gap_common import *
import argparse

ANALYSIS = ('.claude/worktrees/cleanup-unused-branches-deee8e/documentation/V4_LNN_ONLY_GAP_ANALYSIS.md')
TRACE = ROOT / 'tools/neural-budget/evidence/trace-trajectories.jsonl.gz'

# Section 2 of the analysis: LNN_FIRST strict on the promoted path, LNN_ONLY not. The classical rescues.
GROUP_B = [
    'gd-s17-w0-p0-d0-r00', 'gd-s17-w0-p1-d1-r00', 'gd-s31-w0-p0-d0-r00', 'gd-s31-w0-p2-d0-r00',
    'gd-s31-w0-p2-d1-r00', 'gd-s38-w0-p0-d2-r00', 'gd-s38-w1-p0-d0-r01', 'gd-s38-w1-p3-d2-r00',
    'gd-s45-w0-p3-d1-r00', 'gd-s45-w1-p0-d0-r01', 'gd-s52-w0-p0-d0-r00', 'gd-s52-w0-p0-d1-r00',
    'gd-s52-w0-p1-d0-r01', 'gd-s52-w1-p0-d0-r00', 'gd-s59-w0-p0-d0-r00', 'gd-s59-w0-p1-d0-r00',
    'gd-s59-w1-p2-d0-r00', 'gd-s59-w1-p4-d1-r00',
]

# Section 3 of the analysis: not LNN_FIRST strict on the promoted path, but strictly solved by at least one
# archived arm. These are the cases some other seed reaches and this one does not.
GROUP_C = [
    'gd-s10-w0-p3-d1-r00', 'gd-s17-w0-p2-d3-r00', 'gd-s24-w0-p2-d0-r00', 'gd-s24-w1-p2-d0-r00',
    'gd-s24-w1-p4-d0-r00', 'gd-s31-w0-p0-d2-r00', 'gd-s31-w0-p1-d1-r00', 'gd-s31-w0-p3-d2-r00',
    'gd-s31-w0-p4-d2-r00', 'gd-s31-w1-p0-d1-r00', 'gd-s31-w1-p1-d1-r00', 'gd-s31-w1-p1-d3-r00',
    'gd-s31-w1-p2-d0-r00', 'gd-s38-w0-p0-d3-r00', 'gd-s38-w0-p1-d1-r00', 'gd-s38-w0-p2-d0-r00',
    'gd-s38-w1-p1-d0-r00', 'gd-s38-w1-p3-d0-r00', 'gd-s45-w0-p2-d1-r00', 'gd-s45-w0-p2-d3-r00',
    'gd-s45-w0-p4-d0-r00', 'gd-s45-w1-p1-d1-r00', 'gd-s45-w1-p4-d2-r00', 'gd-s52-w0-p1-d1-r00',
    'gd-s52-w0-p1-d2-r00', 'gd-s52-w0-p1-d3-r00', 'gd-s52-w1-p3-d0-r00', 'gd-s52-w1-p3-d2-r01',
    'gd-s59-w0-p0-d2-r00', 'gd-s59-w0-p3-d1-r00', 'gd-s59-w0-p4-d3-r00', 'gd-s59-w1-p3-d1-r00',
    'gd-s59-w1-p4-d0-r00', 'gd-s59-w1-p4-d3-r00',
]


def promotion_partition():
    """Strict sets of the promoted path, per mode, intersected over both blocks."""
    stable = {mode: None for mode in STRATEGIES}
    for block in BLOCKS:
        cases = promotion_cases(block)
        for mode in STRATEGIES:
            group = strict_ids(cases, mode)
            stable[mode] = group if stable[mode] is None else stable[mode] & group
    union = {mode: set() for mode in STRATEGIES}
    for block in BLOCKS:
        cases = promotion_cases(block)
        for mode in STRATEGIES:
            union[mode] |= strict_ids(cases, mode)
    return stable, union


def features():
    """Which cases carry a side draw, a steam feed or a stage heat loop, from the case-id schema itself."""
    result = {}
    for row in promotion_cases(1).values():
        condition = row['condition']
        result[row['id']] = bool(condition['sideDrawCount'] or condition['heatLoopCount'] or condition['steam'])
    return result


def build():
    stable, union = promotion_partition()
    crawling = sorted(row['id'] for row in read_rows(TRACE) if row['crawling'])
    carries = features()

    # B: first-strict and not only-strict in both blocks. C: neither classical nor first strict in either.
    checked_b = sorted(i for i in stable['neuralFirst'] if i not in union['neural'])
    checked_c_candidates = {i for i in carries} - union['neuralFirst'] - union['current']

    groups = {
        'group-b': dict(
            ids=sorted(GROUP_B), source=ANALYSIS, definition='LNN_FIRST strict, LNN_ONLY not, both blocks',
            recheckedAgainstPromotionEvidence=sorted(GROUP_B) == checked_b,
            promotionEvidenceIds=checked_b,
            transcriptionOnlyIds=sorted(set(GROUP_B) - set(checked_b)),
            evidenceOnlyIds=sorted(set(checked_b) - set(GROUP_B)),
            rampEligibleIds=sorted(i for i in GROUP_B if carries.get(i)),
            note='The ramp handoff can only apply to a case that carries a draw, steam or stage heat; the '
                 'rest of B is out of its declared scope.'),
        'group-c': dict(
            ids=sorted(GROUP_C), source=ANALYSIS,
            definition='not LNN_FIRST strict on the promoted path, strictly solved by some archived arm',
            promotedPathNeverStrict=sorted(set(GROUP_C) - checked_c_candidates) == [],
            outsidePromotionArchiveIds=sorted(set(GROUP_C) - set(carries)),
            rampEligibleIds=sorted(i for i in GROUP_C if carries.get(i)),
            note='Membership depends on archived arms this study never runs, so only the promoted-path half '
                 'of the definition is rechecked here.'),
        'crawling': dict(
            ids=crawling, source=info(TRACE)['path'],
            definition='not converged at 16 iterations / 2 s, converged at 48 iterations / 6 s',
            note='Read from the neural-budget trace classification, not transcribed.'),
    }
    for name, value in groups.items():
        value['count'] = len(value['ids'])
        freeze(IDS / f'{name}.json', value)
        print(json.dumps({name: {k: v for k, v in value.items() if k != 'ids'}}, indent=1), flush=True)
    overlap = dict(bAndCrawling=sorted(set(GROUP_B) & set(crawling)),
                   cAndCrawling=sorted(set(GROUP_C) & set(crawling)),
                   bAndC=sorted(set(GROUP_B) & set(GROUP_C)))
    freeze(IDS / 'overlap.json', overlap)
    print(json.dumps(overlap), flush=True)


if __name__ == '__main__':
    argparse.ArgumentParser(description='Write the committed target-group id lists.').parse_args()
    build()
