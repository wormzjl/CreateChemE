# Why convergence improved so little

2026-09-05. Analysis of `codex/v3-solver-core-fixes` against frozen `main` at `bdfeff1`. Production code was not changed during this analysis. Three GPT-5.6 Terra/high subagents reviewed the existing results and current source; one additional bounded baseline replay was run separately from the benchmark.

The patch is primarily a performance improvement. It does not yet demonstrate an expanded set of cases the solver can solve. The benchmark's 26/64 to 27/64 acceptance gain is valid under its 60-second deadline, but **main also solves the sole additional case with more time**.

## The apparent convergence win is a deadline win

The new probe used the same frozen baseline, input, cutoff, timing JVM flags, and three warmup controls, with a 120-second public-call allowance and 135-second watchdog. It returned `SUCCESS_REDUCED` for `N64-on` in **83.292 seconds**, without fallback, after 15 terminal-attempt Newton iterations. Native acceptance and convergence evidence passed. The candidate's original three serial calls took 33.93–34.43 seconds.

Main retains 788 of 990 points (202 removed, zero closure-pruned); candidate retains 754 (236 removed, including 34 closure-pruned). The support change and faster linear work both remain in the comparison. This replay does not isolate their individual contributions. It does establish that this input was reachable by the original solver under a longer deadline. One replay is outcome evidence, not a new matched performance measurement, and the original 60-second benchmark remains unchanged.

No baseline case classified as `NONCONVERGENCE` became a candidate success. Paired screen results with available fields have the same terminal iteration count, maximum residual, and solve path, except the deadline-truncated baseline observation for `F02-wet-on`; its serial outcome is nonconvergence on both versions. These fields are terminal diagnostics, not complete Newton trajectories.

## The difficult operating regions did not improve

| Subset | Main accepted | Candidate accepted |
| --- | ---: | ---: |
| All 64 cases, within 60 seconds | 26/64 | 27/64 |
| 40% side-draw loading | 0/16 | 0/16 |
| 99/100/101 kPa boundary panel | 0/12 | 0/12 |
| 60 kPa cases | 2/16 | 2/16 |
| Steam enabled | 8/26 | 8/26 |
| Cutoff disabled | 13/32 | 13/32 |

The subsets overlap. They are descriptive results from designed operating points, not estimates of real-world failure rates. Input and structural validation do not establish a physically feasible steady state for every extreme point.

All 32 candidate screen `NONCONVERGENCE` outcomes report iteration-budget exhaustion. Thirty finish with maximum scaled residuals between **0.00958 and 0.4657**, versus the solver's `1e-8` gate. The other two (`F01-dry-off/on`) stop at approximately `1.87e-7` on an intermediate 90 kPa problem. Thus most recorded failures are far from the residual requirement; they are not simply otherwise-converged states rejected by the final-step certificate. Trace conditioning could still contribute to their stagnation, but the available records do not prove that cause.

## Why the current fixes have limited convergence reach

- **LU column scaling reduces work.** Its arithmetic and the nonlinear equations are preserved. Faster iterations are valuable, but a fixed 24/32-iteration attempt can arrive at the same failed state sooner.
- **Structural band storage prevents loss of essential small coefficients.** The manufactured small-coefficient regression demonstrates a real correctness fix. The matched crude results do not show that this defect was blocking the difficult operating regions in this matrix.
- **Feed reachability removes disconnected support points.** It acts only with truncation enabled and only where a disconnected group exists. Draw supply paths force retention of many points. Failed reduced attempts can retry the full problem, which still has the original nonlinear difficulties. This is narrower than a general treatment of negligible local flows.

The patch does not change the residual formulation, final log-flow step gate, continuation schedule, or per-attempt iteration budgets.

## The strongest actionable design issues

**1. The draw ramp can propagate failure instead of its last accepted state.** The existing ramp uses fixed fractions 0.25, 0.5, 0.75, and 1.0. On an intermediate failure it assigns that failed pass to `previous`, skips the other intermediate fractions, then attempts full loading. For `F06-dry`, the recorded failed quarter-ramp residual is about `1.71e-5`; the full request ends at `0.0134`. For `F08-dry`, it deteriorates from about `1.67e-4` to `0.0183`. These observations show the problematic route; they do not prove a smaller step will converge. See [ramp state handling](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3ColumnCalculator.java:730).

