'use strict';
// Shared parsing / classification helpers for the neural-failure mechanics analysis.
// Read-only: consumes eval/*/evaluation.jsonl journals, writes nothing itself.

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const JOURNALS = [
  { key: 'G-17023-160', dir: 'G-17023-160-holdoutv2-1', primary: true, label: 'G-17023 e160 (primary)' },
  { key: 'G-17041-160', dir: 'G-17041-160-holdoutv2-1', label: 'G-17041 e160' },
  { key: 'B-17023-160', dir: 'B-17023-160-holdoutv2-1', label: 'B-17023 e160' },
  { key: 'B-17011-80', dir: 'B-17011-80-holdoutv2-1', label: 'B-17011 e80' },
  { key: 'packaged', dir: 'packaged-holdoutv2-1', label: 'packaged (shipped)' },
];

const STRICT_QUALS = new Set(['DRY_EQUILIBRIUM', 'WET_EQUILIBRIUM']);

function isStrict(run) {
  return !!(run && run.success && STRICT_QUALS.has(run.waterQualification));
}

const RE_RESIDUAL = /^scaled residual: initial=([^,]+), final=(.+)$/;
const RE_DOMINANT = /^dominant residual: EquationId\[family=([A-Z_]+), node=(-?\d+), component=(-?\d+)\], physical=(.+)$/;
const RE_DIRECTIONS = /^local-block directions: accepted=(\d+), rejected=(\d+)$/;
const RE_FLOOR = /^floor support refreshed (\d+) time\(s\); retained=(\d+)\/(\d+)$/;
const RE_FD = /^fine finite-difference Jacobians: fresh=(\d+), reused=(\d+)$/;
const RE_BUDGET = /neural budget exhausted; attempts=(\d+), iterations=(\d+)\/(\d+), residual=([^,]+), refreshes=(\d+)\/(\d+)/;
const RE_NEURALMS = /neuralMs=(\d+)/;
const RE_ITER_PROGRESS = /^maximum scaled residual ([^\s]+) at iteration (\d+) and ([^\s]+) now$/;
const RE_WITHDRAWAL = /\(withdrawal ([0-9.eE+-]+)\)/;

function num(x) {
  const v = parseFloat(String(x).replace(/E/g, 'e'));
  return Number.isFinite(v) ? v : null;
}

function parseEvents(events) {
  const out = {
    events: events || [],
    initialResidual: null,
    finalResidual: null,
    dominantFamily: null,
    dominantNode: null,
    dominantComponent: null,
    dominantPhysical: null,
    accepted: null,
    rejected: null,
    floorRefreshes: null,
    floorRetained: null,
    floorTotal: null,
    fdFresh: null,
    fdReused: null,
    neuralMs: null,
    budget: null, // {attempts, iterations, iterationCap, residual, refreshes}
    armijo: false,
    iterationCap: false,
    outsideCoverage: false,
    seedRejected: false,
    traceFallback: false,
    freeWaterDeclined: false,
    verifiedFinal: false,
    zeroPivot: false,
    bestIterResidual: null,
    bestIterIndex: null,
    vleState: null, // {component, node, temperature, liquidFlow, vaporFlow}
  };
  for (const e of out.events) {
    let m;
    if ((m = e.match(RE_RESIDUAL))) { out.initialResidual = num(m[1]); out.finalResidual = num(m[2]); continue; }
    if ((m = e.match(RE_DOMINANT))) {
      out.dominantFamily = m[1]; out.dominantNode = parseInt(m[2], 10);
      out.dominantComponent = parseInt(m[3], 10); out.dominantPhysical = num(m[4]); continue;
    }
    if ((m = e.match(RE_DIRECTIONS))) { out.accepted = parseInt(m[1], 10); out.rejected = parseInt(m[2], 10); continue; }
    if ((m = e.match(RE_FLOOR))) { out.floorRefreshes = +m[1]; out.floorRetained = +m[2]; out.floorTotal = +m[3]; continue; }
    if ((m = e.match(RE_FD))) { out.fdFresh = +m[1]; out.fdReused = +m[2]; continue; }
    if ((m = e.match(RE_ITER_PROGRESS))) { out.bestIterResidual = num(m[1]); out.bestIterIndex = +m[2]; continue; }
    if ((m = e.match(RE_BUDGET))) {
      out.budget = {
        attempts: +m[1], iterations: +m[2], iterationCap: +m[3],
        residual: num(m[4]), refreshes: +m[5], refreshCap: +m[6],
      };
    }
    if ((m = e.match(RE_NEURALMS))) out.neuralMs = +m[1];
    if ((m = e.match(/^dominant VLE state: component=([^,]+), node=(\d+), temperature=([^,]+), liquid-flow=([^,]+), vapor-flow=(.+)$/))) {
      out.vleState = { component: m[1], node: +m[2], temperature: num(m[3]), liquidFlow: num(m[4]), vaporFlow: num(m[5]) };
      continue;
    }
    if (/no admissible Armijo/.test(e)) out.armijo = true;
    if (/iteration budget exhausted/.test(e)) out.iterationCap = true;
    if (/model unavailable or outside coverage/.test(e)) out.outsideCoverage = true;
    if (/neural seed rejected/.test(e)) out.seedRejected = true;
    if (/Stage-trace support fell back to identity/.test(e)) out.traceFallback = true;
    if (/free-water continuation declined/.test(e)) out.freeWaterDeclined = true;
    if (/verified final Newton correction/.test(e)) out.verifiedFinal = true;
    if (/zero or tiny pivot/.test(e)) out.zeroPivot = true;
  }
  return out;
}

