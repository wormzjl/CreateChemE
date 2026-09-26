# Fluid scheduler WP4: persistence, checkpoint format 3, review of the implementation

Date: 2026-09-23. Branch `claude/fluid-scheduler` (worktree `agent-ae139e4fc1b184b36`), not pushed, not merged. Batch `2026-09-23-fluid-scheduling-rest`.

Plan: `FLUID_ISLAND_REST_PLAN.md` revision 3 (main checkout, `documentation/2026-09-23-fluid-scheduling-rest/`): requirement R5, sections 3.5 and 3.6, section 5 items 7 and 8, section 6 report addition "save time". Every number quoted here is in `wp4-tables.md`. Logs, test XML, reports, gate and campaign scripts, comparison output and the old-world refusal report are in `wp4-logs/`. Dev-client screenshots are in `wp4-screenshots/`.

**Benchmark window from now on: 60 s warm-up, 60 s measured.** WP2 and WP3 figures quoted for comparison used 120 s windows and are marked.

## 0. Summary

* **Built:** checkpoint format 3 as plan 3.5 describes it: an NBT envelope with the world epoch, the ledger and modules, and one compound per island whose payload is an opaque, checksummed, cached byte array. Certificates are saved with a validity signature, restored on load (materialised from their base to the saved committed tick without a solve, holding only their horizon), and discarded with the inventory kept when the signature changed or `restDetection=false`. Every new field is validated on load. Formats 1 and 2 are refused with the instruction to create a fresh world. Deviations are listed in section 1.8.
* **Save time (60 s window):** once a world's islands are certified, a save copies every payload and the topology: **7.6 ms** for rest100 one cadence after the window, against 78 ms with certificates off and 103 ms for stress100, whose through-flow islands re-encode every payload. A cold save of 100 islands and about 10,000 devices costs 257 to 340 ms.
* **Load without a solve burst:** 1,000 certified islands load in 5.7 ms of registration after a 167 ms decode; from the load to their horizon the coordinator dispatches no solve, runs no pump, fires no deadline and visits no island; at the horizon the 1,000 wake together and solve one revalidating interval each.
* **rest100 off against on:** 100 STEADY, full solves 1200 to 20, reference-grid deviation 1.64e-8, ledger deviation 0: the WP2 pattern at the shorter window.
* **Gates on `ce25b78`:** science 161/0, runtime 193/0, network 30/9, 19/14, 37/3, regression exactly zero, GameTest 28 passed, P12 and P31 byte-identical to the WP2 copies.
* **Dev client (MCP bridge):** a lone tank and a dead-headed line rest, the world is saved and reopened, and both status lines continue from their saved since times ("RESTING: no flow since 110.7 s" and "since 134.5 s") while the views advance; the load log reads `certificates_saved=2 certificates_restored=2 awake=0`. The old format-2 development world is refused with a clear message and its fluid checkpoint is left byte-identical.

## 1. What was built, against plan 3.5, 3.6 and R5

Commits of WP4 on the branch, after `94077cd`:

| commit | content |
|---|---|
| `ff2fba1` | certificates carry their saved form (`IslandCertificate.Saved`) and signature (`IslandCertificate.Signature`); `register` restores or discards; payload generations; counters `certificatesRestored`, `certificatesDiscarded`, `payloadsEncoded`, `payloadsReused` |
| `4f39701` | checkpoint format 3 (`FluidCheckpointCodec`, `FluidSavedData`), refusal of older formats, removal of the legacy upgrade paths and their tests, `FluidCheckpointFormatTest` (9) |
| `86d3332` | GameTests: a module-coupled set through a hold (`FluidPropertyReloadGameTests`), a saved resting world reloading without a solve (`FluidCheckpointGameTests`) |
| `4dcfa01` | save time in the paced benchmark |
| `f6b5dd8` | topology format 4: the topology ledger saved without its online tick, its encoding kept while the ledger is unchanged |
| `ce25b78` | a log line at world load |
| `3b6f206` | changelog |

### 1.1 Format 3 (plan 3.5, first bullet)

One NBT compound (`FluidCheckpointCodec.write` / `decode`):

