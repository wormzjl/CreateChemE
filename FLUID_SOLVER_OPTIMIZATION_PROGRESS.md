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

### WP2-A4 - estimate the TR-BDF2 error with a linear filter instead of a nonlinear solve

The embedded order-three companion ran a complete nonlinear `implicit.solve` on a perturbed
inventory - one of the three coupled Newton solves per substep, with its own active-set loop and
reconstruction - only to filter the defect. That stage differs from stage two by nothing but the
reservoir target inventories, and `PhaseLayout.residual` subtracts the target on a reservoir's
component and energy rows only, so the shift is `J*dx = delta/scale` on those rows and zero on the
volume, equilibrium, hydraulic and junction rows. `PassiveStepSolver` keeps the last successful
solve's equations, converged variables and Newton workspace; `companion()` applies that
factorization once (`Verification.NONE`, the result is never committed), decodes the endpoint and
reconstructs it on the corrected base exactly as an ordinary solve would. The nonlinear stage
remains the fallback - no matching last solve, a superseded or singular factorization, a decoded
point outside the property domain, a refused reconstruction - and is selectable through the
package-visible `TrBdf2StepSolver.companionFilter`; `companionFilters` and `companionSolves` count
both. It fires: quiet island 11312 falls back once in its first interval, where the linearized flows
do not survive the transport reconstruction.

| fixture | wall ms | substeps acc/rej | implicit solves | companion filter/solve | Jacobian builds | LU factor ms | residual evaluations | allocated MB |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| quiet 11312, mean of 5 | 67.4 -> 52.3 | 11/0 -> 14/1 | 6.8 -> 6.4 | 0/11 -> 14/1 | 0.8 -> 0.6 | 46.0 -> 30.9 | 193 -> 137 | 63.8 -> 48.6 |
| quiet 11324, mean of 5 | 27.8 -> 36.9 | 11/0 -> 18/3 | 6.8 -> 8.6 | 0/11 -> 21/0 | 0.8 unchanged | 18.6 -> 22.2 | 175 -> 190 | 31.3 -> 42.3 |
| cold 11312, one interval | 1848 -> 1697 | 50/16 -> 52/15 | 199 -> **135** | 0/66 -> 67/0 | 47 -> 41 | 326 -> 291 | 9993 -> 8731 | 2132.6 -> 1906.9 |
| 100-reservoir chain | 3334 -> 2985 | 46/23 -> 45/22 | 208 -> **135** | 0/69 -> 67/0 | 23 -> 22 | 228 -> 213 | 4513 -> 4149 | 5203.2 -> 4633.3 |

Implicit solves per substep are 3 -> 2 exactly, on both transient fixtures (135 = 2 x 67 attempts
plus the one algebraic port solve).

**Linear versus nonlinear estimate.** Both were computed at the same stage-two state on every
substep of all four fixtures (a scratch build that ran the nonlinear stage alongside the filter and
was reverted before this commit). Ratios are linear/nonlinear on the interval controller's own two
terms:

| fixture | substeps compared | state term, min/median/max | pipe term, min/median/max | linear endpoint vs nonlinear endpoint |
|---|---:|---|---|---|
| cold 11312 | 67 | 0.854 / 0.9997 / 1.094 | 0.024 / 1.000 / 1.176 | median 2.2%, max 12.4% of the correction |
| 100-chain | 67 | 0.987 / 1.000 / 1.052 | 0.987 / 1.000 / 1.066 | median 1.2%, max 9.6% |
| quiet 11312 | 25 | 0.005 / 1.000 / 258 | 0.002 / 0.991 / 452 | max 1.3e-8 absolute, on 1e-11..1e-8 terms |
| quiet 11324 | 23 | 0.012 / 1.000 / 176 | 0.012 / 1.000 / 188 | max 1.2e-8 absolute |

On the transients, where the estimate actually controls the step, the two agree within 10% on the
state term and 18% on the pipe term, which also settles the sign: a flipped sign would put the ratio
near 3 and the endpoint disagreement at twice the correction, not at 2% of it. The quiescent
extremes are not a disagreement about the defect but about the estimator's floor: on 16 of 25 and 15
of 23 of those substeps the **nonlinear** stage exits at Newton iteration 0, because the perturbed
residual is already inside its 1e-9 solve tolerance, and reports a correction of exactly zero. The
linear filter has no tolerance and resolves it. Both estimates are five to seven orders below the
1e-3 step tolerance there.

That extra sensitivity is the item's one cost. Island 11312 still reaches one substep per 5 s
interval from the third interval on and gets 22% cheaper; island 11324 now rejects the full 5 s step
on three of its five intervals (the term that trips is the pipe term on pipes carrying 1e-8 to
1e-6 kg/s, where a 8e-8 kg/s difference in the estimated average flow is 2.7 times the controller's
own numerical allowance) and is 33% more expensive. Every accepted step still meets the unchanged
error criteria, and refining a step makes the committed trajectory more accurate, not less. Adding
the base point's own residual to the right-hand side - `J*dx = delta/scale - r`, which is the
consistent linearization when the base point is the reconstruction rather than the Newton root - was
measured and rejected: it moved the reference deviations by less than a factor of two and cost two
more rejections on 11324 and one more on 11312.

Accuracy against the 154007d references, under the declared gate
(`1e-6` relative state, `1e-4` K, `1e-6` phase fraction, `1e-3` flow or the controller's allowance):

| quantity | gate | quiet 11312 | quiet 11324 | cold 11312 | chain |
|---|---:|---:|---:|---:|---:|
| state / moles, relative | 1e-6 | 2.0e-9 | 9.6e-10 | 3.9e-10 | 1.8e-9 |
| temperature, K | 1e-4 | 1.2e-9 | 6.6e-10 | 4.3e-9 | 1.8e-8 |
| phase volume fraction | 1e-6 | 5.6e-12 | 3.3e-12 | 2.0e-11 | 8.3e-11 |
| average flow, worst absolute kg/s | - | 7.0e-9 | 6.6e-9 | 3.2e-9 | 4.7e-9 |
| controller allowance there, kg/s | - | 3.18e-8 | 3.18e-8 | 3.18e-8 | 3.16e-8 |

Every flow deviation is at most 22% of the controller's own allowance for that pipe, the same
character as A1's. The suites named for this item pass unchanged: CadenceTrajectoryQualification,
TransientQualification (3), HydraulicReferenceQualification (4), HydraulicMatrix (4),
SharedSourceDepletion (2), FluidFallbackQualification (6), CausalModuleCoordinator (6),
ModuleTransferPlanner (3), BufferedTransfers (4), BufferedCommit (1), and all other science.fluid
tests.

- Gate: 793 JUnit tests, 14 GameTests, green.
- Commit `bc14098`.

### WP2-C5 - remove per-job string building and per-pass string keys

`ApproximationAnchor` concatenated both revision strings from immutable model identity on every
call, once per dispatched island job; the allocation profile found that as 0.99% of all sampled
allocation, essentially all of the solver's string building. They are built once per model now and
held in one weak-keyed slot, published as a single immutable record so a reader can never pair a
model with another model's strings and so caching one cannot keep its property catalog alive.

`PassiveStepSolver` keyed the active-set cycle check on
`modes.toString()+Arrays.toString(boundaryClosed)+`phase signatures, and the workspace maps on boxed
identity lists plus two more rendered strings, rebuilding all of it on every pass. A phase signature
is four flags, so it is an `int`; modes and node kinds are ordinals, so they are `byte`s; the
component mask, node identities and kinds depend on the graph alone, so they are evaluated once per
solve rather than once per pass, which is why the mask moved out of `Equations`. The cycle key is
now the structure key the workspace maps already needed - within one solve every other field of it
is constant, so the two have the same equality semantics - and the previous-flow guard compares
`long[]` identities instead of building a `List<Long>` three times per solve.

| fixture | allocated MB | everything else |
|---|---:|---|
| quiet 11312, mean of 5 | 48.7 -> 48.6 | unchanged |
| quiet 11324, mean of 5 | 42.4 -> 42.3 | unchanged |
| cold 11312, one interval | 1908.2 -> 1906.9 | unchanged |
| 100-reservoir chain | 4636.3 -> 4633.3 | unchanged |

Every counter, substep sequence and wall time is inside run noise: the replay harness drives
`RetainedSolver` directly and never reaches the job dispatch path where the anchor strings were
built, so the item's larger half is not observable on these fixtures and is carried by the profile's
own attribution.

- Verified EXACT (bitwise, substep counts included) against a capture of `bc14098` in
  `build/probe/reference-a4`, on all four fixtures.
- Gate: 793 JUnit tests, 14 GameTests, green.
- Commit `cffdcff`.

### WP3-A4b - treat a companion defect below the Newton tolerance as unresolved

The linear filter has no residual of its own to answer to, so two rules now keep it inside what the
engine can resolve, and the second one is not the rule this item was planned around.

**The tolerance rule (as specified).** When the filter's scaled right-hand side (`delta/scale`, the
same scaling the stage-two Newton converged under) is at or below that solve's tolerance - 1e-9,
1e-10 for a small headspace, 1e-6 approximate, now carried on the `LastSolve` holder - the
correction is zero, because the engine cannot resolve a target shift its own stage solve treats as
converged. That is what the nonlinear stage did: its Newton exited at iteration 0 and returned the
stage-two point. It fires on 17 of 67 cold substeps, 0 of 67 chain substeps, and on the single
substep of quiet 11312's warm intervals.

