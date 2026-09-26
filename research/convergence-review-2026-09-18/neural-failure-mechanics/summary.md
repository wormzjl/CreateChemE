# Why neural seeds fail: a mechanism partition of the 312-request holdout v2

Generated 2026-09-18 from five comparison journals in `research/convergence-review/eval/*/evaluation.jsonl`. Read-only: no solver, Gradle or Java was run. Scripts: `lib.js` (parsing + classification), `analyze.js` (all statistics, writes `summary.json`), `render.js` (this document).

**Vocabulary.** *Strict success* = the route returned `success` **and** a `waterQualification` of `DRY_EQUILIBRIUM` or `WET_EQUILIBRIUM`; anything else accepted is an advisory. *Neural-only* = predict a full column state with the Transformer, then run the direct Newton corrector from that seed (16 base iterations, extendable to 48 while the residual keeps falling, 2 s wall budget). *Classical* = the cold route from scratch (30 s offline deadline in this harness). *Neural-first* = neural-only, then the classical route if the seed fails.

All five journals cover exactly the same 312 request ids (verified, no id mismatch).

## 1. The partition (primary journal G-17023 epoch 160)

| mechanism | n | % of non-strict | mean ms | median ms | mean Newton it. | classical strict | neural-first strict | strict for >=1 other model | strict answer demonstrated anywhere | liquid-depletion | zero-boil-up | heat-gated |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| strict success | 118 | - | 364 | 282 | 2.3 | 76 | 118 | 108 | 118 | 3 | 0 | 22 |
| (b1) 2 s wall budget exhausted | 29 | 15% | 2,004 | 2,003 | 11.8 | 2 | 2 | 4 | 4 | 3 | 0 | 5 |
| (b2) no correction ran | 3 | 2% | 1,002 | 1,049 | 0.0 | 0 | 0 | 0 | 0 | 2 | 0 | 1 |
| (c) converged <1e-6, not strict | 21 | 11% | 707 | 705 | 2.0 | 1 | 1 | 0 | 1 | 0 | 0 | 2 |
| (d) every direction rejected | 74 | 38% | 1,108 | 1,146 | 13.0 | 7 | 7 | 9 | 12 | 41 | 0 | 28 |
| (e) CMB crawl (resid 0.5-1.5) | 36 | 19% | 838 | 882 | 10.7 | 12 | 12 | 20 | 20 | 5 | 0 | 3 |
| (f) other CMB-dominant | 11 | 6% | 927 | 668 | 17.3 | 5 | 5 | 5 | 5 | 2 | 0 | 1 |
| (g) VLE-dominant | 13 | 7% | 556 | 576 | 9.5 | 1 | 1 | 4 | 4 | 7 | 0 | 3 |
| (h) ENERGY-dominant | 7 | 4% | 1,191 | 1,233 | 15.3 | 2 | 2 | 2 | 2 | 2 | 0 | 0 |

Totals: 312 requests, 118 strict neural-only successes (38%), 194 non-strict. The classical route reaches 106 strict; neural-first reaches 148.

