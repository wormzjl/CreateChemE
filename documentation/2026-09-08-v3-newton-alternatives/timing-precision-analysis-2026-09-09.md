# Timing weak spots and precision tradeoffs — 9 September 2026

The strongest improvement opportunity is repeated banded linear algebra, especially its sparse-row data structures and work repeated during final verification. The measured public C/D calculations spend approximately 85–89% of worker execution samples inside the banded solver, including calls nested in verification. Only about 2–3% of samples contain thermodynamic frames. Relaxing the existing closure setting is not a reliable general speed control: large relaxations usually add time while returning essentially the same products.

This is an opt-in investigation on `codex/plan-2-solver-experiments`, following the [Plan 2 experiments](plan-2-experiment-results-2026-09-09.md). ChatGPT 6 Pro planned the bounded measurements and reviewed the intermediate profile. Production behavior, acceptance gates, and the user's existing source changes are preserved. Run instructions are in [the benchmark README](../../benchmarks/solver-alternatives/README.md).

## What was measured

The public workload calls the actual calculator on current TJL19 cases A–E: dry base; cooled side draws; steam with three cooled sections and side draws; concentrated 40 MW cooling; and dry side draws. Every timed call includes all initialization, continuation, retries, correction, audits and publication work. Its terminal Newton count is not a whole-calculation work count. A default accepted result is created outside timing as a numerical reference under the same thermo model; it is not independent physical truth.

These measurements run in a standalone numerical JVM. They do not measure Minecraft client ticks, networking, UI latency or application-level cached-result reuse.

The first unprofiled JVM compares closure `1e-8` (default), `1e-6`, and stress controls `1e-4`/`1e-3`, with one warmup and three measured serial repetitions per policy and rotated ordering. The second JVM confirms default versus `1e-6`. All optional trace cutoffs remain zero; the existing always-on support floor remains active. Java is 21.0.11 with a 2 GB maximum heap. Raw timings, CPU time, worker allocation, process GC counts, diagnostics and public streams are retained. No timing assertion is placed in CI.

