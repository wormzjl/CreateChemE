# V3 convergence closure (T5) and product-path band (T4): implementation review

Date: 2026-09-07. Branch `claude/v3-literature-cdu-handoff-3179dc`, base `f245b39` (end of T1–T3).
Plan: `documentation/V3_PHASE_SPECIFIC_TRUNCATION_PLAN.md` sections 10 (T5) and 8 row T4.
Prior work: `documentation/V3_PHASE_SPECIFIC_TRUNCATION_REVIEW.md`, `documentation/V3_TRUNCATION_EVALUATION.md`.

| Commit | Subject | Tests |
|---|---|---|
| `4424d58` | Accept a V3 solve at an authored convergence closure | 431, 0 failures |
| `faefa0a` | Let the flow floor act between the feed tray and a side draw | 431, 0 failures |

Baseline at `f245b39` was 412 tests. Every commit was made with the full suite green
(`./gradlew.bat test --offline`, toolchain JDK 21.0.11).

**Headline for the reader in a hurry.** T5 works as specified and its payoff on the hard cases is zero: the
draw-rate wall and the 40 MW case plateau two to three orders above the loosest closure the range admits, and
a loose closure costs 75 to 100% more wall time on the cases that already converged (section 2.2, 2.3). T4 is
where the win is: 38 to 59 forced trace points removed per draw case, case B 38% faster, case C 13% faster,
no outcome-kind regression anywhere (section 3.2).

---

## 1. T5 — what changed

Files: `CreateChemE`, `ProcessSolveServices`, `V3ColumnCalculator`, `V3SimultaneousColumnSolver`,
`V3ConvergenceEvidence`, `V3AcceptanceAuditor`, `V3InputDigest`, `V3SolverDiagnostics`, `V3ColumnResult`,
`V3ColumnDisplayResult`, `V3TruncationFallback`, `ColumnV3Network`, `ColumnCalculatorV3BlockEntity`;
tests `V3ConvergenceClosureTest` (new, 15), `V3ClosureCodecTest` (new, 3), `V3ColumnCommandTest` (+1).

### 1.1 The knob

```
config columnV3.columnV3ConvergenceClosurePercent, defineInRange(0.0, 0.0, 0.1)
fraction = percent / 100                      -> [0, 1e-3]
tau      = max(1e-8, fraction)                 V3ColumnCalculator.closureTolerance(fraction)
```

`ProcessSolveServices.submitV3Column` reads it on the server thread and freezes it into
`V3ColumnCommand(input, stageTraceCutoffMoleFraction, convergenceClosureFraction)`, which revalidates
`[0, 1e-3]` in its own compact constructor (a config reload cannot change an admitted command). A two-argument
`V3ColumnCommand` remains for the cutoff-only callers and means closure 0.

Public facade: `V3ColumnCalculator.calculate(input, control, cutoff, closure)`. The 1-, 2- and 3-argument
overloads delegate with closure 0, and the 3-argument overload at closure 0 delegates to the *existing*
2-argument path unchanged, so the frozen call chain is byte-for-byte what it was.

### 1.2 How tau travels

`TruncationPolicy` is renamed `SolvePolicy` and gains `closureTolerance`. It is already threaded through every
`solveSingleProblem`, ramp rung, continuation stage, recovery, condenser-phase correction and the coarse
finite-difference recovery, so tau reaches all of them for free. One correction was required: the ramp used to
hand intermediate rungs `SolvePolicy.OFF`, which would have reset the closure as well as the cutoff; it now
uses `policy.withoutCutoff()`, which drops the cutoff and keeps the closure.

`grep SCALED_RESIDUAL_TOLERANCE src/main` now returns exactly one line, the constant's own declaration. No
solve path reads it.

### 1.3 The gates

