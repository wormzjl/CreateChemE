# A solids feed applied to a full tank

**Worktree** `D:\Minecraft\Modding\1.21\CreateChemE\.claude\worktrees\agent-ab8d04b83a80709c0`
**Branch** `claude/full-tank-solids-event`, from `claude/junction-phantom-trace` @ `04ad5ca`
**Commits** `a6f91f7`, `80f1b44`, `c7da543`

The last observed in-game hold of the solid-phase fluid system. A player's
`generator - pipe - inline_filter - pipe - reservoir` line at 400 kPa fills its tank and settles;
setting the generator to a 5 % `createcheme:demo_particle` feed then held the island immediately and
permanently on `HELD: Junction mass continuity does not close`, and setting the feed back to 0 % did
not release it.

It is **two** defects, and neither is in the step solver. Both live in what the runtime does that a
step-solver probe does not do: a configuration event **rebuilds the whole island**.

---

## 1. Setup verification

```
$ git checkout -b claude/full-tank-solids-event claude/junction-phantom-trace
Switched to a new branch 'claude/full-tank-solids-event'
$ git log --oneline -1
04ad5ca Hold the filter block line to forty intervals at every swept pressure
```

At that tip, before any change here: `fluidScienceTest` 161 / 0 skipped, `fluidRuntimeTest` 117 /
0 skipped, `fluidSolverRegression -PfluidRegressionMode=exact` 0.000e+00 on `chain-100` (the three
saved-island fixtures SKIP on this host, as they have for every report on this track — no readable
`core.dat`), `fluidNetworkBenchmark` 30/9, 19/14, 37/3, `runFluidGameTestServer` 20/20.

No other worktree and no file under `D:\Minecraft\Modding\1.21\CreateChemE` outside this worktree
was written. No bare `git stash` was used; the one detached checkout (for baseline timings, §7) was
`git checkout 04ad5ca` and back.

---

## 2. The runtime reproduction, and why the step-solver probe missed it

### 2.1 What the runtime does that a probe does not

`FluidWorldAuthority.submit` queues a topology event for any device edit. `applyPending` then, for
every affected island:

```java
var stock = boundaries();                       // the LIVE non-junction, positive-id nodes
...
if (replacement.device().boundary() && !old.spec().equals(replacement.spec())) {
    var initialized = replacement.spec().initialize(replacement.device(), model, () -> {});
    stock.put(edit.id(), initialized);          // the EDITED boundary, freshly initialized
}
var nextCompiled = PhysicalFluidTopology.compile(active..., stock, cakes, model.initialNitrogenCharge(...));
```

and hands the resulting graphs to `IslandCoordinator.topology`, which registers each as a **new**
`Island` — new revision, new `RetainedSolver`, `WAITING: full solve after topology change`.

So an edit is not a boundary swap inside a running graph. It is a **recompile**: the two boundaries
keep the states they have reached, everything else in the island is minted again, and the retained
solver is discarded. The previous report's probe (`SolidsIntoAFullTankProbeTest`, deleted) swapped
the generator's `Reservoir` inside the existing `PassiveNetwork` at intervals 1…30 and was OK at
every switch point — correctly, because that path never touches a junction.

### 2.2 The measurement

A temporary probe built the line through `PhysicalFluidTopology`, filled the tank on clear water for
20 intervals of 5 s on one retained solver, and then applied the event exactly as `applyPending`
applies it. Four arms, one run, base = `04ad5ca`:

| arm | what | result |
| --- | --- | --- |
| **A** | runtime rebuild + 5 % slurry generator | **HELD at interval 1/20: `Junction mass continuity does not close`** |
| **B** | in-place generator swap, same graph (what the earlier probe did) | OK 20 intervals |
| **C** | runtime rebuild, generator left clear | OK 20 intervals |
| **E** | as A, then a void placed behind the tank so the line flows | **HELD at interval 1/40: `Singular Newton Jacobian: Sparse LU rejected a singular matrix; active-set pass=0`** |

B is the control that says the step solver is innocent. C is the control that says recompilation on
its own is innocent. A and E are the two defects.

The island the rebuild produces, printed at the moment of the event:

