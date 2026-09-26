'use strict';
// Renders summary.md from summary.json. Run `node analyze.js` first.

const fs = require('fs');
const path = require('path');
const S = require('./summary.json');
const L = require('./lib.js');

const P = S.primary;
const out = [];
const w = (s) => out.push(s === undefined ? '' : s);
const KEYS = S.journals.map((j) => j.key);
const LABEL = Object.fromEntries(S.journals.map((j) => [j.key, j.label]));

function table(header, rows) {
  w('| ' + header.join(' | ') + ' |');
  w('|' + header.map(() => '---').join('|') + '|');
  for (const r of rows) w('| ' + r.join(' | ') + ' |');
  w();
}
const n0 = (x) => (x === null || x === undefined || !Number.isFinite(x) ? '-' : Math.round(x).toLocaleString('en-US'));
const n1 = (x) => (x === null || x === undefined || !Number.isFinite(x) ? '-' : x.toFixed(1));
const n2 = (x) => (x === null || x === undefined || !Number.isFinite(x) ? '-' : x.toFixed(2));
const pct = (x) => (x === null || x === undefined || !Number.isFinite(x) ? '-' : (100 * x).toFixed(0) + '%');
const sci = (x) => {
  if (x === null || x === undefined || !Number.isFinite(x)) return '-';
  if (x === 0) return '0';
  if (Math.abs(x) >= 1e-3 && Math.abs(x) < 1e4) return Number(x.toPrecision(3)).toString();
  return x.toExponential(1);
};
const histStr = (h, top) => {
  let e = Object.entries(h).filter(([, v]) => v);
  e.sort((a, b) => b[1] - a[1]);
  if (top) e = e.slice(0, top);
  return e.map(([k, v]) => `${k}: ${v}`).join(', ') || '-';
};

const MECHS = Object.keys(S.mechanismLabels);
const SHORT = {
  strict: 'strict success',
  'a-coverage': '(a) outside coverage / seed rejected',
  'b-timeout': '(b1) 2 s wall budget exhausted',
  'b-nocorrection': '(b2) no correction ran',
  'c-converged': '(c) converged <1e-6, not strict',
  'd-alldirrej': '(d) every direction rejected',
  'e-cmbcrawl': '(e) CMB crawl (resid 0.5-1.5)',
  'f-cmbother': '(f) other CMB-dominant',
  'g-vle': '(g) VLE-dominant',
  'h-energy': '(h) ENERGY-dominant',
  'i-other': '(i) remainder',
};
const ORDER = ['strict', ...MECHS];

// ---------------------------------------------------------------------------
w('# Why neural seeds fail: a mechanism partition of the 312-request holdout v2');
w();
w(`Generated ${S.generated.slice(0, 10)} from five comparison journals in \`research/convergence-review/eval/*/evaluation.jsonl\`. Read-only: no solver, Gradle or Java was run. Scripts: \`lib.js\` (parsing + classification), \`analyze.js\` (all statistics, writes \`summary.json\`), \`render.js\` (this document).`);
w();
w('**Vocabulary.** *Strict success* = the route returned `success` **and** a `waterQualification` of `DRY_EQUILIBRIUM` or `WET_EQUILIBRIUM`; anything else accepted is an advisory. *Neural-only* = predict a full column state with the Transformer, then run the direct Newton corrector from that seed (16 base iterations, extendable to 48 while the residual keeps falling, 2 s wall budget). *Classical* = the cold route from scratch (30 s offline deadline in this harness). *Neural-first* = neural-only, then the classical route if the seed fails.');
w();
w('All five journals cover exactly the same 312 request ids (verified, no id mismatch).');
w();

// ------------------------------------------------------------------ lead ---
w('## 1. The partition (primary journal G-17023 epoch 160)');
w();
{
  const t = S.task1_partition[P];
  const sol = S.task7_evidence.solvability[P];
  const rows = [];
  for (const m of ORDER) {
    const r = t.table.find((x) => x.mech === m);
    if (!r || !r.n) continue;
    rows.push([
      SHORT[m], r.n, m === 'strict' ? '-' : pct(r.shareOfNonStrict),
      n0(r.meanMs), n0(r.medianMs), n1(r.meanIterations),
      r.classicalStrict, r.neuralFirstStrict, r.otherModelStrict,
      sol[m].provablySolvable,
      r.liquidDepletion, r.zeroBoilUp, r.heatGated,
    ]);
  }
  table(['mechanism', 'n', '% of non-strict', 'mean ms', 'median ms', 'mean Newton it.', 'classical strict', 'neural-first strict', 'strict for >=1 other model', 'strict answer demonstrated anywhere', 'liquid-depletion', 'zero-boil-up', 'heat-gated'], rows);
  w(`Totals: 312 requests, ${t.strict} strict neural-only successes (${pct(t.strict / t.total)}), ${t.nonStrict} non-strict. The classical route reaches ${S.task6_time.classicalStrictTotal} strict; neural-first reaches ${S.task6_time.neuralFirstStrictTotal}.`);
  w();
  w('Column meanings: *classical strict* / *neural-first strict* = how many requests in that mechanism the other two routes solve strictly in this same journal. *strict for >=1 other model* = solved strictly by the neural-only route of at least one of the other four models. *strict answer demonstrated anywhere* = some route (classical, or any of the five models\' neural-only or neural-first) produced a strict result for that id, i.e. the request is provably answerable and the failure is a real miss rather than an ill-posed request. *liquid-depletion* = max side-draw withdrawal fraction >= 0.8. *zero-boil-up* = no steam, reboiler duty exactly 0, feed above the bottom (none occur in holdout v2). *heat-gated* = some route reported a condensation cap / "not below the" failure.');
  w();
  w('Deviation from the requested ladder, stated explicitly: step (b) ("no iterations / no `scaled residual` event") turned out to hold two physically different populations, so it is reported split. **(b1)** are runs killed by the 2 s wall budget: their event list is truncated before the residual line is emitted, but the failure text carries `iterations=a/b`, and they did iterate (median 10). **(b2)** are the three runs where the corrector genuinely never started. Everything else follows the requested priority order exactly.');
}
w();

