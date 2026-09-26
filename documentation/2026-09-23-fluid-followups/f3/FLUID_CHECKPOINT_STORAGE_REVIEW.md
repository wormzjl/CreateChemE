# F3: checkpoint storage - layout decision, payload, contracts, evidence

Date: 2026-09-24. Batch `2026-09-23-fluid-followups`, package F3 (F1 pumped fills and F2 placement cost before it, F4 pump model and thermo domain after it). Branch `claude/fluid-followups` in worktree `agent-ae139e4fc1b184b36`, over F2's head `b86b147`; F3 is `4f38107` to `e837ada` (seven commits). Not merged, not pushed. Every number below is in `f3-tables.md`; raw outputs, gate logs, test XML, reports and scripts in `f3-logs/`; probe and harness sources (not committed) in `f3-logs/probes/`.

**Windows.** Off-line numbers are single saves or loads (no window). The one paced run used a **60 s warm-up + 60 s window**, as WP4's reference run did (`f3-tables.md` section 4 gives both runs' dates and windows).

## 0. Summary

* **The owner's question.** "Any better methods other than json?" The checkpoint was NBT on the outside, but every island payload inside it, the topology ledger and the transfer ledger were JSON text in NBT byte arrays, and the whole compound was re-compressed and rewritten at every save: 24 MB of NBT for rest100, about 450 ms of IO thread per autosave even when nothing changed (measured, `f3-tables.md` 3.1), behind a 64 MiB bound that capped a world at about 430 certified rest100-size islands.
* **Layout chosen (the recommended way, one refinement).** Per-island storage units with the mod's own dirty tracking, a small core record in the world's saved data, no world-wide size bound. The units live in **pack files, one new pack per save** holding exactly the units that changed (compaction keeps packs at least half live and at most 16), rather than one file per island: a durable atomic file costs 2.3 to 2.7 ms on this disk whatever its size, so one file per island would cost 2.4 s of IO thread per 1,000 changed islands and 10,000 files per world; a pack costs one such write per save (section 1).
* **Payload slimmed.** Units are binary (exact double bits, sparse arrays, varints), the graph topology is written once per unit, the anchor (identical to the island's graph in 14 of 14 islands measured) and a REST certificate's start graph are one byte, the energy reference and thermodynamic revision are stored once per world. Per island: certified rest100 **144,229 + 1,046 bytes to 34,311 + 133**, awake stress100 114,280 + 170 to 22,938 + 133, in-game rest line 6,675 + 1,044 to 406 + 133, lone tank 3,526 + 1,044 to 243 + 133 (payload + envelope against unit + index row). The topology unit went binary too: about 850 bytes a device to 54.
* **Seeded cache.** A load seeds the dirty tracking and an island registered from a loaded snapshot keeps its payload generation, so the first save after a load writes nothing for an island that did not change (the WP4 open item and WP5's 201 ms autosave stall): 10,000 islands, first save after the load, 0 units encoded (`FluidCheckpointStoreTest`); in the GameTest, the restarted store's first save encodes nothing for the reloaded tank.
* **Timings, 100 certified rest100 islands, off-line:** cold save 205 to 63 ms of server thread and 459 to 19 ms of IO thread; a save with nothing changed 9.6 to 3.4 ms and 454 to 4.6 ms; load 806 to 49 ms. 1,000 islands: format 3 refused every save at its 64 MiB bound; format 4 saves them cold in 145 + 49 ms, warm in 15 + 6 ms, and loads them in 230 ms. **Paced rest100, certificates on:** cold save 339.8 to 76.4 ms (plus a 27.3 ms timed write), warm 4.8 to 3.9 ms, next cadence 7.6 to 10.6 ms with nothing encoded (the capture's materialisation is 9.5 ms of it).
* **Gates on `3f30155`:** science 161/0, runtime 220/0 (210 + 10 new), network 30/9, 19/14, 37/3, regression exactly zero, GameTests 30 passed, P12 and P31 byte-identical to the WP2 copies.
* **Beyond the brief:** the topology unit is binary as well (section 3.6); the paced benchmark crashed on a window without any solve (a consequence of F1's tolerance default) and its worker and latency gates now hold vacuously there (`3f30155`, section 7).

## 1. The layout decision (T2)

### 1.1 Options weighed

| option | writes per save | files at 10,000 islands | crash and ordering | removal of islands | verdict |
|---|---|---|---|---|---|
| one file per island (`data/createcheme_fluid/<id>.dat`) | one flushed atomic file per changed island: 2.3 to 2.7 ms each (`f3-tables.md` 1) = 2.4 s of IO thread per 1,000 changed, 24 s per 10,000 (a through-flow world changes every island at every autosave) | 10,000 | needs a commit order across files anyway | delete the file | rejected: IO cost scales with changed islands at a fixed 2.5 ms, loads open 10,000 files (100 ms for 1,000 in the probe), backups copy 10,000 files |
| fixed shards (N islands per file by identity) | one write per dirty shard, rewriting every island in it | 10,000 / N | as above | rewrite the shard | rejected: one changed island rewrites its whole shard, so the saving over rewriting everything shrinks as islands wake at random |
| per-island `SavedData` instances | one file each, written by Minecraft | 10,000 in `data/` beside vanilla's files | none: `DimensionDataStorage` saves its map in hash order, the core could be written before or after its islands | none: the storage keeps every instance it ever loaded and has no delete; a failed read is logged and treated as absent (a silently empty island) | rejected |
| **packs: one new pack per save with the changed units (chosen)** | **one flushed atomic file per save (6 ms for 5 MB, 37 ms for 50 MB), plus the core record** | **at most 16 referenced (+ those of the previous core record)** | **pack first, core record last; the core's rename is the commit** | **dead units are dropped from the index; a pack less than half live is compacted; unreferenced packs are deleted** | **chosen** |

Packs are shards by save rather than by identity: they keep the owner's rule (a save writes only the islands whose payload generation changed, unchanged islands cost nothing, no world-wide bound) and pay one durable write per save instead of one per island.

### 1.2 What is stored where

* **Core record** (`data/createcheme_fluid_core.dat`, the world's saved data, gzip NBT as every saved data file, with Minecraft's `DataVersion`): `FluidFormat` 4; `Epoch`; `Ledger` (transfers, pending material, capacity reservations, modules and bindings; JSON, as before, with `LedgerSHA256`); `Strings`, an append-only table of the long texts units refer to (dimension, package, the full property revision anchors and signatures carry, the certificate policy); `Packages`, per property package its compressibility, thermodynamic revision, energy revision and energy components; `Packs` and `PackBytes`; `NextPack`, `NextGeneration`; `Topology` (generation, pack, offset, length, SHA-256); `Islands` in columns (`Id`, `Revision`, `Generation`, `Online`, `Committed`, `Retry`, `Cadence`, `Base`, `Kind`, `Since`, `Start`, `Horizon`, `Pack`, `Offset`, `Length`, `SHA256`); `SHA256` over every field. The clock and certificate summary live here because they change without the unit changing (online time advances every tick; a certified island is only materialised).
* **Island unit** (`FluidCheckpointCodec.islandUnit`): magic, unit format 1, kind, island identity, revision and unit generation (the load checks all three against the index); dimension and package (table references) and compressibility; status; fallback allowance; fences; the graph's topology once (node ids, elevations, kinds, volumes; pipe ends, sections, controls); its state (inventories, phases, pipe blocked masks and filters); the approximation anchor as "the same graph", a state over the same topology, or a full graph, with its revision and modes; the last solved interval (seconds, flows, substeps, pump work, boundaries, rejection reasons, modes, heads, acceptance, pipe transfers with zero streams as one byte); for a certified island its signature (property revision and policy by reference, the 32-byte graph digest) and the interval's start graph, again related to the main graph. A certified island's unit records its certificate base, not its materialised state, so it never changes while the island stays certified.
* **Topology unit**: the world topology ledger without its online tick (the epoch), binary (section 3.6).
* **Packs** (`data/createcheme_fluid/fluid-<n>.pack`): magic, pack format 1, pack number, then units back to back. A save starts a new pack past 256 MiB, so no save is bounded by one array.

### 1.3 Dirty tracking and the seeded cache

`FluidCheckpointStore` keeps, per island, the revision and in-memory payload generation its stored unit records and where the unit lies. At a save, an island whose revision and payload generation match keeps its unit in place; any other is encoded into the new pack under a fresh persistent generation (the per-world counter `NextGeneration`; the JVM-wide payload generation does not survive a restart, so the "generation" the index and unit headers carry is this one). The topology unit is kept while the ledger is the same objects (`sameWorldBody`, as WP4's topology cache). A load reads the index, gives every decoded snapshot a fresh payload generation and records it with the unit; `IslandCoordinator.register` now keeps a snapshot's payload generation (`4f38107`) and touches the island when it discards a saved certificate (a discarded certificate changes the unit's contents). So the first save after a load writes nothing for an unchanged island, and with the scheduler's self-verification on (every unit test and GameTest) every reused unit is re-encoded and its SHA-256 compared with the stored one.

### 1.4 Commit protocol, crashes and orphans

* A save is prepared on the server thread (capture, then the changed units encoded and the core record built) and committed on Minecraft's IO worker (`IOUtilities.withIOWorker`, so a flushing save such as server stop waits for it): the new pack(s) first, each through a temporary file beside it, flushed to disk (`FileChannel.force`) and renamed over the target atomically; then the core record the same way. The core record's rename is the commit. `FluidSavedData.save(File)` overrides Minecraft's saved-data write so that nothing reaches the core before its packs; `save(CompoundTag)` commits on the calling thread (the benchmark and tests).
* A crash before the core's rename leaves the previous core record and every pack it references intact; the new pack and any temporary file are orphans. A load deletes, once it has accepted the checkpoint, every pack its core record does not reference and every temporary file beside the packs or the core record (`cleanOrphans`); a refused load deletes nothing.
* After a commit, a pack is deleted only when neither the new core record nor the one before it references it, so a copy of the world taken during a save still finds the packs of the core record it copied.
* A commit whose predecessor was not written (an IO failure) is not written either; the next save writes every unit afresh into a new pack and is self-contained. A commit never throws on the IO worker.
* Compaction: a pack less than half live, or the smallest packs beyond 16 referenced, have their live units moved into the save's new pack (read by range, digest-checked, copied byte for byte: same generation and SHA-256).

### 1.5 Load validation

Every pack the core lists must exist, have exactly the listed size, and carry its magic, pack format 1 and its own number; every unit's SHA-256 must match the index; its header must name the island, revision and generation the index gives. Otherwise the world is refused: "Fluid pack N is missing", "holds N bytes but the checkpoint expects M: a half-written or damaged pack", "Fluid checkpoint checksum mismatch in the unit of island N", "The stored unit of island N holds island N revision R generation G but the index expects ... the world's fluid data is inconsistent", "Fluid pack format N cannot be read", "Fluid storage unit format N ... cannot be read", each ending "Create a fresh world for this development build." Packs are read one at a time and released once their units are decoded.

### 1.6 Bounds removed

The 64 MiB bound on ledger plus payloads, the 10,000-island bound, and the 64-million-character and 2-million-element bounds on the topology JSON are gone. What remains bounds a single unit's structure against corrupt data (as format 3 did): at most 10,000 nodes and 100,000 pipes per graph, 4,096 components per array, 1 MiB per text, 65,536 fences, 16 referenced packs (by compaction), 256 MiB per pack (by starting another).

## 2. What a payload held, and what went (T1)

`f3-tables.md` section 2 gives every section for the four island kinds. For a certified rest100 island (mean of six, 17.7 nodes, 24.5 pipes):

| part | format 3 JSON | format 4 | why |
|---|---|---|---|
| thermodynamic revision and energy reference | 766 | 0 | derived from the model: stored once per package in the core record and checked at load (a changed basis or energy datum still refuses the world) |
| approximation anchor | 31,813 | 28 | its graph was the island's graph in 14 of 14 islands: stored as "the same graph" (1 byte) plus its revision by reference and its modes; a different state over the same topology, or a different topology, is still stored in full |
| certified interval's start graph | 30,989 | 9,042 | a state over the base graph's topology (identity data not repeated); for REST certificates identical to the base: 1 byte |
| graph topology | 5,945 | 1,522 | binary, once per unit |
| graph state | 25,055 | 9,041 | binary doubles with their exact bits; zero entries not stored |
| pipe transfers | 48,237 | 14,276 | binary; an empty direction is one byte |
| rest of the interval, status, identity, signature | 1,327 | 402 | varints, table references, a 32-byte digest instead of 64 hex characters |
| **unit** | **144,229** | **34,311** (gzip 27,453) | |
| per-island envelope | 1,046 (NBT compound with the signature's three strings) | 133 (index row) | |

What stays because it is not reconstructible: inventories and phase splits (a flash would not reproduce the committed bits), the last interval's pipe transfers and boundaries (the solver's gross transport history, which STEADY replay scales), the certificate's start graph (its per-interval deltas are replayed), the anchor when it differs. Nothing derived is stored except the island identity, revision and generation that the unit header repeats deliberately so a unit can be checked against its index row. Compressing units was measured and not adopted: gzip takes a binary rest100 unit from 34 to 27 KB (20 %) for IO-thread CPU at every write and every load.

## 3. Contracts kept

### 3.1 Round trips

Exact through the real layout on disk (`FluidCheckpointStoreTest`, first test) and through the in-memory image (`FluidCheckpointFormatTest`): clocks (online, committed, retry, cadence), inventories and phase states bit for bit, fences, statuses, allowances, last results (flows, heads, modes, pump work, pipe transfers), pending material and capacity reservations, a module, a queued topology event, REST and STEADY certificates (kind, since, base, horizon, signature, interval start and base graphs) and a held island (status and retry tick). A loaded checkpoint saves back to the same core record with no pack written. A topology decoded from its unit re-encodes to the same bytes (`aTopologyRoundTripsEveryFieldExactlyAndRefusesAMalformedUnit`: pumps, valves, two dimensions, slurry grades, events with a replacement, a removal and a recovery, solid totals, pending recoveries).

### 3.2 Format-3 validation rules, every field

Kept, each with its message, now reached through the index columns or the unit: missing or mistyped fields; checksum mismatch (envelope, ledger, unit, topology); a changed thermodynamic basis or energy datum ("use a fresh development world"); a base tick below 0 other than the awake sentinel, later than the committed tick; a horizon earlier than it; an awake island carrying a certificate; a certified island without its record; an interval empty or negative or disagreeing with its result; since outside [0, base]; an unknown kind; a malformed signature; nonfinite per-node deltas; a fence before the committed tick; an island ahead of the epoch; module type or scientific revision not this build's. New with the layout: a unit outside its pack, in an unlisted pack, of another identity, revision or generation, truncated, with trailing bytes, of another unit or pack format; a duplicate or out-of-range generation; a next-pack counter not after the last pack (`everyInvalidCertificateFieldIsRefused` edits 18 index fields, each unsealed and then resealed to reach its own check, plus malformed units).

### 3.3 Certificates

The signature discard rules are unchanged: certificates off, a different property revision, policy or graph identity discard the certificate and keep the inventory (`aSignatureMismatchDiscardsTheCertificateAndKeepsTheInventory`, seven cases, and `restDetectionOffDiscardsEverySavedCertificateAndKeepsTheInventory`); inconsistent certificate data under a matching signature refuses the load. A certificate saved during a property hold is still not kept (the island is saved awake at its held state, WP4 deviation 5; `holdSaveAndRestartKeeps...`). A certified island still loads materialised to its committed tick without a solve, and 1,000 of them dispatch nothing before their horizon.

### 3.4 Owner's standing rules

* No save compatibility: format 3 and older (and other unit or pack formats) are refused with the fresh-world instruction; there is no reader, migration or legacy test for them.
* Engine-owned presentation and the tick loop: untouched. A save happens where Minecraft saves; the per-tick hook still only marks the saved data dirty; file IO moved off the server thread (format 3 built and deep-copied the whole tag on the server thread; format 4 encodes only changed units there).
* One Gradle invocation at a time; no suite while a client or server ran; no `git stash` (the "before" harness ran with the sources restored to `b86b147` by `git restore` and restored to `HEAD` right after, verified clean).

### 3.5 Behaviour that changed

* Minecraft's saved-data write of the core record is replaced by the store's own commit (same file, same gzip NBT with `DataVersion`, same atomic write), so the packs precede it.
* A save no longer rewrites the whole checkpoint: the core record (about 21.7 KB of NBT for 100 islands, 6.2 KB on disk) is rewritten at every save, packs only when a unit changed.
* The benchmark's save report adds the write time, units moved, pack and core record bytes and the files on disk; its `compressedBytes` field (the gzip size of the old tag) is gone.

### 3.6 The topology unit (beyond the brief)

The topology ledger was the last JSON in a save and, after the island payloads, most of its bytes: 8.7 MB for 10,180 devices, 87 MB for 101,800 (over the 64-million-character bound), re-encoded whole at the first save after any placement or edit and parsed at every load (most of format 3's 800 ms load). Its unit is now binary (`0d44cb0`): devices in identity order with their position, kind, facing, geometry and composition (texts, geometries and compositions shared through per-unit tables), events, totals, basis and recoveries; validated as the ledger validates itself. 54 bytes a device; the 1,000-island world's load went from 3.3 s (JSON topology in a unit, run `t4-after`) to 0.23 s.

## 4. Tests (T3)

| test | what it holds |
|---|---|
| `FluidCheckpointStoreTest.clocksMaterialFencesPendingEventsModulesCertificatesAndHeldIslandsRoundTripThroughTheFiles` | Minecraft's save path (`SavedData.save(File)`) writes the core record and `fluid-1.pack` in a temporary world folder; a fresh store reads everything back exactly, a start-up-held island's retry tick and status included |
| `...aSaveWritesOnlyTheUnitsOfIslandsThatChanged` | a save after no change writes no unit and no pack (every pack file byte-identical); after one island's solve, one new pack holding exactly that island's unit, and the index points the other islands at pack 1 |
| `...aCrashBetweenAPackAndItsCoreRecordLeavesThePreviousCheckpointAndAnOrphanTheLoadDeletes` | an injected failure after the pack, before the core record's rename: the save reports it was not written, the previous core record stays byte-identical, a restart reads the previous state, and its cleanup deletes the orphan pack and two leftover temporaries; the failed store's next save writes everything afresh and succeeds |
| `...aCommitWhosePredecessorFailedIsNotWrittenAndTheNextSaveWritesEverythingAfresh` | commits queued on an IO executor: the first fails at its pack, the one built on it is not written, no file changes, the next save is self-contained and reads back |
| `...aHalfWrittenDamagedOrMissingPackRefusesTheWorldAndLeavesEveryFileAsItWas` | a truncated pack, a flipped unit byte, a missing pack and a pack naming another number each refuse the world with its message; every file, an unreferenced orphan included, is byte-identical after the refusal |
| `...unitsOfRemovedOrReplacedIslandsAreDroppedAndTheirPacksCompactedAndDeleted` | three of four islands removed: the survivor's unit moves out of the sparse pack; the unreferenced pack stays one save and is then deleted; a replaced island's pack likewise; 40 saves changing one of 20 islands: at most 16 packs referenced and the files on disk always exactly the packs of the last two core records |
| `...aSaveLargerThanOnePackSpansSeveralAndReadsBack` | with a 1,000-byte pack size, 12 islands span several packs, read back, and a later change writes one |
| `...tenThousandIslandsSaveAndLoadThroughTheIndex` | 10,000 certified islands: cold, warm (nothing encoded or written but the core), load, registration, first save after the load (nothing encoded), with timings printed |
| `...theFirstSaveAfterALoadEncodesNothingThatDidNotChange` | awake, REST and STEADY islands and the topology reloaded and registered: nothing encoded, no pack; with certificates off at the restart exactly the three discarded islands are written |
| `FluidCheckpointFormatTest` (9, rewritten) | round trip with pending material, module and queued event, saving back to the same core record; restored certificates continue bit for bit without a solve; every invalid field refused; certificates discarded when off or when the signature changed (a discarded island's payload generation changes); the dirty rule counted through `FluidSavedData`; 1,000 certified islands load without a solve burst; hold, save and restart; format 3 and older, a pack of another format refused and left untouched |
| `FluidCheckpointCodecTest` (6: 5 rewritten, 1 new) | round trip and re-encoding to the same image, the anchor stored as the island's graph; missing, mistyped, unsealed, flipped and unknown-version refusals; a changed basis, overridden property data or energy datum asks for a fresh world; a velocity limit keeps stock and invalidates the anchor; the topology unit round trip |
| `FluidBasisTest`, `WorldTopologyLedgerTest` | moved from the JSON topology functions to the binary ones |
| GameTest `FluidCheckpointGameTests.aSavedRestingTankReloadsCertifiedWithItsStatusLineAndNoSolve` (extended) | the world's own saved data saved through `SavedData.save(File)` on NeoForge's IO worker; the core record (format 4 with `DataVersion`) and packs copied as a restarted server finds them and read by a fresh store; the tank restored certified with its status line; the restarted store's first save encodes nothing for it; 200 ticks with no solve |
| GameTest `FluidHarnessGameTests.savedDataKeepsADepletedNitrogenChargeAndAtomicWritesKeepThePreviousCheckpoint` (moved to format 4) | save through the IO worker into a scratch folder, read back with clock and inventory exact, a failing atomic write leaves the core record as it was, a flipped unit byte and a truncated pack are refused |
| GameTests `FluidModuleGameTests`, `SolidPhaseGameTests`, `FluidHarnessGameTests` (world round trip) | unchanged in what they hold; they reload through a reopened store |

## 5. Timings (T4)

`f3-tables.md` sections 3 to 5. Off-line harness at `b86b147` and at `0d44cb0`, same fixture (clones of the paced benchmark's first 20 ladders), same machine state (no game, 27.8 to 28.4 GB free), one after the other:

| 100 islands | save | server thread ms before / after | IO thread ms before / after | load ms before / after |
|---|---|---|---|---|
| certified | cold | 204.9 / 62.8 | 459.2 / 18.6 | 806.1 / 48.5 |
| certified | warm | 9.6 / 3.4 | 453.9 / 4.6 | 757.9 / 24.8 |
| certified | next cadence | 20.1 / 10.3 | 480.1 / 5.6 | 802.0 / 21.7 |
| awake | cold | 155.3 / 17.5 | 328.4 / 9.6 | 786.8 / 22.3 |
| awake | warm | 5.3 / 1.9 | 341.8 / 4.1 | 691.8 / 22.8 |
| awake | next cadence | 112.0 / 8.0 | 386.6 / 11.8 | 743.8 / 25.0 |

1,000 islands: every format-3 save refused at the 64 MiB bound; format 4 cold 145.4 + 48.7 ms (certified) and 112.8 + 34.8 ms (awake), warm 14.5 + 6.4 and 12.9 + 5.5 ms, next cadence 60.2 + 9.7 ms (certified, nothing encoded; 57.5 ms is the capture's materialisation of 1,000 certified islands) and 78.2 + 41.1 ms (awake, all 1,000 re-encoded), load 157 to 240 ms. The IO-thread column is new evidence: format 3 re-compressed its whole checkpoint at every save, which the WP4 save-time figures (server thread only) did not show.

**Paced rest100, certificates on, 60 s + 60 s** (`rest100-f3-on-r02` against WP4's `rest100-wp4-on-r01`): cold 339.8 to 76.4 ms of server thread (F3 plus a 27.3 ms timed write); warm 4.8 to 3.9 ms (+3.7 ms write of the 21.7 KB core record); next cadence 7.6 to 10.6 ms with nothing encoded in either (capture 5.1 against 9.5 ms: materialisation of 100 certified islands, not storage; preparation 2.5 against 1.1 ms). Bytes written per save: 24.1 MB of NBT (gzip 4.87 MB) at every save against 4.39 MB cold and 21.7 KB warm; on disk 4.87 against 4.37 MB. The window also shows the F1 tolerance default: 0 full solves (WP4 at 1e-9: 20), 100 STEADY.

## 6. Gates (T5)

On `3f30155` (`e837ada` adds only the changelog), one Gradle invocation at a time, `JAVA_OPTS=-Xshare:off`, no game running; `f3-logs/gates.log`, `f3-logs/gate-final-*.log`.

| gate | result |
|---|---|
| `fluidScienceTest --rerun` | 161 / 0 |
| `fluidRuntimeTest --rerun` | 220 / 0 (210 + 10 new) |
| `fluidNetworkBenchmark --rerun` | 30/9, 19/14, 37/3 |
| `fluidSolverRegression -PfluidRegressionMode=exact --rerun` | exactly zero (chain-100: 0.000e+00 in state, temperature, phase fraction, flow) |
| `runFluidGameTestServer -PfluidGameTestRunId=f3-final` | All 30 required tests passed |
| P12 / P31 fingerprints | byte-identical to the `fluid-scheduler/wp2-logs` copies |

A development GameTest run (`f3-dev1`, on the format-4 working tree before its first commit) also passed all 30 and left `world/data/createcheme_fluid_core.dat` with `fluid-1.pack` and `fluid-2.pack` after the server's stop save.

## 7. Deviations from the brief, and what was skipped

1. **Packs, not one file per island** (section 1.1), with the measurement that decided it.
2. **The topology unit is binary too** (`0d44cb0`, section 3.6). The brief asked to slim the island payload; after that the topology JSON was the largest part of a cold save and most of a load.
3. **"Generation" in the index and unit headers is the persistent per-world unit generation**, not the JVM payload generation (which restarts at every server start); the payload generation stays the in-memory dirty key.
4. **The certificate signature moved into the unit** (it changes only with the certificate, which touches the unit anyway); the index keeps the summary (base, kind, since, start, horizon).
5. **The paced benchmark's worker and latency gates hold vacuously when no solve falls in the window** (`3f30155`). The first paced run (`rest100-f3-on-r01`) crashed in the report code before any save: with eps_s at 1e-7 (F1) every rest100 island certifies before the window, the latency list was empty and its percentile unboxed as null. The run was repeated once after the fix (`rest100-f3-on-r02`); every other gate is unchanged.
6. **The off-line harness clones solved islands** (the paced benchmark's first 20 ladders, re-identified and re-signed) instead of solving 1,000 distinct ones, and runs with a 10 GiB heap: the first 4 GiB attempt ran out of memory once 1,000 awake islands held their solver workspaces (simulation memory, not storage).
7. **The "before" harness ran on the `b86b147` sources restored into this worktree** by `git restore` and restored to `HEAD` right after (no stash, no second worktree).
8. **GameTests: 30, none added**; two were extended to the new layout, as the brief allowed.

Skipped under the owner's benchmark rule, and why: no dev-client or dedicated-server run (the brief allowed none beyond the GameTest; the seeded cache's effect on WP5's 201 ms autosave stall is established by the unit tests and the GameTest, which show nothing encoded at the first save after a load, and by the off-line IO timings, which show the whole-checkpoint recompression gone); no paced `stress100`, `transient100` or certificates-off pair (storage does not reach the solve path; P12, P31 and the exact regression are byte-identical); no repeats of anything that passed; the harness's earlier attempts (`t4-after`, `-r2`, `-r3`) are superseded, not repeats of a pass.

## 8. Open items

* **Directory flush.** Units and the core record are flushed before their rename, but the renames themselves are not flushed to the directory (Java has no portable directory `fsync`; NeoForge's saved-data write does the same). A power loss right after a save could, on some file systems, keep an older core record; keeping the previous core record's packs one save longer means that older record still finds its packs.
* **Delta encoding.** A STEADY certificate's start graph (9 KB of the 34 KB unit) and the pipe transfers (14 KB) could be stored as differences against the base; not done (the unit is already 4.2 times smaller and the start graph is written only when the certificate is issued).
* **The core record is rewritten at every save**: 133 bytes an island (about 1.4 MB of NBT, 0.4 MB on disk, for 10,000 islands), because online ticks advance every tick. Columns keep it cheap; splitting clocks from the index would not remove the rewrite.
* **The topology unit is rewritten whole when the ledger changes**: 54 bytes a device (5.7 MB at 101,800 devices). Per-region topology units would bound it; not needed at today's sizes.
* **Pack heuristics** (half live, 16 packs, 256 MiB) are covered by tests but not tuned against a long in-game session.
* **A refused world still crashes the client** (pre-existing; WP4 open item); the refusal message now also names a missing or damaged pack.
* **The paced benchmark's gates** for a fully certified window are now vacuous for worker and latency; the owner may want a dedicated rest gate (for instance zero full solves) for such profiles.
* **Documents** stay in the worktree's untracked `documentation/fluid-followups/` (`FLUID_CHECKPOINT_STORAGE_REVIEW.md`, `f3-tables.md`, `f3-logs/`); nothing was copied to the main checkout, merged or pushed. Benchmark and GameTest worlds are left under `run/` (`fluid-benchmark/rest100-f3-on-r0{1,2}`, `fluid-gametest-f3-{dev1,final}`).
