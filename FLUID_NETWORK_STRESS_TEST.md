# 100-network CPU stress test

## Latest stress characterization (before class consolidation) — 2026-09-16

Luna reran `luna-stress100-12cap-r01` on production artifact `8dae23fe56e0139ec0b4d00c0931fddea208bd9708c896553695057578338d3a`, automatic workers, 60 s warmup + 120 s measurement, with JFR enabled. All 100 networks / 1,993 reservoirs advanced with fixture chunks unloaded; allocated/active/outstanding maxima were 12. Component/energy errors were `3.9744e-7` / `1.8586e-9` tolerance units. Runtime audit passed with zero errors. These establish integrity and bounded concurrency.

**Startup/recovery remains an unresolved performance weakness.** The run recorded 291 warmup holds and 273 measured holds, zero approximation, and final debt median/p95/max 2.0/4.5/4.5 s. Useful equivalent throughput was 28.7188 five-second intervals/s, including catch-up; it is not a steady-state speedup claim. Engine p95/max were 1.9872/18.3643 ms/tick.

The observed final 1,200-tick window still had 28 holds; queue-inclusive latency p95/max were 113.975/155.477 **seconds**, and engine p95/max were 1.2688/5.6292 ms/tick. The final 400 ticks had zero holds and latency p95 298.20 ms, but that shorter window cannot replace whole-run or 1,200-tick evidence. The ordinary performance gates did not pass; `stressIntegrityPassed` is a separate conservation/advance/unloaded-chunk check.

Both this and the historical long run below had JFR enabled. This recording reports mean JVM user/system CPU of 42.6332%/1.8207% and mean machine total CPU of 78.9905%; host activity outside the benchmark JVM is a comparability concern. No causal explanation for the higher holds is established by these measurements. Exact raw report/audit hashes, GC event counts, hold timing and limitations are in `FLUID_LUNA_TEST_REVIEW.md`. Older results below retain their original artifacts and are not substituted for this outcome.

## Purpose

Measure the production simulation engine under many independent, nontrivial hydraulic solves. Identify wasted CPU capacity, deadline/retry behavior, queue growth, starvation and server-thread cost. The scheduler and worker-allocation changes belong to normal gameplay code, not this fixture.

User-selected CPU policy: **automatic demand-based allocation, up to 12 shared solver workers**. Explicit worker-count overrides remain available for controlled comparisons. Workers are JVM platform threads, not physical-core affinity assignments. Automatic sizing also respects the host's available processors minus two, with a minimum of one.

## Reproducible fixture

| Setting | Value |
|---|---|
| Seed | `2026091603` |
| Independent networks | Exactly 100; physical topology compilation verifies isolation |
| Reservoirs per network | `10 + (networkIndex * 13 % 21)`, covering 10–30 |
| Total finite reservoirs | 1,993, each 1 m³ |
| Physical pipes | 9,110, each 1 m long, 50 mm diameter, 45 µm roughness |
| Compiled hydraulic edges | 2,966 |
| Topology | Two series rails with reservoir-to-reservoir parallel rungs; odd reservoir counts add one reservoir on the inlet |
| Boundaries | One generator at 150,100 Pa and one void at 150,000 Pa per network |
| Initial state | 350 K, decreasing pressure along each rail, seeded ±2 Pa perturbations |
| Material basis | All 22 gameplay components: wet TJL crude plus 0.1 mol nitrogen and 0.2 mol water per mole of crude |
| Physics | TR-BDF2, pressure losses enabled, configured 100 m/s plus acoustic velocity cap, unchanged conservation/property/error gates |
| Cadence | Fixed 5 s for reproducible capacity characterization; deadline recovery may request shorter complete intervals |
| Worker wall deadline | 2 s; retries retain simulation debt |
| World/TE scope | Persistent physical registry and actual production world runtime; fixture chunks remain unloaded, verified at the end |

Conceptual layout (all R nodes own finite inventory):

```text
G ─ [optional R] ─ R ─ R ─ R ─ ... ─ R
                  │   │   │         │
                  R ─ R ─ R ─ ... ─ R ─ V
```

The fixture creates no solver executor. Minecraft's actual shared process service executes every job. GameTest supplies 20-TPS pacing, records diagnostics and checks final accounting. This separates engine multithreading from rendering/chunk-generation costs; it does not qualify a fully loaded 11,303-block world or client rendering.

## Runs and recorded metrics

Use a fresh run ID. Short diagnostic comparisons use 30 s warmup plus 60 s measurement. Follow-up characterization uses 60 s warmup plus 120 s measurement. These are **stress characterizations, not M9 qualification replicates**.

```powershell
.\gradlew.bat fluidServerBenchmark `
  -PfluidBenchmarkRunId=stress100-unique-run `
  -PfluidBenchmarkProfile=stress100 `
  -PfluidBenchmarkWorkers=0 `
  -PfluidStressWarmupSeconds=60 `
  -PfluidStressMeasurementSeconds=120 `
  -PfluidStressProfile=true
```

