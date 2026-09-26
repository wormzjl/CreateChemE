'use strict';
// Read-only analysis of neural-only failure mechanics on the fresh 312-request holdout v2.
// Writes summary.json next to this script. No solver / Gradle / Java involved.

const fs = require('fs');
const path = require('path');
const L = require('./lib.js');

const OUT = __dirname;
const journals = {};
for (const j of L.JOURNALS) journals[j.key] = L.loadJournal(j.dir);
const PRIMARY = 'G-17023-160';
const prim = journals[PRIMARY];

const allIds = prim.map((r) => r.id);
const idSets = Object.fromEntries(Object.entries(journals).map(([k, rows]) => [k, new Set(rows.map((r) => r.id))]));
const idMismatch = Object.entries(idSets)
  .filter(([, s]) => s.size !== allIds.length || allIds.some((id) => !s.has(id)))
  .map(([k]) => k);

// strict-by-model lookup
const strictBy = {};
for (const [k, rows] of Object.entries(journals)) {
  strictBy[k] = new Set(rows.filter((r) => r.strictNeural).map((r) => r.id));
}

const MECH_KEYS = L.MECHANISMS.map(([k]) => k);
const MECH_LABEL = Object.fromEntries(L.MECHANISMS);
const ORDER = ['strict', ...MECH_KEYS];

function groupBy(rows, key) {
  const g = {};
  for (const r of rows) (g[r[key]] = g[r[key]] || []).push(r);
  return g;
}

// ---------------------------------------------------------------- task 1 ---
function partition(journalKey) {
  const rows = journals[journalKey];
  const others = Object.keys(journals).filter((k) => k !== journalKey);
  const g = groupBy(rows, 'mech');
  const nonStrict = rows.filter((r) => r.mech !== 'strict').length;
  const table = [];
  for (const m of ORDER) {
    const rs = g[m] || [];
    if (!rs.length) { table.push({ mech: m, n: 0 }); continue; }
    const ms = L.stats(rs.map((r) => r.neuralMs));
    const iters = L.stats(rs.map((r) => r.iterations));
    const otherStrict = rs.filter((r) => others.some((k) => strictBy[k].has(r.id))).length;
    const domFam = {};
    for (const r of rs) { const f = r.p.dominantFamily || '(none)'; domFam[f] = (domFam[f] || 0) + 1; }
    table.push({
      mech: m,
      label: m === 'strict' ? 'strict neural-only success' : MECH_LABEL[m],
      n: rs.length,
      shareOfNonStrict: m === 'strict' ? null : rs.length / nonStrict,
      meanMs: ms.mean, medianMs: ms.median, p90Ms: ms.p90, totalMs: ms.sum,
      meanIterations: iters.mean, medianIterations: iters.median, maxIterations: iters.max,
      classicalStrict: rs.filter((r) => r.strictCurrent).length,
      neuralFirstStrict: rs.filter((r) => r.strictNeuralFirst).length,
      liquidDepletion: rs.filter((r) => r.st.liquidDepletion).length,
      zeroBoilUp: rs.filter((r) => r.st.zeroBoilUp).length,
      heatGated: rs.filter((r) => r.st.heatGated).length,
      otherModelStrict: otherStrict,
      dominantFamilies: domFam,
      meanFinalResidual: L.stats(rs.map((r) => r.p.finalResidual)).median,
      ids: rs.map((r) => r.id),
    });
  }
  return { journal: journalKey, total: rows.length, strict: g.strict ? g.strict.length : 0, nonStrict, table };
}

const task1 = Object.fromEntries(Object.keys(journals).map((k) => [k, partition(k)]));

