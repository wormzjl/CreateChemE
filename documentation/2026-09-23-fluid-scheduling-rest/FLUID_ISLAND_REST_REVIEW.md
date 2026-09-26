# Fluid island scheduling, rest and steady flow: batch review (WP0 to WP5)

Date: 2026-09-23. Batch `2026-09-23-fluid-scheduling-rest`. Branch `claude/fluid-scheduler` (worktree `agent-ae139e4fc1b184b36`) at `1401cab`, 30 commits over `main` @ `42fdf41` (27 of WP0 to WP4, 3 of WP5), not pushed, not merged. Plan: `FLUID_ISLAND_REST_PLAN.md` revision 3 (main checkout, `documentation/2026-09-23-fluid-scheduling-rest/`), the review its WP5 row asks for. Per-package reports: `FLUID_SCHEDULER_WP0_BASELINE.md`, `FLUID_SCHEDULER_WP1_REVIEW.md` to `FLUID_SCHEDULER_WP5_REVIEW.md` and their tables, all in this folder.

## 0. Verdict

* **Delivered as planned, with recorded deviations.** Every requirement of plan section 2 is met (section 1.1). The deviations of the five packages are collected in section 3; none changes the plan's contract, and three were adopted from findings (quiet-pipe exemption, topology format 4, round-budget check each tick).
* **What the owner asked for is visible in game.** On a dedicated server with the mod's placement defaults, 1,000 resting networks cost 0.12 cores on the branch against 0.56 on the scheduler's predecessor (an empty world costs 0.10), the engine's share of a tick falls from 1.52 to 0.011 ms, whole-tick p50 from 1.61 to 0.17 ms, and allocation from 204 MiB/s to the empty world's 0.3 MiB/s with no collection; steady through-flow without a vessel certifies and costs the same floor. Awake networks keep their solves and lose most of their server-thread overhead. The client stays at its 120 fps cap on both builds unless held retries saturate the workers.
* **What it does not change:** held retries (start-up wall-budget holds and solver failures of pumped fills under placement defaults) cost about 12 cores on both builds for minutes; placement is quadratic in the world's device count on both builds. Both are pre-existing and are listed for the owner (section 5).
* **Paced reference unchanged within noise.** `transient100`, which cannot certify, keeps its throughput and latency (every range overlaps over three pairs at the 60 s window) while its server-thread engine time falls from 0.66 to 0.74 ms to about 0.010 ms per tick.
* **Gates** on the branch head `1401cab`: science 161/0, runtime 193/0, network 30/9, 19/14, 37/3, regression exactly zero, 28 GameTests, P12 and P31 byte-identical to WP0; the in-game rig is opt-in and inert in every suite.
* **Five owner decisions remain open** (section 5), plus two items to schedule. Nothing is merged or pushed.

## 1. Plan against delivery

### 1.1 Requirements (plan section 2)

