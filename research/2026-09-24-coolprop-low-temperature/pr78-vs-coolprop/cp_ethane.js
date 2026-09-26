function fit(c,T){const d=T-298.15;let cp=0;for(let k=5;k>=0;k--)cp=cp*d+c[k];return cp;}
const ethaneFit=[52.810839998282816,0.12642417422855318,2.5310154721470207e-05,-2.463921910967482e-07,3.4586923626802897e-10,-1.7871383580177725e-13];
const gurvich={298.15:52.49,400:65.46,500:77.94,600:89.19,700:99.14,800:107.94,900:115.71,1000:122.55,1100:128.55,1200:133.80,1300:138.39};
console.log("T[K]  C2H6 fit  Gurvich  dev%");
for(const [T,ref] of Object.entries(gurvich)){const v=fit(ethaneFit,+T);console.log(`${(+T).toFixed(2).padStart(8)}  ${v.toFixed(2).padStart(8)}  ${ref.toFixed(2).padStart(7)}  ${(100*(v/ref-1)).toFixed(2).padStart(6)}`);}
