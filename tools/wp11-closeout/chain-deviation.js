// WP11: per-quantity deviation of one solver-regression reference against another (same fixture), with the metrics of
// SolverRegressionHarness.compare: pressure, mass, volume relative; temperature absolute (K); each component's moles
// relative with the 1e-9-of-node-total floor; phase volume fractions as absolute differences of V_phase/V; average pipe
// flows relative to max(|q_ref|, controller floor 1e-9 + 2e-9 (m1 + m2)/5 s). Prints max (with where) and mean over all
// entries (moles: over every node and component with a nonzero reference or new value).
// Usage: node tools/wp11-closeout/chain-deviation.js <reference.json> <new.json>
const fs = require("fs");
const [ref, cur] = process.argv.slice(2).map(p => JSON.parse(fs.readFileSync(p, "utf8")));
const stats = {};
const add = (q, v, where) => {
  const s = stats[q] || (stats[q] = { max: 0, sum: 0, n: 0, where: "-" });
  s.sum += v; s.n++; if (v > s.max) { s.max = v; s.where = where; }
};
ref.intervals.forEach((ri, k) => {
  const ci = cur.intervals[k];
  if (ri.accepted !== ci.accepted || ri.rejected !== ci.rejected) console.log(`interval ${k}: substeps ${ri.accepted}/${ri.rejected} -> ${ci.accepted}/${ci.rejected}`);
  else console.log(`interval ${k}: substeps ${ri.accepted}/${ri.rejected} (equal)`);
  ri.nodes.forEach((a, n) => {
    const b = ci.nodes[n], at = `node ${a.id}`;
    add("pressure (relative)", Math.abs(a.pressure - b.pressure) / Math.abs(a.pressure), at);
    add("temperature (K)", Math.abs(a.temperature - b.temperature), at);
    add("mass (relative)", Math.abs(a.mass - b.mass) / Math.abs(a.mass), at);
    add("vessel volume (relative)", Math.abs(a.volume - b.volume) / Math.abs(a.volume), at);
    for (const f of ["liquidVolume", "vaporVolume", "waterVolume"]) add(`${f} fraction (absolute)`, Math.abs(a[f] / a.volume - b[f] / b.volume), at);
    for (const f of ["liquidVolume", "vaporVolume", "waterVolume"]) if (a[f] > 0) add(`${f} (relative)`, Math.abs(a[f] - b[f]) / a[f], at);
    const total = a.moles.reduce((s, x) => s + Math.abs(x), 0);
    a.moles.forEach((x, c) => {
      if (x === 0 && b.moles[c] === 0) return;
      add("component moles (relative)", Math.abs(x - b.moles[c]) / Math.max(Math.abs(x), 1e-9 * total), `${at} moles[${c}]`);
    });
  });
  ri.flows.forEach((q, e) => {
    const floor = 1e-9 + 2e-9 * (ri.nodes[e].mass + ri.nodes[e + 1].mass) / 5;
    add("pipe flow (relative to max(|q|, floor))", Math.abs(q - ci.flows[e]) / Math.max(Math.abs(q), floor), `pipe ${e}`);
    add("pipe flow (kg/s, absolute)", Math.abs(q - ci.flows[e]), `pipe ${e}`);
  });
});
console.log("| Quantity | max | mean | entries | max at |");
console.log("|---|---|---|---|---|");
for (const [q, s] of Object.entries(stats)) console.log(`| ${q} | ${s.max.toExponential(3)} | ${(s.sum / s.n).toExponential(3)} | ${s.n} | ${s.where} |`);