| requirement | delivered | evidence | status |
|---|---|---|---|
| R1 no per-tick process work | Deadline heap on the shared online epoch; the tick hook checks `nextDue()`, pumps only when something became ready, drains the mailbox, flushes a presentation bucket and checks open rounds' wall budgets (O(open rounds)). | WP1: idle-tick island visits 700 to 0, topology snapshots 1 to 0, pumps 3 to 0 in all four profiles; `idleIslandsCostNothingPerTickAtOneHundredOrAThousand`; WP2 `certifiedIslandsCostNothingPerTick...`. WP5 in game: 0 island visits, pumps and deadlines over a 300 s resting soak (section 4.3). | met |
| R2 engine-owned presentation | `fluid-4` static/live payloads, 100-tick buckets keyed by owning island, queued inputs answered on the following bucket, loaded devices only marked. | WP3: 1.000 to 1.015 live payloads per 100 ticks per menu, 80/80 edits answered on the first bucket after them, none at their own tick; dev-client screenshots. | met, with deviations 3.3.1 and 3.3.2 below |
| R3 rest | REST certificate: exact identity, no solve, solver caches released, committed time materialised on demand. | WP2 tests; dead-headed lines and lone tanks certify REST; in game the closed tank lines of `rest100`/`rest1000` certify REST (WP5). | met; the harness `rest100` ladders certify STEADY, not REST (finding 2.1.1) |
| R4 steady flow | STEADY certificate: scaled replay of the last solved interval, relative budget horizon, revalidation. | WP2: `rest100` 2400 to 18 full solves, deviation at most 2.4e-8; through-flow at 1e-7 deviation at most 1.5e-8, ledger conserved to 1.2e-15. | met; through-flow ladders never certify at the default 1e-9 (open decision 1) |
| R5 persistence | Checkpoint format 3 (NBT), cached payloads, persisted certificates with validity signatures, validation on load, formats 1 and 2 refused. | WP4: round trips, 13 invalid-field refusals, signature discards, 1,000 certified islands load with no solve; warm save 7.6 ms against 78 ms. | met |
| R6 determinism | Rest, replay and wake depend on committed results, fences, deadlines and ticks only. | P12 and P31 fingerprints byte-identical at every WP; certificates-on runs bitwise equal to certificates-off where nothing certifies; viewers bitwise neutral. | met, except the pre-existing start-up hold (open decision 3) |
| R7 measurement | Non-certifiable `transient100` reference; savings on certifying fixtures; identical observation overhead both sides. | WP0 to WP4 paced pairs; WP5 in-game server and client pairs and paced `transient100` ranges. | met; three repeats only for `transient100` (deviation 3.5.6) |

### 1.2 Design sections (plan section 3) and work packages (section 7)

| plan item | WP | delivered | commits |
|---|---|---|---|
| 3.1 deadline scheduler, shared epoch, completions, rounds, retries, allocator, events, recoveries | WP1 | as planned; deviations 3.1.1 to 3.1.8 | `a0a791a`, `b697adc`, `cadd182`, `74b62e3` |
| 3.2 materialised time | WP2 | `IslandClock.rest`, materialisation on demand only | `3075004` |
| 3.3 certificates, horizon, invalidation, drive index | WP2 | as planned; deviations 3.2.1 to 3.2.9 | `3075004`, `7d89784`, `4f1686e` |
| 3.4 presentation | WP3 | as planned; deviations 3.3.1 to 3.3.8 | `9d58e95`, `f081342` |
| 3.5 persistence, format 3 | WP4 | as planned; deviations 3.4.1 to 3.4.9 | `ff2fba1`, `4f39701`, `f6b5dd8` |
| 3.6 property hold, requalification | WP2 (memory), WP4 (saved) | as decided in revision 3 | `3075004`, `86d3332` |
| 3.7 worker pool | unchanged | allocator shrinks to one admitted worker at zero demand (WP2 rest100: 1 worker at the end) | - |
| 4 numerical contract, six `[fluid]` keys | WP2 | defaults and ranges exactly as the table | `3075004` |
| 5 tests 1 to 9 | WP1 to WP4 | items 1 to 8 covered; item 9 is WP0, WP2 to WP5 | see gate history |
| 6 benchmarks | WP0, WP2 to WP5 | `transient100`, `rest100`, `mixed100` (WP0), certificate measurements (WP2), `viewers` (WP3), save time (WP4), in-game rig, paced ranges and `module` (WP5) | `eb28fc5`, `82568c6`..`e924eae`, `04632e9`, `2f8d43a`, `4dcfa01`, `36caef6`, `39aabac` |
| 7 WP5: benchmarks, stress-test and acceptance sections, this review | WP5 | this document, `FLUID_SCHEDULER_WP5_REVIEW.md`, `FLUID_NETWORK_STRESS_TEST_WP5.md`, `FLUID_NETWORK_ACCEPTANCE_WP5.md` | `36caef6`, `39aabac`, changelog |

## 2. Findings that change what the plan expected

### 2.1 From the paced harness

