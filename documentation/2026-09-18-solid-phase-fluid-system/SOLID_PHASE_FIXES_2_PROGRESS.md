# Solid-phase transient solver — fixes E–H and the closure semantics

Branch `claude/solid-phase-fixes-2`, final HEAD `e97fe5b`, base `a4db1fe`
(itself four commits on `440a754`).
Worktree `D:\Minecraft\Modding\1.21\CreateChemE\.claude\worktrees\agent-ac557ada3c6d34aad`.
Date 2026-09-22.

---

## 0. Setup verification

| Check | Result |
| --- | --- |
| `git checkout -b claude/solid-phase-fixes-2 claude/solid-phase-fixes` | done |
| `git log -1 --oneline` at branch point | `a4db1fe Key retained solver state on a pipe's identity, not on its cake` |
| Other Gradle build active before the first invocation? | no — 6 `java.exe`, CPU delta ≤ 0.02 s over 3 s for all six |
| `git stash` used | never; the one pre-existing entry (`codex/plan-2-solver-experiments`) is untouched |
| Other checkouts/worktrees written | none; the two report files named in the brief were read only |
| Final `git status --short` | clean |

```
e97fe5b Close both directions of a transport failure
3076716 Let a junction no open connection reaches keep its pressure
2457670 Prepare a donor's mobility once per node, and measure the endpoint-rate cache
c46b2a3 Close a connection that would overfill a receiver's population basis
904775a Hand a declared transition's next segment the step the island had before the search
d346bfc Estimate solid and filter step error with the embedded companion, not step doubling
3e949c4 Sweep solid and filter islands with the block Jacobian instead of refusing them
```

---

## 1. End to end, base against final

One probe, one host, four warm-up runs then eleven measured repeats, run at `a4db1fe`
and at `e97fe5b` by checking the tree over and back.

| case | `a4db1fe` best / median | `e97fe5b` best / median | change (median) |
| --- | --- | --- | --- |
| 10-reservoir slurry chain, 5 s | 103 / 110 ms | **45 / 50 ms** | **−55 %** |
| 30-reservoir slurry chain, 5 s | 351 / 371 ms | **83 / 87 ms** | **−77 %** |
| pump-filter island, 20 × 5 s | 368 / 380 ms | **256 / 277 ms** | **−27 %** |

Accepted/rejected substeps: 10-reservoir chain 94 / 1 → **30 / 6**, 30-reservoir chain
112 / 6 → **31 / 10**. The two deposition closures stay at the same thresholds
(`0.171692885497` and `0.171693943649` against the base's `0.171692885500` and
`0.171693943661`, 11 digits) and the filter capacity closure at the same `1.63125 s`.

---

## 2. Fix E — block Jacobian for solid and filter islands (`3e949c4`)

### What changed

`Equations.differentiateEntries` no longer returns `-1` for an island with solids or a
filter. Two things had to be true first.

1. **The neighbour's solid rows.** `PhaseLayout.balanceRows` and `junctionRows` already
   write the three aggregate solid rows, but against the layout's own seed reference
   (`solidReference`), because a layout evaluated alone has no accumulated target. The
   whole-island assembly overwrites them with the accumulated targets. `nodeTargetRows`
   — the neighbour re-assembly the sweep uses — stopped at the first write, so a
   neighbour's solid rows would have been differentiated against the seed reference while
   the base residual `f` held the real target: the derivative is then wrong by
   `(target − reference)/scale/step`, not merely missing. Both assemblies now finish
   through a shared `nodeSolidRows`, which is a no-op on a clear island.
2. **The filter column.** `filterOffsets[edge]` is now a swept column of its edge, as
   `which==2` of the edge loop. Its whole stencil is that edge's hydraulic row and its own
   loading row — `buildSparsity` declares exactly that, and no node block carries the
   loading unknown — so one `edgeRows` call writes all of it and the node blocks are not
   touched. The per-edge velocity-cap cache stays bypassed for filter edges, unchanged,
   because the clogging coefficient reads the loading unknown.

`hasSolids(graph)` is also hoisted into an `Equations.solidSupport` field instead of being
re-evaluated per node in the constructor.

### Equivalence evidence

`src/test/java/.../network/BlockJacobianEquivalenceTest.java` (new, 2 tests). It builds the
Jacobian at the accepted point of one solve twice: once through `differentiateEntries`,
once by perturbing each column alone and differencing `residual`. The coloured fallback
*is* that column-by-column difference — colouring only packs columns whose declared
stencils are disjoint into one evaluation — so the test requires **bit-for-bit** equality,
not a tolerance.