Separate JFR recordings profile two default calls each for C and D after reference setup. JFR attribution is sampled, not a stopwatch partition; requested sample period is 2 ms. Inclusive dimensions overlap. The classifier gives final verification precedence over its nested Jacobian and LU work. Profiling times are excluded from the speed comparison, and these separate JVM runs do not isolate profiling overhead. See [Oracle's sampling caveat](https://docs.oracle.com/en/java/javase/21/troubleshoot/troubleshoot-performance-issues-using-jfr.html) and [JFR event settings](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.jfr/jdk/jfr/EventSettings.html).

## Dominant costs

| Worker execution samples | C, 751 samples | D, 1,106 samples |
|---|---:|---:|
| Banded LU, inclusive of verification | 84.6% | 89.4% |
| Banded LU, excluding verification | 56.5% | 69.9% |
| Final verification, including nested work | 32.0% | 21.1% |
| Jacobian construction, excluding verification | 3.5% | 4.3% |
| Thermodynamic frames, inclusive | 2.8% | 2.4% |

These rows must not be added together. No audit sample was captured; that does not mean the audits cost nothing. Both recorded calculations passed their fresh audits. The [profile JSON](../../build/reports/solver-alternatives/timing/profile.json) also retains allocation-site estimates and method stacks.

The LU rows use `TreeMap<Integer, Double>` in [V3BandedPivotedSolver](../../src/main/java/com/wormzjl/createcheme/science/column/v3/linalg/V3BandedPivotedSolver.java). Repeated lookup, insertion/update, boxing and row iteration account for substantial sampled work. `SparseRows.put` appears in 311 C samples and 471 D samples. This identifies data-structure overhead within linear algebra; it is not evidence that floating-point arithmetic alone dominates.

Representative profiled calls allocate 30.1 GB (C) and 51.9 GB (D) on the worker thread. These are cumulative allocations, not simultaneously live memory. Corresponding wall/CPU times are 10.38/10.22 s and 16.33/16.11 s. Process GC collection time is only 83/112 ms in those calls, so “mostly GC pauses” would be the wrong diagnosis. Allocation-site sample weights are estimates and do not reconcile exactly with per-call allocation counters; they locate candidates rather than assign exact byte totals to functions.

The timed fixed-problem kernel replays complement this profile. On a near-converged wet snapshot, one residual costs roughly 0.070 ms, a fine Jacobian 16.1 ms, band conversion 0.342 ms, and a banded solve 15.5 ms in the first JVM. The Jacobian and LU allocate approximately 39.5 MB and 45.0 MB respectively. These costs apply to this snapshot and cannot be multiplied by terminal iteration counts to reconstruct the public path. The public path includes different stage counts, local-block steps and failed intermediate attempts.

## Closure versus delivered accuracy

The existing authored closure changes residual stopping, log-flow correction allowance, and specified closure-aware audit limits. It leaves the final temperature-step allowance `1e-6 K + 1e-9*T` and linear backward-error limit `1e-12` unchanged. Consequently, a looser residual test can send more intermediate states into expensive verification without making their certifying steps small enough.

The verifier constructs a fresh fine Jacobian and performs a direct solve, potentially followed by eight damped linear solves. If none produces a qualifying candidate, it returns without moving the current state, and the ordinary solver path continues. That path does not automatically reuse the unsuccessful verification's work. Closure can also change intermediate continuation seeds. These are credible mechanisms for nonmonotone runtime; the default-only C/D profiles do not establish which mechanism caused each individual public slowdown.

| Case | Default median, JVM 1 / JVM 2 | `1e-6` time change, JVM 1 / JVM 2 | `1e-4` / `1e-3` time change, JVM 1 only |
|---|---:|---:|---:|
| A | 3.351 / 3.126 s | -0.5% / -1.4% | -1.8% / +0.6% |
| B | 6.466 / 5.853 s | -1.1% / +0.5% | +49.2% / +106.4% |
| C | 9.642 / 8.833 s | -2.0% / -6.6% | +34.1% / +70.6% |
| D | 15.974 / 15.345 s | +6.9% / +4.8% | +24.4% / +30.2% |
| E | 5.264 / 4.861 s | +9.1% / +10.4% | +30.6% / +57.8% |

Negative percentages mean faster. Each entry is a median of three serial measurements, not a tail-latency statistic. The stress controls have one JVM of evidence only. The modest C benefit repeats, but the D/E penalty also repeats; small A/B differences do not establish a useful gain. In the first JVM, stress closure `1e-3` increased B from 6.47 s to 13.35 s and C from 9.64 s to 16.45 s. See the preserved [sweep](../../build/reports/solver-alternatives/timing/closure-sweep.json), [confirmation](../../build/reports/solver-alternatives/timing/closure-confirmation.json) and [derived summary](../../build/reports/solver-alternatives/timing/analysis-summary.json).

The proposed minor-error screen is maximum product-temperature difference 0.01 K, relative computed molar/mass-flow difference `1e-4`, maximum absolute product mole-fraction difference `1e-5`, and condenser-duty difference at most `max(1 W, 1e-4*abs(referenceDuty))`. All 90 measured public outputs are accepted and pass this screen. Maximum observed changes across both JVMs are only `5.12e-12 K`, `2.96e-14` relative molar flow, `3.75e-14` relative mass flow, `2.60e-14` absolute mole fraction and `1.54e-6 W` condenser duty. Maximum hydrocarbon component allocation changes are `8.22e-12 mol/s` and `9.17e-14` of the corresponding feed-component flow. The tested closure changes therefore produce almost no delivered precision sacrifice in these cases, despite sometimes substantial extra cost.

This is a screening definition for the experiment, not a production guarantee. Stream/component identities and observed phase presence match. All-stage accuracy is tested only on inner-state fixtures; public results expose product summaries, so intermediate wet-set identity and all-stage error are not independently qualified by this comparison. Trace-component allocation is assessed in absolute mol/s and relative to its feed allocation, not by dividing by an almost-zero product trace.

An energy-row tolerance is not a product-error bound. For these roughly 725 mol/s feeds, the row energy scale is about 72.5 MW: `1e-6` corresponds to about 72.5 W and `1e-3` to about 72.5 kW per row at the stopping threshold. Those are allowed thresholds, not observed defects or output errors.

## Small optimization checks

Workspace reuse returns one solve-local scratch object through the existing factory, with fresh separate audit workspaces. The qualification compares residuals after intervening valid and rejected invalid trials, every initial Jacobian entry, final state bits, evidence and audit. Both JVMs preserve numerical results exactly but reduce allocation by less than 1%: roughly 343.76 to 341.23 MB for wet and 52.37 to 52.09 MB for Holland. Wet median time increases 0.9%/5.6%; Holland changes +1.4%/-1.3%. There is no measured reproducible speed gain. The [inner confirmation](../../build/reports/solver-alternatives/timing/inner-kernels-confirmation.json) retains these checks.

The flash candidate preserves the current flash code except for precomputing `exp(logK)`/`expm1(logK)` within a Rachford–Rice solve and stopping when a computed midpoint equals a floating-point bracket endpoint. It retains the 100-step cap, finite checks, phase classification and flash convergence tolerances. A generated class keeps the rest synchronized with current source. The final qualification passes 200 synthetic root vectors, ten recorded Wilson vectors, and ten complete flashes with bitwise equal phase, compositions and enthalpy, covering liquid, vapor and two-phase outcomes. The first batch had nine full fixtures; the confirmation adds an all-vapor endpoint and correctly labels the high-temperature fixture, which is actually two-phase.

The feed flash improves from 851 to 138 microseconds in the first JVM (30-call batches), and 773 to 76.6 microseconds in the second (200-call batches). The converged liquid-endpoint test improves from 201 to 59.1 and 158 to 55.9 microseconds. The larger batch improves warmup confidence, but these are still microbenchmarks. The [flash confirmation](../../build/reports/solver-alternatives/timing/flash-confirmation.json) establishes a promising precision-preserving kernel change, not an end-to-end column gain. Pure endpoint shortcuts that never enter the RR loop do not have that work to save.

Modest inner-closure measurements are case-dependent. Wet closure `1e-5` reduces fine iterations from three to two and medians from 133.2 to 100.4 ms, then 120.0 to 88.9 ms: 24.6%/25.9% faster. It still passes the default audit and differs by less than `1e-12 K`. The same closure changes Holland from 16.2 to 130.8 ms, then 15.5 to 132.8 ms: 8.1/8.6 times slower. It retains two iterations and essentially identical output, and expands allocation from 52.4 to 481.9 MB. Its residual first meets the relaxed threshold one iteration earlier, leaving about 125 ms of subsequent correction work in a representative first-JVM run. This supports the early-verification explanation on that fixed problem without claiming an exact verifier invocation count. These are fixed-problem restart measurements, not public cold-calculation speedups.

Starting the final wet state again with unavailable evidence takes 523/537 ms and 1.90 GB of allocation to obtain a new certificate at iteration zero. This is a separate certification-entry replay, not the original solve's approximately 33 ms terminal tail.

An additional [untimed replay of the actual private verification routines](../../build/reports/solver-alternatives/timing/verification-gates-qualified.json) identifies the direct candidate's rejection condition on that state. The native maximum residual improves from `2.9088e-14` to `2.8422e-14`. The actual step/backward-error certificate passes, and a fresh default audit passes. Yet the original verifier rejects the candidate solely because squared merit increases from `6.394e-27` to `7.081e-27`. Evaluating candidate merit with the base state's frozen scales gives essentially the same value; changed scaling does not explain this example. The physical state changes by only `7.39e-13 K` and at most `3.25e-11 mol/s` in a component flow. The actual verifier method is invoked to confirm this classification; no acceptance condition is changed and no candidate is published. The analogous Holland direct candidate passes all gates.

The near-terminal snapshots distinguish two other situations. Wet's first state below `1e-5` yields a qualifying direct correction at that authored closure, explaining the saved iteration. Its log-flow change is `1.06e-6`, so it **does not have a default `1e-8` certificate**, despite passing a separately computed default physical audit. Holland's early direct correction passes residual and merit but fails step evidence: log-flow change `1.93e-5` exceeds its `1e-5` closure, and the temperature-step ratio is about 99. A roundoff-only merit allowance would not fix that rejection.

Interestingly, that rejected Holland candidate already differs from its independent oracle by only `6.72e-10 K` and `1.38e-10` relative component flow. This illustrates that correction size and actual solution error are different. It motivates a separate future study of coordinated step tolerances if a minor accuracy concession is desired; it does not validate a new temperature/log-flow threshold from one fixture.

This is concrete evidence of roundoff-level merit sensitivity on one fixed state. It does not attribute every later damping trial or prove how much public C/D time a revised rule would save. The existing trace API does not expose those exact counts.

## Improvement priorities

1. **Reduce sparse-row overhead and repeated linear algebra.** Investigate primitive row storage and reuse of already computed verification work when the same state/problem/scales continue into an ordinary correction. Neither inherently requires less numerical precision. They require a separate implementation and equivalence/regression comparison; this batch measures the opportunity without replacing the linear solver.
2. **Test roundoff-aware final verification.** This is the most concrete candidate for a very small numerical concession: tolerate a bounded noise-scale merit increase after native residual, actual correction evidence and fresh physical audit meet their requirements. The direct replay identifies the blocking gate, but no replacement rule or speedup has been tested. Do not use a loose global merit allowance or remove the certificate. First define an absolute/relative numerical floor and test it on near-root, genuinely nonconverged, phase-negative and support-boundary cases.
3. **Keep the flash micro-optimization in perspective.** It is promising for flash-heavy workloads and passed exact comparisons here. It is secondary for these LU-dominated full columns.
4. **Avoid one global relaxed closure recommendation.** The measured benefit and penalty depend on the case. A reproducible wet-restart improvement cannot be generalized to the public continuation chain or Holland.

The final focused checks pass 298 existing research assertions plus map/thermal self-checks. Across two JVMs, 90 measured public calls pass acceptance and output screening; 60 measured inner attempts pass their selected-closure audit, with workspace equivalence and independent Holland qualification retained. Flash qualification passes the 210 root-vector checks and ten complete flash comparisons above. One initial workspace test expected a raw argument exception; the PR model wraps it in a typed `DOMAIN` exception. The harness was corrected to recognize only that expected domain rejection, then rerun successfully. No numerical failure was hidden.

All 243 original Java/resource files match the starting snapshot, with no files added in that comparison set; [the verification manifest](../../build/reports/solver-alternatives/timing/verification-manifest.json) records provenance. Source changes are confined to benchmark support and reporting; no new production tolerance or solver is selected. ChatGPT 6 Pro completed its final review and returned `C2C DONE` for iteration 2, confirming the measurements, direct-gate attribution and bounded recommendations with no further correction required.