1. **Closed islands certify STEADY, not REST (WP2).** A closed ladder at hydrostatic balance is found by Newton iteration and keeps residual flows of 1e-11 to 2e-8 kg/s, so plan section 6's "every island certifies REST" in `rest100` cannot hold. They certify STEADY with windows of 926 to 17,280 intervals (median about 4.1 h); the cost is one revalidating solve per window. Exact REST arises only where a row pins a flow to zero: a dead-headed line, a pump at shutoff, a blocked pipe, an island with no pipe. The in-game closed tank lines are of the last kind in effect: two identical tanks at one elevation give exactly zero flow and certify REST (WP5).
2. **Through-flow never certifies at `eps_s` 1e-9 (WP2).** The `stress100` ladders are a slow physical transient: their holdup is flushed by the throughput with e-folding times of 2,100 to 8,400 s and a per-interval change of 6e-8 to 2e-7 for hours. At 1e-8 nothing certifies either; at 1e-7, 92 certificates are issued, full solves fall 16.6 %, and every deviation stays under 1.5e-8. In game, a through line with a vessel stays awake for the same reason, while a through line with no vessel certifies STEADY within seconds (WP5).
3. **Revalidation never renews a drifting certificate (WP2).** At 1e-7 a revalidation compares the new slice with the interval certified K intervals earlier, so 74 of 74 horizon wakes dropped and requalified.
4. **The flow test needed an exemption for quiet pipes (WP2, `4f1686e`).** The plan's relative flow test divided solver noise by solver noise on settled closed ladders; a pipe that moves less than `eps_s` of the inventory behind it is now exempt.
5. **Event application cost with many devices (WP3).** `applyPending` compiles the whole active registry and reads every island for each event: the first edit with 10,803 loaded devices cost about 205 ms JIT-cold. WP5 measures the same cost as placement cost in game (section 4.3).
6. **Save size bound (WP4).** Certified payloads carry the interval's starting graph; the 64 MiB bound on ledger plus payloads caps a world at about 430 certified islands of `rest100` size (open decision 4).

### 2.2 From the in-game measurement (WP5)