Run one JVM at a time. Do not run other CPU benchmarks or recompile measured classes during a run. Compare fixed 1/2/8 workers and automatic allocation where useful; retain failed baselines and artifact/fixture hashes. JFR profiling is optional and its presence must be recorded when comparing runs.

Reports live in `build/reports/fluid/M9/<run-id>/report.json`; profiles are `stress.jfr` beside the report.

Record:

- Completed job rate **and simulated seconds advanced per wall second**. A shorter retry is not counted as a full five-second interval when reporting useful throughput.
- Per-network completion counts, committed time and debt; every network must advance for a healthy result.
- Worker duration, readiness-to-publication latency, held/approximate results and substep rejections.
- Active workers, current allocated worker limit, outstanding/ready jobs and pending completions once per second.
- Component and energy accounting, including all generator/void transfers.
- Engine server-thread p50/p95/max cost, tick spacing, and unloaded-chunk verification.
- JFR process/machine CPU load and hot stacks. CPU totals from accepted outcomes alone undercount timed-out work; do not present that lower bound as total CPU utilization.

### Success criteria

1. No lost/duplicated components or energy; original BAL tolerances pass.
2. All 100 networks advance. No unbounded admitted queue or duplicate outstanding owner.
3. Automatic worker allocation never exceeds the configured ceiling, grows when independent demand appears, and shrinks after sustained lower demand without cancelling active work.
4. Under sustainable steady load, aggregate real-time ratio is at least 1 and debt remains bounded. Overload must be reported rather than hidden through skipped time or weaker accuracy.
5. Improved scheduling reduces idle capacity while independent work waits. Improved useful throughput must accompany CPU utilization; merely burning CPU on failed retries is not success.
6. Unit concurrency tests, scientific reference tests and dedicated GameTests remain passing.

## Verified production changes

### Original scheduler leaves available workers idle

The original coordinator admitted one group sized to currently free workers, then admitted nothing until every member completed or the group timed out. In the eight-worker baseline it averaged only 2.50 reported active workers despite persistent demand.

Production change: `IslandCoordinator` allows multiple bounded publication groups. Each retains its own completion barrier/deadline, while a free worker may begin an independent owner's job. One outstanding job per owner, owner-thread publication, stale-result rejection, per-tick dispatch/completion budgets and bounded staged results remain enforced.

### Static automatic sizing does not follow demand

The old automatic setting selected eight workers once at startup. Production changes in `ProcessSolveServices`, `BoundedCpuSolveService` and the consolidated `WorkerAllocation` adjust admission parallelism within one shared pool. Capacity grows immediately for independent ready work; lower demand must persist for ten online seconds before shrinking. Reducing capacity never cancels active jobs. User-selected automatic ceiling is 12 (`solver.automaticWorkerLimit`); `solver.workers=0` enables this policy.

### Repeated deadline failures can prevent any physical progress

The saved 30-reservoir/45-edge replay required about 2.13 s for its initial five-second interval even without competing jobs. Repeating that same interval from the same initial state under a two-second budget can waste all CPU indefinitely. Filling 14 workers in an intermediate diagnostic raised CPU use sharply but also raised failed attempts; it is retained as a failed experiment.

Production changes:

- Measured-error substep selection replaces repeated doubling/halving, with the original error acceptance test unchanged. The saved replay's five-second solve reduced error rejections from 32 to 15; standalone time changed from 2.13 s to 1.84 s. This is a diagnostic replay, not a paced-server claim.
- After a wall deadline, request a shorter **whole** interval on the next bounded retry (down to one game tick). Discard the failed attempt completely. Grow the retry span after full successes until the configured cadence is reached again. Committed stock, debt, fences, conservation and fallback-allowance rules are unchanged; a cost hint cannot extend approximate grace.

### Unnecessary server-thread work

Profiling exposed physical-registry snapshot validation when no topology event was pending and repeated serialization/hashing of immutable viscosity data during admission. Production now tests pending-event availability without constructing the snapshot, and computes the viscosity revision once per immutable model. Actual snapshots still validate their identities/positions; a new property model computes its own revision. No numerical/property tolerance was changed by either optimization.

## Baselines retained

| Run | Workers | Completed intervals/s | Mean active workers | Measured holds | Every network advanced? |
|---|---:|---:|---:|---:|---|
| `stress100-baseline-2w-r01` | 2 fixed | 0.867 | 1.62 | 16 | No |
| `stress100-baseline-auto-r01` | 8 old automatic | 2.964 | 2.50 | 54 | No |
| `stress100-demand-auto-r01` | 14 intermediate automatic | 4.197 | 14.00 | 406 | No |

All three conserved components and energy, but none is a healthy stress pass. They used full five-second requests, so their job rate equals five-second-equivalent interval rate. The last run predates the user's 12-worker ceiling and the shorter-interval recovery change.

## Current results

Final artifact SHA-256: `e4675976a69d6d40a2e24e2da70d501d275c7ccbb6e357b52e8321e71c635f67`.

