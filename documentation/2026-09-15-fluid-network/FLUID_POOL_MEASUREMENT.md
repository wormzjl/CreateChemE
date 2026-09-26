# Fluid network: the production-pool measurement after WP1-WP3 (review section 3.5)

Worktree `D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/fluid-solver-opt`, branch
`claude/fluid-solver-optimization`, HEAD `dba6179` (= `c2580ed` + the pool-safe diagnostics commit).
Baseline `154007dc1f68574634cc0ef64ba66f132d83e2e3`, run in the same session from a throwaway
worktree that has since been removed.

Review section 3.5 left one question unresolved. The in-process counters charged 18-28% of a
single-threaded interval to linear algebra and 60-67% to residual evaluation; JFR on the 12-worker
stress run charged 66.5% to linear algebra and 21.5% to properties. Three explanations were offered
and none was settled: (a) memory-hierarchy/SMT contention with 12 workers, (b) the profiled islands
refreshing Jacobians more often than the replays, (c) top-frame attribution. The review named the
decisive experiment: one server-benchmark run with the probe counters ported and enabled, compared
against JFR on the same run. That is this document. It also decides the priority of B3.

---

## Headline

| # | quantity | 154007d (same session) | HEAD | change |
|---:|---|---:|---:|---|
| 1 | worker ms per accepted 5 s interval, p50 | 56.56 | **3.28** | 17.2x |
| 2 | worker ms, p95 / max | 122.53 / 2000.76 | **5.88 / 15.56** | 20.8x / 128x |
| 3 | intervals held (round or wall deadline) | 86 | **0** | - |
| 4 | jobs that reached the 2 s wall budget | 12 | **0** | - |
| 5 | substeps per accepted interval, p50 / mean / max | 3 / 3.32 / 35 | **1 / 1.00 / 1** | - |
| 6 | Newton Jacobian builds in 120 s | (not counted) | **0** | - |
| 7 | Newton LU factorizations in 120 s | (not counted) | **0** | - |
| 8 | worker CPU (`jdk.ThreadCPULoad`, summed) | 0.2067 of host | **0.0066** | 31.2x |
| 9 | worker execution samples in the window | 11 859 | **331** | 35.8x |
| 10 | exclusive CPU: EJML numeric LU factorization | 42.43% | **0.60%** | - |
| 11 | exclusive CPU: triangular solves + verification | 20.92% | **50.45%** | - |
| 12 | exclusive CPU: all sparse linear algebra | 65.80% | **51.06%** | - |
| 13 | sampled allocation rate | 3.22 GiB/s | **0.087 GiB/s** | 36.9x |
| 14 | allocation per accepted 5 s interval | 122.8 MiB | **4.47 MiB** | 27.5x |
| 15 | GC pauses / total / share of window | 483 / 1466 ms / 1.22% | **6 / 45.3 ms / 0.038%** | 32x |
| 16 | collections caused by `G1 Humongous Allocation` | 214 of 393 | **0 of 5** | - |
| 17 | `one` profile gate, p50 / p95 / max | 112.1 / 167.2 / 206.3 (review) | **8.96 / 10.20 / 15.94** | 12.5x / 16.4x / 12.9x |
| 18 | aggregate realtime ratio (simulated s per real s per island) | 1.262 | **1.000** | at cadence |

Item 18 is the reason the job count fell from 3312 to 2400 rather than rising. The baseline spent
its whole measured window draining warm-up debt, so it completed 26.9 intervals per second. HEAD
enters the window with the debt already gone and completes exactly 20.0 per second, which is what
100 islands on a 5 s cadence require. Every comparison below is therefore per job or per simulated
second, not per second of wall clock.

---

## 0. Runs, commands and scripts

Host: AMD Ryzen 7 9700X, 16 hardware threads, Windows 11, JDK 21.0.11, G1, `-Xms4096m -Xmx4096m`.
One Gradle invocation at a time throughout; no two JVMs were ever measured concurrently.

| run id | commit | profile | workers | what | artefacts |
|---|---|---|---:|---|---|
| `opt-wp5-stress100-12w-r01` | `dba6179` | stress100 | 12 (auto) | the main measurement | `build/reports/fluid/M9/opt-wp5-stress100-12w-r01/` |
| `opt-wp5-one-2w-r01` | `dba6179` | one | 2 | the design gate | `build/reports/fluid/M9/opt-wp5-one-2w-r01/` |
| `opt-wp5-baseline-stress100-12w-r01` | `154007d` | stress100 | 12 (auto) | same-session baseline | `build/reports/fluid/M9/opt-wp5-baseline-stress100-12w-r01/` |
| `opt-wp5-smoke-r01` | `dba6179` | one (pilot) | 2 | report-key smoke test | `build/reports/fluid/M9/opt-wp5-smoke-r01/` |

The stored baseline (`memory-candidate-4g-r01`, in the Codex worktree) is quoted for continuity
only; the same-session rerun is what the comparison uses.

