# V3 110 kPa timeout diagnosis — 2026-08-31

## Conclusion

The reported timeout reproduces with **stage trace truncation disabled and no game queue**. The untruncated reproduction exposes condenser-branch recovery ordering and expensive linear algebra as the immediate causes.

The first untruncated 30-stage liquid-only candidate actually converges numerically. Its independent condenser-phase audit rejects it. The calculator then spends the remaining budget on a fresh material-closed solve of that same branch before it can try the alternate branch. The full-call profile is dominated by dense normal-equation construction and repeated whole-matrix pivot-growth scans.

This investigation adds benchmark tooling only. It does not change production solver behavior, the 45-second service deadline, or the configured cutoff.

## Reproduction and controls

Serial public-facade calls, no Minecraft client/executor, Java 21.0.11 HotSpot, 512 MiB initial / 2 GiB maximum heap, 16 logical processors; CPU identifier `AMD64 Family 26 Model 68 Stepping 0, AuthenticAMD`. One 150 kPa warmup precedes the measured matrix. Times exclude Gradle startup and input construction, but include checkpoint observations. These are diagnostic timings, not throughput claims or statistical confidence intervals.

Fixed input: registered TJL assay / CDU17 package, 30 stages, feed stage 24, feed 638.15 K, stage pressure drop 750 Pa, condenser 323.15 K, organic reflux ratio 2. The user's 2610.7 kmol/h corresponds to 725.1944444444445 mol/s. The assay vector is reconstructed from registered data, not captured bit-for-bit from the historical UI input.

| Case | Feed kmol/h | Duty MW | Cutoff fraction | Wall s | Solver-thread CPU s | Outcome |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| 150 kPa | 2610.7 | 8 | 0 | 14.269 | 14.234 | Success |
| 110 kPa | 2610.7 | 8 | 0 | 90.070 | 89.672 | Diagnostic deadline |
| 110 kPa | 2610.7 | 8 | 1e-6 | 90.000 | 89.672 | Diagnostic deadline |
| 100 kPa | 2610.7 | 8 | 0 | 19.502 | 19.422 | Property failure: feed flash did not converge in 64 iterations |
| 110 kPa, existing-test flow | 2000 | 8 | 0 | 25.088 | 24.938 | Success |
| 110 kPa, matched duty/feed | 2610.7 | 10.4428 | 0 | 10.848 | 10.781 | Success |

Raw matrix: [v3-timeout-initial.json](results/v3-timeout-2026-08-31/v3-timeout-initial.json). The warmup succeeded in 14.634 s. GC consumed 210 ms and 212 ms in the two 90-second timeout cells, respectively. Allocation was cumulative 49.25 GB / 47.04 GB, **not** resident heap usage. CPU time closely follows wall time, so neither queue wait nor GC is required to reproduce this timeout.

The existing `V3LowPressureColdProbeTest` exercises 2000 kmol/h at 8 MW, not the reported flow. Holding 8 MW while increasing feed changes heat input from 14.4 to 11.032 kJ/mol. The matched-duty control restores 14.4 kJ/mol. These are different operating points with the same numerical system size; their successes do not qualify the original operating point.

## Where the request goes

1. `V3ColumnCalculator.preferredCondenserBranch` flashes a **4-stage initializer** and selects liquid-only.
2. The 4, 8, and 15-stage liquid-only rungs converge and pass their audits.
3. The 30-stage liquid-only rung converges with maximum scaled residual `9.897446759882858e-9`, and passes final-Newton-step, equilibrium, energy, material, and topology gates. Only `CONDENSER_PHASE` fails: `outlet TP flash requires a vapor phase`.
4. `solveDwsimStageContinuation` recognizes that mismatch and skips projected rung recovery, but its returned pass still sets `allowsFreshMaterialClosedFallback=true` because this is the requested stage count.
5. `calculateBranch` starts a fresh 30-stage `MATERIAL_CLOSED` solve **on the same condenser branch**. Checkpoint stacks at about 20 seconds and afterward are in this fallback, through `solve:290` and damped Gauss-Newton.
6. The alternate condenser branch is attempted only after `calculateBranch` returns. Deadline cancellation escapes first, so the alternate is never reached in these timed-out facade runs.

See [V3ColumnCalculator.java](../src/main/java/com/wormzjl/createcheme/science/column/v3/V3ColumnCalculator.java), particularly lines 89–100, 154–164, and 288–299. The branch/recovery ordering and dense fallback already exist in baseline commit `6185117`; they were not introduced by the stage-trace wiring.

