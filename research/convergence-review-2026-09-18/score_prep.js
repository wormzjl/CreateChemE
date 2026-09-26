// usage: node score_prep.js <baseline-run-dir> <arm-run-dir> [<arm-run-dir> ...]
// Scores seed-preparation arms against a baseline run on the same inputs: strict counts, latency,
// paired gains/losses, applied/declined counts, and initial-residual distributions.
const fs = require('fs');
const strict = m => m && m.success && (m.waterQualification === 'DRY_EQUILIBRIUM' || m.waterQualification === 'WET_EQUILIBRIUM');
const reW = /\(withdrawal ([0-9.eE+-]+)\)/;
function withdrawal(res) {
  const chk = (res && res.diagnostics && res.diagnostics.acceptanceAudit && res.diagnostics.acceptanceAudit.checks || []).find(c => c.family === 'SIDE_DRAW_SPLIT');
  if (chk) return chk.value; const m = reW.exec((res && res.failure) || ''); return m ? +m[1] : null;
}
function load(dir) {
  const rows = fs.readFileSync(dir + '/evaluation.jsonl', 'utf8').split('\n').filter(Boolean).map(JSON.parse);
  const meta = JSON.parse(fs.readFileSync(dir + '/run.json', 'utf8'));
  return { rows, meta, byId: new Map(rows.map(r => [r.id, r])) };
}
function evInfo(res) {
  const ev = (res && res.diagnostics && res.diagnostics.events) || []; const o = { prep: 'none' };
  for (const e of ev) {
    let m;
    if ((m = /experiment: seedPrep=(\w+) (applied|declined)/.exec(e))) o.prep = m[2];
    if ((m = /scaled residual: initial=([0-9.eE+-]+), final=([0-9.eE+-]+)/.exec(e))) { o.init = +m[1]; o.fin = +m[2]; }
    if ((m = /local-block directions: accepted=(\d+), rejected=(\d+)/.exec(e))) { o.acc = +m[1]; o.rej = +m[2]; }
    if ((m = /dominant residual: EquationId\[family=([A-Z_]+)/.exec(e))) o.fam = m[1];
  }
  o.it = res && res.diagnostics ? res.diagnostics.newtonIterations : null;
  return o;
}
const q = (a, f) => { const s = a.filter(x => x != null && isFinite(x)).sort((x, y) => x - y); return s.length ? s[Math.min(s.length - 1, Math.floor(f * s.length))] : NaN; };
const base = load(process.argv[2]);
const depletion = new Set();
// Liquid-depletion family: a side-draw request no lane solved strictly whose best state withdraws >= 0.8 of
// the liquid reaching a draw tray (a withdrawal read off a diverged iterate is noisy, so solved ids are never in it).
for (const r of base.rows) { if (!r.input.sideDraws.length) continue; if (['current', 'neural', 'neuralFirst'].some(m => strict(r[m]))) continue; const w = Math.max(...['current', 'neural', 'neuralFirst'].map(m => { const v = withdrawal(r[m]); return v == null ? -1 : v; })); if (w >= 0.8) depletion.add(r.id); }
function summary(run, label) {
  const rows = run.rows; const n = rows.length;
  const cnt = m => rows.filter(r => strict(r[m])).length;
  const ms = m => rows.reduce((s, r) => s + r[m].ms, 0) / n;
  const keep = rows.filter(r => !depletion.has(r.id));
  const cntK = m => keep.filter(r => strict(r[m])).length;
  const infos = rows.map(r => evInfo(r.neural));
  const applied = infos.filter(i => i.prep === 'applied').length, declined = infos.filter(i => i.prep === 'declined').length;
  const initAll = infos.map(i => i.init), initOk = rows.map((r, k) => strict(r.neural) ? infos[k].init : null), initFail = rows.map((r, k) => !strict(r.neural) ? infos[k].init : null);
  const noIt = infos.filter(i => i.init == null).length;
  const acc0 = infos.filter(i => i.acc === 0 && i.rej > 0).length;
  const crawl = infos.filter(i => i.fam === 'COMPONENT_MATERIAL_BALANCE' && i.fin != null && i.fin >= 0.5 && i.fin <= 1.5).length;
  console.log(`\n== ${label}  model=${run.meta.modelId}  n=${n}  elapsed=${run.meta.elapsedSeconds.toFixed(0)}s`);
  console.log(`strict  classical ${cnt('current')}  neural-only ${cnt('neural')}  neural-first ${cnt('neuralFirst')}   | excl. depletion (n=${keep.length}): ${cntK('current')} / ${cntK('neural')} / ${cntK('neuralFirst')}`);
  console.log(`mean ms classical ${ms('current').toFixed(0)}  only ${ms('neural').toFixed(0)}  first ${ms('neuralFirst').toFixed(0)}  ratio first/classical ${(ms('neuralFirst') / ms('current')).toFixed(3)}`);
  console.log(`prep applied ${applied} declined ${declined} | neural-only: no-iteration ${noIt}, all-directions-rejected ${acc0}, CMB crawl ${crawl}`);
  console.log(`initial residual p10/p50/p90  all ${q(initAll, .1).toExponential(2)} ${q(initAll, .5).toExponential(2)} ${q(initAll, .9).toExponential(2)} | strict ${q(initOk, .5).toExponential(2)} | failed ${q(initFail, .5).toExponential(2)}`);
  const decl = {}; for (const r of rows) for (const e of ((r.neural.diagnostics && r.neural.diagnostics.events) || [])) { const m = /seedPrep=\w+ declined: (.*)$/.exec(e); if (m) { const k = m[1].replace(/[0-9.eE+-]+/g, '#').slice(0, 90); decl[k] = (decl[k] || 0) + 1; } }
  if (Object.keys(decl).length) console.log('declined reasons', JSON.stringify(decl));
  const gate = {}; for (const r of rows) for (const e of ((r.neural.diagnostics && r.neural.diagnostics.events) || [])) { const m = /seedPrep=liftSelect (rawMerit|raw)=.* -> (\w+)/.exec(e); if (m) { const k = (m[1] === 'rawMerit' ? 'merit-gate ' : 'max-gate ') + m[2]; gate[k] = (gate[k] || 0) + 1; } }
  if (Object.keys(gate).length) console.log('gate decisions', JSON.stringify(gate));
  return { rows };
}
summary(base, process.argv[2] + ' (baseline)');
for (const dir of process.argv.slice(3)) {
  const arm = load(dir); summary(arm, dir);
  for (const m of ['neural', 'neuralFirst']) {
    let gain = 0, loss = 0; const gains = [], losses = [];
    for (const r of arm.rows) { const b = base.byId.get(r.id); if (!b) continue; const a = strict(r[m]), bb = strict(b[m]); if (a && !bb) { gain++; gains.push(r.id); } if (!a && bb) { loss++; losses.push(r.id); } }
    console.log(`paired vs baseline [${m}]: gains ${gain} losses ${loss}` + (gains.length + losses.length <= 30 ? `  gains=${gains.join(',')}  losses=${losses.join(',')}` : ''));
  }
  // common-success latency
  const common = arm.rows.filter(r => { const b = base.byId.get(r.id); return b && strict(r.neural) && strict(b.neural); });
  const t = (rs, pick) => rs.reduce((s, r) => s + pick(r), 0) / Math.max(1, rs.length);
  console.log(`common neural-only successes n=${common.length}: baseline mean ms ${t(common, r => base.byId.get(r.id).neural.ms).toFixed(0)}  arm ${t(common, r => r.neural.ms).toFixed(0)}; mean iterations baseline ${t(common, r => base.byId.get(r.id).neural.diagnostics.newtonIterations).toFixed(1)} arm ${t(common, r => r.neural.diagnostics.newtonIterations).toFixed(1)}`);
}