**782 unit tests and all 12 required dedicated GameTests pass; build succeeds** (`build/fluid-stress-final-regression.log`). Deterministic concurrency checks cover growth/shrinkage without cancelled owners, queued-work completion, overlapping group barriers, independent deadlines, late results and shorter-whole-interval recovery. The full scientific suite retains accuracy, phase, conservation and velocity-clamp checks.

### Matched short comparison

Both `stress100-baseline-auto-r01` and `stress100-final-12cap-matched-r01` use the identical 100-network physical fixture, JFR profiling, 30 s warmup and 60 s measurement in fresh JVMs. The old automatic policy used eight workers; the final policy is demand-based with the user's twelve-worker ceiling. This compares the combined engine changes, not an isolated per-thread algorithm speedup.

Sol independently decoded both saved worlds' `createcheme_fluid_core.dat` topology records. All 11,303 active registrations and the constructed/destroyed material ledgers are canonically equal. Active-registry SHA-256: `fa440a8830e4dd9947d07bbbf8e08483ca737f473ec0877ce8e2607766c5055a`; constructed-ledger SHA-256: `12dfbe6f9943f14770d10f8ecef194a35b040cf4893bba3f64323a8ec9e12914`. Fixture class-bytecode hashes still differ because the diagnostic harness evolved; this audit establishes equal physical registries and constructed inventories, not byte-identical harnesses. See `FLUID_SOL_TEST_REVIEW.md`.

| Metric | Old automatic engine | Final engine |
|---|---:|---:|
| Useful five-second-equivalent intervals/s | 2.964 | 28.027 |
| Aggregate simulated-time / wall-time ratio | 0.148 | 1.401 |
| Every network advanced | No | Yes |
| Final p95 / maximum debt | 92.1 / 92.1 s | 4.35 / 4.35 s |
| Engine server p95 / maximum | 0.851 / 5.188 ms/tick | 1.604 / 11.389 ms/tick |
| Warmup deadline holds | 30 | 111 |
| Measured deadline holds | 54 | 132 |
| Measured approximate intervals | 0 | 0 |

Useful throughput improved **9.46× in this matched diagnostic**. The final engine makes more attempts, including failed shorter-interval retries, then catches up; higher raw failure counts are retained, not hidden. Its 1.401 aggregate ratio includes debt recovery and is not a claim that a continuously synchronized simulation should run ahead of wall time. All networks finish within the five-second cadence. Component/energy errors are `5.107e-7` / `5.531e-11` tolerance units, well below the pass threshold of one.

### Longer recovery observation

The earlier `stress100-improved-12cap-r01` used the same twelve-worker scheduling/retry policy before the final viscosity-hash cache: 60 s warmup and 120 s measurement. All networks advanced, useful throughput was 21.628 equivalent intervals/s, and maximum final debt was 4.9 s. It retained 222 warmup holds and 42 measured holds. In its last observed 1,200 ticks there were no held/approximate intervals and p95 readiness-to-publication was 155.4 ms. Its engine p95 was still 4.81 ms/tick, motivating the hash-cache optimization.

**Final-artifact longer run passed its integrity/recovery checks:** `stress100-final-12cap-long-r01`, 60 s warmup plus 120 s measurement. All 100 networks advanced and all fixture chunks remained unloaded. Useful throughput was **22.321 equivalent intervals/s**, aggregate real-time ratio **1.116**, final median/p95/maximum debt **2.15 / 4.65 / 4.65 s**. Allocated workers and outstanding jobs never exceeded **12**. Component/energy errors were `3.642e-7` / `1.105e-9` tolerance units.

The complete run retains **248 warmup holds and 13 measured holds**, with no approximate intervals. Whole-measurement engine p95 was **1.0647 ms/tick**, maximum **5.4387 ms/tick**. In the last observed **1,200 ticks**, 1,200 published intervals had **zero holds and zero approximations**; p95 readiness-to-publication was **132.48 ms** (maximum 190.57 ms), and engine p95 was **0.8632 ms/tick**. Whole-run results and the recovery window are both preserved; the tail does not erase startup misses.

`python examples/Fluid-Benchmarks.py stress` produces `build/reports/fluid/stress100-summary.json`, with per-network counts, useful throughput, debt, whole-run failures and an explicitly separate final-window view. It marks which runs match the current JAR. Older stress/ordinary reports cannot qualify the current artifact.

## Limits and remaining work

- Cold-start transients still cause two-second deadline holds. Recovery now advances conserved complete intervals instead of endlessly repeating the original request; this is not a zero-hold startup claim.
- These tests use fixed cadence to compare capacity. They do not close the separate aggregate-load cadence-adaptation qualification gap (P31).
- Chunk-independent engine operation is exercised here; loaded-TE/rendering load is a different test.
- Repeatability across additional hosts, the full ordinary M9 matrix and the 30-minute soak remain separate gates. A stress integrity pass does not close M9.
