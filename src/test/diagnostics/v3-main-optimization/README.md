# Flash and verification experiment on updated main

Base: `db46e8894f49651362bb73355bf126ddfcf638a0`, branch `codex/flash-verification-main`.
Measured on 2026-09-09. The earlier TreeMap-LU profile does not describe this base.

## Flash

`V3FeedFlash` computes `exp(logK)` and `expm1(logK)` once per Rachford–Rice
root solve, using two scratch arrays allocated once per two-phase flash. It
stops bisection at an unchanged binary64 midpoint, after checking that the
residual is finite. It retains the 100-step cap, endpoint arithmetic, inactive
component handling, 64 flash iterations, convergence limits, and workspace
arrays used by truncation/reference validation.

The frozen `db46e88` implementation is retained only in test sources. Tests
compare 1,022 root vectors, including extreme/nonfinite values and zero support,
and 30 complete CDU17/TJL19 flashes across temperature and pressure. They compare
successful outputs exactly and require matching failures. The existing endpoint,
truncation, invalid-domain and cancellation tests also pass.

The initial full suite passed: 514 tests, zero failures/errors/skips.

Two separate JVMs per version each perform two warmups then five measurements
for each A–E case and the public Holland benchmark. Inputs come from the current
closure fixtures: A/B/D/E use CDU17 and C uses wet TJL19. The timer covers the
calculator (including its acceptance audit), excluding fixture construction and
JSON serialization. Holland uses its dedicated public calculator. All 120
recorded results match per case across versions and repetitions, including
streams, duties, solve paths, convergence evidence, and audit values.

Median wall time in milliseconds (five samples per cell):

| Case | Base JVM 1 | Base JVM 2 | Flash JVM 1 | Flash JVM 2 |
|---|---:|---:|---:|---:|
| A | 222.45 | 237.38 | 215.69 | 219.90 |
| B | 879.57 | 843.31 | 861.65 | 864.85 |
| C | 1035.67 | 904.19 | 958.41 | 945.66 |
| D | 1003.04 | 856.51 | 760.88 | 848.11 |
| E | 604.54 | 508.79 | 475.78 | 534.31 |
| Holland | 41.02 | 42.51 | 42.71 | 48.39 |

These serial stage runs have visible warmup/process variation. They support
modest end-to-end impact, not a uniform percentage speedup. Holland does not
use this PR flash; its variation is a useful timing control.

An alternating-batch microbenchmark compares the complete reference and optimized
flash kernels at 638.15 K and 267,250 Pa with reused caller workspaces. Four
warmup batches precede ten measured batches, each of 100 calls per version.
CDU17 takes approximately 616–622 versus 53–55 microseconds per call; TJL19 takes
approximately 776–886 versus 69–88 microseconds. The local improvement is about
11×. It does not imply an 11× column-calculation improvement. The optimized
two-phase call adds two small arrays; it does not allocate them per iteration.

Reproduce with `gradlew v3MainOptimizationProbe` and
`gradlew v3FlashOptimizationProbe`. Set `optimizationReport`,
`optimizationWarmup`, and `optimizationSamples` Gradle properties as needed.
Raw local measurements are under `build/reports/main-optimization/`; the flash
microbenchmark output is `build/main-optimization-flash-kernel.log`.

## Final-verification merit

The branch retains a small direct-verification experiment. Ordinary non-increasing
merit still qualifies. A direct fresh correction may also qualify if **both** base
and candidate native scaled residual maxima are at most `min(1e-12, requested
closure)`. This exception applies only to the direct final-verification correction.
Regularized verification, line search, continuation budgets, requested closure,
fresh fine Jacobian, actual coordinate-step evidence, backward error, and the
independent publication audit retain their existing requirements.

This is a fixed near-root allowance. Its row-wise bound is explicit; it is not a
proof that every admitted merit increase comes from floating-point roundoff.

