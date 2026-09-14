"""LNN-gap follow-up primitives.

The promotion campaign settled what the mod ships. This study asks what the same shipped weights cannot
solve, and whether a solver or decoder rule — never a weight, never a tolerance, never an audit — reaches
any of it. Every arm below differs from the promoted baseline in exactly one registered field.

Predecessor artifacts are read only and bound by the SHA-256 their registrations recorded. The population
is not frozen in this module: it is a parameter, because the campaign runs on a cleaned validation set
built separately, and its identity is pinned at registration time rather than here.
"""
import gzip
import hashlib
import json
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

STUDY = ROOT / 'tools/lnn-gap'
IDS = STUDY / 'ids'
EVIDENCE = STUDY / 'evidence'
# v1 is round one, the seven arms. v2 re-measures the one arm whose registered option combination turned
# out to hit a solver defect rather than its intervention, on a core carrying the fix and with its own
# re-run baseline. v3 is the combination round the declared rules select.
REVISION = os.environ.get('LNN_GAP_REVISION', 'v1')
assert REVISION in ('v1', 'v2', 'v3'), REVISION
OUT = ROOT / f'build/lnn-gap/{REVISION}'

# The promotion campaign's committed per-case evidence and decoded-seed digests; read only. These are the
# parity gate: the baseline arm must reproduce them, restricted to the ids the cleaned population keeps.
PROMOTION = ROOT / 'tools/transformer-promotion'
PROMOTION_EVIDENCE = PROMOTION / 'evidence'
PROMOTION_INPUTS = PROMOTION / 'inputs'

BASE_NAME = 'F-20260911-s4160'
BASE_WEIGHTS_SHA = '7f909d025e12cbc7e8457b459adfbc63e48f52fb9e98ce45b91e64e84ddd1962'
WARMUP_SHA = 'f213c11059f9b81b57fbc9a32bc1bb86fbca26f583fe24f0027e8bd03ec50769'
PROMOTION_INPUTS_SHA = '95d773b06126b1637492e07cc5e7ab62b819655f5ac221c98c961bff436a9a0a'
BUNDLED_ARTIFACT = ROOT / 'src/main/resources/data/createcheme/neural/v3-column-transformer-f0.json'
WARMUP = PROMOTION_INPUTS / 'warmup.json'

STRATEGIES = ['current', 'neural', 'neuralFirst']
WORKERS = 10
DEADLINE_SECONDS = 30
HEAP_BYTES = 4 * 1024 ** 3
BLOCKS = [1, 2]
REFERENCE = 'baseline'

# The promoted decoder rule. Every arm carries it: this study changes solver and candidate rules, and the
# one decoder question it asks (E4) is about offering a second decode, not about replacing the first.
PHASE_FLOOR_DECODER = dict(rule='zero-phase-floor', zeroPhaseFloorFactor=10.0)

# The promoted correction, restated field by field so an arm is a visible delta against it rather than a
# separate rule that happens to look similar.
PROMOTED_CORRECTION = dict(maximumIterations=16, budgetMillis=2000, extensionBlock=8,
                           extensionMaximumIterations=48, contractionWindow=8, contractionFactor=0.5,
                           stallWindow=8, stallFactor=0.9, stallResidualFloor=1e-6)


def correction(**delta):
    """The promoted correction with the named fields replaced. Unknown fields are a registration error."""
    assert set(delta) <= set(PROMOTED_CORRECTION), sorted(set(delta) - set(PROMOTED_CORRECTION))
    return {**PROMOTED_CORRECTION, **delta}


def arm(correction_rule=None, candidates='SINGLE', recovery='NONE'):
    return dict(decoder=PHASE_FLOOR_DECODER, candidates=candidates, recovery=recovery,
                correction=correction_rule or dict(PROMOTED_CORRECTION))


# One arm per registered experiment. The baseline is the promoted path exactly; every other arm differs
# from it in one field, named in protocol.md beside the question it answers.
ARMS = {
    'baseline': arm(),
    # E1: the extension gate. E1a keeps the eight-iteration blocks and only stops refusing a trajectory
    # whose residual has not risen; E1b removes the gate by raising the base cap, which is the
    # configuration the diagnostic actually measured.
    'E1a': arm(correction(contractionFactor=1.0)),
    'E1b': arm(correction(maximumIterations=48, extensionBlock=0, extensionMaximumIterations=0,
                          contractionWindow=0, contractionFactor=0.0)),
    # E2: the early stall abort. E2a widens the window and loosens the factor; E2b removes the abort.
    'E2a': arm(correction(stallWindow=12, stallFactor=0.95)),
    'E2b': arm(correction(stallWindow=0, stallFactor=0.0, stallResidualFloor=0.0)),
    # E3: the terminal state of a failed correction goes to the classical authored-feature ramp.
    'E3': arm(recovery='RAMP_HANDOFF'),
    # E4: the same forward pass offers its prune decode as a second candidate.
    'E4': arm(candidates='DECODE_VARIANTS'),
}
ROUND_ONE = ['baseline', 'E1a', 'E1b', 'E2a', 'E2b', 'E3', 'E4']
# The second round's combination arm is not written here: it is whatever the registered rules selected from
# round one, so it is read from that round's analysis rather than chosen again.
COMBINATION = 'E5'
COMBINATION_SOURCE = ROOT / 'build/lnn-gap/v1/validation-analysis.json'


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def read_rows(path):
    path = Path(path)
    opener = gzip.open if path.suffix == '.gz' else open
    with opener(path, 'rt', encoding='utf-8') as stream:
        return [json.loads(line) for line in stream if line.strip()]