// The mechanism partition, in the priority order requested.
// (b) is reported split into b-timeout (wall budget exhausted while iterating) and
// b-nocorrection (corrector never produced residual telemetry): both satisfy the
// literal "no scaled-residual event" rule but are physically different.
const MECHANISMS = [
  ['a-coverage', '(a) outside coverage / seed rejected'],
  ['b-timeout', '(b1) 2 s wall budget exhausted (iterated, no residual telemetry)'],
  ['b-nocorrection', '(b2) no correction iterations ran'],
  ['c-converged', '(c) converged (<1e-6) but not strict'],
  ['d-alldirrej', '(d) every local-block direction rejected'],
  ['e-cmbcrawl', '(e) CMB crawl (residual in [0.5, 1.5], accepted>0)'],
  ['f-cmbother', '(f) other CMB-dominant'],
  ['g-vle', '(g) VLE-dominant'],
  ['h-energy', '(h) ENERGY_BALANCE-dominant'],
  ['i-other', '(i) remainder'],
];

function classify(rec, p) {
  const n = rec.neural;
  if (p.outsideCoverage || p.seedRejected || rec.rawPrediction && rec.rawPrediction.supported === false) return 'a-coverage';
  const hasRes = p.initialResidual !== null;
  if (p.budget && !hasRes) return 'b-timeout';
  if (!hasRes) return 'b-nocorrection';
  if (n.diagnostics.newtonIterations === 0 && p.finalResidual === p.initialResidual && p.finalResidual >= 1e-6) {
    // no progress at all, but residual telemetry exists -> keep going through the ladder
  }
  const fin = p.finalResidual;
  if (fin !== null && fin < 1e-6) return 'c-converged';
  if (p.accepted === 0 && p.rejected > 0) return 'd-alldirrej';
  const fam = p.dominantFamily;
  if (fam === 'COMPONENT_MATERIAL_BALANCE') {
    if (fin !== null && fin >= 0.5 && fin <= 1.5 && p.accepted > 0) return 'e-cmbcrawl';
    return 'f-cmbother';
  }
  if (fam === 'VAPOR_LIQUID_EQUILIBRIUM') return 'g-vle';
  if (fam === 'ENERGY_BALANCE') return 'h-energy';
  return 'i-other';
}

function structure(rec) {
  const i = rec.input;
  const wattsSpec = i.specifications.find((s) => 'watts' in s);
  const watts = wattsSpec ? wattsSpec.watts : null;
  const kelvinSpec = i.specifications.find((s) => 'kelvin' in s);
  const ratioSpec = i.specifications.find((s) => 'ratio' in s);
  // liquid-depletion family: max side-draw withdrawal fraction >= 0.8
  let withdrawal = null;
  for (const k of ['current', 'neural', 'neuralFirst']) {
    const run = rec[k];
    if (!run) continue;
    const a = run.diagnostics && run.diagnostics.acceptanceAudit;
    if (a && a.checks) {
      const c = a.checks.find((x) => x.family === 'SIDE_DRAW_SPLIT');
      if (c && typeof c.value === 'number') withdrawal = Math.max(withdrawal === null ? -Infinity : withdrawal, c.value);
    }
    const m = (run.failure || '').match(RE_WITHDRAWAL);
    if (m) withdrawal = Math.max(withdrawal === null ? -Infinity : withdrawal, num(m[1]));
  }
  if (withdrawal === -Infinity) withdrawal = null;
  const failText = ['current', 'neural', 'neuralFirst'].map((k) => (rec[k] && rec[k].failure) || '').join(' ');
  let branch = null;
  for (const k of ['neural', 'neuralFirst', 'current']) {
    if (rec[k] && rec[k].seed && rec[k].seed.branch) { branch = rec[k].seed.branch; break; }
  }
  return {
    stageCount: i.stageCount,
    feedStage: i.feedStageNumber,
    steam: i.steamFeeds.length > 0,
    steamCount: i.steamFeeds.length,
    draws: i.sideDraws.length,
    pumparounds: i.pumparounds.length,
    reboilerWatts: watts,
    condenserKelvin: kelvinSpec ? kelvinSpec.kelvin : null,
    refluxRatio: ratioSpec ? ratioSpec.ratio : null,
    withdrawal,
    liquidDepletion: withdrawal !== null && withdrawal >= 0.8,
    zeroBoilUp: i.steamFeeds.length === 0 && watts === 0 && i.feedStageNumber < i.stageCount,
    heatGated: /condensation-capped|not below the/.test(failText),
    branch,
    source: rec.design && rec.design.source,
    family: rec.design && rec.design.family,
    seedResidual: rec.rawPrediction && rec.rawPrediction.nativeResidual
      ? rec.rawPrediction.nativeResidual.maximumScaledMeshResidual : null,
    rawMs: rec.rawPrediction ? rec.rawPrediction.ms : null,
  };
}