**What the tolerance rule did not fix.** Alone it leaves quiet 11324 at 18/3 and moves quiet 11312
from 14/1 to 16/2. The rejecting attempt is always the full 5 s step, where the defect is 1.3e-9 to
5.0e-9 - just *above* the tolerance, because a 5 s step carries the largest target shift - so the
rule never reaches it. Probing the term the controller actually rejects on (island 11324, pipe 13,
1.24e-6 kg/s): the genuine order-three flow defect `e0*q0+e1*q1+e2*q2` is -8.9e-9 kg/s, and the
filter contributes `ALPHA*(qc-q2)` = +5.4e-8 kg/s, six times larger and of the sign that should have
cancelled it. Run with `companionFilter=NONLINEAR` at the same points, the filter term is
-1.70e-8 against a +1.67e-8 defect: it cancels to 3e-10, which is why the nonlinear stage accepts
every warm 5 s step.

**Root cause: the filter may be applying another step's implicit operator.** `forkPreconditioner`
carries a factorization across substeps and intervals, and a quiet island rebuilds no Jacobian at all
after its first interval, so the 5 s attempt is filtered through a chord built at a stage step of
0.54 s or less. As a Newton search direction that is fine - the nonlinear residual corrects it - but
the TR-BDF2 filter *is* `(I-alpha*h*J)^-1`, so a chord from a smaller step under-damps, and the
overshoot lands in the flow unknowns, where it is 1.4x the controller's own numerical allowance on a
pipe carrying 1.2e-6 kg/s. Measured directly: on every warm 5 s attempt of both quiet islands the
linearized point leaves a residual of 5.9e-9 to 2.3e-8 on the perturbed equations - 2.4 to 5 times
the defect it was handed, i.e. the linear system was not solved at all.

**The rule that follows: the filtered point must solve the perturbed equations.** After applying the
factorization, `companion` evaluates `f(x+dx)-delta/scale` and refuses the filter when its max-norm
exceeds `max(newtonTolerance, defect)`, which hands that substep to the nonlinear stage exactly as a
missing factorization already does. It is the same self-check `SparseNewton.estimateCorrection`
already applies to its own probe. The check costs one residual assembly and no node decodes: the
endpoint decode that follows reads the same per-node cache, so `stateCalls` is unchanged on every
fixture. Requiring the factorization to be the solve's own instead (a provenance test rather than a
result test) was measured and rejected: it fixes the quiet islands equally but refuses 31 of 67 cold
and 47 of 67 chain filters, costing +7% and +19% wall against this row's transients.

| fixture | wall ms | substeps acc/rej | implicit solves | Jacobian builds | residual evaluations | node state() calls | allocated MB | KB per residual |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| quiet 11312, mean of 5 | 53.5 -> 43.7 | 14/1 -> **11/0** | 6.4 -> 5.0 | 0.6 unchanged | 137 -> 132 | 2064 -> 1782 | 48.7 -> 40.2 | 364 -> 312 |
| quiet 11312, warm intervals 2-4 | 7.6-11.3 -> 4.4-5.9 | 1/0 each | 2 | 0 | 6-14 -> 4 | 360-600 -> 300 | 8.4-13.7 -> 6.7 | - |
| quiet 11324, mean of 5 | 39.8 -> 26.5 | 18/3 -> **11/0** | 8.6 -> 5.8 | 0.8 unchanged | 190 -> 172 | 1818 -> 1289 | 42.3 -> 30.3 | 228 -> 181 |
| cold 11312, one interval | 1692 -> 1649 | 52/15 unchanged | 135 | 41 | 8731 | 98070 | 1906.4 -> 1903.2 | 224 -> 223 |
| 100-reservoir chain | 2940 -> 3002 | 45/22 unchanged | 135 | 22 | 4149 | 246000 | 4631.6 -> 4645.1 | 1143 -> 1146 |

Companion accounting per interval: quiet 11312 warm 1 filter, of which 1 below tolerance (interval 1
refuses its one filter); quiet 11324 warm refuses its one filter every interval; cold 67 filters, 17
below tolerance, 0 refused; chain 67 filters, 0 below tolerance, 0 refused. The transient wall
figures are run noise around an unchanged trajectory - both single-interval fixtures compare
**bitwise identical** (0.0 on every quantity) against a capture of 8889c7d in
`build/probe/reference-wp2`, so the two new rules touch quiescent islands only.

Accuracy against the 154007d references, under the declared gate:

| quantity | gate | quiet 11312 | quiet 11324 | cold 11312 | chain |
|---|---:|---:|---:|---:|---:|
| state / moles, relative | 1e-6 | 1.0e-9 | 6.2e-10 | 3.9e-10 | 1.8e-9 |
| temperature, K | 1e-4 | 1.2e-9 | 7.3e-10 | 4.3e-9 | 1.8e-8 |
| phase volume fraction | 1e-6 | 5.5e-12 | 3.5e-12 | 2.0e-11 | 8.3e-11 |
| average flow, worst absolute kg/s | - | 1.9e-9 | 8.0e-9 | 3.2e-9 | 4.7e-9 |
| controller allowance there, kg/s | - | 3.18e-8 | 3.18e-8 | 3.18e-8 | 3.16e-8 |

Against `reference-wp2` (8889c7d) the quiet fixtures move 1.0e-9 and 8.9e-10 on state, 3.8e-10 and
5.1e-10 K, 7.0e-13 and 1.0e-12 on phase fraction, and at most 9.8e-9 kg/s on a flow - 31% of the
controller's allowance for that pipe. Two new counters record the rules:
`companionDefectsBelowTolerance` and `companionFiltersRefused`.

- Gate: 793 JUnit tests, 14 GameTests, green.
- Commit `16f9cfd`.
### WP3-C1 - evaluate water properties without the thread-local context and cache them per node temperature

Three changes to the most expensive primitive in the solver, which the probe measured at 2.34 us of
a 4.4 us node `state()`:

**(a) No context on the property path.** `V3WaterProperties` now has an explicit-argument form of
every correlation taking the `MaterialCatalog.Water` record, with the context forms delegating to
them, and `WaterRegion1.evaluate` takes the record for its saturation domain check.
`FluidThermodynamics` resolves the record once in its constructor - through
`MaterialRuntime.with(catalog,packageId,MaterialRuntime::water)`, so the resolution including its
fallback package is exactly the one the context form performed - and never touches `MaterialRuntime`
again. That removes a `Context` allocation and a `ThreadLocal` set/remove per `saturationPressure`,
`vaporWaterEnthalpy` and `waterLiquid` call, and a `ThreadLocal.get` plus two immutable-map probes
per coefficient access, about eight per saturation call (`MapN.probe` alone was 3.1% of pool CPU).

**(b) No `Math.pow` in IF97 Region 1.** The 34 terms need `x^0..x^32` and `y^-43..y^17`; both tables
are built once per evaluation by multiplication, negative exponents from the reciprocal, instead of
up to six `Math.pow` calls per term (about 100 per evaluation). Deviation from the pow form over the
whole Region 1 domain: **6.3e-14** on specific volume, 5.4e-13 on cp, 8.4e-13 on dv/dT, 9.4e-13 on
dv/dP, 1.6e-12 on d2v/dT2. Enthalpy reaches 1.8e-11 and internal energy 2.5e-9 relative, both at the
triple point, where the IF97 datum puts them through zero and a relative measure is meaningless
(1.8e-11 of 0.6 J/kg). At the 2 MPa reference pressure, the only pressure the fluid model ever
evaluates Region 1 at, the maximum over 273.16-485 K is **3.3e-13**, on dv/dT. The IAPWS checkpoints
in `WaterRegion1Test` (v to 5e-12, h and u to 1e-3 J/kg, cp to 1e-4) pass unchanged.

**(c) A prepared temperature per node.** `Equations.cachedTemperatureTerms` becomes
`cachedPrepared`, a `FluidThermodynamics.Prepared` holding the PR mixing terms, the
reference-pressure Region 1 state, the saturation pressure and the ideal-gas water-vapor enthalpy -
every property that depends on temperature alone. `state`, `waterLiquid` and `saturationPressure`
take it; the standalone paths pass `null` and compute each member as before. Members are computed on
first use, because a trial point can be outside the domain of a property it does not use. A colored
Jacobian perturbation of a node's composition or pressure columns, and every residual at an unchanged
node temperature, now reuse all four: measured, one prepared bundle serves 2.0 (chain) to 2.7 (cold)
`state()` calls.

