# Continuation schedule, source pumparound arrangement and heat-rung energy-shift predictor: implementation review

Date: 2026-09-08. Worktree `.claude/worktrees/v3-low-pressure-gaps-989c00`, branch
`claude/v3-literature-cdu-handoff-3179dc`, base `faefa0a`. Plan and measurements:
`documentation/V3_THESIS_PUMPAROUND_ARRANGEMENT.md` (arrangement and follow-up list) and
`documentation/V3_WALL_AND_40MW_BALANCE_ANALYSIS.md` (the energy-shift valley diagnosis).

| Commit | Subject | Tests |
|---|---|---|
| `fc91887` | Double the V3 continuation grid from fifteen up to the request | 433, 0 failures |
| `422af61` | Qualify the source pumparound arrangement instead of a single-tray duty | 434, 0 failures |
| `be8016c` | Predict the rung temperature shift when the stage heat or steam changes | 441, 0 failures |

Baseline at `faefa0a` was 431 tests, 0 failures, 5 m 35 s. Every commit was made with the full suite green
(`./gradlew.bat test --offline`, toolchain JDK 21.0.11).

**Headline.** The continuation schedule was the whole 40-tray blocker: three literature variants go from 250
to 560 second typed failures to 5 to 15 second solves, and one of them (`L7`) from failure to success. The
predictor is the whole heat-ramp blocker: the 40 MW case converges again to exactly the pre-regression state
(condenser -30.11 MW), the source three-cooler arrangement without draws converges for the first time with
zero heat-ramp stalls, and the full literature CDU now solves its MESH system at the requested input to
5.0e-14 — its only remaining failure is a `WATER_DEW_POINT` audit check at 1.162, which is a contract
question and not a convergence one.

---

## 1. WP-A — the continuation grid schedule

File: `V3ColumnCalculator.dwsimStageCounts` (now package-private static); test
`V3DwsimStageContinuationTest` (+2).

### 1.1 The rule

```
for stageCount in {4, 8, 15}:  if stageCount < request: emit stageCount
for stageCount = 30, 60, 120, ...:  while stageCount < request: emit stageCount
emit request
```

In words: the seed grids 4, 8 and 15 where they are below the request, then a doubling of 15 for as long as
the doubled grid is *strictly* below the request, then the request itself.

| Request | Schedule |
|---|---|
| 2 to 4 | unchanged (`{4}`, `{2}`, …) |
| 15 | 4-8-15 |
| 20 | 4-8-15-20 |
| **30** | **4-8-15-30** (identical to the old rule) |
| 31 | 4-8-15-30-31 |
| 40 | 4-8-15-30-40 |
| 60 | 4-8-15-30-60 |
| 61 | 4-8-15-30-60-61 |
| 64 (`MAX_STAGE_COUNT`) | 4-8-15-30-60-64 |

A request of 30 or fewer stages is byte-for-byte what it was, because the first doubled grid is already 30 and
30 is not strictly below 30. No digest, solve path or timing of an existing case moves; the five-case
identity check of `V3ConvergenceClosureTest` passed unchanged at this commit.

### 1.2 Tests

- `theGridScheduleDoublesFromFifteenAndIsUnchangedAtOrBelowThirtyStages`: for every request in
  `MIN_STAGE_COUNT..30` the schedule equals the legacy `{4, 8, 15}`-prefix construction written out in the
  test; explicit pins for 31, 40, 60, 61 and 64; and, over the whole admitted range, the schedule ends on the
  request, is strictly increasing, and never more than doubles above 15.
- `thePlainFortyTrayLiteratureColumnConvergesOnTheDoubledSchedule`: the plain TJL19 40-tray column (feed tray
  37 of 40, 638.15 K, 250 kPa, drop 0, condenser 332.15 K, reflux 4.17, reboiler 8 MW, feed
  737.6996333000835 mol/s of `createcheme:tia_juana_light` on `createcheme:tjl19_dwsim`) inside a 60 s budget.
  Measured **4.94 s, SUCCESS**, audit accepted, path `cold/dwsim-sequential/4-8-15-30-40/fine-fd/...`.

### 1.3 L1..L7 before and after (`build/pkgcmp/LitVariants`)

Logs `build/pkgcmp/lit-variants.log` (base) and `lit-variants-wpa.log`.

