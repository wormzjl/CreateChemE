# F1 tables: pumped fills, holds, start-up determinism, tolerance default

Date: 2026-09-24. Batch `2026-09-23-fluid-followups`, package F1. Branch `claude/fluid-followups` over `main` @ `23beadd` (0.3.0), head `111d805` (code as of `d17e76a`; `7f933ff` for the paced and in-game runs, the last code change before the GameTest and the changelog) unless a row says otherwise. Machine: AMD Ryzen 7 9700X (8 cores, 16 threads), 48 GB, Windows 11; every timed run started with no `Endfield.exe` and at least 20 GB free (gates in `f1-logs/gates.log`, paced runs in `f1-logs/paced/campaign.log`, in-game runs in `f1-logs/rig/campaign.log`). JVMs: JDK 21.0.11 for tests and game, `-Xshare:off`; game runs `-Xmx4096m`, 12 automatic solver workers, default `createcheme-common.toml` of the build under test (`certificateStationaryTolerance` 1e-9 on `23beadd`, 1e-7 on the branch).

**Windows: every in-game run 60 s warm-up after placement + 60 s measured, soaks 60 s + 300 s; paced runs 60 s warm-up + 60 s measured. Numbers of different windows are not compared.**

## 1. Gates

| gate | `23beadd` (WP5 final, `1401cab` code) | `111d805` | log |
|---|---|---|---|
| `fluidScienceTest --rerun` | 161 / 0 | 161 / 0 | `f1-logs/gate-head-science.log` |
| `fluidRuntimeTest --rerun` | 193 / 0 | 202 / 0 (+3 `IslandCoordinatorTest`, +6 `FluidPumpedFillLineTest`) | `gate-head-runtime.log` |
| `fluidNetworkBenchmark --rerun` (accepted/rejected substeps, 2/10/100 reservoirs) | 30/9, 19/14, 37/3 | 30/9, 19/14, 37/3 | `gate-head-network.log` |
| `fluidSolverRegression -PfluidRegressionMode=exact` | 0.000e+00 | 0.000e+00 (no re-capture) | `gate-head-regression.log` |
| `runFluidGameTestServer` | 28 passed; the new `FluidPumpedFillGameTests` fails there ("Didn't succeed or fail within 40000 ticks", held at `1.6678766152722075E-6`; run stopped by hand after 14 min, `gate-gtbase-gametest.log`) | 29 passed | `gate-head-gametest.log` |
| P12 / P31 SHA-256 | `56332b64ea3f3bde...` / `4dcb80a40266...` | byte-identical (also at every intermediate gate run) | `f1-logs/head-P12-*.json`, `head-P31-*.json` |

## 2. Off-line reproduction (synchronous rig, T1)

Placement defaults (reservoirs 1 m3 nitrogen at 298.15 K and 101,325 Pa, generators water at 298.15 K and 101,325 Pa, voids nitrogen at 101,325 Pa, pipes 1 m of 0.05 m bore, pumps 0.01 m3/s and 500 kPa). In game residuals are from the WP5 probe logs; off-line from `f1-logs/probe-01.out`, `coordinated-01.out`.

| layout | in game (WP5, 0.3.0) | off-line on `23beadd` | same bits |
|---|---|---|---|
| six-tank fill `GUPRPRPRPRPRPR`, first slice | `Newton iteration limit at residual 7.923408755940031E-7` | same | yes |
| six-tank fill after one committed tick | `Newton line search stalled at residual 0.09603699127936284` repeated | `0.09593857326...` family at slice [1,+2] | same family |
| three-tank fill (fill100 soak, 33 lines) | `Newton line search stalled at residual 0.09597743331520116` repeated from tick 109 | `0.1047935300925...` (velocity clamp) and trace-limiter stalls | same mechanisms |
| pump transfer `RUPR` | `Newton iteration limit at residual 0.09571166727086529` at committed tick 508 | same, interval 5 (t = 20 s) | yes |
| two to two `RPRUPRPR` | held by 70 s | `Interval substep limit ... advanced=3.725 of 5.0 s`, `0.09083830403854434` | same kind |
| vented chain (probe4) | held, `Linearized error estimate does not contract` | `Newton iteration limit at residual 1.954255329315851E-6` on every retry (0.00 s of work each) | same kind |

## 3. Root-cause evidence (T2)

