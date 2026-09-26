# V3 cold core benchmark results

Execution date: 2026-09-05. **The default benchmark execution is complete**, including screening, serial outcome confirmations, isolated timing, slowdown review, and separate profiles.

The candidate uses **33.3% less elapsed time** across the 12-case isolated timing panel, with one additional accepted 64-tray case and no repeatable baseline-success loss. This is a useful performance improvement; overall convergence remains limited at 27 of 64 cases.

Subsequent [convergence analysis](D:/Minecraft/Modding/1.21/CreateChemE/documentation/2026-09-05-v3-solver-core-review/V3_CONVERGENCE_ANALYSIS.md) replayed the baseline `N64-on` case with a separate 120-second allowance: main also succeeded, in 83.3 seconds. The original 60-second result remains valid, but the sole new acceptance is a deadline-performance gain, not evidence of a newly reachable input.

A later [structural review](D:/Minecraft/Modding/1.21/CreateChemE/documentation/2026-09-05-v3-solver-core-review/V3_FUNDAMENTAL_SOLVER_REDESIGN.md) corrected the size-panel pressure interpretation: the resolver's total drop is `(N-1) × stagePressureDrop`, so its executed inputs did not maintain the constant span originally claimed. This does not change paired comparisons on identical inputs; cross-size conclusions must account for the varying pressure span. Frozen inputs and raw results remain unchanged.

Baseline is `main` at `bdfeff1b0a42d477d49acb1a269fdff1c478171f`. Candidate is the frozen, uncommitted `codex/v3-solver-core-fixes` working tree based on that commit. Three GPT-5.6 Terra subagents, all using high reasoning, implemented and reviewed the worker, process supervisor, and analysis. Numerical execution uses local JVMs.

The [benchmark plan](D:/Minecraft/Modding/1.21/CreateChemE/documentation/2026-09-05-v3-solver-core-review/V3_COLD_CORE_BENCHMARK_PLAN.md) defines the 64-case default suite, acceptance checks, deadlines, confirmation rules, and timing selection. The optional historical replay and trace-wall panels are outside this execution.

## Accepted outcomes

After required serial confirmations, the baseline accepts **26/64 (40.6%)**, and the candidate accepts **27/64 (42.2%)**. There are 26 shared successes, one repeatable candidate-only deadline win, zero baseline-success losses, and 37 shared non-acceptances. These are solutions accepted by the current solver contract within 60 seconds, not independently proven thermodynamic roots.

| Panel | Cases | Main accepted | Candidate accepted |
| --- | ---: | ---: | ---: |
| Factorial operating screen | 36 | 12 | 12 |
| Pressure boundary, 99/100/101 kPa | 12 | 0 | 0 |
| Column size | 10 | 8 | 9 |
| Performance controls | 6 | 6 | 6 |
| **Total** | **64** | **26** | **27** |

| Steam / cutoff | Cases | Main accepted | Candidate accepted |
| --- | ---: | ---: | ---: |
| Dry / off | 19 | 9 | 9 |
| Dry / `1e-6` | 19 | 9 | 10 |
| Wet / off | 13 | 4 | 4 |
| Wet / `1e-6` | 13 | 4 | 4 |

The parallel screen initially accepted 26 cases on each revision. All three cells requiring serial review were retained and rechecked:

- `F02-wet-on`: three serial pairs all returned `API_FAILURE/NONCONVERGENCE`; the original baseline parallel deadline remains in the raw journal.
- `N64-off`: both revisions reached the 60-second deadline in the serial pair.
- `N64-on`: all three main calls reached the deadline; all three candidate calls returned `SUCCESS_REDUCED` in 33.93, 34.43, and 34.20 seconds. Each also met the 45-second calculator-latency flag.