// ------------------------------------------------------------ conclusion ---
w('## 2. Conclusion');
w();
{
  const ct = S.task7_evidence.crossTab;
  const t = S.task1_partition[P];
  w(`**The ceiling first.** Of the ${t.nonStrict} non-strict neural-only outcomes in the primary journal, only **${ct[0].provablySolvable}** are on requests where *any* route in *any* of the five journals ever produced a strict answer. The other ${ct[0].n - ct[0].provablySolvable} requests were never solved strictly by anything, including the classical route with its 30 s deadline. So the realistic headroom for a seed pre-step on this holdout is about ${ct[0].provablySolvable} requests (118 -> ~166), not 194.`);
  w();
  const rows = ct.map((c) => [c.name, c.n, c.provablySolvable, c.classicalStrict, c.illDesigned]);
  table(['slice of the 194 non-strict', 'n', 'strict answer demonstrated anywhere', 'classical strict (this journal)', 'flagged ill-designed (liquid-depletion or heat-gated)'], rows);

  const sol = S.task7_evidence.solvability[P];
  const tt = S.task1_partition[P];
  const nOf = (m) => (tt.table.find((x) => x.mech === m) || { n: 0 }).n;
  w('Which lever addresses which mechanism, and the headroom each one can actually reach:');
  w();
  table(['lever', 'mechanisms it addresses', 'failures in those mechanisms', 'of which a strict answer is demonstrated somewhere'], [
    ['(i) material-balance projection of the seed', '(e), (f), and the CMB-dominant half of (d)', nOf('e-cmbcrawl') + nOf('f-cmbother') + S.task1_directionRejectedDetail[P].dominantFamily.COMPONENT_MATERIAL_BALANCE, '<= ' + ct[1].provablySolvable + ' (all CMB-dominant failures)'],
    ['(ii) larger / iteration-scaled budget', '(b1)', nOf('b-timeout'), sol['b-timeout'].provablySolvable],
    ['(iii) better training data (phase presence)', '(g), and the VLE-dominant part of (d)', nOf('g-vle') + S.task1_directionRejectedDetail[P].dominantFamily.VAPOR_LIQUID_EQUILIBRIUM, '<= ' + ct[4].provablySolvable + ' (all VLE-dominant failures)'],
    ['(iv) nothing / fix the request or the water model', '(b2), (c), the liquid-depletion part of (d)', nOf('b-nocorrection') + nOf('c-converged') + tt.table.find((x) => x.mech === 'd-alldirrej').liquidDepletion, S.task7_evidence.leverIvSolvable],
  ]);
  w('The four rows overlap where a mechanism has mixed causes, so they do not sum to 194; the binding constraint is the 48-request solvability ceiling in the table above.');
  w();

  w('### (i) A material-balance projection of the seed before Newton');
  w();
  const cmb = S.task7_evidence.cmbScaleEvidence[P];
  w(`**Plausible, and this is where the headroom is.** COMPONENT_MATERIAL_BALANCE is the dominant residual family in ${cmb.n} of the ${t.nonStrict} failures (${pct(cmb.n / t.nonStrict)}), carrying ${ct[1].provablySolvable} of the ${ct[0].provablySolvable} demonstrably-solvable misses. Three facts say the defect is a component-balance defect on trace species, which is exactly what a Wang-Henke style tridiagonal component-balance solve at frozen temperature repairs:`);
  w();
  w(`1. The dominant CMB residual is physically negligible in ${cmb.physicalBands['<1e-9 mol/s'] + cmb.physicalBands['1e-9..1e-6'] + cmb.physicalBands['1e-6..1e-3']} of ${cmb.n} cases (abs(physical) < 1e-3 mol/s; median ${sci(cmb.physicalAbs.median)} mol/s) while the *scaled* residual sits near 1. ${cmb.scaledAtLeastHalfButPhysicalBelow1e6} cases have scaled residual >= 0.5 with abs(physical) < 1e-6 mol/s.`);
  w(`2. ${cmb.finalResidualWithin1pctOfOne} of ${cmb.n} end within 1 % of a scaled residual of exactly 1.0 (${cmb.finalResidualWithin01pctOfOne} within 0.1 %), the signature of a balance whose entire flow term is missing, i.e. a component sitting on the trace floor where the seed should have put a small positive flow.`);
  w(`3. At least ${S.task7_evidence.cmbComponentClass.certainlyPseudoCut} of the ${S.task7_evidence.cmbComponentClass.n} dominant components are \`crude_pc*\` pseudo-cuts rather than one of the seven light real components; only ${S.task7_evidence.cmbComponentClass.certainlyLightReal} are certainly a light real component (see the index caveat in section 6).`);
  w();
  w(`A projection would also attack mechanism (d): ${S.task1_directionRejectedDetail[P].dominantFamily.COMPONENT_MATERIAL_BALANCE} of the ${S.task1_directionRejectedDetail[P].n} "every direction rejected" cases are CMB-dominant, and a rejected local-block direction on a trace component is precisely a direction that a linear balance solve would have supplied for free. The strongest quantitative support is in section 7: only ${S.task3_initialResidual.thresholds.find((x) => x.x === 1).strictAboveX_corrector} of ${S.task3_initialResidual.thresholds.find((x) => x.x === 1).strictWithInit} strict successes start the corrector above a scaled residual of 1, and P(strict given initial residual <= 0.3) = ${pct(S.task7_evidence.reverseThreshold.find((x) => x.x === 0.3).pStrictGivenBelow)} versus ${pct(S.task7_evidence.reverseThreshold.find((x) => x.x === 0.3).pStrictGivenAbove)} above it. Lowering the corrector's *starting* residual is the single most discriminating thing you can do to the seed.`);
  w();
  w('### (ii) A larger / iteration-scaled budget');
  w();
  const td = S.task1_timeoutDetail[P];
  const cs = S.task7_evidence.costScaling;
  w(`**Plausible but small, and cheap to test.** ${td.n} requests (${pct(td.n / t.nonStrict)} of failures, and ${pct(S.task6_time.perMech['b-timeout'].shareOfTotal)} of all neural-only wall time) died on the 2 s wall clock, not on a numerical obstruction. ${td.convergedButTimedOut} of them had already reached a residual below 1e-6 and ${td.residualBands['<1e-6'] + td.residualBands['1e-6..1e-3']} in total were below 1e-3 - they were converged or nearly so and were cut off before the acceptance audit. Only ${ct[6].provablySolvable} of the ${td.n} are on requests with a demonstrated strict answer, so the expected gain is roughly +4 on this holdout, but the fix is a budget number.`);
  w();
  w(`The budget bites entirely at large N: timeout share is 0 % below 20 stages, ${pct(cs['20-34'].timeoutShare)} at 20-34, ${pct(cs['35-49'].timeoutShare)} at 35-49 and ${pct(cs['50-64'].timeoutShare)} at 50-64 (median stage count of a timeout: ${n0(td.stageCount.median)}). Answering the question posed in the brief: it is **not** mainly preparation cost. Transformer inference is ${n1(td.rawPredictionMs.median)} ms at the median even for these columns. What scales is the corrector: median wall time per Newton iteration rises ${n0(cs['2-9'].strictMsPerIterationMedian)} ms (N 2-9) -> ${n0(cs['50-64'].strictMsPerIterationMedian)} ms (N 50-64), so a 2 s budget buys ~80 iterations on a small column and ~9 on a 60-stage one. Fixed set-up is real but secondary: strict runs that needed <= 1 Newton iteration still cost a median ${n0(cs['50-64'].fixedOverheadMsMedian)} ms at N 50-64 versus ${n0(cs['2-9'].fixedOverheadMsMedian)} ms at N 2-9. A budget expressed in iterations, or scaled by N, would recover the truncated runs; a flat 2 s will keep truncating tall columns.`);
  w();
  w('### (iii) Better training data');
  w();
  const vle = S.task7_evidence.vleStateEvidence[P];
  w(`**Plausible for the VLE family, but the payoff on this holdout is small.** ${ct[4].n} failures are VAPOR_LIQUID_EQUILIBRIUM-dominant and they look qualitatively different from the CMB family: the residual barely moves (median final/initial ratio ${n2(S.task2_familyProfiles[P].VAPOR_LIQUID_EQUILIBRIUM.ratioFinalOverInitial.median)}, ${S.task2_familyProfiles[P].VAPOR_LIQUID_EQUILIBRIUM.ratioBands['0.99..1.0 (flat)']} of ${S.task2_familyProfiles[P].VAPOR_LIQUID_EQUILIBRIUM.n} are flat to within 1 %), the physical residual is large (median ${sci(S.task2_familyProfiles[P].VAPOR_LIQUID_EQUILIBRIUM.dominantPhysicalAbs.median)}), and the \`dominant VLE state\` telemetry shows a collapsed phase pair: ${vle.bothFlowsBelow1e3} of ${vle.n} have *both* the liquid and the vapour flow of the offending component below 1e-3 mol/s, with vapour flows as low as 1e-84. That is a phase-presence error in the seed (a component placed in one phase that the equilibrium says must be in the other), and a fixed-temperature material balance cannot repair it - it needs either a flash/K-value correction step or a seed that predicts the phase split better. But only ${ct[4].provablySolvable} of these ${ct[4].n} requests have a demonstrated strict answer, and ${ct[4].illDesigned} are flagged ill-designed, so most of this family is not lost accuracy.`);
  w();
  w(`A separate training-data signal is model-to-model variance (section 9): ${S.task5_crossModel.some} ids are strict for some models and not others, and on those ids the failing models most often show ${histStr(S.task5_crossModel.mechOnSome, 3)}. Those mechanisms are seed-quality-limited, not solver-limited.`);
  w();
  w('### (iv) Nothing (ill-designed or ill-posed requests)');
  w();
  const cd = S.task1_convergedDetail[P];
  w(`**The largest single block.** ${ct[0].n - ct[0].provablySolvable} of the ${ct[0].n} failures were never solved strictly by any route in any journal; ${ct[0].illDesigned} of the ${ct[0].n} are flagged by the structural heuristics (liquid-depletion ${t.table.filter((x) => x.mech !== 'strict').reduce((a, x) => a + (x.liquidDepletion || 0), 0)}, heat-gated ${t.table.filter((x) => x.mech !== 'strict').reduce((a, x) => a + (x.heatGated || 0), 0)}; zero-boil-up designs have been eliminated from holdout v2 - 0 occurrences). Mechanism (d) is the worst offender: ${S.task1_partition[P].table.find((x) => x.mech === 'd-alldirrej').liquidDepletion} of its ${ct[6] && S.task1_partition[P].table.find((x) => x.mech === 'd-alldirrej').n} cases are liquid-depletion designs, where every local-block direction is rejected because there is no downflow left to perturb.`);
  w();
  w(`Mechanism (c) deserves its own note: **it is not a numerical failure at all.** All ${cd.n} cases reached a residual below 1e-6; ${cd.accepted} were *accepted* by the solver with every audit check passing, and were downgraded only because the qualification came back \`DRY_SUPERSATURATED\` - a tray below the water dew point with no free-water phase, after a "free-water continuation declined" event fired in ${cd.freeWaterDeclined} of ${cd.n} cases. ${S.task7_evidence.solvability[P]['c-converged'].neverSolvedAnywhere} of them are never strict on any route and 14 of the 19 are \`DRY_SUPERSATURATED\` on the classical route too, so the request itself has no dry equilibrium. This is the known open three-phase modelling decision, not a seed defect; a material-balance projection cannot help, and counting these as neural failures understates the model by ${cd.accepted} requests, ~${pct(cd.accepted / t.total)} of the holdout.`);
  w();
  w('### What could not be determined from these journals');
  w();
  w('- **Whether a projection would actually converge.** The journals record residuals, not states. There is no per-node component flow dump for the failing seeds, so I cannot simulate the projection or measure how far the seed is from the stage material balances; I can only show that the residual that dominates is a component balance and is physically tiny. A probe that logs the seed state and the post-projection residual is the missing experiment.');
  w('- **Whether the trace floor is the cause or a symptom.** Failing runs retain a slightly smaller share of the floor support than strict ones (median ' + n2(S.task4_structure[P]['e-cmbcrawl'].floorSupportRetainedFraction.median) + ' for (e) versus ' + n2(S.task4_structure[P].strict.floorSupportRetainedFraction.median) + ' for strict successes), but the direction of causation is not observable here.');
  w('- **The condenser branch for most failures.** `seed.branch` is only written on runs that produced a state, so it is missing for ' + S.task4_structure[P]['d-alldirrej'].branchUnknown + ' of ' + S.task4_structure[P]['d-alldirrej'].n + ' of mechanism (d) and ' + S.task4_structure[P]['b-timeout'].branchUnknown + ' of ' + S.task4_structure[P]['b-timeout'].n + ' of the timeouts. The branch split in section 8 is therefore reported only where observed.');
  w('- **Whether the never-solved 146 are genuinely infeasible.** "No route ever solved it strictly" is evidence, not proof; the classical route itself hit its 30 s deadline on ' + S.task6_time.classicalDeadline + ' requests, and 65 of the 146 carry no ill-design flag.');
  w('- **Component identity for the CMB family.** The dominant-residual event reports a local index into the solver\'s active component basis, not a global one (measured in section 6). I can bound it - at least ' + S.task7_evidence.cmbComponentClass.certainlyPseudoCut + ' of ' + S.task7_evidence.cmbComponentClass.n + ' are `crude_pc*` pseudo-cuts - but I cannot name the exact cut without a journal that records the active basis per request.');
  w('- **Time measurements are single-run and unpinned.** Journals were produced by separate benchmark runs, so ms figures carry machine and scheduling noise; the wall-budget conclusions rest on the 2000-2015 ms clustering, which is unambiguous, not on fine timing.');
}
w();

