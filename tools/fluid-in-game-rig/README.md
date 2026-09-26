# Fluid in-game performance rig

Measures the whole Minecraft process while fluid networks run: CPU by thread family, heap, allocation, GC, whole-tick and engine time per tick, island kinds and scheduling counters, and the client frame rate. The networks are placed with the mod's placement defaults. A run uses either a dedicated server (driven over RCON, no rendering) or the integrated client (driven through the langyo/minecraft-mod-mcp bridge). Each run takes a fresh copy of a template world, places a scenario with one datapack function, waits 60 s after every device is registered, measures a 60 s window, and then records the live heap after one full collection.

## Batch and provenance

- Built in fluid scheduling **WP5** (batch `2026-09-23-fluid-scheduling-rest`; review `FLUID_SCHEDULER_WP5_REVIEW.md` section 1, tables `wp5-tables.md`). The mod side is commits `36caef6` and `39aabac`.
- Copied and extended by follow-ups **F1** (`f1-rig/`) and **F2** (`f2-rig/`) (batch `2026-09-23-fluid-followups`; `FLUID_PUMPED_FILL_REVIEW.md` section 6, `FLUID_PLACEMENT_REVIEW.md` section 5).
- Moved here on 2026-09-24 by the tooling cleanup (review `2026-09-23-fluid-followups/FLUID_TOOLING_CLEANUP_REVIEW.md`). The mod-side instruments were **removed from the code in `c1b8464`**. `mod-side/` holds them, with a patch that re-attaches them.
- The batch folders keep their own copies (`wp5-rig/`, `f1-rig/`, `f2-rig/`). The reviews cite those copies, so they stay as evidence. This folder is the canonical copy, and the one to run.

### What changed between the three copies

| copy | changes against the one before |
|---|---|
| `wp5-rig` | The original. Its `gen-datapack.js` ends with `FILL` = three tanks (`GUPRPRPR`). WP5's `probe-r01` and `probe2-r01` ran while `FILL` was still six tanks (237 and 528 devices), so the copy's `probe.mcfunction`, `probe2.mcfunction` and `EXPECTED` counts (189, 480) no longer match those runs. It logs to `../wp5-logs/<run>/`. |
| `f1-rig` | Adds `FILL6` (the six-tank chain) and uses it in `probe` and `probe2` (237 and 528 devices, as WP5 ran them). `run-server.js` logs to `../f1-logs/rig/<run>/`. Adds `f1-campaign.sh` and `f1-table.js`. |
| `f2-rig` | Adds the `rest400` scenario (the first 400 lines of `rest1000`, 2,000 devices). Adds the marginal functions (`noop`, `plus_tank`/`minus_tank`, `plus_pipe`/`minus_pipe`, `edit_pipe`/`edit_back`, `plus20`/`minus20`), with a marginal phase after the window in the `rest*` scenarios (`run-server.js`; `F2_MARGINAL=0` turns it off). Logs to `../f2-logs/rig/<run>/`. Adds `f2-analyze.js`, `f2-campaign.sh`, `f2-pairs.sh` and the one-time edit scripts `edits-gen.js` and `edits-run.js` that made these changes. Drops F1's campaign and table scripts; this folder keeps them. |
| this folder | `f2-rig`, plus F1's `f1-table.js` and `campaigns/f1/f1-campaign.sh`. The campaign drivers, WP5's gate script and F2's edit scripts moved to `campaigns/`. The baseline patch moved to `mod-side/baseline-eb28fc5/`, and the detached mod side is in `mod-side/`. Nine scripts and `tools/f3.ps1` were edited so the rig runs from `tools/` (next section). Everything else is byte-identical to `f2-rig` (`documentation/2026-09-23-fluid-followups/cleanup-logs/tools-manifest.tsv`). |

### Changes in this copy (`changes-from-f2-rig.diff`)

The batch copies wrote their logs next to themselves (`../wp5-logs`, `../f1-logs/rig`, `../f2-logs/rig`) and hard-coded the worktrees. This copy reads them from the environment instead:

- `RIG_LOGS` (required): the folder that receives one directory per run, for instance `documentation/<batch>/<package>/<package>-logs/rig`. Read by `run-server.js`, `run-client.js`, `campaign.js`, `tables.js`, `compact.js`, `build-tables.js` and `paced.js`. `build-tables.js` writes `wp5-tables.md` into `$RIG_LOGS/..`. `paced.js` reads `$RIG_LOGS/paced/reports`.
- `RIG_WORKTREE` / `RIG_BASE_WORKTREE`: the candidate (`wp5`) and baseline (`base`) builds of `campaign.js`.
- `RIG_JDK` (optional): the JDK `bin` folder with `jcmd`, `jfr` and `java`. The default is `C:/Program Files/Java/jdk-21.0.11/bin`. `tools/f3.ps1` reads it too.

