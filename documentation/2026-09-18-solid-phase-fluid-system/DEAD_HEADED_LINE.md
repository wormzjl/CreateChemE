# The line the generator cannot drive

Worktree `D:\Minecraft\Modding\1.21\CreateChemE\.claude\worktrees\agent-a5f899be8bc91482c`,
branch `claude/dead-headed-line`, base `ec9ea74`.

**Headline.** The dead-headed line is fixed, and it was not one defect but three ingredients of
which only one needed removing. The physical answer is a flow of exactly zero, reached through the
boundary closure the pass loop already has; the whole defect is that the pass could never converge
to the point that closure is taken from. The dump says so precisely: the Newton direction's own
linear model was exact to `1.3e-19` and pointed straight at the reverse root, and the step limiter
capped `alpha` at `5.0e-17` because of a phantom water trace nothing delivers — and every larger step
was refused outright by `FluidThermodynamics`' own `Invalid water split`, so no threshold on the
limiter could ever have let the journey happen.

So the closure is taken **before** the pass, from the point the pass starts at, by the sign test the
hydraulic row itself implies. All seven dead-headed shapes go from held to **40/40 intervals with
zero Newton failures**, the exact regression gate stays at `0.000e+00` with the reference untouched,
and the benchmark is unchanged. A second rule then removes the phantom trace itself, and it is
provably a no-op anywhere the first rule has not closed something — measured: with the closure
disabled, all seven shapes stall at exactly the residuals the unmodified tree stalls at, to every
digit.

Everything below is measured. Where I predict rather than measure, I say so, and section 10 lists
what I did not do.

---

## 1. Setup verification

| Step | Result |
| --- | --- |
| `git checkout -b claude/dead-headed-line claude/elevated-line-probe` | done |
| `git log --oneline -1` | `ec9ea74 Build the block lines with an elevation difference and hold them to their column` — as required |
| Other checkouts touched | none written. `D:\Minecraft\Modding\1.21\CreateChemE` and every other worktree were read only. Four files were *copied out* of read-only references into this worktree: the MCP bridge jar from `solid-phase-fluid-system-plan-4369b0`, and `cmd.sh` / `mc.sh` / `poke.ps1` from `agent-a28b2d53abd8b3809`'s `build/mcp`. `Flatten.class` and `Poke.class` were used in place from `agent-a74f606336340ffe9`'s `build/flatten` on the classpath, not copied. |
| `git stash` | never used, bare or otherwise. The one baseline comparison that needed the unmodified solver (the timing samples of section 8) was taken by copying `PassiveStepSolver.java` to `/tmp/keep.java`, `git checkout ec9ea74 --` on it, measuring, and copying it back; `git diff HEAD --quiet` then confirmed the tree matched `HEAD` again. |
| Gradle concurrency | `Get-Process java \| Select Id,CPU` sampled twice six seconds apart before the first invocation — the busiest of the seven idle daemons moved 0.02 s of CPU in 6 s — and checked before each later one. One Gradle invocation at a time throughout. No suite ran while the dev client was up: the client was started only after every suite, the regression, the benchmark, the GameTest server and the timing samples had finished. |
| `python` in Git Bash | not used. Everything is Bash, PowerShell, Gradle or a small Java helper. |
| Commit trailer | every commit ends with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. |
| Island event/fence contract | `IslandClock`, `IslandCoordinator.aligned` and `FluidWorldAuthority.nextReadyEvent` are untouched. `git diff ec9ea74..HEAD --stat` names two files, neither of them those. |

### Baselines, re-measured on this checkout before any change

| Gate | Baseline | Required |
| --- | --- | --- |
| `fluidScienceTest` | 161 tests, 0 skipped, 0 failures | 161 |
| `fluidRuntimeTest` | 125 tests, 0 skipped, 0 failures | 125, 0 skipped |
| `fluidSolverRegression -PfluidRegressionMode=exact` | `chain-100` `0.000e+00` on all four gates; `quiet-11312`, `quiet-11324`, `cold-11312` SKIP (no readable `core.dat` in this worktree) | zero deviation |
| `fluidNetworkBenchmark` | 30/9, 19/14, 37/3, all CONVERGED | 30/9, 19/14, 37/3 |
| `runFluidGameTestServer` | All 20 required tests passed | 20 |

---

## 2. Reproduction

A temporary `DeadHeadStallProbe` under `science/fluid/network` built the seven shapes through the
real `PhysicalFluidTopology` and ran each for 40 × 5 s intervals on one retained
`PassiveIntervalSolver` from `RetainedSolver.COLD_START_SECONDS`, the way the island worker runs
them. A temporary `PassiveStepSolver.STALL_PROBE` consumer in the `SparseNewton.Nonconvergence`
catch dumped the stalled point. Both were removed before the first commit.

All seven reproduce, and every residual matches `ELEVATED_LINE_PROBE.md` section 6 **to every
printed digit**:

