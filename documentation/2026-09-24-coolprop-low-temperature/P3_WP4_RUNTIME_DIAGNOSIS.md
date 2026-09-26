# P3 WP4 runtime diagnosis: the two failing fluid runtime tests

Date 2026-09-25. Batch `2026-09-24-coolprop-low-temperature`, follow-up of [P3_DIRECT_LIQUID_PATH.md](P3_DIRECT_LIQUID_PATH.md) section 8 (the two failures) and its open item 1; input for WP7. Read-only diagnosis on `claude/coolprop-multiphase-thermo-37f6b0` at `959ea0d`: no tracked file changed, nothing committed. Probes, patched scratch copies and every run quoted below: `tools/wp4-runtime-diagnosis/` (git-ignored, README there; working copies under `build/diagnosis/`).

## 0. Summary

| | failure 1: six-tank pumped fill held on the wall deadline | failure 2: pumped vertical line settles 593 Pa low |
|---|---|---|
| root cause | 99.2 % of the slice's 2,084,304 checkpoints are the outer phase flashes (`FluidThermodynamics.flashTP`) of the five dry nitrogen tanks: each carries a water trace (the 1e-12 entry seed, or 1e-15 to 1e-39 carried by reversals), which sends the flash into its partial-pressure bisection (about 27 steps), and every step restarts a successive-substitution split from Wilson ratios (about 31 iterations): about 890 checkpoints per trace flash, and 2,288 of the slice's 3,914 flashes are trace flashes. Newton, Jacobians and line searches are 0.6 %. The water compressibility is not the cause: the cost is not monotone in it (2.02 to 2.12 M for 1e-10 to 2e-9 /Pa), and at the same 1e-9 /Pa it is 1.87 M with the old 996.0 kg/m3 density and 2.08 M with the new 997.05. The case sat at 93 % of the budget, and any change of water properties reshuffles its step sequence by +-5 %. | The pump's connection is stated with two different static columns. The hydraulic row uses the pass's frozen column (`headDensities`), which picks the tank's bulk density (water and nitrogen, 880.8 kg/m3) once a closed pump's head has balanced it; the pump's mode tests (`demand`, the start-of-solve reopen test, `headLimitMassFlow`) use the suction's water (997.2 kg/m3). On the head limit the row still drives about 4.9 kg/s forward while the mode test sees the water column and closes the pump; the next solve reopens it (549 to 659 Pa of margin on the water column) and the same pass closes it again, forever. The tank freezes wherever the first such closure happened: path-dependent (593 Pa below the test's value now, 913 Pa with the retired water, and 168 Pa early before WP4, hidden then by a stale expectation). |
| recommended fix | Start `flashTP`'s partial-pressure solve at its fixed point `pc = p - w R T / V_gas` and verify with the bisection's own residual test; the bisection stays as the fallback. | A pump connection's static column is always its suction's density (fix E2), the density every other pump expression already uses. |
| measured effect | First slice 2,084,304 to 200,371 checkpoints (10.4x), warm wall 1,725 to 205 ms; same substeps (46/22) and end states to the printed digits. The test's rig commits the whole first 5 s slice in one job of 1.0 s (was: 11 jobs, 22.0 s, never committed). Three-tank fill 8.2 s to 0.4 s of work, vented chain 8.2 s to 0.4 s. | Tank settles at 860,949.28 Pa, the model's own shutoff steady state to 0.01 Pa (pump at its limit, water column), independent of the path (the retired water lands on its own steady state too); 65.4 Pa above the test's value, inside its 90 Pa. Flat islands are bit-identical (the column multiplies dz = 0). |
| alternative | Raise the wall budget to at least 2.5 s: the rig then passes by the pre-WP4 path (committed 66 ticks by online tick 907, 16 jobs, 27.9 s of work), with 17 % headroom on the first slice. | E1: the pump's mode tests read the frozen column instead. Measured 860,949.51 Pa here, but it keeps the column switch, so the settle point can still land on the lighter column's rest point (3.4 kPa higher). |

No Gradle invocation was made by this agent; see section 5.

## 1. Method

The previous agent's single targeted run (`build/diagnosis/gradle-run1.log`, `fluidRuntimeTest` on the two classes, both failing at `FluidPumpedFillLineTest.java:155` and `ElevatedBlockLineIslandTest.java:226`) compiled the tree at `959ea0d`; its classes were snapshotted to `build/diagnosis/snap/` before other agents edited the worktree. The fluid, runtime and test sources of that snapshot equal `959ea0d` (the WP5 and WP6b edits in the worktree touch `science/thermo/phase/**` and, since, the deprecated shims of `FluidThermodynamics`; neither is on these paths). Everything below ran with plain `java` (JDK 21) against that snapshot, with two scratch copies of `FluidThermodynamics` and `PassiveStepSolver` whose diagnostic switches are off by default (then bit for bit `959ea0d`).

Reproduction is exact: the first slice costs 2,084,304 checkpoints (1,866,339 with the retired water; WP4's numbers), the rig copy ends `online 2400, committed 0, 11 jobs, 22.0 s of work, HELD: wall deadline` (the test's message), and the pumped line ends at 860,290.71 Pa (the test's 860,290.7).

## 2. Failure 1: `FluidPumpedFillLineTest.aSixTankChainPassesTheStartThatHeldItForever`

### 2.1 The island and the budget

`GUPRPRPRPRPRPR` compiles to 8 nodes and 7 connections: the generator (fixed, water at 101,325 Pa), the pump's outlet junction (node 1, zero holdup, water), six 1 m3 nitrogen tanks; connection 1 is the pump (PUMP_TARGET, 0.01 m3/s), the others passive. The rig (`FluidPumpedFillLineTest.java:56-90`) registers the island at its 100-tick cadence with the world's budgets (`IslandCoordinator.Settings(2_000_000_000L,1_500_000_000L,...)`, line 67; config default `wallBudgetMilliseconds` 2000, `CreateChemE.java:94`) and charges 1 us per solver checkpoint. The hold policy halves the slice from 100 ticks to 1 (7 jobs, each cut at 2,000,001 checkpoints), then retries the 1-tick slice with a growing back-off; the 1-tick slice costs 2,084,304, so it is cut every time (`out/rig-six-region1.txt`).

### 2.2 Where the checkpoints go

Every checkpoint attributed to its call site and caller chain (`StackWalker`, `out/six-region1-attr.txt`; lines of `959ea0d`):

| where | checkpoints | share |
|---|---|---|
| `flashTP` from `initialPhaseSeeds` (`PassiveStepSolver.java:193`, flash at 737): the seed of every implicit solve | 927,120 | 44.5 % |
| `flashTP` from `phaseCorrection` after a converged pass (line 291, flash at 1116) | 988,894 | 47.4 % |
| `flashTP` from `phaseCorrection` after the conservative reconstruction (line 364) | 111,004 | 5.3 % |
| `flashTP` from `phaseCorrection` after a failed Newton (line 286) | 41,354 | 2.0 % |
| block Jacobian sweep (`differentiateEntries`, node columns 1908, edge columns 1935), 186 builds | 7,489 | 0.36 % |
| Newton iterations and line search (`SparseNewton.java:140,145,165`) | 4,345 | 0.21 % |
| conservative reconstruction, active-set passes, interval attempts | 3,916 | 0.19 % |

Inside the flashes, 96.3 % of all checkpoints are the successive-substitution loop of `splitHydrocarbon` (`FluidThermodynamics.java:320`) and 2.9 % the bisection loop of `flashTP` (line 303). Per substep: 68 substeps (46 accepted, 22 rejected, 64 interval attempts, step down to 1.02e-6 s at the evaporation event), 207 implicit stage solves, 214 Newton solves, 1,509 iterations, 975 backtracks; a typical attempt costs 18k to 32k checkpoints, an implicit solve about 10.1k, of which about 100 are Newton.

Flash branches (counters in the scratch copy, `out/six-region1-attempts.txt`): 3,914 flashes, of which 2,288 took the bisection; they spent 61,246 bisection steps (26.8 per flash) and 1,898,626 split iterations (31.0 per split). The water mole fraction of those 2,288 flashes: 1,034 at the 1e-12 entry trace that `initialPhaseSeeds` plants (line 736) in a tank water has not reached, 1,000 between 1e-39 and 1e-15 (traces the reversals carried), 254 between 1e-6 and 1e-2 (the first tank's humid gas before it saturates).

### 2.3 Why a trace flash is expensive

A tank that holds gas only is never "complete" for `phaseCorrection` (`hydroComplete`, line 1112: a vapour without a liquid), so every converged pass flashes it, and every implicit solve re-flashes its seed. With water present and `p > psat`, `flashTP` first tries the saturated split at `pc = p - psat` (lines 294-300); for a trace the water cannot saturate the gas (`required > w`), so it falls into the bisection on the hydrocarbon partial pressure `pc` (lines 301-311): bracket `[1e-6, p]`, stop when `|pc + w R T / V_gas - p| < 1e-8 p`. The root lies `w R T / V_gas`, about `y_w p`, below `p` (1e-7 Pa for the entry trace), so the bisection needs about 27 halvings to come within `1e-8 p` of it, and each halving calls `splitHydrocarbon`, which restarts from Wilson ratios and converges its damped update (`ln k = (ln k + target)/2`, line 339) linearly to 1e-8: 31 iterations, two Peng-Robinson phase evaluations each. The bisection is also the less accurate answer: it stops 1e-8 p (1e-3 Pa) from a root whose water partial pressure is 1e-7 Pa or less.

### 2.4 The water compressibility is not the cause

`-Ddiag.water=kappa -Ddiag.waterKappa=k`: Region 1 water at 101,325 Pa carried by `exp(-k (P - P0))`, so only the compressibility changes (`out/six-kappa-*.txt`):

| k [1/Pa] | 1e-10 | 2e-10 | 3e-10 | 4.5e-10 | 4.6e-10 | 6e-10 | 8e-10 | 1e-9 | 1.5e-9 | 2e-9 | 4e-9 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| checkpoints | 2,098,124 | 2,111,933 | 2,079,862 | 2,037,401 | 2,065,563 | 2,084,786 | 2,015,876 | 2,076,203 | 2,121,691 | 2,111,273 | 1,905,087 |
| implicit solves | 209 | 210 | 207 | 202 | 205 | 207 | 200 | 206 | 211 | 210 | 191 |

The cost scatters by +-5 % with no trend, and at 1e-9 /Pa it is 2,076,203 with the new density at 1 atm against 1,866,339 with the retired water (the same 1e-9 /Pa, 996.0 kg/m3). The cost per implicit solve is the same on both paths (10,069 against 9,980); the new water takes 207 implicit solves instead of 187 because its step sequence differs from the first Newton rejections of the slice on (the first accuracy attempt is 3.9e-4 s against 7.8e-4 s; `out/six-*-attempts.txt`). The brief's stiffness mechanisms do not act here:

- no liquid-full vessel: the only liquid-full unknowns are the pump's outlet junction (zero holdup, no volume closure); the first tank holds 4.9e-4 m3 of free water in 1 m3 at the end of the slice;
- the acoustic velocity clamp is not binding anywhere: every node's limit is the configured 100 m/s (water's isothermal bound about 1,490 m/s, nitrogen's about 300 m/s);
- the pump stays on PUMP_TARGET through the whole slice (no mode transition in `out/six-region1-pumptrace.txt`);
- Newton and Jacobian work is 0.6 % of the cost.

WP4's reading (liquid-full stiffness, plan section 10) is therefore not what happened: the slice was already at 93 % of its budget (F1 measured it in the same state), almost all of it in trace flashes, and the 0.1 % density change of WP4's water moved its trajectory enough to cross 100 %.

### 2.5 Root cause

The outer phase flash of a gas node that carries a water trace is about 890 checkpoints (and about 0.7 ms of warm wall), because `flashTP` bisects the hydrocarbon partial pressure to `1e-8 p` from a bracket starting at `[1e-6, p]` and each bisection step re-runs a cold successive-substitution split. A pumped fill into a chain of dry tanks performs thousands of them in its first 50 ms (every seed and every converged pass flashes every gas tank), so its first slice costs about 2 s whatever the water model is.

### 2.6 Recommended fix (WP7)

In `flashTP`, when the saturated try shows the gas unsaturated (`required > w` with `V_gas > 0`), start the `pc` solve at its fixed point before the bisection: all the water is vapour, and `pc` solves `pc + w R T / V_gas(pc) = p`. With the saturated try's own split at `pc0 = p - psat`:

```
guess = p / (1 + w R T / (V_gas(pc0) pc0))            // ideal-gas scaling of V_gas to the root
repeat up to 3 times:
    split at guess; V = V_gas(guess)
    if |guess + w R T / V - p| < 1e-8 p and the state's |pc + pw - p| <= 1e-6 p: return that state
    guess = p - w R T / V
fall through to the unchanged bisection
```

The acceptance tests are the bisection's own (`FluidThermodynamics.java:306` and 310), so the flash's contract does not change; for a trace the accepted point is closer to the root than the bisection's. Implemented behind `-Ddiag.flashFix=true` in the scratch copy (`patches/FluidThermodynamics.diag.patch`) and measured:

| run | without | with the fix |
|---|---|---|
| six-tank first slice, checkpoints (`out/six-region1-fix.txt`) | 2,084,304 | 200,371 (2,276 fixed points accepted, 0 fallbacks, 0 bisections) |
| same, retired water | 1,866,339 | 180,547 |
| same, warm wall per slice (12 runs in one JVM, last 6 averaged) | 1,725 ms | 205 ms |
| substeps, end states of the slice | 46/22 | 46/22, every node equal to the printed digits (100,447.5 Pa, 288.147 K in the first tank) |
| the test's rig, six tanks (`out/rig-six-region1-fix.txt`) | 11 jobs, 22.0 s, committed 0, held | 1 job of 1,005,345 checkpoints, the whole 100-tick slice committed at online tick 100, PUMP_TARGET: the test passes |
| rig, three-tank fill to shutoff (`out/rig-three-fix-*.txt`) | 58 jobs, 8.2 s of work (one job cut at 2.0 s) | 53 jobs, 0.4 s; 601,325 Pa in every tank, pump CLOSED, STEADY |
| rig, vented three-tank chain (`out/rig-vent-fix-*.txt`) | 125 jobs, 8.2 s, committed 11,980 of 12,000 | 120 jobs, 0.4 s, committed 12,000; FULL at the pump's target |
| rig, gas transfer `RUPR` | 3 jobs, 0.0 s | unchanged (no water) |

Risk: the fix changes flash results within the flash's own tolerance, so trajectories that pass through unsaturated water-bearing gas move at the 1e-8 level and pinned bitwise references can move with them (chain-100 in exact mode is already re-captured at WP11; P12/P31 fingerprints and substep pins should be re-run; pure-water and dry nodes do not take the branch, so the water-chain pins of `SolidChainTransportTest` should not move). A further, optional halving of the remaining cost is to warm-start the split's ratios (every remaining flash still runs about 31 cold iterations per split); not needed for these tests.

### 2.7 The brief's other candidates

- Initial pressure guess for liquid-full water nodes, row scaling of the water volume row: nothing to act on; there is no liquid-full vessel and Newton is 0.6 % of the cost.
- Pump simplification rule: the pump never leaves PUMP_TARGET in the slice; it is not involved in failure 1 (it is in failure 2).
- A substep policy (for example error control at phase appearance, F1 open item 4): could remove some of the 64 attempts, but each attempt would still pay about 30k checkpoints of flashes; the flash fix removes 90 % at every attempt.

### 2.8 Alternative: raise the wall budget

With the soft budget kept at 75 % of the wall budget (the world rule), the rig passes at 2.5 s and at 3.0 s by exactly F1's pre-WP4 path: committed 66 ticks by online tick 907, 16 jobs, 27.9 s (2.5 s) and 31.5 s (3.0 s) of work, including two soft-budget holds whose approximate fallback was refused (`out/rig-six-wall-*.txt`). What the budget protects (fluid-followups F1, `FLUID_PUMPED_FILL_REVIEW.md` sections 2.5 and 3, `FLUID_STARTUP_HOLD_REVIEW.md` section 4): it caps how long one island job holds a worker, it is the length of a scheduler round (a round publishes when it closes), and it is the work a held attempt throws away at every level of the halving ladder. Raising it makes every hold and every round up to 25 % more expensive for all islands, leaves this start at about 2 s of wall per online tick (1.7 s warm on one quiet thread, more under load), and keeps it 17 % from the edge, inside the +-5 % scatter of 2.4 plus the next property change. Not recommended except as a stopgap.

## 3. Failure 2: `ElevatedBlockLineIslandTest.elevatedLinesIntegrateFortyIntervalsLikeTheirFlatControl`, pumped rising line

### 3.1 The expectation

`ElevatedBlockLineIslandTest.java:226-227`: `pressure + PUMP_HEAD - head(pressure, LIFT)` with `head = waterDensity(400 kPa) g LIFT` (lines 125-129; the density is the model's own `flashTP` of water at 298.15 K and 400 kPa, 997.1825 kg/m3 now, 996.3059 with the retired water; not a hard-coded 996), `PUMP_HEAD` = 500,000 Pa, `LIFT` = 4, tolerance `1e-4 (pressure + PUMP_HEAD)` = 90 Pa. So 860,883.92 Pa. The formula ignores the P1 scaling of the pump's limit (F4: `riseLimit = setting * rho_suction / rho_ref`, `PassiveStepSolver.java:1051-1056`), which puts the model's limit at 500,000 x 997.1781 / 997.0480 = 500,065.23 Pa.

### 3.2 The island and its steady state by hand

The line compiles to the generator (y = -59), the pump's outlet junction (y = -58) and the tank (y = -55), with a passive connection generator to junction (dz = 1) and the pump connection junction to tank (dz = 3). At rest with the pump at its limit and water in the connection:

```
P_junction = 400,000 - 997.1825 x 9.80665 x 1                       = 390,220.98 Pa
P_tank     = P_junction + 500,000 x 997.1781/997.0480 - 997.1781 x 9.80665 x 3
           = 390,220.98 + 500,065.23 - 29,336.93                    = 860,949.28 Pa
```

That is the physically right steady state of this model (a pump that holds its head limit against a 4 m water column), 65.4 Pa above the test's formula; with the retired water and the new reference density the same formula gives 860,541.56 Pa, and before WP4 (retired water, reference 996.0) 861,063.27 Pa.

### 3.3 What the solver does

`out/elev-region1-trace.txt` (`-Ddiag.pumpTrace=true`): the pump runs on PUMP_TARGET for 17 intervals (9.97 kg/s); in interval 18 it reaches its limit and closes with the tank at 860,290.71 Pa, 658.6 Pa of head margin left on the water column at the committed state (548.8 Pa at the substep states the reopen test reads); for the remaining 22 intervals every solve does the same two passes:

```
[solve start] carried CLOSED: reopen margin 548.8 Pa -> PUMP_HEAD_LIMIT   (water column, PassiveStepSolver.java:169-171)
[pass 0] PUMP_HEAD_LIMIT -> CLOSED: flow=+4.94 kg/s head=500,065.06 margin=-39.2 Pa rho_suction=997.18 column=880.79
```

On the head limit the hydraulic row (`driving = P_a - P_b - headDensities[edge] g dz + head`, line 1807) is stated with the tank's bulk density (880.8 kg/m3, water under nitrogen), so it drives 4.9 kg/s forward; the mode test (`margin = limit - demand`, line 304, `demand` at 1073-1076) states the column with the suction's density (`rho` of the junction, 997.2 kg/m3), so with the junction pressure the forward flow's friction pulls down, the pump "cannot hold" the water column and is closed (line 309, `atShutoff` forbids reopening in this solve). CLOSED is the accepted pass, zero flow, the tank never moves again.

### 3.4 Root cause

`demand`'s documented identity ("in PUMP_HEAD_LIMIT ... the margin is the edge's own pressure loss", lines 1060-1072) holds only when its column equals the row's. The row's column comes from `Equations.headDensities` (lines 1530-1592), which includes the actuator head in its driving pressure (1540-1542); after a closure, the carried head balances the tank's lighter column, so the next pass finds the water column inadmissible and the tank column nearest its rest point (the "neither admissible" branch, line 1589) and states the tank's density. From then on `margin = loss(q) - (rho_suction - rho_column) g dz = loss(q) - 3.4 kPa`: the pump closes while the row still pushes forward, and reopens at every solve start on the water column. Where the tank freezes is the first state at which this happened: 593 Pa below the expectation now, 913 Pa with the retired water and the new reference density (`out/elev-legacy-none.txt`), and before WP4 about 168 Pa below the model's steady state (861,063 against the observed 860,895), which passed only because the expectation's missing P1 scaling (+145 Pa then) cancelled most of it. The stiffer water did not create the defect; it moved the trajectory to a different closing point.

### 3.5 Recommended fix (WP7): E2, the pump connection's column is its suction's density

In `Equations` (after line 1591): `if(pipe.control() instanceof FlowControl.Pump) headDensities[edge]=first;` (the seed density of the suction end). Every other pump expression already uses the suction's density: the rise limit (1051-1056), the mode test and `demand` (304, 1073), the start-of-solve reopen test (169-170), `headLimitMassFlow` (1029), the target row's volume flow (1859) and the shaft power (1782). Physically a pump moves its suction's fluid and a closed pump admits no backflow (it is closed, not throttled), so the connection stands in what it pumped; the rule also removes a switch from the pump model, in line with the owner's pump simplification rule. Measured (`-Ddiag.pumpColumn=suction`, `out/elev-*-E2.txt`):

| water | tank after 40 intervals | model steady state (3.2) | margin at the end | test |
|---|---|---|---|---|
| Region 1 (WP4) | 860,949.28 Pa | 860,949.28 Pa | 0.00 Pa | passes (+65.36 Pa, tolerance 90) |
| retired water, new reference density | 860,541.68 Pa | 860,541.56 Pa | -0.11 Pa | (its own steady state) |
| Region 1 plus the flash fix of 2.6 | 860,949.28 Pa | | | the two fixes are independent |

The passive lines of the same test are untouched (no pump), and every flat island is bit-identical, because the column multiplies a dz of zero. The expectation should then be re-derived as the model's own steady state and the tolerance tightened to the passive lines' (the comment at lines 222-225, "the pump ... does work on the water", is not the reason for the old gap):

```
rho_g = waterDensity(pressure); rho_j = waterDensity(pressure - rho_g g 1)
expected = pressure - rho_g g 1 + PUMP_HEAD rho_j / model.pumpReferenceDensity() - rho_j g (LIFT - 1)
```

(860,949.28 Pa; the outlet junction is one block up, as the test's own comment says).

### 3.6 Alternative: E1, the mode tests read the frozen column

`demand` at line 304 with `equations.headDensities[i]` instead of the suction density (and the reopen test and `checkApproximation` with the same column): consistent, and measured at 860,949.51 Pa (Region 1) and 860,541.72 Pa (retired water). It keeps the column switch, though: a pass that states the tank's column would let the pump push to that column's rest point, 3.4 kPa higher, and the start-of-solve reopen test has no equations yet, so it needs the previous pass's column carried. E2 is the smaller and simpler change.

## 4. What WP7 must know

1. Neither failure is a formulation defect of WP4. Failure 1 is a cost defect of the outer flash that WP4's water change pushed over the budget; failure 2 is a pump-model inconsistency that existed before WP4 and was masked by a stale expectation.
2. The WP5 agent is editing `FluidThermodynamics.java` in this worktree (so far only the deprecated shims); the flash fix touches `flashTP` only. Apply both fixes on top of WP5's and WP6b's commits.
3. The flash fix is not bitwise: re-run `fluidRuntimeTest`, `fluidScienceTest`, the network suites and P12/P31, and expect the chain-100 exact reference to be re-captured at WP11 as planned. The E2 fix is bitwise on flat islands.
4. After both fixes the two tests should pass unchanged; the pumped-line expectation should still be re-derived (3.5), and the six-tank test would pass with a large margin (one job of 1.0 s where the budget is 2 s).
5. The rig's clock is 1 us per checkpoint; warm wall is 0.83 us per checkpoint before the fix and 1.03 after (the remaining checkpoints bracket more work), so the in-game gain is about the same factor (1,725 to 205 ms per first slice).
6. `checkApproximation` (lines 570-579) tests a closed pump's reopening with `head < limit - band`, i.e. on the row's column; with E2 it agrees with the solve's own test.

## 5. Runs

No Gradle invocation by this agent (the lock file was never taken). Plain `java` probe runs against the snapshot, each 1 to 20 s, no suite and no dev client alongside: the six-tank first slice in count, attempts, attribution and warm modes (Region 1 and retired water, 11 compressibilities, with and without the flash fix, with the pump trace), the rig for the six-tank chain (budgets 2.0, 2.5 and 3.0 s, with and without the fix), the two- and three-tank fills, the vented chain and the gas transfer (with and without the fix), and the pumped rising line for 40 intervals (Region 1 and retired water; no fix, E1, E2; with the trace; with the flash fix). Outputs in `tools/wp4-runtime-diagnosis/out/`.