| Gate | Before | After |
|---|---|---|
| Newton stop `maximumResidual <= scaledTolerance` | 1e-8 | tau |
| `V3ConvergenceEvidence` final log-flow step | `<= MAXIMUM_LOG_FLOW_CHANGE` (1e-8) | `<= closureOf(tau)` |
| `MAXIMUM_LINEAR_BACKWARD_ERROR` | 1e-12 | unchanged |
| `MAXIMUM_TEMPERATURE_STEP_RATIO` | 1.0 | unchanged |
| `verifyFinalNewtonCorrection` / `verifiedCandidate` | `scaledTolerance` parameter | same parameter, now tau; `satisfiesGates(scaledTolerance)` |
| Calculator publication | `Converged && audit.accepted()` | `Converged && audit.accepted() && evidence.satisfiesGates(tau)` |
| Audit `EQUILIBRIUM` | 1e-8 | `max(1e-8, tau)` |
| Audit `CONDENSER_PHASE` split | 1e-8 | `max(1e-8, tau)` |
| Audit `GLOBAL_ENERGY_BALANCE` | `max(1, 1e-6 x largest)` | `max(that, nodeCount x tau x energyScale)`, `energyScale = max(1, F_total x 1e5 W)` |
| Audit `CONDENSER_ENERGY_BALANCE` | `max(1, 1e-6 x largest)` | `max(that, tau x largest)` |
| Audit `LOCAL_COMPONENT_BALANCE`, `ENERGY_BALANCE` | 1.0 | unchanged |
| Audit `TRUNCATION_MASS_DEFECT`, `PHASE_TRUNCATION_DEFECT` | flow-floor mass budgets | unchanged |

Two limits inside `V3AcceptanceAuditor.flashAtWaterMoleFraction` still read `CONDENSER_PHASE_SPLIT_LIMIT`:
they are the auditor's *own independent flash* convergence criterion, not an acceptance limit, and must not
move with the solver's closure. They are deliberately left at 1e-8.

### 1.4 Deviation: the closure lives in the evidence record

The plan says "`V3ConvergenceEvidence.satisfiesGates()` becomes `satisfiesGates(tau)`; the evidence record and
the published diagnostics carry tau". Both were done, and in that order deliberately:

- `V3ConvergenceEvidence` gains a sixth component `closureTolerance`, set by the solver from its own
  `scaledTolerance`. `satisfiesGates()` now means "at the closure this certificate was produced at", and
  `satisfiesGates(tau)` is the explicit form used on the solver's own stop and on the calculator's
  publication gate.
- Consequence: the eight existing no-argument `satisfiesGates()` call sites (`V3ColumnResult`'s own
  invariant, `V3ColumnOutcome.Success`'s invariant, the condenser phase-correction precondition,
  `V3HollandExample32`, and five tests) become correct at any closure without being touched. A gate can no
  longer be evaluated against a tolerance the state was not solved to, which was the main correctness risk of
  threading tau by hand into a dozen call sites.
- A five-component constructor and `unavailable()` keep the frozen default, so the two tests that build
  evidence directly are unchanged.
- `V3ColumnResult.closureTolerance()` therefore *delegates* to the evidence rather than storing a second
  copy. The plan asked for a field; a delegating accessor carries the same value and cannot disagree with the
  gate that admitted it. `V3SolverDiagnostics` does store its own `closureTolerance` component (with a
  ten-component constructor defaulting it), because a typed *failure* has no final-step certificate and must
  still report the closure it was asking for.

`V3ConvergenceEvidence.closureOf(scaledTolerance)` clamps to `[1e-8, 1e-3]`: unit fixtures solve at 1e-9, and
the final-step gate must not become *tighter* than its historical 1e-8 just because a fixture asked for a
tighter residual.

### 1.5 Label and digest

`formulationRevision(input, cutoff, tau)` appends `closureSuffix(tau)`:

```
tau <= 1e-8            -> ""                       (every historical label byte for byte)
otherwise              -> "-closure" + mantissa + "e" + exponent
                          1e-3 -> "-closure1e-3", 5e-4 -> "-closure5e-4", 2.5e-5 -> "-closure2.5e-5"
```
Mantissa is `BigDecimal(value).round(MathContext(6)).stripTrailingZeros().toPlainString()`, exponent is
`floor(log10 tau)`, so the label is canonical and deterministic.

`V3InputDigest.of(..., cutoff, closure)` hashes a named `convergence-closure-bits` field when
`tau > 1e-8` and nothing at all otherwise, exactly as the cutoff field does.

### 1.6 Transport

