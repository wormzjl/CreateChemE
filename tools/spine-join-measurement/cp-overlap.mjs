// Measures the ideal-gas Cp of CoolProp's alpha0 against the NASA CEA 9-coefficient polynomial over their overlap,
// to choose the join temperature of each P2 spine record (batch 2026-09-24-coolprop-low-temperature).
import fs from 'node:fs';
const W = new URL('../../', import.meta.url).pathname.replace(/^\/([A-Z]:)/, '$1');
const cea = {
  Nitrogen: { R: 8.314472, lo: [2.210371497e+04,-3.818461820e+02,6.082738360e+00,-8.530914410e-03,1.384646189e-05,-9.625793620e-09,2.519705809e-12], tmin: 200 },
  Methane: { R: 8.314472, lo: [-1.766850998e+05,2.786181020e+03,-1.202577850e+01,3.917619290e-02,-3.619054430e-05,2.026853043e-08,-4.976705490e-12], tmin: 200 },
  Ethane: { R: 8.314472, lo: [-1.862044161e+05,3.406191860e+03,-1.951705092e+01,7.565835590e-02,-8.204173220e-05,5.061135800e-08,-1.319281992e-11], tmin: 300 },
  CarbonDioxide: { R: 8.314472, lo: [4.943650540e+04,-6.264116010e+02,5.301725240e+00,2.503813816e-03,-2.127308728e-07,-7.689988780e-10,2.849677801e-13], tmin: 200 },
};
const publishedMax = { Nitrogen: 1000, Methane: 625, Ethane: 675, CarbonDioxide: 1100 };
const cpCea = (a, T) => a[0]/T**2 + a[1]/T + a[2] + a[3]*T + a[4]*T**2 + a[5]*T**3 + a[6]*T**4;
function coolprop(name) {
  const e = JSON.parse(fs.readFileSync(`${W}src/test/resources/science/thermo/coolprop/${name}.json`, 'utf8')).EOS[0];
  const Tr = e.STATES.reducing.T, R = e.gas_constant;
  let a = 0; const pw = [], pe = [];
  for (const t of e.alpha0) {
    if (t.type === 'IdealGasHelmholtzLogTau') a += t.a;
    else if (t.type === 'IdealGasHelmholtzPower') t.n.forEach((n, i) => pw.push([n, t.t[i]]));
    else if (t.type === 'IdealGasHelmholtzPlanckEinstein') t.n.forEach((n, i) => pe.push([n, t.t[i]]));
    else if (t.type === 'IdealGasHelmholtzPlanckEinsteinFunctionT') t.n.forEach((n, i) => pe.push([n, t.v[i] / t.Tcrit]));
  }
  return { R, Ttp: e.Ttriple, cp: T => { const tau = Tr / T; let c = 1 + a;
    for (const [n, t] of pw) c -= n * t * (t - 1) * tau ** t;
    for (const [n, th] of pe) { const x = th * tau, ex = Math.exp(-x); c += n * x * x * ex / (1 - ex) ** 2; }
    return R * c; } };
}
for (const name of Object.keys(cea)) {
  const cp = coolprop(name), c = cea[name];
  const lo = Math.max(c.tmin, cp.Ttp), hi = Math.min(publishedMax[name], 1000);
  console.log(`== ${name}: overlap ${lo}..${hi} K (CoolProp R ${cp.R}, CEA R ${c.R})`);
  let best = null;
  for (let T = lo; T <= hi + 1e-9; T += 25) {
    const a = cp.cp(T), b = c.R * cpCea(c.lo, T), d = b / a - 1;
    if (!best || Math.abs(d) < Math.abs(best[1])) best = [T, d];
    console.log(`${T.toFixed(2).padStart(8)}  coolprop ${a.toFixed(5).padStart(10)}  cea ${b.toFixed(5).padStart(10)}  cea/coolprop-1 ${(100 * d).toFixed(4).padStart(8)} %`);
  }
  console.log(`   smallest step on the 25 K grid: ${best[0]} K, ${(100 * best[1]).toFixed(4)} %`);
}
