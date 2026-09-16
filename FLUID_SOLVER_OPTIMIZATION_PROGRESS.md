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
- Commit `0901c8e`.

### WP1-B1 - check the sparse backward error only where it can fail

`Factorization.solveMultiple` ran `checkResidual` (a full sparse mat-vec) after every solved vector
and refined on failure; refinement never triggered in 1990 measured checks. The check is now a
factorization property: `Verification.UNTIL_VERIFIED` checks until this factorization has passed
once, `NONE` skips it for the merit probe whose result is only compared, `EACH` keeps the old
behaviour for the standalone entry points. `ConservativeTransport` checks the first component
vector of its 23 and leaves the rest to the junction-continuity and conservation gates it already
runs. A Newton step that fails to contract calls the new `Factorization.verify` on the direction it
used, so a genuinely bad factorization still raises `SolveFailure` and refreshes exactly as before.

| fixture | backward-error checks | Newton LU solve ms | wall ms | allocated MB |
|---|---:|---:|---:|---:|
| quiet 11312, mean of 5 | 205 -> 9 | 2.71 -> 2.40 | 44.5 -> 43.6 | 39.1 unchanged |
| quiet 11324, mean of 5 | 205 -> 9 | 1.44 -> 1.27 | 19.8 -> 20.1 | 23.5 unchanged |
| cold 11312, one interval | 5238 -> 252 | 158.1 -> 113.5 | 2070 -> 2035 | 2897 unchanged |
| 100-reservoir chain | 6256 -> 265 | 383.2 -> 189.3 | 4016 -> 3776 | 6991 unchanged |

- Trajectory: bitwise identical to A2 on all four fixtures, verified in EXACT mode against a
  post-A2 capture (`-PfluidRegressionReferences=build/probe/reference-a2 -PfluidRegressionMode=exact`),
  including substep counts - as expected, since the check never changed a result.
- Gate: 792 JUnit tests, 14 GameTests, green.
- Commit `1a8d3ff`.

### WP1-A1 - carry the accepted step size across intervals

`PassiveIntervalSolver` now separates the controller's step estimate from the attempt: each attempt
uses `min(estimate, remaining interval)`, and an attempt truncated by the interval boundary no
longer shrinks the estimate, because the truncation is not evidence about the step size. Within a
single interval this is behaviourally identical to before (only the final attempt is ever truncated,
and a rejection still shrinks from the attempted step). `nextStepEstimate()` exposes the estimate
after a successful interval, and `RetainedSolver` feeds it into the next one, bounded by the
interval and `maximumStep`; an island with no history, a revision change, a hold, or an approximate
interval restarts from `COLD_START_SECONDS` = 0.05. Like `maximumSliceTicks`, the hint is an
ephemeral cost hint and is deliberately not persisted. Tolerances, error criteria and the pipe term
are unchanged.

| fixture | wall ms | substeps acc/rej | implicit solves | Jacobian builds | residual evaluations | allocated MB |
|---|---:|---:|---:|---:|---:|---:|
| quiet 11312, warm interval | 22.2 -> 19-30 (58 when a Jacobian is rebuilt) | 3 / 0 -> **1 / 0** | 9 -> 3 | 0 | 15 -> 26-50 | 22.9 -> 21.6-37.3 |
| quiet 11312, first interval | 96 -> 192 | 3 / 0 -> 7 / 0 | 10 -> 22 | 1 -> 3 | 207 -> 622 | 66.6 -> 176.0 |
| quiet 11324, warm interval | 11.9 -> 6.7-10.6 | 3 / 0 -> **1 / 0** | 9 -> 3 | 0 | 15 -> 5-27 | 13.8 -> 4.7-13.4 |
| quiet 11324, first interval | 31.7 -> 104.9 | 3 / 0 -> 7 / 0 | 10 -> 22 | 1 -> 4 | 207 -> 813 | 39.8 -> 126.9 |
| cold 11312, one interval | 2035 -> 1824 | 51 / 15 -> 50 / 16 | 199 | 54 -> 47 | 11391 -> 9993 | 2897 -> 2609 |
| 100-reservoir chain | 3776 -> 3085 | 47 / 31 -> **46 / 23** | 235 -> 208 | 31 -> 23 | 5776 -> 4513 | 6991 -> 5809 |

Quiet islands reach one substep per interval from the second interval on, as required. The first
interval of an island with no history is more expensive (7 substeps from the 0.05 s cold start),
which is the intended trade: it is paid once per island per session, and the transient fixtures both
get cheaper because they no longer pay the initial rejections from 1 s.

Accuracy against the 154007d references, under the declared gate
(`-PfluidRegressionMode=relative`: 1e-6 relative state, 1e-4 K, 1e-6 phase fraction, 1e-3 flow):

