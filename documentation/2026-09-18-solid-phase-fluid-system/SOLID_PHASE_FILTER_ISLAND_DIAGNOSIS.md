# Solid-phase filter islands: why every placed filter was HELD

Worktree `.claude/worktrees/agent-a0fba21980f2fa09a`, branch `claude/solid-phase-filter-fix`,
based on `claude/solid-phase-gui-regression` (`fca6a15`).

## 1. Setup verification

| Check | Result |
| --- | --- |
| `git checkout -b claude/solid-phase-filter-fix claude/solid-phase-gui-regression` | done |
| `git log --oneline -3` at the branch point | `fca6a15 Build the solver regression chain…`, `bfb22ef Repair three small defects…`, `a399100 Close the feed the population limit…` — as expected |
| No other checkout or worktree touched | confirmed; every command ran from this worktree, the Codex worktree and the agent worktrees were read only |
| Bare `git stash` / `git stash pop` | never used; history was reshaped with `git reset --mixed` inside this worktree only |
| One Gradle invocation at a time | held throughout; `Get-Process java` sampled before the first run (four idle daemons, ~0.03 CPU-seconds per 6 s wall). Two `fluidRuntimeTest` runs overlapped a live dev client — see §10 |
| Commit trailer | every commit ends `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>` |

Commits produced (oldest first):

```
782e6ab Decide a tight Newton tolerance from nodes that hold an inventory
a152b0a Mix in the reconstruction exactly the junctions the equations mixed
287c862 Solve the block lines a player builds around an inline filter
90f16c7 Cover the driven filter line, and record what still stops it
```

**Read §5b before concluding that filter islands are fixed.** Two defects are fixed and verified:
an at-rest filter line to a tank no longer holds, and a filter line carrying solids with no driving
pressure no longer holds. A third, F3, is diagnosed but **not** fixed: a filter line feeding a tank
above roughly 150 kPa still stops. It is pre-existing — present at `fca6a15`, where F1 simply
reached it first — and fixing it means rescaling a row every island in the tree shares. §6 records
a fourth, older defect that is not about filters at all.

## 2. Reproduction without the client

`src/test/java/com/wormzjl/createcheme/runtime/fluid/FilterBlockLineIslandTest.java`
(`fluidRuntimeTest`) builds each recorded block line through the real
`PhysicalFluidTopology.compile(devices, boundaryStates, filterStock, idleSeed)`, with the world's
own defaults taken from `FluidWorldAuthority.place` and `SolidTransportSettings.defaults`:

* device geometry `PipeResistance.Geometry(1, .05, .000045, 0)`, pump `FlowControl.Pump(.01, 500000, 1)`;
* generator `FluidDeviceSpec(1 m³, 298.15 K, P, water[, SlurryFeed])`, reservoir and void
  nitrogen-charged at 1 m³ / 298.15 K / 101325 Pa;
* filter stock `InlineFilter(capacity 0.01 m³, clean resistance 1e6)`;
* every block at `y = -59`, in a straight line, filter facing east.

It then runs the island the way a worker does: consecutive 5 s intervals on one retained
`PassiveIntervalSolver`, starting from `RetainedSolver.COLD_START_SECONDS` (0.05 s) and carrying
`nextStepEstimate()` forward. **That last point is what every existing fixture was missing** — the
GameTest and `SolidRuntimeTest.physicalPumpFilterAndNitrogenReceiverStartTogether` do loop
intervals, but from the 1 s default step and with a slurry generator, and the single-interval
fixtures never reach the settled state at all.

What the compiler actually produces for `generator → pipe → filter → pipe → reservoir`
(asserted by `aFilterBlockCompilesToTwoZeroHoldupJunctionsAroundItsOwnEdge`):

```
node[0] id=1   GENERATOR  liquid water 1.0 m³, 101325 Pa
node[1] id=3   JUNCTION   seeded from the generator: liquid-full
node[2] id=5   RESERVOIR  nitrogen 1.0 m³, 101325 Pa
node[3] id=-3  JUNCTION   seeded from the generator: liquid-full
pipe 8261561421756122062  0->1  passive
pipe 3932769516861099846  3->2  passive
pipe -9223372036854775805 1->3  passive, filter          (= Long.MIN_VALUE + 3)
```

