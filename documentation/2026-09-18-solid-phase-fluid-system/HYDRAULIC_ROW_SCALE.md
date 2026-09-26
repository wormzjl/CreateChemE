# F3 — the hydraulic residual row's constant 1e5 Pa scale

Worktree `D:\Minecraft\Modding\1.21\CreateChemE\.claude\worktrees\agent-ae086faac00564dbc`,
branch `claude/hydraulic-row-scale`.

**Headline.** The defect is real, the fix is in, and it does exactly what it was supposed to do to
the hydraulic rows — but it does **not** make the driven filter line survive. Two node-block
defects were sitting immediately behind it at the same order of magnitude, and they are what stops
the line now, in the fixture and in the world. Everything below is measured; nothing is predicted.

---

## 1. Setup verification

| Step | Result |
| --- | --- |
| `git checkout -b claude/hydraulic-row-scale claude/solid-phase-filter-fix` | done |
| `git log --oneline -1` | `90f16c7 Cover the driven filter line, and record what still stops it` — as required |
| Other checkouts touched | none. The main checkout, the diagnosis worktree `agent-a0fba21980f2fa09a` and the Codex worktree were read only. |
| `git stash` | never used, bare or otherwise. Baseline comparisons were taken by editing one line in place and editing it back, never by stashing. |
| Gradle concurrency | `tasklist \| grep -i java` before every invocation. Two idle Gradle daemons of my own were present throughout with flat CPU (21.5 s and 23.8 s unchanged across a 3 s sample); no suite ever ran while the dev client was up, and the dev client was started only after every suite and GameTest had finished. |
| `python` in Git Bash | not used. Everything is Bash, PowerShell or a small Java helper. |
| Commit trailer | every commit ends with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. |

---

## 2. Reproduction, before any change

`@Disabled` removed from
`FilterBlockLineIslandTest.filterLineToATankKeepsIntegratingOnceTheTankIsFull`
(`src/test/java/com/wormzjl/createcheme/runtime/fluid/`), then
`./gradlew.bat fluidRuntimeTest --tests '*FilterBlockLineIslandTest*' --offline`:

```
FilterBlockLineIslandTest > filterLineToATankKeepsIntegratingOnceTheTankIsFull() FAILED
    org.opentest4j.AssertionFailedError at FilterBlockLineIslandTest.java:223
10 tests completed, 1 failed
```

The failure message, verbatim from the JUnit XML:

```
HELD at interval 8/40 (t=35 s): Newton line search stalled at residual 68.477746681025; active-set pass=1
  node[0] id=1  GENERATOR P=400000.0            liquid=1.0                vapour=0.0
  node[1] id=3  JUNCTION  P=400000.0000374963   liquid=0.9999999999999607 vapour=0.0
  node[2] id=5  RESERVOIR P=399999.9996416321   liquid=0.7447789580011628 vapour=0.2552210423718994
  node[3] id=-3 JUNCTION  P=399999.9996041793   liquid=1.0000000000004188 vapour=0.0
  pipe 0->1 Passive filter=false
  pipe 3->2 Passive filter=false
  pipe 1->3 Passive filter=true
```

The two filter junctions, node 1 and node 3, sit **3.73e-4 Pa** apart on a settled island — the
diagnosis's 3.5e-4 Pa, reproduced. (The quoted norm is 68.5 rather than the diagnosis's 3.5e-9
because the trajectory through the interval controller differs; the settled island state printed
with it is the same one. The per-row instrumentation below pins the binding row directly and does
not depend on which number the stall happened to print.)

---

## 3. Design

### The change

`PassiveStepSolver.Equations` gains

```java
final double[] pressureScales;                       // one per edge, built in the constructor
...
pressureScales[edge] = Math.max(1e5,
        Math.max(seeds.get(pipe.first()).pressure(), seeds.get(pipe.second()).pressure()));
```

and `edgeRows` divides by `pressureScales[edge]` instead of the literal `1e5`.

Why this scale:

