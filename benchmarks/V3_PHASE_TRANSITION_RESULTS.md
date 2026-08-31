# Warm condenser transition and performance fixes — 2026-08-31

## Outcome

The 110 kPa / 2610.7 kmol/h / 8 MW timeout is fixed in the tested operating point, without relaxing acceptance or increasing the 45-second deadline. Three fresh-JVM cold samples each now succeed in 4.645–4.696 seconds with truncation off and 2.203–2.267 seconds with truncation on.

The additionally requested **50, 70, and 100 kPa cases are not qualified successes**. Both cutoff settings terminate with `PROPERTY_OUT_OF_RANGE`, reporting `V3 feed flash did not converge within 64 iterations`. This is a TP-flash algorithm failure, not proof that the requested operating point is physically infeasible. The production pressure-continuation route may fail at an intermediate point before reaching the requested pressure. No result was published for those failures.

All **232 regression tests passed**, with zero failures, errors, or skips. Build and benchmark-harness self-test passed. No approximation mode was introduced.

## Cold benchmark before implementation

The requested pre-change baseline was captured **before any solver implementation edits**: eleven serial, fresh-JVM first-solve invocations, no warmup, one case per JVM, unchanged 45-second budget. Four affected production file hashes were checked unchanged from before the first case through the final case. See [baseline evidence and hashes](results/v3-phase-cold-before/BASELINE.md).

The final after-run repeats the same eleven invocations and case order. JDK 21.0.11, 512 MiB initial / 2 GiB maximum heap; the Minecraft client was closed. Input construction and JVM/Gradle startup are outside solver timing. This is fresh-JVM cold solver latency, not game queue latency or OS-cache-cold startup. Checkpoint observation overhead is included consistently; profiling was disabled.

Fixed physical input unless noted: TJL/CDU17 registered package, 30 stages, feed stage 24, feed 638.15 K, condenser 323.15 K, 750 Pa/stage pressure drop, reflux ratio 2, duty 8 MW, feed 2610.7 kmol/h. Truncation on means a mole-fraction cutoff of `1e-6` (0.0001 mol%).

| Case | Samples per version | Before median / range (s) | After median / range (s) | Result |
| --- | ---: | --- | --- | --- |
| 150 kPa, off | 3 | 14.879 / 14.163–14.906 | 3.778 / 3.664–3.993 | Success → success |
| 110 kPa, off | 3 | All deadline at 45.001–45.291 | 4.672 / 4.645–4.696 | Deadline → strict success |
| 110 kPa, on | 3 | All deadline at 45.001 | 2.230 / 2.203–2.267 | Deadline → strict success |
| 100 kPa, off | 1 | 19.389 | 4.578 | TP-flash failure remains |
| 110 kPa, 2000 kmol/h, off | 1 | 25.667 | 8.283 | Success → success |

Timeout samples are censored: 45 seconds is not an estimate of their eventual convergence time. At 150 kPa, the median improved approximately 3.94× while input digests and complete serialized product streams remained exactly equal in all three before/after pairs. The 2000 kmol/h control also retains exactly equal digest and streams.

Representative raw reports: [110 off before](results/v3-phase-cold-before/110-off-1.json), [110 off after](results/v3-phase-cold-after/110-off-1.json), [110 on after](results/v3-phase-cold-after/110-on-1.json). All three repetitions are in the corresponding `before` and `after` directories.

## Requested lower-pressure tests

Each cell below is one first solve in its own fresh JVM, with the same physical inputs and budget as above. These runs used the same final production code as the after matrix; no production edits occurred between them.

| Requested top pressure | Cutoff off (s) | Cutoff on (s) | Both outcomes |
| --- | ---: | ---: | --- |
| 50 kPa | 4.737 | 34.284 | TP-flash nonconvergence |
| 70 kPa | 5.021 | 32.969 | TP-flash nonconvergence |
| 100 kPa | 4.723 | 35.598 | TP-flash nonconvergence |

Raw evidence: [50 off](results/v3-phase-low-pressure/50-off.json), [50 on](results/v3-phase-low-pressure/50-on.json), [70 off](results/v3-phase-low-pressure/70-off.json), [70 on](results/v3-phase-low-pressure/70-on.json), [100 off](results/v3-phase-low-pressure/100-off.json), [100 on](results/v3-phase-low-pressure/100-on.json).

The cutoff-on failures take longer because additional branch/recovery attempts occur before the untruncated terminal failure. Checkpoint breadcrumbs show work in the alternate branch's 150 kPa anchor solve. They do not, by themselves, identify which flash call fails or prove that changing initial branch selection would cure it. The same typed 100 kPa failure was captured before these changes. Robust TP-flash convergence and lower-pressure recovery ordering remain follow-up work; this patch does not waive those failures.

## What changed