`V3ColumnDisplayResult` gains `double closureTolerance`. NBT `DATA_VERSION` 7 -> 8 with key
`ClosureTolerance` in the result compound; a version 7 result without the key reads as 1e-8, which is exactly
the closure every version 7 result was accepted at. Wire `WIRE_SCHEMA_VERSION` 7 -> 8; the double is written
*before* the optional duty ledger so the ledger stays the trailing block (the existing
`V3PumparoundCodecTest.aSeventeenthStageDutyIsRejectedByBothTransports` computes an offset back from
`writerIndex`, and appending after the ledger broke it). No screen change.

---

## 2. T5 — measurements

Probe: `build/pkgcmp/RefreshProbe` (now printing the digest and reading `-Dv3.closure`), cap 3, JDK 21.0.11.
Logs `build/pkgcmp/refresh-head-base.*` (HEAD `f245b39`), `refresh-t5-default.*`, `refresh-t5-c1e-3.*`.

### 2.1 The five-case identity check at the default closure

| Case | HEAD `f245b39` | T5 at closure 0 | identical |
|---|---|---|---|
| A dry base CDU17 | SUCCESS, newton 0, resid 2.26e-14, digest `35e93d9c731dbf5e`, `cold/dwsim-sequential/4-8-15-30/fine-fd/liquid-only-condenser` | same | yes |
| B dry preset draws + 3 MW | SUCCESS, newton 4, resid 5.68e-14, digest `aa8b6aeeb7018c3c`, `.../draw-ramp-1.0/liquid-only-condenser/draws-3/heat-1` | same | yes |
| C wet TJL19, 3 PA, 3 draws | SUCCESS, newton 3, resid 1.36e-12, digest `2f3360070876d3ea`, `.../draws-3/steam-1/heat-3` | same | yes |
| D dry 40 MW return tray | NONCONVERGENCE, newton 16, resid 0.0117, `.../failed-stage-30/liquid-only-condenser/heat-1` | same | yes |
| E dry CDU17, 3 draws | SUCCESS, newton 3, resid 5.33e-14, digest `f847e65966cd4fd7`, `.../draws-3` | same | yes |

Outcome kind, solve path, published Newton iterations, scaled residual, digest and both stage-trace event
lines are bit-identical. No re-pin was needed anywhere in the suite for T5.

### 2.2 The five cases at closure 1e-3

| Case | closure 0 | closure 1e-3 | published newton | wall time |
|---|---|---|---|---|
| A | SUCCESS 2.42 s, resid 2.26e-14 | SUCCESS 2.56 s, resid 1.85e-13 | 0 -> 0 | +6% |
| B | SUCCESS 6.17 s, resid 5.68e-14 | SUCCESS 12.51 s, resid 6.83e-13 | 4 -> 3 | **+103%** |
| C | SUCCESS 9.49 s, resid 1.36e-12 | SUCCESS 16.68 s, resid 1.96e-06 | 3 -> 2 | **+76%** |
| D | NONCONV 8.65 s, resid 0.0117 | NONCONV 10.66 s, resid 0.0117 | 16 -> 16 | +23% |
| E | SUCCESS 2.97 s, resid 5.33e-14 | SUCCESS 5.19 s, resid 3.60e-10 | 3 -> 2 | **+75%** |

Every published check is inside its tau-scaled limit and every case that succeeded at the default still
succeeds. **Newton iterations fall everywhere and wall time rises everywhere.** Per-attempt lines
(`refresh-t5-*.err`), case B:

| attempt | closure 0 | closure 1e-3 |
|---|---|---|
| 4-stage cold | 18 it, 77 ms | 11 it, 76 ms |
| 8-stage cold | 35 it, 282 ms | 27 it, 314 ms |
| 15-stage cold | 38 it, 538 ms | 29 it, 568 ms |
| 30-stage cold | 36 it, 948 ms | 28 it, 1038 ms |
| `heat-ramp-0.25` | 5 it, 114 ms | 4 it, 397 ms |
| `draw-ramp-0.125` | 22 it, 892 ms | 11 it, 1806 ms |
| `draw-ramp-0.25` | 6 it, 298 ms | 5 it, 984 ms |

Iterations fall by a third to a half; time per iteration rises three to fourfold on the warm rungs. The
mechanism is `verifyFinalNewtonCorrection`: it runs whenever the residual is at or below the tolerance but
the last accepted step has no certificate at that tolerance. A loose tau puts the residual under the bar many
iterations earlier, at states whose last step was still large, so the solver pays a full fine
finite-difference Jacobian plus a banded solve plus up to eight damped normal-equations solves — each with
its own residual evaluation — on iterations that used to be a plain Newton step. The closure trades Newton
iterations for final-step certifications, and on these cases that trade is a net loss.