## Layout

| path | what |
|---|---|
| `run-server.js` | One dedicated-server run. It checks the machine, moves the bridge jar to `run/mods-client-only`, copies the template to `run/bench-<runId>` and writes `server.properties` from `server.properties.in`. It starts `gradlew runServer -PfluidRigDiagnosticTicks=200 -PfluidRigHeapMiB=4096`, then over RCON runs `function createcheme_bench:forceload` and the scenario, and waits until a `fluid_diag` line reports every device. Then come the warm-up and the window (JFR with `wp5-window.jfc`, `sampler.ps1`, `jcmd Thread.print` every 10 s, heap info), `jcmd GC.run`, the marginal phase (`rest*` only) and `stop`. |
| `run-client.js` | One integrated-client run. It puts the bridge jar in `run/mods`, writes `options.txt`, copies the template to `run/saves/bench-<runId>` and starts `gradlew runClient ... -PfluidClientQuickPlay=bench-<runId>`. Commands are typed into chat through the bridge's HTTP endpoint (127.0.0.1:9876). The window is the same as the server's. It ends with a plain and an F3 screenshot, then save and quit. |
| `campaign.js` | Runs a plan one run at a time: `server`, `client`, `soak`, or a list of `kind:build:scenario[:warmup:window[:rep]]`. It checks the machine before each run and runs `analyze.js` after it. A crashed JVM's `hs_err` file goes to `$RIG_LOGS/jvm-crash/` and the run is repeated once. |
| `analyze.js` | Turns a run directory into `summary.json` (fields below). |
| `f2-analyze.js` | F2's placement stall and marginal single-block costs, in `f2-summary.json`. |
| `tables.js`, `compact.js`, `build-tables.js`, `paced.js`, `f1-table.js` | WP5's pair, compact and paced tables and F1's table, built from `summary.json` files. |
| `gen-datapack.js` | Writes the `createcheme_bench` datapack (1.21.1, pack format 48). `node gen-datapack.js template/bench-template/datapacks` regenerates the stored datapack byte for byte. |
| `template/bench-template/` | The void superflat template world: creative, peaceful, day, weather and mob cycles off, 48 force-loaded chunks (x 16..111, z 16..143), spawn platform at 64, 100, 80, `allowCommands 1b`, datapack enabled, no fluid data. |
| `server.properties.in` | Offline mode, RCON on 25575 with password `wp5bench`, `max-tick-time=-1`, view and simulation distance 10. |
| `wp5-window.jfc`, `make-jfc.js`, `minecraft-flightrecorder-config.jfc` | The window's JFR settings: Minecraft 1.21.1's own `/jfr` profile with `jdk.ObjectCount` off, and thread CPU, thread allocation and resident set every 1 s. |
| `sampler.ps1` | Samples process CPU, working set, private bytes and threads once a second. |
| `rig-lib.js`, `rcon.js` | Shared helpers and a Source RCON client. |
| `level-allow-commands.js` | Sets `allowCommands` in a `level.dat` (see the pitfalls below). |
| `tools/` | `Flatten` flattens the bridge's alpha-0 screenshots. `KeyTap` and `f3.ps1` send a real F3 key event. |
| `mod-side/` | The detached instruments, `reattach.patch`, and `baseline-eb28fc5/` (the same instruments for a build older than the rig, as WP5 used them). |
| `campaigns/` | The batch's campaign drivers as they ran, with their hard-coded worktrees: `wp5/after-client.sh`, `wp5/paced-campaign.sh`, `wp5/gates.sh`, `f1/f1-campaign.sh`, `f2/f2-campaign.sh` and `f2/f2-pairs.sh`. Also F2's one-time edits `f2/edits-gen.js` and `f2/edits-run.js`, which are already applied and no longer match. |

## Prerequisites

- Node.js (22 was used), PowerShell, and the JDK 21 the game runs on (`jcmd`, `jfr`, `java`).
- In the worktree under test:
  - The mod-side instruments re-attached (next section).
  - `run/eula.txt` with `eula=true`. The owner accepts the EULA; an agent does not.
  - For client runs, the MCP bridge jar at `run/mods/minecraft-mcp-1.21.1-neoforge-v0.3.0.jar`. The server driver moves it aside and the client driver moves it back.