// mechanism (b1) timeout detail on every journal: neuralMs / stage count / residual reached
const timeoutDetail = {};
for (const [k, rows] of Object.entries(journals)) {
  const rs = rows.filter((r) => r.mech === 'b-timeout');
  const conv = rs.filter((r) => r.p.budget && r.p.budget.residual !== null && r.p.budget.residual < 1e-6);
  timeoutDetail[k] = {
    n: rs.length,
    stageCount: L.stats(rs.map((r) => r.st.stageCount)),
    neuralMs: L.stats(rs.map((r) => r.neuralMs)),
    rawPredictionMs: L.stats(rs.map((r) => r.st.rawMs)),
    iterationsDone: L.stats(rs.map((r) => r.p.budget ? r.p.budget.iterations : null)),
    iterationCap: L.stats(rs.map((r) => r.p.budget ? r.p.budget.iterationCap : null)),
    residualReached: L.stats(rs.map((r) => r.p.budget ? r.p.budget.residual : null)),
    convergedButTimedOut: conv.length,
    convergedIds: conv.map((r) => r.id),
    residualBands: bands(rs.map((r) => r.p.budget ? r.p.budget.residual : null)),
    attemptsHist: hist(rs.map((r) => r.p.budget ? r.p.budget.attempts : null)),
  };
}
const nocorrectionDetail = {};
for (const [k, rows] of Object.entries(journals)) {
  const rs = rows.filter((r) => r.mech === 'b-nocorrection');
  nocorrectionDetail[k] = {
    n: rs.length,
    ids: rs.map((r) => r.id),
    stageCounts: rs.map((r) => r.st.stageCount),
    neuralMs: rs.map((r) => Math.round(r.neuralMs)),
    traceFallback: rs.filter((r) => r.p.traceFallback).length,
  };
}
function hist(values) {
  const h = {};
  for (const v of values) if (v !== null && v !== undefined) h[v] = (h[v] || 0) + 1;
  return h;
}
function bands(values) {
  const edges = [[0, 1e-6, '<1e-6'], [1e-6, 1e-3, '1e-6..1e-3'], [1e-3, 0.1, '1e-3..0.1'], [0.1, 1, '0.1..1'], [1, 10, '1..10'], [10, Infinity, '>10']];
  const h = {};
  for (const [, , lab] of edges) h[lab] = 0;
  for (const v of values) {
    if (v === null || v === undefined || !Number.isFinite(v)) continue;
    for (const [lo, hi, lab] of edges) if (v >= lo && v < hi) { h[lab]++; break; }
  }
  return h;
}

// mechanism (c) detail: which audit families failed, which qualification
const convergedDetail = {};
for (const [k, rows] of Object.entries(journals)) {
  const rs = rows.filter((r) => r.mech === 'c-converged');
  const quals = {}, fams = {};
  for (const r of rs) {
    const q = r.neuralSuccess ? (r.waterQualification || '(none)') : 'REJECTED:' + (r.waterQualification || 'no-qualification');
    quals[q] = (quals[q] || 0) + 1;
    const ff = r.auditFailed.length ? r.auditFailed : ['(all audit checks passed)'];
    for (const f of ff) fams[f] = (fams[f] || 0) + 1;
  }
  convergedDetail[k] = {
    n: rs.length, qualifications: quals, failedAuditFamilies: fams,
    accepted: rs.filter((r) => r.neuralSuccess).length,
    rejected: rs.filter((r) => !r.neuralSuccess).length,
    freeWaterDeclined: rs.filter((r) => r.p.freeWaterDeclined).length,
    medianMs: L.stats(rs.map((r) => r.neuralMs)).median,
  };
}

// mechanism (d) detail: dominant family + residual band while every direction is rejected
const dirRejDetail = {};
for (const [k, rows] of Object.entries(journals)) {
  const rs = rows.filter((r) => r.mech === 'd-alldirrej');
  dirRejDetail[k] = {
    n: rs.length,
    dominantFamily: hist(rs.map((r) => r.p.dominantFamily)),
    rejectedDirections: L.stats(rs.map((r) => r.p.rejected)),
    finalResidualBands: bands(rs.map((r) => r.p.finalResidual)),
    ratioStats: L.stats(rs.map((r) => (r.p.initialResidual ? r.p.finalResidual / r.p.initialResidual : null))),
    armijo: rs.filter((r) => r.p.armijo).length,
    floorRetainedFrac: L.stats(rs.map((r) => (r.p.floorTotal ? r.p.floorRetained / r.p.floorTotal : null))),
  };
}

