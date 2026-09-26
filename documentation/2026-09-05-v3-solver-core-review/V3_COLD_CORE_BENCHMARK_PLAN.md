# V3 cold core benchmark v1

Designed 2026-09-05 for **`codex/v3-solver-core-fixes`**, based on **`main` at `bdfeff1`**. The **64-case default suite has now been executed** against frozen revisions, including required serial confirmations and the isolated timing panel. See the [execution results](D:/Minecraft/Modding/1.21/CreateChemE/documentation/2026-09-05-v3-solver-core-review/V3_COLD_CORE_BENCHMARK_RESULTS.md). The specification below preserves the predeclared method; optional panels remain unrun.

**Post-run correction:** source review found that the resolver shares pressure between condenser/tray 1 and last tray/reboiler. The actual total span is `(N-1) × stagePressureDrop`, so the original `(N+1)` size-panel construction did not keep it constant. The immutable inputs/results are retained; the corrected interpretation is recorded below and in the [structural review](D:/Minecraft/Modding/1.21/CreateChemE/documentation/2026-09-05-v3-solver-core-review/V3_FUNDAMENTAL_SOLVER_REDESIGN.md).

The benchmark answers three separate questions: whether the candidate returns more accepted solutions, whether it reduces total cold-call work, and whether enabled truncation actually produces a useful reduced solution. It also checks reported products and the existing physical audits. It does not treat agreement with main as independent proof of thermodynamic accuracy.

## Why the historical matrix needs adjustment

The seven quantitative rows in [V3_COLD_DOE_BENCHMARK.md](D:/Minecraft/Modding/1.21/CreateChemE/documentation/2026-09-01-v3-full-cdu-draw-wall/V3_COLD_DOE_BENCHMARK.md) all satisfy `coded_pressure + coded_temperature + coded_loading = 0`. Their three main-effect columns have rank **2**, or rank **3** after adding an intercept. Consequently, that seven-row block cannot identify all three main effects. The later pressure/interaction augmentations add coverage, and the recorded paired outcomes remain useful regression observations; the defect is in treating the initial block as a clean definitive screening design.

