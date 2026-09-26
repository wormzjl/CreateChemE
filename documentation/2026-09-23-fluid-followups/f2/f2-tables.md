# F2 tables: placement, removal and edit cost against world size

Date: 2026-09-24. Batch `2026-09-23-fluid-followups`, package F2. Branch `claude/fluid-followups` (worktree `agent-ae139e4fc1b184b36`) over F1's head `111d805`. Machine: AMD Ryzen 7 9700X (8 cores, 16 threads), 48 GB, Windows 11, JDK 21.0.11, `-Xshare:off`; every timed run with no `Endfield.exe` and at least 20 GB free (`f2-logs/gates.log`, `f2-logs/rig/campaign.log`).

**Windows.** Probe and unit-test timings are medians of repeated single events (30 in the probe, 60 in the test) after a warm-up of 10 to 20 events at the same world size; they have no time window. In-game runs: dedicated server, placement, then 60 s warm-up + 60 s measured window, then the marginal phase (after the window). Numbers of different windows are not compared.

## 1. Profile of the old event path (T1)

`f2-logs/probes/PlacementProfileProbe-before.java` on `41de055` (the event path moved into `PhysicalRegistry` without changing it) with the uncommitted timers of `f2-logs/probes/instrumentation-before.patch`; output `f2-logs/probe-before-01.txt`. World: WP5 rest lines (tank, three pipes, tank; 16 columns 6 blocks apart, rows 2 apart), one event per block, each applied before the next, as the rig's datapack places them; a real `IslandCoordinator` that runs no solve. "submit" includes the authority's `at(position)` check before a placement.

### 1.1 One event, median of 30 (ms)

| event | N = 500 | N = 2,000 | N = 5,000 |
|---|---|---|---|
| place a lone tank (new island) | 1.725 | 5.201 | 19.302 |
| remove it | 1.319 | 5.058 | 18.785 |
| extend a line (pipe beside it, dead-end branch) | 1.592 | 5.214 | 19.256 |
| remove that pipe | 1.360 | 5.044 | 18.880 |
| merge two lines (pipe between their tanks) | 1.833 | 5.091 | 18.904 |
| split them (remove it) | 1.381 | 5.046 | 18.920 |
| edit (pipe facing) | 1.646 | 5.024 | 19.250 |
| next 100 blocks of the build, per block | 1.685 | 5.376 | 20.602 |
| build to N, one event per block (s) | 0.69 (0 to 500) | 4.58 (500 to 2,000) | 34.66 (2,000 to 5,000) |
| `at(position)` first after an event / warm | 0.017 / 0.0001 | 0.069 / 0.0001 | 0.181 / 0.0003 |
| registry load (server start), median of 5 | 2.2 | 5.7 | 21.7 |

### 1.2 Where one event's time goes (ms per event, 600 measured events at each size)

| phase | N = 500 | N = 2,000 | N = 5,000 | growth |
|---|---|---|---|---|
| `PhysicalFluidTopology.compile` over the whole registry | 1.388 | 4.415 | 17.256 | worse than O(N) (1.2) |
| ledger snapshot at apply (`applyReady`: copy and validate every registration) | 0.049 | 0.146 | 0.577 | O(N) |
| filter cakes: every island's snapshot (materialises certified ones) + every registration | 0.029 | 0.106 | 0.449 | O(N) |
| ledger snapshot at queue (`queue`) | 0.049 | 0.150 | 0.407 | O(N) |
| owners map copied and filtered | 0.013 | 0.036 | 0.301 | O(N) |
| replacement scan (every compiled island tested against the touched ids) | 0.023 | 0.040 | 0.219 | O(N) |
| boundary stock: every island's snapshot | 0.015 | 0.040 | 0.129 | O(N) |
| chunk index rebuilt | 0.021 | 0.039 | 0.108 | O(N) |
| registration copy (incl. the rebuilt latest map) | 0.023 | 0.043 | 0.096 | O(N) |
| members (inverse of owners) rebuilt | 0.010 | 0.037 | 0.090 | O(N) |
| position map of the proposed registry | 0.013 | 0.042 | 0.089 | O(N) |
| coordinator `topology` (incl. the constructed-stock scan of every island) | 0.019 | 0.015 | 0.056 | O(N) |
| active registry copied | 0.005 | 0.016 | 0.045 | O(N) |
| selected ids (scan of every owner) | 0.009 | 0.006 | 0.018 | O(N) |
| edits and boundary initialisation | 0.015 | 0.014 | 0.017 | O(1) |
| ready event and alignment, submit walk, fences | 0.010 | 0.009 | 0.010 | O(1) (walk: O(component)) |
| **total** | 1.69 | 5.16 | 19.77 | |