* `FluidFormat` 3 and `Epoch`, the world's online tick at the save.
* `Ledger`: JSON of the buffered-transfer ledger (buffers, pending material, capacity reservations) and the module states and bindings, with `LedgerSHA256`.
* `Islands`: one compound per island with `Id`, `Revision`, the clock (`Online`, `Committed`, `Retry`, `Cadence`), `Base` (the certificate's base tick, or the awake sentinel -1), a `Certificate` compound when certified (`Kind`, `Since`, `Start`, `Horizon`, and the signature's `PropertyRevision`, `Policy`, `Graph`), the `Payload` byte array and `PayloadSHA256`.
* The payload is the JSON of dimension, package, compressibility, thermodynamic revision and energy reference, graph, fallback allowance, anchor, last solved interval, status and fences, and for a certified island `certifiedFrom`, the graph its certified interval started from. A certified island's payload records its certificate base (the interval's end state and the interval itself), not its materialised state.
* `SHA256` covers the format, the epoch, the ledger digest and, per island, every scalar field, the certificate fields and the payload digest.
* The size bound is kept: ledger and payloads together at most 64 MiB, refused on encode and on decode.
* The world topology stays beside it (`FluidSavedData`), now as topology format 4 (section 1.8, item 3).

### 1.2 Certificates and signatures (plan 3.5, second bullet)

* A certificate keeps the solved interval it replays (`IslandCertificate.Interval`: start and end ticks, the graph it started from, the result). Replay needs exactly that: `Summary` is rebuilt from it bit for bit, and so are the per-node deltas, the scaled boundary transfers, pump work and pipe history.
* `IslandCoordinator.Certified` carries the saved form `Optional<IslandCertificate.Saved>` (kind, since tick, horizon, interval, signature). It is empty while a property hold freezes the island (section 1.5).
* The signature is made when the certificate is issued: `ApproximationAnchor.revision(model)` (the full revision, with velocity clamp, trace cutoff and solid settings), the six policy values written exactly in a fixed order (`restDetection`, the two doubles `eps_s` and `delta_budget` in hexadecimal, and `K_max`, the confirm count and the recheck seconds), and a SHA-256 of graph identity: node ids, kinds, elevations, vessel volumes, fixed-node inventories (moles, energy, solids) and temperature and pressure bits, and pipes with endpoints, sections, control, blocked mask and filter (capacity, resistance, stop flag, cake, energy).
* On registration (`IslandCoordinator.register`): with certificates off the certificate is discarded ("certificates are off (restDetection=false)"); a differing signature part discards it ("its property revision changed", "its certificate policy (... when saved, ... now) changed", "its graph identity changed"). A discarded island keeps the inventory it was loaded with, starts awake with its solver caches, reads `WAITING: saved <KIND> certificate discarded: <reason>` and solves its debt. Otherwise the certificate is re-issued from its interval under the current policy and must come out with the saved kind and horizon; it is then restored with its since tick, no solver caches and only its horizon (or module drive) as deadline.

### 1.3 Cached payloads (plan 3.5, first and last bullets)

* `FluidCheckpointCodec.PayloadCache`, owned by `FluidSavedData`, keeps per island the encoded payload and its digest keyed on revision and payload generation. A matching island is copied into the save (its bytes cloned, so a tag can never alter the cache); any other is encoded and cached. Islands absent from a save are forgotten.
* The payload generation is a JVM-wide unique number (`IslandCoordinator.Snapshot.payloadGeneration`). It changes whenever anything a payload records changes and never on materialisation: at every round closure (accepted or refused), at dispatch (status `SOLVING` or `WAITING: shared worker capacity`), at every fence installed or released, at certificate creation and renewal, at a wake, at a hold and a resume, and when materialisation stops a certified island at its fence (its status then reads `WAITING: event alignment`). A snapshot built outside a coordinator has generation 0 and is never cached.
* Verification: runs with the scheduler's self-verification switch (every unit test and GameTest) re-encode each copied payload and each kept topology and fail if the cache would have written anything else.
* Dirty marking is unchanged: `FluidWorldAuthority.tick` still marks the saved data every tick.

### 1.4 Load and validation (plan 3.5, third bullet)

