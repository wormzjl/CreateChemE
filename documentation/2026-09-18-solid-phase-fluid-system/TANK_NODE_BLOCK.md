# F4 — a tank behind a zero-holdup device junction

Worktree `D:\Minecraft\Modding\1.21\CreateChemE\.claude\worktrees\agent-a74f606336340ffe9`,
branch `claude/tank-node-block`, base `ae3b37c`.

**Headline.** The defect is one mechanism, not two, and it is neither the tank's node block nor the
Newton tolerance. A zero-holdup junction's mixing rows are a **ratio** of the flows that feed it, so
they do not shrink with those flows; deciding which neighbour donates *inside the residual* put a
finite jump in them at exactly zero flow, and the one-sided differences the Jacobian is built from
only ever sample one side of it. A settled tank's own last correction moves its pressure by about
1.5 mPa, which through the line's resistance moves every flow in the island by 1.6e-6 kg/s and
crosses that jump — so the linear model predicted 8e-25 and the step delivered 1.7e-3, twenty orders
apart, and all twenty-four backtracks landed on the same plateau. Freezing the donor for the pass
that mixes on it and revising it in the outer active-set loop fixes the **valve** line completely,
at every pressure, with the tank settling on its generator. The **filter** line is measurably better
but still stops, on a *second and different* defect which is root-caused with numbers in section 7
and is **not fixed**.

Everything below is measured. Where I predict rather than measure, I say so.

---

## 1. Setup verification

| Step | Result |
| --- | --- |
| `git checkout -b claude/tank-node-block claude/pump-shutoff-active-set` | done |
| `git log --oneline -1` | `ae3b37c Hold both pumped filter lines to forty intervals and to the shutoff state` — as required |
| Other checkouts touched | none written. `D:\Minecraft\Modding\1.21\CreateChemE` and every other worktree were read only. Three files were *copied out* of read-only references and into this worktree: the MCP bridge jar from `solid-phase-fluid-system-plan-4369b0`, and `Flatten.java` / `Poke.java` / `poke.ps1` from `agent-ae086faac00564dbc`'s `build/`. `build/probe/core*.dat` was copied from `fluid-perf-probe` and then deleted again — see section 6. |
| `git stash` | never used, bare or otherwise. The one baseline comparison that needed the unmodified tree was taken by copying the two files to `/tmp`, `git checkout --` on them, measuring, and copying them back. |
| Gradle concurrency | `Get-Process java \| Select Id,CPU` before every invocation, twice at the start to confirm the idle daemons were not accumulating CPU (all six flat across a 4 s sample). No suite was ever run while the dev client was up; the client was started only after every suite, the regression, the benchmark and the GameTest server had finished. |
| `python` in Git Bash | not used. Everything is Bash, PowerShell, Gradle or a small Java helper. |
| Commit trailer | every commit ends with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. |

---

## 2. Reproduction, before any change

A temporary `TankNodeProbeTest` (deleted before the first commit) built the three block lines through
the real `PhysicalFluidTopology` and ran each for 40 x 5 s intervals on one retained
`PassiveIntervalSolver` from `RetainedSolver.COLD_START_SECONDS`, exactly as
`FilterBlockLineIslandTest` does. The whole sweep runs in 2.7 s, which is what made the rest of this
possible.

Lines: `generator -> pipe -> inline_filter -> pipe -> reservoir`,
`generator -> pipe -> PressureValve(200000) -> pipe -> pipe -> reservoir`, and the filter-free
control `generator -> 4 pipes -> reservoir`. Generator 1 m³ water at P; tank 1 m³ nitrogen at
101325 Pa; everything at y=-59.

| generator | filter line | valve line | filter-free control |
| --- | --- | --- | --- |
| 101.325 kPa | OK 40/40 | — | OK 40/40 |
| 150 kPa | HELD @23, residual **70.652** | — | OK 40/40 |
| 200 kPa | HELD @17, residual **1.0514e-9** | — | OK 40/40 |
| 250 kPa | HELD @13, residual **67.052** | — | — |
| 300 kPa | HELD @11, residual **67.469** | HELD @7, residual **1.3456e-9**, tank 297780.38 Pa | — |
| 350 kPa | HELD @9, residual **1.4126e-9** | — | — |
| 400 kPa | HELD @7, residual **1.2857e-9**, tank 399998.24 Pa | HELD @6, residual **1.000000082240371e-9**, tank **387430.526** Pa | OK 40/40 |
| 600 kPa | HELD @5, residual **1.1125e-9** | HELD @5, residual **1.0251e-9** | OK 40/40 |