```powershell
# HEAD, main measurement
.\gradlew.bat fluidServerBenchmark -PfluidBenchmarkRunId=opt-wp5-stress100-12w-r01 `
  -PfluidBenchmarkProfile=stress100 -PfluidBenchmarkWorkers=0 -PfluidStressWarmupSeconds=60 `
  -PfluidStressMeasurementSeconds=120 -PfluidStressProfile=true -PfluidBenchmarkMemory=true `
  -PfluidBenchmarkHeapMiB=4096 -PfluidBenchmarkDiagnostics=true --console=plain

# HEAD, one-profile gate
.\gradlew.bat fluidServerBenchmark -PfluidBenchmarkRunId=opt-wp5-one-2w-r01 `
  -PfluidBenchmarkProfile=one -PfluidBenchmarkWorkers=2 -PfluidBenchmarkDiagnostics=true --console=plain

# 154007d, same-session baseline, in a throwaway detached worktree (removed afterwards)
git worktree add .claude/worktrees/fluid-baseline-154007d 154007dc1f68574634cc0ef64ba66f132d83e2e3
.\gradlew.bat fluidServerBenchmark -PfluidBenchmarkRunId=opt-wp5-baseline-stress100-12w-r01 `
  -PfluidBenchmarkProfile=stress100 -PfluidBenchmarkWorkers=0 -PfluidStressWarmupSeconds=60 `
  -PfluidStressMeasurementSeconds=120 -PfluidStressProfile=true -PfluidBenchmarkMemory=true `
  -PfluidBenchmarkHeapMiB=4096 --console=plain
git worktree remove .claude/worktrees/fluid-baseline-154007d --force
```

Analysis, from `build/reports/fluid/analysis-claude-wp5/`:

```bash
JFR="C:/Program Files/Java/jdk-21.0.11/bin/jfr.exe"
PY="C:/Users/wormz/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe"

"$JFR" print --events jdk.ExecutionSample --stack-depth 48 ../M9/<run>/stress.jfr > exec-samples-<run>.txt
"$PY" cpu_buckets_wp5.py exec-samples-<run>.txt ../M9/<run>/report.json          > cpu-buckets-<run>.json
"$PY" cpu_buckets_wp5.py exec-samples-head.txt  ../M9/<run>/report.json warmup   > cpu-buckets-head-warmup.json

"$JFR" print --json --events jdk.GCPhasePause,jdk.GarbageCollection,jdk.GCHeapSummary,\
jdk.ThreadCPULoad,jdk.CPULoad,jdk.GCCPUTime ../M9/<run>/stress.jfr > gc-cpu-<run>.json
"$PY" gc_cpu_wp5.py gc-cpu-<run>.json ../M9/<run>/report.json > gc-cpu-<run>-result.json

"$JFR" print --events jdk.ObjectAllocationSample --stack-depth 12 ../M9/<run>/stress.jfr > alloc-<run>.txt
"$PY" alloc_wp5.py alloc-<run>.txt ../M9/<run>/report.json > alloc-<run>-result.json

"$PY" reports_wp5.py ../M9/opt-wp5-stress100-12w-r01/report.json \
                     ../M9/opt-wp5-baseline-stress100-12w-r01/report.json > reports-wp5.json
