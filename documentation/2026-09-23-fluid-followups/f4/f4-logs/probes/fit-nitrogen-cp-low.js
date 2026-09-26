// F4: fits the nitrogen ideal-gas Cp segment below 273.16 K to the NIST zero-pressure Cp (../nist/cp0-zero-pressure-63-303K.tsv,
// Span et al. 2000 equation of state via the NIST WebBook, linear extrapolation of the 0.1 and 1 kPa isobars to zero pressure),
// constrained to equal the existing 298.15..900 K fit at the joint so the heat capacity (and so the enthalpy's slope) is continuous.
// Output: six coefficients in powers of (T - 298.15 K), the form the catalog reads.
const fs=require('fs');
const high=[29.124372589504464,0.0005780310349492723,3.07622401005812e-06,4.1241579732790716e-08,-8.967281103873693e-11,5.418990171918791e-14];
const horner=(c,x)=>{let v=0;for(let k=c.length-1;k>=0;k--)v=v*x+c[k];return v;};
const joint=273.16, J=horner(high,joint-298.15);
const rows=fs.readFileSync(__dirname+'/../nist/cp0-zero-pressure-63-303K.tsv','utf8').trim().split('\n').map(l=>l.split('\t').map(Number)).filter(r=>r[0]<=joint+1e-9);
// Cp_low(T) = J + sum_{k=1..3} a_k u^k, u=(T-joint)/100: the constraint holds for any a.
const deg=3, A=[], b=[];
for(const [t,cp] of rows){const u=(t-joint)/100;A.push([...Array(deg)].map((_,k)=>Math.pow(u,k+1)));b.push(cp-J);}
// normal equations
const N=[...Array(deg)].map(()=>Array(deg).fill(0)),r=Array(deg).fill(0);
for(let i=0;i<A.length;i++)for(let p=0;p<deg;p++){r[p]+=A[i][p]*b[i];for(let q=0;q<deg;q++)N[p][q]+=A[i][p]*A[i][q];}
for(let p=0;p<deg;p++){for(let q=p+1;q<deg;q++){const f=N[q][p]/N[p][p];for(let s=p;s<deg;s++)N[q][s]-=f*N[p][s];r[q]-=f*r[p];}}
const a=Array(deg).fill(0);for(let p=deg-1;p>=0;p--){let s=r[p];for(let q=p+1;q<deg;q++)s-=N[p][q]*a[q];a[p]=s/N[p][p];}
// expand J + sum a_k ((x+d)/100)^k, x=T-298.15, d=298.15-joint, into powers of x
const d=298.15-joint, c=Array(6).fill(0);c[0]=J;
const binom=(n,k)=>{let v=1;for(let i=1;i<=k;i++)v=v*(n-k+i)/i;return v;};
for(let k=1;k<=deg;k++)for(let j=0;j<=k;j++)c[j]+=a[k-1]/Math.pow(100,k)*binom(k,j)*Math.pow(d,k-j);
let worst=0,worstT=0;
for(const [t,cp] of rows){const e=horner(c,t-298.15)/cp-1;if(Math.abs(e)>Math.abs(worst)){worst=e;worstT=t;}}
console.log(JSON.stringify({joint,cpAtJointHigh:J,cpAtJointLow:horner(c,joint-298.15),coefficients:c,worstRelativeError:worst,atKelvin:worstT,points:rows.length},null,1));
for(const t of [63.15,77.355,100,150,200,250,273.16])console.log(t,horner(c,t-298.15).toFixed(6));
