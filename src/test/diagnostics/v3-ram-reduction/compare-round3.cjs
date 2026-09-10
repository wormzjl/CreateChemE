const fs = require('node:fs');
const assert = require('node:assert/strict');
const root = 'build/reports/v3-ram-reduction/';
const names = process.argv.slice(2);
if (!names.length) names.push('baseline-1', 'candidate-1', 'baseline-2', 'candidate-2');
const labels = ['Default', ...['Feed', 'Temperature', 'Pressure', 'Reflux', 'Steam', 'Cooling']
    .flatMap(factor => [5, 10].flatMap(percent => ['Low', 'High'].map(sign => `Default${factor}${sign}${percent}`)))];
const reports = Object.fromEntries(names.map(name => [name,
    JSON.parse(fs.readFileSync(root + 'round3-' + name + '.json', 'utf8'))]));
const median = values => [...values].sort((a, b) => a - b)[Math.floor(values.length / 2)];
const payload = row => Object.fromEntries(['input', 'kind', 'diagnostics', 'streams', 'evidence', 'audit', 'duty', 'failure']
    .map(key => [key, row[key]]));
const summary = { checked: 0, successful: 0, failed: 0, cases: [] };
for (const name of names) assert.equal(reports[name].length, 25 * (name.startsWith('scout-') ? 1 : 5));
for (const label of labels) {
    const reference = reports[names[0]].find(row => row.case === label);
    const item = { case: label, kind: reference.kind, runs: {}, solvePath: reference.diagnostics.solvePath };
    for (const name of names) {
        const samples = reports[name].filter(row => row.case === label);
        assert.equal(samples.length, name.startsWith('scout-') ? 1 : 5);
        for (const sample of samples) {
            assert.deepEqual(payload(sample), payload(reference), `${name}/${label}/${sample.sample}`);
            if (sample.kind === 'SUCCESS') {
                assert.ok(sample.audit.checks.every(check => check.passed));
                const e = sample.evidence;
                assert.equal(e.closureTolerance, 1e-8);
                assert.ok(e.hasFinalNewtonStep && e.finalLinearBackwardError <= 1e-12
                    && e.maximumLogFlowChange <= 1e-8 && e.maximumTemperatureStepRatio <= 1);
                summary.successful++;
            } else {
                assert.equal(sample.kind, 'FAILURE');
                assert.ok(sample.failure.startsWith('Failure[code=NONCONVERGENCE,'));
                summary.failed++;
            }
            summary.checked++;
        }
        item.runs[name] = { wallMs: median(samples.map(row => row.wallMs)), cpuMs: median(samples.map(row => row.cpuMs)),
            allocatedMiB: median(samples.map(row => row.allocatedBytes)) / 1048576 };
    }
    summary.cases.push(item);
}
const output = names[0].startsWith('scout-') ? 'round3-scout-comparison.json' : 'round3-comparison.json';
fs.writeFileSync(root + output, JSON.stringify(summary, null, 2));
console.log(`${summary.checked} exact outcomes: ${summary.successful} successes and ${summary.failed} unchanged nonconvergence results.`);
for (const item of summary.cases) console.log(item.case, JSON.stringify(item.runs));