This is worth recording plainly: **the plan's expectation of "fewer Newton iterations and lower time" is half
right. The iterations are fewer; the time is not lower.** If the closure is to be a performance knob, the
follow-up is in the certification path (for example, only attempting certification when the last accepted
step is already within a small multiple of tau), not in the closure itself.

### 2.3 The payoff measurement — draw-rate wall and large-draw case

`build/pkgcmp/WallProbe` (new): the sweep-A definition of `documentation/V3_CDU_CONVERGENCE_RISK.md`, which is
exactly `V3SideDrawCalculatorTest.canonicalInput(150_000)` with the three draw rates scaled — 30 trays,
Tia Juana Light, feed 2610.7 kmol/h at 638.15 K, feed stage 24, 150 kPa, condenser 400 K, reflux 2.0,
Q_R 8 MW, draws at trays 8/15/22 of 496/653/149 kmol/h. Scale 1.00 is
`V3SideDrawCalculatorTest.originalLargeDrawCase`; scale 0.25 is the qualified lane. Log
`build/pkgcmp/wall-t5.log`.

| scale | closure 0 | closure 5e-4 | closure 1e-3 |
|---|---|---|---|
| 0.25 | SUCCESS 5.91 s, 4 it, resid 2.31e-14 | SUCCESS 8.18 s, 2 it, resid 1.08e-08 | SUCCESS 9.06 s, 2 it, resid 1.08e-08 |
| 0.40 | NONCONV 7.49 s, 32 it, resid 0.07539 | NONCONV 9.48 s, 32 it, resid 0.07539 | NONCONV 9.85 s, 32 it, resid 0.07539 |
| 0.50 | NONCONV 7.75 s, 32 it, resid 0.4437 | NONCONV 8.65 s, 32 it, resid 0.4437 | NONCONV 9.09 s, 32 it, resid 0.4437 |
| 0.75 | NONCONV 6.59 s, 32 it, resid 0.7247 | NONCONV 7.31 s, 32 it, resid 0.7247 | NONCONV 7.70 s, 32 it, resid 0.7247 |
| 1.00 (`originalLargeDrawCase`) | NONCONV 7.10 s, 32 it, resid 1.233 | NONCONV 7.46 s, 32 it, resid 1.233 | NONCONV 7.73 s, 32 it, resid 1.233 |
| D (40 MW) | NONCONV 8.65 s, resid 0.0117 | — | NONCONV 10.66 s, resid 0.0117 |

**The payoff on the hard cases is zero.** Every failing case ends at the *same* residual and the *same*
iteration count at every closure; the final rung's plateau (0.075 to 1.23) sits two to three orders of
magnitude above the loosest closure the range admits, and the 40 MW rung's 0.0117 sits one order above it.
The failure diagnostics are character-identical. This is exactly what plan section 10's own "What tau will
not fix" paragraph predicted, now measured: these are ramp and starvation failures, not crawls between 1e-8
and tau. Nothing in the admitted range [1e-8, 1e-3] reaches them, and a knob wide enough to reach 0.075 would
be a 7.5% imbalance, which is not a convergence criterion.

The one visible effect is on the case that already converged: 0.25x drops from 4 published iterations to 2,
at a residual of 1.08e-08 instead of 2.31e-14, and costs 38% more wall time.

### 2.4 Tests added for T5

`V3ConvergenceClosureTest` (15 tests):

| Test | What it pins |
|---|---|
| `theDefaultClosureLeavesEveryEvaluationCaseExactlyWhereItWas` (5 cases) | outcome kind, solve path, published iterations, `diagnostics.closureTolerance()`, no `-closure` in the label, and the digest recomputed through the *pre-closure* five-argument `V3InputDigest.of` overload |
| `aLooseClosureAcceptsEveryCaseWithinItsOwnScaledLimits` (A, B, C, E) | SUCCESS at 1e-3; residual and final log-flow step within tau; every audit check inside its own limit; `EQUILIBRIUM` limit is exactly tau; the two 1.0 families unchanged; label suffix; digest differs from the default; iterations do not exceed the default's |
| `theGlobalEnergyClosureLimitIsTheSumOfTheTrayClosures` | `nodeCount x tau x energyScale`, and that the default closure does not widen it |
| `theClosureLabelIsCanonicalAndOnlyAppearsAboveTheDefault` | the six label forms |
| `theAdmittedClosureRangeIsClosedAtBothEnds` | `[0, 1e-3]` on the facade, `[1e-8, 1e-3]` on `requireClosure`, the `closureOf` clamp at both ends |
| `theFinalStepGateMovesWithTheClosureAndTheOtherTwoDoNot` (3 closures) | only the log-flow gate moves; backward-error and temperature-step gates do not; `unavailable(tau)` never passes |

