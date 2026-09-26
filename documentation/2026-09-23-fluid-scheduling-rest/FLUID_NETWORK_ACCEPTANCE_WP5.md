# Section for `FLUID_NETWORK_ACCEPTANCE.md`: fluid scheduling, rest and steady flow (2026-09-23)

Standalone section for the owner's acceptance record (`documentation/2026-09-15-fluid-network/FLUID_NETWORK_ACCEPTANCE.md`), written at the end of batch `2026-09-23-fluid-scheduling-rest`. Branch `claude/fluid-scheduler`, not merged. Evidence: `FLUID_SCHEDULER_WP0_BASELINE.md` and `FLUID_SCHEDULER_WP1_REVIEW.md` to `FLUID_SCHEDULER_WP5_REVIEW.md` with their tables and logs in `documentation/fluid-scheduler/` of the batch worktree; batch review `FLUID_ISLAND_REST_REVIEW.md`.

## Scheduling, rest and steady-flow checkpoint

Branch head `1401cab`: **161 science tests, 193 runtime tests, 28 GameTests pass; network benchmark 30/9, 19/14, 37/3; solver regression exactly zero; P12 and P31 byte-identical to the WP0 values** (`wp5-logs/gates.log`). Paced benchmark artifact `createcheme-0.2.0.jar` SHA-256 `b592365892250cdd97101273b5b063ebe2173187ec5f44c2dc24bddff67de232` (branch at `1401cab` content); the baseline side of the WP5 pairs is `5f29ae8a2e6eae7f7b409afb90bc65ceeae311a246660894b95ec4249983bf10` (eb28fc5 plus the uncommitted, inert in-game rig). Nothing is merged; the batch review is `FLUID_ISLAND_REST_REVIEW.md`.

### Gates per work package

| gate | WP1 `f3ccbae` | WP2 `e924eae` | WP3 `2f8d43a` | WP4 `ce25b78` | WP5 `1401cab` |
|---|---|---|---|---|---|
| `fluidScienceTest` | 161 / 0 | 161 / 0 | 161 / 0 | 161 / 0 | 161 / 0 |
| `fluidRuntimeTest` | 148 / 0 | 177 / 0 | 189 / 0 | 193 / 0 | 193 / 0 |
| `fluidNetworkBenchmark` (2/10/100 reservoirs) | 30/9, 19/14, 37/3 | same | same | same | 30/9, 19/14, 37/3 |
| `fluidSolverRegression` exact | 0.000e+00 | 0.000e+00 | 0.000e+00 | 0.000e+00 | 0.000e+00 |
| GameTests | 21 | 22 | 26 | 28 | 28 passed |
| P12 / P31 fingerprints | byte-identical to WP0 | same | same | same | byte-identical |

