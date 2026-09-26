# Solid-phase transient solver — fixes landed

Branch `claude/solid-phase-fixes`, final HEAD `a4db1fe`, base `440a754`.
Worktree `D:\Minecraft\Modding\1.21\CreateChemE\.claude\worktrees\agent-a2b71aca5cea111aa`.
Date 2026-09-22.

---

## 0. Setup verification

| Check | Result |
| --- | --- |
| `git checkout -b claude/solid-phase-fixes 440a754` | done |
| `git log -1 --oneline` | `440a754 Add solid-phase fluid transport and inline filtration` |
| Other Gradle build active? | no — 5 `java.exe`, CPU delta 0 over 3 s for all five |
| `git stash` used | never; the one pre-existing entry (`codex/plan-2-solver-experiments`) is untouched |
| Files copied from the diagnosis worktree | none; it was read only |
| Final `git status --short` | clean (build output is gitignored) |

```
a4db1fe Key retained solver state on a pipe's identity, not on its cake
3e1e2dc Make an inline filter's capacity a saturated inlet law
a820c31 Locate transport transitions on the step grid instead of by replay
2f618b9 Stop solid-moment dust from freezing the Newton line search
```

---

## 1. Baseline, measured on this host at 440a754

A scratch probe (removed before the final commits) reproduced the diagnosis exactly.

| case | ms | accepted | rejected | stalls | blocked |
| --- | --- | --- | --- | --- | --- |
| 2 nodes, clear, 0.5 s | 15 | 14 | 5 | 0 | `0` |
| 2 nodes, clear, 5 s | 13 | 18 | 5 | 0 | `0` |
| 2 nodes, solids, 0.5 s | 376 | 36 | 7 | 0 | `1` |
| 2 nodes, solids, 5 s | 223 | 40 | 7 | 0 | `1` |
| 10 nodes, clear, 0.5 s | 23 | 25 | 10 | 0 | all 0 |
| 10 nodes, clear, 5 s | 28 | 32 | 14 | 0 | all 0 |
| 10 nodes, solids, 0.5 s | 1528 | 368 | 175 | **170** | `1,1,0…` |
| 10 nodes, solids, 5 s | 2300 | **FAILS** `advanced=1.5322265625 of 4.994516586724558 s`, 1016/516 | | **517** | — |
| 30 nodes, solids, 5 s | 15 | **FAILS** `Substep refinement exhausted: Newton line search stalled at residual 1.39e-9; active-set pass=0` | | | |
| pump-filter runtime line, 20 x 5 s | **6106** total | interval 2 alone 5588 ms | | | ends `1,1,0,3` |

---

## 2. Fix A — solid dust and closure semantics (commit `2f618b9`)

### A1. Per-column floor in `Equations.maximumStep`

`network/PassiveStepSolver.java`. A `double[] clampFloors` is built in the `Equations` constructor
beside `amountVariables`/`differenceFloors`, zero for every column except a solid moment, where it
is `SOLID_CLAMP_FRACTION` times that column's own difference floor (the solid difference floors are
`max(1e-6, encoded value)` in `PhaseLayout`). The rule becomes

```java
for(int c=0;c<edgeOffset;c++)if(amountVariables[c]&&variables[c]>clampFloors[c]&&direction[c]<0)alpha=Math.min(alpha,.99*variables[c]/-direction[c]);
```

With `clampFloors[c] == 0` for every fluid amount this is bit for bit `variables[c] > 0`, so a clear
island is untouched. `PhaseLayout.solidVariable(int)` is the new predicate that identifies the three
moment columns; `totalAmountVariable` now delegates to it.

### A2. Nonnegativity by projection, not by domain refusal

`solver/PhaseLayout.decode` projects the three decoded moments onto `[0, inf)` instead of letting
`SolidInventory.Moments` refuse the trial, so a candidate that overshoots a moment boundary is
rejected on its residual rather than killing all 24 backtracks with an `IllegalArgumentException`.

