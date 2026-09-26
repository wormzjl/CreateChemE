// Where two FluidServerBenchmark runs part, island by island, from their publication fingerprints
// (reports from e924eae on), plus the reference-grid deviation of every island on the grid in both runs.
//   node wp2diverge.js <a/report.json> <b/report.json>
const path = require('path');
const [aPath, bPath] = process.argv.slice(2);
const A = require(path.resolve(aPath)), B = require(path.resolve(bPath));
const fa = A.certificates.publicationFingerprints || {}, fb = B.certificates.publicationFingerprints || {};
const name = r => r.manifest.runId;
console.log(`${name(A)} (restDetection ${A.certificates.restDetection}) vs ${name(B)} (restDetection ${B.certificates.restDetection})`);
if (!Object.keys(fa).length || !Object.keys(fb).length) console.log('  (one report has no publication fingerprints)');
else {
  let same = 0, parted = [], compared = 0;
  for (const id of Object.keys(fa)) {
    const x = fa[id], y = fb[id]; if (!y) continue; compared++;
    // Compare solved publications only where both runs solved the same end tick; a certified side has replays.
    const mx = new Map(x.map(s => { const [t, k, h] = s.split(':'); return [t + ':' + k, h]; }));
    let first = null, common = 0;
    for (const s of y) { const [t, k, h] = s.split(':'); const g = mx.get(t + ':' + k); if (g === undefined) continue; common++; if (g !== h && first === null) first = { tick: +t, kind: k }; }
    if (first === null) same++; else parted.push({ id, ...first, common });
  }
  console.log(`  fingerprints: ${compared} islands, ${same} identical on every common publication, ${parted.length} parted`);
  parted.sort((p, q) => p.tick - q.tick);
  for (const p of parted.slice(0, 20)) console.log(`    island ${p.id}: first differing publication ends at tick ${p.tick} (${p.kind}), ${p.common} common publications`);
}
// Warm-up holds: islands whose grid was shifted by a startup wall-deadline retry.
const held = r => { const s = new Set(); for (const x of r.warmupSamples.concat(r.samples || [])) if (!x.timing.accepted) s.add(String(x.island)); return s; };
const ha = held(A), hb = held(B);
console.log(`  held at startup: ${ha.size} / ${hb.size} islands; held in one run only: ${[...ha].filter(i => !hb.has(i)).length + [...hb].filter(i => !ha.has(i)).length}`);
// Reference grid, exact islands in both runs.
const ra = A.certificates.reference, rb = B.certificates.reference;
if (ra && rb) {
  const t = ra.islandTick; let n = 0, nz = 0, worst = 0, worstE = 0, worstL = 0, at = null;
  for (const id of Object.keys(ra.before)) {
    const p = ra.before[id], q = rb.before[id]; if (!p || !q || p.tick !== t || q.tick !== t) continue; n++;
    let dev = 0, devE = 0;
    p.inventories.forEach((row, k) => row.forEach((v, c) => { const w = q.inventories[k][c]; if (c === row.length - 1) { devE = Math.max(devE, Math.abs(w - v) / Math.max(Math.abs(v), 1)); return; } if (v > 1e-12) dev = Math.max(dev, Math.abs(w - v) / Math.abs(v)); }));
    const sc = Math.max(...p.external.slice(0, -1).map(Math.abs), 1e-30); let devL = 0; p.external.forEach((v, c) => { if (c < p.external.length - 1) devL = Math.max(devL, Math.abs(q.external[c] - v) / sc); });
    if (dev || devE || devL) nz++;
    if (dev > worst) { worst = dev; at = id; } worstE = Math.max(worstE, devE); worstL = Math.max(worstL, devL);
  }
  console.log(`  reference grid tick ${t}: ${n} islands exact in both, ${nz} not bitwise equal; worst inventory ${worst.toExponential(3)} (island ${at}), energy ${worstE.toExponential(3)}, ledger ${worstL.toExponential(3)}`);
  // An island's ledger is its net boundary exchange (inflow minus outflow), which for a through-flow island is
  // only its accumulation. Two better measures: the ledger deviation against the island's own inventory of the
  // component (the budget's reference), and conservation, i.e. whether the ledger moved by exactly what the
  // inventory moved, component by component (energy includes pump work, so it is reported, not asserted).
  let worstLi = 0, atLi = null, worstC = 0, atC = null, worstCE = 0;
  for (const id of Object.keys(ra.before)) {
    const p = ra.before[id], q = rb.before[id]; if (!p || !q || p.tick !== t || q.tick !== t) continue;
    const sum = x => x.inventories.reduce((s, row) => s.map((v, c) => v + row[c]), new Array(x.inventories[0].length).fill(0));
    const ip = sum(p), iq = sum(q), m = ip.length - 1;
    for (let c = 0; c < m; c++) {
      if (ip[c] <= 1e-12) continue;
      const dl = q.external[c] - p.external[c], di = iq[c] - ip[c];
      const rel = Math.abs(dl) / ip[c]; if (rel > worstLi) { worstLi = rel; atLi = `${id}/c${c}`; }
      const cons = Math.abs(dl - di) / ip[c]; if (cons > worstC) { worstC = cons; atC = `${id}/c${c}`; }
    }
    const dle = q.external[m] - p.external[m], die = iq[m] - ip[m];
    worstCE = Math.max(worstCE, Math.abs(dle - die) / Math.max(Math.abs(ip[m]), 1));
  }
  console.log(`  ledger deviation against the island's own inventory: worst ${worstLi.toExponential(3)} (${atLi}); conservation (ledger deviation minus inventory deviation, against inventory): worst ${worstC.toExponential(3)} (${atC}); energy ${worstCE.toExponential(3)}`);
}