First-path trace: [v3-probe-stage-local.json](results/v3-timeout-2026-08-31/v3-probe-stage-local.json). It takes approximately 19 seconds total, of which 15.91 seconds is the final rung, including diagnostic observation overhead. The residual is near its tolerance well before that rung ends; this is not evidence of 128 endlessly accepted local directions.

A repeat with [explicit outlet flash evidence](results/v3-timeout-2026-08-31/v3-probe-stage-local-flash.json) reproduced the same final candidate and rejection. The 4/8/15-stage outlets flash liquid. The 30-stage outlet flashes **two-phase with vapor fraction 0.0006356535322307919 (0.063565 mol%)**, after 38 PR iterations, not a Wilson-only shortcut or an infinitesimal endpoint residue. Thus a small but resolved condenser-phase transition occurs along this continuation path under the implemented thermodynamic model.

The isolated fresh material-closed fallback is much farther from convergence: residual 35.51 initially, 29.95 at iteration 10, 30.05 at iteration 20, and last observed at 25.24 at iteration 36 shortly before its separate 45-second deadline fires. [Fallback trace](results/v3-timeout-2026-08-31/v3-probe-material-closed.json). Duplicate FINE-Jacobian/LU diagnostics at iterations 0/1/2 succeed; the later full-facade `solve:290` stacks establish that later Newton linear solves fail and invoke regularized recovery. A first-step singularity is not the explanation.

A [second linear diagnostic](results/v3-timeout-2026-08-31/v3-probe-material-closed-linear.json) samples iterations 0/10/20. The duplicate solve succeeds at 0, but returns `SINGULAR: V3 banded LU encountered a zero or tiny pivot` at both 10 and 20. These Jacobians have no off-band entries and no columns whose maximum magnitude is at or below `1e-10`; this is not an entirely zero-column artifact. The extra diagnostic work totals 0.57 seconds. This confirms the later linear-failure route into regularized recovery without asserting a unique underlying cause of rank/conditioning loss.

## CPU profile

A separate warmed run recorded per-case JFR profiles, with the ordinary 45-second budget. The 110 kPa off case again timed out: 45.412 s wall, 45.188 s CPU, 111 ms GC. Slight deadline overshoot is consistent with cooperative checkpoints. The 150 kPa control succeeded in 14.688 s.

| Inclusive sampled operation | 110 kPa off, 2792 samples | 150 kPa off, 875 samples |
| --- | ---: | ---: |
| `dampedNormalMatrix` | 1031 / 36.9% | 200 / 22.9% |
| `SparseRows.maximumAbsoluteValue` | 1249 / 44.7% | 463 / 52.9% |
| All `V3BandedPivotedSolver.solve` | 1735 / 62.1% | 667 / 76.2% |

These are stack-sample shares, not instrumented wall-time percentages. The last row **includes** the matrix-scan row; do not add them. Sampling shows linear algebra, not thermodynamic property evaluation, dominates these runs.

- `V3SimultaneousColumnSolver.dampedNormalMatrix` constructs dense `JᵀJ` before extracting its band. At 976 coordinates that is 465,333,376 multiply-add terms per construction, even though the original Jacobian is stage-banded. The damping loop may rebuild it up to eight times for the same Jacobian; final-Newton verification has another regularized path. Dense defensive copies add allocation.
- `V3BandedPivotedSolver.solve` invokes `SparseRows.maximumAbsoluteValue` after **every pivot**. That helper traverses all row maps and stored values, including already factored rows, solely to update pivot-growth evidence. For bounded fill width b this introduces approximately O(n²b) traversal work. TreeMap lookups, iteration, boxing, and column scaling add further costs. This is present even in successful controls.

Raw profile summary: [v3-timeout-profile.json](results/v3-timeout-2026-08-31/v3-timeout-profile.json). JSON evidence is preserved under `benchmarks/results/v3-timeout-2026-08-31`, outside disposable build output. The large JFR recordings remain in `build/reports/benchmarks` as `v3-timeout-profile.json-150-off-0.jfr` and `v3-timeout-profile.json-110-off-0.jfr` (removed by Gradle clean). Checkpoint stacks in the JSON are breadcrumbs, not CPU samples; the profile table uses only JFR `jdk.ExecutionSample` events for the solver thread.

## Counterfactual scope and next work