Column meanings: *classical strict* / *neural-first strict* = how many requests in that mechanism the other two routes solve strictly in this same journal. *strict for >=1 other model* = solved strictly by the neural-only route of at least one of the other four models. *strict answer demonstrated anywhere* = some route (classical, or any of the five models' neural-only or neural-first) produced a strict result for that id, i.e. the request is provably answerable and the failure is a real miss rather than an ill-posed request. *liquid-depletion* = max side-draw withdrawal fraction >= 0.8. *zero-boil-up* = no steam, reboiler duty exactly 0, feed above the bottom (none occur in holdout v2). *heat-gated* = some route reported a condensation cap / "not below the" failure.

Deviation from the requested ladder, stated explicitly: step (b) ("no iterations / no `scaled residual` event") turned out to hold two physically different populations, so it is reported split. **(b1)** are runs killed by the 2 s wall budget: their event list is truncated before the residual line is emitted, but the failure text carries `iterations=a/b`, and they did iterate (median 10). **(b2)** are the three runs where the corrector genuinely never started. Everything else follows the requested priority order exactly.

## 2. Conclusion

**The ceiling first.** Of the 194 non-strict neural-only outcomes in the primary journal, only **48** are on requests where *any* route in *any* of the five journals ever produced a strict answer. The other 146 requests were never solved strictly by anything, including the classical route with its 30 s deadline. So the realistic headroom for a seed pre-step on this holdout is about 48 requests (118 -> ~166), not 194.

| slice of the 194 non-strict | n | strict answer demonstrated anywhere | classical strict (this journal) | flagged ill-designed (liquid-depletion or heat-gated) |
|---|---|---|---|---|
| all non-strict | 194 | 48 | 30 | 91 |
| CMB-dominant (any mechanism) | 94 | 33 | 21 | 37 |
|   ... abs(physical) < 1e-3 mol/s | 63 | 24 | 15 | 23 |
|   ... abs(physical) >= 1e-3 mol/s | 31 | 9 | 6 | 14 |
| VLE-dominant (any mechanism) | 50 | 6 | 2 | 34 |
| ENERGY-dominant (any mechanism) | 18 | 5 | 5 | 9 |
| wall-budget timeout (no family telemetry) | 29 | 4 | 2 | 8 |
| converged, advisory qualification | 21 | 1 | 1 | 2 |

Which lever addresses which mechanism, and the headroom each one can actually reach:

| lever | mechanisms it addresses | failures in those mechanisms | of which a strict answer is demonstrated somewhere |
|---|---|---|---|
| (i) material-balance projection of the seed | (e), (f), and the CMB-dominant half of (d) | 84 | <= 33 (all CMB-dominant failures) |
| (ii) larger / iteration-scaled budget | (b1) | 29 | 4 |
| (iii) better training data (phase presence) | (g), and the VLE-dominant part of (d) | 39 | <= 6 (all VLE-dominant failures) |
| (iv) nothing / fix the request or the water model | (b2), (c), the liquid-depletion part of (d) | 65 | 3 |

The four rows overlap where a mechanism has mixed causes, so they do not sum to 194; the binding constraint is the 48-request solvability ceiling in the table above.

### (i) A material-balance projection of the seed before Newton

**Plausible, and this is where the headroom is.** COMPONENT_MATERIAL_BALANCE is the dominant residual family in 94 of the 194 failures (48%), carrying 33 of the 48 demonstrably-solvable misses. Three facts say the defect is a component-balance defect on trace species, which is exactly what a Wang-Henke style tridiagonal component-balance solve at frozen temperature repairs:

1. The dominant CMB residual is physically negligible in 63 of 94 cases (abs(physical) < 1e-3 mol/s; median 8.5e-6 mol/s) while the *scaled* residual sits near 1. 26 cases have scaled residual >= 0.5 with abs(physical) < 1e-6 mol/s.
2. 22 of 94 end within 1 % of a scaled residual of exactly 1.0 (17 within 0.1 %), the signature of a balance whose entire flow term is missing, i.e. a component sitting on the trace floor where the seed should have put a small positive flow.
3. At least 82 of the 94 dominant components are `crude_pc*` pseudo-cuts rather than one of the seven light real components; only 7 are certainly a light real component (see the index caveat in section 6).

A projection would also attack mechanism (d): 37 of the 74 "every direction rejected" cases are CMB-dominant, and a rejected local-block direction on a trace component is precisely a direction that a linear balance solve would have supplied for free. The strongest quantitative support is in section 7: only 1 of 118 strict successes start the corrector above a scaled residual of 1, and P(strict given initial residual <= 0.3) = 78% versus 3% above it. Lowering the corrector's *starting* residual is the single most discriminating thing you can do to the seed.

### (ii) A larger / iteration-scaled budget

**Plausible but small, and cheap to test.** 29 requests (15% of failures, and 23% of all neural-only wall time) died on the 2 s wall clock, not on a numerical obstruction. 3 of them had already reached a residual below 1e-6 and 4 in total were below 1e-3 - they were converged or nearly so and were cut off before the acceptance audit. Only 4 of the 29 are on requests with a demonstrated strict answer, so the expected gain is roughly +4 on this holdout, but the fix is a budget number.

The budget bites entirely at large N: timeout share is 0 % below 20 stages, 4% at 20-34, 9% at 35-49 and 28% at 50-64 (median stage count of a timeout: 54). Answering the question posed in the brief: it is **not** mainly preparation cost. Transformer inference is 11.1 ms at the median even for these columns. What scales is the corrector: median wall time per Newton iteration rises 25 ms (N 2-9) -> 209 ms (N 50-64), so a 2 s budget buys ~80 iterations on a small column and ~9 on a 60-stage one. Fixed set-up is real but secondary: strict runs that needed <= 1 Newton iteration still cost a median 663 ms at N 50-64 versus 29 ms at N 2-9. A budget expressed in iterations, or scaled by N, would recover the truncated runs; a flat 2 s will keep truncating tall columns.

### (iii) Better training data

**Plausible for the VLE family, but the payoff on this holdout is small.** 50 failures are VAPOR_LIQUID_EQUILIBRIUM-dominant and they look qualitatively different from the CMB family: the residual barely moves (median final/initial ratio 0.93, 16 of 50 are flat to within 1 %), the physical residual is large (median 4.81), and the `dominant VLE state` telemetry shows a collapsed phase pair: 22 of 50 have *both* the liquid and the vapour flow of the offending component below 1e-3 mol/s, with vapour flows as low as 1e-84. That is a phase-presence error in the seed (a component placed in one phase that the equilibrium says must be in the other), and a fixed-temperature material balance cannot repair it - it needs either a flash/K-value correction step or a seed that predicts the phase split better. But only 6 of these 50 requests have a demonstrated strict answer, and 34 are flagged ill-designed, so most of this family is not lost accuracy.

A separate training-data signal is model-to-model variance (section 9): 129 ids are strict for some models and not others, and on those ids the failing models most often show e-cmbcrawl: 95, d-alldirrej: 54, b-timeout: 53. Those mechanisms are seed-quality-limited, not solver-limited.

### (iv) Nothing (ill-designed or ill-posed requests)

**The largest single block.** 146 of the 194 failures were never solved strictly by any route in any journal; 91 of the 194 are flagged by the structural heuristics (liquid-depletion 62, heat-gated 43; zero-boil-up designs have been eliminated from holdout v2 - 0 occurrences). Mechanism (d) is the worst offender: 41 of its 74 cases are liquid-depletion designs, where every local-block direction is rejected because there is no downflow left to perturb.

Mechanism (c) deserves its own note: **it is not a numerical failure at all.** All 21 cases reached a residual below 1e-6; 19 were *accepted* by the solver with every audit check passing, and were downgraded only because the qualification came back `DRY_SUPERSATURATED` - a tray below the water dew point with no free-water phase, after a "free-water continuation declined" event fired in 19 of 21 cases. 20 of them are never strict on any route and 14 of the 19 are `DRY_SUPERSATURATED` on the classical route too, so the request itself has no dry equilibrium. This is the known open three-phase modelling decision, not a seed defect; a material-balance projection cannot help, and counting these as neural failures understates the model by 19 requests, ~6% of the holdout.

### What could not be determined from these journals

- **Whether a projection would actually converge.** The journals record residuals, not states. There is no per-node component flow dump for the failing seeds, so I cannot simulate the projection or measure how far the seed is from the stage material balances; I can only show that the residual that dominates is a component balance and is physically tiny. A probe that logs the seed state and the post-projection residual is the missing experiment.
- **Whether the trace floor is the cause or a symptom.** Failing runs retain a slightly smaller share of the floor support than strict ones (median 0.68 for (e) versus 0.77 for strict successes), but the direction of causation is not observable here.
- **The condenser branch for most failures.** `seed.branch` is only written on runs that produced a state, so it is missing for 66 of 74 of mechanism (d) and 24 of 29 of the timeouts. The branch split in section 8 is therefore reported only where observed.
- **Whether the never-solved 146 are genuinely infeasible.** "No route ever solved it strictly" is evidence, not proof; the classical route itself hit its 30 s deadline on 14 requests, and 65 of the 146 carry no ill-design flag.
- **Component identity for the CMB family.** The dominant-residual event reports a local index into the solver's active component basis, not a global one (measured in section 6). I can bound it - at least 82 of 94 are `crude_pc*` pseudo-cuts - but I cannot name the exact cut without a journal that records the active basis per request.
- **Time measurements are single-run and unpinned.** Journals were produced by separate benchmark runs, so ms figures carry machine and scheduling noise; the wall-budget conclusions rest on the 2000-2015 ms clustering, which is unambiguous, not on fine timing.

## 3. Mechanism definitions (applied in this priority order)

| mechanism | rule applied to the neural-only record |
|---|---|
| (a) outside coverage / seed rejected | `model unavailable or outside coverage` or `neural seed rejected:` in events, or `rawPrediction.supported == false` |
| (b1) wall budget exhausted | no `scaled residual:` event **and** a `neural budget exhausted; attempts=..., iterations=a/b, residual=...` event |
| (b2) no correction ran | no `scaled residual:` event and no budget event (in practice: `Stage-trace support fell back to identity`) |
| (c) converged, not strict | final scaled residual < 1e-6 and not a strict success |
| (d) every direction rejected | `local-block directions: accepted=0, rejected=b` with b > 0 |
| (e) CMB crawl | dominant family COMPONENT_MATERIAL_BALANCE, final residual in [0.5, 1.5], accepted > 0 |
| (f) other CMB-dominant | dominant family COMPONENT_MATERIAL_BALANCE, otherwise |
| (g) VLE-dominant | dominant family VAPOR_LIQUID_EQUILIBRIUM |
| (h) ENERGY-dominant | dominant family ENERGY_BALANCE |
| (i) remainder | anything left (empty on all five journals) |

## 4. The same partition across all five models

| mechanism | G-17023 e160 (primary) | G-17041 e160 | B-17023 e160 | B-17011 e80 | packaged (shipped) |
|---|---|---|---|---|---|
| strict success | 118 | 119 | 113 | 116 | 74 |
| (a) outside coverage / seed rejected | 0 | 0 | 2 | 2 | 10 |
| (b1) 2 s wall budget exhausted | 29 | 43 | 41 | 40 | 59 |
| (b2) no correction ran | 3 | 3 | 2 | 1 | 0 |
| (c) converged <1e-6, not strict | 21 | 25 | 12 | 21 | 11 |
| (d) every direction rejected | 74 | 67 | 70 | 59 | 69 |
| (e) CMB crawl (resid 0.5-1.5) | 36 | 28 | 36 | 41 | 49 |
| (f) other CMB-dominant | 11 | 12 | 16 | 15 | 16 |
| (g) VLE-dominant | 13 | 11 | 16 | 11 | 20 |
| (h) ENERGY-dominant | 7 | 4 | 4 | 6 | 4 |
| (i) remainder | 0 | 0 | 0 | 0 | 0 |

The retrained models are close to each other and well ahead of the shipped one (G-17023-160: 118, G-17041-160: 119, B-17023-160: 113, B-17011-80: 116, packaged: 74 strict of 312). The packaged model loses ground in exactly the mechanisms the retrained models improve: more timeouts, more CMB crawl, more VLE.

## 5. Mechanism detail

### 5.1 (b1) The 2 s wall budget

| journal | n | median N | median neural ms | median inference ms | median iterations done | median iteration cap | median residual reached | reached <1e-6 | residual bands |
|---|---|---|---|---|---|---|---|---|---|
| G-17023 e160 (primary) | 29 | 54 | 2,003 | 11.1 | 10 | 29 | 1.36 | 3 | 1..10: 9, 1e-3..0.1: 7, >10: 7, <1e-6: 3, 0.1..1: 2, 1e-6..1e-3: 1 |
| G-17041 e160 | 43 | 49 | 2,001 | 11.4 | 11 | 29 | 1 | 2 | 1..10: 13, 1e-3..0.1: 10, >10: 9, 0.1..1: 7, <1e-6: 2, 1e-6..1e-3: 2 |
| B-17023 e160 | 41 | 51 | 2,001 | 11.8 | 12 | 29 | 1.03 | 4 | 1..10: 13, >10: 10, 1e-3..0.1: 9, 0.1..1: 5, <1e-6: 4 |
| B-17011 e80 | 40 | 53 | 2,001 | 11.9 | 11 | 29 | 1.27 | 6 | 1..10: 15, 1e-3..0.1: 8, >10: 8, <1e-6: 6, 0.1..1: 3 |
| packaged (shipped) | 59 | 50 | 2,001 | 12.9 | 12 | 29 | 1 | 0 | 1..10: 31, 1e-3..0.1: 13, 0.1..1: 12, >10: 2, 1e-6..1e-3: 1 |

Inference itself is ~10-15 ms. The wall clock is consumed by the corrector, and the corrector rarely reaches its own iteration cap (median 10 of 29 allowed) before the wall fires.

| stage band | requests | median inference ms | median ms per Newton iteration (strict runs) | median ms of strict runs needing <=1 iteration | timeouts | timeout share |
|---|---|---|---|---|---|---|
| 2-9 | 35 | 2.1 | 25 | 29 | 0 | 0% |
| 10-19 | 39 | 3.3 | 50 | 132 | 0 | 0% |
| 20-34 | 51 | 6.1 | 100 | 258 | 2 | 4% |
| 35-49 | 129 | 9.2 | 148 | 328 | 11 | 9% |
| 50-64 | 58 | 13.2 | 209 | 663 | 16 | 28% |

### 5.2 (b2) No correction ran

| journal | n | stage counts | neural ms | of which "stage-trace support fell back to identity" |
|---|---|---|---|---|
| G-17023 e160 (primary) | 3 | 37, 61, 62 | 1383, 1049, 574 | 3 |
| G-17041 e160 | 3 | 37, 35, 54 | 1966, 1260, 1221 | 3 |
| B-17023 e160 | 2 | 25, 20 | 1292, 935 | 2 |
| B-17011 e80 | 1 | 62 | 1823 | 1 |
| packaged (shipped) | 0 | - | - | 0 |

Every one of these is the same structural event - feed reachability emptied a structural phase, so the stage-trace support degenerated to the identity and the corrector never started - and in the primary journal every one is a tall column (N 37-62) with a heavy side draw; across the five journals the range is N 20-62. All are liquid-depletion or heat-gated designs and none is solvable by any route.

### 5.3 (c) Converged but not strict

| journal | n | accepted (advisory) | rejected | qualification | failing audit families | "free-water continuation declined" |
|---|---|---|---|---|---|---|
| G-17023 e160 (primary) | 21 | 19 | 2 | DRY_SUPERSATURATED: 19, REJECTED:no-qualification: 2 | (all audit checks passed): 19, CONDENSER_PHASE: 2 | 19 |
| G-17041 e160 | 25 | 24 | 1 | DRY_SUPERSATURATED: 24, REJECTED:no-qualification: 1 | (all audit checks passed): 24, CONDENSER_PHASE: 1 | 24 |
| B-17023 e160 | 12 | 11 | 1 | DRY_SUPERSATURATED: 11, REJECTED:no-qualification: 1 | (all audit checks passed): 11, CONDENSER_PHASE: 1 | 11 |
| B-17011 e80 | 21 | 20 | 1 | DRY_SUPERSATURATED: 20, REJECTED:no-qualification: 1 | (all audit checks passed): 20, CONDENSER_PHASE: 1 | 20 |
| packaged (shipped) | 11 | 9 | 2 | DRY_SUPERSATURATED: 9, REJECTED:no-qualification: 2 | (all audit checks passed): 9, CONDENSER_PHASE: 1, SIDE_DRAW_SPLIT: 1 | 9 |

`(all audit checks passed)` means the run was accepted with every acceptance-audit family passing, including `WATER_DEW_POINT`, which is emitted as a warning rather than a rejection by design. The only genuine audit rejections in this bucket are `CONDENSER_PHASE` (1-2 per journal) and one `SIDE_DRAW_SPLIT` in the packaged journal.

### 5.4 (d) Every local-block direction rejected

| journal | n | dominant family | median rejected directions | median final/initial residual | final residual bands | "no admissible Armijo step" |
|---|---|---|---|---|---|---|
| G-17023 e160 (primary) | 74 | COMPONENT_MATERIAL_BALANCE: 37, VAPOR_LIQUID_EQUILIBRIUM: 26, ENERGY_BALANCE: 11 | 15 | 0.46 | 1..10: 35, >10: 20, 0.1..1: 10, 1e-3..0.1: 9 | 7 |
| G-17041 e160 | 67 | VAPOR_LIQUID_EQUILIBRIUM: 30, COMPONENT_MATERIAL_BALANCE: 24, ENERGY_BALANCE: 13 | 14 | 0.44 | 1..10: 24, >10: 21, 1e-3..0.1: 11, 0.1..1: 9, 1e-6..1e-3: 2 | 13 |
| B-17023 e160 | 70 | COMPONENT_MATERIAL_BALANCE: 33, VAPOR_LIQUID_EQUILIBRIUM: 26, ENERGY_BALANCE: 11 | 12 | 0.53 | 1..10: 32, >10: 19, 0.1..1: 13, 1e-3..0.1: 5, 1e-6..1e-3: 1 | 5 |
| B-17011 e80 | 59 | COMPONENT_MATERIAL_BALANCE: 23, VAPOR_LIQUID_EQUILIBRIUM: 22, ENERGY_BALANCE: 14 | 14 | 0.33 | 1..10: 20, >10: 15, 1e-3..0.1: 12, 0.1..1: 12 | 7 |
| packaged (shipped) | 69 | COMPONENT_MATERIAL_BALANCE: 37, ENERGY_BALANCE: 18, VAPOR_LIQUID_EQUILIBRIUM: 14 | 10 | 0.64 | 1..10: 30, 0.1..1: 18, 1e-3..0.1: 16, >10: 5 | 11 |

This is the single largest failure bucket in every journal. It is not a stalled residual - the median run still cuts its residual roughly in half - it is a run where the line search accepts nothing and the residual plateaus above 1. Half of these are CMB-dominant, which is why a material-balance pre-step is also relevant here.

## 6. Where the dominant residual sits (task 2)

Applied to every non-strict record with a dominant-residual event, grouped by dominant family regardless of which mechanism bucket it landed in.

**COMPONENT_MATERIAL_BALANCE** (primary journal, n = 94; by mechanism: d-alldirrej: 37, e-cmbcrawl: 36, f-cmbother: 11, c-converged: 10)

| node class | observed | expected if the node were uniform | enrichment |
|---|---|---|---|
| condenser | 1 | 3.5 | 0.28 |
| rectifying (above feed) | 73 | 76.3 | 0.96 |
| at feed | 5 | 3.5 | 1.42 |
| stripping (below feed) | 7 | 7.2 | 0.98 |
| reboiler | 8 | 3.5 | 2.27 |

**VAPOR_LIQUID_EQUILIBRIUM** (primary journal, n = 50; by mechanism: d-alldirrej: 26, g-vle: 13, c-converged: 11)

| node class | observed | expected if the node were uniform | enrichment |
|---|---|---|---|
| condenser | 3 | 2.1 | 1.44 |
| rectifying (above feed) | 31 | 39.6 | 0.78 |
| at feed | 3 | 2.1 | 1.44 |
| stripping (below feed) | 10 | 4.2 | 2.39 |
| reboiler | 3 | 2.1 | 1.44 |

**ENERGY_BALANCE** (primary journal, n = 18; by mechanism: d-alldirrej: 11, h-energy: 7)

| node class | observed | expected if the node were uniform | enrichment |
|---|---|---|---|
| condenser | 0 | 0.7 | 0.00 |
| rectifying (above feed) | 9 | 14.5 | 0.62 |
| at feed | 2 | 0.7 | 2.75 |
| stripping (below feed) | 5 | 1.3 | 3.83 |
| reboiler | 2 | 0.7 | 2.75 |

**Read the node histogram against the null, not raw.** These designs put the feed at a median 89 % of column height (p10 0.75, p90 0.97 of N+1), so "above the feed" already covers most nodes by construction. Against a uniform-node null the real signals are: CMB is 2.3x enriched at the **reboiler** and 3.5x depleted at the condenser; VLE is 2.4x enriched in the **stripping section** and mildly enriched at the condenser, feed and reboiler; ENERGY is enriched at the feed tray and below it. The raw "78 % above feed" reading for CMB is an artifact.

**Caveat on component identity, measured not assumed.** The `component=` field inside `dominant residual: EquationId[...]` is *not* an index into `input.componentBasis.componentIds`; it indexes the solver's active (retained, non-floored) component basis for that request. Pairing it against the `dominant VLE state` event, which names the component outright, on the 405 records across all five journals where both events refer to the same node, gives a global-minus-local offset of 1: 176, 2: 107, 0: 105, 3: 17 - always non-negative, never larger than 3. So a local index of k guarantees a global index of at least k, and the two-sided bound global <= local + 3 holds empirically. Component names below are therefore quoted only where the `dominant VLE state` event supplies them; for CMB only the bounded classification is reported.

| family | n | dominant component |
|---|---|---|
| COMPONENT_MATERIAL_BALANCE | 94 | median local index 10 of a 19-component slate; certainly a crude_pc* pseudo-cut in 82, certainly a light real component in 7, ambiguous in 5 |
| VAPOR_LIQUID_EQUILIBRIUM | 50 | named by the VLE-state event: crude_pc12 (10), crude_pc11 (5), crude_pc05 (5), Isopentane (5), crude_pc03 (4), crude_pc08 (4), crude_pc04 (3), crude_pc09 (3) |
| ENERGY_BALANCE | 18 | component = -1 (no component) in all cases |

CMB local-index histogram (index: count): 10: 16, 8: 14, 9: 13, 12: 9, 11: 8, 7: 7, 13: 5, 16: 5, 0: 4, 15: 3, 1: 2, 4: 2, 6: 2, 14: 2, 3: 1, 5: 1.

The CMB defect therefore sits deep in the pseudo-cut range - species that carry little flow and are the first to be floored. VLE leads with the heaviest cut (crude_pc12, 10 of 50) and then spreads across the mid cuts and Isopentane, with a tail on Propane/Ethane: both ends of the slate, where one phase can vanish entirely.

Does the residual fall at all? (final / initial scaled residual)

| family | n | initial residual p10/p50/p90 | final residual p10/p50/p90 | ratio p10/p50/p90 | ratio bands |
|---|---|---|---|---|---|
| COMPONENT_MATERIAL_BALANCE | 94 | 0.0701 / 1.72 / 346 | 0.00117 / 0.995 / 1.78 | 3.1e-4 / 0.281 / 0.995 | 0.01..0.5: 36, <0.01 (fell hard): 19, 0.5..0.9: 17, 0.9..0.99: 11, >1 (rose): 7, 0.99..1.0 (flat): 4 |
| VAPOR_LIQUID_EQUILIBRIUM | 50 | 2.3e-4 / 6.06 / 384 | 5.6e-14 / 4.81 / 80.1 | 1.1e-10 / 0.929 / 1 | 0.99..1.0 (flat): 16, 0.9..0.99: 12, <0.01 (fell hard): 7, 0.01..0.5: 7, 0.5..0.9: 6, >1 (rose): 2 |
| ENERGY_BALANCE | 18 | 0.00279 / 0.541 / 177 | 0.00289 / 0.0195 / 0.217 | 0.002 / 0.226 / 1 | 0.01..0.5: 6, <0.01 (fell hard): 4, 0.99..1.0 (flat): 4, >1 (rose): 3, 0.9..0.99: 1 |

CMB failures **do** make progress (median ratio 0.28) and then park just under 1 - a crawl. VLE failures barely move (median ratio 0.93, 16 of 50 flat to within 1 %) - a block, not a crawl.

Physical magnitude of the dominant residual:

| measure | value |
|---|---|
| CMB abs(physical) median | 8.5e-6 mol/s |
| CMB abs(physical) bands | 1e-9..1e-6: 28, 1e-6..1e-3: 24, >=1 mol/s: 19, 1e-3..1: 12, <1e-9 mol/s: 11 |
| CMB with scaled >= 0.5 but abs(physical) < 1e-6 | 26 of 94 |
| CMB final residual within 1 % of exactly 1.0 | 22 of 94 |
| VLE dominant-state records | 50 |
| VLE vapour-flow bands | 1e-12..1e-3: 28, >=1e-3: 17, <1e-30: 4, 1e-30..1e-12: 1 |
| VLE with both phase flows < 1e-3 mol/s | 22 of 50 |
| VLE with either phase flow < 1e-3 mol/s | 41 of 50 |
| VLE dominant-state temperature median | 449 K |

## 7. Does the initial residual predict the outcome? (task 3, primary journal)

| mechanism | n | corrector's initial scaled residual p10/p50/p90 | raw seed residual (rawPrediction.nativeResidual) p10/p50/p90 |
|---|---|---|---|
| strict success | 118 | 4.7e-9 / 0.00501 / 0.068 | 3.62 / 6.43 / 17.6 |
| (b1) 2 s wall budget exhausted | 29 | - / - / - | 4.07 / 5.79 / 18.2 |
| (b2) no correction ran | 3 | - / - / - | 17.1 / 18.9 / 20.3 |
| (c) converged <1e-6, not strict | 21 | 9.2e-12 / 0.00342 / 0.0793 | 4.08 / 5.84 / 16.4 |
| (d) every direction rejected | 74 | 0.997 / 14.3 / 540 | 4.4 / 7.4 / 15.7 |
| (e) CMB crawl (resid 0.5-1.5) | 36 | 0.7 / 0.997 / 4.96 | 3.68 / 7.25 / 14.6 |
| (f) other CMB-dominant | 11 | 0.0206 / 0.984 / 5.71 | 4.61 / 7.17 / 18.2 |
| (g) VLE-dominant | 13 | 1.21 / 4.83 / 24.8 | 4.11 / 7.9 / 16.9 |
| (h) ENERGY-dominant | 7 | 0.00209 / 0.337 / 1.64 | 3.29 / 5.6 / 7.87 |

**Two different residuals, two different answers.** The *raw* neural seed residual is ~6 for everything and carries almost no information: the probability that a random strict success has a lower raw seed residual than a random failure is 0.53 (0.5 = coin flip). The residual the *corrector actually starts from*, after the solver's own seed preparation (floor support, native trace-support projection), is highly discriminating: 0.90. The solver already performs a repair step that moves the seed from ~6 to ~0.005 when it works - and failures are precisely the cases where that repair leaves the residual at O(1) or above.

| x | strict successes with initial residual > x | failures with initial residual > x | of all records above x, share that fail |
|---|---|---|---|
| 0.3 | 4 / 118 | 130 / 162 | 97% |
| 1 | 1 / 118 | 99 / 162 | 99% |
| 3 | 1 / 118 | 76 / 162 | 99% |
| 10 | 0 / 118 | 46 / 162 | 100% |

| x | records with initial residual <= x | of which strict | P(strict given <= x) | records above x | of which strict | P(strict given > x) |
|---|---|---|---|---|---|---|
| 0.03 | 116 | 91 | 78% | 164 | 27 | 16% |
| 0.1 | 141 | 110 | 78% | 139 | 8 | 6% |
| 0.3 | 146 | 114 | 78% | 134 | 4 | 3% |
| 1 | 180 | 117 | 65% | 100 | 1 | 1% |
| 3 | 203 | 117 | 58% | 77 | 1 | 1% |
| 10 | 234 | 118 | 50% | 46 | 0 | 0% |

Only 32 of the 146 records that start at or below 0.3 go on to fail, and their composition is telling: c-converged: 21, d-alldirrej: 5, f-cmbother: 3, h-energy: 3. In other words, once the corrector starts below ~0.3 the only remaining failure modes are the advisory-qualification block (c) and a handful of stubborn cases - the numerical route is essentially solved. A further 32 records (the 29 timeouts and the 3 no-correction runs) carry no initial-residual telemetry at all and are excluded from both tables above.

## 8. Structure profile per mechanism (task 4, primary journal)

| mechanism | n | median N | stage bands 2-9 / 10-19 / 20-34 / 35-49 / 50-64 | steam on | mean draws | mean pumparounds | median side-draw withdrawal | condenser branch LIQUID_ONLY / TWO_PHASE / unknown |
|---|---|---|---|---|---|---|---|---|
| strict success | 118 | 34 | 20 / 21 / 18 / 44 / 15 | 82 / 118 | 1.69 | 2.09 | 0.22 | 58 / 60 / 0 |
| (b1) 2 s wall budget exhausted | 29 | 54 | 0 / 0 / 2 / 11 / 16 | 17 / 29 | 2.03 | 2.17 | 0.43 | 2 / 3 / 24 |
| (b2) no correction ran | 3 | 61 | 0 / 0 / 0 / 1 / 2 | 2 / 3 | 3.00 | 2.00 | 1.81 | 0 / 0 / 3 |
| (c) converged <1e-6, not strict | 21 | 39 | 1 / 2 / 0 / 17 / 1 | 19 / 21 | 2.33 | 2.62 | 0.26 | 12 / 8 / 1 |
| (d) every direction rejected | 74 | 30 | 10 / 12 / 20 / 24 / 8 | 23 / 74 | 1.84 | 2.36 | 1.05 | 3 / 5 / 66 |
| (e) CMB crawl (resid 0.5-1.5) | 36 | 40 | 2 / 1 / 5 / 17 / 11 | 24 / 36 | 1.69 | 2.19 | 0.25 | 9 / 6 / 21 |
| (f) other CMB-dominant | 11 | 39 | 0 / 1 / 2 / 6 / 2 | 11 / 11 | 2.45 | 2.36 | 0.34 | 4 / 2 / 5 |
| (g) VLE-dominant | 13 | 38 | 2 / 0 / 3 / 5 / 3 | 9 / 13 | 1.54 | 2.15 | 1.19 | 0 / 1 / 12 |
| (h) ENERGY-dominant | 7 | 36 | 0 / 2 / 1 / 4 / 0 | 5 / 7 | 2.14 | 2.43 | 0.35 | 2 / 2 / 3 |

Reading: strict successes are spread across all stage bands; the timeouts and the no-correction cases are exclusively tall columns; (c) clusters at N 35-49 with steam on in 19 of 21 (it is a steam-stripping/water artefact); (d) is the only bucket with a markedly elevated side-draw withdrawal. The condenser branch is unrecorded for most failures because `seed.branch` is only written when a state is produced - where it is visible, the split tracks the population and shows no branch effect.

## 9. Cross-model stability (task 5)

| models solving the id strictly (neural-only) | ids |
|---|---|
| 0 of 5 | 150 |
| 1 of 5 | 21 |
| 2 of 5 | 21 |
| 3 of 5 | 36 |
| 4 of 5 | 51 |
| 5 of 5 | 33 |

33 ids are strict under all five models, 150 under none, 129 under some. Per-model strict counts: G-17023-160 118, G-17041-160 119, B-17023-160 113, B-17011-80 116, packaged 74. The union over the five neural-only routes is 162 ids, against 106 for the classical route - model choice buys more than the classical fallback does.

| population | mechanisms shown by the models that fail (counts over model x id) |
|---|---|
| the 129 "some models solve it" ids | e-cmbcrawl: 95, d-alldirrej: 54, b-timeout: 53, f-cmbother: 32, g-vle: 26, a-coverage: 5, h-energy: 4, c-converged: 1 |
| the 150 "no model solves it" ids | d-alldirrej: 285, b-timeout: 159, e-cmbcrawl: 95, c-converged: 89, g-vle: 45, f-cmbother: 38, h-energy: 21, a-coverage: 9, b-nocorrection: 9 |
| same, restricted to the primary model on the "some" ids | e-cmbcrawl: 20, d-alldirrej: 9, f-cmbother: 5, b-timeout: 4, g-vle: 4, h-energy: 2 |

**The contested ids fail differently from the hopeless ones.** On ids that some model solves, the failing models are dominated by CMB crawl (95) - the mechanism a projection targets. On ids no model solves, the dominant mechanism is "every direction rejected" (285), and the population is structurally sick: only 4 of the 150 are strict on the classical route, 58 are liquid-depletion designs and 37 are heat-gated. The universally-solved ids are short columns (mean N 21.8) versus mean N 36.5 for the never-solved.

## 10. Time accounting (task 6, primary journal)

| mechanism | n | total ms | share of neural-only wall time | mean ms | median ms | p90 ms |
|---|---|---|---|---|---|---|
| strict success | 118 | 42,981 | 17% | 364 | 282 | 883 |
| (b1) 2 s wall budget exhausted | 29 | 58,129 | 23% | 2,004 | 2,003 | 2,012 |
| (b2) no correction ran | 3 | 3,007 | 1% | 1,002 | 1,049 | 1,317 |
| (c) converged <1e-6, not strict | 21 | 14,847 | 6% | 707 | 705 | 1,145 |
| (d) every direction rejected | 74 | 81,976 | 32% | 1,108 | 1,146 | 1,780 |
| (e) CMB crawl (resid 0.5-1.5) | 36 | 30,180 | 12% | 838 | 882 | 1,389 |
| (f) other CMB-dominant | 11 | 10,199 | 4% | 927 | 668 | 1,409 |
| (g) VLE-dominant | 13 | 7,233 | 3% | 556 | 576 | 1,045 |
| (h) ENERGY-dominant | 7 | 8,336 | 3% | 1,191 | 1,233 | 1,714 |
| **total** | 312 | 256,889 | 100% | 823 | - | - |

Strict successes consume 17% of neural-only wall time (42,981 ms of 256,889 ms); 213,908 ms is spent failing. The two most expensive failure buckets are (d) at 32% and the wall-budget timeouts at 23%. A cheap pre-filter that predicted (d) would return about a third of the neural-only budget.

Neural-first rescue cost (the 30 requests where the seed failed and the classical route then succeeded strictly; all 30 of them are also strict for classical-alone):

| route on those 30 requests | mean ms | median ms | p10 | p90 | total ms |
|---|---|---|---|---|---|
| neural-only attempt (wasted) | 1,034 | 899 | 500 | 1,826 | 31,023 |
| classical alone | 4,083 | 2,771 | 622 | 12,341 | 122,478 |
| neural-first (seed + classical) | 5,156 | 3,914 | 1,240 | 14,321 | 154,665 |
| overhead (neural-first minus classical) | 1,073 | 980 | 404 | 1,995 | 32,187 |

A failed seed costs a median 980 ms on top of the classical route it then has to run - about 35.4 % of the classical time on those requests. Over the whole 312: neural-only 256,889 ms, neural-first 1,790,217 ms, classical 2,090,726 ms (the classical route hit its 30 s offline deadline on 14 requests, which dominates its total).

---

Regenerate with `node analyze.js && node render.js` from this directory. `summary.json` holds every number in this document plus the per-mechanism id lists.