The first narrow experiment should preserve the last accepted seed after a failed rung and still make the existing final requested-input attempt. Evaluate F06/F08 dry, cutoff off/on. If continuation step size is then shown to be the obstacle, replace the fixed fractions inside this existing ramp with bounded subdivisions and one total work budget. Avoid wrapping it in another recovery strategy.

**2. Artificial intermediate problems block requested cases.** Dry P99/P100 stop at the mandatory 150 kPa anchor, before attempting the requested pressure. F01 dry stops during pressure reduction at 90 kPa. A failed anchor is not proof of target infeasibility. Instrument a few representative paths before changing ordering; compare a target-aware initialization or a simpler continuation path within one fixed work budget. Do not infer from the boundary panel alone that the `<=100 kPa` condition is the root cause: P101 also fails without that pressure route. See [pressure continuation](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3ColumnCalculator.java:389).

**3. Four outcomes per revision are masked by diagnostic construction.** F01 wet and F02 dry, each cutoff off/on, return `INVALID_INPUT` with the message `solvePath is blank or exceeds bounded contract`. The recorded `input` path is the exception handler's replacement, not the original numerical path. Generated paths are nonblank; composed pressure/phase/feature suffixes can exceed the 128-character diagnostic limit. Diagnostics are constructed before the result is classified, so the underlying solve could have failed or succeeded—we cannot count four hidden wins. Correcting my earlier report's wording, this is a diagnostic-path contract overflow/masking issue, not evidence of a blank solver path. Compact the path at the diagnostic boundary and retain detailed legs in events, then rerun only these four inputs. See [publication boundary](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3ColumnCalculator.java:255), [exception mapping](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3ColumnCalculator.java:303), and [path contract](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3SolverDiagnostics.java:28).

**4. Negligible-flow treatment is still unresolved.** Every retained flow must remain strictly positive in log coordinates, and every retained flow shares the `1e-8` maximum log-step gate. For example, a 1% change in a `1e-20 mol/s` local flow has an approximately 0.01 log step, despite changing only `1e-22 mol/s`. Reachability pruning does not remove a tiny point that is still connected to feed. A future formulation needs consistent support, absolute/relative physical error scales, conservation, and phase rules together. Simply deleting small Jacobian entries, flooring flows without conservation, or loosening all acceptance thresholds would not address this coherently. See [flow coordinates](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3DryMeshCoordinateMap.java:72) and [final-step gate](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3ConvergenceEvidence.java:16).

The draw withdrawal fraction can also exceed one during iterations, producing negative net downflow even though log-mapped component flows are positive. Some terminal cases violate this, but many failed states have withdrawal fractions below one. It warrants a feasibility-aware trial-step investigation, not a claim that it explains all high-loading failures or an arbitrary clamp on withdrawal.

## Recommended next work

Keep the measured performance fixes. First restore truthful diagnostics for the four masked inputs. Then isolate draw-ramp seed handling on the four F06/F08 dry cells, recording accepted/rejected steps, maximum residual, limiting flow/temperature coordinate, and audit outcome. Broader trace-formulation or continuation changes should follow those observations and replace the existing mechanism rather than add another layer. Further LU profiling is lower priority than this convergence work.

Preserve acceptance and conservation limits. The existing original-versus-regularized Newton-certificate defect also remains open; improving reported success by weakening that certificate is not a valid convergence result.

Evidence: [derived convergence summary](D:/Minecraft/Modding/1.21/CreateChemE/build/v3-convergence-analysis/convergence-summary.json), [longer-deadline probe journal](D:/Minecraft/Modding/1.21/CreateChemE/build/v3-convergence-analysis/baseline-n64-on-120s/samples.jsonl), [probe runner](D:/Minecraft/Modding/1.21/CreateChemE/build/v3-convergence-analysis/deadline_probe.py), and the [original benchmark report](D:/Minecraft/Modding/1.21/CreateChemE/documentation/2026-09-05-v3-solver-core-review/V3_COLD_CORE_BENCHMARK_RESULTS.md).
