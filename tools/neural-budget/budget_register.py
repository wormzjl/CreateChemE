"""Copy the frozen inputs, write the pipeline manifests and freeze the registration.

Nothing here trains, selects or measures. It binds the study to the predecessor artifacts by hash and
records the deliberate source delta against the sealed trace-followup archive. The delta is declared as
an allowlist rather than an exact set, because which intervention this study implements is decided by the
bounded diagnostic under the pre-declared rules in protocol.md; every file that actually differs must
still be named in that allowlist, and every other core source must match the archive byte for byte.
"""
from budget_common import *
from datetime import datetime, timezone
import argparse
import subprocess
import zipfile

FROZEN = {
    'validation-inputs.jsonl': (PRIOR_TRACE / 'validation-inputs.jsonl', VALIDATION_INPUTS_SHA),
    'validation-references.jsonl': (PRIOR_TRACE / 'validation-references.jsonl', None),
    'warmup.json': (PRIOR / 'build/neural-hybrid-learning/v1/warmup.json', WARMUP_SHA),
    'F0-model.json': (PRIOR_TRACE / f'models/{BASE_NAME}/model.json', BASE_WEIGHTS_SHA),
}
# The archived F0 journals of the predecessor campaign; read only, used as the parity target.
ARCHIVED_F0 = {block: PRIOR_CAPACITY / f'validation/block-{block}/F0/evaluation.jsonl' for block in BLOCKS}

# Files this study is allowed to differ in, and why. Anything else must match the sealed archive bytes.
ALLOWED_SOURCE_DELTA = {
    'src/main/java/com/wormzjl/createcheme/science/column/v3/V3ColumnCalculator.java':
        'in-flight correction evidence, the observation seam, and the neural correction budget',
    'src/main/java/com/wormzjl/createcheme/science/column/v3/V3NewtonTrace.java':
        'no-op attempt-boundary observations for the bounded diagnostic',
    'src/main/java/com/wormzjl/createcheme/science/column/v3/V3SimultaneousColumnSolver.java':
        'the progress-based rung budget of the neural path, if the diagnostic selects it',
    'src/main/java/com/wormzjl/createcheme/science/column/v3/V3InitializationOptions.java':
        'the neural correction budget the pipeline manifest states, if the diagnostic selects it',
    'src/main/java/com/wormzjl/createcheme/science/column/v3/V3FactorizedNeuralFeatures.java':
        'the opt-in decoder options, inherited from the decoder-floor study and extended if selected',
    'tools/neural/V3ColumnTransformerInitializer.java':
        'threads the decode options; inherited from the decoder-floor study',
}
ADDED_TOOL_SOURCES = {'tools/neural-budget/java/V3BudgetInitializer.java',
                      'tools/neural-budget/java/V3BudgetModels.java',
                      'tools/neural-budget/java/V3BudgetEvaluationProbe.java',
                      'tools/neural-budget/java/V3BudgetDecodeCheck.java',
                      'tools/neural-budget/java/V3BudgetTraceProbe.java'}
NEEDED_TOOLS = {'V3HybridBaseline.java', 'V3HybridResidualInitializer.java', 'V3BoundedEvaluation.java',
                'V3CandidateModels.java', 'V3ColumnTransformerInitializer.java',
                'V3MechanisticTransformerInitializer.java', 'V3NeuralMvpProbe.java'}


def core_sources():
    """The predecessor's native-core source closure, resolved against this worktree."""
    previous = read(PRIOR_TRACE / 'training-plan.json')
    declared = [entry for entry in previous['dependencies'] if entry['path'].endswith('.java')
                and (entry['path'].startswith('src/main/') or Path(entry['path']).name in NEEDED_TOOLS)]
    added = sorted(ROOT / path for path in ADDED_TOOL_SOURCES)
    return declared, sorted({ROOT / entry['path'] for entry in declared} | set(added))