* **Pass-constant.** `seeds` is fixed for the whole Newton solve, so the scale is a constant
  multiplier of the row. No derivative of it enters the Jacobian, the sparsity pattern is
  untouched, and the block sweep and the coloured whole-island sweep still evaluate the identical
  row — which is what keeps `BlockJacobianEquivalenceTest` bit-for-bit true. `initialPhaseSeeds`
  gives every node a seed, including a fixed one (it returns the boundary state unchanged), so the
  scale is well defined on generator and void endpoints too.
* **Floored at 1e5 Pa.** Every island at or below atmospheric pressure is bit-identical to what it
  was. Only islands that actually run above 1 bar move.
* **Per edge, not per island.** An island can span a 4 bar header and an atmospheric vent; only the
  rows that sit at high pressure should be loosened. A single island-wide maximum would relax an
  atmospheric branch by the header's factor for no reason. I found no case that wanted the
  island-wide form, so I did not implement and measure it; if one turns up, the constructor loop is
  the only place that changes.
* **Not the live iterate's pressures.** That would make the scale a function of the unknowns: its
  derivative would enter every one of these rows, the `max` would put a kink in them, and the rows
  would stop being a fixed positive multiple of the physical equation. All of it to track a
  pressure that a converging Newton is already driving to the pass's own endpoint pressures. Not
  measured, because the cost is structural rather than numerical and the benefit is nil.

### Row audit

Every residual in `edgeRows` and in the node blocks, decided one at a time:

| Row | Dimension | Decision |
| --- | --- | --- |
| hydraulic balance `(driving-loss)/1e5` | pressure | **rescaled** — the defect itself |
| `PUMP_HEAD_LIMIT` `(head - maximumAddedPressure())/1e5` | pressure | **rescaled** |
| `VALVE_REGULATING` `(P_a - targetPressure())/1e5` | pressure | **rescaled** |
| `VALVE_OPEN` `head/1e5` | pressure | **rescaled** |
| throttled inlet `(copySign(limit,driving)-flow)/max(limit,1e-8)` | mass flow | unchanged |
| closed edge `f = flow`, `CLOSED` `f = flow` | mass flow | unchanged |
| `PUMP_TARGET` `(flow/rho - targetVolumeFlow())/.01` | volume flow | unchanged |
| filter loading `loading - volume/capacity` | dimensionless fraction | unchanged |
| `PhaseLayout.balanceRows` component / water rows | amount | already divided by `componentScales[i]` |
| `PhaseLayout.balanceRows` energy row | energy | already divided by `energyScale` |
| `PhaseLayout.balanceRows` volume row | volume | already `(V - V_target)/V_target` |
| junction net-flow row | mass flow | unchanged |
| junction retained-pressure row `x[p] - log(retainedPressure/1e5)` | log pressure | already relative; the `1e5` is inside a logarithm, not a residual scale |
| junction enthalpy / amount rows | already relative | unchanged |
| equilibrium fugacity rows, water saturation row | logarithmic | already relative |
| partial-pressure closure `(pc + pw - P)/P` | pressure | already divided by the **live** pressure — the existing precedent for what this change does |
| head unknown's `*1e5` in `initial()`, `nodeAccumulate`, `edgeRows`, `checkApproximation`, the accepted-head readback | — | **variable** scale, not a residual scale; left exactly as it was |

The four rescaled rows are all pressure-dimensioned rows of the *same edge*. Rescaling only the
hydraulic one would have left the actuator rows 4x tighter on a 400 kPa island, which simply moves
which row stalls; keeping one unit per edge is the point.

### Effect on the `checkConservation` / equation gate

`PassiveStepSolver.solve` reconstructs the accepted point, re-encodes it, re-evaluates
`equations.residual(reconstructed)` and refuses it above `1e-8` (FULL acceptance). Because that
gate re-evaluates the *same* rows, its hydraulic threshold becomes relative in exactly the same
way:

| | before | after |
| --- | --- | --- |
| gate on the hydraulic row at 101 kPa | 1e-8 x 1e5 = **1e-3 Pa** | 1e-3 Pa (floored, unchanged) |
| gate on the hydraulic row at 400 kPa | 1e-3 Pa (absolute) | 1e-8 x 4e5 = **4e-3 Pa** |
| gate on the hydraulic row at 4 MPa | 1e-3 Pa (absolute) | 4e-2 Pa |

