// F2 changes to the copied run-server.js: logs under f2-logs/rig, the rest400 scenario, and after the window a marginal
// phase in the rest scenarios: ten times each, a no-op function (the RCON round trip), then single-block functions and
// their undo (lone tank, line branch pipe, facing edit, twenty lone tanks), 500 ms apart, each command's epoch bounds
// recorded so the analysis can take the ticks that follow it from fluid_diag_ticks.csv.
const fs = require('fs'), path = require('path');
const file = path.join(__dirname, 'run-server.js');
let s = fs.readFileSync(file, 'utf8').replace(/\r\n/g, '\n');
function rep(a, b) { const n = s.split(a).length - 1; if (n !== 1) throw new Error(n + ' for ' + a.slice(0, 80)); s = s.replace(a, () => b); }
rep(`// F1 copy of the WP5 driver (logs under f1-logs/rig/, probe counts of the WP5 probes with six-tank fills).`, `// F2 copy of the F1/WP5 driver (logs under f2-logs/rig/; rest400; a marginal single-block phase after the window).`);
rep(`const rig = __dirname, logs = path.resolve(rig, '..', 'f1-logs', 'rig', runId);`, `const rig = __dirname, logs = path.resolve(rig, '..', 'f2-logs', 'rig', runId);`);
rep(`rest1000: 5000, pure100: 600,`, `rest1000: 5000, rest400: 2000, pure100: 600,`);
rep(`  L.fullGc(info.pid, path.join(logs, 'heap-live.txt')); note('live heap');
`, `  L.fullGc(info.pid, path.join(logs, 'heap-live.txt')); note('live heap');
  if (/^rest/.test(scenario) && process.env.F2_MARGINAL !== '0') {
    await L.sleep(5000); info.marginal = [];
    for (const [op, undo] of [['noop', null], ['plus_tank', 'minus_tank'], ['plus_pipe', 'minus_pipe'], ['edit_pipe', 'edit_back'], ['plus20', 'minus20']])
      for (let k = 0; k < 10; k++) for (const f of undo ? [op, undo] : [op]) {
        const t0 = Date.now(); const reply = ok(f, await rcon.command('function createcheme_bench:' + f)); const t1 = Date.now();
        info.marginal.push({op: f, t0, t1, rttMs: t1 - t0, reply: reply.slice(0, 80)}); await L.sleep(500);
      }
    note('marginal done', {commands: info.marginal.length});
    await L.sleep(3000);
  }
`);
fs.writeFileSync(file, s);
console.log('run-server.js patched');