Note the inlet junction keeps the filter's **own positive** physical id (3); only the outlet
junction is compiler-minted and negative. The `SolidEventIntegrator.solve` pre-interval guard
`a.id()<0 && b.id()<0 && a.kind()==GENERATOR && b.kind()==VOID` therefore never fires on a
connected filter — it only matches the dormant disconnected-filter island, which is what it is for.
It is not implicated.

### Reproduction table (all at `fca6a15`, before any fix)

| Shape | Live world | This reproduction |
| --- | --- | --- |
| (i) generator → pipe → filter → pipe → reservoir, clear, at rest | HELD: `Newton line search stalled at residual 1.0470535549152183E-10; active-set pass=1` | **HELD at interval 8/40 (t = 35 s)**: `Substep refinement exhausted: Newton line search stalled at residual 1.0276868244756662E-10; active-set pass=1` |
| (ii) generator → pump → pipe → filter → pipe → reservoir, clear, at rest | HELD: `…stalled at residual 1232666.6046244698; active-set pass=1` | **HELD at interval 19/40 (t = 90 s)**: `Interval substep limit; no partial interval may commit: advanced=1.3159… of 5.0 s, accepted=510, rejected=514, reasons={Phase/device active-set cycle=12, Newton iteration limit …=502}` |
| (iii) generator 5 % / 100 µm → pipe → filter → pipe → void, no driving pressure | HELD: `Conservative reconstruction fails equation gate: 0.05834390392013748` | **HELD at interval 1/1 (t = 0)**: `Conservative reconstruction fails equation gate: 0.11669069400247264` |
| (iv) generator 20 % / 100 µm at 300 kPa → filter → void | HELD: `…residual 16435.926921102466` | OK (not reproduced; see §7) |
| (v) generator 5 % / 100 µm at 150 kPa → filter → void | HELD: `…residual 5531.127013914242` | OK (not reproduced; see §7) |
| control: generator → 4 pipes → reservoir, at rest and 5 % at 300 kPa | healthy all session | OK, 40/40 intervals, 1 accepted substep per interval |

(i) and (iii) reproduce the recorded message essentially exactly — (i) to two significant figures of
the same residual, (iii) as the same gate with a different numeric value because my generator is 1 m³
of pure water rather than whatever the live generator held. (ii) reproduces as a hold of the same
island at the same stage of filling, with a different terminal message; §6 shows why the live number
is not reachable from this fixture and why it does not matter.

## 3. Bisect

One Gradle run per point, carrying the reproduction file in (untracked files survive `git checkout`).

| Commit | (i) tank filter line | (ii) pumped filter line | (iii) solids, no pressure | pump control, **no filter** |
| --- | --- | --- | --- | --- |
| `3c27271` (merge base, **no solid phase at all**) | n/a (no FILTER kind) | n/a | n/a | **HELD 17/40**, `Newton iteration limit at residual 2.6322406197546577E-6` |
| `440a754` Codex implementation | HELD 13/40, `Fluid hydrocarbon state outside model domain` | OK but 880 accepted / 440 rejected, all `Phase/device active-set cycle` | **HELD**, gate `0.11669069400247264` | HELD 17/40, byte-identical message |
| `a4db1fe` fixes A–D | HELD 13/40, same | same | HELD, same gate | HELD 17/40, same |
| `e97fe5b` fixes E–H | **HELD 8/40**, `Newton line search stalled at residual 1.0276868244756662E-10` | **HELD 19/40** | HELD, same gate | HELD 17/40, same |
| `bfb22ef` GUI pass | identical to `e97fe5b` | identical | identical | identical |

Reading:

* **(iii) is Codex-original.** The gate failure is present and numerically identical at `440a754`
  and unchanged by every later fix.
* **(i) is Codex-original in substance**, but its *symptom* was changed by the E–H series
  (`3e949c4`…`e97fe5b`). Before them the island died at interval 13 inside the property domain;
  after them it dies at interval 8 with the line-search stall the live world reported. No fix
  introduced the hold; they moved where a permanently unsolvable island gives up.
* **(ii) is not a filter defect at all.** The same line with the filter block removed holds at the
  same interval with the same message, and does so at `3c27271` — before a single line of
  solid-phase code existed. See §6.

## 4. Root cause