// ---------------------------------------------------------------- task 2 ---
function nodeClass(r) {
  const node = r.p.dominantNode, N = r.st.stageCount, feed = r.st.feedStage;
  if (node === null) return null;
  if (node === 0) return 'condenser';
  if (node === N + 1) return 'reboiler';
  if (node < feed) return 'rectifying (above feed)';
  if (node === feed) return 'at feed';
  return 'stripping (below feed)';
}
function familyProfile(rows, famName) {
  const rs = rows.filter((r) => r.mech !== 'strict' && r.p.dominantFamily === famName);
  const nodes = {}, comps = {}, rel = [];
  const byMech = {};
  for (const r of rs) {
    const nc = nodeClass(r); nodes[nc] = (nodes[nc] || 0) + 1;
    // NOTE: dominantComponent is a LOCAL index into the active component basis (see componentIndexOffset).
    const c = r.p.dominantComponent;
    comps[c] = (comps[c] || 0) + 1;
    if (r.p.dominantNode > 0 && r.p.dominantNode <= r.st.stageCount) rel.push(r.p.dominantNode - r.st.feedStage);
    byMech[r.mech] = (byMech[r.mech] || 0) + 1;
  }
  const ratios = rs.map((r) => (r.p.initialResidual ? r.p.finalResidual / r.p.initialResidual : null));
  // Null model: dominant node drawn uniformly over the N+2 nodes of that column.
  // Needed because these designs put the feed at ~89 % of column height, so
  // "above the feed" already covers most nodes by construction.
  const expected = { condenser: 0, 'rectifying (above feed)': 0, 'at feed': 0, 'stripping (below feed)': 0, reboiler: 0 };
  for (const r of rs) {
    const N = r.st.stageCount, f = r.st.feedStage, T = N + 2;
    expected.condenser += 1 / T;
    expected['rectifying (above feed)'] += (f - 1) / T;
    expected['at feed'] += 1 / T;
    expected['stripping (below feed)'] += (N - f) / T;
    expected.reboiler += 1 / T;
  }
  const enrichment = {};
  for (const k of Object.keys(expected)) enrichment[k] = expected[k] > 0 ? (nodes[k] || 0) / expected[k] : null;
  return {
    n: rs.length,
    byMechanism: byMech,
    nodeClass: nodes,
    nodeClassExpectedUniform: expected,
    nodeClassEnrichment: enrichment,
    relativeOffset: L.stats(rel),
    relativeOffsetHist: bucketOffsets(rel),
    componentIndex: comps,
    initialResidual: L.stats(rs.map((r) => r.p.initialResidual)),
    finalResidual: L.stats(rs.map((r) => r.p.finalResidual)),
    ratioFinalOverInitial: L.stats(ratios),
    ratioBands: ratioBands(ratios),
    dominantPhysicalAbs: L.stats(rs.map((r) => Math.abs(r.p.dominantPhysical))),
    physicalBelow1e6: rs.filter((r) => Math.abs(r.p.dominantPhysical) < 1e-6).length,
  };
}
function bucketOffsets(rel) {
  const h = { '<=-10': 0, '-9..-3': 0, '-2..-1': 0, '0 (feed tray)': 0, '1..2': 0, '3..9': 0, '>=10': 0 };
  for (const d of rel) {
    if (d <= -10) h['<=-10']++;
    else if (d <= -3) h['-9..-3']++;
    else if (d <= -1) h['-2..-1']++;
    else if (d === 0) h['0 (feed tray)']++;
    else if (d <= 2) h['1..2']++;
    else if (d <= 9) h['3..9']++;
    else h['>=10']++;
  }
  return h;
}
function ratioBands(ratios) {
  const h = { '<0.01 (fell hard)': 0, '0.01..0.5': 0, '0.5..0.9': 0, '0.9..0.99': 0, '0.99..1.0 (flat)': 0, '>1 (rose)': 0 };
  for (const v of ratios) {
    if (v === null || !Number.isFinite(v)) continue;
    if (v < 0.01) h['<0.01 (fell hard)']++;
    else if (v < 0.5) h['0.01..0.5']++;
    else if (v < 0.9) h['0.5..0.9']++;
    else if (v < 0.99) h['0.9..0.99']++;
    else if (v <= 1.0) h['0.99..1.0 (flat)']++;
    else h['>1 (rose)']++;
  }
  return h;
}
// attach component ids for naming
const COMPONENT_IDS = JSON.parse(fs.readFileSync(path.join(L.ROOT, 'eval', L.JOURNALS[0].dir, 'evaluation.jsonl'), 'utf8')
  .split(/\r?\n/).find(Boolean)).input.componentBasis.componentIds;

const task2 = {};
for (const [k, rows] of Object.entries(journals)) {
  task2[k] = {
    COMPONENT_MATERIAL_BALANCE: familyProfile(rows, 'COMPONENT_MATERIAL_BALANCE'),
    VAPOR_LIQUID_EQUILIBRIUM: familyProfile(rows, 'VAPOR_LIQUID_EQUILIBRIUM'),
    ENERGY_BALANCE: familyProfile(rows, 'ENERGY_BALANCE'),
  };
}