So the gate is loosened at high pressure by the same factor as the Newton tolerance, which is the
intent: both become statements about relative pressure closure. The `1e-8` constant, the
`APPROXIMATE` 1e-6 variant and everything the gate checks are untouched.

---

## 4. Proving the physics is unchanged, then re-recording

All three runs against `src/test/resources/fluid/regression/chain-100.json`. The other three
reference islands (`quiet-11312`, `quiet-11324`, `cold-11312`) SKIP in this worktree — there is no
readable `core.dat` for them — so chain-100 is the whole of the replayed set here.

**(a) `-PfluidRegressionMode=relative`, old reference untouched — PASS.**

```
chain-100 vs reference: max deviation:
  state/moles     8.054e-10 relative   (interval 0 node 10 moles[3]: 1.1372393959414324 -> 1.1372393950254442)
  temperature     7.797e-09 K          (interval 0 node 10: 349.9517022960429 -> 349.9517022882458)
  phase fraction  3.647e-11            (interval 0 node 10 vaporVolume: 0.9568095053030017 -> 0.9568095053482308)
  flow            2.917e-07 relative   (interval 0 pipe 8 averageMassFlow: -0.018873661120650677 -> -0.018873666626248262,
                                        difference 5.50559758547009E-9 kg/s,
                                        controller allowance 3.201855123473851E-8 kg/s)
BUILD SUCCESSFUL
```

Against the declared gate (1e-6 relative on state/moles, 1e-4 K, 1e-6 phase fraction, 1e-3 on
flows) every margin is three orders or better, and the flow difference is 17 % of the controller's
own numerical allowance. The chain runs at `150000 + 1000*cos(0.7*i)` Pa, so **every edge of it is
above the 1e5 floor and genuinely rescaled** — this is not a vacuous pass.

**(b) `-PfluidRegressionMode=exact` — FAIL, as the record of what moved.** Same four numbers as
(a); the mode asserts bit equality and the assertion at `FluidSolverRegressionTest.java:66` fires.

**(c) `-PfluidRegressionCapture=true`** → `Fluid solver regression CAPTURED src\test\resources\fluid\regression\chain-100.json`.

**(d) `-PfluidRegressionMode=exact` again — PASS.**

```
chain-100 vs reference: max deviation: state/moles 0.000e+00 relative (-), temperature 0.000e+00 K (-),
                                       phase fraction 0.000e+00 (-), flow 0.000e+00 relative (-)
```

---

## 5. Baselines

### Substep counts — nothing needed re-recording

`SolidChainTransportTest.clearChainSubstepCountsAreUnchanged` was expected to move and **did not**.
It still measures 25/10, 32/14 and 18/5, and its assertions are untouched. This is not a vacuous
result either: the chain it runs is the same 150–160 kPa chain, so its edges are rescaled by
1.5–1.6x; the accept/reject sequence is simply robust to it, because the binding rows on that
island are not hydraulic.

| Assertion | Recorded | Measured now | Change |
| --- | --- | --- | --- |
| `clearChainSubstepCountsAreUnchanged` 0.5 s, 10 nodes | 25 / 10 | 25 / 10 | none |
| same, 5 s, 10 nodes | 32 / 14 | 32 / 14 | none |
| same, 5 s, 2 nodes | 18 / 5 | 18 / 5 | none |
| `SolidChainTransportTest` bounds `<=50` / `<=16` (10- and 30-node solid chains) | pass | pass | none |
| `SolidClosureFeasibilityTest` thresholds (3 tests) | pass | pass | none |
| `BlockJacobianEquivalenceTest` bit-for-bit | pass | pass | none, and structurally cannot change: both sweeps call the same `edgeRows` |
| `WorkerTrajectoryEquivalenceTest` accepted/rejected equality | pass | pass | none |

### `fluidNetworkBenchmark` — one count moves

Baseline taken in the same session on the same machine by setting `pressureScales[edge]=1e5` and
running, then editing it straight back (no stash):