| island | columns | entries | neighbour solid couplings | filter columns | mismatches |
| --- | --- | --- | --- | --- | --- |
| ten-node slurry chain | 69 | 1177 | 126 | 0 | **0** |
| filter island, junction fed by a filter edge and a plain edge | 16 | 196 | 6 | 1 | **0** |

The "neighbour solid couplings" count is asserted `> 0`: a comparison that never reaches a
solid row a donor's own column moves would prove nothing. Negative control — with
`nodeTargetRows` left at the seed reference, the filter island reports disagreeing entries
and the chain does not even converge (`Newton line search stalled at residual 1.94e-4`).

`SolidChainTransportTest.filterIslandReusesItsSolverWorkspacesAcrossIntervals` now also
asserts `jacobianBlockBuilds > 0` and `jacobianBlockFallbacks == 0`, so a future change
that pushes a filter island back onto the coloured sweep fails.

### Cost

Bit-identical: the chains kept 94/1 and 112/6 substeps, the same closures at the same
velocities to every printed digit, the filter island the same 342/19 workspace reuses.

| case | coloured | block |
| --- | --- | --- |
| 10-node chain, 5 s (best of 7) | 86 ms | 82 ms |
| 30-node chain, 5 s (best of 7) | 316 ms | 312 ms |
| pump-filter fixture (test XML) | 732 ms | 690 ms |
| whole-island residuals inside a Jacobian, 10 nodes / 30 nodes | 4600 / 8700 | **0 / 0** |
| property decodes inside a Jacobian, 10 / 30 nodes | 16100 / 91350 | 13800 / 78300 |

**Honest note:** the wall gain is inside the run-to-run spread. The coloured sweep already
decoded only the nodes a colour group perturbs (`decode` caches per node), so what the
block sweep removes is the *assembly* — 46 % of all residual evaluations — and the
property evaluations, which are where these islands spend their time, barely move. The
change is landed for what it is: bit-identical, and the removal of a whole class of work
that scaled with colours.

---

## 3. Fix F — embedded estimator for solid and filter islands (`d346bfc`)

### It works; here is what it needed

The refusal (`StageGuard.requiresStepDoubling`, and the throw in `TrBdf2StepSolver`) was
older than the transition machinery: the event is now located by `checkRate` and
`checkFilters` inside the stage solves, and the filter capacity is a law inside the step,
so nothing about a solid island needs the coarse-versus-refined comparison. What the
companion was actually missing was the solid half of its own defect.

1. **Solid target rows.** `PhaseLayout.targetRows` gained `deltaSolidMoments`, written to
   the three solid rows at the same sign convention and divided by `solidScale`. A layout
   with solid unknowns now *requires* them; leaving them zero would claim the companion
   holds the solid inventory fixed while its corrected graph moves it.
2. **Corrected solid stock.** The corrected reservoirs carried
   `new Inventory(volume, n, energy)` — the three-argument form, i.e. **no solids at all**.
   The interval's own error term compares per-population masses, so every particle would
   have looked like it vanished and every step would have been rejected. They now carry the
   same order-three combination applied to the conserved populations. Its endpoint-rate
   term is accumulated where the rate is read (`E0*dt*` alongside the existing `ALPHA*dt*`),
   because dividing it back out of a finished stage inventory would have required holding a
   negative population.
3. **The corrected graph's cakes.** It used `initial.pipes()`; it now uses
   `secondGraph.pipes()`, so the material the companion hands a filter lands on the same
   base its stage-two solve used. For an island without filters this is the same pipe list,
   so clear islands are untouched.
4. **A floor for the defect stock.** Two of the companion's three weights are negative
   (`E0 = −0.1381`, `E2/ALPHA = −0.667`), so a population whose stage differences are the
   size of the stock they are differences of lands below zero — which is every node far
   enough down a chain, where backward Euler leaves 1e-30 to 1e-70 of the source.
   `SolidInventory.Accumulator.finishNonNegative()` is a second finish reserved for
   uncommitted estimates. **This was not optional:** the first attempt used `finish()` and
   failed both chains outright (`Invalid particle mass`, `Solid inventory
   overflow/underflow`), and step halving does not help, because the dust is the same size
   relative to its own stock at every step size. Owned material still uses `finish()`.

`StageGuard.requiresStepDoubling` had no remaining implementation and is removed with its
call site.

