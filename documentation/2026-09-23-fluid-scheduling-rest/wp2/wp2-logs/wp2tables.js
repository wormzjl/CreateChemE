// Builds the WP2 benchmark tables from FluidServerBenchmark reports. node wp2tables.js <M9 directory>
const path = require('path'), fs = require('fs');
const dir = path.resolve(process.argv[2]);
const load = id => { const f = path.join(dir, id, 'report.json'); return fs.existsSync(f) ? JSON.parse(fs.readFileSync(f, 'utf8')) : null; };
const e = (v, d = 2) => v === null || v === undefined || Number.isNaN(v) ? 'n/a' : v === 0 ? '0' : Math.abs(v) >= 0.01 && Math.abs(v) < 1e5 ? Number(v).toFixed(d) : Number(v).toExponential(d);
const mean = a => a.length ? a.reduce((x, y) => x + y, 0) / a.length : NaN;
function row(r) {
  const c = r.certificates, s = r.stressSamples || [];
  const refusals = {};
  for (const [id, t] of Object.entries(c.finalRefusalsVerbatim || {})) {
    const m = t.match(/changed by (-?\d+(\.\d+)?([eE][-+]?\d+)?)/); const v = m ? Number(m[1]) : null;
    const kind = t.startsWith('revalidation') ? 'revalidation' : t.includes('temperature or pressure') ? 'T/P drift' : t.includes('pipe flow') ? 'flow' : t.includes('component') ? 'component' : t.includes('energy') ? 'energy' : t === 'null' ? 'none: horizon slice in flight' : t.slice(0, 30);
    const band = v === null ? '' : v > 1e-6 ? ' >1e-6' : v > 1e-7 ? ' 1e-7..1e-6' : v > 1e-8 ? ' 1e-8..1e-7' : v > 1e-9 ? ' 1e-9..1e-8' : ' <=1e-9';
    refusals[kind + band] = (refusals[kind + band] || 0) + 1;
  }
  const series = c.certifiedIslandsPerSecond || [];
  return {
    run: r.manifest.runId, rest: c.restDetection, eps: c.stationaryTolerance,
    full: c.fullSolves, replayed: `${c.replayedIntervals} / ${e(c.replayedSeconds, 0)} s`, rested: `${c.restedIntervals} / ${e(c.identityAdvancedSeconds, 0)} s`,
    kinds: Object.entries(c.finalIslandKinds).map(([k, v]) => `${v} ${k}`).join(', '),
    certified: series.length ? `${series[0]} / ${series[Math.min(29, series.length - 1)]} / ${series[series.length - 1]}` : 'n/a',
    refusals: !c.restDetection ? '(certificates off)' : Object.entries(refusals).sort().map(([k, v]) => `${v} ${k}`).join('; ') || '-',
    eligible: e(mean(s.map(x => x.eligibleIslands))), zeroDemand: `${s.filter(x => x.eligibleIslands === 0 && x.readyJobs === 0 && x.outstandingJobs === 0).length}/${s.length}`,
    heap: c.heapAfterForcedGcBytes !== undefined ? e(c.heapAfterForcedGcBytes / 1048576, 0) + ' MiB' : '-',
    ready: `${e(r.readyToPublicationMilliseconds.median)} / ${e(r.readyToPublicationMilliseconds.p95)}`,
    worker: `${e(r.workerMilliseconds.median)} / ${e(r.workerMilliseconds.p95)}`,
    engine: `${e(r.engineServerMillisecondsPerTick.median, 3)} / ${e(r.engineServerMillisecondsPerTick.p95, 3)}`,
    tick: `${e(r.wholeTickMilliseconds.median, 3)} / ${e(r.wholeTickMilliseconds.p95, 3)}`,
    solvesPerSecond: e(r.runtimeCounters.perSecond.solvesDispatched), issued: r.runtimeCounters.totals.certificatesIssued,
    balance: `${e(r.componentBalanceToleranceUnits)} / ${e(r.energyBalanceToleranceUnits)}`, integrity: r.stressIntegrityPassed,
    held: `${r.heldIntervals} / ${r.warmupHeldIntervals}`, workers: r.workers,
  };
}
const pairs = [['transient100', 'transient100-wp2-off-r02', 'transient100-wp2-on-r03'], ['stress100', 'stress100-wp2-off-r03', 'stress100-wp2-on-r02'],
  ['rest100', 'rest100-wp2-off-r03', 'rest100-wp2-on-r02'], ['mixed100', 'mixed100-wp2-off-r02', 'mixed100-wp2-on-r02']];