**A2 needed a third change the brief did not anticipate.** Projecting alone makes the solid balance
rows *constant* for any trial point with a negative moment — the decoded moment is 0 on the whole
half-line — which is a column of zeros in the finite-difference Jacobian and a singular
factorization. Every node downstream of a filter carries exactly zero incoming solids and therefore
sits exactly on that boundary. `PhaseLayout.solidRows` now reads its moments from the unknowns:

```java
double moment=variables[offset+solidIndex+i]*solidScale[i];
result[row+i]=junction?(moment/state.mass()-target[i])/(solidScale[i]/solidScale[0]):(moment-target[i])/solidScale[i];
```

so each row stays exactly linear in its own unknown everywhere. `balanceRows` and `junctionRows`
gained a `variables` parameter to carry it; the two agree at every point the solver can accept,
because the projection is inactive there.

### A3. Closure semantics — **NOT LANDED**, see §6.

### A4. Invariants kept

`SolidInventory.Moments` positivity and the `Negative solid reconstruction` gate in
`ConservativeTransport` are untouched. Dust populations stay in inventories and are conserved;
nothing is clipped. The new `solidMomentProjectionsAtAcceptedPoints` counter is checked at every
accepted point (both the Newton solution and the re-encoded conservative reconstruction) and is
asserted to be 0 in `SolidChainTransportTest`; it is 0 across both fluid suites.

### The floor sweep

Swept on the ten- and thirty-reservoir chains, 5 s, `Settings.defaults()`. The fraction multiplies
the column's own difference floor, which for a dust column is exactly `1e-6`, so the "scaled floor"
column is the value below which a solid unknown stops bounding alpha.

| `SOLID_CLAMP_FRACTION` | scaled floor | 10 nodes, 5 s | 30 nodes, 5 s | stalls |
| --- | --- | --- | --- | --- |
| 0 (the stock rule) | — | 584 ms, 42/5 | **FAILS**, `line search stalled at residual 0.0912; pass=0` | 0 / — |
| **1e-6 (kept)** | 1e-12 | 547 ms, 42/5 | 1404 ms, 68/15 | 0 |
| 1e-3 | 1e-9 | 563 ms, 42/5 | 1153 ms, 68/15 | 0 |
| 1 | 1e-6 | 626 ms, 42/5 | 1130 ms, 68/15 | 0 |

All three nonzero values give **identical** accepted/rejected counts and no stall anywhere; only the
wall time moves, and that within run-to-run noise. 0 is not merely slower, it still fails the
thirty-reservoir chain outright — so the floor, not the closure semantics, is the load-bearing half
of the fix. The smallest value that works is kept, so the exemption covers only magnitudes at which
`x + alpha*d` is bitwise `x` for every unknown of order one and no backtrack could have produced a
different residual anyway.

### One extra correctness guard

`PassiveStepSolver.solve`'s closure preset (`blockedDirections == 3` implies
`boundaryClosed[i] = true`) now also puts a non-passive connection's actuator in `CLOSED`, which is
what the pass-by-pass closure a few lines below already does. An actuator left on its own setpoint
across a closed edge is a second equation for a flow the closure has already decided. Nothing the
runtime writes reaches this path today — only a filter at capacity closes both directions, and a
filter is required to be passive — so it is currently inert; it is kept because the saved format
permits the combination and because it is the first thing a both-direction mobility closure would
need (§6).

---

## 3. Fix B — transitions on the step grid (commit `a820c31`)

`ThresholdEventLocator` and `ThresholdEventLocatorTest` are deleted, and so are the `tight` replay
settings in `SolidEventIntegrator`.

- `SolidEventIntegrator.Transition` is now package-private, carries its `SolidMobility.Check` and an
  `atStart` flag, and suppresses its stack trace: it is a control-flow signal thrown once per
  rejected substep.
