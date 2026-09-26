// F1 in-game table: one row per run directory (summary.json from analyze.js, gradle-server.log fluid_diag lines, latest.log).
// Usage: node f1-table.js <runDir> [...]
const fs = require('fs'), path = require('path');
const rows = [];
for (const dir of process.argv.slice(2)) {
  const s = JSON.parse(fs.readFileSync(path.join(dir, 'summary.json'), 'utf8'));
  const run = JSON.parse(fs.readFileSync(path.join(dir, 'run.json'), 'utf8'));
  const log = fs.readFileSync(path.join(dir, 'gradle-server.log'), 'utf8');
  const diag = [...log.matchAll(/fluid_diag online_tick=(\d+) [^\n]*?kinds=(\{[^}]*\}) statuses=(\{[^}]*\})[^\n]*?counters=\{([^}]*)\}/g)];
  // Periods inside the window: the window starts after placement + warm-up; use the placed online tick + warm-up ticks.
  const placed = run.placedState ? run.placedState.onlineTick : 0;
  const start = placed + 20 * (run.warmupSeconds || 60), end = start + 20 * (run.windowSeconds || 60);
  const sum = {};
  for (const d of diag) { const t = +d[1]; if (t <= start || t > end + 1) continue; for (const kv of d[4].split(', ')) { const [k, v] = kv.split('='); if (v !== undefined) sum[k] = (sum[k] || 0) + (+v); } }
  const last = diag.filter(d => +d[1] <= end + 1).pop();
  const latest = fs.existsSync(path.join(dir, 'latest.log')) ? fs.readFileSync(path.join(dir, 'latest.log'), 'utf8') : '';
  const heldWarnings = (latest.match(/status=HELD/g) || []).length;
  rows.push(`| ${path.basename(dir)} | ${run.warmupSeconds}+${run.windowSeconds} s | ${s.process.cpuCores.toFixed(2)} | ${s.allocationMiBPerSecond.toFixed(1)} | ${s.gc.count} | ${s.ticks.tickMs.p50.toFixed(3)} / ${s.ticks.tickMs.p95.toFixed(3)} | ${s.ticks.engineMs.p50.toFixed(4)} | ${sum.solvesDispatched || 0} | ${sum.budgetHolds ?? 'n/a'} / ${sum.numericalHolds ?? 'n/a'} / ${sum.retriesDeferred ?? 'n/a'} | ${heldWarnings} | ${last ? last[2] + ' ' + last[3] : ''} |`);
}
console.log('| run | window | process CPU, cores | allocation MiB/s | GC count | tick ms p50 / p95 | engine ms p50 | solves in window | budget / numerical holds / deferred retries in window | HELD warnings in the whole log | kinds and statuses at the window end |');
console.log('|---|---|---|---|---|---|---|---|---|---|---|');
for (const r of rows) console.log(r);