`V3ClosureCodecTest` (3 tests): round trip at 1e-8/1e-6/5e-4/1e-3 with and without a duty ledger through both
transports, the version 7 migration, and both transports rejecting NaN, 1e-2 and 1e-9.

`V3ColumnCommandTest.immutableCommandRevalidatesTheClosureAndCarriesItIntoTheFacade`: the `[0, 1e-3]`
revalidation and that the command's digest matches the facade's at the same closure and differs from the
frozen one.

The identity check deliberately recomputes the digest instead of pinning a hex literal, because T4 changes
the formulation label and would invalidate a literal for a reason that has nothing to do with the closure.
The literal hex values measured at HEAD are in section 2.1 above.

---

## 3. T4 — what changed

Files: `V3TruncationSupport`, `V3ColumnCalculator` (labels only); tests `V3TruncationSupportTest`,
`V3SideDrawAuditTest`, `V3FlashTruncationColumnTest`, `V3PumparoundCalculatorTest`,
`V3StageTraceCalculatorTest`, `V3ConvergenceClosureTest`.

### 3.1 The exact rule

Before, in `derive`:

```
band = [ min(feedTray, minDrawTray) , max(feedTray, maxDrawTray) ]
every point on every node in the band -> BOTH, unconditionally
```
and, after pruning, any ABSENT point on a draw tray dropped the whole support back to identity; and
`requireCompatible` threw on any removed side-draw-tray point.

After:

1. `node == feedTrayNumber` -> `BOTH`, unconditionally. **Unchanged**: the feed tray is the root of every
   material path and of the reachability BFS.
2. Every other node, including every tray between the feed and a draw and the draw trays themselves, is
   decided by the per-phase flow floor and the authored cutoff exactly like any other tray.
3. `breakEquilibriumFreeCycles`, `restoreEmptiedPhases`, `pruneUnreachable` run as before.
4. New `ensureSideDrawLiquid`, after pruning, sweeping downward:
   ```
   for each node with an authored side draw, ascending:
     for each component c:
       if requiresSideDrawLiquid(node, c): phases[node][c] = of(true, retainsVapor)
   ```
   with
   ```
   requiresSideDrawLiquid(node, c) =
        node - 1 >= 0
     && topology.hasLiquidPhase(node) && condenserPhases.hasLiquid(node, c)
     && (node - 1 != condenserNode || refluxRatio > 0)
     && topology.hasLiquidPhase(node - 1) && condenserPhases.hasLiquid(node - 1, c)
     && phases[node - 1][c].retainsLiquid()
   ```
   The pass only ever *adds* a liquid phase. An `ABSENT` point it lifts becomes `LIQUID_ONLY`; a
   `VAPOR_ONLY` point becomes `BOTH`.
5. `breakEquilibriumFreeCycles` runs once more over the result.
6. `requireCompatible` uses the *same* `requiresSideDrawLiquid` predicate:
   ```
   nodeSideDrawRate > 0 && requiresSideDrawLiquid(node, c) && !retainsLiquid(node, c) -> throw
   ```
   The feed-tray rule ("cannot remove a feed-tray point") and the reachability rule are unchanged.

The predicate is one shared static so that the constructive repair and the invariant check can never drift
apart — a drift would surface as an `IllegalArgumentException` escaping `derive` into a typed `INVALID_INPUT`
failure, which is the one failure mode this rule can produce.

### 3.2 Why the ordering is safe

- `ensureSideDrawLiquid` runs *after* `pruneUnreachable` because the guarantee is over the liquid the tray
  above actually keeps, and pruning is what decides that. A point it lifts out of `ABSENT` is reachable by
  the very liquid edge that lifted it (its source is non-`ABSENT`, therefore reachable post-prune), so no
  re-prune is needed and `closurePrunedCount` is unaffected.
