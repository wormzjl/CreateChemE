// F4: builds the nitrogen viscosity log tables below 273.16 K from NIST WebBook data (Span et al. 2000 / Lemmon &
// Jacobsen 2004 transport), and reports the log-linear interpolation error against every NIST row in range.
// Liquid: saturated liquid, triple point (63.151 K) to the critical point (126.192 K), nodes >= 1.5 K apart.
// Vapour below 273.16 K: the 10 kPa isobar (near-dilute; below the 12.52 kPa triple-point pressure, so vapour from
// the triple point up), joined to the existing 100 kPa table at its first node, 273.16 K, which is kept as it was.
const fs=require('fs');
const rows=f=>fs.readFileSync(__dirname+'/../nist/'+f,'utf8').trim().split('\n').slice(1).map(l=>l.split('\t'));
const sat=rows('sat-triple-to-critical.tsv').map(r=>[+r[0],+r[11]*1e-6]);
const iso=rows('isobar-10kPa-63-273K.tsv').map(r=>[+r[0],+r[11]*1e-6]).filter(r=>r[0]<273.16-1);
function pick(data,gap){const out=[data[0]];for(const r of data){if(r[0]-out[out.length-1][0]>=gap)out.push(r);}if(out[out.length-1]!==data[data.length-1])out.push(data[data.length-1]);return out;}
function interp(nodes,t){let i=1;while(i<nodes.length-1&&nodes[i][0]<t)i++;const [t0,m0]=nodes[i-1],[t1,m1]=nodes[i];const f=(t-t0)/(t1-t0);return Math.exp(Math.log(m0)+f*(Math.log(m1)-Math.log(m0)));}
const liquid=pick(sat,1.5);liquid[0]=[63.151,liquid[0][1]];
let worst=0,at=0;for(const [t,m] of sat){if(t>liquid[liquid.length-2][0])continue;const e=interp(liquid,t)/m-1;if(Math.abs(e)>Math.abs(worst)){worst=e;at=t;}}
const vapor=iso;
let vw=0,vat=0;
const existing=JSON.parse(fs.readFileSync(__dirname+'/../../../../src/main/resources/data/createcheme/materials/properties/nitrogen.json','utf8')).viscosity.vapor;
console.log(JSON.stringify({
  liquid:{nodes:liquid.length,first:liquid[0],last:liquid[liquid.length-1],worstInterpolationError:worst,atKelvin:at,
    temperatures:liquid.map(r=>r[0]),coefficients:liquid.map(r=>r[1])},
  vaporBelow:{nodes:vapor.length,first:vapor[0],last:vapor[vapor.length-1],temperatures:vapor.map(r=>r[0]),coefficients:vapor.map(r=>r[1])},
  existingVaporFirst:[existing.temperatures_kelvin[0],existing.coefficients[0]]
},null,1));
let w116=0,a116=0;for(const [t,m] of sat){if(t>116)continue;const e=interp(liquid,t)/m-1;if(Math.abs(e)>Math.abs(w116)){w116=e;a116=t;}}
console.error('liquid worst interpolation error at T<=116 K (the liquid range at <=2 MPa):',w116,'at',a116);
