// Compact before/after tables for the WP5 review (one row per scenario): node compact.js server|client|soak|repeats
const fs = require('fs'), path = require('path');
const logs = path.resolve(__dirname, '..', 'wp5-logs');
const get = id => { const f = path.join(logs, id, 'summary.json'); return fs.existsSync(f) ? JSON.parse(fs.readFileSync(f, 'utf8')) : null; };
const pick = (o, p) => p.split('.').reduce((a, k) => a == null ? a : a[k], o);
const n = (v, d) => v == null || Number.isNaN(v) ? 'n/a' : Number(v).toFixed(d);
const pair = (a, b, p, d) => `${n(pick(a, p), d)} / ${n(pick(b, p), d)}`;
const ratio = (a, b, p) => { const x = pick(a, p), y = pick(b, p); return x == null || y == null || x === 0 ? 'n/a' : (y / x).toFixed(2); };
const kind = process.argv[2] || 'server';
const SCEN = kind === 'server' ? ['empty', 'rest100', 'through100', 'fill100', 'mixed100', 'rest1000', 'pure100'] : ['empty', 'rest100', 'through100', 'fill100', 'mixed100', 'rest1000'];
const pre = kind === 'client' ? 'cli' : 'srv';
if (kind === 'server' || kind === 'client') {
  const cols = [
    ['tick ms p50 / p95 (every tick)', s => [pick(s, 'ticks.tickMs.p50'), pick(s, 'ticks.tickMs.p95')], 3],
    ['engine ms per tick p50', s => [pick(s, 'ticks.engineMs.p50')], 4],
    ['process CPU, cores', s => [pick(s, 'process.cpuCores')], 2],
    ['GC count / pause ms', s => [pick(s, 'gc.count'), pick(s, 'gc.pauseMsTotal')], 0],
    ['live heap MiB', s => [pick(s, 'liveHeapMiB')], 0],
    ['working set MiB (mean)', s => [pick(s, 'process.workingSetMiB.mean')], 0],
    ['allocation MiB/s', s => [pick(s, 'allocationMiBPerSecond')], 1],
  ];
  if (kind === 'client') cols.unshift(['FPS mean / p5', s => [pick(s, 'fps.mean'), pick(s, 'fps.p5')], 1]);
  let out = `| scenario (${kind}) | ` + cols.map(c => c[0] + ': before -> after').join(' | ') + ' |\n|---|' + cols.map(() => '---|').join('') + '\n';
  for (const s of SCEN) {
    const a = get(`${pre}-${s}-base-r01`), b = get(`${pre}-${s}-wp5-r01`);
    if (!a || !b) continue;
    out += `| ${s} | ` + cols.map(([, f, d]) => { const x = f(a), y = f(b); return `${x.map(v => n(v, d)).join(' / ')} -> ${y.map(v => n(v, d)).join(' / ')}`; }).join(' | ') + ' |\n';
  }
  process.stdout.write(out);
} else if (kind === 'ratios') {
  let out = '| scenario | server CPU after/before | server tick p50 after/before | server engine p50 after/before | server live heap after/before | client CPU after/before | client FPS mean after/before | client live heap after/before |\n|---|---|---|---|---|---|---|---|\n';
  for (const s of ['empty', 'rest100', 'through100', 'fill100', 'mixed100', 'rest1000', 'pure100']) {
    const a = get(`srv-${s}-base-r01`), b = get(`srv-${s}-wp5-r01`), c = get(`cli-${s}-base-r01`), d = get(`cli-${s}-wp5-r01`);
    out += `| ${s} | ${ratio(a, b, 'process.cpuCores')} | ${ratio(a, b, 'ticks.tickMs.p50')} | ${ratio(a, b, 'ticks.engineMs.p50')} | ${ratio(a, b, 'liveHeapMiB')} | ${c ? ratio(c, d, 'process.cpuCores') : '-'} | ${c ? ratio(c, d, 'fps.mean') : '-'} | ${c ? ratio(c, d, 'liveHeapMiB') : '-'} |\n`;
  }
  process.stdout.write(out);
} else if (kind === 'repeats') {
  let out = '| run | process CPU cores | fluid workers + not attributed, cores | tick ms p50 / p95 | engine ms p50 / p95 | GC count | live heap MiB | allocation MiB/s | statuses at window end |\n|---|---|---|---|---|---|---|---|---|\n';
  for (const s of ['fill100', 'mixed100']) for (const b of ['base', 'wp5']) for (const r of ['01', '02']) {
    const x = get(`srv-${s}-${b}-r${r}`); if (!x) continue;
    out += `| srv-${s}-${b}-r${r} | ${n(pick(x, 'process.cpuCores'), 2)} | ${n((pick(x, 'threadCores.fluidWorkers') || 0) + (x.threadCoresUnattributed || 0), 2)} | ${n(pick(x, 'ticks.tickMs.p50'), 3)} / ${n(pick(x, 'ticks.tickMs.p95'), 3)} | ${n(pick(x, 'ticks.engineMs.p50'), 4)} / ${n(pick(x, 'ticks.engineMs.p95'), 4)} | ${n(pick(x, 'gc.count'), 0)} | ${n(x.liveHeapMiB, 0)} | ${n(x.allocationMiBPerSecond, 0)} | ${x.endState ? Object.entries(x.endState.statuses).map(([k, v]) => k + ' ' + v).join(', ') : ''} |\n`;
  }
  process.stdout.write(out);
}
