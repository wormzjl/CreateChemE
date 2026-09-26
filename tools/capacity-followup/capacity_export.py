"""Full-anchor depth exports with the predecessor's unchanged feature/decoder fixtures."""
from capacity_common import *
from export_models import fixture_rows, fixture_forward
from capacity_native import execute


def export(model, directory, name, checkpoint=None, plan=None):
    binding, document = base_binding()
    norm = normalization()
    document.update(modelId='capacity/' + name, featureRevision='v3-capacity-1',
                    modelType='capacity-transformer', layerCount=len(model.blocks),
                    initializationSourceSha256=binding['weights']['sha256'],
                    sourceCheckpointSha256=binding['checkpoint']['sha256'],
                    checkpointSha256=digest(checkpoint) if checkpoint else None,
                    trainingPlanSha256=digest(plan) if plan else None,
                    dataSha256=digest(SOURCE / 'N804.jsonl') if checkpoint else None,
                    fixtureDataSha256=digest(SOURCE / 'N804.jsonl'),
                    selection='Registered depth-capacity study; no retired-test selection.')
    document['weights'] = {key: dict(shape=list(value.shape), values=value.detach().cpu().numpy().ravel().tolist())
                           for key, value in model.state_dict().items()}
    freeze(directory / 'model.json', document)
    anchors = hybrid.load_anchors(ANCHORS)
    fixture_model = copy.deepcopy(model).double().eval()
    fixtures = [fixture_forward(fixture_model, row, norm, anchors, 'full') for row in fixture_rows()]
    freeze(directory / 'fixture.json', fixtures)
    pipeline = dict(revision='hybrid-pipeline-v1', id=document['modelId'], kind='capacity-transformer',
                    weights=info(directory / 'model.json'), materialCompletion=False)
    freeze(directory / 'pipeline.json', pipeline)
    return dict(**info(directory / 'pipeline.json'), weights=pipeline['weights'])


def parity(directory, model, suffix=''):
    java_proof=directory/f'java-parity{suffix}.json'
    execute('V3CapacityParityCheck', [directory / 'model.json', directory / 'fixture.json', java_proof],
            directory / f'java-parity{suffix}.log')
    native = read(java_proof)
    assert native['passed'] and native['exactMasks'] and native['predictionCancellationPropagated']
    assert native['scheduling']['terminated'] and native['scheduling']['distinctWorkerThreads'] == 10
    rows = fixture_rows(); batch = dataset(rows); fixtures = read(directory / 'fixture.json')
    with torch.no_grad():
        raw, branch = forward(model.cpu().float().eval(), batch, normalization())
    maxima = dict(raw=0., temperatureKelvin=0., flowOverFeed=0., branch=0.)
    for index, (row, fixture) in enumerate(zip(rows, fixtures)):
        count = row['input']['stageCount'] + 2
        values = raw[index, :count].numpy()
        maxima['raw'] = max(maxima['raw'], float(np.abs(values - fixture['raw']).max()))
        maxima['branch'] = max(maxima['branch'], float(np.abs(branch[index].numpy() - fixture['branchLogits']).max()))
        chosen = hybrid.base.BRANCHES[int(branch[index, :2].argmax())]
        assert chosen == fixture['branch']
        decoded = hybrid.factor.decode(row['input'], values, chosen, .02)
        target = fixture['decoded']; feed = sum(row['input']['feedComponentMolarFlowsMolPerSecond'])
        assert np.array_equal(decoded['wetTrays'], target['wetTrays'])
        maxima['temperatureKelvin'] = max(maxima['temperatureKelvin'], float(np.abs(decoded['temperatures'] - target['temperatures']).max()))
        for phase in ('liquid', 'vapor'):
            expected = np.asarray(target[phase])
            assert np.array_equal(decoded[phase] == 0, expected == 0), (row['id'], phase)
            maxima['flowOverFeed'] = max(maxima['flowOverFeed'], float(np.abs(decoded[phase] - expected).max() / feed))
        maxima['flowOverFeed'] = max(maxima['flowOverFeed'], float(np.abs(decoded['freeWater'] - target['freeWater']).max() / feed))
    # Float32 versus float64 follows the predecessor's exact-mask rule; maxima remain explicit evidence.
    proof = dict(passed=True, layerCount=len(model.blocks), fixtures=len(rows), exactMasks=True,
                 maximumFloat32Differences=maxima, model=info(directory / 'model.json'),
                 fixturesFile=info(directory / 'fixture.json'), javaParity=info(java_proof))
    freeze(directory / f'precision-check{suffix}.json', proof)
    print(f'Native parity passed for {directory.name}: {len(model.blocks)} layers, exact masks, ten owned workers.', flush=True)
    return proof
