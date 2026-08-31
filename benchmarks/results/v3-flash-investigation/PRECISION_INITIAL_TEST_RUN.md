# Precision-only initial regression run

Command: `gradlew.bat test build v3TimeoutBenchmarkHarnessTest --offline --no-daemon --console=plain`, JDK 21.0.11.

Initial eager-refinement variant: stable Cardano for well-separated one-real-root cases; residual-decreasing refinement of every physical one-/three-root value. Existing coalescence classification and all solver/audit tolerances unchanged.

Outcome: **238 tests, one failure**. All six new precision tests passed, including the exact captured 137250 Pa flash and independent 90-digit cubic-root checks. The existing `V3ExactWarmStartSweepTest.physicallyAcceptedLiquidOnlyStatePassesFreshExactInputHotStart` failed its cold-convergence assertion before attempting hot reuse.

Input: binary PC03/PC10, 100 mol/s, 550 K feed, two stages, feed stage 1, top pressure 250000 Pa, pressure drop 750 Pa, condenser 300 K, reflux ratio 2, reboiler duty zero.

Failure: `MAX_ITERATIONS`, 128 iterations; maximum scaled residual `1.1397327139659222e-5`; scaled merit `3.982136365094524e-10`; no final Newton convergence certificate. The helper tries a sequential seed, bubble-point recovery, and coarse finite-difference recovery; it reports its original attempt when those recoveries are not accepted.

This was not accepted as a passing regression, and no flash-truncation code or benchmark matrix had started. Follow-up qualifies a narrower precision repair while retaining the original test inputs, strict convergence criteria, and independent physical audits.

## Qualified follow-up

The final precision variant first checks the original analytic root's backward residual against eight ulps of the sum of absolute polynomial terms. Already-accurate values retain their exact original bits. Inaccurate roots receive stable Cardano (one-root path) and residual-decreasing, branch-bounded refinement. All seven final root tests, including a new bit-preservation control, pass.

The gated variant restored the accepted liquid-only cold/hot case. A separate direct test of a deliberately invalid two-phase binary branch still needed recovery, while the companion exact-warm test already converged that same input using existing bubble-point/coarse-FD recovery. The direct phase-gate fixture now uses those same recovery paths, retaining the original input, per-attempt iteration budget, final Newton certificate, fresh non-phase checks, strict phase rejection, and zero-iteration hot-reuse assertions. No physically rejected result was reclassified as accepted.

A counterfactual that disabled all three-root refinement worsened the negative-fixture convergence and failed the independent three-root accuracy oracle; it was not retained.

Final precision-only verification: **239 tests passed, zero failures**; `build` and the timeout-benchmark harness self-test passed. Only after this result did the frozen-source `v3-flash-cold-precision-only` matrix start. Flash-truncation implementation remained untouched throughout that matrix.
