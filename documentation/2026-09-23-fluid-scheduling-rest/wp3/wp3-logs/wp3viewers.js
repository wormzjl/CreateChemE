// Viewers against no viewers, island by island: startup-held islands, the islands the scripted edits replaced,
// and whether every other island's solved publications are identical. node wp3viewers.js <plain/report.json> <viewers/report.json>
const path = require('path');
const [aPath, bPath] = process.argv.slice(2);
const A = require(path.resolve(aPath)), B = require(path.resolve(bPath));
const held = r => { const s = new Set(); for (const x of r.warmupSamples.concat(r.samples || [])) if (!x.timing.accepted) s.add(String(x.island)); return s; };
const solved = list => (list || []).filter(s => s.split(':')[1] === 'S');
const fa = A.certificates.publicationFingerprints, fb = B.certificates.publicationFingerprints;
const ha = held(A), hb = held(B);
const lastTick = list => list.length ? +list[list.length - 1].split(':')[0] : -1;
let identical = 0, replaced = [], parted = [], heldParted = [];
for (const id of Object.keys(fa)) {
  const x = solved(fa[id]), y = solved(fb[id] || []);
  const replacedHere = lastTick(y) < lastTick(x) - 200;
  const n = Math.min(x.length, y.length); let first = -1;
  for (let i = 0; i < n; i++) if (x[i] !== y[i]) { first = i; break; }
  if (replacedHere) { replaced.push({ id, commonBeforeReplacement: first < 0 ? n : first, lastSolvedPlain: lastTick(x), lastSolvedViewers: lastTick(y), firstDifferenceAt: first < 0 ? null : x[first].split(':')[0] }); continue; }
  if (first < 0 && x.length === y.length) identical++;
  else (ha.has(id) || hb.has(id) ? heldParted : parted).push({ id, at: first < 0 ? 'length' : x[first].split(':')[0], lengths: `${x.length}/${y.length}` });
}
const newIds = Object.keys(fb).filter(id => !fa[id]);
console.log(`${A.manifest.runId} vs ${B.manifest.runId}: ${Object.keys(fa).length} fixture islands`);
console.log(`  identical solved publications, same count: ${identical}`);
console.log(`  startup-held (either run) and parted: ${heldParted.length} ${JSON.stringify(heldParted)}`);
console.log(`  parted without a startup hold: ${parted.length} ${JSON.stringify(parted)}`);
console.log(`  replaced by scripted edits: ${replaced.length} ${JSON.stringify(replaced)}`);
console.log(`  replacement islands only in the viewers run: ${newIds.length}`);
console.log(`  held at startup: ${[...ha].sort().join(',')} / ${[...hb].sort().join(',')}`);
