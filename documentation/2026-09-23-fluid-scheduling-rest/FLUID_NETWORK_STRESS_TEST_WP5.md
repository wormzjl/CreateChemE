# Section for `FLUID_NETWORK_STRESS_TEST.md`: fluid scheduling, rest and steady flow (2026-09-23)

Standalone section for the owner's stress-test document (`documentation/2026-09-15-fluid-network/FLUID_NETWORK_STRESS_TEST.md`), written at the end of batch `2026-09-23-fluid-scheduling-rest` (branch `claude/fluid-scheduler`, not merged). Sources: `FLUID_SCHEDULER_WP0_BASELINE.md`, `FLUID_SCHEDULER_WP1_REVIEW.md` to `FLUID_SCHEDULER_WP5_REVIEW.md` and their tables in `documentation/fluid-scheduler/` of the batch worktree.

## Scheduling, rest and steady-flow results — 2026-09-23

### What changed

Islands are scheduled by deadline instead of being visited every tick (WP1); islands whose intervals repeat exactly (REST) or within `eps_s` (STEADY) advance without solving and release their solver state (WP2); presentation runs on engine-owned 100-tick buckets (WP3); checkpoint format 3 saves certificates and copies unchanged island payloads (WP4). WP5 measured the whole process in game.

### Profiles added to the paced harness

| profile | fixture | termination | role |
|---|---|---|---|
| `transient100` | 100 ladders; a 200 kPa generator fills 1000 m3 reservoirs through 100 m of 0.30 m pipe; fill time constant about 3,900 s measured | elapsed window | performance reference that cannot certify |
| `rest100` | 100 closed ladders (the `stress100` rung pressures, no generator or void) | elapsed window | certification savings: every island certifies (STEADY, see below) |
| `mixed100` | networks mod 4: 0 and 1 transient, 2 through (`stress100`), 3 closed | elapsed window | demand follows the transient half |
| `viewers` | `mixed100` with all 365 fixture chunks loaded (10,803 devices), 16 open menus, 80 scripted edits | elapsed window | presentation packets, view builds, edit replies |
| `stress100` | unchanged | elapsed window | now measures the steady path |
| `module` | unchanged | 200 intervals per island | drive index under certification |

Report additions: scheduling counters per tick and idle tick (`FluidRuntimeDiagnostics`), full solves, replayed and identity-advanced spans, certified islands per second, certificate refusals and evidence, a mid-window reference state and publication fingerprints, after-GC heap, save time (cold, warm, next cadence), and every run's configured warm-up and window.

### Windows

From 2026-09-23 every paced profile uses **60 s warm-up and 60 s measured** (`-PfluidStressWarmupSeconds=60 -PfluidStressMeasurementSeconds=60`), the shortest window whose `transient100` latency stays inside the 120 s run-to-run band on all eight quiet runs (`BENCHMARK_WINDOW_LENGTH_ANALYSIS.md`). WP0 to WP3 ran 60 s + 120 s; numbers of the two windows are never compared. In-game runs use 60 s after placement plus a 60 s window, soaks 60 s plus 300 s.

### Paced results (12 automatic workers, `-PfluidBenchmarkWorkers=0`)

| measure | window | before | after |
|---|---|---|---|
| island visits / topology snapshots / pumps per idle tick, all four profiles | 60 + 120 s | 700 / 1.00 / 3.00 | 0 / 0 / 0 |
| `transient100` engine ms per tick p50 (three runs each) | 60 + 120 s | 0.90 to 1.18 | 0.024 to 0.026 |
| `transient100` ready-to-publication p50 (three runs each) | 60 + 120 s | 20.97 to 23.80 ms | 20.16 to 23.57 ms |
| `rest100` full solves, certificates off / on | 60 + 120 s | 2400 | 18 |
| `rest100` after-GC heap, off / on | 60 + 120 s | 1000 MiB | 331 MiB |
| `mixed100` full solves, off / on | 60 + 120 s | 2400 | 1807 |
| `stress100` full solves at `eps_s` 1e-9 / 1e-7 | 60 + 120 s | 2400 | 2400 / 2002 |
| `rest100` autosave one cadence after the window, off / on | 60 + 60 s | 78.3 ms | 7.6 ms |
| `transient100` engine ms per tick p50, three pairs (WP5) | 60 + 60 s | 0.66 to 0.74 | 0.0103 to 0.0107 |
| `transient100` whole tick ms p50, three pairs (WP5) | 60 + 60 s | 0.89 to 0.99 | 0.22 to 0.23 |
| `transient100` ready-to-publication p50 / p95, three pairs (WP5) | 60 + 60 s | 20.02 to 20.80 / 65.5 to 67.1 ms | 19.70 to 20.44 / 64.2 to 68.9 ms (overlapping: within noise) |
| `module` (120 s + 200 intervals per island), certificates off / on (WP5) | interval count | REPLICATE_PASSED, 400 intervals, ready-to-publication p50 254 ms | REPLICATE_PASSED, 400 intervals, 232 ms; no certificate (module-coupled islands are excluded from STEADY) |

