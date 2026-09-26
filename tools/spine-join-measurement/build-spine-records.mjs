// Builds the four reference-spine records (N2, CH4, C2H6, CO2) of batch 2026-09-24-coolprop-low-temperature from the
// sources and writes them to src/main/resources/data/createcheme/materials/spine/. Run with Node 22 from this folder:
// node build-spine-records.mjs [--write]. Without --write it only prints the measurements.
//
// History of what it builds (the P2 version of this script is kept in previous/build-spine-records-p2.mjs):
//  - P2 (dc82327): r1 of all four records (CoolProp alpha0 to a join, NASA CEA above), then in the test resources.
//  - P3 WP3 (50dfe31): the records moved to the main resources; CO2 r2 adds the same alpha0 below the triple point to
//    90 K as its own segment (graded estimated_declared_error). That hand edit is reproduced here.
//  - P3 WP9a: the CEA gas constant 8.314510 J/(mol K) (nasa/cea 3f4441d2 source/param.f90.in line 32,
//    "gas_constant = 8314.5100d+00 ! J/kmol-K", verified in P3 WP3) instead of 8.314472 for the CEA segments and the
//    CEA standard entropies (N2 and C2H6 r2, CO2 r3); methane r2 (decision D13): Setzmann-Wagner to 425 K, the GERG-2008
//    ideal-gas function of Jaeschke and Schley 1995 from 425 to 1200 K, nothing above 1200 K.
//
// Sources (read-only):
//  - CoolProp fluid files bundled with the Java oracle (src/test/resources/science/thermo/coolprop, revision ae81610e):
//    EOS[0].alpha0 terms, reducing temperature, gas constant, molar mass, triple-point temperature.
//  - NASA CEA thermo.inp (nasa/cea 3f4441d2) via the verbatim records of research/.../sources/nasa-cea/cea-species-extract.txt.
//  - ATcT 1.220 formation enthalpies via research/.../sources/coolprop/hformation-atct-9b35f538.json.
//  - NIST AGA8 GERG2008.cpp (research/.../sources/nist-aga8/GERG2008.cpp): methane's n0i, th0i and R* (parsed; lines printed).
//  - JANAF S(298.15 K) for the entropy disagreement: janaf.nist.gov tables N-023, C-067, C-095 (fetched 2026-09-24).
//  - Measurement only (never written into a record): the ExoMol-derived methane reference of WP10,
//    research/.../methane-ideal-gas/outputs/methane_cp_table.json (P3_REFERENCES_WP8_WP10.md part B).
import fs from 'node:fs';
import path from 'node:path';

const HERE = path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'));
const WORKTREE = path.resolve(HERE, '../..');
const MAIN = 'D:/Minecraft/Modding/1.21/CreateChemE';
const RESEARCH = `${MAIN}/research/2026-09-24-coolprop-low-temperature`;
const SOURCES = `${RESEARCH}/sources`;
const R_CEA = 8.31451; // nasa/cea 3f4441d2 source/param.f90.in line 32 (8314.5100 J/(kmol K))
const R_CEA_P2 = 8.314472; // what P2 used, printed for the comparison only
const T0 = 298.15;
const GERG_JOIN = 425, GERG_END = 1200; // decision D13

const species = [
  { key: 'nitrogen', component: 'Nitrogen', coolprop: 'Nitrogen', cea: 'N2', group: 'N2', janafS: 191.609, janafTable: 'N-023', revision: 2 },
  { key: 'methane', component: 'Methane', coolprop: 'Methane', cea: 'CH4', group: 'CH4', janafS: 186.251, janafTable: 'C-067', revision: 2 },
  { key: 'ethane', component: 'Ethane', coolprop: 'Ethane', cea: 'C2H6', group: 'C2H6', janafS: null, janafTable: null, revision: 2 },
  { key: 'carbon_dioxide', component: 'CarbonDioxide', coolprop: 'CarbonDioxide', cea: 'CO2', group: 'CO2', janafS: 213.795, janafTable: 'C-095', revision: 3 },
];
// Published range of each reference equation (P0 section 4 correction 6; CoolProp's T_max is an extrapolation limit).
const publishedMax = { Nitrogen: 1000, Methane: 625, Ethane: 675, CarbonDioxide: 1100 };
// The P2 joins, kept. The P2 rule (smallest Cp step on the 25 K grid) is re-run with R = 8.314510 and printed.
const P2_JOIN = { Nitrogen: 600, Ethane: 500, CarbonDioxide: 975 };

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
const cpCea = (c, T, R = R_CEA) => R * (c[0] / T ** 2 + c[1] / T + c[2] + c[3] * T + c[4] * T ** 2 + c[5] * T ** 3 + c[6] * T ** 4);
const sCea = (c, T, R = R_CEA) => R * (-c[0] / (2 * T ** 2) - c[1] / T + c[2] * Math.log(T) + c[3] * T + c[4] * T ** 2 / 2 + c[5] * T ** 3 / 3 + c[6] * T ** 4 / 4 + c[8]);