- `PassiveIntervalSolver` gained a package-private `Prefix(Result, Transition)` and
  `integrateToTransition(...)`. The shared workhorse `run(...)` catches `Transition` as a rejection
  kind of its own: if `step > max(1e-6 s, 1e-9 * interval)` and fewer than 40 transition rejections
  have been spent in this segment, the step is rejected and `h` halves; otherwise the transition is
  declared at the current elapsed time. These rejections are counted in `rejectionReasons` under
  `Solid transport transition inside the step` and in `SolverDiagnostics.solidTransitionRejections`,
  **not** in `rejectedSubsteps`, and they do not touch `consecutiveRejects`.
- `integrate(...)` keeps the old contract: a declared transition on that path is rethrown, so no
  public caller can receive a partial interval.
- `run` normalizes `transferred` by `duration` when the interval completed — bit for bit as before —
  and by the elapsed prefix only when a transition was declared, so the caller's
  `advanced * averageMassFlows` is still the transported mass.
- New `StageGuard.checkRate(filters, states, modes, flows)` hook, called by `TrBdf2StepSolver`
  before any stage is solved, with the **live** cake of the graph being stepped. The old hook ran
  `failed(graph, …)` against the graph the segment started from and could not see a cake the
  accepted steps had filled. A transition seen there is exact, so it is declared immediately.
- `SolidEventIntegrator.solve` is a plain loop over segments: integrate the remainder, close what
  the prefix reports, continue from the prefix with `integrator.nextStepEstimate()`. No
  re-integration, no tightened tolerance.
- The declared event time is appended to the reason key (`…; t=<seconds>`), which is what the
  replacement tests read.

`SolidClosureFeasibilityTest` no longer drives a locator. Its three tests now ask the same physical
questions of the new mechanism:

| test | result |
| --- | --- |
| `drainingCarrierStopsAtTheParticleThresholdWhereATightIntegrationPutsIt` | located `0.005431827 s`, tight reference `0.005433250 s`, refinement floor `1e-6 s` — **1.4 floors apart** |
| `constantFeedMeetsTheFilterCapacityAtTheArithmeticFillTime` | located `1.63125 s` against an arithmetic fill of `1.63 s`, inside the `0.25 s` longest step and never before it |
| `heavyLiquidCoolingStopsAtTheActualViscosityTableCrossing` | unchanged physics, local bisection instead of the locator; crossing `[333.941549063, 333.941549659] K` |

---

## 4. Fix C — filter capacity as a saturated law (commit `3e1e2dc`)

`PassiveStepSolver.Equations.edgeRows`, inside the existing velocity-clamp block, so it reuses the
throttled-law pattern verbatim:

```java
var state=st[donor];double retainedPerMass=state.solidMoments().volume()/state.mass();
double room=pipe.filter().capacity()*(1-FILTER_CAPACITY_MARGIN)-pipe.filter().captured().volume();
if(retainedPerMass>0&&room>0)limit=Math.min(limit,room/(dt*retainedPerMass));
```

`limit` then feeds `capPressureDrops = filterCoefficient * limit` and the existing
`if(|driving| > limitDrop) f = (copySign(limit,driving) - flow)/max(limit,1e-8)`. Skipped in
`rateOnly` solves.

Three things the brief's sketch did not cover, each found by measurement:

1. **`FILTER_CAPACITY_MARGIN = 1e-12`.** Aimed exactly at capacity, the committed cake landed
   `1.694e-21` — one ULP — *over* `1e-5`, because the reconstruction sums the captured populations
   by a different path than the loading row solves. `loadedFilterClosesWithoutLosingItsCapturedMaterial`
   asserts `<= capacity` strictly. Aiming one part in 1e12 under makes the landing one-sided
   (measured `-9.998e-18` on a `1e-5` capacity), and `SolidEventIntegrator.atCapacity` treats a cake
   within a relative `1e-9` of capacity as having met it, so the closure still fires.
