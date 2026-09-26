# F2: placement, removal and edit cost against world size - root cause, fix, contracts, evidence

Date: 2026-09-24. Batch `2026-09-23-fluid-followups`, package F2 (F1 pumped fills before it, F3 checkpoint storage after it). Branch `claude/fluid-followups` in worktree `agent-ae139e4fc1b184b36`, over F1's head `111d805`; F2 is `41de055` to `b86b147` (nine commits, head `b86b147`). Not merged, not pushed. Every number below is in `f2-tables.md`; raw material in `f2-logs/` (probes and their outputs, gate logs, `rig/<run>/` for the in-game runs), the rig in `f2-rig/`.

**Windows.** Off-line numbers are medians of repeated single events (no window). In-game runs: dedicated server, the scenario function, then 60 s warm-up + 60 s measured window, then a marginal phase after the window. During the task the owner narrowed in-game work to what is strictly needed: one before/after pair at 800 and 5,000 devices, the marginal-block measurement once (inside the two 5,000-device runs), no `transient100` rerun, no separate recertification run (section 5).

## 0. Summary

* **Root cause, three sentences.** Every placed, removed or edited device became one topology event that was applied inside the call that placed it, and applying one event recompiled the whole registry (`PhysicalFluidTopology.compile` over every device, 89 % of the event at 5,000 devices), read every island twice (materialising every certified one) and copied or rebuilt six world-sized structures (two ledger snapshots, the owners map, its inverse, the chunk index, the registration view). The compile itself was worse than linear, because assembling each island scanned every run of the registry, so one event cost 1.7, 5.2 and 19.3 ms at 500, 2,000 and 5,000 devices. A command placing N devices paid that over its own growing registry, quadratically in N: 58 to 60 s for WP5's 5,000.
* **Fix.** The compile assembles each island from its own runs (`d33b9b1`, linear); the ledger keeps live indexed collections and builds a snapshot only for a save (`b1a14ff`); the coordinator applies a batch of one tick's events as one change and indexes owned stock (`934040a`); the event path, moved out of the authority into `PhysicalRegistry` (`41de055`), queues an event with a touched set that stops at owned or queued devices, applies what is queued at the next tick's hook (or before a read that needs it, or every 1,024 events) in per-tick batches, and compiles only the connected components a batch touches, keeping everyone else's compiled form, ownership and chunk index (`924deaa`, `51f6935`).
* **Off-line** (same probe, same worlds): one event at 5,000 devices 19.3 ms before, 0.03 to 0.13 ms after, flat from 500 to 5,000; removal and edit the same; 100 blocks placed as one command 0.058 ms per block in one batch, each component compiled once. Tests: `placementCostIsFlatInWorldSize` 0.152 / 0.045 / 0.035 ms at 500 / 2,000 / 5,000 devices; the new GameTest places 5,000 devices in one call in 524 ms and one more pipe in 0.64 ms.
* **In game** (dedicated server, one run each, 60 s + 60 s): the 800-device function 1,468 ms to 145 ms (tick loop stalled 1,527 to 291 ms); the 5,000-device function 48,442 ms to 638 ms (stalled 48,481 to 739 ms; "Can't keep up! ... 968 ticks behind" gone); one more block in the 5,000-device world 28 to 29 ms of server thread to 0.6 to 1.6 ms, twenty tanks 477 to 3.4 ms; the 1,000 rest lines certify REST by online tick 600 in both builds and the 100 pumped fills end FULL at 0.37 against 0.40 cores.
* **Contracts kept:** event ticks, exactly-once application in causal order, a new identity and revision for every replaced island, fences, the ledger snapshot and its validation, the format-3 checkpoint (no format change), certified islands an event does not touch (not read, not materialised, payload reused), engine-owned presentation. Observable changes: events apply at the next tick's hook instead of inside the placing call, and islands a tick would have created and replaced again are not created (section 3.2).
* **Gates** (on `cfbb02d`): science 161/0, runtime 210/0 (202 + 8 new), network 30/9, 19/14, 37/3, regression exactly zero, GameTests 30 (29 + 1 new), P12/P31 byte-identical. The paced `transient100` rerun was dropped on the owner's instruction.

## 1. Root cause with numbers (T1)

### 1.1 Method

