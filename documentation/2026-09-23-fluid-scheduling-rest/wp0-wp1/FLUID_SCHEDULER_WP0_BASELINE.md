# Fluid scheduler WP0: counters, fixtures and baseline

Date: 2026-09-23. Branch `claude/fluid-scheduler`, WP0 commit `0176080` on top of `2a7bfdd` (merged solid-phase fluid system plus `AGENTS.md`); after the rebase onto `42fdf41` the same change is `eb28fc5`, and the baseline runs below were taken on `0176080`. Plan: `FLUID_ISLAND_REST_PLAN.md` revision 3, section 7 row WP0 and section 6. Scope: measurement only; the scheduler is unchanged in this commit.

## 1. What WP0 added

| Piece | Where | Purpose |
|---|---|---|
| `FluidRuntimeDiagnostics` | `src/main/java/com/wormzjl/createcheme/runtime/fluid/FluidRuntimeDiagnostics.java` | Opt-in server-thread counters. `ENABLED` is false by default; each recording site is one volatile read when off. Never persisted, never read by the runtime. `pause()`/`resume()` exclude a harness's own reads. |
| Counter sites | `IslandCoordinator`, `WorldTopologyLedger.Snapshot`, `CausalModuleCoordinator.advance`, `FluidWorldAuthority.view`, `FluidNetwork.sendState` | One increment per unit of work, see section 2. |
| Benchmark profiles | `src/fluidGameTest/java/com/wormzjl/createcheme/fluid/gametest/FluidServerBenchmark.java` | `transient100`, `rest100`, `mixed100` beside the unchanged `stress100`, all with elapsed-window termination. |
| Report fields | same | `runtimeCounters` (totals, per wall second, per tick, idle-tick totals and per-tick means, idle ticks with any work, per-tick maxima), `ladders` (per-family intervals, latency, worker time, substeps), `transientFill` (measured fill time constant). |
| P12/P31 fingerprints | `WorkerTrajectoryEquivalenceTest`, `CadenceTrajectoryQualificationTest` | P12 writes `build/reports/fluid/P12-worker-trajectories.json` (SHA-256 over every published frame's raw bits per worker mode). P31 now writes ordered JSON (the old `Map.of` output was not byte-stable across JVMs) with `trajectoryDigests` over every published interval and, for the adaptive run, every publication clock. |
| Counter unit test | `FluidRuntimeDiagnosticsTest` | Inert when disabled, counts when enabled, excludes a paused observer. |

The Gradle tasks `fluidServerBenchmark`/`prepareFluidBenchmark` pass `fluidBenchmarkProfile` through without validating it, so `build.gradle` needed no change; the fixture rejects unknown names.

## 2. Counter definitions

| Counter | Counted at | Unit |
|---|---|---|
| `islandVisits` | Before WP1: `IslandCoordinator.tick` accrual loop (+islands), `pump` eligibility loop (+islands), each `FairIslandQueue` readiness test (+1). From WP1: each re-derivation of one island's readiness and deadline (+1) and each fair-queue readiness test (+1). | one examination of one island's scheduling state |
| `moduleScans` | `CausalModuleCoordinator.advance` past its re-entrancy guard | one pass over every module and pending transfer |
| `topologySnapshots` | `WorldTopologyLedger.Snapshot` compact constructor | one construction (copies the active map, validates every registration, builds a position set) |
| `islandSnapshots` | `IslandCoordinator.Snapshot` compact constructor | one construction |
| `viewBuilds` | `FluidWorldAuthority.view` | one device view |
| `menuPackets` | `FluidNetwork.sendState` after sending | one menu state packet |
| `readinessPumps` | `IslandCoordinator.pump` past its guard | one pump execution |
| `solvesDispatched` | successful `Dispatcher.submit` in `pump` | one island interval dispatched |
| `completionsRouted` | `IslandCoordinator.completed` for an owned, not yet terminal attempt | one terminal result |
| `islandsPublished` | `closeRound`, size of the published list | one island publication |

Added in WP1 (absent, hence zero, in WP0 reports): `deadlinesFired` (current scheduler deadlines acted on), `drainContinuations`, `drainContinuationsDeferred`.

A benchmark tick's counts cover its tick hooks plus the mailbox work done since the previous tick (the harness reads the counters at the start of its post-tick hook, before it services the mailbox until the next tick). An idle tick routed no completion, dispatched no solve, published no island and (WP1 only) fired no current deadline. The harness pauses the counters around its once-a-second `world.capture()`.

## 3. Fixtures

All timed profiles: 100 isolated ladders, `10 + (network*13 % 21)` reservoirs per ladder (10 to 30), current-basis wet TJL20 + 10 % nitrogen + 20 % water at 350 K, every fixture chunk unloaded, 5 s cadence, adaptive cadence off, `wallBudgetMilliseconds = 2000`, elapsed-window termination (warm-up then measurement window, like `stress100`).

| Profile | Ladders | Generator | Reservoirs | Pipes | Void |
|---|---|---|---|---|---|
| `stress100` | 100 THROUGH | 150.1 kPa | 1 m³ | 1 m blocks, 0.05 m | 150.0 kPa |
| `transient100` | 100 TRANSIENT | 200 kPa | 1000 m³ | feed 7 blocks of 100/7 m (100 m), rungs 1 m blocks, all 0.30 m | none |
| `rest100` | 100 CLOSED | none | 1 m³, rung pressures spread 150.1 to 150.0 kPa plus ±2 Pa | 1 m blocks, 0.05 m | none |
| `mixed100` | network mod 4: 0 and 1 TRANSIENT (50), 2 THROUGH (25), 3 CLOSED (25) | as above | as above | as above | as above |

`stress100` builds the identical fixture as before: same device order, identities, geometry, specification volume and random sequence; a 1 m³ reservoir computes its amounts as `x / V_unit * 1`, bitwise equal to the old `x / V_unit`.

### 3.1 Why 0.30 m, and the fill time constant

The plan asks for generators filling 1000 m³ reservoirs through 100 m of pipe with a fill time constant of order thousands of seconds. With the `stress100` bore of 0.05 m that is not achievable. A throwaway probe (direct `PassiveIntervalSolver` intervals on one 20-reservoir ladder; source and output kept in `wp0-reference/TransientFixtureProbe.java.txt` and `transient-fixture-probe.xml`) measured:

| Generator | Bore | τ = gap / (dP̄/dt) | Relative inventory change per 5 s |
|---|---|---|---|
| 200 kPa | 0.05 m | 552,000 s | 4.8e-6 |
| 300 kPa | 0.05 m | 756,000 s | 1.2e-5 |
| 200 kPa | 0.20 m | 14,200 s | 1.9e-4 |
| **200 kPa** | **0.30 m** | **4,930 s** | **5.4e-4** |
| 200 kPa | 0.40 m | 2,320 s | 1.1e-3 |
| 175 kPa | 0.30 m | 3,770 s | 3.4e-4 |

The chosen 200 kPa / 0.30 m ladder has τ ≈ 4,930 s for 20 reservoirs (τ scales with reservoir count, so about 2,500 to 7,400 s over the fixture). Measured in the running engine over the 100 transient ladders, averaged, from the 60 s to the 180 s committed-time mark: τ = 3,934 s (`transient100`) and 3,881 s (`mixed100`), with the mean generator-to-reservoir gap falling from 49,140 Pa to 47,670 Pa. The fill is about 3 % complete at the end of the window.

Note for WP2: at τ ≈ 4,900 s the per-interval relative change is about 5.4e-4 and it changes by about 9e-8 per interval (probe: 5.403e-4 to 5.315e-4 over 11 intervals), about 90 times the plan's `eps_s = 1e-9`, so the stationarity test of plan section 3.3 cannot pass. With the 0.05 m bore the change-of-delta would have been about 5e-11, below `eps_s`, and the fixture would have certified as STEADY. The `stress100` through-flow ladders are the case the plan expects to certify.

## 4. Baseline runs (unchanged runtime)

Command, one at a time, fresh run id each:

```
./gradlew.bat fluidServerBenchmark -PfluidBenchmarkProfile=<profile> -PfluidBenchmarkRunId=<profile>-baseline-r01 \
  -PfluidBenchmarkWorkers=0 -PfluidStressWarmupSeconds=60 -PfluidStressMeasurementSeconds=120 -PfluidStressProfile=true --offline --console=plain
```

Reports: `build/reports/fluid/M9/<profile>-baseline-r01/report.json`, with `runtime-audit.json` (all PASS, 0 runtime errors) and `stress.jfr`. For the noise estimate `transient100` was repeated twice more on this commit (`transient100-baseline-r02`, `-r03`, both PASS); the three-run ranges are in `FLUID_SCHEDULER_WP1_REVIEW.md` section 3.3 and `noise.md`. Console logs of every baseline run are in `baseline-logs/`. Manifest artifact SHA-256 prefix `80fc302e47349436` for all four. Machine: AMD64 Family 26 Model 68 (16 logical processors), Java 21.0.11. `workers = 0` resolves to 12 automatic workers. `AGENTS.md` says large solver campaigns use 8 to 10 workers; these runs follow the brief's `-PfluidBenchmarkWorkers=0`, which is also what the earlier `stress100-baseline-auto-r01` used.

### 4.1 Per-tick counters (mean over all ticks / mean over idle ticks)

| counter | stress100 | transient100 | rest100 | mixed100 |
|---|---|---|---|---|
| islandVisits | 797 / **700** | 808 / **700** | 774 / **700** | 807 / **700** |
| moduleScans | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |
| topologySnapshots | 1.00 / **1.00** | 1.00 / **1.00** | 1.00 / **1.00** | 1.00 / **1.00** |
| islandSnapshots | 1.00 / 0 | 1.00 / 0 | 1.00 / 0 | 1.00 / 0 |
| viewBuilds | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |
| menuPackets | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |
| readinessPumps | 3.81 / **3.00** | 3.95 / **3.00** | 3.64 / **3.00** | 3.91 / **3.00** |
| solvesDispatched | 1.00 / 0 | 1.00 / 0 | 1.00 / 0 | 1.00 / 0 |
| completionsRouted | 1.00 / 0 | 1.00 / 0 | 1.00 / 0 | 1.00 / 0 |
| islandsPublished | 1.00 / 0 | 1.00 / 0 | 1.00 / 0 | 1.00 / 0 |
| ticks (idle) | 2400 (2280) | 2399 (2279) | 2400 (2280) | 2400 (2280) |
| idle ticks with any island visit | 2280 | 2279 | 2280 | 2280 |
| idle ticks with any topology snapshot | 2280 | 2279 | 2280 | 2280 |
| idle ticks with any readiness pump | 2280 | 2279 | 2280 | 2280 |

The 700 visits on every idle tick are the review's P1 finding measured: 100 clock accruals in `IslandCoordinator.tick`, plus three pumps (the drain pump, the pump inside `IslandCoordinator.tick`, the explicit pump at the end of `FluidWorldAuthority.tick`), each scanning all 100 islands for eligibility and then offering all 100 to the fair queue because workers are free. The one topology snapshot per idle tick is P2: `deliverRecoveries` calls `topology.snapshot()` every tick, and after the online tick advanced that constructs a new validated snapshot. No fixture has modules, so the module-polling finding is not visible here (it is measured in the WP1 unit test).

All 100 islands start at committed tick 0 with a 100-tick cadence, so they fall due together: 120 of 2400 ticks carry all the work (up to 64 dispatches per tick, the per-tick limit), and the other 2280 are idle.

### 4.2 Per wall second

| counter | stress100 | transient100 | rest100 | mixed100 |
|---|---|---|---|---|
| islandVisits | 15942 | 16156 | 15475 | 16130 |
| topologySnapshots | 20.0 | 20.0 | 20.0 | 20.0 |
| islandSnapshots | 20.0 | 20.0 | 20.0 | 20.0 |
| readinessPumps | 76.1 | 78.9 | 72.7 | 78.2 |
| solvesDispatched | 20.0 | 20.0 | 20.0 | 20.0 |
| completionsRouted | 20.0 | 20.0 | 20.0 | 20.0 |
| islandsPublished | 20.0 | 20.0 | 20.0 | 20.0 |

### 4.3 Throughput, latency, integrity

| metric | stress100 | transient100 | rest100 | mixed100 |
|---|---|---|---|---|
| workers | 12 | 12 | 12 | 12 |
| measured s | 120.0 | 120.0 | 120.0 | 120.0 |
| accepted intervals / s | 19.992 | 20.000 | 19.992 | 19.992 |
| aggregate realtime ratio | 0.9996 | 1.0000 | 0.9996 | 0.9996 |
| held / approximate | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |
| ready-to-publication ms p50 / p95 / max | 9.82 / 57.19 / 64.25 | 21.26 / 65.59 / 79.38 | 9.41 / 57.88 / 65.53 | 16.21 / 59.57 / 70.22 |
| dispatch-to-publication ms p50 / p95 | 3.56 / 4.65 | 5.38 / 15.92 | 2.50 / 5.67 | 4.49 / 12.25 |
| worker ms p50 / p95 | 2.47 / 4.03 | 4.04 / 10.33 | 1.62 / 3.33 | 2.83 / 7.52 |
| engine server ms per tick p50 / p95 / max | 1.235 / 2.060 / 17.89 | 0.903 / 1.222 / 8.62 | 1.145 / 1.764 / 16.01 | 0.855 / 1.254 / 15.74 |
| whole tick ms p50 / p95 | 1.480 / 2.727 | 1.151 / 2.117 | 1.397 / 2.471 | 1.100 / 2.033 |
| mean worker CPU occupancy | 0.0035 | 0.0078 | 0.0023 | 0.0047 |
| component / energy balance (tolerance units) | 3.20e-7 / 1.83e-11 | 3.96e-7 / 8.98e-10 | 4.73e-7 / 2.43e-10 | 3.54e-7 / 1.34e-9 |
| fill time constant s | n/a | 3934 | n/a | 3881 |
| integrity passed | true | true | true | true |

The ready-to-publication p95 of about 57 to 66 ms is the cohort effect: 100 islands fall due in the same tick and the per-tick dispatch limit of 64 sends the remaining 36 one tick later.

## 5. Trajectory references for WP1

`fluidRuntimeTest` on the WP0 commit: 130 tests, 0 failures (129 existing plus `FluidRuntimeDiagnosticsTest`). Archived in `documentation/fluid-scheduler/wp0-reference/`:

| File | SHA-256 |
|---|---|
| `P12-worker-trajectories.json` (FIXED_ONE, FIXED_TWO and AUTOMATIC_TWELVE all `219f9d25...`) | `56332b64ea3f3bde9f23486ec71f708ad25b42044d655003c7982bcf9296be57` |
| `P31-cadence-trajectories.json` | `4dcb80a40266689328916775227f10a3b77f47b31e199ef2e2bd1d2d3441c645` |
