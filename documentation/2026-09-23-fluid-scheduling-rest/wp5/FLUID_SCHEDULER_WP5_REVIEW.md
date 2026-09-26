# Fluid scheduler WP5: in-game performance and the paced benchmarks, review

Date: 2026-09-23. Branch `claude/fluid-scheduler` (worktree `agent-ae139e4fc1b184b36`), not pushed, not merged. Batch `2026-09-23-fluid-scheduling-rest`. Baseline worktree `fluid-baseline-eb28fc5` (detached at `eb28fc5`, the WP0 commit: `main` @ `42fdf41` runtime plus the opt-in counters and benchmark profiles, before the scheduler, certificates, presentation and format 3), with the uncommitted rig patch of section 1.4.

Plan: `FLUID_ISLAND_REST_PLAN.md` revision 3, section 6, section 7 row WP5. Owner instruction for WP5: "try to bench the overall in-game performance (cpu+ram) instead of doing simple cold test alone". Coordinator instruction during the task: use the dedicated server for the server-side measurement (the owner accepted the EULA; `run/eula.txt` was put in place by them) and keep integrated-client sessions for client FPS, CPU and RAM, so every scenario has a server row and a client row. Every number quoted here is in `wp5-tables.md`; raw material per run is in `wp5-logs/<run-id>/`; the rig is in `wp5-rig/`.

**Window for every in-game run: 60 s warm-up after the scenario's blocks are placed and registered, then 60 s measured; the two soak runs use 60 s + 300 s. The paced `transient100` pairs use 60 s + 60 s. No number of one window is compared with a number of another.**

## 0. Summary

* **Built:** an in-game rig that measures the whole process on a dedicated server (no rendering; driven over RCON) and in the integrated client (rendering; driven through the langyo/minecraft-mod-mcp bridge), on the branch and on eb28fc5, for seven scenarios built with `/setblock` from a datapack so that every device carries the mod's placement defaults. Per run: 60 s warm-up after placement, 60 s window with JFR, a process sampler, thread CPU every 10 s, per-tick whole-tick and engine time, island kinds and scheduling counters, and the live heap after one full collection. Opt-in diagnostics committed as `36caef6` and `39aabac`; everything else is untracked in `wp5-rig/`. 32 measured in-game runs (7 server pairs, 6 client pairs, 2 soaks, 4 repeats), plus 4 probes and 5 pilots.
* **Resting and steady networks cost the empty world's floor on the branch** (dedicated server): `rest1000` 0.56 to 0.12 cores (empty world 0.10), engine 1.52 to 0.011 ms per tick, whole tick p50 / p95 1.61 / 2.63 to 0.17 / 0.34 ms, allocation 204 to 0.3 MiB/s, 37 collections to none, live heap 378 to 321 MiB; `rest100` 0.28 to 0.10 cores; the vessel-free through line `pure100` 0.24 to 0.10 cores. Over the window the branch made no solve, island visit, pump, deadline or topology snapshot for them (eb28fc5: 11.1 million island visits and 14,000 solves for `rest1000`).
* **Awake networks do the same solves with less server thread:** `through100` 1,400 solves on both, 0.37 against 0.35 cores, engine 0.19 to 0.07 ms per tick, whole tick p50 0.36 to 0.23 ms.
* **Held retries dominate the fill scenarios on both builds:** 100 pumped three-tank fills placed at once are held for minutes (start-up wall budget, Newton failures) at about 12.2 cores and 9 GiB/s of allocation on both builds; `mixed100` inherits it (7.8 and 7.8 cores eb28fc5, 9.0 and 7.8 branch over two runs each). Only the server-thread share differs (whole tick p50 0.58 to 0.62 against 0.34 to 0.41 ms).
* **Client:** the frame rate stays at the 120 fps cap (119 mean) on both builds wherever no work is held (eb28fc5's empty run had one 1 fps second); the fluid differences show as on the server (`rest1000` 1.96 to 1.45 cores, engine 1.33 to 0.010 ms per tick); p5 falls to 71 to 87 fps on both builds when held retries saturate the worker pool.
* **Soak (branch, 300 s):** `rest100` stayed REST with no engine work except one autosave; `fill100` recovered from its holds over two to four minutes (with a relapse), filled, and 67 lines certified STEADY at the pumps' head limit while 33 stayed held.
* **Regimes as the brief expected:** `rest100` and `rest1000` all REST, `through100` and `fill100` none certified, `mixed100` 25 REST, all within 20 s of placement where they certify.
* **Findings:** placement is quadratic in the world's device count (5,000 devices in one command stall the server 58 to 60 s on both builds); pumped fills into closed or vented tank chains hold for minutes or indefinitely under placement defaults; Minecraft's `ServerTickTime` excludes the engine's tick and its `/jfr` profile forces full collections; the bridge's `execute_command` never reaches the server.
* **Paced harness:** `transient100` three pairs at 60 s + 60 s: every throughput and latency range overlaps (ready-to-publication p50 20.02 to 20.80 ms eb28fc5 against 19.70 to 20.44 ms branch), the gate "within noise" is met, and the engine's per-tick time falls from 0.66 to 0.74 ms to 0.0103 to 0.0107 ms. `module`: both certificate settings pass as replicates with the same outcome; no island certifies there, because both carry module transfers (excluded from STEADY by the plan).
* **Gates on `1401cab`:** science 161/0, runtime 193/0, network 30/9, 19/14, 37/3, regression exactly zero, GameTest 28 passed, P12 and P31 byte-identical to the WP0 values.

## 1. The in-game rig

### 1.1 Worlds and scenarios

* **Template world** (`wp5-rig/template/bench-template/`): created by the branch's dedicated server with a void superflat preset (one layer of air, biome `the_void`, its start platform), creative, peaceful; gamerules `doMobSpawning`, `doDaylightCycle`, `doWeatherCycle`, `doPatrolSpawning`, `doTraderSpawning`, `doInsomnia` false, `randomTickSpeed 0`, `spawnRadius 0`; time 6000, clear weather; world spawn on a 3 x 3 glass platform at (64, 100, 80). The 48 chunks x 16..111, z 16..143 are force-loaded in the template. The mod's data files (`createcheme_fluid_core.dat`) were deleted, so it is a fresh fluid world for both builds (eb28fc5 reads format 2 only, the branch format 3 only). `level.dat` carries `allowCommands 1b` (patched by `level-allow-commands.js`; the dedicated server ignores it). The datapack `createcheme_bench` (1.21.1 layout, pack format 48) is in its `datapacks/` and is enabled in `level.dat`. Every run copies the template to a fresh folder (`run/bench-<run-id>` for the server, `run/saves/bench-<run-id>` for the client) and deletes the copy afterwards; no world is reused.
* **Placement.** Every device is placed with `/setblock` from one datapack function, so `FluidDeviceBlock.onPlace` registers it with the mod's placement defaults, as for a player-built network: reservoirs 1 m3 of nitrogen at 298.15 K and 101,325 Pa, generators water at 298.15 K and 101,325 Pa, voids nitrogen at 101,325 Pa, pipes 1 m of 0.05 m bore, pumps 0.01 m3/s target with a 500 kPa head limit (`FluidWorldAuthority.place`). The config is the default one in both builds (`createcheme-common.toml` identical except the branch's six certificate keys at their defaults; `adaptiveCadence = true`, `workers = 0`, 12 automatic workers). Lines run along +x at y = 64, facing east, one empty row between lines so no two lines touch.

