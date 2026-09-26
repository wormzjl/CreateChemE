// Summarizes the pinned CoolProp fluid files (dev/fluids at ae81610e, CoolProp 8.0.0): EOS range, term families,
// critical/triple states, melting line, ancillaries and transport models. Run: node summarize.mjs > coolprop-fluid-summary.txt
import { readFileSync, readdirSync } from 'node:fs';

const rows = [];
for (const f of readdirSync('fluids').filter((x) => x.endsWith('.json')).sort()) {
  const j = JSON.parse(readFileSync('fluids/' + f, 'utf8'));
  const e = j.EOS[0];
  const c = j.STATES.critical, tl = j.STATES.triple_liquid;
  const ml = j.ANCILLARIES.melting_line;
  let melt = 'none';
  if (ml) {
    const parts = ml.parts || [];
    const lo = Math.min(...parts.map((p) => p.T_min)), hi = Math.max(...parts.map((p) => p.T_max));
    melt = `${ml.type} ${lo}..${hi} K (${ml.BibTeX || ''})`;
  }
  const pS = j.ANCILLARIES.pS;
  const vis = j.TRANSPORT && j.TRANSPORT.viscosity, con = j.TRANSPORT && j.TRANSPORT.conductivity;
  const tdesc = (t) => (t ? `${t.BibTeX || ''}${t.hardcoded ? ' [hardcoded ' + t.hardcoded + ']' : ''}${JSON.stringify(t).includes('"hardcoded"') && !t.hardcoded ? ' [partly hardcoded]' : ''}` : 'none');
  rows.push([
    f.replace('.json', ''),
    `EOS ${e.BibTeX_EOS}; CP0 ${e.BibTeX_CP0 || ''}`,
    `Ttriple ${e.Ttriple} K, T_max ${e.T_max} K, p_max ${e.p_max / 1e6} MPa`,
    `Tc ${c.T} K, pc ${c.p} Pa, rhoc ${c.rhomolar} mol/m3; triple p ${tl.p} Pa`,
    `acentric ${e.acentric}, M ${e.molar_mass} kg/mol, pseudo_pure ${e.pseudo_pure}`,
    `alpha0 [${e.alpha0.map((t) => t.type.replace('IdealGasHelmholtz', '')).join(', ')}]`,
    `alphar [${[...new Set(e.alphar.map((t) => t.type.replace('ResidualHelmholtz', '')))].join(', ')}]`,
    `melting ${melt}`,
    `pS ancillary ${pS ? pS.Tmin + '..' + pS.Tmax + ' K, max err ' + (pS.max_abserror_percentage ?? '?') + ' %' : 'none'}`,
    `viscosity ${tdesc(vis)}; conductivity ${tdesc(con)}`,
  ].join('\n    '));
}
console.log('CoolProp 8.0.0 (commit ae81610e7d23efc57f9d051c8e70a4d66e87537f) dev/fluids summary; MIT licence.\n');
console.log(rows.join('\n'));
