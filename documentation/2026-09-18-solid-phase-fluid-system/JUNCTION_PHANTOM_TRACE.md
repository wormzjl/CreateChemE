# The phantom trace on a zero-holdup junction

Worktree `D:\Minecraft\Modding\1.21\CreateChemE\.claude\worktrees\agent-af48289c1308c2ea4`,
branch `claude/junction-phantom-trace`, base `0f13e31`.

**Headline.** A zero-holdup junction owns no volume, so nothing in its equations fixes the amount of
a phase — a vessel's volume closure does that, and a junction has none. Everything a pass states
about a junction was nevertheless being read off the wrong things: its species from an *undirected*
reachability closure, its composition from its own stored guess with a step of inflow blended in,
and — when the Newton failed — its species again from a *diverged* trial point. The three together
put a `1e-12` nitrogen trace on both of a filter's junctions, flashed it into a vapour phase of
2.65e-14 of the node's scale, and left the water-saturation and partial-pressure rows stated on a
vapour that is numerically nothing and that nothing delivers, so its only root was exactly zero —
which `maximumStep`'s 0.99 nonnegativity rule can only approach one percent at a time. **The fix is
one sentence applied four times: a junction's species, its composition and its donor are read off
the single point its pass starts at, and revised only by a point the Newton actually reached.** The
filter block line now runs 40/40 at 150, 200, 250, 300, 350, 400 and 600 kPa and settles where the
filter-free control settles; the valve and pumped lines are unchanged; the exact solver regression is
at 0.000e+00 against the untouched reference.

Everything below is measured. Where I predict rather than measure, I say so. One thing is **not**
fixed and is recorded in section 9: feeding solids into an already-full tank holds the island on a
different defect, in `ConservativeTransport`.

---

## 1. Setup verification

| Step | Result |
| --- | --- |
| `git checkout -b claude/junction-phantom-trace claude/tank-node-block` | done |
| `git log --oneline -1` | `0f13e31 Cover the valve block line, and record what stops the filter one now` — as required |
| Other checkouts touched | none written. `D:\Minecraft\Modding\1.21\CreateChemE` and every other worktree were read only. Four files were *copied out* of read-only references into this worktree: the MCP bridge jar from `solid-phase-fluid-system-plan-4369b0`, and `cmd.sh` / `mc.sh` / `shot.sh` / `poke.ps1` from `agent-a74f606336340ffe9`'s `build/mcp/`. `poke.ps1` still loads `Poke.class` from that reference worktree, read only. |
| `git stash` | never used, bare or otherwise. The one baseline timing comparison that needed the unmodified tree was taken by copying the three changed files to `build/base-compare/`, `git checkout --` on them, measuring, and copying them back. |
| Gradle concurrency | `Get-Process java \| Select Id,CPU` before every invocation, twice at the start across a 5 s sample to confirm the idle daemons were flat. No suite was ever run while the dev client was up; both client sessions started only after every suite, the regression, the benchmark and the GameTest server had finished, and each was stopped before anything else ran. |
| `python` in Git Bash | not used. Bash, PowerShell, Gradle and the reference worktree's two Java helpers only. |
| Commit trailer | both commits end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. |

---

## 2. Reproduction, before any change

A temporary `JunctionTraceProbeTest` (deleted before the first commit) built the filter and valve
block lines through the real `PhysicalFluidTopology` and ran each for 40 x 5 s intervals on one
retained `PassiveIntervalSolver` from `RetainedSolver.COLD_START_SECONDS`, exactly as
`FilterBlockLineIslandTest` does. The whole sweep runs in 2.4 s.

| generator | filter line, at `0f13e31` | valve line, at `0f13e31` |
| --- | --- | --- |
| 150 kPa | HELD @23, `Newton line search stalled at residual 66.6965224000565; active-set pass=1` | — |
| 200 kPa | HELD @16, `Newton iteration limit at residual 0.2976517281372205; active-set pass=0` | — |
| 250 kPa | HELD @13, `Newton line search stalled at residual 67.052061031005; active-set pass=1` | — |
| 300 kPa | HELD @10, `Newton iteration limit at residual 0.2979746558324505; active-set pass=0` | OK 40/40 |
| 350 kPa | HELD @9, `Newton line search stalled at residual 65.15472183915661; active-set pass=1` | — |
| 400 kPa | HELD @8, `Newton iteration limit at residual 0.2978784328236266; active-set pass=0` | OK 40/40 |
| 600 kPa | HELD @5, `Newton iteration limit at residual 1.6094272778967522; active-set pass=0` | OK 40/40 |

