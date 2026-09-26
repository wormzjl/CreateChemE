// P0 / D5 study harness: PR78 (as in the mod: Soave-form kappa with the PR78 split at omega 0.491) against
// Maxwell-Bonnell (API TDB 5A1.18, with the Watson-K correction) for the bundled crude cuts, on the CURRENT
// regrouped slate (crude-regrouped-r1). Re-creates the comparison of documentation/2026-08-31-v3-vdu/
// V3_VDU_LITERATURE_AND_THERMO_DATA.md section 2.4 (whose script build/pkgcmp/vdu-lit/psat-check.mjs no longer
// exists) and adds the two-point (NBP at 101.325 kPa + MB point at 10 mmHg) anchoring of omega and Tc, holding Pc.
// Run from the worktree root: node research/2026-09-24-coolprop-low-temperature/vdu-k-policy/psat-mb-check.mjs
import { readFileSync } from 'node:fs';

const R = 8.31446261815324;
const dir = 'src/main/resources/data/createcheme/materials/properties/';
const WATER_60F = 999.016; // kg/m3 at 60 F, for SG

function kappa(w) {
  return w <= 0.491 ? 0.37464 + 1.54226 * w - 0.26992 * w * w
    : 0.379642 + 1.48503 * w - 0.164423 * w * w + 0.016666 * w * w * w;
}
function cubicRoots(c2, c1, c0) { // z^3 + c2 z^2 + c1 z + c0 = 0, real roots
  const q = (3 * c1 - c2 * c2) / 9, r = (9 * c2 * c1 - 27 * c0 - 2 * c2 ** 3) / 54, d = q ** 3 + r * r;
  if (d > 0) { const s = Math.cbrt(r + Math.sqrt(d)), t = Math.cbrt(r - Math.sqrt(d)); return [s + t - c2 / 3]; }
  const th = Math.acos(r / Math.sqrt(-(q ** 3)));
  return [0, 1, 2].map((k) => 2 * Math.sqrt(-q) * Math.cos((th + 2 * Math.PI * k) / 3) - c2 / 3).sort((a, b) => a - b);
}
function lnPhi(z, A, B) {
  const s2 = Math.SQRT2;
  return z - 1 - Math.log(z - B) - A / (2 * s2 * B) * Math.log((z + (1 + s2) * B) / (z + (1 - s2) * B));
}
// Pure-component PR78 at T, P: real roots above B and ln(phi) of the smallest (liquid) and largest (vapour).
function phases(T, P, c) {
  const a = 0.45723553 * R * R * c.tc * c.tc / c.pc * (1 + kappa(c.w) * (1 - Math.sqrt(T / c.tc))) ** 2;
  const b = 0.07779607 * R * c.tc / c.pc;
  const A = a * P / (R * T) ** 2, B = b * P / (R * T);
  const z = cubicRoots(-(1 - B), A - 3 * B * B - 2 * B, -(A * B - B * B - B ** 3)).filter((x) => x > B);
  return { z, B, lnL: lnPhi(z[0], A, B), lnV: lnPhi(z[z.length - 1], A, B) };
}
// Saturation pressure at T (T < Tc): successive substitution P <- P phiL/phiV, bracketing out single-root states.
function prPsat(T, c) {
  let P = c.pc * Math.pow(10, 7 / 3 * (1 + c.w) * (1 - c.tc / T));
  for (let it = 0; it < 500; it++) {
    const ph = phases(T, P, c);
    if (ph.z.length < 2) { if (ph.z[0] < 0.3) P /= 3; else P *= 3; continue; } // one root: liquid-like -> P too high
    const d = ph.lnL - ph.lnV;
    P *= Math.exp(d);
    if (Math.abs(d) < 1e-12) break;
  }
  return P;
}
// Saturation temperature at P: bisection on ln Psat(T) - ln P (monotonic in T).
function prTsat(P, c) {
  let lo = 0.2 * c.tc, hi = 0.9999 * c.tc;
  if (prPsat(hi, c) < P) return NaN;
  for (let i = 0; i < 100; i++) { const m = 0.5 * (lo + hi); if (prPsat(m, c) < P) lo = m; else hi = m; }
  return 0.5 * (lo + hi);
}
// Maxwell-Bonnell (API TDB 5A1.18): temperature at pressure P [Pa] for NBP [K] and Watson K.
function mbTsat(P, nbp, kw) {
  const mmHg = P / 133.322368;
  const tbR = nbp * 1.8, tbF = tbR - 459.67;
  const f = tbF < 200 ? 0 : tbF > 400 ? 1 : (tbF - 200) / 200;
  const tbp = tbR - 2.5 * f * (kw - 12) * Math.log10(mmHg / 760);
  const L = Math.log10(mmHg);
  const inv = (p, q, r, s) => (s * L - q) / (p - r * L); // log10 P = (pX + q)/(rX + s)  ->  X
  let X = inv(2663.129, -5.994296, 95.76, -0.972546);
  if (X > 0.0022) X = inv(3000.538, -6.761560, 43, -0.987672);
  else if (X < 0.0013) X = inv(2770.085, -6.412631, 36, -0.989679);
  const tR = tbp / (X * (748.1 - 0.2145 * tbp) + 0.0002867 * tbp);
  return tR / 1.8;
}
// Two-point anchoring: omega and Tc so that PR78 Tsat(101325 Pa) = NBP and Tsat(1333.22 Pa) = MB(10 mmHg), Pc held.
function anchor(c, nbp, kw) {
  const t10 = mbTsat(1333.22368, nbp, kw);
  let x = { tc: c.tc, pc: c.pc, w: c.w };
  for (let it = 0; it < 40; it++) {
    const r1 = prTsat(101325, x) - nbp, r2 = prTsat(1333.22368, x) - t10;
    if (Math.abs(r1) < 1e-7 && Math.abs(r2) < 1e-7) break;
    const hT = 1e-3 * x.tc, hW = 1e-5;
    const a11 = (prTsat(101325, { ...x, tc: x.tc + hT }) - nbp - r1) / hT, a12 = (prTsat(101325, { ...x, w: x.w + hW }) - nbp - r1) / hW;
    const a21 = (prTsat(1333.22368, { ...x, tc: x.tc + hT }) - t10 - r2) / hT, a22 = (prTsat(1333.22368, { ...x, w: x.w + hW }) - t10 - r2) / hW;
    const det = a11 * a22 - a12 * a21;
    x = { ...x, tc: x.tc - (r1 * a22 - r2 * a12) / det, w: x.w - (a11 * r2 - a21 * r1) / det };
  }
  return x;
}