- The second `breakEquilibriumFreeCycles` cannot invalidate the invariant just established: the guard fires
  only where `retainsLiquid(node)` is *already* true and sets the point to `BOTH`, so it only ever adds a
  **vapour** phase. It can therefore never newly satisfy `requiresSideDrawLiquid` for the tray below.
- It is run at all because a lifted `LIQUID_ONLY` draw tray directly above a `VAPOR_ONLY` tray is a new
  equilibrium-row-free pair. That particular pair is *not* a null direction — the draw's own withdrawal
  fraction `w > 0` leaves the second material row reading `-w delta` — but the guard is cheap, it did not fire
  on any of the five cases before or after, and relying on a hand argument in the exact family that produced
  the trace-pair wall is not worth the saving.
- `restoreEmptiedPhases` is not re-run: adding phases cannot empty one.

### 3.3 Measurements

`build/pkgcmp/refresh-t4-default.*`, `refresh-t4-cap1.*`, `refresh-t4-c1e-3.*`, `wall-t4-default.log`.

Cap 3, default closure:

| Case | T5 (`4424d58`) | T4 (`faefa0a`) | removed points | one-phase | time |
|---|---|---|---|---|---|
| A dry base | SUCCESS 2.42 s, 0 it | SUCCESS 2.39 s, 0 it | 130/480 -> 130/480 | 7 -> 7 | -1% |
| B draws + PA | SUCCESS 6.17 s, 4 it | SUCCESS 3.85 s, 2 it | 86/480 -> **125/480** | 0 -> 6 | **-38%** |
| C wet TJL19 | SUCCESS 9.49 s, 3 it | SUCCESS 8.30 s, 3 it | 116/608 -> **175/608** | 20 -> 26 | **-13%** |
| D 40 MW | NONCONV 8.65 s, 0.0117 | NONCONV 8.66 s, 0.0117 | 140/480 -> 140/480 | 8 -> 8 | 0% |
| E dry 3 draws | SUCCESS 2.97 s, 3 it | SUCCESS 2.88 s, 3 it | 85/480 -> **123/480** | 0 -> 5 | -3% |

39, 59 and 38 further points removed on B, C and E — exactly the 36-to-59 block
`V3_TRUNCATION_EVALUATION.md` section 5 identified as the forced band, and the first time any of it has been
reachable by the floor. A and D have no side draws, so their band was already the feed tray alone and their
support, iteration counts, residuals and event lines are unchanged (only the formulation label, hence the
digest, moves).

Sink-edge defect over feed, unchanged in order despite the extra removals: B 2.39e-11 -> 2.36e-11,
C 9.10e-13 -> 6.77e-13, E 1.23e-11 -> 3.51e-11. All far inside the 8 x budget.

Cap 1 (`-Dv3.refreshes=1`), the T3 gate: A 2.44 s, B 3.82 s, C **SUCCESS 8.12 s**, E 2.86 s — case C still
holds at a refresh cap of one, and is now faster there than the whole T3 run was at cap 3.

Cap 3, closure 1e-3, after T4: A SUCCESS 2.60 s (0 it), B SUCCESS 8.61 s (1 it), C SUCCESS 12.95 s (2 it),
D NONCONV 10.79 s (0.0117), E SUCCESS 4.51 s (2 it). The closure's wall-time penalty of section 2.2 is
unchanged by T4.

### 3.4 The draw-rate wall at the default closure after T4

| scale | T5 (`4424d58`) | T4 (`faefa0a`) |
|---|---|---|
| 0.25 | SUCCESS 5.91 s, 4 it, resid 2.31e-14 | SUCCESS 5.37 s, 4 it, resid 2.40e-14 |
| 0.40 | NONCONV 7.49 s, 32 it, resid 0.07539, tray 22 D/L 0.667 | NONCONV 6.59 s, 32 it, resid 0.07868, tray 22 D/L 0.751 |
| 0.50 | NONCONV 7.75 s, 32 it, resid 0.4437, tray 22 D/L 1.228 | NONCONV 6.92 s, 32 it, resid 0.4522, tray 22 D/L 1.242 |
| 0.75 | NONCONV 6.59 s, 32 it, resid 0.7247, tray 22 D/L 1.220 | NONCONV 6.17 s, 32 it, resid 1.036, tray 22 D/L 1.802 |
| 1.00 | NONCONV 7.10 s, 32 it, resid 1.233, tray 22 D/L 1.878 | NONCONV 8.59 s, 16 it, resid 1.490, tray 15 D/L 2.665 |