Every interval and every residual matches `TANK_NODE_BLOCK.md` section 7 to the digits it records.

---

## 3. The valve line, and why it was already safe

The brief asks what protects it. It is neither the valve closing nor the retained-pressure rule: the
trace never becomes a phase there, because **nitrogen is not in that junction's basis at all**.
Dumped from the graph each line is standing on, at 400 kPa:

```
filter line, node[1] id=3 JUNCTION      reachable={19 20}
   stored P=399999.999997 mass=9.963059e+02 nHCvap=0 wv=0 Vvap=0            vapProps=false
   seed   P=399999.999997 mass=9.963059e+02 nHCvap=5.530338e-08 wv=4.412721e-10 Vvap=3.450963e-10 vapProps=true
filter line, node[3] id=-3 JUNCTION     reachable={19 20}   (identical)
valve  line, node[1] id=3 JUNCTION      reachable={20}
   stored P=400000.000000 mass=9.963059e+02 nHCvap=0 wv=0 Vvap=0            vapProps=false
   seed   P=400000.000000 mass=9.963059e+02 nHCvap=0 wv=0 Vvap=0            vapProps=false
```

`5.530338e-08` is exactly `total * 1e-12` on a junction holding 5.530338e+04 mol. The stored state of
every junction on both lines is pure water; the trace is entirely an artefact of `initialPhaseSeeds`.

The difference is one clause in `reachableComponents`:

```java
if((directions==null||directions[edge]<-1e-14)&&pipe.control() instanceof FlowControl.Passive&&boundaryAllowed(graph,pipe,-1))
    outgoing.get(pipe.second()).add(pipe.first());
```

Upstream species transport is granted **only across a passive connection**. A `PressureValve` is an
actuator, so the tank's nitrogen cannot travel back through it, and the valve's junction — which
sits on the generator side of the valve — is water only. A filter edge is a *passive* pipe that
happens to carry a filter, so nothing stopped the closure walking the tank's nitrogen back through
it into both of the filter's junctions. The layouts that result are 3 unknowns against 6: the valve
junction carries `waterLiquid, lnT, lnP`, and the filter junction carries
`vapor[19], waterLiquid, waterVapor, lnT, lnP, lnPhc`.

The valve line is therefore the experimental proof that the narrow layout runs: it is the same block
shape, the same tank, the same pressures, and it has always passed 40/40.

---

## 4. The mechanism, with dumps

### 4.1 The stalled point

Filter line at 400 kPa, interval 8, `dt=1.0`, pass 0. Rows, against the pass's own seed:

```
row  label                                f(stalled)       f(seed)
4    n1.water-saturation                2.978786e-01  2.220446e-16
5    n1.partial-pressure-closure        3.221843e-03  0.000000e+00
16   n3.water-saturation                2.978785e-01  2.220446e-16
17   n3.partial-pressure-closure        3.221843e-03  1.455192e-16
node[1] block: sigma=[3.51e15, 7.72e8, 1.0, 0.5038, 2.84e-9, 2.40e-149]   rank 5 of 6
    with every island column: sigma=[3.51e15, 7.72e8, 1.414, 1.0, 0.5038, 2.84e-9, 2.40e-149, 0 ...]
newton direction: d[n1.vapor[19]] = -2.652252742e-14 on x = +2.652252741e-14, alpha cap = 0.98999999985
```

