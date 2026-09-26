// Parses one or more WP5 run directories into summary JSON (<runDir>/summary.json) and prints it.
// Sources per run: run.json (window bounds, placement), the Minecraft /jfr recording of the window, the external
// sampler CSV, the jcmd thread dumps at the window's start and end, fluid_diag_ticks.csv (per-tick whole-tick and
// engine time), the fluid_diag log lines (island kinds, statuses, scheduling counters), jcmd GC.heap_info after
// one full collection (live heap), and for client runs the bridge's debug fields (FPS).
// Usage: node analyze.js <runDir> [<runDir> ...]
const fs = require('fs'), path = require('path'), cp = require('child_process');
const JFR = path.join(process.env.RIG_JDK || 'C:/Program Files/Java/jdk-21.0.11/bin', 'jfr.exe');
const CORES = 16;
function quant(values, q) { if (!values.length) return null; const s = [...values].sort((a, b) => a - b); return s[Math.min(s.length - 1, Math.max(0, Math.ceil(q * s.length) - 1))]; }
function mean(v) { return v.length ? v.reduce((a, b) => a + b, 0) / v.length : null; }
function seconds(iso) { if (typeof iso === 'number') return iso; const m = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?$/.exec(iso || ''); return m ? (+(m[1] || 0)) * 3600 + (+(m[2] || 0)) * 60 + (+(m[3] || 0)) : NaN; }
function jfrEvents(file, types) {
  const out = cp.execFileSync(JFR, ['print', '--json', '--events', types.join(','), file], {encoding: 'utf8', maxBuffer: 1 << 30});
  return JSON.parse(out).recording.events;
}
function family(name) {
  if (name === 'Server thread') return 'server';
  if (name === 'Render thread') return 'render';
  if (/^createcheme|solve|fluid/i.test(name)) return 'fluidWorkers';
  if (/^GC Thread|^G1 |^GC /.test(name)) return 'gc';
  if (/CompilerThread|^C1 |^C2 /.test(name)) return 'jit';
  if (/^JFR|^Attach Listener|^RCON|^Signal Dispatcher/.test(name)) return 'observation';
  if (/^VM Thread|^VM Periodic|^Service Thread|^Monitor Deflation|^Notification Thread|^Common-Cleaner|^Reference Handler|^Finalizer/.test(name)) return 'jvmOther';
  return 'other';
}
function parseDump(file) {
  const text = fs.readFileSync(file, 'utf8'); const threads = new Map();
  for (const line of text.split(/\r?\n/)) {
    const m = /^"(.+?)"(?:.*?)\bcpu=([\d.]+)(ms|s)\b.*?\bnid=(\w+)/.exec(line); if (!m) continue;
    threads.set(m[4] + '|' + m[1], {name: m[1], cpuMs: +m[2] * (m[3] === 's' ? 1000 : 1)});
  }
  return threads;
}
function logTimes(runJson) { const d = new Date(runJson.events[0].t); return {y: d.getFullYear(), mo: d.getMonth(), d: d.getDate()}; }
// Console lines start "[HH:MM:SS]", latest.log lines "[23<month>2026 HH:MM:SS.mmm]" (the month name follows the locale).
function lineEpoch(line, day) { const m = /^\[[^\]]*?(\d\d):(\d\d):(\d\d)(?:\.(\d+))?\]/.exec(line); return m ? new Date(day.y, day.mo, day.d, +m[1], +m[2], +m[3], m[4] ? +m[4].slice(0, 3).padEnd(3, '0') : 0).getTime() : null; }
function parseMap(s) { const out = {}; if (!s) return out; for (const part of s.replace(/^\{|\}$/g, '').split(/,\s*/)) { const [k, v] = part.split('='); if (k && v !== undefined) out[k.trim()] = +v; } return out; }
function analyze(dir) {
  const run = JSON.parse(fs.readFileSync(path.join(dir, 'run.json'), 'utf8'));
  const w0 = run.windowStart, w1 = run.windowEnd, W = (w1 - w0) / 1000;
  const r = {runId: run.runId, scenario: run.scenario, kind: run.kind, windowSeconds: W, configuredWindowSeconds: run.windowSeconds, warmupSeconds: run.warmupSeconds, windowStartIso: new Date(w0).toISOString(), placementMillis: run.placementMillis ?? null};
  // Minecraft /jfr recording.
  const jfr = fs.readdirSync(dir).find(f => f.endsWith('.jfr'));
  if (jfr) {
    const ev = jfrEvents(path.join(dir, jfr), ['minecraft.ServerTickTime', 'jdk.CPULoad', 'jdk.GarbageCollection', 'jdk.GCHeapSummary', 'jdk.ThreadAllocationStatistics', 'jdk.ResidentSetSize']);
    const by = t => ev.filter(e => e.type === t).map(e => e.values);
    const rss = by('jdk.ResidentSetSize').map(v => v.size / 1048576);
    r.jfrRssMiB = {samples: rss.length, mean: mean(rss), max: rss.length ? Math.max(...rss) : null, last: rss.length ? rss[rss.length - 1] : null};
    const tick = by('minecraft.ServerTickTime').map(v => seconds(v.averageTickDuration) * 1000);
    r.mcServerTickTimeMs = {samples: tick.length, p50: quant(tick, .5), p95: quant(tick, .95), max: quant(tick, 1), mean: mean(tick)};
    const cpu = by('jdk.CPULoad');
    r.jfrCpuCores = {samples: cpu.length, jvm: mean(cpu.map(v => (v.jvmUser + v.jvmSystem) * CORES)), user: mean(cpu.map(v => v.jvmUser * CORES)), system: mean(cpu.map(v => v.jvmSystem * CORES)), machine: mean(cpu.map(v => v.machineTotal * CORES))};
    const gcs = by('jdk.GarbageCollection');
    const inspection = new Set(gcs.filter(g => /Heap Inspection/.test(g.cause)).map(g => g.gcId));
    const own = gcs.filter(g => !inspection.has(g.gcId));
    r.gc = {count: own.length, pauseMsTotal: own.reduce((a, g) => a + seconds(g.sumOfPauses) * 1000, 0), longestPauseMs: own.reduce((a, g) => Math.max(a, seconds(g.longestPause) * 1000), 0), names: [...new Set(own.map(g => g.name))], heapInspectionGcs: inspection.size};
    const heaps = by('jdk.GCHeapSummary');
    const after = heaps.filter(h => h.when === 'After GC' && !inspection.has(h.gcId)).map(h => h.heapUsed / 1048576);
    const inspectedAfter = heaps.filter(h => h.when === 'After GC' && inspection.has(h.gcId)).map(h => h.heapUsed / 1048576);
    const committed = heaps.map(h => h.heapSpace.committedSize / 1048576);
    r.heapMiB = {afterGcMean: mean(after), afterGcLast: after.length ? after[after.length - 1] : null, afterGcSamples: after.length, afterFullGcAtJfrStartEnd: inspectedAfter, committedMax: committed.length ? Math.max(...committed) : null};
    // Allocation: per thread, the growth of the cumulative counter over the recording.
    const alloc = new Map();
    for (const e of ev.filter(e => e.type === 'jdk.ThreadAllocationStatistics')) {
      const t = e.values.thread ? (e.values.thread.javaThreadId + '|' + e.values.thread.javaName) : 'x'; const a = alloc.get(t) || {first: null, last: 0, t0: null, t1: null};
      const at = Date.parse(e.values.startTime); if (a.first === null) { a.first = e.values.allocated; a.t0 = at; } a.last = e.values.allocated; a.t1 = at; alloc.set(t, a);
    }
    let bytes = 0, t0 = Infinity, t1 = 0; for (const a of alloc.values()) { bytes += a.last - a.first; t0 = Math.min(t0, a.t0); t1 = Math.max(t1, a.t1); }
    r.allocationMiBPerSecond = t1 > t0 ? bytes / 1048576 / ((t1 - t0) / 1000) : null;
    const report = fs.readdirSync(dir).find(f => /^jfr-report.*\.json$/.test(f));
    if (report) { const j = JSON.parse(fs.readFileSync(path.join(dir, report), 'utf8')); r.mcReport = {durationMs: j.durationMs, allocationMiBPerSecond: j.heap.allocationRateBytesPerSecond / 1048576, gcCount: j.heap.gcCount, gcTotalMs: j.heap.gcTotalDurationMs, jvmCpuMean: j.cpuPercent.jvm.average * CORES}; }
  }
  // External sampler.
  const csv = path.join(dir, 'sampler.csv');
  if (fs.existsSync(csv)) {
    const rows = fs.readFileSync(csv, 'utf8').trim().split(/\r?\n/).slice(1).map(l => l.split(',').map(Number)).filter(x => x[0] >= w0 - 1500 && x[0] <= w1 + 1500);
    if (rows.length > 1) {
      const a = rows[0], b = rows[rows.length - 1], dt = (b[0] - a[0]) / 1000;
      r.process = {samples: rows.length, cpuCores: (b[1] - a[1]) / dt, userCores: (b[2] - a[2]) / dt, kernelCores: (b[3] - a[3]) / dt,
        workingSetMiB: {mean: mean(rows.map(x => x[4] / 1048576)), last: b[4] / 1048576, max: Math.max(...rows.map(x => x[4] / 1048576))},
        privateMiB: {mean: mean(rows.map(x => x[5] / 1048576)), last: b[5] / 1048576}, threads: {mean: mean(rows.map(x => x[7])), last: b[7]}};
    }
  }
  // Thread CPU by family from the two jcmd dumps.
  const ds = path.join(dir, 'threads-start.txt'), de = path.join(dir, 'threads-end.txt');
  if (fs.existsSync(ds) && fs.existsSync(de)) {
    // Every dump of the window, in order: a thread's CPU in the window is its largest reading minus its reading at
    // the start (0 for a thread created inside the window); a thread that exited after its last reading loses only
    // what it did after that reading.
    const mids = fs.readdirSync(dir).filter(f => /^threads-mid-\d+\.txt$/.test(f)).sort().map(f => parseDump(path.join(dir, f)));
    const s = parseDump(ds), all = [...mids, parseDump(de)], seen = new Map();
    for (const dump of all) for (const [k, v] of dump) { const p = seen.get(k); if (!p || v.cpuMs > p.cpuMs) seen.set(k, v); }
    const fam = {}, top = [];
    for (const [k, v] of seen) { const d = (v.cpuMs - (s.get(k)?.cpuMs || 0)) / 1000 / W; const f = family(v.name); fam[f] = (fam[f] || 0) + d; top.push([v.name, d]); }
    r.threadDumps = 2 + mids.length;
    r.threadCores = fam; r.threadCoresTotal = Object.values(fam).reduce((a, b) => a + b, 0);
    if (r.process) r.threadCoresUnattributed = r.process.cpuCores - r.threadCoresTotal;
    r.topThreads = top.sort((a, b) => b[1] - a[1]).slice(0, 12).map(([n, d]) => `${n}=${d.toFixed(4)}`);
    r.threadNames = [...new Set([...seen.values()].map(v => v.name.replace(/[#-]?\d+$/, '#')))].sort();
  }
  // Per-tick times from the rig's CSV.
  const tc = path.join(dir, 'fluid_diag_ticks.csv');
  if (fs.existsSync(tc)) {
    const rows = fs.readFileSync(tc, 'utf8').trim().split(/\r?\n/).slice(1).map(l => l.split(',').map(Number)).filter(x => x[0] >= w0 && x[0] <= w1);
    const tk = rows.map(x => x[2]), en = rows.map(x => x[3]);
    r.ticks = {count: rows.length, tickMs: {p50: quant(tk, .5), p95: quant(tk, .95), p99: quant(tk, .99), max: quant(tk, 1), mean: mean(tk)}, engineMs: {p50: quant(en, .5), p95: quant(en, .95), p99: quant(en, .99), max: quant(en, 1), mean: mean(en)}, ticksPerSecond: rows.length / W};
  }
  // fluid_diag log lines.
  const logFile = ['latest.log', 'gradle-server.log', 'gradle-client.log'].map(f => path.join(dir, f)).find(f => fs.existsSync(f));
  if (logFile) {
    const day = logTimes(run); const lines = fs.readFileSync(logFile, 'utf8').split(/\r?\n/).filter(l => l.includes('fluid_diag'));
    const periodic = lines.filter(l => /fluid_diag online_tick=/.test(l)).map(l => ({t: lineEpoch(l, day), line: l}));
    const inWindow = periodic.filter(p => p.t >= w0 - 500 && p.t <= w1 + 500);
    const last = [...periodic].filter(p => p.t <= w1 + 1000).pop();
    const counters = {}; for (const p of inWindow) { const m = /counters=(\{.*\})/.exec(p.line); for (const [k, v] of Object.entries(parseMap(m && m[1]))) counters[k] = (counters[k] || 0) + v; }
    r.diagCountersInWindow = counters; r.diagPeriodsInWindow = inWindow.length;
    if (last) {
      const g = k => (new RegExp(k + '=(\\{[^}]*\\}|\\S+)').exec(last.line) || [])[1];
      r.endState = {onlineTick: +g('online_tick'), devices: +g('devices'), islands: +g('islands'), kinds: parseMap(g('kinds')), statuses: parseMap(g('statuses')), workersActive: +g('workers_active'), workersLimit: +g('workers_limit')};
    }
    const gaps = lines.filter(l => /gap_ms=/.test(l)).map(l => ({t: lineEpoch(l, day), ms: +/gap_ms=([\d.]+)/.exec(l)[1], devices: +/devices=(\d+)/.exec(l)[1]}));
    const placeStart = (run.events.find(e => e.what === 'place start') || {}).t;
    // The placement gap: the longest tick gap between the scenario command and the first period line with every device.
    const registered = (run.events.find(e => e.what === 'devices registered') || {}).t || Infinity;
    const pg = run.scenario === 'empty' ? [] : gaps.filter(g => placeStart && g.t >= placeStart - 1000 && g.t <= registered + 1000);
    r.placementGap = pg.length ? pg.reduce((a, b) => b.ms > a.ms ? b : a) : null;
    r.heldLines = fs.readFileSync(logFile, 'utf8').split(/\r?\n/).filter(l => /status=HELD/.test(l)).length;
  }
  // Live heap after one full collection at the end.
  const live = path.join(dir, 'heap-live.txt');
  if (fs.existsSync(live)) { const m = /garbage-first heap\s+total (\d+)K, used (\d+)K/.exec(fs.readFileSync(live, 'utf8')); if (m) r.liveHeapMiB = +m[2] / 1024, r.committedHeapAfterFullGcMiB = +m[1] / 1024; }
  // Client FPS: the rig's once-a-second Minecraft.getFps() log.
  const fpsCsv = path.join(dir, 'fluid_diag_fps.csv');
  if (fs.existsSync(fpsCsv)) {
    const fps = fs.readFileSync(fpsCsv, 'utf8').trim().split(/\r?\n/).slice(1).map(l => l.split(',').map(Number)).filter(x => x[0] >= w0 && x[0] <= w1).map(x => x[1]);
    r.fps = {samples: fps.length, mean: mean(fps), p5: quant(fps, .05), min: fps.length ? Math.min(...fps) : null, max: fps.length ? Math.max(...fps) : null};
  }
  fs.writeFileSync(path.join(dir, 'summary.json'), JSON.stringify(r, null, 2));
  return r;
}
module.exports = {analyze};
if (require.main === module) for (const d of process.argv.slice(2)) console.log(JSON.stringify(analyze(path.resolve(d)), null, 2));