// ---------------------------------------------------------------- task 3 ---
const task3 = (() => {
  const g = groupBy(prim, 'mech');
  const dist = {};
  for (const m of ORDER) {
    const rs = g[m] || [];
    dist[m] = {
      n: rs.length,
      correctorInitial: L.stats(rs.map((r) => r.p.initialResidual)),
      seedNativeResidual: L.stats(rs.map((r) => r.st.seedResidual)),
    };
  }
  const thresholds = [0.3, 1, 3, 10].map((x) => {
    const strict = prim.filter((r) => r.mech === 'strict');
    const fail = prim.filter((r) => r.mech !== 'strict');
    const withInit = prim.filter((r) => r.p.initialResidual !== null);
    const aboveAll = withInit.filter((r) => r.p.initialResidual > x);
    const aboveSeed = prim.filter((r) => r.st.seedResidual !== null && r.st.seedResidual > x);
    return {
      x,
      strictAboveX_corrector: strict.filter((r) => r.p.initialResidual !== null && r.p.initialResidual > x).length,
      strictWithInit: strict.filter((r) => r.p.initialResidual !== null).length,
      failAboveX_corrector: fail.filter((r) => r.p.initialResidual !== null && r.p.initialResidual > x).length,
      failWithInit: fail.filter((r) => r.p.initialResidual !== null).length,
      precisionFailAboveX: aboveAll.length ? aboveAll.filter((r) => r.mech !== 'strict').length / aboveAll.length : null,
      countAboveX: aboveAll.length,
      strictAboveX_seed: strict.filter((r) => r.st.seedResidual !== null && r.st.seedResidual > x).length,
      failAboveX_seed: fail.filter((r) => r.st.seedResidual !== null && r.st.seedResidual > x).length,
      precisionFailAboveX_seed: aboveSeed.length ? aboveSeed.filter((r) => r.mech !== 'strict').length / aboveSeed.length : null,
      countAboveX_seed: aboveSeed.length,
    };
  });
  // how separable overall (AUC-ish): fraction of (strict, fail) pairs correctly ordered on seed residual
  const s = prim.filter((r) => r.mech === 'strict' && r.st.seedResidual !== null).map((r) => r.st.seedResidual);
  const f = prim.filter((r) => r.mech !== 'strict' && r.st.seedResidual !== null).map((r) => r.st.seedResidual);
  let win = 0, tot = 0;
  for (const a of s) for (const b of f) { tot++; if (a < b) win++; else if (a === b) win += 0.5; }
  const sc = prim.filter((r) => r.mech === 'strict' && r.p.initialResidual !== null).map((r) => r.p.initialResidual);
  const fc = prim.filter((r) => r.mech !== 'strict' && r.p.initialResidual !== null).map((r) => r.p.initialResidual);
  let win2 = 0, tot2 = 0;
  for (const a of sc) for (const b of fc) { tot2++; if (a < b) win2++; else if (a === b) win2 += 0.5; }
  return { dist, thresholds, aucSeedResidual: tot ? win / tot : null, aucCorrectorInitial: tot2 ? win2 / tot2 : null };
})();

// ---------------------------------------------------------------- task 4 ---
function structProfile(rows) {
  const g = groupBy(rows, 'mech');
  const out = {};
  for (const m of ORDER) {
    const rs = g[m] || [];
    const bandsH = Object.fromEntries(L.STAGE_BANDS.map((b) => [b, 0]));
    for (const r of rs) bandsH[L.stageBand(r.st.stageCount)]++;
    out[m] = {
      n: rs.length,
      stageBands: bandsH,
      meanStageCount: L.stats(rs.map((r) => r.st.stageCount)).mean,
      medianStageCount: L.stats(rs.map((r) => r.st.stageCount)).median,
      steamOn: rs.filter((r) => r.st.steam).length,
      drawCount: hist(rs.map((r) => r.st.draws)),
      meanDraws: L.stats(rs.map((r) => r.st.draws)).mean,
      pumparoundCount: hist(rs.map((r) => r.st.pumparounds)),
      meanPumparounds: L.stats(rs.map((r) => r.st.pumparounds)).mean,
      branch: hist(rs.map((r) => r.st.branch)),
      branchUnknown: rs.filter((r) => !r.st.branch).length,
      feedFraction: L.stats(rs.map((r) => r.st.feedStage / r.st.stageCount)),
      floorSupportRetainedFraction: L.stats(rs.map((r) => (r.p.floorTotal ? r.p.floorRetained / r.p.floorTotal : null))),
      medianWithdrawal: L.stats(rs.map((r) => r.st.withdrawal)).median,
      withdrawalP90: L.stats(rs.map((r) => r.st.withdrawal)).p90,
      designFamily: hist(rs.map((r) => r.st.family)),
      source: hist(rs.map((r) => r.st.source)),
    };
  }
  return out;
}
const task4 = Object.fromEntries(Object.keys(journals).map((k) => [k, structProfile(journals[k])]));

