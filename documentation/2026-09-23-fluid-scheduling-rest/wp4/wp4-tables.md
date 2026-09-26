# Fluid scheduler WP4 tables

Date: 2026-09-23. Branch `claude/fluid-scheduler`, code at `ce25b78` (changelog `3b6f206`). The benchmark runs were built from `f6b5dd8` (artifact SHA-256 `faed3ead9014...`, the same for all three); the only later code commit, `ce25b78`, adds a log line at world load and does not touch the measured path. Reports and audits are in `wp4-logs/reports/`, logs in `wp4-logs/bench-*.log`.

**Benchmark window for every run below: 60 s warm-up, 60 s measured** (`-PfluidStressWarmupSeconds=60 -PfluidStressMeasurementSeconds=60`), 12 workers (`-PfluidBenchmarkWorkers=0`), `-PfluidStressProfile=true`, rest100 with `-PfluidBenchmarkMemory=true`. The WP2 and WP3 figures quoted for comparison used a 120 s window; they are marked where they appear.

## 1. Save time (plan section 6, report addition)

Each save is the whole fixture through the world's saved data, as an autosave makes it, timed on the server thread: capture (which materialises certified islands) plus encoding. `cold`: payload cache cleared first, at the end of the window. `warm`: the next save, same tick. `next cadence`: one more save 100 paced ticks later, after awake islands solved again and certified ones were only materialised. The topology ledger (about 10,000 fixture devices) is encoded again only when it changed. gzip is the compressed NBT size a file write produces (not timed).

| run | save (online tick) | total ms | capture ms | encode ms | payloads encoded / copied | topology encoded | payload MB | topology MB | total MB | gzip MB | final kinds |
|---|---|---|---|---|---|---|---|---|---|---|---|
| rest100-wp4-off-r01 | cold (2435) | 257.0 | 3.5 | 253.5 | 100 / 0 | yes | 11.35 | 8.18 | 19.53 | 3.53 | 100 AWAKE |
| rest100-wp4-off-r01 | warm (2435) | 4.1 | 1.9 | 2.2 | 0 / 100 | no | 11.35 | 8.18 | 19.53 | 3.53 | 100 AWAKE |
| rest100-wp4-off-r01 | next cadence (2535) | 78.3 | 1.7 | 76.6 | 100 / 0 | no | 11.41 | 8.18 | 19.59 | 3.56 | 100 AWAKE |
| rest100-wp4-on-r01 | cold (2454) | 339.8 | 3.3 | 336.5 | 100 / 0 | yes | 14.80 | 8.18 | 22.97 | 4.65 | 100 STEADY |
| rest100-wp4-on-r01 | warm (2454) | 4.8 | 2.1 | 2.7 | 0 / 100 | no | 14.80 | 8.18 | 22.97 | 4.65 | 100 STEADY |
| rest100-wp4-on-r01 | next cadence (2554) | **7.6** | 5.1 | 2.5 | **0 / 100** | no | 14.80 | 8.18 | 22.97 | 4.65 | 100 STEADY |
| stress100-wp4-on-r01 | cold (2473) | 337.5 | 4.8 | 332.8 | 100 / 0 | yes | 11.84 | 9.14 | 20.99 | 3.65 | 100 AWAKE |
| stress100-wp4-on-r01 | warm (2473) | 5.2 | 2.6 | 2.5 | 0 / 100 | no | 11.84 | 9.14 | 20.99 | 3.65 | 100 AWAKE |
| stress100-wp4-on-r01 | next cadence (2573) | **103.2** | 2.2 | 101.1 | **100 / 0** | no | 11.84 | 9.14 | 20.99 | 3.65 | 100 AWAKE |