The seed satisfies every row to 1.175e-9. The junction's water-saturation row is
`ln(P_water / P_sat(T))` and its partial-pressure closure is
`(P_hc + P_water - P) / P`; both are stated only because `waterVaporIndex >= 0` and
`vaporActive`, and both of those are true only because the seeded trace opened a vapour phase. The
component's own row, `fraction[19]`, pins it to the incoming mass fraction, which is the junction's
stored guess whenever the arriving mass is below `ConservativeTransport.JUNCTION_INFLOW_FLOOR` — and
the stored guess has no nitrogen. So the only root is exactly zero, `Equations.maximumStep` caps
every step at `0.99 * x / -d`, the unknown falls by one percent an iteration, and the 20-iteration
limit arrives first with 0.2979 left in the saturation row. When a phase correction intervenes first
the same block is reached from a worse point and the number is 65 to 70 instead.

### 4.2 Why the previous refinement could not save it

`refineJunctionReachability` already existed, and the pass loop already called it. The pass-by-pass
trace of the failing interval shows why it did not help:

```
[probe] PASS 0 dt=1.0 reachable= j1={19,20} j3={19,20} seed j1[nHCvap=5.530338155793083E-8 size=6]
[probe] FAIL dt=1.0 pass=0 trialFlows=[3.133875725923318E-7, 3.133875725923318E-7, 1.5800949272947686E-6]
[probe]   refined -> j1={20} j3={20}
[probe] PASS 1 dt=1.0 reachable= j1={20} j3={20} seed j1[nHCvap=0.0 size=3]            <- converges
...
[probe] PASS 0 dt=1.5833e-6 reachable= j1={19,20} j3={19,20} seed j1[nHCvap=5.5303e-8 size=6]
[probe] FAIL dt=1.5833e-6 pass=0 trialFlows=[-1.137963236386765E-6, -1.137963236386765E-6, -1.137963236386765E-6]
        (no refinement: the directed closure from these flows is {19,20} again)
```

The refinement is fed `failure.lastVariables()` — a point the Newton never reached. At a stalled
point on this line the trial's flows are **-1.1379632e-6 kg/s**, on a line whose generator drives it
forwards, and the closure reads that as the tank back-feeding both junctions. So the trace was put
back at every one of the 22 refinement levels, each time from the same diverged evidence. Where the
trial happened to come out forwards, the refinement narrowed the basis and the very same step
converged at pass 1 — which is the second proof that the narrow layout is the right one.

`TANK_NODE_BLOCK.md` section 4 had already stated the rule this violates, three lines below the call
site: *a point the Newton never reached is not evidence about this pass's frozen junction donors.*
It was evidence about the basis those donors state.

### 4.3 A per-connection hydraulic estimate is not a flow pattern

With the basis directed and the diverged path closed, six of the seven pressures ran to the end and
150 kPa stopped at interval 23 on the junction's own mixing rows:

```
[probe] PASS 0 dt=1.0 reachable= j1={20} j3={19,20} rateOnly=true kinds=[GENERATOR, JUNCTION, PORT, JUNCTION]
        warmPipes=false previousFlows=[] start=[-7.474328875822778E-7, -7.205813914445969E-7, 1.6270598039991313E-5]
[probe] FAIL dt=1.0 pass=0 msg=Newton iteration limit at residual 1.8255600645614622E-4
[probe]   row  3 n3.fraction[19]         f= 1.794701e-04  seed=-2.710505e-20   x[n3.vapor[19]]= 1.650337e-04
[probe]   row  5 n3.specific-enthalpy    f= 1.825560e-04  seed= 4.793437e-10
```

That is the endpoint **rate** evaluation, on the port graph, with no history at all
(`previousFlows=[]`), so its starting point is `initialMassFlow` — which is solved one connection at
a time, against the two pressures at that connection's ends. Those ends are zero-holdup junctions
whose pressures are outputs of the previous solve carrying that solve's roundoff, and on a settled
line they differ by micropascals. The result is not a flow pattern any junction can be in: the tank
pushing **-7.2e-7 kg/s** back into the filter's outlet junction while the filter pushes **+1.6e-5
kg/s** forward into the same junction, a net imbalance of 1.7e-5 kg/s where the solve's own net-flow
row accepts about 1e-9. Reading a junction's species, its composition and its frozen donor off that
point is the same mistake as reading them off an undirected closure.

### 4.4 The seed a junction cannot travel from

