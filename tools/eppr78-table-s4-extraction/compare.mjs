// Compare the Table S4 matrix (s4-matrix.tsv) with a group_interactions record (eppr78_2022.json).
// Usage: node compare.mjs <s4-matrix.tsv> <eppr78_2022.json> [report.txt]
import fs from 'node:fs';
const [tsv, json, report] = process.argv.slice(2);
const j = JSON.parse(fs.readFileSync(json, 'utf8'));
const num = {};
for (const g of j.groups) num[g.name] = g.number;
const bundled = new Map();
for (const p of j.pairs) {
  const a = num[p.first], b = num[p.second];
  if (a === undefined || b === undefined) throw new Error('unmapped pair ' + JSON.stringify(p));
  bundled.set(`${Math.min(a, b)}-${Math.max(a, b)}`, [p.a_mpa, p.b_mpa]);
}
const s4 = new Map();
const names = {};
for (const line of fs.readFileSync(tsv, 'utf8').split('\n')) {
  if (!line || line.startsWith('#') || line.startsWith('k\t')) continue;
  const [k, l, nk, nl, A, B] = line.split('\t');
  names[k] = nk; names[l] = nl;
  if (k === l) continue;
  s4.set(`${Math.min(k, l)}-${Math.max(k, l)}`, [A, B]);
}
let same = 0;
const diffs = [];
const label = key => { const [a, b] = key.split('-'); return `${key} (${names[a]}/${names[b]})`; };
for (const [key, [A, B]] of s4) {
  const bp = bundled.get(key);
  if (A === 'NA') { if (bp) diffs.push(`S4 prints NA, record has A=${bp[0]} B=${bp[1]}: ${label(key)}`); continue; }
  if (!bp) { diffs.push(`missing in record (S4 A=${A} B=${B}): ${label(key)}`); continue; }
  if (Number(A) === bp[0] && Number(B) === bp[1]) same++;
  else diffs.push(`differs: ${label(key)} S4 A=${A} B=${B}; record A=${bp[0]} B=${bp[1]}`);
}
for (const key of bundled.keys()) if (!s4.has(key)) diffs.push(`in record but not in S4: ${label(key)} A=${bundled.get(key)[0]} B=${bundled.get(key)[1]}`);
const pilot = [5, 6, 12, 13];
const pilotLines = [];
for (const a of pilot) for (const b of pilot) if (a < b) {
  const key = `${a}-${b}`; const s = s4.get(key), r = bundled.get(key);
  pilotLines.push(`${label(key)}: S4 A=${s ? s[0] : '?'} B=${s ? s[1] : '?'}; record A=${r ? r[0] : '?'} B=${r ? r[1] : '?'} ${s && r && Number(s[0]) === r[0] && Number(s[1]) === r[1] ? 'IDENTICAL' : 'DIFFERENT'}`);
}
const lines = [
  `Record ${j.id} revision ${j.revision}: ${bundled.size} pairs.`,
  `Table S4: ${[...s4.values()].filter(v => v[0] !== 'NA').length} numeric off-diagonal pairs, ${[...s4.values()].filter(v => v[0] === 'NA').length} NA pairs.`,
  `Identical pairs: ${same}. Discrepancies: ${diffs.length}.`,
  '', 'Pilot pairs (groups 5 CH4, 6 C2H6, 12 CO2, 13 N2):', ...pilotLines, '', 'Discrepancies:', ...diffs];
console.log(lines.join('\n'));
if (report) fs.writeFileSync(report, lines.join('\n') + '\n');
