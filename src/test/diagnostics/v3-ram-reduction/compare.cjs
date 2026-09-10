const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = process.argv[2] || 'build/reports/v3-ram-reduction';
const names = ['baseline-1', 'candidate-1', 'baseline-2', 'candidate-2'];
const reports = Object.fromEntries(names.map(name => [name, JSON.parse(fs.readFileSync(path.join(root, name + '.json'), 'utf8'))]));
const median = values => {
    const ordered = [...values].sort((a, b) => a - b);
    const middle = Math.floor(ordered.length / 2);
    return ordered.length % 2 ? ordered[middle] : (ordered[middle - 1] + ordered[middle]) / 2;
};
const payload = row => Object.fromEntries(['kind', 'diagnostics', 'streams', 'evidence', 'audit', 'duty'].map(key => [key, row[key]]));
const summary = { checked: 0, cases: [] };
for (const label of ['A', 'B', 'C', 'D', 'E', 'Holland', 'C64']) {
    const reference = reports['baseline-1'].find(row => row.case === label);
    const item = { case: label, runs: {} };
    for (const name of names) {
        const samples = reports[name].filter(row => row.case === label);
        assert.equal(samples.length, 7);
        for (const sample of samples) {
            assert.equal(sample.kind, 'SUCCESS');
            assert.ok(sample.audit.checks.every(check => check.passed));
            assert.deepEqual(payload(sample), payload(reference), `${name}/${label}/${sample.sample}`);
            summary.checked++;
        }
        item.runs[name] = { wallMs: median(samples.map(row => row.wallMs)), cpuMs: median(samples.map(row => row.cpuMs)),
            allocatedMiB: median(samples.map(row => row.allocatedBytes)) / 1048576 };
    }
    summary.cases.push(item);
}
summary.broadChecked = summary.checked;
summary.holland = [];
const hollandReference = reports['baseline-1'].find(row => row.case === 'Holland');
for (const name of names) {
    const samples = JSON.parse(fs.readFileSync(path.join(root, 'holland-' + name + '.json'), 'utf8'));
    assert.equal(samples.length, 40);
    for (const sample of samples) {
        assert.deepEqual(payload(sample), payload(hollandReference));
        summary.checked++;
    }
    summary.holland.push({ run: name, samples: samples.length, medianWallMs: median(samples.map(row => row.wallMs)),
        meanWallMs: samples.reduce((sum, row) => sum + row.wallMs, 0) / samples.length,
        allocatedMiB: median(samples.map(row => row.allocatedBytes)) / 1048576 });
}
summary.memory = [];
const memoryReference = reports['baseline-1'].find(row => row.case === 'C64');
for (const heap of ['64m', '128m', '512m']) {
    for (const variant of ['baseline', 'candidate']) {
        const r = JSON.parse(fs.readFileSync(path.join(root, `memory-C64-${heap}-${variant}.json`), 'utf8'));
        if (heap === '64m' && variant === 'baseline') {
            assert.equal(r.status, 'OUT_OF_MEMORY');
            summary.memory.push({ heap, variant, status: r.status });
            continue;
        }
        assert.equal(r.status, 'COMPLETED');
        assert.equal(r.samples.length, 3);
        for (const sample of r.samples) {
            assert.equal(sample.kind, 'SUCCESS');
            assert.equal(sample.auditPassed, true);
            assert.deepEqual(sample.streams, memoryReference.streams);
            assert.deepEqual(sample.certificate, memoryReference.evidence);
            assert.deepEqual(sample.diagnostics, memoryReference.diagnostics);
            summary.checked++;
        }
        summary.memory.push({ heap, variant, status: r.status,
            peakSampledHeapMiB: Math.max(...r.samples.map(row => row.peakSampledHeapUsed)) / 1048576,
            peakProcessWorkingSetMiB: r.afterBeforeGC.lifetimePeakWorkingSet / 1048576,
            peakSolveWorkingSetMiB: Math.max(...r.samples.map(row => row.peakSampledWorkingSet)) / 1048576,
            afterGcHeapMiB: r.afterForcedGC.heapUsed / 1048576,
            medianWallMs: median(r.samples.map(row => row.wallMs)),
            meanAllocatedMiB: r.samples.reduce((sum, row) => sum + row.allocatedBytes, 0) / r.samples.length / 1048576 });
    }
}
summary.memoryConfirmation = [];
for (const variant of ['baseline', 'candidate']) {
    const r = JSON.parse(fs.readFileSync(path.join(root, `memory-C64-512m-${variant}-confirm.json`), 'utf8'));
    assert.equal(r.status, 'COMPLETED');
    assert.equal(r.samples.length, 3);
    for (const sample of r.samples) {
        assert.equal(sample.kind, 'SUCCESS');
        assert.equal(sample.auditPassed, true);
        assert.deepEqual(sample.streams, memoryReference.streams);
        assert.deepEqual(sample.certificate, memoryReference.evidence);
        assert.deepEqual(sample.diagnostics, memoryReference.diagnostics);
        summary.checked++;
    }
    summary.memoryConfirmation.push({ variant,
        peakProcessWorkingSetMiB: r.afterBeforeGC.lifetimePeakWorkingSet / 1048576,
        peakSolveWorkingSetMiB: Math.max(...r.samples.map(row => row.peakSampledWorkingSet)) / 1048576,
        afterGcHeapMiB: r.afterForcedGC.heapUsed / 1048576 });
}
fs.writeFileSync(path.join(root, 'comparison.json'), JSON.stringify(summary, null, 2));
console.log(`All ${summary.checked} successful outcomes match baseline exactly. The baseline C64/64 MiB OOM is separately confirmed.`);