For `N64-on`, the candidate removes 236 of 990 component-stage points, including 34 closure-pruned points. The resulting liquid-only-condenser problem has 1,565 unknowns and equations. Its hydrocarbon loss is approximately 0.00143418 mol/s, or 1.98 ppm of feed, below the existing 8 ppm truncation budget. No untruncated fallback is used. Both graph pruning and linear-work changes are present, so this result does not isolate an individual fix's contribution.

## Isolated performance

All **72 planned calls completed successfully: 12 cases × three pairs × two revisions**. Revision order alternates by case and round, with saved shuffled case orders. The journal confirms a maximum of one active timing solve and no unmatched starts. All **36 matched case/repetition pairs have identical output fingerprints**, and all 72 native acceptance audits pass.

The equal-weight geometric mean of per-case paired candidate/main ratios is **0.6673588349**, or **33.264% less elapsed time**. All 12 median times improve, ranging from 28.4% to 42.6%. No case reaches the predeclared 10% slowdown threshold, so the slowdown phase completes with zero additional pairs. These medians and paired geometric means are distinct summaries; the aggregate is not an average of the displayed median percentages.

These are three paired repetitions on one host/JDK and a predetermined subset of shared successes. They do not establish statistical significance, performance on other hardware, or latency for failed cases. The 64-tray candidate-only win has no matched baseline solved time and is excluded from this speed aggregate.

| Case | Main median, s | Candidate median, s | Median time reduction | Paired geometric B/A |
| --- | ---: | ---: | ---: | ---: |
| `A150-quarter-off` | 7.177 | 4.729 | 34.1% | 0.6675 |
| `A150-quarter-on` | 6.425 | 4.228 | 34.2% | 0.6569 |
| `A100-quarter-off` | 11.141 | 7.130 | 36.0% | 0.6557 |
| `A100-quarter-on` | 9.614 | 6.264 | 34.8% | 0.6691 |
| `A250-wet-slowdown-off` | 8.787 | 6.044 | 31.2% | 0.6857 |
| `A250-wet-slowdown-on` | 15.535 | 11.118 | 28.4% | 0.7066 |
| `F09-wet-off` | 12.415 | 8.268 | 33.4% | 0.6620 |
| `F09-wet-on` | 12.271 | 8.159 | 33.5% | 0.6979 |
| `F03-dry-off` | 17.213 | 9.882 | 42.6% | 0.5969 |
| `F03-dry-on` | 31.019 | 19.876 | 35.9% | 0.6315 |
| `F05-dry-off` | 6.080 | 4.332 | 28.7% | 0.7022 |
| `F05-dry-on` | 5.858 | 4.087 | 30.2% | 0.6845 |

Using the same paired, equal-case weighting, calculator-thread CPU time falls **33.4%**, and calculator-thread allocated bytes fall **16.5%**. These allocation counters measure cumulative object churn, not live heap or peak process memory; they exclude other threads and native allocations. GC deltas remain in each raw record. All timing calls on both revisions complete within 45 seconds (observed range 3.80–31.99 seconds); this is a calculator-call flag, not an end-to-end in-game guarantee.

Failure cost remains separate. In the parallel screen, API-failure median latency is 31.67 seconds on main (35 calls) and 20.59 seconds on candidate (36 calls), with different case populations and contention. These figures are diagnostic costs, not speedup evidence. The isolated `F02-wet-on` failure median is 39.63 seconds on main versus 25.23 seconds on candidate, but neither returns an accepted answer. Serial deadlines remain approximately 60 seconds. Full per-status costs are in [final-report-metrics.json](D:/Minecraft/Modding/1.21/CreateChemE/build/v3-cold-core-run-20260905-01/final-report-metrics.json).

## Output and numerical guards

All 26 shared successful screen cases have identical output fingerprints and no stream-flow, component-flow, temperature, phase, or stream-identity review flags. All 52 screen successes pass native acceptance and external hydrocarbon/water closure checks. Positive-cutoff cases are checked against the native sink-edge loss audit where exposed, and in all cases against external closure and the existing loss budget; exact component closure is not imposed on legitimately removed material.

