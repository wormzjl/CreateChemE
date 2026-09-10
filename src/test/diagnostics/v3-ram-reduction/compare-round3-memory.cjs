const fs = require('node:fs');
const assert = require('node:assert/strict');
const root = 'build/reports/v3-ram-reduction/';
const referenceRows = JSON.parse(fs.readFileSync(root + 'round3-baseline-1.json', 'utf8'));
const summary = { checked: 0, completedRuns: 0, outOfMemoryRuns: 0, deadlineRuns: 0, runs: [] };
const median = values => [...values].sort((a, b) => a - b)[Math.floor(values.length / 2)];
function readRun(label, heap, mode, variant, pair) {
    const name = `round3-memory-${label}-${heap}-${mode}-${variant}${pair ? '-' + pair : ''}`;
    const r = JSON.parse(fs.readFileSync(root + name + '.json', 'utf8'));
    const reference = referenceRows.find(row => row.case === label);
    const item = { case: label, heap, mode, variant, pair, status: r.status };
    assert.ok(r.vmArguments.includes('-DprobeDeadlineMillis=45000'));
    assert.ok(r.vmArguments.includes('-Xmx' + heap));
    assert.equal(r.warmupCount, mode === 'cold' ? 0 : 3);
    for (const row of r.samples) {
        assert.equal(row.kind, 'SUCCESS');
        assert.equal(row.auditPassed, true);
        assert.deepEqual(row.streams, reference.streams);
        assert.deepEqual(row.certificate, reference.evidence);
        assert.deepEqual(row.diagnostics, reference.diagnostics);
        summary.checked++;
    }
    if (r.status === 'COMPLETED') {
        assert.equal(r.samples.length, mode === 'cold' ? 1 : mode === 'long' ? 10 : 3);
        Object.assign(item, {
            meanWallMs: r.samples.reduce((sum, row) => sum + row.wallMs, 0) / r.samples.length,
            meanAllocatedMiB: r.samples.reduce((sum, row) => sum + row.allocatedBytes, 0) / r.samples.length / 1048576,
            peakHeapMiB: Math.max(...r.samples.map(row => row.peakSampledHeapUsed)) / 1048576,
            peakMeasuredResidentMiB: Math.max(...r.samples.map(row => row.peakSampledWorkingSet)) / 1048576,
            beforeMeasurementsResidentPeakMiB: r.before.lifetimePeakWorkingSet / 1048576,
            peakResidentMiB: r.afterBeforeGC.lifetimePeakWorkingSet / 1048576,
            afterGcHeapMiB: r.afterForcedGC.heapUsed / 1048576
        });
        if (mode === 'long') Object.assign(item, {
            firstThreeMedianMs: median(r.samples.slice(0, 3).map(row => row.wallMs)),
            lastFiveMedianMs: median(r.samples.slice(-5).map(row => row.wallMs))
        });
        summary.completedRuns++;
    } else if (r.status === 'OUT_OF_MEMORY') {
        item.oomFrames = r.oomFrames;
        summary.outOfMemoryRuns++;
    } else {
        assert.equal(r.status, 'DEADLINE');
        summary.deadlineRuns++;
    }
    summary.runs.push(item);
    return item;
}
for (const label of ['Default', 'DefaultPressureLow5']) {
    for (const heap of ['32m', '40m', '48m', '64m', '512m']) {
        const baseline = readRun(label, heap, 'warm', 'baseline');
        const candidate = readRun(label, heap, 'warm', 'candidate');
        if (baseline.status === 'COMPLETED' || heap === '512m') assert.equal(candidate.status, 'COMPLETED');
    }
    for (const pair of [1, 2]) for (const variant of ['baseline', 'candidate']) {
        assert.equal(readRun(label, '512m', 'cold', variant, pair).status, 'COMPLETED');
    }
}
for (const variant of ['baseline', 'candidate']) assert.equal(readRun('Default', '512m', 'long', variant).status, 'COMPLETED');
fs.writeFileSync(root + 'round3-memory-comparison.json', JSON.stringify(summary, null, 2));
console.log(`${summary.checked} exact successful memory outcomes; ${summary.completedRuns} completed JVMs, `
    + `${summary.outOfMemoryRuns} OOMs and ${summary.deadlineRuns} deadlines at constrained heaps.`);
console.table(summary.runs.map(({ oomFrames, ...item }) => item));