Use eight high/low combinations plus a center point instead. A three-factor, two-level full factorial has eight combinations, while a center supplies an aggregate check away from the corners. This follows the basic [NIST factorial design](https://www.itl.nist.gov/div898/handbook/pri/section3/pri333.htm) and [center-point guidance](https://www.itl.nist.gov/div898/handbook/pri/section3/pri337.htm). The center alone cannot separate the three individual quadratic effects. Analyze the solver's discrete outcomes descriptively; do not turn deterministic reruns into independent evidence for statistical significance.

The replacement quantitative matrix has rank **3**, or **4** with an intercept. The manifest records these checks. Historical replay remains a separately labelled optional regression panel.

## Frozen revisions and inputs

Baseline A is `main` at `bdfeff1`. Candidate B is the current `codex/v3-solver-core-fixes` working tree. Because B is uncommitted, `HEAD` alone is insufficient provenance: preserve its complete source/required-resource hashes, binary patch, and harness hash before compiling. Build A and B into separate clean output directories from immutable source snapshots. Do not measure whichever files happen to be present under a moving branch name.

The expanded [case manifest](D:/Minecraft/Modding/1.21/CreateChemE/src/test/resources/v3-benchmarks/v3-cold-core-v1.json) is authoritative. Kelvin and SI flow values in that file take precedence over rounded display values in this document.

Common 30-tray settings are the original Tia Juana Light fixture:

- Registered package `createcheme:cdu17_tjl_acs2018`, assay `createcheme:tia_juana_light`, expected dataset revision `cdu17-tjl-kl1976-r2`.
- Hydrocarbon feed `2610.7 / 3.6 = 725.1944444444443 mol/s`, at 638.15 K; feed tray 24.
- Condenser is node 0, trays are 1–30, and the reboiler is node 31. Pressure drop is 750 Pa between successive trays; the condenser shares tray 1 pressure and the reboiler shares tray 30 pressure. The total span is 21,750 Pa.
- Organic reflux ratio 2; reboiler duty 8 MW.
- For loading fraction `d`, total draw is `d × hydrocarbon feed`, distributed over trays 8/15/22 in the ratio 496:653:149. Steam is excluded from the loading denominator.
- Steam off means no water feed. Steam on means exactly 8 mol/s at 450 K into the sump, node `N+1`.
- Cutoff is either exactly 0 or `1e-6`. The positive value exercises the new reachability code; cutoff 0 bypasses it.

Resolve the ordered assay components and full feed-flow vector once before measurement, save them with the run, and verify that both revisions use the same dataset. Do not infer the active component count from the package name.

## Default matrix: 64 unique cases per revision

| Panel | Construction | Cases |
| --- | --- | ---: |
| Operating screen | 8 factorial corners + center, crossed with steam off/on and cutoff off/on | 36 |
| Pressure boundary | 99/100/101 kPa at 75 °C and 22.5% draws, same four combinations | 12 |
| Column size | Five tray counts, dry and without draws, cutoff off/on | 10 |
| Performance controls | Previously measured quarter-draw cases and the historical steam slowdown control | 6 |
| **Default suite** | **Same 64 inputs/cutoffs on A and B** | **128 measured calls in the initial screen** |

The nine operating points are:

| Row | Pressure, kPa | Condenser, °C | Total draw/feed |
| --- | ---: | ---: | ---: |
| F01 | 60 | 50 | 5% |
| F02 | 60 | 50 | 40% |
| F03 | 60 | 100 | 5% |
| F04 | 60 | 100 | 40% |
| F05 | 250 | 50 | 5% |
| F06 | 250 | 50 | 40% |
| F07 | 250 | 100 | 5% |
| F08 | 250 | 100 | 40% |
| F09 | 155 | 75 | 22.5% |

Each row has four cases: dry/off, dry/on, wet/off, wet/on. The 32 corner cases cross all five two-level factors: pressure, temperature, loading, steam, and cutoff. Report the steam/cutoff groups separately as well as the overall comparison.

The 99/100/101 kPa panel specifically crosses the calculator's **inclusive `<=100 kPa` pressure-continuation trigger**. Do not substitute the admission lane's `<100 kPa` label for that actual routing condition.

Column-size controls use 250 kPa, 50 °C, no side draws, no steam, the same feed and duty, and both cutoffs:

| Trays N | Feed tray | Drop between trays, Pa | Actual condenser-to-reboiler drop |
| --- | ---: | ---: | ---: |
| 4 | 3 | 4650 | 13,950 Pa |
| 8 | 6 | 2583.3333333333335 | 18,083.33 Pa |
| 15 | 12 | 1453.125 | 20,343.75 Pa |
| 30 | 24 | 750 | 21,750 Pa |
| 64 | 51 | 357.6923076923077 | 22,534.62 Pa |

Feed tray is `floor(0.8 N)`; the executed pressure-drop input is `23250 / (N+1)`. The intended constant-span claim was incorrect: the implementation produces `(N-1) × drop`. Changing tray count changes pressure span, the physical separation problem, and continuation route, so this is a size-robustness panel, not a direct measurement of asymptotic algorithmic complexity. A new manifest version should use `targetSpan / (N-1)` and save resolved node pressures if a constant span is desired. The no-draw cases avoid forced retention along draw supply paths masking the support change.

The six performance controls are:

| Control | Pressure | Condenser | Draws | Steam | Cutoffs |
| --- | ---: | ---: | --- | --- | --- |
| Quarter-draw A150 | 150 kPa | **400 K = 126.85 °C** | `0.25 × (496,653,149) / 3.6 mol/s` | off | 0, `1e-6` |
| Quarter-draw A100 | 100 kPa | **400 K = 126.85 °C** | same exact flows | off | 0, `1e-6` |
| Historical slowdown | 250 kPa | 50 °C | 22.5% of hydrocarbon feed | 8 mol/s | 0, `1e-6` |

The quarter-draw loading is about 12.43%, not 22.5%, and the 400 K condenser is outside the primary screen's 50–100 °C range. These are explicit controls, not factorial rows. The steam slowdown control is needed because the new factorial corners and center do not otherwise include it.

## Optional panels and mechanism checks

`historical_replay` reproduces the 36 documented operating/cutoff-off cells under fresh builds. Nine already exist in the default suite, so enabling it adds **27 unique cases**, reaching **91**. The manifest maps every historical index to its actual case ID; shared cases run once. Old reported counts are background context, never the new baseline data.

`trace_walls` adds **6 cases**: 60 kPa, condenser 85/90/95 °C, 22.5% draws, dry, cutoff 0/`1e-6`. Both optional panels plus default total **97 unique cases per revision**. These target previously observed trace-related difficulty, but the main-based solver may stop at an earlier intermediate problem. Record the observed route rather than assuming the old W5 mechanism repeats.

Run the existing support, essential-small-coefficient, and LU scaling regressions separately. Keep the known final-certificate counterexample as an explicit outstanding diagnostic; its presence must not be concealed by the aggregate accepted-solution count. Run the existing Holland independent-oracle benchmark separately as an accuracy guard. Its oracle-seeded solve must not be counted as a cold crude convergence result.

## Parallel screening on the 16-core host

Use **up to 12 concurrent JVM workers in total**, split into six A workers and six B workers at the default size. Each worker performs one CPU solve at a time. This is a combined limit, not 12 workers per revision. The host currently reports 16 logical processors, 47.6 GiB total RAM, and approximately 30.7 GiB available RAM; availability must be checked again before execution.

Screen-worker settings are `-Xms128m -Xmx1536m -XX:ActiveProcessorCount=1 -XX:+UseSerialGC`, identical for A and B. Budget approximately 2 GiB per process, including native/JIT overhead, and reserve 6 GiB for the host. Start with `min(12, floor((availableGiB - 6)/2))`, rounding down to an even count for equal revision pools. Fall back to serial execution when two workers do not fit. The estimate is not a measured peak-RSS guarantee: reduce concurrency if observed worker memory exceeds it. The large 64-tray controls must be pilot-checked for memory limits before the full screen.

The coordinator owns the workers, the finite pending-case list, deadlines, and one result writer. Dispatch only to idle workers; avoid nested pools and unbounded submission. No numerical state, mutable workspace, accepted seed, or previous solve result is shared across requests. Immutable property data and JIT-compiled classes may remain loaded. A fixed set of platform threads can supervise process I/O; virtual threads do not add CPU capacity to the solves.

Warm each worker with the same three manifest-listed controls before measured work. Use a saved shuffle with seed 20260905 and interleave revisions and cutoff settings. Record the actual dispatch and completion order, worker IDs, flags, and concurrency. All screen elapsed times are retained as diagnostics but **excluded from isolated performance claims**.

One 60-second cooperative deadline covers the entire public call, including continuation, audits, and untruncated fallback. Start the clock immediately before `V3ColumnCalculator.calculate(input, control, cutoff)` and stop it on return; do not reset it for individual attempts. A supervisor watchdog at 75 seconds after a worker's `CALL_STARTED` signal terminates an unresponsive owned worker. Queue/startup time is separate. Record late returns and actual elapsed time; a success arriving after 60 seconds is `LATE_SUCCESS`, not a timely accepted result.

Every parallel timeout, watchdog termination, memory-limit event, or A/B outcome change requires an **idle-machine serial confirmation on both revisions**. Keep the original parallel observation and the confirmation as separate records. Until confirmation, a resource-limited parallel failure is inconclusive for scientific regression. Identical ordinary typed failures need not all be repeated. A changed accepted solution or failure should receive three serial paired runs before being classified as repeatable or unstable.

On cancellation, stop dispatching, cancel active requests cooperatively, then terminate only owned workers after bounded grace. Record unstarted cases as `NOT_RUN`, never as nonconvergence. Drain/read outputs and reap every process. A crashed worker's unfinished case receives a terminal harness outcome before the worker is replaced; bound repeated restarts and stop the run if resource failure persists. Do not write results concurrently to one shared JSON file.

Short independent regression classes and input validation may also run in parallel. Keep tests with wall-time assertions and the serial timing phase away from concurrent numerical workloads.

## Isolated timing and profiling

After screening and required confirmations, stop the screening pool. Use two warmed revision workers with identical `-Xms512m -Xmx2g -XX:ActiveProcessorCount=16 -XX:+UseG1GC` settings, with **only one active solve across both**. These flags match the intended isolated measurement configuration; they differ from screening flags, which is another reason not to mix the two timing populations.

Time at most **12 shared-success cases** initially. The manifest prioritizes the six performance controls, the four factorial centers, and the two 64-tray controls. Skip those that do not succeed on both revisions and fill unused slots deterministically from remaining shared successes in manifest order. Freeze and report that selected list before confirmation timings. Candidate-only successes count as convergence wins and get reported latency, but cannot yield a matched solved-latency speedup.

Collect three fresh cold-call pairs per selected case. Alternate revision order AB/BA/AB, reversing the starting order for the next case. Shuffle case order between rounds with a saved seed. Preserve every sample; do not discard slow valid calls or replace failures with a nominal timeout duration.

Report each revision's median elapsed time and the paired time ratios per case. For a suite summary, weight each selected case equally using a geometric mean of candidate/baseline time ratios. Report failure latency, deadline outcomes, and time-to-accepted-result separately; a fast failure is not a speedup.

An apparent per-case median slowdown of at least 10% triggers **five additional alternating pairs**; show both the original three and all eight pairs. The 10% threshold is a review rule, not a statistical significance claim. The initial performance target is a 10% or greater aggregate reduction without a repeatable baseline-success loss or an unexplained confirmed slowdown. Improving only a diagonal-matrix microbenchmark is insufficient.

JFR and detailed allocation profiling run afterward on selected cases. Basic thread CPU and allocated-byte counters may be collected consistently on both versions, with unsupported values recorded as null; identify their scope. Thread allocation excludes other threads/native memory, while process CPU includes JVM work. Record GC deltas and process peak memory where available. Do not mix profiled and unprofiled elapsed samples.

Also report whether an **isolated** accepted call completed within 45 seconds, the current default configured solver deadline. This is a calculator-latency flag, not an end-to-end in-game guarantee: queueing, network, rendering, and server contention are excluded.

## Outcome and correctness records

The headline success is **accepted by the current solver contract within the deadline**. Store the raw API result independently of the harness classification. Every native audit check and final-step field must remain available; the existing regularized-certificate limitation means `hasFinalNewtonStep=true` is not independent verification of the original Newton equations. Report `rawNewtonCertificateVerified=null` unless a separately specified original-system check actually ran.

Classify timely successful results by their actual terminal support and route:

- `SUCCESS_EXACT`: requested cutoff 0.
- `SUCCESS_REDUCED`: positive cutoff, nonidentity returned support, and no untruncated retry.
- `SUCCESS_IDENTITY`: positive cutoff, identity returned support, no untruncated retry.
- `SUCCESS_FALLBACK`: positive cutoff and the public untruncated retry completed successfully.

Keep typed API failures, cooperative deadlines, `LATE_SUCCESS`, watchdog kills, resource limits, harness exceptions, cancellations, and `NOT_RUN` distinct. A failure of the 150 kPa anchor must not be described as demonstrated infeasibility at the requested low pressure. The bounded event history may not expose all internal attempts; absent details remain unknown. Do not silently discard the existing diagnostic-path-length `INVALID_INPUT` issue from user-visible failure totals.

Record requested cutoff, returned support cutoff/counts/note, native reduced success versus fallback, final condenser branch, unknown/equation counts, removed points, and closure-pruned points where the success result exposes them. Failures do not expose a resolved terminal problem/state, so corresponding fields are null. Preserve original path and event strings even when a derived classification is added.

The current initializer-iteration, residual-evaluation, and linear-solve diagnostic counters are hardcoded to zero. Store the raw diagnostics, but expose request-wide values as **unavailable/null**. Label the available Newton count `terminalAttemptNewtonIterations`; do not use it as total request work. First-chain reflective replays or richer instrumentation belong in separate untimed investigations.

For every success, save every product stream by stable ID, including component mole flows `stream total × mole fraction`, temperature, phase, and total/mass flow. Include overhead water and any free-water product. Reconstruct incoming water separately from the hydrocarbon feed; never compare wet total products only to dry feed, and never renormalize away missing material.

For cutoff 0, check external hydrocarbon component closure using a bound derived from the existing material-residual scale: for component i, `(N+2) × 1e-8 × max(F_i, F × 1e-12)`, plus a documented floating-point summation allowance. Record water closure separately. For positive cutoff, compare reconstructed hydrocarbon loss with the fresh sink-edge truncation audit, allowing the same summed residual/roundoff allowance, and retain its existing `8 × cutoff` feed-mole loss budget. Record all energy/equilibrium/water/phase audit results without relaxing their native limits.

Compare A/B only at the same exact input and cutoff. Record bit-identical outputs when present. Predetermine review flags for stream or component-flow drift above `1e-6 × F`, temperature drift above 0.01 K, or changes in product/phase identity. These are review thresholds, not proof that a different audited root is invalid. Compare cutoff-on/off separately as approximation sensitivity. The crude screen has no independent experimental truth; the separate Holland oracle guard serves a different accuracy purpose.

## Deliverables, implementation boundary, and budgets

Each run should retain `run.json` provenance, append-only `samples.jsonl`, full successful stream records, a comparison JSON, and a Markdown report with panel-wise paired outcomes, confirmed regressions/wins, support/fallback rates, latency distributions, and output differences. Include manifest/input/assay/source/harness hashes, JDK/JVM details, actual worker count, deadlines, interrupted/not-run cells, and all serial confirmations. Failed and timed-out cases remain in convergence denominators; not-run cases are disclosed separately and invalidate a complete-suite claim.

Use a dedicated same-package test worker compiled from `src/test/java` and the manifest under `src/test/resources`; do not add benchmark logic to production. The build contains JavaExec benchmark task declarations whose old main classes are missing, and remaining legacy probe sources reference those missing classes. Execution now uses `V3ColdCoreBenchmarkWorker` plus the Python supervisor and analyzer described in [scripts/README.md](D:/Minecraft/Modding/1.21/CreateChemE/tools/v3-cold-core-benchmark/README.md); it does not rely on the legacy Gradle benchmark tasks.

At 12 workers, six per revision, the default first screen needs about eleven case slots per worker plus three warmup slots. If every call consumes its 60-second allowance, that is roughly **14 minutes of worker time on the longest queue**, or about **17.5 minutes at the 75-second watchdog bound**, plus startup/compilation/I/O. These are scheduling bounds, not a measured runtime promise. Fewer memory-permitted workers increase that time. Required serial rechecks may dominate if many parallel cases hit deadlines.

The initial timing panel adds at most 72 measured serial calls, plus six timing-worker warmups: up to 78 minutes of nominal 60-second call budgets. Each confirmed-slowdown investigation adds ten calls (five pairs). Optional panels and serial outcome confirmations are reported as separate work, not hidden in the first-screen estimate.

## Design validation performed

The manifest contains **64 default cases**, **36 historical references with 9 reused default cases**, **6 optional trace cases**, and **97 unique cases in the extended suite**. Its IDs, suite membership, draw loading, input construction, declared property envelopes, and reflux-compatible structural ledgers were validated against the currently compiled system: **97 cases, 62 distinct physical inputs, 194 valid two-phase/liquid-only branch ledgers**. Vapor-only condensers are correctly excluded from this structural preflight because these fixtures prescribe positive reflux; runtime phase selection remains the calculator's responsibility.

At the design-validation checkpoint, no nonlinear solve or new benchmark timing had been executed. The [validation report](D:/Minecraft/Modding/1.21/CreateChemE/build/cold-core-design/validation.json) and [validator](D:/Minecraft/Modding/1.21/CreateChemE/build/solver-audit/BenchmarkDesignValidator.java) are local build artifacts. The earlier 353-test pass and three-case speed measurements belong to the implementation checkpoint; the subsequent [64-case execution report](D:/Minecraft/Modding/1.21/CreateChemE/documentation/2026-09-05-v3-solver-core-review/V3_COLD_CORE_BENCHMARK_RESULTS.md) records the new observations and 354-test guard result. The frozen run manifest retains its original design-status metadata; only the workspace manifest's execution-status fields were updated after measurement.
