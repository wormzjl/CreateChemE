"""Render the committed summary JSON from the frozen analysis. Adds no new measurement."""
from floor_common import *

REFERENCE = 'F0-baseline'


def summary():
    analysis = read(OUT / 'validation-analysis.json')
    plan = read(OUT / 'study-plan.json')
    counts = {}
    for name in ORDER:
        counts[name] = dict(
            decoder=PIPELINES[name],
            classical=[analysis['classicalStrictCountsByRun'][f'{block}/{name}'] for block in BLOCKS],
            only=[analysis['summaries'][f'{block}/{name}']['modes']['neural']['outcomes'].get('strict', 0)
                  for block in BLOCKS],
            first=[analysis['summaries'][f'{block}/{name}']['modes']['neuralFirst']['outcomes'].get('strict', 0)
                   for block in BLOCKS],
            firstAdvisory=[analysis['summaries'][f'{block}/{name}']['modes']['neuralFirst']['outcomes'].get('advisory', 0)
                           for block in BLOCKS],
            firstFailed=[analysis['summaries'][f'{block}/{name}']['modes']['neuralFirst']['outcomes'].get('failed', 0)
                         for block in BLOCKS],
            allCaseFirstMeanMillis=[analysis['summaries'][f'{block}/{name}']['modes']['neuralFirst']['milliseconds']['mean']
                                    for block in BLOCKS],
            allCaseOnlyMeanMillis=[analysis['summaries'][f'{block}/{name}']['modes']['neural']['milliseconds']['mean']
                                   for block in BLOCKS],
            elapsedSeconds=[analysis['runMetadata'][f'{block}/{name}']['elapsedSeconds'] for block in BLOCKS],
            aboveFloorOmissions=analysis['referenceOmissions'][name]['aboveFloorOmissions'])
    result = dict(
        revision='decoder-floor-summary-v1', studyPlan=info(OUT / 'study-plan.json'),
        analysis=info(OUT / 'validation-analysis.json'), baseCommit=plan['baseCommit'],
        model=plan['model']['name'], weightsSha256=plan['model']['weights']['sha256'],
        cases=plan['cases'], blocks=plan['blocks'], strategies=STRATEGIES, workers=WORKERS,
        measuredRequests=analysis['measuredRequests'], warmupRequests=analysis['warmupRequests'],
        parity=dict(decodeSeedsReproduceArchive=read(OUT / 'preflight-parity.json')['defaultDecodeReproducesArchive'],
                    strictIdentitySetsReproduceArchive=analysis['archiveParity']['allIdentitySetsReproduced'],
                    perBlock={block: {mode: analysis['archiveParity'][block][mode]
                                      for mode in STRATEGIES}
                              for block in ('1', '2')}),
        pipelines=counts, decodeLiftEffect=analysis['decodeLiftEffect'],
        contrasts={name: {mode: dict(
            blocks=value['blocks'], reproducedAcrossBlocks=value['reproducedAcrossBlocks'],
            stableBothSuccessCases=value['stableBothSuccessCases'],
            stableCommonSuccessCosts=value['stableCommonSuccessCosts'],
            stablePairedMillis=value['stablePairedMillis'],
            allCaseMeanMillis=value['allCaseMeanMillis'])
            for mode, value in analysis['contrasts'][name]['byMode'].items()}
            for name in ORDER if name != REFERENCE},
        qualification=analysis['qualification'],
        anyVariantPasses=any(value['passed'] for value in analysis['qualification'].values()),
        transitions=analysis['transitions'],
        classicalStrictUnionCases=len(analysis['classicalStrictUnionIds']),
        classicalStableAcrossEveryRun=analysis['classicalStableAcrossEveryRun'],
        productionDefaultChanged=False, automaticPromotion=False)
    freeze(OUT / 'report-summary.json', result)
    exact_copy(OUT / 'report-summary.json', ROOT / 'tools/decoder-floor-followup/summary.json')
    print(json.dumps(dict(written='report-summary.json', anyVariantPasses=result['anyVariantPasses'],
                          parity=result['parity']['strictIdentitySetsReproduceArchive'])), flush=True)
    return result


if __name__ == '__main__':
    summary()