// Closed forms of a helmholtz_ideal_terms segment, as ReferenceSpine evaluates them (h and s up to constants).
function helmholtz(R, Tr, terms) {
  const planck = [], cosh = [], power = [];
  let a = 0;
  for (const t of terms) {
    if (t.type === 'log_tau') a += t.a;
    else if (t.type === 'power') t.n.forEach((n, i) => power.push([n, t.t[i]]));
    else if (t.type === 'planck_einstein') t.n.forEach((n, i) => planck.push([n, t.t[i]]));
    else if (t.type === 'planck_einstein_function_t') t.n.forEach((n, i) => planck.push([n, t.v[i] / t.critical_temperature_kelvin]));
    else if (t.type === 'planck_einstein_cosh') t.n.forEach((n, i) => cosh.push([n, t.t[i]]));
    else throw new Error(`term ${t.type}`);
  }
  return {
    cp: T => {
      const tau = Tr / T; let c = 1 + a;
      for (const [n, t] of power) c -= n * t * (t - 1) * tau ** t;
      for (const [n, th] of planck) { const x = th * tau, e = Math.exp(-x); c += n * x * x * e / (1 - e) ** 2; }
      for (const [n, th] of cosh) { const x = th * tau, e = Math.exp(-2 * x); c += n * x * x * 4 * e / (1 + e) ** 2; }
      return R * c;
    },
    h: T => {
      const tau = Tr / T; let c = 1 + a;
      for (const [n, t] of power) c += n * t * tau ** t;
      for (const [n, th] of planck) { const x = th * tau; c += n * x / Math.expm1(x); }
      for (const [n, th] of cosh) { const x = th * tau; c -= n * x * Math.tanh(x); }
      return R * T * c;
    },
    s: T => {
      const tau = Tr / T; let c = (1 + a) * Math.log(T);
      for (const [n, t] of power) c += n * (t - 1) * tau ** t;
      for (const [n, th] of planck) { const x = th * tau, d = -Math.expm1(-x); c += n * (x * Math.exp(-x) / d - Math.log(d)); }
      for (const [n, th] of cosh) { const x = th * tau; c += n * (-x * Math.tanh(x) + x + Math.log1p(Math.exp(-2 * x)) - Math.LN2); }
      return R * c;
    },
  };
}

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
  return { Tr, R, Ttp: eos.Ttriple, molarMass: eos.molar_mass, terms, reference: eos.BibTeX_EOS, ...helmholtz(R, Tr, terms) };
}