The valve row at 400 kPa reproduces `documentation/PUMP_SHUTOFF_ACTIVE_SET.md` section 5 to the
digit — interval 6, tank at 387430 Pa, `active-set pass=0`, residual `1.000000082240371E-9`. So F4a
and the valve hold are the same event, and the "tank node block" name was wrong: the valve line has
no filter, no solids and no device corner.

### A correction to the recorded row map

`documentation/HYDRAULIC_ROW_SCALE.md` section 7 reads the 400 kPa tank's block as "nitrogen
material (3), water material (4), energy (5), volume closure (6), nitrogen vapour/liquid equilibrium
(7), water saturation (8)". Labelled directly off `PhaseLayout`, the block is

```
3 n2.material[19]   4 n2.water-material   5 n2.energy
6 n2.volume-closure 7 n2.water-saturation 8 n2.partial-pressure-closure
```

There is **no nitrogen vapour/liquid equilibrium row**: this tank's hydrocarbon liquid volume is
zero, so `PhaseLayout.liquidActive` is false, nitrogen is vapour-only and carries no split unknown
and no equilibrium row. The two rows that floored at 1.0e-9 to 1.4e-9 are the **volume closure** and
the **water saturation**. Same for the "junction starvation" rows at 150 kPa: the row that reaches
-1.0 is the outlet junction's **partial-pressure closure**, not its amount normalization, and the
one at ~-67 is its **water saturation**, not its specific enthalpy. Both junction rows belong to a
vapour phase the junction should not have; see section 7.

---

## 3. The dumps that establish the mechanism

`StallProbe` (a temporary file, deleted before the first commit) dumped, at every stalled Newton
point: the row map and labelled residual, the same residual at the pass's seed, the same residual
under equations rebuilt at `dt = 1e-12` (the *rest* residual), the ledger-versus-state gap of every
node, the junction inflow accounting, a finite-difference Jacobian with the singular values and
left-null content of the whole island and of each node block, the Newton direction with labelled
columns, the residual at 34 step lengths along it, and a one-ULP sensitivity sweep.

### 3.1 The stall is not the tank, and not the ledger

Filter line at 400 kPa, deepest refinement, `dt = 6.6398e-9 s`, tolerance 1e-9:

```
row  label                                  f(stalled)        f(seed)   f_rest(seed)
6    n2.volume-closure                    9.576056e-10   9.679797e-10   9.679797e-10
7    n2.water-saturation                  1.285666e-09   1.299593e-09   1.299593e-09
8    n2.partial-pressure-closure          1.023524e-11   1.034598e-11   1.034598e-11
   every other row |f| <= 1.9e-16
node[2] ledger-vs-state: worst amount gap 0.0   energy gap 1.9e-6 J (1.0e-15 relative)
node[2] block: sigma=[1.257e5, 257.4, 1.468, 1.409, 0.3433, 7.054e-4]
    cond=1.78e8  ||f_block||=1.2857e-9  unreachable=2.31e-25
```

Three things at once. The ledger and the state agree **exactly** (the `LEDGER step-out` probe reports
a gap of 0.000e+00 at every accepted step of every line, so `ConservativeTransport.repartition` is
exact in the amounts). The rest residual equals the residual, so nothing about this is the step size.
And the tank block is **not singular**: the least-squares residual a step confined to its own six
columns cannot remove is 2.3e-25, i.e. the block can be solved. So the earlier reading of this as a
"near-singular block" was wrong — it is perfectly solvable and the Newton simply cannot take the
step.