Screen routes match on both revisions: 13 exact successes, seven native reduced successes, six successful untruncated fallbacks, and zero identity-support successes. The confirmed `N64-on` win adds one native reduced case to the candidate's final accepted set. Failed and deadline-limited calls remain in the 64-case denominator.

Cutoff-on/off comparison is a separate approximation check. Of 26 jointly accepted revision/case pairs, only `F05-dry` exceeds the predeclared flow-drift review threshold, identically on both revisions: maximum component-flow change 0.001303 mol/s and total-stream change 0.001404 mol/s, versus the 0.0007252 mol/s review threshold. Its temperature change is 0.000649 K, with no phase or stream-identity change. These audited truncation differences are not candidate-versus-main regressions.

Frozen numerical guard suites pass **46/46 on main and 55/55 on candidate**, including the separate Holland oracle guard. The full project suite passes **354 tests with zero failures, errors, or skips**. The oracle guard is separate from the cold crude success count.

The existing original-versus-regularized Newton-certificate limitation remains. A saved manufactured residual of `1e-9` with zero derivative yields a native `Converged` final-correction claim on both revisions. This is not an out-of-tolerance physical-column failure, but it prevents treating the native final-step field as an independent original-system certificate. `rawNewtonCertificateVerified` remains null. Request-wide iteration/residual/linear-solve counts are also unavailable; recorded terminal-attempt iterations are not aggregate request work.

## Separate profiles and next targets

Four serial JFR recordings cover `A150-quarter-off` and `N30-on` on both revisions. All four calls pass native acceptance and hydrocarbon/water closure. A150 retains all 480 points; N30-on retains 371 and removes 109 on both revisions, without fallback. The first capture attempt could not create JFR's default Windows temporary repository. Its failed journal is preserved; the successful retry uses profile-owned repository/temp directories. No profile or failed-capture observation enters the primary performance aggregate.

For A150, 144 of 380 main-thread execution samples on main include `TreeMap.getEntry`, versus eight of 244 on candidate. `SparseRows.columnMaximum` appears in 15/380 main stacks and 0/244 candidate stacks. These observations are consistent with removing repeated column lookups, but are not an ablation isolating each patch. Sparse LU remains prominent: `V3BandedPivotedSolver.solve` appears in 157/244 candidate main stacks. The shorter N30 recordings contain only 88 and 54 main-thread samples, so their proportions are especially coarse.

The profiles also expose substantial boxing and Jacobian local-term allocation. Raw JFR allocation weights contain large first-event values spanning recording boundaries and cannot be treated as per-call totals. The saved analysis preserves those weights and separately labels a view excluding each thread's first event; this qualified view remains sampled evidence. The worker's exact calling-thread deltas supply the quantitative allocation comparison. JFR's recording window also includes request handling and result serialization around the calculator.

These results support retaining the three focused production fixes. Further work should examine remaining sparse-LU/map overhead and local Jacobian allocations, while treating side-draw convergence, continuation failures, and the raw Newton certificate as separate correctness/design work. Adding more recovery layers is not supported by this benchmark alone. The [profile summary](D:/Minecraft/Modding/1.21/CreateChemE/build/v3-cold-core-run-20260905-01/jfr-profile-20260905-02/profile-summary.md) links the recorded counts to explicit sampling limits; its [JSON](D:/Minecraft/Modding/1.21/CreateChemE/build/v3-cold-core-run-20260905-01/jfr-profile-20260905-02/profile-summary.json) and all four JFR files are retained beside it.

## Remaining failure routes

The final outcome set contains 32 `NONCONVERGENCE` cases and four `INVALID_INPUT` cases on each revision. Main also has two deadlines; candidate has one. The four invalid-input cells per revision (`F01-wet` and `F02-dry`, each cutoff off/on) expose the diagnostic `solvePath` contract defect: composed paths can exceed 128 characters, and the exception handler replaces the real result with an `input` failure path. The original wording calling these blank solver paths was inaccurate. Their underlying numerical outcomes are unavailable; they remain failures in the user-visible denominator.

