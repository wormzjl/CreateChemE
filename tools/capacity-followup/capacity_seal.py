"""Replay artifact accounting and bind all capacity results into an immutable archive."""
from capacity_common import *
from capacity_register import verify_plan, archive_input_proof
from capacity_benchmark import bindings, verify_selection
from capacity_fit_all import verify_pairing
import argparse
import hashlib
import zipfile

CACHE = ROOT/'.neural-cache/capacity-followup-v1'


def archive_matches(manifest):
    archive = ROOT/manifest['archive']['path']
    assert digest(archive) == manifest['archive']['sha256']
    with zipfile.ZipFile(archive) as z:
        assert z.testzip() is None
        assert len(z.namelist()) == len(manifest['entries'])
        assert set(z.namelist()) == {e['entry'] for e in manifest['entries']}
        for entry in manifest['entries']:
            data = z.read(entry['entry'])
            assert len(data) == entry['bytes'] and hashlib.sha256(data).hexdigest() == entry['sha256']


def verify_exports_and_exposure():
    torch.set_num_threads(4)
    plan, models = bindings()
    reports = verify_pairing()
    rows = read_rows(SOURCE/'N804.jsonl')
    streams = {}
    # Independent schedule reconstruction, without calling the training batch generator.
    for seed in plan['training']['seeds']:
        generator = torch.Generator(device='cpu').manual_seed(seed)
        counts = {row['id']: 0 for row in rows}
        order = hashlib.sha256()
        endpoints, step = {}, 0
        while step < plan['training']['updates']:
            permutation = torch.randperm(len(rows), generator=generator).numpy()
            for start in range(0, len(rows), 32):
                ix = permutation[start:start+32]
                step += 1
                order.update(ix.tobytes())
                for index in ix: counts[rows[index]['id']] += 1
                if step in plan['training']['checkpoints']:
                    endpoints[step] = dict(counts=dict(counts), hash=order.hexdigest())
        streams[seed] = endpoints
    verified = []
    for name, item in models.items():
        if name in plan['controls']: continue
        cp = torch.load(ROOT/item['checkpoint']['path'], map_location='cpu', weights_only=True)
        doc = read(ROOT/item['weights']['path'])
        report = cp['report']
        arm, seed, step = item['arm'], item['seed'], item['step']
        assert cp['normalization'] == normalization() == doc['normalization']
        assert report['dataSha256'] == TRAIN_SHA == doc['dataSha256']
        assert report == read(OUT/'fits'/f'{arm}-{seed}'/f'checkpoint-{step}.json')
        assert set(cp['state_dict']) == set(doc['weights'])
        assert sum(value.numel() for value in cp['state_dict'].values()) == plan['arms'][arm]['parameters']
        for key, value in cp['state_dict'].items():
            tensor = doc['weights'][key]
            assert list(value.shape) == tensor['shape'] and torch.isfinite(value).all()
            assert torch.equal(value, torch.tensor(tensor['values'], dtype=value.dtype).reshape(tensor['shape']))
        expected = streams[seed][step]
        assert report['checkpoint']['perCasePresentationCounts'] == expected['counts']
        assert report['checkpoint']['minibatchOrderSha256'] == expected['hash']
        assert set(expected['counts'].values()) == {step//26}
        assert report['checkpoint']['totalSamplePresentations'] == sum(expected['counts'].values())
        optimizer = cp['optimizer_state_dict']
        for group in optimizer['param_groups']:
            assert group['lr'] == 8e-5 and group['weight_decay'] == 1e-4
        assert len(optimizer['state']) == len(cp['state_dict'])
        for state in optimizer['state'].values():
            assert int(state['step']) == step
            assert torch.isfinite(state['exp_avg']).all() and torch.isfinite(state['exp_avg_sq']).all()
        native = read(OUT/'models'/name/'java-parity.json')
        assert native['passed'] and native['fixtures'] == 14 and native['parallelComparisons'] == 40
        assert native['maximumRawDifference'] <= 5e-5
        assert native['maximumTemperatureDifferenceK'] <= 5e-5
        assert native['maximumFlowDifferenceOverFeed'] <= 2e-6
        assert native['maximumBranchLogitDifference'] <= 5e-5
        assert native['exactMasks'] and native['exactAnchorParity'] and native['baselineFailureRetained']
        assert native['baselineCancellationPropagated'] and native['predictionCancellationPropagated']
        verified.append(name)
    assert len(verified) == 12
    assert {p.name for p in (OUT/'fits').iterdir() if p.is_dir()} == set(reports)
    assert {p.name for p in (OUT/'models').iterdir() if p.is_dir()} == set(verified)
    print('Verified twelve tensor exports, optimizer endpoints and independent per-ID shuffle reconstruction.', flush=True)
    return verified


def verify_all():
    torch.set_num_threads(4)
    plan = verify_plan()
    registration = read(CACHE/'registration-manifest.json')
    assert registration == read(ROOT/'tools/capacity-followup/registration-manifest.json')
    archive_matches(registration)
    assert registration['plan'] == info(OUT/'training-plan.json')
    assert archive_input_proof() == read(OUT/'archive-input-proof.json')
    for item in plan['predecessors']:
        assert digest(ROOT/item['manifest']['path']) == item['manifest']['sha256']
        assert digest(ROOT/item['archive']['path']) == item['archive']['sha256']
    core = read(OUT/'native-core-build.json')
    assert core['passed'] and core['noPreexistingProjectClasspath'] and len(core['sources']) == 113
    for entry in core['sources']+core['compiledClasses']:
        assert digest(ROOT/entry['path']) == entry['sha256']
    runtime = read(OUT/'native-runtime-proof.json')
    assert runtime['passed'] and runtime['exactCompleteDecodedPredictions']
    assert runtime['allTrainCases'] == 804 and runtime['rejectedMalformedManifests'] == 8
    for loaded in runtime['loadedClasses']:
        path = Path(loaded['origin'])/(loaded['class'].replace('.', '/')+'.class')
        assert digest(path) == loaded['sha256']
        assert any(Path(entry['path']) == path and entry['sha256'] == loaded['sha256'] for entry in plan['runtime'])
    verified = verify_exports_and_exposure()
    vp = verify_selection()
    assert vp['measuredRequests'] <= plan['maximumValidationRequests']
    expected = dict(selection=info(OUT/'selection.json'), validationPlan=info(OUT/'validation-plan.json'),
        trainingPlan=info(OUT/'training-plan.json'))
    assert read(OUT/'validation-execution-lock.json') == expected
    assert read(OUT/'screen-execution-lock.json') == dict(plan=info(OUT/'training-plan.json'),
        models=info(OUT/'models.json'), panel=plan['screeningPanel'])
    from capacity_analysis import build
    analyses = []
    for stage in ('screen', 'validation'):
        result, cases, profiles = build(stage)
        assert result == read(OUT/f'{stage}-analysis.json')
        assert cases == read_rows(OUT/f'{stage}-case-evidence.jsonl')
        assert profiles == read_rows(OUT/f'{stage}-profile-evidence.jsonl')
        actual = {(int(block.name.split('-')[1]), directory.name) for block in (OUT/stage).iterdir()
            if block.is_dir() for directory in block.iterdir() if directory.is_dir()}
        assert actual == {(block, name) for block in result['blocks'] for name in result['pipelines']}
        analyses.append(dict(stage=stage, summary=info(OUT/f'{stage}-analysis.json'),
            caseEvidence=info(OUT/f'{stage}-case-evidence.jsonl'), profileEvidence=info(OUT/f'{stage}-profile-evidence.jsonl'),
            measuredRequests=result['measuredRequests'], warmupRequests=result['warmupRequests']))
        print('Exact summary/case/profile replay matched '+stage, flush=True)
    from capacity_report import build as rebuild_report
    summary, text = rebuild_report()
    assert summary == read(OUT/'report-summary.json')
    assert text == (OUT/'report.md').read_text(encoding='utf-8')
    assert (OUT/'report.md').read_bytes() == (ROOT/'tools/capacity-followup/results.md').read_bytes()
    return dict(revision='capacity-final-verification-v1', passed=True, registeredFits=4, verifiedCheckpoints=verified,
        exactTensorExportsVerified=True, optimizerEndpointsVerified=True, perIdExposureAndOrderIndependentlyRecomputed=True,
        sourceAndLoadedRuntimeVerified=True, archivedInputsRevalidated=True, selectionRecomputed=True,
        fullValidationGuardVerified=True, analysesRecomputed=analyses, reportRecomputed=True,
        registrationArchive=registration['archive'], registrationManifest=info(CACHE/'registration-manifest.json'),
        predecessors=plan['predecessors'], measuredNativeRequests=summary['measuredNativeRequests'],
        warmupRequests=summary['warmupRequests'], newTestRequests=0, newSolverRequestsByVerification=0,
        productionDefaultChanged=False, analysisSources=[info(ROOT/'tools/capacity-followup'/name) for name in (
            'capacity_analysis.py', 'capacity_report.py', 'capacity_seal.py', 'test_capacity_analysis.py')])


def seal():
    proof = verify_all()
    freeze(OUT/'verification.json', proof)
    paths = {p for p in OUT.rglob('*') if p.is_file()}
    paths.update(p for p in (ROOT/'tools/capacity-followup').rglob('*')
        if p.is_file() and '__pycache__' not in p.parts and p.suffix != '.pyc')
    paths.update(p for p in OUT.parent.glob('*.log') if not p.name.startswith('seal-output'))
    paths.add(CACHE/'registration-manifest.json')
    # The imported pure contrast helper is an additive analysis dependency.
    paths.add(ROOT/'tools/trace-followup/analyze_native.py')
    archive, entries = CACHE/'results.zip', []
    with zipfile.ZipFile(archive, 'x', compression=zipfile.ZIP_DEFLATED) as z:
        for path in sorted(paths):
            data, name = path.read_bytes(), path.relative_to(ROOT).as_posix()
            z.writestr(name, data)
            entries.append(dict(entry=name, bytes=len(data), sha256=hashlib.sha256(data).hexdigest()))
    manifest = dict(revision='capacity-results-cache-v1', archive=info(archive), entries=entries,
        registrationArchive=proof['registrationArchive'], registrationManifest=proof['registrationManifest'],
        predecessors=proof['predecessors'], verification=info(OUT/'verification.json'), summary=info(OUT/'report-summary.json'),
        restore='Restore registration.zip and results.zip at this repository root with base 6528c38 plus capacity-study commits. The recorded Python environment, JDK and Gson dependency are external requirements. Runtime classes and registered source bytes are in registration.zip. Prior archives retain inherited artifacts.')
    archive_matches(manifest)
    freeze(CACHE/'results-manifest.json', manifest)
    freeze(ROOT/'tools/capacity-followup/cache-manifest.json', manifest)
    print(json.dumps(dict(sealedResults=True, entries=len(entries), sha256=manifest['archive']['sha256'],
        measuredNativeRequests=proof['measuredNativeRequests'], warmupRequests=proof['warmupRequests'])), flush=True)


def verify_archive():
    manifest = read(CACHE/'results-manifest.json')
    assert manifest == read(ROOT/'tools/capacity-followup/cache-manifest.json')
    archive_matches(manifest)
    assert digest(ROOT/manifest['registrationArchive']['path']) == manifest['registrationArchive']['sha256']
    for item in manifest['predecessors']:
        assert digest(ROOT/item['archive']['path']) == item['archive']['sha256']
    print(json.dumps(dict(verifiedResultEntries=len(manifest['entries']), registrationUnchanged=True, predecessorsUnchanged=6)))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['seal', 'verify', 'check-exports'])
    {'seal': seal, 'verify': verify_archive, 'check-exports': verify_exports_and_exposure}[parser.parse_args().mode]()