| shape | baseline `ec9ea74` | section 6 |
| --- | --- | --- |
| flat, tank charged 101326 Pa (1 Pa adverse) | HELD @2, `Newton line search stalled at residual 1.9355024035429073E-5` | same |
| flat, 110000 Pa (8675 Pa adverse) | HELD @1, `0.0788636363636365` | same |
| flat, 140395 Pa (39070 Pa adverse) | HELD @1, `0.27828626375583176` | same |
| flat, 200000 Pa (98675 Pa adverse) | HELD @1, `0.493375` | same |
| **rising, 101325 Pa, tank 4 blocks up** | HELD @1, `Newton line search stalled at residual 5.066656785680298E-4` | same, and the dev client's own log printed the same digits for the placed blocks |
| flat, generator–pipe–filter–pipe–tank at 140395 Pa | HELD @1, `Newton iteration limit at residual 0.29469706587678757` | same |
| rising, the same through a filter | HELD @1, `Empty fluid initialization` | same |

`0.493375` is `98675 / 200000` — the whole adverse pressure showing on a row whose flow cannot move
at all.

### 2.1 The stalled point

The dump at the **last** failure before each hold, which is the one that decides it. Columns and
rows labelled (the row labels are reconstructed from `PhaseLayout`'s own ordering by reflection; the
column labels from its index fields).

Rising line at 101.325 kPa, `dt = 2.7932474023194545e-8`, active-set pass 0, two nodes, six unknowns:

```
  0 col=n1.vapor[19]     x=+9.999999999990e-01 | row=n1.balance[19]            f=+0.000000000000e+00  amount=true  clampFloor=0
  1 col=n1.waterVapor    x=+9.999999999990e-25 | row=n1.waterBalance           f=-5.331016848110e-09  amount=true  clampFloor=0
  2 col=n1.lnT           x=-1.603364388707e-01 | row=n1.energy                 f=+8.830674140157e-08
  3 col=n1.lnP           x=+1.316298660901e-02 | row=n1.volume                 f=+7.412370273130e-09
  4 col=n1.partialPress  x=+1.316297915980e-02 | row=n1.partialPressureClosure f=-7.449203029361e-09
  5 col=edge0.flow       x=+1.405773123132e-01 | row=edge0.hydraulic           f=-5.066656785680e-04
||f||inf = 5.066656785680298E-4
node[0] GENERATOR y=-59 P=101325.000000 rho=996.008376   n[20]=5.528687e+04
node[1] RESERVOIR y=-55 P=101325.000008 rho=1.145353     n[19]=4.088590e+01   n[20]=4.088590e-23
headDensities=[1.145353077693726]   pressureScales=[101325.00000779484]   boundaryClosed=[false]
```

`n[20] = 4.0886e-23` on a tank charged with pure nitrogen is the phantom water: the entry trace
`initialPhaseSeeds` plants at `total*1e-12` for every component `reachableComponents` says can
arrive. It is the *only* reason the tank has a `waterVapor` unknown and a `waterBalance` row at all.

The hydraulic row swept across its own flow, everything else held:

```
 q=+1.0e-02  -4.451326846832e-04      q=-1.0e-16  -4.434079981857e-04
 q=+1.0e-06  -4.434081706544e-04      q=-1.0e-12  -4.434079981827e-04
 q=+0.0e+00  -4.434079981857e-04      q=-1.0e-06  -4.434049985087e-04
                                      q=-1.0e-02  -2.427256767514e-04
```

Negative everywhere on the forward branch and rising towards zero only on the reverse one: the row's
value at `q = 0` is `-4.434e-4 × 101325 = -44.93 Pa`, so there is no forward root, and the only root
is at a negative flow the generator forbids.

The Newton direction from a dense one-sided Jacobian built with the solver's own `differenceScale`:

```
||d||inf = 6.611237495100e-01 at column 5 (edge0.flow)
  d[1] n1.waterVapor = -1.974032496e-08
  d[5] edge0.flow    = -6.611237495e-01
linear prediction ||f+Jd||inf = 1.265784860553145E-19
```

The direction is essentially exact and it goes straight to the reverse root — which is the *right*
answer, because a converged reverse flow is what makes `illegalDirection` fire and close the
boundary. And then:

```
-- maximumStep --
  alpha cap = 5.015115009636e-17
  binding column 1 (n1.waterVapor): x=1.000000e-24  d=-1.974032e-08  clampFloor=0.000e+00
     -> .99*x/-d = 5.015115e-17
  every amount column that could bind:
      0 n1.vapor[19]   x=+1.000000e+00  d=-1.084202e-19  cap=9.131138e+18
      1 n1.waterVapor  x=+1.000000e-24  d=-1.974032e-08  cap=5.015115e-17
-- residual along the direction --
  alpha=5.0151e-17  ||f||inf=5.066656785680e-04   flow=+1.405773e-01
```

The residual does not move in the twelfth digit. The line search has exactly one trial available to
it and it is bitwise the point it started from, so it reports a stall.

The same dump for the two flat controls:

| case | `alpha` cap | binding column | `||f+Jd||inf` | residual at the cap |
| --- | --- | --- | --- | --- |
| flat 101326 (1 Pa) | `3.980378382578e-18` | `n1.waterVapor` `x=1e-24`, `clampFloor=0` | `9.105604182983729E-21` | `1.935502403543e-05`, unchanged |
| flat 140395 (39 kPa) | `1.616038630655e-08` | `n1.waterVapor` `x=1e-12`, `clampFloor=0` | `5.551115082229167E-17` | `9.998839163404e-01`, i.e. *worse* |
| rising 101325 | `5.015115009636e-17` | `n1.waterVapor` `x=1e-24`, `clampFloor=0` | `1.265784860553145E-19` | `5.066656785680e-04`, unchanged |

### 2.2 Which of the three ingredients binds

The brief named three candidates. The dump answers all three.

* **The phantom trace's limiter cap — this is the one that binds, in all seven shapes.** It is the
  proximate cause of every stall: `alpha` is capped at `1.6e-8` to `4.0e-18` by a `waterVapor`
  unknown whose `clampFloor` is zero, and the residual at that `alpha` is bitwise what it was.
* **The reverse-branch friction slope — real, but secondary.** It shows in the 140395 case, where
  the reverse branch is not the friction law at all but the *velocity clamp*: the row reads exactly
  `-1.000000000000e+00` at `q = -1e-16` against `-0.27828626` at `q = 0`, because
  `|driving| > limitDrop` on the nitrogen side and the throttled-inlet law takes over as
  `(-limit - flow)/limit`. That is a second degree-zero jump on the same row, and it is why that
  case's direction is `-2238` rather than `-0.66`. It would force the line search through about
  fifteen backtracks; it would not by itself have stopped it.
* **The missing boundary closure — the model answer, unreachable.** `boundaryClosed` is only ever
  set from a *converged* point showing an illegal direction, and the pass cannot converge, so the
  closure the answer needs is never taken.

And one measurement that settles candidate (c) outright, taken by re-walking the same direction with
the limiter ignored:

```
-- the same search with the limiter ignored (what removing the trace buys) --
  alpha=1.0000e+00  <Invalid water split>
  alpha=5.0000e-01  <Invalid water split>
  ... every halving down to 1.4552e-11 ...
```

`FluidThermodynamics` line 203 rejects a negative water amount at construction. So **no clamp floor
for a fluid amount can work on its own**: raising the limiter's floor past the trace only moves the
failure from `Newton line search stalled` to `Fluid state outside domain`. The trace has to not be
there, or the journey has to not be needed. That is why option (c) is not implemented here; see
section 10 for its relation to the recorded todo.

---

## 3. The rules

Two, one commit each, in `PassiveStepSolver`.

### Rule B — `closeDeadHeads`: the closure decided from the pass's start point

A connection's hydraulic row is `driving - loss(q)`, and `loss` is zero at zero flow and strictly
increasing away from it. So:

* a root with `q > 0` exists **only** when the driving pressure a forward flow stands in is positive;
* a root with `q < 0` exists **only** when the driving pressure a reverse flow stands in is negative.

A connection one of whose ends forbids a direction — a generator, a void, a directionally blocked
pipe — therefore has **no admissible root at all** when the only sign with a root is the forbidden
one, and its one admissible answer is a flow of exactly zero. That is the answer `boundaryClosed`
already produces. It is now taken before the pass rather than after a convergence that cannot
happen, exactly as `SolidEventIntegrator`'s pre-interval rate pass closes a transport failure rather
than letting the interval discover it.

```java
boolean forward=forwardAllowed&&difference-a.state().mass()/a.state().volume()*GRAVITY*dz>0;
boolean reverse=reverseAllowed&&difference-b.state().mass()/b.state().volume()*GRAVITY*dz<0;
if(!forward&&!reverse)for(int link:chain)boundaryClosed[link]=true;
```

Three conditions keep it a statement that cannot be wrong for the solve it is made in.

* **Each direction is tested against the column that would fill it.** A forward flow stands in the
  fluid of `first` and a reverse flow in that of `second` — the same pairing F7's `headDensities`
  states a connection's head on. A direction is refused only when the column that would fill it does
  not drive it. On a flat island `dz` is zero and both tests read the same pressure difference, so
  the column never enters.
* **Neither end of the run is a zero-holdup junction.** This is the load-bearing one. A boundary
  holds its pressure fixed by construction; a vessel's pressure moves only with what it receives,
  and *monotonically against the flow that delivers it* — pushing water into a nitrogen tank raises
  its pressure, which makes an already-adverse driving pressure more adverse. So across a
  boundary/vessel pair, a driving pressure that refuses a direction at the starting point refuses it
  everywhere the solve can go. A junction owns no volume, its pressure is an output of the previous
  solve carrying that solve's roundoff, and it is free to move as far as the hydraulics need, so it
  is not evidence about anything.
* **Only passive connections.** R1's device-first rule exists precisely so that a boundary direction
  is never closed on evidence that is really a device's symptom — "a pump held above its shutoff
  head pushes its whole suction line backwards, and the generator edge feeding it is the first thing
  that shows it." A pre-pass closure of an actuator's connection would take that decision before the
  device had spoken at all, so actuator connections are excluded, and so is any run through a
  junction an actuator touches.

