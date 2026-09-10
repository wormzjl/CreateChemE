const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = 'build/reports/v3-ram-reduction';
const names = ['baseline-1', 'candidate-1', 'baseline-2', 'candidate-2'];
const payload = row => Object.fromEntries(['kind', 'diagnostics', 'streams', 'evidence', 'audit', 'duty'].map(key => [key, row[key]]));
const median = values => [...values].sort((a, b) => a - b)[Math.floor(values.length / 2)];
let reference;
const summary = { checked: 0, timing: [], memory: [] };
for (const name of names) {
    const rows = JSON.parse(fs.readFileSync(path.join(root, 'default-' + name + '.json'), 'utf8'));
    assert.equal(rows.length, 7);
    for (const row of rows) {
        assert.equal(row.case, 'Default');
        assert.equal(row.kind, 'SUCCESS');
        assert.ok(row.audit.checks.every(check => check.passed));
        assert.equal(row.evidence.closureTolerance, 1e-8);
        assert.ok(row.evidence.hasFinalNewtonStep && row.evidence.finalLinearBackwardError <= 1e-12
            && row.evidence.maximumLogFlowChange <= 1e-8 && row.evidence.maximumTemperatureStepRatio <= 1);
        reference ??= row;
        assert.deepEqual(payload(row), payload(reference));
        summary.checked++;
    }
    summary.timing.push({ run: name, medianWallMs: median(rows.map(row => row.wallMs)),
        medianAllocatedMiB: median(rows.map(row => row.allocatedBytes)) / 1048576 });
}
for (const variant of ['baseline', 'candidate']) {
    const r = JSON.parse(fs.readFileSync(path.join(root, 'default-memory-' + variant + '.json'), 'utf8'));
    assert.equal(r.status, 'COMPLETED');
    assert.ok(r.input.includes('stageCount=40'));
    assert.ok(r.vmArguments.includes('-DprobeDeadlineMillis=45000'));
    for (const row of r.samples) {
        assert.equal(row.kind, 'SUCCESS');
        assert.equal(row.auditPassed, true);
        assert.deepEqual(row.streams, reference.streams);
        assert.deepEqual(row.certificate, reference.evidence);
        assert.deepEqual(row.diagnostics, reference.diagnostics);
        summary.checked++;
    }
    summary.memory.push({ variant, peakHeapMiB: Math.max(...r.samples.map(row => row.peakSampledHeapUsed)) / 1048576,
        peakResidentMiB: r.afterBeforeGC.lifetimePeakWorkingSet / 1048576,
        afterGcHeapMiB: r.afterForcedGC.heapUsed / 1048576,
        meanAllocatedMiB: r.samples.reduce((sum, row) => sum + row.allocatedBytes, 0) / r.samples.length / 1048576 });
}
summary.solvePath = reference.diagnostics.solvePath;
summary.maximumScaledResidual = reference.diagnostics.maximumScaledResidual;
summary.waterDewPoint = reference.audit.checks.find(check => check.family === 'WATER_DEW_POINT');
fs.writeFileSync(path.join(root, 'default-comparison.json'), JSON.stringify(summary, null, 2));
console.log(`${summary.checked} default-preset outcomes match exactly, including warning/audit values and convergence evidence.`);
