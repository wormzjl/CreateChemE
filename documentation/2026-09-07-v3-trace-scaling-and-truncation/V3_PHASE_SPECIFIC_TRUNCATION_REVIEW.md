# V3 phase-specific truncation and refresh hardening: implementation review

Date: 2026-09-07. Branch `claude/v3-literature-cdu-handoff-3179dc`, base `3f71fb4`.
Plan: `documentation/V3_PHASE_SPECIFIC_TRUNCATION_PLAN.md`. Measurements:
`documentation/V3_TRUNCATION_EVALUATION.md`. Prior work: `documentation/V3_TRACE_BALANCE_REMEDIES_REVIEW.md`.
Work packages T1, T2, T3. **T4 (product-path band) was not in scope and was not touched.**

| Commit | Subject | Tests |
|---|---|---|
| `cff1f12` | Bound the repeat of a stalled drop-only floor-support refresh | 402, 0 failures |
| `8ca44ee` | Make V3 stage support per phase instead of per point | 410, 0 failures |
| `f245b39` | Reinsert floor-supported points along the flow with an equilibrium split | 412, 0 failures |

Baseline at `3f71fb4` was 401 tests. Every commit was made with the full suite green
(`./gradlew.bat test --offline`, toolchain JDK 21.0.11).

## 1. What is in each commit

### T1 — `cff1f12`

Files: `V3ColumnCalculator`, `V3TruncationSupport`, `V3PumparoundCalculatorTest`.

`V3TruncationSupport.retainedPointCount()` added. In `solveSingleProblem`'s refresh loop, a refresh that
follows a **stalled** attempt and does not increase the retained count no longer gets a fresh full Newton
budget; it gets `STALLED_DROP_REFRESH_ITERATIONS = MAXIMUM_NEWTON_ITERATIONS / 8 = 16`, bounded by the
attempt's own `maximumIterations`. A refresh after a converged attempt, and any refresh that reinserts, keeps
the full budget. `nextIterations` is reset to `maximumIterations` on every other loop pass.

**Deviation from the plan, and why.** Plan section 5 says to `break` instead of re-solving. That was
implemented first and measured, and it is not free:

| Case | plan's `break` | bounded repeat |
|---|---|---|
| D 40 MW return-tray (`RefreshProbe`) | NONCONVERGENCE 7.28 s | SUCCESS 8.41 s |
| `V3PumparoundSteamCalculatorTest.tjl19SolvesSumpSteamWithThreePumparounds` | LINEAR_SOLVE_FAILURE | SUCCESS |

The plan's premise is right — of the four drop-only refreshes after a stall on case D, all four stalled again
with a *worse* final residual (0.070→0.220, 0.006→0.108, 0.007→0.127, 0.008→0.148). What the plan missed is
that the repeat is not useless: the continuation ramp re-derives the next rung's support from the state the
repeat leaves, and on case D that state fell 7 more points below the floor and the smaller problem converged
at `heat-ramp-1.0`. Cutting the repeat off entirely hands the ramp a state with 40 fewer Newton iterations on
it and loses the rung. A sweep of the budget was measured on case D: 6 iterations loses the case, 12, 16 and
20 keep it; 16 was chosen as one eighth of the Newton budget and validated on the whole suite.

