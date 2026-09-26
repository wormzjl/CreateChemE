// Builds the four P2 reference-spine records (N2, CH4, C2H6, CO2) of batch 2026-09-24-coolprop-low-temperature from
// the sources and writes them to src/test/resources/data/createcheme/materials/spine/. Run with Node 22 from this
// folder: node build-spine-records.mjs [--write]. Without --write it only prints the measurements.
//
// Sources (read-only):
//  - CoolProp fluid files bundled with the Java oracle (src/test/resources/science/thermo/coolprop, revision ae81610e):
//    EOS[0].alpha0 terms, reducing temperature, gas constant, molar mass, triple-point temperature.
//  - NASA CEA thermo.inp (nasa/cea 3f4441d2) via the verbatim records of research/.../sources/nasa-cea/cea-species-extract.txt.
//  - ATcT 1.220 formation enthalpies via research/.../sources/coolprop/hformation-atct-9b35f538.json.
//  - JANAF S(298.15 K) for the entropy disagreement: janaf.nist.gov tables N-023, C-067, C-095 (fetched 2026-09-24).
import fs from 'node:fs';
import path from 'node:path';

const HERE = path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'));
const WORKTREE = path.resolve(HERE, '../..');
const MAIN = 'D:/Minecraft/Modding/1.21/CreateChemE';
const SOURCES = `${MAIN}/research/2026-09-24-coolprop-low-temperature/sources`;
const R_CEA = 8.314472; // NASA/TP-2002-211556 (CODATA 1998), the gas constant of the thermo.inp coefficients
const T0 = 298.15;

const species = [
  { key: 'nitrogen', component: 'Nitrogen', coolprop: 'Nitrogen', cea: 'N2', group: 'N2', janafS: 191.609, janafTable: 'N-023' },
  { key: 'methane', component: 'Methane', coolprop: 'Methane', cea: 'CH4', group: 'CH4', janafS: 186.251, janafTable: 'C-067' },
  { key: 'ethane', component: 'Ethane', coolprop: 'Ethane', cea: 'C2H6', group: 'C2H6', janafS: null, janafTable: null },
  { key: 'carbon_dioxide', component: 'CarbonDioxide', coolprop: 'CarbonDioxide', cea: 'CO2', group: 'CO2', janafS: 213.795, janafTable: 'C-095' },
];
// Published range of each reference equation (P0 section 4 correction 6; CoolProp's T_max is an extrapolation limit).
const publishedMax = { Nitrogen: 1000, Methane: 625, Ethane: 675, CarbonDioxide: 1100 };

function ceaRecord(name) {
  const text = fs.readFileSync(`${SOURCES}/nasa-cea/cea-species-extract.txt`, 'utf8').split(/\r?\n/);
  const start = text.findIndex(l => l.startsWith(`# ${name}:`));
  if (start < 0) throw new Error(`CEA record ${name} not found`);
  const header = text[start + 2];
  const count = parseInt(header.slice(0, 2), 10), refCode = header.slice(3, 9).trim();
  const num = s => parseFloat(s.replace(/D/g, 'e'));
  const intervals = [];
  for (let i = 0; i < count; i++) {
    const base = start + 3 + 3 * i, range = text[base], c1 = text[base + 1], c2 = text[base + 2];
    const tlo = parseFloat(range.slice(0, 11)), thi = parseFloat(range.slice(11, 21));
    const a = [0, 16, 32, 48, 64].map(o => num(c1.slice(o, o + 16)));
    a.push(num(c2.slice(0, 16)), num(c2.slice(16, 32)));
    const b = [num(c2.slice(48, 64)), num(c2.slice(64, 80))];
    if ([...a, ...b].some(v => !Number.isFinite(v))) throw new Error(`CEA ${name}: unparsed coefficient`);
    intervals.push({ tlo, thi, coefficients: [...a, ...b] });
  }
  return { refCode, intervals, title: text[start].slice(2) };
}
const cpCea = (c, T) => R_CEA * (c[0] / T ** 2 + c[1] / T + c[2] + c[3] * T + c[4] * T ** 2 + c[5] * T ** 3 + c[6] * T ** 4);
const sCea = (c, T) => R_CEA * (-c[0] / (2 * T ** 2) - c[1] / T + c[2] * Math.log(T) + c[3] * T + c[4] * T ** 2 / 2 + c[5] * T ** 3 / 3 + c[6] * T ** 4 / 4 + c[8]);