### F1 — the tight Newton tolerance, set by nodes that own nothing

`PassiveStepSolver.solve` chose its Newton tolerance with

```java
boolean smallHeadspace = seeds.stream().anyMatch(s -> s.vaporVolume()/s.volume() < .01);
double tolerance = acceptance==APPROXIMATE ? 1e-6 : smallHeadspace ? 1e-10 : 1e-9;
```

The predicate looked at **every** seed state regardless of what kind of node held it.

A placed filter block compiles to two `JUNCTION` nodes, and `PhysicalFluidTopology` seeds every
junction from `boundaryStates.get(boundaries.getFirst()).state()` — the island's lowest-id boundary,
which on a player's line is the generator he placed first. On a water line that seed is liquid-full,
so `vaporVolume()/volume() == 0 < .01` and **the two junctions turned the tolerance down to 1e-10 for
the whole island**. The generator does the same thing, but a generator is present on the healthy
control line too, so on its own it is survivable; what makes a filter line different is that it has
to stay at 1e-10 while carrying a *tank* whose rows cannot meet it.

Measured, at the interval that dies (full labelled residual dump, 16 rows):

```
f[7] = 1.0276868244756662E-10   EQUILIBRIUM  node=2 id=5 RESERVOIR local=4/6
       mass=2.2899 kg  T=291.59 K  P=101325 Pa  vapourFraction=0.99887
every other row |f| <= 2.2e-12
```

The tank has taken on 0.0011 m³ of liquid water, so it is two-phase, and its water-saturation
equilibrium row `log(p_water / p_sat(T))` floors at 1.03e-10 by arithmetic alone. The value is
**identical at dt = 9.96e-7, 4.98e-7 and 2.49e-7 s with identical unknowns** — it is a fixed point
of the residual, not a step-size artefact. So the controller halves, halves again, and
`PassiveIntervalSolver` gives up with `Substep refinement exhausted`. The island never advances, and
per `IslandCoordinator` a held island never advances again: edits queue forever behind
`WAITING: configuration event`, and it survives block removal and reload.

Mechanism in one sentence: **a zero-holdup junction, which owns no volume, no stock and not a single
Newton unknown, was allowed to demand of every real node a tolerance those nodes cannot reach.**

The fix (`782e6ab`) asks only the nodes that hold a finite inventory:

```java
for (int i = 0; i < seeds.size(); i++) {
    var kind = graph.reservoirs().get(i).kind();
    if (kind != NodeKind.RESERVOIR && kind != NodeKind.PORT) continue;
    if (seeds.get(i).vaporVolume()/seeds.get(i).volume() < .01) { smallHeadspace = true; break; }
}
```

`GENERATOR`, `VOID` and `JUNCTION` are excluded: the first two carry a prescribed boundary state,
the third a normalized guess, and `Equations` gives none of them a layout, an unknown or a row.
`PORT` is deliberately **kept**: `TrBdf2StepSolver` rewrites every `RESERVOIR` as `PORT` for its
instantaneous rate graph, so a port is a finite reservoir wearing a different hat. My first attempt
excluded `fixed()` wholesale, which also caught `PORT`, and
`HydraulicMatrixQualificationTest.hydrostaticRestAndReversedElevationUseTheSameGravityEnergyLedger`
caught it immediately: a liquid-full hydrostatic pair drifted from 0 to `1.634742275043802E-10` kg/s
against its 1e-10 assertion. That test is exactly the case the tight tolerance buys, and it passes
again with `PORT` restored.

### F2 — the reconstruction mixed a junction the equations had retained

`PassiveStepSolver.Equations` decides what a zero-holdup junction contains from what arrives, but
only above a floor, in two places:

```java
// junctionInflow
if (incomingMass[node] > 1e-14) { ...fractions from inflow...; return incomingEnergy[node]/incomingMass[node]; }
// ...otherwise the stored guess
// nodeSolidRows
if (incomingMass[node] > 1e-14) { solids[c] /= incomingMass[node]; }
else { solids = reservoir.state().solidMoments().values(); solids[c] /= reservoir.state().mass(); }
```

`ConservativeTransport.reconstruct0` asked a different question:

```java
if (reservoir.junction()) { add(columns,row,row,1); if (incoming[node]==0) { ...stored guess... } }
...
if (index[receiver] < 0 || flows[edge] == 0) continue;
```