At 400 kPa, interval 9, the previous accepted step ends with the tank fractionally above the
generator and the whole line genuinely carrying backwards. The basis correctly admits nitrogen and
the junction is correctly fed by the tank — and it still stalled at 66.93, on `n3.specific-enthalpy`
and `n3.fraction[19]`. The reason is the seed. `initialPhaseSeeds` modelled a junction as *its own
stored guess plus a step's worth of inflow*:

```java
double mass=Math.min(state.mass()*.25,dt*Math.abs(flow));
for(int i=0;i<count;i++)n[i]+=mass*feed[i]/incoming.mass();
```

At `dt=1 s` and 3.6e-5 kg/s that blends 3.6e-5 kg of tank fluid into a 996 kg guess. The junction's
*converged* composition is not that: it is the mixture itself, 1.54e-3 mass fraction nitrogen. The
seed was seven orders below the answer, in an unknown bounded below by zero, and the Newton had to
travel there under the same 0.99 cap. A junction owns no stock, so "its guess corrected by what
arrives" is the wrong model for it in the first place.

---

## 5. The rule, and why it cannot recur or cycle

One sentence: **a zero-holdup junction owns no volume, so its species, its composition and its donor
are all read off the single point its pass starts at, and revised only by a point the Newton
actually reached.** Four places implement it.

* **`refineJunctionReachability`, called once per pass from `startPoint`.** A junction carries the
  species the connections that point reads as donating into it can deliver — the directed closure —
  and nothing else. The `hasSolids || any filter` guard is gone: the rule is about zero-holdup
  nodes, not about filters, and a junction between two devices' outlets is passive on both sides
  whether or not a filter is anywhere near it.
* **`restateJunctions`.** A junction's seed is the mixture its donors deliver, normalized to the
  amount its guess carries and swept along the donor chain so that a junction fed by another
  junction sees what that one is about to hold. Nothing invents a trace on a junction any more. A
  vessel is untouched: it has the volume closure that fixes the amount of the phase a `1e-12` entry
  trace opens, so its trace keeps doing the job it was added for.
* **`PhaseSupport.support` / `PhaseLayout`.** A masked component with no hydrocarbon phase that
  could hold it is an omitted trace — `ABSENT`, no unknown, no row — instead of a present component
  with no phase, which is the only case the layout used to *throw* on
  (`A hydrocarbon-phase appearance pass is required`). It stays in the mask, so a later pass whose
  seed does carry it states it again.
* **`initialMassFlows`.** The hydraulic estimate a cold pass starts from contracts a maximal run of
  degree-two passive junctions to one resistance between the two nodes at its ends, which do own
  their pressures, and lays the single flow that resistance passes on every connection in it. An
  actuator is never contracted through — its setpoint and not the chain's resistance decides what it
  passes — and a junction of degree three or more is left alone, because its split needs the solve.

**Why it cannot recur.** The rule is not a threshold on the live iterate; it is the discipline the
rest of this solver already follows, extended to the last thing that was not following it. Device
modes, phase regimes, trace support, boundary closure and — since F4 — the junction donor are each
decided once per pass from the pass's own starting point and revised by the outer loop. The junction
*basis* and the junction *seed* were the two that were still being taken from whatever was to hand:
an undirected closure that ignores where the fluid is going, a diverged Newton iterate, and a
per-connection hydraulic estimate that is not a flow pattern. All three are now the same array.

**Why it cannot cycle with the frozen-donor loop.** It cannot disagree with it. `startPoint` carries
the whole edge half of `Equations.buildInitial`, and `solve` calls it at the top of every pass; the
basis, the seed and `junctionDonorFirst` are three readings of that one array. A statement and its
own consequence cannot alternate.

**Why it cannot cycle with the phase-correction loop.** `phaseCorrection` replaces seeds with flashed
states and touches neither `reachable` nor any flow, so the next pass's `sameTransport` test is
unchanged and the junction statement does not move. Likewise `reactivate`.

