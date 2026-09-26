// WP5 in-game campaign driver: runs the listed runs one after another (one Gradle invocation at a time), each
// through run-server.js or run-client.js, waiting for the machine gate; a run whose JVM crashed keeps its hs_err
// file under $RIG_LOGS/jvm-crash/ and is rerun once under the id <runId>-rerun. Builds: wp5 = $RIG_WORKTREE (the
// candidate), base = $RIG_BASE_WORKTREE (a baseline worktree carrying mod-side/baseline-eb28fc5 or reattach.patch).
// Usage: node campaign.js <plan>   plans: server, soak, client, or a comma list of run specs kind:build:scenario[:warmup:window]
const fs = require('fs'), path = require('path'), cp = require('child_process');
const L = require('./rig-lib');
const WT = {base: process.env.RIG_BASE_WORKTREE, wp5: process.env.RIG_WORKTREE};
const SCENARIOS = ['empty', 'rest100', 'through100', 'fill100', 'mixed100', 'rest1000'];
const rig = __dirname, logs = L.logsRoot(), log = path.join(logs, 'campaign.log');
const say = s => { const line = `${new Date().toISOString()} ${s}`; console.log(line); fs.appendFileSync(log, line + '\n'); };
function plan(name) {
  const alt = (kind) => SCENARIOS.flatMap((s, i) => (i % 2 ? ['wp5', 'base'] : ['base', 'wp5']).map(b => ({kind, build: b, scenario: s, warmup: 60, window: 60})));
  if (name === 'server') return [...alt('server'), ...['base', 'wp5'].map(b => ({kind: 'server', build: b, scenario: 'pure100', warmup: 60, window: 60}))];
  if (name === 'client') return alt('client');
  if (name === 'soak') return ['rest100', 'fill100'].map(s => ({kind: 'server', build: 'wp5', scenario: s, warmup: 60, window: 300, soak: true}));
  return name.split(',').map(x => { const [kind, build, scenario, warmup, window, rep] = x.split(':'); return {kind, build, scenario, warmup: +(warmup || 60), window: +(window || 60), rep: rep || '01'}; });
}
function runId(r) { return `${r.kind === 'server' ? 'srv' : 'cli'}-${r.scenario}-${r.build}${r.soak ? '-soak' : ''}-r${r.rep || '01'}`; }
function crashFiles(wt) { const out = []; for (const d of [wt, path.join(wt, 'run')]) if (fs.existsSync(d)) for (const f of fs.readdirSync(d)) if (/^hs_err_pid\d+\.log$/.test(f)) out.push(path.join(d, f)); return out; }
(async () => {
  fs.mkdirSync(logs, {recursive: true}); fs.mkdirSync(path.join(logs, 'jvm-crash'), {recursive: true});
  for (const r of plan(process.argv[2] || 'server')) {
    let id = runId(r);
    for (let attempt = 1; attempt <= 2; attempt++) {
      for (;;) { const m = L.machine(); if (m.endfield === 0 && m.freeMiB >= 20480) { say(`gate ok ${JSON.stringify(m)} for ${id}`); break; } say(`gate failed ${JSON.stringify(m)}; waiting 60 s`); await L.sleep(60000); }
      if (!WT[r.build]) throw new Error(`set ${r.build === 'base' ? 'RIG_BASE_WORKTREE' : 'RIG_WORKTREE'} for build ${r.build}`);
      const before = new Set(crashFiles(WT[r.build]));
      say(`start ${id} (${r.kind} ${r.build} ${r.scenario} warmup ${r.warmup} s window ${r.window} s)`);
      const script = path.join(rig, r.kind === 'server' ? 'run-server.js' : 'run-client.js');
      const res = cp.spawnSync(process.execPath, [script, WT[r.build], id, r.scenario, String(r.warmup), String(r.window)], {stdio: ['ignore', fs.openSync(path.join(logs, id + '.console.log'), 'w'), 'inherit'], timeout: 3 * 3600 * 1000});
      say(`end ${id} status=${res.status}`);
      const crashes = crashFiles(WT[r.build]).filter(f => !before.has(f));
      if (crashes.length) { for (const f of crashes) fs.copyFileSync(f, path.join(logs, 'jvm-crash', id + '-' + path.basename(f))); say(`JVM crash in ${id}: ${crashes.join(', ')}; rerun once`); id += '-rerun'; continue; }
      if (res.status !== 0) say(`run ${id} failed (status ${res.status}); see ${id}.console.log`);
      else { try { require('./analyze').analyze(path.join(logs, id)); say(`analyzed ${id}`); } catch (e) { say(`analysis of ${id} failed: ${e.message}`); } }
      break;
    }
  }
  say('campaign done');
})();