- **Warm phase transition:** after a numerically converged candidate fails only its condenser-phase audit, flash the incoming overhead, construct a new branch and component-conserving condenser split, retain the interior profile, and re-solve the coupled column. There is one bounded warm correction per rung; changed reflux is solved, not patched onto published streams.
- **Branch-aware continuation and provenance:** stage/pressure continuation carries the selected branch forward. New topology, coordinates, and frozen truncation support are resolved for the new attempt. Final digest, diagnostics, and product extraction use the selected final problem. Unresolved phase mismatches cannot trigger duplicate cold restarts; successful corrections do not disable legitimate later numerical recovery. Selected coarse candidates receive the same phase handling.
- **Independent two-phase audit:** a fresh flash of combined condenser outlets must classify as two-phase and reproduce total vapor fraction and component phase-flow fractions within `1e-8`. This is a split-consistency check, not permission to ignore an appearing phase. Existing liquid-only, material, equilibrium, energy, truncation-defect, and final-step gates remain enforced.
- **Normal equations:** cache `JᵀJ` and `−Jᵀr` once per regularized attempt. Exactly stage-banded Jacobians use compact band-aware accumulation in the original summation order. Off-stage numerical noise retains the old dense compatibility calculation and off-band guard. No thresholds or damping formulas changed.
- **LU pivot growth:** replace the whole-matrix scan after every pivot with incremental maxima from the same elimination writes. Pivot choices, arithmetic, conditioning guards, and evidence remain unchanged.

Implementation: [calculator](../src/main/java/com/wormzjl/createcheme/science/column/v3/V3ColumnCalculator.java), [phase-transfer helper](../src/main/java/com/wormzjl/createcheme/science/column/v3/V3CondenserPhaseTransition.java), [audit](../src/main/java/com/wormzjl/createcheme/science/column/v3/V3AcceptanceAuditor.java), [normal products](../src/main/java/com/wormzjl/createcheme/science/column/v3/V3NormalEquations.java), [LU solver](../src/main/java/com/wormzjl/createcheme/science/column/v3/linalg/V3BandedPivotedSolver.java).

## Numerical result and approximation assessment

At 110 kPa with cutoff off, the final independently checked condenser vapor fraction is `2.0859906489061975e-4` (0.020860 mol%). This differs from the rejected liquid-only candidate's 0.063565 mol% flash prediction: re-solving the coupled column matters.

Final product flows (mol/s): vapor `0.24922163498826339`, liquid distillate `398.1635741326289`, bottoms `326.7816486768205`. The maximum scaled residual is `8.684828750085972e-11`; independent phase-split mismatch is `1.0417808651332072e-13` against its `1e-8` limit.

With cutoff on, 129 stage-component points are removed. The mass defect/feed is `2.1692430136293688e-6`, below the unchanged `8e-6` budget; all phase and final-step checks pass. Final vapor flow is `0.24911538417502974 mol/s`, about 0.0426% below the untruncated vapor flow.

There is no demonstrated need to ignore vapor to meet the 110 kPa deadline. Furthermore, vapor is only 0.03437% of total feed but carries about **2.62% of feed ethane** and 0.846% of feed propane in the untruncated solution. Small bulk vapor fraction does not guarantee comparably small light-component partitioning error. An approximation mode would need its own explicit accuracy contract and reference validation; it would not solve the lower-pressure TP-flash nonconvergence. It was therefore not added.

## Verification and fixture corrections

Command: `gradlew.bat test build v3TimeoutBenchmarkHarnessTest --offline --no-daemon --console=plain`. Result: 232 tests, zero failures/errors/skips; build and harness self-test successful.

Coverage includes bitwise dense-oracle comparison of normal products/gradients over all damping steps, off-band threshold/noise behavior, immutable inputs/results, cancellation identity, 250 deterministic LU differential cases, phase-transfer component conservation and branch provenance, accepted/rejected two-phase audits, and real 110 kPa off/on integration tests.

Strict phase verification exposed old tests that treated numerically closed PC03/PC10 two-phase roots at 300 K as physically accepted. Their original five numerical cases are retained, including zero-iteration exact hot reuse, but now both cold and hot phase audits must reject them. A separate physically valid liquid-only case retains positive accepted-state warm-start coverage. The direct binary test also verifies that an invalid candidate cannot be published.

At 400 K, phase-aware continuation changes the mask's deciding seed and the truncated attempt exceeds the unchanged mass-defect budget. Its regression now requires the explicit untruncated retry, strict final acceptance, full-feed product closure, and requested-cutoff provenance. The 323.15 K case still requires a genuinely truncated result, exact zeros, and a passing defect audit. These changes strengthen physical acceptance rather than relaxing tests to accept invalid states.

## Reproduce and artifact

Set `JAVA_HOME` to JDK 21 and put its `bin` on `PATH`. From this worktree, run [Run-V3ColdBenchmark.ps1](Run-V3ColdBenchmark.ps1) with a new label (existing snapshot directories are never overwritten):

```powershell
./benchmarks/Run-V3ColdBenchmark.ps1 -Label repeat
```

For a lower-pressure cell, launch one separate Gradle invocation per case (replace `50-off` with `50-on`, `70-off`, `70-on`, `100-off`, or `100-on`):

```powershell
./gradlew.bat v3TimeoutBenchmark --offline --no-daemon --console=plain '-Pv3Warmup=0' '-Pv3Samples=1' '-Pv3DeadlineSeconds=45' '-Pv3Cases=50-off' '-Pv3Report=build/reports/benchmarks/50-off-repeat.json'
```

Run serially with no client or other solver/build competing for CPU. A typed scientific failure is recorded in JSON even if the benchmark process itself exits successfully.

Built jar: `build/libs/createcheme-0.1.0.jar`, 615,528 bytes. SHA-256: `329c507f6b3cd73a938667e571746aee9dd42caafe7ca1f14e19611a4eb2b027`.

Implementation branch: `codex/stage-trace-truncation`. No production cutoff/default, rollout setting, or service deadline was changed by this patch.