**Why the pass sequence stays finite.** `reachable` and the junction seeds are written in exactly one
place, guarded by `sameTransport(stated, start)`. `start` is a function of the graph, the modes, the
boundary closure and `previousFlows`; none of those changes except in the `changed` branch, which is
an active-set change like any other. So between two active-set changes the statement is made at most
once and then returns false, and the active set itself is the cycle key `seen`, with
`maximumPasses` behind it. `junctionsTurned` is the revision path and is deliberately answered on
the **basis** and not on the flows: two points that classify every connection the same way cannot
produce different bases, so the classification is only the screen, and when it fires the closure is
built and the bases compared. Answering on the classification alone reports a change that restates
nothing, and the pass after it rebuilds the same active set and trips the cycle guard — measured,
as `Phase/device active-set cycle` on every swept pressure, before the test was corrected.

### Two rules that were implemented, measured and reverted

* **A continuity screen on the basis.** "A start point that does not balance a junction says nothing
  about what that junction is mixing" — junctions whose net flow misses zero by more than
  `1e-8*(1+scale)` keep the mixture they hold. It fixes 150 kPa (1.83e-4 -> 2.57e-5) and **breaks
  350 kPa**, which went from 40/40 to HELD @9 on `n3.specific-enthalpy` at 2.41e-4. The reason is
  instructive: the screen removes the tank from the junction's *basis* while
  `junctionDonorFirst` still has the tank *donating*, so the enthalpy row asks a water-only junction
  to hold a stream it has no composition for. It breaks the very property section 5 rests on.
  Reverted; `initialMassFlows` fixes the same start point at its source instead.
* **Restating the junctions as a `continue`.** The first working version discarded the pass and ran
  again, rebuilding *all* the seeds through `initialPhaseSeeds`. It throws away the converged states
  that an active-set change had just installed as seeds, and it cost
  `aFilterBlockCostsAPumpedLineNoIntervalOfItsOwn` its control line: HELD at interval 17 on
  `Newton iteration limit at residual 4.1938299879665043E-7`, a pump island with no filter and no
  solids in it. Moving the statement above the layouts, and restating only the junctions, restores
  it. The pumped control line is the fixture that catches this; it is why the statement is not a
  `continue`.

---

## 6. Qualification

The rule is gated on the island having a zero-holdup junction, at all four sites:

| Change | Gate |
| --- | --- |
| `refineJunctionReachability`, `restateJunctions`, `junctionsTurned` | `hasJunction` |
| junction seeding | `restateJunctions` returns its argument when the island has no junction |
| `initialMassFlows` chain contraction | returns the per-connection estimate when no degree-two passive junction exists |
| `PhaseSupport.ABSENT` for a masked component with no phase | replaces a case that could only ever throw |

So an island without a junction is bit for bit what it was, and that is what the gate measures:

```
fluidSolverRegression -PfluidRegressionMode=exact
chain-100 vs reference: max deviation: state/moles 0.000e+00 relative (-), temperature 0.000e+00 K (-),
                        phase fraction 0.000e+00 (-), flow 0.000e+00 relative (-)
BUILD SUCCESSFUL
```

The reference was **not** re-captured and was not touched. The F3 relative-gate discipline is
therefore not needed: there is nothing to quote.