`> 1e-14` versus `== 0`. An island at rest is not at exactly zero flow — it is at the flow its
pressure residual leaves it, which is 1e-14 to 1e-16 kg/s. Every such island falls in the window
where the two disagree.

On a clear island nothing shows: what arrives and what was stored are the same fluid, so both
answers give the same mass fractions. **Across a filter edge they are opposites.** The stream leaving
a filter carries no solids at all, so the reconstruction set the outlet junction's solid fraction to
0 while the equations still held it at the seeded slurry's. The gate then re-evaluates the equations
at the reconstructed point and sees the whole fraction:

```
GATE row=9  JUNCTION_SOLID_MOMENT[0]  node=3 id=-3 JUNCTION
      stateSolidKg=125.0  inventorySolidKg=125.0  incomingMass=3.922681317536491E-16
      value=0.11669069400247264   flows=[4.44e-16, 4.44e-16, 4.44e-16]
```

125 kg of solids on a 1071 kg junction is 0.11670. The live world's 0.05834 is the same quantity on
its own generator's slurry. `incomingMass = 3.92e-16` is below the 1e-14 floor, so the equations
retained; `flows = 4.44e-16` is not zero, so the reconstruction mixed.

The fix (`a152b0a`) gives the two sides one predicate:

```java
static final double JUNCTION_INFLOW_FLOOR = 1e-14;          // ConservativeTransport
boolean[] fed = new boolean[nodes];
for (...) fed[node] = !reservoirs.get(node).junction() || incoming[node] > JUNCTION_INFLOW_FLOOR;
...
if (!fed[node]) { ...stored guess... }
if (index[receiver] < 0 || flows[edge] == 0 || !fed[receiver]) continue;
```

and `PassiveStepSolver` now reads the same constant. This is exact rather than approximate:
`incomingMass` in the equations and `incoming` in the reconstruction are the same expression
(`|flow| * (1 - donorSolidFraction)`) accumulated over the same edges in ascending edge order from
the same candidate states, so they are bitwise equal and the two branches can never disagree.

## 5. Verification

Run sequentially, one Gradle invocation at a time.

| Suite | Baseline | Result |
| --- | --- | --- |
| `fluidScienceTest` | 161 | **161 tests, 0 failures** |
| `fluidRuntimeTest` | 106 | **116 tests, 0 failures, 1 skipped** (106 + 10 new, one of them the disabled F3 reproduction of §5b) |
| `fluidSolverRegression -PfluidRegressionMode=exact` | zero deviation | **BUILD SUCCESSFUL**, `chain-100 vs reference: max deviation: state/moles 0.000e+00 relative, temperature 0.000e+00 K, phase fraction 0.000e+00, flow 0.000e+00` |
| `fluidNetworkBenchmark` substeps | 30/11, 19/14, 37/3 | **30/11, 19/14, 37/3** — unchanged |
| `runFluidGameTestServer -PfluidGameTestRunId=filter-fix-01` | 20 | **`All 20 required tests passed :)`**, 11.49 s |

Caveat on the regression: three of its four fixtures skip in *every* checkout because
`build/probe/core.dat` and `core-fallback.dat` do not exist anywhere on this machine
(`quiet-11312`, `quiet-11324`, `cold-11312` all report "no readable … island"). Only `chain-100`
actually ran. That is a pre-existing gap in the worktree, not something this branch changed, but it
means the bit-identity evidence is one clear-fluid chain, not four islands.

### Fixture timings (3 samples each, `--rerun-tasks`, same session, seconds)

| Fixture | Base `fca6a15` | Head `287c862` |
| --- | --- | --- |
| `SolidRuntimeTest.physicalPumpFilterAndNitrogenReceiverStartTogether` | 0.570 / 0.536 / 0.628 | **0.411 / 0.331 / 0.367** |
| `SolidChainTransportTest.thirtyReservoirChainIntegratesOneIntervalWithinItsBudget` | 0.570 / 0.473 / 0.620 | 0.671 / 0.502 / 0.691 |
| `SolidChainTransportTest.tenReservoirChainIntegratesFiveSecondsWithoutALineSearchStall` | 0.155 / 0.219 / 0.074 | 0.175 / 0.121 / 0.154 |
| `SolidChainTransportTest.filterIslandReusesItsSolverWorkspacesAcrossIntervals` | 0.049 / 0.037 / 0.041 | 0.043 / 0.062 / 0.079 |
| `SolidChainTransportTest.clearChainSubstepCountsAreUnchanged` | 0.056 / 0.068 / 0.064 | 0.051 / 0.070 / 0.069 |

