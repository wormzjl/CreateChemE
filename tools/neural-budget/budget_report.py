"""Render the committed summary JSON from the frozen analysis. Adds no new measurement."""
from budget_common import *

REFERENCE = 'F0-baseline'


def summary():
    analysis = read(OUT / 'validation-analysis.json')
    plan = read(OUT / 'study-plan.json')
    counts = {}
    for name in ORDER:
        counts[name] = dict(
            decoder=PIPELINES[name]['decoder'], correction=PIPELINES[name]['correction'],
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
            onlyNewtonIterations=[analysis['summaries'][f'{block}/{name}']['modes']['neural']['newtonIterations']
                                  for block in BLOCKS],
            elapsedSeconds=[analysis['runMetadata'][f'{block}/{name}']['elapsedSeconds'] for block in BLOCKS],
            aboveFloorOmissions=analysis['referenceOmissions'][name]['aboveFloorOmissions'])
    diagnostic = OUT / 'trace-analysis.json'
    result = dict(
        revision='neural-budget-summary-v1', studyPlan=info(OUT / 'study-plan.json'),
        analysis=info(OUT / 'validation-analysis.json'), baseCommit=plan['baseCommit'],
        model=plan['model']['name'], weightsSha256=plan['model']['weights']['sha256'],
        cases=plan['cases'], blocks=plan['blocks'], strategies=STRATEGIES, workers=WORKERS,
        measuredRequests=analysis['measuredRequests'], warmupRequests=analysis['warmupRequests'],
        diagnostic=(dict(artifact=info(diagnostic), **{key: read(diagnostic)[key]
                                                       for key in ('cases', 'convergedByConfiguration', 'decision')})
                    if diagnostic.exists() else None),
        parity=dict(decodeSeedsReproduceArchive=read(OUT / 'preflight-parity.json')['defaultDecodeReproducesArchive'],
                    strictIdentitySetsReproduceArchive=analysis['archiveParity']['allIdentitySetsReproduced'],
                    perBlock={block: {mode: analysis['archiveParity'][block][mode] for mode in STRATEGIES}
                              for block in ('1', '2')}),
        pipelines=counts, decodeDifference=analysis['decodeDifference'],
        contrasts={name: {mode: dict(
            blocks=value['blocks'], reproducedAcrossBlocks=value['reproducedAcrossBlocks'],
            stableBothSuccessCases=value['stableBothSuccessCases'],
            stableCommonSuccessCosts=value['stableCommonSuccessCosts'],
            stablePairedMillis=value['stablePairedMillis'],
            stableFailureCosts=value['stableFailureCosts'],
            allCaseMeanMillis=value['allCaseMeanMillis'])
            for mode, value in analysis['contrasts'][name]['byMode'].items()}
            for name in ORDER if name != REFERENCE},
        qualification=analysis['qualification'],
        anyVariantPasses=any(value['passed'] for value in analysis['qualification'].values()),
        registeredGroups=analysis['registeredGroups'],
        classicalStrictUnionCases=len(analysis['classicalStrictUnionIds']),
        classicalStableAcrossEveryRun=analysis['classicalStableAcrossEveryRun'],
        productionDefaultChanged=False, automaticPromotion=False)
    freeze(OUT / 'report-summary.json', result)
    exact_copy(OUT / 'report-summary.json', ROOT / 'tools/neural-budget/summary.json')
    print(json.dumps(dict(written='report-summary.json', anyVariantPasses=result['anyVariantPasses'],
                          parity=result['parity']['strictIdentitySetsReproduceArchive'])), flush=True)
    return result


if __name__ == '__main__':
    summary()
