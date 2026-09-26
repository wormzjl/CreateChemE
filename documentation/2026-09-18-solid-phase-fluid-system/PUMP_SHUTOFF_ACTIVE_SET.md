# A pump at its own shutoff held its island

Worktree `D:\Minecraft\Modding\1.21\CreateChemE\.claude\worktrees\agent-af5232d111e7302fa`,
branch `claude/pump-shutoff-active-set`.

## 1. Setup verification

| step | result |
| --- | --- |
| `git checkout -b claude/pump-shutoff-active-set claude/hydraulic-row-scale` | `Switched to a new branch` |
| `git log --oneline -1` | `bdc113c Record what the driven filter line stops on now that the row is scaled` |
| main checkout and sibling worktrees | never written; the only reads were the read-only references named in the brief |
| `git stash` | never used |
| Gradle concurrency | every invocation preceded by a `java.exe` CPU-delta check; only one Gradle ran at a time, and no suite ran while the dev client was up |

Commits on top of `bdc113c`:

```
ae3b37c Hold both pumped filter lines to forty intervals and to the shutoff state
eb25fc1 Stop a pump at its own shutoff from holding its island
```

`documentation/` is gitignored in this repository, so this report and the screenshots are not
committed; they live in the worktree and are reproduced in the handoff message.

## 2. Reproduction

A standalone harness (temporary, deleted) built the brief's control line through the real
`PhysicalFluidTopology` and ran 40 consecutive 5 s intervals on one retained
`PassiveIntervalSolver` starting from `RetainedSolver.COLD_START_SECONDS`, with the solver
instrumented per active-set pass and per Newton iteration.

The line compiles to **three nodes and two edges**, not to a chain of pipe nodes:

```
node[0] id=1 GENERATOR  y=-59  P=101325 Pa  (fixed)   V=1 m3, liquid-full water
node[1] id=2 JUNCTION   y=-59  zero holdup                (the pump block's own node)
node[2] id=6 RESERVOIR  y=-59  P=101325 Pa  V=1 m3, nitrogen
pipe[0] 0->1 Passive        (the generator-to-pump connection)
pipe[1] 1->2 Pump(.01 m3/s, 500000 Pa max added, efficiency 1)   (the pump and its three pipes)
```

The tank fills over sixteen intervals and the line stops in the seventeenth:

```
=== INTERVAL 16 t=75 s step=10 s tankP=407848.816 deficitToShutoff=193476.184 liquidFraction=0.7498   OK
=== INTERVAL 17 t=80 s step=10 s tankP=508549.194 deficitToShutoff= 92775.806 liquidFraction=0.7997
    HELD at interval 17: Substep refinement exhausted: Newton iteration limit at
                         residual 1.661037194510301E-7; active-set pass=1
```

Same interval and same failure class as the brief; the residual differs
(1.661e-7 here against the 2.632e-6 the brief quotes from
`FilterBlockLineIslandTest`), because the fixture runs the filtered line on the same
`FluidThermodynamics` instance before the control line and the harness does not. Nothing in the
diagnosis turns on the digit.

### 2.1 Per-pass numbers

A representative attempt inside interval 17, at `dt = 0.366117 s`
(`Pa` is the junction, `Pb` the tank, `margin = 500000 - (Pb - Pa)`):

| pass | mode in | flow kg/s | head Pa | P junction | P tank | tank - shutoff | decided |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 0 | PUMP_TARGET | 9.960056 | 534261.52 | 98693.50 | 614534.52 | +13209.5 | PUMP_HEAD_LIMIT |
| 1 | PUMP_HEAD_LIMIT | **-0.02799** | 500000.00 | 101325.08 | 601325.65 | **+0.65** | CLOSED (**not applied**) |
| 2 | PUMP_HEAD_LIMIT | 0.000000 | 500000.00 | 101369.17 | 601369.17 | +44.2 | no change, accepted |

Counts over the whole baseline run of interval 17:

```
PUMP_TARGET     -> PUMP_HEAD_LIMIT     77
PUMP_TARGET     -> PUMP_TARGET          6
PUMP_HEAD_LIMIT -> PUMP_HEAD_LIMIT     53
PUMP_HEAD_LIMIT -> CLOSED              12      <- decided twelve times
CLOSED          -> anything              0      <- the pump never once ran in CLOSED
```

The terminal twenty consecutive rejections, as the controller halves the step from 0.031 s to
2.8e-10 s:

```
FAIL dt=3.13e-02 pass=1 Newton iteration limit at residual  8.551384e-09  flows=[1.0627135, 1.0627135]
FAIL dt=1.82e-02 pass=1                                     2.689173e-07  flows=[0.0543190, 0.0543190]
FAIL dt=1.78e-05 pass=1                                     1.681152e-07  flows=[0.2242703, 0.2242703]
FAIL dt=3.48e-08 pass=1                                     1.661037e-07  flows=[0.2245866, 0.2245866]
```

Both the residual and the flow converge to constants as `dt -> 0`. **The stall is step-size
independent**, which is why twenty halvings cannot reach it.

### 2.2 The decisive Newton trace

At the first failure of the interval (`dt = 1.46447 s`, pass 2):

```
NEWTON it=18 alpha=1.0000    norm=1.244516e-07 next=1.684342e-03 worstRow=1  descent=false
NEWTON it=18 alpha=0.5000    norm=1.244516e-07 next=1.684342e-03 worstRow=1  descent=false
NEWTON it=18 alpha=0.2500    norm=1.244516e-07 next=1.684342e-03 worstRow=1  descent=false
   ... eleven more halvings, all with the identical candidate residual ...
NEWTON it=18 alpha=0.000122  norm=1.244516e-07 next=1.244364e-07 worstRow=10 reduction=0.9999 descent=true
FAIL dt=1.46447 pass=2 ... modes=[PASSIVE, PUMP_HEAD_LIMIT] flows=[0.0, -1.0000215085702656E-14]
```

A candidate residual that does not change with the step length is a discontinuity, not a
curvature. Row 1 belongs to the junction's mixing block, and the flow sits at
`-1.0000215085702656E-14`: `ConservativeTransport.JUNCTION_INFLOW_FLOOR` is exactly `1e-14`.
`junctionInflow` switches between the incoming mixture and the stored guess at that floor, and the
donor upwinding switches at zero, so the junction's mass-fraction, enthalpy and normalization rows
are discontinuous exactly where this flow is parked. Newton cannot converge there at any step size.

## 3. The mechanism: what is confirmed and what is refuted

**Refuted, as a numerical claim.** There is no `PUMP_HEAD_LIMIT` / `CLOSED` chatter anywhere in the
baseline run. The pump never entered `CLOSED` even once (zero `CLOSED -> ...` lines). The reverse
flows that fired the `flows[i] < -1e-10` test were macroscopic - 0.017, 0.028, 0.052, 0.064, 0.41,
0.68, 0.92, 1.14, 2.46 kg/s - not 1e-10 noise. And the two transitions are not each other's inverse:
mapped onto the one scalar they both read,

```
margin = maximumAddedPressure - (P_discharge - P_suction + rho*g*dz)
```

the old close test is `margin < ~ -1e-9 Pa` (a reverse flow of 1e-10 kg/s through a
~5.8 Pa/(kg/s) connection) and the old reopen test is `margin > +0.01 Pa`. Those two acceptance
regions are disjoint and correctly ordered, so the pair is already a hysteresis band and **cannot
cycle on its own**.

**Confirmed, as a principle.** The brief's rule - *a mode switch must not be decided by a quantity
the current solve cannot resolve* - is exactly what is violated, and it is violated in a way that
loses the transition rather than repeating it. The failure chain, all five steps measured:

1. The tank reaches the pump's shutoff head, 101325 + 500000 = 601325 Pa.
2. Every solve restarts the pump on `PUMP_TARGET` (the mode list is rebuilt at the top of
   `PassiveStepSolver.solve`), which pins the flow to 9.96 kg/s and drives the tank 2600-13000 Pa
   past shutoff. The head-limit pass that follows therefore converges to a **reverse** flow.
3. A reverse flow is illegal at a `GENERATOR`, so `boundaryAllowed` fails on pipe[0]. Pipe[0] is
   scanned before the pump edge and the pass applies **one** active-set change, so the boundary
   closure is taken and the pump's own `CLOSED` decision - made in the same pass - is discarded.
   `boundaryClosed` is never cleared again within a solve.
4. With the suction edge closed, the junction's mass balance forces the pump edge's flow to zero.
   `flows[i] < -1e-10` is then false (the flow is `-1e-14`), so the pump is never offered `CLOSED`
   again. It is left holding its head limit with the flow parked on the junction's upwind and
   `JUNCTION_INFLOW_FLOOR` discontinuities, and the Newton stalls there.
