// Attempt histogram over simulated time from a solver trace file: node profile.js <trace>
const fs = require('fs');
const lines = fs.readFileSync(process.argv[2], 'utf8').split(/\r?\n/);
const edges = [0, 1e-4, 1e-3, 0.01, 0.05, 0.1, 0.25, 0.5, 1, 2, 3, 4, 5.01];
const ok = new Array(edges.length).fill(0), rej = new Array(edges.length).fill(0), reasons = {};
for (const l of lines) {
  const m = /^  A (OK2?|REJ) t=([^ ]+) step=([^ ]+)(.*)$/.exec(l);
  if (!m) continue;
  const t = parseFloat(m[2]);
  let b = 0;
  while (b + 1 < edges.length && t >= edges[b + 1]) b++;
  if (m[1] === 'REJ') {
    rej[b]++;
    const r = m[4].replace(/[-+]?[0-9]+(\.[0-9]+)?([Ee][-+]?[0-9]+)?/g, '#').trim();
    reasons[r] = (reasons[r] || 0) + 1;
  } else ok[b]++;
}
for (let b = 0; b < edges.length - 1; b++) if (ok[b] + rej[b]) console.log(`[${edges[b]}, ${edges[b + 1]}) ok=${ok[b]} rej=${rej[b]}`);
console.log(Object.entries(reasons).sort((a, b) => b[1] - a[1]).map(([r, n]) => n + '  ' + r).join('\n'));