The authority's event path cannot run without a Minecraft server, so it was first moved, unchanged, into a server-free class, `PhysicalRegistry`, that reaches the world through a small host (the coordinator, players for filter recoveries, the presentation rekey) (`41de055`; all 29 GameTests pass on it, `f2-logs/gate-extract-gametest.log`). The probe `f2-logs/probes/PlacementProfileProbe-before.java` drives that class with a real `IslandCoordinator` that runs no solve, on worlds of WP5 rest lines (tank, three pipes, tank; 16 columns 6 blocks apart, rows 2 apart), one event per block applied before the next, as the rig's datapack places them. At 500, 2,000 and 5,000 devices it times single events (30 each after 10 of warm-up, the world restored after each), a position lookup after an event (a block-entity load binds through it) and a registry load (server start). An uncommitted instrumentation (`f2-logs/probes/instrumentation-before.patch`) timed every phase of the two methods. Output `f2-logs/probe-before-01.txt`; the compile alone in `compile-before-01.txt`.

### 1.2 One event is O(N), and the compile in it worse

One event cost 1.7 / 5.2 / 19.3 ms at 500 / 2,000 / 5,000 devices (placing a lone tank; every other event within 10 % of it: removing it, extending a line, removing that pipe, merging two lines, splitting them, a facing edit). Per event at 5,000 devices (`f2-tables.md` 1.2):

| term | ms | order |
|---|---|---|
| `PhysicalFluidTopology.compile` of every registered device | 17.26 | worse than O(N): 12.8 ms of the 16.5 ms compile is island assembly scanning every run for every island (O(islands x runs)); `TopologyCompiler` itself 3.7 ms |
| two ledger snapshots, each copying and validating every registration (queue, apply) | 0.98 | O(N) |
| island reads: every island's snapshot, twice (filter cakes, boundary stock), materialising every certified island; plus every registration | 0.58 | O(N) |
| owners map copied and filtered, members inverse, chunk index, replacement scan, active copy, selected-ids scan | 0.78 | O(N) |
| registration copy and position map of the proposed registry | 0.19 | O(N) |
| coordinator `topology`, whose constructed-stock check scans every island's reservoirs | 0.06 | O(N) |
| the edit itself, boundary initialisation, readiness and alignment, the submit walk, fences | 0.03 | O(1); the walk O(component) |

A command of N placements therefore paid the sum over its growing registry: 0.69 s for the first 500 devices, 4.6 s for the next 1,500 and 34.7 s for the 3,000 after that in the probe, 58 to 60 s in game (WP5). The in-game marginal cost WP5 derived (about 23 ms at 5,000 devices) is the probe's 19.3 ms plus Minecraft's own block placement and the materialisation of the resting certified islands that the two island reads forced at each event.

### 1.3 Removal, edit, chunk load and unload, server start

* **Removal and edit** take the same path (`FluidDeviceBlock.onRemove` and a facing change in `onPlace` submit one event each): 18.8 to 19.3 ms at 5,000 devices, the same terms.
* **Chunk load.** `loadedChunk` marks the devices of that chunk, O(devices in the chunk). Each loading block entity binds through `at(position)`, which after any event rebuilt the registration view and the position index once: 0.18 ms at 5,000 devices, O(N) once per change, later lookups 0.3 us. **Unload** only unmarks the device, O(1).
* **Server start** compiles the whole registry once and binds it to the saved islands: 2.2 / 5.7 / 21.7 ms at 500 / 2,000 / 5,000 devices, once per start.

## 2. The fix (T2)

### 2.1 Commits

