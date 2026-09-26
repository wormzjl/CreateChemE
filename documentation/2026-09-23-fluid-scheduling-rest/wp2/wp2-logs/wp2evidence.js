// Certificate evidence of one FluidServerBenchmark run (reports from e924eae on): certified counts over time,
// final refusals by kind and magnitude, and the last measured stationarity per ladder family.
//   node wp2evidence.js <report.json>
const path = require('path');
const r = require(path.resolve(process.argv[2]));
const c = r.certificates;
// Island ids follow network order 0..99; the timed fixtures assign ladder families by network.
const ids = Object.keys(r.reservoirsPerIsland || {}).map(Number).sort((a, b) => a - b);
const family = id => { const n = ids.indexOf(Number(id)); if (r.profile === 'mixed100') return n % 4 < 2 ? 'TRANSIENT' : n % 4 === 2 ? 'THROUGH' : 'CLOSED';
  return { stress100: 'THROUGH', transient100: 'TRANSIENT', rest100: 'CLOSED' }[r.profile] || '?'; };
console.log(`${r.manifest.runId}: restDetection ${c.restDetection}, eps_s ${c.stationaryTolerance}, budget ${c.inventoryBudget}, K_max ${c.maximumIntervals}, confirm ${c.confirmIntervals}`);
console.log(`  full solves ${c.fullSolves}, replayed ${c.replayedIntervals} intervals / ${c.replayedSeconds} s, rested ${c.restedIntervals} / ${c.identityAdvancedSeconds} s, final ${JSON.stringify(c.finalIslandKinds)}`);
const series = c.certifiedIslandsPerSecond || [];
if (series.length) {
  const firstAt = k => { const i = series.findIndex(v => v >= k); return i < 0 ? 'never' : i + 1 + ' s'; };
  const last = series[series.length - 1];
  console.log(`  certified per measured second: first ${series[0]}, last ${last}, min ${Math.min(...series)}, max ${Math.max(...series)}; half of the final count at ${firstAt(Math.ceil(last / 2))}, all at ${firstAt(last)}`);
  const marks = [0, 10, 20, 30, 60, 90, series.length - 1].filter(i => i < series.length);
  console.log('  certified at measured second ' + marks.map(i => `${i + 1}:${series[i]}`).join(' '));
}
// Final refusals by kind and by decade of their number.
const verbatim = c.finalRefusalsVerbatim || {};
const kinds = {};
for (const [id, text] of Object.entries(verbatim)) {
  const kind = text.replace(/-?\d+(\.\d+)?([eE][-+]?\d+)?/g, '#').replace(/\(pipe #\)/, '(pipe)');
  const m = text.match(/changed by (-?\d+(\.\d+)?([eE][-+]?\d+)?)/); const v = m ? Number(m[1]) : null;
  const band = v === null ? 'n/a' : v > 1e-6 ? '>1e-6' : v > 1e-7 ? '1e-7..1e-6' : v > 1e-8 ? '1e-8..1e-7' : v > 1e-9 ? '1e-9..1e-8' : '<=1e-9';
  const key = family(id) + ' | ' + kind.slice(0, 70); kinds[key] = kinds[key] || {}; kinds[key][band] = (kinds[key][band] || 0) + 1;
}
console.log('  final refusals (family | reason: magnitude band counts):');
for (const [k, v] of Object.entries(kinds)) console.log(`    ${k}: ${JSON.stringify(v)}`);
// Last evidence per island: the smallest stationary tolerance and which test sets it.
const cols = c.evidenceColumns || [];
const ev = c.evidence || {};
const byFamily = {};
for (const [id, rows] of Object.entries(ev)) {
  const withPair = rows.filter(x => x[1] >= 0); if (!withPair.length) continue; const last = withPair[withPair.length - 1];
  const f = family(id); (byFamily[f] = byFamily[f] || []).push(last);
}
const med = a => { a = a.slice().sort((x, y) => x - y); return a.length ? a[Math.floor(a.length / 2)] : NaN; };
for (const [f, rows] of Object.entries(byFamily)) {
  const part = i => `${med(rows.map(x => x[i])).toExponential(2)} [${Math.min(...rows.map(x => x[i])).toExponential(1)}, ${Math.max(...rows.map(x => x[i])).toExponential(1)}]`;
  console.log(`  ${f} (${rows.length} islands with a compared pair at their last solve) median [min, max]: measure ${part(1)}, state ${part(2)}, component ${part(3)}, energy ${part(4)}, flow ${part(5)}, relative flow change ${part(6)}, drift ${part(7)}`);
  const bands = { '>1e-6': 0, '1e-7..1e-6': 0, '1e-8..1e-7': 0, '1e-9..1e-8': 0, '<=1e-9': 0 };
  for (const x of rows) { const v = x[1]; bands[v > 1e-6 ? '>1e-6' : v > 1e-7 ? '1e-7..1e-6' : v > 1e-8 ? '1e-8..1e-7' : v > 1e-9 ? '1e-9..1e-8' : '<=1e-9']++; }
  console.log(`    measure at the last solve by band: ${JSON.stringify(bands)}`);
}
