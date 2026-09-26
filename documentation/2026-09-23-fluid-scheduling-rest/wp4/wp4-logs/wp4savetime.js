// Save-time table from paced benchmark reports (checkpoint format 3):
// node wp4savetime.js <report.json> [<report.json> ...]
const path = require('path');
const rows = [];
console.log('| run | window (warm-up / measured) | save | total ms | capture ms | encode ms | payloads encoded / copied | topology encoded | payload MB | topology MB | total MB | gzip MB | final kinds |');
console.log('|---|---|---|---|---|---|---|---|---|---|---|---|---|');
for (const file of process.argv.slice(2)) {
  const r = require(path.resolve(file));
  const window = `${r.warmupSeconds} s / ${r.measurementSecondsConfigured} s`;
  const kinds = JSON.stringify(r.certificates.finalIslandKinds);
  for (const k of ['cold', 'warm', 'nextCadence']) {
    const s = r.saveTime && r.saveTime[k]; if (!s) continue;
    const mb = b => (b / 1048576).toFixed(2);
    console.log(`| ${r.manifest.runId} | ${window} | ${k} (tick ${s.onlineTick}) | ${s.totalMilliseconds.toFixed(1)} | ${s.captureMilliseconds.toFixed(1)} | ${s.encodeMilliseconds.toFixed(1)} | ${s.payloadsEncoded} / ${s.payloadsReused} | ${s.topologyEncoded} | ${mb(s.payloadBytes)} | ${mb(s.topologyBytes)} | ${mb(s.bytes)} | ${mb(s.compressedBytes)} | ${kinds} |`);
  }
}
