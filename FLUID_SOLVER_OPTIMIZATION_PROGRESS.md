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

### WP0 - diagnostics and harness (15403b9)

Gate: 790 JUnit tests, 14 GameTests, green. References captured; no solver behaviour changed
(the diagnostics are off in the gate, and the baselines above were captured with them on, which
the quiet fixtures reproduce bitwise with them off).

### WP1-A2 - retain the interval solver per island across jobs

| fixture | wall ms | substeps | implicit solves | Jacobian builds | LU factorizations | residual evaluations | allocated MB |
|---|---:|---:|---:|---:|---:|---:|---:|
| quiet 11312, mean of 5 | 81.9 -> 44.5 | 3 / 0 unchanged | 10 -> 9.2 | 1.6 -> 0.4 | 1.6 -> 0.4 | 324 -> 92 | 88.8 -> 39.1 |
| quiet 11312, warm interval only | 81.9 -> 22.2 | 3 / 0 | 10 -> 9 | 1.6 -> 0 | 1.6 -> 0 | 324 -> 15 | 88.8 -> 22.9 |
| quiet 11324, mean of 5 | 41.4 -> 19.8 | 3 / 0 unchanged | 10 -> 9.2 | 1.6 -> 0.4 | 1.6 -> 0.4 | 323 -> 92 | 53.2 -> 23.5 |
| quiet 11324, warm interval only | 41.4 -> 11.9 | 3 / 0 | 10 -> 9 | 1.6 -> 0 | 1.6 -> 0 | 323 -> 15 | 53.2 -> 13.8 |
| cold 11312, one interval | 2036 -> 2070 | 51 / 15 unchanged | 199 | 54 | 54 | 11391 | 2897 |
| 100-reservoir chain, one interval | 3945 -> 4016 | 47 / 31 unchanged | 235 | 31 | 31 | 5776 | 6991 |

- Jacobian builds per quiet interval: interval 0 builds one (nothing retained yet), and after that
  three of four intervals build none and the fourth rebuilds once, when the chord iteration fails to
  contract at 0.8 and `SparseNewton` refreshes on purpose. RCM orderings for the Newton matrix drop
  to 0 after the first interval (the remaining orderings per interval are the 12-13 transport
  reconstructions, now counted separately as `transportOrderings`).
- `implicitSolves` also drops 10 -> 9: the retained `TrBdf2StepSolver.endpointRates` entry for the
  end of the previous interval is the start of this one, so the algebraic port solve is skipped.
- The single-interval fixtures retain nothing and are unchanged; their wall difference is run noise.
- Trajectory: **not bitwise identical**, contrary to the review's expectation for A2. The retained
  factorization preconditions the first Newton solve of the next interval, so that solve reaches the
  same root along a different path. Measured against the 154007d references: 1.0e-9 relative on
  state and moles (worst point is a fixed 1 m^3 volume, which the baseline closed to 1+1.0e-9 and
  the retained solve closes to 1+4e-16, so A2 is the more accurate of the two there), 1.3e-9 K,
  5.5e-12 on phase volume fractions, and 2.4e-9 kg/s on a pipe carrying 7.5e-7 kg/s, which is 3% of
  the interval controller's own declared flow floor (1e-9 + 2e-9 m/dt, about 7.7e-8 kg/s there).
  Substep counts, rejection reasons and every physics gate are unchanged, and the cold and chain
  fixtures stay bitwise identical. The fixtures' declared gate is therefore
  `WITHIN_NEWTON_TOLERANCE` (1e-8 relative state, 1e-7 K, 1e-9 phase fraction, 1e-6 relative flow or
  5e-8 kg/s absolute) instead of EXACT, and the harness prints the measured maximum every run.
- Gate: 792 JUnit tests (2 new in `RetainedSolverTest`), 14 GameTests, green.