**0.40x does not converge.** The answer to the plan's question is no. Every failing scale stays a typed
NONCONVERGENCE, 5 to 12% faster to fail, with a residual of the same order (0.75x and 1.00x end higher, and
1.00x now names tray 15 rather than tray 22 — still one of the three authored trays, which is what
`V3SideDrawCalculatorTest.originalLargeDrawCase` requires and it passes). Removing the forced band therefore
removed the *mechanism* the evaluation blamed but not the *failure*: at 0.40x and above the final iterate is
already past or near withdrawal fraction 1 at tray 22, which is regime 2 of
`documentation/V3_CDU_CONVERGENCE_RISK.md` — physical starvation of a dry column, not a support question.
No draw case regresses in outcome kind, so T4 stands.

### 3.5 Re-pins

Every one of these changed because the accepted state or the formulation label changed. No tolerance was
loosened and no assertion band widened.

| Location | Old | New | Why |
|---|---|---|---|
| `V3ColumnCalculator.LEGACY_FORMULATION_REVISION` | `v3-dry-mesh-r16` | `v3-dry-mesh-r23` | T4 formulation bump |
| `V3ColumnCalculator.FORMULATION_REVISION` | `v3-dry-mesh-r17-flash-trace` | `v3-dry-mesh-r24-flash-trace` | idem |
| side-draw labels | `…r18-side-draws`, `…r19-side-draws` | `…r25…`, `…r26…` | idem |
| stage-heat label | `v3-dry-mesh-r20` | `v3-dry-mesh-r27` | idem |
| wet labels | `v3-wet-mesh-r21-steam`, `v3-wet-mesh-r22-steam` | `…r28…`, `…r29…` | idem |
| `V3FlashTruncationColumnTest` (2 literals) | `r16`, `r17-flash-trace` | `r23`, `r24-flash-trace` | idem |
| `V3PumparoundCalculatorTest.revisionsGain…` (4) | `r16`, `r17-flash-trace`, `r20-stage-heat`, `r20-flash-trace-stage-heat` | `r23`, `r24-flash-trace`, `r27-stage-heat`, `r27-flash-trace-stage-heat` | idem |
| `V3StageTraceCalculatorTest` (2 literals) | `v3-dry-mesh-r16` | `v3-dry-mesh-r23` | idem |
| `V3ConvergenceClosureTest` case B published iterations | 4 | 2 | case B's accepted state changes |
| `V3TruncationSupportTest.sideDrawSupplyPathsKeepTraceComponentsConnectedAboveAndBelowTheFeed` | band assertions | rewritten as `aSideDrawTrayKeepsTheLiquidItReceivesAndNotAWholeBandBackToTheFeed`, draws moved from trays 1+4 to 1+3 | the test pinned the band rule itself |
| `V3SideDrawAuditTest.nonFeedDrawTrayRetainsItsMaterialPathWithoutExpandingTheWholeColumn` | band assertions | rewritten as `aDrawTrayKeepsOnlyTheTraceItsOwnLiquidSupplyDelivers` | idem |
| `V3SideDrawAuditTest.drawAboveFeedRetainsItsVaporSupplyPathAsWellAsTheLiquidDrawPoint` | band assertions | rewritten as `aDrawAboveTheFeedIsDecidedByItsOwnLiquidSupplyToo` | idem |

The three rewritten tests are the T4 semantics, stated positively:

- draw one tray below the feed: the trace *is* kept, in the liquid only (`retainsLiquid` true,
  `retainsVapor` false) — the guarantee firing;
- draw two trays below the feed: the intervening tray carries no trace, so the draw receives none and the
  point is `ABSENT`, its inflow counted by the sink-edge audit — the guarantee correctly *not* firing;
- draw above the feed with no retained liquid arriving from the condenser: decided by the flow like any
  other tray.

No digest hex literal exists anywhere in the suite; every `V3InputDigest` assertion recomputes from the
problem and the revision string, so the label bump propagates without a re-pin.