def info(path):
    path = Path(path)
    try:
        name = path.relative_to(ROOT).as_posix()
    except ValueError:
        name = str(path)
    return {'path': name, 'sha256': digest(path)}


def freeze(path, value):
    """Write once. A measurement that has to overwrite its own record is not a measurement."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('x', encoding='utf-8') as stream:
        json.dump(value, stream, indent=1, sort_keys=True, allow_nan=False)


def plan():
    return read(OUT / 'study-plan.json')


def population_path():
    """The cleaned validation population this study was registered against."""
    return Path(plan()['population']['absolutePath'])


def arm_path(name):
    return OUT / 'arms' / f'{name}.json'


def spec(name):
    """One arm's registered manifest fields.

    Round one's arms are the constants above. The combination arm has no constant, because its fields are
    whatever round one's registered rules selected; it is read from an already-frozen manifest if this
    revision has one, and otherwise from round one's analysis. Either way nothing chooses it here.
    """
    if name in ARMS:
        return ARMS[name]
    if arm_path(name).exists():
        return {key: value for key, value in read(arm_path(name)).items() if key != 'pipeline'}
    if name == COMBINATION and COMBINATION_SOURCE.exists():
        selected = read(COMBINATION_SOURCE)['decisions']['combination']['arm']
        if selected:
            return selected
    raise KeyError(f'Unknown arm {name}; round one selected nothing to combine')


def run_directory(name, block):
    return OUT / 'validation' / f'block-{block}' / name


def decode_path(name):
    return OUT / 'decode' / f'{name}.jsonl'


def decode_rule(name):
    """The decoder:candidates argument the decode check takes for one arm."""
    entry = spec(name)
    decoder = entry['decoder']
    left = 'prune' if decoder['rule'] == 'prune' else repr(decoder['zeroPhaseFloorFactor'])
    return f"{left}:{entry['candidates']}"


def id_list(name):
    """A committed target group, as a list of case identifiers."""
    return read(IDS / f'{name}.json')['ids']


def mean(values):
    return sum(values) / len(values) if values else None


def median(values):
    ordered = sorted(values)
    if not ordered:
        return None
    middle = len(ordered) // 2
    return ordered[middle] if len(ordered) % 2 else (ordered[middle - 1] + ordered[middle]) / 2


def stats(values):
    values = [v for v in values if v is not None]
    if not values:
        return None
    average = mean(values)
    variance = sum((v - average) ** 2 for v in values) / len(values)
    return dict(n=len(values), mean=average, median=median(values), min=min(values), max=max(values),
                sd=variance ** 0.5)


def strict_ids(cases, mode):
    return {i for i, record in cases.items() if record['modes'][mode]['outcome'] == 'strict'}


def promotion_cases(block):
    """The promotion run's committed per-case evidence for one block."""
    return {row['id']: row for row in read_rows(PROMOTION_EVIDENCE / f'case-block{block}.jsonl.gz')}


def promotion_seed_digests():
    return {row['id']: (row['seedSha256'] if row['supported'] else None)
            for row in read_rows(PROMOTION_EVIDENCE / 'decode-seed-digests.jsonl.gz')}


def seed_digest(seed):
    """The predecessor's sealing routine, unchanged: canonical JSON of the Gson seed, then SHA-256."""
    if seed is None:
        return None
    return hashlib.sha256(json.dumps(seed, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def handoff_millis(mode_row):
    """What a ramp handoff cost this request, or None when the arm never armed one.

    The calculator publishes `handoffMs=` in its initialization event only when a recovery rule was
    selected and the request was eligible for it, so absence is itself evidence and is kept distinct
    from zero.
    """
    for event in mode_row.get('events') or []:
        for part in str(event).split(';'):
            part = part.strip()
            if part.startswith('handoffMs='):
                try:
                    return float(part[len('handoffMs='):])
                except ValueError:
                    return None
    return None


def initializer(mode_row):
    """The route that published this request: LNN, LNN_RAMP_HANDOFF, LNN_FAILED, CURRENT_BACKUP or None."""
    for event in mode_row.get('events') or []:
        for part in str(event).split(';'):
            part = part.strip()
            if part.startswith('initializer='):
                return part[len('initializer='):]
    return None


def verify_frozen():
    """Bind this study to the bytes it claims to be measuring, before it measures anything."""
    assert digest(BUNDLED_ARTIFACT) == BASE_WEIGHTS_SHA, 'The bundled artifact is not the registered F0 export'
    assert digest(WARMUP) == WARMUP_SHA, 'The warmup input changed'
    return dict(bundledArtifact=info(BUNDLED_ARTIFACT), warmup=info(WARMUP))
