// WP11: process CPU (in cores) and GC over a window of a paced benchmark's JFR (jfr print --json output of jdk.CPULoad,
// jdk.GarbageCollection, jdk.GCHeapSummary). Usage: node jfr-window.js <dir with cpuload.json gc.json heap.json> <windowStartISO> <windowEndISO> <cores>
const fs = require("fs"), path = require("path");
const [dir, from, to, cores] = process.argv.slice(2);
const t0 = Date.parse(from), t1 = Date.parse(to), n = +cores;
const ev = f => JSON.parse(fs.readFileSync(path.join(dir, f), "utf8")).recording.events;
const inWin = e => { const t = Date.parse(e.values.startTime); return t >= t0 && t <= t1; };
const cpu = ev("cpuload.json").filter(inWin);
const user = cpu.reduce((s, e) => s + e.values.jvmUser, 0) / cpu.length, sys = cpu.reduce((s, e) => s + e.values.jvmSystem, 0) / cpu.length;
const machine = cpu.reduce((s, e) => s + e.values.machineTotal, 0) / cpu.length;
console.log(`window ${from} .. ${to}: ${cpu.length} CPU samples; process CPU ${((user + sys) * n).toFixed(3)} cores (user ${(user * n).toFixed(3)}, system ${(sys * n).toFixed(3)}); machine total ${(machine * n).toFixed(3)} cores of ${n}`);
const gc = ev("gc.json").filter(inWin);
console.log(`collections in the window: ${gc.length}: ` + gc.map(e => `${e.values.startTime.slice(11, 23)} ${e.values.name} ${e.values.sumOfPauses}`).join("; "));
const heap = ev("heap.json").filter(inWin).filter(e => e.values.when === "After GC");
if (heap.length) console.log(`heap used after GC in the window (MiB): ` + heap.map(e => (e.values.heapUsed / 1048576).toFixed(0)).join(", "));
