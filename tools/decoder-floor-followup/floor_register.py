"""Copy the frozen inputs, write the pipeline manifests and freeze the registration.

Nothing here trains, selects or measures. It binds the study to the predecessor artifacts by hash and
records the single deliberate source delta against the sealed trace-followup archive.
"""
from floor_common import *
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

# The files this study deliberately changes relative to the sealed native core.
DECLARED_SOURCE_DELTA = {'src/main/java/com/wormzjl/createcheme/science/column/v3/V3FactorizedNeuralFeatures.java',
                         'tools/neural/V3ColumnTransformerInitializer.java'}
ADDED_TOOL_SOURCES = {'tools/decoder-floor-followup/java/V3FloorInitializer.java',
                      'tools/decoder-floor-followup/java/V3FloorModels.java',
                      'tools/decoder-floor-followup/java/V3FloorEvaluationProbe.java',
                      'tools/decoder-floor-followup/java/V3FloorDecodeCheck.java'}
NEEDED_TOOLS = {'V3HybridBaseline.java', 'V3HybridResidualInitializer.java', 'V3BoundedEvaluation.java',
                'V3CandidateModels.java', 'V3ColumnTransformerInitializer.java',
                'V3MechanisticTransformerInitializer.java', 'V3NeuralMvpProbe.java'}


# The transformer promotion retired these readers out of `src/main` into the offline tools and added the
# promoted initializer, its anchor and the liquid-supply screen to the core. Re-registering this study
# against the current tree therefore also needs its DECLARED_SOURCE_DELTA widened; its committed evidence
# stands on the tree it was sealed against, which is what the cache manifest binds.
PROMOTED_CORE_SOURCES = {
    'src/main/java/com/wormzjl/createcheme/science/column/v3/V3AnchorTransformerInitializer.java',
    'src/main/java/com/wormzjl/createcheme/science/column/v3/V3NativeAnchor.java',
    'src/main/java/com/wormzjl/createcheme/science/column/v3/V3LiquidSupplyScreen.java',
}
RETIRED_CORE_SOURCES = {
    f'src/main/java/com/wormzjl/createcheme/science/column/v3/{name}.java':
        f'tools/neural/retired/{name}.java'
    for name in ('V3DenseNeuralInitializer', 'V3GeneralNeuralInitializer', 'V3FactorizedNeuralInitializer',
                 'V3NearestProfileInitializer', 'V3PhaseAwareNeuralInitializer')
}


def resolved(path):
    """Where a declared core source lives in this worktree, after the promotion's relocations."""
    return RETIRED_CORE_SOURCES.get(path, path)


def core_sources():
    """The predecessor's native-core source closure, resolved against this worktree."""
    previous = read(PRIOR_TRACE / 'training-plan.json')
    declared = [entry for entry in previous['dependencies'] if entry['path'].endswith('.java')
                and (entry['path'].startswith('src/main/') or Path(entry['path']).name in NEEDED_TOOLS)]
    added = sorted(ROOT / path for path in ADDED_TOOL_SOURCES | PROMOTED_CORE_SOURCES)
    return declared, sorted({ROOT / resolved(entry['path']) for entry in declared} | set(added))


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
            local = (ROOT / resolved(entry['path'])).read_bytes()
            if archived == local:
                unchanged += 1
            elif normalized(archived) == normalized(local):
                # This worktree's checkout normalized the line endings of a mixed-ending file. The text
                # is identical, so the compiled behaviour is too; it is recorded rather than hidden.
                reencoded.append(entry['path'])
            else:
                changed.append(dict(path=entry['path'], studyPath=resolved(entry['path']),
                                    archivedSha256=entry['sha256'],
                                    studySha256=digest(ROOT / resolved(entry['path']))))
    assert {entry['path'] for entry in changed} == DECLARED_SOURCE_DELTA, changed
    return dict(passed=True, archive=manifest['archive'], archiveMatchedSources=unchanged,
                lineEndingOnlyDifferences=sorted(reencoded),
                declaredDeltaSources=changed, addedToolSources=[info(ROOT / p) for p in sorted(ADDED_TOOL_SOURCES)],
                rationale='Only the decoder gains an opt-in option; the default path stays bit identical '
                          'and the F0-baseline parity check measures that claim on all 405 inputs.')


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
    manifests = {}
    for name, decoder in PIPELINES.items():
        path = pipeline_path(name)
        value = dict(id=f'decoder-floor/{name}', kind='anchor-augmented', materialCompletion=False,
                     revision='decoder-floor-pipeline-v1', decoder=decoder, weights=weights)
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
    tools = sorted(p for p in (ROOT / 'tools/decoder-floor-followup').rglob('*')
                   if p.is_file() and p.suffix in ('.py', '.java', '.md') and p.name != 'results.md')
    commit = subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=ROOT, capture_output=True, text=True, check=True)
    plan = dict(revision='decoder-floor-study-v1', createdUtc=datetime.now(timezone.utc).isoformat(),
                baseCommit=commit.stdout.strip(),
                question='Does an opt-in decoder presence floor, which seeds a kept-but-below-floor component at '
                         'a multiple of its support floor instead of zero, change strict native outcomes on the '
                         'frozen F0 weights?',
                model=dict(name=BASE_NAME, weights=info(INPUTS / 'F0-model.json'),
                           source=external(PRIOR_TRACE / f'models/{BASE_NAME}/model.json')),
                pipelines={name: dict(manifest=manifests[name], decoder=decoder)
                           for name, decoder in PIPELINES.items()},
                orderByBlock=[ORDER, list(reversed(ORDER))], blocks=len(BLOCKS), cases=len(rows),
                referenceCases=len(references), strategies=STRATEGIES,
                workers=WORKERS, requestDeadlineSeconds=DEADLINE_SECONDS, neuralBudgetMillis=NEURAL_BUDGET_MILLIS,
                maximumIterations=MAXIMUM_ITERATIONS, heapBytes=HEAP_BYTES,
                measuredRequests=len(BLOCKS) * len(PIPELINES) * len(rows) * len(STRATEGIES),
                warmupRequests=len(BLOCKS) * len(PIPELINES) * 6,
                frozenInputs=[info(INPUTS / name) for name in sorted(FROZEN)],
                archivedParityJournals={str(block): external(path) for block, path in ARCHIVED_F0.items()},
                parityRule='F0-baseline must reproduce the archived F0 decoded seeds exactly before the campaign, '
                           'and must reproduce the archived classical/ONLY/FIRST strict identity sets afterwards.',
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
    assert plan['revision'] == 'decoder-floor-study-v1'
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
