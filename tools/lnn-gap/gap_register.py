"""Registration: the population, the arms, the block order and the production delta, frozen before any run.

Registration happens once and cannot be rewritten, so the campaign cannot choose its own arms, its own
order or its own population after seeing a result. Two things are checked here rather than asserted in
prose: that the four production files this study touches are the only ones that differ from the promotion
commit, and that the population it is about to measure is the one it recorded.
"""
from gap_common import *
from datetime import datetime, timezone
import argparse
import subprocess

# The promotion commit this study's production delta is measured against.
PROMOTION_BASE = '7c7d88e28e20baeb80bcbea4c4bf171631fc29b1'

# Every production file this study is allowed to have changed. A delta outside this set means the campaign
# is measuring something it did not register, and registration fails rather than explaining it afterwards.
ALLOWED_SOURCE_DELTA = {
    'src/main/java/com/wormzjl/createcheme/science/column/v3/V3AnchorTransformerInitializer.java',
    'src/main/java/com/wormzjl/createcheme/science/column/v3/V3ColumnCalculator.java',
    'src/main/java/com/wormzjl/createcheme/science/column/v3/V3InitializationOptions.java',
    'src/main/java/com/wormzjl/createcheme/science/column/v3/V3NeuralModels.java',
}


def git(*args):
    return subprocess.run(['git', *args], cwd=ROOT, check=True, capture_output=True, text=True).stdout


def source_delta():
    changed = sorted(p for p in git('diff', '--name-only', f'{PROMOTION_BASE}..HEAD', '--',
                                    'src/main/java', 'src/main/resources').splitlines() if p.strip())
    unexpected = sorted(set(changed) - ALLOWED_SOURCE_DELTA)
    assert not unexpected, f'Production files changed outside the registered delta: {unexpected}'
    return dict(base=PROMOTION_BASE, head=git('rev-parse', 'HEAD').strip(), changed=changed,
                allowed=sorted(ALLOWED_SOURCE_DELTA),
                note='The bundled weight artifact is not in this list, and the registration asserts its '
                     'SHA-256 separately: no arm may move a weight byte.')


def population(path):
    path = Path(path).resolve()
    rows = read_rows(path)
    ids = [row['id'] for row in rows]
    assert len(set(ids)) == len(ids), 'The cleaned population repeats a case identifier'
    archived = set(promotion_cases(1))
    return dict(absolutePath=str(path), sha256=digest(path), cases=len(ids), ids=sorted(ids),
                insidePromotionArchive=len(set(ids) & archived),
                outsidePromotionArchiveIds=sorted(set(ids) - archived),
                droppedFromPromotionArchiveIds=sorted(archived - set(ids)))


def register(path, arms):
    assert REFERENCE in arms, 'Every round carries the baseline arm; there is nothing to pair against otherwise'
    specs = {name: spec(name) for name in arms}
    OUT.mkdir(parents=True, exist_ok=True)
    for name in arms:
        manifest = dict(pipeline=name, **specs[name])
        target = arm_path(name)
        if target.exists():
            assert read(target) == manifest, f'Arm {name} was registered with different values'
        else:
            freeze(target, manifest)
    # Reversed arm order between the blocks, so a systematic drift in machine load cannot be read as a
    # difference between arms.
    order = [list(arms), list(reversed(arms))]
    plan_value = dict(
        revision='lnn-gap-registration-v1', studyRevision=REVISION,
        createdUtc=datetime.now(timezone.utc).isoformat(),
        protocol=info(STUDY / 'protocol.md'),
        frozen=verify_frozen(), population=population(path), sourceDelta=source_delta(),
        arms={name: dict(pipeline=name, manifest=info(arm_path(name)), **specs[name]) for name in arms},
        orderByBlock=order, blocks=BLOCKS, workers=WORKERS, requestDeadlineSeconds=DEADLINE_SECONDS,
        heapBytes=HEAP_BYTES, strategies=STRATEGIES, reference=REFERENCE,
        groups={name: info(IDS / f'{name}.json') for name in ('group-b', 'group-c', 'crawling')},
        parityTarget=dict(evidence=[info(PROMOTION_EVIDENCE / f'case-block{b}.jsonl.gz') for b in BLOCKS]
                          + [info(PROMOTION_EVIDENCE / 'decode-seed-digests.jsonl.gz')],
                          rule='the baseline arm reproduces the promotion run\'s classical, LNN_ONLY and '
                               'LNN_FIRST strict identity sets, restricted to the kept ids, in both blocks, '
                               'and its first decoded candidate reproduces the promotion seed digests'),
        measuredRequests=len(arms) * len(BLOCKS) * len(STRATEGIES),
        note='Requests are per case; multiply by the population size.')
    target = OUT / 'study-plan.json'
    if target.exists():
        existing = read(target)
        for key in ('arms', 'orderByBlock', 'population', 'sourceDelta', 'protocol'):
            assert existing[key] == plan_value[key], f'Registration already exists and differs in {key}'
        print(f'Verified existing registration {target}', flush=True)
    else:
        freeze(target, plan_value)
    print(json.dumps(dict(arms=list(arms), cases=plan_value['population']['cases'],
                          outside=len(plan_value['population']['outsidePromotionArchiveIds']),
                          dropped=len(plan_value['population']['droppedFromPromotionArchiveIds']),
                          sourceDelta=plan_value['sourceDelta']['changed']), indent=1), flush=True)
    return plan_value


def verify_plan():
    """Re-read the registration and re-check everything it can still check."""
    value = plan()
    assert value['revision'] == 'lnn-gap-registration-v1'
    assert digest(BUNDLED_ARTIFACT) == BASE_WEIGHTS_SHA, 'The bundled artifact moved after registration'
    assert digest(value['population']['absolutePath']) == value['population']['sha256'], 'The population moved'
    assert digest(STUDY / 'protocol.md') == value['protocol']['sha256'], 'The protocol moved after registration'
    for name, entry in value['arms'].items():
        assert read(arm_path(name)) == dict(pipeline=name, **spec(name)), f'Arm {name} drifted from its manifest'
        assert digest(arm_path(name)) == entry['manifest']['sha256']
    assert source_delta()['changed'] == value['sourceDelta']['changed'], 'The production delta changed'
    return value


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['register', 'verify'])
    parser.add_argument('--population', help='cleaned validation population, one JSON request per line')
    parser.add_argument('--arms', nargs='*', default=ROUND_ONE)
    parsed = parser.parse_args()
    if parsed.mode == 'register':
        if not parsed.population:
            raise SystemExit('register needs --population')
        register(parsed.population, parsed.arms)
    else:
        print(json.dumps({k: v for k, v in verify_plan().items() if k not in ('population', 'arms')}, indent=1))
