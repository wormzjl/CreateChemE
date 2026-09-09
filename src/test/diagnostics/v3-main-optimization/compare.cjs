// Compare recorded public outputs, then summarize timings without asserting a speedup.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const directory = process.argv[2] || 'build/reports/main-optimization';
const runs = Object.fromEntries(['baseline-1', 'baseline-2', 'flash-1', 'flash-2', 'bounded-1', 'bounded-2']
    .map(name => [name, JSON.parse(fs.readFileSync(path.join(directory, name + '.json'), 'utf8'))]));
const median = values => {
    const sorted = [...values].sort((a, b) => a - b);
    const middle = Math.floor(sorted.length / 2);
    return sorted.length % 2 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
};
const payload = row => Object.fromEntries(['kind', 'diagnostics', 'streams', 'evidence', 'audit', 'duty'].map(key => [key, row[key]]));
const summary = { comparisons: 0, flashExactComparisons: 0, unaffectedBoundedComparisons: 0, cases: [] };
for (const label of ['A', 'B', 'C', 'D', 'E', 'Holland']) {
    const baseline = runs['baseline-1'].find(row => row.case === label);
    const result = { case: label, measurements: {}, maxError: { temperatureKelvin: 0, molarFlowRelative: 0,
        massFlowRelative: 0, moleFraction: 0, massFraction: 0, vaporFraction: 0, dutyRelative: 0 } };
    const error = result.maxError;
    for (const [name, rows] of Object.entries(runs)) {
        const selected = rows.filter(row => row.case === label);
        result.measurements[name] = { samples: selected.length, medianWallMs: median(selected.map(row => row.wallMs)),
            medianCpuMs: median(selected.map(row => row.cpuMs)), medianAllocatedBytes: median(selected.map(row => row.allocatedBytes)) };
        for (const row of selected) {
            assert.equal(row.kind, 'SUCCESS');
            assert.equal(row.diagnostics.solvePath, baseline.diagnostics.solvePath);
            assert.ok(row.audit.checks.every(check => check.passed));
            const evidence = row.evidence;
            assert.equal(evidence.closureTolerance, 1e-8);
            assert.ok(evidence.hasFinalNewtonStep && evidence.finalLinearBackwardError <= 1e-12
                && evidence.maximumLogFlowChange <= 1e-8 && evidence.maximumTemperatureStepRatio <= 1);
            assert.ok(row.diagnostics.maximumScaledResidual <= 1e-8);
            summary.comparisons++;
            if (!name.startsWith('bounded')) {
                assert.deepEqual(payload(row), payload(baseline));
                summary.flashExactComparisons++;
            } else if (label !== 'C') {
                assert.deepEqual(payload(row), payload(baseline));
                summary.unaffectedBoundedComparisons++;
            }
            assert.equal(row.streams.length, baseline.streams.length);
            row.streams.forEach((stream, index) => {
                const old = baseline.streams[index];
                assert.equal(stream.streamId, old.streamId);
                assert.equal(stream.phase, old.phase);
                assert.equal(stream.pressurePascal, old.pressurePascal);
                error.temperatureKelvin = Math.max(error.temperatureKelvin, Math.abs(stream.temperatureKelvin - old.temperatureKelvin));
                error.molarFlowRelative = Math.max(error.molarFlowRelative, Math.abs(stream.molarFlowMolPerSecond / old.molarFlowMolPerSecond - 1));
                error.massFlowRelative = Math.max(error.massFlowRelative, Math.abs(stream.massFlowKgPerSecond / old.massFlowKgPerSecond - 1));
                error.vaporFraction = Math.max(error.vaporFraction, Math.abs(stream.vaporMoleFraction - old.vaporMoleFraction));
                assert.equal(stream.moleFractions.length, old.moleFractions.length);
                stream.moleFractions.forEach((fraction, component) => {
                    const original = old.moleFractions[component];
                    assert.equal(fraction.componentId, original.componentId);
                    error.moleFraction = Math.max(error.moleFraction, Math.abs(fraction.moleFraction - original.moleFraction));
                    error.massFraction = Math.max(error.massFraction, Math.abs(fraction.massFraction - original.massFraction));
                });
            });
            if (row.duty) {
                assert.deepEqual(row.duty.stageDuties, baseline.duty.stageDuties);
                for (const key of ['condenserWatts', 'reboilerWatts', 'stageHeatTotalWatts', 'feedEnthalpyWatts', 'steamEnthalpyWatts']) {
                    error.dutyRelative = Math.max(error.dutyRelative, Math.abs(row.duty[key] - baseline.duty[key]) / Math.max(1, Math.abs(baseline.duty[key])));
                }
            }
        }
    }
    // Conservative product comparison limits, separate from the solver's native acceptance audit.
    assert.ok(error.temperatureKelvin <= 1e-9);
    for (const key of ['molarFlowRelative', 'massFlowRelative', 'dutyRelative']) assert.ok(error[key] <= 1e-9);
    for (const key of ['moleFraction', 'massFraction', 'vaporFraction']) assert.ok(error[key] <= 1e-10);
    summary.cases.push(result);
}
summary.controlled = [];
for (const name of ['old-1', 'new-1', 'old-2', 'new-2']) {
    const report = path.join(directory, 'controlled-' + name + '.json');
    if (!fs.existsSync(report)) continue;
    const rows = JSON.parse(fs.readFileSync(report, 'utf8'));
    for (const label of ['A', 'C', 'Holland']) {
        const selected = rows.filter(row => row.case === label);
        const expected = runs[name.startsWith('old') ? 'flash-1' : 'bounded-1'].find(row => row.case === label);
        for (const row of selected) assert.deepEqual(payload(row), payload(expected));
        summary.controlled.push({ run: name, case: label, exactComparisons: selected.length,
            medianWallMs: median(selected.map(row => row.wallMs)),
            medianCpuMs: median(selected.map(row => row.cpuMs)),
            medianAllocatedBytes: median(selected.map(row => row.allocatedBytes)) });
    }
}
fs.writeFileSync(path.join(directory, 'analysis-summary.json'), JSON.stringify(summary, null, 2) + '\n');
console.log(`Validated ${summary.comparisons} public results; ${summary.flashExactComparisons} exact baseline/flash comparisons and ${summary.unaffectedBoundedComparisons} exact unaffected bounded comparisons.`);
