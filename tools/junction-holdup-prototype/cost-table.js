// Review 8.6: tabulate the HOLDUP_COST lines of one or more probe XML files (runs 84+).
// Usage: node cost-table.js <static.xml|-> <transient.xml> [static-summary-only]
// Prints a markdown row per transient case, a static-matrix total, and transient totals.
const fs = require('fs');
function lines(file) {
  if (!file || file === '-') return [];
  return fs.readFileSync(file, 'utf8').split(/\r?\n/).filter(l => l.startsWith('HOLDUP_COST '));
}
function parse(line) {
  const r = {};
  r.case = /case=(\S+)/.exec(line)[1];
  for (const k of ['ms', 'implicitSolves', 'newtonSolves', 'newtonIterations', 'newtonBacktracks', 'activeSetPasses', 'jacobianBuilds',
    'luFactorNanos', 'luSolveNanos', 'companionSolves', 'companionFilters', 'endpointRateBuilds', 'endpointRateReuses', 'stepAttempts',
    'accepted', 'flashCalls', 'seedCalls', 'seedFlashes', 'reconstructCalls', 'stateCalls']) {
    const m = new RegExp(' ' + k + '=([0-9.]+)').exec(line); r[k] = m ? Number(m[1]) : 0;
  }
  const a = /threadAllocMB=([0-9.]+)/.exec(line); r.allocMB = a ? Number(a[1]) : 0;
  r.by = {};
  const b = /rejectedBy=\{([^}]*)\}/.exec(line);
  if (b && b[1].trim()) for (const kv of b[1].split(', ')) { const [k, v] = kv.split('='); r.by[k] = Number(v); }
  return r;
}
function sum(rows) {
  const t = { case: 'total', by: {} };
  for (const r of rows) for (const [k, v] of Object.entries(r)) {
    if (k === 'case') continue;
    if (k === 'by') { for (const [kk, vv] of Object.entries(v)) t.by[kk] = (t.by[kk] || 0) + vv; continue; }
    t[k] = (t[k] || 0) + v;
  }
  return t;
}
function row(r) {
  const rej = Object.entries(r.by).filter(([, v]) => v > 0).map(([k, v]) => k + ' ' + v).join(', ') || '-';
  const rejected = Object.values(r.by).reduce((x, y) => x + y, 0);
  return `| ${r.case} | ${r.ms} | ${r.accepted} | ${rejected} | ${rej} | ${r.newtonSolves} | ${(r.newtonSolves / Math.max(1, r.accepted)).toFixed(2)} | ${(r.newtonIterations / Math.max(1, r.newtonSolves)).toFixed(2)} | ${r.newtonBacktracks} | ${r.jacobianBuilds} | ${((r.luFactorNanos + r.luSolveNanos) / 1e6).toFixed(1)} | ${r.companionSolves}/${r.companionFilters} | ${r.endpointRateBuilds} | ${r.seedCalls}/${r.seedFlashes} | ${r.flashCalls} | ${r.allocMB.toFixed(0)} |`;
}
const [, , staticFile, transientFile] = process.argv;
console.log('| case | ms | accepted | rejected | rejected by | Newton solves | Newton/accepted | iter/Newton | backtracks | Jacobians | LU ms | companion solves/filters | rate builds | seed calls/flashes | flashes | alloc MB |');
console.log('|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|');
const tr = lines(transientFile).map(parse);
for (const r of tr) console.log(row(r));
if (tr.length) console.log(row(Object.assign(sum(tr), { case: 'transient total' })));
const st = lines(staticFile).map(parse);
if (st.length) console.log(row(Object.assign(sum(st), { case: 'static total (' + st.length + ' cases)' })));