Unit costs on this host (median of 2000 after 2000 warm-up calls, `FluidPropertyBenchmarkTest`,
TJL20 + 0.2 mol water per mol hydrocarbon at 350 K; the clock granularity is 100 ns, and the probe's
154007d figures in brackets come from a different host):

| kernel | ns |
|---|---:|
| `state(...)` with the prepared temperature | **3800** [4400 with prepared terms only] |
| `state(...)` without it | 7900 [5600] |
| `prepare(T)` | 100 |
| `waterLiquid(T,P)`, evaluating Region 1 | **200** [2338] |
| `waterLiquid(T,P)` with the prepared temperature | 100 |
| `saturationPressure(T)` | 100 [450] |

| fixture | wall ms | substeps acc/rej | implicit solves | Jacobian builds | residual evaluations | node state() calls | allocated MB | KB per residual |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| quiet 11312, mean of 5 | 43.7-44.0 -> 44.8-45.3 | 11/0 unchanged | 5.0 | 0.6 | 132 | 1782 | 40.2 -> 41.8 | 312 -> 325 |
| quiet 11324, mean of 5 | 26.4-27.8 -> **20.6-20.9** | 11/0 unchanged | 5.8 | 0.8 | 172 | 1289 | 30.3 -> 30.1 | 181 -> 180 |
| cold 11312, one interval | 1649-1730 -> **1307-1328** | 52/15 unchanged | 135 | 41 | 8731 | 98070 | 1903.2 -> 1883.0 | 223 -> 221 |
| 100-reservoir chain | 2802-3002 -> **2329-2334** | 45/22 unchanged | 135 | 22 | 4149 | 246000 | 4645.1 -> 4694.4 | 1146 -> 1159 |

Every counter is unchanged: this item changes no decision, only the cost of evaluating the same
properties. Wall times are the range over two runs of each commit in the same session. Quiet 11312 is
the one fixture that does not improve, and its mean is 82% two intervals that build three Jacobians
and factorize them (its warm intervals are 4 residual evaluations and 300 node decodes, where the
triangular solve and the transport reconstruction dominate and the LU timer alone moves 5.8-10.7 ms
between runs). Allocation is flat: the power tables are two arrays per Region 1 evaluation, 776 B,
which is why the chain - 124599 prepared temperatures - pays 49 MB more while the cold island, whose
bundles are reused more, pays 20 MB less. Where the 316 KB per residual evaluation actually goes is
C2's item.

Accuracy. The two fixtures whose A4b trajectory was bitwise identical to 8889c7d isolate this item's
own roundoff against `build/probe/reference-wp2`: **cold 11312 8.0e-14 relative on state, 8.5e-13 K,
3.7e-15 on phase fraction; chain 1.5e-13, 1.2e-12 K, 6.4e-15**, substep counts unchanged on both -
the promised ~1e-12 or below. Under the declared gate against the 154007d references the deviations
are A4b's, unchanged to three digits (state 1.0e-9 / 6.2e-10 / 3.9e-10 / 1.8e-9, temperature 1.2e-9
/ 7.3e-10 / 4.3e-9 / 1.8e-8 K, flow at most 8.0e-9 kg/s against a 3.18e-8 kg/s allowance).

- Gate: 793 JUnit tests, 14 GameTests, green.
- Commit `d91e13d`.
### WP3-C2 - stop cloning and streaming inside the residual

Pure removal of copies: every counter, substep sequence and committed state is **bitwise identical**
to the previous commit on all four fixtures (`-PfluidRegressionReferences=build/probe/reference-c1
-PfluidRegressionMode=exact`, 0.0 on every quantity).

**Ownership instead of copies.** `FluidThermodynamics.State`, `TranslatedPengRobinson.Phase` and
`HydrocarbonModel.Phase` no longer clone in their constructors; the arrays belong to the record from
construction on. The copying accessors stay exactly as they were for every caller outside the
solver - the checkpoint codec, `FluidView`, the module and runtime paths all still receive copies -
and the solver packages read through named no-copy views (`liquidView`, `vaporView`,
`logFugacityView`, `logFugacityCoefficientsView`, `partialMolarVolumesView`, each documented as
"the caller must not mutate"). Every construction site was audited so no array is reachable twice:
`state(...)` copies a caller's arrays and `adoptingState(...)` is the entry for a caller that
allocated them for that call alone (`PhaseLayout.decode`, `ConservativeTransport.repartition`); the
vapor `HydrocarbonModel.Phase` adopts the translated evaluation's coefficients, whose enclosing
record is discarded on the same line; the liquid one adopts `GlobalLiquidResponse.logFugacity`'s
freshly built result, which clones its own input. Per node decode that removes six clones of the
component basis and, per residual, two more per node in `PhaseLayout.residual` alone.

**Loops instead of streams, without moving a digit.** `Arrays.stream(x).sum()` is not a plain loop:
`DoubleStream.sum` accumulates with Kahan compensation, so a naive loop would have changed results.
`PhaseLayout.sum` reproduces `Collectors.sumWithCompensation` and `computeFinalSum` exactly - same
order, same negated low-order term, same spurious-NaN rule - which is what lets this commit be
bitwise identical while the stream objects disappear from the residual (two per node in the
equilibrium rows, one per junction, two per layout). `junctionResidual` also stops boxing its
component basis into an `ArrayList<Integer>` per evaluation: the graph fixes that list, so it is
built once with the layout.

**Scratch instead of garbage.** `Equations` keeps its residual scratch (`targets`, `energy`,
`incoming`, `incomingMass`, `incomingEnergy`, `netMass`, the junction mass fractions) and the
per-node variable snapshots, all refilled per evaluation; the returned residual stays a fresh array,
because `SparseNewton` holds the current and the candidate at once. `MixtureViscosity.vapor` takes a
three-vector workspace owned by the step solver instead of allocating per decoded node.
`PipeResistance.pressureDrop` and `PassiveNetwork.Pipe.pressureDrop` compute the same arithmetic and
the same checks without a `Loss` record per section per edge - the residual reads nothing else from
one - and the initial-flow bisection, 50 iterations per pipe, stops allocating too.

| fixture | wall ms | substeps acc/rej | implicit solves | Jacobian builds | residual evaluations | node state() calls | allocated MB | KB per residual |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| quiet 11312, mean of 5 | 44.8-45.3 -> 31.9-34.6 | 11/0 unchanged | 5.0 | 0.6 | 132 | 1782 | 41.8 -> **28.6** | 325 -> 222 |
| quiet 11324, mean of 5 | 20.6-20.9 -> 17.8-18.6 | 11/0 unchanged | 5.8 | 0.8 | 172 | 1289 | 30.1 -> **20.3** | 180 -> 121 |
| cold 11312, one interval | 1307-1328 -> 1248-1250 | 52/15 unchanged | 135 | 41 | 8731 | 98070 | 1883.0 -> **1118.0** | 221 -> 131 |
| 100-reservoir chain | 2329-2334 -> 2236-2300 | 45/22 unchanged | 135 | 22 | 4149 | 246000 | 4694.4 -> **3169.1** | 1159 -> 782 |

`state()` with a prepared temperature 3800 -> 3700 ns, without 7900 -> 7100 ns (the same
microbenchmark as C1). The allocation removed per residual evaluation is 88 KB on the cold island
(765 MB over 8731 evaluations) and 368 KB on the chain (1525 MB over 4149, a 100-node island against
the 30-node one the 316 KB profile figure was measured on). The KB-per-residual column is the
interval's whole allocation divided by its residual evaluations, so it still carries the Jacobian
colouring, the factorizations and the transport reconstructions; what is left inside the residual
itself is the decode's two component vectors per node, the `Transport` record that caches them, and
`temperatureTerms`' three n x n matrices per distinct temperature, which is C3's item.

Accuracy: bitwise identical to `d91e13d` (EXACT on all four fixtures). Under the declared gate
against the 154007d references the deviations are C1's, digit for digit: state 1.0e-9 / 6.2e-10 /
3.9e-10 / 1.8e-9, temperature 1.2e-9 / 7.3e-10 / 4.3e-9 / 1.8e-8 K, phase fraction at most 8.3e-11,
worst flow 8.0e-9 kg/s against a 3.18e-8 kg/s controller allowance.

- Gate: 793 JUnit tests, 14 GameTests, green.
- Commit `e604043`.
### WP3-C4 - prepare pure-component viscosities per node temperature

C1 and C2 left the transport term visible: after them a node decode paid 1300 ns for the liquid
mixture viscosity and 1600 ns for the vapor one against a 3800 ns `state()`, so the `Transport` row
the decode builds was still 43% on top of the state it describes. That is the measurement this
optional item was gated on, and it is met.

`MixtureViscosity.Prepared` holds one exact temperature's pure-component terms: the logarithm of
each liquid viscosity with the regime it came from (supported carrier or conditional solute), each
vapor viscosity with its square root, and the water liquid value. The composition-dependent mixing
stays per evaluation - the logarithmic weighting, and Wilke's double loop, which is O(n^2) with a
division per pair and is what remains of the vapor cost. `FluidThermodynamics.Prepared` carries the
bundle next to the PR terms and the water state, so the per-node cache that already keyed on the
exact temperature now covers transport too.