| island | accepted/rejected before | after | first call ms before → after | warm ms before | warm ms after (2 runs) |
| --- | --- | --- | --- | --- | --- |
| 2 reservoirs, 91 eq | 30 / **11** | 30 / **9** | 139.0 → 147.2 / 142.6 | [52.9, 35.3] | [58.3, 34.9], [48.9, 34.6] |
| 10 reservoirs, 459 eq | 19 / 14 | 19 / 14 | 97.2 → 118.6 / 123.3 | [80.5, 62.3] | [70.1, 56.8], [89.5, 63.6] |
| 100 reservoirs, 4599 eq | 37 / 3 | 37 / 3 | 1102.6 → 1029.5 / 1075.0 | [907.6, 837.3] | [851.0, 873.0], [904.9, 1005.5] |

The recorded baseline 30/11, 19/14, 37/3 reproduced exactly, so the comparison is sound. The only
change is the two-reservoir island shedding two rejected substeps (11 → 9), which is an
improvement. Warm times are inside the machine's spread in both directions.

### Fixture timings, 3 forced samples each

| | baseline | with the fix |
| --- | --- | --- |
| `SolidChainTransportTest` (whole class) | 0.711, 0.771, 0.721 s | 0.769, 0.712, 0.717 s |
| `SolidRuntimeTest.physicalPumpFilterAndNitrogenReceiverStartTogether` | 0.647, 0.641, 0.637 s | 0.649, 0.657, 0.678 s |

No regression beyond the spread. The pump-filter mean moves 0.642 → 0.661 s (+3 %), inside the
0.637–0.678 s range the six samples span.

---

## 6. Verification

| Check | Baseline | Result |
| --- | --- | --- |
| `fluidScienceTest` | 161 | **161 tests, 0 skipped, BUILD SUCCESSFUL** |
| `fluidRuntimeTest` | 116, 1 skipped | **116 tests, 1 skipped, BUILD SUCCESSFUL** (see §7 — the skip is back) |
| `runFluidGameTestServer -PfluidGameTestRunId=hydraulic-scale-01` | 20 | **`All 20 required tests passed :)`**, 20 game tests in 5.417 s |
| `fluidSolverRegression` relative / exact / capture / exact | — | PASS / FAIL-as-record / CAPTURED / PASS at 0.000e+00 |
| Re-enabled fixture passing 40/40 | required | **NOT ACHIEVED** — see §7 |

---

## 7. What the hydraulic rows do now, and what stops the line instead

I instrumented `SparseNewton`'s stall message with the six largest residual rows and
`PassiveStepSolver` with the block offsets, the step size, the tolerance and a one-ULP sensitivity
sweep, swept the generator pressure from 101 kPa to 600 kPa before and after the change, and then
removed every probe. Row indices map to blocks through the printed offsets: for the 400 kPa island
`offsets=[0,0,3,9] edgeOffset=12 filters=[-1,-1,15]`, so rows 0–2 are the filter inlet junction,
3–8 the tank, 9–11 the filter outlet junction, 12–14 the three hydraulic rows and 15 the filter
loading row. Inside the tank block the order is nitrogen material (3), water material (4), energy
(5), volume closure (6), nitrogen vapour/liquid equilibrium (7), water saturation (8).

### The hydraulic rows are fixed

| generator | binding row before | binding row after | filter hydraulic row after |
| --- | --- | --- | --- |
| 300 kPa | **filter hydraulic row 2.26e-9** | tank/junction block | — |
| 400 kPa | junction starvation | tank block 1.29e-9 | **-2.4e-14** |
| 600 kPa | **filter hydraulic row 4.39e-9** | tank block 1.11e-9 | **-4.1e-12** |
| 200 kPa | junction starvation | tank block 1.05e-9 | **-2.0e-13** |

After the change a hydraulic row is **never** the binding row at any swept pressure. Where one was
binding before (300 kPa at 2.26e-9, 600 kPa at 4.39e-9) it is now three to five orders smaller.
That is the whole of what F3 was.

### What is left

Full sweep, 40 intervals each, filter line versus the filter-free control:

| P kPa | before | after | control (both) |
| --- | --- | --- | --- |
| 101 | OK 40/40 | OK 40/40 | OK 40/40 |
| 150 | junction starvation @22 | junction starvation @23 | OK 40/40 |
| 200 | junction starvation @15 | tank block 1.05e-9 @17 | OK 40/40 |
| 250 | tank block 1.11e-9 @14 | junction starvation @13 | OK 40/40 |
| 300 | filter hydraulic 2.26e-9 @10 | junction starvation @11 | OK 40/40 |
| 350 | junction starvation @9 | tank block 1.41e-9 @9 | OK 40/40 |
| 400 | junction starvation @8 | tank block 1.29e-9 @7 | OK 40/40 |
| 600 | filter hydraulic 4.39e-9 @6 | tank block 1.11e-9 @5 | OK 40/40 |

The interval the line reaches is unchanged within trajectory noise. Two defects, neither of them an
edge-row scaling question, were already behind F3:

**F4a — the tank's node block floors just above tolerance.** At 200, 350, 400 and 600 kPa the
binding rows are the tank's nitrogen vapour/liquid equilibrium row (1.0e-9 to 1.4e-9) and its
volume closure (5e-10 to 1e-9), against the 1e-9 Newton tolerance, with the step refined to
6.6e-9 s and the seed point already at 1.2996e-9. Five Newton iterations move it by 1 %. It is not
evaluation noise: nudging any unknown by a single unit in the last place moves **no** row by more
than 1e-13, so the residual is smooth there. A square smooth system that a Newton direction cannot
reduce is a near-singular block — here a trace-nitrogen equilibrium pair on a pressurized water
tank, which is the same cancellation the existing tolerance comment in `PassiveStepSolver`
(`1e-11 stalls on caloric cancellation in nearly liquid-full water with trace N2`) was calibrated
around at atmospheric pressure and evidently not above it.

**F4b — the filter's outlet junction starves when the tank fills.** At 150, 250 and 300 kPa the
island collapses to 13 unknowns and the outlet junction's amount normalization row reaches -1.0
(its total amount driven to zero) with its specific enthalpy row at ~-67. That is a gross failure,
not a tolerance one.

Fixing either would change the physics of every island in the tree and would not have passed the
relative regression gate this task is built around, so I did not attempt them. The fixture is
therefore back to `@Disabled`, with the measurement above written into its Javadoc in place of the
old prediction, and the suite count returns to 116 with 1 skipped.

---

## 8. In-game confirmation

Dev client via the langyo/minecraft-mod-mcp bridge (jar copied into `run/mods/`,
`pauseOnLostFocus:false` in `run/options.txt`), driven over the bridge's HTTP API at
`localhost:9876/api/cmd` — the same transport the diagnosis worktree's helpers use. Started only
after every suite and GameTest had finished. Creative superflat world, blocks at `y = -59`.
Screenshots in `documentation/screenshots/hydraulic-row-scale/`, alpha-flattened; the client log is
beside them as `client-latest.log`.

Bridge notes beyond the diagnosis's §9: in this build `enter_control_mode` works from the **title
screen**, so no `java.awt.Robot` was needed to reach a world — `click_button_index`,
`switch_tab` and `get_screen_buttons` drove world creation entirely. `execute_command` is still
broken and chat is the way in; `press_enter` is not a command, `press_key {"key":"enter"}` is.
`get_player_info`'s `gamemode` field reads `survival` in a creative world and cannot be trusted.
Typing into `EditBox` widgets still needs `Robot` (`build/mcp/poke.ps1`, clipboard paste).