// --------------------------------------------------------- mechanism defs ---
w('## 3. Mechanism definitions (applied in this priority order)');
w();
table(['mechanism', 'rule applied to the neural-only record'], [
  ['(a) outside coverage / seed rejected', '`model unavailable or outside coverage` or `neural seed rejected:` in events, or `rawPrediction.supported == false`'],
  ['(b1) wall budget exhausted', 'no `scaled residual:` event **and** a `neural budget exhausted; attempts=..., iterations=a/b, residual=...` event'],
  ['(b2) no correction ran', 'no `scaled residual:` event and no budget event (in practice: `Stage-trace support fell back to identity`)'],
  ['(c) converged, not strict', 'final scaled residual < 1e-6 and not a strict success'],
  ['(d) every direction rejected', '`local-block directions: accepted=0, rejected=b` with b > 0'],
  ['(e) CMB crawl', 'dominant family COMPONENT_MATERIAL_BALANCE, final residual in [0.5, 1.5], accepted > 0'],
  ['(f) other CMB-dominant', 'dominant family COMPONENT_MATERIAL_BALANCE, otherwise'],
  ['(g) VLE-dominant', 'dominant family VAPOR_LIQUID_EQUILIBRIUM'],
  ['(h) ENERGY-dominant', 'dominant family ENERGY_BALANCE'],
  ['(i) remainder', 'anything left (empty on all five journals)'],
]);