def source_delta():
    """Prove exactly which sources differ from the sealed archive bytes, and why."""
    declared, _ = core_sources()
    # The registration archive is the one that carries the native-core source closure.
    manifest = read(ROOT / 'tools/trace-followup/registration-manifest.json')
    archive = PRIOR / manifest['archive']['path']
    assert digest(archive) == manifest['archive']['sha256']
    results = read(ROOT / 'tools/trace-followup/cache-manifest.json')
    assert digest(PRIOR / results['archive']['path']) == results['archive']['sha256'] == PREDECESSOR_ARCHIVE_SHA

    def normalized(data):
        return data.replace(b'\r\n', b'\n')

    changed, unchanged, reencoded = [], 0, []
    with zipfile.ZipFile(archive) as zipped:
        for entry in declared:
            archived = zipped.read(entry['path'])
            local = (ROOT / entry['path']).read_bytes()
            if archived == local:
                unchanged += 1
            elif normalized(archived) == normalized(local):
                # This worktree's checkout normalized the line endings of a mixed-ending file. The text is
                # identical, so the compiled behaviour is too; it is recorded rather than hidden.
                reencoded.append(entry['path'])
            else:
                changed.append(dict(path=entry['path'], archivedSha256=entry['sha256'],
                                    studySha256=digest(ROOT / entry['path']),
                                    reason=ALLOWED_SOURCE_DELTA[entry['path']]))
    undeclared = sorted({entry['path'] for entry in changed} - set(ALLOWED_SOURCE_DELTA))
    assert not undeclared, undeclared
    return dict(passed=True, archive=manifest['archive'], archiveMatchedSources=unchanged,
                lineEndingOnlyDifferences=sorted(reencoded),
                declaredDeltaSources=changed, allowedDeltaSources=ALLOWED_SOURCE_DELTA,
                addedToolSources=[info(ROOT / p) for p in sorted(ADDED_TOOL_SOURCES)],
                rationale='The learned path gains in-flight evidence, an observation seam and, where the '
                          'diagnostic selects it, a reshaped correction budget. The classical path, the '
                          'decoder default, the acceptance audit and every tolerance are untouched, and the '
                          'F0-baseline parity check measures that claim on all 405 inputs.')


def copy_inputs():
    for name, (source, expected) in FROZEN.items():
        target = INPUTS / name
        if target.exists():
            assert digest(target) == digest(source), name
            continue
        assert expected is None or digest(source) == expected, name
        exact_copy(source, target)
    rows = read_rows(INPUTS / 'validation-inputs.jsonl')
    assert len(rows) == 405 and len({row['id'] for row in rows}) == 405
    references = read_rows(INPUTS / 'validation-references.jsonl')
    assert len(references) == 168 and {r['id'] for r in references} <= {r['id'] for r in rows}
    return rows, references


def write_pipelines():
    weights = info(INPUTS / 'F0-model.json')
    assert weights['sha256'] == BASE_WEIGHTS_SHA
    document = read(INPUTS / 'F0-model.json')
    assert document['anchorLayout'] == 'full' and document['outputConvention'] == 'absolute'
    assert document['presenceThreshold'] == .02 and document['traceFloorFraction'] == 1e-10
    assert PIPELINES['F0-baseline'] == dict(decoder=dict(rule='prune'), correction=BASELINE_CORRECTION), \
        'The baseline pipeline is the production path and never changes'
    manifests = {}
    for name, variant in PIPELINES.items():
        path = pipeline_path(name)
        value = dict(id=f'neural-budget/{name}', kind='anchor-augmented', materialCompletion=False,
                     revision='neural-budget-pipeline-v1', decoder=variant['decoder'],
                     correction=variant['correction'], weights=weights)
        if path.exists():
            assert read(path) == value, name
        else:
            freeze(path, value)
        manifests[name] = info(path)
    return manifests


