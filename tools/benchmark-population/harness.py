"""The one hook a campaign harness needs to run on a cleaned population instead of an archived one.

A campaign study is sealed against the bytes it measured, so this must not change any default.  Both
``tools/neural-budget/`` and ``tools/transformer-promotion/`` therefore call :func:`resolve` with the
archived population they already use, and get it back unchanged unless a caller explicitly asks for another
one -- by the ``--population`` flag on the study's native entry point, or by setting
``CREATECHEME_BENCHMARK_POPULATION`` in the environment.

A requested population must be registered in ``tools/benchmark-population/v1/manifest.json`` by SHA-256.
That is the whole safety property: a campaign can only be pointed at a population whose derivation, source
hashes and exclusion reasons are committed, so a number produced on it can always be traced back to the rule
that produced the denominator.

    from pathlib import Path
    import sys
    sys.path.insert(0, str(ROOT / 'tools/benchmark-population'))
    import harness

    POPULATION = harness.resolve(INPUTS / 'validation-inputs.jsonl', argument)
    POPULATION.path      # what to hand the Java probe
    POPULATION.label     # 'archived', or e.g. 'validation-cleaned'; use it in output directory names
    POPULATION.cases     # row count
    POPULATION.sha256

Outputs must be kept apart: a filtered run writes beside the archived run, never over it.  Label the run
directory with ``POPULATION.label`` whenever it is not ``archived``.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
from typing import NamedTuple

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / 'tools/benchmark-population/v1/manifest.json'
ENVIRONMENT_VARIABLE = 'CREATECHEME_BENCHMARK_POPULATION'
ARCHIVED = 'archived'


class Population(NamedTuple):
    path: Path
    label: str
    cases: int
    sha256: str
    registered: dict | None

    @property
    def archived(self) -> bool:
        return self.label == ARCHIVED


def _digest(path: Path) -> str:
    import hashlib
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def _rows(path: Path) -> int:
    with Path(path).open(encoding='utf-8') as stream:
        return sum(1 for line in stream if line.strip())


def registry() -> dict:
    if not MANIFEST.exists():
        return {}
    manifest = json.loads(MANIFEST.read_text(encoding='utf-8'))
    entries = {}
    for population, entry in manifest['populations'].items():
        for name, record in entry['files'].items():
            if not name.endswith('-inputs.jsonl'):
                continue
            entries[record['sha256']] = {
                'population': population, 'file': name,
                'rows': record['rows'], 'rule': manifest['rule'],
                'excludedByReason': entry['counts']['excludedByReason'],
                'source': entry['source']}
    return entries


def resolve(default: Path, argument=None) -> Population:
    """Return the population to measure.  With no argument and no environment override, the default."""
    requested = argument or os.environ.get(ENVIRONMENT_VARIABLE)
    if not requested:
        default = Path(default)
        if not default.exists():
            raise SystemExit(f"This study's archived population {default} is not staged yet; run its "
                             'registration step first, or pass --population.')
        return Population(default, ARCHIVED, _rows(default), _digest(default), None)
    path = Path(requested).resolve()
    if not path.exists():
        raise SystemExit(f'Population {path} does not exist')
    digest = _digest(path)
    entry = registry().get(digest)
    if entry is None:
        raise SystemExit(
            f'Population {path} (sha256 {digest}) is not registered in {MANIFEST}. '
            'Run tools/benchmark-population/classify.py, or point at one of its filtered files, so the '
            'denominator a campaign publishes is always traceable to the rule that produced it.')
    return Population(path, f"{entry['population']}-cleaned", entry['rows'], digest, entry)