5. The interval solver halves the step twenty times. The stall is a property of the point, not of
   the step, so refinement cannot help, and the island is held for good.

A second, independent failure sits behind the first and only becomes visible once it is fixed: the
`PUMP_TARGET -> PUMP_HEAD_LIMIT` warm start hands the head-limit Newton the target flow it has just
refused. Near shutoff that is 9.96 kg/s against an answer of 0.07, with a power-law loss in between
(turbulent at 9.96 kg/s, Reynolds ~253000; laminar at 0.07, Reynolds ~1900), so Newton walks down
it by roughly halving the flow per iteration. The measured reduction ratios degrade 0.14, 0.49,
0.65, 0.73, 0.78, 0.81 between Jacobian refreshes and the walk does not fit in twenty iterations -
again at every step size.

## 4. The rules

### R1 - a device's own mode is decided before the connection its reverse flow closes

An illegal direction on a passive connection next to a device that has just decided it cannot run
in its current mode is that device's *symptom*. The pass applies one active-set change, so taking
the symptom first discards the cause, and `boundaryClosed` is permanent within a solve. The
boundary closure is now recorded and applied only if no device wanted to move in the same pass.

For an island with no actuators this is bit-identical: for a passive connection the pump/valve
branch cannot produce `next != mode`, so the only change available is still the boundary closure,
still on the first violating edge. `fluidSolverRegression -PfluidRegressionMode=exact` (chain-100,
no pumps) confirms zero deviation.

### R2 - the head-limit / closed pair is decided on one resolved margin

Both tests now read the same scalar - `margin = maximumAddedPressure - demand`, with
`demand = P_discharge - P_suction + rho*g*dz` from the pass's converged states - against one band:

```java
band = max(0.01 Pa, tolerance * pressureScales[edge])

PUMP_HEAD_LIMIT -> CLOSED           iff margin < band
CLOSED          -> PUMP_HEAD_LIMIT  iff margin > band   and the edge is not latched
```

The band is the pressure that edge's own hydraulic row is actually converged to: the
pressure-dimensioned rows are stated in `Equations.pressureScales`, so a solve converged to
`tolerance` has decided them to `tolerance * scale` Pa - 1e-4 Pa on an atmospheric island, 6e-4 Pa
at 6 bar. Through the line's ~5.8 Pa/(kg/s) that is a flow resolution of order 1e-5 kg/s, against
the 1e-10 kg/s the old rule closed on. The 0.01 Pa floor is the band the `CLOSED` side already
used; keeping it means the reopening test is bit for bit the test it was on every island whose
scale is at or below 1e5 Pa, which is every island the regression reference was captured on.

`checkApproximation`'s mirrored pump-feasibility probe reads the same band instead of a literal
0.01 Pa, so an approximate solve cannot refuse a point the mode rule just accepted.

### Why it cannot cycle

The shutoff corner is **degenerate**: head at the maximum and zero flow satisfy both modes'
equations at once. `CLOSED` is its well-posed representative, because it pins the flow to exactly
zero through the actuator row instead of leaving the solve to find zero by iteration - which is what
keeps a zero-holdup junction off its upwind and `JUNCTION_INFLOW_FLOOR` discontinuities.

A single band on a single scalar is not by itself enough, and this was **measured, not assumed**.
`margin` is the same expression in both modes but not the same physical quantity, because the two
modes converge to different states: in `PUMP_HEAD_LIMIT` it is the pump edge's own loss, in `CLOSED`
it is the whole path's driving pressure at zero flow. On the filtered pumped line, where the filter
edge carries ~63x the pump edge's resistance, the observed pair was

```
pass=1 CLOSED           q=0          head=499999.214  margin=0.786 Pa  -> reopen
pass=2 PUMP_HEAD_LIMIT  q=8.56e-04   head=500000.000  margin=0.010 Pa  -> close
pass=3 Phase/device active-set cycle
```

So the pair is closed with an explicit anti-cycling latch, which is the standard device for a
degenerate active set:

* a pump closed by the corner rule is latched for the remainder of that solve and may not reopen;
* the latch is released in exactly one place - the top of the **next** solve, where a carried
  `CLOSED` pump is re-tested against the accepted start-of-step state.