def register():
    rows, references = copy_inputs()
    manifests = write_pipelines()
    declared, sources = core_sources()
    tools = sorted(p for p in (ROOT / 'tools/neural-budget').rglob('*')
                   if p.is_file() and p.suffix in ('.py', '.java', '.md', '.json') and p.name != 'results.md')
    commit = subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=ROOT, capture_output=True, text=True, check=True)
    plan = dict(revision='neural-budget-study-v1', createdUtc=datetime.now(timezone.utc).isoformat(),
                baseCommit=commit.stdout.strip(),
                question='Do the learned seed\'s capped corrections stop while still contracting, and does a '
                         'progress-based correction budget or a phase-level decoder floor on the frozen F0 '
                         'weights change strict native outcomes or failure latency?',
                model=dict(name=BASE_NAME, weights=info(INPUTS / 'F0-model.json'),
                           source=external(PRIOR_TRACE / f'models/{BASE_NAME}/model.json')),
                pipelines={name: dict(manifest=manifests[name], **variant) for name, variant in PIPELINES.items()},
                orderByBlock=[ORDER, list(reversed(ORDER))], blocks=len(BLOCKS), cases=len(rows),
                referenceCases=len(references), strategies=STRATEGIES,
                workers=WORKERS, requestDeadlineSeconds=DEADLINE_SECONDS, neuralBudgetMillis=NEURAL_BUDGET_MILLIS,
                maximumIterations=MAXIMUM_ITERATIONS, heapBytes=HEAP_BYTES,
                measuredRequests=len(BLOCKS) * len(PIPELINES) * len(rows) * len(STRATEGIES),
                warmupRequests=len(BLOCKS) * len(PIPELINES) * 6,
                traceConfigurations=TRACE_CONFIGS,
                traceTargets=info(IDS / 'trace-targets.json'),
                frozenInputs=[info(INPUTS / name) for name in sorted(FROZEN)],
                archivedParityJournals={str(block): external(path) for block, path in ARCHIVED_F0.items()},
                parityRule='F0-baseline must reproduce the archived F0 decoded seeds exactly before the campaign, '
                           'and must reproduce the archived classical/ONLY/FIRST strict identity sets afterwards. '
                           'Published diagnostics fields are not part of the parity claim: the in-flight evidence '
                           'fix deliberately replaces the archived zero-iteration placeholder of a budget-stopped '
                           'failure with what that attempt had measured.',
                gateRule='A variant passes only with a strict FIRST gain in both blocks, preservation of the '
                         'contemporaneous classical strict union, and no pooled mean FIRST latency regression '
                         'against F0-baseline.',
                sourceDelta=source_delta(), coreSources=[info(p) for p in sources],
                declaredCoreDependencies=declared, tools=[info(p) for p in tools],
                testEvaluationAllowed=False, newTrainingAllowed=False, productionDefaultChanged=False,
                automaticPromotion=False)
    freeze(OUT / 'study-plan.json', plan)
    verify_plan()
    print(json.dumps(dict(registered=plan['revision'], pipelines=list(PIPELINES), cases=len(rows),
                          measuredRequests=plan['measuredRequests'])), flush=True)


def verify_plan():
    plan = read(OUT / 'study-plan.json')
    assert plan['revision'] == 'neural-budget-study-v1'
    assert plan['workers'] == WORKERS and plan['maximumIterations'] == MAXIMUM_ITERATIONS
    assert plan['neuralBudgetMillis'] == NEURAL_BUDGET_MILLIS and plan['requestDeadlineSeconds'] == DEADLINE_SECONDS
    assert set(plan['pipelines']) == set(ORDER) and plan['orderByBlock'] == [ORDER, list(reversed(ORDER))]
    for entry in plan['frozenInputs'] + plan['coreSources'] + [v['manifest'] for v in plan['pipelines'].values()]:
        assert digest(ROOT / entry['path']) == entry['sha256'], entry['path']
    for entry in plan['archivedParityJournals'].values():
        assert digest(Path(entry['path'])) == entry['sha256'], entry['path']
    return plan


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['register', 'verify'])
    mode = parser.parse_args().mode
    register() if mode == 'register' else print(json.dumps({'verified': verify_plan()['revision']}))