| measurement | value | source |
|---|---|---|
| first tank at t = 7.7 ms of the three-tank fill | 281.3 K, 96,826 Pa, 6.9e-5 m3 water (wet-bulb cooling of dry nitrogen) | `f1-logs/trace/fill3-i1.txt` |
| connections at that point | tank 2 to tank 1 and tank 3 to tank 2 reversed, -0.2246 kg/s at the velocity limit | same |
| Newton failures of the three-tank fill's first interval pinned by the trace limiter | 73 of 76, all on the second or third tank's steam amount, x from 1e-12 down to 1e-28 | `failures.js`, `limits.js` on `fill3-i1b.txt` |
| same, six-tank first interval / six-tank retry at [1,+2] | 14 of 19 / 20 of 20 | `fill6-i1b.txt`, `fill6-attempt13.txt` |
| step length the limiter allowed at the first stall | 2.23e-10, 2.23e-12, 2.23e-14, 2.23e-16 (col 10 = tank 2 steam, x = 1e-12, d = -4.43e-3) | `fill6-i1b.txt` |
| vented chain retry: rate-solve start flows from the cut attempt, tanks at rest | 0.452 and -0.222 kg/s; linear convergence to 1.2e-6 in 20 iterations, every retry | `vent3-attempt3.txt` |
| velocity-clamp jump at zero flow (three-tank, t = 0.128 s, tank 2 to tank 3) | row -0.0234 at q = +2.9e-188, 0.99998 on the reverse side; driving -2343 Pa | `fill3-slice12.txt` |
| gas transfer suction at the stall | 273.1600 K, 74,594 Pa after 21.86 s; model floor 273.16 K | `rupr-i5.txt` |
| cost of the three-tank fill's first 50 ms (warm, one thread) | 858 ms: 62 attempts, 197 Newton solves, 1,385 iterations, 15,159 property states | `counters-01.out` |

## 4. Ablation (FluidPumpedFillLineTest, real coordinator, one worker, 1 us per solver checkpoint)

| build | three-tank fill | vented three-tank | six-tank fill (2 min) | gas transfer (20 min) | log |
|---|---|---|---|---|---|
| old solver + new hold policy | STEADY at 601,325 Pa, 15.2 s of work | FULL, 15.2 s | never commits, `7.923408755940031E-7`, 11 jobs, 3.2 s | parked at 273.17 K, 23 jobs, 7.3 s | `ablation-old-solver.xml` |
| trace fix + old hold policy | STEADY, 9.7 s | held forever, `1.965038869308691E-6`, 120 jobs | 24 wall holds, 48.0 s, committed 0 | 240 jobs, 58.2 s, not at the floor | `ablation-old-policy.xml` |
| `7f933ff` (all fixes) | STEADY at 601,325 Pa at 264 s, 8.2 s of work, 58 jobs | FULL at the pump's target, 8.3 s | committed 66 ticks by online tick 907, 23.4 s, 16 jobs | parked at 273.17 K, 23 jobs, 7.3 s, 9 deferred retries | gate-final-runtime |

## 5. In game, dedicated server (T3, T4)

Before = `23beadd` (for fill100 the WP5 runs on `1401cab`, identical code; cited with their windows), after = branch. Probes rebuilt with the WP5 composition (six-tank fills, 237 and 528 devices). `n/a`: the counter does not exist on that build. Full rows: `f1-logs/rig/f1-ingame-table.md`; runs under `f1-logs/rig/<run>/` and `fluid-scheduler/wp5-logs/<run>/`.

| run | build | window | process CPU, cores | allocation MiB/s | GC | tick ms p50 / p95 | solves in window | HELD warnings (whole log) | kinds and statuses at the window end |
|---|---|---|---|---|---|---|---|---|---|
| srv-fill100-wp5-r01 | before | 60+60 s | 12.24 | 9106.6 | 620 | 0.377 / 1.411 | 427 | 743 | FULL 30, HELD 58, SOLVING 12 |
| srv-fill100-wp5-r02 | before | 60+60 s | 12.30 | 9587.1 | 611 | 0.344 / 1.046 | 437 | 754 | FULL 32, HELD 56, SOLVING 12 |
| srv-fill100-f1-r01 | `1dac4bd` (trace fix + hold policy only) | 60+60 s | 12.17 | 10020.2 | 704 | 0.329 / 0.861 | 458 | 712 | FULL 6, HELD 82, SOLVING 12 |
| srv-fill100-f1-r02 | after | 60+60 s | **0.35** | 32.9 | 2 | 0.137 / 0.413 | 1400 | **0** | **FULL 100** |
| srv-fill100-wp5-soak-r01 | before | 60+300 s | 6.87 | 4822.6 | 1372 | 0.251 / 0.561 | 7557 | 2479 | STEADY 67, HELD 33 |
| srv-fill100-f1-soak-r01 | after | 60+300 s | **0.32** | 199.2 | 77 | 0.095 / 0.267 | 6300 | 1 | **STEADY 100** (all by online tick 5400) |
| srv-probe-base-r01 | before | 60+60 s | 2.03 | 1815.7 | 138 | 0.185 / 0.548 | 133 | 146 | REST 17, STEADY 2; FULL 4, HELD 4, SOLVING 4 |
| srv-probe-f1-r01 | after | 60+60 s | 0.58 | 196.8 | 17 | 0.145 / 0.450 | 181 | 42 | REST 17, STEADY 2, FULL 12 |
| srv-probe2-base-r01 | before | 60+60 s | 7.59 | 7459.4 | 532 | 0.303 / 1.029 | 1241 | 634 | REST 17, STEADY 2; FULL 30, HELD 14 |
| srv-probe2-f1-r01 | after | 60+60 s | 0.89 | 478.8 | 38 | 0.165 / 0.541 | 657 | 55 | REST 17, STEADY 32, FULL 14 |
| srv-probe3-base-r01 | before | 60+60 s | 2.62 | 2964.5 | 175 | 0.183 / 0.539 | 248 | 408 | HELD 24 |
| srv-probe3-f1-r01 | after | 60+60 s | 2.23 | 2383.4 | 176 | 0.157 / 0.774 | 184 | 312 | HELD 24 (8 budget, 160 numerical holds, 88 deferred retries in the window) |
| srv-probe3-f1-soak-r01 | after | 60+300 s | 0.55 (per minute: 1.99, 0.24, 0.21, 0.09, 0.22) | | | | | | HELD 24, retries one per island every 32 to 64 cadences at the end |
| srv-probe4-base-r01 | before | 60+60 s | 2.34 | 2216.0 | 131 | 0.212 / 0.611 | 360 | 262 | FULL 13, HELD 10, SOLVING 1 |
| srv-probe4-f1-r01 | after | 60+60 s | 0.24 | 11.1 | 0 | 0.132 / 0.419 | 316 | 34 | FULL 24 |