**The run, not the connection.** The statement is made across the maximal passive chain through
degree-two junctions that `initialMassFlows` already contracts — "one resistance between the two
nodes at its ends, which do own their pressures." Those junctions carry the one flow the chain
passes and own no pressure, and a static column is path-independent, so the column across the chain
is the column across its two ends and nothing in between enters it. A run of one connection is the
ordinary case and reduces to the single-edge test exactly. This is what reaches the same line with
an inline filter in it, whose two junctions stand between the generator and the tank; without it
those two shapes stay held (measured, section 4).

### Rule A — the species set is read off the connections this pass leaves open

`reachableComponents` computed the undirected closure over every connection `boundaryAllowed`
permits, ignoring `boundaryClosed`. So behind a connection the pass has pinned to a flow of exactly
zero, a vessel was still seeded with an entry trace of what that connection alone could have
brought — a component with no donor, whose balance row is `(amount - 0)/scale` and whose only root
is therefore exactly zero, sitting on a nonnegativity boundary the limiter must respect.

The fix is to state the species set **after** the closure and hand the closure to it: `reachable`
and `seeds` move below `closeDeadHeads`, and `reachableComponents` skips a closed connection. This
is the same statement `refineJunctionReachability` makes for a junction's basis — a junction carries
what the connections this pass reads as donating into it deliver, and nothing else — asked of a
vessel's entry trace.

**Why the closure and not the flows.** A rule stated from the *sign of the pass's starting flows*
was implemented and measured, and it is wrong. On the flat line 1 Pa adverse, the start estimate is
a negative flow the generator forbids, so "nothing delivers water" — but the solve then converges to
a *forward* flow once the tank has drifted below the generator, the water has no balance row to
arrive into, and the statement is contradicted by the pass's own converged point. Measured: it fixed
the elevated shape outright (40/40, 0 Newton failures) and turned the flat 1 Pa case from a hold at
interval 1 into **507 accepted substeps advancing 1.57 s of a 5 s interval and then holding anyway**,
at a new residual `1.8823487595517047E-6` on a point where the tank had lost 2.2 Pa it should not
have. That candidate is rejected. A *closed* connection cannot be contradicted: its flow is pinned
to zero by a row of its own, for the whole solve, and `boundaryClosed` is never cleared within a
solve.

### Why they cannot recur

Both are the discipline the rest of this solver already follows, applied to the last two things that
were not following it: **a decision that changes which equations a pass states is a decision of the
pass, taken from the point the pass starts at, and revised only by a converged point.** Device
modes, phase regimes, trace support, the frozen junction donor, the junction basis and the static
head each already work that way. The boundary closure was taken from a converged point only — which
is correct when one exists, and is exactly the case that does not. The species set was taken from a
closure that did not know what the pass had already decided.

### Why they cannot cycle

* **With the active-set loop.** `closeDeadHeads` runs once, before any pass, and writes only into
  `boundaryClosed` — the same array the pass loop's own closure writes, read by the same code. The
  closure is monotone within a solve and never cleared, so the rule adds **no transition** to the
  active-set sequence; it can only remove one (the pass that would have discovered the same closure
  the slow way). The cycle key `seen` already contains `boundaryClosed`, so nothing about the guard
  changes.
* **With the phase-correction loop.** `phaseCorrection` replaces seeds with flashed states and
  touches neither `boundaryClosed` nor any flow, so it cannot move either statement. Likewise
  `reactivate`.
* **With the frozen-donor and junction-basis loops.** Both are stated from `startPoint`, which skips
  a closed connection and leaves its flow at zero. A closed connection therefore reads as carrying
  nothing to `donorsTurned`, `junctionsTurned`, `refineJunctionReachability` and `restateJunctions`
  alike, consistently, for the whole solve. Rule A is the same reading of the same fact, made once
  at the top instead of per pass.
* **With the static-head loop.** F7's `headDensities` is computed per pass from the seeds and is
  deliberately outside the workspace key. Rule B reads the *accepted states* rather than the seeds,
  so it does not depend on rule A's output at all, and rule A does not depend on `headDensities`.
  There is no path from one to the other.
* **Across solves.** Both rules are per solve. A line whose generator is raised afterwards is
  decided again from the new starting point and opens — fixture
  `raisingTheGeneratorReopensADeadHeadedLineAndFillsTheTank`, and the in-game pass of section 9.

---

## 4. The ablation

Seven shapes, 40 × 5 s intervals each, one retained solver. "OK 40/40 (0)" is forty intervals with
zero Newton failures anywhere on the way.