```
(rebuilt island after the event)
  node[0] id=1  GENERATOR P=400000.0           mass=1071.4906085273121 solidKg=125.0 invSolidKg=125.0
  node[1] id=3  JUNCTION  P=400000.0           mass=1071.4906085273121 solidKg=125.0 invSolidKg=125.0
  node[2] id=5  RESERVOIR P=400000.0002132296  mass= 743.1612560090696 solidKg=  0.0 invSolidKg=  0.0
  node[3] id=-3 JUNCTION  P=400000.0           mass=1071.4906085273121 solidKg=125.0 invSolidKg=125.0
  pipe 0->1 plain   pipe 1->3 filter   pipe 3->2 plain
```

`node[1]` is the filter's inlet junction, `node[3]` its compiler-minted outlet junction. Both have
been minted holding **125 kg of the generator's particles**, including the one on the far side of the
filter edge.

---

## 3. Defect 1 — the throw. Two states, one normalisation

### 3.1 The dump

Instrumented at `ConservativeTransport.java:191`, on arm A's first interval (`dt = 1.0`, every flow
exactly 0.0 because the tank is full):

```
PROBE junction continuity node=1 id=3 kind=JUNCTION fed=false total=1.0001158038514524
  incoming=0.0 outgoing=0.0 dt=1.0
  reconstructed fluidSum=0.8833400880902277  solidSum=0.11677571576122463
  candidate mass=1070.4280353596107  solidKg=124.87604031080282  s_cand=0.1166599119097723
  stored inventory solidKg=125.0
  edge 0 q=0.0 filter=false donor=0 id=1 s_cand(donor)=0.11665991190702421
          s_recon(donor)=0.11665991190702421 fluidSum(donor)=0.8833400880929758
```

It is **not** the filter-side weight `1/(1-s)` the previous report predicted. It is the **inlet**
junction, on the *unfed* branch, with no flow anywhere in the island.

### 3.2 The mechanism

For a node whose composition the reconstruction does not solve for — a prescribed boundary, or a
zero-holdup junction below `JUNCTION_INFLOW_FLOOR` — `reconstruct0` wrote the fraction vector as

```java
var n = PhaseLayout.totalAmounts(candidate.get(node));
for (int c = 0; c < components; c++)
    rhs[c][row] = n[c] * molecularWeight[c] / candidate.get(node).mass();      // the CANDIDATE
for (int c = 0; c < populationKeys.size(); c++)
    rhs[components+c][row] = reservoir.inventory().solids().mass(key) / candidate.get(node).mass();
//                           ^^^^^^^^^ the STORED INVENTORY, over the candidate's total mass
```

and then asserted `|Σ − 1| ≤ 1e-8`.

On a vessel the two agree, because a vessel's stored inventory is exactly what its candidate carries.
**A junction owns no volume, so its total amount is a free scale the Newton moves.** Here:

| quantity | value |
| --- | --- |
| candidate fluid fraction sum | 0.8833400880902277 |
| candidate solid fraction | 0.1166599119097723 |
| *their sum* | **1.0000000000000000** |
| stored inventory solids | 125.0 kg |
| candidate solids | 124.87604031080282 kg |
| candidate total mass | 1070.4280353596107 kg |
| stored solids / candidate mass | 0.11677571576122463 |
| **total written** | **1.0001158038514524** |

The Newton had the junction's solid **fraction** right to 1e-15 (0.1166599119097723 against the
generator's 0.11665991190702421 — the equations' unfed branch pins the fraction, not the amount) and
had shrunk its **total** by 0.099 %, which is a junction's right. 0.12396 kg / 1070.43 kg =
1.158e-4, against a gate of 1e-8. Held on interval 1, every interval, for ever.

A clear-water island never sees this: with no populations anywhere, `conserved == components`, there
are no solid rows, and the candidate's fluid fractions sum to exactly 1 on their own.

### 3.3 The rule

**A composition is read off one state.** `ConservativeTransport.storedFractions` now takes every
entry from the candidate; the stored inventory supplies nothing but the *split* of the candidate's
aggregate solid mass across its populations:

```java
var amounts = PhaseLayout.totalAmounts(state);
for (int c = 0; c < components; c++) result[c] = amounts[c]*molecularWeight[c]/state.mass();
var stored = reservoir.inventory().solids(); double storedMass = stored.massKg();
double scale = storedMass > 0 ? state.solidMoments().mass()/storedMass : 0;
for (int c = 0; c < populationKeys.size(); c++)
    result[components+c] = scale*stored.mass(populationKeys.get(c))/state.mass();
```

The split is the only thing the inventory *can* supply: a Newton candidate carries its solids as the
three aggregate moments and keeps the seed's population list unscaled —
`PhaseLayout.decode`'s "Moment-only trial states never become owned inventories before population
reconstruction". The absolute masses in that list are stale by construction.