### Measured (best of seven warm repeats)

| case | step doubling | embedded companion |
| --- | --- | --- |
| 10-node chain, 5 s | 82 ms, 94/1 substeps | **64 ms, 47/2** |
| 30-node chain, 5 s | 312 ms, 112/6 | **173 ms, 55/5** |
| pump-filter fixture (test XML) | 690 ms | **504 ms** |
| property evaluations, 10 / 30 nodes | 84 323 / 380 178 | **48 281 / 189 749** |

The linear filter answers 42 of 49 and 47 of 60 companion stages; the rest are refused by
the filter's own residual check and pay a full nonlinear stage, exactly as before.

Clear-fluid paths untouched: `clearChainSubstepCountsAreUnchanged` (25/10, 32/14, 18/5)
still holds and the network benchmark still reports 30/11, 19/14, 37/3.

---

## 4. Step regrowth after a declared transition (`904775a`)

The coordinator's second note. Refining onto a transition halves the step to the ~1 µs
floor, and the interval reported *that* as its next-step estimate; `SolidEventIntegrator`
starts the next segment from it, so every closure cost another ~20 accepted substeps
doubling back out. A segment that ends on a declared transition now hands on the
controller's estimate from the moment the search began.

| case | before | after |
| --- | --- | --- |
| 10 nodes, 5 s | 47 accepted / 2 rejected, 64 ms | **29 / 6**, 64 ms |
| 30 nodes, 5 s | 55 / 5, 173 ms | **31 / 10**, 171 ms |

Wall time is unchanged — the work moves from accepted steps to rejected ones — but
committed work is not: a third of the reconstructions and boundary ledger entries an
interval carries. `SolidChainTransportTest`'s bounds come down from 140/20 to **50/16**.

---

## 5. Fix G — the 65th population is a closure (`c46b2a3`)

`SolidMobility.Reason.POPULATION_LIMIT`, evaluated in `SolidEventIntegrator.failed`, so it
reaches the pre-interval rate pass and both stage guards at once. The connection is chosen
by the existing deterministic rule (lowest velocity ratio, then lowest pipe id), one per
pass until the union fits. The throw stays in `ConservativeTransport` as the invariant.

**Deviation from the brief, deliberately.** The brief specifies the union of the receiver's
own populations and those of its immediate donors. The reconstruction's population system
has only positive coefficients, so a stock reaches every node joined to it by open
connections a filter does not empty — the union that decides whether an inventory can be
built is the **transitive** one, and a one-hop rule would still have held a three-node
chain forever. An inline filter's cake is a receiver too and is checked the same way
(`captured ∪ reach[donor]`).

None of this costs an ordinary island anything: the bound that skips the whole computation
is the island's population count *with* duplicates (if the island holds ≤ 64 in total, no
union of them can exceed 64), which is one field read per node.

**One behaviour change beyond the closure:** a closure the interval starts with is now
reported in `rejectionReasons` at `t=0`, like one declared inside it. The pre-interval pass
has always been able to close a connection and has never said so, and for this closure that
report is the only account there is. No existing fixture's reason counts moved.

Test: `SolidTransportAcceptanceTest.aSixtyFifthPopulationClosesAFeedInsteadOfStallingTheInterval`
— two feeds of 64 trace-mass grades each (1e-12 kg per grade, so every population is below
the trace volume fraction and *nothing but the limit* can close anything) into one reservoir
draining to a void. It integrates the full 5 s, exactly one feed closes, the reservoir holds
64 populations and still receives from the other. Negative control: with the closure removed
the same fixture fails `Substep refinement exhausted: Solid population limit exceeded (64)`.

---

## 6. Fix H — two cheaper misses (`2457670`)

### H1, the endpoint-rate cache: **refuted, and the proposed key would be wrong**

New counters `endpointRateReuses` / `endpointRateBuilds`. Measured on the pump-filter island
over twenty intervals: **121 reuses against 5 builds**, the five misses being interval
boundaries where `InventoryEquilibrium.refresh` rebuilds the states the key holds. The
`RateKey(PassiveNetwork, Acceptance)` already hits, because `PassiveIntervalSolver.replace`
and `TrBdf2StepSolver`'s own endpoint construct value-equal graphs from the same state
objects.

