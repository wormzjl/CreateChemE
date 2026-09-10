const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = 'build/reports/v3-ram-reduction';
const reference = JSON.parse(fs.readFileSync(path.join(root, 'default-candidate-1.json'), 'utf8'))[0];
const groups = new Map();
let weight = 0, allocated = 0, samples = 0, checked = 0, doubleArrays = 0;
for (const run of ['1', '2']) {
    const measured = JSON.parse(fs.readFileSync(path.join(root, `default-allocation-${run}.json`), 'utf8'));
    const profile = JSON.parse(fs.readFileSync(path.join(root, `default-allocation-${run}-summary.json`), 'utf8'));
    assert.equal(measured.status, 'COMPLETED');
    assert.equal(measured.samples.length, 5);
    for (const row of measured.samples) {
        assert.equal(row.kind, 'SUCCESS');
        assert.equal(row.auditPassed, true);
        assert.deepEqual(row.streams, reference.streams);
        assert.deepEqual(row.certificate, reference.evidence);
        assert.deepEqual(row.diagnostics, reference.diagnostics);
        checked++;
    }
    const bytes = measured.samples.reduce((sum, row) => sum + row.allocatedBytes, 0);
    assert.ok(Math.abs(profile.estimatedAllocationWeight / bytes - 1) < 0.05, 'Allocation sampling failed its counter cross-check');
    allocated += bytes;
    weight += profile.estimatedAllocationWeight;
    samples += profile.allocationSamples;
    doubleArrays += profile.classes['[D'];
    for (const [group, bytes] of Object.entries(profile.groups)) groups.set(group, (groups.get(group) || 0) + bytes);
}
const result = { checkedOutcomes: checked, allocationSamples: samples, measuredMiBPerSolve: allocated / checked / 1048576,
    estimatedWeightToCounterRatio: weight / allocated, doubleArrayPercent: 100 * doubleArrays / weight,
    groups: [...groups.entries()].sort((a, b) => b[1] - a[1]).map(([group, bytes]) => ({
        group, estimatedPercent: 100 * bytes / weight, estimatedMiBPerSolve: bytes / checked / 1048576 })) };
fs.writeFileSync(path.join(root, 'default-allocation-breakdown.json'), JSON.stringify(result, null, 2));
console.log(JSON.stringify(result, null, 2));
