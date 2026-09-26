// F2 changes to the copied WP5/F1 datapack generator (applied once with ../f2-logs/scripts/apply-edits.js semantics).
const fs = require('fs'), path = require('path');
const file = path.join(__dirname, 'gen-datapack.js');
let s = fs.readFileSync(file, 'utf8').replace(/\r\n/g, '\n');
function rep(a, b) { const n = s.split(a).length - 1; if (n !== 1) throw new Error(n + ' for ' + a.slice(0, 80)); s = s.replace(a, () => b); }
rep(`function grid1000(kind) {
  const cmds = [];
  for (let n = 0; n < 1000; n++)`, `function grid1000(kind, count = 1000) {
  const cmds = [];
  for (let n = 0; n < count; n++)`);
rep(`  rest1000: grid1000('REST'),`, `  rest1000: grid1000('REST'),
  // F2: the first 400 lines of rest1000's grid (2,000 devices).
  rest400: grid1000('REST', 400),`);
rep(`const summary = {};`, `// F2 marginal functions, placed in every grid's free space inside the force-loaded chunks: a lone tank above the grid
// (a new island), a pipe on top of the first line's first pipe (a dead-end branch of that line's island), the first
// line's middle pipe turned north and back (a facing edit), twenty lone tanks, and a no-op for the RCON round trip.
const Y = 64;
const marginal = {
  noop: ['time query gametime'],
  plus_tank: [\`setblock 16 70 16 \${ID.R}[facing=east]\`], minus_tank: ['setblock 16 70 16 minecraft:air'],
  plus_pipe: [\`setblock 17 65 16 \${ID.P}[facing=east]\`], minus_pipe: ['setblock 17 65 16 minecraft:air'],
  edit_pipe: [\`setblock 18 64 16 \${ID.P}[facing=north]\`], edit_back: [\`setblock 18 64 16 \${ID.P}[facing=east]\`],
  plus20: Array.from({length: 20}, (_, k) => \`setblock \${16 + 2 * k} 70 20 \${ID.R}[facing=east]\`), minus20: Array.from({length: 20}, (_, k) => \`setblock \${16 + 2 * k} 70 20 minecraft:air\`),
};
for (const [name, cmds] of Object.entries(marginal)) fs.writeFileSync(path.join(fn, name + '.mcfunction'), cmds.join('\\n') + '\\n');
const summary = {};`);
fs.writeFileSync(file, s);
console.log('gen-datapack.js patched');