The pump-filter fixture is measurably faster (median 0.570 → 0.367 s): it is a generator/void/junction
island, so F1 stops holding it to 1e-10 for no reason. The `SolidChainTransportTest` chains are
reservoir-only islands that keep the tight tolerance, and their spread is ±40 % run to run on this
machine — the head samples sit inside the base range in every row. Their own assertions, which are
exact substep and workspace counts rather than times, all pass. No regression.

## 5b. F3 — a driven, tank-terminated filter line still stops (not fixed)

Found by the in-game pass, not by the fixture: raising the generator of a
`generator → pipe → filter → pipe → reservoir` line to 400 kPa — the one thing a player does to
make a filter carry anything — filled the tank and then held the island, with
`Newton line search stalled at residual 1.0053784466554722E-9`. Screenshot
`g1-held-before.png`. It reproduces in `fluidRuntimeTest` at interval 8 of 40 with residual 3.5e-9
(`filterLineToATankKeepsIntegratingOnceTheTankIsFull`, disabled with this account in its comment),
and the filter-free control at the same 400 kPa fills the same tank and runs all 40.

**It is pre-existing.** At `fca6a15` the same line dies at the same interval 8, at
`1.0179235231981111E-10` — F1, which was simply reached first and hid this one.

Mechanism, from the labelled dump at the stalled point (island at rest, all flows ~1e-14 kg/s, tank
and generator both at 400000 Pa):

```
f[14] = 3.5179743119729238E-9   HYDRAULIC edge=2 id=-9223372036854775805 filter=true
        junction id=3  P = 400000.0000330558
        junction id=-3 P = 399999.99968125834      -> 3.5e-4 Pa apart
f[12], f[13] (the two plain pipe rows) = -3.3e-10
```

The hydraulic row is `(driving − loss)/1e5`. Dividing by a **fixed** 1e5 Pa rather than by the
island's own pressure means a 1e-9 Newton tolerance demands 1e-4 Pa *absolutely*, whatever the
island runs at: a relative 1e-9 at 101 kPa, which converges, and 2.5e-10 at 400 kPa, which does
not. The filter edge carries it rather than the pipes because its clean resistance is ~894
Pa/(kg/s) against a pipe's ~5.8, so the same imbalance lands on its row ten times larger.

I did not fix it. Rescaling that row changes the residual of every island in the tree and cannot be
bit-identical against the recorded regression reference — the one property this branch's
verification currently proves untouched — so it needs its own capture and its own qualification,
not a hunk in a filter fix. Loosening the tolerance a second time would be exactly the unprincipled
move that produced F1.

Consequences worth stating plainly: **filtration itself works** — the void-terminated lines carry
solids under real driving pressure, fill, clog and recover, in the fixture and in the client (§9).
What still stops is a filter line that ends in a tank and is driven above roughly 150 kPa.

## 6. The pumped shape is a pre-existing pump defect, not a filter defect

`generator → pump → 3 pipes → reservoir`, **no filter anywhere**, held at interval 17/40 with
`Newton iteration limit at residual 2.6322406197546577E-6; active-set pass=1` — and it does so at
`3c27271`, before any solid-phase commit. The pumped *filter* line holds one interval later (18/40),
so the filter block costs the island nothing.

What is happening, from the labelled dump: the tank has been filled to 601323.6 Pa against the
pump's shutoff of 101325 + 500000 = 601325 Pa, i.e. 1.4 Pa from stall, and is 83 % liquid. The
failing row is the pump edge's own hydraulic row in `PUMP_HEAD_LIMIT`; at `440a754` the same line
reported 440 `Phase/device active-set cycle` rejections, which is the mode logic chattering between
`PUMP_HEAD_LIMIT` and `CLOSED` as the discharge crosses the shutoff head.

