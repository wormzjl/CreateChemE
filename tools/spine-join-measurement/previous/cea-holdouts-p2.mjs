// CEA 9-coefficient Cp (second interval, 1000..6000 K) against the ideal-gas holdouts at 1000, 1100 and 1200 K:
// NIST-JANAF tables (Chase 1998; janaf.nist.gov C-067 CH4, N-023 N2, C-095 CO2) and the NIST WebBook Gurvich table for C2H6.
const R = 8.314472;
const hi = {
  Nitrogen: [5.877124060e+05,-2.239249073e+03,6.066949220e+00,-6.139685500e-04,1.491806679e-07,-1.923105485e-11,1.061954386e-15],
  Methane: [3.730042760e+06,-1.383501485e+04,2.049107091e+01,-1.961974759e-03,4.727313040e-07,-3.728814690e-11,1.623737207e-15],
  Ethane: [5.025782130e+06,-2.033022397e+04,3.322552930e+01,-3.836703410e-03,7.238405860e-07,-7.319182500e-11,3.065468699e-15],
  CarbonDioxide: [1.176962419e+05,-1.788791477e+03,8.291523190e+00,-9.223156780e-05,4.863676880e-09,-1.891053312e-12,6.330036590e-16],
};
const holdout = {
  Nitrogen: { 1000: 32.697, 1100: 33.241, 1200: 33.723, src: 'JANAF N-023' },
  Methane: { 1000: 71.795, 1100: 75.529, 1200: 78.833, src: 'JANAF C-067' },
  Ethane: { 1000: 122.55, 1100: 128.55, 1200: 133.80, src: 'NIST WebBook C74840 Gurvich' },
  CarbonDioxide: { 1000: 54.308, 1100: 55.409, 1200: 56.342, src: 'JANAF C-095' },
};
const cp = (a, T) => R * (a[0]/T**2 + a[1]/T + a[2] + a[3]*T + a[4]*T**2 + a[5]*T**3 + a[6]*T**4);
for (const [name, a] of Object.entries(hi)) for (const T of [1000, 1100, 1200])
  console.log(`${name.padEnd(14)} ${T}  cea ${cp(a, T).toFixed(4).padStart(9)}  ${holdout[name].src.padEnd(28)} ${holdout[name][T].toFixed(3).padStart(8)}  cea/holdout-1 ${(100 * (cp(a, T) / holdout[name][T] - 1)).toFixed(3).padStart(7)} %`);