**Why it cannot recur.** The fluid mass and the solid mass now come from the same object and are
divided by that object's own `mass()`, and `State.withSolidState` defines
`mass = fluidMass − oldMoments.mass() + newMoments.mass()`. The gate `Σ = 1` is therefore an
identity of the expression rather than a coincidence of two sources agreeing, at **both** call sites
(the fixed-boundary fractions and the unfed-junction row) — so a fixed boundary whose candidate ever
diverges from its inventory cannot silently poison a fed junction downstream of it either.

**Bit-identical where they already agreed.** `SolidInventory.massKg()` *is* `moments().mass()` — the
same summation — so when a state's moments came from its own inventory the scale is exactly `1.0`
and the product is the same double. With no solids, `storedMass == 0` gives 0, as `0/mass` did.

---

## 4. Defect 2 — the seed. A junction minted with a boundary's stock

### 4.1 The measurement

With defect 1 fixed, arm A runs 20/20. Arm E — the same island with a void behind the tank so the
line actually flows and the filter has something to do — still held on interval 1:

```
Substep refinement exhausted: Singular Newton Jacobian: Sparse LU rejected a singular matrix;
active-set pass=0
```

Two controls, run in the same probe:

| arm | junction seeds | result |
| --- | --- | --- |
| E | as the compiler mints them: 125 kg on both | **HELD @1, singular at pass 0** |
| E2 | rebuilt from a graph whose junctions were clear — *but the compiler re-mints them from the generator anyway*, so identical seeds | **HELD @1, singular at pass 0** |
| E3 | the identical graph with `withSolids(SolidInventory.EMPTY)` applied by hand to the two junctions | **OK 40 intervals** |

E2 is worth stating plainly: there is no way to reach a clear junction through the compiler on a
slurry line, because *every* compile re-mints them from the first boundary. E3 isolates the cause to
one field.

### 4.2 The mechanism

`PhysicalFluidTopology.compile`:

```java
var seed = boundaryStates.get(boundaries.getFirst()).state();   // lowest-id boundary = the generator
...
reservoirs.add(new PassiveNetwork.Reservoir(id, node.elevation(), seed, NodeKind.JUNCTION));
```

A junction needs a temperature, a pressure and a composition to start a solve from. It was also
being handed the generator's **stock**: 125 kg of `createcheme:demo_particle`, on every junction in
the island, on activation and again on every topology event.

On the junction *past* the filter edge that is a population nothing can ever deliver — the filter
strips the stream. That is exactly `documentation/JUNCTION_PHANTOM_TRACE.md`'s phantom trace one
level up: on the aggregate solid moments instead of on a component. Nothing delivers it, so its only
root is exactly zero, and the block is singular at the first active-set pass.

### 4.3 The rule

**A zero-holdup junction is minted with the boundary's properties and none of its stock.** It owns
no volume, so it holds no particles:

```java
var boundarySeed = boundaryStates.get(boundaries.getFirst()).state();
var seed = boundarySeed.solids().empty() ? boundarySeed : boundarySeed.withSolids(SolidInventory.EMPTY);
```

**Why it cannot recur.** It is the rule `PassiveStepSolver.initialPhaseSeeds` already states for
species — *"Junction property guesses own no inventory and therefore cannot introduce a species"* —
applied to populations, which are stock in the most literal sense. A junction that is actually being
fed still gets the donors' solid fraction, from the equations' own fed branch
(`nodeSolidRows`: `solidIncoming[node]/incomingMass[node]`), which is where a junction's solids
belong: they are a property of what is passing through it, never of what it holds.

**Bit-identical** wherever the island's first boundary carries no solids — every clear-water island
and every fixture that does not configure a slurry generator.

### 4.4 Which commit fixes what

Both are individually sufficient for the recorded hold, and measured as such:

| | defect-1 fix only | defect-2 fix only | both |
| --- | --- | --- | --- |
| A (5 % onto a full tank) | OK 20 | OK 20 | OK 20 |
| E (then drained) | **HELD, singular** | OK 40 | OK 40 |

