const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = 'build/reports/v3-ram-reduction';
const names = process.argv.slice(2);
if (!names.length) names.push('baseline-1', 'candidate-1', 'baseline-2', 'candidate-2');
const labels = ['Default', ...['Feed', 'Temperature', 'Pressure', 'Reflux', 'Steam', 'Cooling']
    .flatMap(factor => ['Low', 'High'].map(sign => 'Default' + factor + sign))];
const reports = Object.fromEntries(names.map(name => [name,
    JSON.parse(fs.readFileSync(path.join(root, 'round2-' + name + '.json'), 'utf8'))]));
const median = values => [...values].sort((a, b) => a - b)[Math.floor(values.length / 2)];
const payload = row => Object.fromEntries(['input', 'kind', 'diagnostics', 'streams', 'evidence', 'audit', 'duty']
    .map(key => [key, row[key]]));
const summary = { checked: 0, cases: [] };
for (const name of names) assert.equal(reports[name].length, 13 * 7);
for (const label of labels) {
    const reference = reports[names[0]].find(row => row.case === label);
    const item = { case: label, runs: {}, solvePath: reference.diagnostics.solvePath };
    for (const name of names) {
        const samples = reports[name].filter(row => row.case === label);
        assert.equal(samples.length, 7);
        for (const sample of samples) {
            assert.equal(sample.kind, 'SUCCESS', `${name}/${label}`);
            assert.ok(sample.audit.checks.every(check => check.passed));
            const e = sample.evidence;
            assert.equal(e.closureTolerance, 1e-8);
            assert.ok(e.hasFinalNewtonStep && e.finalLinearBackwardError <= 1e-12
                && e.maximumLogFlowChange <= 1e-8 && e.maximumTemperatureStepRatio <= 1);
            assert.deepEqual(payload(sample), payload(reference), `${name}/${label}/${sample.sample}`);
            summary.checked++;
        }
        item.runs[name] = { wallMs: median(samples.map(row => row.wallMs)),
            cpuMs: median(samples.map(row => row.cpuMs)),
            allocatedMiB: median(samples.map(row => row.allocatedBytes)) / 1048576 };
    }
    summary.cases.push(item);
}
fs.writeFileSync(path.join(root, 'round2-comparison.json'), JSON.stringify(summary, null, 2));
console.log(`${summary.checked} outcomes match exactly across the default and 12 perturbations.`);
for (const item of summary.cases) console.log(item.case, JSON.stringify(item.runs));