| quantity | gate | quiet 11312 | quiet 11324 | cold 11312 | chain |
|---|---:|---:|---:|---:|---:|
| state / moles, relative | 1e-6 | 1.7e-9 | 8.4e-10 | 1.2e-9 | 1.6e-8 |
| temperature, K | 1e-4 | 1.3e-9 | 9.6e-10 | 1.1e-8 | 1.5e-7 |
| phase volume fraction | 1e-6 | 5.6e-12 | 3.3e-12 | 5.0e-11 | 7.1e-10 |
| average flow, worst relative | 1e-3 or the controller's allowance | 2.3e-1 | 7.2e-3 | 1.8e-4 | 8.2e-6 |
| average flow, worst absolute kg/s | - | 7.2e-9 | 8.9e-9 | 3.4e-9 | 4.3e-9 |
| controller allowance there, kg/s | - | 3.18e-8 | 3.18e-8 | 3.18e-8 | 3.16e-8 |

The flow clause is measured against the interval controller's own per-pipe scale, not a fixed
constant: `PassiveIntervalSolver.flowError` subtracts a declared numerical allowance
`1e-9 + 2e-9*(m_first + m_second)/duration` before judging a pipe, so a flow agrees when
`|dq| <= max(1e-3 * max(|q_reference|, floor), floor)`. A fixed denominator floor gates a quiescent
pipe far below the engine's own resolution - island 11312's worst pipe carries 18 ng/s against an
allowance of 3.18e-8 kg/s, so its relative figure is meaningless while its absolute difference is
23% of what the engine itself calls equation noise - and no change of step sequence could satisfy a
purely relative bound there. Every flow deviation on every fixture is at most 8.9e-9 kg/s, i.e. 28%
of the controller's allowance for that pipe. The formula is documented on
`SolverRegressionHarness.Relative` next to `numericalFloor`, so the gate and the controller cannot
drift apart.

Every accuracy suite named for this item passes unchanged: CadenceTrajectoryQualificationTest,
TransientQualificationTest (3), HydraulicReferenceQualificationTest (4), HydraulicMatrix (4),
SharedSourceDepletion (2), FluidFallbackQualification (6) and all other science.fluid tests.

- Gate: 792 JUnit tests, 14 GameTests, green.
- Commit `cad3474`.

### Share the retained solver across module transfer trials

No production code constructs `FluidIslandCommand` with a per-job handle any more - the coordinator
passes `island.retained` - but the module path never went through that constructor. A buffered
interval becomes a `BufferedIslandCommand`, whose `ModuleTransferPlanner.prepare` built its own
`new PassiveIntervalSolver(model)` and solved the interval up to 1 + 12 + 12 times while searching
for a feasible transfer size, then discarded it: the full pre-A2 cost, once per buffered interval.

`RetainedSolver.run` now leases the island's solver for a whole job - every trial shares one lease
and one latch - `prepare` takes the handle, `BufferedIslandCommand` carries it, and
`CausalModuleCoordinator.command` passes `original.retained()` when it replaces the ordinary
command. Invalidation is unchanged: a revision bump replaces the handle before the next dispatch,
and a held, failed or approximate outcome resets the step estimate.

One refinement came out of the measurement. A trial introduces a source or sink term, which is a
boundary change rather than a continuation, so `Job.solveTrial` starts it from the cold step and
leaves the island's estimate alone; only the transfer-free baseline solve continues and updates it.
Without that rule the carried estimate made the measured buffered interval *more* expensive - 11
Jacobian builds and 28 implicit solves against 6 and 37 for a fresh solver - because it opened with
an oversized attempt on a freshly disturbed network. With it, sharing costs nothing and saves what
the island has already built.

Measured effect: the only module fixture available in this worktree is a 2-node island (a generator
feeding one reservoir, in `RetainedSolverTest`), where sharing is neutral - 6 versus 6 Jacobian
builds, 188 versus 190 residual evaluations, 21 versus 24 Jacobian colours, so a little structure
carries and the rest is a matrix too small to matter. The saving scales with the node block, so
showing it needs a multi-node module island: `FluidModuleProfileTest` would give that, but it needs
a saved module world (`run/fluid-benchmark/pilot-module-warm-01`), which is build output and is not
present here. The new test asserts the invariant the measurement does support - sharing never costs
more work than a fresh solver - so a regression in the trial rule would be caught.

Restart equivalence had to be re-expressed for this to land.
`CausalModuleCoordinatorTest.restartWithPartialFeedOrPendingProductsContinuesTheSamePhysicalTrajectory`
compared a continuously running world against one restored from a checkpoint at tick 150/350 with an
absolute 1e-4 J on reservoir internal energy - a bitwise assertion in disguise on a 2.25e8 J
inventory. A restored island's retained solver is cold while the continuously running one is warm, so
their module intervals reach the same root along different paths: -2.2547663751797843e8 J versus
-2.2547663752132654e8 J, 3.3e-3 J apart, 1.5e-11 relative. The comparison is now relative (1e-9) with
the previous absolute bound as the floor for near-zero amounts, on both the component moles and the
energy, with the reason stated in the test. Every conservation and ledger assertion there is
untouched and still exact, including the stranded-parcel equality in the neighbouring tests.