| variant | flat 1 Pa | flat 8675 Pa | flat 39070 Pa | flat 98675 Pa | rising dead-head | flat filter | rising filter |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **baseline `ec9ea74`** | HELD `1.9355024035429073E-5` (19) | HELD `0.0788636363636365` (24) | HELD `0.27828626375583176` (23) | HELD `0.493375` (23) | HELD `5.066656785680298E-4` (21) | HELD `0.29469706587678757` (1) | HELD `Empty fluid initialization` (0) |
| **A alone** (shipped form) | *bit-identical to baseline, every digit* | same | same | same | same | same | same |
| **A′ alone** (rejected: trace gated on the start flows' own signs) | HELD `1.8823487595517047E-6`, 507 accepted / 517 rejected | not measured | HELD `0.24515394134138208`, 510/514 | not measured | **OK 40/40 (0)** | not measured | not measured |
| **B alone, single connection** | OK 40/40 (0) | OK 40/40 (0) | OK 40/40 (0) | OK 40/40 (0) | OK 40/40 (0) | HELD `0.29469706587678757` | HELD `Empty fluid initialization` |
| **B alone, contracted run** | OK 40/40 (0) | OK 40/40 (0) | OK 40/40 (0) | OK 40/40 (0) | OK 40/40 (0) | OK 40/40 (0) | OK 40/40 (0) |
| **A + B (shipped)** | OK 40/40 (0) | OK 40/40 (0) | OK 40/40 (0) | OK 40/40 (0) | OK 40/40 (0) | OK 40/40 (0) | OK 40/40 (0) |

Read it this way.

* **B is what fixes the defect.** Alone it takes every plain shape from held to forty intervals with
  no Newton failure at all, which is the strongest statement available: the pass never has to search
  for anything.
* **The contraction is what reaches the filter shapes.** Two of the seven are the difference between
  the single-connection form and the run form, and they are the difference between a player who put
  a filter in the line being bricked and not.
* **A alone is the identity.** With `closeDeadHeads` disabled, all seven shapes stall at exactly the
  baseline residuals. That is by construction: a pipe blocked in both directions was already
  excluded from the closure by `boundaryAllowed`, so the only connections A removes are the ones B
  has just taken. A therefore carries no risk of its own and cannot be the thing that moves a gate.
  What it buys is that the phantom trace is **gone** rather than routed around: a dead-headed tank
  stops being re-flashed with an unknown and a row it has no business owning.
* **A′ is recorded because it is the obvious rule and it is wrong.** The reason is in section 3, and
  it is the reason the shipped A keys on the closure instead: a statement a converged point can
  contradict is not a statement the pass may make.
* **Measured side effect, in the right direction.** On `ElevatedBlockLineIslandTest`'s
  `elevatedLinesIntegrateFortyIntervalsLikeTheirFlatControl`, the rising line *through a filter*
  moved from `360918.6884` Pa to `360918.3479` Pa against a predicted `360918.3068` — error `0.382`
  Pa down to `0.041` Pa, because the line now stops at its own hydrostatic balance instead of
  creeping past it. The plain rising (`360918.47228805406`), falling (`439081.8102046798`), flat
  control (`400000.0056142352`), pumped (`860894.6708657928`) and falling-at-101.325 kPa
  (`140395.02214833171`) endpoints are unchanged to the digits that report recorded; the balanced
  line moved by `7.1e-9` Pa.

---

## 5. Qualification

**The exact gate at zero, with the reference untouched.**

```
chain-100 vs reference: max deviation: state/moles 0.000e+00 relative (-), temperature 0.000e+00 K (-),
                                       phase fraction 0.000e+00 (-), flow 0.000e+00 relative (-)
BUILD SUCCESSFUL
```

`src/test/resources/fluid/regression/chain-100.json` is **untouched** — `git log -1` on it still
names `3c84036`, the F3 re-capture, and `git status` reports no change to it. No re-capture was
needed and the F3 relative-gate discipline does not apply, because the change is bit-identical for
every island in the replayed set. The bit-identity is structural rather than lucky:

* `chain-100` has every component present at every node, so `initialPhaseSeeds` plants no entry
  trace and rule A cannot reach it;
* its connections have no generator, no void and no blocked direction, so `boundaryAllowed` permits
  both signs on every one of them, `forwardAllowed && reverseAllowed` is true, and `closeDeadHeads`
  `continue`s before it computes anything.

The other three reference islands (`quiet-11312`, `quiet-11324`, `cold-11312`) SKIP in this
worktree, as they did for F3, F4, the pump track and the static-head track — there is no readable
`core.dat` here. That is a real limitation of this evidence and it is the same limitation every
recent change on this track has carried. `chain-100` is the whole of the replayed set.

`fluidNetworkBenchmark`: **30/9, 19/14, 37/3 — unchanged**, all three CONVERGED. No re-recording
needed.

---

## 6. Verification

Sequentially, one Gradle invocation at a time, `tasklist | grep -i java` checked before each. No
suite ran while the dev client was up.

| Check | Result |
| --- | --- |
| `fluidScienceTest` | **161 tests, 0 skipped, 0 failures** |
| `fluidRuntimeTest` | **129 tests, 0 skipped, 0 failures** — 125 before, plus the four new `DeadHeadedLineIslandTest` fixtures |
| `fluidSolverRegression -PfluidRegressionMode=exact` | PASS at `0.000e+00` on all four gates of `chain-100`, reference untouched; three islands SKIP |
| `fluidNetworkBenchmark` | **30/9, 19/14, 37/3 — unchanged**, all CONVERGED |
| `runFluidGameTestServer -PfluidGameTestRunId=dead-head-01` | **All 20 required tests passed** |
| the dead-head elevated line, 40/40 at zero flow, tank unchanged, generator closed | PASS — `aDeadHeadedElevatedLineRestsInsteadOfHolding` |
| the four flat charged-tank lines, same | PASS — `aTankChargedAboveItsGeneratorRestsInsteadOfHolding` |
| both filter shapes, same | PASS — `aDeadHeadedFilterLineRestsInsteadOfHolding` |
| raising the generator re-opens the line and fills the tank | PASS — `raisingTheGeneratorReopensADeadHeadedLineAndFillsTheTank`, tank at `360918.4722880688` Pa, `715.34` kg |
| putting the generator back to 101.325 kPa leaves it at rest | PASS — same fixture, third stage |
| seven-pressure filter sweep `filterLineToATankKeepsIntegratingOnceTheTankIsFull` | PASS |
| valve fixture `valveLineToATankKeepsIntegratingOnceTheTankIsFull` | PASS |
| pumped fixture `aFilterBlockCostsAPumpedLineNoIntervalOfItsOwn` | PASS |
| full-tank solids fixtures (`FullTankSolidsEventTest`, `SolidRuntimeTest`) | PASS |
| elevated fixtures (`ElevatedBlockLineIslandTest`, all four) | PASS — and `aDeadHeadedElevatedLineBehavesLikeItsFlatEquivalent` now reports **all four shapes OK 40/40** where it reported all four HELD |

Two honesty notes about the per-commit numbers in the commit messages. First, while the temporary
probe existed `fluidScienceTest` reported **162**, not 161 — the extra one is the probe itself, and
the 161 in the table above is the final tree with the probe deleted. Second, the intermediate
states were each measured at the tree the commit contains, but the numbers were gathered
continuously rather than re-measured from a clean checkout of each commit in turn; the full table
above *is* a clean run of the final tree, with `--rerun-tasks` on both suites.

### What the dead-headed fixture actually asserts

Not "it did not throw". For each of the seven shapes, after forty intervals:

* every connection's accepted mode is `CLOSED`;
* every average mass flow is **exactly** `0` (`assertEquals(0, flow, 0)`);
* the tank's conserved inventory is what it went in as, to `1e-12` relative per component — measured
  drift is **one unit in the last place** of its nitrogen over the forty intervals — and its energy
  likewise;
* its pressure has not moved by more than `1e-9` relative, measured at about a nanopascal.

Those last bits are a state re-decoded from an unchanged inventory each substep, not a transfer. The
flows being bitwise zero is the statement that nothing moved.

---

## 7. Timing

`SolidRuntimeTest.physicalPumpFilterAndNitrogenReceiverStartTogether`, same session, one Gradle
invocation at a time, nothing else running, `--rerun-tasks` each time:

| | s1 | s2 | s3 | s4 | s5 | s6 | median |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `ec9ea74` (baseline solver in place) | 0.312 | 0.319 | 0.315 | — | — | — | 0.315 |
| this branch | 0.308 | 0.314 | 0.378 | 0.317 | 0.329 | 0.310 | 0.316 |

Within the noise of the measurement, which is what the exact gate already implies for this island:
it has no dead-headed connection, so `closeDeadHeads` walks its connection list once and closes
nothing, and rule A is handed an all-false closure. The `0.378` sample is an outlier; three more
were taken because of it and the median did not move.

---

## 8. In-game confirmation

The MCP bridge jar from `solid-phase-fluid-system-plan-4369b0` in `run/mods`, `pauseOnLostFocus:false`
in `run/options.txt`, `./gradlew.bat runClient --offline` started only after every suite, the
regression, the benchmark, the GameTest server and the timing samples had finished. Screenshots in
`documentation/screenshots/dead-headed-line/`, all flattened with `Flatten`.

### The line

A fresh superflat creative world with commands on (`02-create-world.png` … `08-superflat.png`). The
**bricking scenario exactly**, placed with `/setblock` in that order and nothing else:

```
/setblock 0 -59 0 createcheme:fluid_generator
/setblock 0 -58 0 createcheme:fluid_pipe
/setblock 0 -57 0 createcheme:fluid_pipe
/setblock 0 -56 0 createcheme:fluid_pipe
/setblock 0 -55 0 createcheme:fluid_reservoir
```

Every device at the world's own defaults, a four-block column between the generator and the tank
(`14-line-full.png`). This is the build that produced

```
HELD: Substep refinement exhausted: Newton line search stalled at residual 5.066656016570093E-4;
      active-set pass=0
```

on the previous branch, whose first log line matched the unit fixture's `5.066656785680298E-4` to
every digit.

### It is not held

The generator's own screen, on the line as placed (`15-generator-screen.png`):

```
Fluid Generator            FULL
Pressure     101.33 kPa abs
Temperature  298.15 K
Flow (last)  0.00 kg/s
Source: no depletion
Lag  0.50 s
```

`FULL`, not `HELD`, and **`Flow (last) 0.00 kg/s`** — which is the closure's own answer, the same
exact zero the fixture asserts. The tank (`21-tank-dead-head.png`):

```
Fluid Reservoir            FULL
Pressure     101.33 kPa abs
Temperature  298.15 K
Volume       1000.00 L
Mass         1.15 kg
Lag  0.45 s
L 0  W 0  V 100  S 0 %
```

1.15 kg of pure nitrogen at 100 % vapour — the charge it was placed with, untouched, which is the
in-world form of the fixture's inventory assertion.

### The setting is accepted, the line fills, and it can be put back

Typing `400000` into the generator's pressure field and pressing Apply (`22-generator-400-typed.png`,
`23-generator-applied.png`):