It is specifically the pump's device active set, not tank stiffness: a **passive** generator held at
the pump's own 601325 Pa fills the same tank to the same place over the same intervals and never
stops (`aPassiveLineAtThePumpsShutoffPressureFillsTheSameTank`, 40/40 intervals). So the live
world's shape (ii) was misattributed to the filter; it will reappear on any pumped line whose tank
reaches the pump's shutoff pressure.

I did not fix it. It is a different subsystem, it predates the entire branch under review, and a
change to the pump active set needs its own measurement and its own regression capture rather than
being folded into a filter fix. The permanent fixture asserts the invariant that *is* about
filtration — `aFilterBlockCostsAPumpedLineNoIntervalOfItsOwn` requires the filtered line to survive
at least as many intervals as the filter-free control — so it keeps passing once the pump is fixed,
and the comment on it says to raise both to `OK` then.

## 7. Shapes (iv) and (v): not reproduced

The two recorded solids-with-flow holds — 20 % / 100 µm at 300 kPa and 5 % / 100 µm at 150 kPa into
a void — integrate cleanly in this fixture at `fca6a15`, i.e. **before** either fix, so whatever
stopped them in the world is not captured by the block line alone. Two differences I did not
eliminate:

* the live generator had been edited through the device screen, so its composition and temperature
  may not be the fixture's pure water at 298.15 K;
* the live island had already been running (and, per the report, already HELD once), so its tank,
  junction and cake states at the moment of the edit were not the compiler's fresh seeds.

Both recorded residuals (16435.9 and 5531.1) are far too large to be either defect above, and the
shape of `(driving − loss)/1e5` with a saturated filter inlet makes a throttled-inlet row
(`(copySign(limit,driving) − flow)/max(limit,1e-8)`) the natural suspect: when a nearly full cake
drives `room/(dt·retainedPerMass)` towards zero, that denominator collapses to its 1e-8 floor and a
residual of order `flow/1e-8` is exactly the magnitude reported. **That is a hypothesis I did not
test.** It is the first thing to probe if a filter island holds again with a partly full cake.

What can be said positively is that the shape those two rows name now works in the real client: a
void-terminated filter line carried 1 % solids at 150 kPa and 10 % solids at 300 kPa, filled,
clogged at exactly 100 %, and recovered, without a single hold (§9). If the live (iv)/(v) holds had
a cause of their own it did not reappear under the same settings.

## 8. Reject-to-source for trace transfers

### 8a. Is it the root cause of F1?

Related but not the same thing, and it is not the right fix.

F1's trigger *is* a trace transfer: the island at rest carries 4.44e-16 kg/s across all three edges,
which is a trace by any definition. But the defect is not that the trace was delivered — it is that
the two halves of one step disagreed about **whether it was delivered at all**, and the gate that
compares them reported the junction's entire solid fraction. Reject-to-source would mask it: if the
sub-trace flow were returned to the donor and the committed edge flow were exactly zero, the
reconstruction would take its retain branch and the two would agree by accident.

Two reasons that is the wrong fix here:

1. **It does not close the window.** The equations switch on the *node's total* incoming rate summed
   over its edges, not on one edge. Ten edges each just under a per-edge trace threshold still sum
   above the node floor, and one edge just over it can still leave the node under. The two
   predicates have to *be* the same predicate; making transfers smaller only makes the disagreement
   rarer.
2. **It is not bit-identical.** Reject-to-source changes what every island commits, clear ones
   included, so the exact-mode regression reference would have to be re-captured — which is the one
   property this branch's verification currently proves untouched. The consistency fix is provably
   bitwise unchanged wherever a junction's inflow exceeds the floor, which is every island that is
   actually flowing.

### 8b. Would it let the existing dust floors be retired?