fill100 after, per 10 s period (`srv-fill100-f1-r02`): placement at online tick about 108; FULL 15 at tick 200, FULL 88 with 12 solving at 400 and 600, FULL 100 from 800 on, no budget or numerical hold in any period. The soak's pressure at the end is the pump's shutoff: 601,325 Pa in every tank (FluidPumpedFillLineTest reproduces the end state to 1 Pa).

## 6. Start-up determinism (Problem 3), paced `transient100`, 60 s + 60 s

| pair | build | fingerprints (islands identical on every common publication) | held at start-up | reference grid tick 1900 |
|---|---|---|---|---|
| WP5 on-r01 / on-r02 / on-r03 (pairwise) | `1401cab` | 95 of 100; 10905, 10907, 10910, 10912, 10915 part at their first retried slice (tick 50) | the same 5 in all three | 95 exact, 0 differ |
| transient100-f1-r01 vs r02 | `1dac4bd` | **100 of 100** | the same 5, in both | 95 exact, 0 differ |
| transient100-f1-r03 vs r04 | `7f933ff` | **100 of 100** | the same 5, in both | 95 exact, 0 differ |
| transient100-f1-r01 vs r03 | across `1dac4bd` and `7f933ff` | 100 of 100 | same | |

Latency of r03 / r04: ready-to-publication p50 19.51 / 19.98 ms, p95 67.77 / 65.88 ms, engine p50 0.0068 ms per tick, 0 held intervals in the window, integrity passed. First round (r01): 12 islands dispatched at tick 100 on a cold JIT; the 7 that finished took 1.05 to 1.55 s of wall at a CPU/wall of 0.55 to 0.81; the 5 cut are the heaviest; their 50-tick retries took 0.46 to 0.64 s at CPU/wall about 1.

## 7. Tolerance default, paced `stress100`, 60 s + 60 s (`7f933ff`)

| measure | off (`stress100-f1-off-r02`) | on, default eps_s 1e-7 (`stress100-f1-on-r02`) |
|---|---|---|
| full solves in the window | 1200 | 1028 (-14.3 %) |
| certificates issued / STEADY at the end / most at once | 0 | 40 / 17 / 19 |
| replayed intervals / seconds | 0 | 58 / 938.4 s |
| reference grid (97 islands exact in both): inventory / energy deviation | | 6.76e-9 / 9.86e-9 (budget 1e-6) |
| ledger against the island's own inventory / conservation | | 8.24e-10 / 1.2e-15 (energy 3.1e-12) |
| balance units component / energy, integrity | 3.42e-7 / 4.15e-10, passed | 3.43e-7 / 3.06e-10, passed |
| held at start-up | 2 islands | 3 islands (1 held in one run only) |

The same pair on `1dac4bd`: 1200 / 1030 full solves, 40 issued, 17 STEADY at the end, inventory 6.76e-9, energy 9.86e-9 (`paced/cmp-stress100-f1.txt`). WP2 measured 1e-7 at a 60 s + 120 s window: 92 issued, 2400 / 2002 full solves, inventory 1.0e-8, energy 1.45e-8.