// ------------------------------------------------------- all five journals ---
w('## 4. The same partition across all five models');
w();
{
  const rows = [];
  for (const m of ORDER) {
    const r = [SHORT[m]];
    for (const k of KEYS) {
      const e = S.task1_partition[k].table.find((x) => x.mech === m);
      r.push(e && e.n ? e.n : 0);
    }
    rows.push(r);
  }
  table(['mechanism', ...KEYS.map((k) => LABEL[k])], rows);
  w('The retrained models are close to each other and well ahead of the shipped one (' + KEYS.map((k) => `${k}: ${S.task5_crossModel.perModelStrict[k]}`).join(', ') + ' strict of 312). The packaged model loses ground in exactly the mechanisms the retrained models improve: more timeouts, more CMB crawl, more VLE.');
  w();
}

// ---------------------------------------------- mechanism detail sections ---
w('## 5. Mechanism detail');
w();
w('### 5.1 (b1) The 2 s wall budget');
w();
{
  const rows = KEYS.map((k) => {
    const d = S.task1_timeoutDetail[k];
    return [LABEL[k], d.n, n0(d.stageCount.median), n0(d.neuralMs.median), n1(d.rawPredictionMs.median),
      n0(d.iterationsDone.median), n0(d.iterationCap.median), sci(d.residualReached.median), d.convergedButTimedOut,
      histStr(d.residualBands)];
  });
  table(['journal', 'n', 'median N', 'median neural ms', 'median inference ms', 'median iterations done', 'median iteration cap', 'median residual reached', 'reached <1e-6', 'residual bands'], rows);
  w('Inference itself is ~10-15 ms. The wall clock is consumed by the corrector, and the corrector rarely reaches its own iteration cap (median ' + n0(S.task1_timeoutDetail[P].iterationsDone.median) + ' of ' + n0(S.task1_timeoutDetail[P].iterationCap.median) + ' allowed) before the wall fires.');
  w();
  const cs = S.task7_evidence.costScaling;
  table(['stage band', 'requests', 'median inference ms', 'median ms per Newton iteration (strict runs)', 'median ms of strict runs needing <=1 iteration', 'timeouts', 'timeout share'],
    L.STAGE_BANDS.map((b) => [b, cs[b].n, n1(cs[b].rawPredictionMs), n0(cs[b].strictMsPerIterationMedian), n0(cs[b].fixedOverheadMsMedian), cs[b].timeoutN, pct(cs[b].timeoutShare)]));
}

