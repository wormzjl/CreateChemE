// WP11: side-by-side of two paced FluidServerBenchmark reports (report.json): window, workers, solves, worker and
// engine timings, certificates, balance, held intervals. Usage: node tools/wp11-closeout/bench-compare.js <a.json> <b.json> [labelA labelB]
const fs = require("fs");
const [a, b] = process.argv.slice(2, 4).map(p => JSON.parse(fs.readFileSync(p, "utf8")));
const la = process.argv[4] || "A", lb = process.argv[5] || "B";
const s = x => x == null ? "-" : typeof x === "object" ? JSON.stringify(x) : String(x);
const d = x => x ? `${x.median} / ${x.p95} / ${x.max} (n ${x.count})` : "-";
const rows = [
  ["run id", r => r.manifest.runId], ["artifact", r => r.manifest.artifact], ["status / gates", r => r.status + " / " + r.gatesPassed],
  ["property revision (head)", r => String(r.propertyRevision).slice(0, 90)],
  ["workers", r => r.workers], ["warm-up s / window s (measured)", r => r.warmupSeconds + " / " + r.measurementSecondsConfigured + " (" + r.measuredSeconds + ")"],
  ["reservoirs / physical pipes / compiled pipes / islands", r => [r.reservoirs, r.physicalPipes, r.compiledPipes, r.islands].join(" / ")],
  ["held intervals (window) / warm-up held", r => r.heldIntervals + " / " + r.warmupHeldIntervals],
  ["full solves in the window (worker ms count)", r => r.workerMilliseconds && r.workerMilliseconds.count],
  ["worker ms p50 / p95 / max", r => d(r.workerMilliseconds)],
  ["dispatch to publication ms", r => d(r.dispatchToPublicationMilliseconds)],
  ["ready to publication ms", r => d(r.readyToPublicationMilliseconds)],
  ["engine ms per tick p50 / p95 / max", r => d(r.engineServerMillisecondsPerTick)],
  ["whole tick ms p50 / p95 / max", r => d(r.wholeTickMilliseconds)],
  ["tick spacing ms", r => d(r.tickSpacingMilliseconds)],
  ["mean worker CPU occupancy (lower bound)", r => r.meanWorkerCpuOccupancy], ["mean reported active workers", r => r.meanReportedActiveWorkers],
  ["completed intervals per s", r => r.completedIntervalsPerSecond], ["aggregate realtime ratio (incl. certified)", r => r.aggregateRealtimeRatio + " (" + r.aggregateRealtimeRatioIncludingCertified + ")"],
  ["substeps per interval p50 / p95 / max", r => r.ladders && r.ladders.THROUGH && d(r.ladders.THROUGH.substeps)],
  ["component / energy balance units", r => r.componentBalanceToleranceUnits + " / " + r.energyBalanceToleranceUnits],
  ["integrity", r => r.stressIntegrityPassed],
];
console.log(`| measure | ${la} | ${lb} |`); console.log("|---|---|---|");
for (const [name, f] of rows) { let x, y; try { x = s(f(a)); } catch (e) { x = "-"; } try { y = s(f(b)); } catch (e) { y = "-"; } console.log(`| ${name} | ${x} | ${y} |`); }
for (const [lab, r] of [[la, a], [lb, b]]) {
  const c = r.certificates || {};
  console.log(`\n${lab} certificates: ` + JSON.stringify(Object.fromEntries(Object.entries(c).filter(([k]) => k !== "note" && !Array.isArray(c[k]) && typeof c[k] !== "object"))));
  const t = (r.runtimeCounters || {}).totals; if (t) console.log(`${lab} runtime counters: ` + JSON.stringify(t));
  if (r.memory) console.log(`${lab} memory: ` + JSON.stringify(r.memory).slice(0, 600));
}