"$PY" reports_wp5.py ../M9/opt-wp5-one-2w-r01/report.json > reports-one-wp5.json
```

Scripts in `build/reports/fluid/analysis-claude-wp5/`: `parse_exec.py` (copied verbatim from the
baseline analysis), `window.py` (derives the measurement or warm-up window from the run's own
report instead of a hard-coded constant), `cpu_buckets_wp5.py`, `gc_cpu_wp5.py`, `alloc_wp5.py`,
`reports_wp5.py`. The bucket table is the baseline's, extended for the classes WP1-WP3 introduced
(`SparseLuSolver$Storage.factor`, `FluidThermodynamics$Prepared`, `MixtureViscosity$Prepared`,
`RetainedSolver`) and with `TranslatedPengRobinson$TemperatureTerms` charged to the temperature-terms
bucket. Large text exports (`exec-samples-baseline.txt`, `alloc-baseline.txt`) were deleted after
parsing; `exec-samples-head.txt` and `alloc-head.txt` are kept (49 MB together).

**Method check.** Run on the same-session 154007d recording the scripts reproduce the stored
baseline analysis: numeric LU 42.43% vs 42.26% published, triangular solves 20.92% vs 21.32%,
linear algebra total 65.80% vs 66.51%, `differentiate` inclusive 66.69% vs 65.52%,
`Equations.residual` inclusive 26.01% vs 24.74%, `FluidThermodynamics.state` 16.32% vs 16.2%,
`temperatureTerms` allocation 13.60% vs 13.39%, `growMaxLength` 13.57% vs 11.88%. The bucketing and
the window derivation are therefore the published method, not a new one.

**Host drift.** None. The same-session 154007d rerun reproduces the stored baseline within run
noise: p50 56.56 vs 55.44 ms, p95 122.53 vs 119.56, max 2000.76 vs 2000.22, worker CPU 0.2067 vs
0.1825 of the host, allocation 3.22 vs 2.93 GiB/s, held 86 vs 51. HEAD differs from both by
factors of 17-37, far outside that spread.

---

## 1. Interval statistics (`report.json`)

| metric | 154007d | HEAD | HEAD `one` gate |
|---|---:|---:|---:|
| jobs measured / accepted | 3312 / 3226 | 2400 / 2400 | 200 / 200 |
| worker ms p50 / p95 / p99 / max | 56.56 / 122.53 / 1594.5 / 2000.76 | 3.28 / 5.88 / 10.17 / 15.56 | 8.96 / 10.20 / 13.83 / 15.94 |
| worker ms mean | 81.59 | 3.50 | 8.82 |
| summed worker wall time | 263.3 s | 8.40 s | 1.76 s |
| held intervals (measured) | 86 | 0 | 0 |
| held intervals (warm-up) | 271 | 33 | 0 |
| jobs at the 2 s wall | 12 | 0 | 0 |
| substeps per accepted, p50 / mean / max | 3 / 3.32 / 35 | 1 / 1.00 / 1 | 1 / 1.00 / 1 |
| rejected substeps, total / jobs affected | 700 / 206 | 0 / 0 | 0 / 0 |
| dispatch to publication p95 | 181.8 ms | 6.73 ms | 10.40 ms |
| ready to publication p95 | 64.0 s | 54.9 ms | 11.2 ms |
| engine server ms per tick, p50 / p95 | - | 0.094 / 0.191 | 0.011 / 0.157 |
| completed intervals per second | 26.87 | 20.00 | - |
| aggregate realtime ratio | 1.262 | 1.000 | - |
| status | STRESS_COMPLETED, integrity passed | STRESS_COMPLETED, integrity passed | **REPLICATE_PASSED** |
| component / energy balance | 3.9e-7 / 2.9e-9 | 5.4e-7 / 6.8e-10 | gates passed |

Worker ms p50 by island size, all 21 sizes present in the fixture:

| reservoirs | 10 | 12 | 14 | 16 | 18 | 20 | 22 | 24 | 26 | 28 | 30 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 154007d p50 | 29.4 | 33.2 | 42.6 | 47.2 | 48.6 | 52.6 | 57.8 | 67.8 | 73.8 | 77.0 | 80.6 |
| HEAD p50 | 1.78 | 2.13 | 2.58 | 2.43 | 2.93 | 3.31 | 3.47 | 3.63 | 4.06 | 4.16 | 4.44 |
| ratio | 16.5 | 15.6 | 16.5 | 19.4 | 16.6 | 15.9 | 16.6 | 18.7 | 18.2 | 18.5 | 18.2 |

The improvement is uniform across sizes (14.5x to 19.4x over all 21), so it is not a small-island
effect. The baseline's max column runs to 1.85-1.97 s from 21 reservoirs upward; HEAD's worst job
of any size is 15.6 ms.

The `one` profile is the design gate the review quoted at p50 112 ms, p95 167 ms, max 206 ms against
a 2000 ms budget. It now runs at p50 8.96, p95 10.20, max 15.94 ms, and the benchmark's own verdict
is `REPLICATE_PASSED` with zero held intervals and zero rejected substeps over 200 intervals.

---

## 2. Solver diagnostics under the pool (`report.json` -> `solverDiagnostics`)

Exact counts, summed over all twelve workers inside the measurement window. Nanosecond totals are
occupied thread time and their sum over threads legitimately exceeds the window.

| counter | stress100, total | per accepted interval | `one`, per interval |
|---|---:|---:|---:|
| step attempts / accepted | 2400 / 2400 | 1.000 / 1.000 | 1.000 / 1.000 |
| implicit solves | 4870 | 2.029 | 2.000 |
| active-set passes | 4870 | 2.029 | 2.000 |
| companion filters / nonlinear companion solves | 2330 / 70 | 0.971 / 0.029 | 1.000 / 0.000 |
| companion defects below tolerance | 1558 | 0.649 | 1.000 |
| Newton solves / iterations / backtracks | 4870 / 6646 / 0 | 2.029 / 2.769 / 0 | 2.000 / 2.000 / 0 |
| residual evaluations | 11 516 | 4.798 | 4.000 |
| residual evaluations inside a Jacobian | **0** | **0** | **0** |
| **Jacobian builds** | **0** | **0** | **0** |
| **Newton LU factorizations** | **0** | **0** | **0** |
| **Newton RCM orderings** | **0** | **0** | **0** |
| Newton LU solves (vectors) / ns | 7488 / 2.288e9 | 3.120 / 0.953 ms | 2.000 / 1.202 ms |
| backward-error checks / refinements | 7200 / 0 | 3.000 / 0 | 3.000 / 0 |
| superseded factorizations | 0 | 0 | 0 |
| transport factorizations / ns | 9600 / 4.41e7 | 4.000 / 0.018 ms | 4.000 / 0.057 ms |
| transport solves (vectors) / ns | 211 200 / 7.29e7 | 88.00 / 0.030 ms | 88.00 / 0.079 ms |
| transport RCM orderings / ns | 9600 / 1.01e6 | 4.000 / 0.0004 ms | 4.000 / 0.002 ms |
| reconstructions / ns | 9600 / 1.298e9 | 4.000 / 0.541 ms | 4.000 / 1.686 ms |
| node `state()` calls | 510 178 | 212.6 | 1000.0 |
| `temperatureTerms` calls | 380 016 | 158.3 | 700.0 |
| `flashTP` calls | 0 | 0 | 0 |
| factorization storages allocated | 9600 | 4.000 | 4.000 |
| dominant error term per attempt | state 2385, pipe 15 | - | state 200 |

The three zeroes are the whole story of WP1-WP3. In 2400 measured production intervals the Newton
side never built a Jacobian, never factorized and never ordered: A2's retained per-island solver
carries a factorization built during warm-up, A1's carried step size makes a quiet 5 s interval a
single substep, and the chord iteration converges against that preconditioner in 2.8 Newton
iterations per interval. The only factorizations left in the steady state are the four
`ConservativeTransport` reconstructions per interval, which still build a fresh RCM ordering, a
fresh factorization and a fresh EJML storage every time (`luStorages` 4 per interval) - that is the
one part of B2 that was never applied, and it costs 0.018 ms of 3.5 ms.

---

## 3. CPU profile (`jdk.ExecutionSample`, exclusive buckets, first match walking the stack down)

Three windows: the same-session baseline's measurement window; HEAD's own 60 s warm-up, which is
the cold/transient regime with all twelve workers saturated; and HEAD's measurement window.

| exclusive bucket | 154007d measured | HEAD warm-up | HEAD measured |
|---|---:|---:|---:|
| `SparseLuSolver` factorization (EJML numeric LU) | **42.43%** | **38.82%** | **0.60%** |
| `Factorization.solve*` / `checkResidual` | 20.92% | 18.82% | 50.45% |
| `SparseLuSolver` ordering (RCM) | 2.44% | 0.20% | 0.00% |
| `SparseNewton.differentiate` + `Pattern` own | 3.47% | 3.30% | 0.00% |
| `TranslatedPengRobinson.evaluate` / `normalize` | 8.23% | 12.33% | 11.18% |
| `WaterRegion1` / `V3WaterProperties` / `MaterialRuntime` | 7.56% | 0.34% | 0.60% |
| `MixtureViscosity` / `ViscosityCorrelation` | 4.28% | 7.63% | 6.95% |
| `PhaseLayout` encode/decode/residual | 1.53% | 5.73% | 8.76% |
| `TranslatedPengRobinson.temperatureTerms` | 0.56% | 1.14% | 3.62% |
| `Equations` assembly | 2.03% | 2.90% | 3.02% |
| `PipeResistance` / `Pipe.loss` | 1.32% | 1.68% | 0.91% |
| `FluidThermodynamics.state` / `Prepared` | 0.69% | 1.02% | 1.81% |
| `PengRobinson78` cubic root | 0.67% | 0.82% | 0.30% |
| `TrBdf2StepSolver` / `PassiveIntervalSolver` / `PipeTransfer` | 0.92% | 1.22% | 6.95% |
| `ConservativeTransport` own | 0.14% | 0.42% | 0.30% |
| `java.util.stream` | 1.36% | 0.50% | 1.51% |
| `Arrays.copyOf` / `clone` / `arraycopy` | 0.14% | 0.88% | 1.21% |
| `flashTP` | 0.00% | 0.00% | 0.00% |
| worker samples in window | 11 859 | 5005 | 331 |

Grouped:

| group | 154007d measured | HEAD warm-up | HEAD measured |
|---|---:|---:|---:|
| all sparse linear algebra | 65.80% | 57.84% | 51.06% |
| Jacobian colouring / `differentiate` own | 3.47% | 3.30% | 0.00% |
| residual: PR temperature terms | 0.56% | 1.14% | 3.62% |
| residual: PR evaluate + cubic root + liquid response | 9.34% | 13.75% | 11.48% |
| residual: water | 7.56% | 0.34% | 0.60% |
| residual: viscosity + transport properties | 5.61% | 9.31% | 7.86% |
| residual: decode + assembly + `state()` + other | 4.26% | 9.65% | 13.60% |
| reconstruction own | 0.14% | 0.42% | 0.30% |
| everything else | 3.26% | 4.26% | 11.48% |

Inclusive coverage:

| inclusive | 154007d measured | HEAD warm-up | HEAD measured |
|---|---:|---:|---:|
| `SparseNewton.differentiate` | 66.69% | 67.27% | **0.00%** |
| `SparseNewton.solve` | 94.22% | 92.71% | 62.84% |
| `SparseLuSolver$Factorization.<init>` / `$Storage.factor` | 42.43% | 57.64% | 51.06% |
| `Factorization.solveMultiple` | 20.92% | 18.82% | 50.45% |
| `PassiveStepSolver$Equations.residual` | 26.01% | 33.85% | 24.17% |
| `FluidThermodynamics.state` | 16.32% | 16.22% | 17.22% |
| `PhaseLayout.decode` | 16.16% | 16.80% | 15.11% |
| `ConservativeTransport.reconstruct` | 1.00% | 1.26% | 4.23% |
| `org.ejml LuUpLooking_DSCC` | 38.14% | 34.78% | 0.60% |
| `org.ejml TriangularSolver_DSCC` | 50.72% | 49.53% | 48.94% |
| `MaterialRuntime.with` | 7.56% | 0.00% | 0.00% |
| `PassiveIntervalSolver.integrate` / `RetainedSolver.solve` | 99.93% | 99.96% | 100.00% |

Two things to read carefully.

*The 331-sample window.* HEAD's measurement window contains 331 worker execution samples because
there is so little worker CPU left to sample. On a 50% bucket the binomial standard error is 2.7
percentage points, so the 50.45% solve bucket is 50.5 +/- 5.4 pp at 95%, and the 0.60% factorization
bucket is 2 samples. Every conclusion below is drawn from differences far larger than that.

*Two clocks that do not agree.* Within HEAD's window the counters say the Newton solve region is
0.953 ms of a 3.50 ms job (27.2% of job wall time) and reconstruction 0.541 ms (15.5%); JFR says
solves are 50.45% of worker CPU samples and reconstruction 4.23% inclusive. The two cannot be
reconciled by any bucketing choice, and the reason is visible in the denominators: the jobs'
summed wall time is 8.40 s while `jdk.ThreadCPULoad` reports 12.7 CPU-s for the same twelve threads
in the same window, which is impossible for single-threaded jobs. The same inversion is present in
the baseline (263.3 s of job wall against 396.8 CPU-s) and in the stored baseline analysis
(262 s against 350 CPU-s), so it is not a HEAD artefact. The cause is resolution: Windows reports
thread CPU time in 15.6 ms quanta, which is larger than an entire 3.3 ms job, so
`workerCpuNanos`, `meanWorkerCpuOccupancy` and `jdk.ThreadCPULoad` are all unusable as absolute
CPU at this job size. What survives is: `System.nanoTime` region timers (exact wall time of the
region), JFR sample *fractions* within one window (unbiased share of on-CPU time), and JFR sample
*ratios between runs* (the sampling period is the same). Those three are what this document uses;
absolute CPU-seconds are not claimed anywhere.

---

## 4. GC and allocation

| quantity | 154007d | HEAD measured | HEAD warm-up |
|---|---:|---:|---:|
| sampled allocation weight | 386.7 GiB | 10.48 GiB | 97.5 GiB |
| allocation rate | 3.22 GiB/s | **0.0873 GiB/s** | 1.61 GiB/s |
| allocation per accepted 5 s interval | 122.8 MiB | **4.47 MiB** | - |
| share on worker threads | 99.75% | 89.5% | 99.55% |
| GC pauses / total / share of window | 483 / 1466 ms / 1.22% | **6 / 45.3 ms / 0.038%** | - |
| pause p50 / p95 / max | 2.85 / 6.80 / 9.75 ms | 10.36 / 11.44 / 11.50 ms | - |
| collections by `G1 Humongous Allocation` | 214 of 393 | **0 of 5** | - |
| collections by `G1 Evacuation Pause` | 179 | 5 | - |
| heap used after GC, min / median / p95 / max | 372 / 1717 / 3379 / 3749 MiB | 1530 / 1548 / 1571 / 1575 MiB (5 samples) | - |
| top type | `double[]` 77.8% | `double[]` 77.7% | `double[]` 89.9% |

HEAD's after-GC heap is **not** a live-set measurement and must not be read as one. The baseline
ran 393 collections in 120 s, several of them mixed, so its minimum of 372 MiB is the live set of
100 islands plus the server. HEAD ran five young evacuation pauses and no mixed cycle at all, so
nothing ever collected the old generation; its 1530 MiB floor is warm-up debris that G1 had no
reason to touch at an allocation rate of 87 MiB/s into a 4 GiB heap. The meaningful statement is
that GC work fell by a factor of 32 in pause time and by 65 in pause count, and that the humongous
allocation path that forced 54% of the baseline's collections is gone.

Allocation attribution, HEAD measurement window:

| share | category |
|---:|---|
| **43.55%** | `TranslatedPengRobinson.temperatureTerms` (three n x n matrices per distinct temperature) |
| 9.39% | `PipeTransfer` / `ConservativeTransport` / `PipeResistance` records |
| 6.85% | `TranslatedPengRobinson.evaluate` / `normalize` / `logPhi` / partial volumes |
| 6.49% | `PhaseLayout` decode / residual / totalAmounts / encode |
| 4.26% | `Equations.residual` / `states` |
| 2.97% | `WaterRegion1` / `V3WaterProperties` |
| 1.57% | `MixtureViscosity` (`Prepared.<init>` 1.43 pp) |
| 1.40% | `SparseNewton.differentiate` / `evaluate` / `solve` |
| 1.19% | `SparseLuSolver$Storage` (the retained EJML solver and buffers) |
| 0.43% | defensive record clones (`State`, `Phase`, `GlobalLiquidResponse`) |
| 0.37% | `org.ejml` internals, of which `DMatrixSparseCSC.growMaxLength` **0.32%** |

Against the baseline's profile: defensive record clones 17.03% -> 0.43% (C2), EJML internals
14.17% -> 0.37% and `growMaxLength` alone 13.57% -> 0.32% (B2), `SparseMatrix.<init>` re-cloning
4.17% -> below the cut, `Equations.residual`/`states` 12.36% -> 4.26%, `SparseNewton` 8.91% -> 1.40%
(no Jacobians). `temperatureTerms` is unchanged in absolute terms per distinct temperature and has
simply become the majority of a 27x smaller total: 13.60% of 122.8 MiB per interval is 16.7 MiB,
43.55% of 4.47 MiB is 1.95 MiB, so it did fall 8.6x with the call count, but everything around it
fell further.

---

## 5. The five questions

### (a) Is the pool-vs-single-thread discrepancy from section 3.5 resolved?

**Yes, and both halves of it.**

*The pool profile was real, not an attribution artefact.* HEAD's own warm-up window is the cold
transient regime with twelve saturated workers, and it reproduces the baseline's shape almost
exactly: numeric LU 38.82% (baseline 42.43%), triangular solves 18.82% (20.92%), all linear algebra
57.84% (65.80%), `differentiate` inclusive 67.27% (66.69%). Hypothesis (c), top-frame attribution,
is refuted: the same bucketing on the same code gives 0.60% factorization in the quiet window and
38.82% in the transient one, so the bucketing follows the work, not the frame layout. Hypothesis
(b), more Jacobian refreshes under the pool than in the replays, is confirmed as the mechanism: the
baseline's pool was doing transient work (3.32 substeps per interval, 700 rejected substeps, 206
jobs with rejections) while the replays measured quiet intervals, and the single-thread replays
were right about the quiet regime all along. Hypothesis (a), contention, is real but secondary:
the baseline's pool cost 80.6 ms p50 on a 30-reservoir island against 51.2 ms for the same island
single-threaded, a 1.57x penalty at 12 saturated workers.

*The per-worker cost per job now matches the single-thread interval cost.* On a 30-reservoir island
the pool's p50 is **4.44 ms**; the same island replayed single-threaded at this HEAD
(`quiet-11312`, warm intervals 2-4 of the `reference-wp3` capture) takes **5.60, 6.43 and 5.60 ms**,
allocating 5.5 MB per interval against the pool's 4.47 MiB. The pool is if anything marginally
cheaper, because the replay harness runs with the counters enabled and feeds a freshly deserialised
graph. The 1.57x contention penalty has vanished with the load that caused it: mean worker
occupancy is 0.8%, so there is no longer anything for twelve workers to contend over. The brief's
17-38 ms figure is the *mean over five replayed intervals including the cold first one* (102.7 ms
for `quiet-11312` interval 0); the warm interval, which is what a production steady-state job is,
is 4-6 ms, and that is what the pool now delivers.

*Linear algebra versus residual evaluation under the pool, after WP1-3.* In the quiet steady state,
51.06% of worker CPU is linear algebra and 37.16% is residual evaluation, but the composition has
inverted: factorization is 0.60% and triangular solves are 50.45%. The counters, measuring wall
time of the same regions, put linear algebra at 28.6% of job wall and reconstruction at 15.5%; the
two clocks disagree for the reason given in section 3, and the disagreement does not touch any
conclusion here, because both agree that factorization is gone (JFR 2 of 331 samples; the counters
exactly 0 factorizations in 2400 intervals).

### (b) How much did WP1-3 move the production numbers?

Against the same-session 154007d baseline, per accepted 5 s interval:

| | 154007d same session | HEAD | factor |
|---|---:|---:|---|
| worker ms p50 | 56.56 | 3.28 | 17.2x |
| worker ms p95 | 122.53 | 5.88 | 20.8x |
| worker ms max | 2000.76 | 15.56 | 128x |
| worker ms p99 | 1594.5 | 10.17 | 157x |
| jobs measured | 3312 | 2400 | at cadence, not backlogged |
| held intervals | 86 | 0 | eliminated |
| 2 s wall hits | 12 | 0 | eliminated |
| allocation rate | 3.22 GiB/s | 0.0873 GiB/s | 36.9x |
| allocation per interval | 122.8 MiB | 4.47 MiB | 27.5x |
| GC pause share of window | 1.22% | 0.038% | 32x |
| worker CPU (ThreadCPULoad ratio) | 0.2067 | 0.0066 | 31.2x |
| worker CPU (execution-sample ratio) | 11 859 samples | 331 samples | 35.8x |
| after-GC live set | 372 MiB (min, 393 collections) | not measurable (5 collections, no mixed cycle) | - |

Per simulated second of island time the fall is larger than per job, because HEAD's jobs advance a
full 5 s each while the baseline's averaged 4.70 s: 15 150 simulated seconds for the baseline's
263.3 s of worker wall against 12 000 for HEAD's 8.40 s, i.e. 17.4 ms of worker wall per simulated
second down to 0.70 ms, a factor of 25. The review's own units (CPU-ms per simulated second) give
26.2 -> 0.92 on the execution-sample calibration, a factor of 28.

The `one` design gate moved from p50 112.1 / p95 167.2 / max 206.3 ms (review) to p50 8.96 / p95
10.20 / max 15.94 ms, and the run's verdict is `REPLICATE_PASSED`.

The review's step-1-to-3 forecast was "quiet 30-reservoir interval 52 ms -> about 10 ms". The
measured result is 4.4 ms under the pool, better than forecast, because A1's carried step size
turned out to collapse the quiet interval to a *single* substep rather than the forecast three.

### (c) B3 (block-structured or fill-aware LU): what is the realistic ceiling, and is it worth doing before WP6 and WP7?

**Recommendation: do not do B3 next. Move it behind WP6 and WP7, and reconsider it only as part of
transient handling (A5) if the cold-start tail ever becomes a gameplay problem.**

The measured ceiling in the steady state is small in absolute terms and irrelevant against the
budget. B3 targets the factorization kernel, and there are **zero** Newton factorizations in 2400
production intervals; the entire numeric-LU bucket is 0.60% of worker CPU (2 samples of 331). What
remains is the triangular solve, at 50.45% of worker CPU or 27.2% of job wall depending on which
clock you take, and the four transport factorizations per interval at 0.018 ms. A block LU would
give block forward/back substitution as well, so take the optimistic reading and assume it could
halve the solve: that is 3.28 -> 2.4 ms on p50 and 5.88 -> 4.4 ms on p95 by the counters, or
3.28 -> 2.5 and 5.88 -> 4.4 by JFR. A *perfect* B3 that made all linear algebra free would give
p50 1.6-2.4 ms and p95 2.9-4.3 ms. Against a 2000 ms wall budget that moves p95 from 0.29% of
budget to 0.22%. The fill ratio the review measured (2.9x on the ladder, 1.6x on the chain) is
unchanged and confirms the same thing from the other side: there is no fill to recover.

Where B3 would still pay is the transient regime, and only there. HEAD's warm-up window still
charges 38.82% of worker CPU to numeric LU and 57.84% to linear algebra overall, with p95 of 1000 ms
and seven jobs within 30 ms of the 2 s wall. A block LU three times faster in the factorization
kernel would take roughly 26% off transient cost: warm-up p95 1000 -> ~740 ms, max 1971 -> ~1460 ms.
That is worth having eventually, but it is a cold-start improvement, not a steady-state one, and
the cheaper A5 levers have not been exhausted.

WP6 and WP7 rank ahead of it on the same measurement:

- **WP6 (kernel consolidation + C3 + B4 analytic node blocks)** now owns the largest single item in
  the profile that is not a triangular solve. The residual and its properties are 37.2% of quiet
  worker CPU (PR evaluate 11.5%, decode/assembly/`state()` 13.6%, viscosity 7.9%, temperature terms
  3.6%) and 33.1% of warm-up CPU, and `temperatureTerms` alone is **43.55% of all remaining
  allocation**. C3 attacks exactly that allocation; the consolidated kernel attacks the evaluate
  bucket in both regimes. B4's analytic blocks matter only when Jacobians are built, which is now
  only on transients - but that is the same regime B3 targets, and B4 removes 191 residual
  evaluations per build rather than making the factorization of that build cheaper.
- **WP7 (trace truncation)** shrinks the node block 47 -> 41, which is about 13% off the solve
  dimension and therefore off the one bucket that still dominates the quiet window, plus a
  proportional cut in the vapour loops inside the residual. It helps both regimes and it composes
  with everything else.

There is also one unfinished piece of B2 worth more than B3 per unit of effort:
`ConservativeTransport` still builds four fresh RCM orderings, four fresh factorizations and four
fresh EJML storages per interval (`luStorages` = 4.000 per interval). It is only 0.018 ms of
factorization today, but reconstruction as a whole is 0.541 ms of a 3.50 ms job by the counters, and
caching the transport ordering and structure per island is the item the review already wrote down
under B2.

### (d) Transient tail (A5): how many jobs hit the wall or were held, and is the ledger-impact criterion needed?

**In the measured production window, none: zero jobs at the wall, zero held, zero rejected substeps,
every interval a single accepted attempt.** The dominant error term over 2400 attempts was the state
term 2385 times and the pipe term 15 times, and all 2400 attempts were accepted. So in steady state
the pipe flow-error term - the thing that was the entire step budget on transients - never forces a
refinement any more, because the carried step size never has to rediscover itself.

The transient tail has not disappeared, it has moved into the cold-start phase, and the benchmark's
warm-up records it:

| warm-up (60 s, 100 cold islands) | 154007d | HEAD |
|---|---:|---:|
| jobs | 890 | 1260 |
| held | 271 | **33** |
| worker ms p50 / p95 / max | 53.0 / 1709.9 / 2000.1 | 4.2 / 1000.3 / 1971.5 |
| jobs >= 1900 ms | 21 | **7** |
| substeps p50 / p95 / max | 3 / 45 / 55 | 1 / 46.7 / 59 |
| rejected substeps, total / worst job | 961 / 18 | 1366 / 18 |

Reading: `RetainedSolver.COLD_START_SECONDS` = 0.05 already covers the part A5 was written for - the
repeated re-payment of the start at every interval boundary. That is why the warm-up p50 is 4.2 ms
rather than 53 ms and why held intervals fell from 271 to 33. What it does not cover is the genuine
first solve of a cold island, which still needs up to 59 substeps with 18 rejections and still
reaches 1.97 s; seven such jobs came within 30 ms of the wall. Those are not step-restart artefacts,
they are the real transient, and they are where the pipe term still governs.

**The review's optional ledger-impact flow criterion is not needed now, and should not be adopted
yet.** It trades ledger accuracy for speed, it is only reachable on cold starts, no production
interval was held or approximate, and the cheaper options in front of it are untouched: the warm-up
transient is 57.8% linear algebra, so B3, C3/WP6 and WP7 all attack it without weakening the flow
integral that `checkConservation` bounds. Revisit the criterion only if a realistic
player-driven-change workload - not a 100-island cold boot - is shown to hold intervals.

One caveat on attribution: the diagnostics are process-wide sums and carry no island identity, so
they cannot say which island produced a wall-hitting job. The per-job attribution in the tables
above comes from `report.json` `samples` (island id, substeps, rejected substeps, worker nanos) and
`reservoirsPerIsland`, which is sufficient; adding island identity to the counters was not needed
and was not done.

### (e) RAM: allocation per interval and the humongous share; what remains?

Allocation per accepted 5 s interval is **4.47 MiB**, down from 122.8 MiB on the same-session
baseline (27.5x); the rate is 0.0873 GiB/s, down from 3.22 GiB/s (36.9x). The humongous-allocation
share is **zero**: no collection in the measurement window was caused by `G1 Humongous Allocation`,
against 214 of 393 on the baseline, and `DMatrixSparseCSC.growMaxLength` fell from 13.57% of all
allocation to **0.32%**. B2's storage reuse did what it was meant to do; the EJML L/U regrowth that
forced 54% of the baseline's collections no longer happens because the retained storage is never
regrown.

What remains, as a share of the 4.47 MiB:

- **`TranslatedPengRobinson.temperatureTerms`, 43.55%** (1.95 MiB per interval, 158 calls). This is
  C3, untouched, and it is now the single largest allocation site by a factor of four. Three n x n
  matrices per distinct temperature; the per-node exact-temperature cache from C1/C4 reuses them
  within a node but the coupled solve still rebuilds them per node per temperature.
- 9.39% `PipeTransfer` / `ConservativeTransport` / `PipeResistance` records, the largest single
  chain being `PipeTransfer$Stream.lambda$copy$0` at 2.56% - a stream-based copy in the transport
  path that C2 did not reach.
- 6.85% `TranslatedPengRobinson.evaluate` / `normalize` / `logPhi` / partial volumes, and 6.49%
  `PhaseLayout` (2.20 pp of it `encode`), both of which the section-6 consolidation targets.
- 4.26% `Equations.residual` / `states`, 2.97% `WaterRegion1.evaluate`, 1.93%
  `PassiveNetwork$Inventory.moles` (a defensive clone per accessor call), 1.57% `MixtureViscosity`
  (mostly `Prepared.<init>`, the C4 bundle, which is the half-kilobyte per prepared temperature that
  C4 knowingly bought).
- 1.19% `SparseLuSolver$Storage`: four fresh EJML storages per interval, all on the transport side.
- ~4% JDK containers and streams, of which `java.util.IdentityHashMap$KeyIterator` is 2.00% of all
  allocation - something iterates an identity map per job and is worth a look during WP6.
- Defensive record clones, the baseline's 17.03%, are down to 0.43%.

On the live set: it cannot be read from this run. With five young collections and no mixed cycle,
HEAD's after-GC minimum of 1530 MiB is uncollected warm-up debris, not live data. The baseline's
372 MiB minimum over 393 collections remains the best estimate of the live set of 100 islands plus
the server, and nothing in WP1-WP3 changes retained state except the per-island retained solver,
which adds one factorization storage and one pattern per island. The review's suggestion of a
`jcmd <pid> GC.class_histogram` after a forced GC at the end of the fixture is still the right way
to pin it, and is still not implemented.

---

## 6. What this measurement does not say

- The stress100 fixture reaches a quiescent steady state. Its islands are isolated ladders with no
  external disturbance, so after warm-up nothing perturbs them and A1's carried step grows to the
  full interval. A production world with player-driven topology and flow changes will re-enter the
  transient regime, where the profile is the warm-up column of section 3, not the measured one.
  Everything said about B3 being low priority is a statement about the steady state plus the
  observation that no interval was held; it is not a claim that transients are free.
- 331 worker samples is a thin profile. The large conclusions (factorization gone, solves dominant,
  water path gone) are all multi-sigma, but no single-percent figure in HEAD's measured column
  should be quoted on its own.
- Absolute CPU seconds are not claimed, for the 15.6 ms thread-clock reason in section 3. Only
  wall-time region totals, within-window sample fractions, and between-run sample ratios are used.
- `process-memory.csv` (process working set / private bytes) was not collected: it needs a separate
  external sampler that the benchmark does not launch. Only JVM heap and JFR data are reported.
- The baseline run carries no `solverDiagnostics` block, because the property does not exist at
  154007d. Counter comparisons against the baseline are therefore JFR-based only.