// GERG-2008 ideal part of methane (component 1) from NIST's GERG2008.cpp, parsed so that the record carries the file's
// numbers verbatim. Jaeschke and Schley 1995 form: cp0/R* = B0 + C0 (D0/T / sinh(D0/T))^2 + E0 (F0/T / cosh(F0/T))^2
// + G0 (H0/T / sinh(H0/T))^2 + I0 (J0/T / cosh(J0/T))^2 with B0 = n0i[1][3], (C0, E0, G0, I0) = n0i[1][4..7],
// (D0, F0, H0, J0) = th0i[1][4..7] in K, R* = Rs = 8.31451.
function gergMethane() {
  const lines = fs.readFileSync(`${SOURCES}/nist-aga8/GERG2008.cpp`, 'utf8').split(/\r?\n/);
  const find = (re) => { const i = lines.findIndex(l => re.test(l)); if (i < 0) throw new Error(`GERG2008.cpp: ${re}`); return i; };
  const value = (line, name) => {
    const m = line.match(new RegExp(name.replace(/[[\]]/g, '\\$&') + '\\s*=\\s*([-+0-9.eE]+);'));
    if (!m) throw new Error(name);
    return Number(m[1]);
  };
  const nLine = find(/^\s*n0i\[1\]\[3\]/), thLine = find(/^\s*th0i\[1\]\[4\]/), rsLine = find(/^\s*Rs = /), rLine = find(/^\s*RGERG = /), tcLine = find(/^\s*Tc\[1\] = /);
  const n = [3, 4, 5, 6, 7].map(k => value(lines[nLine], `n0i[1][${k}]`));
  const th = [4, 5, 6, 7].map(k => value(lines[thLine], `th0i[1][${k}]`));
  const Rs = value(lines[rsLine], 'Rs'), RGERG = value(lines[rLine], 'RGERG'), Tc = value(lines[tcLine], 'Tc[1]');
  const terms = [
    { type: 'log_tau', a: Number((n[0] - 1).toPrecision(12)) },
    { type: 'planck_einstein', n: [n[1], n[3]], t: [2 * th[0], 2 * th[2]] }, // ln|sinh(x)| = x + ln(1 - e^-2x) - ln 2
    { type: 'planck_einstein_cosh', n: [n[2], n[4]], t: [th[1], th[3]] },
  ];
  // The Jaeschke-Schley cp0 written out directly, to check the mapping onto the loader's terms.
  const cpDirect = T => Rs * (n[0] + n[1] * (th[0] / T / Math.sinh(th[0] / T)) ** 2 + n[2] * (th[1] / T / Math.cosh(th[1] / T)) ** 2
    + n[3] * (th[2] / T / Math.sinh(th[2] / T)) ** 2 + n[4] * (th[3] / T / Math.cosh(th[3] / T)) ** 2);
  return { n, th, Rs, RGERG, Tc, terms, cpDirect, lines: { n: nLine + 1, th: thLine + 1, Rs: rsLine + 1, RGERG: rLine + 1, Tc: tcLine + 1 },
    ...helmholtz(Rs, 1, terms) };
}

const atct = JSON.parse(fs.readFileSync(`${SOURCES}/coolprop/hformation-atct-9b35f538.json`, 'utf8')).fluids;
const write = process.argv.includes('--write');
const outDir = `${WORKTREE}/src/main/resources/data/createcheme/materials/spine`;
const pct = (x, d = 4) => `${x >= 0 ? '+' : ''}${(100 * x).toFixed(d)}`;