```
Settings accepted at the current simulation event.
Pressure  400.00 kPa abs      Flow (last)  32.83 kg/s      FULL
```

That is the sentence the held island never printed: on the previous branch the same action produced
`WAITING: configuration event / HELD: …` and the setting never took. Here it takes at once and the
line starts carrying 32.83 kg/s.

After it fills and settles (`24-tank-filling.png`):

```
Fluid Reservoir            FULL
Pressure     360.92 kPa abs
Temperature  298.23 K
Volume       1000.00 L
Mass         715.34 kg
Lag  0.45 s
L 0  W 72  V 28  S 0 %
```

| quantity | in-game | `DeadHeadedLineIslandTest` | predicted |
| --- | --- | --- | --- |
| tank pressure | 360.92 kPa | 360918.4722880688 Pa | 360918.3068 Pa = 400 kPa − 39081.69 |
| tank mass | 715.34 kg | 715.340167017609 kg | — |
| generator | 400.00 kPa | 400000 Pa | — |

Putting the generator back to `101325` (`25-generator-lowered.png`) also prints
`Settings accepted at the current simulation event`, and leaves

```
Pressure  101.33 kPa abs      Flow (last)  0.00 kg/s      FULL      Lag 0.90 s
```

with the tank still at `360.92 kPa / 715.34 kg / FULL`, lag 0.20 s (`28-tank-at-rest.png`) — at
rest, not held, and not draining, because a generator cannot take fluid back and the closure says so
before the pass rather than after one that cannot converge. That is the fixture's third stage,
in the world.