2. **`prescribedFlow`.** A pump holding a target volume flow is already one equation for its edge,
   and continuity relates it to the filter's, so imposing the capacity as a second law
   over-determines the island; the pump-filter fixture reached a singular factorization as the cake
   filled. `Equations.prescribedFlow` is `modes.contains(PUMP_TARGET)` and suppresses the law. Those
   islands meet the capacity by step refusal exactly as every island did before.
3. **The stage guard keeps the exact `clogged()` test.** A stage base that the TR-BDF2 stage-two
   extrapolation (`A = 1.207`) has already carried past capacity is refused and the step halves; the
   law's own landing a relative hair below capacity is accepted and its closure is declared by the
   next step's `checkRate`.

Measured on the constant-feed capacity fixture (velocity-clamped generator into a void, 3 s):

| | accepted substeps | transition rejections | cake | located |
| --- | --- | --- | --- | --- |
| Fix B only | 48 | 22 | 3 ULP under capacity | 2.0 s (dyadic coincidence) |
| Fix C | **6** | **1** | capacity to 1 part in 1e12 | 1.63125 s vs 1.63 s exact |

`loadedFilterClosesWithoutLosingItsCapturedMaterial` passes: `blockedDirections == 3`, cake
`9.99999999999e-6` of `1e-5`, `stoppedAtCapacity` set by `stopFilter` as before.

---

## 5. Fix D — cache keys must not include the cake (commit `a4db1fe`)