Components are filled the first time a mixture actually contains one, never in advance: the
correlations refuse temperatures outside their own range and the conditional-solute curves refuse
temperatures outside their sampled domain, so eager preparation would have raised a property
failure for a component the caller never asked about. Filling in index order keeps the first
failure the same one the composition would have produced.

| kernel | ns, C2 | ns, C4 |
|---|---:|---:|
| `viscosity.liquid` | 1300 | **100** |
| `viscosity.vapor` | 1600 | **1300** (the Wilke loop) |
| `state(...)` with the prepared temperature | 3700 | 3800 |

| fixture | wall ms | substeps acc/rej | implicit solves | Jacobian builds | residual evaluations | node state() calls | allocated MB | KB per residual |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| quiet 11312, mean of 5 | 31.9-34.6 -> 34.5 | 11/0 unchanged | 5.0 | 0.6 | 132 | 1782 | 28.6 -> 29.0 | 222 -> 225 |
| quiet 11324, mean of 5 | 17.8-18.6 -> 16.9 | 11/0 unchanged | 5.8 | 0.8 | 172 | 1289 | 20.3 -> 20.6 | 121 -> 123 |
| cold 11312, one interval | 1248-1250 -> **1149** | 52/15 unchanged | 135 | 41 | 8731 | 98070 | 1118.0 -> 1135.4 | 131 -> 133 |
| 100-reservoir chain | 2236-2300 -> **1979** | 45/22 unchanged | 135 | 22 | 4149 | 246000 | 3169.1 -> 3230.3 | 782 -> 797 |

Allocation rises by half a kilobyte per prepared temperature (four vectors over the basis), which is
17 MB on the cold island and 61 MB on the chain, against 8% and 13% of their wall time. The quiet
fixtures are inside their run-to-run spread; their warm intervals decode four nodes.

- Verified EXACT (bitwise, substep counts included) against `build/probe/reference-c1`, on all four
  fixtures, which is also the capture `e604043` proved itself against.
- Gate: 793 JUnit tests, 14 GameTests, green.
- Commit `f814bc0`.

## WP5 - production pool measurement

Review section 3.5 left one question open that no replay can answer: the in-process counters charge
18-28% of a single-threaded interval to linear algebra and 60-67% to residual evaluation, while JFR
on the 12-worker stress run charges 66.5% to linear algebra and 21.5% to properties. That decides
whether B3 (block/fill-aware LU) belongs before or after the residual work, so it is measured on the
production pool, with the solver counters running there for the first time.

### WP5-D1 - report the solver counters from the server benchmark

`SolverDiagnostics` was thread-confined by contract: the `inJacobian` / `inReconstruct` nesting
markers were plain static booleans, so twelve workers would have interleaved them and mis-charged
each other's transport factorizations to the Newton buckets. The markers are now per thread (one
`ThreadLocal` holder, entered and restored in the same `finally`), the counters were already
`LongAdder`s and stay process-wide sums, and the nanosecond timers are per-thread deltas summed into
those adders - under a pool they measure occupied thread time, whose sum over twelve workers exceeds
the window by design. The attempt log is bounded at 200 000 records, with two new exact counters
(`stepAttempts`, `stepAttemptsAccepted`) that survive the bound. `sample()` and `reset()` already
returned every counter and cleared them; `reset()` now also clears the calling thread's markers.

`-PfluidBenchmarkDiagnostics=true` (system property `createcheme.fluid.benchmark.diagnostics`) makes
`FluidServerBenchmark` reset and enable the counters at the first measured tick - not at warm-up, so
the readout is the steady state - and disable them as the first statement of `finish`, before the
report is assembled. The readout lands in `report.json` under `solverDiagnostics`: `counters` (raw
counts and nanosecond totals), `perAcceptedInterval` (the same divided by the accepted intervals
published in the window), `attemptDominantTerms`, and the note that the nanoseconds are thread time.
Cost with the property absent is unchanged: every site is still one volatile boolean read, and the
thread-local holder is never allocated.

- Trajectory: verified EXACT (bitwise, substep counts included) on all four fixtures against
  `build/probe/reference-wp3`, captured at `c2580ed` before this change.
- Confirmed end to end on a pilot run (`opt-wp5-smoke-r01`, `one` profile, 2 workers): the new key is
  written, the Newton and transport buckets are separated (1 Newton factorization against 20
  transport ones over 5 intervals), and `fluidServerBenchmark` parses the report with the extra key
  (`runtime-audit.json` status `PASS`).
- Gate: 793 JUnit tests, 14 GameTests, green.
- Commit `dba6179`.

### WP5-D2 - the measurement (documentation/FLUID_POOL_MEASUREMENT.md)

Three runs, one Gradle invocation at a time: `opt-wp5-stress100-12w-r01` (HEAD, stress100, 12
workers, 60 s warm-up + 120 s, JFR + memory + diagnostics), `opt-wp5-one-2w-r01` (HEAD, the `one`
design gate, 2 workers, 200 intervals), and `opt-wp5-baseline-stress100-12w-r01` (154007d, same
settings, from a throwaway worktree that was removed afterwards). The same-session baseline
reproduces the stored `memory-candidate-4g-r01` within run noise (p50 56.56 vs 55.44 ms, allocation
3.22 vs 2.93 GiB/s, worker CPU 0.2067 vs 0.1825 of the host), so there is no host drift and the
HEAD numbers stand on their own. The analysis scripts reproduce the published baseline buckets
(numeric LU 42.43% vs 42.26%, solves 20.92% vs 21.32%), so the method is the published one.

| stress100, 12 workers, 120 s window | 154007d same session | HEAD | factor |
|---|---:|---:|---|
| worker ms p50 / p95 / p99 / max | 56.56 / 122.53 / 1594.5 / 2000.76 | **3.28 / 5.88 / 10.17 / 15.56** | 17x / 21x / 157x / 128x |
| jobs measured / held / at the 2 s wall | 3312 / 86 / 12 | 2400 / **0** / **0** | - |
| substeps per accepted, p50 / mean / max | 3 / 3.32 / 35 | **1 / 1.00 / 1** | - |
| allocation rate / per accepted interval | 3.22 GiB/s / 122.8 MiB | **0.087 GiB/s / 4.47 MiB** | 37x / 27.5x |
| GC pauses / total / share of window | 483 / 1466 ms / 1.22% | **6 / 45.3 ms / 0.038%** | 32x |
| collections forced by humongous allocation | 214 of 393 | **0 of 5** | - |
| worker CPU (ThreadCPULoad / sample ratio) | 0.2067 / 11 859 samples | **0.0066 / 331 samples** | 31x / 36x |
| aggregate realtime ratio | 1.262 (draining debt) | **1.000** (at cadence) | - |
| `one` profile gate p50 / p95 / max | 112.1 / 167.2 / 206.3 (review) | **8.96 / 10.20 / 15.94** | 13x / 16x / 13x |

| exclusive worker CPU | 154007d measured | HEAD warm-up | HEAD measured |
|---|---:|---:|---:|
| EJML numeric LU factorization | 42.43% | 38.82% | **0.60%** |
| triangular solves + verification | 20.92% | 18.82% | 50.45% |
| all sparse linear algebra | 65.80% | 57.84% | 51.06% |
| Jacobian colouring / `differentiate` own | 3.47% | 3.30% | **0.00%** |
| residual + properties (all buckets) | 27.33% | 34.19% | 37.16% |
| water path (`WaterRegion1` / `MaterialRuntime`) | 7.56% | 0.34% | 0.60% |

The counters tell the whole story in three zeroes: over 2400 measured production intervals the
Newton side performed **0 Jacobian builds, 0 LU factorizations and 0 RCM orderings**. A1's carried
step makes a quiet 5 s interval one substep, A2's retained solver supplies a preconditioner built
during warm-up, and the chord iteration closes it in 2.77 Newton iterations and 4.80 residual
evaluations per interval. The only factorizations left are the four `ConservativeTransport`
reconstructions per interval, which still build a fresh ordering, factorization and EJML storage
every time - the one part of B2 that was never applied, worth 0.018 ms of a 3.50 ms job.

Answers to the five questions the brief asked, in full in the document:

- **(a) resolved, both halves.** The pool profile was real: HEAD's own warm-up window, which is the
  cold regime with twelve saturated workers, reproduces the baseline shape (LU 38.82%, linear
  algebra 57.84%, `differentiate` inclusive 67.27%), so top-frame attribution is refuted and the
  mechanism was hypothesis (b) - the profiled pool was doing transient work the quiet replays never
  did. Contention (hypothesis a) was real but secondary: 80.6 ms pool p50 against 51.2 ms
  single-threaded on the same 30-reservoir island at 154007d. Per-job cost now matches the replay
  exactly: pool p50 4.44 ms on a 30-reservoir island against 5.6-6.4 ms for `quiet-11312`'s warm
  intervals single-threaded.