for (const s of species) {
  const cp = coolpropFluid(s.coolprop), cea = ceaRecord(s.cea);
  const low = cea.intervals[0], high = cea.intervals[1];
  const S0 = sCea(low.coefficients, T0), S0old = sCea(low.coefficients, T0, R_CEA_P2), h = atct[s.coolprop].standard_state.hmolar_formation;
  const ceaSource = (i) => `NASA CEA thermo.inp (github.com/nasa/cea 3f4441d2, Apache-2.0; McBride, Zehe and Gordon 2002, NASA/TP-2002-211556), `
    + `${s.cea} record ${cea.refCode} (${cea.title.split(': ')[1]}), interval ${i.tlo}..${i.thi} K; coefficients a1..a7, b1, b2 as printed, b1 and b2 not used; `
    + `gas constant 8.314510 J/(mol K) as CEA defines it (source/param.f90.in)`;
  const coolpropSegment = (min, max, extra = {}) => ({
    type: 'helmholtz_ideal_terms', temperature_min_kelvin: min, temperature_max_kelvin: max,
    source: extra.source ?? `CoolProp ${s.coolprop}.json EOS[0].alpha0 (revision ae81610e, MIT; ${cp.reference}); lead and enthalpy-entropy offset terms omitted (constants only)`,
    revision: extra.revision ?? `coolprop-ae81610e-${s.key}-alpha0`, ...(extra.coverage ? { coverage: extra.coverage } : {}),
    gas_constant_j_per_mol_kelvin: cp.R, reducing_temperature_kelvin: cp.Tr, terms: cp.terms,
  });
  const segments = [];
  let recordSource, grade, evidence;
  if (s.key === 'methane') {
    const g = gergMethane();
    console.log(`GERG2008.cpp: RGERG line ${g.lines.RGERG} (${g.RGERG}), Rs line ${g.lines.Rs} (${g.Rs}), Tc[1] line ${g.lines.Tc} (${g.Tc}), `
      + `n0i[1][3..7] line ${g.lines.n} (${g.n.join(', ')}), th0i[1][4..7] line ${g.lines.th} (${g.th.join(', ')} K)`);
    let worstMap = 0;
    for (let T = 100; T <= 3000; T += 7.3) worstMap = Math.max(worstMap, Math.abs(g.cp(T) / g.cpDirect(T) - 1));
    console.log(`  mapping check: loader-form Cp against the written-out Jaeschke-Schley cp0, worst relative ${worstMap.toExponential(2)} over 100..3000 K`);
    const steps = [];
    for (let T = 375; T <= 1000; T += 25) steps.push(`${T} ${pct(g.cp(T) / cp.cp(T) - 1, 5)}`);
    console.log(`  Cp step SW -> GERG (%) on the 25 K grid: ${steps.join(', ')}`);
    const stepJoin = g.cp(GERG_JOIN) / cp.cp(GERG_JOIN) - 1;
    console.log(`  join ${GERG_JOIN} K (D13): SW ${cp.cp(GERG_JOIN).toFixed(6)} -> GERG ${g.cp(GERG_JOIN).toFixed(6)} J/(mol K), step ${stepJoin.toExponential(3)}`);
    // Holdout (measurement only): the WP10 ExoMol-derived reference.
    const ref = JSON.parse(fs.readFileSync(`${RESEARCH}/methane-ideal-gas/outputs/methane_cp_table.json`, 'utf8')).rows;
    const spineCp = T => (T <= GERG_JOIN ? cp.cp(T) : g.cp(T));
    const spineH = T => T <= GERG_JOIN ? cp.h(T) - cp.h(T0) : cp.h(GERG_JOIN) - cp.h(T0) + g.h(T) - g.h(GERG_JOIN);
    let worstCp = 0, worstH = 0;
    for (const r of ref) {
      if (r.T < 300 || r.T > GERG_END) continue;
      const dcp = spineCp(r.T) / r.cp_reference - 1, dh = spineH(r.T) - r.dh_reference;
      worstCp = Math.max(worstCp, Math.abs(dcp)); worstH = Math.max(worstH, Math.abs(dh));
      console.log(`  ${String(r.T).padStart(6)} K: spine Cp ${spineCp(r.T).toFixed(4)} ref ${r.cp_reference.toFixed(4)} (${pct(dcp, 3)} %, declared -${r.declared_minus_pct.toFixed(2)}/+${r.declared_plus_pct.toFixed(2)} %); `
        + `h-h298 ${spineH(r.T).toFixed(1)} ref ${r.dh_reference.toFixed(1)} (${dh >= 0 ? '+' : ''}${dh.toFixed(1)} J/mol)`);
    }
    segments.push(coolpropSegment(cp.Ttp, GERG_JOIN));
    segments.push({
      type: 'helmholtz_ideal_terms', temperature_min_kelvin: GERG_JOIN, temperature_max_kelvin: GERG_END,
      source: `GERG-2008 ideal-gas part of methane (Kunz and Wagner 2012, J. Chem. Eng. Data 57, 3032; the cp0 equation of Jaeschke and Schley 1995, Int. J. Thermophys. 16, 1381) `
        + `as NIST's AGA8 GERG2008.cpp codes it (github.com/usnistgov/AGA8): n0i[1][3..7] (line ${g.lines.n}) and th0i[1][4..7] (line ${g.lines.th}) verbatim with R* = Rs = 8.31451 (line ${g.lines.Rs}); `
        + `log_tau a = n0i[1][3] - 1; the ln sinh terms (j = 4, 6) as planck_einstein with t = 2 th0i, the ln cosh terms (j = 5, 7) as planck_einstein_cosh with t = th0i; `
        + `reducing temperature 1 K, so t is in K as GERG2008.cpp evaluates th0i/T; n0i[1][1..2] only fix h and s at GERG's reference state and are not carried`,
      revision: 'gerg2008-aga8-methane-ideal-jaeschke-schley-1995', gas_constant_j_per_mol_kelvin: g.Rs, reducing_temperature_kelvin: 1, terms: g.terms,
    });
    recordSource = `Reference spine r2 (batch 2026-09-24-coolprop-low-temperature, decisions D6 and D13): CoolProp alpha0 (Setzmann and Wagner 1991) from the triple point to ${GERG_JOIN} K, `
      + `the GERG-2008 ideal-gas function (Jaeschke and Schley 1995) from ${GERG_JOIN} to ${GERG_END} K (Cp step at the join ${pct(stepJoin, 4)} %); methane's ideal gas is not available above ${GERG_END} K; `
      + `ATcT 1.220 formation enthalpy; CEA standard entropy (R = 8.314510). Built by tools/spine-join-measurement/build-spine-records.mjs. r2 (P3 WP9a) replaces the NASA CEA (Gurvich 1991) segments above 375 K of r1.`;
    grade = 'qualified';
    evidence = `P3 WP9a ReferenceSpineTest (decision D13): the Setzmann-Wagner segment equals the Java Helmholtz oracle to 1e-9 and the GERG-2008 segment a transcription of GERG2008.cpp; `
      + `against the ideal-gas Cp of the ExoMol MM line list (a test holdout, P3_REFERENCES_WP8_WP10.md part B; nothing derived from it is bundled) the spine's Cp is within ${(100 * worstCp).toFixed(2)} % from 300 to ${GERG_END} K `
      + `(D7 target 0.2 %; the reference's own declared error grows to -0.10/+0.62 % at 1200 K) and h(T) - h(298.15) within ${Math.ceil(worstH)} J/mol; unavailable above ${GERG_END} K`;
  } else {
    // Join rule of P2: the multiple of 25 K with the smallest Cp step between max(298.15 K, CEA start) and
    // min(published EOS range, 1000 K); the step is measured against the CEA interval that starts at the join.
    let rule = null;
    const grid = [];
    for (let T = Math.ceil(Math.max(T0, low.tlo) / 25) * 25; T <= Math.min(publishedMax[s.coolprop], 1000); T += 25) {
      const interval = T < low.thi ? low : high;
      const step = Math.abs(cpCea(interval.coefficients, T) / cp.cp(T) - 1);
      grid.push([T, step]);
      if (!rule || step < rule.step) rule = { T, step };
    }
    const joinT = P2_JOIN[s.coolprop], interval = joinT < low.thi ? low : high;
    const stepNow = cpCea(interval.coefficients, joinT) / cp.cp(joinT) - 1, stepP2 = cpCea(interval.coefficients, joinT, R_CEA_P2) / cp.cp(joinT) - 1;
    const near = grid.filter(([T]) => Math.abs(T - joinT) <= 50).map(([T, st]) => `${T} ${st.toExponential(2)}`).join(', ');
    console.log(`${s.component}: join ${joinT} K kept; Cp step ${stepNow.toExponential(3)} (P2 with 8.314472: ${stepP2.toExponential(3)}); `
      + `rule with 8.314510 picks ${rule.T} K (${rule.step.toExponential(2)}); steps near: ${near}`);
    const ceaStep1000 = cpCea(high.coefficients, 1000) / cpCea(low.coefficients, 1000) - 1;
    console.log(`  CEA step at 1000 K ${ceaStep1000.toExponential(2)}`);
    if (s.key === 'carbon_dioxide') segments.push(coolpropSegment(90, cp.Ttp, {
      source: `CoolProp ${s.coolprop}.json EOS[0].alpha0 (revision ae81610e, MIT; ${cp.reference}), the terms of the next segment continued below the triple point, where the Span-Wagner equation of state states no range; lead and enthalpy-entropy offset terms omitted (constants only)`,
      revision: `coolprop-ae81610e-${s.key}-alpha0-subtriple`,
      coverage: { grade: 'estimated_declared_error', evidence: "Extrapolation: the Span-Wagner alpha0 is a sum of Planck-Einstein (harmonic-oscillator) terms on the rigid linear-rotor limit Cp0 = 3.5 R, analytic below the triple point, but the equation of state's range starts at the triple point 216.592 K, so nothing in the source qualifies it below. Held to JANAF C-095 (Chase 1998) at 100 and 200 K within 0.2 % by ReferenceSpineTest (P3: -0.008 % and +0.018 %); JANAF tabulates no 150 K value. Declared error 0.2 % in Cp from 90 K to the triple point." },
    }));
    segments.push(coolpropSegment(cp.Ttp, joinT));
    if (joinT < low.thi) segments.push({ type: 'nasa9', temperature_min_kelvin: joinT, temperature_max_kelvin: low.thi, source: ceaSource(low),
      revision: `cea-3f4441d2-${s.key}-${low.tlo}-${low.thi}`, gas_constant_j_per_mol_kelvin: R_CEA, coefficients: low.coefficients });
    segments.push({ type: 'nasa9', temperature_min_kelvin: high.tlo, temperature_max_kelvin: high.thi, source: ceaSource(high),
      revision: `cea-3f4441d2-${s.key}-${high.tlo}-${high.thi}`, gas_constant_j_per_mol_kelvin: R_CEA, coefficients: high.coefficients });
    recordSource = `P2 reference spine (batch 2026-09-24-coolprop-low-temperature, decision D6): CoolProp alpha0 from the triple point to ${joinT} K (join at the smallest Cp step on a 25 K grid in P2; step now ${pct(stepNow, 4)} %), NASA CEA above; ATcT 1.220 formation enthalpy; CEA standard entropy. Built by tools/spine-join-measurement/build-spine-records.mjs.`
      + (s.key === 'carbon_dioxide' ? ' r2 (P3 WP3): the same alpha0 continued below the triple point to 90 K as its own segment, graded estimated_declared_error, for the CO2-lean liquid in liquid methane (fixture F7).' : '')
      + ` r${s.revision} (P3 WP9a): the CEA segments and the standard entropy with CEA's own gas constant 8.314510 J/(mol K) instead of 8.314472.`;
    grade = 'qualified';
    evidence = `P2 ReferenceSpineTest: CoolProp segment equal to the Java Helmholtz oracle to 1e-9; CEA Cp within 0.2 % of ${s.key === 'ethane' ? 'the NIST WebBook Gurvich table (C74840)' : 'JANAF ' + s.janafTable} at 1000, 1100 and 1200 K (D7); unchanged with R = 8.314510 (P3 WP9a)`
      + (s.key === 'carbon_dioxide' ? '. Applies from the triple point 216.592 K; the segment below it carries its own grade (estimated_declared_error).' : '');
  }
  console.log(`  S(298.15) CEA ${S0.toFixed(6)} J/(mol K) with 8.314510 (P2 with 8.314472: ${S0old.toFixed(6)}); record ${Number(S0.toFixed(4))}`
    + (s.janafS ? `, JANAF ${s.janafS} (diff ${(S0 - s.janafS).toFixed(4)})` : '') + `; dfH ATcT ${h.value} +- ${h.uncertainty}; M ${cp.molarMass}`);
  if (!write) continue;
  const entropy = { value_j_per_mol_kelvin: Number(S0.toFixed(4)), pressure_pascal: 100000,
    source: `NASA CEA thermo.inp ${s.cea} (${cea.refCode}) polynomial at 298.15 K and 1e5 Pa with R = 8.314510`
      + (s.cea === 'C2H6' ? ', 1.85 K below its 300 K interval start' : '')
      + (s.key === 'methane' ? ' (the polynomial is not a segment of this record)' : '')
      + (s.janafS ? `; CEA states no uncertainty, the uncertainty given is |CEA - JANAF| with JANAF ${s.janafTable} S(298.15) = ${s.janafS} (Chase 1998), a disagreement estimate`
                  : '; no uncertainty: CEA states none and no second tabulation was available locally') };
  if (s.janafS) entropy.uncertainty_j_per_mol_kelvin = Number(Math.abs(S0 - s.janafS).toFixed(4));
  const record = {
    schema_version: 1, id: `createcheme:spine_${s.key}`, component: s.component, revision: `spine-${s.key}-r${s.revision}`,
    source: recordSource,
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