| commit | change |
|---|---|
| `41de055` | The event path moves out of `FluidWorldAuthority` into `PhysicalRegistry` unchanged (host interface; all 29 GameTests pass). |
| `d33b9b1` | `PhysicalFluidTopology.compile` buckets runs once by the group that holds their first node, in run order: the same islands, pipes and views; linear (16.5 to 8.7 ms at 5,000 devices, 52 to 16.7 ms at 10,000). |
| `b1a14ff` | `WorldTopologyLedger` keeps the applied and queued registries, their position indexes, the queue and the accounting as live collections. Queue and apply validate an event against an overlay of the positions it changes, with the same rules and messages as before (revision, kind, position, retired and reserved identities, no re-initialised reservoir, no two devices at one position). A `Snapshot` is built only when asked for (a save), is validated as a restored one, and shares its immutable parts until the ledger changes, so a save still keeps the encoding of an unchanged ledger (`sameWorldBody`). `applyBatch` stages several queued events in queue order, each checked against the ones before it; each must meet no earlier event left out of the batch. |
| `934040a` | `IslandCoordinator.topology` takes the events of a batch (every affected island fenced for at least one of them, all at its committed tick; none carried into a replacement); the constructed-stock refusal reads an index of reservoir owners, checked by self-verification, instead of scanning every island. |
| `924deaa` | `PhysicalRegistry` queues with a bounded touched set and applies per-tick batches that compile only what they touch (2.2, 2.3); the authority applies lazily (2.4); opt-in counters `topologyBatches`, `topologyEvents`, `compiledDevices`. |
| `51f6935` | `diagnosticSnapshots()` and `presentationKey()` apply queued events first, as `view()` and `capture()` do; the presentation's own key lookup does not. |
| `525aa8d`, `cfbb02d` | Tests (section 4). |
| `b86b147` | Changelog entries under `[Unreleased]` (Changed: per-tick batches; Fixed: cost in the touched islands). |

### 2.2 Submit: a touched set that stops at owners

An event's touched set orders it against other queued events (the ledger lets an event pass an earlier one only when identities and positions are disjoint) and names the islands it fences. It used to be the whole connected component of the edit. It is now the edited devices, the devices beside an edited position, and every device reached from them through applied devices that no island owns and no queued event edits (a dead-end branch, a line with no tank). The walk stops at a device an island owns - that island is fenced whole - and at a device a queued event edits - that event now orders this one. Two events that reach one island through different devices are ordered by that island's fences: the island stops at the earliest fence, so a later event cannot align on it before an earlier one, and events of one tick on it apply together. A queued device behind an ownerless branch is reached because the branch devices are in the touched set (`anEventReachesAnIslandThroughAnOwnerlessBranch`, `aHeldEventKeepsItsTickAndOrdersTheEventsThatDependOnIt`). Walking a long ownerless line (pipes with no tank) still costs its length, which is the component the event touches.

### 2.3 Apply: per-tick batches over the touched components

`applyPending` builds a batch from the queue in order: the first applicable event fixes the batch's tick and dimension, and every later event of that tick and dimension joins if it meets no earlier event left out and its owners (the islands fenced for it, and the owners of what it touches) are aligned at its tick. A filter recovery is applied alone, as before, because its cake is read from its island when it applies. For one batch:

1. read the affected islands once (boundary stock and filter cakes; already aligned, so certified ones are already materialised to the tick);
2. play the batch's edits in queue order over the applied registry (boundary initialisation as before); the ledger books every construction and destruction in order, and the islands see the net change (a tank placed and broken in one tick is neither);
3. walk the post-batch connection graph from every touched device and every device of the affected islands: these connected components are the only part of the registry the batch can change;
4. compile exactly those devices, give every compiled island that holds a selected device a new identity from the identity sequence, and hand the coordinator one change for the batch;
5. in the commit, replace the diagnostics, pipe views, owners, members and chunk entries of exactly those devices, and bump the ownership version the module host rebinds on;
6. fence the replacement islands for queued events that touch them, as before.

A boundary or filter reached outside the fenced islands would mean that a touched set missed an island: that throws instead of creating a second owner. (The old path could not miss an island, since its touched set was the whole component; had it done so, the coordinator would have refused a reservoir and a generator or void would have got two islands.)

### 2.4 When events apply

A queued event applies at the next tick's hook, where queued events were already tried every tick; or earlier when something reads what it changes (`view`, `capture`, `materialiseAll`, `diagnosticSnapshots`, `presentationKey`, and a filter recovery, which still applies at once so the item arrives with the action); or when 1,024 have queued since the last application, which keeps a command's queue far inside the ledger's 4,096-event bound. A device's registration (`registrations()`, `at()`) is visible at once, as before, because that is the queued view. Nothing is pushed to a client by any of this: a publication marks loaded devices for their buckets, as before.

## 3. Contracts (T2)

### 3.1 Event ticks, exactly once, causal order

