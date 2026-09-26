// One WP5 client run: fresh copy of the template world opened by quick play in the dev client (integrated server,
// rendering in the same JVM), driven through the langyo/minecraft-mod-mcp bridge's HTTP endpoint (the mod the MCP
// server talks to). Commands are typed into chat (open_chat + type_text + Enter): the bridge's execute_command goes
// through a KubeJS client method that the server never sees. Every command's chat reply is read back from the log and
// the run fails on "Unknown or incomplete command" or any other error. Scenario function, warm-up, measured window
// (JFR with Minecraft's profile minus heap inspection, external sampler, jcmd thread CPU every 10 s; client FPS comes
// from the rig's fluid_diag_fps.csv), then a plain and an F3 screenshot, save and quit. Every wait is bounded.
// Usage: node run-client.js <worktree> <runId> <scenario> [warmupSeconds=60] [windowSeconds=60]
const fs = require('fs'), path = require('path'), cp = require('child_process');
const L = require('./rig-lib');
const [worktree, runId, scenario, warmupArg, windowArg] = process.argv.slice(2);
if (!worktree || !runId || !scenario) { console.error('usage: node run-client.js <worktree> <runId> <scenario> [warmup] [window]'); process.exit(2); }
const warmup = Number(warmupArg || 60), window = Number(windowArg || 60);
const rig = __dirname, logs = path.resolve(rig, '..', 'wp5-logs', runId);
const run = path.join(worktree, 'run'), level = 'bench-' + runId, template = path.join(rig, 'template', 'bench-template');
const EXPECTED = {empty: 0, rest100: 500, through100: 700, fill100: 800, mixed100: 700, rest1000: 5000, pure100: 600};
const info = {runId, scenario, kind: 'client', worktree, warmupSeconds: warmup, windowSeconds: window, events: []};
const note = (what, extra = {}) => { const e = {t: Date.now(), iso: L.stamp(), what, ...extra}; info.events.push(e); console.log(e.iso, what, JSON.stringify(extra).slice(0, 400)); fs.writeFileSync(path.join(logs, 'run.json'), JSON.stringify(info, null, 2)); };
const BRIDGE = 'http://127.0.0.1:9876';
const latest = path.join(run, 'logs', 'latest.log');
async function status() { try { const r = await fetch(BRIDGE + '/api/status', {signal: AbortSignal.timeout(2000)}); return r.ok ? await r.json() : null; } catch { return null; } }
async function cmd(name, params = {}, timeoutMs = 30000) {
  const r = await fetch(BRIDGE + '/api/cmd', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({cmd: name, ...params}), signal: AbortSignal.timeout(timeoutMs)});
  const text = await r.text(); let v; try { v = JSON.parse(text); } catch { v = text; }
  if (!r.ok) throw new Error(`bridge ${name} -> ${r.status} ${text}`);
  return v;
}
const size = () => fs.existsSync(latest) ? fs.statSync(latest).size : 0;
const tail = from => { const b = fs.readFileSync(latest); return b.slice(from).toString('utf8'); };
/** Types a command into chat and waits (at most 15 s) for its chat reply; fails the run on an error reply. */
async function command(c, expect, timeoutMs = 15000) {
  const from = size();
  await cmd('open_chat'); await L.sleep(300);
  await cmd('type_text', {text: c, press_enter: true});
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const t = tail(from);
    const bad = /\[CHAT\] (Unknown or incomplete command|Unknown function|Incorrect argument|That position is not loaded|An unexpected error|Could not|No player was found|You do not have permission)[^\n]*/.exec(t);
    if (bad) { note('command failed', {command: c, reply: bad[0]}); throw new Error(`command ${c} failed: ${bad[0]}`); }
    const good = expect.exec(t);
    if (good) { note('command', {command: c, reply: good[0]}); return good[0]; }
    if (Date.now() > deadline) { note('command timeout', {command: c, log: t.slice(-800)}); throw new Error(`no reply to ${c} within ${timeoutMs} ms`); }
    await L.sleep(250);
  }
}
/** The last fluid_diag period line after an offset, with devices and islands. */
function lastDiag(from) { const m = [...tail(from).matchAll(/fluid_diag online_tick=(\d+)[^\n]*? devices=(\d+) islands=(\d+) kinds=(\{[^}]*\}) statuses=(\{[^}]*\})/g)].pop(); return m ? {onlineTick: +m[1], devices: +m[2], islands: +m[3], kinds: m[4], statuses: m[5]} : null; }
const OPTIONS = ['pauseOnLostFocus:false', 'onboardAccessibility:false', 'tutorialStep:none', 'skipMultiplayerWarning:true', 'joinedFirstServer:true',
  'renderDistance:10', 'simulationDistance:10', 'maxFps:120', 'enableVsync:false', 'inactivityFpsLimit:"minimized"', 'fullscreen:false', 'soundCategory_master:0.0'].join('\n') + '\n';