Where the 1.2996e-9 seed comes from is measured too: the step before it printed
`ACCEPT dt=5.626906e-01 newton=9.899088e-10 tol=1.0e-09 reconstruction=1.299593e-09 ratio=1.30`. The
Newton exits at the first iterate inside the ball, the reconstruction re-encodes it and lands 30 %
above, and the next solve is handed that as its seed. **The filter-free control does the same thing**
and copes: tightening the acceptance gate from 1e-8 to the Newton tolerance was tried, and it breaks
the control lines too (CLEAR 150 and CLEAR 400 start holding on
`Conservative reconstruction fails equation gate: 1.000000082740371E-9`, a value refinement cannot go
below), so the seed's residual is a universal property of this solver and not the defect. That
experiment was reverted.

### 3.2 What the Newton direction does

Same point:

```
newton direction: ||d||inf=1.6313232e-6 at column 12   linear prediction ||f+Jd||=8.27e-25
    d[ 7] n2.lnP    x=+1.386294361e+00 d=+3.714361612e-09      (1.486 mPa on 400 kPa)
    d[12] edge0.flow x=-4.020656e-15   d=-1.631323e-06
    d[13] edge1.flow x=-4.020656e-15   d=-1.631323e-06
    d[14] edge2.flow x=-4.020656e-15   d=-1.631323e-06
    alpha=1.000e+00 |f|=1.703195580e-03 worst=n3.specific-enthalpy flow0=-1.631323e-06
    alpha=5.000e-01 |f|=1.703195574e-03 worst=n3.specific-enthalpy flow0=-8.156616e-07
    ...
    alpha=1.490e-08 |f|=1.703195568e-03 worst=n3.specific-enthalpy flow0=-2.832927e-14
    alpha=7.451e-09 |f|=1.703195568e-03 worst=n3.specific-enthalpy flow0=-1.617496e-14
```

The pressure correction the tank's own rows need is 1.486 mPa. The filter edge's clean coefficient
at this state is about 894 Pa per kg/s, so 1.486e-3/894 = 1.66e-6 kg/s — which is exactly the flow
change the direction carries. **The flow move is not optional; it is the hydraulic consequence of the
pressure move.**

And the residual along that direction is **flat at 1.7031955e-3 across seven orders of alpha** and
then drops back to the base value in one halving. A residual that does not vary with the step length
is not a slope; it is a step. The binding row is `n3.specific-enthalpy`, the filter outlet junction's,
and the step is at `|q| = 1e-14 kg/s`: below it `junctionInflow` retained the stored guess and the row
was 1.9e-16; above it the row reads the donor, the donor at negative flow is the tank at 298.2418 K
against the junction's stored 298.15 K, and cp·ΔT/(U/m) = 4180·0.0918/2.26e5 = 1.7e-3.

A coarser dump of the same line, at `dt = 0.5715 s`, shows the other face of the same switch: there
the base flow is +2.02e-11 (the generator donates, whose enthalpy *is* the junction's stored one, so
the row is 0) and the direction is -1.886e-4, so the jump is taken at the **upwind sign change**
rather than at the floor. Same plateau, same value 1.7031954e-3, same 24 failed backtracks.

### 3.3 Why the Jacobian does not know

`SparseNewton.differentiate0` perturbs each column by `+1e-6 * differenceScale`, and
`differenceScale` for a flow unknown is `max(1, |x|) = 1`. So the flow column is always differenced
**from 5e-14 to +1e-6**, i.e. entirely on the positive branch, where the junction's donor is the
generator and the enthalpy row does not move. `J[n3.specific-enthalpy][edge0.flow]` therefore comes
out ~0, the linear model predicts 8e-25, and the step goes the other way onto a branch the Jacobian
never sampled. That is the complete explanation of the twenty-order gap.

### 3.4 The control, for contrast

`LEDGER step-in` on the filter-free line at 400 kPa reports `worst=0.000000e+00` at every settled
step, and the line runs 40/40. It has no junction, so it has no degree-zero row: a plain pipe carries
its donor's properties only inside terms already multiplied by the flow (`target[c] += dt*flow*...`,
`stored += dt*flow*(h+gz)`), so its own upwind switch has a jump of size O(|q|·Δ) which is zero at
the switch. **The presence of a zero-holdup junction is the whole difference between the two lines.**

### 3.5 One defect or two