| Floor | Retirable? | Why |
| --- | --- | --- |
| `SOLID_CLAMP_FRACTION` (1e-6) in `Equations.maximumStep`'s `clampFloors` | **No** | It bounds the *Newton trial path* inside one step, not what is committed. The 1e-26…1e-70 kg dust exists because backward Euler's own linear system moves ~1e-6 of a donor's solids one hop per iteration; Newton visits those iterates whatever the step finally commits. Enforcing a trace threshold inside the residual instead would make the residual discontinuous in the flow unknown — precisely the kink that stalls a line search, and precisely the shape of F2. |
| `PhaseLayout.decode`'s nonnegativity projection on the three moments | **No** | Same reason: it exists so an overshooting candidate is a point the line search may evaluate and reject on its residual rather than a domain failure that kills every backtrack. Reject-to-source does not change which trial points are visited. |
| `SolidInventory.Accumulator.finishNonNegative()` in the TR-BDF2 companion (`TrBdf2StepSolver:186`) | **No** | The companion forms a *signed* combination `base + e1/α(first−firstBase) + e2/α(second−base)` of per-population masses. A population can come out at −1e-30 by cancellation with every stage inventory nonnegative. That is an error-estimator artefact, not a transfer. |
| the 1e-10 kg allowance in `PassiveIntervalSolver.error`'s per-population term | **Weakened, not retired** | Its job is to stop the embedded estimator reading a relative error of 1 on two populations that differ by dust. Reject-to-source would stop transfers *creating* sub-trace populations, so the term would fire less. But a population can also be driven down through the trace band from above, by withdrawal or by filter capture, and the floor is still needed for those. |

So: three of the four protect the inside of a Newton/TR-BDF2 step, where nothing has been committed
and where imposing a threshold would introduce the very discontinuity these fixes are about. Only
the fourth is touched, and only partially.

### 8c. What it would cost

* **A regression re-capture.** Not bit-identical, by construction.
* **A new trajectory discontinuity.** A threshold on a *committed* quantity flips a delivery on and
  off as flow crosses it between intervals. To avoid that showing up as accuracy rejections it would
  need the same event/refinement treatment `SolidEventIntegrator` gives a transport closure — a
  declared transition the interval solver refines onto.
* **A conservation restatement.** "Returned to the donor" has to appear in
  `ConservativeTransport`'s boundary ledger, in `checkConservation`, and for a filter edge in the
  cake accounting, or `GLOBAL_ENERGY_BALANCE` and the mass audit will see stock appear at the donor.
* **Three call sites, not one.** `reconstruct` alone is not enough: `Equations` must agree (§8a
  point 1), and `SolidEventIntegrator`'s rate pass decides closures from the same flows.

Recommended framing if it is picked up: *one trace predicate, stated once, consumed by the residual,
the reconstruction and the ledger* — which is the same principle as the F2 fix, applied to a larger
threshold. Budget a regression re-capture.

## 9. In-game confirmation

Dev client via the langyo/minecraft-mcp bridge, creative superflat world, blocks at `y = -59`.
Screenshots in `documentation/screenshots/solid-phase-filter-fix/`, alpha-flattened; the client log
is kept beside them as `client-latest.log`. (Only the evidential captures are kept — the
world-creation and window-probing shots, including whole-desktop captures taken while working out
the input path, were deleted.)

Two bridge notes for whoever does this next. The bridge refuses every input command with
`not in control mode` until control mode is on, and the pause menu has **no** "MCP Take Over"
button in this build — the HTTP command `enter_control_mode` turns it on directly, which is what I
used. Getting as far as a loaded world still needs real desktop input, because control mode cannot
be entered before there is a world: `build/mcp/poke.ps1` forces the window to the foreground with
`AttachThreadInput` and drives `java.awt.Robot` in the bridge's own 427x240 widget coordinates.
Calling `open_chat` *before* control mode leaves the client in a broken `ChatScreen` that swallows
every mouse event; a real ESC clears it.

