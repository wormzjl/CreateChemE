"""Locked ten-worker validation of every registered arm, in two reversed-order blocks.

Nothing here may overlap another campaign or a Gradle run: every block owns ten worker threads.
"""
from gap_common import *
from gap_native import execute, next_log
from gap_register import verify_plan
import argparse


def validate_run(directory, name):
    rows = read_rows(directory / 'evaluation.jsonl')
    meta = read(directory / 'run.json')
    registration = verify_plan()
    cases = registration['population']['cases']
    rule = spec(name)
    manifest = dict(pipeline=name, **rule)
    assert meta['revision'] == 'lnn-gap-column-evaluation-v1'
    assert meta['arm'] == name and meta['armManifest'] == manifest, 'The run measured a different arm'
    assert meta['complete'] and meta['completed'] == len(rows) == cases
    assert meta['scheduling']['distinctWorkerThreads'] == WORKERS and meta['scheduling']['terminated']
    assert meta['maximumHeapBytes'] == HEAP_BYTES and meta['workers'] == WORKERS
    assert meta['deadlineMillis'] == DEADLINE_SECONDS * 1000
    assert meta['modelArtifactSha256'] == BASE_WEIGHTS_SHA, 'The run did not load the registered weights'
    assert meta['warmupSha256'] == WARMUP_SHA
    assert meta['sourceSha256'] == registration['population']['sha256']
    assert meta['correctionIterations'] == rule['correction']['maximumIterations']
    assert meta['correctionBudgetMillis'] == rule['correction']['budgetMillis']
    assert meta['candidateRule'] == rule['candidates'] and meta['recovery'] == rule['recovery']
    # The baseline is the promoted path itself, loaded from the production holder, or it is not a baseline.
    if name == REFERENCE:
        assert meta['modelSource'] == 'V3NeuralModels.bundled()'
        assert meta['recovery'] == 'NONE' and meta['candidateRule'] == 'SINGLE'
    assert len({row['id'] for row in rows}) == cases
    assert {row['id'] for row in rows} == set(registration['population']['ids'])
    return rows, meta


def invoke(name, block):
    directory = run_directory(name, block)
    if directory.exists():
        validate_run(directory, name)
        print(f'Verified completed run {directory}', flush=True)
        return
    print(f'START block {block} {name}', flush=True)
    execute('V3GapEvaluationProbe',
            [directory, population_path(), arm_path(name), WORKERS, DEADLINE_SECONDS, WARMUP],
            next_log(f'validation-block{block}-{name}-%d.log'))
    rows, meta = validate_run(directory, name)
    print(json.dumps({'completeBlock': block, 'arm': name, 'cases': len(rows),
                      'seconds': meta['elapsedSeconds'],
                      'strict': {mode: sum(row['modes'][mode]['outcome'] == 'strict' for row in rows)
                                 for mode in STRATEGIES}}), flush=True)


def validation(only):
    registration = verify_plan()
    parity = read(OUT / 'preflight-parity.json')
    assert parity['allArmsReproducePromotionSeeds'], 'Run the decode parity preflight first'
    for block, names in zip(BLOCKS, registration['orderByBlock']):
        for name in names:
            if only and name not in only:
                continue
            invoke(name, block)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['validation'])
    parser.add_argument('--arms', nargs='*', default=[],
                        help='restrict this invocation to these arms; the registered order is unchanged')
    parsed = parser.parse_args()
    validation(parsed.arms)
