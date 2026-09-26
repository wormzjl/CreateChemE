// Extracts selected species records from NASA CEA data/thermo.inp (NASA TP-2002-211556 appendix A format)
// and writes them verbatim plus a parsed summary. Run from this folder: node extract-species.mjs > cea-species-extract.txt
// S(298.15 K, 1 bar) and Cp(298.15 K) are evaluated from the 9-coefficient polynomial of the interval holding 298.15 K;
// they are derived values (the file stores coefficients, not S298). Hf(298.15 K) is the stored record-2 value.
import { readFileSync } from 'node:fs';

const R = 8.31446261815324; // J/(mol K), CODATA 2018; CEA TP-2002-211556 uses 8.314472 (differences < 2e-6 relative)
const wanted = [
  'N2', 'O2', 'Ar', 'CO2', 'NH3', 'H2', 'H2O', 'CH4', 'C2H6', 'C2H4', 'C3H6,propylene', 'C3H8',
  'C4H10,n-butane', 'C4H10,isobutane', 'C5H12,n-pentane', 'C5H12,i-pentane', 'H2S', 'CO',
  // condensed phases present in the file for the same species (reference only)
  'H2O(cr)', 'H2O(L)', 'CH4(L)', 'C2H4(L)', 'C2H6(L)', 'C3H6(L),propyle', 'C3H8(L)', 'C4H10(L),n-buta',
  'C4H10(L),isobut', 'C5H12(L),n-pent', 'H2(L)', 'NH3(L)', 'N2(L)', 'O2(L)',
];
const lines = readFileSync('thermo.inp', 'utf8').split(/\r?\n/);
const num = (s) => Number(s.trim().replace(/D/g, 'E'));
const fields = (s, width, count) => Array.from({ length: count }, (_, i) => s.substring(i * width, (i + 1) * width));

// Species records start after the 'thermo' header line; the name occupies columns 1-18 (A18 in practice, A24 max).
const records = new Map();
for (let i = 0; i < lines.length; i++) {
  const name = lines[i].substring(0, 18).trim();
  if (!wanted.includes(name) || records.has(name)) continue;
  const header = lines[i + 1];
  if (!header || !/^ ?\d/.test(header)) continue;
  const intervals = parseInt(header.substring(0, 2), 10);
  const phase = parseInt(header.substring(50, 52), 10);
  const mw = num(header.substring(52, 65));
  const hf = num(header.substring(65, 80));
  const refCode = header.substring(3, 9).trim();
  const formula = header.substring(10, 50).trim().replace(/\s+/g, ' ');
  const body = [lines[i], header];
  const ranges = [];
  let j = i + 2;
  for (let k = 0; k < intervals; k++) {
    const r = lines[j], c1 = lines[j + 1], c2 = lines[j + 2];
    body.push(r, c1, c2);
    const tlo = num(r.substring(0, 11)), thi = num(r.substring(11, 22));
    const dh = num(r.substring(65, 80));
    const a = [...fields(c1, 16, 5).map(num), num(c2.substring(0, 16)), num(c2.substring(16, 32))];
    const b = [num(c2.substring(48, 64)), num(c2.substring(64, 80))];
    ranges.push({ tlo, thi, dh, a, b });
    j += 3;
  }
  let assignedT = null;
  if (intervals === 0) { body.push(lines[j]); assignedT = num(lines[j].substring(0, 11)); } // zero-interval condensed record: enthalpy assigned at one temperature
  records.set(name, { name, comment: lines[i].substring(18).trim(), intervals, phase, mw, hf, refCode, formula, ranges, body, assignedT });
}

const cpR = (a, T) => a[0] / T ** 2 + a[1] / T + a[2] + a[3] * T + a[4] * T ** 2 + a[5] * T ** 3 + a[6] * T ** 4;
const hRT = (a, b, T) => -a[0] / T ** 2 + a[1] * Math.log(T) / T + a[2] + a[3] * T / 2 + a[4] * T ** 2 / 3 + a[5] * T ** 3 / 4 + a[6] * T ** 4 / 5 + b[0] / T;
const sR = (a, b, T) => -a[0] / (2 * T ** 2) - a[1] / T + a[2] * Math.log(T) + a[3] * T + a[4] * T ** 2 / 2 + a[5] * T ** 3 / 3 + a[6] * T ** 4 / 4 + b[1];

const out = [];
out.push('NASA CEA thermo.inp extract (github.com/nasa/cea, data/thermo.inp at commit 3f4441d28a02fccbb140e1a028d9902390981389, Apache-2.0)');
out.push('Format: NASA TP-2002-211556 appendix A. Cp/R = a1 T^-2 + a2 T^-1 + a3 + a4 T + a5 T^2 + a6 T^3 + a7 T^4; H/RT adds b1/T; S/R adds b2. Standard state 1 bar.');
out.push('Columns: name | phase (0 gas; for a zero-interval condensed record the stored value is the enthalpy H(T) at the assigned temperature, not dfH298) | ref code | formula | MW g/mol | dfH(298.15) J/mol (stored) | intervals [Tlo..Thi K] | H298-H0 J/mol | derived Cp(298.15) J/(mol K) | derived S(298.15, 1 bar) J/(mol K) | derived H(298.15) from polynomial J/mol');
out.push('');
for (const name of wanted) {
  const r = records.get(name);
  if (!r) { out.push(`${name} | NOT FOUND`); continue; }
  const iv = r.ranges.map((x) => `${x.tlo}..${x.thi}`).join(', ');
  let at = r.ranges.find((x) => x.tlo <= 298.15 && x.thi >= 298.15);
  let flag = '';
  if (!at && r.ranges.length && r.ranges[0].tlo > 298.15) { at = r.ranges[0]; flag = ' (first interval starts at ' + at.tlo + ' K: extrapolated 1.85 K)'; }
  let cp = '', s = '', h = '';
  if (at) {
    cp = (R * cpR(at.a, 298.15)).toFixed(3);
    s = (R * sR(at.a, at.b, 298.15)).toFixed(3);
    h = (R * 298.15 * hRT(at.a, at.b, 298.15)).toFixed(1);
  }
  const ivText = r.intervals === 0 ? `0: enthalpy assigned at ${r.assignedT} K` : `${r.intervals}: ${iv}`;
  out.push(`${r.name} | ${r.phase} | ${r.refCode} | ${r.formula} | ${r.mw} | ${r.hf} | ${ivText} | ${r.ranges[0] ? r.ranges[0].dh : ''} | ${cp} | ${s} | ${h}${flag}`);
}
out.push('');
out.push('Verbatim records (for loaders and review):');
for (const name of wanted) {
  const r = records.get(name);
  if (!r) continue;
  out.push('');
  out.push(`# ${r.name}: ${r.comment}`);
  for (const l of r.body) out.push(l);
}
console.log(out.join('\n'));