### 1.3 The compile itself (`f2-logs/compile-before-01.txt`, `compile-fixed-01.txt`; median of 15)

| devices / islands | before: `compile` | of which `TopologyCompiler.compile` | after bucketing runs (`d33b9b1`) |
|---|---|---|---|
| 500 / 100 | 1.852 ms | 0.610 ms | 1.511 ms |
| 2,000 / 400 | 9.606 ms | 2.354 ms | 4.712 ms |
| 5,000 / 1,000 | 16.473 ms | 3.689 ms | 8.737 ms |
| 10,000 / 2,000 | 52.158 ms | 8.227 ms | 16.686 ms |

The island assembly scanned every run of the registry for every island (O(islands x runs)): 12.8 of the 16.5 ms at 5,000 devices and 44 of 52 ms at 10,000.

## 2. The new event path (T2), same probe

`f2-logs/probes/PlacementProfileProbe.java` on `51f6935`; output `f2-logs/probe-after-01.txt`. "submit" queues the event; "apply" is `applyPending()` right after it, so each row is one event applied alone (a player placing one block); the last row is 100 blocks queued as one command and applied as the world applies them.

| event (median of 30, ms) | N = 500 | N = 2,000 | N = 5,000 | devices compiled per event |
|---|---|---|---|---|
| place a lone tank | 0.138 | 0.131 | 0.125 | 1 |
| remove it | 0.017 | 0.009 | 0.006 | 0 |
| extend a line | 0.051 | 0.039 | 0.030 | 6 |
| remove that pipe | 0.043 | 0.033 | 0.024 | 5 |
| merge two lines | 0.122 | 0.068 | 0.075 | 11 |
| split them | 0.070 | 0.057 | 0.066 | 10 |
| edit (pipe facing) | 0.044 | 0.030 | 0.038 | 5 |
| next 100 blocks, one event each, per block | 0.122 | 0.079 | 0.079 | |
| 100 blocks as one command, per block (1 batch, 100 events, 100 devices compiled) | 0.074 | 0.060 | 0.058 | |
| build to N (s) | 0.10 | 0.12 | 0.22 | |
| `at(position)` first after an event | 0.000 | 0.000 | 0.000 | |
| registry load (server start) | 2.8 | 7.2 | 21.7 | |

A lone tank costs most because a tank's charge is initialised twice (validated when queued, then built when applied: a flash each); that is independent of the world.

## 3. Unit and GameTest evidence (T3)

| test | result |
|---|---|
| `PhysicalRegistryTest.placementCostIsFlatInWorldSize` (synchronous rig, 60 placements after 20 warm-up at each size) | median 0.152 / 0.045 / 0.035 ms at 500 / 2,000 / 5,000 devices (`f2-logs/newtests-02.log`); 6 devices compiled at every size |
| `FluidPlacementGameTests` (GameTest server, `f2-logs/gate-dev4-gametest.log`) | 5,000 devices placed in one call and applied in 523.8 ms; one more pipe 0.637 ms (median of 11) |

## 4. In game, dedicated server (T4)

