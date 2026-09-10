const fs = require('node:fs');
const assert = require('node:assert/strict');
const root = 'build/reports/v3-ram-reduction/';
const timing = JSON.parse(fs.readFileSync(root + 'round2-baseline-1.json', 'utf8'));
const summary = { checked: 0, runs: [] };
function checkRun(label, heap, variant, suffix = '') {
    const reference = timing.find(row => row.case === label);
    const name = `round2-memory-${label}-${heap}-${variant}${suffix}`;
    const r = JSON.parse(fs.readFileSync(root + name + '.json', 'utf8'));
    assert.equal(r.status, 'COMPLETED');
    assert.equal(r.samples.length, 3);
    assert.ok(r.vmArguments.includes('-DprobeDeadlineMillis=45000'));
    assert.ok(r.vmArguments.includes('-Xmx' + heap));
    for (const row of r.samples) {
        assert.equal(row.kind, 'SUCCESS');
        assert.equal(row.auditPassed, true);
        assert.deepEqual(row.streams, reference.streams);
        assert.deepEqual(row.certificate, reference.evidence);
        assert.deepEqual(row.diagnostics, reference.diagnostics);
        summary.checked++;
    }
    summary.runs.push({ case: label, heap, variant, repeat: suffix !== '',
        peakHeapMiB: Math.max(...r.samples.map(row => row.peakSampledHeapUsed)) / 1048576,
        peakMeasuredResidentMiB: Math.max(...r.samples.map(row => row.peakSampledWorkingSet)) / 1048576,
        beforeMeasurementsResidentPeakMiB: r.before.lifetimePeakWorkingSet / 1048576,
        peakResidentMiB: r.afterBeforeGC.lifetimePeakWorkingSet / 1048576,
        afterGcHeapMiB: r.afterForcedGC.heapUsed / 1048576,
        meanAllocatedMiB: r.samples.reduce((sum, row) => sum + row.allocatedBytes, 0) / r.samples.length / 1048576 });
}
for (const label of ['Default', 'DefaultRefluxLow']) {
    for (const heap of ['128m', '512m']) {
        for (const variant of ['baseline', 'candidate']) checkRun(label, heap, variant);
    }
}
for (const variant of ['baseline', 'candidate']) checkRun('DefaultRefluxLow', '512m', variant, '-repeat');
fs.writeFileSync(root + 'round2-memory-comparison.json', JSON.stringify(summary, null, 2));
console.log(`${summary.checked} heap-capped outcomes match the timing baseline exactly.`);
console.table(summary.runs);
