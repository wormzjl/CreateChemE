"""Render the complete capacity results from reconstructed evidence."""
from capacity_common import *


def number(value, digits=2):
    return 'unavailable' if value is None else f'{value:.{digits}f}'


def table(lines, headings, rows):
    lines.extend(['', '| '+' | '.join(headings)+' |', '| '+' | '.join(['---']*len(headings))+' |'])
    lines.extend('| '+' | '.join(map(str, row))+' |' for row in rows)
    lines.append('')


def build():
    plan = read(OUT/'training-plan.json')
    screen, val = [read(OUT/f'{stage}-analysis.json') for stage in ('screen', 'validation')]
    selection = read(OUT/'selection.json')
    fits = {f'{arm}-{seed}': read(OUT/'fits'/f'{arm}-{seed}'/'report.json')
        for seed in plan['training']['seeds'] for arm in plan['armOrder']}
    qualified = {control: sorted(name for name, value in val['qualification'][control].items() if value['passed']) for control in ('F0', 'I')}
    summary = dict(revision='capacity-report-summary-v1', strongReplicatedDepthBenefit=val['strongReplicatedDepthBenefit'],
        fixedDepthGates=val['fixedDepthGates'], qualifiedAgainstControls=qualified,
        selected=selection['nativeSelections'], newFits=4, checkpoints=12,
        measuredNativeRequests=screen['measuredRequests']+val['measuredRequests'],
        warmupRequests=screen['warmupRequests']+val['warmupRequests'], independentValidationCases=405,
        newTestRequests=0, productionDefaultChanged=False)
    lines = ['# Four-layer hybrid capacity results', '',
        ('The four-layer model meets the registered replicated depth-benefit rule.' if val['strongReplicatedDepthBenefit'] else
         'The four-layer model does not meet the registered replicated depth-benefit rule.'), '',
        'This compares 156,440 parameters with 89,496 parameters at the same width. Both start with exactly the same full-anchor F0 predictions and receive matched minibatches and additional updates. Solver acceptance criteria, loss, decoder, support policy, data and request budgets remain unchanged.', '',
        'F0 is the preceding full-anchor model; I is the original Transformer. FIRST means neural initialization with the existing classical fallback; ONLY is a separately timed neural-only request. All success counts below require the unchanged strict audit.', '',
        f'Candidates meeting the further-qualification gates against F0: {", ".join(qualified["F0"]) or "none"}. Against I: {", ".join(qualified["I"]) or "none"}. Passing against I alone does not demonstrate improvement over F0. No production model was changed.', '',
        f'The campaign contains {summary["measuredNativeRequests"]:,} measured corrected-column requests and {summary["warmupRequests"]} TRAIN warmups. The two full-validation blocks repeat the same 405 inputs; they are not 810 independent cases. The 65-case screen and all validation references are historical, with no fresh test set.', '',
        '## Primary comparison: matched +2,080 updates']
    rows = []
    for seed, value in val['fixedDepthGates'].items():
        rows.append([seed, '/'.join(map(str, value['referenceStrictFirstCounts'])), '/'.join(map(str, value['strictFirstCounts'])),
            number(value['referencePooledFirstMeanMillis']), number(value['pooledFirstMeanMillis']),
            value['preservesClassicalUnionBothBlocks'], value['passed']])
    table(lines, ['Order seed', 'L2 FIRST blocks 1/2', 'L4 FIRST blocks 1/2', 'L2 all-case mean ms', 'L4 all-case mean ms', 'Preserves classical union', 'All gates pass'], rows)
    lines.extend(['A replicated benefit requires strict FIRST improvement for both order seeds in both blocks, preservation of the contemporaneous classical-success union, and no pooled mean FIRST latency regression for either matched pair. Equal update counts do not imply equal compute.', '',
        '## Complete validation counts and costs'])
    rows = []
    for name in val['pipelines']:
        for block in val['blocks']:
            modes = val['summaries'][f'{block}/{name}']['all']['modes']
            first = modes['neuralFirst']
            rows.append([name, block, *[modes[m]['outcomes'].get('strict', 0) for m in plan['strategies']],
                first['outcomes'].get('advisory', 0), first['outcomes'].get('failed', 0),
                number(first['milliseconds']['mean']), number(first['milliseconds']['p95']),
                number(first['cpuMillis']['mean'] if first['cpuMillis'] else None),
                number(first['allocatedBytes']['mean']/1024**2 if first['allocatedBytes'] else None), first['fallbackTrue']])
    table(lines, ['Pipeline', 'Block', 'Classical', 'ONLY', 'FIRST', 'FIRST advisory', 'FIRST failed', 'FIRST mean ms', 'FIRST p95 ms', 'FIRST CPU mean ms', 'FIRST allocation mean MiB', 'Fallback observed'], rows)
    lines.extend(['All costs include failed and advisory requests. Native anchor construction, inference and failed neural attempts consume the two-second neural allowance; fallback uses the remaining thirty-second request budget. All native runs use ten owned workers, sixteen correction iterations and a 4 GiB heap.', '',
        '## Paired gains and losses'])
    rows = []
    comparisons = {**val['contrasts'], **{f'vs_{control}/{name}': c for control, values in val['versusControls'].items() for name, c in values.items()}}
    for label, comparison in comparisons.items():
        for block, populations in comparison['blocks'].items():
            only, first = [populations['all'][mode] for mode in ('neural', 'neuralFirst')]
            rows.append([label, block, f'{only["gains"]}/{only["losses"]}', f'{first["gains"]}/{first["losses"]}'])
    table(lines, ['Candidate minus reference', 'Block', 'ONLY gains/losses', 'FIRST gains/losses'], rows)
    lines.extend(['These are paired case transitions, not just net counts. Fixed comparisons use equal exposure. Each selected comparison uses the independently screened checkpoint for that arm and seed and can compare different exposure. Case IDs for both-success, reference-only, candidate-only and neither, together with profile discrepancies and timing distributions, are preserved in validation-analysis.json.', '',
        '## Costs on stable common successes'])
    rows = []
    for label, modes in val['stableCommonSuccessCosts'].items():
        for mode, values in modes.items():
            a, b = values['reference']['milliseconds'], values['candidate']['milliseconds']
            rows.append([label, 'FIRST' if mode == 'neuralFirst' else 'ONLY', values['independentCases'],
                number(a['mean'] if a else None), number(b['mean'] if b else None),
                number(values['pairedMillis']['mean'] if values['pairedMillis'] else None)])
    table(lines, ['Comparison', 'Strategy', 'Same cases successful in both blocks', 'Reference mean ms', 'Candidate mean ms', 'Paired mean change ms'], rows)
    lines.extend(['These populations succeed under both pipelines in both blocks. Their cost is reported separately because a lower all-case mean can result from earlier failures. Repeated timings are descriptive, not independent samples for confidence claims.', '',
        '## Panel and remaining validation cases'])
    rows = []
    for name in val['pipelines']:
        for block in val['blocks']:
            for population in ('screening', 'remaining'):
                value = val['summaries'][f'{block}/{name}'][population]
                rows.append([name, block, population, value['cases'],
                    *[value['modes'][m]['outcomes'].get('strict', 0) for m in plan['strategies']],
                    number(value['modes']['neuralFirst']['milliseconds']['mean'])])
    table(lines, ['Pipeline', 'Block', 'Population', 'Cases', 'Classical', 'ONLY', 'FIRST', 'FIRST mean ms'], rows)
    lines.extend(['The panel was selected from historical outcomes. The remaining 340 inputs are also historical validation, not a new holdout.', '', '## Training and additional-block activity'])
    rows = []
    for name, fit in fits.items():
        points = [dict(step=0, **fit['initialMetrics'])]+fit['checkpoints']
        for point in points:
            rows.append([name, point['step'], number(point['train']['selectionScore']['mean'], 6),
                number(point['validation']['selectionScore']['mean'], 6)])
    table(lines, ['Fit', 'Additional updates', 'Train profile score', 'Validation profile score'], rows)
    lines.append('Both columns use the same per-column profile score; lower is better. They cover N804 and the same 168 certified validation references. Training loss is not treated as interchangeable with this score.')
    table(lines, ['Fit', 'Parameters', 'Training seconds including diagnostics', 'Peak allocated GPU MiB'],
        [[name, fit['parameters'], number(fit['trainingSeconds']), number(fit['peakAllocatedGpuBytes']/1024**2)] for name, fit in fits.items()])
    rows = []
    for name, fit in fits.items():
        for point in fit['checkpoints']:
            for block, value in point['addedBlocks']['blocks'].items():
                rows.append([name, point['step'], block, *[number(value[key], 6) for key in (
                    'attentionOutputWeightNorm', 'attentionOutputBiasNorm', 'feedForwardOutputWeightNorm',
                    'feedForwardOutputBiasNorm', 'trainProbeHiddenDifferenceL2', 'trainProbeHiddenDifferenceMaximum')]])
    table(lines, ['Fit', 'Update', 'Added block index', 'Attention W norm', 'Attention b norm', 'FF W norm', 'FF b norm', 'Hidden change L2', 'Hidden max change'], rows)
    lines.extend(['The new projection weights and biases start at zero. Activity is measured on the fixed first 32 TRAIN rows after each saved endpoint. The two added blocks copy the same trained basis initially; they are not independently random new layers. Fresh optimizer state, dropout zero, deterministic float32 CUDA and TF32 disabled apply to both arms.', '',
        '## Frozen checkpoint selection'])
    table(lines, ['Arm', 'Seed', 'Selected endpoint', 'Preserves screen classical union'],
        [[arm, seed, item['pipeline'], item['preservesClassicalUnion']] for arm, values in selection['nativeSelections'].items() for seed, item in values.items()])
    table(lines, ['Screen pipeline', 'ONLY / 65', 'FIRST / 65', 'FIRST mean ms'],
        [[name, len(record['strictOnlyIds']), len(record['strictFirstIds']), number(record['firstMeanMillis'])]
         for name, record in selection['records'].items()])
    lines.extend(['## Failure, profile and runtime evidence'])
    rows = []
    for name in val['pipelines']:
        for block in val['blocks']:
            value = val['summaries'][f'{block}/{name}']['all']
            first, pp = value['modes']['neuralFirst'], value['profiles']
            rows.append([name, block, json.dumps(first['statuses'], sort_keys=True),
                json.dumps(first['stopPhraseCounts'], sort_keys=True), json.dumps(first['terminalOwners'], sort_keys=True),
                pp['available'], pp['aboveFloorOmissions'], pp['positivePhaseComponentOmissions']])
    table(lines, ['Pipeline', 'Block', 'FIRST statuses', 'Observed stop phrases', 'Terminal owners', 'Available reference profiles', 'Above-floor omissions', 'Positive-phase omissions'], rows)
    rows = []
    for name in val['pipelines']:
        for block in val['blocks']:
            metadata = val['runMetadata'][f'{block}/{name}']
            rows.append([name, block, number(metadata['modelLoadMillis']), number(metadata['heapUsedAtEndBytes']/1024**2),
                json.dumps(metadata['poolPeakUsage'], sort_keys=True)])
    table(lines, ['Pipeline', 'Block', 'Model load ms', 'Heap used at end MiB', 'Memory-pool peaks'], rows)
    lines.extend(['Stop-phrase counts can overlap. Absent evidence remains unknown. FIRST terminal ownership is read from its own request; separately timed ONLY is not used to infer FIRST’s internal trajectory. Projected MESH maxima and terminal audit ratios depend on state and support and are not a single optimization objective.', '',
        'Native seed profiles, reference support omissions, temperature/traffic/component errors, initial projected residual distributions, per-mode CPU/allocation distributions, case service and queue times, and stage/steam/side-draw/heat-loop breakdowns are in the analysis and case/profile evidence files. Reference discrepancies do not prove that only one root or support pattern is admissible.', '',
        '## Verification and preservation', '',
        'Before fitting, all 905 TRAIN cases had exactly matching Python initial predictions, all 804 optimization cases matched in train and eval modes, and independent Java F0/L2/L4 predictions matched completely on all 804. Eight malformed model manifests were rejected. Eleven initialization/schedule tests passed. All twelve exports passed the original native numerical tolerances, exact masks, cancellation and forty shared predictions across ten workers.', '',
        'The native core was rebuilt from 113 archive-matched sources with only Gson on the compiler classpath. Recorded loaded-class fingerprints match the isolated runtime. Existing solver sources, prior model artifacts and the main checkout remain outside the experiment changes.', '',
        'Preflight failures are retained: sandbox access to the Gson cache failed; an initial full-source rebuild included an unused Minecraft-dependent probe and its error display hit an encoding failure. The corrected dependency closure and UTF-8 logging were verified before registration. These were preflight failures, not discarded training or native-evaluation results.', '',
        'The results sealer independently checks checkpoint/export tensors, optimizer endpoints, shuffled per-ID exposure, runtime and archive bindings, selection and validation locks, then exactly replays case/profile analyses and this report. Registration and all six predecessor archives are checked. No solver requests or new fits are created by analysis or sealing.', '',
        'Artifacts: training-plan.json; fits/*/report.json and full checkpoints; models.json and native parity proofs; selection.json; validation-plan.json; screen/ and validation/ journals; screen-analysis.json and validation-analysis.json; per-case and per-profile JSONL evidence; verification.json. The registration and results ZIP files are under .neural-cache/capacity-followup-v1.', '',
        'The historical quarantined target remains excluded from new training but its previous influence is inherited through F0. This experiment does not claim unlearning, independent test generalization, or that more width/depth would necessarily help.'])
    return summary, '\n'.join(lines)+'\n'


def main():
    summary, text = build()
    freeze(OUT/'report-summary.json', summary)
    (OUT/'report.md').write_text(text, encoding='utf-8')
    (ROOT/'tools/capacity-followup/results.md').write_text(text, encoding='utf-8')
    print(json.dumps(summary), flush=True)


if __name__ == '__main__': main()