- **(b)** p50 17x, p95 21x, max 128x, held intervals and wall hits to zero, allocation 27.5x per
  interval, GC pause share 32x, worker CPU 31x; per simulated second, 17.4 ms of worker wall down to
  0.70 ms. Better than the review's "52 ms -> about 10 ms" forecast for steps 1-3, because the
  carried step collapsed the quiet interval to one substep instead of three.
- **(c) B3 is not next.** Its target no longer exists in the steady state; a perfect block LU that
  made all linear algebra free would move p50 from 3.28 to 1.6-2.4 ms and p95 from 5.88 to 2.9-4.3
  ms, i.e. p95 from 0.29% of the 2 s budget to 0.22%. It still pays on transients (warm-up is 38.8%
  numeric LU), but as cold-start work. WP6 and WP7 rank ahead: the residual and its properties are
  37.2% of quiet worker CPU, `temperatureTerms` is 43.55% of all remaining allocation (C3), and
  truncation shrinks the one bucket that still dominates the quiet window. The unfinished B2 item -
  caching the transport ordering and structure per island - is worth more per unit effort than B3.
- **(d)** Zero wall hits, zero held intervals, zero rejected substeps and 2400 of 2400 attempts
  accepted in the measured window; the pipe term dominated 15 attempts and rejected none.
  `COLD_START_SECONDS` already covers what A5 was written for (warm-up held 271 -> 33, warm-up p50
  53 -> 4.2 ms), but the genuine cold solve still runs to 59 substeps, 18 rejections and 1.97 s, and
  seven warm-up jobs came within 30 ms of the wall. The optional ledger-impact flow criterion is
  **not needed** and should not be adopted: it trades ledger accuracy for speed in a regime where
  nothing is being held and where B3/WP6/WP7 all help without touching the flow integral.
- **(e)** 4.47 MiB per accepted interval, humongous share zero (`growMaxLength` 13.57% -> 0.32%, no
  humongous-caused collection). What remains is `temperatureTerms` at 43.55%, transport records at
  9.39%, PR evaluate 6.85%, `PhaseLayout` 6.49%. The live set is not measurable on this run: with
  five young collections and no mixed cycle the after-GC floor is warm-up debris, not live data.

- Measurement only; no solver code changed for it.
- Commit `cc7c845`.

## WP6a - thermo kernel consolidation

Baseline for this work package is `79c6c7e`, captured into `build/probe/reference-wp5`
(`-PfluidRegressionCapture=true -PfluidRegressionReferences=build/probe/reference-wp5`); the two
commits before it changed no code, so this is also the trajectory `dba6179` proved EXACT against
`build/probe/reference-wp3`.

| fixture, 79c6c7e | wall ms | substeps acc/rej | implicit solves | Jacobian builds | residual evaluations | node state() calls | allocated MB | KB per residual |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| quiet 11312, mean of 5 | 36.2 | 11/0 | 5.0 | 0.6 | 132 | 1782 | 29.0 | 225 |
| quiet 11324, mean of 5 | 17.3 | 11/0 | 5.8 | 0.8 | 172 | 1289 | 20.6 | 123 |
| cold 11312, one interval | 1091 | 52/15 | 135 | 41 | 8731 | 98070 | 1135.4 | 133 |
| 100-reservoir chain | 1825 | 45/22 | 135 | 22 | 4149 | 246000 | 3230.3 | 797 |

`temperatureTermsCalls` on the same baseline - the allocation site WP5 measured at 43.55% of the
production pool's remaining allocation - is 762 and 514 per quiet interval, **36 085** on the cold
island and **124 599** on the chain. At three 21x21 matrices (10.6 KB) per call that is 34% of the
cold interval's allocation and 41% of the chain's, which is what step 2 removes.

### WP6a-B2b - reuse the transport ordering and factorization storage across intervals

`ConservativeTransport.reconstruct` built a fresh `SparseLuSolver.Storage` - an EJML LU solver, a CSC
copy, two dense vectors, a sorter and the scaling buffers - and a fresh fill-reducing ordering on
every call, three or four times per interval for the life of the island. That is the one part of B2
the WP2 item never applied. The new `ConservativeTransport.Workspace` holds one storage that every
reconstruction refills in place, plus a four-entry ordering cache keyed by structure; `TrBdf2StepSolver`
creates one and hands it to both of its stage solvers and to its own endpoint-rate reconstruction, so
an island holds exactly one, retained by A2's per-island solver.

Reuse is bitwise by construction. A `Storage` clears every buffer it reads before reading it and its
`verified` flag is reset by each factorization, so a refilled storage behaves as a fresh one; each
transport factorization is consumed inside the reconstruction that produced it, so no holder can be
superseded. The ordering key is the sparsity pattern **and a bitmask of the stored entries that are
exactly zero**, because the reverse Cuthill-McKee graph skips a stored zero - so a cached permutation
is the permutation a fresh computation would have produced, not merely a valid one.

| fixture | wall ms | transport factorizations | transport orderings | LU storages | transport factor ms | transport ordering ms | allocated MB |
|---|---:|---:|---:|---:|---:|---:|---:|
| quiet 11312, mean of 5 | 36.2 -> 34.2-39.2 | 9.0 unchanged | 9.0 -> **1.4** | 9.4 -> **0.6** | 0.41 -> 0.12 | 0.014 -> 0.001 | 29.0 -> 28.9 |
| quiet 11324, mean of 5 | 17.3 -> 17.0-18.3 | 9.0 unchanged | 9.0 -> **0.4** | 9.4 -> **0.6** | 0.18 -> 0.07 | 0.008 -> 0.000 | 20.6 -> 20.5 |
| cold 11312, one interval | 1091 -> 1115-1182 | 269 unchanged | 269 -> **25** | 271 -> **3** | 2.33 -> 1.61-1.77 | 0.25 -> 0.05 | 1135.4 -> 1132.7 |
| 100-reservoir chain | 1825 -> 1789-1855 | 269 unchanged | 269 -> **2** | 271 -> **3** | 3.02 -> 2.23-2.40 | 0.11 -> 0.01 | 3230.3 -> 3223.3 |

Every substep count, implicit solve, Jacobian build, residual evaluation and `state()` call is
unchanged. A warm quiet interval now builds **no** ordering and **no** storage at all; the three
storages left on the transient fixtures are the island's one transport storage and A2/B2's two Newton
ones, created once with the solver. The chain's 269 reconstructions share **two** structures and the
cold island's 269 share 25 (its active set moves nodes between the free and junction blocks), so the
four-entry cache is sized for what the fixtures actually produce. Wall time is inside the run-to-run
spread on every fixture - the item removes 0.2-0.7 ms of a 1.1-1.8 s transient interval and about
0.3 ms of a 5 ms warm one - and the counters are the evidence, as the method says.

**The `IdentityHashMap$KeyIterator` (2.00% of all pool allocation, "worth a look during WP6") is not
ours and is not removable.** All eight samples in `build/reports/fluid/analysis-claude-wp5/alloc-head.txt`
carry the identical stack `IdentityHashMap$KeySet.iterator()` <- `Collections$SetFromMap.iterator()`
<- `jdk.internal.misc.TerminatingThreadLocal.threadTerminated()` <- `Thread.exit()`, and the four
inside the measured window (21:23:32-21:25:32) are on `IO-Worker-1`, `IO-Worker-4`, `Worker-Main-4`
and `modloading-worker-0` - not one of them a solver thread. Their extrapolated weights sum to
214.2 MB against the window's 10.7 GiB, which reproduces the 2.00% exactly, and 202.7 MB of it is the
single sample on the mod-loading worker that finally died five minutes after boot. It is JFR's
per-sample extrapolation applied to four ~32-byte iterator allocations on thread death, not a
per-interval map iteration: no identity map is iterated anywhere on the fluid solver's path
(`ProcessSolveServices.STATES` and `FluidWorldAuthority.SERVERS` are only ever `get`/`put`/`remove`).
Nothing to replace with a list or an array.

- Verified EXACT (bitwise, substep counts included) against `build/probe/reference-wp5` on all four
  fixtures, twice, in two separate runs.
- Gate: 793 JUnit tests, 14 GameTests, green.
- Commit `dff77da`.

### WP6a-1 - promote the column's Peng-Robinson kernel to the shared thermo package

`science/column/v3/thermo/V3PengRobinsonKernel` (package-private) becomes
`science/thermo/PengRobinsonKernel` (public). Every expression is the one the column has been
running, in the same order; the only change is where the critical constants come from. The kernel
took a `V3PropertyPackage` and read `component(i).criticalTemperatureKelvin()` inside
`prepareTemperature`, `evaluateDerivatives` and `wilsonK`; it now reads three arrays the constructor
filled with exactly those doubles, so it no longer depends on the column and the fluid network can
construct one. `V3PengRobinsonSession.kernelFor(V3PropertyPackage)` is the single place that knows
how a V3 package presents that data, and `V3PengRobinsonSession`/`V3PengRobinsonThermo` remain the
column's façade unchanged in behaviour.