Every event keeps the tick it was queued at; a batch holds the events of one tick and its replacement islands start committed at that tick; a waiting event keeps its tick through later ticks and orders what depends on it (`aHeldEventKeepsItsTickAndOrdersTheEventsThatDependOnIt`; the GameTest `anUnresolvedIslandHorizonDoesNotBlockUnrelatedPhysicalEdits` passes unchanged). An event leaves the ledger only in the commit of the batch that applied it, and a second application finds nothing (`aTicksEventsApplyOnceInOrderAsOneBatch`: 104 events of one tick - twenty lines, a tank placed and broken, a pipe placed and turned - apply as one batch and leave the registry, islands, diagnostics, pipe views and the construction and destruction totals bit for bit as one-by-one application does). The fence rules are WP1/WP2's: fences are installed when an event is queued (on the owners of its touched set) and when a replacement island takes over a queued event's device; alignment requires every affected island at the event tick with no attempt running.

### 3.2 Identity and revision

A replaced island gets a new identity from the identity sequence and revision max(old) + 1, as before (`mergesAndSplitsProduceTheRightIslandsAndRevisions`). An island no event touches keeps its identity, so the presentation's client cache (keyed by island) and a certificate's signature (a digest of the island's graph) are untouched. Within one tick, one-by-one application created intermediate islands that a batch never creates: in the 104-event test the first line's island was replaced three times one by one and not at all in the batch, so identities and revisions of islands built within a tick differ from before. Nothing reads intermediate identities; menus rekey at the commit.

### 3.3 Ledger snapshot and format 3

The `Snapshot` record, its validation and the saved topology body are unchanged; a snapshot is built only for a save. Two consequences, neither a format change: saved touched sets are smaller (2.2), and junction reservoirs (internal nodes at pump outlets and filters) are numbered per compile of the touched components instead of per compile of the world. Junction identities are internal to an island (the checkpoint requires unique identities per dimension only for finite reservoirs), and the old path renumbered them at every event anyway. F3 is free to restructure storage.

### 3.4 Certified islands and payloads

An island no event touches is neither read nor materialised nor replaced; it keeps its certificate and its payload generation, so the next save copies its payload (`certifiedIslandsUntouchedByAnEventStayCertified`: eight resting lines certify, a pipe beside one replaces that one, the other seven keep certificate, payload generation and clock, one materialisation in all). The old path materialised every certified island at every event (without changing their payloads).

### 3.5 Presentation, modules, tick loop

Menus follow their devices to the replacement islands' buckets at the commit, as before; a device bound before its event applies is marked under its own key, then under its island's when the island's publication marks it. The module host rebinds when the ownership version changes (at every application, as the replaced map did before). Event application stays where it was, in the tick hook's handling of queued events - a dependency change, not process-state calculation - and the hook does nothing when nothing is queued or due.

## 4. Tests (T3)

| test | what it holds |
|---|---|
| `PhysicalRegistryTest.anEventTouchingOneIslandCompilesAndReplacesOnlyThatIsland` | 50 lines; a pipe beside line 0: touched set of 2, one batch, 6 devices compiled (counter `compiledDevices`), a new identity and revision + 1 for line 0, every other island still there with its payload generation |
| `...mergesAndSplitsProduceTheRightIslandsAndRevisions` | a bridge merges two lines (11 devices, revision max + 1, no stock moved), breaking it splits them into two new islands (revision max + 2), the host learns the removed device |
| `...aTicksEventsApplyOnceInOrderAsOneBatch` | 104 events of one tick: nothing applies before the batch, one batch of 104, 101 devices compiled, nothing on a second application; registry, islands, diagnostics, views and accounting equal to one-by-one application, bit for bit |
| `...removalAndEditFollowTheSamePath` | removing a middle pipe compiles the 4 remaining devices and leaves two tanks with dead-end pipes; a facing edit compiles 5 and replaces the island with revision + 1 |
| `...anEventReachesAnIslandThroughAnOwnerlessBranch` | a tank at the end of a 4-pipe dead-end branch fences and joins the line (10 devices) |
| `...aHeldEventKeepsItsTickAndOrdersTheEventsThatDependOnIt` | an event on a held island waits at its tick; an unrelated tank applies at once; a pipe beside the waiting one waits behind it; both apply after the hold |
| `...certifiedIslandsUntouchedByAnEventStayCertified` | synchronous worker: 8 resting lines certify; a pipe beside one: 1 materialisation, 7 islands with the same certificate, payload generation and clock |
| `...placementCostIsFlatInWorldSize` | the synchronous-rig scaling test: 60 placements (and removals) of a pipe beside a line in worlds of 500, 2,000 and 5,000 devices: 6 devices compiled at every size, median 0.152 / 0.045 / 0.035 ms (asserted: 5,000 within 4 x 500 + 0.05 ms, and under 1 ms) |
| `FluidPlacementGameTests.fiveThousandDevicesPlacedAtOnceApplyInBatchesAndOneMoreCostsLittle` | GameTest: 1,000 rest lines placed through the blocks in one call become 1,000 islands; 523.8 ms for the 5,000 devices; one more pipe 0.637 ms median of 11 (asserted under 10 ms); cleaned up through 5,000 removal events |

The existing ledger tests (`WorldTopologyLedgerTest`), checkpoint tests (`FluidCheckpointFormatTest`, including the unchanged-ledger save that keeps its encoding) and every GameTest pass unchanged.

## 5. In game: before and after (T4)

Dedicated server, the WP5 template world and placement defaults, one run each (the owner's minimal set), before = `111d805`, after = `cfbb02d`; `f2-tables.md` section 4, runs under `f2-logs/rig/`.

**Window: 60 s warm-up after the devices are registered + 60 s measured; the marginal phase after the window.**

| | 800 devices (`fill100`) before | after | 5,000 devices (`rest1000`) before | after |
|---|---|---|---|---|
| the placing function (RCON round trip) | 1,468 ms | **145 ms** | 48,442 ms | **638 ms** |
| first tick after it, whole / engine | 10.3 / 8.3 ms | 116.7 / 114.4 ms | 15.1 / 10.8 ms | 84.4 / 78.6 ms |
| tick loop stalled (last tick before to first tick after) | 1,527 ms | **291 ms** | 48,481 ms | **739 ms** |
| "Can't keep up" | none | none | `Running 48416ms or 968 ticks behind` | none |
| end of the window | FULL 100, 0.40 cores | FULL 100, 0.37 cores | REST 1,000 (by online tick 600), 0.079 cores | REST 1,000 (by online tick 600), 0.092 cores |

WP5 measured the same two functions at 1,485 ms and 58.0 s on the old path (one run each; the spread between runs is not measured). After the fix the 5,000-device function applies its events in five batches (four inside the command at 1,024 events, one at the next tick) and 1,000 lines certify REST exactly as before; the pumped fills fill as in F1.

**One more block in the 5,000-device world** (median of 10, server-thread time the block adds: round trip plus the engine time of the ticks in the next 450 ms, less a no-op's):

| block | before | after |
|---|---|---|
| a lone tank | 29.3 ms | **1.47 ms** |
| breaking it | 29.0 ms | **0.63 ms** |
| a pipe beside a line (its island replaced) | 28.5 ms | **1.62 ms** |
| breaking it | 28.0 ms | **1.23 ms** |
| a facing edit / back | 29.3 / 28.2 ms | **0.78 / 0.95 ms** |
| twenty tanks in one function / breaking them | 476.8 / 413.0 ms | **3.41 / 0.85 ms** |

Before, the cost landed in the command for a new tank (it touches no island) and in one later 25 to 28 ms tick for the others: every island a previous block had replaced was solving its first one-tick slices, so the next event on it waited for its attempt and applied at a tick hook, whole-registry compile included. After, every event applies at a tick hook and the largest tick in the 450 ms is 0.5 to 1.9 ms; the after costs include the first solves of the island the block creates or replaces, which is why they exceed the probe's 0.03 to 0.13 ms. The WP3 figure (one edit in a 10,803-device world, 205 ms JIT-cold, under 10 ms warm on the old path) was not rerun; the scaling test's flatness (section 4) is what stands for it.

Two things differ in the windows, neither a cost: the start-up round of 100 pumped fills held 10 islands once after the fix and 2 before (the cold-JIT, 2 s round budget variance F1 documented; both FULL 100 by online tick 800, as in F1's runs), and `fill100`'s engine p50 is 0.069 against 0.013 ms at the same mean (0.150 against 0.154 ms), because the batch gave the 100 lines consecutive island identities, one per presentation bucket, so a small flush falls on every tick instead of several larger ones on fewer ticks.

## 6. Gates (T5)

On `cfbb02d`, the last code commit (`b86b147` adds only the changelog), one Gradle invocation at a time, `JAVA_OPTS=-Xshare:off`, no game running; logs `f2-logs/gate-final-*.log`, sequence `f2-logs/gates.log`.

| gate | result |
|---|---|
| `fluidScienceTest --rerun` | 161 / 0 |
| `fluidRuntimeTest --rerun` | 210 / 0 (202 + 8 new) |
| `fluidNetworkBenchmark --rerun` | 30/9, 19/14, 37/3 |
| `fluidSolverRegression -PfluidRegressionMode=exact` | exactly zero (chain-100: 0.000e+00 in state, temperature, phase fraction, flow) |
| `runFluidGameTestServer` | 30 passed (29 + 1 new), on `cfbb02d` (`gate-dev4-gametest.log`) |
| P12 / P31 fingerprints | byte-identical to the `fluid-scheduler/wp2-logs` copies (`56332b64ea3f3bde...`, `4dcb80a40266...`) |
| paced `transient100`, certificates on, 60 s + 60 s | not run (owner's instruction; section 7) |

## 7. Deviations from the brief

1. **Owner instruction during the task: minimal in-game runs.** In game only one run each of `fill100` (800 devices) and `rest1000` (5,000) per build, no repeats; the marginal-block measurement once, inside each `rest1000` run after its window; the 500- and 2,000-device sizes only off-line (probe and scaling test). Not run: the paced `transient100` with certificates on (its islands are registered from a checkpoint and it has no topology event in its window, so this change cannot reach it; P12 and P31 are byte-identical), a separate `rest1000` recertification run (the `rest1000` run itself ends with REST 1,000), and the WP3 `viewers` figure (its edits take the path measured here; not rerun, so its "one edit in a 10,803-device world" is stated from the off-line scaling, not measured).
2. **The event path was moved out of the authority first** (`41de055`), so that the old algorithm could be profiled and the new one tested without a server. The move is its own commit, GameTest-verified.
3. **Events no longer apply inside the placing call.** The brief asked for batching per tick; doing that means an event applies at the next tick's hook. Reads that must see the islands (`view`, `capture`, `materialiseAll`, `diagnosticSnapshots`, `presentationKey`, a filter recovery) apply what is queued first, so every existing test and GameTest passes unchanged. One GameTest (`FluidPropertyReloadGameTests`) reads `diagnosticSnapshots()` in the tick it places its tank and crashed the GameTest server until that read, too, applied queued events (`51f6935`).
4. **Touched sets are smaller** (2.2). The brief did not ask for it; without it a command building one large island would have walked the whole growing island at every block (quadratic again, in the walk rather than the compile).
5. **The constructed-stock index in the coordinator** was not in the brief; the check it replaces scanned every island's reservoirs per added tank (O(N) per event).

## 8. Open items

* **A tank's charge is initialised twice** (validated when queued, built when applied: one flash each, about 0.06 ms): most of a lone tank's 0.13 ms and of the 0.6 ms a tank costs in game. Caching the queued initialisation for the application would halve it.
* **Server start still compiles the whole registry once** (21.7 ms at 5,000 devices, both builds): linear and once per start, left as is.
* **A long ownerless run** (a pipe line with no tank) is walked at every event that reaches it: proportional to that run, which is the component the event touches; left as is.
* **Start-up holds of pumped fills placed at once** remain a wall-time outcome (F1 section 3): the first round's one-tick slices on a cold JIT held 2 islands on 111d805 and 10 on the branch in these single runs, all retried within 10 s; a batch creates the 100 islands in one tick, as the old path did within one command.
* **`WorldTopologyLedger.MAXIMUM_EVENTS` (4,096)** still bounds a blocked queue; lazy application keeps a command's queue at 1,024 at most, but events waiting on a held island still count against the bound (pre-existing).
* **Documents** stay in the worktree's untracked `documentation/fluid-followups/` (`FLUID_PLACEMENT_REVIEW.md`, `f2-tables.md`, `f2-logs/`, `f2-rig/`); nothing was copied to the main checkout, nothing merged or pushed.