w('### 5.2 (b2) No correction ran');
w();
{
  const rows = KEYS.map((k) => {
    const d = S.task1_noCorrectionDetail[k];
    return [LABEL[k], d.n, d.stageCounts.join(', ') || '-', d.neuralMs.join(', ') || '-', d.traceFallback];
  });
  table(['journal', 'n', 'stage counts', 'neural ms', 'of which "stage-trace support fell back to identity"'], rows);
  w('Every one of these is the same structural event - feed reachability emptied a structural phase, so the stage-trace support degenerated to the identity and the corrector never started - and in the primary journal every one is a tall column (N 37-62) with a heavy side draw; across the five journals the range is N 20-62. All are liquid-depletion or heat-gated designs and none is solvable by any route.');
  w();
}

w('### 5.3 (c) Converged but not strict');
w();
{
  const rows = KEYS.map((k) => {
    const d = S.task1_convergedDetail[k];
    return [LABEL[k], d.n, d.accepted, d.rejected, histStr(d.qualifications), histStr(d.failedAuditFamilies), d.freeWaterDeclined];
  });
  table(['journal', 'n', 'accepted (advisory)', 'rejected', 'qualification', 'failing audit families', '"free-water continuation declined"'], rows);
  w('`(all audit checks passed)` means the run was accepted with every acceptance-audit family passing, including `WATER_DEW_POINT`, which is emitted as a warning rather than a rejection by design. The only genuine audit rejections in this bucket are `CONDENSER_PHASE` (1-2 per journal) and one `SIDE_DRAW_SPLIT` in the packaged journal.');
  w();
}

w('### 5.4 (d) Every local-block direction rejected');
w();
{
  const rows = KEYS.map((k) => {
    const d = S.task1_directionRejectedDetail[k];
    return [LABEL[k], d.n, histStr(d.dominantFamily), n0(d.rejectedDirections.median), n2(d.ratioStats.median), histStr(d.finalResidualBands), d.armijo];
  });
  table(['journal', 'n', 'dominant family', 'median rejected directions', 'median final/initial residual', 'final residual bands', '"no admissible Armijo step"'], rows);
  w('This is the single largest failure bucket in every journal. It is not a stalled residual - the median run still cuts its residual roughly in half - it is a run where the line search accepts nothing and the residual plateaus above 1. Half of these are CMB-dominant, which is why a material-balance pre-step is also relevant here.');
  w();
}