* A certified island is materialised at decode from its base to the saved committed tick (`IslandCertificate.rebuild` then `graphAt`), without a solve. Its snapshot carries that inventory, the interval's result as its last result, and the saved form, which registration then judges (section 1.2).
* Refused, each with a message naming the island and the values: a base other than the awake sentinel below 0; a base later than the committed tick; a horizon earlier than the committed tick; an awake island carrying a certificate; a certified island without its record; an interval that is empty or does not end at the base; an interval whose length disagrees with its result; since outside [0, base]; an unknown kind; a malformed signature; nonfinite per-node deltas; a fence before the committed tick; an island ahead of the epoch; a missing or mistyped field; any checksum mismatch; a changed thermodynamic basis or energy reference ("use a fresh development world").

### 1.5 Property hold (plan 3.6)

The in-memory behaviour of WP2 is unchanged: the hold materialises a certified island to the hold tick and caps it there, online time keeps accruing, resume discards every certificate. A save during the hold writes the island awake at the held state (its `Certified.saved` is empty), with the online time accrued since as debt. After a restart the island solves its debt from the held state and requalifies over `restConfirmIntervals` (`holdSaveAndRestartKeepsCommittedFrozenAndDebtAndRequalifiesOverTheConfirmCount`, confirm 2 and 3).

### 1.6 Older formats refused, legacy code removed (plan 3.5, fourth bullet)

* `FluidSavedData.load` and `FluidCheckpointCodec.decode` read format 3 only: "Fluid checkpoint format N cannot be read: this build reads format 3 only and has no upgrade from older formats. Create a fresh world for this development build." A topology other than format 4 is refused likewise.
* `FluidSavedData.open` used to refuse an unreadable file with a generic message (the storage logs the cause separately); it now reads the file again for the reason and puts it into the refusal. The file is never written.
* Removed: `upgradeLegacySolids`, the format-2 absent-field defaults (production capacity, modules, bindings, module type), the legacy topology decode, and the energy-datum migration (section 1.8, item 1), with `FluidSaveCompatibilityTest`, `EnergyReferenceMigrationTest` and `SolidRuntimeTest.actualVersionOnePayloadMigratesAdditively`. `FixedSplitModuleTest` and `ProductionCapacityTest` now assert that a missing module list, module type or capacity record is refused.

### 1.7 Tests (plan 5 items 7 and 8)

* **`FluidCheckpointFormatTest`** (9, replacing `FluidSaveCompatibilityTest`), on a synchronous rig with real solves:
  * round trips through `FluidSavedData`: clocks exact, inventories bit for bit, fences, statuses, allowances, pending material, a module, a queued topology event, and REST and STEADY certificates (kind, since, base, horizon, signature, interval start and base graphs); a loaded checkpoint saves back to the same bytes;
  * a restored certificate continues without a solve and bit for bit with the unsaved island over 5,000 ticks, keeps its since tick and status, holds only its horizon; the payload holds the base and the load materialises it;
  * every invalid field refused (13 edits of the island and certificate compounds, a nonfinite energy delta, a fence before the committed tick), each first as an unsealed edit and then resealed to reach its own check;
  * `restDetection=false` discards both certificates, keeps the inventories and the islands solve again;
  * signature mismatch: the property revision (a velocity limit, same thermodynamic revision), each of the five non-switch policy values, and a graph-identity change (every pipe section one metre longer) each discard and keep the inventory;
  * cached payloads, counted: the first save encodes 3; the next copies 3; after the awake island solves, 1 encoded and 2 copied; after the STEADY island's horizon revalidation, 2 encoded and 1 copied; the topology is encoded once;
  * 1,000 certified islands load without a solve burst (section 4);
  * hold, save and restart (section 1.5);
  * formats 1 and 2 and topology format 2 refused with the fresh-world instruction and the tag left unchanged.