Within one solve a pump can therefore take `PUMP_HEAD_LIMIT -> CLOSED` at most once and
`CLOSED -> PUMP_HEAD_LIMIT` at most once, and after the former the latter is disabled. The pass
sequence on this pair is bounded by two transitions per pump, so it terminates; the release test is
taken once per solve against a state the pass sequence cannot change, so it is not a transition the
sequence can take again. The existing `seen`/`WorkspaceKey` cycle detector remains as a backstop and
no longer fires on this line.

### R3 - a head-limited pump is seeded from the limit it is holding

`Equations.initial` now seeds a `PUMP_HEAD_LIMIT` edge by bisecting the head-limit hydraulic balance
on the start-of-step states - the same bisection a passive connection already used, with the pump's
maximum added to the driving pressure - whenever the carried head is above the maximum, i.e. the
carried point was not itself on the limit. A point already on the limit (the previous pass, or a
settled island's previous accepted step) carries its own flow forward exactly as before.

### R4 - the device active set is carried across solves

A pump standing on its head limit, or at its shutoff corner, is a property of the island's state and
not of one step. `previousModes` now carries `PUMP_HEAD_LIMIT` and `CLOSED` into the next solve on
the same graph; the target branch and the velocity clamp are still decided against the new step's
own states, and a presentation-only accepted mode is never carried.

### Ablation

Each rule disabled on its own, the other three active, on the control line:

| disabled | outcome |
| --- | --- |
| *(none)* | **OK 40/40**, tank 601324.9912 Pa |
| R1 actuator-first | OK 40/40, but the tank ends at 601325.9096 Pa - 0.91 Pa **above** shutoff, on the spurious permanent suction closure |
| R2 corner band | **HELD** interval 17: `Newton line search stalled at residual 1.5675529750828925E-9; active-set pass=0` |
| R3 head-limit seed | **HELD** interval 17: `Newton iteration limit at residual 4.385381497390758E-7; active-set pass=0` |
| R4 carried modes | **HELD** interval 17: `Newton iteration limit at residual 2.300934990649718E-6; active-set pass=1` |

R2, R3 and R4 are each individually necessary. R1 is not necessary for the line to survive, but it
is what makes the answer right rather than merely finite, and it removes a permanent, spurious
`boundaryClosed` on the suction edge.

## 5. The valve decision: no change

The valve transitions have the same *shape* - `VALVE_REGULATING`/`VALVE_OPEN` close on
`flows[i] < -1e-10`, a threshold as far below the row's resolution as the pump's was. They were
examined and left alone, for reasons that are measured rather than argued:

* A valve corner fixture was built: generator at 400 kPa - pipe -
  `PressureValve(200000)` - two pipes - tank, run for 40 intervals the same way. It **holds at
  interval 6**, with the tank at 387430 Pa. That is 187 kPa *above* the valve's 200 kPa target, the
  failure is at `active-set pass=0` with no mode transition taken, and the message is
  `Newton line search stalled at residual 1.000000082240371E-9` against a 1e-9 tolerance.
* Running the same fixture with **all four rules disabled** gives
  `HELD at interval 6 ... residual 1.0000085199353496E-9; active-set pass=0` - the same interval,
  the same pressure, the same residual to six figures.

So that line stops on the separate, pre-existing tank node-block defect recorded in
`documentation/HYDRAULIC_ROW_SCALE.md` section 7 and in `FilterBlockLineIslandTest`'s own comment
("the tank's nitrogen vapour/liquid equilibrium row and its volume closure, which floor at 1.0e-9 to
1.4e-9 against the 1e-9 tolerance"), and it stops before the valve ever reaches a corner. I did not
conflate the two, and I could not construct a case where a valve's own active set fails.

Reasoning about why the shapes differ, offered as a hypothesis and not as a measurement: a valve at
its corner is not degenerate the way the pump is. `VALVE_OPEN` pins the head to zero, which is what a
wide-open passive connection already does, and the passive lines that park at a vanishing flow
against a full tank - `filterFreeLineToATankKeepsIntegratingOnceTheTankIsFull` at 400 kPa and
`aPassiveLineAtThePumpsShutoffPressureFillsTheSameTank` at 601325 Pa - both run 40/40. Tightening the
valve's close tests to a resolved reverse flow would be a strict subset of today's closures, i.e. a
change with no demonstrated benefit and some risk, so it was not made. **This is the weakest claim in
this report**: if a valve island is later found to hold at a regulating corner, R2 is the shape to
apply to it.

## 6. Verification

Run sequentially, one Gradle at a time, each preceded by a `java.exe` CPU-delta check; no suite ran
while the dev client was up.

| check | required | measured |
| --- | --- | --- |
| `fluidScienceTest` | 161 green | 161 tests, 0 failures, 0 errors, 0 skipped |
| `fluidRuntimeTest` | 116 green, 1 skipped | 116 tests, 0 failures, 0 errors, 1 skipped |
| the skipped fixture | stays the filter-to-tank node-block one | `FilterBlockLineIslandTest.filterLineToATankKeepsIntegratingOnceTheTankIsFull`, still `@Disabled` |
| `fluidSolverRegression -PfluidRegressionMode=exact` | zero deviation | `chain-100 vs reference: max deviation: state/moles 0.000e+00 relative, temperature 0.000e+00 K, phase fraction 0.000e+00, flow 0.000e+00` |
| `fluidNetworkBenchmark` | 30/9, 19/14, 37/3 | `acceptedSubsteps=30, rejectedSubsteps=9` / `19, 14` / `37, 3` |
| `runFluidGameTestServer -PfluidGameTestRunId=pump-shutoff-01` | 20 passing | `========= 20 GAME TESTS COMPLETE IN 5.793 s` / `All 20 required tests passed :)` |

### Timing

`SolidRuntimeTest.physicalPumpFilterAndNitrogenReceiverStartTogether`, three samples each, same
tree, the only difference being `PassiveStepSolver.java` at `bdc113c` versus fixed:

| | s1 | s2 | s3 | mean |
| --- | --- | --- | --- | --- |
| before | 0.560 | 0.565 | 0.551 | 0.559 s |
| after | 0.571 | 0.581 | 0.592 | 0.581 s |

+3.9 %. The extra cost is the head-limit bisection (50 iterations of `pressureDrop` per pump edge
per solve) plus the carried-mode re-test; the suite-level wall times did not move measurably.

### The fixture

`aFilterBlockCostsAPumpedLineNoIntervalOfItsOwn` now asserts `OK` for both pumped lines and adds an
endpoint check against the passive-at-shutoff reference:

```
(ii)        generator-pump-pipe-filter-pipe-tank : OK intervals=40 ...
(control)   generator-pump-3 pipes-tank          : OK intervals=40 ...
(reference) passive generator at 601325 Pa       : OK intervals=40 ...
(endpoint)  pumped  tank P=601324.9911504235 mass=829.0195909460377 T=298.21485878094234
(endpoint)  passive tank P=601325.0002008874 mass=828.9544530255172 T=298.2920489296276
```

* pressure against the pump's own shutoff, 101325 + 500000: **1.5e-8 relative**, inside the declared
  1e-6;
* pressure against the passive reference: **1.5e-8 relative**, inside 1e-6;
* mass against the passive reference: **7.9e-5 relative**, asserted at 1e-4, not 1e-6.

The mass tolerance is loosened deliberately and the reason is physical, not numerical: the two lines
do not reach 601325 Pa by the same energy path. The reference is fed 298.15 K water already at
601325 Pa; the pumped line is fed 298.15 K water at 101325 Pa and the pump raises it, and the tank
ends **0.077 K colder** (298.215 K against 298.292 K), which is a few times 1e-5 of water density on
its own. Holding the inventory to 1e-6 would be a statement about the two feeds' enthalpies, not
about the pump's active set. The pressure check, which is the one the brief asks for, is met at 1e-6
in both forms.

## 7. In-game confirmation

`minecraft-mcp-1.21.1-neoforge-v0.3.0.jar` copied into `run/mods/`, `pauseOnLostFocus:false` written
to `run/options.txt`, `./gradlew.bat runClient --offline` started only after every suite had
finished, and the bridge driven over `http://localhost:9876/api/cmd`. A fresh creative world with
commands on; the line built at y=-59 with `/setblock`, which goes through `FluidDeviceBlock.onPlace`
and therefore through `FluidWorldAuthority.place`, so the devices carry the world's own defaults -
`FlowControl.Pump(.01, 500000, 1)` and a 101325 Pa nitrogen reservoir, identical to the fixture:

```
/setblock -202 -59 -374 createcheme:fluid_generator[facing=north]
/setblock -201 -59 -374 createcheme:fluid_pump[facing=east]
/setblock -200 -59 -374 createcheme:fluid_pipe[facing=north]
/setblock -199 -59 -374 createcheme:fluid_pipe[facing=north]
/setblock -198 -59 -374 createcheme:fluid_pipe[facing=north]
/setblock -197 -59 -374 createcheme:fluid_reservoir[facing=north]
```

Screenshots in `documentation/screenshots/pump-shutoff/` (flattened):

| file | what it shows |
| --- | --- |
| `01-create-world.png`, `04-creative.png`, `05-commands-on.png` | the world set to creative with commands on |
| `06-in-world.png`, `07-pocket.png` | in world, the pocket cleared at y=-59 |
| `08-line-built.png`, `09-line-lit.png`, `10-line-side.png` | the six blocks placed and lit |
| `12-reservoir-gui.png` | **reservoir: `Pressure 601.32 kPa abs`, `Mass 829.02 kg`, `Temperature 298.21 K`, `L 0 W 83 V 17 S 0 %`, `Lag 1.85 s`** |
| `14-pump-gui.png` | **pump: `FULL / CLOSED`, `Flow (last) 0.00 kg/s`, `Pressure change 500.00 kPa`, `Pressure 101.33 kPa abs`, `Lag 2.00 s`** |
| `15-pump-after-2min.png` | the same pump two minutes later: still `FULL / CLOSED`, `Lag 1.10 s` |
| `16-reservoir-final.png` | the same reservoir at the end: 601.32 kPa, 829.02 kg, `Lag 0.55 s` |
| `17-line-overview.png` | the filled tank on the end of the line |

The in-world tank matches the unit test to the digits the GUI prints: 601.32 kPa against
601324.991 Pa, 829.02 kg against 829.0196 kg, 298.21 K against 298.2149 K, 83 % liquid against
83.08 %. The pump's accepted mode is `CLOSED` with the head held at its 500.00 kPa maximum and zero
flow - the shutoff corner that R2 makes reachable.

The island keeps solving: the reported lag falls 1.85 s -> 1.10 s -> 0.55 s over four minutes
rather than growing, which is what a held island's lag does.

```
grep -c "status=HELD" run/logs/latest.log   -> 0
grep -c "fluid_island"  run/logs/latest.log -> 0
```

`FluidWorldAuthority` logs `fluid_island={} committed_tick={} status={}` at WARN for any island whose
status starts with `HELD`, so the absence of both strings is evidence rather than silence. No
CreateChemE warning or exception appears in the log at all.

The client was stopped and no Minecraft `java` process remains.

## 8. What I did not do, and what is unverified

* **The intermediate commits are not split by rule.** The four rules live in one file and three of
  them are individually necessary, so any intermediate state would be a red commit. The work is in
  two commits - solver, then fixture - each green, with the rules explained separately in the solver
  commit message. The report is not committed because `documentation/` is gitignored here.
* **The valve is unchanged and its corner is untested** (section 5). I could not build a valve line
  that reaches a regulating corner, because the natural one stops earlier on the separate tank
  node-block defect. The claim "the valve does not share this defect" rests on that failure to
  reproduce plus a structural argument, not on a passing corner fixture.
* **The filtered pumped line's endpoint is not asserted against the reference.** It now runs 40/40
  and settles at 601324.215 Pa, 0.78 Pa below shutoff rather than the control's 0.009 Pa, because
  the band is read on the pump edge's own loss and the filter carries ~63x that edge's resistance.
  That is inside the band's stated meaning - the pump stops when its own connection carries less
  than the pressure its own row resolves - but it means the stopping point depends on how much
  resistance sits downstream of the pump. A formulation whose scalar is the whole path's margin in
  both modes would not have that property; I did not find one that stays local to the edge.
* **The separate filter-to-tank node-block defect is untouched.** `filterLineToATankKeepsIntegrating\
OnceTheTankIsFull` is still `@Disabled`, and the valve line above stops on the same thing.
* **`SolidRuntimeTest` timing is three samples on a machine with idle Gradle daemons present.** The
  +3.9 % is within what I would expect from run-to-run variation on this host; I did not run enough
  samples to separate a real regression of that size from noise.
* The in-game check used a fresh world and ran for about five minutes after the tank reached
  shutoff. It is evidence that the island does not hold at shutoff and that the endpoint matches the
  unit test; it is not a long-soak test.
* `.mcp.json` is left untracked in the worktree, as it is in the other worktrees.
