const fs = require('node:fs');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const root = 'build/reports/v3-continuation/';
const data = JSON.parse(fs.readFileSync(root + 'profile.json', 'utf8'));
const reference = JSON.parse(fs.readFileSync(root + 'reference.json', 'utf8'));
const broad = JSON.parse(fs.readFileSync('build/reports/v3-ram-reduction/round3-candidate-2.json', 'utf8'));
const witness = JSON.parse(fs.readFileSync(root + 'unprofiled-nearby.json', 'utf8'));
const manifest = JSON.parse(fs.readFileSync('build/generated/continuation-profile/manifest.json', 'utf8'));
for (const item of manifest) {
    const source = fs.readFileSync(item.source, 'utf8').replaceAll('\r\n', '\n');
    assert.equal(crypto.createHash('sha256').update(source).digest('hex'), item.sha256, item.source);
}
const payload = row => Object.fromEntries(['input', 'kind', 'diagnostics', 'streams', 'audit', 'evidence', 'duty', 'failure']
    .map(key => [key, row[key]]));
function accepted(row) {
    assert.equal(row.kind, 'SUCCESS');
    assert.ok(row.audit.checks.every(check => check.passed));
    const e = row.evidence;
    assert.equal(e.closureTolerance, 1e-8);
    assert.ok(e.hasFinalNewtonStep && e.finalLinearBackwardError <= 1e-12 && e.maximumLogFlowChange <= 1e-8
        && e.maximumTemperatureStepRatio <= 1);
}
function comparableScopes(scopes) {
    return scopes.map(s => Object.fromEntries(Object.entries(s).filter(([key]) => !['wallMs', 'exclusiveWallMs',
        'allocatedBytes', 'exclusiveAllocatedBytes'].includes(key))));
}
function auditAccounting(scopes) {
    const children = new Map();
    for (const s of scopes) if (s.parent >= 0) children.set(s.parent, (children.get(s.parent) || 0) + s.allocatedBytes);
    for (const s of scopes) {
        assert.equal(s.exclusiveAllocatedBytes + (children.get(s.id) || 0), s.allocatedBytes);
        assert.ok(s.exclusiveAllocatedBytes >= 0);
    }
}
assert.equal(data.full.length, 10);
assert.equal(data.direct.length, 20);
const summary = { profiledFullOutcomes: 0, directExperiments: 20, witnessed: 0, full: [], direct: [], witness: [] };
for (const row of data.full) {
    const ref = reference.find(x => x.case === row.case);
    assert.deepEqual(payload(row), payload(ref), row.case);
    assert.deepEqual(payload(ref), payload(broad.find(x => x.case === row.case)), row.case + ' prior benchmark');
    const same = data.full.find(x => x.case === row.case);
    assert.deepEqual(comparableScopes(row.scopes), comparableScopes(same.scopes));
    auditAccounting(row.scopes);
    const r = row.scopes[0];
    assert.ok(Math.abs(r.allocatedBytes / ref.allocatedBytes - 1) < 0.05, 'instrumentation changed allocation materially');
    const newton = row.scopes.filter(s => s.kind === 'newton');
    const failed = newton.filter(s => s.outcome !== 'CONVERGED');
    const initBytes = row.scopes.filter(s => s.kind === 'initializer').reduce((sum, s) => sum + s.allocatedBytes, 0);
    const failedBytes = failed.reduce((sum, s) => sum + s.allocatedBytes, 0);
    summary.full.push({ case: row.case, repeat: row.repeat, kind: row.kind, MiB: r.allocatedBytes / 1048576,
        wallMs: r.wallMs, newtonSolves: newton.length, failedNewtonSolves: failed.length,
        failedNewtonMiB: failedBytes / 1048576, failedNewtonAllocationPercent: 100 * failedBytes / r.allocatedBytes,
        initializerMiB: initBytes / 1048576, initializerAllocationPercent: 100 * initBytes / r.allocatedBytes,
        totalNewtonIterations: r.newtonIterations, residualEvaluations: r.residualEvaluations,
        prPhaseEvaluations: r.prPhaseEvaluations, finiteDifferenceJacobians: r.finiteDifferenceJacobians,
        localJacobians: r.localJacobians, linearSolves: r.linearSolves, lineSearchTrials: r.lineSearchTrials,
        topPasses: row.scopes.filter(s => s.kind === 'pass').sort((a, b) => b.allocatedBytes - a.allocatedBytes).slice(0, 6)
    });
    summary.profiledFullOutcomes++;
}
for (const row of data.direct) {
    auditAccounting(row.scopes);
    const same = data.direct.find(x => x.case === row.case && x.mode === row.mode);
    for (const key of ['kind', 'audit', 'evidence', 'streams', 'duty', 'failureCode', 'termination']) assert.deepEqual(row[key], same[key]);
    assert.deepEqual(comparableScopes(row.scopes), comparableScopes(same.scopes));
    if (row.mode === 'direct-nearby-default') accepted(row);
    else { assert.equal(row.kind, 'FAILURE'); assert.equal(row.failureCode, 'MAX_ITERATIONS'); }
    const r = row.scopes[0];
    summary.direct.push({ case: row.case, repeat: row.repeat, mode: row.mode, kind: row.kind,
        MiB: r.allocatedBytes / 1048576, wallMs: r.wallMs, newtonIterations: r.newtonIterations,
        lineSearchTrials: r.lineSearchTrials, differenceFromColdResult: row.differenceFromColdResult });
}
function streamDifference(a, b) {
    assert.deepEqual(a.map(x => x.streamId), b.map(x => x.streamId));
    let temperature = 0, flow = 0, composition = 0;
    for (let i = 0; i < a.length; i++) {
        assert.equal(a[i].phase, b[i].phase);
        temperature = Math.max(temperature, Math.abs(a[i].temperatureKelvin - b[i].temperatureKelvin));
        flow = Math.max(flow, Math.abs(a[i].molarFlowMolPerSecond - b[i].molarFlowMolPerSecond) / a[i].molarFlowMolPerSecond);
        assert.deepEqual(a[i].moleFractions.map(x => x.componentId), b[i].moleFractions.map(x => x.componentId));
        for (let j = 0; j < a[i].moleFractions.length; j++) composition = Math.max(composition,
            Math.abs(a[i].moleFractions[j].moleFraction - b[i].moleFractions[j].moleFraction));
    }
    return { maximumTemperatureDifferenceK: temperature, maximumFlowRelativeDifference: flow, maximumMoleFractionDifference: composition };
}
assert.equal(witness.length, 25);
for (const row of witness) {
    assert.equal(row.scopes.length, 1, 'the witness must use uninstrumented production classes');
    const profiled = data.direct.find(x => x.case === row.case && x.mode === row.mode);
    if (profiled) for (const key of ['kind', 'audit', 'evidence', 'streams', 'duty', 'failureCode']) assert.deepEqual(row[key], profiled[key]);
    if (row.kind === 'SUCCESS') accepted(row);
    else {
        assert.equal(row.case, 'DefaultTemperatureLow10');
        assert.equal(row.kind, 'FAILURE');
        assert.equal(row.failureCode, 'MAX_ITERATIONS');
    }
    const cold = broad.find(x => x.case === row.case);
    const delta = row.kind === 'SUCCESS' && cold.kind === 'SUCCESS' ? streamDifference(cold.streams, row.streams) : null;
    if (delta) {
        assert.ok(delta.maximumTemperatureDifferenceK < 1e-6);
        assert.ok(delta.maximumFlowRelativeDifference < 1e-8);
        assert.ok(delta.maximumMoleFractionDifference < 1e-8);
    }
    summary.witness.push({ case: row.case, kind: row.kind, coldKind: cold.kind, MiB: row.scopes[0].allocatedBytes / 1048576,
        wallMs: row.scopes[0].wallMs, differenceFromColdResult: delta, failureCode: row.failureCode, termination: row.termination });
    summary.witnessed++;
}
fs.writeFileSync(root + 'summary.json', JSON.stringify(summary, null, 2));
console.log(`${summary.profiledFullOutcomes} full profiles match the production outputs; seed experiments repeat exactly.`);
console.log(`${summary.witnessed} uninstrumented nearby-seed checks: ${summary.witness.filter(x => x.kind === 'SUCCESS').length} successes.`);
console.table(summary.full.filter(x => x.repeat === 0).map(({ topPasses, ...row }) => row));
console.table(summary.witness);