const fields = [['run', 'run id'], ['full', 'full solves in window'], ['solvesPerSecond', 'solves dispatched / s'], ['replayed', 'replayed intervals / seconds'], ['rested', 'identity-advanced intervals / seconds'],
  ['issued', 'certificates issued in the window'], ['kinds', 'final kinds'], ['certified', 'certified islands at measured s 1 / 30 / 120'], ['refusals', 'final refusals (kind, magnitude)'],
  ['eligible', 'mean eligible islands'], ['zeroDemand', 'zero-demand samples'], ['heap', 'heap after forced GC'], ['ready', 'ready-to-publication ms p50 / p95'],
  ['worker', 'worker ms p50 / p95'], ['engine', 'engine server ms per tick p50 / p95'], ['tick', 'whole tick ms p50 / p95'], ['held', 'held intervals window / warm-up'],
  ['balance', 'component / energy balance units'], ['integrity', 'integrity passed'], ['workers', 'workers']];
let out = '### WP2 certificate benchmark, quiet machine, off = `-PfluidRestDetection=false`, on = defaults (eps_s 1e-9, budget 1e-6, K_max 17280, confirm 2)\n\n';
out += '| metric | ' + pairs.map(p => `${p[0]} off | ${p[0]} on`).join(' | ') + ' |\n|---|' + pairs.map(() => '---|---|').join('') + '\n';
const rows = pairs.map(p => [row(load(p[1])), row(load(p[2]))]);
for (const [k, label] of fields) out += `| ${label} | ` + rows.map(([a, b]) => `${a[k]} | ${b[k]}`).join(' | ') + ' |\n';
out += '\n### Sensitivity, stress100 (eps_s via `-PfluidCertificateTolerance`)\n\n';
const sens = ['stress100-wp2-off-r02', 'stress100-wp2-off-r03', 'stress100-wp2-on-r02', 'stress100-wp2-tol8-r01', 'stress100-wp2-tol7-r01'].map(id => row(load(id)));
out += '| metric | ' + sens.map(s => s.run).join(' | ') + ' |\n|---|' + sens.map(() => '---|').join('') + '\n';
for (const [k, label] of [['rest', 'restDetection'], ['eps', 'eps_s'], ['full', 'full solves in window'], ['replayed', 'replayed intervals / seconds'], ['issued', 'certificates issued in the window'], ['kinds', 'final kinds'], ['certified', 'certified islands at measured s 1 / 30 / 120'], ['refusals', 'final refusals'], ['ready', 'ready-to-publication ms p50 / p95'], ['engine', 'engine ms p50 / p95'], ['balance', 'component / energy balance units'], ['integrity', 'integrity passed']])
  out += `| ${label} | ` + sens.map(s => s[k]).join(' | ') + ' |\n';
const series = id => (load(id).certificates.certifiedIslandsPerSecond || []);
out += '\n### Certified islands per measured second (every 10 s)\n\n| run | ' + Array.from({ length: 13 }, (_, i) => i === 0 ? 's1' : `s${i * 10}`).join(' | ') + ' |\n|---|' + '---|'.repeat(13) + '\n';
for (const id of ['rest100-wp2-on-r02', 'mixed100-wp2-on-r02', 'stress100-wp2-tol7-r01', 'rest100-wp2-on-r01', 'mixed100-wp2-on-r01']) { const s = series(id); out += `| ${id} | ` + Array.from({ length: 13 }, (_, i) => s[i === 0 ? 0 : Math.min(i * 10 - 1, s.length - 1)]).join(' | ') + ' |\n'; }
process.stdout.write(out);