An isolated diagnostic copy of the current solver observes its actual candidate
gate without changing the decision. Across A–E/Holland it records 149 candidates
before the change and 148 after it. In the normal wet C run, one direct candidate
was rejected solely for merit growth: native residual maximum `2.1887e-14` to
`6.4103e-14`, squared merit `8.1065e-27` to `1.7925e-26`. Its actual fresh step
evidence and recomputed independent audit pass. The allowance accepts this
candidate and avoids subsequent work.

Instrumenting all five `V3BandedPivotedSolver.solve` call sites inside the
simultaneous solver gives the following whole-calculation counts. These exclude
linear solves owned by other classes and are **untimed** diagnostic runs.

| Case | Before | After |
|---|---:|---:|
| A | 97 | 97 |
| B | 173 | 173 |
| C | 153 | 151 |
| D | 244 | 244 |
| E | 136 | 136 |
| Holland | 3 | 3 |

All 60 combined-build public measurements pass the native closure, fresh step
certificate and independent audit. A/B/D/E/Holland match the flash-only outputs
exactly. C preserves its solve path and all stream identities/phases; maximum
differences versus main across its ten measurements are:

| Published quantity | Maximum difference |
|---|---:|
| Stream temperature | 5.69e-13 K |
| Molar flow | 3.11e-15 relative |
| Mass flow | 3.78e-15 relative |
| Mole fraction | 1.76e-14 absolute |
| Mass fraction | 1.91e-14 absolute |
| Condenser duty | 1.49e-7 W (about 1.64e-15 relative) |

The first two combined-build timing runs had slower timings even in unchanged
cases, so they do not establish the concession's performance. A separate
alternating comparison compiled each solver version into the same isolated
classpath with **instrumentation disabled**. Four serial JVMs (old/new/old/new)
each use five warmups and ten measurements for A, C, and Holland. All 120 outcomes
match their corresponding previously measured version exactly.

| Case | Old 1 ms | New 1 ms | Old 2 ms | New 2 ms |
|---|---:|---:|---:|---:|
| A | 256.77 | 260.88 | 255.70 | 255.68 |
| C | 966.71 | 958.13 | 967.05 | 920.75 |
| Holland | 27.75 | 27.43 | 27.20 | 25.68 |

C's median measured allocation falls from 1,250,452,784 bytes to 1,204,037,072
and 1,227,144,176 bytes in the two new runs: about 23–46 MB, or 1.9–3.7%.
Its observed median timing reduction is about 0.9–4.8%; the control variation and
small number of JVM forks limit that estimate. The strongest evidence is the two
avoided solver calls and bounded, independently audited output difference.
This is a modest targeted optimization, not a general solver speedup.

Final validation: **516 tests passed**, zero failures/errors/skips, including
the independent Holland benchmark, phase-invalid numerical-root rejection,
closure contracts, and new tests immediately above/below the near-root ceiling,
tighter requested tolerance, nonfinite values, regularized corrections, and each
individual correction-evidence gate. `node src/test/diagnostics/v3-main-optimization/compare.cjs`
checks all recorded public outputs and writes `analysis-summary.json`.

The reproducible observation/isolated-compilation script is
`src/test/diagnostics/v3-main-optimization/verification.init.gradle`. Run it with Gradle's `-I`
option and task `v3VerificationDiagnostic`. For the old solver, first extract
`db46e88:src/main/java/com/wormzjl/createcheme/science/column/v3/V3SimultaneousColumnSolver.java`
to a local file and supply it as `verificationSource`; `verificationReport`
selects the output JSON. `v3ControlledComparison` uses the same source override,
`verificationObserve=false`, and an `optimizationReport` path. These generated
classes stay outside the production artifact. Both original probes use a 2 GB
maximum Java heap; the project configures the Java 21 toolchain.

## Review status

ChatGPT 6 Pro supplied a conditional qualification plan. Its existing chat could
discover the replacement connector's nine actions but could not invoke them,
reporting a disabled/stale binding even after authorization, action refresh and
explicit replacement selection. It therefore **did not independently review or
approve this branch's code or results**. Local implementation and validation are
complete; the C2C review checkpoint remains blocked on that binding.