1. **Resting networks cost what an empty world costs** (WP5 section 3.4). On the dedicated server, 1,000 resting lines take 0.12 cores against 0.56 on eb28fc5 and 0.10 for an empty world; the engine's share of a tick falls from 1.52 to 0.011 ms, whole ticks from 1.61 to 0.17 ms (p50), and allocation from 204 to 0.3 MiB/s; no collection runs in the window. Over a 300 s resting soak the only engine work was one autosave.
2. **Awake networks cost the same solves and less server thread.** `through100` dispatches the same 1,400 solves on both builds; process CPU is unchanged within 6 % while the engine's per-tick cost falls to about a third.
3. **Held retries are untouched by this batch.** Pumped fills into closed nitrogen-tank chains placed 100 at a time hold for minutes at about 12 cores on both builds; a third of the three-tank lines never recover within 300 s, and six-tank chains, vented chains and tank-to-tank pump transfers do not recover in the probes. Start-up holds (open decision 3) and solver failures for liquid entering gas-filled vessels are the cause; they dominate every scenario that contains such lines, and repeats of one build differ by up to 16 % in CPU (WP5 section 3.5).
4. **Placement is quadratic** (pre-existing): every placed device recompiles the whole registry; 5,000 devices placed in one command stall the server for 58 to 60 s on both builds, and one more block in a 5,000-device world costs about 23 ms.
5. **Memory.** In game the retained-memory gain is modest because player-built lines are small: 1,000 resting lines hold 57 MiB more live heap awake (eb28fc5, 378 MiB) than certified (branch, 321 MiB) on the dedicated server, and 62 MiB more in the client (658 against 596 MiB). The working set follows the committed Java heap, not the live heap, and is not a retained-memory measure.
6. **Minecraft's `ServerTickTime` does not include the engine's tick** (NeoForge fires `ServerTickEvent.Post` after Minecraft tallies the tick), and Minecraft's `/jfr` profile forces full collections; the in-game rig measures ticks itself and records with its own JFR settings.
7. **The first autosave after a start encodes every payload** (about 200 ms with Minecraft's chunk save in both soaks); seeding the payload cache at load is the WP4 follow-up that removes the fluid part.

## 3. Deviations from the plan, collected

Each item names its package; the reasoning is in that package's report.

### 3.1 WP1 (deadline scheduler)

1. The two redundant per-tick pumps became O(1) `pumpIfUseful` checks, keeping the exact dispatch tick of every case the old pumps covered.
2. Round timeout under server lag: resolved in `74b62e3` by checking every open round's wall budget each tick, O(open rounds).
3. A drain continuation posted in the exhausted tick runs in that tick in 1.21.1 (`TickTask(1)` does not delay), so it defers to the next tick's drain; it cannot spin and strands nothing.
4. Recoveries are delivered on queueing and on a bounded `RECOVERY_RETRY` deadline (20 ticks, 1 tick after a cap hit), not from login or chunk-load hooks.
5. Added: an attempt that drains after its round closed reports `onReleased`, which books an immediate module deadline.
6. `MODULE_HORIZON` deadlines were only a safety net until certificates existed.
7. A drain that routed only calculator completions no longer triggers a fluid demand observation unless a ready fluid owner can use the freed worker.
8. Existing clock tests keep the detached (constant-epoch) path; `workers = 0` (12 automatic) in benchmarks against the 8 to 10 rule for solver campaigns; the P31 report became byte-stable ordered JSON in WP0.

### 3.2 WP2 (certificates)

1. Certificates lived in memory only until WP4.
2. The certificate stores the last solved interval and scales by the fraction of it, not per-second rates (same arithmetic).
3. Solids never replay: entry requires unchanged node solids, and STEADY excludes solids in transport and filters (plan section 9 defers cake accumulation).
4. Energy is compared against `max(|U|, nRT)`, not `|U|`.
5. Added a first-order temperature and pressure drift test so pump heating and slow depletion cannot certify.
6. Quiet pipes are exempt from the relative flow test (finding 2.1.4).
7. REST also requires zero gross pipe transfer, pump work and boundary transfer; gross flow with a zero average is refused.
8. The module host reads stored island state and never materialises a certified island mid-decision.
9. `viewers` and `module` rows were left to WP3 and WP5; one quiet pair per profile instead of three repeats.

### 3.3 WP3 (presentation)

1. Buckets are keyed by the owning island, not `identity % 100`, so a bucket reads each certified island once.
2. No `PRESENTATION_BUCKET` heap entry: the presentation keeps its own next-due tick, checked in O(1) at the end of the world tick.
3. Added: a presentation read materialises a certified island but never onto a wake it has not acted on.
4. "A menu that opens shows the last delivered view" is a 32-device client cache; the server sends nothing at open.
5. After a `Queued ...` reply, `Applied` follows on the first bucket after the event applies.
6. A refusal wrapped by Gson is unwrapped to its innermost reason.
7. Two immediate messages remain outside simulation status: the `debugChat` HELD broadcast and the "waiting for its world identity" action bar on use.
8. Added: a view that fails to build is logged and the bucket continues; `FluidWorldAuthority.at()` uses a position index.

### 3.4 WP4 (persistence)

1. The energy-datum migration was removed with the format-2 reader, not left untouched: it served only old saves.
2. The certificate's interval starting graph sits in the cached payload, so a certified island's whole save is copied.
3. Added topology format 4: the topology is saved without its online tick and its encoding kept while the ledger is unchanged (warm save 64 to 4 ms).
4. The payload generation also changes at dispatch, fence changes, wakes, holds, resumes and stops at a fence.
5. A certificate saved during a property hold is not kept.
6. A certificate whose saved kind or horizon its own interval does not reproduce refuses the load instead of being discarded.
7. Not persisted: a revalidation in progress, a qualification streak, diagnostics, the retry-span hint, solver caches.
8. The first save after a load encodes every payload (the cache starts empty).
9. The module-coupled "pair" of plan 5 item 8 is three islands and two fixed-split modules, because a fixed-split module needs one feed and two products.

### 3.5 WP5 (benchmarks)

1. Server row on the dedicated server and client row in the integrated client, per the coordinator's instruction during the task (the owner accepted the EULA).
2. JFR window recording with Minecraft's profile minus `jdk.ObjectCount`, plus resident set size, instead of `/jfr start`; per-tick time from the rig, not `minecraft.ServerTickTime`.
3. Client commands typed into chat; the bridge's `execute_command` never reaches the server.
4. `fill100` is a held-retry regime, not a clean filling one: no default-placement layout fills for the whole window without start-up holds (WP5 section 2).
5. Added the `pure100` server pair and repeats of `fill100` and `mixed100`.
6. One in-game run per scenario, build and kind; three pairs only for paced `transient100`.
7. The `module` profile: see section 4.2.

## 4. Measurements

### 4.1 Gate history

| gate | WP0 `0176080` | WP1 `f3ccbae` | WP2 `e924eae` | WP3 `2f8d43a` | WP4 `ce25b78` | WP5 `1401cab` |
|---|---|---|---|---|---|---|
| `fluidScienceTest` | - | 161 / 0 | 161 / 0 | 161 / 0 | 161 / 0 | 161 / 0 |
| `fluidRuntimeTest` | 130 / 0 | 148 / 0 (149 after `74b62e3`) | 177 / 0 | 189 / 0 | 193 / 0 | 193 / 0 |
| `fluidNetworkBenchmark` (accepted/rejected substeps, 2/10/100 reservoirs) | - | 30/9, 19/14, 37/3 | same | same | same | 30/9, 19/14, 37/3 |
| `fluidSolverRegression -PfluidRegressionMode=exact` | - | 0.000e+00 | 0.000e+00 | 0.000e+00 | 0.000e+00 | 0.000e+00 |
| `runFluidGameTestServer` | - | 21 passed | 22 passed | 26 passed | 28 passed | 28 passed |
| P12 / P31 fingerprints | `56332b64...` / `4dcb80a4...` recorded | byte-identical | byte-identical | byte-identical | byte-identical | byte-identical |

Scheduler self-verification (`-Dcreatecheme.fluid.scheduler.verify=true`) is on in every unit suite and GameTest run from WP1 on.

### 4.2 Paced harness (dedicated GameTest server, fixture chunks unloaded unless stated)

| profile | window | before | after | source |
|---|---|---|---|---|
| idle-tick island visits / topology snapshots / pumps (all four profiles) | 60 + 120 s | 700 / 1.00 / 3.00 per idle tick | 0 / 0 / 0 | WP1 3.1 |
| `transient100` engine ms per tick p50, three runs each | 60 + 120 s | 0.90 to 1.18 | 0.024 to 0.026 | WP1 3.3 |
| `transient100` ready-to-publication p50, three runs each | 60 + 120 s | 20.97 to 23.80 ms | 20.16 to 23.57 ms | WP1 3.3 |
| `rest100` full solves in window, certificates off / on | 60 + 120 s | 2400 | 18 | WP2 6 |
| `rest100` after-GC heap, off / on | 60 + 120 s | 1000 MiB | 331 MiB | WP2 6 |
| `mixed100` full solves, off / on | 60 + 120 s | 2400 | 1807 | WP2 6 |
| `stress100` at `eps_s` 1e-7, full solves | 60 + 120 s | 2400 | 2002 | WP2 5 |
| `viewers` live payloads per 100 ticks per menu, off / on | 60 + 120 s | 1.000 | 1.015 | WP3 3 |
| `rest100` save one cadence after the window, off / on | 60 + 60 s | 78.3 ms | 7.6 ms | WP4 3 |
| `rest100` full solves, off / on | 60 + 60 s | 1200 | 20 | WP4 table 2 |
| `transient100` pairs (WP5), three runs each | 60 + 60 s | engine ms per tick p50 0.66 to 0.74, whole tick p50 0.89 to 0.99 ms, ready-to-publication p50 / p95 20.02 to 20.80 / 65.5 to 67.1 ms | 0.0103 to 0.0107, 0.22 to 0.23 ms, 19.70 to 20.44 / 64.2 to 68.9 ms (latency ranges overlap: within noise) | WP5 |
| `module` (interval count), certificates off / on (WP5) | 120 s warm-up + 200 intervals per island | off: REPLICATE_PASSED, 400 solved intervals, module committed tick 22200 | on: the same; no certificate issued (both islands carry module transfers, excluded from STEADY by plan section 9) | WP5 |

Windows differ between rows and are stated; 60 s and 120 s numbers are not compared with each other (`BENCHMARK_WINDOW_LENGTH_ANALYSIS.md`).

### 4.3 In game (WP5)

Dedicated server, 60 s warm-up after placement + 60 s window, one run each (before = eb28fc5, after = branch; full rows, client rows, soaks and repeats in `wp5-tables.md`):

| scenario (server) | tick ms p50 / p95 (every tick): before -> after | engine ms per tick p50: before -> after | process CPU, cores: before -> after | GC count / pause ms: before -> after | live heap MiB: before -> after | working set MiB (mean): before -> after | allocation MiB/s: before -> after |
|---|---|---|---|---|---|---|---|
| empty | 0.206 / 0.409 -> 0.185 / 0.348 | 0.0217 -> 0.0108 | 0.10 -> 0.10 | 0 / 0 -> 0 / 0 | 304 -> 305 | 1629 -> 1603 | 0.3 -> 0.3 |
| rest100 | 0.339 / 0.714 -> 0.172 / 0.318 | 0.1652 -> 0.0110 | 0.28 -> 0.10 | 4 / 23 -> 0 / 0 | 311 -> 306 | 1327 -> 1389 | 20.4 -> 0.3 |
| through100 | 0.362 / 0.746 -> 0.233 / 0.545 | 0.1909 -> 0.0696 | 0.37 -> 0.35 | 4 / 18 -> 1 / 5 | 318 -> 317 | 1568 -> 1728 | 21.6 -> 12.6 |
| fill100 | 0.579 / 1.293 -> 0.377 / 1.411 | 0.3116 -> 0.1204 | 12.20 -> 12.24 | 679 / 653 -> 620 / 644 | 746 -> 897 | 1829 -> 1973 | 9180.9 -> 9106.6 |
| mixed100 | 0.576 / 1.677 -> 0.374 / 1.127 | 0.3824 -> 0.2106 | 7.81 -> 9.02 | 433 / 424 -> 409 / 410 | 641 -> 541 | 1908 -> 2213 | 5872.8 -> 6984.3 |
| rest1000 | 1.609 / 2.626 -> 0.170 / 0.341 | 1.5190 -> 0.0112 | 0.56 -> 0.12 | 37 / 132 -> 0 / 0 | 378 -> 321 | 1396 -> 1378 | 203.6 -> 0.3 |
| pure100 | 0.333 / 0.784 -> 0.147 / 0.309 | 0.1849 -> 0.0098 | 0.24 -> 0.10 | 1 / 4 -> 0 / 0 | 312 -> 306 | 1441 -> 1888 | 12.5 -> 0.3 |

After / before:

| scenario | server CPU after/before | server tick p50 after/before | server engine p50 after/before | server live heap after/before | client CPU after/before | client FPS mean after/before | client live heap after/before |
|---|---|---|---|---|---|---|---|
| empty | 1.00 | 0.90 | 0.50 | 1.00 | 1.04 | 1.03 | 1.00 |
| rest100 | 0.34 | 0.51 | 0.07 | 0.98 | 0.88 | 1.00 | 0.99 |
| through100 | 0.94 | 0.64 | 0.36 | 1.00 | 1.01 | 1.00 | 1.00 |
| fill100 | 1.00 | 0.65 | 0.39 | 1.20 | 1.00 | 0.98 | 0.94 |
| mixed100 | 1.15 | 0.65 | 0.55 | 0.84 | 0.85 | 1.00 | 0.86 |
| rest1000 | 0.21 | 0.11 | 0.01 | 0.85 | 0.74 | 1.00 | 0.91 |
| pure100 | 0.41 | 0.44 | 0.05 | 0.98 | - | - | - |

Client frame rate: 119 fps mean (the cap is 120) on both builds in every scenario without held work, except eb28fc5's empty run (115 fps mean, one second at 1 fps 45 s into its window); p5 71 to 87 fps on both builds when the worker pool is saturated by held retries. The `mixed100` server ratio of 1.15 did not repeat: the branch's second run used 7.76 cores against 7.75 and 7.81 for eb28fc5 (WP5 section 3.5).

Regime evidence (branch, end of window): `rest100` REST 100, `rest1000` REST 1,000, `pure100` STEADY 100 (all within 20 s of placement); `through100` AWAKE 100 (FULL); `fill100` AWAKE 100 (FULL 30, HELD 58, SOLVING 12); `mixed100` REST 25, AWAKE 75. The brief's sanity counts hold (rest all certified, through and fill none, mixed 25).

## 5. Owner decisions still open

1. **Default stationarity tolerance.** At `eps_s` 1e-9 through-flow with a vessel never certifies (the `stress100` ladders drift by about 2e-7 per interval for hours; an in-game through line with a 1 m3 vessel is flushed of its nitrogen and never qualifies). At 1e-7, `stress100` issues 92 certificates, full solves fall 16.6 %, and every inventory and energy deviation stays under 1.5e-8 against the 1e-6 budget; accounting stays exact. The defaults are the plan's; the owner's earlier answer was "OK for now, configurable".
2. **Horizon revalidation against the certified interval or its extrapolation** (plan change). Comparing the revalidating slice with the interval certified K intervals earlier drops every drifting certificate (74 of 74 at 1e-7); comparing it with the certificate's extrapolation to the horizon would renew a steadily drifting one. Plan section 3.3 specifies the former.
3. **Start-up wall-budget retry nondeterminism** (pre-existing). An island whose first slice exceeds the 2 s wall budget is retried with the solver caches the cancelled job left, so its trajectory depends on how far that job got; 4 or 5 islands per paced run part between otherwise identical runs. Candidate fix: replace the retained solver after a non-accepted attempt that the wall budget cut off, at the cost of one cold start per hold. In game the same holds dominate the first two minutes of every pumped fill line (WP5 finding).
4. **The 64 MiB checkpoint bound** on ledger plus payloads: about 430 certified islands of `rest100` size (150 KB each) fit, fewer than format 2's about 550 awake ones. Options: a binary payload, a bound per island, or a larger total.
5. **A refused save crashes the client** instead of returning to the title screen. Opening a format-2 world stops the integrated server with the refusal message and the client closes with a crash report; the file is left byte-identical. A disconnect with the message would be friendlier.

Two items from WP5 that the owner may want to schedule (not decisions within this batch's scope):

6. **Placement cost** (pre-existing): batching the events of one tick into one registry compile, or compiling only the touched component, would make placement linear in the world's device count.
7. **Pumped fills into closed or vented tank chains** fail in the solver under placement defaults (probes 1 to 4 of WP5) and retry indefinitely; this is a science investigation, outside the scheduling batch.

## 6. Follow-ups (not decisions)

* `FluidWorldAuthority.compiled` keeps the initial compile graph of every island next to the live graph (plan section 9).
* STEADY with solids in transport or filters, and steady replay for islands with scheduled transfers, remain excluded (plan section 9).
* Seed the payload cache at load (WP4; WP5 finding 5).
* View building is O(island) per device, so a large island's bucket is O(island squared) (WP3).
* A gross-transfer ledger in the benchmark harness for through-flow islands (WP2).
* `FluidWorldAuthority.legacyUnbound` is reachable only from test fixtures (WP4).
* Immediate messages outside simulation status: the `debugChat` HELD broadcast and the identity-wait action bar (WP3).
* Plan section 5 item 1's real-executor idle-scaling variant and item 4's shared V3 `contention` profile were not run (WP1).
* The 30-minute paced soak (acceptance P52) was not run; WP5 ran two 300 s in-game soaks.

## 7. State of the branch

* Branch `claude/fluid-scheduler`, head `1401cab` (27 commits of WP0 to WP4 over `main` @ `42fdf41`, then WP5's `36caef6`, `39aabac` and the changelog commit). Not pushed, not merged; the merge (a minor version bump per `AGENTS.md`) is the owner's.
* The branch reads checkpoint format 3 only: development worlds saved by `main` (format 2) are refused with the instruction to create a fresh world (owner rule).
* Documents of the batch are in the worktree's untracked `documentation/fluid-scheduler/` (reports, tables, logs, screenshots, the WP5 rig); per `AGENTS.md` they are copied into the main checkout's `documentation/2026-09-23-fluid-scheduling-rest/` when the work merges or the task ends, and the batch row of `documentation/INDEX.md` is updated then. The coordinator does that copy.
* The baseline worktree `fluid-baseline-eb28fc5` (detached at `eb28fc5`, uncommitted rig patch) is left in place for the coordinator to remove.