Scheduler self-verification (every island's readiness, deadline and pending count re-derived from scratch at every pump and tick, and every cached payload re-encoded and compared) is on in every unit suite and GameTest run.

### Benchmark windows and acceptance numbers

Every paced profile now uses a 60 s warm-up and a 60 s measured window, 12 automatic workers, one JVM at a time, a machine gate before each run (no `Endfield.exe`, at least 20 GB free), and a fresh run id; WP0 to WP3 figures used 60 s + 120 s and are not compared with 60 s ones. Paced acceptance for this batch:

| requirement | acceptance number | status |
|---|---|---|
| no per-tick island work (R1) | idle-tick island visits, topology snapshots and pumps 0 in all four profiles (700 / 1 / 3 before); in game 0 island visits over a 300 s resting soak | met |
| `transient100` within noise (R7) | three pairs at 60 s + 60 s (WP5): every throughput and latency range overlaps (ready-to-publication p50 20.02 to 20.80 ms eb28fc5 against 19.70 to 20.44 ms branch, p95 65.5 to 67.1 against 64.2 to 68.9 ms); engine ms per tick p50 0.66 to 0.74 against 0.0103 to 0.0107; all audits PASS, 0 held intervals in the windows | met |
| `rest100` at zero fluid demand | 100 of 100 certified, full solves 2400 to 18 (60 + 120 s) and 1200 to 20 (60 + 60 s), allocator at one admitted worker, zero-demand samples 120 of 120 | met (STEADY, not REST: see the batch review) |
| presentation (R2) | 1.000 to 1.015 live payloads per 100 ticks per menu; 80 of 80 edits answered on the first bucket after them | met |
| persistence (R5) | 1,000 certified islands load with no solve until their horizon; certified autosave 7.6 ms against 78 ms | met |
| determinism (R6) | P12, P31 byte-identical; certificates-on runs bitwise equal to off where nothing certifies; viewers bitwise neutral | met, except the pre-existing start-up hold |
| `module` profile | `module-wp5-off-r01` and `module-wp5-on-r01`: both REPLICATE_PASSED, 200 FULL intervals per island, 0 holds, module committed tick 22200, 0 pending transfers, 1 planned capacity record, identical balance residuals; no certificate issued with certificates on (module-coupled islands carry scheduled transfers and are excluded from STEADY) | met for correctness; certification under modules is covered by `CausalModuleCoordinatorTest`, not by this profile |

### In-game acceptance numbers (WP5, 60 s + 60 s, one run each)

Dedicated server, before (eb28fc5) -> after (branch):

| scenario (server) | tick ms p50 / p95 (every tick): before -> after | engine ms per tick p50: before -> after | process CPU, cores: before -> after | GC count / pause ms: before -> after | live heap MiB: before -> after | working set MiB (mean): before -> after | allocation MiB/s: before -> after |
|---|---|---|---|---|---|---|---|
| empty | 0.206 / 0.409 -> 0.185 / 0.348 | 0.0217 -> 0.0108 | 0.10 -> 0.10 | 0 / 0 -> 0 / 0 | 304 -> 305 | 1629 -> 1603 | 0.3 -> 0.3 |
| rest100 | 0.339 / 0.714 -> 0.172 / 0.318 | 0.1652 -> 0.0110 | 0.28 -> 0.10 | 4 / 23 -> 0 / 0 | 311 -> 306 | 1327 -> 1389 | 20.4 -> 0.3 |
| through100 | 0.362 / 0.746 -> 0.233 / 0.545 | 0.1909 -> 0.0696 | 0.37 -> 0.35 | 4 / 18 -> 1 / 5 | 318 -> 317 | 1568 -> 1728 | 21.6 -> 12.6 |
| fill100 | 0.579 / 1.293 -> 0.377 / 1.411 | 0.3116 -> 0.1204 | 12.20 -> 12.24 | 679 / 653 -> 620 / 644 | 746 -> 897 | 1829 -> 1973 | 9180.9 -> 9106.6 |
| mixed100 | 0.576 / 1.677 -> 0.374 / 1.127 | 0.3824 -> 0.2106 | 7.81 -> 9.02 | 433 / 424 -> 409 / 410 | 641 -> 541 | 1908 -> 2213 | 5872.8 -> 6984.3 |
| rest1000 | 1.609 / 2.626 -> 0.170 / 0.341 | 1.5190 -> 0.0112 | 0.56 -> 0.12 | 37 / 132 -> 0 / 0 | 378 -> 321 | 1396 -> 1378 | 203.6 -> 0.3 |
| pure100 | 0.333 / 0.784 -> 0.147 / 0.309 | 0.1849 -> 0.0098 | 0.24 -> 0.10 | 1 / 4 -> 0 / 0 | 312 -> 306 | 1441 -> 1888 | 12.5 -> 0.3 |

Integrated client:

| scenario (client) | FPS mean / p5: before -> after | tick ms p50 / p95 (every tick): before -> after | engine ms per tick p50: before -> after | process CPU, cores: before -> after | GC count / pause ms: before -> after | live heap MiB: before -> after | working set MiB (mean): before -> after | allocation MiB/s: before -> after |
|---|---|---|---|---|---|---|---|---|
| empty | 115.4 / 111.0 -> 119.0 / 118.0 | 0.578 / 0.952 -> 0.595 / 0.894 | 0.0184 -> 0.0111 | 1.46 -> 1.53 | 20 / 56 -> 16 / 54 | 575 -> 573 | 1952 -> 2113 | 122.8 -> 124.0 |
| rest100 | 119.3 / 118.0 -> 119.1 / 119.0 | 0.731 / 1.222 -> 0.589 / 0.891 | 0.1544 -> 0.0121 | 1.71 -> 1.51 | 20 / 69 -> 16 / 62 | 583 -> 578 | 2112 -> 2103 | 144.0 -> 124.2 |
| through100 | 119.0 / 119.0 -> 119.0 / 118.0 | 0.808 / 1.473 -> 0.636 / 1.052 | 0.2128 -> 0.0696 | 1.72 -> 1.75 | 12 / 60 -> 17 / 62 | 589 -> 588 | 2657 -> 2221 | 145.5 -> 136.7 |
| fill100 | 115.1 / 71.0 -> 112.3 / 71.0 | 1.282 / 3.578 -> 1.054 / 3.606 | 0.3604 -> 0.1009 | 13.32 -> 13.27 | 410 / 507 -> 398 / 483 | 1779 -> 1670 | 3107 -> 3182 | 9190.2 -> 9155.3 |
| mixed100 | 116.0 / 87.0 -> 115.6 / 71.0 | 1.140 / 6.123 -> 0.842 / 4.029 | 0.3686 -> 0.1291 | 8.39 -> 7.17 | 260 / 320 -> 198 / 272 | 684 -> 588 | 3112 -> 3346 | 5701.3 -> 4761.0 |
| rest1000 | 119.1 / 118.0 -> 119.2 / 119.0 | 1.811 / 2.820 -> 0.530 / 0.873 | 1.3278 -> 0.0096 | 1.96 -> 1.45 | 35 / 136 -> 12 / 34 | 658 -> 596 | 2436 -> 2401 | 332.1 -> 124.3 |

Regimes at the end of each window (branch): `rest100` REST 100, `rest1000` REST 1,000, `pure100` STEADY 100, `through100` and `fill100` none certified, `mixed100` 25 REST. Resting soak (300 s): no solve, island visit, pump, deadline or collection; one autosave. Not accepted as a performance claim: the held-retry scenarios (`fill100`, the fill half of `mixed100`) vary by up to 16 % between runs of one build and cost the same on both.

### Matrix rows touched by this batch

| ID | change in this batch |
|---|---|
| P11, P35 | Events take their owners from a fence index; a certified island is aligned to an event's tick by materialisation. |
| P12 | Still byte-identical; a fourth mode `AUTOMATIC_TWELVE_CERTIFICATES` (certificates on, entry evidence evaluated every interval) matches the others frame by frame. |
| P29 | Replay scales the recorded boundary transfers; the ledger moves by exactly the inventory change (1.2e-15 relative at `eps_s` 1e-7). |
| P31 | Rows run with certificates off and stay byte-identical. |
| P34, P45 | Checkpoint format 3: clocks, fences, pending material, certificates with validity signatures round-trip and are validated; formats 1 and 2 are refused (no compatibility, owner rule). |
| P36, P37 | A completion drain that exhausts its 64-per-tick budget owes one continuation (`FluidCompletionBudgetGameTests`); in game the budget was reached 32 times in the `fill100` soak, and each exhausted drain owed and deferred exactly one continuation (32 and 32). |
| P38, P43 | The module host decides from stored island state and a per-island drive index; certified feed islands wake exactly at their drives. |
| P44 | `viewers`: with 10,803 loaded devices and 16 menus, every island that was not edited or held follows the unloaded trajectory bit for bit. |
| P47 | Property hold: a certified island and a module-coupled set freeze committed time, keep debt, discard their certificates at resume and requalify. `EnergyReferenceMigrationTest` was removed with the format-2 reader. |
| P48, P49 | Menu protocol `fluid-4`: static and live payloads on the island's bucket; edits and recoveries are queued inputs answered on the following bucket. |
| P50 | `transient100`, `rest100`, `mixed100`, `viewers` added with elapsed-window termination; three `transient100` pairs at 60 s + 60 s (WP5). |
| P51 | One more current-artifact replicate pair on the branch (`module-wp5-off-r01`, `module-wp5-on-r01`, 12 automatic workers): both pass; certificates never engage on module-coupled islands. |
| P52 | Not run as specified (30-minute paced soak). WP5 ran two 300 s in-game soaks on the branch's dedicated server (`rest100`, `fill100`). |