| Variant | `faefa0a` | after WP-A |
|---|---|---|
| L1 literature full (40 trays, draws, steam, 3 coolers, no reboiler) | NONCONVERGENCE 246.3 s, resid 7.90, base grid | NONCONVERGENCE **14.8 s**, resid 0.104, reaches the requested rung |
| L2 plain 40 trays, reboiler 8 MW | NONCONVERGENCE 287.2 s, resid 1.99 | **SUCCESS 5.1 s**, resid 6.39e-14 |
| L3 plain 40 trays, drop 750 Pa | NONCONVERGENCE 564.3 s, resid 10.9 | **SUCCESS 5.0 s**, resid 2.13e-14 |
| L4 plain 30 trays | SUCCESS 3.8 s, resid 7.82e-14 | SUCCESS 3.3 s, resid 7.82e-14, identical path (unchanged) |
| L5 40 trays, steam only | NONCONVERGENCE 270.1 s, resid 7.90 | NONCONVERGENCE **14.0 s**, resid 0.00247 |
| L6 40 trays, steam + draws | NONCONVERGENCE 270.4 s, resid 7.90 | NONCONVERGENCE **13.4 s**, resid 0.556 |
| L7 40 trays, steam + 3 coolers | NONCONVERGENCE 270.4 s, resid 7.90 | **SUCCESS 14.8 s**, resid 6.76e-14 |

Every 40-tray case used to die at the base grid before any authored feature was applied. After the change
they all reach their own ramps, and the remaining failures are ramp failures with a named cause.

---

## 2. WP-B — the source arrangement replaces the single-tray duty

Files: `V3PumparoundCalculatorTest`, `V3SideDrawCalculatorTest`, `V3Sotelo2019SideDrawCase`. No production
code.

### 2.1 The 40 MW test is replaced

`V3PumparoundCalculatorTest.aLargeSingleTrayDutyStaysWithinTheTypedFailureContract` is **removed**. Its input
— 40 MW on one tray with a return-tray split — is a configuration the source arrangement never uses:
Ledezma-Martinez (2019), section 1.2 and tables A4/A6, spreads 41.93 MW over three coolers, each drawn at a
side-product stage and returned two to four stages above it.

New `theThesisThreeCoolerArrangementCarriesItsWholeDutyWithTheSideDraws`: the 30-tray CDU17 column at the
source operating point (feed 2610.7 kmol/h at 638.15 K, feed tray 24, 250 kPa top, 750 Pa/stage, condenser
332.15 K, reflux 4.17, reboiler 8 MW) with coolers `(return 6, draw 8, -12.84 MW)`, `(13, 15, -17.89 MW)`,
`(20, 22, -11.20 MW)` all UNIFORM, plus three side draws on trays 8 / 15 / 22.

| Quantity | Measured |
|---|---|
| Outcome | **SUCCESS**, 9.92 s, 7 published Newton iterations, residual 3.20e-14 |
| Solve path | `cold/dwsim-sequential/4-8-15-30/fine-fd/draw-ramp-1.0/condenser-phase-correction/draws-3/heat-3` |
| Ledger stage-heat total | **-41.93 MW** over 9 trays (asserted to 1 W) |
| Condenser duty | **-22.00 MW**, against a heat-free base of **-59.37 MW** (asserted strictly smaller in magnitude) |
| `GLOBAL_ENERGY_BALANCE` | passed |

After WP-C the same case is SUCCESS in 5.8 s at 6 iterations and residual 1.60e-14 (variant `V2` below).

**Deviation, deliberate.** The task text describes the side draws as
"`ColumnCalculatorV3BlockEntity.pilotPresetInput().sideDraws()`, trays 8/15/22". The preset actually authors
its three draws on trays **13, 17 and 22**; trays 8 / 15 / 22 are the stages the source pairs each cooler
with, and are what `build/pkgcmp/LitVariants` variant `V2` measured. The test therefore reads the three
**rates** from `pilotPresetInput().sideDraws()` and places them on trays 8 / 15 / 22, with that stated in a
comment. Using the preset trays unchanged would have been a different configuration from the one the plan
measured.

### 2.2 The over-large single-tray contract that stays

Two tests keep the 40 MW / 200 MW inputs, both with a stated purpose:

- `impossibleCoolingIsRejectedByTheStaticAdmissionGateWithoutASolve` (200 MW): the static infeasibility gate,
  `INFEASIBLE_SPECIFICATION` with zero Newton iterations. Unchanged.