Keying it on `Pipe.identity()` would also have been **incorrect**: the rate solve carries a
filter loading unknown pinned to `captured().volume()/capacity()` and feeds it into the
clogging resistance, so the endpoint rate genuinely depends on the cake and a cake-blind key
would answer a filling filter with a stale rate. `SolidChainTransportTest` now fails if the
hit rate collapses; it currently reports 57 reuses and no rebuilds.

### H2, the mobility guard: landed, measurably free

`SolidMobility.Donor` is the part of `check` the donor alone decides — the two carrier
viscosity correlations, whether any population is active for blockage, the largest of those,
and the suspension velocity. `SolidEventIntegrator.failed` prepares it once per donor node
instead of once per connection per direction, and a node that is never a donor is never
prepared. The carrier density and mixture viscosity were being recomputed *per population*
although neither depends on one; they are now computed once. Taking the largest active
population for the bore refusal refuses on exactly the same donors as walking them in order
did, because a bore refusal reports no suspension velocity at all.

| case (15 warm repeats, best / median) | per connection | per donor node |
| --- | --- | --- |
| 10-node chain, 64 active grades | 475 / 507 ms | 468 / 500 ms |
| 30-node clear chain | 62 / 70 ms | 61 / 72 ms |
| 100-reservoir benchmark | within its own spread, 37/3 substeps | same |

**Honest note:** measurably free, not faster. The guard is not where these islands spend
their time — a 64-grade island spends it in the reconstruction's 64 extra right-hand sides.
An earlier 7-repeat pair suggested a 10 % regression; at 15 repeats it disappears, and no
mechanism could account for it (the change strictly removes evaluations; the only additions
are one array and one record per call).

---

## 7. The isolated junction, root-caused and fixed (`3076716`)

### Root cause

A junction owns no volume, so only the hydraulics decide its pressure: the net mass flow
row balances arrivals against departures, and the connected pressure drops relate that
balance to the pressure here. Close every connection and both halves go at once. Each
closed connection already carries a row saying its own flow is zero, so the net flow row
becomes their sum — **satisfied identically, with zero derivative with respect to every
unknown the junction owns** — and the block is one equation short of its own pressure. The
remaining rows pin the mass fractions, the specific enthalpy and the total amount to the
stored guess (`junctionInflow` falls back to it when nothing arrives), and none of them
touches pressure.

This is reachable today, without any change of closure semantics: a junction between two
stopped filters, between two pumps whose target flow is zero, or between two connections a
saved checkpoint closed in both directions.

### The rule

An isolated junction keeps the pressure it had. It is the rule reachability already applies
— "a hydraulically isolated junction retains only its arbitrary property guess" — extended
to the one property the rows did not already cover. Isolation is read from the pass's own
active set, not from the flows, so a connection that is open and carrying nothing still
answers for the junction. `PhaseLayout.junctionRows` takes a `retainedPressure`
(0 = not isolated) and writes `x[pressureIndex] − ln(P/1e5)` in place of the net flow row;
`Equations` builds the per-node array once per pass.

### Test

`PassiveStepSolverTest.aJunctionNoOpenConnectionReachesKeepsItsPressureInsteadOfGoingSingular`.
A junction between two fully closed connections, with a third branch of the same island
still carrying the step — **without that third branch Newton meets its tolerance at
iteration zero and never factorizes anything**, which is why a two-pipe fixture passes with
or without the rule. Negative control reproduces the previous pass's exact message:
`Singular Newton Jacobian: Sparse LU rejected a singular matrix; active-set pass=0`.

---

## 8. Closure semantics switched to both directions (`e97fe5b`)

With the junction rule in place the blocker is gone, so `SolidEventIntegrator` now closes
both directions of every transport failure, which is the plan's rule: a settled bed is in
the connection, not in one end of it.

The second half of the reason is cost. `PassiveStepSolver` presets `boundaryClosed` only
for a fully closed connection, so a one-directional closure left the first Newton pass free
to drive flow through the forbidden direction — manufacturing, hop by hop, exactly the solid
dust the first fix in this series had to floor — and the active set needed two or three
passes per solve to find its way back.

| case (11 warm repeats, best / median) | one direction | both directions |
| --- | --- | --- |
| 10-reservoir chain, 5 s | 62 / 70 ms | **45 / 51 ms** |
| 30-reservoir chain, 5 s | 186 / 215 ms | **80 / 103 ms** |
| pump-filter island, 20 × 5 s | 191 / 226 ms | 258 / 270 ms |