One, for the stall. The 150/250/300 kPa "junction starvation" rows and the 200/350/400/600 kPa
"tolerance floor" rows are two *symptoms* of the same switch reached from two sides, and the fix
moves both: after it, no line stalls on a junction mixing row at any swept pressure, and the valve
line runs to the end at all of them. What remains on the filter line (section 7) is a genuinely
separate defect — a phantom trace on a junction — that the first one was hiding.

---

## 4. The fix

`PassiveStepSolver.Equations.junctionDonorFirst`: one boolean per connection, read off the point the
pass starts at, saying which end donates into a zero-holdup junction **for the whole of that pass**.
`nodeAccumulate`'s junction accumulators (`incomingMass`, `incoming[]`, `incomingEnergy`,
`solidIncoming[]`) read it; the reservoir targets, the net-flow balance, the energy ledger and every
edge row keep the live upwind, so an accepted point is upwinded exactly as `ConservativeTransport`
reconstructs it and F2's equations/reconstruction agreement is untouched.

The outer active-set loop revises it: `donorsTurned` compares the converged flows against the donors
the pass was solved under and, if any connection with a junction at either end has turned around,
takes it as one more active-set change, exactly like a device mode. The donor signs join the
`WorkspaceKey`, which is also the cycle key, so the pass sequence stays finite.

Three decisions inside it, each measured rather than argued:

* **A connection with no junction at either end has no frozen donor.** Its entry is a constant
  `true`. Without that, an island of plain reservoirs keyed its workspace cache on flow signs that
  nothing in its equations reads, the preconditioner reuse changed, and `chain-100` moved by 1.2e-14.
  With it, the exact regression is 0.000e+00 on every gate.
* **A diverged trial point is not evidence.** Running `donorsTurned` on `failure.lastVariables()` as
  well was implemented and measured: it turns the filter's junctions onto the tank, which hands them
  the nitrogen of section 7, and **every** swept pressure lost an interval (400 kPa went from
  interval 8 back to 7, and from a tank settled at 399999.99971 Pa to one at 399998.24 Pa). Reverted;
  the comment in the catch block records it.
* **Freezing the donor, not smoothing it.** The alternative — making the junction's stored guess a
  donor of last resort at `JUNCTION_INFLOW_FLOOR`, so that the branch becomes one continuous
  equation — was implemented in full, in the equations and in `ConservativeTransport` together, and
  measured. It does remove the jump (the alpha ladder becomes monotone) but it does not fix
  anything, because the row still rises from 0 to 1.7e-3 within a 1e-14 kg/s window, which a
  24-backtrack line search cannot resolve either. Worse, it regressed two solids fixtures
  (`filterLineToAVoidIntegratesWithSolidsAndNoDrivingPressure` and
  `SolidRuntimeTest.physicalPumpFilterAndNitrogenReceiverStartTogether`) with
  `Fluid hydrocarbon state outside model domain`, because the two branches it blends are **not
  normalised the same way**: the fed branch divides by the delivered *fluid* mass rate, so its
  fractions sum to 1, while the stored branch divides by the stored *total* mass, so its fractions
  sum to 1 minus the solid fraction. Blending them mixes two different normalisations. That is a real
  latent inconsistency in the existing pair of branches, recorded here and left alone; it is
  currently harmless because only one branch is ever active. The blend was fully reverted.

### Why it cannot recur

The rule is not a threshold. It is the same rule the solver already applies to every other switch:
*a decision that changes which physical stream a row reads is an active-set decision and belongs to
the pass, not to the residual.* Device modes, phase regimes, trace support and boundary closure were
already decided once per pass from the pass's own seeds and revised by the outer loop; the junction
donor was the last one still being taken from the live iterate. With it frozen, one pass's residual
is a smooth function of its own unknowns, so the one-sided differences the Jacobian is built from are
valid along the step they produce, and the line search cannot be handed a direction whose derivative
does not hold. A future row that reads a donor will be smooth for the same reason, because the donor
it reads is a field of the pass.