- Gate: 793 JUnit tests (1 new), 14 GameTests, green. The regression harness is unaffected - it does
  not exercise the module path - and reports the same deviations as A1.
- Commit `3b281cb`.

### WP2-B2 - reuse the sparse factorization workspace across refactorizations

`SparseLuSolver.Factorization` allocated an EJML solver, a `DMatrixSparseCSC`, the scaling and solve
buffers and a re-cloned `SparseMatrix` per factorization, and EJML regrew L/U through
`growMaxLength` on every `decompose` (11.9% of all allocation in the profile, 10.3 pp of it from
`LuUpLooking_DSCC.initialize`). The storage is now a `SparseLuSolver.Storage`, and the question the
review did not ask is where it may live. Per `SparseNewton.Workspace` buys nothing: a workspace is
keyed by `(structure, dt)`, the controller almost never repeats a step size exactly, and the cold
island's 47 Jacobian builds are spread over 66 freshly forked workspaces. The storage therefore
belongs to a **fork family** - a workspace is forked from the previous timestep's and they run
strictly in sequence - so the whole family refills one EJML solver in place and the L/U capacity
survives. Newton factorization storages per interval: 3 -> 2 quiet, **47 -> 2** cold, **23 -> 2** on
the chain (the 265-277 remaining per cold interval are `ConservativeTransport`'s one-shot
reconstructions, about 10-15 KB each).

A refill supersedes the `Factorization` handles taken from that storage before it. A holder of one
rebuilds, which is exactly what a modified-Newton refresh already does, and `SparseNewton` checks
`superseded()` where it already checks for a missing factorization, so the rebuild costs no
iteration. The new `luSupersededFactorizations` counter measures how often a sibling timestep
invalidates a live preconditioner: **0 on all four fixtures**. It is not a hypothetical path -
`StructuralReuseTest` constructs it deliberately - and both of its tests still assert the same roots.
Retained state does not grow: at most two stores per island (`implicit` and `algebraic`) replace up
to eight live factorizations.

| fixture | wall ms | substeps | LU factorizations | LU factor ms | Newton storages | allocated MB |
|---|---:|---:|---:|---:|---:|---:|
| quiet 11312, mean of 5 | 65.5 -> 67.4 | 7/0 then 1/0 unchanged | 0.8 unchanged | 41.6 -> 46.0 total | 4 -> 2 | 65.8 -> 63.8 |
| quiet 11324, mean of 5 | 25.0 -> 27.8 | unchanged | 0.8 unchanged | 20.6 -> 18.6 total | 5 -> 2 | 32.5 -> 31.3 |
| cold 11312, one interval | 1904 -> 1848 | 50 / 16 unchanged | 47 unchanged | 340 -> 326 | 47 -> 2 | 2608.8 -> **2132.6** |
| 100-reservoir chain | 3459 -> 3334 | 46 / 23 unchanged | 23 unchanged | 196 -> 228 | 23 -> 2 | 5808.6 -> **5203.2** |

Wall times are the mean of two runs each, measured in the same session against the same source tree
reverted to `3b281cb` and back, because the quiet fixtures' run-to-run spread is larger than this
item's effect on them: they build no Jacobian at all on a warm interval, so there is nothing to
reuse and nothing to gain. The transient fixtures, which do, drop 18% and 10% of their allocation.

`setStructureLocked(true)` was measured to be unavailable rather than unprofitable:
`LuUpLooking_DSCC.setStructureLocked(true)` **throws** in EJML 0.44 ("Pivots change depending on
numerical values and not just the matrix's structure") and `isStructureLocked()` is hard-coded
false. There is no symbolic phase to keep, so `Pattern.numericMatrix` keeps dropping exact zeros and
the LU is never fed structural zeros. `SparseMatrix` gained an `adopting()` factory - same
validation, no defensive copy - used by `numericMatrix`, `symbolicMatrix` and
`ConservativeTransport.matrix`, and the colored difference scratch (`derivatives`, `steps`) is
family-wide too. `ConservativeTransport.reconstruct` was left structurally alone: its matrices are
n = 18-100 with ~n + edges nonzeros, so its 265 factorizations per cold interval are about 4 MB of
the 2133 and its ordering is the identity below n = 128; its cost is in `repartition` and the
property evaluations, not the linear algebra.

- Verified EXACT (bitwise, substep counts included) against a capture of `3b281cb` in
  `build/probe/reference-wp1`, on all four fixtures.
- Gate: 793 JUnit tests, 14 GameTests, green.
- Commit `cb5a881`.