The five-node pump-filter island goes the other way, because its closures isolate a junction
and are re-examined more often. That is the trade, and the chains are the case that scales.
Substep counts, closure thresholds and the filter fill time are unchanged; the benchmark
keeps 30/11, 19/14, 37/3.

`SolidClosureFeasibilityTest` asserted `blockedDirections == 1`; it now asserts 3, with the
reason in the comment. `PassiveNetwork.Pipe`'s doc is updated: every closure the transport
machinery makes produces 3, a saved checkpoint may still carry 1 or 2, and a caller may
still impose one. Closures are still reconsidered from scratch at every interval, so a
connection whose driving pressure recovers restarts in either direction
(`closedParticlesRestartWhenDrivingPressureIncreases` still passes).

---

## 9. What I did not do, and why

- **`SolidMobility.monitored` still routes every wet island through `SolidEventIntegrator`.**
  The brief asked to gate the *check*, not the routing, and I left the routing alone: the
  `immobileViscosity` path in `PassiveIntervalSolver.solve` depends on it, and a wet island
  whose carrier thickens has to be caught by something.
- **`Equations.prescribedFlow` is still island-wide** (`modes.contains(PUMP_TARGET)`), so one
  target-flow pump anywhere disables the saturated filter law for every filter in the island.
  Flagged by the coordinator, out of scope here, and nothing in this series builds on it as
  if it were per-edge.
- **The population limit is evaluated on the reachable union, not on what the reconstruction
  would literally produce.** Exact-zero cancellations in the population solve could in
  principle leave a node with fewer nonzero populations than reach it; the rule then closes a
  connection that did not have to close. It cannot go the other way, which is the direction
  that matters.
- **`fluidSolverRegression` still cannot run at this branch point.** It fails identically at
  `a4db1fe` and at `e97fe5b` with `java.lang.IllegalArgumentException` at
  `FluidSolverRegressionTest.java:89` (`Fluid basis mismatch`) — the saved islands are on a
  different fluid basis than the current model. Pre-existing. The substitutes are
  `clearChainSubstepCountsAreUnchanged` (25/10, 32/14, 18/5, exact, recorded at `440a754`)
  and the benchmark's substep counts.
- **No GUI work.**

---

## 10. Verification

One Gradle invocation at a time throughout.

| step | result |
| --- | --- |
| `fluidScienceTest` | **160 tests, 0 failures, 0 errors** (156 at `440a754` + 4 new) |
| `fluidRuntimeTest` | **105 tests, 0 failures, 0 errors** |
| `fluidNetworkBenchmark` | pass; substep counts below |
| `runFluidGameTestServer -PfluidGameTestRunId=solid-fixes-02` | **All 20 required tests passed** in 12.12 s |
| `fluidSolverRegression` | cannot run, pre-existing (§9) |
| `git status --short` | clean |

### `build/reports/fluid/M2-network-scaling-screening.json`, warm ms

| reservoirs | branch baseline | this branch (two runs) | accepted / rejected |
| --- | --- | --- | --- |
| 2 | 51.9 / 35.9 | 66.5 / 53.4 and 68.6 / 50.3 | **30 / 11**, unchanged |
| 10 | 93.5 / 54.1 | 101.1 / 73.2 and 80.7 / 63.7 | **19 / 14**, unchanged |
| 100 | 892.7 / 1089.2 | 1132.6 / 1063.0 and 1078.5 / 985.5 | **37 / 3**, unchanged |

The substep counts are the load-bearing claim: these are clear-fluid islands and nothing in
this series may touch them. The wall times sit inside this host's spread for the fixture,
which across today's twelve benchmark runs ran 870–1133 ms on the hundred-reservoir warm
repeat with no correlation to the change under test.

### Tests added or changed

- **`BlockJacobianEquivalenceTest`** (new, 2 tests) — §2.
- **`PassiveStepSolverTest.aJunctionNoOpenConnectionReachesKeepsItsPressureInsteadOfGoingSingular`**
  (new) — §7.
- **`SolidTransportAcceptanceTest.aSixtyFifthPopulationClosesAFeedInsteadOfStallingTheInterval`**
  (new) — §5.
- **`SolidChainTransportTest`** — bounds 140/20 → 50/16; new assertions that a filter island
  keeps the block Jacobian sweep (`jacobianBlockBuilds > 0`, `jacobianBlockFallbacks == 0`)
  and its endpoint-rate cache (`reuses > 4 × builds`).
- **`SolidClosureFeasibilityTest`** — `blockedDirections` 1 → 3, §8.