// ------------------------------------------------------------ task 2 ---
w('## 6. Where the dominant residual sits (task 2)');
w();
w('Applied to every non-strict record with a dominant-residual event, grouped by dominant family regardless of which mechanism bucket it landed in.');
w();
{
  for (const fam of ['COMPONENT_MATERIAL_BALANCE', 'VAPOR_LIQUID_EQUILIBRIUM', 'ENERGY_BALANCE']) {
    const f = S.task2_familyProfiles[P][fam];
    w(`**${fam}** (primary journal, n = ${f.n}; by mechanism: ${histStr(f.byMechanism)})`);
    w();
    const keys = ['condenser', 'rectifying (above feed)', 'at feed', 'stripping (below feed)', 'reboiler'];
    table(['node class', 'observed', 'expected if the node were uniform', 'enrichment'],
      keys.map((k) => [k, f.nodeClass[k] || 0, n1(f.nodeClassExpectedUniform[k]), n2(f.nodeClassEnrichment[k])]));
  }
  w('**Read the node histogram against the null, not raw.** These designs put the feed at a median 89 % of column height (p10 0.75, p90 0.97 of N+1), so "above the feed" already covers most nodes by construction. Against a uniform-node null the real signals are: CMB is 2.3x enriched at the **reboiler** and 3.5x depleted at the condenser; VLE is 2.4x enriched in the **stripping section** and mildly enriched at the condenser, feed and reboiler; ENERGY is enriched at the feed tray and below it. The raw "78 % above feed" reading for CMB is an artifact.');
  w();
  {
    const off = S.task7_evidence.componentIndexOffset;
    w('**Caveat on component identity, measured not assumed.** The `component=` field inside `dominant residual: EquationId[...]` is *not* an index into `input.componentBasis.componentIds`; it indexes the solver\'s active (retained, non-floored) component basis for that request. Pairing it against the `dominant VLE state` event, which names the component outright, on the ' + off.pairs + ' records across all five journals where both events refer to the same node, gives a global-minus-local offset of ' + histStr(off.offsetHistogram) + ' - always non-negative, never larger than ' + off.maxObservedOffset + '. So a local index of k guarantees a global index of at least k, and the two-sided bound global <= local + ' + off.maxObservedOffset + ' holds empirically. Component names below are therefore quoted only where the `dominant VLE state` event supplies them; for CMB only the bounded classification is reported.');
    w();
    const cc = S.task7_evidence.cmbComponentClass;
    const vs = S.task7_evidence.vleStateEvidence[P];
    const vleTop = Object.entries(vs.componentHist).sort((a, b) => b[1] - a[1]).slice(0, 8).map(([k, v]) => `${k} (${v})`).join(', ');
    table(['family', 'n', 'dominant component'], [
      ['COMPONENT_MATERIAL_BALANCE', cc.n, `median local index ${n0(cc.medianLocalIndex)} of a 19-component slate; certainly a crude_pc* pseudo-cut in ${cc.certainlyPseudoCut}, certainly a light real component in ${cc.certainlyLightReal}, ambiguous in ${cc.ambiguous}`],
      ['VAPOR_LIQUID_EQUILIBRIUM', vs.n, `named by the VLE-state event: ${vleTop}`],
      ['ENERGY_BALANCE', S.task2_familyProfiles[P].ENERGY_BALANCE.n, 'component = -1 (no component) in all cases'],
    ]);
    w('CMB local-index histogram (index: count): ' + histStr(cc.localIndexHistogram) + '.');
    w();
    w('The CMB defect therefore sits deep in the pseudo-cut range - species that carry little flow and are the first to be floored. VLE leads with the heaviest cut (crude_pc12, ' + vs.componentHist.crude_pc12 + ' of ' + vs.n + ') and then spreads across the mid cuts and Isopentane, with a tail on Propane/Ethane: both ends of the slate, where one phase can vanish entirely.');
    w();
  }
  w('Does the residual fall at all? (final / initial scaled residual)');
  w();
  {
    const rows = [];
    for (const fam of ['COMPONENT_MATERIAL_BALANCE', 'VAPOR_LIQUID_EQUILIBRIUM', 'ENERGY_BALANCE']) {
      const f = S.task2_familyProfiles[P][fam];
      rows.push([fam, f.n,
        [f.initialResidual.p10, f.initialResidual.median, f.initialResidual.p90].map(sci).join(' / '),
        [f.finalResidual.p10, f.finalResidual.median, f.finalResidual.p90].map(sci).join(' / '),
        [f.ratioFinalOverInitial.p10, f.ratioFinalOverInitial.median, f.ratioFinalOverInitial.p90].map(sci).join(' / '),
        histStr(f.ratioBands)]);
    }
    table(['family', 'n', 'initial residual p10/p50/p90', 'final residual p10/p50/p90', 'ratio p10/p50/p90', 'ratio bands'], rows);
    w('CMB failures **do** make progress (median ratio ' + n2(S.task2_familyProfiles[P].COMPONENT_MATERIAL_BALANCE.ratioFinalOverInitial.median) + ') and then park just under 1 - a crawl. VLE failures barely move (median ratio ' + n2(S.task2_familyProfiles[P].VAPOR_LIQUID_EQUILIBRIUM.ratioFinalOverInitial.median) + ', ' + S.task2_familyProfiles[P].VAPOR_LIQUID_EQUILIBRIUM.ratioBands['0.99..1.0 (flat)'] + ' of ' + S.task2_familyProfiles[P].VAPOR_LIQUID_EQUILIBRIUM.n + ' flat to within 1 %) - a block, not a crawl.');
    w();
  }
  w('Physical magnitude of the dominant residual:');
  w();
  {
    const c = S.task7_evidence.cmbScaleEvidence[P];
    const v = S.task7_evidence.vleStateEvidence[P];
    table(['measure', 'value'], [
      ['CMB abs(physical) median', sci(c.physicalAbs.median) + ' mol/s'],
      ['CMB abs(physical) bands', histStr(c.physicalBands)],
      ['CMB with scaled >= 0.5 but abs(physical) < 1e-6', c.scaledAtLeastHalfButPhysicalBelow1e6 + ' of ' + c.n],
      ['CMB final residual within 1 % of exactly 1.0', c.finalResidualWithin1pctOfOne + ' of ' + c.n],
      ['VLE dominant-state records', v.n],
      ['VLE vapour-flow bands', histStr(v.vaporFlowBands)],
      ['VLE with both phase flows < 1e-3 mol/s', v.bothFlowsBelow1e3 + ' of ' + v.n],
      ['VLE with either phase flow < 1e-3 mol/s', v.eitherFlowBelow1e3 + ' of ' + v.n],
      ['VLE dominant-state temperature median', n0(v.temperature.median) + ' K'],
    ]);
  }
}