- `aStalledDropOnlyFloorRefreshCostsABoundedRepeatOnTheFortyMegawattCase` (40 MW): the work-budget probe of
  the bounded stalled drop-only floor refresh. Its outcome is still not pinned; its checkpoint ceiling is
  re-pinned in WP-C (section 3.6).

### 2.3 The wall sweep

`originalLargeDrawCaseAttemptsRequestedGeometryAndNamesAnAuthoredTray` is renamed
**`anOverSpecifiedProductSlateAttemptsRequestedGeometryAndNamesAnAuthoredTray`** with its assertions
unchanged. Its javadoc now states the arithmetic: the three draws of 496 / 653 / 149 kmol/h are 1 298 kmol/h,
49.7% of the feed moles, and that column at a 400 K condenser and reflux 2.0 sends about 59% of the feed
overhead, so the requested products exceed the feed. The source slate (table A5) is 45.6% side draws and
32.4% overhead. The test qualifies the typed-failure contract only — requested geometry attempted, the
exhausted authored tray named, no continuation-grid excuse — and nothing about convergence.

New `theThesisArrangedFortyPercentDrawsConvergeWithTheirPumparounds`: the same column with the draws at 0.40x
and one cooler per draw at 0.40x the source duties — `(6, 8, -5.136 MW)`, `(13, 15, -7.156 MW)`,
`(20, 22, -4.48 MW)`, UNIFORM.

| Quantity | Measured |
|---|---|
| Outcome | **SUCCESS**, 11.7 s, 3 published Newton iterations, residual 1.07e-14 |
| Solve path | `cold/dwsim-sequential/4-8-15-30/fine-fd/draw-ramp-1.0/draws-3/heat-3` |
| Ledger stage-heat total | -16.772 MW (asserted to 1 W) |
| `SIDE_DRAW_SPLIT` (largest withdrawal fraction) | **0.2818**, asserted below 0.5 |
| Published side-draw rates | equal to the authored rates to 1e-8 |

**Deviation, unavoidable.** The task asks additionally for "tray-23 liquid above 20 mol/s". `V3ColumnResult`
deliberately does not retain the internal MESH state — its own javadoc says physical profiles stop at the
product streams — so there is no public or package-private hook for an internal tray flow, and the task's
fallback applies: the test asserts on the outcome, the ledger and the audit only. The `SIDE_DRAW_SPLIT` check
is the closest published proxy and is the one the analysis used: at withdrawal 0.125 on tray 22 the liquid
reaching tray 23 is 0.875 of tray 22's, which the reflection probe measured as 57 mol/s against 0.8 mol/s
without the coolers. The three withdrawal fractions the probe reported for this input are 0.18 / 0.28 / 0.13,
and the audit's 0.2818 is their maximum, so the published check pins the same quantity.

`V3Sotelo2019SideDrawCase.DRY_QUALIFICATION_SCALE` gains a javadoc stating why 0.75x and 1.0x are invalid
specifications rather than hard cases, and pointing at the 0.40x draws-plus-coolers lane.

---

## 3. WP-C — the heat and steam rung energy-shift predictor

New file `V3EnergyShiftPredictor` (package-private); `V3ColumnCalculator` (feed-flash extraction, ramp
wiring, one diagnostic event); tests `V3EnergyShiftPredictorTest` (new, 6), `V3ConvergenceClosureTest`
(case D re-pinned, +1 case), `V3PumparoundCalculatorTest` (checkpoint ceiling re-pinned).

### 3.1 The formula as implemented

With every flow frozen at the seed and the seed's compositions frozen, the tray energy rows are linearised in
the tray temperatures only:

```
dE_n/dT_n     = -(L_n Cp_L,n + V_n Cp_V,n)
dE_n/dT_(n-1) = (1 - w_(n-1)) L_(n-1) Cp_L,(n-1)
dE_n/dT_(n+1) = V_(n+1) Cp_V,(n+1)
J_T dT = -E(seed under the NEW rung duties)
```

- Rows and columns are nodes `1..reboilerNode`. The condenser has no temperature unknown (its outlet is a
  specification) and therefore neither a row nor a column, so row 1 has no sub-diagonal entry; the sump has
  both a row and a column and no super-diagonal entry.
- `L Cp_L` and `V Cp_V` are the **total phase enthalpy-rate slopes in W/K**, taken as a central one-kelvin
  finite difference (`T ± 1 K`) of `V3MeshResidualEvaluator.localTerms(...)`, which is the evaluator's own
  phase enthalpy at the node's frozen composition. Taking the slope of the whole phase energy rather than of a
  molar Cp means a wet node's water-vapour term is differentiated together with the hydrocarbon term, exactly
  as the residual assembles it.
