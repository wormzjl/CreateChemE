"""Locked ten-worker validation of every pipeline, in two reversed-order blocks."""
from budget_common import *
from budget_native import execute
from budget_register import verify_plan
from budget_preflight import decode_path
import argparse


def validate_run(directory, name):
    rows = read_rows(directory / 'evaluation.jsonl')
    meta = read(directory / 'run.json')
    assert meta['revision'] == 'neural-budget-column-evaluation-v1'
    assert meta['complete'] and meta['completed'] == len(rows) == 405
    assert meta['scheduling']['distinctWorkerThreads'] == WORKERS and meta['scheduling']['terminated']
    assert meta['maximumHeapBytes'] == HEAP_BYTES and meta['workers'] == WORKERS
    assert meta['deadlineMillis'] == DEADLINE_SECONDS * 1000 and meta['neuralBudgetMillis'] == NEURAL_BUDGET_MILLIS
    assert meta['neuralMaximumIterations'] == MAXIMUM_ITERATIONS
    assert meta['modelSha256'] == digest(pipeline_path(name)) and meta['warmupSha256'] == WARMUP_SHA
    assert meta['sourceSha256'] == VALIDATION_INPUTS_SHA
    assert meta['pipelineManifest']['decoder'] == PIPELINES[name]['decoder']
    assert meta['pipelineManifest']['correction'] == PIPELINES[name]['correction']
    # The command line always states the production wall pair; only the manifest may reshape it, and the
    # baseline must not. This is what keeps the baseline arm identical to the archived campaign.
    if name == 'F0-baseline':
        assert meta['correctionIterations'] == MAXIMUM_ITERATIONS
        assert meta['correctionBudgetMillis'] == NEURAL_BUDGET_MILLIS
    assert len({row['id'] for row in rows}) == 405
    # The seed a request actually received must be the seed the preflight recorded for this variant.
    expected = {row['id']: row['seed'] for row in read_rows(decode_path(name))}
    for row in rows:
        prediction = row['rawPrediction']
        seed = prediction.get('seedPresentedByPipeline')
        assert bool(seed) == bool(prediction['supported'])
        assert seed == expected[row['id']], row['id']
        if seed:
            assert canonical_input_hash(seed['input']) == canonical_input_hash(row['input'])
    return rows, meta


def invoke(name, block):
    directory = run_directory(name, block)
    if directory.exists():
        validate_run(directory, name)
        print(f'Verified completed run {directory}', flush=True)
        return
    print(f'START block {block} {name}', flush=True)
    execute('V3BudgetEvaluationProbe',
            [directory, INPUTS / 'validation-inputs.jsonl', pipeline_path(name),
             WORKERS, DEADLINE_SECONDS, NEURAL_BUDGET_MILLIS, MAXIMUM_ITERATIONS, INPUTS / 'warmup.json'],
            OUT / 'logs' / f'validation-block-{block}-{name}.log')
    rows, meta = validate_run(directory, name)
    print(json.dumps({'completeBlock': block, 'pipeline': name, 'cases': len(rows),
                      'seconds': meta['elapsedSeconds'],
                      'strict': {mode: sum(strict({**row, **row[mode]}) for row in rows) for mode in STRATEGIES}}),
          flush=True)


def validation():
    plan = verify_plan()
    parity = read(OUT / 'preflight-parity.json')
    assert parity['defaultDecodeReproducesArchive'], 'Run the decode parity preflight first'
    lock = OUT / 'validation-execution-lock.json'
    expected = dict(studyPlan=info(OUT / 'study-plan.json'), preflight=info(OUT / 'preflight-parity.json'))
    if lock.exists():
        assert read(lock) == expected
    else:
        freeze(lock, expected)
    for block, names in zip(BLOCKS, plan['orderByBlock']):
        for name in names:
            invoke(name, block)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['validation'])
    parser.parse_args()
    validation()