---

## 4. Left undone, and deviations

1. **T5's payoff measurement is negative and the knob is not a performance knob.** Sections 2.2 and 2.3. The
   closure reduces Newton iterations everywhere and wall time nowhere; the hard cases are untouched at any
   admitted closure. The knob is still worth having — the user asked for it and it is the correct place to
   express "0.05 to 0.1% closure is good enough" — but nobody should turn it on expecting a faster or more
   convergent solve on today's cases. The follow-up, if the knob is to pay, is the certification path
   (`verifyFinalNewtonCorrection` firing on states whose last step is far larger than tau), not the closure.
2. **The closure lives in `V3ConvergenceEvidence` rather than being threaded to each gate by hand**, and
   `V3ColumnResult.closureTolerance()` delegates to it rather than storing a second copy (section 1.4).
   Deliberate; the alternative had a dozen call sites that could each be given the wrong tolerance.
3. **The GUI is untouched**, as scoped. `V3ColumnDisplayResult.closureTolerance()` is now available to the
   Convergence page and nothing reads it yet. That line is the separate, in-game-verified change plan
   section 10 describes.
4. **The stage-trace event line still has no `phase-defect/feed=` field** — inherited from T2, unchanged, and
   still published as the `PHASE_TRUNCATION_DEFECT` check's value.
5. **Case D (40 MW return tray) is still NONCONVERGENCE**, unchanged by both work packages and at every
   closure. Its heat rung plateaus at 0.0117, an order above the loosest closure. It remains the T2
   regression recorded in `documentation/V3_PHASE_SPECIFIC_TRUNCATION_REVIEW.md` section 4.1, and a
   ramp-schedule question.
6. **The draw-rate wall is unmoved** (section 3.4). The forced band was the mechanism the evaluation blamed,
   it is gone, and 0.40x still fails — with a final iterate whose tray-22 withdrawal fraction is 0.75 rather
   than 0.67, i.e. *closer* to the physical boundary. That points the next investigation at the ramp
   schedule and the starvation boundary (`V3_CDU_CONVERGENCE_RISK.md` items 1, 2, 5), not at truncation.
7. **`ensureSideDrawLiquid` guarantees the liquid a draw tray receives from the tray above, not "the
   component has a retained feed path"** as the task text put it. These differ for a draw two or more trays
   from the feed whose intervening trays drop the component: the component still has a retained feed path
   *somewhere*, but none of it arrives at the draw. Guaranteeing the arriving liquid is the narrower and the
   physically meaningful rule (the draw withdraws a share of the tray's liquid, which is what the tray above
   delivered), and it is the one the relaxed `requireCompatible` rule in the task text states. The
   consequence is visible in the rewritten `aDrawTrayKeepsOnlyTheTraceItsOwnLiquidSupplyDelivers`.
8. **A closure above 1e-8 changes the digest and the label, so an in-game result computed at a nonzero
   closure will not match one computed at zero.** That is intended, and the config comment says so.
9. `build/pkgcmp/WallProbe.java` is new and `build/pkgcmp/RefreshProbe.java` now prints the digest and reads
   `-Dv3.closure` (through reflection, so the same probe file runs against a build with or without the
   closure overload). `build/pkgcmp/reinstrument.sh` gained `WallProbe` and its `PROBE attempt` anchor was
   updated for the `audit(..., policy)` signature.

---

## 5. Reproducing the measurements

```
bash build/pkgcmp/reinstrument.sh
bash build/pkgcmp/runprobe.sh 3 mytag                       # five cases, cap 3, default closure
awk -f build/pkgcmp/refresh-summary.awk build/pkgcmp/refresh-mytag.err

JAVA=/c/Program\ Files/Java/jdk-21.0.11/bin/java
GSON=~/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.10/*/gson-2.10.jar
"$JAVA" -Dv3.closure=1e-3 -cp "build/pkgcmp/classes-ab;$GSON" \
    com.wormzjl.createcheme.science.column.v3.RefreshProbe    # five cases at a closure
"$JAVA" -Dv3.closure=5e-4 -cp "build/pkgcmp/classes-ab;$GSON" \
    com.wormzjl.createcheme.science.column.v3.WallProbe       # draw-rate wall, 0.25/0.40/0.50/0.75/1.00
```
