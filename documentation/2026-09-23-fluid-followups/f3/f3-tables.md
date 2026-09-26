# F3 tables: checkpoint storage

Date: 2026-09-24. Branch `claude/fluid-followups`, before = `b86b147` (F2 head, checkpoint format 3), after = `3f30155` (code; `e837ada` adds the changelog). Batch `2026-09-23-fluid-followups`. Review: `FLUID_CHECKPOINT_STORAGE_REVIEW.md`. Raw outputs in `f3-logs/`; probe and harness sources (not committed) in `f3-logs/probes/`.

**Windows.** Off-line numbers are single measurements of one save or load each (no window). The one paced run used a **60 s warm-up and a 60 s window** (`rest100-f3-on-r02`, 2026-09-24, window from 11:08:04 local, saves at online ticks 2436 and 2536), as WP4's `rest100-wp4-on-r01` did (2026-09-23, window from 19:03:26 local, saves at 2454 and 2554). Machine state before every timed run: no `Endfield.exe`, 27.7 to 29.0 GB free (`f3-logs/gates.log`).

## 1. Layout decision: cost of one durable atomic file write (`f3-logs/probes/AtomicWriteProbe.java`)

Write a temporary file, `FileChannel.force(true)`, `ATOMIC_MOVE` over the target (what NeoForge's `IOUtilities.atomicWrite` does for saved data), on the worktree's disk, JDK 21. Two repetitions each.

| files x size | total ms | ms per file | read all back, ms |
|---|---|---|---|
| 100 x 5 KB | 269.9 / 241.1 | 2.70 / 2.41 | 9.6 |
| 1,000 x 5 KB | 2,310.2 / 2,446.4 | 2.31 / 2.45 | 100.5 |
| 100 x 50 KB | 229.8 / 230.5 | 2.30 / 2.31 | 9.8 |
| 1 x 5 MB | 6.1 / 5.6 | 6.1 / 5.6 | 1.6 |
| 1 x 50 MB | 38.9 / 36.4 | 38.9 / 36.4 | 12.4 |

A durable file costs about 2.3 to 2.7 ms whatever its size up to 50 KB: one file per island would cost 2.4 s of IO thread per 1,000 changed islands and about 24 s per 10,000; one pack per save costs 6 to 37 ms for 5 to 50 MB.

## 2. What one island's checkpoint holds (T1)

Probes `F3PayloadBreakdownProbe-before.java` (format 3 at `b86b147`, output `f3-payload-before.txt`) and `F3PayloadBreakdownAfter.java` (format 4, `f3-payload-after.txt`): the paced benchmark's first six rest100 ladders (10 to 28 reservoirs, solved on a synchronous rig until all six certify STEADY, tick 400), its first six stress100 ladders (awake, through-flow, same solves), WP5's in-game rest line (nitrogen tank, three pipes, tank; REST) and a lone tank (REST). Same islands, same solves, encoded by each format. Bytes are the payload as written (JSON, UTF-8, compact) before and the binary island unit after; "per-island envelope" is the format-3 NBT island compound without its payload against the format-4 index row in the core record's columns.

#### rest100 CLOSED (mean per island)

| section | format 3 JSON, bytes | format 4 binary, bytes |
|---|---|---|
| island identity (dimension, package, compressibility; unit header) | 108 | 40 |
| thermodynamic revision and energy reference | 766 | 0 |
| graph topology (node ids, elevations, kinds, volumes; pipe ends, sections, controls) | 5,945 | 1,522 |
| graph state (inventories, phases, pipe masks and filters) | 25,055 | 9,041 |
| approximation anchor (revision, graph, modes) | 31,813 | 28 |
| last interval: scalars, flows, heads, modes, boundaries, reasons | 1,046 | 252 |
| last interval: pipe transfers | 48,237 | 14,276 |
| status, allowance, fences | 173 | 75 |
| certificate: interval start graph | 30,989 | 9,042 |
| certificate: signature | 0 | 35 |
| **payload / unit** | **144,229** | **34,311** |
| gzip of the payload / unit | 45,096 | 27,453 |
| per-island envelope: NBT island compound / index row | 1,046 | 133 |
| check: sum of the rows | 144,132 | 34,311 |

#### stress100 THROUGH (mean per island)

| section | format 3 JSON, bytes | format 4 binary, bytes |
|---|---|---|
| island identity (dimension, package, compressibility; unit header) | 108 | 40 |
| thermodynamic revision and energy reference | 766 | 0 |
| graph topology (node ids, elevations, kinds, volumes; pipe ends, sections, controls) | 6,557 | 1,683 |
| graph state (inventories, phases, pipe masks and filters) | 27,510 | 9,842 |
| approximation anchor (revision, graph, modes) | 34,903 | 30 |
| last interval: scalars, flows, heads, modes, boundaries, reasons | 5,580 | 1,795 |
| last interval: pipe transfers | 38,668 | 9,540 |
| status, allowance, fences | 107 | 9 |
| certificate: interval start graph | 21 | 1 |
| certificate: signature | 0 | 0 |
| **payload / unit** | **114,280** | **22,938** |
| gzip of the payload / unit | 32,985 | 18,728 |
| per-island envelope: NBT island compound / index row | 170 | 133 |
| check: sum of the rows | 114,220 | 22,940 |

#### in-game rest line (mean per island)

| section | format 3 JSON, bytes | format 4 binary, bytes |
|---|---|---|
| island identity (dimension, package, compressibility; unit header) | 108 | 40 |
| thermodynamic revision and energy reference | 766 | 0 |
| graph topology (node ids, elevations, kinds, volumes; pipe ends, sections, controls) | 322 | 96 |
| graph state (inventories, phases, pipe masks and filters) | 1,034 | 156 |
| approximation anchor (revision, graph, modes) | 1,934 | 4 |
| last interval: scalars, flows, heads, modes, boundaries, reasons | 173 | 28 |
| last interval: pipe transfers | 751 | 12 |
| status, allowance, fences | 132 | 34 |
| certificate: interval start graph | 1,356 | 1 |
| certificate: signature | 0 | 35 |
| **payload / unit** | **6,675** | **406** |
| gzip of the payload / unit | 1,244 | 226 |
| per-island envelope: NBT island compound / index row | 1,044 | 133 |
| check: sum of the rows | 6,576 | 406 |

#### lone tank (mean per island)

| section | format 3 JSON, bytes | format 4 binary, bytes |
|---|---|---|
| island identity (dimension, package, compressibility; unit header) | 108 | 40 |
| thermodynamic revision and energy reference | 766 | 0 |
| graph topology (node ids, elevations, kinds, volumes; pipe ends, sections, controls) | 61 | 27 |
| graph state (inventories, phases, pipe masks and filters) | 499 | 77 |
| approximation anchor (revision, graph, modes) | 1,129 | 3 |
| last interval: scalars, flows, heads, modes, boundaries, reasons | 158 | 25 |
| last interval: pipe transfers | 11 | 1 |
| status, allowance, fences | 132 | 34 |
| certificate: interval start graph | 560 | 1 |
| certificate: signature | 0 | 35 |
| **payload / unit** | **3,526** | **243** |
| gzip of the payload / unit | 1,015 | 177 |
| per-island envelope: NBT island compound / index row | 1,044 | 133 |
| check: sum of the rows | 3,424 | 243 |

Facts behind the slimming (same 14 islands, `f3-payload-before.txt`):

| fact | islands |
|---|---|
| the anchor's graph equals the island's graph (JSON text identical) | 14 / 14 |
| the anchor's graph has the island graph's topology (ids, elevations, kinds, volumes, pipe ends, sections, controls) | 14 / 14 |
| the certified interval's start graph has the base graph's topology | 8 / 8 certified |
| the certified interval's start graph equals the base graph | 2 / 8 certified (the two REST ones; format 4 stores it as one byte) |

World-level parts of the format-4 core record for these 14 islands (`f3-payload-after.txt`): 9,318 bytes of NBT in all, 3,131 with no island; the string table 4 entries, 1,683 bytes (dimension, package, the full property revision the anchors and signatures share, the certificate policy); the package table 2,644 bytes (package, compressibility, thermodynamic revision, energy revision and its 21 component names, once per world); 132.9 bytes per island index row.

### 2.1 The topology unit

| topology of | format 3 JSON body | format 4 binary unit | per device |
|---|---|---|---|
| 100 rest100 clones, 10,180 devices (harness) | 8.70 MB | 0.57 MB | 855 B to 56 B |
| 100 stress100 clones, 11,380 devices (harness) | 9.72 MB | 0.64 MB | 854 B to 56 B |
| 1,000 rest100 clones, 101,800 devices (harness) | 87.2 MB (over the 64 M character bound) | 5.70 MB | 56 B |
| paced rest100 fixture, 10,180 devices (WP4 `rest100-wp4-on-r01` / F3 `rest100-f3-on-r02`) | 8,575,146 B | 546,292 B | 842 B to 54 B |

## 3. Save time and bytes off-line (T4)

Harness `F3SaveTimingBefore.java` (at `b86b147`, output `f3-save-before.txt`) and `F3SaveTimingAfter.java` (at `0d44cb0`, the final storage code, output `f3-save-after.txt`); fluidRuntimeTest JVM with a 10 GiB heap and the scheduler's self-verification off (`f3-harness.init.gradle`). Islands: clones of the paced benchmark's first 20 rest100 ladders (certified STEADY; 39 KB binary, 163 KB JSON each on average) or of its first 20 stress100 ladders (awake), under new island and node identities with their certificates signed anew, registered in a coordinator; the world topology of their devices. **cold**: every payload or unit encoded (cache cleared, store forgotten); **warm**: the next save in the same tick; **next cadence**: 120 paced ticks later (certified islands only materialise; every awake island has solved one interval). Server thread: before, capture + encode + the deep copy NeoForge's `SavedData.save(File)` makes of the tag; after, capture + preparation (changed units encoded, core record built). IO thread: before, gzip and atomic write of the whole checkpoint (`IOUtilities.writeNbtCompressed`); after, the new pack(s) and the core record written atomically and flushed, unreferenced packs deleted. Load: reading the files back with every validation into a fresh store. Temporary folders on the worktree's disk; 2026-09-24 11:00 to 11:03, no game running.

### 3.1 Before: format 3 (`b86b147`)

| case | save | server thread ms (capture / encode / tag copy) | payloads encoded / copied | topology encoded | payload + topology MB | IO gzip+write ms | file MB | load ms |
|---|---|---|---|---|---|---|---|---|
| 100 certified | cold | 204.9 (3.1 / 190.0 / 11.9) | 100 / 0 | yes | 16.27 + 8.70 | 459.2 | 5.30 | 806.1 |
| 100 certified | warm | 9.6 (1.7 / 2.6 / 5.3) | 0 / 100 | no | 16.27 + 8.70 | 453.9 | 5.30 | 757.9 |
| 100 certified | next cadence | 20.1 (9.0 / 3.3 / 7.9) | 0 / 100 | no | 16.27 + 8.70 | 480.1 | 5.30 | 802.0 |
| 100 awake | cold | 155.3 (0.9 / 152.4 / 2.0) | 100 / 0 | yes | 12.64 + 9.72 | 328.4 | 3.92 | 786.8 |
| 100 awake | warm | 5.3 (1.3 / 2.1 / 1.9) | 0 / 100 | no | 12.64 + 9.72 | 341.8 | 3.92 | 691.8 |
| 100 awake | next cadence | 112.0 (1.1 / 109.1 / 1.9) | 100 / 0 | no | 14.59 + 9.72 | 386.6 | 4.66 | 743.8 |
| 1,000 certified | every save | refused: "Fluid checkpoint exceeds the configured format bound" (64 MiB) | | | | | | |
| 1,000 awake | every save | refused: "Fluid checkpoint exceeds the configured format bound" (64 MiB) | | | | | | |

### 3.2 After: format 4 (`0d44cb0`)

| case | save | server thread ms (capture / prepare) | units encoded / reused / moved | topology encoded | island units + topology MB | IO write ms | new pack MB | core record KB (NBT) | files on disk MB | load ms |
|---|---|---|---|---|---|---|---|---|---|---|
| 100 certified | cold | 62.8 (4.1 / 58.7) | 100 / 0 / 0 | yes | 3.92 + 0.57 | 18.6 | 4.49 | 21.7 | 4.50 | 48.5 |
| 100 certified | warm | 3.4 (1.8 / 1.6) | 0 / 100 / 0 | no | 3.92 + 0.57 | 4.6 | 0 | 21.7 | 4.50 | 24.8 |
| 100 certified | next cadence | 10.3 (9.4 / 0.8) | 0 / 100 / 0 | no | 3.92 + 0.57 | 5.6 | 0 | 21.7 | 4.50 | 21.7 |
| 100 awake | cold | 17.5 (1.4 / 16.1) | 100 / 0 / 0 | yes | 2.53 + 0.64 | 9.6 | 3.17 | 21.4 | 3.17 | 22.3 |
| 100 awake | warm | 1.9 (1.2 / 0.7) | 0 / 100 / 0 | no | 2.53 + 0.64 | 4.1 | 0 | 21.4 | 3.17 | 22.8 |
| 100 awake | next cadence | 8.0 (1.1 / 6.9) | 100 / 0 / 1 | no | 3.18 + 0.64 | 11.8 | 3.81 | 21.4 | 6.98 | 25.0 |
| 1,000 certified | cold | 145.4 (12.7 / 132.6) | 1,000 / 0 / 0 | yes | 39.21 + 5.70 | 48.7 | 44.91 | 145.0 | 44.95 | 230.4 |
| 1,000 certified | warm | 14.5 (11.8 / 2.7) | 0 / 1,000 / 0 | no | 39.21 + 5.70 | 6.4 | 0 | 145.0 | 44.95 | 240.5 |
| 1,000 certified | next cadence | 60.2 (57.5 / 2.7) | 0 / 1,000 / 0 | no | 39.21 + 5.70 | 9.7 | 0 | 145.0 | 44.95 | 228.4 |
| 1,000 awake | cold | 112.8 (10.6 / 102.2) | 1,000 / 0 / 0 | yes | 25.30 + 6.37 | 34.8 | 31.67 | 144.7 | 31.71 | 170.7 |
| 1,000 awake | warm | 12.9 (11.3 / 1.6) | 0 / 1,000 / 0 | no | 25.30 + 6.37 | 5.5 | 0 | 144.7 | 31.71 | 156.8 |
| 1,000 awake | next cadence | 78.2 (11.0 / 67.2) | 1,000 / 0 / 1 | no | 31.78 + 6.37 | 41.1 | 38.15 | 144.7 | 69.86 | 159.7 |

"moved 1" in the awake next-cadence rows is the topology unit: every island unit of pack 1 was replaced, so pack 1 fell below half live and its one live unit moved to the new pack; pack 1 stays on disk one save longer (the files column) and is deleted at the next save. The awake next-cadence island bytes grow because a solved interval carries pipe transfers that the clones' first interval did not.

### 3.3 Before against after, 100 islands

| case | save | server thread ms | IO thread ms | on disk MB | load ms |
|---|---|---|---|---|---|
| certified | cold | 204.9 to 62.8 | 459.2 to 18.6 | 5.30 to 4.50 | 806.1 to 48.5 |
| certified | warm | 9.6 to 3.4 | 453.9 to 4.6 | 5.30 to 4.50 | 757.9 to 24.8 |
| certified | next cadence | 20.1 to 10.3 | 480.1 to 5.6 | 5.30 to 4.50 | 802.0 to 21.7 |
| awake | cold | 155.3 to 17.5 | 328.4 to 9.6 | 3.92 to 3.17 | 786.8 to 22.3 |
| awake | warm | 5.3 to 1.9 | 341.8 to 4.1 | 3.92 to 3.17 | 691.8 to 22.8 |
| awake | next cadence | 112.0 to 8.0 | 386.6 to 11.8 | 4.66 to 6.98 (one pack kept one save longer) | 743.8 to 25.0 |

### 3.4 Earlier harness runs (not used above)

`t4-after` (first run, JSON topology still in its unit), `t4-after-r2` (binary topology, a 105-tick cadence at which only 384 of the 1,000 awake islands had solved: the rig dispatches at most 64 a tick) and `t4-after-r3` (out of heap at 4 GiB once all 1,000 awake islands had solved and kept their solver workspaces) are in `f3-logs/probe-t4-after*.log`. The before run and `t4-after-r4` above used the same fixture, cadence and heap, one after the other.

## 4. The paced run (T4): `rest100-f3-on-r02` against WP4's `rest100-wp4-on-r01`

`fluidServerBenchmark -PfluidBenchmarkProfile=rest100 -PfluidBenchmarkWorkers=0 -PfluidStressWarmupSeconds=60 -PfluidStressMeasurementSeconds=60 -PfluidStressProfile=true -PfluidRestDetection=true -PfluidBenchmarkMemory=true`, 12 automatic workers, **60 s warm-up + 60 s window** (F3: 2026-09-24, run started 11:06:50, window 11:08:04 to 11:09:04 local, measured 60.01 s; WP4: 2026-09-23, window from 19:03:26 local). Audit PASS, integrity passed, 0 held intervals in the window. F3's saves are committed on the server thread so the write is timed (an autosave writes on the IO worker); WP4 timed capture and encode only and did not time the gzip and write of its 24 MB tag. Reports in `f3-logs/reports/rest100-f3-on-r02/`, console log `f3-logs/bench-rest100-f3-on-r02.log`. Run `rest100-f3-on-r01` crashed in the benchmark's report code before any save (no solve in the window; fixed in `3f30155`; crash report in `f3-logs/reports/rest100-f3-on-r01/`).

| save | online tick (WP4 / F3) | total ms WP4 / F3 | capture ms | encode / prepare ms | F3 write ms | encoded / reused | bytes WP4 (tag) / F3 (written: pack + core record) | on disk WP4 (gzip) / F3 (core + packs) |
|---|---|---|---|---|---|---|---|---|
| cold | 2454 / 2436 | 339.8 / 76.4 | 3.3 / 1.0 | 336.5 / 75.4 | 27.3 | 100 / 0 both | 24,089,981 / 4,388,197 | 4,870,996 / 4,372,688 |
| warm | 2454 / 2436 | 4.8 / 3.9 | 2.1 / 1.4 | 2.7 / 2.5 | 3.7 | 0 / 100 both | 24,089,981 / 21,728 | 4,870,996 / 4,372,688 |
| next cadence | 2554 / 2536 | 7.6 / 10.6 | 5.1 / 9.5 | 2.5 / 1.1 | 13.2 | 0 / 100 both | 24,089,981 / 21,728 | 4,871,016 / 4,372,683 |

| other measure (window) | WP4 `rest100-wp4-on-r01` (eps_s 1e-9) | F3 `rest100-f3-on-r02` (eps_s 1e-7, F1 default) |
|---|---|---|
| final kinds | 100 STEADY | 100 STEADY |
| full solves in the window | 20 | 0 |
| replayed intervals | 200 | 200 (10,565 s) |
| engine ms per tick p50 / p95 | 0.0123 / - | 0.012 / 0.0154 |
| whole tick ms p50 / p95 / max | - | 0.265 / 0.500 / 11.6 |
| island units / topology bytes | 15,514,678 / 8,575,146 (JSON) | 3,820,161 / 546,292 (binary) |

## 5. Unit-test timings (gate run, scheduler self-verification on)

| test | measure |
|---|---|
| `FluidCheckpointStoreTest.tenThousandIslandsSaveAndLoadThroughTheIndex` (10,000 certified lone tanks) | cold save: capture 7.6 ms, prepare 48.4 ms, write 54.3 ms (2,430,000 unit bytes, pack 2,430,479 bytes, core record 1,378,028 bytes NBT, 401,632 bytes on disk); warm save: 7.3 / 40.3 / 24.9 ms, nothing encoded (the prepare time is the self-verification re-encoding every reused unit); load 63.7 ms; registration 26.1 ms; first save after the load: 3.4 / 19.2 / 24.4 ms, nothing encoded |
| `FluidCheckpointFormatTest.aThousandCertifiedIslandsLoadWithoutASolveBurst` (1,000 lone tanks) | encode 12.2 ms, decode 13.7 ms, register 5.8 ms, 243,000 unit bytes, core record 144,402 bytes (WP4 at format 3: 53.2 / 167.4 / 5.7 ms, 3,529,000 payload bytes) |
| `FluidCheckpointStoreTest.unitsOfRemovedOrReplacedIslandsAreDroppedAndTheirPacksCompactedAndDeleted` | 40 saves each changing one of 20 islands: at most 16 packs referenced, at most 17 pack files on disk (always exactly the packs of the core record and of the one before it) |

## 6. Gates (T5, on `3f30155`; `f3-logs/gates.log`, `f3-logs/gate-final-*.log`, test XML in `f3-logs/final-test-results/`)

| gate | result |
|---|---|
| `fluidScienceTest --rerun` | 161 tests, 0 failures |
| `fluidRuntimeTest --rerun` | 220 tests, 0 failures (210 + 10 new: `FluidCheckpointStoreTest` 9, `FluidCheckpointCodecTest.aTopologyRoundTripsEveryFieldExactlyAndRefusesAMalformedUnit` 1; `FluidCheckpointFormatTest`'s 9 and `FluidCheckpointCodecTest`'s other 5 rewritten for format 4) |
| `fluidNetworkBenchmark --rerun` | 30/9, 19/14, 37/3 accepted/rejected substeps |
| `fluidSolverRegression -PfluidRegressionMode=exact --rerun` | chain-100 max deviation 0.000e+00 in state/moles, temperature, phase fraction, flow |
| `runFluidGameTestServer -PfluidGameTestRunId=f3-final` | All 30 required tests passed (the checkpoint and persistence GameTests extended, none added) |
| P12 / P31 fingerprints | byte-identical to the `fluid-scheduler/wp2-logs` copies (SHA-256 `56332b64ea3f3bde...`, `4dcb80a40266...`) |