Two refactors inside the promoted class, both order-preserving. The `d^2 a_mix/dT^2` block of
`evaluateDerivatives` - the pure-component root derivatives, the interaction-weighted cross sum and
the mixture second derivative - became `secondTemperatureDerivative(T, workspace, rootDt, rootDt2,
crossDt)`, called from exactly where it used to sit, because step 2's volumetric block needs that
scalar and a second expression for it would be a second thing to keep correct. The BIP matrix is
deep-copied rather than adopted, which a public constructor has to do; V3's package already returned
a fresh array per call, so nothing it does changes.

Three accessors were added for step 2 and none of them compute anything: `coVolume(int)`,
`Workspace.compositionView()`/`attractionRowsView()`, `Evaluation.daMixDt()` and the no-copy views of
the fugacity and derivative vectors.

Tests. The root-selection half of `V3PengRobinsonRootPrecisionTest` moved with the kernel to
`science/thermo/PengRobinsonKernelRootPrecisionTest` (the ninety-digit `BigDecimal` oracle, the
Cardano-repair regressions, the coalescence-band branch policy), unchanged but for the type name. Its
one remaining case drives `V3FeedFlash`, which is package-private to the column, so it stayed behind
as `V3PengRobinsonRootPrecisionTest`. `V3PengRobinsonKernelTest` and `V3PengRobinsonDerivativesTest`
stayed in the column's test package as well, and now exercise the promoted class through
`kernelFor`: both are built on `V3Cdu17TiaJuanaPackage`, a 133-line package-private test fixture that
three other V3 tests also use and that implements the package-private `V3PropertyPackage`, so moving
them would have meant copying that fixture and that interface into `science.thermo`. Keeping them
where their data lives is also the stronger evidence for the bitwise claim: they are the column's own
kernel tests, still passing against the promoted class.

- V3 is bitwise unchanged: 483 tests across 86 `science.column.v3` classes pass, including
  `V3PengRobinsonKernelTest`, `V3PengRobinsonDerivativesTest`, `V3PengRobinsonThermoTest`,
  `V3PengRobinsonRootPrecisionTest`, the pinned-state suites (`V3TraceFloorSupportTest`,
  `V3ConvergenceClosureTest`, `V3PumparoundCalculatorTest`, `V3SideDrawContractTest`,
  `V3SteamFeedContractTest`, `V3FlashTruncationColumnTest`) and the Holland benchmark case.
- Fluid trajectory: EXACT against `build/probe/reference-wp5` on all four fixtures, trivially - this
  commit touches no fluid code - and run anyway.
- Gate: 793 JUnit tests, 14 GameTests, green.
- Commit `7d26793`.

### WP6a-C3 - evaluate the network's Peng-Robinson phases on the shared kernel

`TranslatedPengRobinson.TemperatureTerms` is gone, and with it the three 21 x 21 matrices it built for
every distinct node temperature - 43.55% of all remaining allocation on the production pool, 34% of the
cold island's interval and 41% of the chain's. `evaluate` now takes Z, `ln phi_i`, `H^R`, `a`, `b`,
`da/dT`, `d2a/dT2` and the row sums `S_i` from the promoted kernel; what stays in this class is what the
kernel does not model - the ideal-gas caloric polynomials, the constant volume translation, and the
volumetric block (dv/dT, dv/dP, d2v/dT2, cp, dh/dP, the isothermal compressibility, the partial molar
volumes), whose algebra is unchanged and is now applied to the kernel's terms.

**The sparse-pair mixing plan (C3).** The kernel gained a second, explicitly selected plan. The classical
rule is unchanged - {@code a_ij = sqrt(a_i a_j)(1 - k_ij)} - but the row sums are reached as the rank-one
sum corrected by the few nonzero pairs, `S_i = sqrt(a_i)(sum_j q_j - sum_{j: k_ij != 0} k_ij q_j)`, which
for `createcheme:tjl20_methane` plus nitrogen is 21 components and **11 pairs** instead of a 441-entry
quadratic form. `a_mix` and `da_mix/dT` are then accumulated with the identical expressions the dense
branch uses, so the two plans differ only in how each `S_i` was summed. The plan is selected by the fluid
network's constructor alone: **V3 keeps `Mixing.CLASSICAL` and its bitwise arithmetic.**

**The prepared temperature.** A `TranslatedPengRobinson.Workspace` replaces `TemperatureTerms` on
`FluidThermodynamics.Prepared`, so the per-node exact-temperature cache from C1/C4 now carries the
kernel's 3n pure-component vectors, the ideal-gas cp/h vectors and the root-derivative vectors instead of
3n^2 + 5n doubles. Per evaluation the only allocation left is the `Phase` record and its two output
arrays: the composition is normalized into the workspace rather than cloned, and the mixture sums are
formed in place.

Two order-preserving optimizations came out of the measurement rather than the plan, and both are bitwise
on every path including V3's:

- `d sqrt(a_i)/dT` and its own derivative depend on temperature alone - the dense form computed them once
  per temperature into its matrices - so `prepareRootDerivatives` fills them with the prepared temperature
  and `mixtureSecondTemperatureDerivative` does only the composition-weighted sums per evaluation. That is
  21 square roots and 63 divisions per phase evaluation removed. `secondTemperatureDerivative` still calls
  the two in sequence for `evaluateDerivatives`, in the order that method always used.
- The kernel's fugacity loop recomputed `Math.log(z - reducedB)` on every component. The argument does not
  depend on the component, so hoisting it is the same double n times over: one logarithm per evaluation
  instead of 21.

| kernel, median of 2000 after 2000 warm-up calls | ns at 7d26793 | ns here |
|---|---:|---:|
| `state(...)` with the prepared temperature | 2200 | **1800** |
| `state(...)` without it | 8300 | **3900** |
| `prepare(T)` | 100 | 100 |
| `flashTP` (standalone TP initializer) | 147 700 | **77 600** |

| fixture | wall ms | substeps acc/rej | implicit solves | Jacobian builds | residual evaluations | node state() calls | allocated MB | KB per residual |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| quiet 11312, mean of 5 | 34.2-39.2 -> 28.8-35.6 | 11/0 unchanged | 5.0 | 0.6 | 132 | 1782 | 28.9 -> **18.6** | 225 -> 145 |
| quiet 11312, warm intervals 2-4 | 4.6-5.9 -> 4.0-6.3 | 1/0 each | 2 | 0 | 4 | 300 | 5.5 -> **3.2** | - |
| quiet 11324, mean of 5 | 17.0-18.3 -> 14.0-15.6 | 11/0 unchanged | 5.8 | 0.8 | 172 | 1289 | 20.5 -> **14.6** | 123 -> 87 |
| cold 11312, one interval | 1115-1182 -> **936-947** | 52/15 unchanged | 135 | 41 | 8731 | 98070 | 1132.7 -> **718.9** | 133 -> 84 |
| 100-reservoir chain | 1789-1855 -> **1407-1600** | 45/22 unchanged | 135 | 22 | 4149 | 246000 | 3223.3 -> **1759.7** | 795 -> 434 |

Every substep count, implicit solve, Jacobian build, residual evaluation and node decode is unchanged.
Allocation falls 36% on quiet 11312, 29% on quiet 11324, **37%** on the cold island and **45%** on the
chain, which is `temperatureTerms`' share removed and nothing else: at 21 components its three matrices
were 10.6 KB per prepared temperature, against 36 066 and 124 693 preparations on the two transients.
Wall time falls 15-20% on the transients; the quiet fixtures' means are dominated by their two Jacobian
intervals and their spread is wider than the effect, but their warm intervals now allocate 3.2 MB where
they allocated 5.5.

**The oracle.** `LegacyTranslatedPengRobinson` (test only) is this class as it stood at `7d26793`, kept
verbatim - the dense matrices, the classical mixing read out of them, `PengRobinson78.compressibilityRoot`
for Z. `TranslatedPengRobinsonOracleTest` holds every field of every `Phase` against it over 8
temperatures x 6 pressures x both roots x 13 compositions (the assay-like heavy mixture, a light gas, one
with six components at 1e-12, one with most components exactly zero, pure nitrogen, pure methane,
equimolar, nitrogen plus the heaviest pseudo, and five from a fixed seed), twice: once on the package's
real 11 pairs and once with the interaction matrix zeroed, which is the degenerate case of the same plan.

The tolerance is argued from the arithmetic: the mixture sums carry a relative error of order `n * eps`,
about 2.4e-15 over 21 components, and the volumetric block amplifies it through the `v - b` and
`v^2 + 2bv - b^2` cancellations by two or three orders, so **1e-12**. The **measured maximum is 9.2e-13**
(a partial molar volume in the zero-interaction case); every other field is at or below 3.7e-13, and the
median deviation of every field is zero or one ulp.

