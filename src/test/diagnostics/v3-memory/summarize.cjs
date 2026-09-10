const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const dir = process.argv[2] || 'build/reports/v3-memory';
const names = ['A-512m', 'B-512m', 'C-512m', 'D-512m', 'E-512m', 'Holland-512m', 'C64-512m',
    'C-64m', 'C-128m', 'C-256m', 'C-1g', 'C64-64m', 'C64-128m', 'C64-256m', 'C64-1g',
    'C-repeat20', 'C-profile-qualified', 'C64-profile-qualified'];
const baseline = new Map();
const rows = [];
let checked = 0;
const mib = bytes => bytes / 1048576;
const mean = values => values.reduce((sum, value) => sum + value, 0) / values.length;
for (const name of names) {
    const r = JSON.parse(fs.readFileSync(path.join(dir, name + '.json'), 'utf8'));
    if (r.status === 'OUT_OF_MEMORY') {
        assert.equal(name, 'C64-64m');
        rows.push({ run: name, status: r.status, frames: r.oomFrames });
        continue;
    }
    assert.equal(r.status, 'COMPLETED');
    for (const sample of r.samples) {
        assert.equal(sample.kind, 'SUCCESS');
        assert.equal(sample.auditPassed, true);
        assert.equal(sample.certificate.hasFinalNewtonStep, true);
        assert.ok(sample.certificate.finalLinearBackwardError <= 1e-12);
        assert.ok(sample.certificate.maximumLogFlowChange <= 1e-8);
        assert.ok(sample.certificate.maximumTemperatureStepRatio <= 1);
        const payload = { streams: sample.streams, certificate: sample.certificate, diagnostics: sample.diagnostics };
        if (!baseline.has(r.case)) baseline.set(r.case, payload);
        assert.deepEqual(payload, baseline.get(r.case), name);
        assert.ok(sample.peakSampledHeapUsed <= r.heapMaximum);
        assert.ok(sample.peakSampledHeapUsed <= sample.peakSampledHeapCommitted);
        checked++;
    }
    rows.push({ run: name, status: r.status, samples: r.samples.length, heapLimitMiB: mib(r.heapMaximum),
        peakSampledHeapMiB: mib(Math.max(...r.samples.map(s => s.peakSampledHeapUsed))),
        peakSampledWorkingSetMiB: mib(Math.max(...r.samples.map(s => s.peakSampledWorkingSet))),
        peakProcessWorkingSetMiB: mib(r.afterBeforeGC.lifetimePeakWorkingSet),
        peakSampledPrivateMiB: mib(Math.max(...r.samples.map(s => s.peakSampledPrivateBytes))),
        beforeUsedMiB: mib(r.before.heapUsed), afterForcedGcUsedMiB: mib(r.afterForcedGC.heapUsed),
        meanAllocatedMiB: mib(mean(r.samples.map(s => s.allocatedBytes))),
        meanWallMs: mean(r.samples.map(s => s.wallMs)), meanGcMs: mean(r.samples.map(s => s.gcMillis)),
        meanGcCount: mean(r.samples.map(s => s.gcCount)) });
}
fs.writeFileSync(path.join(dir, 'summary.json'), JSON.stringify({ checkedSuccessfulOutcomes: checked, rows }, null, 2));
console.log(`Validated ${checked} successful measured outcomes, exact per-case agreement across heaps/profiling/repetition, plus the expected C64/64 MiB OOM.`);