function loadJournal(dir) {
  const file = path.join(ROOT, 'eval', dir, 'evaluation.jsonl');
  const rows = [];
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    if (!line.trim()) continue;
    const rec = JSON.parse(line);
    const p = parseEvents(rec.neural.diagnostics.events);
    const st = structure(rec);
    rows.push({
      id: rec.id,
      strictNeural: isStrict(rec.neural),
      strictCurrent: isStrict(rec.current),
      strictNeuralFirst: isStrict(rec.neuralFirst),
      neuralSuccess: !!rec.neural.success,
      neuralStatus: rec.neural.status,
      waterQualification: rec.neural.waterQualification || null,
      neuralMs: rec.neural.ms,
      neuralCpuMs: rec.neural.cpuMillis,
      currentMs: rec.current.ms,
      neuralFirstMs: rec.neuralFirst.ms,
      currentSuccess: !!rec.current.success,
      currentStatus: rec.current.status,
      neuralFirstStatus: rec.neuralFirst.status,
      iterations: rec.neural.diagnostics.newtonIterations,
      diagResidual: rec.neural.diagnostics.maximumScaledResidual,
      solvePath: rec.neural.diagnostics.solvePath,
      auditFailed: (rec.neural.diagnostics.acceptanceAudit && rec.neural.diagnostics.acceptanceAudit.checks
        ? rec.neural.diagnostics.acceptanceAudit.checks.filter((c) => !c.passed).map((c) => c.family) : []),
      currentAuditFailed: (rec.current.diagnostics && rec.current.diagnostics.acceptanceAudit && rec.current.diagnostics.acceptanceAudit.checks
        ? rec.current.diagnostics.acceptanceAudit.checks.filter((c) => !c.passed).map((c) => c.family) : []),
      currentDeadline: rec.current.status === 'DEADLINE_EXCEEDED',
      neuralFirstDeadline: rec.neuralFirst.status === 'DEADLINE_EXCEEDED',
      neuralFailure: rec.neural.failure || null,
      currentFailure: rec.current.failure || null,
      p,
      st,
      mech: null,
    });
    const row = rows[rows.length - 1];
    row.mech = row.strictNeural ? 'strict' : classify(rec, p);
  }
  return rows;
}

// ---- small stats helpers -------------------------------------------------
function quantile(sorted, q) {
  if (!sorted.length) return null;
  const pos = (sorted.length - 1) * q;
  const lo = Math.floor(pos), hi = Math.ceil(pos);
  if (lo === hi) return sorted[lo];
  return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo);
}
function stats(values) {
  const v = values.filter((x) => typeof x === 'number' && Number.isFinite(x)).slice().sort((a, b) => a - b);
  if (!v.length) return { n: 0 };
  const sum = v.reduce((a, b) => a + b, 0);
  return {
    n: v.length, mean: sum / v.length, median: quantile(v, 0.5),
    p10: quantile(v, 0.1), p90: quantile(v, 0.9), min: v[0], max: v[v.length - 1], sum,
  };
}
function fmt(x, d = 1) {
  if (x === null || x === undefined || !Number.isFinite(x)) return '-';
  if (x !== 0 && (Math.abs(x) < 1e-3 || Math.abs(x) >= 1e6)) return x.toExponential(2);
  return x.toFixed(d);
}
function stageBand(n) {
  if (n <= 9) return '2-9';
  if (n <= 19) return '10-19';
  if (n <= 34) return '20-34';
  if (n <= 49) return '35-49';
  return '50-64';
}
const STAGE_BANDS = ['2-9', '10-19', '20-34', '35-49', '50-64'];

module.exports = { ROOT, JOURNALS, MECHANISMS, loadJournal, isStrict, stats, quantile, fmt, stageBand, STAGE_BANDS, parseEvents };