// ---------------------------------------------------------------- task 5 ---
const task5 = (() => {
  const perId = {};
  for (const id of allIds) {
    const models = Object.keys(journals).filter((k) => strictBy[k].has(id));
    perId[id] = models;
  }
  const countHist = {};
  for (const id of allIds) countHist[perId[id].length] = (countHist[perId[id].length] || 0) + 1;
  const allFive = allIds.filter((id) => perId[id].length === 5);
  const none = allIds.filter((id) => perId[id].length === 0);
  const some = allIds.filter((id) => perId[id].length > 0 && perId[id].length < 5);
  // mechanism shown by failing models on 'some' ids
  const mechOnSome = {};
  const mechOnNone = {};
  const rowById = {};
  for (const [k, rows] of Object.entries(journals)) { rowById[k] = Object.fromEntries(rows.map((r) => [r.id, r])); }
  for (const id of some) {
    for (const k of Object.keys(journals)) {
      if (strictBy[k].has(id)) continue;
      const m = rowById[k][id].mech;
      mechOnSome[m] = (mechOnSome[m] || 0) + 1;
    }
  }
  for (const id of none) {
    for (const k of Object.keys(journals)) {
      const m = rowById[k][id].mech;
      mechOnNone[m] = (mechOnNone[m] || 0) + 1;
    }
  }
  // per-model strict counts and how the primary journal's failures look on 'some'
  const primMechOnSome = {};
  for (const id of some) { if (!strictBy[PRIMARY].has(id)) { const m = rowById[PRIMARY][id].mech; primMechOnSome[m] = (primMechOnSome[m] || 0) + 1; } }
  // structure of "never strict" ids
  const noneRows = none.map((id) => rowById[PRIMARY][id]);
  const neuralOnlyUnion = new Set();
  for (const k of Object.keys(journals)) for (const id of strictBy[k]) neuralOnlyUnion.add(id);
  return {
    neuralOnlyUnion: neuralOnlyUnion.size,
    perModelStrict: Object.fromEntries(Object.keys(journals).map((k) => [k, strictBy[k].size])),
    countHist,
    allFive: allFive.length,
    none: none.length,
    some: some.length,
    mechOnSome, mechOnNone, primMechOnSome,
    noneProfile: {
      classicalStrict: noneRows.filter((r) => r.strictCurrent).length,
      liquidDepletion: noneRows.filter((r) => r.st.liquidDepletion).length,
      heatGated: noneRows.filter((r) => r.st.heatGated).length,
      meanStageCount: L.stats(noneRows.map((r) => r.st.stageCount)).mean,
      stageBands: (() => { const h = Object.fromEntries(L.STAGE_BANDS.map((b) => [b, 0])); for (const r of noneRows) h[L.stageBand(r.st.stageCount)]++; return h; })(),
      steamOn: noneRows.filter((r) => r.st.steam).length,
    },
    allFiveProfile: (() => {
      const rs = allFive.map((id) => rowById[PRIMARY][id]);
      const h = Object.fromEntries(L.STAGE_BANDS.map((b) => [b, 0]));
      for (const r of rs) h[L.stageBand(r.st.stageCount)]++;
      return { meanStageCount: L.stats(rs.map((r) => r.st.stageCount)).mean, stageBands: h, steamOn: rs.filter((r) => r.st.steam).length, liquidDepletion: rs.filter((r) => r.st.liquidDepletion).length };
    })(),
  };
})();

// ---------------------------------------------------------------- task 6 ---
const task6 = (() => {
  const g = groupBy(prim, 'mech');
  const perMech = {};
  let total = 0;
  for (const m of ORDER) {
    const rs = g[m] || [];
    const s = L.stats(rs.map((r) => r.neuralMs));
    total += s.sum || 0;
    perMech[m] = { n: rs.length, totalMs: s.sum || 0, meanMs: s.mean, medianMs: s.median, p90Ms: s.p90, cpuMs: L.stats(rs.map((r) => r.neuralCpuMs)).sum };
  }
  for (const m of ORDER) perMech[m].shareOfTotal = total ? perMech[m].totalMs / total : null;
  // neural-first rescue accounting
  const seedFailed = prim.filter((r) => !r.strictNeural);
  const rescued = seedFailed.filter((r) => r.strictNeuralFirst);
  const rescuedClassical = rescued.filter((r) => r.strictCurrent);
  return {
    totalNeuralOnlyMs: total,
    perMech,
    strictTotalMs: perMech.strict.totalMs,
    wastedMs: total - perMech.strict.totalMs,
    seedFailedN: seedFailed.length,
    rescuedN: rescued.length,
    neuralFirstMsOnRescued: L.stats(rescued.map((r) => r.neuralFirstMs)),
    classicalMsOnRescued: L.stats(rescued.map((r) => r.currentMs)),
    neuralOnlyMsOnRescued: L.stats(rescued.map((r) => r.neuralMs)),
    rescuedAlsoClassicalStrict: rescuedClassical.length,
    neuralFirstOverheadMedianMs: (() => {
      const d = rescued.map((r) => r.neuralFirstMs - r.currentMs);
      return L.stats(d);
    })(),
    classicalStrictTotal: prim.filter((r) => r.strictCurrent).length,
    classicalDeadline: prim.filter((r) => r.currentDeadline).length,
    neuralFirstStrictTotal: prim.filter((r) => r.strictNeuralFirst).length,
    wholeSuite: {
      neuralOnlyMs: L.stats(prim.map((r) => r.neuralMs)).sum,
      classicalMs: L.stats(prim.map((r) => r.currentMs)).sum,
      neuralFirstMs: L.stats(prim.map((r) => r.neuralFirstMs)).sum,
    },
  };
})();