* **`FluidCheckpointCodecTest`** (5) moved to NBT: the round trip re-encodes to the same tag; missing and mistyped fields, unsealed edits, a flipped payload byte and an unknown version are refused; a changed basis or energy datum asks for a fresh world; a changed velocity limit keeps stock and invalidates the old anchor.
* **`FluidPropertyReloadGameTests.aModuleCoupledSetIsFrozenByAHoldAndRequalifiesAfterResume`**: three dry tanks coupled through two known-zero fixed-split modules on the shared workers certify REST; the hold freezes all three committed ticks and both modules while online time accrues by 40 ticks or more; resume discards all three certificates; each island requalifies with since equal to base and base at least the held tick plus `restConfirmIntervals` cadences; the modules close cycles again.
* **`FluidCheckpointGameTests.aSavedRestingTankReloadsCertifiedWithItsStatusLineAndNoSolve`**: a lone tank in the GameTest world certifies REST; the world's own saved data is saved as an autosave would save it (format 3, topology at the epoch) and loaded into a fresh coordinator standing in for the restarted server; the tank is restored certified with the saved since tick and the same status line; over 200 ticks nothing is submitted to a worker, the tank is advanced by the identity to now, and its inventory is unchanged.
* **`McpGameplayRegressionTest`** keeps its archived gameplay captures unchanged (their provenance note asks for that). They are solver inputs, read as graphs through `FluidCheckpointCodec.decodeGraph`; the test fills the fields the 2026-09-16 capture predates (solids, blocked masks, filters) as empty and asserts they were absent.

### 1.8 Deviations from the plan and the brief