| Check | Result | Screenshot |
| --- | --- | --- |
| `generator → pipe → inline_filter → pipe → reservoir` built at y=-59 | four blocks plus the reservoir, one island | `f1-line-built.png` |
| generator set to 400 kPa | `Pressure 400.00 kPa abs`, `Flow (last) 38.98 kg/s`, `FULL`, `Settings accepted at the current simulation event.` | `f2-generator-screen.png`, `f3-generator-400kpa.png` |
| **the island keeps SOLVING after the tank fills** | **NO.** `HELD: Substep refinement exhausted: Newton line search stalled at residual 1.000698856611371E-9; active-set pass=1`. `Net 7.21e-10 kg/s`, `Committed 308.40 s`, `Lag 29.20 s` | `f4-filter-after-fill.png` |
| `run/logs/latest.log` has no `status=HELD` for it | **NO.** 179 `status=HELD` records, every one naming `fluid_island=11`, which is this line. Two distinct residuals across the session: `1.000698856611371E-9` and `1.0184075799700792E-9` | `client-latest.log` |
| a 400 kPa filter line that does **not** terminate in a tank | `generator → pipe → inline_filter → pipe → void` at 400 kPa with 5 % `createcheme:demo_particle` / 100 µm: **`filter clogged / FULL`** — solving, not held. `Load 100.00 %`, `Captured 25.00 kg`, `createcheme:demo_particle / 100.00 µm / 25.00 kg`, `Pressure drop 298.45 kPa`, `Committed 791.45 s` | `f5-void-generator-400kpa-solids.png`, `f6-void-filter-400kpa.png` |
| the filter captures solids and recovery restores flow | `Recover solids` → `Solid recovery requested.`, `Load 100.00 % → 74.77 %`, `Captured 25.00 → 18.69 kg`, `Net 0.00 → 2.33 kg/s`, status back to `FULL`, `Committed 830.65 s` | `f7-void-filter-recovered.png` |
| client stopped afterwards, no Minecraft java process left | world saved with `save-all`, process 45860 stopped; the only java processes remaining are my own two idle Gradle daemons | — |

The in-game hold at **1.0007e-9** is the same node-block floor §7 measured in the fixture
(1.0e-9 to 1.4e-9), not a hydraulic row — and it is within 0.5 % of the **1.0053784466554722E-9**
the pre-fix diagnosis recorded for the same line, which is the clearest single statement of the
outcome: in the world, this line's hold was already the node-block defect, and F3 was hiding
underneath it rather than the other way round.

The second line is the positive result: a filter carrying 5 % solids on a **400 kPa** island runs,
clogs at its stated capacity, and recovers. Its island never appears in a `HELD` record.

One deviation from the brief's script: while cycling the generator's composition presets looking
for the water slurry I committed a crude preset by mistake, so the void line's carrier is
`WTI Light Export` rather than water (`L 95 W 0 V 0 S 5 %`). The solids feed is exactly the
requested `createcheme:demo_particle` at 100 µm and 5 vol %. Since the point of that line is the
filter behaving under 400 kPa of driving pressure, I did not rebuild it.

---

## 9. What I did not do, and why

* **F4a and F4b are not fixed.** They are node-block defects — a near-singular trace-nitrogen
  equilibrium pair and a starving zero-holdup junction. Either fix changes the physics of every
  island and would have to clear the relative regression gate on its own terms. Out of scope for a
  residual rescale, and I would rather hand over a measurement than a guess.
* **The re-enabled fixture does not pass, so it is disabled again.** Leaving it red would have
  turned `fluidRuntimeTest` into a suite nobody can use as a gate. Its Javadoc now carries the
  measured row indices, magnitudes and the pressure sweep instead of the old prediction that
  rescaling the row would be the last thing needed.
* **The island-wide and live-iterate scale variants were not measured.** I found no case that
  wanted either; §3 gives the reasoning rather than numbers, and that is an argument, not a
  measurement.
* **Only `chain-100` was re-captured.** The other three reference islands SKIP in this worktree for
  want of a readable `core.dat`; I did not manufacture snapshots for them.
* **The pumped filter line was not re-examined.** `aFilterBlockCostsAPumpedLineNoIntervalOfItsOwn`
  still passes on its own terms (the filter costs the pumped line no interval); the underlying pump
  active-set chatter it documents is untouched by this change and the filter-free control
  reproduces it identically, before and after.
* **No `.mcp.json` / `npx minecraft-mod-mcp mcp` stdio session.** The bridge's HTTP API is the same
  surface and the diagnosis worktree's helpers already speak it; going through the stdio JSON-RPC
  wrapper would have added a process per call for no extra capability.
* **Nothing is pushed.** Three commits on `claude/hydraulic-row-scale`, local only.

---

## 10. Commits

| sha | subject |
| --- | --- |
| `c4079dd` | State an edge's pressure rows in the island's pressure, not in 1e5 Pa |
| `3c84036` | Re-capture the solver regression reference for the rescaled rows |
| `bdc113c` | Record what the driven filter line stops on now that the row is scaled |

Base: `90f16c7` on `claude/solid-phase-filter-fix`.