let child = null, exited = false;
function killGame() { if (info.pid) try { L.ps(`Stop-Process -Id ${info.pid} -Force`); } catch {} }
(async () => {
  if (fs.existsSync(logs)) throw new Error('run id exists: ' + logs);
  fs.mkdirSync(logs, {recursive: true});
  if (EXPECTED[scenario] === undefined) throw new Error('unknown scenario ' + scenario);
  const m = L.machine(); info.machineBefore = m; note('machine', m);
  if (m.endfield > 0 || m.freeMiB < 20480) throw new Error('machine gate failed: ' + JSON.stringify(m));
  if (L.javaProcesses().some(p => /runClient|runServer|devlaunch|BootstrapLauncher|fml\.modFolders/.test(p.CommandLine || ''))) throw new Error('another game JVM is running');
  if (await status()) throw new Error('a bridge endpoint is already answering on 9876');
  // Bridge jar present for client runs only.
  const mods = path.join(run, 'mods'), aside = path.join(run, 'mods-client-only'); fs.mkdirSync(mods, {recursive: true});
  if (fs.existsSync(aside)) for (const f of fs.readdirSync(aside)) if (/minecraft-mcp/.test(f)) fs.renameSync(path.join(aside, f), path.join(mods, f));
  if (!fs.readdirSync(mods).some(f => /minecraft-mcp/.test(f))) throw new Error('bridge jar missing from run/mods');
  fs.writeFileSync(path.join(run, 'options.txt'), OPTIONS);
  const saves = path.join(run, 'saves'); fs.mkdirSync(saves, {recursive: true});
  L.rmDir(path.join(saves, level)); L.copyDir(template, path.join(saves, level));
  for (const f of ['fluid_diag_ticks.csv', 'fluid_diag_fps.csv']) try { fs.unlinkSync(path.join(run, f)); } catch {}
  const gradleLog = path.join(logs, 'gradle-client.log');
  note('launch');
  child = L.spawnGradle(worktree, ['runClient', '--offline', '--console=plain', '-PfluidRigDiagnosticTicks=200', '-PfluidRigHeapMiB=4096', '-PfluidClientQuickPlay=' + level], gradleLog);
  child.on('exit', c => { exited = true; note('gradle exit', {code: c}); });
  for (let i = 0; !(await status()); i++) { if (exited || i > 600) throw new Error('bridge never came up'); await L.sleep(1000); }
  note('bridge up', await status());
  const jvm = await L.findGameJvm(worktree, 'client', 60000); info.pid = jvm.ProcessId; note('jvm', {pid: jvm.ProcessId});
  await L.waitForLine(latest, /Dev joined the game/, 0, 300000); note('joined');
  await L.sleep(4000);
  note('control', {reply: await cmd('enter_control_mode')});
  await command('/function createcheme_bench:view', /\[CHAT\] Running function createcheme_bench:view[^\n]*/);
  note('view', {reply: await cmd('set_view_angle', {yaw: 0, pitch: 90})});
  await command('/function createcheme_bench:forceload', /\[CHAT\] Running function createcheme_bench:forceload[^\n]*/);
  await L.sleep(5000);
  const placeFrom = size(); note('place start'); const placeT0 = Date.now();
  await command('/function createcheme_bench:' + scenario, new RegExp('\\[CHAT\\] \\[Dev\\] createcheme_bench ' + scenario + ' placed (\\d+) devices'), 600000);
  info.placementMillis = Date.now() - placeT0; note('place done', {placementMillisIncludingChatRoundTrip: info.placementMillis});
  // The islands must exist before the warm-up counts: wait (at most 30 s) for a fluid_diag period with every device.
  for (let i = 0; ; i++) {
    const d = lastDiag(placeFrom);
    if (d && d.devices === EXPECTED[scenario]) { note('devices registered', d); info.placedState = d; break; }
    if (i > 60) throw new Error('devices never registered: ' + JSON.stringify(d));
    await L.sleep(500);
  }
  await L.sleep(warmup * 1000);
  L.heapInfo(info.pid, path.join(logs, 'heap-start.txt'));
  const sampler = L.startSampler(rig, info.pid, path.join(logs, 'sampler.csv'));
  note('jfr start', {reply: L.jfrStart(info.pid, rig, path.join(logs, 'window.jfr'))});
  info.windowStart = L.threadDump(info.pid, path.join(logs, 'threads-start.txt')); note('window start');
  for (let k = 1, until = info.windowStart + window * 1000; Date.now() < until; k++) {
    await L.sleep(Math.max(0, Math.min(10000, until - Date.now())));
    if (exited) throw new Error('client exited inside the window');
    if (Date.now() < until - 1000) L.threadDump(info.pid, path.join(logs, `threads-mid-${String(k).padStart(2, '0')}.txt`));
  }
  info.windowEnd = L.threadDump(info.pid, path.join(logs, 'threads-end.txt')); note('window end');
  note('jfr stop', {reply: L.jfrStop(info.pid)});
  await sampler.stop();
  L.heapInfo(info.pid, path.join(logs, 'heap-end.txt'));
  L.fullGc(info.pid, path.join(logs, 'heap-live.txt')); note('live heap');
  info.endState = lastDiag(placeFrom); note('end state', info.endState);
  // Screenshots after the window: plain, then with the F3 overlay (real key event; the window is brought forward).
  note('screenshot', await cmd('screenshot_to_file', {path: path.join(logs, 'view.png')}));
  try {
    note('f3 on', {out: cp.execFileSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(rig, 'tools', 'f3.ps1')], {encoding: 'utf8'}).trim()});
    await L.sleep(2500);
    note('screenshot f3', await cmd('screenshot_to_file', {path: path.join(logs, 'view-f3.png')}));
    cp.execFileSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(rig, 'tools', 'f3.ps1')], {encoding: 'utf8'});
  } catch (e) { note('f3 failed', {message: String(e)}); }
  for (const f of ['view.png', 'view-f3.png']) if (fs.existsSync(path.join(logs, f))) try { cp.execFileSync(path.join(L.JDK, 'java.exe'), ['-cp', path.join(rig, 'tools'), 'Flatten', path.join(logs, f)]); } catch {}
  // Save and quit to the title screen (the pause screen's bottom button), then stop the client.
  try {
    await cmd('pause_game'); await L.sleep(1500);
    note('save and quit', await cmd('click', {x: 426, y: 384}));
    for (let i = 0; i < 60 && !/Stopping server|Saving worlds/.test(tail(0).slice(-20000)); i++) await L.sleep(500);
    await L.sleep(5000);
  } catch (e) { note('quit failed', {message: String(e)}); }
  note('close client'); killGame();
  for (let i = 0; i < 60 && !exited; i++) await L.sleep(500);
  await L.sleep(2000);
  fs.copyFileSync(latest, path.join(logs, 'latest.log'));
  for (const f of ['fluid_diag_ticks.csv', 'fluid_diag_fps.csv']) if (fs.existsSync(path.join(run, f))) fs.renameSync(path.join(run, f), path.join(logs, f));
  info.machineAfter = L.machine(); note('done', info.machineAfter);
  L.rmDir(path.join(saves, level));
  process.exit(0);
})().catch(e => {
  try { note('error', {message: String(e && e.stack || e)}); } catch {}
  console.error(e); killGame(); try { note('killed after error', {pids: L.killGameJvms(worktree)}); } catch {}
  try { if (fs.existsSync(latest)) fs.copyFileSync(latest, path.join(logs, 'latest.log')); } catch {}
  setTimeout(() => process.exit(1), 3000);
});