The one thing it does **not** cover, and I did not fix because no fixture exercises it: `edgeRows`
computes `driving = P_a - P_b - rho*GRAVITY*dz + head` with `rho` the **live** donor's density, so a
line with a nonzero elevation difference between two nodes of different density has a second
degree-zero jump of `g*dz*Δrho/pressureScale` at its own upwind switch. Every fixture here is at
y=-59 throughout, so `dz = 0` and the term is identically zero. If a hold ever appears on a line with
elevation, that is the first thing to look at.

---

## 5. Qualification of the trajectories

`src/test/resources/fluid/regression/chain-100.json`, untouched. The other three reference islands
(`quiet-11312`, `quiet-11324`, `cold-11312`) SKIP in this worktree, as they did for F3 and for the
pump track — there is no readable `core.dat`. I copied the only `core.dat` on this host, from
`fluid-perf-probe/build/probe`, and it is **stale for this tree**: it fails with
`Incompatible fluid property basis ... createcheme:tjl20_methane`, which is the crude-regrouping
property revision. I deleted the copies again. So `chain-100` is the whole of the replayed set, and
that is a real limitation of this evidence.

**`-PfluidRegressionMode=exact` against the existing reference — PASS.**

```
chain-100 vs reference: max deviation: state/moles 0.000e+00 relative (-), temperature 0.000e+00 K (-),
                                       phase fraction 0.000e+00 (-), flow 0.000e+00 relative (-)
BUILD SUCCESSFUL
```

The change is **bit-identical** for every island without a zero-holdup junction, so the relative
gate, the re-capture and the re-verification the F3 track needed are all unnecessary here and the
committed reference is unchanged. For the record, the relative run was taken first and also passed;
the intermediate version that keyed junction-free edges on their flow signs gave
`state/moles 1.184e-14, temperature 2.842e-13 K, phase fraction 4.441e-16, flow 2.298e-11 relative
(1.13e-14 kg/s against a controller allowance of 3.16e-8 kg/s)` — inside the declared gate by four
orders, but the constant-donor rule removed it entirely and that is strictly better.

---

## 6. Verification

Sequentially, one Gradle invocation at a time, `Get-Process java` checked before each.

| Check | Result |
| --- | --- |
| `fluidScienceTest` | **161 tests, 0 skipped, 0 failures** |
| `fluidRuntimeTest` | **117 tests, 1 skipped, 0 failures** — 116 before, plus the new valve fixture; the skip is the filter fixture, still `@Disabled` |
| `fluidSolverRegression -PfluidRegressionMode=exact` | PASS at 0.000e+00 on all four gates (chain-100; three islands SKIP, see section 5) |
| `fluidNetworkBenchmark` | **30/9, 19/14, 37/3 — unchanged**, all three CONVERGED |
| `runFluidGameTestServer -PfluidGameTestRunId=tank-node-01` | **All 20 required tests passed**, 6.007 s |
| valve line 40/40 at 300, 400, 600 kPa | PASS, new fixture `valveLineToATankKeepsIntegratingOnceTheTankIsFull` |
| filter line 40/40 at 150–600 kPa | **STILL FAILS** — fixture remains `@Disabled`; section 7 |
| pumped lines 40/40 (`aFilterBlockCostsAPumpedLineNoIntervalOfItsOwn`) | PASS, unchanged |
| `SolidRuntimeTest.physicalPumpFilterAndNitrogenReceiverStartTogether` | PASS |

The new fixture's endpoints, against the filter-free control at the same pressure:

| generator | valve tank P | control tank P | valve tank mass | control tank mass |
| --- | --- | --- | --- | --- |
| 300 kPa | 300000.00083 | 299999.99983 | 657.3805263 | 657.3805257 |
| 400 kPa | 400000.00148 | 400000.00020 | 743.1612563 | 743.1612565 |
| 600 kPa | 600000.00205 | 600000.00044 | 828.5786247 | 828.5786247 |

3.4e-9 relative on pressure, 8.4e-10 on mass. The fixture asserts both at 1e-6.

### Timings, three samples each, against the unmodified base measured in the same session

| Fixture | base | with the fix |
| --- | --- | --- |
| `SolidRuntimeTest.physicalPumpFilterAndNitrogenReceiverStartTogether` | 0.552, 0.585, 0.553 s | 0.587, 0.534, 0.550 s |
| `SolidChainTransportTest` (4 tests) | 0.689, 0.705, 0.683 s | 0.757, 0.686, 0.681 s |