### Lag and log

Status is `FULL` throughout, never `HELD`. Every lag reading of the session, in order, from both
screens: **0.50, 1.25, 2.35, 0.40, 0.45, 0.35, 0.45, 0.90, 0.95, 0.95, 0.20, 0.80 s**
(`15`, `16`, `17`, `18`, `21`, `23`, `24`, `25`, `26-lag-a.png`, `27-lag-b.png`, `28`,
`29-lag-c.png`) — bounded and oscillating well inside the five-second cadence, against the held
island's monotone 79.70 → 118.70 → 146.70 → 200.85 s that `ELEVATED_LINE_PROBE.md` section 9
recorded.

```
$ grep -c "status=HELD" run/logs/latest.log     0
$ grep -c "fluid_island"  run/logs/latest.log   0
$ grep -c "HELD"          run/logs/latest.log   0
$ grep -icE "nonconverg|stalled|refinement exhausted|active-set cycle" run/logs/latest.log   0
```

Zero on all four, over a 231-line log covering the whole session. `fluid_island=` lines are emitted
only for a non-nominal status, so none at all is the stronger statement.

### Shutdown

Quit through `Save and Quit to Title` and then `Quit Game`; the `runClient` task exited 0.
`Get-CimInstance Win32_Process` for a `java.exe` whose command line names Minecraft reports **no
Minecraft java process remains**. The eight java processes left on the host are idle Gradle daemons.

### What was *not* confirmed in the world

* **The filter shapes.** The in-game pass built the brief's scenario only. Both filter shapes are
  covered by `aDeadHeadedFilterLineRestsInsteadOfHolding` and by the probe, not by the client.
* **A save/restart round-trip of the dead-headed line.** The world was saved and the client quit,
  but it was not reloaded to re-read the tank. The fixture runs forty intervals on one retained
  solver, which is the same continuity claim, and the previous branch's pass did confirm the
  round-trip for a *filled* line. This one is untested in the world.
* **The flat charged-tank shapes.** There is no in-game way to charge a tank above its generator
  without the generator having been at that pressure first, so the flat shapes are the unit
  fixture's business; the elevated shape is the one a player builds by accident.

---

## 9. What I did not do, and why

* **Option (c), a clamp floor for fluid amounts, is not implemented.** Section 2.2 measures why it
  cannot work alone: `FluidThermodynamics` rejects a negative water amount at construction, so a
  floor that lets the limiter past the trace only moves the failure to `Fluid state outside domain`.
  With (a) and (b) in place there is no residual problem for it to solve — every shape converges
  with zero Newton failures — so the brief's own gate for it is not met. Its relation to the user's
  recorded todo **"reject-to-source for trace amounts"** is this: that todo is the general form of
  the same observation, that an amount whose only root is zero should be recognised as such rather
  than chased; the rules here reach the same end for one specific cause (a connection that delivers
  nothing) by never creating the unknown, which is cheaper and carries no threshold. A general
  reject-to-source rule would additionally cover traces whose donor exists but delivers below the
  truncation cutoff, which these rules do not touch. It is **not implemented** here.