const rows = [];
rows.push('cut | NBP K | SG(60F) | Kw | Tc K | Pc kPa | omega | PR Tsat(101.325 kPa)-NBP K | PR-MB at 1 / 5 / 13 kPa K | P_PR/P_MB at 13 kPa | anchored Tc K (dTc) | anchored omega (domega) | anchored: PR-MB at 1 / 5 / 13 kPa K | anchored-minus-current PR Tsat at 101.325 / 250 / 1000 kPa K | PR-MB at 250 kPa current / anchored K');
for (let i = 1; i <= 12; i++) {
  const id = 'crude_pc' + String(i).padStart(2, '0');
  const j = JSON.parse(readFileSync(dir + id + '.json', 'utf8'));
  const c = { tc: j.models.pr78.critical_temperature_kelvin, pc: j.models.pr78.critical_pressure_pascal, w: j.models.pr78.acentric_factor };
  const nbp = j.normal_boiling_point_kelvin, sg = j.standard_liquid_density_kg_per_m3 / WATER_60F;
  const kw = Math.cbrt(nbp * 1.8) / sg;
  const dNbp = prTsat(101325, c) - nbp;
  const d = [1000, 5000, 13000].map((p) => prTsat(p, c) - mbTsat(p, nbp, kw));
  const ratio = prPsat(mbTsat(13000, nbp, kw), c) / 13000; // PR vapour pressure over MB's at MB's 13 kPa temperature
  const x = anchor(c, nbp, kw);
  const da = [1000, 5000, 13000].map((p) => prTsat(p, x) - mbTsat(p, nbp, kw));
  const shift = [101325, 250000, 1000000].map((p) => prTsat(p, x) - prTsat(p, c));
  rows.push([id, nbp.toFixed(1), sg.toFixed(4), kw.toFixed(2), c.tc.toFixed(1), (c.pc / 1000).toFixed(1), c.w.toFixed(3), dNbp.toFixed(2),
    d.map((v) => v.toFixed(1)).join(' / '), ratio.toFixed(2),
    `${x.tc.toFixed(1)} (${(x.tc - c.tc) >= 0 ? '+' : ''}${(x.tc - c.tc).toFixed(1)})`, `${x.w.toFixed(3)} (${(x.w - c.w) >= 0 ? '+' : ''}${(x.w - c.w).toFixed(3)})`,
    da.map((v) => v.toFixed(2)).join(' / '), shift.map((v) => (Number.isNaN(v) ? 'n/a' : v.toFixed(2))).join(' / '),
    [c, x].map((p) => (prTsat(250000, p) - mbTsat(250000, nbp, kw)).toFixed(2)).join(' / ')].join(' | '));
}
console.log(rows.join('\n'));
