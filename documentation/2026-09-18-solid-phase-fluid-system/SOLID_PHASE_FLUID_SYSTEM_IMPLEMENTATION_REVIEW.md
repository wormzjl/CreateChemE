# Review: solid-phase fluid system implementation

Reviewed 2026-09-22. Subject: `codex/solid-phase-fluid-system` @ `440a754` ("Add solid-phase fluid
transport and inline filtration"), one commit on top of `main` @ `3c27271` (fast-forward mergeable),
70 files, +2113/-196, plus Codex's result note `docs/solid-phase-fluid-system-progress.md`.
Plan review it answers: `SOLID_PHASE_FLUID_SYSTEM_PLAN_REVIEW.md` (2026-09-18, findings F1-F17).

Paths are relative to `src/main/java/com/wormzjl/createcheme/` unless they start with `src/` or `build/`.
Line numbers are in the branch.

## What was run

Three things were measured; everything else is a reading of the diff.

1. **Clear-fluid regression, same fixture on both sides.** The branch's rewritten
   `FluidNetworkBenchmarkTest` was copied onto a base worktree (`4e84f0e`, fluid code identical to
   `3c27271`) and `gradlew fluidNetworkBenchmark` run once; compared with Codex's own
   `build/reports/fluid/M2-network-scaling-screening.json` from the same host (2026-09-22 14:2x).
2. **Cost of one closure event on a chain.** A throwaway JUnit probe (deleted afterwards, worktree
   left clean) in the Codex worktree: N-reservoir water chain, reservoir 1 at 160 kPa with 100 kg of
   100 µm demo particles, the rest at 150 kPa - 100·i Pa, 100 m × 0.05 m pipes, default
   `PassiveIntervalSolver.Settings`, one warm-up solve then a timed warm and a timed cold solve, for
   N ∈ {2, 10}, duration ∈ {0.5, 5} s, with and without the particles.
3. **Codex's own reports**, read rather than re-run: `build/test-results/*/*.xml` totals and
   per-test durations, `build/reports/fluid/solid-populations.json`, and
   `run/fluid-gametest-solid-phase-final/logs/latest.log`.

Both Gradle JVMs on the host were idle (0 CPU over 3 s) before either run; no other suite was overlapped.

## Verdict

The design follows the plan review closely and most of it is right: the three-moment reduction (F2)
is implemented exactly as recommended, solids sit on a separate axis with additive save migration and
a separate compatibility check (F3), conservation is audited per population including the filter cake
and boundary histories, recovery is exactly-once through the topology ledger, and the reported test
counts are real (888 / 156 / 105, zero failures; the two `HELD` lines in the GameTest log are the same
negative-test diagnostics main's 2026-09-17 logs contain).

It is **not mergeable yet**, for one measured reason and three read ones:

1. **A slurry chain does not integrate.** A 10-reservoir chain with one slurry tank fails a default
   5 s interval outright (1024 attempts exhausted at 1.53 s, 516 Newton line-search stalls after the
   first deposition closure) and costs 1.46-1.57 s wall for a 0.5 s interval - 45× the clear chain and
   75 % of the in-game 2000 ms budget. In-game that island would hold at "wall deadline" and retry the
   same snapshot every cadence, forever. Codex's tests never exercise more than two reservoirs with
   solids (the game test is a 6-block pump line whose event is a clog, not a deposition closure).
2. Every island containing a filter loses its retained Newton workspace and flow warm start on every
   substep, because pipe record equality now includes the mutable cake.
3. The block Jacobian and the embedded error estimator are switched off for every island that holds
   solids or a filter, which is the cost profile the result note does not state.
4. A 65th particle population arriving through a pipe throws inside transport reconstruction and
   holds the island permanently.

Plus the in-game GUI check the user's rule requires and the note itself says was not done.

The clear-fluid regression I predicted from routing every liquid-bearing island through the event
integrator is **small on the measured fixture** (≤ +11 % on the 100-reservoir chain, single runs, the
2- and 10-node cases inside noise) and is downgraded accordingly.

## Disposition of the plan-review findings

| Plan finding | Status in `440a754` | Note |
|---|---|---|
| F1 discontinuous closures vs step controller | Addressed by a different route: `SolidEventIntegrator` + `ThresholdEventLocator` (stage guards throw a `Transition`; replay with 1 ms / 1e-6 settings; bisection to 1 µs) and the pre-interval rate pass for state-based refusals | Correct in the small; the cost and the post-closure convergence are finding 1 below |
| F2 solid unknowns | **As recommended**: 3 moment unknowns per node (`PhaseLayout.solidRows`), populations reconstructed as extra right-hand sides in `ConservativeTransport` (separate `solidColumns` when a filter is present) | Verified: 7 unknowns / 7 colours for 1, 8, 64 populations |
| F3 revision vs migration | **As recommended**: `FluidBasis` and the thermodynamic revision untouched; settings only enter the anchor revision (`ApproximationAnchor.java:34`); `SolidCompatibility` validates only referenced materials; v1→v2 and topology 2→3 additive upgrades (`FluidCheckpointCodec.upgradeLegacySolids`) | Capacity/resistance changes preserve stock (`currentFilter`, :258) |
| F4 sealed tanks | Kept as designed (MIXED outlets close on dry particles / immobile liquid, GAS outlet reserved, no relief) | Accepted; should be stated in the note as a known behaviour |
| F5 trace populations | Threshold 1e-8 occupied-volume fraction masks blockage; slots are never freed by decision | Accepted; see finding 4 for what happens at 65 |
| F6 slurry density | Done: `State.withSolidState` folds solid mass and volume into `mass()`/`volume()` | Correct everywhere ρ = m/V is read |
| F7 filter details | Separate inlet/outlet fluxes (`deliveredFlow`), carrier viscosity for μ_in (`carrierViscosity`, vapor included in the average), cake booked at donor enthalpy incl. P/ρ and the donor-elevation potential | Energy bookkeeping checked by hand against `nodeAccumulate`; consistent |
| F8 probe order/determinism | Lowest velocity ratio first, pipe id on ties (`SolidEventIntegrator.failed`); closures reconsidered every interval; `equalParallelBranchesChooseTheSameClosuresAcrossReplays` | As recommended |
| F9 module feeds | Decided on the accepted candidate: withdrawal refused for the interval if its boundary carries solids; `MaterialParcel.split` refuses solids | As recommended, tested |
| F10 100 Pa·s live for pc10-12 | Implemented strictly (`immobile`: > threshold); `highViscosityBlocksTheMixedOutletBeforeSlurryThickening` uses pc12 at 330/340 K | No regression scan of existing clear-fluid worlds for immobile states was added |
| F11 "22-component" wording | Basis captured at runtime, benchmark de-hardcoded | Done |
| F12 diameter identity | **Deviates**: exact `BigDecimal` string identity, not quantized; tested as intentional | Accepted; slot exhaustion by near-duplicate sizes remains possible |
| F13 inventory/reference invariants | Solids are sensible-only (298.15 K, no offset); `Inventory` and `MaterialParcel` allow nonzero energy with zero moles when solids are present | Fine |
| F14 binding section | `Pipe.maximumArea()` and `minimumDiameter()` added and used | Done |
| F15 filter life | Unchanged defaults (0.01 m³, 1e6) | Not revisited; playtest question |
| F16 near-packing stiffness | `viscosity()` clamps φ at 0.62-1e-9 instead of throwing; `SolidMobility` refuses at ≥ 0.62 | Residual stays finite; the slope near the clamp is still steep (see finding 1 hypotheses) |
| F17 no fallback | `requiresFull` gates the approximate path in `ProcessSolveServices`, `IslandCoordinator` and `solveApproximate` | Done |

## Measurements

### Clear-fluid benchmark, same test on both sides (5 simulated s, wet TJL20 chains)

| Reservoirs | equations | substeps acc/rej | base warm ms | branch warm ms | base first ms | branch first ms |
|---|---|---|---|---|---|---|
| 2 | 91 | 30/11 | 67.6 / 45.1 | 67.2 / 47.9 | 161.8 | 189.0 |
| 10 | 459 | 19/14 | 109.4 / 85.3 | 97.4 / 79.1 | 140.3 | 161.5 |
| 100 | 4599 | 37/3 | 1087.6 / 1046.3 | 1212.0 / 1138.8 | 1203.4 | 1250.8 |

Single runs each, identical substep counts (the branch changes nothing on the clear path except the
`SolidEventIntegrator` wrapper and its per-stage `SolidMobility.check` per pipe). Read as ≤ +11 % at
100 reservoirs and noise below that. Codex's note reported the branch numbers without a base, which is
why this was run.

### One closure event on a chain (probe, default settings)

| N | duration | particles | warm ms | cold ms | accepted / rejected | blocked pipes | rejection reasons |
|---|---|---|---|---|---|---|---|
| 2 | 0.5 s | no | 18 | 13 | 14 / 5 | 0 | embedded pipe error ×5 |
| 2 | 5 s | no | 9 | 9 | 18 / 5 | 0 | embedded pipe error ×5 |
| 2 | 0.5 s | yes | 275 | 151 | 36 / 7 | 1 | step refinement ×7, DEPOSITION ×1 |
| 2 | 5 s | yes | 143 | 122 | 40 / 7 | 1 | step refinement ×7, DEPOSITION ×1 |
| 10 | 0.5 s | no | 33 | 31 | 25 / 10 | 0 | embedded pipe error ×10 |
| 10 | 5 s | no | 36 | 34 | 32 / 14 | 0 | embedded pipe error ×14 |
| 10 | 0.5 s | yes | **1570** | **1456** | 368 / 175 | 2 | **Newton line search stalled ×170**, DEPOSITION ×2, step refinement ×5 |
| 10 | 5 s | yes | **fails** | - | 1016 / 516 | - | `Interval substep limit; no partial interval may commit: advanced=1.532 of 4.995 s ... reasons={Newton line search stalled at residual #; active-set pass=#=516}, last=... residual 2.13e-4; active-set pass=2` |

508 accepted attempts + 516 rejected = the 1024-attempt cap; mean accepted step ≈ 3 ms after the
first closure. The 2-node case is the one Codex's `drainingSlurryClosesAtAReproducibleInteriorEvent`
covers; it is 15× the clear cost and converges. The 10-node case is what a player builds.

### Codex's own timings (from `build/test-results`)

- `physicalPumpFilterAndNitrogenReceiverStartTogether`: 7.53 s for 20 × 5 s intervals of a
  generator-pump-pipe-filter-pipe-tank line (6 devices, 2 owned reservoirs + 2 filter junctions)
  = **~375 ms per interval** including JIT, against 45-67 ms for a clear 2-reservoir interval.
- `loadedFilterClosesWithoutLosingItsCapturedMaterial`: 0.75-1.53 s for one 1 s interval containing
  one clog event on a 2-node graph (with the test's own tight 1 ms / 1e-5 settings, so not cleanly
  attributable).
- `solid-populations.json`: 7 unknowns, 7 colours, 0.05-0.41 ms per step for 1/8/64 populations on a
  2-node graph - the claim in the note is true and says nothing about interval cost.

## Must resolve

### 1. Post-closure convergence and event cost on chains (CONFIRMED by measurement)

Two coupled problems, both introduced by the F1 route Codex chose:

**(a) After a deposition closure, Newton stalls.** In the 10-node probe every rejection after the
first closure is `Newton line search stalled at residual ~2e-4; active-set pass=2`, 170 of them
in 0.5 s and 516 in the failed 5 s run. The clear chain has none. Where to look first, in order:

- `PassiveStepSolver.java:119-121`: a direction-blocked pipe (`blockedDirections` 1 or 2) is not
  `boundaryClosed`; it only becomes fully closed inside a solve when the Newton point wants the
  blocked direction, and that closure then persists for the rest of that solve even if the
  converged answer wants the other direction. The residual for a direction-blocked, not-yet-closed
  edge is the ordinary hydraulic row, so a step whose true answer is "a trickle backwards" oscillates
  between passes. The "active-set pass=2" in every stall message points here.
- `PhaseLayout.java:78-79, 263`: the three solid rows are scaled by seed mass / seed volume /
  seed mass × 1000 with a difference floor of 1e-6; the flow unknown is in kg/s. Check the Jacobian
  column scaling of the solid unknowns against the edge column on the stalled point.
- `PassiveStepSolver.java:535`: φ is clamped at 0.62 - 1e-9 inside the residual; at φ ≈ 0.6 the
  Krieger-Dougherty slope is ~200×/0.02 and a one-sided finite difference across the clamp gives a
  wrong derivative. Not the probe's regime (φ = 0.04), but the same code path.
- `PassiveStepSolver.java:73`: any blocked pipe in the island forces every pump to start in
  `PUMP_HEAD_LIMIT`; uncommented heuristic, not the probe's case, but it changes the active-set
  start for every closure on pumped lines.

**(b) The event is located to 1 µs by up to 48 replays at 1e-6 tolerance.**
`SolidEventIntegrator.java:78-79` builds `tight` = (min(initialStep, 1 ms), maximumStep,
min(tol, 1e-6)); every bisection probe (`:88-91, 97-101`) re-integrates from the interval start with
those settings, ~22 probes for a 5 s interval at the default 1e-6 s / 1e-8 tolerance. That is the
design I argued against in the plan review's F1: the filter-capacity case has an exact saturated-law
formulation (limit the inlet flow so the end-of-step retained volume cannot exceed capacity; every
stage then agrees on the transferred total), and the deposition case does not need 1 µs - it needs
"not below v_dep in any accepted step", which the accepted-step grid already provides if the guard
rejects the step instead of locating the crossing. The 1e-6 tolerance is also 1000× tighter than the
controller's own accuracy, so the "safe prefix" that gets committed was integrated to a different
accuracy than the rest of the interval.

**Acceptance bar for this finding:** the 10-node probe above converges its 5 s interval at default
settings in well under the 1500 ms soft budget, and a 30-reservoir island (the quiet-11312 fixture)
with one slurry tank passes one interval under the wall budget. Both belong in `fluidScienceTest`
with substep and rejection-count assertions, which none of the current solid tests carry.

### 2. Filter islands lose their retained workspace and warm start on every substep (reading)

`PassiveNetwork.Pipe` is a record and now carries `filter` (the cake) and `blockedDirections`.
`TrBdf2StepSolver.filterStage` (`:112, :123`) rebuilds the stage pipes with a blended cake, so the
pipe list differs on every stage of every step. Two caches key on `pipes.equals(...)`:

- `PassiveStepSolver.WorkspaceKey` (`:258-262`): a new key every stage → `workspaces` (capacity 4)
  churns, `structures` never hits, no `forkPreconditioner` reuse, so every stage rebuilds its
  Jacobian from nothing. This was the WP that took the cold island from 2036 to 767 ms.
- `Equations.initial()` (`:654`): `previousPipes.equals(graph.pipes())` false → flow and head
  unknowns fall back to the bisection guess every stage.

Fix: key both on pipe identity + endpoints + sections + control (and `blockedDirections` if wanted),
never on the cake. Re-measure the 375 ms/interval pump-filter line after the fix.

### 3. Block Jacobian and embedded estimator disabled for every solid or filter island (reading)

`PassiveStepSolver.differentiateEntries` (`:852`) returns -1 whenever the island holds solids or a
filter, handing every Jacobian to the coloured sweep, and `StageGuard.requiresStepDoubling()` /
`TrBdf2StepSolver.java:53` disable the embedded 2(3) estimate, so every accepted step costs three
implicit solves. Neither is needed for correctness: the three solid rows depend on the node's own
solid unknowns and the incident edges exactly like the component rows, and the filter row couples
one edge, one filter unknown and the two endpoint nodes; extending the block sweep is a bounded
change (`buildSparsity` already has the pattern at `:617`). The estimator was disabled so the
`Transition` guard sees stage flows; that is a guard-placement question, not an estimator one.
The result note should state the cost profile either way.

### 4. A 65th population through a pipe holds the island forever (reading)

`ConservativeTransport.java:178` builds each receiver's `SolidInventory` from the reconstructed
populations; `SolidInventory.java:46` throws `IllegalArgumentException` above 64 distinct
material/size keys. The throw happens inside `reconstruct`, so `PassiveIntervalSolver` treats it as a
step rejection, halves, and after 20 rejects raises "Substep refinement exhausted" - every interval,
until the topology changes. Two generators with 64 grades each (the GUI allows 64 per generator)
feeding one tank reach it in one step. "Refuse the operation" for pipe transport has to be a closure
of the offending connection (a `Transition` with its own reason), or a rule that lets the trace
threshold free a slot. No test covers arrival by flow; `canonicalSizes...` only tests `plus()`.

### 5. In-game GUI not verified

The note says "reviewed in code, not visually". The user's rule is the `minecraft-mod-mcp` bridge for
all GUI work; the generator's Solids editor (64 rows × 3 fields paged over `rows`), the filter page,
the recover button and the "blocked with solid" / "filter clogged" status prefixes need one pass.

## Should resolve

- **Routing every liquid island through `SolidEventIntegrator`** (`PassiveIntervalSolver.java:50`,
  `SolidMobility.monitored` is true for any `liquidVolume()>0`): per stage per pipe
  `SolidMobility.check` re-evaluates `viscosity.liquid` without the node's `Prepared` bundle and, in
  the same call, twice (`:31-32` and `:49`). Measured ≤ +11 % on the 100-node chain, so not a
  blocker; gate it on "cutoff reachable" (a per-node once-per-stage test, or an upper bound from the
  pure-component table at the node temperature) and reuse the prepared terms.
- **GUI reason and velocities.** The plan asked the blocked status to carry the reason and the
  measured/required velocities; both exist in the `Transition` message that lands in
  `rejectionReasons`, but `FluidView` only prefixes "blocked with solid".
- **Dry-solid reservoir on a connected pipe fails opaquely.** `Reservoir.empty()` is now false with
  solids, so the "Evacuated reservoir" guard is bypassed and `PassiveStepSolver.viscosity()` (`:537`)
  divides 0/0 → NaN → "Invalid pipe transport properties" → refinement exhausted. Practically
  unreachable by dynamics (amounts never hit exactly zero), but reachable by a hand-edited save; the
  note lists it as a known limitation, so either guard it with a clear status or leave it documented.
- **Event time depends on the replay's step sequence**: a `Transition` thrown from a trial step that
  the controller would have rejected still counts as "blocked at t". Consistent across probes, so
  bisection is well-defined, and the locator's javadoc says so; acceptable once finding 1(b) is
  redesigned, otherwise worth an explicit test.
- **Synthetic v1 fixture.** `actualVersionOnePayloadMigratesAdditively` strips the new fields from a
  v2 payload rather than loading an archived v1 save; `src/test/resources/fluid/regression` is the
  place for a real one.
- **`FluidSavedData.load` sets `encoded=null` for every load**, not only for v1, so every world
  re-encodes on its first save after load; harmless (the round-trip test still passes) but
  unnecessary.
- **`hasSolids(graph)` is O(n) and is called per node in the `Equations` constructor** (`:574`) and
  on every Jacobian build (`:852`); trivial today, quadratic in island size.
- **Filter μ_in averages vapor in** (`carrierViscosity`): a gas-bearing slurry pulls the cake
  resistance toward the gas viscosity. State it or exclude vapor.
- **`FluidDeviceScreen` is 67 lines of dense GUI code with 64×3 `EditBox`es created per page** and a
  `particleText[64][3]` model; fine functionally, but it is the file most likely to need the
  in-game pass.

## Accepted deviations from the plan review

- F12 exact-decimal size identity instead of quantization (tested as intended).
- F5 trace populations keep their slot (user's earlier decision).
- F4 sealed tanks with GAS reserved for later.
- F1 event location instead of saturated laws - accepted as a design choice, rejected on cost until
  finding 1 is closed.

## Result-note accuracy

Verified: 888 / 156 / 105 tests, 0 failures/errors/skips (from the XML); GameTest `HELD` lines are the
same negative-test diagnostics present in main's `run/fluid-gametest-ambient-20260917` log;
7 unknowns / 7 colours; benchmark rows match the JSON; migration and revision claims match the code.
Not verifiable from the note: no base-to-branch comparison (now supplied above); no interval cost for
any slurry island beyond two reservoirs; the 375 ms/interval pump-filter line is derivable from the
XML but not stated; "closed directions are reconsidered at the next requested interval" is true and
means k+1 rate solves per interval per k blocked lines, which the note should say.

## Tests to add before merge

1. 10- and 30-reservoir chains with one slurry tank: 5 s interval converges at default settings with
   bounded rejections and wall time (finding 1).
2. Filter island across 20 intervals: workspace hit counters (`SolverDiagnostics`) show reuse
   (finding 2).
3. Two generators × 64 grades into one tank: the connection closes with a stated reason; the island
   keeps integrating (finding 4).
4. A flush-out: a tank diluted below 1e-8 volume fraction unblocks its outlet (the plan review's
   test 2; still missing).
5. Junction fed by a filter edge and a plain edge at once; filter with different diameters on either
   side in both directions (the plan review's test 8; only reverse filtration is covered).
6. Archived v1 save fixture (should-resolve list).

## Reproduction of the probe

```java
// N-reservoir water chain; reservoir 1 at 160 kPa (+100 kg demo particles, 100 um when solids=true),
// reservoir i at 150000-100*i Pa; pipes 100 m x 0.05 m, roughness 45 um; default interval settings.
var solver=new PassiveIntervalSolver(FluidTestSupport.networkModel());
solver.solve(chain(10,true),5,PassiveIntervalSolver.Settings.defaults(),()->{});
// -> Nonconvergence: Interval substep limit; advanced=1.532 of 4.995 s, accepted=1016, rejected=516
```

## Fix plan (agreed 2026-09-22, executed by an opus subagent on a branch from `440a754`)

Order of landing; steps 1-3 are the merge blockers.

1. **Closure semantics and events.** Diagnose the post-closure stall first (instrumented probe:
   stalled row, flow signs, active-set sequence; experiment with full closure). Then: a failed
   deposition / immobile / carrier / bore check closes the connection in both directions for the
   interval (the plan's own rule), reconsidered by the pre-interval rate pass; a stage guard that sees
   a transition rejects the step like an accuracy rejection so the controller halves until the
   accepted prefix ends within the minimum step of the crossing (no 1 µs bisection, no 1e-6 replay,
   `ThresholdEventLocator` retired); filter capacity becomes a saturated flow law in `edgeRows`
   (end-of-step retained volume cannot exceed capacity), so a clog needs no locating.
   Bar: 10-node probe converges 5 s at defaults well under 1500 ms; 30-reservoir island with one
   slurry tank passes one interval under the wall budget; both in `fluidScienceTest` with rejection
   and wall-time assertions.
2. **Cache keys.** `Pipe.identity()` (id, endpoints, sections, control, blockedDirections) keys
   `WorkspaceKey`, `structures`, `previousPipes`; never the cake. Bar: workspace reuse counters over
   20 intervals of the pump-filter fixture; interval cost down from ~375 ms.
3. **Block Jacobian and estimator back on.** `nodeTargetRows` recomputes the neighbour's three solid
   rows; the filter unknown becomes a swept column; drop `return -1`; drop forced step doubling.
   Bar: clear-fluid digests bit-identical; solid conservation tests green; step cost before/after.
4. **Population limit as a closure.** `SolidMobility` reason `POPULATION_LIMIT` per receiver over the
   union of open inbound donors; the transport throw stays as an invariant. Bar: two 64-grade
   generators into one tank keep integrating.
5. **GUI pass via the MCP bridge**; blocked status carries reason and velocities; gate the
   per-pipe `SolidMobility.check` on "cutoff reachable".

Evidence for merge: the chain probe and the base-vs-branch benchmark re-run at the end.

## Progress 2026-09-22 (fix track)

- **Root cause of the chain failure** (`SOLID_PHASE_STALL_DIAGNOSIS.md`): not the closure flip, the
  φ clamp or the Jacobian. Every node of a solid-bearing island carries three hard-nonnegative
  solid-moment unknowns; backward Euler leaves 1e-26…1e-70 kg of dust downstream; the 0.99 step
  limiter has no floor, so alpha ≈ 1e-17 and the iterate stops changing bit for bit (687/687 stalls
  bind on a solid unknown). Direction-blocked pipes amplify (active set wrong until pass 2).
- **Fixes landed** on `claude/solid-phase-fixes` @ `a4db1fe` (four commits on `440a754`, report in
  `SOLID_PHASE_FIXES_PROGRESS.md`): per-column clamp floor + decode projection + solid rows linear in
  their unknowns; transitions as step-grid rejections with a package-private prefix path
  (`ThresholdEventLocator` and the 1e-6 replay deleted; `StageGuard.checkRate` with the live cake);
  filter capacity as a saturated inlet law (margin 1e-12, suppressed while a `PUMP_TARGET` prescribes
  a flow); `Pipe.identity()` keys for `WorkspaceKey`, `structures`, `previousPipes`.
  Measured: 10-node chain 5 s FAIL → 136 ms (94/1, 0 stalls); 30-node chain FAIL → 496 ms; pump-filter
  fixture 6106 → 760 ms (interval with the fill 5588 → 236 ms); `fluidScienceTest` 156, `fluidRuntimeTest`
  105, GameTest 20/20, benchmark substep counts unchanged at all three sizes.
- **Not landed, by measurement**: two-way closure on a mobility failure isolates a junction between
  two closed pipes → singular Jacobian on the pump-filter fixture; closure stays one-directional
  (finding 1 of the plan review's F1 is therefore still open as a physics question, not a solver one).
- **Reviewer's reading of the four commits**: sound. Notes passed to the next agent: (1) `balanceRows`/
  `junctionRows` write solid rows against the seed reference and `nodeRows` overwrites them with the
  real targets, so a block-sweep neighbour re-assembly must do the same; (2) after a declared
  transition `nextStepEstimate` is the halved micro-step, costing ~20 accepted micro-steps per closure;
  (3) `prescribedFlow` is island-wide. Pre-existing: `fluidSolverRegression` cannot run at this branch
  point (`Fluid basis mismatch`), so the clear-chain substep-count test is the bit-identity substitute.
- **In progress**: `claude/solid-phase-fixes-2` (block Jacobian for solid/filter islands, embedded
  estimator re-enable, `POPULATION_LIMIT` closure, `endpointRates` key and `monitored` gating,
  optional isolated-junction root cause). Then the GUI pass via the MCP bridge.

## Progress 2026-09-22 (fixes-2 track) — reviewed

Branch `claude/solid-phase-fixes-2` @ `e97fe5b`, seven commits on `a4db1fe` (agent worktree `.claude/worktrees/agent-ac557ada3c6d34aad`). Agent report: `documentation/SOLID_PHASE_FIXES_2_PROGRESS.md` (Codex copy `docs/solid-phase-fixes-2-progress.md`).

### Evidence I checked myself (not taken from the report)

| Artifact | Result |
| --- | --- |
| `build/test-results/fluidScienceTest/*.xml` | 160 tests, 0 failures, 0 errors |
| `build/test-results/fluidRuntimeTest/*.xml` | 105 tests, 0 failures, 0 errors |
| `build/reports/fluid/M2-network-scaling-screening.json` | 2/10/100 reservoirs accepted/rejected 30/11, 19/14, 37/3 — identical to `440a754` and to main `3c27271`; warm ms 68.6/50.3, 80.7/63.7, 1078.5/985.5 |
| `run/fluid-gametest-solid-fixes-02/logs/latest.log` | `20 GAME TESTS COMPLETE IN 12.12 s`, `All 20 required tests passed` |
| `build/test-results/fluidSolverRegression` | 1 failure, pre-existing `Fluid basis mismatch` (same at `a4db1fe`) |

### Commit verdicts

| Commit | Verdict | What I verified |
| --- | --- | --- |
| `3e949c4` Fix E block Jacobian | sound | `nodeRows` and `nodeTargetRows` both finish through one `nodeSolidRows` (my note 1). Filter loading unknown swept as `which==2` of its edge with no node re-assembly; a receiver across a filter takes its solid share from the donor state only (`factor=1-donor.solidMoments().mass()/donor.mass()`), so no loading→node coupling exists to miss. `BlockJacobianEquivalenceTest` compares bit-for-bit against a column-by-column difference over the declared stencil and asserts >0 neighbour solid couplings and >0 filter columns; `trial[column]` restored after each column. |
| `d346bfc` Fix F embedded companion | sound | `PassiveIntervalSolver.error` (l.207-219) compares per-population masses with a 1e-10 absolute floor, so the controller sees solid transport error under the companion. Solid target rows use the same sign convention and `solidScale`; companion stock = stage-two base + E0·dt·rate + (e1/α)(stage1 − base1) + (e2/α)(stage2 − base2), same weights as the amounts; `finishNonNegative` is reachable only from the uncommitted estimate; corrected graph on stage-two cakes. Substeps 94/1 → 47/2 and 112/6 → 55/5 with unchanged closure thresholds. |
| `904775a` step regrowth after a declared transition | sound | My note 2. `beforeTransition` captured at the first transition rejection of the segment; `nextStepEstimate` hands it on only when a transition was declared after rejections. Note G2 below. |
| `c46b2a3` Fix G `POPULATION_LIMIT` closure | sound, one should-fix | Transitive reach over open non-filter edges with non-zero flow is the right union (positive-coefficient population system). Skip bound = island population count with duplicates. Negative control fails with the `ConservativeTransport` invariant. See G1 for the selection rule. |
| `2457670` Fix H donor preparation + rate-cache counters | sound | Largest active diameter refuses exactly the donors the ordered walk refused (a bore refusal reports no velocity); `required` is the same max; carrier density/viscosity hoisted out of the population loop. Endpoint-rate key correctly *not* changed: the rate solve pins the loading unknown to `captured().volume()/capacity()` and reads it through the clogging resistance, so a cake-blind key would be wrong; measured 121 reuses / 5 builds. |
| `3076716` isolated junction keeps its pressure | sound | Pressure unknown is `ln(P/1e5)` (`PhaseLayout` l.169/194) so the replacement row is linear in it. Isolation is read from `boundaryClosed` and `Mode.CLOSED` of the pass; a zero-target pump already maps to `CLOSED` (`PassiveStepSolver` l.95/170) so the "two shut pumps" case is covered. Test carries a third open branch so the matrix is actually factorized, and a negative control reproduces the singular-LU message. |
| `e97fe5b` both-direction closure | sound | Plan rule restored now that the junction singularity is gone. Chains −27 %/−52 % median; the five-node pump-filter island +19 % (226 → 270 ms per 20×5 s = 13.5 ms per interval) is an accepted trade. `closedParticlesRestartWhenDrivingPressureIncreases` still passes, so a closure is still per-interval. |

### Findings on this track

- **G1 (should-fix, small):** `overPopulated` flags *every* open inbound connection of an over-populated receiver, and the deterministic rule (lowest velocity ratio, lowest id) may close one whose removal does not make the union fit, then close the next as well. Example: receiver holds 60 native grades, feed A adds 4, feed B adds 10 → both flagged, A may close first, B closes next pass, A did not need to close. Prefer, among flagged inbound connections of the same receiver, the one whose donor reach contributes the most keys absent from the other donors (ties by the existing rule). Not a blocker: needs >64 distinct populations reachable in one island.
- **G2 (note):** `transitionRejects` is never reset inside a segment, so the 40-rejection cap is per segment (fine, a segment ends at its declared transition) and `beforeTransition` is the estimate at the *first* search of the segment, which is the intended one.
- **G3 (note):** a closure made by the pre-interval rate pass is now recorded in `rejectionReasons` at `t=0`. Consumers are the checkpoint `History` and `ModuleTransferPlanner` pass-through only; no GUI or log reads it yet. This is what makes the reason available every interval for the status line (G4).
- **G4 (should-fix, GUI pass):** `FluidWorldAuthority` l.291 prefixes `"blocked with solid / "` from `blockedDirections!=0` alone; the `Transition` message (`"blocked with solid: REASON; velocity=…; deposition=…"`) never reaches the player and carries no pipe id, so it cannot be matched to a device. Add the pipe id to the message and append the matching reason to the status. Assigned to the GUI pass.
- **Pre-existing, out of scope:** `fluidSolverRegression` cannot run on any branch at this base (`FluidSolverRegressionTest.java:89` basis mismatch); the recorded clear-chain substep counts and the benchmark counts are the bit-identity substitutes until the saved islands are re-recorded.

### Verdict

`claude/solid-phase-fixes-2` @ `e97fe5b` is mergeable on the solver side: all four original blockers are closed (chain stall, cache defeat, disabled block Jacobian/estimator, 65th-population hold), clear-fluid substep counts are unchanged at three sizes, both suites and the GameTest are green. Remaining before merge: the in-game GUI pass (user rule: MCP bridge) including G4.

## Progress 2026-09-22 (GUI pass) — reviewed

Branch `claude/solid-phase-gui` @ `bfb22ef`, three commits on `e97fe5b` (agent worktree `.claude/worktrees/agent-a377346aa370aa91d`). Report: `documentation/SOLID_PHASE_GUI_PASS.md` (Codex copy `docs/solid-phase-gui-pass.md`), 82 flattened screenshots under `documentation/screenshots/solid-phase-gui/`.

| Commit | Verdict | Notes |
| --- | --- | --- |
| `3672cf1` reason in the device status (G4) | sound | `Transition` carries `pipeId` and names it in the message; `FluidWorldAuthority.solidClosure` renders `blocked with solid: DEPOSITION (0.95 m/s, needs 2.89 m/s)`, falls back to the bare text without a record, names velocity-less reasons plainly. Existing `closureTime` parsing (`startsWith` reason, `lastIndexOf("; t=")`) unaffected. Runtime test added. |
| `a399100` population-limit selection (G1) | sound | Flagged node receivers are narrowed per receiver to the feed contributing the most keys no other open inbound donor, own stock or injection supplies; ties by the shared rule. Cake receivers keep the single-donor path. Test with the 60 + 4 + 10 shape closes only the 10-key feed. |
| `bfb22ef` three screen defects | sound | Recover button disabled when the view has no filter state; solids editor page indicator; validation message wrapped to two lines. |

Suites: `fluidScienceTest` 161, `fluidRuntimeTest` 106, 0 failures. Attribution: these three commits carry `Co-Authored-By: Claude Opus 5 (1M context)` rather than the Fable line the brief asked for; cosmetic, amend before merge if consistency matters.

### In-game results

- (a) generator solids editor, (b) slurry transport with the new `blocked with solid: DEPOSITION (…)` status and re-opening on higher pressure, (d) persistence of solids, populations and status across save/reload: **PASS** with screenshots.
- (c) in-line filter: **BLOCKED** — only the static page and the inactive Recover button could be verified.
- (e) client log: **FAIL** — 885 `status=HELD` warnings from six islands, every one containing an inline filter; no exceptions.

### F1 — every live-world island containing an inline filter is permanently HELD (BLOCKER)

| Topology (creative superflat, two fresh worlds) | Solids | Result |
| --- | --- | --- |
| generator → pipe → filter → pipe → reservoir | none, at rest | HELD from creation: `Newton line search stalled at residual 1.05e-10; active-set pass=1` |
| generator → pump → pipe → filter → pipe → reservoir | none, at rest | HELD from creation: `residual 1232666.6046244698` (byte-identical in both worlds) |
| generator → pipe → filter → pipe → void | none | healthy, `FULL` |
| same, 20 % / 100 µm at 300 kPa | solids | HELD on apply: `residual 16435.93; active-set pass=0` |
| same, 5 % / 100 µm at 150 kPa | solids | HELD on apply: `residual 5531.13; active-set pass=0` |
| same, 5 % / 100 µm, pressure unchanged (no flow) | solids | HELD on apply: `Conservative reconstruction fails equation gate: 0.05834390392013748` |

The filter-free line ran the whole session without a single HELD. The GameTest suite (20/20 on every fix branch) and the unit fixtures use reservoirs joined directly by a filter pipe; the live world compiles a filter as two zero-holdup junctions around a filter edge (`PhysicalFluidTopology.filterIdentity`), so the failing configuration is the physical-topology form and has never been exercised by a test. Whether it also fails at `440a754` is unknown (the Codex GUI was never checked in-game). Consequences observed: F2 a HELD island cannot be reconfigured (edits queue behind `WAITING: configuration event` forever); F3 a HELD island survives block removal and a world reload and delays topology events of unrelated new devices.

### Verdict

Not mergeable until F1 is root-caused and fixed: the in-line filter is one of the three deliverables and does not work in a live world. Next step: an opus diagnosis on the physical-topology filter island (reproduce with `PhysicalFluidTopology` in `fluidRuntimeTest`, bisect `440a754` → `a4db1fe` → `e97fe5b` → `bfb22ef`, then fix). Also open: GUI-4 (two rows per page at the default window).

### Addendum 2026-09-22 — real bit-identity evidence

`fluidSolverRegression` is usable again (`claude/fluid-regression-rerecord` @ `c9150dc`, see `documentation/FLUID_SOLVER_REGRESSION_RERECORD.md`). On `claude/solid-phase-gui-regression` (= `bfb22ef` + that commit as `fca6a15`) the bitwise gate `-PfluidRegressionMode=exact` against the reference recorded on `main` passes with zero deviation (37/1 substeps). The clear-fluid regression concern is therefore closed by measurement, not by proxy. F1 (live-world filter islands HELD) remains the blocker.

### Todo (user, 2026-09-22): reject-to-source for trace amounts of material transfer

A transfer that would deliver only a trace amount of material over a step (a solid population below the trace volume fraction, or the 1e-26…1e-70 kg dust backward Euler leaves down a chain; by extension a trace fluid component) should be rejected back to the source node instead of being delivered. Mass stays conserved at the donor; the receiver never gains a sub-trace population. This would remove the dust at its origin rather than tolerate it, and could retire the floors that currently stand in for it: `SOLID_CLAMP_FRACTION` in `Equations.maximumStep`, the decode projection in `PhaseLayout`, `SolidInventory.Accumulator.finishNonNegative` in the companion, and the 1e-10 kg absolute floor in `PassiveIntervalSolver.error`. Not designed yet; to be specified against the reconstruction (`ConservativeTransport.reconstruct`) and the ledger before implementation. Possibly related to F1's zero-flow reconstruction-gate failure — handed to the diagnosis agent as a candidate, not a mandate.

## Progress 2026-09-22 (filter-island diagnosis) — reviewed

Branch `claude/solid-phase-filter-fix` @ `90f16c7`, four commits on `fca6a15` (agent worktree `.claude/worktrees/agent-a0fba21980f2fa09a`). Report `documentation/SOLID_PHASE_FILTER_ISLAND_DIAGNOSIS.md` (Codex copy `docs/solid-phase-filter-island-diagnosis.md`), 18 files under `documentation/screenshots/solid-phase-filter-fix/` incl. the client log.

| Commit | Verdict | What I verified |
| --- | --- | --- |
| `782e6ab` F1: tight Newton tolerance decided only by RESERVOIR/PORT seeds | sound | Junction, generator and void own no unknown or row; the two filter junctions are seeded liquid-full from the first boundary and dragged every filter island to 1e-10, which a two-phase tank's water-saturation row floors at 1.03e-10. PORT kept (hydrostatic-pair test needs it). Regression chain is reservoir-only → bit-identical. |
| `a152b0a` F2: reconstruction and equations share `JUNCTION_INFLOW_FLOOR` (1e-14) | sound | Previously `==0` vs `>1e-14`; an island at rest carries ~1e-14…1e-16 kg/s, so the reconstruction mixed a junction the equations retained; across a filter edge (no solids in the stream) the gate saw the junction's whole solid fraction (0.117 / live 0.058). `fed[]` applied to both the retain branch and the edge loop; same accumulation → bitwise agreement. |
| `287c862`, `90f16c7` `FilterBlockLineIslandTest` | sound | Builds the block lines through the real `PhysicalFluidTopology.compile` with world defaults and loops 5 s intervals from the 0.05 s cold start on one retained solver — the path no fixture exercised; 10 runtime tests (one disabled = F3 reproduction). |

Verified myself: XML 161/0, 116/0 (1 skipped), regression exact zero deviation, benchmark 30/11, 19/14, 37/3, GameTest `filter-fix-01` 20/20; client log HELD only for `fluid_island=11` (the F3 line). In-game: reservoir-terminated filter line at 101 kPa SOLVING; capture (Load/Captured rising), Recover → Recovered Solids item + Load reset, clogged at 100 % with `filter clogged`, recovery restores flow — all screenshotted. Check (c) of the GUI pass is now closed.

Bisect: F2 is Codex-original (identical at 440a754); F1 is Codex-original in substance (symptom moved by fixes E–H from a property-domain failure at interval 13 to the line-search stall at interval 8); shape (ii) is not a filter defect.

### Still open after this track

- **F3 (not fixed, filter-reachable):** a filter line feeding a *tank* driven above ~150 kPa stalls (`residual 1.0e-9`, interval 8/40 in the fixture, in-game at 400 kPa). Cause: the hydraulic row is scaled by a fixed 1e5 Pa, so 1e-9 Newton tolerance demands 1e-4 Pa absolutely; the filter's ~894 Pa/(kg/s) clean resistance lands the rest imbalance on its row ten times larger than on a pipe. Pre-existing scaling, but only a filter makes it bite (filter-free control at 400 kPa runs). Fix = scale the row by the island's pressure; not bit-identical → own branch + regression re-capture (now possible). Disabled reproduction `filterLineToATankKeepsIntegratingOnceTheTankIsFull` marks it.
- **Pump near shutoff head (pre-existing on main 3c27271, no filter involved):** generator → pump → pipes → tank holds at interval 17/40 when the tank reaches the pump's shutoff pressure (`PUMP_HEAD_LIMIT ↔ CLOSED` chatter). The live shape (ii) was this. Own track.
- **Shapes (iv)/(v)** (solids with flow into a void, live residuals 16435 / 5531) not reproduced; the same settings now run in-game without a hold. Hypothesis to probe if it recurs: the throttled-inlet row's `max(limit,1e-8)` denominator with a nearly full cake.
- **Reject-to-source (user todo):** assessed in report §8 — not F1's cause (the defect was two predicates, not a delivery); three of the four dust floors guard Newton/TR-BDF2 trial points and cannot be retired by it; the 1e-10 kg error floor only weakened; cost = regression re-capture, a declared transition for the threshold, ledger restatement, three consumers of one predicate.

### Verdict

Solver + GUI + filter: the feature works end to end in a live world at low pressure and to a void at any pressure. Recommend fixing F3 before merge (it is the tank-terminated case a player builds first), as its own reviewed commit on top of `90f16c7` with a regression re-capture; the pump defect is main's and can follow separately. Merge candidate after that: fast-forward `main` (3c27271) to the F3-fixed tip (includes the Codex commit, fixes A–H, GUI, harness fix `fca6a15`, filter fixes).

## Progress 2026-09-23 (F3 hydraulic row scale) — reviewed

Branch `claude/hydraulic-row-scale` @ `bdc113c`, three commits on `90f16c7` (agent worktree `.claude/worktrees/agent-ae086faac00564dbc`). Report `documentation/HYDRAULIC_ROW_SCALE.md` (Codex copy `docs/hydraulic-row-scale.md`), 8 files under `documentation/screenshots/hydraulic-row-scale/`.

| Commit | Verdict | What I verified |
| --- | --- | --- |
| `c4079dd` per-edge `pressureScales = max(1e5, max(P_first, P_second))` from the pass's seeds, applied to the hydraulic, `PUMP_HEAD_LIMIT`, `VALVE_REGULATING` and `VALVE_OPEN` rows | sound | Pass-constant (no Jacobian term, block/coloured sweeps identical), floored so ≤1 bar islands stay bit-identical, per edge. Row audit in the report §3 covers every other residual. Equation gate loosens by the same factor at pressure (intended). |
| `3c84036` chain-100 reference re-captured | sound | Qualified first: relative gate PASS against the old reference (state 8.1e-10, T 7.8e-9 K, phase 3.6e-11, flow 2.9e-7 rel = 17 % of the controller allowance) on a chain whose every edge is above the floor; exact then re-captured and verified at zero deviation. |
| `bdc113c` fixture javadoc updated, fixture re-disabled | sound | Honest: the line still holds, for two reasons behind F3 (below). |

Verified myself: XML 161/0, 116/0 (1 skipped), regression exact zero deviation, benchmark 30/**9**, 19/14, 37/3 (2-reservoir island sheds two rejections; recorded as the new baseline), GameTest `hydraulic-scale-01` 20/20; `clearChainSubstepCountsAreUnchanged` still 25/10, 32/14, 18/5 (rescaled edges, robust). In-game: 400 kPa filter → void line with 5 % solids clogs at 100 % and recovers (positive); 400 kPa filter → tank line holds at 1.0007e-9 = the node-block floor below.

### F3 is fixed; F4a / F4b were behind it (still open, filter → tank lines only)

After the rescale no hydraulic row is the binding row at any swept pressure (filter row 4.39e-9 → 4.1e-12 at 600 kPa). The driven filter → tank line still stops, at unchanged intervals, on the tank's node block:
- **F4a** (200, 350, 400, 600 kPa): the tank's nitrogen vapour/liquid equilibrium row and volume closure floor at 1.0–1.4e-9 against the 1e-9 tolerance with the step refined to 6.6e-9 s; the residual is smooth there (one-ULP nudges move no row by >1e-13) → a near-singular trace-nitrogen equilibrium pair on a pressurized water tank — the same cancellation the existing `1e-11 stalls on caloric cancellation…` comment calibrated at atmospheric pressure only. The filter-free control at the same pressures runs 40/40, so the filter's junction pair upstream is what makes the tank block bind.
- **F4b** (150, 250, 300 kPa): the filter's outlet junction starves once the tank is full — its amount normalization row reaches −1.0 (total amount driven to zero), specific enthalpy row ~−67. Gross, not a tolerance edge; likely the retained-guess/inflow-floor path of a zero-holdup junction whose only inflow has gone to ~0 (interaction with `JUNCTION_INFLOW_FLOOR` / `retainedPressures`).

Both change every island's physics if fixed and need their own qualification against the (now working) relative regression gate. Next diagnosis after the pump agent (Gradle serial). Until then the documented limit is: **an in-line filter that feeds a tank stops once the tank fills at any driving pressure above atmospheric**; filters into a void or an open line work at any pressure.

Pump agent launched 2026-09-23 on `claude/pump-shutoff-active-set` from `bdc113c` (hypothesis: mode switch on a reverse flow of 1e-10 kg/s that the hydraulic row's resolution cannot support, inverse of the CLOSED→HEAD_LIMIT hysteresis).

## Progress 2026-09-23 (pump shutoff active set) — reviewed

Branch `claude/pump-shutoff-active-set` @ `ae3b37c`, two commits on `bdc113c` (agent worktree `.claude/worktrees/agent-af5232d111e7302fa`). Report `documentation/PUMP_SHUTOFF_ACTIVE_SET.md` (Codex copy `docs/pump-shutoff-active-set.md`), 13 screenshots under `documentation/screenshots/pump-shutoff/`.

My chatter hypothesis was **refuted by measurement**: the pump never entered CLOSED in the failing interval. Measured chain: every solve restarted the pump on PUMP_TARGET (9.96 kg/s), which drove the tank 2.6–13 kPa past shutoff; the head-limit pass then converged to a reverse flow; the suction pipe's reverse flow at the generator failed `boundaryAllowed` and, being scanned first with one active-set change per pass, was applied while the pump's own CLOSED decision (taken 12 times) was discarded; `boundaryClosed` is never cleared within a solve, so the pump was left on its head limit with the flow parked at −1.0000215e-14 kg/s — exactly `JUNCTION_INFLOW_FLOOR`, where the junction's mixing rows are discontinuous — and Newton stalled at every step size (residual constant as dt → 0). Behind it, the PUMP_TARGET → HEAD_LIMIT warm start (9.96 kg/s vs answer 0.07 on a power-law loss) exhausted the 20 Newton iterations at every step size.

| Commit | Verdict | What I verified |
| --- | --- | --- |
| `eb25fc1` four rules in `PassiveStepSolver.solve` | sound | R1 device mode decided before the boundary closure its reverse flow shows (deferred to a later pass; bit-identical for actuator-free islands since only a device can set `changed`). R2 HEAD_LIMIT↔CLOSED decided on one scalar `margin = max − demand`, same expression in both modes (= the edge's own loss in HEAD_LIMIT, the head in hand in CLOSED), against `shutoffBand = max(0.01 Pa, tolerance × pressureScale)`, with an `atShutoff` latch that forbids reopening within the solve and is released only at the next solve's start against the accepted state → acyclic, and the old 0.01 Pa reopen band is kept so ≤1e5 Pa islands are bit-identical. R3 head-limited pumps seeded by bisection on the limit (`headLimitMassFlow`). R4 `previousModes` carried across solves on the same graph identity (HEAD_LIMIT/CLOSED only). Ablation: R2, R3, R4 each necessary; R1 corrects the endpoint (0.91 Pa above shutoff without it). |
| `ae3b37c` fixture | sound | Both pumped lines OK 40/40; pumped tank vs passive-at-shutoff tank 1.5e-8 relative in pressure; mass 7.9e-5 asserted at 1e-4 with the physical reason (0.077 K colder feed path). |

Verified myself: XML 161/0, 116/0 (1 skipped), regression exact zero deviation (chain-100 has no pumps), benchmark 30/9, 19/14, 37/3 unchanged, GameTest `pump-shutoff-01` 20/20; client log 0 `status=HELD`, 0 `fluid_island`; in-game pump `FULL / CLOSED` at 500.00 kPa head with the tank at 601.32 kPa / 829.02 kg matching the fixture to every printed digit, lag falling over four minutes. Valves unchanged (weakest claim, see below).

Notes: the filtered pumped line settles 0.78 Pa below shutoff because the band reads the pump edge's own loss and the filter carries ~63× its resistance — a 1e-6 relative effect at 6 bar, accepted. The valve corner could not be reached: a `generator 400 kPa → pipe → PressureValve(200 kPa) → 2 pipes → tank` line holds at interval 6 with the tank at 387 kPa, residual 1.0000000822e-9 at pass 0 with no transition, identical with all four rules disabled — **that is F4a again, on a line with no filter**, so F4 is not filter-specific: it is a tank fed through a zero-holdup device junction at pressure.

### Verdict

Pump defect fixed and confirmed in-game; all baselines held. Tip for the F4 diagnosis: `ae3b37c`.

## Progress 2026-09-23 (F4 junction donor switch) — reviewed

Branch `claude/tank-node-block` @ `0f13e31`, two commits on `ae3b37c` (agent worktree `.claude/worktrees/agent-a74f606336340ffe9`). Report `documentation/TANK_NODE_BLOCK.md` (Codex copy `docs/tank-node-block.md`), 21 files under `documentation/screenshots/tank-node-block/`.

**Root cause (one mechanism, measured with labelled residual/Jacobian dumps):** a zero-holdup junction's mixing rows (mass fractions, specific enthalpy) are ratios of the flows feeding it, so they do not shrink with the flows; deciding the donor inside the residual put a finite jump in them at exactly zero flow, and the one-sided differences the Jacobian is built from (`+1e-6` on a flow of 5e-14) only ever sample the positive branch. On a settled tank the last pressure correction (1.5 mPa) moves every flow by 1.6e-6 kg/s through the filter's 894 Pa/(kg/s), crossing the jump: linear prediction 8e-25, delivered 1.7e-3, 24 backtracks on the same plateau. The "tank node block" reading was wrong: the tank block is solvable (unreachable residual 2.3e-25); the 1.3e-9 seed is the reconstruction re-encoding (30 % above the Newton exit) and is universal (tightening the 1e-8 gate breaks the filter-free controls). Row-map correction: the rows that floored were the tank's volume closure and water saturation, not a nitrogen equilibrium row (this tank has no hydrocarbon liquid → no split unknown).

| Commit | Verdict | What I verified |
| --- | --- | --- |
| `a02a426` frozen junction donor per pass | sound | `junctionDonorFirst` read off the pass's initial point; only the junction accumulators (`incomingMass`, `incoming[]`, `incomingEnergy`, `solidIncoming[]`) read it; reservoir targets, net-flow, energy ledger and edge rows keep the live upwind; `donorsTurned` on a converged point is an active-set change; donor signs in `WorkspaceKey` (cycle key); junction-free connections constant `true` so junction-free islands are bit-identical (exact gate 0.000e+00, reference untouched). Diverged trial points deliberately not read (measured worse). Smoothing the switch instead was implemented, measured (regressed two solids fixtures, exposed a normalisation mismatch between `junctionInflow`'s two branches) and reverted. |
| `0f13e31` valve fixture + filter fixture javadoc | sound | Valve line 40/40 at 300/400/600 kPa, tank within 3.4e-9 relative of the filter-free control. |

Verified myself: XML 161/0, 117/0 (1 skipped), regression exact zero deviation, benchmark 30/9, 19/14, 37/3, GameTest `tank-node-01` 20/20, client log HELD only for `fluid_island=26` (the filter line). In-game: valve line SOLVING with lag 0.00 s after its tank fills; filter line with 5 % solids clogs and solves (tank never fills); clear-water filter line fills the tank then holds at residual 66.97.

### F5 — phantom nitrogen trace on the filter's junctions (still open, filter → tank only)

`reachableComponents` is undirected, so the tank's nitrogen is "reachable" at both filter junctions whichever way the line flows; `initialPhaseSeeds` writes `total·1e-12` of it into each and flashes; a junction owns no volume, so that trace becomes a vapour phase of 2.65e-14 of the node's scale, and the junction's water-saturation and partial-pressure-closure rows are stated on a vapour that is numerically nothing: the block is rank 5 of 6 (σ_min 2.4e-149). Nothing delivers the trace (the inflow fraction row pins nitrogen at 5.5e-10), so Newton's only root is exactly zero and the 0.99 nonnegativity rule caps every step; the 20-iteration limit arrives at 0.298 (or 65–70 after a phase correction). Tried and reverted: skipping the trace seed on junctions (phase-appearance pass required), narrowing reachability from accepted flows (re-widened by the pass loop's own backflow). Candidate: trace support at the node level — a phase that exists only as a seeded trace on a zero-holdup junction is not a phase — via the existing `PhaseSupport`/`TraceTruncationPolicy`/`singlePhaseComponentCount` machinery; changes how junction layouts are built → own track with regression qualification. Open question: why the valve's junction (also seeded with the trace) survives.

Latent, recorded, not fixed: (a) `junctionInflow`'s two branches normalise differently (fed: delivered fluid mass, sum 1; stored: total mass incl. solids, sum 1 − solid fraction) — harmless while only one branch is active; (b) `edgeRows` uses the live donor's density in `driving` (`rho·g·dz`), a second degree-zero jump on lines with elevation difference between nodes of different density — every fixture is flat, untested.

### Verdict

Fourth layer of the onion (F1 tolerance → F3 row scale → F4 donor switch → F5 phantom trace), each real, each verified. Tip `0f13e31` is mergeable for everything except a filter feeding a tank; F5 agent launched 2026-09-23.

## Progress 2026-09-23 (F5 junction phantom trace) — reviewed

Branch `claude/junction-phantom-trace` @ `04ad5ca`, two commits on `0f13e31` (agent worktree `.claude/worktrees/agent-af48289c1308c2ea4`). Report `documentation/JUNCTION_PHANTOM_TRACE.md` (Codex copy `docs/junction-phantom-trace.md`), 27 files under `documentation/screenshots/junction-phantom-trace/`.

**Why the valve line was already safe:** `reachableComponents` grants upstream species transport only across a *passive* connection; a valve is an actuator, so the tank's nitrogen never reached the valve's junction (basis `{water}`, 3 unknowns), while a filter edge is a passive pipe carrying a filter, so the closure walked the nitrogen into both filter junctions (basis `{N2, water}`, 6 unknowns, vapour phase 5.5e-8 mol = `total·1e-12`). **Mechanism:** the trace exists only in `initialPhaseSeeds`; its `fraction[19]` row pins it to the stored guess (no nitrogen) so its only root is 0, and the 0.99 nonnegativity cap makes Newton approach it 1 % per iteration. The pre-existing `refineJunctionReachability` could not help because it was fed `failure.lastVariables()` — a diverged trial whose flows (−1.14e-6 kg/s on a forward-driven line) re-widened the basis at all 22 refinement levels.

| Commit | Verdict | What I verified |
| --- | --- | --- |
| `38bfa83` junction species/seed/donor from the pass's start point | sound in design; large (437 lines in `PassiveStepSolver`, 14 in `PhaseLayout`) | Four sites, all gated on `hasJunction` so junction-free islands are bit-identical (exact gate 0.000e+00, reference untouched): `refineJunctionReachability` once per pass from `startPoint` (directed closure, `hasSolids||filter` guard removed); `restateJunctions` seeds a junction as its donors' delivered mixture normalised to its guess's amount, keeping the stored solid state; `PhaseSupport`/`PhaseLayout`: a masked component with no phase to hold it is `ABSENT` (omitted trace) instead of a throw; `initialMassFlows` contracts a degree-two passive-junction run to one resistance so a cold start is a flow pattern a junction can be in. Revisions only from converged points (`junctionsTurned` compares bases, not classifications); `sameTransport` guards restatement; cycle key unchanged in kind. Two alternatives implemented, measured, reverted (continuity screen broke 350 kPa; restating all seeds as a `continue` broke the pumped control). |
| `04ad5ca` filter fixture re-enabled as a 7-pressure sweep | sound | 150–600 kPa all 40/40; tank within 4.4e-7 (P) / 3.1e-7 (mass) of the filter-free control. |

Verified myself: `fluidRuntimeTest` re-run on the tip → 117 tests, 0 failures, **0 skipped** (Gradle FROM-CACHE of the agent's execution on identical inputs at 04:06; the agent's later single-test timing runs had overwritten the XML), 29 sweep lines `OK intervals=40`; science 161/0; regression exact zero deviation; benchmark 30/9, 19/14, 37/3; GameTest `junction-trace-01` 20/20; pumped filter fixture 2× faster (0.66 → 0.33 s, 3-unknown junction layouts). In-game: clear-water filter → tank line fills to 743.16 kg and keeps SOLVING at lag 0.10 s, zero HELD (was lag 177 s with 154 HELD at `0f13e31`).

### F6 — solids fed into an already-full filter line (open, runtime layer)

Setting the generator to 5 % solids after the tank is full holds the island at once: `HELD: Junction mass continuity does not close` (`ConservativeTransport:191`, `|total−1|>1e-8`); the filter captures nothing (no flow), and setting the feed back to 0 % does not release it (`WAITING: configuration event / HELD`). Not reproducible through `PassiveIntervalSolver` (generator swapped to slurry at 12 switch points of a 60-interval run: OK, solver retained or reset) → the runtime configuration-event path or the retained solver's warm state across an unchanged graph identity. Mechanism candidate: the junction across the filter edge is weighted `1/(1−s)` with the *candidate* donor's solid fraction while the donor's reconstructed fluid fractions sum to `1−s'`; F5's restated junction seed keeps the *stored* solids (none) while the delivered mixture now carries solids; the recorded `junctionInflow` fluid-vs-total normalisation mismatch is the same family. F6 agent launched 2026-09-23 from `04ad5ca`.

### Verdict

Fifth layer. Tip `04ad5ca` is mergeable for every scenario measured except "add solids to a filter line whose tank is already full" (island holds and cannot be reconfigured). Latent, still unfixed: `junctionInflow` normalisation; live-density `rho·g·dz` jump on elevated lines; chain contraction limited to degree-two junctions.

## Progress 2026-09-23 (F6 solids onto a full tank) — reviewed

Branch `claude/full-tank-solids-event` @ `c7da543`, three commits on `04ad5ca` (agent worktree `.claude/worktrees/agent-ab8d04b83a80709c0`). Report `documentation/FULL_TANK_SOLIDS_EVENT.md` (Codex copy `docs/full-tank-solids-event.md`), 29 files under `documentation/screenshots/full-tank-solids-event/`.

**Why the step-solver probe missed it:** a device edit is not a boundary swap inside the running graph; `FluidWorldAuthority.applyPending` recompiles the island (`PhysicalFluidTopology.compile` with the live boundaries and the freshly initialised edited one), registers it as a new island and discards the retained solver. Two defects on that path, both Codex-original in the runtime, neither in the step solver:

| Commit | Verdict | What I verified |
| --- | --- | --- |
| `a6f91f7` `ConservativeTransport.storedFractions` | sound | For a node the reconstruction does not solve (prescribed boundary, unfed junction) the fluid half was read off the candidate and the solid half off the stored inventory, both over the candidate's mass; a junction's total is a free scale Newton moves (−0.099 %), so `Σ = 1.000116` against a 1e-8 gate. Now both halves come from the candidate, the inventory supplying only the population split (`scale = moments.mass()/stored.massKg()`); identity of the expression, bit-identical where the two agreed (exact gate 0.000e+00, reference untouched). After `80f1b44` no live path reaches the throw; kept as the correct statement (agent says so explicitly). |
| `80f1b44` `PhysicalFluidTopology.compile` | sound | Junctions were minted with the first boundary's *state including its solids* (125 kg on every junction, on activation and every edit, including past a filter edge where nothing can deliver them) → singular Jacobian at pass 0 once the line flows. Now the seed is the boundary's properties with `SolidInventory.EMPTY`; bit-identical when the first boundary carries none. Same rule as `initialPhaseSeeds` for species. |
| `c7da543` `FullTankSolidsEventTest` (4 fixtures) | sound | Drives the event the way the world does (rebuild), keeps the in-place swap as the control that never reproduced it; capture after draw-down; feed back to 0 %. |

Verified myself: `fluidRuntimeTest` 121/0/0 skipped (FROM-CACHE of the real run at 05:04; the agent's timing runs had overwritten the XML), 29 sweep lines OK 40; science 161; regression exact zero; benchmark 30/9, 19/14, 37/3; GameTest `full-tank-solids-01` 20/20; client log 0 HELD over the 255-line session. In-game: solids applied to a full tank accepted at lag 0.55 s; filter captures 24.21 → 25.00 kg after a void draws the tank down (a void cannot sit against a reservoir; one pipe between); Recover yields `createcheme:recovered_solids`; feed back to 0 % accepted and applied.

### Open, by category

- **Design decision (user):** a permanently HELD island cannot take a configuration event — `IslandClock` keeps a held island's `committedTick`, `IslandCoordinator.aligned` needs every affected island at the fence tick, so `applyPending` never reaches the edit. Exact, deliberate, and a change to the determinism contract (an edit lands at one tick for every island it touches). With the six fixes no known line holds, so it is dormant; but any future hold is permanent and unrecoverable in-game.
- **Latent, untested:** `edgeRows` `driving` uses the live donor's density → degree-zero jump `g·dz·Δρ/pressureScale` at the upwind switch on lines with elevation between nodes of different density (water/nitrogen: ~2e-2 per metre at 4 bar), the F4 mechanism on the hydraulic row; every fixture and GameTest is flat. Probe agent launched 2026-09-23.
- **Latent, harmless while unreachable:** `junctionInflow` branch normalisation; `restateJunctions` keeps stored solids; chain contraction limited to degree-two junctions.
- **Evidence gap:** the three saved-island regression fixtures skip on every branch (no current-basis `core.dat`); only chain-100 is replayed. Stale 154007d references still to delete.
- **Housekeeping:** GUI-pass commits carry the Opus attribution line; GUI-4 (two rows per page at the default window).

### Verdict

Six layers (F1 tolerance, F3 row scale, F4 donor switch, F5 phantom trace, F6 event rebuild ×2) plus fixes A–H, the GUI pass and the pump rules: every measured scenario now runs, in fixtures and in the client. **`c7da543` is the merge candidate** (fast-forward `main` 3c27271 → `c7da543`: Codex commit, A–H, GUI, harness fix, F1/F2, F3, pump R1–R4, F4, F5, F6), subject to the elevation probe's result.

## Progress 2026-09-23 (F7 elevated lines) — reviewed

Branch `claude/elevated-line-probe` @ `ec9ea74`, two commits on `c7da543` (agent worktree `.claude/worktrees/agent-a28b2d53abd8b3809`). Report `documentation/ELEVATED_LINE_PROBE.md` (Codex copy `docs/elevated-line-probe.md`), 41 files under `documentation/screenshots/elevated-line-probe/`.

**The risk was real.** A vertical pipe stack compiles to one connection with `dz = 4` (asserted). On the unmodified tree three of four elevated shapes held (rising, falling, rising through a filter; the pumped one passed only because the pump keeps the flow positive). Dump at 400 kPa: the hydraulic row steps by exactly `(ρ_first − ρ_second)·g·dz/pressureScale = 0.0275415` at zero flow (measured 2.7541514e-2), linear prediction 1.08e-19 vs delivered 2.76e-2, flat plateau over six orders of α — F4's mechanism on the hydraulic row. Physically: whenever `ρ_second·g·dz < ΔP < ρ_first·g·dz` the row has **no root** (each branch wants the other sign), and a rising line fills its tank to the upper edge of exactly that band.

| Commit | Verdict | What I verified |
| --- | --- | --- |
| `8c072f7` `Equations.headDensities` | sound | One density per connection from the pass's seeds (with the actuator head from the start point), used only in `driving`'s static head; friction and velocity cap keep the live donor (odd in the flow, no jump). Admissibility rule: both columns admissible → first; neither (the band) → the column nearest its own rest point (rest is a fixed point of the rule); one admissible with the other forbidden → the start point's flow sign (bistability = history). Not revised by the active-set loop (would alternate in the band) and not in the cycle key (measured worse: cache churn broke the balanced line). Bitwise zero for `dz = 0` (exact gate 0.000e+00, reference untouched). |
| `ec9ea74` `ElevatedBlockLineIslandTest` (4 fixtures) | sound | Rising/falling/filter/pump/balanced lines 40/40 at 400 kPa within 0.4 Pa of the hydrostatic prediction (pump 23.6 Pa, physical, asserted at 1e-4); the dead-head fixture asserts flat/elevated *equivalence* of the remaining failure. |

Verified myself from artifacts: 161/0, 125/0/0 skipped, regression exact zero, benchmark 30/9, 19/14, 37/3, GameTest `elevated-01` and `elevated-02` 20/20; client log 0 HELD in the clean session. In-game: vertical 400 kPa line settles at 360.92 kPa / 715.34 kg = the fixture's endpoint (400 kPa − 39.08 kPa column), lag bounded 1.5–3.5 s, survives save + client restart. The existing science fixture `hydrostaticRestAndReversedElevationUseTheSameGravityEnergyLedger` caught a wrong draft of the rule (0.18 Pa residual driving); the tree was not flat everywhere.

### F8 — the dead-headed line (open, pre-existing, default settings)

A water generator at the default 101.325 kPa with a nitrogen tank four blocks above cannot lift the column; the only remaining direction (gas down into the generator) is forbidden by `boundaryAllowed`, so the model answer is zero flow through the boundary closure — and the solver never gets there. Reproduced **in the client on the first build** (`HELD … residual 5.066656785680298E-4`, identical digits to the fixture) and it is not about elevation: the flat equivalent (tank charged above its generator at 1 Pa, 8.7 kPa, 39 kPa, 98.7 kPa adverse, and the filter shape) holds bit-identically on the unmodified solver. Mechanism from the dump: the flow must travel from 0 to the reverse root, the friction slope on the nitrogen branch is ~3 orders steeper than on the water branch, and the nonnegativity limiter pins α to nothing because the tank carries a phantom **water** trace at 1e-22 (the `total·1e-12` reachable-component seed) whose only root is zero and which nothing delivers — the F5 phantom-trace family on a reservoir. Consequence (in the world): the held island applies neither configuration nor topology events, survives block removal, save and client restart; the only way out was to build elsewhere. F8 agent launched 2026-09-23.

Latent, recorded: the velocity clamp's donor (`capMassFlows`/`capPressureDrops` indexed by the flow's sign) is a second degree-zero switch on the same row, dormant now that the head is frozen.

### Verdict

Seven layers. **Merge candidate: `ec9ea74`** (fast-forward `main` 3c27271 → `ec9ea74`), subject to F8 since a default-settings vertical line permanently holds; the HELD-island event stall remains the user's design decision.

## Progress 2026-09-23 (F8 dead-headed line) — reviewed

Branch `claude/dead-headed-line` @ `1403eeb`, three commits on `ec9ea74` (agent worktree `.claude/worktrees/agent-a5f899be8bc91482c`). Report `documentation/DEAD_HEADED_LINE.md` (Codex copy `docs/dead-headed-line.md`), 30 files under `documentation/screenshots/dead-headed-line/`.

**Mechanism (dump):** the physical answer is exactly zero flow through the boundary closure; the pass could never converge to the point that closure is taken from. The Newton direction was exact (residual prediction 1.3e-19) and pointed at the reverse root, but `maximumStep` capped alpha at 5e-17 on the tank's phantom water trace (`waterVapor = 1e-24`, `clampFloor = 0`), so the line search had one trial, bitwise its start. A clamp floor cannot work alone: every larger alpha is refused by the thermodynamics' `Invalid water split` (measured down to alpha = 1.5e-11).

| Commit | Verdict | What I verified |
| --- | --- | --- |
| `b0590a4` `closeDeadHeads` | sound | Before any pass, over each maximal passive run through degree-two non-actuated junctions with non-junction ends: if one direction is forbidden by `boundaryAllowed` and the driving pressure (tested against the column that would fill each direction, F7's pairing) has no root in the allowed direction, close the whole run. Monotone within a solve, adds no transition (only removes one), `boundaryClosed` already in the cycle key; actuator connections excluded (R1 intact); per solve, so raising the generator re-opens. Vessel/boundary ends only (a vessel's pressure moves monotonically against the delivering flow, so an adverse start stays adverse). |
| `88dda15` species from open connections | sound | `reachableComponents` skips closed connections and is stated after the closure, so no phantom entry trace is planted behind a pinned-zero connection. Identity wherever nothing was closed (ablation: A alone = baseline to every digit). The obvious alternative (trace gated on start-flow signs) was implemented, measured wrong on the 1 Pa case, and rejected. |
| `1403eeb` `DeadHeadedLineIslandTest` (4 fixtures) | sound | Asserts mode CLOSED, flow exactly 0, inventory to 1e-12 per component (one ULP drift over 40 intervals), pressure to 1e-9; raise the generator: fills to 360918.47 Pa; lower it: rest. |

Ablation: B alone fixes the five plain shapes; the run contraction is what reaches the two filter shapes; A + B ships. Side effect in the right direction: the rising-through-filter elevated line's endpoint error 0.382 to 0.041 Pa.

Verified myself: `fluidRuntimeTest` 129/0/0 skipped (FROM-CACHE of the real run at 07:26), 20 sweep lines OK 40; science 161; regression exact zero, reference untouched; benchmark 30/9, 19/14, 37/3; GameTest `dead-head-01` 20/20; client log 0 HELD and 0 `fluid_island` over the session. In-game, the exact bricking scenario placed with `/setblock` at defaults: generator `FULL`, `Flow (last) 0.00 kg/s`, tank untouched at 1.15 kg nitrogen; 400 kPa accepted (`Settings accepted at the current simulation event`) and the tank fills to 360.92 kPa / 715.34 kg; back to 101.325 kPa: at rest, lag 0.2 to 2.4 s.

User-visible note (agent flagged for a second opinion): a tank settled at its own hydrostatic balance under its generator is dead-headed in the same sense, so its connection's accepted mode is now `CLOSED` rather than `PASSIVE`. Device screens show `FULL` and `Flow (last) 0.00 kg/s` either way; no fixture asserts the mode; passive pipes do not print their mode in the status.

## Merge summary 2026-09-23

**Merge candidate: `claude/dead-headed-line` @ `1403eeb`**, 37 commits over `main` @ `3c27271`, fast-forwardable (`git merge-base --is-ancestor` confirmed): Codex `440a754`, fixes A-D (`a4db1fe`), E-H (`e97fe5b`), GUI pass (`bfb22ef`), regression harness (`fca6a15`), filter F1/F2 (`90f16c7`), hydraulic-row scale F3 (`bdc113c`), pump R1-R4 (`ae3b37c`), junction donor F4 (`0f13e31`), junction phantom trace F5 (`04ad5ca`), event rebuild F6 (`c7da543`), static head F7 (`ec9ea74`), dead-head F8 (`1403eeb`).

Final gates on the tip: `fluidScienceTest` 161, `fluidRuntimeTest` 129 (0 skipped), `fluidSolverRegression -PfluidRegressionMode=exact` zero deviation (chain-100; the three saved-island fixtures skip for want of a current-basis snapshot), `fluidNetworkBenchmark` 30/9, 19/14, 37/3, GameTest 20/20. Every scenario measured on this track runs in fixtures and in the dev client: slurry chains; the pump at shutoff; filters into a void; filters and valves into tanks at 150 to 600 kPa; solids applied to a full line, capture, recovery, clogging, feed back to zero; vertical lines up and down, through filters and pumps; dead-headed lines at default settings.

Still open, none blocking in my judgment:
1. User design decision: a permanently HELD island cannot take configuration or topology events (determinism fence). Dormant now; the reason any residual hold is a brick.
2. Latent switch: the velocity clamp's donor is indexed by the live flow sign (measured jump -0.278 to -1.0 at zero flow on the 140 kPa case); dormant now that the head is frozen and dead-heads close before the pass.
3. Evidence gap: the three saved-island regression fixtures need a current-basis stress-world snapshot; the stale 154007d references should be deleted (`git rm`, blocked by tool policy in this session).
4. Housekeeping: the GUI-pass commits (`3672cf1`, `a399100`, `bfb22ef`) carry the Opus attribution line; GUI-4 two rows per page at the default window; `junctionInflow` branch normalisation and `restateJunctions` keeping stored solids (harmless while unreachable).
5. User todo: reject-to-source for trace amounts, assessed in `TANK_NODE_BLOCK.md` section 8 and `DEAD_HEADED_LINE.md` section 9; the F5/F8 rules reach the same end for seeded traces without a threshold; the general rule would additionally cover delivered sub-cutoff traces.

Merge command, from the main checkout, your call:

    git -C D:/Minecraft/Modding/1.21/CreateChemE merge --ff-only claude/dead-headed-line
