# Initial V3 core fixes

2026-09-05. Branch: **`codex/v3-solver-core-fixes`**, created from **`main` at `bdfeff1`**. Changes are local and uncommitted. This is the first implementation following the main-branch review.

The patch changes three production files and adds no recovery strategies or convergence tolerances:

- [V3TruncationSupport](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3TruncationSupport.java:312) now retains a component-stage point only when it is reachable from that component's feed through retained physical flow edges. Vapor moves upward, liquid downward, and condenser liquid feeds downward only with reflux. This removes disconnected circulation groups that previously required impossible positive-flow material balances. Forced draw supply paths, cutoff-off identity, phase-emptiness fallback, and the mass-defect audit remain.
- [Newton band conversion](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3SimultaneousColumnSolver.java:424) derives storage from the adjacent-stage structure and retains all coefficients inside that structure. It eliminates the dense defensive copy and repeated stage searches. Substantial nonadjacent coupling still fails the structural guard. This fixes direct Newton conversion; normal-equation recovery's separate bandwidth policy is not changed.
- [LU column scaling](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/linalg/V3BandedPivotedSolver.java:264) now computes maxima and scales columns with two passes over stored entries. It replaces two all-row scans for every column while preserving the original multiplication/division and subnormal handling.

Regression evidence:

- New disconnected-group tests fail in four cases against the original implementation and pass with source reachability. The focused support suite passes 17/17 checks, including all condenser branches, draw supply paths, and cutoff zero.
- A direct-matrix probe embeds `A=[[0,1e-12],[1,0]]` in an otherwise diagonal stage problem. Main drops the small coefficient and returns `SINGULAR`; the patch preserves it and solves with zero backward error. [Before](D:/Minecraft/Modding/1.21/CreateChemE/build/core-fixes-benchmark/matrix-before.txt), [after](D:/Minecraft/Modding/1.21/CreateChemE/build/core-fixes-benchmark/matrix-after.txt).
- Old/new LU results match byte-for-byte across 1,027 comparison cases, including 556 successful solves with identical solution bits and diagnostics. The regression includes independent extreme row/column scales, subnormal columns, and underflow-to-zero cases.
- Full **`.\gradlew.bat test --offline --console=plain` passes: 353 tests, zero failures, zero errors, zero skips**, in 2m 43s. An initial run exposed an overly ill-conditioned newly authored test fixture; that fixture was corrected and the entire suite rerun. Production sources were unchanged between those two suite runs.

Serial cold-column comparison against main:

| Case | Main median | Core-fixes median | Reduction |
| --- | ---: | ---: | ---: |
| 150 kPa, cutoff off | 7.405 s | 5.044 s | 31.9% |
| 100 kPa, cutoff off | 11.730 s | 7.646 s | 34.8% |
| 100 kPa, cutoff `1e-6` | 10.149 s | 6.654 s | 34.4% |

All 18 measured solves succeeded and passed the existing physical audits and convergence gates. The compared reported stream flows, temperatures, and mole fractions were identical. The cutoff-enabled case removed the same 32 component-stage points on both versions; the untruncated cases retained all points. These cases verify unchanged accepted outputs and reduced work; they do not establish new success on previously failing real-column cases.

Method: identical Tia Juana Light 30-tray fixture, feed tray 24, feed 2,610.7 kmol/h equivalent at 638.15 K, 400 K condenser, reflux ratio 2, reboiler duty 8 MW, 750 Pa/tray, and quarter-rate draws at trays 8/15/22. Each version ran in its own JVM with the same 512 MB initial / 2 GB maximum heap. One cold 150 kPa solve warmed classes, followed by three repetitions of each measured case with reversed case order in the middle repetition. Every solve started from its input without a retained solution. The main group ran first and the candidate group second, serially after the full suite had finished. The results are local measurements; grouped version order and the small fixture set limit generalization.

Raw [main results](D:/Minecraft/Modding/1.21/CreateChemE/build/core-fixes-benchmark/main.json), [candidate results](D:/Minecraft/Modding/1.21/CreateChemE/build/core-fixes-benchmark/candidate.json), [comparison](D:/Minecraft/Modding/1.21/CreateChemE/build/core-fixes-benchmark/comparison.json), [harness](D:/Minecraft/Modding/1.21/CreateChemE/build/solver-audit/CoreFixesBenchmark.java), and [source hashes](D:/Minecraft/Modding/1.21/CreateChemE/build/core-fixes-benchmark/source-hashes.json) are retained as ignored local build artifacts.

An isolated identity-matrix LU benchmark also improved from 7.372 to 0.873 ms at dimension 1,000 and from 341.334 to 2.453 ms at dimension 8,000. These larger ratios apply to that linear-scaling microbenchmark, not whole-column solves. [Method and timings](D:/Minecraft/Modding/1.21/CreateChemE/build/core-fixes-benchmark/linear-scaling.txt).

Still pending from the design review: a coherent formulation for physically negligible local flows, the original-versus-regularized final certificate contract, normal-product bandwidth/noise policy, aggregate work diagnostics, and broader simplification of the existing main continuation paths. This patch deliberately does not claim to solve those remaining convergence limitations.