1. **Energy-datum migration removed, not left untouched.** The brief asked to keep `migrateToSensibleReference` if it serves a live property change and otherwise to leave it untouched and report it. It serves only old saves: a world's energy reference is `EnergyReference.sensible(basis)`, a function of the basis alone, and a basis change is refused anyway by the thermodynamic revision. The migration reads only format-1 and format-2 checkpoints. Keeping it would have meant keeping a format-2 reader and, through `FluidSavedData.migrateToSensibleReference`, an explicit format-2 to format-3 upgrade, which plan 3.5 excludes; porting it to format 3 would have extended it. It was removed with the format-2 reader and its test (`EnergyReferenceMigrationTest`, a legacy-save test). It had no production caller. It is in git history (`94077cd`) if the owner wants a format-3 datum migration later.
2. **The certificate's heavy part is in the payload.** The island compound carries the certificate's scalar fields and signature as the plan lists them; the interval's starting graph (`certifiedFrom`) sits in the cached payload with the base graph and the interval's result, so that a certified island's whole save is copied.
3. **Topology format 4 (added).** The first pilot showed the topology JSON (8.2 MB for the fixture's 10,803 devices) re-encoded at every save, because its snapshot carries the online tick: the warm save took 64 ms. The envelope already carries the world epoch, so the topology is now saved without the online tick, read at the epoch, and its encoding kept while the ledger's immutable parts are the same objects. Warm save: 64 ms to 14 ms in the pilot, 4 to 5 ms in the measured runs.
4. **Payload generation.** The plan names accepted solves and certificate creations; the generation also changes at dispatch, fence changes, wakes, holds and resumes and when a certified island stops at its fence, because each changes something a payload records (section 1.3). Verification compares every copied payload with a fresh encoding in every test run.
5. **A certificate saved during a hold is not kept.** Plan 3.6 discards every certificate at resume; a save during the hold writes the island awake at its held state, which is what a resume would produce. Its saved status is the hold reason until its first solve after the restart.
6. **Inconsistent certificate data refuses the load.** A matching signature with a kind or horizon that the saved interval does not give under the same policy is not a changed world but inconsistent data, so the load is refused rather than the certificate discarded.
7. **Not persisted:** a horizon revalidation in progress, a qualification streak, the refusal and evidence diagnostics, the retry-span hint and solver caches. A restart during a revalidation slice or a qualification streak starts the streak again; an island restarted during its revalidation loses its since tick.
8. **The first save after a load encodes every payload** (the cache starts empty). Seeding it from the loaded bytes is a follow-up.
9. **World epoch.** `Epoch` is the topology's online tick; a checkpoint without topology (test fixtures only) takes its most advanced island's online tick. No island may be ahead of the epoch.
10. **Module-coupled "pair".** A fixed-split module needs one feed and two product buffers, so the GameTest couples three islands through two modules, the configuration WP2's unit test certifies.
11. **The save-and-reload GameTest** loads into a fresh coordinator, since a GameTest cannot restart its own server; the real restart is the dev-client check (section 5).
12. **Benchmark:** one run per profile, as the brief asked; rest100 has no second on/off pair for a floor. The runs were built from `f6b5dd8`; `ce25b78` adds only the load log line.

## 2. Gates

On `ce25b78` (`3b6f206` is the changelog). One Gradle invocation at a time, `JAVA_OPTS=-Xshare:off`, daemon with `-Xshare:off`; sequence in `wp4-logs/gates.log`, logs in `wp4-logs/final-gate-*.log`. Quiet machine (no Endfield.exe, 27.8 to 28.1 GB free).

| gate | result |
|---|---|
| `fluidScienceTest --rerun` | 161 tests, 0 failures |
| `fluidRuntimeTest --rerun` | 193 tests, 0 failures (189 + 9 new - 5 legacy) |
| `fluidNetworkBenchmark --rerun` | 30/9, 19/14, 37/3 accepted/rejected substeps |
| `fluidSolverRegression -PfluidRegressionMode=exact` | chain-100 max deviation 0.000e+00 in state/moles, temperature, phase fraction, flow |
| `runFluidGameTestServer -PfluidGameTestRunId=wp4-final-r01` | All 28 required tests passed (26 + 2), scheduler self-verification on |
| P12 / P31 fingerprints | byte-identical to the `wp2-logs` copies (SHA-256 `56332b64...`, `4dcb80a4...`) |

## 3. Save time (plan section 6, T4)

Campaign `wp4-logs/campaign.sh`: before each run no Endfield.exe and at least 20 GB free (26.3 to 28.4 GB), 19:00 to 19:07, no crash. Runs `rest100-wp4-off-r01`, `rest100-wp4-on-r01` (both with `-PfluidBenchmarkMemory=true`) and `stress100-wp4-on-r01`, 12 workers, **60 s warm-up and 60 s window**. All audits PASS, integrity passed, 0 held intervals.

| run | cold ms | warm ms | next cadence ms | next cadence payloads encoded / copied | bytes written (MB) | gzip (MB) |
|---|---|---|---|---|---|---|
| rest100, certificates off | 257.0 | 4.1 | 78.3 | 100 / 0 | 19.5 | 3.5 |
| rest100, certificates on (100 STEADY) | 339.8 | 4.8 | **7.6** | **0 / 100** | 23.0 | 4.7 |
| stress100, certificates on (100 AWAKE) | 337.5 | 5.2 | **103.2** | **100 / 0** | 21.0 | 3.7 |

* **Cold** is the full JSON encoding of every payload and the topology: about 20 MB for 100 islands and 10,803 devices, 250 to 340 ms.
* **Warm** (the next save in the same tick) copies everything in every profile, since nothing changed; it measures the copy, 4 to 5 ms for 20 to 23 MB.
* **Next cadence** is the realistic autosave: 100 paced ticks later, the through-flow and certificates-off islands have solved and are encoded again, while the certified rest100 islands were only materialised and are copied. Capture (which materialises 100 certified islands) is 5.1 ms of the 7.6 ms.
* Certified payloads are about 30 % larger (14.8 against 11.4 MB) because they also carry the interval's starting graph.

## 4. Load without a solve burst (plan 5 item 7)

`FluidCheckpointFormatTest.aThousandCertifiedIslandsLoadWithoutASolveBurst`, gate run: 1,000 lone tanks certified REST, with a 60 s recheck so that the test can see their horizon (tick 1400), saved at tick 300 (3.53 MB of payloads, encoded in 53.2 ms) and decoded in 167.4 ms; registration takes 5.7 ms and restores all 1,000 certificates, discarding none, and leaves no island ready. Over ticks 301 to 1399 the coordinator dispatches 0 solves, runs 0 pumps, fires 0 deadlines, materialises nothing and visits no island. At tick 1400 the 1,000 horizons fire; the woken tanks solve one revalidating interval each (the slice `[1400, 1500]`, at most 64 dispatches per tick) and all renew.

## 5. Dev-client verification (MCP bridge)

Driven through the langyo/minecraft-mod-mcp bridge (`run/mods/minecraft-mcp-1.21.1-neoforge-v0.3.0.jar`) in the dev client started with `JAVA_OPTS=-Xshare:off ./gradlew.bat runClient --offline`, the only Gradle invocation while it ran, after the campaign and before the gates. Screenshots are alpha-flattened.

* **World:** a fresh creative superflat world ("New World (1)"). The bridge's `click_button_index` changes a cycle button's label without calling its setter, so the world was created with the defaults; commands were typed into chat, which worked. Devices placed with `/setblock` and `/fill`: a lone reservoir at (8, -60, 7), and a generator at (11, -60, 7) feeding three pipes that end in air (`04-devices-placed.png`).

| check | screenshot | observed |
|---|---|---|
| both rest before the save | `05-tank-resting-before-save.png`, `06-line-resting-before-save.png` | tank "RESTING: no flow since 110.7 s", Lag 0.00 s, View 175.10 s; generator "RESTING: no flow since 134.5 s", View 205.50 s |
| save and quit to title | (file) | `createcheme_fluid_core.dat`: FluidFormat 3, TopologyFormat 4, Epoch 4550, 2,298 bytes |
| reopen: the log | `wp4-logs/dev-client.log` | `fluid_world status=LOADED format=3 online_tick=4550 islands=2 certificates_saved=2 certificates_restored=2 awake=0` |
| reopen: the tank | `08-tank-resting-after-reload.png`, `09-tank-resting-after-reload-later.png` | "RESTING: no flow since 110.7 s", Lag 0.00 s, View 250.10 s then 280.10 s |
| reopen: the line | `10-line-resting-after-reload.png` | "RESTING: no flow since 134.5 s", View 295.50 s |
| old format-2 world | `11-old-format2-world-selected.png`, `wp4-logs/old-world-refusal-crash-report.txt` | refused at server start (below) |

* **No solve on load.** The load line records both certificates restored and nothing awake. A restored REST certificate has no horizon and never solves; had either island solved (after a discard or a wake), its status would have left RESTING and a requalified certificate would carry a since tick after the load. Both since times are the saved ones while the views advance past the saved epoch.
* **Old world.** Opening the WP3 development world (FluidFormat 2) stops the integrated server at start with "Existing fluid authority could not be read: Fluid checkpoint format 2 cannot be read: this build reads format 3 only and has no upgrade from older formats. Create a fresh world for this development build. Refusing to replace its inventories: .\saves\New World\.\data\createcheme_fluid_core.dat". As before for any unreadable fluid authority, the client closes with a crash report carrying that message. `createcheme_fluid_core.dat` is byte-identical afterwards (SHA-256 before and after in `wp4-logs/old-world-*.sha256`). Minecraft itself rewrote `level.dat` and the region files during the aborted start; the world was restored byte for byte from the copy taken before the check.
* Setup screenshots: `00-title`, `01-world-list`, `02-create-world`, `03-create-world-superflat`, `07-world-list-after-save`.

## 6. Open items and follow-ups

* **Size bound.** The 64 MiB bound on ledger plus payloads (kept per the brief) caps a world at roughly 430 certified islands of rest100 size (150 KB each), fewer than format 2's roughly 550 awake ones, because certified payloads carry the starting graph. The JSON payloads are verbose (every node's phase arrays as decimal text); a binary payload, or a bound per island, would lift the cap and cut the cold save.
* **A refused world crashes the client.** Pre-existing handling for an unreadable fluid authority; the message now names the reason. A disconnect to the title screen with that message would be friendlier.
* **Seed the payload cache at load** from the loaded bytes (section 1.8, item 8).
* **`FluidWorldAuthority.legacyUnbound`** (a checkpoint with islands but no topology) is now reachable only from test fixtures that save without a topology; it can go with those fixtures.
* **Startup-hold nondeterminism** (WP2 section 8) is unchanged.
* **Plan items not done:** one run per profile, not three; the plan's `module` and `viewers` rows belong to WP5.
* **Documents** stay in the worktree's untracked `documentation/fluid-scheduler/`. Nothing was copied to the main checkout; per `AGENTS.md` that happens at merge or when the batch ends. Nothing is pushed or merged.
