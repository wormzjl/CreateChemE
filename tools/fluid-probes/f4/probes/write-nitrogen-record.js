// F4: writes the cryogenic nitrogen property record (src/main/resources/.../properties/nitrogen.json) from the NIST
// data in ../nist/ and the fits in fit-nitrogen-cp-low.js / build-nitrogen-viscosity-tables.js. Run from the worktree root.
// Run once, against the r1 record of e837ada (git show e837ada:src/main/resources/data/createcheme/materials/properties/nitrogen.json):
// it appends the new vapour nodes to the existing table, so a second run on the r2 record would duplicate them.
const fs=require('fs'),cp=require('child_process');
const path='src/main/resources/data/createcheme/materials/properties/nitrogen.json';
const d=JSON.parse(fs.readFileSync(path,'utf8'));
const low=JSON.parse(cp.execSync('node documentation/fluid-followups/f4-logs/probes/fit-nitrogen-cp-low.js').toString().split('\n63.15')[0]);
const tables=JSON.parse(cp.execSync('node documentation/fluid-followups/f4-logs/probes/build-nitrogen-viscosity-tables.js',{stdio:['ignore','pipe','ignore']}).toString());
const out={};
for(const [k,v] of Object.entries(d)){
  out[k]=v;
  if(k==='temperature_max_kelvin'){
    out.fluid_domain={temperature_min_kelvin:63.151,temperature_max_kelvin:900,pressure_min_pascal:100,pressure_max_pascal:2000000,
      evidence:'Nitrogen triple point 63.151 K (Span et al. 2000 via NIST) to 900 K. Validated in F4 (FluidNitrogenCryogenicTest): ideal-gas Cp below 273.16 K within 0.02 % of NIST zero-pressure Cp; PR78 saturation pressure at 77.355 K +1.23 %, saturated liquid density +0.34 %; vapour Cp at 100 K and 200 K, 1 atm, within 1 %; viscosity tables cover the range.'};
  }
}
out.revision='fluid-nitrogen-nist-r2';
out.source='NIST WebBook nitrogen Shomate Cp fit (298.15..900 K) and a zero-pressure Cp segment below 273.16 K (Span et al. 2000 via NIST); viscosity tables from NIST (Lemmon and Jacobsen 2004 via NIST); PR constants from CoolProp. Nitrogen cross-interactions initially estimated zero.';
out.temperature_min_kelvin=63.151;
out.ideal_gas_cp.below={temperature_kelvin:273.16,coefficients:low.coefficients,
  source:'Fitted in F4 (documentation/fluid-followups/f4-logs/probes/fit-nitrogen-cp-low.js) to the NIST WebBook zero-pressure nitrogen Cp, 63.16..273.16 K (0.1 and 1 kPa isobars extrapolated to zero pressure), constrained to equal the 298.15..900 K fit at 273.16 K; worst deviation 0.018 % (at the joint).'};
out.viscosity.liquid={type:'log_table',revision:'nitrogen-nist-viscosity-r2',
  source:'NIST WebBook nitrogen saturated liquid (Lemmon and Jacobsen 2004 viscosity), triple point to critical point; log-linear interpolation within 0.1 % of NIST below 116 K (the liquid range at or below 2 MPa), 1.1 % near the critical point; saturation-pressure approximation.',
  estimated:false,temperature_min_kelvin:63.151,temperature_max_kelvin:126.192,pressure_min_pascal:12519.8,pressure_max_pascal:3395800,
  reference_temperature_kelvin:77.355,temperatures_kelvin:tables.liquid.temperatures,coefficients:tables.liquid.coefficients};
const v=d.viscosity.vapor;
out.viscosity.vapor={type:'log_table',revision:'nitrogen-nist-viscosity-r2',
  source:'NIST WebBook nitrogen: 10 kPa isobar from the triple point to 268.151 K (near-dilute vapour), then the unchanged 100 kPa isobar from 273.16 K; reference-pressure approximation.',
  estimated:false,temperature_min_kelvin:63.151,temperature_max_kelvin:v.temperature_max_kelvin,pressure_min_pascal:10000,pressure_max_pascal:100000,
  reference_temperature_kelvin:v.reference_temperature_kelvin,
  temperatures_kelvin:tables.vaporBelow.temperatures.map((t,i)=>i===0?63.151:t).concat(v.temperatures_kelvin),
  coefficients:tables.vaporBelow.coefficients.concat(v.coefficients)};
out.provenance=Object.assign({},d.provenance,{
  cp_low:'https://webbook.nist.gov/cgi/fluid.cgi?ID=C7727379&Action=Data&Wide=on&Digits=12&RefState=DEF&TUnit=K&PUnit=kPa&DUnit=kg%2Fm3&HUnit=kJ%2Fmol&WUnit=m%2Fs&VisUnit=uPa*s&STUnit=N%2Fm&Type=IsoBar&P=0.1&TLow=63.16&THigh=303.16&TInc=5 and P=1',
  saturation:'https://webbook.nist.gov/cgi/fluid.cgi?ID=C7727379&Action=Data&Wide=on&Digits=12&RefState=DEF&TUnit=K&PUnit=kPa&DUnit=kg%2Fm3&HUnit=kJ%2Fmol&WUnit=m%2Fs&VisUnit=uPa*s&STUnit=N%2Fm&Type=SatT',
  gas_low:'https://webbook.nist.gov/cgi/fluid.cgi?ID=C7727379&Action=Data&Wide=on&Digits=12&RefState=DEF&TUnit=K&PUnit=kPa&DUnit=kg%2Fm3&HUnit=kJ%2Fmol&WUnit=m%2Fs&VisUnit=uPa*s&STUnit=N%2Fm&Type=IsoBar&P=10&TLow=63.151&THigh=273.151&TInc=5'});
fs.writeFileSync(path,JSON.stringify(out,null,2)+'\n');
console.log('written',path,'liquid nodes',out.viscosity.liquid.temperatures_kelvin.length,'vapour nodes',out.viscosity.vapor.temperatures_kelvin.length);
