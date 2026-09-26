// Builds the WP3 benchmark tables from FluidServerBenchmark reports. node wp3tables.js <M9 directory>
const path = require('path'), fs = require('fs');
const dir = path.resolve(process.argv[2]);
const load = id => { const f = path.join(dir, id, 'report.json'); return fs.existsSync(f) ? JSON.parse(fs.readFileSync(f, 'utf8')) : null; };
const e = (v, d = 2) => v === null || v === undefined || Number.isNaN(v) ? 'n/a' : v === 0 ? '0' : Math.abs(v) >= 0.01 && Math.abs(v) < 1e5 ? Number(v).toFixed(d) : Number(v).toExponential(d);
const mean = a => a.length ? a.reduce((x, y) => x + y, 0) / a.length : NaN;
function row(r) {
  const c = r.certificates, s = r.stressSamples || [], rc = r.runtimeCounters, ticks = rc.ticks;
  const per100 = name => rc.totals[name] === undefined ? 'n/a' : e(rc.totals[name] / ticks * 100, 3);
  return {
    run: r.manifest.runId, rest: c.restDetection, window: `${r.warmupTicks / 20} s / ${r.measurementSecondsConfigured ?? Math.round(r.measuredSeconds)} s`, ref: c.reference ? c.reference.islandTick : 'n/a', full: c.fullSolves, solvesPerSecond: e(rc.perSecond.solvesDispatched),
    replayed: `${c.replayedIntervals} / ${e(c.replayedSeconds, 0)} s`, rested: `${c.restedIntervals} / ${e(c.identityAdvancedSeconds, 0)} s`,
    kinds: Object.entries(c.finalIslandKinds).map(([k, v]) => `${v} ${k}`).join(', '),
    eligible: e(mean(s.map(x => x.eligibleIslands))),
    ready: `${e(r.readyToPublicationMilliseconds.median)} / ${e(r.readyToPublicationMilliseconds.p95)}`,
    worker: `${e(r.workerMilliseconds.median)} / ${e(r.workerMilliseconds.p95)}`,
    engine: `${e(r.engineServerMillisecondsPerTick.median, 3)} / ${e(r.engineServerMillisecondsPerTick.p95, 3)} / ${e(r.engineServerMillisecondsPerTick.max, 1)}`,
    tick: `${e(r.wholeTickMilliseconds.median, 3)} / ${e(r.wholeTickMilliseconds.p95, 3)}`,
    viewBuilds: per100('viewBuilds'), menuPackets: per100('menuPackets'), bucketFlushes: per100('bucketFlushes'), devicePresentations: per100('devicePresentations'),
    islandSnapshots: per100('islandSnapshots'), materialisations: per100('materialisations'), queuedInputs: rc.totals.queuedInputs ?? 'n/a', idle: `${rc.idleTicks}/${rc.ticks}`,
    idleVisits: rc.idleTicksWithAny.islandVisits, chunks: r.allFixtureChunksLoaded ? `all ${r.loadedFixtureChunks} loaded` : r.allFixtureChunksUnloaded ? 'all unloaded' : 'mixed',
    balance: `${e(r.componentBalanceToleranceUnits)} / ${e(r.energyBalanceToleranceUnits)}`, integrity: r.stressIntegrityPassed,
    held: `${r.heldIntervals} / ${r.warmupHeldIntervals}`, workers: r.workers, artifact: r.manifest.artifactSha256.slice(0, 12),
  };
}
const runs = ['mixed100-wp2-off-r02', 'mixed100-wp2-on-r02', 'mixed100-wp3-on-r01', 'viewers-wp3-off-r01', 'viewers-wp3-on-r01'].map(id => [id, load(id)]).filter(([, r]) => r);
const fields = [['window', 'warm-up / measurement window (configured; older reports: measured)'], ['ref', 'reference island tick (mid-window)'], ['rest', 'restDetection'], ['chunks', 'fixture chunks'], ['full', 'full solves in window'], ['solvesPerSecond', 'solves dispatched / s'], ['replayed', 'replayed intervals / seconds'],
  ['rested', 'identity-advanced intervals / seconds'], ['kinds', 'final kinds'], ['eligible', 'mean eligible islands'], ['ready', 'ready-to-publication ms p50 / p95'],
  ['worker', 'worker ms p50 / p95'], ['engine', 'engine server ms per tick p50 / p95 / max'], ['tick', 'whole tick ms p50 / p95'],
  ['viewBuilds', 'view builds per 100 ticks'], ['devicePresentations', 'device presentations per 100 ticks'], ['menuPackets', 'menu packets per 100 ticks'],
  ['bucketFlushes', 'bucket flushes per 100 ticks'], ['islandSnapshots', 'island snapshots per 100 ticks'], ['materialisations', 'materialisations per 100 ticks'],
  ['queuedInputs', 'queued inputs in window'], ['idle', 'idle ticks'], ['idleVisits', 'idle ticks with an island visit'],
  ['held', 'held intervals window / warm-up'], ['balance', 'component / energy balance units'], ['integrity', 'integrity passed'], ['workers', 'workers'], ['artifact', 'artifact sha256']];
