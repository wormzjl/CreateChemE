# Fluid scheduler WP3: engine-owned presentation, review of the implementation

Date: 2026-09-23. Branch `claude/fluid-scheduler` (worktree `agent-ae139e4fc1b184b36`), not pushed, not merged. Batch `2026-09-23-fluid-scheduling-rest`.

Plan: `FLUID_ISLAND_REST_PLAN.md` revision 3 (main checkout, `documentation/2026-09-23-fluid-scheduling-rest/`): requirement R2, section 3.4, section 5 item 2, section 6 row `viewers`. Every number quoted here is in `wp3-tables.md`. Logs, test XML, campaign and gate scripts and comparison scripts are in `wp3-logs/`. Dev-client screenshots are in `wp3-screenshots/`.

## 0. Summary

* **Built:** menu protocol `fluid-4` (static and live payloads), presentation buckets, dirty-marked loaded devices, and queued inputs whose reply the engine composes and delivers with the next bucket. The 10-tick menu refresh and every reply from a packet handler are gone. Deviations are in section 1.7.
* **Science is untouched by viewers.** On the `viewers` profile (mixed100 with all 365 fixture chunks loaded, 10,803 bound devices, 16 open menus, 80 scripted edits), every island that was neither edited nor held at startup follows the mixed100 trajectory bit for bit, including the certified closed ladders that the menus materialise at every bucket. The islands that part are exactly the startup-held ones, and they part the same way between two runs without viewers.
* **Rates:** 1.000 to 1.015 live payloads per 100 ticks per menu, 0.040 static; 0.998 (certificates off) and 0.775 (on) view builds per 100 ticks per consumer. 80 of 80 edits were answered on the first bucket after them, none at their own tick, with latencies of 2 to 99 ticks.
* **Gates on `2f8d43a`:** science 161/0, runtime 189/0, network 30/9, 19/14, 37/3, regression exactly zero, GameTest 26 passed, P12 and P31 byte-identical to the WP2 copies.
* **Dev client (MCP bridge):** RESTING and STEADY status lines on screen, a view that moves only on its 5 s bucket, `Waiting for the engine` on first open and after Apply, `Applied` on the following bucket, and a reopened menu showing the last delivered view until its bucket. Screenshots are in section 5.

## 1. What was built, against plan 3.4 and R2

Commits of WP3 on the branch, after `9b954a4`:

| commit | content |
|---|---|
| `9d58e95` | `FluidPresentation` (buckets, dirty devices, menus, queued inputs, replies), `IslandCoordinator.presentation` (a read that never wakes), new counters, `PresentationBucketTest` (7) |
| `f081342` | protocol `fluid-4`, handlers queue inputs, menu and block-entity binding, world wiring, screen, client cache, `FluidPacketCodecTest` (+5), `FluidPresentationGameTests` (4), audit pattern |
| `04632e9` | `viewers` benchmark profile |
| `2f8d43a` | every paced report states its configured warm-up and window and how the reference tick was derived (owner's request during the task) |
| `94077cd` | changelog WP3 entries |

### 1.1 Buckets (plan 3.4, bullet 3; R2)

`FluidPresentation` owns the schedule.

* A device or menu belongs to bucket `key % 100`. The key is the island that owns the device, or the device itself when it has none.
* The bucket for online tick `t` flushes at the end of the world tick, after the tick's engine work and events, when `t % 100` is its slot.
* The per-tick check is one comparison with the next due tick. That tick is found over a 100-bit mask of non-empty buckets, so no device is scanned. A flush visits only its own bucket.
* In a flush, each device's view is built once, from one presentation read of its island, whether a menu or a loaded block entity asked for it.
* `published` marks the island's loaded members dirty and nothing else. `onLoad`, `bindIdentity` and `loadedChunk` only mark. A block entity reports its unload and removal, which drops its mark.
* At startup, devices in chunks that were already loaded are marked once. This is O(chunks), for block entities that loaded before the authority existed. The first bucket binds them and presents them.
* Views are built only for devices that are loaded or have an open menu. With all fixture chunks unloaded (every other profile), view builds, flushes and menu packets stay at zero, and the per-publication marking is a set lookup per member.
* A view reads its island with `IslandCoordinator.presentation`. A certified island is materialised on demand, as the plan asks, but never onto a wake it has not acted on: the read stops one tick short of its horizon or module drive, and the island's own deadline wakes it at the same tick as without viewers. Replay is a function of the certificate and the tick (`graphAt`), so the materialised state does not depend on how many reads came before.

### 1.2 Menu protocol `fluid-4` (plan 3.4, bullet 1)

* **Static payload** `fluid_static`: kind, registration revision, component axis, presets and material names.
* **Live payload** `fluid_live`: view, controls and the engine's reply.
* Both go out only from the owning island's bucket. The static payload goes with a menu's first bucket and again only when the device's registration revision changed. In practice that is after an accepted edit: 5 per edited menu in the window, 0 elsewhere.
* The client joins the two parts and validates the whole again (`MenuData.of`).
* `FluidDeviceMenu.broadcastChanges` no longer sends anything, and `sendState` no longer exists.
* A menu subscribes when it is constructed on the server and unsubscribes in `removed`. A menu that is abandoned (not open, not valid, player gone) is dropped at its next bucket.

### 1.3 Queued inputs (plan 3.4, bullet 2)

* The edit and recovery handlers validate menu, position, identity, permissions, rate, revision and bounds as before. They queue the ledger event and pass the input to the world. They send nothing. An inadmissible packet is still dropped with no reply at all.
* The reply is composed at the menu's first bucket after the online tick the input arrived in. An input that arrives inside a bucket's own tick waits for the next bucket. The possible replies are:
  * `Not applied: <reason>` for a refusal;
  * `Applied` once the event is no longer pending, or for an edit that changed nothing;
  * otherwise `Queued for simulation event at tick N`, followed by `Applied` at the first bucket after the event applies.
* Several replies in one bucket are joined with ` / `, bounded at 1,024 characters.
* The screen shows `Waiting for the engine` from Apply, or Recover solids, until a reply arrives.

### 1.4 Screen

* On opening, the screen shows the last state delivered for that device in this session (a client cache of 32 devices, cleared at logout), with `Last delivered view. Waiting for the engine`. With nothing cached it shows `Waiting for the engine`.
* It updates only when a live payload arrives.
* The Lag line now also shows `View <t> s`, the bucket time of the displayed view, so the 5 s cadence is visible.

### 1.5 Counters

New counters: `bucketFlushes`, `devicePresentations`, `staticPayloads`, `queuedInputs` and `inputReplies`, beside the existing `viewBuilds` and `menuPackets`. In the benchmark, a tick with a bucket flush no longer counts as idle.

### 1.6 Tests (plan 5 item 2)

* **`PresentationBucketTest`**, 7 tests on the synchronous rig with real solves:
  * zero, one and sixteen menus on a transient and a steady island: exactly 20 deliveries per 2,000 ticks per menu, all at the island's bucket, one view per device and one island read per bucket, and static only on the first bucket and after a revision change;
  * loaded against unloaded devices: a transient island's loaded device is presented once per interval at its bucket, a steady island's device is not presented after it certifies, and an unloaded device never builds a view;
  * replies on the following bucket and never before: refused, queued then applied, applied, no-op, arrival inside the bucket's own tick, and a refused recovery;
  * science identical with and without viewers: rest, steady, a settled pair revalidating every 3 intervals, and a transient fill, over 12,000 ticks with 16 menus and every device loaded. The solved publications match by WP2's `publications` fingerprint, and solve counts, final states and replayed boundary totals also match;
  * a presentation read stops one tick short of the horizon and never wakes the island;
  * a resting island with a loaded device costs nothing per tick, and with a menu one flush and one materialisation per 100 ticks;
  * a menu follows its device to a new island's bucket.
* **`FluidPacketCodecTest`**, +5 tests:
  * `fluid-4` and distinct payload identities;
  * the static and live split and rejoin;
  * axis and reply bounds;
  * the client cache (LRU and logout);
  * the refusal reason unwrapped from the JSON reader.
* **`FluidPresentationGameTests`**, 4 GameTests, tick-precise, each waiting for its own cleanup:
  * a menu opened between buckets receives nothing until its island's next bucket, which brings the static and live payloads; a second menu opened 30 ticks after the first's delivery gets its first payload exactly at the next bucket and a live-only one 100 ticks later;
  * an edit sent the tick before the island's bucket is answered at that bucket with exactly `Queued for simulation event at tick e`. The next delivery, on the replacement island's first bucket after the event applied, says `Applied`, and a stale edit's refusal arrives on the first bucket after it;
  * RESTING (lone tank) and STEADY (nitrogen line to a void) status lines arrive with their bucket, and the resting view has no lag;
  * a chunk load binds with the identity only (the update tag is `{FluidIdentity}`), marks the device without building a view, and the view appears exactly at the first bucket after the load.

### 1.7 Deviations from the plan and the brief

1. **Bucket key.** Plan 3.4 says devices flush at `identity % 100`. The implementation keys by the owning island (the device itself without one), because the same section puts menus on "the owning island's presentation bucket", and because a per-device key would read a certified island up to 100 times per 100 ticks. An island replaced by an event gets a new identity and therefore a new phase; open menus are re-keyed with it.
2. **No `PRESENTATION_BUCKET` heap entry.** The brief allows either option. The presentation keeps its own next-due tick, checked in O(1) at the end of the world tick, so a bucket sees the events applied in its own tick. The reserved enum constant was removed.
3. **Presentation reads never wake.** This is an addition: a view materialises a certified island but stops short of an unacted wake (1.1). The unit tests and the benchmark show the effect: trajectories are bitwise equal with and without viewers.
4. **"A menu that opens shows the last delivered view"** is a client-side cache of the last state delivered per device. The server sends nothing at open, the static payload included. A first-ever open shows `Waiting for the engine` (screenshot `12`).
5. **Follow-up `Applied`.** After a `Queued ...` reply, the application is reported at the first bucket after it.
6. **Refusal texts.** A control refused by its own validation reaches the handler wrapped by Gson ("Failed to invoke constructor ... with args [..., [D@79d1b6d, ...]"). This was pre-existing. The handler now replies with the innermost reason: `Not applied: Controls are outside the supported range`.
7. **Recovery refusal at application.** A recovery whose event finds no cake used to send a chat message immediately while the event applied. It is now `Not applied: Filter unchanged: inventory full or no captured solids` in the reply. Still immediate, and outside simulation status:
   * the `debugChat` option's HELD broadcast (a debug switch, off by default);
   * the action-bar refusal "Fluid device is waiting for its world identity." when a block is used before binding.
8. **Robustness and cost additions:**
   * a view that fails to build is logged (`fluid_presentation device=... status=FAILED`, which the benchmark audit counts as a runtime error) and the rest of the bucket continues;
   * `FluidWorldAuthority.at()` uses a position index rebuilt with the registrations, because every block-entity load and binding asks it.
9. **Benchmark windows.** The pair and the mixed100 run used 60 s warm-up and a 120 s window. They were finished before the owner's switch to 60 s windows, and were kept per the instruction. The reports and `wp3-tables.md` state the windows. The harness already derived the reference tick from the configured window (mid-window on the 100-tick grid); since `2f8d43a` every report also states its configured warm-up, window and that derivation.
10. **`Queued` in the benchmark.** None of the 15 accepted edits was still queued at its bucket: every event applied within about three ticks. The tick-precise `Queued` reply is covered by the GameTest, where the edit lands the tick before the bucket.
11. **Viewers integrity.** The profile's integrity requires every fixture chunk loaded (instead of unloaded) and the presentation checks passed.

## 2. Gates

On `2f8d43a` (the only later commit, `94077cd`, is the changelog). One Gradle invocation at a time, `JAVA_OPTS=-Xshare:off`, daemon with `-Xshare:off`; sequence in `wp3-logs/gates.log`, logs in `wp3-logs/final-gate-*.log`.

| gate | result |
|---|---|
| `fluidScienceTest --rerun` | 161 tests, 0 failures |
| `fluidRuntimeTest --rerun` | 189 tests, 0 failures (177 + 12) |
| `fluidNetworkBenchmark --rerun` | 30/9, 19/14, 37/3 accepted/rejected substeps |
| `fluidSolverRegression -PfluidRegressionMode=exact` | chain-100 max deviation 0.000e+00 in state/moles, temperature, phase fraction, flow |
| `runFluidGameTestServer -PfluidGameTestRunId=wp3-final-r01` | All 26 required tests passed, scheduler self-verification on |
| P12 / P31 fingerprints | byte-identical to the `wp2-logs` copies (SHA-256 `56332b64...` and `4dcb80a4...`, the WP0 values) |

**Flake found and fixed during development.** `dev-gametest-2`: the existing `gameplayPlacementTransfersFluidAndRemovalClosesThePersistentOwnershipLedger` failed once with "Removal did not account for the retained nitrogen". The cause was a first version of the new GameTests: they removed their blocks and succeeded without waiting, so a removal event could apply during the next batch and land in that batch's destroyed-material ledger. They now wait for their own cleanup, as the other GameTests do. The two dev runs that followed and the gate run each passed 26 of 26.

## 3. The viewers benchmark (plan section 6)

Profile: mixed100 (50 transient, 25 through, 25 closed ladders), with every fixture chunk forced loaded (365 chunks, 10,803 devices, all bound by the first buckets of the warm-up). Sixteen FakePlayer menus open 10 s into the warm-up, one on each of networks 0 to 15:

* a generator on transient and through ladders;
* a reservoir on closed ladders;
* a pipe on network 3.

In the window, each menu edits five times through the real handler, 400 ticks apart and staggered by 23 ticks:

* ACCEPTED on networks 0, 2 and 3: a generator pressure, or a pipe roughness, back and forth;
* INVALID on network 1: an out-of-range pressure;
* STALE on networks 4 to 15: an old revision; on a closed ladder's reservoir the kind is refused first.

That makes 80 edits, 15 of them accepted. The runs were a quiet pair (`-PfluidRestDetection=true` and `false`) and one `mixed100` certificates-on run to compare with WP2. All three used 12 workers, a 60 s warm-up and a 120 s window. All runtime audits PASS and all integrity checks passed.

| measure (window) | viewers-wp3-off-r01 | viewers-wp3-on-r01 |
|---|---|---|
| live payloads / 100 ticks / menu | 1.000 | 1.015 |
| static payloads / 100 ticks / menu | 0.040 | 0.040 |
| all menu packets / 100 ticks / menu | 1.039 | 1.055 |
| view builds / 100 ticks / consumer (16 menus + 10,803 loaded devices) | 0.998 | 0.775 |
| device presentations / 100 ticks / loaded device | 0.999 | 0.776 |
| bucket flushes (per 100 ticks) | 2,310 (97.5) | 1,825 (77.0) |
| deliveries off their bucket | 0 | 0 |
| edits answered on the first delivery after them | 80 / 80 | 80 / 80 |
| replies at the edit's own tick, unanswered, wrong text, accepted never Applied | 0, 0, 0, 0 | 0, 0, 0, 0 |
| reply latency, ticks: min / median / p95 / max | 5 / 42 / 96 / 96 | 2 / 44 / 90 / 99 |
| reply texts | Applied 15; Stale fluid controls 45; Reservoir initialization is fixed 15; Controls are outside the supported range 5 | same |
| engine ms per tick p50 / p95 / max | 0.166 / 0.515 / 211.4 | 0.150 / 0.455 / 205.9 |
| whole tick ms p50 / p95 | 0.727 / 1.310 | 0.638 / 1.246 |
| full solves in window | 2400 | 1818 |

Latency is spread evenly over 1 to 100 ticks, as it should be for edits at arbitrary phases of a 100-tick bucket (histograms in `wp3-tables.md`). View builds per consumer sit at 1 per 100 ticks with certificates off, where every island publishes every interval. With certificates on they fall to 0.775, because devices of certified islands are not rebuilt without a publication. The menus on the four closed ladders show `STEADY: replaying ... kg/s since ... s, next check at ... s` (and after its edits network 3's pipe menu shows `RESTING: no flow since 156.5 s`).

**Engine time.** Presenting 10,803 loaded devices costs:

* 0.15 ms per tick at the p50 of engine time (0.011 without viewers);
* whole-tick p50 0.64 ms against 0.23 ms.

Ticks above 5 ms occur about once per 100 ticks (27 in 2,371, p99 6.1 ms). They recur at fixed phases 100 ticks apart, which fits the buckets of the largest islands (up to about 170 devices each); the per-bucket cost was not timed separately. The two maxima, 206 and 211 ms, are the first accepted edit of each run, 3 ticks after it arrived. Applying a topology event compiles the whole 10,803-device registry and reads every island (`filterStock`, `boundaries`), and the first time is JIT-cold. Later edits stay under 10 ms. That cost is pre-existing event cost, not presentation (section 6).

**No-viewer numbers unchanged.** `mixed100-wp3-on-r01` against `mixed100-wp2-on-r02`:

* full solves 1807 and 1807;
* replayed 50 intervals, 3736 s against 3765 s, different only by where the final materialisation fell in wall time;
* 25 STEADY in both runs;
* ready-to-publication p50 15.5 against 14.9 ms (within the WP1 noise band);
* 0 view builds, flushes and menu packets.

Engine p50 falls from 0.021 to 0.011 ms: a publication no longer queues every member for a view-refresh check. Trajectories: 95 islands identical on every solved publication; the 5 that part were held at startup (next paragraph).

**Science against no viewers** (`wp3-logs/viewers-comparisons.txt`, `reference-grid-comparisons.txt`):

| pair | identical islands | parted (all startup-held) | replaced by edits | reference grid |
|---|---|---|---|---|
| mixed100-wp3-on-r01 vs viewers-wp3-on-r01 | 93 | 5 | 10804, 10806 (and 10807, also held) | 94 exact, only 10815 (held) not bitwise |
| mixed100-wp2-on-r02 vs viewers-wp3-on-r01 | 94 | 4 | same | 95 exact, only 10815 (held) not bitwise |
| mixed100-wp2-off-r02 vs viewers-wp3-off-r01 | 92 | 5 | 10804, 10806, 10807 | 92 exact, all bitwise |

* The parted islands are the ones a startup wall-deadline hold retried. They part at the retried first slice (tick 50 or 100), exactly as between two runs without viewers (mixed100-wp2-on-r02 vs mixed100-wp3-on-r01: 5 parted, all held; grid differences only on held islands 10807 and 10815).
* The edited islands follow mixed100 until their first edit's fence, and their replacements exist only in the viewers runs.
* The certified closed ladders materialised by their menus at every bucket are bitwise equal on the grid, and so are their ledgers (zero).

The expected bitwise equality of the solved trajectories holds wherever the fixture is the same.

## 4. Pilot run

`viewers-wp3-pilot-r01` (20 s warm-up, 40 s window, uncommitted code) failed its integrity check on two INVALID replies that carried Gson's wrapper text. That led to deviation 6. Everything else in it matched the final runs: 1.01 live packets per 100 ticks per menu, and all 32 edits answered on the first bucket after them.

## 5. Dev-client verification (MCP bridge)

Driven through the langyo/minecraft-mod-mcp bridge (`run/mods/minecraft-mcp-1.21.1-neoforge-v0.3.0.jar`) in the dev client started with `JAVA_OPTS=-Xshare:off ./gradlew.bat runClient --offline`. This was the only Gradle invocation while it ran, started after every suite, and the client was closed afterwards through Save and Quit and Quit Game.

* **World:** a fresh creative superflat world, commands on, structures off, peaceful, daylight cycle off.
* **Devices:** a lone reservoir at (2, -60, 0), and a generator, three pipes and a void along x = -8..-4, z = 0. Placed with `/setblock` and `/fill` typed into chat.
* **Controls:** the pressure field was typed with real key events (a `java.awt.Robot` helper, `build/wp3mcp/field.ps1`), because the bridge's typing does not reach a container screen's EditBox. Screenshots were alpha-flattened (`build/wp3mcp/Flatten`).

| check | screenshot(s) | observed |
|---|---|---|
| RESTING status line | `04-tank-menu-just-opened.png` | "RESTING: no flow since 98.9 s", Lag 0.00 s, View 125.10 s (lone tank) |
| RESTING on a line | `06-generator-menu-just-opened.png` | generator at 101,325 Pa into a void at 101,325 Pa: "RESTING: no flow since 110.9 s" |
| the view moves only on its bucket | `05-tank-refresh-a..f.png`, stacked in `05-tank-refresh-stack.png` | View times 125.10, 140.10, 145.10, 155.10, 160.10, 165.10, 175.10 s: always on the tank island's 5 s grid (x.10) |
| nothing on open | `12-void-first-open.png` then `13-void-first-bucket.png` | first open of the void: "Waiting for the engine" and no data; at the bucket the view appears (View 400.65 s, the line's slot) |
| a reopened menu shows the last delivered view | `10-reopen-shows-last-delivered-view.png` then `11-reopen-updated-on-bucket.png` | on reopening: View 320.65 s with "Last delivered view. Waiting for the engine"; at the next bucket the view updates (View 350.65 s) and the message clears |
| an edit is answered on the following bucket, never immediately | `07-generator-pressure-typed.png`, `08-apply-waiting-for-the-engine.png`, `08b-apply-after-1s.png`, stacked in `08-09-apply-stack.png` | pressure 150000 typed; right after Apply: "Waiting for the engine" with the old view (View 275.60 s, 101.33 kPa); on the next bucket, the replacement island's (View 280.65 s): "Applied", 150.00 kPa, "WAITING: full solve after topology change" |
| STEADY status line | `09-apply-reply-on-next-bucket.png`, `11`, `13` | "STEADY: replaying 17.70 kg/s since 288.6 s, next check at 86688.6 s", Flow 17.70 kg/s |

* The generator's island was certified when the edit arrived, so its event applied at once by materialisation, and the reply on the following bucket was `Applied`. `Queued for simulation event at tick N` needs an awake island and a bucket within about two ticks of the edit. The bridge cannot time that, and the GameTest covers it tick-precisely.
* The two "just opened" screenshots (`04`, `06`) came after their first bucket because of the bridge's latency. `12` caught the state before it.
* Setup screenshots: `00-title`, `01-create-world`, `01b-create-world-creative`, `01c-create-world-superflat`, `02-world`, `03-devices-placed`.

## 6. Open items and follow-ups

* **Event application cost with many loaded devices (pre-existing).** `applyPending` compiles the whole active registry and reads every island for each event. With 10,803 devices the first event of a run cost about 205 ms, JIT-cold, and later ones under 10 ms. This is not presentation work, but it becomes visible once worlds have many devices.
* **View building cost.** `view(id)` is O(island) per device, so a large island's bucket is O(island²); the 5 to 10 ms ticks recurring every 100 ticks in the viewers runs are consistent with that. Building all of an island's device views in one pass would make a bucket O(island). This is only needed when many devices are loaded.
* **Startup-hold nondeterminism** (WP2 section 8) is still the only source of parted islands between any two runs.
* **Immediate messages outside simulation status** (deviation 7): the `debugChat` HELD broadcast, and the "waiting for its world identity" action bar on use.
* **Plan items not done:**
  * one quiet pair plus one mixed100 run, not three repeats (per the brief);
  * the module profile is WP5;
  * persistence is WP4; the presentation holds no state that must persist.
* **Documents** stay in the worktree's untracked `documentation/fluid-scheduler/`. Nothing was copied to the main checkout; per `AGENTS.md` that happens at merge or when the batch ends. Nothing is pushed or merged.