// --------------------------------------------- task 7 supporting evidence ---
// E1. Is the dominant CMB residual physically tiny (trace species) or physically real?
function cmbScaleEvidence(rows) {
  const rs = rows.filter((r) => r.mech !== 'strict' && r.p.dominantFamily === 'COMPONENT_MATERIAL_BALANCE');
  const abs = rs.map((r) => Math.abs(r.p.dominantPhysical));
  return {
    n: rs.length,
    physicalAbs: L.stats(abs),
    physicalBands: {
      '<1e-9 mol/s': abs.filter((x) => x < 1e-9).length,
      '1e-9..1e-6': abs.filter((x) => x >= 1e-9 && x < 1e-6).length,
      '1e-6..1e-3': abs.filter((x) => x >= 1e-6 && x < 1e-3).length,
      '1e-3..1': abs.filter((x) => x >= 1e-3 && x < 1).length,
      '>=1 mol/s': abs.filter((x) => x >= 1).length,
    },
    scaledAtLeastHalfButPhysicalBelow1e6: rs.filter((r) => r.p.finalResidual >= 0.5 && Math.abs(r.p.dominantPhysical) < 1e-6).length,
    finalResidualWithin1pctOfOne: rs.filter((r) => Math.abs(r.p.finalResidual - 1) < 0.01).length,
    finalResidualWithin01pctOfOne: rs.filter((r) => Math.abs(r.p.finalResidual - 1) < 0.001).length,
    heavyComponentShare: rs.filter((r) => r.p.dominantComponent >= 7).length / (rs.length || 1),
    rectifyingShare: rs.filter((r) => r.p.dominantNode > 0 && r.p.dominantNode < r.st.feedStage).length / (rs.length || 1),
  };
}
// E2. VLE phase-presence collapse: vapour flow underflow next to a finite liquid flow
function vleStateEvidence(rows) {
  const rs = rows.filter((r) => r.mech !== 'strict' && r.p.vleState);
  const ratio = rs.map((r) => (r.p.vleState.liquidFlow > 0 ? r.p.vleState.vaporFlow / r.p.vleState.liquidFlow : null));
  return {
    n: rs.length,
    ofWhichDominantVLE: rows.filter((r) => r.mech !== 'strict' && r.p.dominantFamily === 'VAPOR_LIQUID_EQUILIBRIUM').length,
    vaporFlowBands: {
      '<1e-30': rs.filter((r) => Math.abs(r.p.vleState.vaporFlow) < 1e-30).length,
      '1e-30..1e-12': rs.filter((r) => Math.abs(r.p.vleState.vaporFlow) >= 1e-30 && Math.abs(r.p.vleState.vaporFlow) < 1e-12).length,
      '1e-12..1e-3': rs.filter((r) => Math.abs(r.p.vleState.vaporFlow) >= 1e-12 && Math.abs(r.p.vleState.vaporFlow) < 1e-3).length,
      '>=1e-3': rs.filter((r) => Math.abs(r.p.vleState.vaporFlow) >= 1e-3).length,
    },
    liquidFlowStats: L.stats(rs.map((r) => r.p.vleState.liquidFlow)),
    vaporOverLiquid: L.stats(ratio.filter((x) => x !== null && x > 0).map((x) => Math.log10(x))),
    componentHist: hist(rs.map((r) => r.p.vleState.component)),
    bothFlowsBelow1e3: rs.filter((r) => Math.abs(r.p.vleState.liquidFlow) < 1e-3 && Math.abs(r.p.vleState.vaporFlow) < 1e-3).length,
    eitherFlowBelow1e3: rs.filter((r) => Math.abs(r.p.vleState.liquidFlow) < 1e-3 || Math.abs(r.p.vleState.vaporFlow) < 1e-3).length,
    temperature: L.stats(rs.map((r) => r.p.vleState.temperature)),
  };
}
// E3. Timeout scaling: is the 2 s wall preparation or iteration cost?
function costScaling(rows) {
  const out = {};
  for (const band of L.STAGE_BANDS) {
    const rs = rows.filter((r) => L.stageBand(r.st.stageCount) === band);
    const strictRs = rs.filter((r) => r.mech === 'strict');
    const perIter = strictRs.filter((r) => r.iterations > 0).map((r) => r.neuralMs / r.iterations);
    out[band] = {
      n: rs.length,
      strictN: strictRs.length,
      rawPredictionMs: L.stats(rs.map((r) => r.st.rawMs)).median,
      strictTotalMsMedian: L.stats(strictRs.map((r) => r.neuralMs)).median,
      strictIterationsMedian: L.stats(strictRs.map((r) => r.iterations)).median,
      strictMsPerIterationMedian: L.stats(perIter).median,
      // fixed overhead estimate = median ms of strict runs that needed <=1 iteration
      fixedOverheadMsMedian: L.stats(strictRs.filter((r) => r.iterations <= 1).map((r) => r.neuralMs)).median,
      fixedOverheadN: strictRs.filter((r) => r.iterations <= 1).length,
      timeoutN: rs.filter((r) => r.mech === 'b-timeout').length,
      timeoutShare: rs.length ? rs.filter((r) => r.mech === 'b-timeout').length / rs.length : null,
    };
  }
  return out;
}
// E4. Provable solvability: does ANY route in ANY journal prove a strict answer exists?
const strictAnywhere = new Set();
for (const rows of Object.values(journals)) {
  for (const r of rows) if (r.strictNeural || r.strictCurrent || r.strictNeuralFirst) strictAnywhere.add(r.id);
}
function solvabilityByMech(rows) {
  const g = groupBy(rows, 'mech');
  const out = {};
  for (const m of ORDER) {
    const rs = g[m] || [];
    out[m] = {
      n: rs.length,
      provablySolvable: rs.filter((r) => strictAnywhere.has(r.id)).length,
      neverSolvedAnywhere: rs.filter((r) => !strictAnywhere.has(r.id)).length,
      illDesigned: rs.filter((r) => r.st.liquidDepletion || r.st.heatGated || r.st.zeroBoilUp).length,
      illDesignedAndUnsolved: rs.filter((r) => !strictAnywhere.has(r.id) && (r.st.liquidDepletion || r.st.heatGated || r.st.zeroBoilUp)).length,
    };
  }
  return out;
}
// E5. Reverse conditional on the corrector's initial residual
const reverseThreshold = [0.03, 0.1, 0.3, 1, 3, 10].map((x) => {
  const withInit = prim.filter((r) => r.p.initialResidual !== null);
  const below = withInit.filter((r) => r.p.initialResidual <= x);
  const above = withInit.filter((r) => r.p.initialResidual > x);
  return {
    x,
    nBelow: below.length, strictBelow: below.filter((r) => r.mech === 'strict').length,
    pStrictGivenBelow: below.length ? below.filter((r) => r.mech === 'strict').length / below.length : null,
    nAbove: above.length, strictAbove: above.filter((r) => r.mech === 'strict').length,
    pStrictGivenAbove: above.length ? above.filter((r) => r.mech === 'strict').length / above.length : null,
  };
});
const failedBelow03 = (() => {
  const rs = prim.filter((r) => r.mech !== 'strict' && r.p.initialResidual !== null && r.p.initialResidual <= 0.3);
  return { n: rs.length, byMech: hist(rs.map((r) => r.mech)) };
})();

