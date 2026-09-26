// Least-squares degree-5 fit, in powers of (T - 298.15 K), of CO2's ideal-gas Cp from CoolProp's Span and Wagner 1996
// alpha0 over 216.592..900 K, for the shifted_polynomial_5 the property loader still demands of
// properties/pilot_carbon_dioxide.json (P2 test fixture; P3 retires it in the pilot package). Run: node fit-pilot-co2-cp.mjs
import fs from 'node:fs';
import path from 'node:path';

const HERE = path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'));
const eos = JSON.parse(fs.readFileSync(path.resolve(HERE, '../../src/test/resources/science/thermo/coolprop/CarbonDioxide.json'), 'utf8')).EOS[0];
const Tr = eos.STATES.reducing.T, R = eos.gas_constant;
let a = 0; const pe = [];
for (const t of eos.alpha0) {
  if (t.type === 'IdealGasHelmholtzLogTau') a += t.a;
  if (t.type === 'IdealGasHelmholtzPlanckEinstein') t.n.forEach((n, i) => pe.push([n, t.t[i]]));
}
const cp = T => { const tau = Tr / T; let c = 1 + a; for (const [n, th] of pe) { const x = th * tau, e = Math.exp(-x); c += n * x * x * e / (1 - e) ** 2; } return R * c; };

const lo = 216.592, hi = 900, scale = 100, degree = 5, rows = [];
for (let T = lo; T <= hi + 1e-9; T += (hi - lo) / 400) rows.push([(T - 298.15) / scale, cp(T), T]);
const n = degree + 1, A = Array.from({ length: n }, () => new Array(n + 1).fill(0));
for (const [x, y] of rows) for (let i = 0; i < n; i++) { for (let j = 0; j < n; j++) A[i][j] += x ** (i + j); A[i][n] += y * x ** i; }
for (let i = 0; i < n; i++) { // Gauss-Jordan with partial pivoting
  let p = i; for (let r = i + 1; r < n; r++) if (Math.abs(A[r][i]) > Math.abs(A[p][i])) p = r;
  [A[i], A[p]] = [A[p], A[i]];
  for (let r = 0; r < n; r++) if (r !== i) { const f = A[r][i] / A[i][i]; for (let c = i; c <= n; c++) A[r][c] -= f * A[i][c]; }
}
const scaled = A.map((row, i) => row[n] / row[i]);
const coefficients = scaled.map((c, k) => c / scale ** k);
let worst = 0, at = 0;
for (const [, y, T] of rows) { const d = T - 298.15; let v = 0; for (let k = degree; k >= 0; k--) v = v * d + coefficients[k]; const e = Math.abs(v / y - 1); if (e > worst) { worst = e; at = T; } }
console.log(JSON.stringify(coefficients));
console.log(`worst relative deviation ${(100 * worst).toFixed(4)} % at ${at.toFixed(2)} K over ${lo}..${hi} K`);