Indistinguishable; the spread within each set is larger than the difference between them. The base
numbers were taken by copying the two changed files to `/tmp`, `git checkout --`, measuring, and
copying them back — no stash.

---

## 7. What still stops the filter line, and what I did not do

The filter line is better and still broken. At 400 kPa it now reaches interval 8 with the tank at
**399999.99971 Pa** against the filter-free control's 400000.00020 — i.e. it fills correctly —
where at `ae3b37c` it stopped at interval 7 with the tank still at 399998.24 Pa. But it stops.

| generator | at `ae3b37c` | now |
| --- | --- | --- |
| 150 kPa | HELD @23, 70.652 | HELD @23, 66.697 |
| 200 kPa | HELD @17, 1.0514e-9 | HELD @16, iteration limit at 0.29765 |
| 250 kPa | HELD @13, 67.052 | HELD @13, 67.052 |
| 300 kPa | HELD @11, 67.469 | HELD @10, iteration limit at 0.29797 |
| 350 kPa | HELD @9, 1.4126e-9 | HELD @9, 65.155 |
| 400 kPa | HELD @7, 1.2857e-9 | HELD @8, iteration limit at 0.29788 |
| 600 kPa | HELD @5, 1.1125e-9 | HELD @5, iteration limit at 1.6094 |

No pressure stalls on a junction mixing row any more. The new failure, dumped at 400 kPa,
`dt = 6.485e-3`, `pass=0`, whose **seed already satisfies every row to 1.175e-9**:

```
row  label                                f(stalled)       f(seed)
4    n1.water-saturation                2.978786e-01  2.220446e-16
5    n1.partial-pressure-closure        3.221843e-03  0.000000e+00
16   n3.water-saturation                2.978785e-01  2.220446e-16
17   n3.partial-pressure-closure        3.221843e-03  1.455192e-16
node[1] block: sigma=[3.51e15, 7.72e8, 1.0, 0.5038, 2.84e-9, 2.40e-149]
    with every island column: sigma=[3.51e15, 7.72e8, 1.414, 1.0, 0.5038, 2.84e-9, 2.40e-149, 0 ...]
newton direction: ||d||inf=2.18e-5   alpha cap=0.9899999998460421
    d[ 0] n1.vapor[19]     x=+2.652252741e-14 d=-2.652252742e-14
    d[12] n3.vapor[19]     x=+2.652252574e-14 d=-2.652252567e-14
    alpha=1.000e+00 outside domain
```

The mechanism, in one paragraph. `reachableComponents` is an **undirected** closure, so the tank's
nitrogen is "reachable" at both of the filter's junctions whichever way the line is actually flowing.
`initialPhaseSeeds` then writes `n[19] = total*1e-12` into each of them and flashes it. A junction owns
no volume, so that trace becomes a vapour phase of 2.65e-14 of the node's own scale, and the
junction's water-saturation and partial-pressure-closure rows are then stated on a vapour that is
numerically nothing: the block is **rank five of six** (smallest singular value 2.4e-149, and still
1.0e-139 using every column in the island). Nothing delivers that trace — the junction's own
`fraction[19]` row pins its nitrogen to an inflow mass fraction of 5.5e-10 — so the Newton's only
root for it is **exactly zero**, and `maximumStep`'s nonnegativity rule caps each step at 0.99 of the
distance to that boundary. The unknown falls by a factor 0.99 per iteration, the 20-iteration limit
arrives first, and the two junctions are left with their water-saturation at 0.2979 and their
partial-pressure closure at 3.2e-3. When a phase correction intervenes first, the same block is
reached from a worse point and the numbers are 65 to 70 instead.

This is the same shape `HYDRAULIC_ROW_SCALE` section 7 called "junction starvation" at 150/250/300
kPa, with the rows correctly labelled.

### What I tried on it, and measured

* **Skip the `total*1e-12` trace on junctions only.** Immediate `A hydrocarbon-phase appearance pass
  is required` at interval 1 at every pressure: the junction's seed becomes pure liquid water with
  nitrogen still in the component mask, so `PhaseLayout` has a present component and no active
  hydrocarbon phase. Reverted.