function coolpropFluid(name) {
  const eos = JSON.parse(fs.readFileSync(`${WORKTREE}/src/test/resources/science/thermo/coolprop/${name}.json`, 'utf8')).EOS[0];
  const Tr = eos.STATES.reducing.T, R = eos.gas_constant, terms = [];
  for (const t of eos.alpha0) {
    if (t.type === 'IdealGasHelmholtzLogTau') terms.push({ type: 'log_tau', a: t.a });
    else if (t.type === 'IdealGasHelmholtzPower') terms.push({ type: 'power', n: t.n, t: t.t });
    else if (t.type === 'IdealGasHelmholtzPlanckEinstein') terms.push({ type: 'planck_einstein', n: t.n, t: t.t });
    else if (t.type === 'IdealGasHelmholtzPlanckEinsteinFunctionT') terms.push({ type: 'planck_einstein_function_t', n: t.n, v: t.v, critical_temperature_kelvin: t.Tcrit });
    else if (t.type === 'IdealGasHelmholtzLead' || t.type === 'IdealGasHelmholtzEnthalpyEntropyOffset') continue; // constants only
    else throw new Error(`unsupported alpha0 term ${t.type}`);
  }
  const cp = T => {
    const tau = Tr / T;
    let c = 1;
    for (const t of terms) {
      if (t.type === 'log_tau') c += t.a;
      else if (t.type === 'power') t.n.forEach((n, i) => { c -= n * t.t[i] * (t.t[i] - 1) * tau ** t.t[i]; });
      else {
        const th = t.type === 'planck_einstein' ? t.t : t.v.map(v => v / t.critical_temperature_kelvin);
        t.n.forEach((n, i) => { const x = th[i] * tau, e = Math.exp(-x); c += n * x * x * e / (1 - e) ** 2; });
      }
    }
    return R * c;
  };
  return { Tr, R, Ttp: eos.Ttriple, molarMass: eos.molar_mass, cp, terms, reference: eos.BibTeX_EOS };
}

const atct = JSON.parse(fs.readFileSync(`${SOURCES}/coolprop/hformation-atct-9b35f538.json`, 'utf8')).fluids;
const write = process.argv.includes('--write');
const outDir = `${WORKTREE}/src/test/resources/data/createcheme/materials/spine`;
if (write) fs.mkdirSync(outDir, { recursive: true });