// ------------------------------------------------------------ task 3 ---
w('## 7. Does the initial residual predict the outcome? (task 3, primary journal)');
w();
{
  const d = S.task3_initialResidual.dist;
  const rows = [];
  for (const m of ORDER) {
    if (!d[m] || !d[m].n) continue;
    rows.push([SHORT[m], d[m].n,
      [d[m].correctorInitial.p10, d[m].correctorInitial.median, d[m].correctorInitial.p90].map(sci).join(' / '),
      [d[m].seedNativeResidual.p10, d[m].seedNativeResidual.median, d[m].seedNativeResidual.p90].map(sci).join(' / ')]);
  }
  table(['mechanism', 'n', "corrector's initial scaled residual p10/p50/p90", 'raw seed residual (rawPrediction.nativeResidual) p10/p50/p90'], rows);
  w(`**Two different residuals, two different answers.** The *raw* neural seed residual is ~6 for everything and carries almost no information: the probability that a random strict success has a lower raw seed residual than a random failure is ${n2(S.task3_initialResidual.aucSeedResidual)} (0.5 = coin flip). The residual the *corrector actually starts from*, after the solver's own seed preparation (floor support, native trace-support projection), is highly discriminating: ${n2(S.task3_initialResidual.aucCorrectorInitial)}. The solver already performs a repair step that moves the seed from ~6 to ~0.005 when it works - and failures are precisely the cases where that repair leaves the residual at O(1) or above.`);
  w();
  const th = S.task3_initialResidual.thresholds;
  table(['x', 'strict successes with initial residual > x', 'failures with initial residual > x', 'of all records above x, share that fail'],
    th.map((t) => [t.x, `${t.strictAboveX_corrector} / ${t.strictWithInit}`, `${t.failAboveX_corrector} / ${t.failWithInit}`, pct(t.precisionFailAboveX)]));
  const rt = S.task7_evidence.reverseThreshold;
  table(['x', 'records with initial residual <= x', 'of which strict', 'P(strict given <= x)', 'records above x', 'of which strict', 'P(strict given > x)'],
    rt.map((t) => [t.x, t.nBelow, t.strictBelow, pct(t.pStrictGivenBelow), t.nAbove, t.strictAbove, pct(t.pStrictGivenAbove)]));
  w(`Only ${S.task7_evidence.failedBelow03.n} of the 146 records that start at or below 0.3 go on to fail, and their composition is telling: ${histStr(S.task7_evidence.failedBelow03.byMech)}. In other words, once the corrector starts below ~0.3 the only remaining failure modes are the advisory-qualification block (c) and a handful of stubborn cases - the numerical route is essentially solved. A further 32 records (the 29 timeouts and the 3 no-correction runs) carry no initial-residual telemetry at all and are excluded from both tables above.`);
  w();
}

// ------------------------------------------------------------ task 4 ---
w('## 8. Structure profile per mechanism (task 4, primary journal)');
w();
{
  const s = S.task4_structure[P];
  const rows = [];
  for (const m of ORDER) {
    if (!s[m] || !s[m].n) continue;
    rows.push([SHORT[m], s[m].n, n0(s[m].medianStageCount),
      L.STAGE_BANDS.map((b) => s[m].stageBands[b]).join(' / '),
      `${s[m].steamOn} / ${s[m].n}`,
      n2(s[m].meanDraws), n2(s[m].meanPumparounds),
      n2(s[m].medianWithdrawal),
      (s[m].branch.LIQUID_ONLY || 0) + ' / ' + (s[m].branch.TWO_PHASE || 0) + ' / ' + s[m].branchUnknown]);
  }
  table(['mechanism', 'n', 'median N', 'stage bands 2-9 / 10-19 / 20-34 / 35-49 / 50-64', 'steam on', 'mean draws', 'mean pumparounds', 'median side-draw withdrawal', 'condenser branch LIQUID_ONLY / TWO_PHASE / unknown'], rows);
  w('Reading: strict successes are spread across all stage bands; the timeouts and the no-correction cases are exclusively tall columns; (c) clusters at N 35-49 with steam on in ' + S.task4_structure[P]['c-converged'].steamOn + ' of ' + S.task4_structure[P]['c-converged'].n + ' (it is a steam-stripping/water artefact); (d) is the only bucket with a markedly elevated side-draw withdrawal. The condenser branch is unrecorded for most failures because `seed.branch` is only written when a state is produced - where it is visible, the split tracks the population and shows no branch effect.');
  w();
}