* **Narrow the junctions' reachable set from the previously accepted flows** (apply
  `refineJunctionReachability` before the first pass instead of only after one). No change at any
  pressure: the pass loop re-widens it from its own converged flows, which are the backflow.
  Reverted.
* The remaining candidate, which I did **not** implement: a zero-holdup junction has no volume, so a
  vapour phase of 1e-14 of its scale is not a phase — it is the trace-support machinery
  (`PhaseSupport`, `TraceTruncationPolicy`, `singlePhaseComponentCount`) applied one level up, at
  the node rather than at the component. That is a change to how junction layouts are built and it
  would move every filter, valve and pump island, so it needs its own track and its own regression
  qualification.

### Other things I did not do, and why

* **I did not re-enable the filter fixture.** It still fails; it stays `@Disabled` with its Javadoc
  rewritten to the measurements above so the reproduction survives and the next reader is not handed
  a stale prediction. `fluidRuntimeTest` is therefore 117 with **1 skipped**, not 0 skipped.
* **I did not re-capture the solver reference.** The exact gate passes at zero against the existing
  one; re-capturing would be a no-op that removes the evidence.
* **I did not fix the normalisation mismatch** between `junctionInflow`'s two branches (section 4).
  It is latent, it cannot bite while only one branch is live, and touching it moves solids.
* **I did not run `fluidStressProfile`, `fluidServerBenchmark` or the module profile.** They need
  snapshots this worktree does not have, and the brief did not ask for them.
* **The three saved-island regression fixtures are unverified**, because no compatible `core.dat`
  exists on this host. Only the synthetic 100-reservoir chain was replayed.

---

## 8. In-game confirmation

Dev client via the langyo/minecraft-mod-mcp bridge (jar copied into `run/mods/`,
`pauseOnLostFocus:false` written to `run/options.txt`), over `localhost:9876/api/cmd`. Started only
after every suite, the regression, the benchmark and the GameTest server had finished, and stopped
before anything else was run. Creative superflat world, difficulty peaceful, `doDaylightCycle false`,
both lines placed with `/setblock` at **y = -59**. Screenshots in
`documentation/screenshots/tank-node-block/`, alpha-flattened; the client log is beside them as
`client-latest.log`.

Bridge notes for the next reader, beyond what the two earlier reports record: `enter_control_mode`
works from the title screen, but `click_button_index` on a `CycleButton` goes through
`manual_cycle`, which moves the widget's internal index **without firing its value change** — the
"Allow Commands" toggle reported `newIdx` flipping while the label stayed `OFF` through three
clicks. A real click through `poke.ps1 gui X Y` sets it properly. `right_click` is the command that
opens a block's screen (`use_block`, `interact` and `use` are all unknown); aim it by teleporting
with an explicit yaw and pitch.

