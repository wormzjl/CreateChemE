// Current shifted degree-5 ideal-gas Cp fits (from the bundled property records) versus NIST Shomate (Chase 1998).
function fit(c,T){const d=T-298.15;let cp=0;for(let k=5;k>=0;k--)cp=cp*d+c[k];return cp;}
function shomate([A,B,C,D,E],T){const t=T/1000;return A+B*t+C*t*t+D*t*t*t+E/(t*t);}
const methaneFit=[35.605746481777615,0.037000414354219455,0.0001459299391720176,-4.216765855568231e-07,5.351033897511078e-10,-2.715203964835217e-13];
const nitrogenFit=[29.124372589504464,0.0005780310349492723,0.00000307622401005812,4.1241579732790716e-8,-8.967281103873693e-11,5.418990171918791e-14];
const methaneSh1=[-0.703029,108.4773,-42.52157,5.862788,0.678565]; // 298-1300 K
const nitrogenSh=[19.50583,19.88705,-8.598535,1.369784,0.527601]; // 500-2000 K
const nitrogenShLow=[28.98641,1.853978,-9.647459,16.63537,0.000117]; // 100-500 K
console.log("T[K]  CH4 fit  CH4 Shomate  dev%   |  N2 fit  N2 Shomate  dev%");
for(const T of [298.15,400,500,600,700,800,900,950,1000,1073.15,1100,1143.15,1200,1300]){
  const m=fit(methaneFit,T), ms=shomate(methaneSh1,T);
  const n=fit(nitrogenFit,T), ns=T<500?shomate(nitrogenShLow,T):shomate(nitrogenSh,T);
  console.log(`${T.toFixed(2).padStart(8)}  ${m.toFixed(3).padStart(8)}  ${ms.toFixed(3).padStart(8)}  ${(100*(m/ms-1)).toFixed(2).padStart(6)}   |  ${n.toFixed(3).padStart(7)}  ${ns.toFixed(3).padStart(7)}  ${(100*(n/ns-1)).toFixed(2).padStart(6)}`);
}