After the defect-2 fix I could not construct **any** live path that reaches the defect-1 throw: a
junction's solids then only ever come from a converged reconstruction, where its inventory and its
state agree, and at rest the Newton leaves a junction's total mass bit-identical (measured on a
valve line carrying 5 % slurry into a filling tank — arm G, junction mass unchanged at
1071.4906085273121 over 40 intervals, with and without the defect-1 fix). **So the defect-1 commit is
the correct statement of an expression that is currently only reachable through a re-seed, and I am
keeping it because it is what the throw's own gate rests on, not because a live fixture still needs
it.** That is the honest position; §9 says what that costs.

---

## 5. The release — a separate defect, deliberately not fixed

In game, setting the feed back to 0 % on the held island read
`WAITING: configuration event / HELD: Junction mass continuity does not close`, with the lag growing
167 → 204 → 323 s. That is **not** a second defect in the event path; it is a consequence of the
hold, and it is exact:

* `IslandClock.completed(slice, accepted=false)` does not advance `committedTick` — *"a held result
  retains all of its debt"*.
* `IslandCoordinator.aligned(event, affected)` requires `i.clock.committedTick() == fenceTick` for
  every affected island.
* `FluidWorldAuthority.nextReadyEvent` returns nothing until `aligned`, so `applyPending` never
  reaches the edit.

A permanently held island therefore cannot be reconfigured. **It is a separate defect and it is not
small.** The fence is the determinism contract — an edit lands at one named tick for every island it
touches, and never inside an admitted or committed interval. Letting a held island take its edit at
its own `committedTick` satisfies the second half but breaks the first: a multi-island event would
then take effect at a different simulated instant per island. And any cheap version of "this island
is stuck" is a threshold on consecutive holds, which is what this track is explicitly not allowed to
add. It needs its own decision about what an event means for an island that cannot advance.

With both fixes the island never holds, so the release applies on the next alignment — confirmed in
game (§8) and in the fixture.

---

## 6. Qualification

`ConservativeTransport` changed, so the exact gate is the one that matters. `chain-100` has no
junctions and no solids:

```
chain-100 vs reference: max deviation: state/moles 0.000e+00 relative (-), temperature 0.000e+00 K (-),
                        phase fraction 0.000e+00 (-), flow 0.000e+00 relative (-)
```

**Reference untouched.** `quiet-11312`, `quiet-11324` and `cold-11312` SKIP — no readable `core.dat`
on this host, as for F3, F4 and F5. That is the standing gap on this track: the three saved-island
trajectories are unverified here, and only the synthetic 100-reservoir chain was replayed.

`fluidNetworkBenchmark`: **30/9, 19/14, 37/3** — unchanged, same rejection reasons
(`Embedded pipe error`).

---

## 7. Verification

One Gradle invocation at a time; `tasklist | grep -i java` checked before each, and the only java
processes present were idle Gradle daemons (CPU deltas under 0.05 s over six seconds). No suite ran
while the dev client was up, and the client was started only after every suite.

| Check | Result |
| --- | --- |
| `fluidScienceTest` | **161 tests, 0 skipped, 0 failures, 0 errors** |
| `fluidRuntimeTest` | **121 tests, 0 skipped, 0 failures, 0 errors** (117 + 4 new) |
| seven-pressure filter sweep (`filterLineToATankKeepsIntegratingOnceTheTankIsFull`) | 150/200/250/300/350/400/600 kPa all **OK intervals=40** |
| valve lines (`valveLineToATankKeepsIntegratingOnceTheTankIsFull`) | 300/400/600 kPa all **OK intervals=40** |
| pumped lines | **OK intervals=40** |
| `status=HELD` anywhere in `FilterBlockLineIslandTest` output | **0** |
| `fluidSolverRegression -PfluidRegressionMode=exact` | chain-100 **0.000e+00** on all four metrics; 3 SKIP |
| `fluidNetworkBenchmark` | **30/9, 19/14, 37/3** |
| `runFluidGameTestServer -PfluidGameTestRunId=full-tank-solids-01` | **All 20 required tests passed**, 4.896 s |

### New fixtures — `FullTankSolidsEventTest`

| Fixture | What it pins |
| --- | --- |
| `aCompiledJunctionCarriesTheBoundarysPropertiesAndNoneOfItsStock` | a minted junction takes the first boundary's T and P and has `solidMoments().mass() == 0` and an empty inventory, while the generator keeps its 125 kg |
| `aSolidsFeedAppliedToAFullTankKeepsTheIslandIntegrating` | fill to 400000.0002132296 Pa, apply the feed **through the rebuild**, 20 intervals; the in-place swap runs alongside as the control |
| `theFilterCapturesOnceTheDrainedTankLetsTheLineFlow` | drawn down through a void: 40 intervals, cake clogged at >24 kg, inlet junction carries the generator's solid fraction, outlet junction carries none |
| `asecondConfigurationEventPutsTheFeedBackToZero` | the release rebuild integrates and the tank stays on its generator |

