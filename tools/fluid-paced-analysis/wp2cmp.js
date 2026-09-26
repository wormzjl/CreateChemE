// Compare a certificates-off and a certificates-on FluidServerBenchmark report:
// node wp2cmp.js <off/report.json> <on/report.json>
const path = require('path');
const [offPath, onPath] = process.argv.slice(2);
const off = require(path.resolve(offPath)), on = require(path.resolve(onPath));
const f = (v, d = 3) => v === undefined || v === null ? 'n/a' : (typeof v === 'number' ? (Math.abs(v) >= 1000 ? v.toFixed(0) : Math.abs(v) < 1e-3 && v !== 0 ? v.toExponential(2) : v.toFixed(d)) : String(v));
const mean = a => a.length ? a.reduce((x, y) => x + y, 0) / a.length : null;
function summary(r) {
  const c = r.certificates, rc = r.runtimeCounters, s = r.stressSamples || [];
  const measured = s.filter(x => true);
  const certifiedSeries = c.certifiedIslandsPerSecond || [];
  const mem = (r.memorySamples || []).filter(m => m.measured);
  return {
    profile: r.profile, restDetection: c.restDetection, runId: r.manifest && r.manifest.runId,
    integrity: r.stressIntegrityPassed, held: r.heldIntervals, approximate: r.approximateIntervals,
    balance: r.componentBalanceToleranceUnits, energyBalance: r.energyBalanceToleranceUnits,
    measuredSeconds: r.measuredSeconds, fullSolves: c.fullSolves,
    fullSolvesPerSecond: c.fullSolves / r.measuredSeconds,
    replayedIntervals: c.replayedIntervals, replayedSeconds: c.replayedSeconds,
    restedIntervals: c.restedIntervals, identityAdvancedSeconds: c.identityAdvancedSeconds,
    finalKinds: JSON.stringify(c.finalIslandKinds),
    certifiedFirst: certifiedSeries[0], certifiedLast: certifiedSeries[certifiedSeries.length - 1],
    certifiedMin: certifiedSeries.length ? Math.min(...certifiedSeries) : null, certifiedMax: certifiedSeries.length ? Math.max(...certifiedSeries) : null,
    meanActiveWorkers: mean(s.map(x => x.activeWorkers)), lastActiveWorkers: s.length ? s[s.length - 1].activeWorkers : null,
    zeroActiveWorkerSamples: s.filter(x => x.activeWorkers === 0).length + '/' + s.length,
    meanEligible: mean(s.map(x => x.eligibleIslands)), lastEligible: s.length ? s[s.length - 1].eligibleIslands : null,
    zeroDemandSamples: s.filter(x => x.eligibleIslands === 0 && x.readyJobs === 0 && x.outstandingJobs === 0).length + '/' + s.length,
    meanWorkerCpuOccupancy: r.meanWorkerCpuOccupancy,
    realtime: r.aggregateRealtimeRatio, realtimeInclCertified: r.aggregateRealtimeRatioIncludingCertified,
    readyToPubP50: r.readyToPublicationMilliseconds.median, readyToPubP95: r.readyToPublicationMilliseconds.p95, readyToPubMax: r.readyToPublicationMilliseconds.max,
    workerP50: r.workerMilliseconds.median, workerP95: r.workerMilliseconds.p95,
    engineP50: r.engineServerMillisecondsPerTick.median, engineP95: r.engineServerMillisecondsPerTick.p95, engineMax: r.engineServerMillisecondsPerTick.max,
    wholeTickP50: r.wholeTickMilliseconds.median, wholeTickP95: r.wholeTickMilliseconds.p95,
    solvesPerSecond: rc.perSecond.solvesDispatched, visitsPerSecond: rc.perSecond.islandVisits, pumpsPerSecond: rc.perSecond.readinessPumps,
    deadlinesPerSecond: rc.perSecond.deadlinesFired, materialisationsPerSecond: rc.perSecond.materialisations,
    certificatesIssued: rc.totals.certificatesIssued, certificatesRenewed: rc.totals.certificatesRenewed, certificateWakes: rc.totals.certificateWakes,
    idleTicks: rc.idleTicks + '/' + rc.ticks, idleTicksWithVisit: rc.idleTicksWithAny.islandVisits,
    heapAfterForcedGcMiB: c.heapAfterForcedGcBytes !== undefined ? c.heapAfterForcedGcBytes / 1048576 : null,
    heapAfterLastGcEndMiB: mem.length ? mem[mem.length - 1].heapAfterLastGc / 1048576 : null,
    heapUsedMedianMiB: mem.length ? mem.map(m => m.heapUsed).sort((a, b) => a - b)[Math.floor(mem.length / 2)] / 1048576 : null,
    gcCountInWindow: mem.length ? mem[mem.length - 1].gcCount - mem[0].gcCount : null,
  };
}
const a = summary(off), b = summary(on);
console.log(`\n| metric | off (${a.runId}) | on (${b.runId}) |\n|---|---|---|`);
for (const k of Object.keys(a)) if (!['runId'].includes(k)) console.log(`| ${k} | ${f(a[k])} | ${f(b[k])} |`);
// Reference-state comparison: interpolate each island to the reference tick.
function reference(r) {
  const ref = r.certificates.reference; if (!ref) return null;
  const t = ref.islandTick, out = {};
  for (const id of Object.keys(ref.before)) {
    const p = ref.before[id], q = ref.after[id];
    if (!q) { out[id] = null; continue; }
    const w = q.tick === p.tick ? 0 : (t - p.tick) / (q.tick - p.tick);
    const lerp = (x, y) => x.map((v, i) => v + w * (y[i] - v));
    out[id] = { exact: p.tick === t, inventories: p.inventories.map((row, n) => lerp(row, q.inventories[n])), external: lerp(p.external, q.external) };
  }
  return { tick: t, islands: out };
}
const ra = reference(off), rb = reference(on);
for (const exactOnly of [true, false]) if (ra && rb) {
  let worstInv = 0, worstInvAt = null, worstEnergy = 0, worstExt = 0, worstExtAt = null, compared = 0, interpolated = 0, missing = 0;
  let totalA = null, totalB = null;
  console.log(exactOnly ? '\n[islands on the reference grid in both runs]' : '\n[all islands, shifted grids interpolated]');
  for (const id of Object.keys(ra.islands)) {
    const x = ra.islands[id], y = rb.islands[id];
    if (!x || !y) { missing++; continue; }
    if (exactOnly && (!x.exact || !y.exact)) continue;
    compared++; if (!x.exact || !y.exact) interpolated++;
    x.inventories.forEach((row, n) => row.forEach((v, c) => {
      const w = y.inventories[n][c];
      if (c === row.length - 1) { const scale = Math.max(Math.abs(v), 1); const d = Math.abs(w - v) / scale; if (d > worstEnergy) worstEnergy = d; return; }
      if (v < 1e-30 && w < 1e-30) return;
      const d = Math.abs(w - v) / Math.max(Math.abs(v), 1e-30);
      if (v > 1e-12 * 1 && d > worstInv) { worstInv = d; worstInvAt = `${id}/node${n}/c${c} base ${v.toExponential(4)}`; }
    }));
    const scale = Math.max(...x.external.slice(0, -1).map(Math.abs), 1e-30);
    x.external.forEach((v, c) => { if (c === x.external.length - 1) return; const d = Math.abs(y.external[c] - v) / scale; if (d > worstExt) { worstExt = d; worstExtAt = `${id}/c${c}`; } });
    if (!totalA) { totalA = x.external.map(() => 0); totalB = y.external.map(() => 0); }
    x.external.forEach((v, c) => { totalA[c] += v; totalB[c] += y.external[c]; });
  }
  console.log(`\nReference tick ${ra.tick}: ${compared} islands compared (${interpolated} interpolated), ${missing} missing.`);
  console.log(`  max relative inventory deviation (component with base > 1e-12 mol): ${worstInv.toExponential(3)} at ${worstInvAt}`);
  console.log(`  max relative internal-energy deviation (scale max(|U|,1 J)): ${worstEnergy.toExponential(3)}`);
  console.log(`  max island boundary-ledger deviation (relative to that island's largest ledger component): ${worstExt.toExponential(3)} at ${worstExtAt}`);
  if (totalA) { let worst = 0; const big = Math.max(...totalA.slice(0, -1).map(Math.abs), 1e-30); totalA.forEach((v, c) => { if (c < totalA.length - 1) worst = Math.max(worst, Math.abs(totalB[c] - v) / big); }); console.log(`  summed boundary ledger deviation (relative to its largest component): ${worst.toExponential(3)}`); }
}
