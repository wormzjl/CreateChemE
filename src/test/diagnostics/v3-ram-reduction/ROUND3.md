# Compact Jacobian storage and reusable linear workspaces

Branch: `codex/solver-ram-reduction`. This pass implements the first two larger
memory reductions requested after round two. The comparator is the branch as it
stood immediately before this pass, including both earlier allocation reductions.

## Results

This pass removes a further **23.6–33.7% of cumulative allocation** across the
default and its 24 larger perturbations. Default allocation falls from about
**1682 MiB to 1158 MiB per solve** (1.64 GiB to 1.13 GiB). The heaviest case,
pressure -5%, falls from **7821 MiB to 5973 MiB**.

All **500 measured timing outcomes** match exactly: 440 successes and 60 unchanged
nonconvergence outcomes for the three temperature cases. All **502 regression
tests pass**, with zero failures, errors or skips.

Runtime is generally improved. In pair one, 23 of 25 case medians improve; reflux
+5% and reflux -10% are 0.94% and 0.81% slower respectively. In pair two every
case median improves, including those two cases. Gains across the improving
comparisons range up to 10.9%. Default medians are 1158.2 → 1136.7 ms in pair one
and 1154.4 → 1148.8 ms in pair two. The small first-pair slowdowns did not repeat;
these measurements do not promise a speedup for every individual invocation.

The complete second pair is below. Arrows mean pre-pass baseline to candidate.
Both pairs' per-case timing, CPU time and allocation medians are retained in
`round3-comparison.json`.

| Case | Allocation MiB/solve | Less allocation | Runtime ms/solve |
|---|---:|---:|---:|
| Default | 1682.0 → 1157.7 | 31.2% | 1154.4 → 1148.8 |
| Feed -5% | 2491.4 → 1840.5 | 26.1% | 1654.8 → 1588.2 |
| Feed +5% | 1338.8 → 903.3 | 32.5% | 968.3 → 930.4 |
| Feed -10% | 2164.4 → 1535.5 | 29.1% | 1464.3 → 1426.8 |
| Feed +10% | 5499.0 → 3969.5 | 27.8% | 3924.7 → 3790.8 |
| Temperature -5% (nonconvergent) | 3640.6 → 2509.7 | 31.1% | 2473.0 → 2444.0 |
| Temperature +5% (nonconvergent) | 3017.8 → 2001.5 | 33.7% | 2180.7 → 2053.4 |
| Temperature -10% (nonconvergent) | 2797.1 → 1873.4 | 33.0% | 1871.1 → 1825.0 |
| Temperature +10% | 3712.9 → 2502.6 | 32.6% | 2625.8 → 2532.3 |
| Pressure -5% | 7821.0 → 5973.3 | 23.6% | 5588.7 → 5339.9 |
| Pressure +5% | 1727.8 → 1187.1 | 31.3% | 1181.1 → 1141.8 |
| Pressure -10% | 1638.8 → 1111.7 | 32.2% | 1123.7 → 1090.6 |
| Pressure +10% | 1784.8 → 1223.5 | 31.4% | 1206.7 → 1169.7 |
| Reflux -5% | 1636.1 → 1100.5 | 32.7% | 1135.5 → 1098.8 |
| Reflux +5% | 5326.3 → 3755.3 | 29.5% | 3828.1 → 3707.0 |
| Reflux -10% | 1876.5 → 1296.6 | 30.9% | 1333.0 → 1290.4 |
| Reflux +10% | 5948.8 → 4140.3 | 30.4% | 3981.7 → 3848.0 |
| Steam -5% | 1722.6 → 1184.9 | 31.2% | 1165.7 → 1134.8 |
| Steam +5% | 5177.7 → 3720.0 | 28.2% | 3693.1 → 3570.5 |
| Steam -10% | 1687.4 → 1152.8 | 31.7% | 1143.1 → 1114.2 |
| Steam +10% | 2027.8 → 1435.2 | 29.2% | 1378.7 → 1334.4 |
| Cooling -5% | 1693.6 → 1163.5 | 31.3% | 1147.5 → 1117.3 |
| Cooling +5% | 1771.9 → 1225.1 | 30.9% | 1195.0 → 1169.4 |
| Cooling -10% | 1351.9 → 912.8 | 32.5% | 956.3 → 935.2 |
| Cooling +10% | 1774.0 → 1225.7 | 30.9% | 1207.5 → 1168.2 |