The owner's minimal set: one run each of `fill100` (800 devices, 100 pumped three-tank lines) and `rest1000` (5,000 devices, 1,000 rest lines) per build; before = `111d805` (F1's head: the old event path), after = `cfbb02d` (this package's code). Runs in that order: after, after, before, before (`f2-logs/rig/campaign.log`, 09:31 to 09:44 local, 28.1 to 28.6 GB free, no `Endfield.exe`), each on a fresh copy of the WP5 template world with the F2 datapack (`f2-rig/gen-datapack.js`), `run-server.js` (`f2-rig/`), analysis `f2-analyze.js` (per run `f2-logs/rig/<run>/f2-summary.json`). `max-tick-time -1` as in WP5.

**Window: 60 s warm-up after the devices are registered + 60 s measured; the marginal phase comes after the window.**

### 4.1 One datapack function placing every device

"Tick-loop gap": end of the last tick before the command to the end of the first tick after it (the rig's per-tick CSV). "First tick after": that tick's whole and engine time (after the fix, the queued events that the command's own 1,024-event batches left apply there).

| run | build | devices | command (RCON round trip) | first tick after: whole / engine | tick-loop gap | "Can't keep up" | WP5 (same function, 60+60 s, `1401cab`) |
|---|---|---|---|---|---|---|---|
| `srv-fill100-f2before-r01` | before | 800 | 1,468 ms | 10.3 / 8.3 ms | 1,527 ms | none | 1,485 ms |
| `srv-fill100-f2after-r01` | after | 800 | **145 ms** | 116.7 / 114.4 ms | **291 ms** | none | |
| `srv-rest1000-f2before-r01` | before | 5,000 | 48,442 ms | 15.1 / 10.8 ms | 48,481 ms | `Running 48416ms or 968 ticks behind` | 58.0 s |
| `srv-rest1000-f2after-r01` | after | 5,000 | **638 ms** | 84.4 / 78.6 ms | **739 ms** | none | |

The after `fill100` command applied its 800 events in one batch at the next tick (counters: 1 batch, 800 events, 800 devices compiled); `rest1000` in 5 batches (4 inside the command at 1,024 events each, 1 at the tick; 5,010 devices compiled: the lines cut by a batch boundary are compiled twice).

### 4.2 The window after placement (60 s)

| run | end state (kinds, statuses) | certified by | process cores | tick ms p50 / p95 / max | engine ms p50 / mean / max | held warnings (whole log) |
|---|---|---|---|---|---|---|
| fill100 before | AWAKE 100: FULL 100 | - | 0.40 | 0.182 / 0.470 / 2.86 | 0.0134 / 0.154 / 16.6 | 2 (start-up round, retried) |
| fill100 after | AWAKE 100: FULL 100 | - | 0.37 | 0.265 / 0.552 / 4.21 | 0.0685 / 0.150 / 10.2 | 10 (start-up round, retried) |
| rest1000 before | REST 1,000: RESTING 1,000 | online tick 600 | 0.079 | 0.166 / 0.281 / 1.41 | 0.0071 / 0.0088 / 0.142 | 0 |
| rest1000 after | REST 1,000: RESTING 1,000 | online tick 600 | 0.092 | 0.169 / 0.360 / 1.60 | 0.0070 / 0.0098 / 0.285 | 0 |

`fill100`'s engine p50 differs while its mean does not: the batch gave the 100 lines consecutive island identities (801 to 900), one per presentation bucket, so a small bucket flush falls on every tick; one by one they got scattered identities, several per bucket and many empty ticks. Held warnings are all "round deadline" on the first round of one-tick slices on a cold JIT (F1's start-up wall-time variance), retried and FULL by online tick 400 in both builds.

### 4.3 One more block in the 5,000-device world (after the window, once per build)

Ten times each, 500 ms apart: a lone tank above the grid (a new island) and its removal, a pipe on top of the first line's first pipe (a dead-end branch: the line's island is replaced) and its removal, the first line's middle pipe turned north and back (a facing edit), twenty lone tanks in one function and their removal. Cost = the command's round trip minus a no-op function's (1 ms in both builds, millisecond resolution) + the engine time of every tick ending in the 450 ms after the reply minus the no-op's (0.06 ms): the server-thread time the block adds, including the first solves of the island it creates or replaces. Median of 10 (max in brackets).

| function | before: cost ms | before: where | after: cost ms | after: largest tick in the 450 ms |
|---|---|---|---|---|
| one tank | 29.3 (208) | in the command (29.5 ms round trip) | **1.47** (9.5) | 0.78 ms |
| break it | 29.0 (122) | a 27.7 ms tick after the command | **0.63** (2.9) | 0.52 ms |
| one pipe beside a line | 28.5 (37.5) | a 25.2 ms tick after the command | **1.62** (2.1) | 1.17 ms |
| break it | 28.0 (32.7) | a 25.9 ms tick | **1.23** (2.1) | 0.82 ms |
| facing edit | 29.3 (32.3) | a 27.8 ms tick | **0.78** (2.0) | 0.66 ms |
| facing back | 28.2 (30.1) | a 26.9 ms tick | **0.95** (2.1) | 0.67 ms |
| twenty tanks, one function | 476.8 (495) | in the command | **3.41** (6.4) | 1.93 ms |
| break the twenty | 413.0 (451) | a 372 ms tick | **0.85** (3.0) | 0.65 ms |

Before, an event on an island that was solving (every island a previous marginal block had replaced or created: a new island solves one-tick slices at first) waited for that island's attempt and applied, whole-registry compile included, at a later tick; a new tank touches no island and applied inside the command. After, every one of them applies at a tick hook.

## 5. Not run (owner's instruction during the task)

Paced `transient100` with certificates on (its islands come from a checkpoint and see no topology event in the window; P12 and P31 below are byte-identical); a separate `rest1000` recertification run (4.2 shows REST 1,000 in the placement run); repeats; 500 and 2,000 devices in game (probe and scaling test instead); the WP3 `viewers` figure.

## 6. Gates (T5)

On `cfbb02d` (the last code commit; `b86b147` adds only the changelog), one Gradle invocation at a time, `JAVA_OPTS=-Xshare:off`, no game running (28.0 GB free); sequence in `f2-logs/gates.log`, logs `f2-logs/gate-final-*.log`, script `f2-logs/gates.sh`.

| gate | result |
|---|---|
| `fluidScienceTest --rerun` | 161 tests, 0 failures, 0 errors |
| `fluidRuntimeTest --rerun` | 210 tests (202 + 8 `PhysicalRegistryTest`), 0 failures, 0 errors |
| `fluidNetworkBenchmark --rerun` | 30/9, 19/14, 37/3 accepted/rejected substeps (2, 10, 100 reservoirs), all converged (`f2-logs/final-M2-network-scaling-screening.json`) |
| `fluidSolverRegression -PfluidRegressionMode=exact --rerun` | chain-100 max deviation 0.000e+00 in state/moles, temperature, phase fraction and flow |
| `runFluidGameTestServer` (`-PfluidGameTestRunId=f2-dev4`, on `cfbb02d`) | All 30 required tests passed (29 + `FluidPlacementGameTests`; scheduler self-verification on) |
| P12 / P31 fingerprints | byte-identical to `fluid-scheduler/wp2-logs`: SHA-256 `56332b64ea3f3bde...` and `4dcb80a40266...` (`f2-logs/final-P12-*.json`, `final-P31-*.json`; also at `dev2`) |

Earlier runs of the same gates during the work: `extract` (GameTest on `41de055`, 29 passed), `dev2` (runtime 202, before the new tests), `dev3` (GameTest 29 with `51f6935`), `dev4` (GameTest 30); `dev1` was lost to a session restart and its configuration-cache corruption (logged in `gates.log`).
