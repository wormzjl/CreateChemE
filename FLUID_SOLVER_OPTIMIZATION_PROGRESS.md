# Fluid solver optimization progress

Work packages from `documentation/FLUID_NETWORK_IMPLEMENTATION_REVIEW.md` section 7, measured with the
saved-island replay harness (`gradlew fluidSolverRegression`, `FluidSolverRegressionTest`) and the
opt-in `science/fluid/diagnostics/SolverDiagnostics` counters. Branch `claude/fluid-solver-optimization`.

## Method

- Fixtures: quiescent island 11312 (30 reservoirs / 45 pipes) and 11324 (18 / 27) from the RAM-run
  snapshot `build/probe/core.dat`, five consecutive 5 s intervals each with the accepted graph fed
  forward; the same island 11312 cold (`committedTick=0`) from `build/probe/core-fallback.dat`, one
  interval; the 100-reservoir cosine-pressure chain of `FluidNetworkBenchmarkTest`, one interval.
- The quiet fixtures replay two discarded warm-up intervals first, so the recorded wall times are not
  a JIT transient. Snapshots are build output and are not committed: the harness skips a fixture whose
  file is absent. Override the location with `-PfluidRegressionSnapshot=<dir or core.dat>`.
- Every measured interval runs with `SolverDiagnostics.ENABLED=true` on the solving thread. With the
  counters off each hook is one volatile boolean read; with them on the instrumented sites are
  low-frequency (Jacobian build, factorization, triangular solve batch, reconstruction, ordering), so
  the recorded wall time is the production wall time within run-to-run noise.
- Allocation is `com.sun.management.ThreadMXBean.getCurrentThreadAllocatedBytes()` on the solving thread.
- Reference states live in `src/test/resources/fluid/regression/` and were captured on 154007d before
  any solver change (`-PfluidRegressionCapture=true`). Each fixture declares EXACT (bitwise on
  temperature, pressure, mass, volume, phase volumes, component totals, average flows and substep
  counts) or RELATIVE; `-PfluidRegressionMode=exact|relative` overrides the declaration for a run.
- Wall time on this host has a run-to-run spread of roughly ±25% on the quiet fixtures; the counters
  are exact and are the primary evidence.

## Baseline: 154007d + WP0 diagnostics (diagnostics off changes nothing)

| fixture | wall ms | substeps acc/rej | implicit solves | Jacobian builds | LU factorizations | residual evaluations | allocated MB |
|---|---:|---:|---:|---:|---:|---:|---:|
| quiet 11312, mean of 5 intervals | 81.9 | 3 / 0 | 10 | 1.6 | 1.6 | 324 | 88.8 |
| quiet 11324, mean of 5 intervals | 41.4 | 3 / 0 | 10 | 1.6 | 1.6 | 323 | 53.2 |
| cold 11312, one interval | 2036 | 51 / 15 | 199 | 54 | 54 | 11391 | 2897 |
| 100-reservoir chain, one interval | 3945 | 47 / 31 | 235 | 31 | 31 | 5776 | 6991 |

Per-interval detail, quiet 11312: the interval alternates between one and two Jacobian builds
(207 and 401 residual evaluations), which is the same 1.2-2.0 spread the probe measured. Every
interval starts at h = 1 s and accepts 1, 2, 2 s. `flashTP` is never called, LU refinement never
triggers (0 of 204-6256 backward-error checks per interval), and the pipe flow term is the only
rejection reason on both transient fixtures.

## Work packages

| # | commit | change | result |
|---|---|---|---|
| WP0 | (this commit) | solver diagnostics + saved-island regression harness + baselines | full gate green, references captured |