That holds on the 999 and 1012 grid points where the legacy's own compressibility is backward accurate.
On the other 249 and 236 - the sub-kilopascal liquid root, where Z sits within a few ulp of B and the
plain analytic Cardano formula loses most of its digits - the two differ by up to 4e-4 relative, and that
difference is the kernel's root repair doing its job, not a regression: the test recomputes the cubic
independently from the classical mixing rule and the PR78 critical constants and requires that the
kernel's compressibility leave the strictly smaller residual in it. **0 regressions in 485 such states.**
Nothing in production reaches them either way: `HydrocarbonModel` evaluates the liquid root only at its
2 MPa reference pressure.

Accuracy against the 154007d references, under the declared gate - unchanged from C1/A4b to three digits,
so this item's own roundoff does not move the trajectory the previous work packages established:

| quantity | gate | quiet 11312 | quiet 11324 | cold 11312 | chain |
|---|---:|---:|---:|---:|---:|
| state / moles, relative | 1e-6 | 9.97e-10 | 6.19e-10 | 3.92e-10 | 1.84e-9 |
| temperature, K | 1e-4 | 1.25e-9 | 7.30e-10 | 4.30e-9 | 1.77e-8 |
| phase volume fraction | 1e-6 | 5.50e-12 | 3.49e-12 | 1.99e-11 | 8.25e-11 |
| average flow, worst absolute kg/s | - | 2.6e-9 | 7.9e-9 | 3.2e-9 | 4.7e-9 |
| controller allowance there, kg/s | - | 3.18e-8 | 3.18e-8 | 3.18e-8 | 3.16e-8 |

The roundoff this commit introduces on its own, against `build/probe/reference-wp5`: **4.8e-12 and
6.9e-12 relative on state** for the two quiet islands, **8.9e-14 and 1.3e-13** for the cold island and the
chain; 2.8e-13 to 1.1e-12 K; at most 5.8e-15 on a phase fraction; and at most 5.6e-11 kg/s on a flow,
which is 0.18% of the controller's own allowance for that pipe. The promised ~1e-12.

- Gate: 794 JUnit tests (1 new), 14 GameTests, green. Every `science.column.v3` suite passes unchanged,
  which is the bitwise evidence for the column: the sparse-pair plan is selected by
  `TranslatedPengRobinson`'s constructor and by nothing else.
- Commit `ac4f120`.

### WP6a-B4 - expose the phase derivatives for the analytic node Jacobian

API only; the solver is bitwise unchanged. `TranslatedPengRobinson.differentiate` fills a caller-owned
`Derivatives` bundle - one per solve, refilled per node, never allocating - with the value block and every
first derivative of it:

| exposed | from | note |
|---|---|---|
| `logFugacityCompositionDerivative(i,j)`, `...RowView(i)` | the kernel's `d ln phi_i/dn_j` | the translation does not depend on composition, so this is the kernel's unchanged |
| `logFugacityTemperatureDerivativeView()` | the kernel's `d ln phi_i/dT` minus `P c_i/(R T^2)` | the translation's own temperature derivative |
| `logFugacityPressureDerivativeView()` | `v_i/(R T) - 1/P` | the exact identity, on the translated partial molar volumes, so the translation cancels |
| `partialMolarEnthalpyView()` | ideal + the kernel's `dH^R/dn_i` + `P c_i` | these sum to the molar enthalpy |
| `partialMolarResidualEnthalpyView()` | the kernel's, untranslated | `-R T^2 d ln phi_i/dT` |
| `residualEnthalpyTemperatureDerivative()` | the kernel's `dH^R/dT` at constant P | |
| `values()` | v, h, u, dv/dT, dv/dP, cp, dh/dP, the isothermal compressibility, d2v/dT2, Z, the ideal-gas cp, the partial molar volumes and `ln phi_i` | the same numbers a `Phase` carries, in a buffer instead of a record |

`FluidThermodynamics.newHydrocarbonDerivatives()` and `hydrocarbonDerivatives(t,p,amounts,root,prepared,out)`
are the entry from a prepared node temperature, so WP6b reaches this without touching package internals.
The documented boundary: the bundle is the equation of state at the pressure it was evaluated at.
`HydrocarbonModel.phase` passes a vapor its own pressure but evaluates a liquid at the 2 MPa reference and
then corrects it with `GlobalLiquidResponse`; a liquid node block has to differentiate that correction
itself, and the free water and the ideal water vapor that `state` composes on top are likewise the caller's.
Nothing here applies them, and the javadoc says so at both entry points.

`evaluate` and `differentiate` now share one body: the volumetric block writes into a `Values` buffer that
the workspace owns for `evaluate` (which copies out of it into the immutable `Phase`, the same two arrays it
always allocated) and that the `Derivatives` owns for `differentiate` (which copies nothing).

`TranslatedPengRobinsonDerivativesTest` holds every exposed derivative against central differences over 9
states x both roots on the 21-component network package, refusing any probe whose endpoints do not stay on
the same cubic root. Steps: 1 mK, 1 umol in a mole, one part in ten thousand of the pressure. **Worst
agreement 7.6e-8 relative** (`dv/dP`, a quantity of order 1e-13 with heavy cancellation); `d ln phi_i/dn_j`
6.4e-9 over 7182 comparisons, `d ln phi_i/dT` 2.1e-10 over 378, `d ln phi_i/dP` 3.6e-9, the partial molar
enthalpies 1.1e-8 over 342, the partial molar volumes 1.4e-8, `cp` 1.8e-10, `d2v/dT2` 8.8e-9. Two identities
need no differencing and hold to roundoff: the partial molar enthalpies sum to the molar enthalpy to
**4.4e-15**, and the volumetric heat capacity equals the ideal-gas capacity plus the kernel's `dH^R/dT` -
a completely separate expression - to **1.9e-15**.

- Verified EXACT (bitwise, substep counts included) against a capture of `ac4f120` in
  `build/probe/reference-wp6a-step2`, on all four fixtures.
- Gate: 795 JUnit tests (1 new), 14 GameTests, green.
- Commit `4988efd`.

### WP6a summary: 79c6c7e -> 4988efd

Measured at `4988efd` itself, two runs in the same session as the `79c6c7e` baseline above.

| fixture | wall ms | allocated MB | substeps acc/rej | Jacobian builds | residual evaluations | node state() calls | transport orderings | LU storages |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| quiet 11312, mean of 5 | 36.2 -> 28.1-37.4 | 29.0 -> **18.9** | 11/0 | 0.6 | 132 | 1782 | 9.0 -> **1.4** | 9.4 -> **0.6** |
| quiet 11324, mean of 5 | 17.3 -> **12.7-13.9** | 20.6 -> **14.8** | 11/0 | 0.8 | 172 | 1289 | 9.0 -> **0.4** | 9.4 -> **0.6** |
| cold 11312, one interval | 1091 -> **809-922** | 1135.4 -> **735.4** | 52/15 | 41 | 8731 | 98070 | 269 -> **25** | 271 -> **3** |
| 100-reservoir chain | 1825 -> **1410-1434** | 3230.3 -> **1816.8** | 45/22 | 22 | 4149 | 246000 | 269 -> **2** | 271 -> **3** |

The allocation figures are 2-3% above the ones in the C3 row because B4's `Values` buffer is two n-vectors
per prepared temperature - 368 B against the 10.6 KB of matrices it replaced, 13 MB on the cold island and
46 MB on the chain, which is the price of `evaluate` and `differentiate` sharing one body. Wall time on the
quiet fixtures is dominated by their two Jacobian intervals and its spread is wider than the item's effect;
their warm intervals allocate 3.2 MB where `79c6c7e` allocated 5.5.

Every substep count, implicit solve, Jacobian build, residual evaluation and node decode is unchanged
across the whole work package: it changes what an evaluation costs, never what the solver decides.
`state(...)` with a prepared temperature 2200 -> 1800 ns and without it 8300 -> 3900 ns; the standalone TP
initializer 147.7 -> 77.6 us.

What is left of the WP5 allocation census on these fixtures: `temperatureTerms`, which was 43.55% of the
production pool's remaining allocation, is gone; the transport storages (1.19%) are gone. The next
candidates it named - the `PipeTransfer`/`ConservativeTransport` records at 9.39%, `PhaseLayout` at 6.49%,
`PassiveNetwork$Inventory.moles`' defensive clone at 1.93% - are untouched, and B4's analytic node blocks
now have the derivative bundle they need.

## WP6b - analytic node Jacobian

Baseline for this work package is `9e7d81b`, captured into `build/probe/reference-wp6a`
(`-PfluidRegressionCapture=true -PfluidRegressionReferences=build/probe/reference-wp6a`).

| fixture, 9e7d81b | wall ms | allocated MB | substeps acc/rej | implicit solves | Jacobian builds | LU | residual evaluations | node state() calls | of those in the Jacobian |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| quiet 11312, 5 intervals | 154.5 | 93.0 | 11/0 | 25 | 3 | 3 | 659 | 8910 | 4320 |
| quiet 11324, 5 intervals | 74.9 | 73.0 | 11/0 | 29 | 4 | 4 | 859 | 6444 | 3456 |
| cold 11312, one interval | 935.3 | 716.8 | 52/15 | 135 | 41 | 41 | 8731 | 98070 | 59040 |
| 100-reservoir chain | 1437.7 | 1770.3 | 45/22 | 135 | 22 | 22 | 4149 | 246000 | 105600 |

