// One WP5 dedicated-server run: fresh copy of the template world, runServer (no rendering in the JVM), scenario
// function over RCON, warm-up, measured window (JFR with Minecraft's profile minus heap inspection, external sampler, jcmd thread CPU), /stop.
// Usage: node run-server.js <worktree> <runId> <scenario> [warmupSeconds=60] [windowSeconds=60]
const fs = require('fs'), path = require('path');
const L = require('./rig-lib');
const {Rcon} = require('./rcon');
const [worktree, runId, scenario, warmupArg, windowArg] = process.argv.slice(2);
if (!worktree || !runId || !scenario) { console.error('usage: node run-server.js <worktree> <runId> <scenario> [warmup] [window]'); process.exit(2); }
const warmup = Number(warmupArg || 60), window = Number(windowArg || 60);
const rig = __dirname, logs = path.resolve(rig, '..', 'wp5-logs', runId);
const run = path.join(worktree, 'run'), level = 'bench-' + runId, template = path.join(rig, 'template', 'bench-template');
const EXPECTED = {empty: 0, rest100: 500, through100: 700, fill100: 800, mixed100: 700, rest1000: 5000, pure100: 600, probe: 189, probe2: 480, probe3: 144, probe4: 264};
const info = {runId, scenario, kind: 'server', worktree, warmupSeconds: warmup, windowSeconds: window, events: []};
const note = (what, extra = {}) => { const e = {t: Date.now(), iso: L.stamp(), what, ...extra}; info.events.push(e); console.log(e.iso, what, JSON.stringify(extra)); fs.writeFileSync(path.join(logs, 'run.json'), JSON.stringify(info, null, 2)); };
(async () => {
  if (fs.existsSync(logs)) throw new Error('run id exists: ' + logs);
  fs.mkdirSync(logs, {recursive: true});
  const m = L.machine(); info.machineBefore = m; note('machine', m);
  if (m.endfield > 0 || m.freeMiB < 20480) throw new Error('machine gate failed: ' + JSON.stringify(m));
  if (L.javaProcesses().some(p => /runClient|runServer|devlaunch|BootstrapLauncher/.test(p.CommandLine || ''))) throw new Error('another game JVM is running');
  // Bridge jar kept out of the server's mods folder (client-only).
  const mods = path.join(run, 'mods'), aside = path.join(run, 'mods-client-only'); fs.mkdirSync(aside, {recursive: true});
  for (const f of fs.readdirSync(mods)) if (/minecraft-mcp/.test(f)) fs.renameSync(path.join(mods, f), path.join(aside, f));
  L.rmDir(path.join(run, level)); L.copyDir(template, path.join(run, level));
  fs.writeFileSync(path.join(run, 'server.properties'), fs.readFileSync(path.join(rig, 'server.properties.in'), 'utf8').replace('@LEVEL@', level));
  const debugDir = path.join(run, 'debug'); const jfrBefore = new Set(fs.existsSync(debugDir) ? fs.readdirSync(debugDir) : []);
  const gradleLog = path.join(logs, 'gradle-server.log');
  note('launch');
  const child = L.spawnGradle(worktree, ['runServer', '--offline', '--console=plain', '-PfluidRigDiagnosticTicks=200', '-PfluidRigHeapMiB=4096'], gradleLog);
  let exited = false; child.on('exit', c => { exited = true; note('gradle exit', {code: c}); });
  await L.waitForLine(gradleLog, /Done \([0-9.]+s\)! For help|BUILD FAILED|Exception in thread "main"/, 0, 900000);
  if (!/Done \([0-9.]+s\)! For help/.test(fs.readFileSync(gradleLog, 'utf8'))) throw new Error('server did not start; see gradle-server.log');
  note('server ready');
  const jvm = await L.findGameJvm(worktree, 'server', 60000); info.pid = jvm.ProcessId; note('jvm', {pid: jvm.ProcessId});
  const rcon = await new Rcon().connect();
  const ok = (c, reply) => { if (!/^Running function /.test(reply)) throw new Error(`command ${c} failed: ${reply}`); return reply; };
  note('forceload', {reply: ok('forceload', await rcon.command('function createcheme_bench:forceload'))});
  await L.sleep(5000);
  const placeFrom = fs.statSync(gradleLog).size;
  note('place start');
  const t0 = Date.now(); const reply = ok(scenario, await rcon.command('function createcheme_bench:' + scenario, 3600000)); const placeMs = Date.now() - t0;
  info.placementMillis = placeMs; note('place done', {reply, placementMillis: placeMs});
  // The islands must exist before the warm-up counts: wait (at most 30 s) for a fluid_diag period with every device.
  for (let i = 0; ; i++) {
    const text = fs.readFileSync(gradleLog).slice(placeFrom).toString('utf8');
    const d = [...text.matchAll(/fluid_diag online_tick=(\d+)[^\n]*? devices=(\d+) islands=(\d+) kinds=(\{[^}]*\}) statuses=(\{[^}]*\})/g)].pop();
    if (d && +d[2] === EXPECTED[scenario]) { info.placedState = {onlineTick: +d[1], devices: +d[2], islands: +d[3], kinds: d[4], statuses: d[5]}; note('devices registered', info.placedState); break; }
    if (i > 60 || exited) throw new Error('devices never registered: ' + (d ? d[0] : 'no fluid_diag line'));
    await L.sleep(500);
  }
  await L.sleep(warmup * 1000);
  if (exited) throw new Error('server exited during the warm-up');
  L.heapInfo(info.pid, path.join(logs, 'heap-start.txt'));
  const sampler = L.startSampler(rig, info.pid, path.join(logs, 'sampler.csv'));
  note('jfr start', {reply: L.jfrStart(info.pid, rig, path.join(logs, 'window.jfr'))});
  info.windowStart = L.threadDump(info.pid, path.join(logs, 'threads-start.txt')); note('window start');
  // Intermediate thread dumps every 10 s: solver worker threads come and go with the allocator, and a thread that
  // exits between two dumps would otherwise lose its CPU time.
  for (let k = 1, until = info.windowStart + window * 1000; Date.now() < until; k++) {
    await L.sleep(Math.max(0, Math.min(10000, until - Date.now())));
    if (Date.now() < until - 1000) L.threadDump(info.pid, path.join(logs, `threads-mid-${String(k).padStart(2, '0')}.txt`));
  }
  info.windowEnd = L.threadDump(info.pid, path.join(logs, 'threads-end.txt')); note('window end');
  note('jfr stop', {reply: L.jfrStop(info.pid)});
  await sampler.stop();
  L.heapInfo(info.pid, path.join(logs, 'heap-end.txt'));
  // After the window: one full collection, so heap-live.txt is the retained (live) heap of the scenario.
  L.fullGc(info.pid, path.join(logs, 'heap-live.txt')); note('live heap');
  note('stop', {reply: await rcon.command('stop').catch(e => String(e))});
  rcon.close();
  for (let i = 0; i < 240 && !exited; i++) await L.sleep(500);
  if (!exited) { note('kill'); try { L.ps(`Stop-Process -Id ${info.pid} -Force`); } catch {} }
  await L.sleep(3000);
  for (const f of fs.existsSync(debugDir) ? fs.readdirSync(debugDir) : []) if (!jfrBefore.has(f) && f.endsWith('.jfr')) { fs.renameSync(path.join(debugDir, f), path.join(logs, f)); note('jfr file', {file: f}); }
  fs.copyFileSync(path.join(run, 'logs', 'latest.log'), path.join(logs, 'latest.log'));
  if (fs.existsSync(path.join(run, 'fluid_diag_ticks.csv'))) fs.renameSync(path.join(run, 'fluid_diag_ticks.csv'), path.join(logs, 'fluid_diag_ticks.csv'));
  for (const f of fs.existsSync(debugDir) ? fs.readdirSync(debugDir) : []) if (!jfrBefore.has(f) && f.endsWith('.json')) fs.renameSync(path.join(debugDir, f), path.join(logs, f));
  info.machineAfter = L.machine(); note('done', info.machineAfter);
  L.rmDir(path.join(run, level));
  process.exit(0);
})().catch(e => {
  try { note('error', {message: String(e && e.stack || e)}); } catch {}
  console.error(e);
  // Never leave a server behind: the next Gradle invocation must be the only one.
  if (info.pid) try { L.ps(`Stop-Process -Id ${info.pid} -Force`); } catch {}
  try { note('killed after error', {pids: L.killGameJvms(worktree)}); } catch {}
  try { fs.copyFileSync(path.join(run, 'logs', 'latest.log'), path.join(logs, 'latest.log')); } catch {}
  setTimeout(() => process.exit(1), 3000);
});