| scenario | content | devices / islands |
|---|---|---|
| `empty` | no network; the same 48 force-loaded chunks | 0 / 0 |
| `rest100` | 100 lines tank, pipe, pipe, pipe, tank | 500 / 100 |
| `through100` | 100 lines generator, pump, pipe, 1 m3 tank, pipe, pipe, void: the pump holds 0.01 m3/s of water through a vessel that floods and is then flushed of its nitrogen | 700 / 100 |
| `fill100` | 100 lines generator, pump, pipe, tank, pipe, tank, pipe, tank: water pumped into a closed chain of three nitrogen tanks | 800 / 100 |
| `mixed100` | 50 fill, 25 through, 25 rest lines (line n: n mod 4 = 0, 1 fill, 2 through, 3 rest) | 700 / 100 |
| `rest1000` | 1,000 rest lines (16 columns by 63 rows) | 5,000 / 1,000 |
| `pure100` (added) | 100 lines generator, pump, pipe, pipe, pipe, void: through-flow with no vessel | 600 / 100 |

The 100-line scenarios use 4 columns 16 blocks apart and 25 rows 2 blocks apart; all scenarios sit inside the same force-loaded 48 chunks.

### 1.2 Run procedure

Server run (`run-server.js`): machine gate (no `Endfield.exe`, at least 20 GB free), bridge jar moved out of `run/mods`, fresh world copy, `server.properties` (offline mode, spawn protection 0, RCON, `max-tick-time -1`, view and simulation distance 10), `JAVA_OPTS=-Xshare:off ./gradlew.bat runServer --offline -PfluidRigDiagnosticTicks=200 -PfluidRigHeapMiB=4096`; when the server is up, over RCON: `function createcheme_bench:forceload`, 5 s, `function createcheme_bench:<scenario>` (RCON returns when the function has run; its wall time is the placement time); wait until a `fluid_diag` period line reports every device; 60 s warm-up; `jcmd GC.heap_info`, external sampler started, JFR recording started, thread dump (window start); thread dumps every 10 s; thread dump (window end); JFR stopped; sampler stopped; `jcmd GC.heap_info`; `jcmd GC.run` then `GC.heap_info` (live heap); `stop` over RCON.

Client run (`run-client.js`): the same gate, bridge jar in `run/mods`, the same `options.txt` in both builds (render and simulation distance 10, 120 fps cap, vsync off, frame-rate reduction only when minimised, sound off, pause on lost focus off), fresh world copy in `run/saves`, `./gradlew.bat runClient --offline -PfluidRigDiagnosticTicks=200 -PfluidRigHeapMiB=4096 -PfluidClientQuickPlay=bench-<run-id>` (quick play opens the world directly); through the bridge's HTTP endpoint (the mod the `minecraft-mod-mcp` MCP server talks to): control mode, `/function createcheme_bench:view` (spectator, `tp 64 150 80`, looking straight down at the networks), `set_view_angle 0 90`, `/function createcheme_bench:forceload`, `/function createcheme_bench:<scenario>`; every command is typed into chat and its chat reply is read back from the log, and the run fails on any error reply; then the same device check, warm-up, window and heap steps as the server; after the window one plain screenshot and one with the F3 overlay (a real F3 key event, the window brought forward), save and quit, client closed.

`campaign.js` runs the scenarios one at a time, alternating which build goes first per scenario, analyses each run (`analyze.js`) and would keep a crashed JVM's `hs_err` file under `wp5-logs/jvm-crash/` and rerun once. `tables.js` builds `wp5-tables.md`'s in-game tables.

### 1.3 What is measured, and how threads are attributed