Attempt counts did **not** drop from 23 to ≤19 as the plan's gate expects, because the refresh still happens —
what drops is its cost: case D's refresh iterations fell from 175 to 79 and its wall time from 11.07 s to
8.41 s (24%, matching the plan's own expectation of "about 25% on case D").

Test: `V3PumparoundCalculatorTest.aStalledDropOnlyFloorRefreshCostsABoundedRepeatOnTheFortyMegawattCase`.
`newtonIterations()` reports the published attempt alone (7 before and after) and cannot see a shortened
repeat, so the test counts `V3SolveControl.checkpoint()` invocations over the whole cold solve, which is
deterministic for a fixed input.

### T2 — `8ca44ee`

Files: `V3TruncationSupport`, `V3ColumnProblem`, `V3DegreeOfFreedomLedger`, `V3StageBlockLayout`,
`V3MeshResidualEvaluator`, `V3AcceptanceAuditor`, `V3ColumnCalculator`, new `V3StageEquilibriumRatios`;
tests `V3PhaseTruncationTest` (new), `V3TruncationNumericsTest`, `V3PumparoundCalculatorTest`,
`V3StageTraceCalculatorTest`, `V3FlashTruncationColumnTest`.

`V3DryMeshCoordinateMap` and `V3BlockJacobianAssembler` needed **no change**: both take their unknown set from
the ledger. Verified by a new oracle test (below). `V3ColumnInitializer`, `V3ColumnStreamProperties` and
`V3DryMeshState` were confirmed to run on identity support or on exact zeros and were left alone.

### T3 — `f245b39`

Files: `V3ColumnCalculator`, `V3PhaseTruncationTest`.

`liftFloorInflow` becomes `liftFloorSupport` (package-private for the unit test, `V3ThermoModel` rather than
`V3PengRobinsonThermo` so a manufactured thermo can drive it).

## 2. The exact rules as implemented

### 2.1 Mask derivation (`V3TruncationSupport.derive`)

`PointPhases` is `ABSENT | LIQUID_ONLY | VAPOR_ONLY | BOTH`, stored as a two-bit mask per (node, component);
`null` still means identity. Per point, in this order:

1. **Product-path band** (feed tray to the outermost side-draw tray, inclusive): `BOTH`, unconditionally.
   Unchanged from before; T4 would relax this.
2. Otherwise, with `floor_c = flowScale(c) * TRACE_FLOOR_FRACTION`:
   ```
   testLiquid = topology.hasLiquidPhase(n) && condenserPhases.hasLiquid(n,c) && liquidTotal(n) > 0
   testVapor  = topology.hasVaporPhase(n)  && vaporTotal(n) > 0
   lAbove = testLiquid && l >= floor_c ;  vAbove = testVapor && v >= floor_c
   if (!testLiquid && !testVapor)  -> BOTH        // untestable, as before
   else if (!lAbove && !vAbove)    -> ABSENT      // today's rule
   else                            -> of(!testLiquid || lAbove, !testVapor || vAbove)
   ```
   A phase that is not *testable* is never removed: its absence is already structural, so a vapour-only
   condenser component stays `BOTH` in the mask and the structural rule keeps its liquid out.
3. **Authored cutoff** (only when `> 0`) composes on the point as a whole, exactly as before: failing the
   mole-fraction rule turns the point `ABSENT`, never one-phase.
4. **Equilibrium-free cycle guard** (`breakEquilibriumFreeCycles`, not in the plan — see 2.7).
5. **Emptied-phase repair** (`restoreEmptiedPhases`, not in the plan — see 2.7).
6. **Reachability pruning**, now per present phase: liquid leaves node `n` only if `retainsLiquid(n,c)` and
   vapour only if `retainsVapor(n,c)`, so a `LIQUID_ONLY` point passes material downward only and a
   `VAPOR_ONLY` point upward only. The target must not be `ABSENT`. Pruning is still at point granularity: a
   point no present phase can reach becomes `ABSENT`.
7. Identity fallbacks unchanged: a forced side-draw point with no retained feed path, or a structural phase
   emptied by pruning, still falls back to identity with its bounded note.
8. `derive` returns the problem's own identity support only when every point is `BOTH`, nothing was pruned and
   the cutoff is zero — the exact off switch is preserved.

Counters: `truncatedPointCount()` counts `ABSENT` (unchanged meaning), `onePhasePointCount()` counts
`LIQUID_ONLY | VAPOR_ONLY`, `retainedPointCount()` is `total − truncated`, and
`presentPhaseCount() = 2·total − 2·truncated − onePhase`. `isIdentity()` is now
`truncated == 0 && onePhase == 0`. `sameRetention` compares the phase masks.

### 2.2 The presence queries

`V3ColumnProblem` owns the only three the package may use, all delegating to the support so that one
implementation exists:

```
hasLiquidUnknown(n,c) = topology.hasLiquidPhase(n) && condenserComponentPhases.hasLiquid(n,c)
                        && support.retainsLiquid(n,c)
hasVaporUnknown(n,c)  = topology.hasVaporPhase(n) && support.retainsVapor(n,c)
hasEquilibriumRow(n,c)= hasLiquidUnknown && hasVaporUnknown
```

Call sites converted: `V3DegreeOfFreedomLedger.enumerateUnknowns` / `enumerateEquations`,
`V3StageBlockLayout` (block size and both validators), `V3MeshResidualEvaluator.localTerms`,
`materialBalance`'s two condenser-liquid terms and `normalizedPublicPhaseComposition`,
`V3AcceptanceAuditor.finitenessAndTopology`, `V3TruncationSupport.projectSeed` / `enumerateSinkEdges` /
`phasesNonempty` / `reachableFromFeed`, and `V3ColumnCalculator.isLogCoordinateFeasible`.

### 2.3 Rows of a one-phase point

- **Material row: kept.** `liquidIn + vaporIn + feed − liquidOut − vaporOut`, with the absent outlet exactly
  zero and an inflow from a neighbour's absent phase exactly zero. No mass is lost: the whole component leaves
  in the phase that is present. The row scale is unchanged (`materialScale`, the local throughput floored),
  and the absent outlet simply does not enter `largest(...)`.
- **Equilibrium row: absent**, by `hasEquilibriumRow`.
- **Composition builder**: a phase the *support* removed must be exactly zero or the evaluator throws
  (`"V3 truncated component flow must be exactly zero"`), generalising the previous whole-point rule. The
  pre-existing leniency for a structurally liquid-free condenser component with a zero flow is unchanged.
- **Energy row**: unchanged; the phase enthalpy sums are over the present flows and the absent one contributes
  nothing.
- **Ledger squareness**: one unknown and one equation, so `V3StageBlockLayout`'s per-node block stays square.

### 2.4 Seed projection

`projectSeed`: a present phase with a non-positive flow gets `max(Double.MIN_VALUE, floor_c)`; an absent phase
is left at exactly zero. Same rule as before, applied per phase instead of per point.

### 2.5 Reinsertion (T3, with the one-phase half pulled forward into T2)

`liftFloorSupport(problem, thermo, state)` runs inside `prepareAttempt`, so it applies to the deciding seed of
every attempt, not only to a refresh. Node properties are evaluated once per node
(`V3StageEquilibriumRatios.of`), giving `K_c = exp(lnφ_L,c − lnφ_V,c)` and the two phase totals; on a wet
column the vapour total carries the water, because the wet VLE row carries a water-dilution term.

Three passes over the working arrays, each reading what the previous ones wrote:

1. **One-phase points.** The absent phase is lifted to the flow its own equilibrium row would give it,
   `v* = K_c (V/L) l` for `LIQUID_ONLY` and `l* = v L / (K_c V)` for `VAPOR_ONLY`, and only when that reaches
   `FLOOR_REINSERTION_FACTOR * floor_c`. An inflow test is the wrong criterion here and the delivered material
   is the wrong value: the present phase already carries the whole inflow, so an inflow share would put a bulk
   flow into a phase the state says is empty.
2. **Removed points, downward** (condenser to reboiler), then
3. **Removed points, upward** (reboiler to condenser).

   For a removed point the criterion is unchanged — the material inflow `I` (liquid from above after the side
   draw, vapour from below, the feed term on the feed tray, the reflux fraction at tray one) must reach
   `FLOOR_REINSERTION_FACTOR * floor_c` — but `I` is now read from the *lifted* arrays, and the split is by the
   local equilibrium: `l = I / (1 + K_c V/L)`, `v = I − l`. A node with only one present phase takes all of
   `I` into that phase; a node whose properties do not evaluate keeps the previous even split. Both writes are
   `Math.max` against the current value, so a lift never shrinks a flow. Where the split leaves one phase below
   the floor the point re-enters as a one-phase point, which is the same comparison `derive` makes next.

   The side-draw retention factor `1 − withdrawal` is still read from the unlifted state: lifts only move
   trace flows, and the withdrawal fraction is a whole-tray quantity.

### 2.6 Audits

- `TRUNCATION_MASS_DEFECT`: unchanged formula. Now gated on `truncatedPointCount() > 0` instead of
  `!isIdentity()`, which is the same condition it always had (a support was non-identity exactly when it had a
  removed point) and keeps one-phase-only supports from adding an empty check.
  `enumerateSinkEdges` now requires the source phase to be present: a vapour edge needs `hasVaporUnknown(source)`
  (previously unchecked) and a liquid edge `hasLiquidUnknown(source)` (previously the condenser rule alone).
- `PHASE_TRUNCATION_DEFECT` (new): over every one-phase point, the largest equilibrium-implied absent-phase
  flow relative to that component's feed, recomputed freshly from the candidate with the same `K` as the lift.
  Limit `FLOOR_DEFECT_SLACK * FLOOR_REINSERTION_FACTOR * TRACE_FLOOR_FRACTION = 2e-9`
  (`V3TruncationSupport.phaseDefectBoundFraction()`; `FLOOR_DEFECT_SLACK` became package-private). Present only
  when `onePhasePointCount() > 0`, so heat-free and truncation-free audits keep their check counts. A candidate
  with a one-phase point whose node has no evaluable equilibrium fails the check rather than skipping it.
- `FINITE_TOPOLOGY`: present phase flows finite and positive, absent phase flows exactly zero — per phase now,
  so a one-phase point's absent flow is held to an exact zero exactly as a removed point's is.

Measured on the accepted states: `PHASE_TRUNCATION_DEFECT` reads 3.6e-11 (case D's stalled candidate) and
2.8e-11 (case C) against the 2e-9 limit — one and a half orders of margin.

### 2.7 Two structural guards the plan does not list

Both were found by measurement, both are in `derive`, and both only ever *add* phases.

**`breakEquilibriumFreeCycles`.** Two adjacent stages exchange a component in a closed loop: its liquid falls
from `n` to `n+1` and its vapour rises from `n+1` back to `n`. Adding the same `δ` to both flows leaves the two
material rows reading `+δ − δ = 0` and `+(1−w)δ − δ = −wδ`, so with no side draw on `n` the pair is an *exact*
null direction of the material block, and the only rows that can pin it are the two equilibrium rows. A fully
retained point always carried both flows and its own VLE row, which is why this never arose before; a
`LIQUID_ONLY` stage directly above a `VAPOR_ONLY` stage has neither row and reintroduces exactly the trace-pair
rank deficiency the flow floor exists to remove. The upper stage, whose vapour the floor removed, is restored
to `BOTH`. The condenser pair is exempt: the reflux split makes the same determinant `1 − R/(1+R) = 1/(1+R)`,
positive for every authored reflux ratio.

This guard did not fire on any of the five evaluation cases (the probe output is bit-identical with and
without it), so it is a correctness guard rather than a measured fix. It was written after the wet TJL19 case
failed with a trace `COMPONENT_MATERIAL_BALANCE` admitting no Armijo-reducing step; that failure turned out to
have a different cause (see 4.2), but the null direction is real and cheap to exclude.

**`restoreEmptiedPhases`.** The floor may thin a node's structural phase but never empty it: a phase with no
unknown has no composition, no enthalpy and no equilibrium row to write. A node whose every component fell
below the floor in one phase has the phase restored on every point that is not `ABSENT`. This reproduces the
previous point-level rule exactly for that node — such a point used to be retained in both phases on the
strength of the other one — so it is a superset of the old support, not a new approximation. Without it the
two-tray binary column of `V3StageTraceCalculatorTest` lost its tray liquid entirely and fell back to identity
with a note, which broke `zeroCutoffPreservesLegacyDigestStreamsAuditAndDiagnosticsExactly`.

### 2.8 Revisions and the event line

`formulationRevision` labels bumped by seven across every family: `r9→r16`, `r10→r17`, `r11→r18`, `r12→r19`,
`r13→r20`, `r14→r21`, `r15→r22`. Assumption and dataset revisions are unchanged: no physical assumption moved.

Event line gains the one-phase count:
`stage-trace cutoff=…; truncated=A/N; one-phase=P; closure-pruned=…; defect/feed=…`. The plan also asks for
`phase-defect/feed=…` in the same line; it was **not** added, because `stageTraceEvent` has no thermodynamic
model and adding one would put a per-node property evaluation on the publication path for a diagnostic string.
The number is already published, freshly recomputed, as the `PHASE_TRUNCATION_DEFECT` check's value.

No transport, NBT, wire or GUI change: support is attempt-local and never serialized.

## 3. Measurements

`build/pkgcmp/RefreshProbe`, JDK 21.0.11, five cases, 30 stages, same machine and session. `build/pkgcmp/`
holds `refresh-base3.*` (re-measured baseline at `3f71fb4`), `refresh-t1b-cap3.*`, `refresh-t2c-cap3.*`,
`refresh-t2-cap1.*`, `refresh-t3-cap3.*`, `refresh-t3-cap1.*`. The archived pre-work logs `refresh-3.*` and
`refresh-1.*` are the plan's own baseline; both are quoted because the re-measured run was 3 to 10% slower
across the board on this session.

### 3.1 Cap 3 (production)

| Case | baseline (archived) | baseline (re-measured) | T1 | T2 | T3 |
|---|---|---|---|---|---|
| A dry CDU17 base | SUCCESS 2.69 s | SUCCESS 2.94 s | SUCCESS 2.72 s | SUCCESS 2.39 s | SUCCESS 2.40 s |
| B dry preset draws + 3 MW | SUCCESS 6.28 s | SUCCESS 6.90 s | SUCCESS 6.27 s | SUCCESS 6.12 s | SUCCESS 6.15 s |
| C wet TJL19, 3 PA, 3 draws | SUCCESS 9.78 s | SUCCESS 10.78 s | SUCCESS 9.79 s | SUCCESS 10.38 s | SUCCESS 9.45 s |
| D dry 40 MW return-tray | SUCCESS 11.07 s | SUCCESS 11.50 s | SUCCESS 8.41 s | **NONCONV 8.45 s** | **NONCONV 8.64 s** |
| E dry CDU17, 3 draws | SUCCESS 3.76 s | SUCCESS 3.77 s | SUCCESS 3.41 s | SUCCESS 3.22 s | SUCCESS 3.23 s |

Against the archived baseline, T3 is 11% faster on A, 2% faster on B, 3% faster on C and 14% faster on E — the
plan's "±10% or better" gate is met on A, B and E and beaten on C. Case D is discussed in 4.1.

Attempts and refresh cost at cap 3:

| Case | attempts (base / T1 / T2 / T3) | refresh iterations (base / T1 / T2 / T3) |
|---|---|---|
| A | 8 / 8 / 8 / 8 | 9 / 9 / 2 / 2 |
| B | 25 / 25 / 25 / 24 | 29 / 29 / 20 / 10 |
| C | 23 / 23 / 26 / 22 | 43 / 43 / 79 / 57 |
| D | 23 / 23 / 22 / 22 | 175 / 79 / 82 / 82 |
| E | 15 / 15 / 16 / 15 | 24 / 24 / 20 / 10 |

### 3.2 Cap 1 (`-Dv3.refreshes=1`) — the T3 gate

| Case | baseline (archived) | T2 | T3 |
|---|---|---|---|
| A | SUCCESS 2.71 s | SUCCESS 2.43 s | SUCCESS 2.37 s |
| B | SUCCESS 7.03 s | SUCCESS 5.92 s | SUCCESS 6.07 s |
| C | **LINEAR_SOLVE_FAILURE 17.08 s** | **LINEAR_SOLVE_FAILURE 16.71 s** | **SUCCESS 9.06 s** |
| D | SUCCESS 11.28 s | NONCONV 9.06 s | NONCONV 8.52 s |
| E | SUCCESS 3.47 s | SUCCESS 3.27 s | SUCCESS 2.92 s |

Case C at cap 1 is the plan's headline gate and it is met: the sweep restores the whole re-entering profile in
one lift, so the refresh cap stops binding.

### 3.3 Reinsertion initial residuals — the other T3 gate

Every reinsertion attempt, baseline versus T3:

| Case | baseline | T3 |
|---|---|---|
| B | 426→… three refreshes at init 2.06, 2.07, 2.11; 5 iterations each | init 0.0144, 0.100, 0.0993; 2, 3, 3 iterations |
| C | 426→430 init 4.65 (11 it), 430→433 init 3.23 (6 it), 433→435 init 2.05 (5 it) | 426→435 in **one** lift, init 0.112 |
| E | init 2.06, 2.07, 2.10; 5 iterations each | init 0.0145, 0.101, 0.0992; 2, 3, 3 iterations |

Gate "no reinsertion attempt starts above scaled residual 0.5": met, worst 0.112.

### 3.4 One-phase points on the published support

`build/pkgcmp/OnePhaseProbe` (new), and the `one-phase=` field of the stage-trace event.

| Case | one-phase points | flow unknowns before → after | which points |
|---|---|---|---|
| A dry base | 7 | 692 → 685 | PC06…PC12 LIQUID_ONLY, one tray each, above their condensation front |
| B preset draws + PA | 0 | — | every asymmetric point is inside the forced product-path band |
| C wet TJL19 | 20 (19 at cap 1) | 974 → 954 | TJL_PC12/PC13 LIQUID_ONLY on nodes 25–31; ethane, propane, the butanes and isopentane VAPOR_ONLY on nodes 29–31 |
| D 40 MW | 8 | — (candidate is a typed failure) | heavies LIQUID_ONLY |
| E dry 3 draws | 0 | — | as B |

This is exactly the set the evaluation predicted: heavy pseudo-components in the trays above their
condensation front do not evaporate, and light ends in the steam-stripped sump section do not condense. It is
also 1 to 2% of flow unknowns on a CDU, exactly as the evaluation estimated — the case for the mask remains the
VDU, not this column.

`build/pkgcmp/phase-trace-t3.log` holds the re-run of `PhaseTraceProbe`. Note that probe bins by structural
phase presence, not by the mask, so its `unknowns(flow)` column is the *pre-mask* count; the masked counts are
in the table above. Its "worst l/v" column now reports `Infinity` where the absent phase is an exact zero,
which is the intended reading.

## 4. Regressions, and what was traded for what

### 4.1 The 40 MW return-tray case (case D) goes back to NONCONVERGENCE

`V3PumparoundCalculatorTest.aLargeSingleTrayDutyStaysWithinTheTypedFailureContract` is unchanged and still
passes — it never pinned the outcome — but the case did converge at `3f71fb4` and does not converge after T2.

What happens: the heat rung at 0.375 stalls, the ramp subdivides it three times and then jumps to 1.0, and
whether `heat-ramp-1.0` lands in a converging basin depends on the exact stalled state it is seeded from. With
the phase mask, `heat-ramp-1.0` starts at retained 347 instead of 344 and crawls from a scaled residual of
0.0121 to 0.0117 with the dominant residual an `ENERGY_BALANCE` on node 17 — not a trace material row.

Ruled out as causes, each by an explicit experiment:

- **The bounded stalled-refresh budget of T1.** Re-running case D with the full budget under T2 also fails
  (11.40 s, residual 0.124). The bounded budget makes it cheaper, not worse.
- **The one-phase lift.** Disabling `liftAbsentPhase` entirely leaves case D bit-identical: the lift never
  fires there.
- **A wrong local-block Jacobian.** New oracle test (5.1) compares `assembleLocal` against the uncoloured
  finite-difference Jacobian on a problem with `LIQUID_ONLY` and `VAPOR_ONLY` points at both step sizes; they
  agree to 1e-6 relative.
- **An equilibrium-free 2-cycle.** The guard of 2.7 is in place and does not fire on this case.

What it looks like instead: a fragile point on a condenser phase transition. Under the *full* repeat budget the
T2 run reaches a candidate whose only failing audit check is `CONDENSER_PHASE` (`EQUILIBRIUM` 7.9e-9 passes,
`GLOBAL_ENERGY_BALANCE` passes) — the condenser phase correction that rescued the case at `3f71fb4` would fix
it, but the correction only runs after a *converged* attempt. That is a ramp-schedule question, exactly as
`V3_TRUNCATION_EVALUATION.md` section 4 already concluded about this case, and the earlier review already
recorded its convergence as an uninvestigated, unverified side effect of the floor-support work.

A 40 MW duty on one tray of a column whose entire condenser removes 46 MW is an extreme input. It was not
worth blocking the work package on, but it is a real loss and it should be re-checked when the ramp schedule
is next touched.

### 4.2 Two intermediate failures that the plan's own text predicted, and their fixes

- The plan's `break` in T1 costs two cases (4.1 table, section 1). Bounded repeat instead.
- Comparing **retained points** rather than **present phases** in that guard cost the wet TJL19 steam rung at
  0.375: the useful refresh there reinserts a phase without changing the point count, so it was bounded to 16
  iterations and the rung was abandoned. The plan says "(after T2: no more present phases)"; `presentPhaseCount()`
  was added in T2 and the guard switched to it, which restored the case.

## 5. Tests

### 5.1 New — `V3PhaseTruncationTest` (10 tests)

Fixture: the manufactured ternary column of `V3TruncationNumericsTest` with a hand-built trace profile that
produces all four phase states at once (ABSENT at the condenser and below tray three, VAPOR_ONLY on tray one,
BOTH on the feed tray, LIQUID_ONLY on tray three).

| Test | What it pins |
|---|---|
| `oneDecidingStateProducesAllFourPhaseStatesAndCountsThemSeparately` | mask derivation, all four states, `truncatedPointCount` 3, `onePhasePointCount` 2, `presentPhaseCount` 28, bounds checking |
| `aOnePhasePointKeepsItsMaterialRowAndLosesItsEquilibriumRow` | the three presence queries; ledger unknown list, equation list and the two material rows' references; `ledger.isValid()` |
| `aOnePhasePointConservesItsComponentIntoThePhaseThatIsPresent` | the LIQUID_ONLY and VAPOR_ONLY material rows term by term; exact zeros in the absent phases; no VLE row off the feed tray |
| `coordinateMapRoundTripsAStateWithAOnePhasePoint` | encode/decode with one-phase points; absent phases decode to exact zero |
| `theLocalBlockJacobianMatchesTheUncoloredOracleWithOnePhasePoints` | `assembleLocal` against `V3FiniteDifferenceJacobian.evaluate` at both step sizes |
| `phaseDefectBoundsTheEquilibriumImpliedAbsentFlowAndFiniteTopologyAcceptsItsExactZeros` | `FINITE_TOPOLOGY` accepts exact zeros; `PHASE_TRUNCATION_DEFECT` limit 2e-9 and passes; `TRUNCATION_MASS_DEFECT` still counts the two sink edges |
| `phaseDefectRejectsAnAbsentPhaseTheEquilibriumRowWouldFill` | the same check fails on a constructed state whose implied absent flow is 1e6 times larger |
| `aNodeNeverLosesAStructuralPhaseToTheFloorAndAnIdentitySupportAddsNoCheck` | `restoreEmptiedPhases`; a point-only support adds `TRUNCATION_MASS_DEFECT` and not `PHASE_TRUNCATION_DEFECT` |
| `aRemovedTraceProfileIsRestoredAcrossAllOfItsTraysInOneSweep` (T3) | six nodes restored in one lift; material closure `l + v = I`; the split reproduces `v/l = K V/L`; the condenser is reached by the upward pass |
| `aOnePhasePointIsLiftedOnlyWhenItsImpliedAbsentFlowReachesTenFloors` (T3) | a point implying 0.004 mol/s is lifted to it and becomes BOTH; a point implying half a floor is untouched and stays LIQUID_ONLY |

### 5.2 New — `V3PumparoundCalculatorTest.aStalledDropOnlyFloorRefreshCostsABoundedRepeatOnTheFortyMegawattCase` (T1)

Counts `V3SolveControl` checkpoints over the whole cold solve and asserts the typed contract.

### 5.3 Re-pinned values

Every one of these is a value that changed because the accepted state or the formulation label changed. No
tolerance was loosened and no assertion band widened.

| Location | Old | New | Why |
|---|---|---|---|
| `V3ColumnCalculator.LEGACY_FORMULATION_REVISION` | `v3-dry-mesh-r9` | `v3-dry-mesh-r16` | T2 formulation bump |
| `V3ColumnCalculator.FORMULATION_REVISION` | `v3-dry-mesh-r10-flash-trace` | `v3-dry-mesh-r17-flash-trace` | idem |
| `V3ColumnCalculator` side-draw labels | `v3-dry-mesh-r11-side-draws`, `v3-dry-mesh-r12-side-draws` | `…r18…`, `…r19…` | idem |
| `V3ColumnCalculator` stage-heat label | `v3-dry-mesh-r13` | `v3-dry-mesh-r20` | idem |
| `V3ColumnCalculator` wet labels | `v3-wet-mesh-r14-steam`, `v3-wet-mesh-r15-steam` | `…r21…`, `…r22…` | idem |
| `V3FlashTruncationColumnTest` (2 literals) | `r9`, `r10-flash-trace` | `r16`, `r17-flash-trace` | idem |
| `V3PumparoundCalculatorTest.revisionsGain…` (4 literals) | `r9`, `r10-flash-trace`, `r13-stage-heat`, `r13-flash-trace-stage-heat` | `r16`, `r17-flash-trace`, `r20-stage-heat`, `r20-flash-trace-stage-heat` | idem |
| `V3StageTraceCalculatorTest` (2 literals) | `v3-dry-mesh-r9` | `v3-dry-mesh-r16` | idem |
| T1 test checkpoint pins (written in T1, re-pinned in T2) | 839 654 bounded / 1 042 879 full, ceiling 925 000 | 881 492 bounded / 1 080 557 full, ceiling 970 000 | the phase mask changes the trajectory of the same case |

No digest literal is hard-coded anywhere in the suite: every `V3InputDigest` assertion recomputes the digest
from the problem and the revision string, so the label bump propagates without a hex re-pin.

### 5.4 Fixture generalisation (not a re-pin)

`V3TruncationNumericsTest.ManufacturedThermo` asserted `composition[3] == 0` on every node but the feed tray;
it now derives the set of nodes the trace may appear on from the state it is constructed with. The standard
fixture's behaviour is unchanged (its trace is only on node 2).

## 6. Left undone, and deviations

1. **T4 was out of scope.** The product-path band is still forced `BOTH` for every component, which is why
   cases B and E have zero one-phase points: their asymmetric points are all inside the band.
2. **`phase-defect/feed=` is not in the stage-trace event line** (2.8). The value is published as the
   `PHASE_TRUNCATION_DEFECT` check.
3. **T1 bounds the stalled drop-only repeat rather than skipping it**, and the plan's attempt-count gate
   (23 → ≤19) is therefore not met; the cost gate is (section 1).
4. **Case D regresses from SUCCESS to NONCONVERGENCE** (4.1). Not root-caused beyond eliminating the four
   candidate mechanisms listed there; it is a ramp-schedule fragility, and the earlier review had already
   flagged the case's convergence as unverified.
5. **The plan's T2 reinsertion rule ("lift an absent phase to `max(floor, inflow-share)`") was not
   implemented.** On a one-phase point the present phase already carries the whole inflow, so an inflow share
   would have put a bulk flow into an empty phase and made every one-phase point flip back to BOTH at the next
   `derive`, defeating the work package. The equilibrium-implied lift of plan section 4 step 3 was pulled
   forward from T3 instead. T3 then added the sweep and the split for fully-removed points.
6. **Two guards were added that the plan does not list** (2.7). The cycle guard is a correctness guard that
   never fired on the evaluation cases; the emptied-phase repair is load-bearing.
7. **`prepareAttempt` still falls back to the identity support when the reduced ledger fails structural
   validation** — pre-existing, unchanged, still not observed on any case.
8. `liftFloorSupport` now costs one fugacity pair per node per attempt (about 1 500 property calls over a
   whole cold solve of case D). It did not show up in any timing, but it is a new cost on the hot path.
9. The mass-defect *bound* argument now has a second half — a one-phase point's implied absent flow — that is
   only guaranteed after a refresh has confirmed no lift is needed, exactly like the sink-edge bound. When the
   refresh cap binds, both audits still recompute the actual value from the candidate, so they remain safe.

## 7. Reproducing the measurements

```
bash build/pkgcmp/reinstrument.sh          # re-copies the science sources, re-applies the two edits, compiles
bash build/pkgcmp/runprobe.sh 3 mytag      # RefreshProbe at cap 3 -> build/pkgcmp/refresh-mytag.{log,err}
bash build/pkgcmp/runprobe.sh 1 mytag1     # cap 1
awk -f build/pkgcmp/refresh-summary.awk build/pkgcmp/refresh-mytag.err
```

`reinstrument.sh` and `runprobe.sh` are new and replace the manual recipe in plan section 9; the two
instrumentation edits (the `-Dv3.refreshes` constant and the `PROBE attempt` line after the audit) are applied
by the script, so they no longer have to be re-applied by hand. `build/pkgcmp/OnePhaseProbe.java` and
`build/pkgcmp/PhaseMaskProbe.java` are new; `PhaseTraceProbe` still works unchanged.
