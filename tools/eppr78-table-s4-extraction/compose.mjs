// Compose the Table S4 matrix (Akl, Bkl in MPa) from the per-cell WMF text runs written by s4.mjs.
// Usage: node compose.mjs <dir> -> writes <dir>/tables/s4-matrix.tsv
import fs from 'node:fs';
import path from 'node:path';
const dir = process.argv[2];
const cells = JSON.parse(fs.readFileSync(path.join(dir, 'tables', 's4-cells.json'), 'utf8'));
const gnum = s => { const m = /group[\s ]*(\d+)/.exec(s); return m ? Number(m[1]) : null; };
const gname = s => s.replace(/\s*\(group[\s ]*\d+\)\s*$/, '').trim();
const names = {};
const rows = [];
const odd = [];
for (const cell of cells) {
  const k = gnum(cell.row), l = gnum(cell.col);
  if (k === null || l === null) { odd.push(['label', cell.table, cell.row, cell.col]); continue; }
  names[k] = names[k] || gname(cell.row);
  names[l] = names[l] || gname(cell.col);
  if (cell.text === '-' || cell.runs.length === 0 && cell.text === '') continue; // upper triangle
  if (cell.text === 'NA') { rows.push([k, l, 'NA', 'NA']); continue; }
  const hmax = Math.max(...cell.runs.map(r => -r.height));
  const main = cell.runs.filter(r => -r.height === hmax);
  if (main.length === 1 && main[0].text === '0') { rows.push([k, l, '0', '0']); continue; }
  const lines = {};
  for (const r of main) (lines[r.y] = lines[r.y] || []).push(r);
  const vals = {};
  let ok = Object.keys(lines).length === 2;
  for (const y of Object.keys(lines)) {
    const rs = lines[y].sort((a, b) => a.x - b.x);
    const label = rs.find(r => /^[AB]\d/.test(r.text));
    if (!label) { ok = false; break; }
    const num = label.text.slice(1);
    const sym = rs.filter(r => r.font === 'Symbol');
    const extra = rs.filter(r => r !== label && r.font !== 'Symbol');
    if (extra.length || sym.length !== 1 || !/^=-?$/.test(sym[0].text) || !/^\d+(\.\d+)?$/.test(num)) { ok = false; break; }
    vals[label.text[0]] = (sym[0].text === '=-' ? '-' : '') + num;
  }
  if (ok && vals.A !== undefined && vals.B !== undefined) rows.push([k, l, vals.A, vals.B]);
  else odd.push(['cell', cell.table, cell.row, cell.col, cell.runs.map(r => `${r.y}:${r.font}:${r.height}:${r.text}`).join(' | ')]);
}
rows.sort((a, b) => a[0] - b[0] || a[1] - b[1]);
const out = ['# Jaubert, Qian, Lasala, Privat 2022, Fluid Phase Equilibria 560, 113456, Supporting Information Table S4 (file 1-s2.0-S0378381222000814-mmc1.docx).',
  '# Group-binary interaction parameters Akl = Alk and Bkl = Blk in MPa (E-PPR78 convention), lower triangle as printed; NA = not available in the table; the diagonal is 0.',
  '# Extracted 2026-09-25 from the MathType previews (WMF text records) of the docx by tools/eppr78-table-s4-extraction; every one of the 820 lower-triangle cells parsed.',
  'k\tl\tgroup_k\tgroup_l\tAkl_MPa\tBkl_MPa',
  ...rows.map(r => `${r[0]}\t${r[1]}\t${names[r[0]]}\t${names[r[1]]}\t${r[2]}\t${r[3]}`)];
fs.writeFileSync(path.join(dir, 'tables', 's4-matrix.tsv'), out.join('\n') + '\n');
const numeric = rows.filter(r => r[2] !== 'NA');
console.log(`rows ${rows.length}, numeric ${numeric.length} (diagonal ${numeric.filter(r => r[0] === r[1]).length}), NA ${rows.length - numeric.length}, odd ${odd.length}`);
for (const o of odd) console.log(JSON.stringify(o));
console.log('groups:', Object.keys(names).length, JSON.stringify(names));