// E6. headroom cross-tab on the primary journal: family x demonstrated solvability
const crossTab = (() => {
  const ns = prim.filter((r) => r.mech !== 'strict');
  const slice = (pred, name) => {
    const rs = ns.filter(pred);
    return {
      name, n: rs.length,
      provablySolvable: rs.filter((r) => strictAnywhere.has(r.id)).length,
      illDesigned: rs.filter((r) => r.st.liquidDepletion || r.st.heatGated).length,
      classicalStrict: rs.filter((r) => r.strictCurrent).length,
    };
  };
  return [
    slice(() => true, 'all non-strict'),
    slice((r) => r.p.dominantFamily === 'COMPONENT_MATERIAL_BALANCE', 'CMB-dominant (any mechanism)'),
    slice((r) => r.p.dominantFamily === 'COMPONENT_MATERIAL_BALANCE' && Math.abs(r.p.dominantPhysical) < 1e-3, '  ... abs(physical) < 1e-3 mol/s'),
    slice((r) => r.p.dominantFamily === 'COMPONENT_MATERIAL_BALANCE' && Math.abs(r.p.dominantPhysical) >= 1e-3, '  ... abs(physical) >= 1e-3 mol/s'),
    slice((r) => r.p.dominantFamily === 'VAPOR_LIQUID_EQUILIBRIUM', 'VLE-dominant (any mechanism)'),
    slice((r) => r.p.dominantFamily === 'ENERGY_BALANCE', 'ENERGY-dominant (any mechanism)'),
    slice((r) => r.mech === 'b-timeout', 'wall-budget timeout (no family telemetry)'),
    slice((r) => r.mech === 'c-converged', 'converged, advisory qualification'),
  ];
})();
// The `component=` index inside the dominant-residual EquationId is NOT an index into
// input.componentBasis.componentIds: it indexes the solver's *active* (retained, non-floored)
// component basis for that request. Measured below by pairing it against the
// `dominant VLE state` event, which names the component outright, on records where both
// events refer to the same node. Offsets are always >= 0, so a local index of k guarantees a
// global index of at least k.
const componentIndexOffset = (() => {
  const gi = Object.fromEntries(COMPONENT_IDS.map((n, i) => [n, i]));
  const off = {};
  let n = 0, negatives = 0, max = 0;
  for (const rows of Object.values(journals)) {
    for (const r of rows) {
      const p = r.p;
      if (!p.vleState || p.dominantFamily !== 'VAPOR_LIQUID_EQUILIBRIUM') continue;
      if (p.vleState.node !== p.dominantNode) continue;
      const g = gi[p.vleState.component];
      if (g === undefined) continue;
      const d = g - p.dominantComponent;
      off[d] = (off[d] || 0) + 1; n++;
      if (d < 0) negatives++;
      if (d > max) max = d;
    }
  }
  return { pairs: n, offsetHistogram: off, negatives, maxObservedOffset: max };
})();
const cmbComponentClass = (() => {
  const cmb = prim.filter((r) => r.mech !== 'strict' && r.p.dominantFamily === 'COMPONENT_MATERIAL_BALANCE');
  const maxOff = componentIndexOffset.maxObservedOffset;
  return {
    n: cmb.length,
    localIndexHistogram: hist(cmb.map((r) => r.p.dominantComponent)),
    // global >= local, so local >= 7 guarantees a crude_pc* pseudo-cut
    certainlyPseudoCut: cmb.filter((r) => r.p.dominantComponent >= 7).length,
    // global <= local + maxObservedOffset, so local <= 6 - maxOff guarantees a light real component
    certainlyLightReal: cmb.filter((r) => r.p.dominantComponent >= 0 && r.p.dominantComponent <= 6 - maxOff).length,
    ambiguous: cmb.filter((r) => r.p.dominantComponent > 6 - maxOff && r.p.dominantComponent < 7).length,
    medianLocalIndex: L.stats(cmb.map((r) => r.p.dominantComponent)).median,
  };
})();

