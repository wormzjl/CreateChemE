"""Shared locations, loaders and hashing for the benchmark-population cleanup.

Every population this study cleans is defined here by the one archived file it comes from, so a reader can
bind a filtered id list back to the bytes it was derived from.  Nothing under the sealed predecessor worktree
is ever written; archives are read in place and zip members are extracted only under ``%TEMP%``.
"""
from __future__ import annotations

import hashlib
import json
import os
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
STUDY = Path(__file__).resolve().parent
OUT = ROOT / 'build/benchmark-population'
V1 = STUDY / 'v1'

# Read-only predecessor worktree.  Never write here.
SEALED = Path('C:/Users/wormz/.codex/worktrees/8848/CreateChemE')

JAVA_HOME = Path('C:/Program Files/Java/jdk-21.0.11/bin')
GSON = Path('C:/Users/wormz/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.10.1/'
            'b3add478d4382b78ea20b1671390a858002feb6c/gson-2.10.1.jar')
# V3NeuralMvpProbe owns the JSON -> V3ColumnInput reader every study in this project has used, so the probe
# parses each population exactly as the campaigns did.  The promotion moved the dense family it also reads
# to tools/neural/retired/, which is therefore on the compile path even though no retired model is loaded.
HELPERS = ['tools/neural/retired/V3DenseNeuralInitializer.java', 'tools/neural/V3NeuralMvpProbe.java',
           'tools/neural/V3BoundedEvaluation.java',
           'tools/benchmark-population/java/V3RequestAdmissionProbe.java']

# The shipped calibrated tier.  Mirrors V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO.
PRODUCTION_SCREEN_RATIO = 0.30

# id -> the rest of the row.  Every population is emitted in this one shape, the shape the promotion harness
# already reads, so a filtered file is a drop-in replacement for the file it was filtered from.
ROW_FIELDS = ('design', 'id', 'input', 'split')

POPULATIONS = ('validation', 'g4fresh', 'g6fresh', 'historical-test', 'train')


def where(path) -> str:
    """Repository-relative posix path for anything in this worktree; absolute for the sealed archives.

    A committed manifest has to be readable from another checkout, so a path under this worktree is
    recorded relative to it, and only the read-only predecessor archives keep their machine-absolute names.
    """
    text = str(path)
    marker = text.split('!', 1)
    try:
        head = Path(marker[0]).resolve().relative_to(ROOT).as_posix()
    except ValueError:
        head = marker[0].replace('\\', '/')
    return head + ('!' + marker[1] if len(marker) > 1 else '')


def sha256_bytes(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def sha256_file(path) -> str:
    return sha256_bytes(Path(path).read_bytes())


def rows(path):
    with Path(path).open(encoding='utf-8') as stream:
        for line in stream:
            if line.strip():
                yield json.loads(line)


def zip_rows(archive, member):
    with zipfile.ZipFile(archive) as bundle:
        payload = bundle.read(member)
    return [json.loads(line) for line in payload.decode('utf-8').splitlines() if line.strip()], sha256_bytes(payload)


def extract_to_temp(archive, member) -> Path:
    """Extract one zip member under %TEMP% and return its path.  Sealed archives are never written to."""
    target = Path(os.environ['TEMP']) / 'benchmark-population'
    target.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as bundle:
        bundle.extract(member, target)
    return target / member


def write_jsonl(path, records):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('w', encoding='utf-8', newline='\n') as stream:
        for record in records:
            stream.write(json.dumps(record, sort_keys=True) + '\n')
    return sha256_file(path)


def write_json(path, document):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(document, indent=1, sort_keys=False) + '\n', encoding='utf-8', newline='\n')
    return sha256_file(path)


def normalise(row, split):
    """Reduce an archived journal row to the four fields every population file carries."""
    return {'design': row.get('design'), 'id': row['id'], 'input': row['input'],
            'split': row.get('split', split)}


def load_prepared(population):
    path = OUT / 'inputs' / f'{population}.jsonl'
    return list(rows(path)), sha256_file(path)