`rest100` certifies STEADY, not REST: a closed ladder at hydrostatic balance keeps residual solver flows of 1e-11 to 2e-8 kg/s. `stress100` does not certify at the default `eps_s` of 1e-9 because its vessels are flushed by the throughput for hours (about 2e-7 per interval).

### In-game results (WP5): whole process, before (eb28fc5) and after (branch)

Worlds are built from a void superflat template with `/setblock` datapack functions, so every device carries the mod's placement defaults (1 m3 nitrogen tanks, water generators and nitrogen voids at 101,325 Pa, 1 m pipes of 0.05 m bore, pumps 0.01 m3/s up to 500 kPa). Each run: fresh world copy, scenario placed in one function, 60 s warm-up, 60 s window; JFR (Minecraft's profile without `jdk.ObjectCount`, plus resident set size), a once-a-second process sampler, `jcmd` thread CPU every 10 s, per-tick whole-tick and engine time, and one full collection after the window for the live heap. Dedicated server over RCON (no rendering) and integrated client through the MCP bridge (rendering, 120 fps cap). One run per scenario, build and kind.

Dedicated server:

| scenario (server) | tick ms p50 / p95 (every tick): before -> after | engine ms per tick p50: before -> after | process CPU, cores: before -> after | GC count / pause ms: before -> after | live heap MiB: before -> after | working set MiB (mean): before -> after | allocation MiB/s: before -> after |
|---|---|---|---|---|---|---|---|
| empty | 0.206 / 0.409 -> 0.185 / 0.348 | 0.0217 -> 0.0108 | 0.10 -> 0.10 | 0 / 0 -> 0 / 0 | 304 -> 305 | 1629 -> 1603 | 0.3 -> 0.3 |
| rest100 | 0.339 / 0.714 -> 0.172 / 0.318 | 0.1652 -> 0.0110 | 0.28 -> 0.10 | 4 / 23 -> 0 / 0 | 311 -> 306 | 1327 -> 1389 | 20.4 -> 0.3 |
| through100 | 0.362 / 0.746 -> 0.233 / 0.545 | 0.1909 -> 0.0696 | 0.37 -> 0.35 | 4 / 18 -> 1 / 5 | 318 -> 317 | 1568 -> 1728 | 21.6 -> 12.6 |
| fill100 | 0.579 / 1.293 -> 0.377 / 1.411 | 0.3116 -> 0.1204 | 12.20 -> 12.24 | 679 / 653 -> 620 / 644 | 746 -> 897 | 1829 -> 1973 | 9180.9 -> 9106.6 |
| mixed100 | 0.576 / 1.677 -> 0.374 / 1.127 | 0.3824 -> 0.2106 | 7.81 -> 9.02 | 433 / 424 -> 409 / 410 | 641 -> 541 | 1908 -> 2213 | 5872.8 -> 6984.3 |
| rest1000 | 1.609 / 2.626 -> 0.170 / 0.341 | 1.5190 -> 0.0112 | 0.56 -> 0.12 | 37 / 132 -> 0 / 0 | 378 -> 321 | 1396 -> 1378 | 203.6 -> 0.3 |
| pure100 | 0.333 / 0.784 -> 0.147 / 0.309 | 0.1849 -> 0.0098 | 0.24 -> 0.10 | 1 / 4 -> 0 / 0 | 312 -> 306 | 1441 -> 1888 | 12.5 -> 0.3 |