// E7. how many of the "lever (iv)" population (no correction / advisory / liquid-depletion (d))
// are nevertheless demonstrably solvable
const leverIvSolvable = prim.filter((r) => r.mech === 'b-nocorrection' || r.mech === 'c-converged'
  || (r.mech === 'd-alldirrej' && r.st.liquidDepletion)).filter((r) => strictAnywhere.has(r.id)).length;

const task7 = {
  leverIvSolvable,
  componentIndexOffset,
  crossTab,
  cmbComponentClass,
  cmbScaleEvidence: Object.fromEntries(Object.keys(journals).map((k) => [k, cmbScaleEvidence(journals[k])])),
  vleStateEvidence: Object.fromEntries(Object.keys(journals).map((k) => [k, vleStateEvidence(journals[k])])),
  costScaling: costScaling(prim),
  solvability: Object.fromEntries(Object.keys(journals).map((k) => [k, solvabilityByMech(journals[k])])),
  strictAnywhereCount: strictAnywhere.size,
  reverseThreshold,
  failedBelow03,
};

// --------------------------------------------------------------- summary ---
const summary = {
  generated: new Date().toISOString(),
  note: 'Read-only analysis of eval/*/evaluation.jsonl. No solver was run.',
  journals: L.JOURNALS,
  primary: PRIMARY,
  idMismatch,
  componentIds: COMPONENT_IDS,
  mechanismLabels: MECH_LABEL,
  task1_partition: task1,
  task1_timeoutDetail: timeoutDetail,
  task1_noCorrectionDetail: nocorrectionDetail,
  task1_convergedDetail: convergedDetail,
  task1_directionRejectedDetail: dirRejDetail,
  task2_familyProfiles: task2,
  task3_initialResidual: task3,
  task4_structure: task4,
  task5_crossModel: task5,
  task6_time: task6,
  task7_evidence: task7,
};
fs.writeFileSync(path.join(OUT, 'summary.json'), JSON.stringify(summary, null, 2));
console.log('wrote summary.json');
console.log('primary strict:', task1[PRIMARY].strict, '/', task1[PRIMARY].total);
console.log('id mismatch:', idMismatch);