Forcing two-phase for the entire 4→8→15→30 ladder fails at the first 4-stage rung with `LINEAR_SINGULAR`; its condenser vapor tends to zero and its overall outlet flash is liquid. [Trace](results/v3-timeout-2026-08-31/v3-probe-two-phase.json). This does not establish that a two-phase **30-stage** solution is infeasible. It means a universal branch override is not a demonstrated fix: branch suitability can change across stage continuation.

Bypassing the small grids and directly initializing the 30-stage two-phase problem also did not produce an accepted result within 45 seconds. Its residual decreased from 42.40 to 0.00670 by iteration 29, but remained far above `1e-8`; all 56 observed local-block proposals were rejected. [Single-stage counterfactual](results/v3-timeout-2026-08-31/v3-probe-single-two-phase.json). Therefore simply forcing two-phase, or merely changing branch order, has not been demonstrated to solve the latency problem. Phase-aware seed transfer and linear-algebra work remain necessary areas to investigate.

The phase audit establishes how this implementation routes the request, not independent proof of a unique thermodynamic equilibrium. The initializer and final candidate have different compositions. Do not weaken the phase audit or assume its seed heuristic should always agree with the final state.

Recommended follow-up, requiring a separate implementation request:

1. Handle a converged candidate's condenser-phase rejection before generic same-branch recovery. Evaluate branch-aware continuation / phase-transition seeding, preserving all independent acceptance gates. Alternate-first scheduling is a candidate, not yet a demonstrated complete fix.
2. Remove the measured linear-algebra overhead: preserve band structure when constructing regularized systems, reuse damping-independent products, and maintain pivot-growth evidence without repeated whole-matrix scans. Validate numerical equivalence and all conditioning guards.
3. Add the exact high-flow 110 kPa case to regression/latency qualification, plus phase-boundary cases across intermediate and final grids. Expose branch/rung/recovery timing on deadlines; current final diagnostics only describe the selected terminal attempt.

Increasing the deadline or changing the user's duty would conceal or alter the problem, not fix this solver path. The 100 kPa flash failure is a separate observed issue, not evidence that pressure continuation would fix this case.

## Reproduce

Run from this implementation worktree with JDK 21; run commands **serially** with the game closed. PowerShell requires quoting the full Gradle property arguments shown here.

```powershell
./gradlew.bat v3TimeoutBenchmarkHarnessTest v3TimeoutBenchmark --offline --no-daemon --console=plain '-Pv3DeadlineSeconds=90' '-Pv3Report=build/reports/benchmarks/v3-timeout-initial.json'
./gradlew.bat v3TimeoutBenchmark --offline --no-daemon --console=plain '-Pv3Cases=150-off,110-off' '-Pv3Profile=true' '-Pv3DeadlineSeconds=45' '-Pv3Report=build/reports/benchmarks/v3-timeout-profile.json'
./gradlew.bat v3ContinuationProbe --offline --no-daemon --console=plain '-Pv3ProbeMode=stage-local' '-Pv3DeadlineSeconds=45' '-Pv3Report=build/reports/benchmarks/v3-probe-stage-local-flash.json'
./gradlew.bat v3ContinuationProbe --offline --no-daemon --console=plain '-Pv3ProbeMode=stage-local' '-Pv3ProbeBranch=two-phase' '-Pv3DeadlineSeconds=45' '-Pv3Report=build/reports/benchmarks/v3-probe-two-phase.json'
./gradlew.bat v3ContinuationProbe --offline --no-daemon --console=plain '-Pv3ProbeMode=single-stage-local' '-Pv3ProbeBranch=two-phase' '-Pv3DeadlineSeconds=45' '-Pv3Report=build/reports/benchmarks/v3-probe-single-two-phase.json'
./gradlew.bat v3ContinuationProbe --offline --no-daemon --console=plain '-Pv3ProbeMode=material-closed' '-Pv3ProbeBranch=liquid-only' '-Pv3LinearDiagnostics=3' '-Pv3DeadlineSeconds=30' '-Pv3Report=build/reports/benchmarks/v3-probe-material-closed-linear.json'
```

Probe modes are deliberately narrower than the facade: no automatic failed-rung recovery, coarse stencil, or alternate-branch fallback. `stage-full` also disables frozen-Jacobian reuse, not only local blocks. Its FD counters count observer callbacks, not every FD evaluation; final-step verification and optional duplicate linear diagnostics are not included. Current `-Pv3LinearDiagnostics=3` samples iterations 0/10/20; the initial material-closed report predates that sampling change and records 0/1/2. All probe timing includes observation overhead. The public harness now checks its deadline after facade return, matching the service's post-command boundary; initial matrix successes were far below budget and unaffected by this addition.