* **The island event/fence contract is untouched.** That a held island takes no configuration or
  topology event, survives block removal, a save and a client restart is reserved for the user by
  the brief. It is also the reason this defect was a brick rather than a stutter, and it remains
  true of any island that does still hold.
* **The velocity clamp's donor is still live.** Section 2.2 measures it directly for the first time:
  on the flat 140395 case the row jumps from `-0.27828626` to exactly `-1.0` across zero flow,
  because `capMassFlows`/`capPressureDrops` are indexed by the sign of the flow and the throttled-
  inlet law takes over on the nitrogen side. `ELEVATED_LINE_PROBE.md` section 10 flagged this and it
  is still true: it is a second degree-zero switch on the same row. It no longer bites on a
  dead-headed line, because the closure means no pass ever crosses zero there, but it is not fixed
  and it reaches into `VelocityClampTest` and the benchmark.
* **Three of the four regression islands SKIP.** No readable `core.dat` exists in this worktree, as
  for every recent change on this track. `chain-100` is the whole of the replayed set.
* **No dead-headed pumped or valved shape.** Rule B deliberately excludes actuator connections to
  keep R1's device-first rule intact, so a pump or a valve dead-heading itself is decided by the
  device machinery as before and is not covered here. The pumped fixtures still pass; whether a
  pumped line can dead-head in the same way was not probed.
* **The dead-headed lines are not in the GameTest set.** The GameTests place their devices at one y
  and none of them charges a tank above its generator; the in-game pass of section 8 is the
  confirmation, done by hand in the dev client.
* **One elevation only.** Every elevated shape uses four blocks, as the brief's scenario does.
  Nothing in either rule has a length scale in it — rule B's test is a sign — so a sweep over `dz`
  would have measured the same sign at a different size.
* **A settled line now presents as CLOSED, and I did not change that.** A tank standing at its own
  hydrostatic balance under its generator is dead-headed in exactly the same sense as one charged
  above it, so rule B closes it too and its connection's accepted mode becomes `CLOSED` rather than
  `PASSIVE`. That is a presentation change for every filled tank in the game. It is correct — the
  connection carries exactly zero — and no fixture in the tree asserts the mode, but it is a
  user-visible difference and it is worth a second opinion rather than my say-so. The in-game screens
  show `FULL` and `Flow (last) 0.00 kg/s`, which is what a player reads, and neither changed.
* **I did not measure whether the closure costs anything on a large island.** `closeDeadHeads` walks
  the connection list once per solve and contracts chains; on `chain-100` (99 connections, no
  boundary among them) it exits each connection at the `forwardAllowed && reverseAllowed` test. The
  benchmark's substep counts are unchanged and `chain-100` is bit-identical, but I took no wall-clock
  profile of the walk itself.

---

## 10. Commits

| sha | |
| --- | --- |
| `b0590a4` | Close, before the pass, a connection whose boundary refuses its only root |
| `88dda15` | Read a vessel's species off the connections this pass leaves open |
| `1403eeb` | Hold the dead-headed lines to a flow of exactly zero |

Base `ec9ea74`, branch `claude/dead-headed-line`. Every commit ends with
`Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. `documentation/` is gitignored in this
tree, so this report and its screenshots are in the worktree but not in any commit. `git diff
ec9ea74..HEAD --stat` names exactly two files: `PassiveStepSolver.java` and the new
`DeadHeadedLineIslandTest.java`.

### Screenshots

`documentation/screenshots/dead-headed-line/`, all flattened with `Flatten`:

| file | |
| --- | --- |
| `01-title.png` … `08-superflat.png` | world setup: creative, commands on, superflat |
| `09-in-world.png`, `10-site.png` | the empty build site |
| `11-line-placed.png` … `14-line-full.png` | the bricking scenario placed with `/setblock`, generator y=-59 to tank y=-55 |
| **`15-generator-screen.png`** | **the line that used to brick, reading `FULL` / `Flow (last) 0.00 kg/s` / lag 0.50 s** |
| `16`–`20` | working shots while aiming at the tank (the bridge's `right_click` has no raycast of its own) |
| **`21-tank-dead-head.png`** | **the tank untouched: 101.33 kPa, 1.15 kg, V 100 %** |
| `22-generator-400-typed.png` | `400000` typed into the pressure field |
| **`23-generator-applied.png`** | **`Settings accepted at the current simulation event`, 400.00 kPa, 32.83 kg/s** |
| **`24-tank-filling.png`** | **the tank filled and settled at 360.92 kPa / 715.34 kg** |
| `25-generator-lowered.png` | back to 101.33 kPa, accepted again, flow 0.00 kg/s |
| `26-lag-a.png`, `27-lag-b.png`, `29-lag-c.png` | lag 0.95 / 0.95 / 0.80 s, bounded |
| `28-tank-at-rest.png` | the tank holding 360.92 kPa under a lowered generator, at rest |
| `30-pause.png` | the pause menu before `Save and Quit to Title` |