### Constrained heaps and cold starts

Both the default and the heaviest case (pressure -5%) were tested in separate
G1 JVMs with native-memory tracking and periodic Windows process-memory sampling.
Warm runs use three warmups and three measurements. Cold runs use zero warmups
and one measured solve per JVM, with two baseline/candidate pairs per input.
A longer default repeat uses three warmups and ten measurements.

| Heap cap | Baseline, both inputs | Candidate, both inputs |
|---|---|---|
| 32 MiB | Out of memory | Out of memory |
| 40 MiB | Out of memory | All three measured solves succeed |
| 48 MiB | Out of memory | All three measured solves succeed |
| 64 MiB | All three measured solves succeed | All three measured solves succeed |
| 512 MiB | All three measured solves succeed | All three measured solves succeed |

The smallest **tested** passing cap falls from **64 to 40 MiB**. This brackets
capacity for this isolated probe; it does not identify the exact minimum. At
40 MiB, the candidate's average measured runtime is 1.70 s for the default and
7.73 s for the pressure case, reflecting the cost of frequent collection at a
tight cap. Performance comparisons above use the same heap settings for both
variants. No solver convergence rule is relaxed at smaller heaps.

Selected memory measurements, in MiB:

| Run, 512 MiB cap | Sampled heap peak | Lifetime resident-process peak |
|---|---:|---:|
| Default, warm three-sample run | 157.0 → 127.1 | 315.8 → 264.9 |
| Pressure -5%, warm three-sample run | 406.0 → 259.6 | 615.9 → 379.8 |
| Default, cold pair 1 | 119.9 → 138.4 | 282.5 → 307.9 |
| Default, cold pair 2 | 185.9 → 96.4 | 346.5 → 239.7 |
| Pressure -5%, cold pair 1 | 269.1 → 162.9 | 398.6 → 298.3 |
| Pressure -5%, cold pair 2 | 254.0 → 179.9 | 382.9 → 308.9 |
| Default, longer ten-sample run | 486.3 → 199.2 | 611.3 → 308.7 |

**Resident peaks are still variable**, as the first cold-default pair shows; it
would be incorrect to claim a uniform reduction for every process. The pressure
case improves in both cold repeats and the warm run. Heap after full collection
remains approximately 18–19 MiB across the completed runs.

The first short instrumented default run was slower (1272 → 1398 ms mean),
despite fewer collections. A longer repeat was faster (1202 → 1165 ms mean),
with last-five medians of 1167 → 1151 ms. Both cold-default pairs were also faster.
Keep the anomalous short run and the startup variability in view; the unprofiled
four-JVM timing comparison is the primary performance measurement.

All **64 successful memory outcomes** match the timing baseline's streams,
diagnostics and convergence certificates, with passed audits. Eight out-of-memory
results are recorded separately; there were no deadlines. Combined with the timing
runs, **564 numerical outcomes match exactly** (504 successes and 60 unchanged
nonconvergence outcomes). The raw memory comparison records every cap and repeat.

## Implementation and ownership

Production finite differences now store each row's adjacent-stage column window
instead of an `N × N` array. Derivative calculations, ordering, frozen scales and
finite-difference step sizes are unchanged. An unexpected off-window value causes
that row to expand: even subthreshold noise and negative zero survive. The existing
Newton band guard and dense normal-product fallback still see these values.

The dense finite-difference entry point remains available for verification.
Immutable Jacobians retain their own storage, including when Newton reuses a frozen
Jacobian. Scalar reads expose no arrays; `values()` still returns an independent
dense snapshot. Structural checks skip only entries known to be positive zero.
Gradient accumulation also skips those implicit zeros for finite scaled residuals,
retaining the order of every nonzero contribution. Non-finite scaled residuals
keep the full loop so that zero times infinity still produces NaN. Normal-product
summation order is unchanged.

Each Newton solve owns one linear workspace. It reuses an assembly matrix and LU
factorization buffers only when size and both bandwidths match exactly. Otherwise
it replaces them, retaining only the current shape. Reset clears the assembly band,
LU fill slots and column divisors; column scales are overwritten before use.

Local, finite-difference, final-correction and damped-normal solves use that
workspace. The undamped normal products, right-hand-side inputs and returned
solutions remain independent. Nothing is pooled globally or held in a thread-local;
the workspace becomes unreachable when the Newton solve returns or unwinds.
Concurrent calculator calls construct separate workspaces.