- `w` is `V3ColumnProblem.liquidWithdrawalFraction(seed, n)` — the side-draw withdrawal fraction — and is
  zero for the sump.
- `E(seed)` is the physical energy residual of the seed evaluated against the **new** rung's duties.
- The system is solved by the Thomas algorithm; a pivot at or below 1e-12 in magnitude aborts the prediction.

### 3.2 The bound: scaling, not truncation (deviation from the literal instruction)

The task says "apply `ΔT` clamped to ±40 K per tray". Implemented as: bound the largest tray move to 40 K by
**scaling the whole vector**, `t = min(1, 40 / max|ΔT|)`, rather than by truncating each entry.

Why, measured. The system is close to singular in the common mode, and that is physics rather than a defect:
moving every tray by the same amount changes what a tray receives as much as what it sends, so the interior
rows barely notice and only the condenser (held at its specified outlet) and the sump absorb the change. That
near-null direction *is* the valley. A per-entry clamp saturates most trays at the bound, which distorts the
direction and destroys the guarantee; scaling keeps the direction the linear system chose, so the linear
model's residual falls from `E` to `(1 - t) E` and a bounded prediction can never make its own rows worse in
the model it was derived from.

Measured on the source three-cooler column, first heat rung, scaled energy residual:

| Bound | first heat rung `0.25` | V1 outcome | V1 heat-ramp stalls | V1 time |
|---|---|---|---|---|
| per-entry truncation | 2.056e-2 -> **8.164e-2** (worse) | SUCCESS | 1 | 5.5 s |
| whole-vector scaling | 2.056e-2 -> **1.490e-2** (better) | SUCCESS | **0** | **4.3 s** |

The task's own acceptance criterion for the integration test — "the predictor reduces the initial scaled
residual of the first heat rung on the thesis-arranged no-draw case" — is satisfied by the scaling form and
is *not* satisfied by the truncating form. That is the reason for the deviation.

### 3.3 Where it is applied, and the gate

In `recoverWithDrawRamp`, on the rung seed, before `prepareAttempt`/Newton, when **all** of:

1. the rung changes the stage-heat fraction or the steam fraction relative to the fractions the seed profile
   belongs to (a pure draw rung changes flows rather than heat and is left alone), **and**
2. the seed is an **accepted** state — the ramp's own seed base, or the accepted state of the previous rung.

Never on a continuation grid: a grid seed comes from a different geometry rather than from a different duty.

Rule 2 is the gate the plan asked for, arrived at by measurement rather than the signature it proposed. The
proposed rule — "apply only when the seed's energy rows share a sign on more than half the trays" — was
implemented and is retained as `V3EnergyShiftPredictor.hasCommonModeSignature` with its own unit test, but it
is **not** the gate, because it does not discriminate on a rung seed: after an accepted rung the energy
residual under the new duties is the duty increment, which is nonzero only on the cooled trays (9 of 31 on the
source arrangement) with the remaining rows at 1e-14 noise of random sign. The signature describes the
*stalled* state the analysis measured, not the seed the predictor sees, so gating on it would have switched
the predictor off exactly where it works.

The measured regression that rule 2 fixes: variant `L7` (40 trays, 1 200 kmol/h steam, three coolers, no
draws) stalls its steam ramp at 5/12 and then jumps to the requested input from that stalled state.
Predicting a 40 K shift from a state that failed its own rung cost the case its convergence.

| L7 | outcome | time | published newton | residual |
|---|---|---|---|---|
| WP-B baseline (no predictor) | SUCCESS | 14.8 s | 2 | 6.76e-14 |
| predictor, ungated | **NONCONVERGENCE** | 16.3 s | 16 | 6.31e-7 (`EQUILIBRIUM` 5.99e-7) |
| predictor, accepted-seed gate | **SUCCESS** | 17.3 s | 0 | 3.51e-14 |

The prediction linearises the energy rows around a profile that satisfies its own rung with those flows; a
state that failed its rung does not, and its residual is not the duty increment the linearisation solves for.
That is the justification, and L7 is the measurement.

### 3.4 Failure handling and telemetry