### Timings — `SolidRuntimeTest.physicalPumpFilterAndNitrogenReceiverStartTogether`

Three samples each, same session, `--rerun-tasks`, nothing else running:

| | s1 | s2 | s3 |
| --- | --- | --- | --- |
| base `04ad5ca` | 0.256 | 0.243 | 0.251 |
| with both fixes | 0.255 | 0.252 | 0.254 |

Within noise. That fixture *is* on the changed path — it configures a slurry generator, so its
junctions are now minted clear — and its assertions (filter clogged, flow 0 to 1e-9, nitrogen
receiver's solids empty) all still hold.

---

## 8. In-game confirmation

Dev client via the langyo/minecraft-mod-mcp bridge (jar copied to `run/mods/`,
`pauseOnLostFocus:false` in `run/options.txt`), over `localhost:9876/api/cmd`. Creative superflat,
`doDaylightCycle false`, `doMobSpawning false`, line placed with `/setblock` at **y = -59**:
`fluid_generator(0) - fluid_pipe(1) - inline_filter(2, facing=east) - fluid_pipe(3) - fluid_reservoir(4)`,
all at z=0. Screenshots in `documentation/screenshots/full-tank-solids-event/`, alpha-flattened; the
client log is beside them as `client-latest.log`.

| Check | Result | Screenshot |
| --- | --- | --- |
| line built at y=-59 | generator, pipe, filter, pipe, reservoir | `09-line-placed.png`, `27-line-overview.png` |
| generator at 400 kPa, clear water | `Pressure 400.00 kPa abs`, `Flow (last) 38.23 kg/s`, `L 0 W 100 V 0 S 0 %`, `Settings accepted at the current simulation event.` | `11-pressure-typed.png`, `12-generator-400kpa.png` |
| the tank fills | `FULL`, `400.00 kPa abs`, `298.24 K`, `743.16 kg`, `W 74 V 26 %` — the fixture's 400000.00021 Pa / 743.1612560 kg | `13-tank-full.png` |
| the lag settles | `Lag 3.15 s` → `1.55 s` → `0.05 s`. Falling, not growing. | `13-tank-full.png`, `14-tank-settled.png`, `16-solids-page.png` |
| **5 % `createcheme:demo_particle` / 100 µm applied to the full tank** | **accepted and the island keeps SOLVING.** `L 0 W 95 V 0 S 5 %`, `Settings accepted at the current simulation event.`, **`Lag 0.55 s`**. Before the fixes this was the exact point that held for ever. | `17-solids-filled.png`, `18-solids-applied.png` |
| drawing the tank down | `/setblock 5 -59 0 createcheme:fluid_pipe` and `/setblock 6 -59 0 createcheme:fluid_void` — a void cannot sit against a reservoir (two boundaries never connect), so the pipe between them is required | `27-line-overview.png` |
| **the filter captures** | **YES.** `Net 4.11 kg/s`, `Forward 4.11 kg/s`, **`Load 96.82 %`**, **`Captured 24.21 kg`**, `Pressure drop 295.91 kPa`, `createcheme:demo_particle / 100.00 µm / 24.21 kg`, `Lag 0.20 s` | `19-filter-capturing.png` |
| **`Recover solids` yields the item** | **YES.** `Solid recovery requested.`, the cake clears and refills, and `/data get entity @s Inventory[0].id` returns **`"createcheme:recovered_solids"`** | `20-filter-recovered.png`, `21-recovered-item-hotbar.png`, `22-recovered-item-id.png` |
| it goes on capturing to capacity | `filter clogged / FULL`, `Load 100.00 %`, **`Captured 25.00 kg`** — the fixture's 24.999999999975 kg to the displayed precision | `23-filter-refilling.png` |
| **feed back to 0 % is accepted and applied** | **YES.** `L 0 W 100 V 0 S 0 %`, `Settings accepted at the current simulation event.`, `Lag 0.65 s`, and **no `WAITING: configuration event`** | `25-feed-zero-typed.png`, `26-feed-zero-applied.png` |
| `run/logs/latest.log` has no `status=HELD` | **zero records** in the whole 255-line session, 05:08 to 05:24 | `client-latest.log` |
| client stopped, no Minecraft java process left | `Save and Quit to Title`, then `Quit Game`; `Get-CimInstance Win32_Process` matching `devlaunch\|neoforge\|net.minecraft.client` returns **nothing**, and the remaining java processes are Gradle daemons | `28-pause-menu.png` |

The same three lines as the previous report, before and after. At `04ad5ca`: the feed is accepted,
the island holds instantly, `Load 0.00 %`, `Captured 0.00 kg`, `Flow 0.00 kg/s`, lag 108 → 323 s, and
123+ `status=HELD` records. Here: the feed is accepted, `Lag 0.55 s`, the filter captures 24.21 then
25.00 kg, the recovered item is in the player's hand, and there are zero `status=HELD` records.

**Deviation from the brief's script, and why.** The brief allowed either a `fluid_void` behind the
reservoir or a pressure cycle. I used the void, plus one `fluid_pipe` between it and the reservoir,
because `PhysicalFluidTopology.compile` never links two boundary devices directly
(`d.boundary() && other.boundary()` is skipped) — a void placed straight against the tank would have
compiled into a separate island and drawn nothing.

---

## 9. What I did not do, and why

* **I did not fix the held-island event stall.** §5: it is a real, separate defect with an exact
  mechanism, and it is a change to the event contract rather than a small one. The two fixes here
  remove the only known way this line reaches it.
* **I have no live fixture that still fails without the `ConservativeTransport` commit.** §4.4 says
  so explicitly and shows the arm-G measurement that made me look for one. The commit is kept
  because it makes the throw's `Σ = 1` an identity of its own expression rather than an agreement
  between two sources, and because it is bit-identical wherever those sources agree — the exact
  regression confirms that. A reviewer who wants the tree to carry only code with a failing witness
  should drop `a6f91f7` and keep `80f1b44`; the fixture suite passes either way, and arm A of §2.2
  is the witness that `a6f91f7` alone also removes the recorded hold.
* **I did not extend `PassiveStepSolver.restateJunctions` to solids.** F5 restates a junction's
  *fluid* from its donors and keeps `stored.solids()`. With junctions minted clear that gap is not
  reachable from the compiler, and closing it would change the seed of every already-working solids
  fixture. It is the natural next step if a junction is ever observed carrying a population its
  donors do not deliver — for instance from a checkpoint written before `80f1b44`.
* **I did not re-capture the solver reference.** The exact gate passes at 0.000e+00 against the
  existing one; re-capturing would erase the evidence.
* **The three saved-island regression fixtures are unverified**, for the same reason as F3, F4 and
  F5: no compatible `core.dat` on this host. §6 says what that costs.
* **I did not run `fluidStressProfile`, `fluidServerBenchmark` or the module profile.** They need
  snapshots this worktree does not have, and the brief did not ask for them.
* **`documentation/` and `run/` are gitignored**, so this report, the screenshots and the client log
  are **not committed**. They live at `documentation/FULL_TANK_SOLIDS_EVENT.md` and
  `documentation/screenshots/full-tank-solids-event/`, and the report is reproduced in full in the
  final message because the worktree may be cleaned.

---

## 10. Commits

| Commit | What |
| --- | --- |
| `a6f91f7` | **Read a junction's held composition off one state, not two.** `ConservativeTransport.storedFractions`: the fluid half and the solid half of a non-solved node's mass-fraction vector both come from the candidate, with the stored inventory supplying only the population split. Two call sites — the fixed-boundary fractions and the unfed-junction row. Bit-identical where candidate and inventory agree. |
| `80f1b44` | **Mint a zero-holdup junction without the boundary's stock.** `PhysicalFluidTopology.compile`: the junction seed is the first boundary's state with its solids stripped. Bit-identical when that boundary carries none. |
| `c7da543` | **Drive a configuration event the way the world drives one.** `FullTankSolidsEventTest`: four fixtures that rebuild the island the way `FluidWorldAuthority.applyPending` rebuilds it, with the in-place boundary swap kept as the control that never reproduced any of this, and the Javadoc carrying the measurements rather than a prediction. |

No commit touches `PassiveStepSolver`, `SparseNewton`, `PhaseLayout`, `build.gradle` or the
regression reference. The temporary `FullTankSolidsEventProbeTest`, the temporary
`ConservativeTransport.PROBE` switch and its dump block were all removed before the first commit;
`git status` between commits shows only the one file each touches.