Wall time is the median of three runs in one session; the counters are exact and are the primary
evidence, as the method says.

### WP6b step 0 - where the transient residual evaluations go

Eleven new counters split the Newton loop by what a residual evaluation was spent on and where a
search direction came from (`newtonSolvesPreconditioned`, `newtonIterationsPreconditioned`,
`newtonIterationsFresh`/`Stale`, `newtonStepEvaluationsFresh`/`Stale`, `newtonMeritProbes`,
`newtonRefreshesStalled`/`Aged`/`Failed`), two count the residual evaluations that never reach
`SparseNewton` at all (`verificationResiduals` for the reconstruction's equation gate,
`companionFilterResiduals` for the linear filter's self-check), and `stateCallsInJacobian` splits the
node decodes the same way the existing `residualEvaluationsInJacobian` splits the evaluations.

The counters close exactly, on every fixture:
`residualEvaluations = newtonSolves + residualEvaluationsInJacobian + newtonIterations + newtonBacktracks`
- one opening evaluation per Newton solve, the coloured sweep, and one line-search evaluation per
iteration plus one per backtrack.

| bucket, cold 11312 (one interval) | count | share of 8731 |
|---|---:|---:|
| Jacobian colouring (41 builds x 191 colours) | 7831 | 89.7% |
| Newton opening evaluation (one per solve) | 135 | 1.5% |
| line search on a fresh Jacobian | 41 | 0.5% |
| line search on a stale chord | 724 | 8.3% |
| backtracks (included in the two lines above) | 4 | - |
| *outside* `SparseNewton`: reconstruction equation gate | 135 | - |
| *outside* `SparseNewton`: companion filter self-check | 50 | - |

| bucket, 100-chain (one interval) | count | share of 4149 |
|---|---:|---:|
| Jacobian colouring (22 builds x 143 colours) | 3146 | 75.8% |
| Newton opening evaluation | 135 | 3.3% |
| line search on a fresh Jacobian | 25 | 0.6% |
| line search on a stale chord | 843 | 20.3% |
| backtracks (included above) | 26 | - |
| reconstruction equation gate / companion self-check | 135 / 67 | - |

| chord reuse | cold 11312 | 100-chain |
|---|---:|---:|
| Newton iterations per implicit solve | 761 / 135 = **5.64** | 842 / 135 = **6.24** |
| solves that opened on an inherited factorization | **133 of 135** | **133 of 135** |
| iterations spent by those solves | 752 of 761 | 829 of 842 |
| iterations whose direction came from a chord | **720 (94.6%)** | **820 (97.4%)** |
| iterations that built their own Jacobian | 41 | 22 |
| refreshes: stalled (contraction > 0.8) / aged (8 iterations) / failed step | 13 / 33 / 0 | 0 / 44 / 0 |
| LU factorizations | 41 | 22 |
| triangular solves (761 + 5 merit + 50 companion; 842 + 26 + 67) | 816 | 935 |
| merit probes | 5 | 26 |

**What this says about how much a cheaper Jacobian can buy.** The colouring is 90% of the cold
island's residual evaluations but only part of its wall time: the same counters put 41 LU
factorizations at 325 ms and 816 triangular solves at 89 ms of a 935 ms interval, and 98 070 node
decodes at roughly 310 ms. A Jacobian build is therefore about 7.6 ms of factorization on top of its
assembly, so **refreshing more often cannot pay**: the 41 builds already cost about 500 ms of the
interval, and refreshing on every one of the 761 iterations would cost 5.8 s of factorization alone
unless the iteration count fell eighteenfold. Step 3 is measured in both directions for that reason.

The second finding sets step 1's ceiling. The coloured sweep already decodes each node **once per one
of its own columns**: 59 040 decodes over 41 builds is 1440 per build, and 1440 = 30 nodes x (47
columns + one restore). Greedy colouring numbers the columns in index order, so a colour group is one
(column, independent node set) pair and consecutive groups perturb the same nodes. A block sweep's
floor is 30 x 47 = 1410 per build - **2% fewer decodes, not fewer**. What a block sweep removes is
the whole-island assembly behind each of the 191 colours, not the properties.

### WP6b step 1 - build the Jacobian from per-node block perturbations

`Equations.residual` is now three passes over shared per-node and per-edge assemblies -
`nodeAccumulate` (this node's backward-Euler targets, energy, junction inflow and net flow, from the
edges incident to it in edge order), `edgeRows` (one edge's hydraulic and actuator rows) and
`nodeRows` (the block that node owns) - and `SparseNewton.Equations` gained an optional
`differentiateEntries` that fills the pattern's values directly. `PassiveStepSolver.Equations`
implements it by perturbing one local column at a time: the node is decoded at the perturbed point
and only the rows that can see it are re-assembled - its own block, the hydraulic and actuator rows
of its incident edges, and the *inflow rows only* of a neighbour across an edge this node donates
across. The flow and actuator columns sweep the same way over their own edge, where no node is
decoded again at all.

Bitwise identity is by construction rather than by luck. Every row comes out of the same three
methods the whole-island residual calls, so no formula is restated; the accumulators are refilled in
edge order, which is the order the island loop reached them in, so the sums are the same doubles; and
a row a column cannot move is seeded from the base residual instead of recomputed, which is the value
the coloured sweep would have differenced to exactly zero. Two rules earn that seeding: a neighbour's
volume closure, equilibrium and normalization rows read its decoded state alone
(`PhaseLayout.balanceRows` and `junctionRows` are the split that makes this expressible without a
second copy of the arithmetic), and a neighbour is reached at all only when this node is the donor on
that edge or meters a pump's shaft power on it. Parallel pipes reach the same neighbour twice, so
every neighbour is seeded before any is recomputed.

The sparsity pattern, the column ordering, the RCM ordering and the numeric matrix are untouched -
`differentiateEntries` fills exactly `columnRows()`'s entries in exactly its order and hands them to
the same factorization step - so B2's workspace and fork-family storage keys stay valid. Verified:
the `jacobianNonzeros`, `luStorages`, `luOrderings` and `transportOrderings` counters are identical
on all four fixtures.

A perturbed node that leaves the property domain hands the whole build back to the coloured sweep,
which has the bounded one-sided stencil for it (`jacobianBlockFallbacks`). **It fired 0 times on all
four fixtures.**

| fixture | wall ms | allocated MB | residual evaluations | block columns | node decodes | of those in the Jacobian |
|---|---:|---:|---:|---:|---:|---:|
| quiet 11312, 5 intervals | 154.5 -> 153.9 | 93.0 -> **85.4** | 659 -> 86 | 4365 | 8910 -> 8820 | 4320 -> 4230 |
| quiet 11324, 5 intervals | 74.9 -> **72.3** | 73.0 -> **65.8** | 859 -> 95 | 3492 | 6444 -> 6372 | 3456 -> 3384 |
| cold 11312, one interval | 935.3 -> **916.9** | 716.8 -> **605.3** | 8731 -> 900 | 59655 | 98070 -> 96840 | 59040 -> 57810 |
| 100-reservoir chain | 1437.7 -> **1369.6** | 1770.3 -> **1687.7** | 4149 -> 1003 | 105578 | 246000 -> 243800 | 105600 -> 103400 |

Substep counts, implicit solves, active-set passes, Jacobian builds, LU factorizations, triangular
solves, orderings and reconstructions are all unchanged; the residual-evaluation column falls to the
Newton loop's own evaluations because the sweep no longer goes through one.

Wall time is the median of three runs of each tree in the same session (`build/probe/wp6b/base-*.json`
and `block-*.json`); the spreads were 925-1023 against 905-950 ms on the cold island and 1420-1444
against 1358-1417 ms on the chain. The measured effect is **-2% to -5% on the transients and inside
the spread on the quiet islands**, which is what the step-0 census predicts and is worth stating
plainly: a coloured build assembles 191 x (30 node blocks + 45 edge rows), a block build 1410 full
node blocks plus about 2100 partial ones and 4200 edge rows, so the expensive part - the 42
logarithms in a node's equilibrium rows - falls about fourfold, not sixtyfold, and the decodes and
the LU do not move at all. Allocation is the clearer gain: the colour loop's `x.clone()` (11.6 KB per
colour) and the fresh residual array per evaluation (11.6 KB) are both gone, along with the
`ArrayList` every residual built to return its states, which is **16% of the cold island's allocation
and 10% of quiet 11324's**.

- Verified EXACT (bitwise, substep counts included) against `build/probe/reference-wp6a` on all four
  fixtures, twice.
- `RetainedSolverTest.sharingTheIslandSolverWithTheModulePlannerNeverCostsMoreWork` measured its
  "sharing never costs more work" invariant on `residualEvaluations`, which no longer spans a
  Jacobian build. It now measures `stateCalls`, the node decodes, which span both halves.
- Gate: 795 JUnit tests, 14 GameTests, green.