Three saved-island fixtures SKIP (`quiet-11312`, `quiet-11324`, `cold-11312` — "no readable
core.dat island"), exactly as they did for the previous two reports; no compatible `core.dat` exists
on this host. Only the synthetic 100-reservoir chain was replayed. **That is a real gap**: the
regression's only executed gate is a junction-free island, which is precisely the case this change
is designed not to move, so the exact-zero result confirms the gating and says nothing about the
islands it does move. Those are covered by the fixtures in section 7 instead.

---

## 7. Verification

Sequentially, one Gradle invocation at a time, `Get-Process java` checked before each.

| Check | Result |
| --- | --- |
| `fluidScienceTest` | **161 tests, 0 skipped, 0 failures** |
| `fluidRuntimeTest` | **117 tests, 0 skipped, 0 failures** — the filter fixture is re-enabled, so the skip is gone |
| `fluidSolverRegression -PfluidRegressionMode=exact` | PASS at 0.000e+00 on all four gates, reference untouched (section 6) |
| `fluidNetworkBenchmark` | **30/9, 19/14, 37/3 — unchanged**, all three CONVERGED |
| `runFluidGameTestServer -PfluidGameTestRunId=junction-trace-01` | **All 20 required tests passed**, 5.146 s |
| filter line 40/40 at 150, 200, 250, 300, 350, 400, 600 kPa | **PASS** — `filterLineToATankKeepsIntegratingOnceTheTankIsFull`, now a sweep with endpoint assertions |
| valve line 40/40 at 300, 400, 600 kPa | PASS, unchanged |
| pumped lines 40/40 (`aFilterBlockCostsAPumpedLineNoIntervalOfItsOwn`) | PASS, unchanged |
| filter line with solids to a void (three fixtures) | PASS, unchanged |
| `SolidRuntimeTest.physicalPumpFilterAndNitrogenReceiverStartTogether` | PASS |
| `SolidChainTransportTest` | PASS, 4 tests |

The re-enabled fixture's endpoints, against the filter-free control at the same pressure:

| generator | filter tank P | control tank P | filter tank mass | control tank mass |
| --- | --- | --- | --- | --- |
| 150 kPa | 150000.00005869 | 150000.00118632 | 309.920178059 | 309.920183316 |
| 200 kPa | 199999.99987363 | 200000.00014703 | 484.540333112 | 484.540333551 |
| 250 kPa | 249999.99995550 | 250000.11085530 | 588.454937362 | 588.455120273 |
| 300 kPa | 300000.00009209 | 299999.99983223 | 657.380525610 | 657.380525702 |
| 350 kPa | 350000.00066249 | 350000.00009464 | 706.447314604 | 706.447314580 |
| 400 kPa | 400000.00021323 | 400000.00019801 | 743.161256009 | 743.161256456 |
| 600 kPa | 600000.00042398 | 600000.00044219 | 828.578624310 | 828.578624667 |

Worst relative gap: 4.4e-7 on pressure (250 kPa) and 3.1e-7 on mass (250 kPa). The fixture asserts
both at 1e-6, the same bound the valve fixture uses.

### Timings, three samples each, against the unmodified base measured in the same session

| Fixture | base | with the fix |
| --- | --- | --- |
| `SolidRuntimeTest.physicalPumpFilterAndNitrogenReceiverStartTogether` | 0.640, 0.666, 0.674 s | **0.317, 0.321, 0.339 s** |
| `SolidChainTransportTest` (4 tests) | 0.698, 0.733, 0.728 s | 0.723, 0.735, 0.739 s |

The pumped filter fixture is twice as fast and the sets do not overlap; the chain fixture is
indistinguishable. I did not instrument the cause, so this is a measurement and not an explanation —
the plausible reading is that a junction that no longer carries an invented trace has a three-unknown
layout instead of six, and no flash to build it. The base numbers were taken by copying the three
changed files to `build/base-compare/`, `git checkout --`, measuring, and copying them back; no
stash.

---

## 8. In-game confirmation

Dev client via the langyo/minecraft-mod-mcp bridge (jar copied into `run/mods/`,
`pauseOnLostFocus:false` written to `run/options.txt`), over `localhost:9876/api/cmd`. Creative
superflat world, peaceful, `doDaylightCycle false`, the line placed with `/setblock` at **y = -59**:
`generator(0) - pipe(1) - inline_filter(2, facing=east) - pipe(3) - fluid_reservoir(4)`, all at z=0.
Screenshots in `documentation/screenshots/junction-phantom-trace/`, alpha-flattened; the client log
is beside them as `client-latest.log`.

| Check | Result | Screenshot |
| --- | --- | --- |
| line built at y=-59 | generator, pipe, filter, pipe, reservoir | `04-filter-line-placed.png`, `22-filter-line-overview.png` |
| generator at 400 kPa, clear water | `Pressure 400.00 kPa abs`, `Flow (last) 40.41 kg/s`, `L 0 W 100 V 0 S 0 %`, `Settings accepted at the current simulation event.` | `06-pressure-typed.png`, `07-generator-applied.png` |
| the tank fills | `FULL`, `400.00 kPa abs`, `298.24 K`, `743.16 kg`, `W 74 V 26 %` — the fixture's 400000.00021 Pa / 743.161256 kg / 298.2418 K | `08-tank-full.png` |
| **the island keeps SOLVING with the tank full** | **YES.** `Lag 2.70 s` on first inspection, **`Lag 0.10 s`** two minutes later. Not growing; falling. | `08-tank-full.png`, `09-tank-still-solving.png` |
| `run/logs/latest.log` has no `status=HELD` | **zero records** across the whole clear-water run, from placement at 04:11 to 04:19 | `client-latest.log` |
| 5 % `createcheme:demo_particle` / 100 µm | accepted — `L 0 W 95 V 0 S 5 %`, `Settings accepted at the current simulation event.` — and the island **held immediately** on `Junction mass continuity does not close` | `12-solids-filled.png`, `13-solids-applied.png` |
| the filter captures | **NO.** `Load 0.00 %`, `Captured 0.00 kg`, `Flow 0.00 kg/s`, `Lag 108.30 s`; `Recover solids` is greyed out because there is nothing to recover | `18-filter-block.png` |
| recovery by setting the feed back to 0 % | **NO.** `Settings accepted`, then `WAITING: configuration event / HELD: Junction mass continuity does not close`, lag climbing 167 -> 204 -> 323 s. A held island cannot reach the configuration event that would clear it. | `19-generator-clear-again.png`, `20-generator-clear-applied.png`, `21-recovery-attempt.png` |
| client stopped, no Minecraft java process left | world saved with `save-all`, both `runClient` sessions stopped; `Get-CimInstance Win32_Process` matching `devlaunch\|neoforge\|Minecraft` returns nothing, and the remaining java processes are Gradle daemons | — |

The two numbers that matter are the same line at the same pressure, before and after: at `0f13e31`
the filter line with a full tank sat at `Lag 177.05 s` with 154 `status=HELD` records naming its
island; here it sits at `Lag 0.10 s` with none.

**Deviation from the brief's script.** The brief asks, after the tank fills, to set 5 % solids,
confirm capture, press `Recover solids`, and confirm the line still solves. The first half cannot be
done in that order: a full tank carries no flow, so a generator that starts feeding solids into it
delivers none, the filter captures nothing, and there is nothing to recover. What happens instead is
section 9. The previous report hit the mirror image of the same geometry from the other side — with
solids from the start, the filter clogs at 25 kg and the tank never fills — and the fixture
reproduces both of those exactly (below).

---

## 9. What I did not fix, and why

### 9.1 Solids fed into an already-full filter line

**Not fixed, and not attributed.** In-game, applying a 5 % solids feed to a line whose tank is
already full holds the island immediately and permanently:

```
[04:19:28] fluid_island=12 committed_tick=10674 status=HELD: Junction mass continuity does not close
   ... 123 identical records, one per second, no other message in the log
```

The throw is `ConservativeTransport.java:191`:

```java
if(Math.abs(total-1)>1e-8)throw new SparseNewton.Nonconvergence("Junction mass continuity does not close");
```

`total` is a junction's reconstructed mass fractions, fluid components plus solid populations,
summed. For the junction on the far side of a filter the fluid weight is
`|q| / incoming[receiver]`, and `incoming` was accumulated as the **delivered** rate,

```java
double deliveredRate=pipe.filter()==null?massRate:massRate*(1-candidate.get(donor).solidMoments().mass()/candidate.get(donor).mass());
```

so the weight is `1/(1-s)` and it is exactly the factor that makes the fluid fractions close to 1
against a donor whose own fluid fractions sum to `1-s`. But `s` there is the **candidate's** solid
fraction — the Newton's — while the donor's `fractions[donor][components+c]` are the ones this
reconstruction is solving for. When the two disagree, the sum misses 1 by the difference. The
comment at `ConservativeTransport.java:138` shows the author of that code meeting the same corner
from the other side and fixing only the unfed case.

What I can say about attribution, precisely:

* The throw is in `ConservativeTransport`, which neither commit here touches.
* The state it is reached from — a quiet filter line standing on a full tank — is a state the base
  cannot reach at all, because that is the state this fix exists to make reachable. So I cannot call
  it pre-existing.
* I could not reproduce it through `PassiveIntervalSolver` in a fixture. A temporary probe
  (`SolidsIntoAFullTankProbeTest`, deleted) built the same island, filled the tank on clear water,
  swapped the generator's boundary state for a 5 % slurry at intervals 1, 2, 3, 4, 5, 6, 7, 8, 10,
  15, 20 and 30, and ran 60 intervals: **OK at every switch point**, with the retained solver kept
  or reset. Which makes the difference something in the runtime layer — the world authority's
  configuration event, the retained solver's cadence, or the approximate-acceptance fallback — and
  not the step solver. The probe did reproduce both of the previous report's in-game solid numbers
  bit for bit, which says it is building the right island: switching at interval 1 clogs the filter
  at `captured=24.999999999975 kg` and stalls the tank at 169941 Pa, and a slurry generator from the
  start stalls it at **128281.41 Pa / 190.443 kg** against the previous report's in-game
  `128.28 kPa / 190.44 kg`.

So: a real, separate, reproducible-in-game defect, with a located throw site and a first analysis,
left for its own track. It does not touch the clear-water path this brief is about.

### 9.2 Other things I did not do

* **I did not re-capture the solver reference.** The exact gate passes at zero against the existing
  one; re-capturing would be a no-op that removes the evidence.
* **I did not extend the chain contraction to junctions of degree three or more.** A tee's split
  needs the solve, not an estimate, and no fixture here builds one. If a hold ever appears on a
  branched junction island, that is the first thing to look at: its basis would still be stated from
  a per-connection estimate that need not balance it.
* **I did not fix the normalisation mismatch** between `junctionInflow`'s two branches that
  `TANK_NODE_BLOCK.md` section 4 records. Still latent, still only one branch live at a time.
* **I did not run `fluidStressProfile`, `fluidServerBenchmark` or the module profile.** They need
  snapshots this worktree does not have, and the brief did not ask for them.
* **The three saved-island regression fixtures are unverified**, for the same reason as the previous
  two reports: no compatible `core.dat` on this host. Section 6 says what that costs.

---

## 10. Commits

| Commit | What |
| --- | --- |
| `38bfa83` | **State a zero-holdup junction's species from what its donors deliver.** `PassiveStepSolver`: `startPoint` extracted from `Equations.buildInitial`; `initialMassFlows` with chain contraction; `restateJunctions`; the per-pass statement of `reachable` and the junction seeds; `junctionsTurned`, `transportDirection`, `sameTransport`; the diverged-trial refinement removed; `refineJunctionReachability` un-guarded and re-documented. `PhaseLayout`: a masked component with no phase to hold it is an omitted trace. This is the defect and the whole of the fix. |
| `04ad5ca` | **Hold the filter block line to forty intervals at every swept pressure.** `FilterBlockLineIslandTest`: `filterLineToATankKeepsIntegratingOnceTheTankIsFull` un-disabled and turned into a seven-pressure sweep against the filter-free control, with the tank's pressure and mass asserted at 1e-6; the Javadoc keeps the reproduction and records which rule removed it. |
| — | This report and the in-game screenshots are **not committed**: `documentation/` is in `.gitignore`, as it was for `HYDRAULIC_ROW_SCALE.md`, `PUMP_SHUTOFF_ACTIVE_SET.md` and `TANK_NODE_BLOCK.md`. They live at `documentation/JUNCTION_PHANTOM_TRACE.md` and `documentation/screenshots/junction-phantom-trace/`, and the whole report is reproduced in the final message because the worktree may be cleaned. |

No commit changes `ConservativeTransport`, `SparseNewton`, `build.gradle` or the regression
reference. The temporary `JunctionTraceProbeTest`, the temporary `SolidsIntoAFullTankProbeTest`, the
temporary `PROBE` switch and dump helpers on `PassiveStepSolver` and the temporary label methods on
`PhaseLayout` were all removed before the first commit; `git status` between the two commits shows
only the file each one touches.