`predict` returns an empty prediction — the seed is used untouched — for a singular system, a property call
outside its domain, a non-finite slope, a zero shift, or a state that does not match the problem.
`CancellationException` propagates. The prediction is measured on the pair the attempt will actually solve
(`prepareAttempt`'s truncated problem and projected seed), because a state whose truncated points hold exact
zeros cannot be evaluated against an identity-support problem; only the temperature vector is carried back to
the caller's untruncated seed, which is valid because the flows are frozen throughout and both
`projectSeed` and `liftFloorSupport` preserve node temperatures.

One bounded event per ramp is published, and the ramp's event list is now capped at
`V3SolverDiagnostics.MAX_EVENTS` instead of being able to overflow it:

```
energy-shift predictor: applied=4, declined=0, largest shift=40.0 K,
  first heat-ramp-0.25 scaled energy 0.020557704831570843 -> 0.014904539781862677
```

`declined` counts predictions the predictor itself refused; rungs skipped by the two conditions above are not
counted. `V3ColumnCalculator.feedFlash(problem, thermo, control, policy)` was extracted from
`solveSingleProblem` so the predictor can build the same evaluator; the value is memoised per rung policy, so
a ramp pays at most two extra feed flashes.

### 3.5 Gates 1 to 5

Probes rebuilt against the head science sources after each change; `RefreshProbe` and `LitVariants` were
extended to print the predictor event, the duty ledger and the failed audit checks. Logs under
`build/pkgcmp/`: `tray-balance-wpc-D.log`, `lit-variants-wpc-gated.log`, `refresh-wpc-gated.log`,
`refresh-timing.log`, and the WP-B baseline `refresh-wpb2.log` compiled from commit `422af61` into
`build/pkgcmp/classes-wpb`.

**Gate 1 — `TrayBalanceProbe2 D`, the single 40 MW cooler.**

| | before (WP-B) | after (WP-C) | at `b42d85a` |
|---|---|---|---|
| Outcome | NONCONVERGENCE, 16 it, resid 0.0117 | **SUCCESS**, 7 it, resid 3.91e-14 | SUCCESS, 7 it, 3.6e-14 |
| Time | 8.66 s | 5.7 s | — |
| Condenser duty | — | **-30.11 MW** | 30.1 MW |
| Tray 1 / feed tray / bottoms | 385 / 520 / 557 K (failed iterate) | **366.8 / 489.5 / 512.9 K** | 367 / 490 / 513 K |
| Liquid below tray 8 | 2086 to 2207 (failed iterate) | 2000 to 2037 | 2000 to 2037 |

The recovered state is the `b42d85a` cold solution, not a different one.

**Gate 2 — `LitVariants V1`, the source three coolers without draws.**

| | before | after |
|---|---|---|
| Outcome | NONCONVERGENCE, 16 it, resid 0.0536 | **SUCCESS**, 0 published it, resid 1.78e-14 |
| Time | 11.0 s | **4.3 s** |
| "stage-heat ramp stopped" events | 5 | **0** |
| Predictor | — | applied on 4 rungs, largest shift 40.0 K, first rung 2.056e-2 -> 1.490e-2 |

**Gate 3 — V2 and V3 still succeed, with fewer stalls and less time.**

| Variant | before | after |
|---|---|---|
| V2 (coolers + draws) | SUCCESS 10.9 s, 7 it, 3.20e-14, **5** stalls | SUCCESS **5.8 s**, 6 it, 1.60e-14, **0** stalls |
| V3 (coolers + draws + 1 200 kmol/h sump steam) | SUCCESS 15.9 s, 1 it, 6.28e-14, **5** stalls | SUCCESS **13.9 s**, 6 it, 3.09e-14, **5** stalls |

V2 also loses its `condenser-phase-correction` leg. V3 keeps its five stalls: its stalls are on the heat ramp
*after* the steam ramp, where the predictor has already been applied 36 times, and they are subdivided and
recovered as before. Time is 12% lower.

**Gate 4 — `RefreshProbe` cases A to E.** Times are the minimum of three runs on each tree; digests are the
first sixteen hexadecimal characters.

| Case | before | after | time |
|---|---|---|---|
| A dry base CDU17 | SUCCESS, 0 it, 2.26e-14, `7d04da5849ea02da`, Q_c -59.37 MW | identical in every field | 2.38 -> 2.38 s (0%) |
| B dry preset draws + 3 MW cooler | SUCCESS, 2 it, 2.49e-14, `37049be65a869d82`, Q_c -51.10 MW | identical in every field; predictor applied 4x, largest 9.50 K, first rung 2.068e-3 -> 3.960e-5 | 3.82 -> 3.86 s (+1.0%) |
| C wet TJL19, 3 coolers, 3 draws | SUCCESS, 3 it, 1.86e-14, `68ee723dab0ab5d9`, Q_c -91.04 MW | SUCCESS, 3 it, 4.47e-14, same digest and path; predictor applied 9x, largest 1.36 K, first rung 7.262e-3 -> 5.876e-6 | 8.15 -> 8.81 s (+8.1%) |
| D dry 40 MW return tray | NONCONVERGENCE, 16 it, 0.0117 | **SUCCESS**, 7 it, 3.91e-14, Q_c -30.11 MW | 8.59 -> **5.31 s** (-38%) |
| E dry CDU17, 3 draws | SUCCESS, 3 it, 2.13e-14, `981a9f8ec140a097`, Q_c -53.57 MW | identical in every field (no predictor: draws only) | 3.06 -> 2.84 s (-7%) |

No outcome regression; every timing inside ±10% except D, which the predictor fixes.

**Gate 5 — `LitVariants L1`, the full literature CDU** (40 trays, feed 37, draws 10 / 18 / 28 at
136.4 / 143.1 / 45.8 mol/s, coolers 8<-10, 16<-18, 26<-28 at 12.84 / 17.89 / 11.20 MW, 1 200 kmol/h steam at
node 41, no reboiler, condenser 332.15 K, reflux 4.17).

| | at `faefa0a` | after WP-A | after WP-C |
|---|---|---|---|
| Outcome | NONCONVERGENCE | NONCONVERGENCE | **ACCEPTANCE_AUDIT_FAILURE** |
| Time | 246.3 s | 14.8 s | 15.0 s |
| Where it stops | the base 40-stage grid, before any feature | the requested draw rung, resid 0.104 | the requested draw rung, **converged**: "verified final Newton correction", 3 iterations, resid **5.02e-14** |
| Steam ramp | never reached | stalls at 5/12 | stalls at 5/12 ("no admissible Armijo-reducing Newton or descent step", resid 1.81e-4) |
| Failed checks | — | EQUILIBRIUM, TRUNCATION_MASS_DEFECT, GLOBAL_ENERGY_BALANCE | **`WATER_DEW_POINT` value 1.162, limit 1.000** — "water would condense on a tray; three-phase trays are outside the V3 contract" |

So the full literature specification now has a solved MESH state at the authored input. The one remaining
gate is the water dew point, which is a contract question — V3 models two phases per tray — and was **not**
touched, as instructed. The thesis's own top tray is at 93.7 °C with a 59 °C condenser and 1 200 kmol/h of
steam, so the question of whether the converged column keeps its top trays above the water dew point is now
answerable on a real converged state: it does not, by a factor of 1.162. The earlier probe copy reported 1.62
on a schedule-patched build; 1.162 is the number on the production path.

The other 40-tray variants, for completeness (after WP-C, with the gate):

| Variant | outcome | time | note |
|---|---|---|---|
| L2 plain 40 trays | SUCCESS | 5.1 s | 6.39e-14 |
| L3 plain 40 trays, 750 Pa | SUCCESS | 4.9 s | 2.13e-14 |
| L4 plain 30 trays | SUCCESS | 3.3 s | unchanged |
| L5 40 trays, steam only | NONCONVERGENCE | 14.3 s | resid 0.00247, steam ramp stalls at 5/12 (unchanged by the predictor) |
| L6 40 trays, steam + draws | NONCONVERGENCE | 14.7 s | resid 0.535 (0.556 at WP-A) |
| L7 40 trays, steam + 3 coolers | SUCCESS | 17.3 s | 3.51e-14, 0 published iterations (14.8 s / 6.76e-14 / 2 it before) |

### 3.6 Re-pins

| Test | old | new | why |
|---|---|---|---|
| `V3ConvergenceClosureTest.theDefaultClosureLeavesEveryEvaluationCaseExactlyWhereItWas` case D | `NONCONVERGENCE, 16, cold/dwsim-sequential/4-8-15-30/failed-stage-30/liquid-only-condenser/heat-1` | `SUCCESS, 7, cold/dwsim-sequential/4-8-15-30/fine-fd/heat-ramp-1.0/condenser-phase-correction/heat-1` | the predictor converges the case; the pin now also checks the digest recomputation branch it never reached |
| `V3ConvergenceClosureTest.aLooseClosureAcceptsEveryCaseWithinItsOwnScaledLimits` | cases `A, B, C, E` | cases `A, B, C, D, E`, D at 7 default iterations | D was excluded because it plateaued three orders above any admitted closure; it no longer plateaus, and it passes the loose-closure lane |
| `V3PumparoundCalculatorTest.aStalledDropOnlyFloorRefreshCostsABoundedRepeatOnTheFortyMegawattCase` | checkpoints `<= 970 000` (measured 881 492) | checkpoints `<= 525 000` (measured **475 375**) | one heat-ramp stall remains instead of four, so the bounded-repeat probe costs 46% less work; the ceiling keeps its 10% margin |

Nothing else in the suite moved. No tolerance was loosened and no assertion widened: the two closure re-pins
are outcome changes in the strengthening direction (a typed failure became an audited success) and the
checkpoint ceiling was **tightened**, not relaxed.

### 3.7 Tests added

`V3EnergyShiftPredictorTest` (6):

| Test | What it pins |
|---|---|
| `theTridiagonalSystemReproducesAKnownTemperatureShiftOnAHandBuiltColumn` | three trays and a sump with manufactured Cp, one behind a 20% side draw: the Jacobian is written out independently of `assemble`, a shift is chosen, `E = -J dT` is computed from it, and `assemble` + `solveTridiagonal` must recover the shift to 1e-9 — with the sub-diagonal of row 1 and the super-diagonal of the sump row asserted zero |
| `theShiftIsScaledToTheTrayBoundWithoutChangingItsDirection` | a shift inside the bound is untouched; one at twice the bound comes back at exactly half of every entry |
| `aBoundedShiftStillReducesTheLinearisedEnergyResidual` | on the same fixture, the bounded shift leaves `(1 - t) E` row by row, so the bound cannot make the predictor's own rows worse |
| `aSingularSystemIsRefusedRatherThanReturningAnArbitraryShift` | a zero-slope system throws with "singular" rather than returning a shift |
| `theCommonModeSignatureNeedsMoreThanHalfTheRowsOnOneSide` | the signature helper's own contract |
| `thePredictorReducesTheFirstHeatRungResidualOfTheThreeCoolerArrangement` | integration: the source three-cooler no-draw column through the public calculator — the predictor event is published, its first heat rung's scaled energy residual falls (2.0558e-2 -> 1.4905e-2 measured), the case is SUCCESS with an accepted audit, and the ledger carries -41.93 MW |

---

## 4. What is left undone, and why

1. **`WATER_DEW_POINT` on the full literature CDU.** L1 converges its MESH system and then fails the audit at
   1.162. This is the open item the plan named and explicitly excluded from this work. Resolving it is a
   modelling decision — a three-phase tray branch, a different condenser specification, or accepting a
   documented dew-point margin — not a solver fix.
2. **L5 and L6 (40 trays, steam without coolers).** Both still stop at the same steam rung, 5/12, with "no
   admissible Armijo-reducing Newton or descent step". The predictor changes nothing there: it already
   reduces that rung's seed residual by three orders (7.043e-3 -> 5.297e-6) and the stall is downstream of it.
   Note that L7, the same column *with* the three coolers, converges — consistent with the wall analysis, the
   coolers supply the internal liquid the steam-stripped column lacks. Worth a separate look; not in scope.
3. **V3's five heat-ramp stalls.** The wet CDU17 case still subdivides its heat ramp five times. It converges
   and is 12% faster, but the predictor does not remove those stalls, which sit after a 36-rung steam ramp.
4. **Tray-level assertions in tests.** As recorded in section 2.3, there is no hook to a published internal
   tray flow, so the 0.40x qualification asserts the audit's `SIDE_DRAW_SPLIT` rather than tray-23 liquid. If
   internal profiles are ever published, that assertion should be strengthened.
5. **`hasCommonModeSignature` is unused by production code.** It is the plan's proposed gate, implemented,
   unit-tested and documented as measured-not-to-discriminate (section 3.3). It is kept because the next
   person to reach for that gate should find the measurement rather than repeat it. If that is unwanted, it
   is a four-line deletion plus one test.
6. **Probe changes are not committed.** `build/pkgcmp/LitVariants.java` and `build/pkgcmp/RefreshProbe.java`
   were extended in place (predictor events, duty ledger, failed audit checks); `build/` is gitignored.