Reading: the steady-state autosave of a world whose islands are certified costs 7.6 ms (payloads and topology copied, the capture's materialisation 5.1 ms); the same world with certificates off, or a through-flow world whose islands keep solving, re-encodes every payload (78 ms and 103 ms). A cold save costs 257 to 340 ms: full JSON encoding of about 20 MB. A certified payload is larger than an awake one (14.8 against 11.4 MB for 100 islands) because it also records the certified interval's starting graph.

Pilots before the topology cache (`rest100-wp4-pilot-r01`, 10 s warm-up, 20 s window, commit `4dcfa01`) and after it (`-r02`, same window, `f6b5dd8`): warm save 63.8 ms against 14.1 ms, the topology JSON (8.2 MB) having been re-encoded at every save before `f6b5dd8`.

## 2. rest100, certificates off against on (wp2cmp.js)

`wp4-logs/cmp-rest100-wp4-off-r01-vs-on-r01.txt`. 60 s window.

| measure | off | on | WP2 pair (120 s window) off / on |
|---|---|---|---|
| final kinds | 100 AWAKE | 100 STEADY | 100 AWAKE / 100 STEADY |
| certified at first / last sample | 0 / 0 | 93 / 100 | 0 / 94, 100 |
| full solves in window | 1200 | 20 (-98.3 %) | 2400 / 18 (-99.3 %) |
| replayed | 0 | 200 intervals, 9,085 s | 0 / 200 intervals, 15,050 s |
| mean eligible islands | 5.8 | 0 | 6.2 / 0 |
| zero-demand samples | 48 / 60 | 60 / 60 | 96 / 120, 120 / 120 |
| shared workers at the end (allocator) | 12 | 1 | - |
| after-GC heap | 996.6 MiB | 332.0 MiB | 1000 / 331 MiB |
| reference grid (tick 1900), inventory deviation | - | 1.64e-8 | 2.38e-8 (tick 2500) |
| internal-energy deviation | - | 1.61e-8 | 2.34e-8 |
| boundary ledger deviation (per island, summed) | - | 0, 0 | 0, 0 |
| component / energy balance units | 2.70e-7 / 1.82e-9 | 2.75e-7 / 6.07e-10 | - |
| ready-to-publication p50 / p95 ms | 7.55 / 54.9 | 3.07 / 4.18 | 8.46 / 55.4, 3.44 / 4.12 |
| engine ms per tick p50 | 0.0115 | 0.0123 | 0.020 / 0.010 |

The pattern is WP2's: every island STEADY, full solves down by 98 % (the certification ramp is a larger share of a 60 s window than of 120 s), a grid deviation of order 1e-8 (smaller than WP2's 2.4e-8 because half the replay time lies before the reference tick) and a zero ledger deviation.

## 3. Load without a solve burst (`FluidCheckpointFormatTest.aThousandCertifiedIslandsLoadWithoutASolveBurst`)

1,000 lone tanks certified REST with a 60 s recheck (horizon at tick 1400), saved at tick 300, loaded into a fresh coordinator at tick 300. Gate run on `ce25b78`:

| measure | value |
|---|---|
| encode / decode / register | 53.2 ms / 167.4 ms / 5.7 ms (3,529,000 payload bytes) |
| certificates restored / discarded | 1000 / 0 |
| ready islands after the load | 0 |
| ticks 301 to 1399: solves dispatched, pumps, deadlines fired, materialisations, island visits | 0, 0, 0, 0, 0 |
| tick 1400: deadlines fired | 1000 (every horizon) |
| ticks 1400 to 1520: solves | 1000, one revalidating interval each (at most 64 dispatches per tick), all renewed |

## 4. Gates (on `ce25b78`, sequence in `wp4-logs/gates.log`)

| gate | result |
|---|---|
| `fluidScienceTest --rerun` | 161 tests, 0 failures |
| `fluidRuntimeTest --rerun` | 193 tests, 0 failures (189 + 9 new, - 2 FluidSaveCompatibilityTest, - 2 EnergyReferenceMigrationTest, - 1 SolidRuntimeTest version-1 migration) |
| `fluidNetworkBenchmark --rerun` | 30/9, 19/14, 37/3 accepted/rejected substeps |
| `fluidSolverRegression -PfluidRegressionMode=exact` | chain-100 max deviation 0.000e+00 in state/moles, temperature, phase fraction, flow |
| `runFluidGameTestServer -PfluidGameTestRunId=wp4-final-r01` | All 28 required tests passed (26 + 2 new), scheduler self-verification on |
| P12 / P31 fingerprints | byte-identical to the `wp2-logs` copies (SHA-256 `56332b64...`, `4dcb80a4...`) |

## 5. Dev client (MCP bridge), `wp4-screenshots/`

| step | screenshot | observed |
|---|---|---|
| fresh world, devices placed | `04-devices-placed.png` | tank at (8, -60, 7); generator at (11, -60, 7) feeding three pipes that end in air |
| before the save | `05-tank-resting-before-save.png`, `06-line-resting-before-save.png` | tank "RESTING: no flow since 110.7 s", View 175.10 s; line "RESTING: no flow since 134.5 s", View 205.50 s |
| saved file | (log) | `createcheme_fluid_core.dat`: FluidFormat 3, TopologyFormat 4, Epoch 4550, 2,298 bytes |
| reload | (log) | `fluid_world status=LOADED format=3 online_tick=4550 islands=2 certificates_saved=2 certificates_restored=2 awake=0` |
| after the reload | `08-tank-resting-after-reload.png`, `09-tank-resting-after-reload-later.png`, `10-line-resting-after-reload.png` | tank "RESTING: no flow since 110.7 s", View 250.10 s then 280.10 s, Lag 0.00 s; line "RESTING: no flow since 134.5 s", View 295.50 s |
| old format-2 world | `11-old-format2-world-selected.png`, `wp4-logs/old-world-refusal-crash-report.txt` | refused at server start: "Existing fluid authority could not be read: Fluid checkpoint format 2 cannot be read: this build reads format 3 only and has no upgrade from older formats. Create a fresh world for this development build. Refusing to replace its inventories: ..."; the client closes with a crash report; `createcheme_fluid_core.dat` byte-identical afterwards |