for (const s of species) {
  const cp = coolpropFluid(s.coolprop), cea = ceaRecord(s.cea);
  const low = cea.intervals[0], high = cea.intervals[1];
  // Join rule: the multiple of 25 K with the smallest Cp step between max(298.15 K, CEA start) and
  // min(published EOS range, 1000 K); the step is measured against the CEA interval that starts at the join.
  let join = null;
  for (let T = Math.ceil(Math.max(T0, low.tlo) / 25) * 25; T <= Math.min(publishedMax[s.coolprop], 1000); T += 25) {
    const interval = T < low.thi ? low : high;
    const step = Math.abs(cpCea(interval.coefficients, T) / cp.cp(T) - 1);
    if (!join || step < join.step) join = { T, step, interval };
  }
  const S0 = sCea(low.coefficients, T0), h = atct[s.coolprop].standard_state.hmolar_formation;
  const stepPercent = 100 * (cpCea(join.interval.coefficients, join.T) / cp.cp(join.T) - 1);
  const ceaStep1000 = 100 * (cpCea(high.coefficients, 1000) / cpCea(low.coefficients, 1000) - 1);
  console.log(`${s.component}: CoolProp ${cp.Ttp}..${join.T} K, CEA ${join.T}..6000 K; Cp step at ${join.T} K ${stepPercent.toFixed(5)} %; `
    + `CEA step at 1000 K ${ceaStep1000.toExponential(2)} %; S(298.15) CEA ${S0.toFixed(6)} J/(mol K)`
    + (s.janafS ? `, JANAF ${s.janafS} (diff ${(S0 - s.janafS).toFixed(4)})` : '') + `; dfH ATcT ${h.value} +- ${h.uncertainty}; M ${cp.molarMass}`);
  if (!write) continue;
  const ceaSource = (i) => `NASA CEA thermo.inp (github.com/nasa/cea 3f4441d2, Apache-2.0; McBride, Zehe and Gordon 2002, NASA/TP-2002-211556), `
    + `${s.cea} record ${cea.refCode} (${cea.title.split(': ')[1]}), interval ${i.tlo}..${i.thi} K; coefficients a1..a7, b1, b2 as printed, b1 and b2 not used`;
  const segments = [{
    type: 'helmholtz_ideal_terms', temperature_min_kelvin: cp.Ttp, temperature_max_kelvin: join.T,
    source: `CoolProp ${s.coolprop}.json EOS[0].alpha0 (revision ae81610e, MIT; ${cp.reference}); lead and enthalpy-entropy offset terms omitted (constants only)`,
    revision: `coolprop-ae81610e-${s.key}-alpha0`, gas_constant_j_per_mol_kelvin: cp.R, reducing_temperature_kelvin: cp.Tr, terms: cp.terms,
  }];
  if (join.T < low.thi) segments.push({ type: 'nasa9', temperature_min_kelvin: join.T, temperature_max_kelvin: low.thi, source: ceaSource(low),
    revision: `cea-3f4441d2-${s.key}-${low.tlo}-${low.thi}`, gas_constant_j_per_mol_kelvin: R_CEA, coefficients: low.coefficients });
  segments.push({ type: 'nasa9', temperature_min_kelvin: high.tlo, temperature_max_kelvin: high.thi, source: ceaSource(high),
    revision: `cea-3f4441d2-${s.key}-${high.tlo}-${high.thi}`, gas_constant_j_per_mol_kelvin: R_CEA, coefficients: high.coefficients });
  const entropy = { value_j_per_mol_kelvin: Number(S0.toFixed(4)), pressure_pascal: 100000,
    source: `NASA CEA thermo.inp ${s.cea} (${cea.refCode}) polynomial at 298.15 K and 1e5 Pa with R = 8.314472`
      + (s.cea === 'C2H6' ? ', 1.85 K below its 300 K interval start' : '')
      + (s.janafS ? `; CEA states no uncertainty, the uncertainty given is |CEA - JANAF| with JANAF ${s.janafTable} S(298.15) = ${s.janafS} (Chase 1998), a disagreement estimate`
                  : '; no uncertainty: CEA states none and no second tabulation was available locally') };
  if (s.janafS) entropy.uncertainty_j_per_mol_kelvin = Number(Math.abs(S0 - s.janafS).toFixed(4));
  const grade = s.key === 'methane' ? 'estimated_declared_error' : 'qualified';
  const evidence = s.key === 'methane'
    ? 'P2 ReferenceSpineTest: CoolProp segment equal to the Java Helmholtz oracle (Setzmann and Wagner 1991 alpha0) to 1e-9; CEA (Gurvich 1991) is 2.6 to 3.5 % above JANAF C-067 at 1000 to 1200 K, the open methane reference disagreement of P0 section 2.4 (declared error, not the D7 0.2 % target)'
    : `P2 ReferenceSpineTest: CoolProp segment equal to the Java Helmholtz oracle to 1e-9; CEA Cp within 0.2 % of ${s.key === 'ethane' ? 'the NIST WebBook Gurvich table (C74840)' : 'JANAF ' + s.janafTable} at 1000, 1100 and 1200 K (D7)`;
  const record = {
    schema_version: 1, id: `createcheme:spine_${s.key}`, component: s.component, revision: `spine-${s.key}-r1`,
    source: `P2 reference spine (batch 2026-09-24-coolprop-low-temperature, decision D6): CoolProp alpha0 from the triple point to ${join.T} K (join at the smallest Cp step on a 25 K grid, ${stepPercent.toFixed(4)} %), NASA CEA above; ATcT 1.220 formation enthalpy; CEA standard entropy. Built by tools/spine-join-measurement/build-spine-records.mjs.`,
    molar_mass_kg_per_mol: cp.molarMass,
    formation_enthalpy: { value_j_per_mol: h.value, uncertainty_j_per_mol: h.uncertainty, temperature_kelvin: 298.15,
      source: `ATcT ${h.version} (${h.id}) as carried in CoolProp master 9b35f538 INFO.STANDARD_STATE` },
    standard_entropy: entropy,
    ideal_gas: { maximum_cp_step_fraction: 0.005, segments },
    groups: { scheme: 'eppr78-2022', counts: { [s.group]: 1 } },
    coverage: { grade, evidence },
  };
  fs.writeFileSync(`${outDir}/${s.key}.json`, JSON.stringify(record, null, 2) + '\n');
}