| quantity | source |
|---|---|
| server tick time p50 / p95 / max | every tick's whole time from the rig's `fluid_diag_ticks.csv`: `ServerTickEvent.Pre` (highest priority) to `ServerTickEvent.Post` (lowest priority, after the mod's own Post handler). Also `minecraft.ServerTickTime` (JFR), reported but not used for comparisons: it is an exponential average (`0.8 old + 0.2 new`) sampled once a second, and `MinecraftServer.tickServer` tallies the tick before NeoForge fires `ServerTickEvent.Post`, so it excludes every Post listener, the fluid engine's tick hook included. That is why it barely moves between the builds while the rig's tick time does |
| engine ms per tick | `FluidRuntimeMeter` (the fluid runtime's server-thread time: tick hooks plus completion wakeups), per tick, from the same CSV |
| process CPU in cores | external sampler (`sampler.ps1`, `Process.TotalProcessorTime` once a second; user + kernel over all threads) and JFR `jdk.CPULoad` |
| CPU by thread family | `jcmd Thread.print` at the window's start, every 10 s and at its end: each thread's largest `cpu=` reading minus its reading at the start (0 for a thread created in the window). Families: Server thread; Render thread; fluid workers (`createcheme-cpu-solve-*`); GC (`GC Thread#*`, `G1 *`); JIT (`C1/C2 CompilerThread*`); observation (`JFR *`, `Attach Listener`, `RCON *`); other. The remainder of the process total is reported as "not attributed": threads that exited between two dumps (the JIT's dynamic compiler threads, short-lived pool threads) and the sampler's one-second edges |
| GC count, pause total, heap after GC | JFR `jdk.GarbageCollection` and `jdk.GCHeapSummary` (after GC) in the window |
| live heap | `jcmd GC.run` after the window, then `GC.heap_info` (used after a full collection) |
| working set, private bytes, threads | external sampler; resident set also from JFR `jdk.ResidentSetSize` |
| allocation rate | JFR `jdk.ThreadAllocationStatistics` (growth of every thread's counter) |
| client FPS | the rig's `fluid_diag_fps.csv`: `Minecraft.getFps()` once a second |
| island kinds, statuses, scheduling counters | the rig's `fluid_diag` log line every 200 ticks (stored island state only; counters summed over the periods inside the window) |
| placement time | RCON round trip of the scenario function (server) and the `fluid_diag gap_ms` line: the gap between two server ticks in which the function ran |

**JFR settings.** Minecraft's `/jfr start` profile (`flightrecorder-config.jfc` from the 1.21.1 client jar) was checked first: it records `minecraft.ServerTickTime`, `jdk.CPULoad` (1 s), `jdk.ThreadCPULoad` (10 s), `jdk.GarbageCollection`, `jdk.GCHeapSummary` and `jdk.ThreadAllocationStatistics` (5 s), but no `jdk.ResidentSetSize`, and it enables `jdk.ObjectCount` with period `everyChunk`, which forces a full "Heap Inspection Initiated GC" at the start, at the end and at every chunk rotation of the recording: 18 forced full collections in the 60 s window of the `fill100` pilot (`pilot-fill100-r01`), in a way that depends on how much the run allocates. The window recording is therefore started with `jcmd JFR.start` and `wp5-rig/wp5-window.jfc`: Minecraft's profile with `jdk.ObjectCount` off, thread CPU and thread allocation every 1 s, and `jdk.ResidentSetSize` every 1 s (`make-jfc.js`). `minecraft.ServerTickTime` is enabled by name, as `/jfr` does; its periodic hook exists in every server. The recording is identical in both builds.

### 1.4 Rig code

* Committed on the branch, opt-in and off by default: `FluidInGameDiagnostics` and `FluidInGameClientDiagnostics` (registered from `CreateChemE` only when `createcheme.fluid.diagnostics.logTicks` is a positive tick count, the client half only on a physical client), and the `build.gradle` switches `-PfluidRigDiagnosticTicks`, `-PfluidRigHeapMiB` and `-PfluidClientQuickPlay` on the `client` and `server` runs (`36caef6`, `39aabac`). With the switches absent nothing is registered and nothing is passed. The diagnostics read stored island state only (`diagnosticSnapshots`), pause the counters around their own reads and decide nothing.
* Baseline, uncommitted in `fluid-baseline-eb28fc5`: the same `build.gradle` switches, the same two classes (the server one with every island counted AWAKE, since eb28fc5 has no certificates), the same registration lines, and a one-line `diagnosticSnapshots()` accessor on `FluidWorldAuthority` (eb28fc5's `IslandCoordinator.snapshots()`, which has no materialisation to avoid). Archived in `wp5-rig/baseline-rig/`. Both builds therefore carry the same observation: the counters (`FluidRuntimeDiagnostics.ENABLED`), the runtime meter, one summary every 200 ticks and one CSV line per tick.
* Untracked in `wp5-rig/`: `gen-datapack.js` (datapack), `template/` (the template world), `server.properties.in`, `level-allow-commands.js`, `rcon.js`, `rig-lib.js`, `run-server.js`, `run-client.js`, `campaign.js`, `after-client.sh`, `sampler.ps1`, `make-jfc.js`, `wp5-window.jfc`, `minecraft-flightrecorder-config.jfc`, `analyze.js`, `tables.js`, `compact.js`, `build-tables.js`, `paced-campaign.sh`, `paced.js`, `gates.sh`, `baseline-rig/` (the baseline patch), `tools/` (screenshot flattening, F3 key tap).

### 1.5 Limitations

* The client row is one JVM with rendering, the integrated server and the fluid engine; its CPU and memory include the renderer (and a KubeJS client thread that took about 0.9 cores in every client run of both builds; section 3.4). The server row has no rendering.
* One run per scenario, build and kind (the brief's plan), except the pilots and the repeats of the two held-retry scenarios (section 3.5), which show a spread of up to 16 % in process CPU between runs of one build there. The noise of the other in-game scenarios is not measured; the paced `transient100` ranges in section 5 give the noise of the engine numbers under the harness.
* Thread attribution misses threads that exit between two 10 s dumps; the remainder is shown.
* Placement happens inside one command, outside any tick's Pre/Post span; its time is the RCON round trip or the logged gap between ticks.
* The dedicated server has no player: block entities are loaded by the force-loaded chunks, and presentation builds views for them (the branch's buckets, eb28fc5's publication-rate refresh), but no packet reaches a client. The client row has one player looking at the networks.
* `adaptiveCadence` is on (the default); the paced harness runs with it off.

## 2. Regimes: what the placement defaults give, and the evidence

The brief's scenario list assumed a generator-to-tank chain that keeps filling and a through line that never certifies. With placement defaults every generator and void sits at 101,325 Pa and every tank at the same charge, so nothing flows without a pump or an elevation difference. Four probes on the branch's dedicated server settled what each layout does (logs in `wp5-logs/probe*-r01/`; one `fluid_diag` line every 10 s; kind counts decode per line type because each probe used distinct multiplicities).

| probe | lines (count) | what happened | verdict |
|---|---|---|---|
| `probe-r01`, 360 s | tank-3 pipes-tank (1); generator-pump-3 pipes-void (2); generator-pump-pipe-1 m3 tank-2 pipes-void (4); generator-pump into six closed tanks (8); generator-3 pipes-void at one elevation (16) | at 20 s: REST 17 (the closed line and the 16 level generator-void lines: equal pressures, no flow), STEADY 2 (the vessel-free pumped lines), FULL 4 (the vented vessel lines), HELD 8 (every six-tank fill). After 6 min the same: the six-tank fills never committed an interval (493 held retries: Newton line search stalled, Newton iteration limit, "linearized error estimate does not contract", round deadline) | rest line and vessel-free through line certify in seconds; the vessel through line stays awake; pumped six-tank fill never advances |
| `probe2-r01`, 300 s | pumped fill into 1, 2 or 3 closed tanks (1, 2, 4); six tanks (8); gravity fill, generator 4 m above one tank (16) or a three-tank chain (32) | first 90 s: 12 of 12 workers busy, 33 held; the one-tank gravity lines filled and certified REST within 60 s; the three-tank gravity lines recovered from start-up holds and certified STEADY between about 150 and 300 s; one-to-three-tank pumped fills mostly recovered after 1 to 12 holds; six-tank fills stayed held | gravity fills end in under a minute; pumped fills of three or fewer tanks run after their start-up holds |
| `probe3-r01`, stopped at 70 s | pump transfer tank to tank (8), tank to two tanks (8), two tanks to two tanks (8) | all 24 held by 70 s ("Interval substep limit; no partial interval may commit") | unusable |
| `probe4-r01`, stopped at 200 s | pumped water through three or six tanks vented to a void (8, 8); the vessel through line (8) | the vessel through lines FULL throughout; 12 of the 16 vented chains held | vented chains unusable |

Chosen layouts (section 1.1): `rest100` and `rest1000` the closed tank line, `through100` the vessel through line, `fill100` the pumped three-tank chain, `pure100` (added) the vessel-free through line. Placement defaults give no layout that keeps filling for two to six minutes without start-up holds: a pumped chain long enough to fill for the whole window holds for most of it, and a gravity fill ends in under a minute. `fill100` therefore measures what a player gets from 100 pumped three-tank lines placed at once: a start-up hold-and-retry phase with the whole worker pool busy, during which a growing share of the lines fills.

**Regime evidence at the end of every window (branch, from `fluid_diag`; eb28fc5 has no certificates, every island is AWAKE there):**

| scenario | server run | client run | expected (brief) | holds |
|---|---|---|---|---|
| `empty` | no islands | no islands | - | yes |
| `rest100` | REST 100, all by the first period after placement (online tick 400) | REST 100 | all certified | yes |
| `through100` | AWAKE 100, FULL 100 for the whole window | AWAKE 100 | none certified | yes |
| `fill100` | AWAKE 100: FULL 30, HELD 58, SOLVING 12 (eb28fc5: FULL 32, HELD 56, SOLVING 12) | AWAKE 100 | none certified | yes, in a held-retry regime (above) |
| `mixed100` | AWAKE 75, REST 25 (the 25 closed lines by online tick 600); FULL 44, HELD 20, SOLVING 11 | AWAKE 75, REST 25 | 25 certified | yes |
| `rest1000` | REST 1,000 by online tick 400 | REST 1,000 | all certified | yes |
| `pure100` | STEADY 100 by online tick 400 | (server only) | - (added) | - |

In `through100` the pump holds 0.01 m3/s of water through each vessel, which floods and is then flushed of its nitrogen: an exponentially decaying trace component changes by a constant fraction of itself per interval and never passes the component test at 1e-9. That is the `stress100` regime (through-flow with a drifting holdup), and in game, too, it does not certify. The same line without the vessel (`pure100`) has no finite inventory and repeats its interval exactly, so it certifies STEADY within 20 s.

**Soak evidence (branch, 60 s + 300 s):** `rest100` stayed REST 100 for the whole 300 s with zero solves, zero island visits and zero deadlines; the only engine work in the window was the autosave at online tick 6000 (100 materialisations, 100 payloads encoded, one topology snapshot). `fill100` (placement at online tick 108; times after placement): 88 held and 12 solving with the pool saturated until about 90 s; a first partial recovery at about 115 s (43 FULL), a relapse to 76 to 80 held at 145 to 175 s, recovery to 56 FULL by 235 s; then, as the pumps reached their 500 kPa head limit, 53 lines certified STEADY by 265 s and 67 by 295 s (dead-headed pumps with a residual drift) while 33 stayed held for the rest of the window, retrying every cadence at little CPU (workers active 0 of 8 at the end). `fluid_diag` per 10 s is in `srv-fill100-wp5-soak-r01/gradle-server.log`.

## 3. In-game results

### 3.1 Dedicated server, 60 s warm-up + 60 s window

Before = eb28fc5, after = branch. Full rows (thread families, JFR resident set, private bytes, threads, Minecraft's ServerTickTime, placement) are in `wp5-tables.md` section 1.

| scenario (server) | tick ms p50 / p95 (every tick): before -> after | engine ms per tick p50: before -> after | process CPU, cores: before -> after | GC count / pause ms: before -> after | live heap MiB: before -> after | working set MiB (mean): before -> after | allocation MiB/s: before -> after |
|---|---|---|---|---|---|---|---|
| empty | 0.206 / 0.409 -> 0.185 / 0.348 | 0.0217 -> 0.0108 | 0.10 -> 0.10 | 0 / 0 -> 0 / 0 | 304 -> 305 | 1629 -> 1603 | 0.3 -> 0.3 |
| rest100 | 0.339 / 0.714 -> 0.172 / 0.318 | 0.1652 -> 0.0110 | 0.28 -> 0.10 | 4 / 23 -> 0 / 0 | 311 -> 306 | 1327 -> 1389 | 20.4 -> 0.3 |
| through100 | 0.362 / 0.746 -> 0.233 / 0.545 | 0.1909 -> 0.0696 | 0.37 -> 0.35 | 4 / 18 -> 1 / 5 | 318 -> 317 | 1568 -> 1728 | 21.6 -> 12.6 |
| fill100 | 0.579 / 1.293 -> 0.377 / 1.411 | 0.3116 -> 0.1204 | 12.20 -> 12.24 | 679 / 653 -> 620 / 644 | 746 -> 897 | 1829 -> 1973 | 9180.9 -> 9106.6 |
| mixed100 | 0.576 / 1.677 -> 0.374 / 1.127 | 0.3824 -> 0.2106 | 7.81 -> 9.02 | 433 / 424 -> 409 / 410 | 641 -> 541 | 1908 -> 2213 | 5872.8 -> 6984.3 |
| rest1000 | 1.609 / 2.626 -> 0.170 / 0.341 | 1.5190 -> 0.0112 | 0.56 -> 0.12 | 37 / 132 -> 0 / 0 | 378 -> 321 | 1396 -> 1378 | 203.6 -> 0.3 |
| pure100 | 0.333 / 0.784 -> 0.147 / 0.309 | 0.1849 -> 0.0098 | 0.24 -> 0.10 | 1 / 4 -> 0 / 0 | 312 -> 306 | 1441 -> 1888 | 12.5 -> 0.3 |

### 3.2 Integrated client, 60 s warm-up + 60 s window

One JVM with rendering (120 fps cap), the integrated server and the engine; one player looking straight down at the networks from 86 blocks above. Full rows in `wp5-tables.md` section 2.

| scenario (client) | FPS mean / p5: before -> after | tick ms p50 / p95 (every tick): before -> after | engine ms per tick p50: before -> after | process CPU, cores: before -> after | GC count / pause ms: before -> after | live heap MiB: before -> after | working set MiB (mean): before -> after | allocation MiB/s: before -> after |
|---|---|---|---|---|---|---|---|---|
| empty | 115.4 / 111.0 -> 119.0 / 118.0 | 0.578 / 0.952 -> 0.595 / 0.894 | 0.0184 -> 0.0111 | 1.46 -> 1.53 | 20 / 56 -> 16 / 54 | 575 -> 573 | 1952 -> 2113 | 122.8 -> 124.0 |
| rest100 | 119.3 / 118.0 -> 119.1 / 119.0 | 0.731 / 1.222 -> 0.589 / 0.891 | 0.1544 -> 0.0121 | 1.71 -> 1.51 | 20 / 69 -> 16 / 62 | 583 -> 578 | 2112 -> 2103 | 144.0 -> 124.2 |
| through100 | 119.0 / 119.0 -> 119.0 / 118.0 | 0.808 / 1.473 -> 0.636 / 1.052 | 0.2128 -> 0.0696 | 1.72 -> 1.75 | 12 / 60 -> 17 / 62 | 589 -> 588 | 2657 -> 2221 | 145.5 -> 136.7 |
| fill100 | 115.1 / 71.0 -> 112.3 / 71.0 | 1.282 / 3.578 -> 1.054 / 3.606 | 0.3604 -> 0.1009 | 13.32 -> 13.27 | 410 / 507 -> 398 / 483 | 1779 -> 1670 | 3107 -> 3182 | 9190.2 -> 9155.3 |
| mixed100 | 116.0 / 87.0 -> 115.6 / 71.0 | 1.140 / 6.123 -> 0.842 / 4.029 | 0.3686 -> 0.1291 | 8.39 -> 7.17 | 260 / 320 -> 198 / 272 | 684 -> 588 | 3112 -> 3346 | 5701.3 -> 4761.0 |
| rest1000 | 119.1 / 118.0 -> 119.2 / 119.0 | 1.811 / 2.820 -> 0.530 / 0.873 | 1.3278 -> 0.0096 | 1.96 -> 1.45 | 35 / 136 -> 12 / 34 | 658 -> 596 | 2436 -> 2401 | 332.1 -> 124.3 |

### 3.3 After / before ratios

| scenario | server CPU after/before | server tick p50 after/before | server engine p50 after/before | server live heap after/before | client CPU after/before | client FPS mean after/before | client live heap after/before |
|---|---|---|---|---|---|---|---|
| empty | 1.00 | 0.90 | 0.50 | 1.00 | 1.04 | 1.03 | 1.00 |
| rest100 | 0.34 | 0.51 | 0.07 | 0.98 | 0.88 | 1.00 | 0.99 |
| through100 | 0.94 | 0.64 | 0.36 | 1.00 | 1.01 | 1.00 | 1.00 |
| fill100 | 1.00 | 0.65 | 0.39 | 1.20 | 1.00 | 0.98 | 0.94 |
| mixed100 | 1.15 | 0.65 | 0.55 | 0.84 | 0.85 | 1.00 | 0.86 |
| rest1000 | 0.21 | 0.11 | 0.01 | 0.85 | 0.74 | 1.00 | 0.91 |
| pure100 | 0.41 | 0.44 | 0.05 | 0.98 | - | - | - |

### 3.4 Reading the results

* **Resting networks cost what an empty world costs.** On the dedicated server, `rest100` falls from 0.28 to 0.10 cores and `rest1000` from 0.56 to 0.12, against 0.10 for `empty`; the fluid workers do no work at all, allocation falls to the empty world's 0.3 MiB/s, and no collection runs in the window (37 in `rest1000` before). The engine's share of a tick falls from 1.52 ms to 0.011 ms at 1,000 networks and to the empty world's level at 100 (0.011 ms against 0.165). Whole ticks follow: `rest1000` p50 1.61 to 0.17 ms, p95 2.63 to 0.34 ms, max 16.0 to 1.3 ms. The counters show why: over the window, eb28fc5 made 11.1 million island visits, 1,200 topology snapshots and 14,000 solves for 1,000 networks that do not move; the branch made none of them (`wp5-tables.md` section 3).
* **Steady through-flow without a vessel certifies and becomes free.** `pure100` falls from 0.24 to 0.10 cores, 12.5 to 0.3 MiB/s, engine 0.185 to 0.010 ms per tick.
* **Awake through-flow costs the same solves and less server thread.** `through100` does the same 1,400 solves in the window on both builds (the vessel line never certifies); process CPU is 0.37 against 0.35 cores, but the engine's per-tick cost falls from 0.19 to 0.07 ms, whole-tick p50 from 0.36 to 0.23 ms, allocation from 21.6 to 12.6 MiB/s and collections from 4 to 1: the per-tick scans are gone (island visits 912,000 to 5,600 in the window).
* **Held-retry load is unchanged.** `fill100` is 12.2 cores on both builds, 9.1 to 9.2 GiB/s of allocation and 620 to 680 collections a minute: the worker pool is saturated by start-up holds and retries that the scheduler neither causes nor removes. The engine's server-thread share still falls (0.31 to 0.12 ms per tick p50). `mixed100` inherits this half and its process CPU differs in opposite directions on the two rows (server 7.8 to 9.0 cores, client 8.4 to 7.2); section 3.5 repeats both scenarios to show that this is the held-retry regime's own run-to-run spread.
* **Memory.** The live heap after a full collection is where the scheduler's memory claim shows: `rest1000` 378 to 321 MiB on the server and 658 to 596 MiB in the client, `mixed100` 641 to 541 (server) and 684 to 588 (client). The in-game lines are small (two tanks and three pipes), so a resting line retains little solver state: 57 MiB for 1,000 awake lines on the server, against 669 MiB for the 100 large `rest100` harness ladders in WP2. The working set is dominated by the committed Java heap, which G1 grows during placement and warm-up and does not return without a collection: it moves by up to 30 % between runs in both directions without following the live heap (`pure100` 1,441 to 1,888 MiB with a live heap of 312 to 306 MiB and no collection in the branch's window), so it is reported but not read as a retained-memory result.
* **Client.** The frame rate sits at the 120 fps cap in every scenario without held work (119 fps mean, p5 118 to 119, both builds), except eb28fc5's empty run (115 fps mean, p5 111), whose window contains one second at 1 fps 45 s in. With the worker pool saturated (`fill100`, `mixed100`) the p5 falls to 71 to 87 fps on both builds: 12 of the machine's 16 hardware threads are busy with solver retries. The client's own work is about 1.4 cores in the empty world, of which about 0.9 cores is a KubeJS client thread (`KubeJS 2101.7.2-build.368`) present in every client run of both builds; the fluid engine's differences show the same way as on the server (`rest1000` 1.96 to 1.45 cores, engine 1.33 to 0.010 ms per tick, whole tick p50 1.81 to 0.53 ms, 35 to 12 collections, allocation 332 to 124 MiB/s).
* **Minecraft's `ServerTickTime` does not see the difference** (`rest1000` 0.155 against 0.140 ms): see section 1.3.

### 3.5 Repeats of the held-retry scenarios (dedicated server, 60 s + 60 s)

The second runs were made after the client block, same template, same order rule.

| run | process CPU cores | fluid workers + not attributed, cores | tick ms p50 / p95 | engine ms p50 / p95 | GC count | live heap MiB | allocation MiB/s | statuses at window end |
|---|---|---|---|---|---|---|---|---|
| srv-fill100-base-r01 | 12.20 | 11.88 | 0.579 / 1.293 | 0.3116 / 1.3318 | 679 | 746 | 9181 | FULL 32, HELD 56, SOLVING 12 |
| srv-fill100-base-r02 | 12.26 | 11.96 | 0.617 / 1.220 | 0.3227 / 1.1776 | 390 | 387 | 9597 | FULL 40, HELD 48, SOLVING 12 |
| srv-fill100-wp5-r01 | 12.24 | 11.83 | 0.377 / 1.411 | 0.1204 / 1.1634 | 620 | 897 | 9107 | FULL 30, HELD 58, SOLVING 12 |
| srv-fill100-wp5-r02 | 12.30 | 11.91 | 0.344 / 1.046 | 0.0696 / 0.9348 | 611 | 757 | 9587 | FULL 32, HELD 56, SOLVING 12 |
| srv-mixed100-base-r01 | 7.81 | 7.58 | 0.576 / 1.677 | 0.3824 / 2.5617 | 433 | 641 | 5873 | FULL 78, HELD 18, SOLVING 4 |
| srv-mixed100-base-r02 | 7.75 | 7.48 | 0.613 / 1.198 | 0.3638 / 2.2104 | 285 | 322 | 6125 | FULL 93, HELD 5, SOLVING 2 |
| srv-mixed100-wp5-r01 | 9.02 | 8.78 | 0.374 / 1.127 | 0.2106 / 1.6102 | 409 | 541 | 6984 | FULL 44, HELD 20, RESTING 25, SOLVING 11 |
| srv-mixed100-wp5-r02 | 7.76 | 7.51 | 0.410 / 0.929 | 0.1942 / 1.3283 | 387 | 610 | 6091 | FULL 55, HELD 18, RESTING 25, SOLVING 2 |

In `mixed100` the branch's first run was the outlier: 9.02 cores against 7.76 in its repeat and 7.75 and 7.81 for eb28fc5. Process CPU in these scenarios is set by the fill lines' held retries over the window, which follow the timing of the cold start (held at the end of the window: 20 and 18 on the branch, 18 and 5 on eb28fc5), not the build. Over the four `fill100` runs CPU stays at 12.20 to 12.30 cores. The live heap after a full collection varies from 322 to 897 MiB between repeats of one build here, because in-flight and held solves own their workspaces when the collection runs; it is only meaningful in the scenarios without held work. The engine's server-thread time is the stable difference: whole-tick p50 0.58 to 0.62 ms (eb28fc5) against 0.34 to 0.41 ms (branch), engine p50 0.31 to 0.38 ms against 0.07 to 0.21 ms, over all eight runs.


### 3.6 Soak (branch, dedicated server, 60 s warm-up + 300 s window)

| measure | `rest100` soak | `fill100` soak |
|---|---|---|
| process CPU, cores | 0.076 | 6.87 |
| tick ms p50 / p95 / max | 0.129 / 0.206 / 201.1 | 0.251 / 0.561 / 210.3 |
| engine ms per tick p50 / p95 / max | 0.0083 / 0.012 / 0.131 | 0.065 / 0.786 / 78.7 |
| GC count / pause ms | 0 / 0 | 1,372 / 1,375 |
| live heap after full GC, MiB | 307 | 507 |
| working set mean, MiB | 1,323 | 2,119 |
| allocation MiB/s | 0.35 | 4,823 |
| islands at the end | REST 100 | STEADY 67, AWAKE 33 (HELD 33) |
| engine work in the window | one autosave: 100 materialisations, 100 payloads encoded, 1 topology snapshot; nothing else | 7,557 solves, 67 certificates issued, 44,184 replayed ticks, 32 drain continuations (the 64-per-tick completion budget reached 32 times) |

The 201 ms and 210 ms maxima are the autosave at online tick 6000 (Minecraft saves every 6,000 ticks): the first save after a world start encodes every fluid payload (the payload cache starts empty, WP4 deviation 8) and the topology, besides Minecraft's own chunk save. Nothing else in the resting soak exceeds 0.4 ms per tick.

### 3.7 Screenshots

Every client run directory holds `view.png` (the networks seen from 86 blocks above) and `view-f3.png` (the same with the F3 overlay), both taken right after the window and flattened (the bridge writes alpha 0). Example, `rest1000` after the window: eb28fc5 `cli-rest1000-base-r01/view-f3.png` shows 120 fps, "Integrated server @ 0.4/50.0 ms", allocation rate 224 MB/s, 1,437 MB of heap in use; the branch `cli-rest1000-wp5-r01/view-f3.png` shows 120 fps, the same 0.4 ms, 143 MB/s and 1,263 MB. The overlay's server time is Minecraft's own tally, which excludes the engine's tick (section 1.3); its allocation rate and heap in use follow the rig's figures.

## 4. Findings

1. **Placement is quadratic in the world's device count (pre-existing, both builds).** Every placed device submits one topology event, and `FluidWorldAuthority.submit` applies it at once: it copies the registrations, builds a position map and runs `applyPending`, which compiles the whole active registry (`PhysicalFluidTopology.compile` over every device) and reads every island. Measured over one datapack function on the dedicated server (before / after): 500 devices 794 / 766 ms, 600 devices 1,317 / 1,282 ms, 700 devices 1,408 / 1,283 ms, 800 devices 1,879 / 1,485 ms, **5,000 devices 59.5 / 58.0 s** (a 58 to 60 s stall of the server thread, "Can't keep up! ... 1,159 to 1,190 ticks behind"); in the client, 5,000 devices take 50.1 / 50.7 s. The total grows with the square of the count (500 to 5,000 devices: 10 times the devices, 75 times the time), so the marginal cost of one placed block grows linearly, about 4.6 us per device already in the world: about 23 ms of server thread for one more block in a 5,000-device world, and about 50 ms in the WP3 fixture's 10,803-device world (WP3 measured 205 ms for the first, JIT-cold event). With the dedicated server's default `max-tick-time` of 60,000 ms the watchdog would have stopped the server a few hundred devices later in the same command (the rig sets `-1`). Batching the events of one tick into one compile, or compiling only the touched component, would make placement linear.
2. **Pumped fill lines are held for minutes, and a third never recover (pre-existing, both builds).** With placement defaults, water pumped into closed chains of nitrogen tanks fails its first slices (wall budget under a cold JIT with 100 islands due at once; Newton line search stalls, iteration limits, "linearized error estimate does not contract", "interval substep limit") and retries every cadence with the whole pool busy: 12.2 cores and about 9 GiB/s of allocation on both builds for as long as they are held. Six-tank chains, vented chains and pump transfers between tanks stay held indefinitely in the probes; in the `fill100` soak, 33 of 100 three-tank lines were still held at the end of the 300 s window. This is the start-up hold of open decision 3 at its worst, plus genuine solver failures for liquid entering gas-filled vessels, not scheduler behaviour: the scheduler dispatches the same retries at the same cadence (`fill100` window: 427 against 442 solves dispatched).
3. **A steady pumped fill certifies STEADY at the pump's head limit.** In the `fill100` soak, 67 lines certified once their pumps dead-headed with a residual drift; they then cost nothing until their horizon.
4. **Resting and steady networks cost nothing in game, on both kinds of server.** No solve, island visit, pump, deadline, topology snapshot or collection in the window at 100 and 1,000 networks (section 3.4); the only engine work over a 300 s resting soak was one autosave.
5. **The first autosave after a start encodes every payload.** The 201 and 210 ms ticks of both soaks are that autosave (payload cache empty after a start, WP4 deviation 8), together with Minecraft's chunk save. Seeding the cache from the loaded bytes (the WP4 follow-up) would remove the fluid part.
6. **Minecraft's own tick time and profile are unreliable for this mod.** `minecraft.ServerTickTime` excludes `ServerTickEvent.Post` listeners, where the engine ticks (section 1.3); Minecraft's `/jfr` profile forces a full collection at every chunk rotation (`jdk.ObjectCount`, 18 in the `fill100` pilot's window) and records no resident set size.
7. **The bridge's `execute_command` never reaches the server.** It resolves to KubeJS's client-side `kjs$runCommand`; even `/help` is refused as "Unknown or incomplete command" (first client pilot, 2026-09-23 20:24). Commands must be typed into chat (`open_chat`, `type_text` with Enter), and the bridge's `get_player_info` game mode and `debug_fields` are not usable either (survival reported in a creative world; `debug_fields` lists reflective fields, no FPS). The F3 overlay needs a real key event.
8. **Presentation work in game.** The branch presents loaded devices on buckets: `through100` 8,400 view builds per minute for 700 loaded devices (one per device per 100 ticks) against eb28fc5's 9,800 publication-driven ones; for resting networks it builds none in the window (their views do not change and they are not published), against 7,000 (`rest100`) and 70,192 (`rest1000`) on eb28fc5.

## 5. Paced harness

### 5.1 `transient100`, three pairs, 60 s warm-up + 60 s window

Command on both sides: `fluidServerBenchmark -PfluidBenchmarkProfile=transient100 -PfluidBenchmarkWorkers=0 -PfluidStressWarmupSeconds=60 -PfluidStressMeasurementSeconds=60 -PfluidStressProfile=true --offline --console=plain`; baseline in `fluid-baseline-eb28fc5`, candidate on the branch with `-PfluidRestDetection=true`; run ids `transient100-wp5-base-r0N` and `transient100-wp5-on-r0N`, interleaved base, on, base, on, base, on (22:23 to 22:37 local); each run after the machine gate (28.0 to 28.5 GB free, no `Endfield.exe`). All six audits and integrity checks passed, 0 held intervals in every window (5 held in every warm-up). Reports and audits in `wp5-logs/paced/reports/`, console logs `wp5-logs/paced/bench-*.log`.


base = `fluid-baseline-eb28fc5` (eb28fc5), on = branch with `-PfluidRestDetection=true`.

| metric | base r01 | base r02 | base r03 | on r01 | on r02 | on r03 | base range | on range | ranges overlap |
|---|---|---|---|---|---|---|---|---|---|
| accepted intervals / s | 19.983 | 19.984 | 19.984 | 20.000 | 19.983 | 20.000 | 19.983 to 19.984 | 19.983 to 20.000 | yes |
| ready-to-publication p50 ms | 20.80 | 20.22 | 20.02 | 19.96 | 20.44 | 19.70 | 20.02 to 20.80 | 19.70 to 20.44 | yes |
| ready-to-publication p95 ms | 65.53 | 67.11 | 65.64 | 64.18 | 68.90 | 64.25 | 65.53 to 67.11 | 64.18 to 68.90 | yes |
| ready-to-publication max ms | 69.83 | 76.46 | 75.07 | 71.96 | 78.69 | 72.21 | 69.83 to 76.46 | 71.96 to 78.69 | yes |
| dispatch-to-publication p50 ms | 4.96 | 4.87 | 5.24 | 4.94 | 5.31 | 5.45 | 4.87 to 5.24 | 4.94 to 5.45 | yes |
| dispatch-to-publication p95 ms | 14.36 | 14.95 | 15.26 | 13.74 | 16.10 | 14.70 | 14.36 to 15.26 | 13.74 to 16.10 | yes |
| worker p50 ms | 3.74 | 3.78 | 3.77 | 3.52 | 3.82 | 3.88 | 3.74 to 3.78 | 3.52 to 3.88 | yes |
| worker p95 ms | 10.76 | 11.27 | 10.92 | 10.88 | 12.54 | 11.80 | 10.76 to 11.27 | 10.88 to 12.54 | yes |
| engine server ms per tick p50 | 0.6677 | 0.6552 | 0.7428 | 0.0103 | 0.0106 | 0.0107 | 0.6552 to 0.7428 | 0.0103 to 0.0107 | no |
| engine server ms per tick p95 | 1.0137 | 0.9780 | 1.2017 | 0.0618 | 0.0538 | 0.0499 | 0.9780 to 1.2017 | 0.0499 to 0.0618 | no |
| engine server ms per tick max | 15.325 | 14.462 | 9.148 | 18.082 | 14.338 | 9.224 | 9.148 to 15.325 | 9.224 to 18.082 | yes |
| whole tick ms p50 | 0.9150 | 0.8890 | 0.9917 | 0.2210 | 0.2192 | 0.2328 | 0.8890 to 0.9917 | 0.2192 to 0.2328 | no |
| whole tick ms p95 | 1.7450 | 1.8428 | 1.7345 | 0.4639 | 0.5196 | 0.4944 | 1.7345 to 1.8428 | 0.4639 to 0.5196 | no |
| mean worker CPU occupancy | 0.0054 | 0.0054 | 0.0054 | 0.0038 | 0.0044 | 0.0056 | 0.0054 to 0.0054 | 0.0038 to 0.0056 | yes |
| held intervals window / warm-up | 0 / 5 | 0 / 5 | 0 / 5 | 0 / 5 | 0 / 5 | 0 / 5 | | | |
| component / energy balance units | 3.79e-7 / 1.11e-9 | 3.68e-7 / 1.06e-9 | 3.52e-7 / 1.07e-9 | 3.55e-7 / 1.04e-9 | 3.64e-7 / 1.07e-9 | 3.45e-7 / 1.09e-9 | | | |
| integrity passed | true | true | true | true | true | true | | | |
| measured s (configured) | 60.1 (not in the eb28fc5 report; -PfluidStressMeasurementSeconds=60) | 60.0 (not in the eb28fc5 report; -PfluidStressMeasurementSeconds=60) | 60.0 (not in the eb28fc5 report; -PfluidStressMeasurementSeconds=60) | 60.0 (60) | 60.0 (60) | 60.0 (60) | | | |

| run | artifact SHA-256 (prefix) |
|---|---|
| transient100-wp5-base-r01 | 5f29ae8a2e6eae7f |
| transient100-wp5-base-r02 | 5f29ae8a2e6eae7f |
| transient100-wp5-base-r03 | 5f29ae8a2e6eae7f |
| transient100-wp5-on-r01 | b592365892250cdd |
| transient100-wp5-on-r02 | b592365892250cdd |
| transient100-wp5-on-r03 | b592365892250cdd |


**Gate: within noise. Met.** Every throughput and latency range overlaps between the two builds (ready-to-publication p50 20.02 to 20.80 ms against 19.70 to 20.44 ms, p95 65.5 to 67.1 against 64.2 to 68.9, worker p95 10.8 to 11.3 against 10.9 to 12.5). The only disjoint ranges are the server thread's: engine ms per tick p50 0.66 to 0.74 against 0.0103 to 0.0107, whole tick p50 0.89 to 0.99 against 0.22 to 0.23 ms, which is the per-tick scanning WP1 removed, measured again at the 60 s window. `transient100` cannot certify (none of its islands certified in any on run), so the reference is what the plan asked for: unchanged latency where nothing certifies.

### 5.2 `module` (interval count), branch, certificates off and on

The existing profile: a generator feeding a 100-reservoir chain split at reservoir 50 into two islands, coupled by a fixed-split module on the buffers of reservoirs 49 to 51; 120 s warm-up, then 200 accepted five-second intervals per island (1,000 s of paced time), 12 automatic workers. Run ids `module-wp5-off-r01` (`-PfluidRestDetection=false`, 22:37 to 22:56) and `module-wp5-on-r01` (defaults, 22:56 to 23:15); the "on" run was capped at 30 minutes in case certified islands never reached 200 solved intervals, and finished in 19. Both audits PASS.


| metric | off | on |
|---|---|---|
| accepted intervals / s | n/a | n/a |
| ready-to-publication p50 ms | 254.34 | 231.74 |
| ready-to-publication p95 ms | 444.21 | 418.55 |
| ready-to-publication max ms | 1303.02 | 455.10 |
| dispatch-to-publication p50 ms | 253.25 | 230.49 |
| dispatch-to-publication p95 ms | 443.27 | 417.63 |
| worker p50 ms | 48.84 | 56.21 |
| worker p95 ms | 425.36 | 399.21 |
| engine server ms per tick p50 | 0.0026 | 0.0027 |
| engine server ms per tick p95 | 0.0145 | 0.0144 |
| engine server ms per tick max | 12.662 | 15.811 |
| whole tick ms p50 | 0.4100 | 0.4082 |
| whole tick ms p95 | 0.6587 | 0.6241 |
| mean worker CPU occupancy | n/a | n/a |
| held intervals window / warm-up | 0 / 0 | 0 / 0 |
| component / energy balance units | 2.37e-7 / 1.21e-9 | 2.37e-7 / 1.21e-9 |
| integrity passed | undefined | undefined |
| measured s (configured) | 1000.4 (60) | 1000.2 (60) |
| status / gates passed | REPLICATE_PASSED / true | REPLICATE_PASSED / true |
| module committed ticks | [22200] | [22200] |
| pending transfer records | 0 | 0 |
| planned capacity records | 1 | 1 |
| full solves / replayed / identity-advanced intervals | 400 / 0 / 0 | 400 / 0 / 0 |
| final island kinds | {"AWAKE":2} | {"AWAKE":2} |
| elapsed s | 1120 | 1120 |


Both runs pass as replicates with the same module outcome (committed tick 22200, no pending transfer, one planned capacity record), the same 400 solved intervals and the same balance residuals. **No certificate was issued:** island 1103 (the feed) is refused every interval as "not a FULL interval without material transfers" (the module withdraws from it), and island 1104 never completes a qualifying streak ("first qualifying interval") because it receives module products. Islands with scheduled transfers are excluded from STEADY by design (plan section 9), so this profile does not exercise the drive index under certification. That is covered by `CausalModuleCoordinatorTest` (WP2: certified islands reproduce the solve-every-interval module outcome, the drive index holds positive withdrawals and due inputs, a known-zero cycle resolves without waking a certified feed, stranded buffers) and by the module-coupled hold GameTest (WP4).

### 5.3 Profiles not rerun

`stress100`, `rest100`, `mixed100` and `viewers` have their pairs already: `stress100`, `rest100` and `mixed100` in WP2 (60 s + 120 s window, `wp2-tables.md`), `viewers` in WP3 (60 s + 120 s, `wp3-tables.md`), `rest100` again in WP4 (60 s + 60 s, `wp4-tables.md` section 2). They were not rerun (brief).

## 6. Gates

On `1401cab` (the branch head: WP5's `36caef6` and `39aabac` plus the changelog), after the whole campaign, one Gradle invocation at a time, `JAVA_OPTS=-Xshare:off`, daemon with `-Xshare:off`, no game running (26.0 to 26.6 GB free); sequence in `wp5-logs/gates.log`, logs `wp5-logs/final-gate-*.log`, script `wp5-rig/gates.sh`. Counts from the test reports (all written 2026-09-23 23:15 to 23:16 local).

| gate | result |
|---|---|
| `fluidScienceTest --rerun` | 161 tests, 0 failures, 0 errors |
| `fluidRuntimeTest --rerun` | 193 tests, 0 failures, 0 errors |
| `fluidNetworkBenchmark --rerun` | 30/9, 19/14, 37/3 accepted/rejected substeps (2, 10, 100 reservoirs) |
| `fluidSolverRegression -PfluidRegressionMode=exact --rerun` | chain-100 max deviation 0.000e+00 in state/moles, temperature, phase fraction and flow |
| `runFluidGameTestServer -PfluidGameTestRunId=wp5-final-r01` | All 28 required tests passed (scheduler self-verification on) |
| P12 / P31 fingerprints | byte-identical to the WP4 copies: SHA-256 `56332b64ea3f3bde...` and `4dcb80a40266...` (the WP0 values) |

WP5 changes no runtime behaviour: its two classes register nothing unless `createcheme.fluid.diagnostics.logTicks` is set, which no test, GameTest or benchmark sets, and its `build.gradle` switches add nothing when absent.


## 7. Deviations from the brief

1. **Dedicated server as the server row** (coordinator instruction during the task): the brief's single integrated-client measurement became a server row (dedicated server, driven over RCON) and a client row (integrated client through the bridge) per scenario and build.
2. **JFR recording.** The window is recorded with `jcmd JFR.start` and Minecraft's own profile minus `jdk.ObjectCount`, plus 1 s thread CPU, thread allocation and resident set size (section 1.3), instead of `/jfr start`, because Minecraft's profile forces full collections and has no resident set size. `minecraft.ServerTickTime` is still recorded; the rig's per-tick time is the one compared.
3. **Commands in the client** are typed into chat, not sent with the bridge's `execute_command` (finding 7).
4. **Client FPS** comes from the rig's once-a-second `Minecraft.getFps()` log, not from the bridge's debug fields (which carry no FPS); the F3 screenshot is taken after the window with a real key event.
5. **`fill100` is not a clean filling regime** (section 2): no layout with placement defaults fills for the whole window without start-up holds; the scenario keeps the brief's generator-to-tank-chain layout with three tanks and reports the held-retry regime it produces.
6. **Added `pure100`** (server pair) to show certification of steady through-flow in game, and **repeats of `fill100` and `mixed100`** (server) to show the held-retry regime's spread (section 3.5).
7. **Live heap** is measured with one full collection after the window (`jcmd GC.run`), outside the window's figures.
8. **Heap limit** `-Xmx4096m` on every run of both builds (`-PfluidRigHeapMiB=4096`), so the JVM default (a quarter of 48 GB) does not decide the heap footprint.
9. **Soak** only on the branch (as the brief), on the dedicated server.
10. **One run per scenario, build and kind**; `transient100` three pairs (section 5).

## 8. Open items

* **Owner decisions** carried to the batch review (`FLUID_ISLAND_REST_REVIEW.md` section 5): default `eps_s`, revalidation against extrapolation, start-up hold nondeterminism (now also the dominant in-game cost of pumped fills, finding 2), the 64 MiB checkpoint bound, a refused save crashing the client.
* **Placement cost** (finding 1): quadratic, pre-existing; a candidate for its own work package.
* **Pumped fills that never recover** (finding 2): 33 of 100 three-tank lines, and every six-tank line, remain held; the solver's handling of liquid entering gas-filled vessels under default settings needs its own investigation (science, out of this batch's scope).
* **Payload cache seeding at load** (finding 5, WP4 follow-up).
* **In-game noise**: one run per scenario; the `fill100`/`mixed100` repeats show that held-retry scenarios vary by more than 15 % in CPU between runs of one build.
* **Documents** stay in the worktree's untracked `documentation/fluid-scheduler/`; nothing was copied to the main checkout. Nothing is pushed or merged. The baseline worktree `fluid-baseline-eb28fc5` is left in place with its uncommitted rig patch, for removal by the coordinator.