// ------------------------------------------------------------ task 5 ---
w('## 9. Cross-model stability (task 5)');
w();
{
  const c = S.task5_crossModel;
  table(['models solving the id strictly (neural-only)', 'ids'],
    Object.entries(c.countHist).sort((a, b) => +a[0] - +b[0]).map(([k, v]) => [k + ' of 5', v]));
  w(`${c.allFive} ids are strict under all five models, ${c.none} under none, ${c.some} under some. Per-model strict counts: ${KEYS.map((k) => `${k} ${c.perModelStrict[k]}`).join(', ')}. The union over the five neural-only routes is ${c.neuralOnlyUnion} ids, against ${S.task6_time.classicalStrictTotal} for the classical route - model choice buys more than the classical fallback does.`);
  w();
  table(['population', 'mechanisms shown by the models that fail (counts over model x id)'], [
    ['the ' + c.some + ' "some models solve it" ids', histStr(c.mechOnSome)],
    ['the ' + c.none + ' "no model solves it" ids', histStr(c.mechOnNone)],
    ['same, restricted to the primary model on the "some" ids', histStr(c.primMechOnSome)],
  ]);
  w(`**The contested ids fail differently from the hopeless ones.** On ids that some model solves, the failing models are dominated by CMB crawl (${c.mechOnSome['e-cmbcrawl']}) - the mechanism a projection targets. On ids no model solves, the dominant mechanism is "every direction rejected" (${c.mechOnNone['d-alldirrej']}), and the population is structurally sick: only ${c.noneProfile.classicalStrict} of the ${c.none} are strict on the classical route, ${c.noneProfile.liquidDepletion} are liquid-depletion designs and ${c.noneProfile.heatGated} are heat-gated. The universally-solved ids are short columns (mean N ${n1(c.allFiveProfile.meanStageCount)}) versus mean N ${n1(c.noneProfile.meanStageCount)} for the never-solved.`);
  w();
}

// ------------------------------------------------------------ task 6 ---
w('## 10. Time accounting (task 6, primary journal)');
w();
{
  const t = S.task6_time;
  const rows = [];
  for (const m of ORDER) {
    const v = t.perMech[m];
    if (!v || !v.n) continue;
    rows.push([SHORT[m], v.n, n0(v.totalMs), pct(v.shareOfTotal), n0(v.meanMs), n0(v.medianMs), n0(v.p90Ms)]);
  }
  rows.push(['**total**', 312, n0(t.totalNeuralOnlyMs), '100%', n0(t.totalNeuralOnlyMs / 312), '-', '-']);
  table(['mechanism', 'n', 'total ms', 'share of neural-only wall time', 'mean ms', 'median ms', 'p90 ms'], rows);
  w(`Strict successes consume ${pct(t.strictTotalMs / t.totalNeuralOnlyMs)} of neural-only wall time (${n0(t.strictTotalMs)} ms of ${n0(t.totalNeuralOnlyMs)} ms); ${n0(t.wastedMs)} ms is spent failing. The two most expensive failure buckets are (d) at ${pct(t.perMech['d-alldirrej'].shareOfTotal)} and the wall-budget timeouts at ${pct(t.perMech['b-timeout'].shareOfTotal)}. A cheap pre-filter that predicted (d) would return about a third of the neural-only budget.`);
  w();
  w('Neural-first rescue cost (the ' + t.rescuedN + ' requests where the seed failed and the classical route then succeeded strictly; all ' + t.rescuedAlsoClassicalStrict + ' of them are also strict for classical-alone):');
  w();
  table(['route on those ' + t.rescuedN + ' requests', 'mean ms', 'median ms', 'p10', 'p90', 'total ms'], [
    ['neural-only attempt (wasted)', n0(t.neuralOnlyMsOnRescued.mean), n0(t.neuralOnlyMsOnRescued.median), n0(t.neuralOnlyMsOnRescued.p10), n0(t.neuralOnlyMsOnRescued.p90), n0(t.neuralOnlyMsOnRescued.sum)],
    ['classical alone', n0(t.classicalMsOnRescued.mean), n0(t.classicalMsOnRescued.median), n0(t.classicalMsOnRescued.p10), n0(t.classicalMsOnRescued.p90), n0(t.classicalMsOnRescued.sum)],
    ['neural-first (seed + classical)', n0(t.neuralFirstMsOnRescued.mean), n0(t.neuralFirstMsOnRescued.median), n0(t.neuralFirstMsOnRescued.p10), n0(t.neuralFirstMsOnRescued.p90), n0(t.neuralFirstMsOnRescued.sum)],
    ['overhead (neural-first minus classical)', n0(t.neuralFirstOverheadMedianMs.mean), n0(t.neuralFirstOverheadMedianMs.median), n0(t.neuralFirstOverheadMedianMs.p10), n0(t.neuralFirstOverheadMedianMs.p90), n0(t.neuralFirstOverheadMedianMs.sum)],
  ]);
  w(`A failed seed costs a median ${n0(t.neuralFirstOverheadMedianMs.median)} ms on top of the classical route it then has to run - about ${n1(t.neuralFirstOverheadMedianMs.median / t.classicalMsOnRescued.median * 100)} % of the classical time on those requests. Over the whole 312: neural-only ${n0(t.wholeSuite.neuralOnlyMs)} ms, neural-first ${n0(t.wholeSuite.neuralFirstMs)} ms, classical ${n0(t.wholeSuite.classicalMs)} ms (the classical route hit its 30 s offline deadline on ${t.classicalDeadline} requests, which dominates its total).`);
  w();
}

w('---');
w();
w('Regenerate with `node analyze.js && node render.js` from this directory. `summary.json` holds every number in this document plus the per-mechanism id lists.');
w();

fs.writeFileSync(path.join(__dirname, 'summary.md'), out.join('\n'));
console.log('wrote summary.md,', out.join('\n').length, 'chars');
