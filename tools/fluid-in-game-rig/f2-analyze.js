// F2 analysis of one rig run: WP5's summary (analyze.js) plus the placement stall and the marginal single-block costs.
// Placement: the scenario function's RCON round trip, the tick loop's gap around it (end of the last tick before the
// command to the end of the first tick after it, from fluid_diag_ticks.csv, epoch_ms = tick end), that first tick's
// whole and engine time, and the server's "Can't keep up" lines. Marginal: per function, the RCON round trip and the
// engine time of the first tick ending after the reply (where queued events apply), and the server-thread cost
// estimate = round trip - the no-op's median round trip + that engine time - the no-op's.
// Usage: node f2-analyze.js <runDir> [...]   writes <runDir>/f2-summary.json
const fs = require('fs'), path = require('path');
const {analyze} = require('./analyze');
const med = v => { if (!v.length) return null; const s = [...v].sort((a, b) => a - b); return s.length % 2 ? s[(s.length - 1) / 2] : (s[s.length / 2 - 1] + s[s.length / 2]) / 2; };
function f2(dir) {
  const base = analyze(dir);
  const run = JSON.parse(fs.readFileSync(path.join(dir, 'run.json'), 'utf8'));
  const rows = fs.readFileSync(path.join(dir, 'fluid_diag_ticks.csv'), 'utf8').trim().split(/\r?\n/).slice(1).map(l => l.split(',').map(Number));
  const start = run.events.find(e => e.what === 'place start').t, done = run.events.find(e => e.what === 'place done').t;
  const before = rows.filter(r => r[0] <= start).pop(), after = rows.find(r => r[0] > done);
  const following = rows.filter(r => r[0] > done && r[0] <= done + 5000);
  const log = fs.readFileSync(path.join(dir, 'gradle-server.log'), 'utf8').split(/\r?\n/);
  const cantKeepUp = log.filter(l => /Can't keep up/.test(l)).map(l => l.replace(/^.*?(\[\d\d:\d\d:\d\d\]).*?(Can't keep up.*)$/, '$1 $2'));
  const out = {runId: run.runId, scenario: run.scenario, devices: base.endState && base.endState.devices, placement: {
    rconMs: run.placementMillis, tickLoopGapMs: after && before ? after[0] - before[0] : null,
    firstTickAfter: after ? {tickMs: after[2], engineMs: after[3]} : null,
    next5s: {maxTickMs: Math.max(...following.map(r => r[2])), maxEngineMs: Math.max(...following.map(r => r[3])), engineMsSum: following.reduce((a, r) => a + r[3], 0)},
    cantKeepUp}, window: {ticks: base.ticks, endState: base.endState, placementGap: base.placementGap, heldLines: base.heldLines, processCores: base.process && base.process.cpuCores}};
  if (run.marginal) {
    // Every tick ending after the reply and before the next command (450 ms): an event on a busy island waits for its
    // attempt and applies at a later tick hook, in both builds.
    const win = m => rows.filter(r => r[0] > m.t1 && r[0] <= m.t1 + 450);
    const byOp = {};
    for (const m of run.marginal) { const w = win(m); (byOp[m.op] ||= []).push({rtt: m.rttMs, engine: w.reduce((x, r) => x + r[3], 0), maxTick: Math.max(0, ...w.map(r => r[2])), ticks: w.length}); }
    const noop = byOp.noop || []; const rtt0 = med(noop.map(x => x.rtt)), eng0 = med(noop.map(x => x.engine));
    out.marginal = {windowMs: 450, noopRttMs: rtt0, noopEngineMsInWindow: eng0, ops: {}};
    for (const [op, list] of Object.entries(byOp)) out.marginal.ops[op] = {n: list.length, rttMs: med(list.map(x => x.rtt)), rttMax: Math.max(...list.map(x => x.rtt)),
      engineMsInWindow: med(list.map(x => x.engine)), maxTickMs: med(list.map(x => x.maxTick)), costMs: med(list.map(x => x.rtt - rtt0 + x.engine - eng0)), costMsMax: Math.max(...list.map(x => x.rtt - rtt0 + x.engine - eng0))};
  }
  fs.writeFileSync(path.join(dir, 'f2-summary.json'), JSON.stringify(out, null, 2));
  return out;
}
module.exports = {f2};
if (require.main === module) for (const d of process.argv.slice(2)) console.log(JSON.stringify(f2(path.resolve(d)), null, 2));
