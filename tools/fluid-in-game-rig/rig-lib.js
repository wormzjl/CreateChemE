// Shared helpers for the WP5 in-game benchmark runs (server and client).
const fs = require('fs'), path = require('path'), cp = require('child_process');
// tools/ copy: the JDK (jcmd, jfr, java) and the folder that receives the run directories come from the environment.
const JDK = process.env.RIG_JDK || 'C:/Program Files/Java/jdk-21.0.11/bin';
/** RIG_LOGS: the folder that receives one directory per run (a batch folder's <package>-logs/rig/, for instance). */
function logsRoot() { if (!process.env.RIG_LOGS) throw new Error('set RIG_LOGS to the folder that receives the run directories'); return path.resolve(process.env.RIG_LOGS); }
const sleep = ms => new Promise(r => setTimeout(r, ms));
function ps(script) { return cp.execFileSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', script], {encoding: 'utf8', maxBuffer: 64 << 20}); }
/** Machine gate before every measured run: no Endfield.exe and at least 20 GB free physical memory. */
function machine() {
  const out = JSON.parse(ps("$e=@(Get-Process Endfield -ErrorAction SilentlyContinue).Count; $f=(Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory; @{endfield=$e; freeKiB=[long]$f} | ConvertTo-Json -Compress"));
  return {endfield: out.endfield, freeMiB: Math.round(out.freeKiB / 1024)};
}
function javaProcesses() {
  const raw = ps("Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Select-Object ProcessId,ParentProcessId,CommandLine,@{n='Created';e={$_.CreationDate.ToString('o')}} | ConvertTo-Json -Compress -Depth 3");
  if (!raw.trim()) return [];
  const v = JSON.parse(raw); return Array.isArray(v) ? v : [v];
}
/** The game JVM of a run: a java.exe whose command line names the worktree's run directory and the given main marker. */
async function findGameJvm(worktree, marker, timeoutMs = 300000) {
  const wt = worktree.replace(/\//g, '\\').toLowerCase(), wt2 = worktree.replace(/\\/g, '/').toLowerCase();
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const hits = javaProcesses().filter(p => p.CommandLine && (p.CommandLine.toLowerCase().includes(wt) || p.CommandLine.toLowerCase().includes(wt2)) && p.CommandLine.includes(marker) && !p.CommandLine.includes('GradleDaemon') && !p.CommandLine.includes('GradleWorkerMain'));
    if (hits.length === 1) return hits[0];
    if (hits.length > 1) throw new Error('more than one game JVM: ' + hits.map(h => h.ProcessId).join(','));
    await sleep(2000);
  }
  throw new Error('game JVM not found');
}
function threadDump(pid, file) {
  const t0 = Date.now();
  const out = cp.execFileSync(path.join(JDK, 'jcmd.exe'), [String(pid), 'Thread.print'], {encoding: 'utf8', maxBuffer: 256 << 20});
  fs.writeFileSync(file, `# epoch_ms=${t0} took_ms=${Date.now() - t0}\n` + out);
  return t0;
}
function heapInfo(pid, file) {
  const out = cp.execFileSync(path.join(JDK, 'jcmd.exe'), [String(pid), 'GC.heap_info'], {encoding: 'utf8'});
  fs.writeFileSync(file, `# epoch_ms=${Date.now()}\n` + out);
}
function fullGc(pid, file) {
  const t0 = Date.now();
  const run = cp.execFileSync(path.join(JDK, 'jcmd.exe'), [String(pid), 'GC.run'], {encoding: 'utf8'});
  const took = Date.now() - t0;
  const out = cp.execFileSync(path.join(JDK, 'jcmd.exe'), [String(pid), 'GC.heap_info'], {encoding: 'utf8'});
  fs.writeFileSync(file, `# epoch_ms=${t0} gc_run_ms=${took}\n` + run + out);
}
/** The window's flight recording: Minecraft's own profile without jdk.ObjectCount, see make-jfc.js. */
function jfrStart(pid, rigDir, file) {
  return cp.execFileSync(path.join(JDK, 'jcmd.exe'), [String(pid), 'JFR.start', 'name=wp5window', 'settings=' + path.join(rigDir, 'wp5-window.jfc'), 'filename=' + file], {encoding: 'utf8'}).trim();
}
function jfrStop(pid) {
  return cp.execFileSync(path.join(JDK, 'jcmd.exe'), [String(pid), 'JFR.stop', 'name=wp5window'], {encoding: 'utf8'}).trim();
}
function startSampler(rigDir, pid, csv) {
  const stop = csv + '.stop'; try { fs.unlinkSync(stop); } catch {}
  const child = cp.spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', path.join(rigDir, 'sampler.ps1'), '-ProcessId', String(pid), '-Out', csv, '-StopFile', stop], {stdio: 'ignore'});
  return {child, stop: async () => { fs.writeFileSync(stop, ''); for (let i = 0; i < 50 && child.exitCode === null; i++) await sleep(100); try { fs.unlinkSync(stop); } catch {} }};
}
function copyDir(src, dst) { fs.cpSync(src, dst, {recursive: true}); }
function rmDir(d) { fs.rmSync(d, {recursive: true, force: true}); }
/** Waits until the file contains a line matching re, reading from offset; returns [match, newOffset]. */
async function waitForLine(file, re, fromOffset = 0, timeoutMs = 600000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (fs.existsSync(file)) {
      const text = fs.readFileSync(file, 'utf8'); const tail = text.slice(fromOffset);
      const m = tail.match(re); if (m) return [m, fromOffset + tail.indexOf(m[0]) + m[0].length];
    }
    await sleep(500);
  }
  throw new Error('timeout waiting for ' + re + ' in ' + file);
}
function spawnGradle(worktree, args, logFile) {
  const log = fs.openSync(logFile, 'w');
  const wt = path.resolve(worktree);
  const child = cp.spawn('cmd.exe', ['/c', path.join(wt, 'gradlew.bat'), ...args], {cwd: wt, env: {...process.env, JAVA_OPTS: '-Xshare:off'}, stdio: ['pipe', log, log], windowsHide: true});
  child.on('error', e => fs.writeSync(log, 'spawn error ' + e + '\n'));
  return child;
}
/** Kills every game JVM of the worktree (and its Gradle wrapper), after a failed run. */
function killGameJvms(worktree) {
  const wt = worktree.replace(/\//g, '\\').toLowerCase(), wt2 = worktree.replace(/\\/g, '/').toLowerCase(); const killed = [];
  for (const p of javaProcesses()) { const c = (p.CommandLine || '').toLowerCase(); if ((c.includes(wt) || c.includes(wt2)) && !c.includes('gradledaemon') && !c.includes('gradleworkermain')) { try { ps(`Stop-Process -Id ${p.ProcessId} -Force`); killed.push(p.ProcessId); } catch {} } }
  return killed;
}
function stamp() { return new Date().toISOString(); }
module.exports = {logsRoot, sleep, ps, machine, javaProcesses, findGameJvm, killGameJvms, threadDump, heapInfo, fullGc, jfrStart, jfrStop, startSampler, copyDir, rmDir, waitForLine, spawnGradle, stamp, JDK};