The ordinary `V3BandedPivotedSolver.solve(matrix, rhs)` API still creates isolated
scratch storage. Its workspace overload requires serial, non-reentrant ownership.
No pivot rule, conditioning threshold, convergence tolerance or accepted physical
domain has changed. Primitive residual and thermodynamic-buffer redesigns are
outside this pass.

## Wider perturbations

The production default is tested along with **24 one-factor perturbations**:
each of six factors at -5%, +5%, -10% and +10%.

| Factor | What changes |
|---|---|
| Feed rate | Every hydrocarbon component rate, preserving feed composition |
| Feed temperature | Absolute temperature in Kelvin |
| Pressure | Top pressure; tray pressure drop stays unchanged |
| Reflux | Organic reflux ratio |
| Steam | Steam rate; steam temperature and location stay unchanged |
| Cooling | All pumparound duties; locations and splits stay unchanged |

The default feed is 638.15 K / 365 C. Temperature perturbations correspond to
333.09 C, 396.91 C, 301.19 C and 428.82 C respectively. The preliminary baseline
returns nonconvergence for -5%, +5% and -10% temperature; these are retained as
failure-equivalence tests. Nonconvergence is not treated as proof of physical
infeasibility, and no convergence criteria are relaxed to make these cases pass.

## Validation design

Timing uses four fresh JVMs in baseline/candidate/baseline/candidate order, serially.
Each case gets three warmups and five measurements, at `-Xms512m -Xmx2g`, with no
JFR or native-memory tracking. All calls retain the default 45-second deadline,
trace truncation off and `1e-8` closure tolerance. These are standalone calls to
the production calculator, not an instrumented Minecraft world.

The five affected baseline classes were copied before editing and placed first
on the baseline classpath. `prepare-round3-baseline.ps1` reconstructs and checks
them using commit `8547feaeffbda5daf2ee9569bbd37d566d46e447` and the saved patch of
earlier ownership changes. The other earlier optimizations are shared by both
variants. The generated manifest records hashes for all five frozen sources.

`compare-round3.cjs` checks input, result kind, every stream, duty, diagnostic,
audit and convergence certificate exactly. Failure details must also match.
Successful solves must pass their independent audits and final correction gates.
The initial one-call scouting comparisons precede the final structural/gradient
scan optimizations; they are recorded separately from the final repeated benchmark.
An interrupted intermediate candidate run is retained as `round3-candidate-provisional`
and excluded from the final comparison. It exposed a small runtime regression that
prompted removal of unnecessary scans of implicit zeros before restarting the candidate.

Regression coverage includes dense-versus-compact bit comparisons for central
and colored finite differences, both step sizes and all condenser branches;
truncated support; injected off-stage noise and signed zero; and existing wet-tray
tests. Reused LU buffers are checked against the independent TreeMap reference,
including hundreds of random bands, wide bands, pivoting, subnormal scaling,
typed failures and failure recovery. Workspace tests check clearing, shape changes
and independence of previously returned solutions.

## Reproduce

```powershell
.\src\test\diagnostics\v3-ram-reduction\prepare-round3-baseline.ps1
.\gradlew.bat test
.\gradlew.bat -I src/test/diagnostics/v3-ram-reduction/round3.init.gradle v3RamRound3 '-PramVariant=baseline' '-PramReport=build/reports/v3-ram-reduction/round3-baseline-1.json' --no-configuration-cache
.\gradlew.bat -I src/test/diagnostics/v3-ram-reduction/round3.init.gradle v3RamRound3 '-PramVariant=candidate' '-PramReport=build/reports/v3-ram-reduction/round3-candidate-1.json' --no-configuration-cache
```

Repeat both variants with suffix `-2`, then run
`node src/test/diagnostics/v3-ram-reduction/compare-round3.cjs`.

For the Windows memory measurements, run
`.\src\test\diagnostics\v3-ram-reduction\run-round3-memory.ps1`, then
`node src/test/diagnostics/v3-ram-reduction/compare-round3-memory.cjs`. The script
covers all caps, cold pairs and the longer default repeat. The actual run added
40/48 MiB and the longer repeat after the initial sweep; the script now includes
them for reproduction. Memory-test timings include the sampling instrumentation.

Raw data and generated comparisons are under `build/reports/v3-ram-reduction/round3-*`;
logs are `build/ram-round3-*.log`. The baseline and candidate labels in these files
refer specifically to this pass.
