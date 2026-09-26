// Classifies Newton pass failures in a solver trace: node failures.js <trace>
// For each "P FAIL", reports whether the last iterations were pinned by the nonnegativity limiter (LIMIT alpha < 1e-6),
// by domain failures (DOMAIN), or neither, and the limiting column's x.
const fs = require('fs');
const lines = fs.readFileSync(process.argv[2], 'utf8').split(/\r?\n/);
let lastLimit = null, lastDomain = false, pass = null, kinds = {}, examples = {};
for (const l of lines) {
  if (l.startsWith('    P pass=')) { pass = l; lastLimit = null; lastDomain = false; continue; }
  let m = /LIMIT col=(\d+) x=([^ ]+) d=([^ ]+) alpha=([^ ]+)/.exec(l);
  if (m) { lastLimit = {col: +m[1], x: +m[2], d: +m[3], alpha: +m[4]}; continue; }
  if (l.includes(' DOMAIN ')) { lastDomain = true; continue; }
  if (/^      N it=/.test(l) && !l.includes('DOMAIN')) { if (!/alpha=[0-9.]+E-/.test(l)) lastLimit = lastLimit && lastLimit.alpha < 1e-6 ? lastLimit : null; }
  m = /^    P FAIL pass=\d+: (.*)$/.exec(l);
  if (m) {
    const msg = m[1].replace(/[-+]?[0-9]+(\.[0-9]+)?([Ee][-+]?[0-9]+)?/g, '#');
    const kind = (lastLimit && lastLimit.alpha < 1e-6 ? 'LIMIT(x=' + (lastLimit.x < 1e-9 ? 'trace' : 'bulk') + ')' : lastDomain ? 'DOMAIN' : 'OTHER') + ' ' + msg;
    kinds[kind] = (kinds[kind] || 0) + 1;
    if (!examples[kind]) examples[kind] = (lastLimit ? JSON.stringify(lastLimit) : '') + ' | ' + (pass || '').slice(0, 300);
  }
}
for (const [k, n] of Object.entries(kinds).sort((a, b) => b[1] - a[1])) console.log(n + '  ' + k + '\n     e.g. ' + examples[k]);