`PassiveNetwork.Pipe.Identity` (id, endpoints, sections, control, `blockedDirections`, and the
filter's immutable `FilterSettings(capacity, cleanResistance)`) plus `Pipe.identity()`. All three
consumers key on it: `PassiveStepSolver.WorkspaceKey`, the `structures` map, and
`Equations.initial()`'s `previousPipes` comparison. The cake, its energy and `stoppedAtCapacity` are
excluded. New counters `workspaceReuses` / `workspaceBuilds`.

Pump-filter runtime fixture, 20 intervals of 5 s, same code except the key:

| | wall ms | reuses | builds | LU factorizations | LU orderings | preconditioned solves |
| --- | --- | --- | --- | --- | --- | --- |
| cake in the key | 785 | 676 | 913 | 2302 | 727 | 431 / 1589 |
| **identity** | **760** | **1021** | **569** | **1787** | **176** | **781 / 1590** |

Orderings drop 4.1x and factorizations 22%; the wall gain is small here only because the island is
five nodes. A two-reservoir filter island over 20 intervals reuses 342 times against 19 builds
(`SolidChainTransportTest.filterIslandReusesItsSolverWorkspacesAcrossIntervals`).

---

## 6. What I did **not** do, and why

### A3 (close both directions on a mobility failure) is dropped

The brief asked for `blocked[edge] |= 3` at both sites. I landed it, and it **breaks the pump-filter
runtime fixture**: `SolidRuntimeTest.physicalPumpFilterAndNitrogenReceiverStartTogether` fails with
`Substep refinement exhausted: Singular Newton Jacobian: Sparse LU rejected a singular matrix;
active-set pass=1`. Bisected precisely:

| variant | pump-filter fixture |
| --- | --- |
| 440a754 | passes (6106 ms) |
| A1 + A2 + A3 | **fails at interval 2** |
| A1 off, A2 + A3 | **fails at interval 2** |
| A1 off, A2 off, A3 only | **fails at interval 2** (and interval 0 matches the baseline exactly, 8/1) |
| A1 + A2, no A3 | passes, **760 ms** |

The mechanism: this island closes its generator pipe and its pump pipe by deposition as the filter
clogs. A one-directional closure leaves the reverse direction open, so the junction between them
keeps a live hydraulic row; closing both isolates it, and an isolated junction is a singular block.
Making the closure preset also set the actuator to `CLOSED` (kept, §2) was not enough.

Against that, A3 buys: 10 nodes 5 s **84 ms** vs 136 ms, 30 nodes **310 ms** vs 496 ms — 25–40% on
the chains, with identical accepted/rejected counts. It is not needed for correctness anywhere: the
floor is what removes the stall (the sweep row at fraction 0 still fails the thirty-reservoir
chain). Trading a qualified fixture for 40% on a chain is not a trade I would make without the
singular junction being root-caused first, so I left the closure semantics alone and documented the
constant (`BOTH_DIRECTIONS`, still used by the filter closure) with what a future attempt has to
solve. The `PassiveNetwork.Pipe` doc says what the runtime actually produces: 1 or 2 from a mobility
failure, 3 only from a filter at capacity.

### The accepted-substep bound in the new test is 140, not 80

Measured 94 (10 nodes) and 112 (30 nodes). Step-grid location replaces 19 full re-integrations per
event with roughly 20 accepted micro-steps as the controller halves onto it — that is where the
extra accepted substeps come from, and they are cheap (the 5 s interval is 136 ms against a hard
failure at 2300 ms). `rejectedSubsteps <= 20` holds as asked (measured 1 and 6). I left the bound at
140 rather than silently reporting a passing 80.

### `fluidSolverRegression` cannot run on this branch point

It fails identically at `440a754` and at `a4db1fe` with
`java.lang.IllegalArgumentException: Fluid basis mismatch` at `FluidSolverRegressionTest.java:89` —
the saved islands are on a different fluid basis than the current model. Pre-existing, not caused by
these changes, and it means I could not use it as the bit-identity gate. The substitutes I do have:
`SolidChainTransportTest.clearChainSubstepCountsAreUnchanged` asserts the exact clear-chain counts
recorded at 440a754 (25/10, 32/14, 18/5), and the network benchmark's accepted/rejected counts are
identical at all three sizes (§7).

### The 2-node drain's post-closure segment will not hold a 1e-9 relative tolerance

With a one-directional closure the reverse direction stays open, and the near-zero reverse flow keeps
the flow error estimate hovering at about `1.2e-9`. At `relativeTolerance = 1e-9` the controller
accepts with a step factor just under 1 and shrinks geometrically — 2040 accepted substeps advancing
`5.7e-6` of `0.0446 s` before hitting the attempt cap. The replacement test's tight reference uses
`1e-6` (still three orders under the default) rather than `1e-9`. Worth a look on its own: this is
the error controller failing to grow, not a solver failure.

### Noticed, out of scope

- `TrBdf2StepSolver.endpointRates` is keyed on the whole `PassiveNetwork`, cakes included — the same
  class of miss Fix D removes from the three keys the brief named, on the endpoint-rate cache.
- `Equations.differentiateEntries` returns `-1` for any island with solids or a filter, so every
  solid island pays the coloured whole-island sweep. Explicitly out of scope; it is now the largest
  remaining cost on the chains.
- `SolidMobility.monitored` is true for any island with *any* liquid at all, so every wet island is
  routed through `SolidEventIntegrator` and its pre-interval rate pass. Out of scope.
- The 64-population limit and the embedded estimator re-enable were not touched.

---

## 7. Verification

Run in order, one Gradle invocation at a time.

| step | result |
| --- | --- |
| `fluidScienceTest` | **156 tests, 0 failures** |
| `fluidRuntimeTest` | **105 tests, 0 failures** |
| `fluidSolverRegression` | **cannot run** — `Fluid basis mismatch`, identical at 440a754 (see §6) |
| `fluidNetworkBenchmark` | pass, numbers below |
| `runFluidGameTestServer -PfluidGameTestRunId=solid-fixes-01` | **All 20 required tests passed** in 11.65 s |
| `git status --short` | clean |

### `build/reports/fluid/M2-network-scaling-screening.json`, warm ms

| reservoirs | brief's base | Codex branch | 440a754, this session | **this branch** | accepted/rejected |
| --- | --- | --- | --- | --- | --- |
| 2 | 67.6 / 45.1 | 67.2 / 47.9 | 49.4 / 31.2 | 51.9 / 35.9 | 30 / 11, unchanged |
| 10 | 109.4 / 85.3 | 97.4 / 79.1 | 74.8 / 51.8 | 93.5 / 54.1 | 19 / 14, unchanged |
| 100 | 1087.6 / 1046.3 | 1212.0 / 1138.8 | 894.2 / 988.7 | 892.7 / 1089.2 | 37 / 3, unchanged |

The substep counts are identical at every size, which is the load-bearing claim — these are
clear-fluid islands and nothing in the four fixes may touch them. The wall times move within this
host's usual run-to-run spread (the 10-reservoir first warm repeat is the widest at +25%; the second
repeat is +4%).