- Machine rules (`AGENTS.md` and the owner's benchmark rules):
  - Before every run, no `Endfield.exe` may be running and at least 20 GB must be free. Both drivers refuse otherwise.
  - One Gradle invocation on the machine at a time, and never a test suite while a game runs. The drivers refuse to start beside another game JVM.
  - 60 s warm-up and 60 s window, one pair per claim, no repeats.
  - Run only the in-game runs a claim strictly needs; unit tests and rig tests are the evidence.

## Re-attach the mod-side instruments

The rig reads `fluid_diag` log lines and the `fluid_diag_ticks.csv` and `fluid_diag_fps.csv` files. `FluidInGameDiagnostics` and `FluidInGameClientDiagnostics` write them when the switches `-PfluidRigDiagnosticTicks`, `-PfluidRigHeapMiB` and `-PfluidClientQuickPlay` are given. All of that was removed in `c1b8464`. To put it back, run this from the repository root of the worktree under test, at `c1b8464` or later:

```bash
git apply tools/fluid-in-game-rig/mod-side/reattach.patch      # or git apply -3 ... if later commits moved the context
```

This restores exactly what `88df883` had (checked in `cleanup-logs/patch-checks.txt`):

- the two classes under `src/main/java/com/wormzjl/createcheme/runtime/fluid/`;
- their registration in `CreateChemE`;
- the three switches, with the `fluidRig` closure, on the `client` and `server` runs.

`mod-side/FluidInGame*.java` equal `git show 88df883:src/main/java/com/wormzjl/createcheme/runtime/fluid/<name>`.

Do not commit the result. Undo it with `git apply -R tools/fluid-in-game-rig/mod-side/reattach.patch`. While it is attached, nothing is registered unless `-PfluidRigDiagnosticTicks` is given, so the gates are unaffected. Still, take it off before any commit.

To attach the instruments to a build from before the rig (WP5's baseline was `eb28fc5`):

1. Run `git apply mod-side/baseline-eb28fc5/tracked-files.patch`. It adds the switches, the registration and a `diagnosticSnapshots()` accessor, and was checked against `eb28fc5`.
2. Copy `mod-side/baseline-eb28fc5/FluidInGame*.java` into `src/main/java/com/wormzjl/createcheme/runtime/fluid/`. The server class there counts every island as `AWAKE`, because there are no certificates before WP2.

## Run

```bash
cd tools/fluid-in-game-rig
export RIG_LOGS=D:/Minecraft/Modding/1.21/CreateChemE/documentation/<batch>/<package>/<package>-logs/rig
node run-server.js <worktree> <runId> <scenario> [warmupSeconds=60] [windowSeconds=60]
node analyze.js "$RIG_LOGS/<runId>"                     # summary.json; f2-analyze.js for the marginal phase
node run-client.js <worktree> <runId> <scenario> [60] [60]   # client row, bridge jar in run/mods
RIG_WORKTREE=<candidate> RIG_BASE_WORKTREE=<baseline> node campaign.js server   # or client, soak, or a run list
```

A run id must be new: the driver refuses one whose `$RIG_LOGS/<runId>` already exists. NeoForge keeps the `run/config/createcheme-common.toml` that an earlier start wrote. A campaign comparing builds whose config defaults differ must therefore set the value first, as `campaigns/f1/f1-campaign.sh` does for `certificateStationaryTolerance`.

### Scenarios (`createcheme_bench:<name>`)

Lines run along +x at y = 64. Letters: G generator, U pump, P pipe, R 1 m3 tank, V void. Every device has the placement defaults: tanks hold nitrogen at 298.15 K and 1 atm, generators supply water, pumps run at 0.01 m3/s and 500 kPa.

| function | content | devices |
|---|---|---|
| `empty` | Nothing; only the 48 force-loaded chunks. | 0 |
| `rest100` | 100 closed lines `RPPPR`. | 500 |
| `through100` | 100 lines `GUPRPPV`: pumped water through a flooding tank into a void. | 700 |
| `fill100` | 100 lines `GUPRPRPR`: pumped water into three closed nitrogen tanks. | 800 |
| `mixed100` | 50 fill, 25 through and 25 rest lines. | 700 |
| `rest1000` | 1,000 rest lines (16 columns by 63 rows). | 5,000 |
| `rest400` | The first 400 lines of `rest1000` (F2). | 2,000 |
| `pure100` | 100 lines `GUPPPV`: through-flow with no vessel. | 600 |
| `probe`, `probe2`, `probe3`, `probe4` | The WP5 and F1 probes, in order: mixed line kinds with six-tank fills; fill variants and gravity lines; pump transfers from tank to tank; vented chains. | 237, 528, 144, 264 |
| `forceload` | `forceload add 16 16 111 143`. | |
| `view` | Spectator mode and `tp @s 64 150 80 0 90`: the client's camera above the networks. | |
| `noop`, `plus_tank`/`minus_tank`, `plus_pipe`/`minus_pipe`, `edit_pipe`/`edit_back`, `plus20`/`minus20` | F2's marginal edits: a no-op for the RCON round trip, a lone tank, a branch pipe on line 0, a facing edit and back, twenty lone tanks. | |

### Per run (`$RIG_LOGS/<runId>/`)

The drivers write:

- `run.json` (events, window bounds, placement time, machine state);
- `gradle-server.log` or `gradle-client.log`, and `latest.log`;
- `window.jfr`, `sampler.csv`, `threads-start.txt`, `threads-mid-NN.txt`, `threads-end.txt`;
- `heap-start.txt`, `heap-end.txt`, `heap-live.txt`;
- `fluid_diag_ticks.csv`, plus `fluid_diag_fps.csv` in the client;
- `view.png` and `view-f3.png` (client only).

`analyze.js` then writes `summary.json` with these fields:

| field | content |
|---|---|
| `windowSeconds`, `warmupSeconds`, `placementMillis` | the run's timing |
| `jfrRssMiB` | resident set from JFR |
| `mcServerTickTimeMs` | Minecraft's own tick average. It excludes the engine's `ServerTickEvent.Post` work, so it is reported but not compared. |
| `jfrCpuCores` | process CPU from JFR |
| `gc` | count, total pause, longest pause, heap-inspection GCs |
| `heapMiB` | heap after each GC |
| `allocationMiBPerSecond` | allocation rate |
| `process` | the sampler's cores, working set, private bytes and threads |
| `threadCores`, `threadCoresUnattributed`, `topThreads` | CPU by thread family (server, render, fluidWorkers, gc, jit, observation, jvmOther, other) |
| `ticks` | whole-tick and engine ms per tick: p50, p95, p99, max |
| `diagCountersInWindow` | scheduling counters over the window |
| `endState` | online tick, devices, islands, kinds, statuses, workers |
| `placementGap`, `heldLines` | the placement stall and the held-island warnings |
| `liveHeapMiB` | heap after the final full collection |
| `fps` | client frame rate (client only) |

## Known pitfalls

- **Cheats in a quick-played world.** A singleplayer world runs `/function` only if its `level.dat` has `allowCommands 1b`. Otherwise the client refuses the scenario, and `run-client.js` fails on the error reply within 15 s. The stored template already carries `allowCommands 1b` (checked in all three batch copies on 2026-09-24).
- **`level-allow-commands.js` is kept as a template tool, not wired into the drivers.** It was run once on the template's `level.dat` in WP5, and no driver ever called it. Wiring it into `run-client.js` would only set a value the template already has. Run it only on a regenerated template: `node level-allow-commands.js template/bench-template/level.dat`.
- **`/function` rejections.** `Unknown function` means the datapack is not enabled in the world (the template enables it). Every command's reply is read back, and any error fails the run.
- **The bridge's `execute_command` never reaches the server.** It resolves to KubeJS's client-side command path, and even `/help` is refused. Commands are therefore typed into chat (`open_chat`, then `type_text` with Enter). The bridge's `get_player_info` and `debug_fields` are unusable too. The frame rate comes from `fluid_diag_fps.csv`, and the F3 overlay needs a real key event (`tools/f3.ps1`).
- **Minecraft's `/jfr` profile forces full GCs.** It enables `jdk.ObjectCount` with period `everyChunk`, which caused 18 heap-inspection GCs in one 60 s window, and it records no resident set. The window is therefore recorded with `jcmd JFR.start` and `wp5-window.jfc`.
- **`max-tick-time -1`.** A pre-F2 build stalls the server thread for about a minute while placing 5,000 devices, and the default watchdog would stop the server.
- **Bridge screenshots have alpha 0.** Flatten them with `java -cp tools Flatten <png>`.
- **Client window assumptions.** The client driver saves and quits by clicking the pause screen's bottom button at (426, 384) in the default window size. It also overwrites `run/options.txt`.

## Checks made at consolidation (2026-09-24, no game run)

- `node gen-datapack.js` into an empty folder reproduces `template/bench-template/datapacks/createcheme_bench` byte for byte.
- `analyze.js` and `f2-analyze.js`, run from this folder on a copy of `f2-logs/rig/srv-rest1000-f2after-r01`, reproduce that run's stored `summary.json` and `f2-summary.json` exactly.
- `node --check` passes on every script.
- `mod-side/reattach.patch` applies to `c1b8464` and to the branch head, and its result equals `88df883` (`cleanup-logs/patch-checks.txt`).
