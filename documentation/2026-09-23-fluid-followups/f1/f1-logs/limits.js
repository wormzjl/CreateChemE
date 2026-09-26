// Maps every small-alpha LIMIT event in a trace to node/local column using the pass line's offsets: node limits.js <trace>
const fs = require('fs');
const lines = fs.readFileSync(process.argv[2], 'utf8').split(/\r?\n/);
let offsets = null, phases = null, edgeOffset = 0, counts = {};
for (const l of lines) {
  let m = /offsets=\[([^\]]*)\] edgeOffset=(\d+)/.exec(l);
  if (m && l.includes('P pass=')) { offsets = m[1].split(', ').map(Number); edgeOffset = +m[2]; phases = /phases=\[([^\]]*)\]/.exec(l)[1].split(', ').map(Number); continue; }
  m = /LIMIT col=(\d+) x=([^ ]+) d=([^ ]+) alpha=([^ ]+)/.exec(l);
  if (!m || +m[4] > 1e-6) continue;
  const col = +m[1]; let node = -1;
  for (let i = 0; i < offsets.length; i++) if (offsets[i] <= col && (i + 1 === offsets.length ? col < edgeOffset : col < offsets[i + 1] || offsets[i + 1] === offsets[i] && false)) node = i;
  // offsets may repeat for fixed nodes; take the last node whose offset <= col and whose next distinct offset > col
  for (let i = 0; i < offsets.length; i++) { const next = offsets.slice(i + 1).find(o => o > offsets[i]) ?? edgeOffset; if (offsets[i] <= col && col < next && next > offsets[i]) node = i; }
  const key = `node ${node} (phase code ${phases[node]}) local ${col - offsets[node]} x~1e${Math.round(Math.log10(+m[2]))}`;
  counts[key] = (counts[key] || 0) + 1;
}
for (const [k, n] of Object.entries(counts).sort((a, b) => b[1] - a[1]).slice(0, 25)) console.log(n + '  ' + k);