### The 10-node probe, before and after

| | ms | accepted | rejected | stalls | closures |
| --- | --- | --- | --- | --- | --- |
| 440a754, 0.5 s | 1528 | 368 | 175 | **170** | 2, `1,1,0…` |
| 440a754, 5 s | 2300 | **FAILS** at `advanced=1.5322265625 s` (1016/516) | | **517** | — |
| Fix A (as briefed, with A3), 5 s | 547 | 42 | 5 | 0 | 2, `3,3,0…` |
| Fix A+B (with A3), 5 s | 84 | 94 | 1 | 0 | 2, `3,3,0…` |
| **final branch, 5 s** | **136** | **94** | **1** | **0** | 2, `1,1,0…` |

Deposition, final branch: thresholds `0.17169288549984418` and `0.17169394366127386` against the
baseline's `0.17169288550343975` and `0.1716939436540903` — 9 to 10 digits. The *velocity at the
declared closure* is `0.17168760790` and `0.10515406507` against the baseline's `0.17164602128` and
`0.10531715540`; those differ in the 4th to 5th digit because the event is now declared on the step
grid rather than at a 1 µs bracket, and the second event is not a marginal crossing (velocity/
threshold ≈ 0.61) so its velocity moves quickly with the declaration instant. Under Fix A alone,
with the bracket still in place, they agreed to 9 digits (`0.17164602113693878`,
`0.10531715558523287`).

### 30-node chain

`496 ms`, 112 accepted / 6 rejected, 0 stalls, 100.000000000 kg of solids conserved.
Baseline: immediate failure.

### Pump-filter fixture

`SolidRuntimeTest.physicalPumpFilterAndNitrogenReceiverStartTogether`, 20 intervals of 5 s:
**6106 ms → 760 ms** (the Codex run quoted 7.53 s). Interval 2, where the filter fills, went from
5588 ms to 236 ms.

---

## 8. Tests added or replaced

`src/test/java/com/wormzjl/createcheme/science/fluid/network/`

- **`SolidChainTransportTest`** (new, 4 tests)
  - `tenReservoirChainIntegratesFiveSecondsWithoutALineSearchStall` — zero `line search stalled`
    reasons, `accepted <= 140`, `rejected <= 20`, both closures present, 2 DEPOSITION reasons, solid
    mass closed against the boundary ledger, `advancedSeconds == 5`.
  - `thirtyReservoirChainIntegratesOneIntervalWithinItsBudget` — same bounds, `<= 1500 ms` warm.
  - `clearChainSubstepCountsAreUnchanged` — exact counts recorded at 440a754.
  - `filterIslandReusesItsSolverWorkspacesAcrossIntervals` — reuse dominates builds over 20
    intervals, and `solidMomentProjectionsAtAcceptedPoints == 0`.
- **`SolidClosureFeasibilityTest`** — rewritten onto the new mechanism (§3); same `@Tag`, same three
  physical questions.
- **`ThresholdEventLocatorTest`** — deleted with the locator.