const rows = runs.map(([, r]) => row(r));
let out = '### Benchmark: mixed100 without viewers (WP2 and WP3 code) and viewers\n\n| metric | ' + runs.map(([id]) => id).join(' | ') + ' |\n|---|' + runs.map(() => '---|').join('') + '\n';
for (const [k, label] of fields) out += `| ${label} | ` + rows.map(x => x[k]).join(' | ') + ' |\n';
for (const [id, r] of runs) {
  const p = r.presentation; if (!p) continue;
  out += `\n### Presentation, ${id}\n\n`;
  out += `Window ticks (${p.windowStartTick}, ${p.windowEndTick}] = ${p.windowTicks}; ${p.openMenus} open menus; ${p.loadedDevices} loaded devices; ${p.editCount} scripted edits; passed ${p.passed}.\n\n`;
  out += '| measure | value |\n|---|---|\n';
  for (const [k, label] of [['livePacketsPer100TicksPerMenu', 'live payloads per 100 ticks per menu'], ['staticPacketsPer100TicksPerMenu', 'static payloads per 100 ticks per menu'],
    ['menuPacketsPer100TicksPerMenu', 'menu packets (all) per 100 ticks per menu'], ['viewBuildsPer100TicksPerConsumer', 'view builds per 100 ticks per consumer (menus + loaded devices)'],
    ['devicePresentationsPer100TicksPerLoadedDevice', 'device presentations per 100 ticks per loaded device'], ['bucketFlushes', 'bucket flushes in window'], ['bucketFlushesPer100Ticks', 'bucket flushes per 100 ticks'],
    ['deliveriesOffTheirBucket', 'deliveries off their bucket'], ['unanswered', 'edits unanswered'], ['repliesNotOnTheFollowingBucket', 'replies not on the first bucket after the edit'],
    ['repliesAtTheInputTick', 'replies at the input tick'], ['wrongReplies', 'wrong reply texts'], ['acceptedEditsNeverApplied', 'accepted edits never reported Applied'], ['latencyMinimumTicks', 'latency minimum, ticks']])
    out += `| ${label} | ${typeof p[k] === 'number' ? e(p[k], 3) : p[k]} |\n`;
  out += `| latency ticks count / median / p95 / max | ${p.latencyTicks.count} / ${p.latencyTicks.median} / ${p.latencyTicks.p95} / ${p.latencyTicks.max} |\n`;
  out += '\nLatency histogram (10-tick bins, lower edge: count): ' + Object.entries(p.latencyHistogramTenTickBins).map(([k, v]) => `${k}: ${v}`).join(', ') + '\n\n';
  out += 'Reply texts: ' + Object.entries(p.replyTexts).map(([k, v]) => `"${k}" x${v}`).join('; ') + '\n\n';
  out += '| network | role | device kind | deliveries in window | static in window | per 100 ticks | buckets | last status |\n|---|---|---|---|---|---|---|---|\n';
  for (const m of p.menus) out += `| ${m.network} | ${m.role} | ${m.kind} | ${m.deliveriesInWindow} | ${m.staticInWindow} | ${e(m.deliveriesPer100Ticks, 3)} | ${m.buckets.join(', ')} | ${String(m.lastStatus).slice(0, 70)} |\n`;
  const accepted = p.edits.filter(x => x.role === 'ACCEPTED');
  out += '\nAccepted edits (network, received, first reply tick and text, Applied tick):\n\n| network | received | reply tick | latency | reply | Applied at |\n|---|---|---|---|---|---|\n';
  for (const x of accepted) out += `| ${x.network} | ${x.receivedTick} | ${x.replyTick} | ${x.latencyTicks} | ${x.reply} | ${x.appliedReplyTick} |\n`;
}
process.stdout.write(out);