| Check | Result | Screenshot |
| --- | --- | --- |
| both lines built at y=-59 | `generator → pipe → inline_filter → pipe → reservoir` at z=0, `generator → pipe → pressure_control_valve → pipe → pipe → reservoir` at z=8 | `03-filter-line-placed.png`, `04-both-lines.png`, `20-filter-line-overview.png`, `21-valve-line-overview.png` |
| filter generator at 400 kPa with 5 % `createcheme:demo_particle` / 100 µm | `Pressure 400.00 kPa abs`, `Flow (last) 9.15 kg/s`, `L 0 W 95 V 0 S 5 %`, `Settings accepted at the current simulation event.` | `07-pressure-typed.png`, `10-solids-filled.png`, `11-generator-applied.png` |
| the filter captures | **`filter clogged / FULL`**, `Load 100.00 %`, `Captured 25.00 kg`, `createcheme:demo_particle / 100.00 µm / 25.00 kg`, `Pressure drop 270.77 kPa`, `Committed 719.05 s`, `Lag 0.30 s` — solving, not held | `18-filter-block.png`, `19-filter-tank.png` |
| **valve line keeps SOLVING after its tank fills** | **YES.** Tank `FULL`, `400.00 kPa abs`, `298.24 K`, `743.16 kg`, `W 74 V 26 %`, `Lag 0.70 s`; the valve reads `FULL / CLOSED`, `Pressure change -1.17e-06 kPa`, `Lag 0.40 s`, later **`Lag 0.00 s`** | `16-valve-tank-gui.png`, `17-valve-block.png`, `26-valve-still-solving.png` |
| **filter line keeps SOLVING after its tank fills** | **NO, on clear water.** With solids it never gets there — the filter clogs at 25.00 kg and the tank stops at 128.28 kPa / 190.44 kg. Set the generator to 0 % solids and press `Recover solids`, and the line runs clear at `Net 29.89 kg/s` until the tank fills and then holds: `HELD: Newton line search stalled at residual 66.9695075162007; active-set pass=1`, `Net 1.15e-06 kg/s`, `Committed` frozen at 897.00 s, `Lag 177.05 s` | `23-generator-clear-water.png`, `24-filter-recovered.png`, `25-filter-clear-water-held.png` |
| `run/logs/latest.log` has no `status=HELD` | **for the valve line, yes** — zero HELD records in the whole session until the filter line was switched to clear water, and then 154 records naming **only** `fluid_island=26`, which is the filter line. The valve line's island never appears in a HELD record at all. | `client-latest.log` |
| client stopped, no Minecraft java process left | world saved with `save-all`, the Gradle `runClient` task stopped; `Get-CimInstance Win32_Process` matching `minecraft\|neoforge\|DevLaunch` returns nothing, and the six remaining java processes are Gradle and Kotlin daemons | — |

The two numbers that matter sit side by side in the same world at the same moment: the valve line at
`Lag 0.00 s` and the filter line frozen at `Lag 177.05 s`. The valve line is the shape the fix
addresses and it now runs; the filter line's hold is `66.9695` — the phantom-trace family of section
7, not the `1.0e-9` line-search family the fix removes, and the in-game residual matches the
fixture's 66.697 at 150 kPa and 67.052 at 250 kPa to two figures.

**Deviation from the brief's script.** The brief asks to confirm "the island keeps SOLVING after the
tank fills, the filter captures, and `run/logs/latest.log` has no `status=HELD`" for the filter line.
The filter captures and the island solves, but with 5 % solids the tank never fills, because a
clogged filter is the thing stopping the flow rather than a full tank; and once I removed the solids
so that the tank *could* fill, the line held. Both halves are reported above rather than one of them
being quietly taken as the answer.

---

## 9. Commits

| Commit | What |
| --- | --- |
| `a02a426` | **Freeze a zero-holdup junction's donor for the pass that mixes on it.** `PassiveStepSolver`: `Equations.initialPoint` and `Equations.junctionDonorFirst`, the junction accumulators in `nodeAccumulate` reading the frozen donor, `carries` extended for it, `donorsTurned` and its use as an active-set change on a converged point, and the donor signs in `WorkspaceKey`. This is the defect and the whole of the fix. |
| `0f13e31` | **Cover the valve block line, and record what stops the filter one now.** `FilterBlockLineIslandTest`: the new `valveLine` builder, `Run.tank()` (the tank is not the last node of a device island), the passing fixture `valveLineToATankKeepsIntegratingOnceTheTankIsFull` at 300/400/600 kPa with endpoint assertions against the filter-free control, and the disabled filter fixture's Javadoc rewritten from prediction to the section 7 measurements — including the row-map correction to `HYDRAULIC_ROW_SCALE` section 7. |
| — | This report and the in-game screenshots are **not committed**: `documentation/` is in `.gitignore`, as it was for `HYDRAULIC_ROW_SCALE.md` and `PUMP_SHUTOFF_ACTIVE_SET.md`. They live in this worktree at `documentation/TANK_NODE_BLOCK.md` and `documentation/screenshots/tank-node-block/`, and the whole report is reproduced in the final message because the worktree may be cleaned. |

No commit changes `ConservativeTransport`, `PhaseLayout`, `SparseNewton`, `build.gradle` or the
regression reference. The temporary `StallProbe.java`, the temporary `TankNodeProbeTest.java`, the
temporary label methods on `PhaseLayout` and the temporary `build.gradle` property passthrough were
all removed before the first commit; `git status` between the two commits shows only the file each
one touches.