Integrated client (120 fps cap):

| scenario (client) | FPS mean / p5: before -> after | tick ms p50 / p95 (every tick): before -> after | engine ms per tick p50: before -> after | process CPU, cores: before -> after | GC count / pause ms: before -> after | live heap MiB: before -> after | working set MiB (mean): before -> after | allocation MiB/s: before -> after |
|---|---|---|---|---|---|---|---|---|
| empty | 115.4 / 111.0 -> 119.0 / 118.0 | 0.578 / 0.952 -> 0.595 / 0.894 | 0.0184 -> 0.0111 | 1.46 -> 1.53 | 20 / 56 -> 16 / 54 | 575 -> 573 | 1952 -> 2113 | 122.8 -> 124.0 |
| rest100 | 119.3 / 118.0 -> 119.1 / 119.0 | 0.731 / 1.222 -> 0.589 / 0.891 | 0.1544 -> 0.0121 | 1.71 -> 1.51 | 20 / 69 -> 16 / 62 | 583 -> 578 | 2112 -> 2103 | 144.0 -> 124.2 |
| through100 | 119.0 / 119.0 -> 119.0 / 118.0 | 0.808 / 1.473 -> 0.636 / 1.052 | 0.2128 -> 0.0696 | 1.72 -> 1.75 | 12 / 60 -> 17 / 62 | 589 -> 588 | 2657 -> 2221 | 145.5 -> 136.7 |
| fill100 | 115.1 / 71.0 -> 112.3 / 71.0 | 1.282 / 3.578 -> 1.054 / 3.606 | 0.3604 -> 0.1009 | 13.32 -> 13.27 | 410 / 507 -> 398 / 483 | 1779 -> 1670 | 3107 -> 3182 | 9190.2 -> 9155.3 |
| mixed100 | 116.0 / 87.0 -> 115.6 / 71.0 | 1.140 / 6.123 -> 0.842 / 4.029 | 0.3686 -> 0.1291 | 8.39 -> 7.17 | 260 / 320 -> 198 / 272 | 684 -> 588 | 3112 -> 3346 | 5701.3 -> 4761.0 |
| rest1000 | 119.1 / 118.0 -> 119.2 / 119.0 | 1.811 / 2.820 -> 0.530 / 0.873 | 1.3278 -> 0.0096 | 1.96 -> 1.45 | 35 / 136 -> 12 / 34 | 658 -> 596 | 2436 -> 2401 | 332.1 -> 124.3 |

Soak (branch, dedicated server, 60 s + 300 s): `rest100` 0.076 cores, tick p50 0.13 ms, no collection, REST 100 throughout with no engine work except one autosave; `fill100` 6.9 cores over the window as its lines recovered from start-up holds, filled, and 67 of 100 certified STEADY at the pumps' head limit (33 still held at the end).

Full tables, thread families, the client rows and the regime evidence are in `FLUID_SCHEDULER_WP5_REVIEW.md` and `wp5-tables.md`.

### What remains expensive

* **Held retries.** Pumped fills into closed nitrogen-tank chains placed 100 at a time are held for minutes (wall budget under a cold JIT, Newton failures) and saturate the 12-worker pool at about 12 cores and 9 GiB/s of allocation on both builds; a third of the three-tank lines never recover within 300 s.
* **Placement.** Each placed device recompiles the whole registry: 5,000 devices in one command stall the server for 58 to 60 s on both builds; one more block in a 5,000-device world costs about 23 ms.
* **First autosave after a start** encodes every island payload (about 200 ms with Minecraft's own chunk save in the soaks).

### Commands

```powershell
# paced, one at a time, fresh run id each
.\gradlew.bat fluidServerBenchmark -PfluidBenchmarkProfile=transient100 -PfluidBenchmarkRunId=<id> `
  -PfluidBenchmarkWorkers=0 -PfluidStressWarmupSeconds=60 -PfluidStressMeasurementSeconds=60 -PfluidStressProfile=true `
  -PfluidRestDetection=true --offline
# in game (rig scripts in the batch folder's wp5-rig/)
node campaign.js server; node campaign.js soak; node campaign.js client
```
