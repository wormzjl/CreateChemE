// Builds the WP5 markdown tables from wp5-logs/<run>/summary.json (run analyze.js first; campaign.js does).
// Usage: node tables.js > ../wp5-tables-generated.md
const fs = require('fs'), path = require('path');
const logs = path.resolve(__dirname, '..', 'wp5-logs');
const SCEN = ['empty', 'rest100', 'through100', 'fill100', 'mixed100', 'rest1000', 'pure100'];
const get = id => { const f = path.join(logs, id, 'summary.json'); if (fs.existsSync(f)) return JSON.parse(fs.readFileSync(f, 'utf8')); const g = path.join(logs, id + '-rerun', 'summary.json'); return fs.existsSync(g) ? JSON.parse(fs.readFileSync(g, 'utf8')) : null; };
const pick = (o, p) => p.split('.').reduce((a, k) => a == null ? a : a[k], o);
const fmt = (v, d = 2) => v == null || Number.isNaN(v) ? 'n/a' : Math.abs(v) >= 1000 ? v.toFixed(0) : v.toFixed(d);
const ratio = (a, b) => a == null || b == null || a === 0 ? 'n/a' : (b / a).toFixed(2);
const kinds = s => s && s.endState ? Object.entries(s.endState.kinds).map(([k, v]) => `${k} ${v}`).join(', ') || 'none' : 'n/a';
const statuses = s => s && s.endState ? Object.entries(s.endState.statuses).map(([k, v]) => `${k} ${v}`).join(', ') || 'none' : 'n/a';
const SERVER_ROWS = [
  ['MC ServerTickTime p50 / p95 / max ms (1 s means)', s => [pick(s, 'mcServerTickTimeMs.p50'), pick(s, 'mcServerTickTimeMs.p95'), pick(s, 'mcServerTickTimeMs.max')], 3],
  ['tick ms p50 / p95 / max (every tick, rig)', s => [pick(s, 'ticks.tickMs.p50'), pick(s, 'ticks.tickMs.p95'), pick(s, 'ticks.tickMs.max')], 3],
  ['engine ms per tick p50 / p95 / max (rig)', s => [pick(s, 'ticks.engineMs.p50'), pick(s, 'ticks.engineMs.p95'), pick(s, 'ticks.engineMs.max')], 4],
  ['process CPU, cores (sampler)', s => [pick(s, 'process.cpuCores')], 3],
  ['process CPU, cores (JFR CPULoad)', s => [pick(s, 'jfrCpuCores.jvm')], 3],
  ['  Server thread, cores', s => [pick(s, 'threadCores.server')], 4],
  ['  fluid workers, cores', s => [pick(s, 'threadCores.fluidWorkers')], 4],
  ['  GC threads, cores', s => [pick(s, 'threadCores.gc')], 4],
  ['  JIT compiler threads, cores', s => [pick(s, 'threadCores.jit')], 4],
  ['  Render thread, cores', s => [pick(s, 'threadCores.render')], 4],
  ['  observation (JFR, attach, RCON), cores', s => [pick(s, 'threadCores.observation')], 4],
  ['  other threads, cores', s => [(pick(s, 'threadCores.other') || 0) + (pick(s, 'threadCores.jvmOther') || 0)], 4],
  ['  not attributed (exited threads, sampling edges), cores', s => [pick(s, 'threadCoresUnattributed')], 4],
  ['GC count / pause ms total (window, heap-inspection GCs excluded)', s => [pick(s, 'gc.count'), pick(s, 'gc.pauseMsTotal')], 1],
  ['heap after GC, mean MiB (young/mixed GCs in window)', s => [pick(s, 'heapMiB.afterGcMean')], 0],
  ['live heap after full GC at window end, MiB', s => [pick(s, 'liveHeapMiB')], 0],
  ['working set mean / max MiB (sampler)', s => [pick(s, 'process.workingSetMiB.mean'), pick(s, 'process.workingSetMiB.max')], 0],
  ['resident set mean / max MiB (JFR)', s => [pick(s, 'jfrRssMiB.mean'), pick(s, 'jfrRssMiB.max')], 0],
  ['private bytes mean MiB', s => [pick(s, 'process.privateMiB.mean')], 0],
  ['allocation MiB/s (JFR thread allocation)', s => [pick(s, 'allocationMiBPerSecond')], 1],
  ['threads (mean)', s => [pick(s, 'process.threads.mean')], 0],
];
const CLIENT_ROWS = [
  ['client FPS mean / p5 / min (Minecraft.getFps, logged every 1 s)', s => [pick(s, 'fps.mean'), pick(s, 'fps.p5'), pick(s, 'fps.min')], 1],
  ...SERVER_ROWS,
];
function pairTable(kind, rows, scenario) {
  const pre = kind === 'server' ? 'srv' : 'cli';
  const a = get(`${pre}-${scenario}-base-r01`), b = get(`${pre}-${scenario}-wp5-r01`);
  const out = [`| ${scenario} (${kind}) | before (eb28fc5) | after (branch) | after / before |`, '|---|---|---|---|'];
  for (const [label, f, d] of rows) {
    const x = a ? f(a) : [], y = b ? f(b) : [];
    if (x.every(v => v == null) && y.every(v => v == null)) continue;
    out.push(`| ${label} | ${x.map(v => fmt(v, d)).join(' / ') || 'n/a'} | ${y.map(v => fmt(v, d)).join(' / ') || 'n/a'} | ${x.map((v, i) => ratio(v, y[i])).join(' / ')} |`);
  }
  out.push(`| island kinds at window end (fluid_diag) | ${kinds(a)} | ${kinds(b)} | |`);
  out.push(`| island statuses at window end | ${statuses(a)} | ${statuses(b)} | |`);
  out.push(`| devices / islands | ${a && a.endState ? a.endState.devices + ' / ' + a.endState.islands : 'n/a'} | ${b && b.endState ? b.endState.devices + ' / ' + b.endState.islands : 'n/a'} | |`);
  out.push(`| placement: function wall ms / tick gap ms | ${a ? fmt(a.placementMillis, 0) + ' / ' + fmt(a.placementGap && a.placementGap.ms, 0) : 'n/a'} | ${b ? fmt(b.placementMillis, 0) + ' / ' + fmt(b.placementGap && b.placementGap.ms, 0) : 'n/a'} | |`);
  out.push(`| window (configured warm-up + window, measured window s) | ${a ? `${a.warmupSeconds} + ${a.configuredWindowSeconds}, ${fmt(a.windowSeconds, 1)}` : 'n/a'} | ${b ? `${b.warmupSeconds} + ${b.configuredWindowSeconds}, ${fmt(b.windowSeconds, 1)}` : 'n/a'} | |`);
  return out.join('\n');
}
const lines = ['# WP5 in-game tables (generated by wp5-rig/tables.js)', ''];
for (const kind of ['server', 'client']) {
  lines.push(`## ${kind === 'server' ? 'Dedicated server (no rendering in the JVM)' : 'Integrated client (rendering and integrated server in one JVM)'}`, '');
  for (const s of SCEN) { lines.push(pairTable(kind, kind === 'server' ? SERVER_ROWS : CLIENT_ROWS, s), ''); }
}
lines.push('## Soak (branch, dedicated server, 60 s warm-up + 300 s window)', '');
for (const s of ['rest100', 'fill100']) {
  const x = get(`srv-${s}-wp5-soak-r01`); if (!x) continue;
  lines.push(`| ${s} soak | value |`, '|---|---|');
  for (const [label, f, d] of SERVER_ROWS) { const v = f(x); if (v.every(q => q == null)) continue; lines.push(`| ${label} | ${v.map(q => fmt(q, d)).join(' / ')} |`); }
  lines.push(`| island kinds / statuses at window end | ${kinds(x)}; ${statuses(x)} |`, '');
}
console.log(lines.join('\n'));