| Check | Result | Screenshot |
| --- | --- | --- |
| A `generator → pipe → filter → pipe → reservoir` line at the default 101325 Pa is not HELD | **`FULL`** on the generator, **`SOLVING`** on the filter with `Committed` advancing 1031.20 → 1063.20 s and `Lag 1.05 s`, tank `FULL` | `f1-line-built.png`, `f2-generator-screen.png`, `c1-reservoir-line-filter.png`, `c2-reservoir-line-filter-full.png`, `c3-reservoir-receiving.png` |
| A generator with `createcheme:demo_particle` solids fills the filter, Load % and Captured kg rise | 150 kPa, 1 % solids: `Load 28.16 % / Captured 7.04 kg` → `Load 41.54 % / Captured 10.38 kg`, `Net 6.01 → 3.06 kg/s` as the cake resistance builds | `a1-three-lines.png`, `a3-applied.png`, `a4-filter-loading.png`, `a5-filter-loading-more.png` |
| `Recover solids` becomes active, yields a `Recovered Solids` item and resets Load | button active, `Solid recovery requested.`, `Load 41.54 % → 9.24 %`, item in hand named **Recovered Solids** (three of them by the end) | `a6-recovered.png`, `a8-recovered-item-held.png`, `c4-overview.png` |
| Running to capacity shows `filter clogged`, and recovery restores flow | 300 kPa, 10 % solids: **`filter clogged / FULL`**, `Load 100.00 % / Captured 25.00 kg`, `Net 0.00 kg/s`, `Pressure drop 198.08 kPa`; after recovery `FULL`, `Net 3.38 kg/s` | `b1-generator-300kpa.png`, `b2-filter-clogged.png`, `b3-recovered-flow-restored.png` |
| `run/logs/latest.log` has no `status=HELD` for these islands | **no HELD for either fresh line** — every one of the 375 HELD records in the session names `fluid_island=11`, the first line, which I had left at 400 kPa on F3 | `client-latest.log` |

The F3 line is also the in-game confirmation of the reported consequences, which is why I left it
running for a while before removing it:

* `HELD: Substep refinement exhausted: Newton line search stalled at residual 1.0053784466554722E-9`
  survives a save and a full client restart (`g1-held-before.png`);
* setting its pressure back to 101325 and pressing Apply gives
  **`WAITING: configuration event / HELD: …`** — the edit is accepted and then queues forever
  behind the hold, exactly as reported (`g2-reset-101325.png`);
* breaking all five of its blocks does not release it: `committed_tick` stays frozen at 6032 and the
  island keeps logging the same hold.

What I did **not** confirm in game: the pumped variant (§6) and shapes (iv)/(v) as the live world
recorded them (§7).

## 10. What I did not do, and why

* **Did not fix F3, the driven tank-terminated filter line (§5b).** Pre-existing at `fca6a15`,
  where F1 reached it first. The fix is to scale the hydraulic row by the island's own pressure
  instead of a fixed 1e5 Pa, which moves every island's residual and needs its own regression
  capture. This is the most important remaining item: filtration works, but a filter feeding a tank
  above roughly 150 kPa still stops.
* **Did not fix the pumped-line hold (§6).** Pre-existing at `3c27271`, reproduces with no filter,
  lives in the pump device active set. Fixing it means changing `PUMP_HEAD_LIMIT ↔ CLOSED` switching
  near shutoff, which needs its own measurement and regression capture.
* **Did not reproduce shapes (iv) and (v) (§7).** They pass in the block-line fixture even before
  the fixes, so the live trigger involves generator settings or an already-running island state I
  could not recover from the report. I recorded a concrete hypothesis instead of guessing a fix.
* **Did not implement reject-to-source (§8).** Explicitly out of mandate, and it is not F1's cause.
* **Did not narrow the bisect below the four requested points.** (i)'s symptom changes somewhere in
  `3e949c4`…`e97fe5b`; I did not isolate which of those seven commits, because none of them
  *introduced* the hold — the hold is present at `440a754`.
* **Did not re-capture the three skipped regression fixtures.** Their `build/probe` snapshots are
  absent from every checkout on this machine; producing them is a separate exercise.
* **Removed all temporary instrumentation.** The labelled residual dump used for §4 and §5b (a
  `describeRow` and `dump` on `Equations`, a debug branch in the gate and in the Newton catch, and a
  `build.gradle` system property) and a probe that made the pre-interval rate pass non-fatal were
  all reverted; `git status` shows nothing but the committed work. That probe is worth recording
  anyway: making the rate pass survive its own non-convergence moves F3's failure from the rate
  pass into the interval integration rather than removing it, so it is not the fix.
* **One process rule was broken.** Two `fluidRuntimeTest` invocations (the first F3 reproduction and
  its instrumented dump) ran while the dev client was still up, before I realised F3 existed. They
  were pass/fail runs, not measurements, and every number quoted in §5 was taken with no client
  running; the client was stopped before any further Gradle. Stated because the rule is the rule.
* **Left the demo world in `run/`.** Two healthy filter lines at `z = 4` and `z = 8`; the F3 line at
  `z = 0` was removed, though its island is still held in the ledger, which is itself the evidence
  in §9.