Reported nonlinear failure routes are iteration-budget exhaustion with side-draw diagnostics and stalled pressure continuation. For P99/P100 dry cases, the continuation anchor fails before the requested operating point. P101 records attempts at the requested 30-stage problem. Neither route proves physical infeasibility. The raw API summaries and path history are retained, and no failed input has a fabricated terminal support or state.

## Execution and provenance

The host exposes 16 logical processors and approximately 47.6 GiB RAM. Available memory at dispatch was 28.75 GiB, so the specified reserve and per-process budget selected **10 parallel screen JVMs, five per revision**, below the combined cap of 12. The 128 measured screen calls completed in approximately 6 minutes 25 seconds. JVM processes have isolated numerical state, bounded deadlines, owned lifecycle, and a single journal writer.

Screen flags: `-Xms128m -Xmx1536m -XX:ActiveProcessorCount=1 -XX:+UseSerialGC`. Serial confirmation/timing flags: `-Xms512m -Xmx2g -XX:ActiveProcessorCount=16 -XX:+UseG1GC`. All use Zulu 25.0.4, compiled with `--release 21`. The whole public calculator call has a 60-second deadline and a 75-second external watchdog after the call-start signal. Screen times are not used for isolated speed claims.

All measured revisions were compiled from separate frozen source/resource copies. No numerical source or manifest was modified during measurement. The supervisor was corrected between phases for bounded cancellation and adaptive three-pair outcome confirmation; prior versions are retained. An additional confirmation invocation completed the newly required `N64-on` pairs before timing selection. All original observations are preserved.

The final integrity check matches **205/205 baseline and 207/207 candidate source/resource hashes**, and all **132 current production files** match the frozen candidate. The workspace manifest's status/report/run-ID metadata was updated only after execution; all numerical cases and benchmark rules still match the immutable run manifest. Analyzer versions and corrections are hash-recorded. Seven supervisor checks and the analyzer self-test pass. `jcmd -l` reports no remaining benchmark-worker JVMs.

The primary journal contains **128 screen calls, 14 serial confirmations, 72 timing calls, four separate pilot calls, and 54 excluded warmups**. No calls were cancelled, left unrun, watchdog-killed, or classified as harness/resource failures in these phases. The optional panels remain unrun. Process peak RSS was not measured, so available-memory checks and absence of resource failures are not peak-memory guarantees.

Local run directory: [v3-cold-core-run-20260905-01](D:/Minecraft/Modding/1.21/CreateChemE/build/v3-cold-core-run-20260905-01).

- [Run metadata and provenance](D:/Minecraft/Modding/1.21/CreateChemE/build/v3-cold-core-run-20260905-01/run.json)
- [Append-only samples, full streams, audits, and failures](D:/Minecraft/Modding/1.21/CreateChemE/build/v3-cold-core-run-20260905-01/samples.jsonl)
- [Machine-readable comparison](D:/Minecraft/Modding/1.21/CreateChemE/build/v3-cold-core-run-20260905-01/comparison.json)
- [Full case table](D:/Minecraft/Modding/1.21/CreateChemE/build/v3-cold-core-run-20260905-01/report.md)
- [Frozen manifest](D:/Minecraft/Modding/1.21/CreateChemE/build/v3-cold-core-run-20260905-01/manifest.json)
- [Candidate patch](D:/Minecraft/Modding/1.21/CreateChemE/build/v3-cold-core-run-20260905-01/candidate.patch)
- [Final source, harness, and execution integrity](D:/Minecraft/Modding/1.21/CreateChemE/build/v3-cold-core-run-20260905-01/final-integrity.json)

Source hashes, harness versions, separate classpaths, certificate probes, and numerical-guard logs are retained in the same run directory. Documentation and build outputs follow this repository's existing local-artifact ignore rules.
